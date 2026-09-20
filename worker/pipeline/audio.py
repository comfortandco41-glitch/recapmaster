import subprocess
from pathlib import Path
from worker.config import FFMPEG_BIN, WORKSPACE_DIR
from worker.db import update_job_status, record_asset

def extract_audio(job_id: str, source_video: Path) -> Path:
    job_dir = WORKSPACE_DIR / job_id
    audio_dir = job_dir / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)
    target_wav = audio_dir / "audio.wav"

    # Checkpoint
    if target_wav.exists() and target_wav.stat().st_size > 1024:
        return target_wav

    update_job_status(
        job_id,
        status="DOWNLOADED",
        progress=25,
        stage="AUDIO_EXTRACTION",
        event_message="Extracting speech audio (16kHz mono PCM WAV)",
    )

    cmd = [
        FFMPEG_BIN,
        "-y",
        "-i", str(source_video),
        "-vn",
        "-ac", "1",
        "-ar", "16000",
        "-c:a", "pcm_s16le",
        str(target_wav),
    ]

    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"FFmpeg audio extraction failed: {result.stderr.strip()}")

    if not target_wav.exists() or target_wav.stat().st_size == 0:
        raise RuntimeError("Extracted audio WAV is missing or empty.")

    record_asset(
        job_id=job_id,
        asset_type="SOURCE_AUDIO",
        storage_key=f"jobs/{job_id}/audio/audio.wav",
        mime_type="audio/wav",
        size_bytes=target_wav.stat().st_size,
    )

    update_job_status(
        job_id,
        status="DOWNLOADED",
        progress=28,
        stage="AUDIO_EXTRACTION",
        event_message="Audio extraction complete",
    )

    return target_wav
