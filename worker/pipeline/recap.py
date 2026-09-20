import re
from pathlib import Path
from typing import Dict, Any

from worker.config import WORKSPACE_DIR
from worker.db import update_job_status, record_asset
from worker.pipeline.translate import translate_text

FILLER_WORDS = re.compile(r"\b(uh|um|er|ah|like|you know|sort of|kind of|这|那|就是|那个)\b", re.IGNORECASE)

def clean_text(text: str) -> str:
    cleaned = FILLER_WORDS.sub("", text)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned

def generate_recap_script(
    job_id: str,
    transcript_data: Dict[str, Any],
    language: str = "my",
) -> Path:
    job_dir = WORKSPACE_DIR / job_id
    transcript_dir = job_dir / "transcript"
    transcript_dir.mkdir(parents=True, exist_ok=True)
    script_path = transcript_dir / "recap_script.txt"

    update_job_status(
        job_id,
        status="TRANSCRIBED",
        progress=58,
        stage="RECAP_GENERATION",
        event_message=f"Synthesizing cinematic movie recap narration ({'Burmese' if language == 'my' else language})",
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

    if language in ("my", "burmese"):
        # Craft a cinematic Burmese movie recap narrative
        intro_my = "ဤဇာတ်ကားတွင် စိတ်လှုပ်ရှားဖွယ် ဇာတ်လမ်းစတင်လာပါသည်။"
        body_my = translate_text(dialogue_summary, target_lang="my") if transcript_data.get("language") != "my" else dialogue_summary
        outro_my = "နောက်ဆုံးတွင် ဇာတ်လမ်းသည် အထွတ်အထိပ်သို့ ရောက်ရှိသွားခဲ့ပြီး ဤအပိုင်းကို အဆုံးသတ်ခဲ့ပါသည်။"
        final_script = f"{intro_my} {body_my} {outro_my}".strip()
    else:
        intro_en = "In this recap, we look into the gripping story and key events of this movie."
        body_en = dialogue_summary
        outro_en = "The confrontation reaches its peak as the story unfolds."
        final_script = f"{intro_en} {body_en} {outro_en}".strip()

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
