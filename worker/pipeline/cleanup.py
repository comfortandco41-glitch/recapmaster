import shutil
from pathlib import Path
from typing import List

from worker.config import WORKSPACE_DIR
from worker.db import get_expired_jobs, mark_job_deleted, update_job_status

def cleanup_job(job_id: str, delete_record: bool = False) -> bool:
    job_dir = WORKSPACE_DIR / job_id

    update_job_status(
        job_id,
        status="CLEANING",
        stage="CLEANUP",
        event_message="Clearing temporary video, audio, transcription, and render files",
    )

    # Delete files in reverse order as specified in architecture.md section 19
    subdirs = ["render", "voice", "transcript", "audio", "source"]
    for sub in subdirs:
        p = job_dir / sub
        if p.exists():
            shutil.rmtree(str(p), ignore_errors=True)

    if job_dir.exists():
        shutil.rmtree(str(job_dir), ignore_errors=True)

    mark_job_deleted(job_id)
    return True

def run_ttl_cleanup() -> List[str]:
    """
    Finds and purges all jobs whose TTL has expired.
    """
    expired = get_expired_jobs()
    cleaned_ids = []

    for job in expired:
        jid = job["id"]
        try:
            cleanup_job(jid)
            cleaned_ids.append(jid)
        except Exception as e:
            print(f"Failed to cleanup expired job {jid}: {e}")

    return cleaned_ids
