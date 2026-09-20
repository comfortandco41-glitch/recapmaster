import sys
import shutil
import subprocess
import json
from pathlib import Path
from typing import Dict, Any

from urllib.parse import urlparse, urlunparse
from worker.config import WORKSPACE_DIR, MAX_SOURCE_SIZE_MB, FFPROBE_BIN, FFMPEG_BIN
from worker.db import update_job_status, record_asset

def clean_media_url(raw_url: str) -> str:
    """Strip unnecessary tracking parameters that cause 412 on Bilibili."""
    try:
        parsed = urlparse(raw_url)
        if "bilibili" in parsed.netloc.lower():
            # Drop query and fragment for Bilibili video & bangumi URLs
            return urlunparse((parsed.scheme, parsed.netloc, parsed.path, "", "", ""))
    except Exception:
        pass
    return raw_url

def get_media_info(file_path: Path) -> Dict[str, Any]:
    cmd = [
        FFPROBE_BIN,
        "-v", "quiet",
        "-print_format", "json",
        "-show_format",
        "-show_streams",
        str(file_path),
    ]
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
        return json.loads(proc.stdout)
    except Exception:
        return {}

def download_source(job_id: str, url: str) -> Path:
    job_dir = WORKSPACE_DIR / job_id
    source_dir = job_dir / "source"
    source_dir.mkdir(parents=True, exist_ok=True)
    target_file = source_dir / "source.mp4"

    # Checkpoint: If already downloaded and valid, reuse
    if target_file.exists() and target_file.stat().st_size > 1024:
        update_job_status(
            job_id,
            status="DOWNLOADED",
            progress=20,
            stage="DOWNLOADING",
            event_message="Source media already downloaded (resuming checkpoint)",
        )
        return target_file

    update_job_status(
        job_id,
        status="DOWNLOADING",
        progress=10,
        stage="DOWNLOADING",
        event_message="Starting source media download via yt-dlp",
    )

    clean_url = clean_media_url(url)
    ffmpeg_dir = str(Path(FFMPEG_BIN).parent)

    # Safe argument array without shell concatenation
    cmd = [
        sys.executable,
        "-m", "yt_dlp",
        "--no-playlist",
        "--no-warnings",
        "--ffmpeg-location", ffmpeg_dir,
        "--max-filesize", f"{MAX_SOURCE_SIZE_MB}m",
        "--extractor-args", "youtube:player_client=ios,android,web",
        "-f", "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo*+bestaudio/best[ext=mp4]/best",
        "--merge-output-format", "mp4",
        "-o", str(target_file),
    ]

    if shutil.which("node"):
        cmd.extend(["--js-runtimes", "node"])

    cmd.append(clean_url)

    try:
        result = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=600,
        )
    except subprocess.TimeoutExpired as e:
        raise RuntimeError(f"Downloader timed out after 600s: {e}")

    if result.returncode != 0:
        err_msg = result.stderr.strip() or result.stdout.strip()
        raise RuntimeError(f"yt-dlp download failed (code {result.returncode}): {err_msg}")

    # Verify media
    if not target_file.exists() or target_file.stat().st_size == 0:
        # Check if yt-dlp saved with another extension
        candidates = list(source_dir.glob("source.*"))
        if candidates and candidates[0].stat().st_size > 0:
            candidates[0].rename(target_file)
        else:
            raise RuntimeError("Downloaded media file was not created or has 0 bytes.")

    file_size = target_file.stat().st_size
    info = get_media_info(target_file)
    duration = float(info.get("format", {}).get("duration", 0))

    # Record asset
    record_asset(
        job_id=job_id,
        asset_type="SOURCE_VIDEO",
        storage_key=f"jobs/{job_id}/source/source.mp4",
        mime_type="video/mp4",
        size_bytes=file_size,
    )

    update_job_status(
        job_id,
        status="DOWNLOADED",
        progress=20,
        stage="DOWNLOADING",
        event_message=f"Source video downloaded successfully ({file_size / (1024*1024):.1f} MB, {duration:.1f}s)",
    )

    return target_file
