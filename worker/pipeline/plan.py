import json
from pathlib import Path
from typing import Dict, Any, List

from worker.config import WORKSPACE_DIR
from worker.pipeline.download import get_media_info

def build_render_plan(
    job_id: str,
    source_video: Path,
    voice_audio: Path,
) -> Path:
    job_dir = WORKSPACE_DIR / job_id
    render_dir = job_dir / "render"
    render_dir.mkdir(parents=True, exist_ok=True)
    plan_path = render_dir / "render-plan.json"

    source_info = get_media_info(source_video)
    voice_info = get_media_info(voice_audio)

    source_duration = float(source_info.get("format", {}).get("duration", 60.0))
    voice_duration = float(voice_info.get("format", {}).get("duration", 20.0))

    # Match exact source video duration
    target_duration = source_duration

    render_plan = {
        "jobId": job_id,
        "mode": "full_length",
        "sourceDuration": round(source_duration, 2),
        "narrationDuration": round(voice_duration, 2),
        "targetDuration": round(target_duration, 2),
    }

    with open(plan_path, "w", encoding="utf-8") as f:
        json.dump(render_plan, f, indent=2)

    return plan_path
