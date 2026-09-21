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
        subtitle_placement TEXT DEFAULT 'bottom',
        subtitle_size REAL DEFAULT 1.0,
        subtitle_margin_v INTEGER,
        blur_box_config TEXT,
        gemini_api_key TEXT,
        created_at TEXT,
        started_at TEXT,
        completed_at TEXT,
        deleted_at TEXT,
        expires_at TEXT,
        error_code TEXT,
        error_message TEXT
    );
    """)

    # Migrate existing tables if new columns don't exist
    new_cols = [
        ("subtitle_placement", "TEXT DEFAULT 'bottom'"),
        ("subtitle_size", "REAL DEFAULT 1.0"),
        ("subtitle_margin_v", "INTEGER"),
        ("blur_box_config", "TEXT"),
        ("voice_rate", "TEXT DEFAULT '+10%'"),
        ("voice_pitch", "TEXT DEFAULT '-2Hz'"),
        ("bg_music_volume", "REAL DEFAULT 0.15"),
        ("gemini_api_key", "TEXT"),
        ("stage", "TEXT"),
        ("source_platform", "TEXT DEFAULT 'YOUTUBE'"),
        ("playback_speed", "REAL DEFAULT 1.0"),
    ]
    for col_name, col_def in new_cols:
        try:
            cur.execute(f"ALTER TABLE jobs ADD COLUMN {col_name} {col_def}")
        except sqlite3.OperationalError:
            pass

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
    burn_subtitles: bool = True,
    subtitle_placement: str = "bottom",
    subtitle_size: float = 1.0,
    subtitle_margin_v: Optional[int] = None,
    blur_box_config: Optional[str] = None,
    voice_rate: Optional[str] = "+10%",
    voice_pitch: Optional[str] = "-2Hz",
    bg_music_volume: Optional[float] = 0.15,
    gemini_api_key: Optional[str] = None,
) -> Dict[str, Any]:
    conn = get_connection()
    try:
        cur = conn.cursor()
        now = datetime.now(timezone.utc).isoformat()
        platform = "BILIBILI" if ("bilibili" in source_url.lower() or "b23.tv" in source_url.lower()) else "YOUTUBE"
        cur.execute(
            """INSERT INTO jobs (id, source_url, source_platform, status, progress, stage, language, voice, sound_style, burn_subtitles, voxcpm_endpoint, subtitle_placement, subtitle_size, subtitle_margin_v, blur_box_config, voice_rate, voice_pitch, bg_music_volume, gemini_api_key, created_at)
               VALUES (?, ?, ?, 'PENDING', 0, 'INITIALIZING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (job_id, source_url, platform, language, voice, sound_style, 1 if burn_subtitles else 0, voxcpm_endpoint, subtitle_placement, subtitle_size, subtitle_margin_v, blur_box_config, voice_rate, voice_pitch, bg_music_volume, gemini_api_key, now)
        )
        conn.commit()
        cur.execute("SELECT * FROM jobs WHERE id = ?", (job_id,))
        row = cur.fetchone()
        return dict(row) if row else {}
    finally:
        conn.close()

def update_job_render_settings(
    job_id: str,
    subtitle_placement: Optional[str] = None,
    subtitle_size: Optional[float] = None,
    subtitle_margin_v: Optional[int] = None,
    blur_box_config: Optional[str] = None,
    burn_subtitles: Optional[bool] = None,
    voice: Optional[str] = None,
    sound_style: Optional[str] = None,
    voice_rate: Optional[str] = None,
    voice_pitch: Optional[str] = None,
    bg_music_volume: Optional[float] = None,
    voxcpm_endpoint: Optional[str] = None,
    gemini_api_key: Optional[str] = None,
) -> bool:
    conn = get_connection()
    try:
        cur = conn.cursor()
        updates = []
        params = []
        if subtitle_placement is not None:
            updates.append("subtitle_placement = ?")
            params.append(subtitle_placement)
        if subtitle_size is not None:
            updates.append("subtitle_size = ?")
            params.append(subtitle_size)
        if subtitle_margin_v is not None:
            updates.append("subtitle_margin_v = ?")
            params.append(subtitle_margin_v)
        if blur_box_config is not None:
            updates.append("blur_box_config = ?")
            params.append(blur_box_config)
        if burn_subtitles is not None:
            updates.append("burn_subtitles = ?")
            params.append(1 if burn_subtitles else 0)
        if voice is not None:
            updates.append("voice = ?")
            params.append(voice)
        if sound_style is not None:
            updates.append("sound_style = ?")
            params.append(sound_style)
        if voice_rate is not None:
            updates.append("voice_rate = ?")
            params.append(voice_rate)
        if voice_pitch is not None:
            updates.append("voice_pitch = ?")
            params.append(voice_pitch)
        if bg_music_volume is not None:
            updates.append("bg_music_volume = ?")
            params.append(float(bg_music_volume))
        if voxcpm_endpoint is not None:
            updates.append("voxcpm_endpoint = ?")
            params.append(voxcpm_endpoint)
        if gemini_api_key is not None:
            updates.append("gemini_api_key = ?")
            params.append(gemini_api_key)

        if not updates:
            return True
        params.append(job_id)
        cur.execute(f"UPDATE jobs SET {', '.join(updates)} WHERE id = ?", params)
        conn.commit()
        return cur.rowcount > 0
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
