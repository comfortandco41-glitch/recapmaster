import re
from pathlib import Path
from typing import Dict, Any, Optional

from worker.config import WORKSPACE_DIR, GEMINI_API_KEY
from worker.db import update_job_status, record_asset
from worker.pipeline.translate import translate_text, _call_gemini_api

FILLER_WORDS = re.compile(r"\b(uh|um|er|ah|like|you know|sort of|kind of|这|那|就是|那个)\b", re.IGNORECASE)

def clean_text(text: str) -> str:
    cleaned = FILLER_WORDS.sub("", text)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned

def generate_recap_script(
    job_id: str,
    transcript_data: Dict[str, Any],
    language: str = "my",
    api_key: Optional[str] = None,
) -> Path:
    job_dir = WORKSPACE_DIR / job_id
    transcript_dir = job_dir / "transcript"
    transcript_dir.mkdir(parents=True, exist_ok=True)
    script_path = transcript_dir / "recap_script.txt"

    active_key = api_key or GEMINI_API_KEY
    engine_label = "Gemini AI" if active_key else "Enhanced Recapper"

    update_job_status(
        job_id,
        status="TRANSCRIBED",
        progress=58,
        stage="RECAP_GENERATION",
        event_message=f"Synthesizing cinematic movie recap narration via {engine_label} ({'Burmese' if language == 'my' else language})",
    )

    segments = transcript_data.get("segments", [])
    raw_lines = [clean_text(s["text"]) for s in segments if s.get("text") and len(s["text"].strip()) > 1]

    # Select representative dialogue and scene lines distributed across the video
    if len(raw_lines) > 20:
        step = max(1, len(raw_lines) // 12)
        selected_lines = [raw_lines[i] for i in range(0, len(raw_lines), step)][:12]
    else:
        selected_lines = raw_lines

    dialogue_summary = " ".join(selected_lines).strip()
    if not dialogue_summary:
        dialogue_summary = "အဓိကဇာတ်ကောင်များသည် မမျှော်လင့်ထားသောအဖြစ်အပျက်များကို ရင်ဆိုင်နေရပြီး ဇာတ်လမ်းသည် အလှည့်အပြောင်းများစွာဖြင့် ဆက်လက်ဖြစ်ပေါ်နေပါသည်။" if language == "my" else "The main characters face unexpected conflicts as the tension builds throughout the scenes."

    final_script = ""

    # If Gemini API key is available and language is Burmese, generate natural recap narration directly
    if active_key and language in ("my", "burmese"):
        gemini_prompt = (
            "You are an expert movie recap creator and voiceover scriptwriter. "
            "Write a concise, dramatic, and captivating cinematic movie recap narration in fluent Burmese (Myanmar language) "
            "based on the following key dialogue and story scenes from the video.\n\n"
            "CRITICAL RULES:\n"
            "1. Do NOT include ANY introductory greetings or phrases (absolutely no 'Welcome', 'In this video', 'မင်္ဂလာပါ', 'ဒီဗီဒီယိုမှာတော့', 'ယနေ့တော့', or similar intro text).\n"
            "2. Start directly and immediately with the plot action and characters.\n"
            "3. Keep the narration natural, immersive, and fast-paced, suitable for a recap video.\n"
            "4. Output ONLY the Burmese narration script text without any explanations, titles, or timestamps.\n\n"
            f"Dialogue excerpts:\n{dialogue_summary}"
        )
        gemini_narration = _call_gemini_api(gemini_prompt, active_key)
        if gemini_narration and len(gemini_narration.strip()) > 15:
            final_script = gemini_narration.strip()

    if not final_script:
        if language in ("my", "burmese"):
            body_my = translate_text(dialogue_summary, target_lang="my", api_key=active_key) if transcript_data.get("language") != "my" else dialogue_summary
            final_script = body_my.strip()
        else:
            final_script = dialogue_summary.strip()

    with open(script_path, "w", encoding="utf-8") as f:
        f.write(final_script)

    record_asset(
        job_id=job_id,
        asset_type="RECAP_SCRIPT",
        storage_key=f"jobs/{job_id}/transcript/recap_script.txt",
        mime_type="text/plain",
        size_bytes=script_path.stat().st_size,
    )

    update_job_status(
        job_id,
        status="TRANSCRIBED",
        progress=60,
        stage="RECAP_GENERATION",
        event_message=f"Cinematic recap script generated ({len(final_script.split())} words)",
    )

    return script_path
