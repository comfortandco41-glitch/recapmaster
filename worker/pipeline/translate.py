import json
import time
import urllib.request
import urllib.parse
from pathlib import Path
from typing import Dict, Any, List, Optional
import requests

from worker.config import WORKSPACE_DIR, GEMINI_API_KEY, GEMINI_MODEL
from worker.db import update_job_status, record_asset
from worker.pipeline.whisper import seconds_to_srt_time

_TRANSLATION_CACHE = {}

# Latest models in priority order (probed against the live API — newest first)
LATEST_GEMINI_MODELS = [
    "gemini-3.8-flash",     # Latest Gemini 3 flagship Flash (fastest, newest)
    "gemini-3.7-flash",     # Gemini 3.7 Flash
    "gemini-3.5-flash",     # Gemini 3.5 Flash
    "gemini-2.5-flash",     # Gemini 2.5 Flash (GA, widely available)
    "gemini-2.5-pro",       # Gemini 2.5 Pro
    "gemini-2.0-flash",     # Gemini 2.0 Flash fallback
    "gemini-1.5-flash",     # Final stable fallback
]

def _call_gemini_api(
    prompt: str,
    api_key: str,
    response_mime: Optional[str] = None,
    preferred_model: Optional[str] = None,
    timeout: int = 25,
) -> Optional[str]:
    """Execute Gemini REST request using the latest available Flash/Pro model with fallback."""
    headers = {"Content-Type": "application/json"}
    payload: Dict[str, Any] = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "temperature": 0.2,
        },
    }
    if response_mime:
        payload["generationConfig"]["responseMimeType"] = response_mime

    candidate_models: List[str] = []
    if preferred_model and preferred_model.strip():
        candidate_models.append(preferred_model.strip())
    if GEMINI_MODEL and GEMINI_MODEL not in candidate_models:
        candidate_models.append(GEMINI_MODEL)
    for m in LATEST_GEMINI_MODELS:
        if m not in candidate_models:
            candidate_models.append(m)

    for model in candidate_models:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
        try:
            resp = requests.post(url, headers=headers, json=payload, timeout=timeout)
            if resp.status_code == 200:
                data = resp.json()
                candidates = data.get("candidates", [])
                if candidates:
                    parts = candidates[0].get("content", {}).get("parts", [])
                    if parts and "text" in parts[0]:
                        return parts[0]["text"].strip()
            elif resp.status_code in (400, 403):
                err_text = resp.text.lower()
                # If API key itself is invalid, stop early
                if "api key not valid" in err_text or "api_key_invalid" in err_text:
                    print(f"[Gemini Translation] Key error HTTP {resp.status_code}: {resp.text[:140]}")
                    return None
                # If model is not supported for this key/tier, continue to next model
                continue
            elif resp.status_code == 404:
                continue
            else:
                continue
        except Exception:
            continue
    return None

def translate_text(
    text: str,
    target_lang: str = "my",
    max_retries: int = 3,
    api_key: Optional[str] = None,
    model: Optional[str] = None,
) -> str:
    if not text or not text.strip():
        return ""

    cache_key = f"{target_lang}:{text.strip()}"
    if cache_key in _TRANSLATION_CACHE:
        return _TRANSLATION_CACHE[cache_key]

    active_key = api_key or GEMINI_API_KEY
    if active_key:
        prompt = (
            "You are a professional film and media subtitle translator. "
            "Translate the following text into natural, fluent, and expressive Burmese (Myanmar language). "
            "Preserve meaning, conversational tone, and punctuation. "
            "Return ONLY the translated Burmese text without any intro, explanation, or notes.\n\n"
            f"Text to translate:\n{text}"
        )
        gemini_result = _call_gemini_api(prompt, active_key, preferred_model=model)
        if gemini_result:
            _TRANSLATION_CACHE[cache_key] = gemini_result
            return gemini_result

    # Fallback to web translation if Gemini key is missing or unavailable
    for attempt in range(max_retries):
        for client in ["dict-chrome-ex", "gtx", "it"]:
            try:
                url = f"https://translate.googleapis.com/translate_a/single?client={client}&sl=auto&tl={target_lang}&dt=t&q=" + urllib.parse.quote(text)
                req = urllib.request.Request(
                    url,
                    headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"}
                )
                with urllib.request.urlopen(req, timeout=12) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                    result = "".join(part[0] for part in data[0] if part and len(part) > 0 and part[0])
                    if result:
                        _TRANSLATION_CACHE[cache_key] = result
                        return result
            except Exception:
                continue
        if attempt < max_retries - 1:
            time.sleep(1.0)

    return text

def translate_transcript_to_burmese(
    job_id: str,
    transcript_data: Dict[str, Any],
    target_lang: str = "my",
    api_key: Optional[str] = None,
    model: Optional[str] = None,
) -> Dict[str, Any]:
    job_dir = WORKSPACE_DIR / job_id
    transcript_dir = job_dir / "transcript"
    transcript_dir.mkdir(parents=True, exist_ok=True)

    burmese_json_path = transcript_dir / "transcript_burmese.json"
    burmese_srt_path = transcript_dir / "transcript_burmese.srt"

    # Checkpoint
    if burmese_json_path.exists() and burmese_srt_path.exists():
        with open(burmese_json_path, "r", encoding="utf-8") as f:
            return json.load(f)

    active_key = api_key or GEMINI_API_KEY
    engine_label = "Gemini AI (Latest Model)" if active_key else "Web Translator"
    print(f"[*] Translating subtitle transcript to Burmese via {engine_label}...")

    update_job_status(
        job_id,
        status="TRANSCRIBED",
        progress=50,
        stage="TRANSLATION",
        event_message=f"Translating video subtitles and recap script to Burmese via {engine_label}",
    )

    segments = transcript_data.get("segments", [])
    translated_segments: List[Dict[str, Any]] = []

    # Batch translate segments
    chunk_size = 20 if active_key else 12
    for i in range(0, len(segments), chunk_size):
        chunk = segments[i:i + chunk_size]
        batch_translated = False

        # Attempt Gemini batch translation if key is available
        if active_key:
            chunk_texts = [s.get("text", "").strip() for s in chunk]
            prompt = (
                "You are an expert movie subtitle translator. "
                "Translate the following array of dialogue subtitle lines into natural, fluent, and expressive Burmese (Myanmar language).\n"
                "Keep conversational flow between characters natural and cinematic.\n"
                "Return a strictly valid JSON array of strings containing the translations in the exact same order.\n\n"
                f"Lines to translate:\n{json.dumps(chunk_texts, ensure_ascii=False)}"
            )
            gemini_raw = _call_gemini_api(prompt, active_key, response_mime="application/json", preferred_model=model)
            if gemini_raw:
                try:
                    lines = json.loads(gemini_raw)
                    if isinstance(lines, list) and len(lines) == len(chunk):
                        for j, seg in enumerate(chunk):
                            translated_segments.append({
                                "start": seg["start"],
                                "end": seg["end"],
                                "text": str(lines[j]).strip() or seg["text"],
                                "original_text": seg["text"],
                            })
                        batch_translated = True
                except Exception:
                    pass

        if not batch_translated:
            combined_text = "\n".join(s["text"] for s in chunk)
            translated_chunk = translate_text(combined_text, target_lang=target_lang, api_key=active_key)
            translated_lines = [l.strip() for l in translated_chunk.split("\n") if l.strip()]

            for j, seg in enumerate(chunk):
                burmese_text = translated_lines[j] if j < len(translated_lines) else translate_text(seg["text"], target_lang=target_lang, api_key=active_key)
                translated_segments.append({
                    "start": seg["start"],
                    "end": seg["end"],
                    "text": burmese_text or seg["text"],
                    "original_text": seg["text"],
                })

        # Respectful delay between translation batches to prevent rate limiting
        if i + chunk_size < len(segments):
            time.sleep(0.25)

    result_data = {
        "language": target_lang,
        "source_language": transcript_data.get("language", "auto"),
        "segments": translated_segments,
    }

    # Save Burmese transcript.json
    with open(burmese_json_path, "w", encoding="utf-8") as f:
        json.dump(result_data, f, indent=2, ensure_ascii=False)

    # Save Burmese transcript.srt
    srt_lines = []
    for idx, seg in enumerate(translated_segments, start=1):
        s_time = seconds_to_srt_time(seg["start"])
        e_time = seconds_to_srt_time(seg["end"])
        srt_lines.append(f"{idx}\n{s_time} --> {e_time}\n{seg['text']}\n")

    with open(burmese_srt_path, "w", encoding="utf-8") as f:
        f.write("\n".join(srt_lines))

    # Also overwrite/update transcript.srt if Burmese is the active language
    main_srt = transcript_dir / "transcript.srt"
    with open(main_srt, "w", encoding="utf-8") as f:
        f.write("\n".join(srt_lines))

    record_asset(
        job_id=job_id,
        asset_type="SRT",
        storage_key=f"jobs/{job_id}/transcript/transcript_burmese.srt",
        mime_type="text/plain",
        size_bytes=burmese_srt_path.stat().st_size,
    )

    update_job_status(
        job_id,
        status="TRANSCRIBED",
        progress=55,
        stage="TRANSLATION",
        event_message=f"Translated {len(translated_segments)} subtitle segments to Burmese",
    )

    return result_data
