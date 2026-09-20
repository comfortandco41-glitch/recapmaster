import math
import subprocess
from pathlib import Path
from typing import Dict, Any, Optional, List

import numpy as np
import soundfile as sf

from worker.config import WORKSPACE_DIR, FFMPEG_BIN
from worker.db import update_job_status, record_asset
from worker.providers.voxcpm import VoxCPMProvider

def group_segments_into_blocks(
    segments: List[Dict[str, Any]],
    max_block_duration: float = 16.0,
    max_gap: float = 1.0,
) -> List[Dict[str, Any]]:
    """Group close dialogue segments into natural narration timing blocks."""
    valid_segs = [s for s in segments if s.get("text") and len(s["text"].strip()) > 0]
    if not valid_segs:
        return []

    blocks: List[Dict[str, Any]] = []
    curr = {
        "start": valid_segs[0]["start"],
        "end": valid_segs[0]["end"],
        "texts": [valid_segs[0]["text"].strip()],
    }

    for s in valid_segs[1:]:
        gap = s["start"] - curr["end"]
        span = s["end"] - curr["start"]
        if gap <= max_gap and span <= max_block_duration:
            curr["end"] = s["end"]
            curr["texts"].append(s["text"].strip())
        else:
            blocks.append({
                "start": curr["start"],
                "end": curr["end"],
                "text": " ".join(curr["texts"]),
            })
            curr = {
                "start": s["start"],
                "end": s["end"],
                "texts": [s["text"].strip()],
            }

    blocks.append({
        "start": curr["start"],
        "end": curr["end"],
        "text": " ".join(curr["texts"]),
    })
    return blocks

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
) -> Path:
    job_dir = WORKSPACE_DIR / job_id
    voice_dir = job_dir / "voice"
    voice_dir.mkdir(parents=True, exist_ok=True)
    target_wav = voice_dir / "voice.wav"

    # Checkpoint
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
        event_message=f"Generating timestamp-synchronized voice via VoxCPM 2 [{sound_style}]{f' ({endpoint})' if endpoint else ''}",
    )

    provider = VoxCPMProvider(endpoint=endpoint, api_key=api_key)
    segments = transcript_data.get("segments", []) if transcript_data else []

    # Timestamped voice dubbing across the full video timeline
    if segments and source_duration and source_duration > 1.0:
        blocks = group_segments_into_blocks(segments, max_block_duration=16.0, max_gap=1.0)
        sample_rate = 24000
        total_samples = int(math.ceil(source_duration * sample_rate))
        full_timeline = np.zeros(total_samples, dtype=np.float32)

        print(f"[*] Synthesizing {len(blocks)} timestamped narration blocks across {source_duration:.1f}s timeline [{sound_style}]...")

        chunks_dir = voice_dir / "chunks"
        chunks_dir.mkdir(parents=True, exist_ok=True)

        last_speech_end = 0.0

        for i, block in enumerate(blocks):
            b_start = block["start"]
            b_end = block["end"]
            b_text = block["text"]
            b_file = chunks_dir / f"block_{i}.wav"

            # Strict sequential timing: Ensure previous dialogue finishes with breathing room
            # Never overlap with the previous block even if original timestamp is earlier
            actual_start = max(b_start, last_speech_end + 0.12)

            # Target start of the next block or end of video
            next_start = blocks[i + 1]["start"] if i < len(blocks) - 1 else source_duration
            avail_window = max(0.5, next_start - actual_start)

            try:
                provider.generate_speech(
                    text=b_text,
                    output_path=b_file,
                    language=language,
                    voice=voice,
                )

                data, sr = sf.read(str(b_file))
                if len(data.shape) > 1:
                    data = data[:, 0]
                dur = len(data) / float(sr)

                # Speed adaptation:
                # When translated Burmese is wordier than original speech, speed up
                # cleanly with FFmpeg atempo (up to 1.50x) so it doesn't lag or overflow
                speed_factor = 1.0
                if dur > avail_window and avail_window >= 0.8:
                    speed_factor = min(1.50, dur / max(0.5, avail_window - 0.08))
                elif language in ["my", "burmese"] and dur > 2.5:
                    speed_factor = 1.08  # slight brisk natural pace for Burmese recap

                if speed_factor > 1.03:
                    fitted_file = chunks_dir / f"block_{i}_fit.wav"
                    cmd = [
                        FFMPEG_BIN,
                        "-y",
                        "-i", str(b_file),
                        "-filter:a", f"atempo={speed_factor:.3f}",
                        "-ac", "1",
                        "-ar", str(sample_rate),
                        str(fitted_file),
                    ]
                    subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
                    data, sr = sf.read(str(fitted_file))
                    if len(data.shape) > 1:
                        data = data[:, 0]

                # Place on canvas strictly sequentially
                start_idx = max(0, int(actual_start * sample_rate))
                end_idx = min(total_samples, start_idx + len(data))
                n_samples = end_idx - start_idx
                if n_samples > 0:
                    full_timeline[start_idx:end_idx] = data[:n_samples]

                actual_dur = len(data) / float(sample_rate)
                last_speech_end = actual_start + actual_dur

            except Exception as be:
                print(f"[VoxCPM] Warning on block {i} ({b_start:.1f}s): {be}")

        # Sound Design Audio Mastering Presets
        SOUND_STYLE_FILTERS = {
            "cinematic_recap": "equalizer=f=120:width_type=h:width=80:g=3.5,equalizer=f=3200:width_type=h:width=1200:g=2.0,acompressor=threshold=0.12:ratio=3:attack=15:release=120,loudnorm=I=-16:LRA=7:TP=-1.5",
            "dramatic_suspense": "equalizer=f=90:width_type=h:width=60:g=4.5,equalizer=f=2500:width_type=h:width=1000:g=2.5,acompressor=threshold=0.08:ratio=4.5:attack=10:release=100,loudnorm=I=-15:LRA=6:TP=-1.5",
            "energetic_action": "equalizer=f=180:width_type=h:width=90:g=-1.5,equalizer=f=4200:width_type=h:width=1500:g=3.0,acompressor=threshold=0.15:ratio=3.5:attack=5:release=80,loudnorm=I=-15:LRA=6:TP=-1.5",
            "emotional_warm": "equalizer=f=250:width_type=h:width=100:g=2.5,equalizer=f=6000:width_type=h:width=2000:g=-3.0,acompressor=threshold=0.15:ratio=2.0:attack=20:release=150,loudnorm=I=-17:LRA=8:TP=-2.0",
            "documentary_studio": "highpass=f=80,equalizer=f=3000:width_type=h:width=1000:g=1.5,acompressor=threshold=0.15:ratio=2.5:attack=15:release=120,loudnorm=I=-16:LRA=7:TP=-1.5",
        }
        audio_filter = SOUND_STYLE_FILTERS.get(sound_style, SOUND_STYLE_FILTERS["cinematic_recap"])

        raw_wav = voice_dir / "voice_raw.wav"
        sf.write(str(raw_wav), full_timeline, sample_rate)

        # Apply sound design mastering via FFmpeg
        cmd_master = [
            FFMPEG_BIN,
            "-y",
            "-i", str(raw_wav),
            "-af", audio_filter,
            "-ar", str(sample_rate),
            str(target_wav),
        ]
        res_master = subprocess.run(cmd_master, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if res_master.returncode != 0 or not target_wav.exists():
            # Fallback if filter had an issue
            sf.write(str(target_wav), full_timeline, sample_rate)

    else:
        # Fallback to single-pass script narration
        with open(script_path, "r", encoding="utf-8") as f:
            narration_text = f.read().strip()

        provider.generate_speech(
            text=narration_text,
            output_path=target_wav,
            language=language,
            voice=voice,
        )

    if not target_wav.exists() or target_wav.stat().st_size == 0:
        raise RuntimeError("Generated voice audio is missing or empty.")

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
        event_message="Narration audio synthesized and normalized to video timeline",
    )

    return target_wav
