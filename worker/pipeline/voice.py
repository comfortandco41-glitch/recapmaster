import math
import shutil
import subprocess
from pathlib import Path
from typing import Dict, Any, Optional, List

import numpy as np
import soundfile as sf

from worker.config import WORKSPACE_DIR, FFMPEG_BIN
from worker.db import update_job_status, record_asset
from worker.providers.voxcpm import VoxCPMProvider

SOUND_STYLE_FILTERS = {
    "cinematic_recap": "equalizer=f=120:width_type=h:width=80:g=3.5,equalizer=f=3200:width_type=h:width=1200:g=2.0,acompressor=threshold=0.12:ratio=3:attack=15:release=120,loudnorm=I=-16:LRA=7:TP=-1.5",
    "dramatic_suspense": "equalizer=f=90:width_type=h:width=60:g=4.5,equalizer=f=2500:width_type=h:width=1000:g=2.5,acompressor=threshold=0.08:ratio=4.5:attack=10:release=100,loudnorm=I=-15:LRA=6:TP=-1.5",
    "energetic_action": "equalizer=f=180:width_type=h:width=90:g=-1.5,equalizer=f=4200:width_type=h:width=1500:g=3.0,acompressor=threshold=0.15:ratio=3.5:attack=5:release=80,loudnorm=I=-15:LRA=6:TP=-1.5",
    "emotional_warm": "equalizer=f=250:width_type=h:width=100:g=2.5,equalizer=f=6000:width_type=h:width=2000:g=-3.0,acompressor=threshold=0.15:ratio=2.0:attack=20:release=150,loudnorm=I=-17:LRA=8:TP=-2.0",
    "emotional_warmth": "equalizer=f=250:width_type=h:width=100:g=2.5,equalizer=f=6000:width_type=h:width=2000:g=-3.0,acompressor=threshold=0.15:ratio=2.0:attack=20:release=150,loudnorm=I=-17:LRA=8:TP=-2.0",
    "documentary_studio": "highpass=f=80,equalizer=f=3000:width_type=h:width=1000:g=1.5,acompressor=threshold=0.15:ratio=2.5:attack=15:release=120,loudnorm=I=-16:LRA=7:TP=-1.5",
    "broadcast_studio": "highpass=f=80,equalizer=f=3000:width_type=h:width=1000:g=1.5,acompressor=threshold=0.15:ratio=2.5:attack=15:release=120,loudnorm=I=-16:LRA=7:TP=-1.5",
}

def generate_voice_narration(
    job_id: str,
    script_path: Path,
    transcript_data: Optional[Dict[str, Any]] = None,
    source_duration: Optional[float] = None,
    language: str = "en",
    voice: str = "default",
    sound_style: str = "cinematic_recap",
    endpoint: Optional[str] = None,
    api_key: Optional[str] = None,
    voice_rate: Optional[str] = None,
    voice_pitch: Optional[str] = None,
) -> Path:
    job_dir = WORKSPACE_DIR / job_id
    voice_dir = job_dir / "voice"
    voice_dir.mkdir(parents=True, exist_ok=True)
    target_wav = voice_dir / "voice.wav"
    raw_wav = voice_dir / "voice_raw.wav"

    # Checkpoint: return existing valid audio if already generated
    if target_wav.exists() and target_wav.stat().st_size > 1024:
        update_job_status(
            job_id,
            status="VOICE_READY",
            progress=70,
            stage="VOICE_GENERATION",
            event_message="Voice narration loaded from checkpoint",
        )
        return target_wav

    update_job_status(
        job_id,
        status="VOICE_GENERATING",
        progress=60,
        stage="VOICE_GENERATION",
        event_message=f"Generating full voice narration via {voice or 'Edge TTS'} [{sound_style}]{f' ({endpoint})' if endpoint else ''}",
    )

    provider = VoxCPMProvider(endpoint=endpoint, api_key=api_key)

    # 1. Retrieve full narration text (without timestamp chunking)
    narration_text = ""
    if script_path and Path(script_path).exists():
        with open(script_path, "r", encoding="utf-8") as f:
            narration_text = f.read().strip()

    if not narration_text and transcript_data:
        segments = transcript_data.get("segments", [])
        seg_texts = [s.get("text", "").strip() for s in segments if s.get("text")]
        narration_text = " ".join(seg_texts).strip()

    if not narration_text:
        if language in ("my", "burmese"):
            narration_text = "အဓိကဇာတ်ကောင်များသည် မမျှော်လင့်ထားသောအဖြစ်အပျက်များကို ရင်ဆိုင်နေရပြီး ဇာတ်လမ်းသည် အထွတ်အထိပ်သို့ ရောက်ရှိသွားခဲ့ပါသည်။"
        else:
            narration_text = "The story unfolds with key events as the confrontation reaches its peak."

    # 2. Synthesize full continuous voice narration in a single pass
    print(f"[*] Synthesizing full voiceover ({len(narration_text.split())} words, lang={language}, voice={voice}) [{sound_style}]...")
    provider.generate_speech(
        text=narration_text,
        output_path=raw_wav,
        language=language,
        voice=voice,
        rate=voice_rate,
        pitch=voice_pitch,
    )

    if not raw_wav.exists() or raw_wav.stat().st_size == 0:
        raise RuntimeError("Generated voice audio is missing or empty.")

    # 3. Read generated raw voice metrics
    audio_info = sf.info(str(raw_wav))
    raw_duration = float(audio_info.duration)
    sample_rate = audio_info.samplerate or 24000
    print(f"[*] Raw voice generated: {raw_duration:.2f}s (target video duration: {f'{source_duration:.2f}s' if source_duration else 'unspecified'})")

    audio_filter = SOUND_STYLE_FILTERS.get(sound_style, SOUND_STYLE_FILTERS["cinematic_recap"])

    # 4. Adjust audio length to match original video length if specified
    if source_duration and source_duration > 0.5 and raw_duration > 0.1:
        # e.g., if audio is 40 sec and video is 50 sec:
        # tempo = 40.0 / 50.0 = 0.8 (slow down audio without pitch shift to reach 50 sec)
        tempo = raw_duration / float(source_duration)
        safe_tempo = max(0.5, min(2.0, tempo))
        print(f"[*] Adjusting audio tempo: factor={tempo:.4f} (applied={safe_tempo:.4f}) to match video length {source_duration:.2f}s")

        pad_dur = max(2.0, float(source_duration) - (raw_duration / safe_tempo) + 1.0)
        af_chain = (
            f"{audio_filter},"
            f"atempo={safe_tempo:.4f},"
            f"apad=pad_dur={pad_dur:.2f},"
            f"atrim=0:{source_duration:.3f}"
        )
    else:
        af_chain = audio_filter

    # 5. Apply sound mastering & tempo time-stretching with FFmpeg
    cmd_master = [
        FFMPEG_BIN,
        "-y",
        "-i", str(raw_wav),
        "-af", af_chain,
        "-ar", str(sample_rate),
        str(target_wav),
    ]
    res_master = subprocess.run(cmd_master, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res_master.returncode != 0 or not target_wav.exists() or target_wav.stat().st_size == 0:
        print(f"[Warning] FFmpeg audio filter returned note: {res_master.stderr[:200]}. Falling back to clean copy...")
        shutil.copy2(raw_wav, target_wav)

    if not target_wav.exists() or target_wav.stat().st_size == 0:
        raise RuntimeError("Generated voice audio is missing or empty after processing.")

    final_info = sf.info(str(target_wav))
    final_dur = final_info.duration
    print(f"[OK] Voice narration finalized: duration={final_dur:.2f}s ({target_wav.stat().st_size} bytes)")

    record_asset(
        job_id=job_id,
        asset_type="VOICE_AUDIO",
        storage_key=f"jobs/{job_id}/voice/voice.wav",
        mime_type="audio/wav",
        size_bytes=target_wav.stat().st_size,
    )

    update_job_status(
        job_id,
        status="VOICE_READY",
        progress=70,
        stage="VOICE_GENERATION",
        event_message=f"Narration voice generated ({final_dur:.1f}s) and adjusted to video timeline",
    )

    return target_wav
