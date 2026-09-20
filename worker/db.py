import sqlite3
import os
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional, Dict, Any, List

def find_db_path() -> Path:
    base = Path(__file__).resolve().parent.parent
    candidate1 = base / "prisma" / "prisma" / "dev.db"
    if candidate1.exists() and candidate1.stat().st_size > 0:
        return candidate1
    candidate2 = base / "prisma" / "dev.db"
    if candidate2.exists() and candidate2.stat().st_size > 0:
        return candidate2
    candidate1.parent.mkdir(parents=True, exist_ok=True)
    return candidate1

def ensure_tables(conn):
    cur = conn.cursor()
    cur.execute("""
    CREATE TABLE IF NOT EXISTS jobs (
        id TEXT PRIMARY KEY,
        source_url TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'PENDING',
        progress INTEGER DEFAULT 0,
        stage TEXT,
        language TEXT DEFAULT 'my',
        voice TEXT DEFAULT 'default',
        sound_style TEXT DEFAULT 'cinematic_recap',
        burn_subtitles INTEGER DEFAULT 1,
        voxcpm_endpoint TEXT,
        voxcpm_api_key TEXT,
        created_at TEXT,
        started_at TEXT,
        completed_at TEXT,
        deleted_at TEXT,
        expires_at TEXT,
        error_code TEXT,
        error_message TEXT
    );
    """)
    cur.execute("""
    CREATE TABLE IF NOT EXISTS job_events (
        id TEXT PRIMARY KEY,
        job_id TEXT NOT NULL,
        stage TEXT NOT NULL,
        message TEXT NOT NULL,
        progress INTEGER DEFAULT 0,
        created_at TEXT NOT NULL
    );
    """)
    cur.execute("""
    CREATE TABLE IF NOT EXISTS job_assets (
        id TEXT PRIMARY KEY,
        job_id TEXT NOT NULL,
        type TEXT NOT NULL,
        storage_key TEXT NOT NULL,
        mime_type TEXT NOT NULL,
        size_bytes INTEGER,
        created_at TEXT NOT NULL,
        expires_at TEXT
    );
    """)
    conn.commit()

def get_connection():
    db_path = find_db_path()
    conn = sqlite3.connect(str(db_path), timeout=30.0)
    conn.row_factory = sqlite3.Row
    ensure_tables(conn)
    return conn

def create_job(
    job_id: str,
    source_url: str,
    language: str = "my",
    voice: str = "default",
    sound_style: str = "cinematic_recap",
    voxcpm_endpoint: Optional[str] = None,
    burn_subtitles: bool = True
) -> Dict[str, Any]:
    conn = get_connection()
    try:
        cur = conn.cursor()
        now = datetime.now(timezone.utc).isoformat()
        cur.execute(
            """INSERT INTO jobs (id, source_url, status, progress, stage, language, voice, sound_style, burn_subtitles, voxcpm_endpoint, created_at)
               VALUES (?, ?, 'PENDING', 0, 'INITIALIZING', ?, ?, ?, ?, ?, ?)""",
            (job_id, source_url, language, voice, sound_style, 1 if burn_subtitles else 0, voxcpm_endpoint, now)
        )
        conn.commit()
        cur.execute("SELECT * FROM jobs WHERE id = ?", (job_id,))
        row = cur.fetchone()
        return dict(row) if row else {}
    finally:
        conn.close()

def get_job(job_id: str) -> Optional[Dict[str, Any]]:
    conn = get_connection()
    try:
        cur = conn.cursor()
        cur.execute("SELECT * FROM jobs WHERE id = ?", (job_id,))
        row = cur.fetchone()
        if not row:
            return None
        return dict(row)
    finally:
        conn.close()

def update_job_status(
    job_id: str,
    status: str,
    progress: Optional[int] = None,
    stage: Optional[str] = None,
    event_message: Optional[str] = None,
    error_code: Optional[str] = None,
    error_message: Optional[str] = None,
):
    conn = get_connection()
    try:
        cur = conn.cursor()
        now = datetime.now(timezone.utc).isoformat()

        # Build update statement
        updates = ["status = ?"]
        params = [status]

        if progress is not None:
            updates.append("progress = ?")
            params.append(progress)

        if error_code is not None:
            updates.append("error_code = ?")
            params.append(error_code)

        if error_message is not None:
            updates.append("error_message = ?")
            params.append(error_message)

        if status == "DOWNLOADING":
            updates.append("started_at = ?")
            params.append(now)

        if status in ("READY", "FAILED"):
            updates.append("completed_at = ?")
            params.append(now)

        params.append(job_id)
        cur.execute(f"UPDATE jobs SET {', '.join(updates)} WHERE id = ?", params)

        # Record event
        if event_message or stage:
            event_id = f"evt_{int(datetime.now().timestamp() * 1000)}_{uuid.uuid4().hex[:6]}"
            stg = stage or status
            msg = event_message or f"Status changed to {status}"
            prg = progress if progress is not None else 0
            cur.execute(
                "INSERT INTO job_events (id, job_id, stage, message, progress, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                (event_id, job_id, stg, msg, prg, now),
            )

        conn.commit()
    finally:
        conn.close()

def record_asset(
    job_id: str,
    asset_type: str,
    storage_key: str,
    mime_type: str,
    size_bytes: Optional[int] = None,
    expires_at: Optional[str] = None,
):
    conn = get_connection()
    try:
        cur = conn.cursor()
        asset_id = f"ast_{int(datetime.now().timestamp() * 1000)}_{uuid.uuid4().hex[:6]}"
        now = datetime.now(timezone.utc).isoformat()
        cur.execute(
            """INSERT INTO job_assets (id, job_id, type, storage_key, mime_type, size_bytes, created_at, expires_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (asset_id, job_id, asset_type, storage_key, mime_type, size_bytes, now, expires_at),
        )
        conn.commit()
    finally:
        conn.close()

def get_expired_jobs() -> List[Dict[str, Any]]:
    conn = get_connection()
    try:
        cur = conn.cursor()
        now = datetime.now(timezone.utc).isoformat()
        cur.execute(
            "SELECT * FROM jobs WHERE expires_at IS NOT NULL AND expires_at < ? AND status != 'DELETED'",
            (now,),
        )
        return [dict(r) for r in cur.fetchall()]
    finally:
        conn.close()

def mark_job_deleted(job_id: str):
    conn = get_connection()
    try:
        cur = conn.cursor()
        now = datetime.now(timezone.utc).isoformat()
        cur.execute("UPDATE jobs SET status = 'DELETED', deleted_at = ? WHERE id = ?", (now, job_id))
        conn.commit()
    finally:
        conn.close()
