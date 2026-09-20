import json
import shutil
import subprocess
from pathlib import Path
from typing import Dict, Any, List, Optional

from worker.config import FFMPEG_BIN, WORKSPACE_DIR
from worker.db import update_job_status, record_asset
from worker.pipeline.download import get_media_info

import re

def srt_time_to_ass_time(srt_time: str) -> str:
    srt_time = srt_time.strip()
    if "," in srt_time:
        main_part, ms = srt_time.split(",")
        chunks = main_part.split(":")
        if len(chunks) == 3:
            h = str(int(chunks[0]))
            m = chunks[1]
            s = chunks[2]
            return f"{h}:{m}:{s}.{ms[:2]}"
    return srt_time.replace(",", ".")

def generate_ass_subtitles(srt_path: Path, ass_output_path: Path, video_width: int = 360, video_height: int = 640) -> Path:
    font_size = max(11, int(video_height * 0.024))
    margin_v = max(18, int(video_height * 0.045))

    ass_lines = [
        "[Script Info]",
        "Title: Burmese Subtitles",
        "ScriptType: v4.00+",
        "WrapStyle: 0",
        "ScaledBorderAndShadow: yes",
        f"PlayResX: {video_width}",
        f"PlayResY: {video_height}",
        "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
        f"Style: Default,Padauk,{font_size},&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,1.5,0.8,2,16,16,{margin_v},1",
        "",
        "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
    ]

    try:
        content = srt_path.read_text(encoding="utf-8")
        cues = re.split(r"\n\s*\n", content.strip())

        for cue in cues:
            lines = [line.strip() for line in cue.strip().split("\n") if line.strip()]
            if len(lines) >= 3 and "-->" in lines[1]:
                timing = lines[1]
                text = " ".join(lines[2:])
                times = timing.split("-->")
                start_ass = srt_time_to_ass_time(times[0])
                end_ass = srt_time_to_ass_time(times[1])

                # Line wrapping for long subtitles
                if len(text) > 34 and "။" in text and not text.endswith("။"):
                    parts = text.split("။")
                    text = parts[0].strip() + "။\\N" + "။".join(parts[1:]).strip()
                elif len(text) > 36 and " " in text:
                    words = text.split(" ")
                    mid = len(words) // 2
                    text = " ".join(words[:mid]) + "\\N" + " ".join(words[mid:])

                ass_lines.append(f"Dialogue: 0,{start_ass},{end_ass},Default,,0,0,0,,{text}")

        ass_output_path.write_text("\n".join(ass_lines), encoding="utf-8-sig")
    except Exception as e:
        print(f"[ASS Subtitles] Warning generating ASS file: {e}")

    return ass_output_path

def render_recap_video(
    job_id: str,
    source_video: Path,
    voice_audio: Path,
    plan_path: Path,
    burn_subtitles: bool = True,
    mix_original_audio: bool = False,
) -> Path:
    source_video = Path(source_video).resolve()
    voice_audio = Path(voice_audio).resolve()
    plan_path = Path(plan_path).resolve()
    job_dir = WORKSPACE_DIR / job_id
    render_dir = job_dir / "render"
    render_dir.mkdir(parents=True, exist_ok=True)
    final_output = render_dir / "final.mp4"

    update_job_status(
        job_id,
        status="RENDERING",
        progress=75,
        stage="RENDERING",
        event_message="Composing full-length video with synchronized narration audio and subtitles",
    )

    with open(plan_path, "r", encoding="utf-8") as f:
        plan = json.load(f)

    source_info = get_media_info(source_video)
    has_source_audio = any(s.get("codec_type") == "audio" for s in source_info.get("streams", []))

    v_stream = next((s for s in source_info.get("streams", []) if s.get("codec_type") == "video"), {})
    video_width = int(v_stream.get("width") or 360)
    video_height = int(v_stream.get("height") or 640)

    # Prepare fonts directory with Padauk for Myanmar Unicode shaping
    fonts_dir = render_dir / "fonts"
    fonts_dir.mkdir(parents=True, exist_ok=True)
    asset_font = Path(__file__).resolve().parent.parent / "assets" / "Padauk-Regular.ttf"
    target_font = fonts_dir / "Padauk-Regular.ttf"

    if asset_font.exists():
        shutil.copy2(asset_font, target_font)
    else:
        # Check system font paths on Linux (e.g. Hugging Face Spaces)
        linux_paths = [
            Path("/usr/share/fonts/truetype/padauk/Padauk-Regular.ttf"),
            Path("/usr/share/fonts/truetype/Padauk-Regular.ttf"),
        ]
        found = next((p for p in linux_paths if p.exists()), None)
        if found:
            shutil.copy2(found, target_font)
        else:
            # Auto-download from Google Fonts official repository if missing
            try:
                import urllib.request
                font_url = "https://raw.githubusercontent.com/google/fonts/main/ofl/padauk/Padauk-Regular.ttf"
                urllib.request.urlretrieve(font_url, str(target_font))
            except Exception as e:
                print(f"[Warning] Could not auto-download Padauk font: {e}")

    # Check for Burmese subtitles or main subtitles
    srt_candidates = [
        job_dir / "transcript" / "transcript_burmese.srt",
        job_dir / "transcript" / "transcript_my.srt",
        job_dir / "transcript" / "transcript.srt",
    ]
    sub_srt = next((p for p in srt_candidates if p.exists() and p.stat().st_size > 10), None)

    # Generate ASS subtitle file for accurate HarfBuzz shaping
    local_ass = None
    if sub_srt and burn_subtitles:
        local_ass = render_dir / "burn_subtitles.ass"
        generate_ass_subtitles(sub_srt, local_ass, video_width=video_width, video_height=video_height)

    # Strategy 1: Mix ducked original audio with full synchronized voiceover track
    render_success = False

    if has_source_audio and mix_original_audio:
        cmd = [
            FFMPEG_BIN,
            "-y",
            "-i", str(source_video),
            "-i", str(voice_audio),
            "-filter_complex",
            "[0:a]volume=0.15[bg];[1:a]volume=1.0[vox];[bg][vox]amix=inputs=2:duration=first:dropout_transition=2[aout]",
            "-map", "0:v:0",
            "-map", "[aout]",
        ]
        if local_ass and local_ass.exists():
            cmd.extend(["-vf", "ass=burn_subtitles.ass:fontsdir=fonts"])
        cmd.extend([
            "-c:v", "libx264",
            "-preset", "fast",
            "-crf", "20",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            "-b:a", "192k",
            "final.mp4",
        ])

        res = subprocess.run(cmd, cwd=str(render_dir), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res.returncode == 0 and final_output.exists() and final_output.stat().st_size > 1024:
            render_success = True
        else:
            print(f"[FFmpeg] Audio mix render note: {res.stderr[:200]}. Trying direct voice track...")

    # Strategy 2: Direct voiceover replacement if mixing had an issue or source has no audio
    if not render_success:
        cmd = [
            FFMPEG_BIN,
            "-y",
            "-i", str(source_video),
            "-i", str(voice_audio),
            "-map", "0:v:0",
            "-map", "1:a:0",
        ]
        if local_ass and local_ass.exists():
            cmd.extend(["-vf", "ass=burn_subtitles.ass:fontsdir=fonts"])
        cmd.extend([
            "-c:v", "libx264",
            "-preset", "fast",
            "-crf", "20",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            "-b:a", "192k",
            "final.mp4",
        ])

        res = subprocess.run(cmd, cwd=str(render_dir), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res.returncode == 0 and final_output.exists() and final_output.stat().st_size > 1024:
            render_success = True
        else:
            # Strategy 3: Clean copy without subtitles if subtitle filter failed
            cmd_clean = [
                FFMPEG_BIN,
                "-y",
                "-i", str(source_video),
                "-i", str(voice_audio),
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "192k",
                "final.mp4",
            ]
            res2 = subprocess.run(cmd_clean, cwd=str(render_dir), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            if res2.returncode == 0 and final_output.exists() and final_output.stat().st_size > 1024:
                render_success = True
            else:
                raise RuntimeError(f"FFmpeg rendering failed: {res2.stderr[:300]}")

    if not final_output.exists() or final_output.stat().st_size == 0:
        raise RuntimeError("Final rendered MP4 video is missing or empty.")

    info = get_media_info(final_output)
    file_size = final_output.stat().st_size
    duration = float(info.get("format", {}).get("duration", 0))

    record_asset(
        job_id=job_id,
        asset_type="FINAL_VIDEO",
        storage_key=f"jobs/{job_id}/render/final.mp4",
        mime_type="video/mp4",
        size_bytes=file_size,
    )

    update_job_status(
        job_id,
        status="READY",
        progress=100,
        stage="COMPLETED",
        event_message=f"Full-length video rendering complete ({duration:.1f}s, {file_size / (1024*1024):.1f} MB)",
    )

    return final_output
