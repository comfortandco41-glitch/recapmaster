import json
import urllib.request
import urllib.parse
from pathlib import Path
from typing import Dict, Any, List

from worker.config import WORKSPACE_DIR
from worker.db import update_job_status, record_asset
from worker.pipeline.whisper import seconds_to_srt_time

import time

_TRANSLATION_CACHE = {}

def translate_text(text: str, target_lang: str = "my", max_retries: int = 3) -> str:
    if not text or not text.strip():
        return ""
    cache_key = f"{target_lang}:{text.strip()}"
    if cache_key in _TRANSLATION_CACHE:
        return _TRANSLATION_CACHE[cache_key]

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
            except Exception as e:
                continue
        if attempt < max_retries - 1:
            time.sleep(1.0)

    return text

def translate_transcript_to_burmese(
    job_id: str,
    transcript_data: Dict[str, Any],
    target_lang: str = "my",
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

    update_job_status(
        job_id,
        status="TRANSCRIBED",
        progress=50,
        stage="TRANSLATION",
        event_message="Translating real video subtitles and recap script to Burmese",
    )

    segments = transcript_data.get("segments", [])
    translated_segments: List[Dict[str, Any]] = []

    # Batch translate segments in moderate chunks with polite throttling to avoid HTTP 429
    chunk_size = 12
    for i in range(0, len(segments), chunk_size):
        chunk = segments[i:i + chunk_size]
        combined_text = "\n".join(s["text"] for s in chunk)
        translated_chunk = translate_text(combined_text, target_lang=target_lang)
        translated_lines = [l.strip() for l in translated_chunk.split("\n") if l.strip()]

        for j, seg in enumerate(chunk):
            burmese_text = translated_lines[j] if j < len(translated_lines) else translate_text(seg["text"], target_lang=target_lang)
            translated_segments.append({
                "start": seg["start"],
                "end": seg["end"],
                "text": burmese_text or seg["text"],
                "original_text": seg["text"],
            })

        # Respectful delay between translation batches to prevent rate limiting
        if i + chunk_size < len(segments):
            time.sleep(0.35)

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
