import json
import shutil
import subprocess
from pathlib import Path
from typing import Dict, Any, List, Optional

from worker.config import FFMPEG_BIN, WORKSPACE_DIR
from worker.db import update_job_status, record_asset
from worker.pipeline.download import get_media_info

import re

ALIGNMENT_MAP = {
    "bottom": 2,
    "top": 8,
    "middle": 5,
    "center": 5,
    "bottom_left": 1,
    "bottom_right": 3,
    "top_left": 7,
    "top_right": 9,
}

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

def generate_ass_subtitles(
    srt_path: Path,
    ass_output_path: Path,
    video_width: int = 360,
    video_height: int = 640,
    placement: str = "bottom",
    font_size_scale: float = 1.0,
    margin_v: Optional[int] = None,
) -> Path:
    font_size = max(10, int(video_height * 0.024 * float(font_size_scale)))
    if margin_v is None:
        margin_v = max(18, int(video_height * 0.045))
    else:
        margin_v = max(0, int(margin_v))

    alignment = ALIGNMENT_MAP.get(str(placement).lower(), 2)

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
        f"Style: Default,Padauk,{font_size},&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,1.5,0.8,{alignment},16,16,{margin_v},1",
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

def build_video_filter_chain(
    video_width: int,
    video_height: int,
    blur_box: Optional[Dict[str, Any]] = None,
    ass_file_relative: Optional[str] = None,
    fonts_dir_relative: Optional[str] = None,
    input_tag: str = "0:v",
    output_tag: str = "vout",
) -> tuple[str, str]:
    """
    Builds FFmpeg filter complex string for logo blur box and ASS subtitle burning.
    Returns (filter_complex_string, final_output_tag).
    """
    filters = []
    current_tag = input_tag

    # Step 1: Blur Box (to remove old logo or watermark)
    if blur_box and blur_box.get("enabled", False):
        try:
            x_pct = float(blur_box.get("x_pct", 0.78))
            y_pct = float(blur_box.get("y_pct", 0.04))
            w_pct = float(blur_box.get("w_pct", 0.18))
            h_pct = float(blur_box.get("h_pct", 0.08))
            if x_pct > 1.0: x_pct /= 100.0
            if y_pct > 1.0: y_pct /= 100.0
            if w_pct > 1.0: w_pct /= 100.0
            if h_pct > 1.0: h_pct /= 100.0

            strength = max(3, min(60, int(blur_box.get("strength", 16))))
            bx = max(0, min(video_width - 4, int(x_pct * video_width)))
            by = max(0, min(video_height - 4, int(y_pct * video_height)))
            bw = max(4, min(video_width - bx, int(w_pct * video_width)))
            bh = max(4, min(video_height - by, int(h_pct * video_height)))

            # Make dimensions even
            bw = bw - (bw % 2)
            bh = bh - (bh % 2)
            if bw < 4: bw = 4
            if bh < 4: bh = 4
            if bx + bw > video_width:
                bw = video_width - bx
                bw = bw - (bw % 2)
            if by + bh > video_height:
                bh = video_height - by
                bh = bh - (bh % 2)

            next_tag = "v_blur" if ass_file_relative else output_tag
            blur_filter = (
                f"[{current_tag}]split=2[v_base][v_crop];"
                f"[v_crop]crop=w={bw}:h={bh}:x={bx}:y={by},avgblur=sizeX={strength}:sizeY={strength}[v_blurred_crop];"
                f"[v_base][v_blurred_crop]overlay=x={bx}:y={by}[{next_tag}]"
            )
            filters.append(blur_filter)
            current_tag = next_tag
        except Exception as e:
            print(f"[Warning] Failed to build blur box filter: {e}")

    # Step 2: ASS Subtitles
    if ass_file_relative:
        font_arg = f":fontsdir={fonts_dir_relative}" if fonts_dir_relative else ""
        sub_filter = f"[{current_tag}]ass={ass_file_relative}{font_arg}[{output_tag}]"
        filters.append(sub_filter)
        current_tag = output_tag

    if not filters:
        return f"[{input_tag}]null[{output_tag}]", output_tag

    return ";".join(filters), output_tag

def ensure_padauk_font(target_dir: Path) -> Path:
    target_dir.mkdir(parents=True, exist_ok=True)
    target_font = target_dir / "Padauk-Regular.ttf"
    if target_font.exists() and target_font.stat().st_size > 1000:
        return target_font

    asset_font = Path(__file__).resolve().parent.parent / "assets" / "Padauk-Regular.ttf"
    if asset_font.exists():
        shutil.copy2(asset_font, target_font)
        return target_font

    linux_paths = [
        Path("/usr/share/fonts/truetype/padauk/Padauk-Regular.ttf"),
        Path("/usr/share/fonts/truetype/Padauk-Regular.ttf"),
    ]
    found = next((p for p in linux_paths if p.exists()), None)
    if found:
        shutil.copy2(found, target_font)
        return target_font

    try:
        import urllib.request
        font_url = "https://raw.githubusercontent.com/google/fonts/main/ofl/padauk/Padauk-Regular.ttf"
        urllib.request.urlretrieve(font_url, str(target_font))
    except Exception as e:
        print(f"[Warning] Could not auto-download Padauk font: {e}")

    return target_font

def render_recap_video(
    job_id: str,
    source_video: Path,
    voice_audio: Path,
    plan_path: Path,
    burn_subtitles: bool = True,
    mix_original_audio: bool = False,
    subtitle_placement: str = "bottom",
    subtitle_size_scale: float = 1.0,
    subtitle_margin_v: Optional[int] = None,
    blur_box: Optional[Dict[str, Any]] = None,
    bg_volume: float = 0.0,
    playback_speed: float = 1.0,
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

    # Ensure Padauk font is ready
    fonts_dir = render_dir / "fonts"
    ensure_padauk_font(fonts_dir)

    # Check for Burmese subtitles or main subtitles
    srt_candidates = [
        job_dir / "transcript" / "transcript_burmese.srt",
        job_dir / "transcript" / "transcript_my.srt",
        job_dir / "transcript" / "transcript.srt",
    ]
    sub_srt = next((p for p in srt_candidates if p.exists() and p.stat().st_size > 10), None)

    # Generate ASS subtitle file for accurate HarfBuzz shaping
    local_ass = None
    ass_relative = None
    if sub_srt and burn_subtitles:
        local_ass = render_dir / "burn_subtitles.ass"
        generate_ass_subtitles(
            sub_srt,
            local_ass,
            video_width=video_width,
            video_height=video_height,
            placement=subtitle_placement,
            font_size_scale=subtitle_size_scale,
            margin_v=subtitle_margin_v,
        )
        ass_relative = "burn_subtitles.ass"

    # -----------------------------------------------------------------------
    # Build the complete FFmpeg filter_complex string from scratch.
    # Pipeline order: video speed → blur box → ASS subtitles
    #                 audio speed on voice (and optional bg audio)
    # -----------------------------------------------------------------------

    # Clamp playback speed to safe range: 0.25x – 4.0x
    speed = max(0.25, min(4.0, float(playback_speed or 1.0)))
    has_speed = abs(speed - 1.0) > 0.01
    has_blur  = bool(blur_box and blur_box.get("enabled", False))
    has_subs  = bool(ass_relative)

    # --- atempo helper (FFmpeg's atempo is clamped 0.5-2.0 per instance) ---
    def _atempo_chain(spd: float) -> str:
        if 0.5 <= spd <= 2.0:
            return f"atempo={spd:.6f}"
        elif spd > 2.0:
            # e.g. 3.0x → atempo=2.0,atempo=1.5
            return f"atempo=2.0,atempo={spd/2.0:.6f}"
        else:
            # e.g. 0.25x → atempo=0.5,atempo=0.5
            return f"atempo=0.5,atempo={spd*2.0:.6f}"

    # --- Build video filter chain ---
    video_parts = []
    current_v = "0:v"

    # Step 1 – video speed
    if has_speed:
        video_parts.append(f"[{current_v}]setpts=PTS/{speed:.6f}[v_speed]")
        current_v = "v_speed"

    # Step 2 – blur box
    if has_blur:
        try:
            x_pct = float(blur_box.get("x_pct", 0.78))
            y_pct = float(blur_box.get("y_pct", 0.04))
            w_pct = float(blur_box.get("w_pct", 0.18))
            h_pct = float(blur_box.get("h_pct", 0.08))
            if x_pct > 1.0: x_pct /= 100.0
            if y_pct > 1.0: y_pct /= 100.0
            if w_pct > 1.0: w_pct /= 100.0
            if h_pct > 1.0: h_pct /= 100.0
            strength = max(3, min(60, int(blur_box.get("strength", 16))))
            bx = max(0, min(video_width  - 4, int(x_pct * video_width)))
            by = max(0, min(video_height - 4, int(y_pct * video_height)))
            bw = max(4, min(video_width  - bx, int(w_pct * video_width)));  bw -= bw % 2
            bh = max(4, min(video_height - by, int(h_pct * video_height))); bh -= bh % 2
            if bw < 4: bw = 4
            if bh < 4: bh = 4
            next_v = "v_blur" if has_subs else "v_out"
            video_parts.append(
                f"[{current_v}]split=2[v_base][v_crop];"
                f"[v_crop]crop=w={bw}:h={bh}:x={bx}:y={by},avgblur=sizeX={strength}:sizeY={strength}[v_blurred];"
                f"[v_base][v_blurred]overlay=x={bx}:y={by}[{next_v}]"
            )
            current_v = next_v
        except Exception as e:
            print(f"[Warning] Blur box filter build failed: {e}. Skipping blur.")
            has_blur = False

    # Step 3 – ASS subtitles
    if has_subs:
        font_arg = f":fontsdir=fonts" if ass_relative else ""
        video_parts.append(f"[{current_v}]ass={ass_relative}{font_arg}[v_out]")
        current_v = "v_out"

    # If no video filters at all we still need a passthrough so mapping is consistent
    has_video_filter = bool(video_parts)
    if not has_video_filter:
        # No video processing needed – use simple stream copy mapping
        vout_tag = None
    else:
        vout_tag = current_v  # final video output label

    video_filter_str = ";".join(video_parts)

    # --- Build audio filter chain (PURE DUBBED NARRATION ONLY) ---
    # The source video's original audio (0:a) is NEVER used or mixed.
    # The final video exclusively uses the synthesized voice narration (1:a).
    atempo_str = _atempo_chain(speed) if has_speed else None

    print(f"[Render] Audio: 100% new dubbed narration only (source audio 0:a completely excluded)")
    print(f"[Render] speed={speed:.2f}x has_speed={has_speed} has_blur={has_blur} has_subs={has_subs}")
    print(f"[Render] video_filter={video_filter_str!r}")

    render_success = False

    # -----------------------------------------------------------------------
    # Primary Render: Pure dubbed audio (1:a) + video filter chain
    # -----------------------------------------------------------------------
    if has_speed:
        # Both video and dubbed audio are speed-adjusted together
        audio_speed_part = f"[1:a]{atempo_str}[a_out]"
        full_filter = f"{video_filter_str};{audio_speed_part}" if has_video_filter else audio_speed_part
        cmd = [
            FFMPEG_BIN, "-y",
            "-i", str(source_video),
            "-i", str(voice_audio),
            "-filter_complex", full_filter,
            "-map", f"[{vout_tag}]" if has_video_filter else "0:v:0",
            "-map", "[a_out]",
            "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k",
            "final.mp4",
        ]
    elif has_video_filter:
        cmd = [
            FFMPEG_BIN, "-y",
            "-i", str(source_video),
            "-i", str(voice_audio),
            "-filter_complex", video_filter_str,
            "-map", f"[{vout_tag}]",
            "-map", "1:a:0",
            "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k",
            "final.mp4",
        ]
    else:
        cmd = [
            FFMPEG_BIN, "-y",
            "-i", str(source_video),
            "-i", str(voice_audio),
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k",
            "final.mp4",
        ]

    res = subprocess.run(cmd, cwd=str(render_dir), capture_output=True, text=True)
    if res.returncode == 0 and final_output.exists() and final_output.stat().st_size > 1024:
        render_success = True
    else:
        print(f"[FFmpeg S1] failed (rc={res.returncode}): {res.stderr[-400:]}")

    # -----------------------------------------------------------------------
    # Strategy 3: Subtitle-only fallback (keep blur+speed if any, drop ASS)
    # Used only when ASS subtitle burn causes the filter to fail.
    # -----------------------------------------------------------------------
    if not render_success and has_subs:
        print("[FFmpeg S3] Retrying without ASS subtitles (keep blur+speed)...")
        # Rebuild video filter without ASS
        fallback_video_parts = [p for p in video_parts if "ass=" not in p]
        # Fix any dangling output label from the removed subtitle filter
        # The blur step outputs "v_blur"; remap it to "v_out"
        rebuilt = []
        for part in fallback_video_parts:
            rebuilt.append(part.replace("[v_blur]", "[v_out]"))
        fallback_video_str = ";".join(rebuilt)
        fallback_vout = "v_out" if rebuilt else None

        if has_speed and not has_blur:
            # Only speed filter was there — output was [v_speed], rename
            fallback_video_str = fallback_video_str.replace("[v_speed]", "[v_out]")
            fallback_vout = "v_out"
        has_fallback_vfilter = bool(rebuilt)

        if has_speed:
            audio_speed_part = f"[1:a]{atempo_str}[a_out]"
            full_filter = f"{fallback_video_str};{audio_speed_part}" if has_fallback_vfilter else audio_speed_part
            cmd3 = [
                FFMPEG_BIN, "-y",
                "-i", str(source_video),
                "-i", str(voice_audio),
                "-filter_complex", full_filter,
                "-map", f"[{fallback_vout}]" if has_fallback_vfilter else "0:v:0",
                "-map", "[a_out]",
                "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k",
                "final.mp4",
            ]
        elif has_fallback_vfilter:
            cmd3 = [
                FFMPEG_BIN, "-y",
                "-i", str(source_video),
                "-i", str(voice_audio),
                "-filter_complex", fallback_video_str,
                "-map", f"[{fallback_vout}]",
                "-map", "1:a:0",
                "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k",
                "final.mp4",
            ]
        else:
            cmd3 = [
                FFMPEG_BIN, "-y",
                "-i", str(source_video),
                "-i", str(voice_audio),
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k",
                "final.mp4",
            ]

        res3 = subprocess.run(cmd3, cwd=str(render_dir), capture_output=True, text=True)
        if res3.returncode == 0 and final_output.exists() and final_output.stat().st_size > 1024:
            render_success = True
        else:
            raise RuntimeError(f"FFmpeg rendering failed on all strategies: {res3.stderr[-300:]}")

    if not render_success:
        raise RuntimeError("FFmpeg rendering failed on all strategies.")

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

def generate_preview_frame(
    source_video: Optional[Path],
    output_image_path: Path,
    subtitle_placement: str = "bottom",
    subtitle_size_scale: float = 1.0,
    subtitle_margin_v: Optional[int] = None,
    blur_box: Optional[Dict[str, Any]] = None,
    sample_text: str = "ရုပ်ရှင်ဇာတ်လမ်း၏ အဓိကအခန်းတွင် မင်းသားက မြို့တော်သို့ ပြန်လည်ရောက်ရှိလာသည်",
    timestamp_sec: float = 2.0,
) -> Path:
    """
    Extracts or creates a sample frame, applies the logo blur box and subtitle styling,
    and returns a preview image so the user can verify adjustments before rendering.
    """
    output_image_path = Path(output_image_path).resolve()
    output_image_path.parent.mkdir(parents=True, exist_ok=True)
    temp_dir = output_image_path.parent / "preview_temp"
    temp_dir.mkdir(parents=True, exist_ok=True)

    fonts_dir = temp_dir / "fonts"
    ensure_padauk_font(fonts_dir)

    video_width = 1280
    video_height = 720
    has_valid_source = False

    if source_video and Path(source_video).exists():
        try:
            info = get_media_info(Path(source_video))
            v_stream = next((s for s in info.get("streams", []) if s.get("codec_type") == "video"), None)
            if v_stream:
                video_width = int(v_stream.get("width") or 1280)
                video_height = int(v_stream.get("height") or 720)
                has_valid_source = True
        except Exception:
            has_valid_source = False

    # Create temporary SRT and ASS file for sample subtitles
    sample_srt = temp_dir / "sample.srt"
    sample_srt.write_text(
        f"1\n00:00:00,000 --> 00:00:10,000\n{sample_text}\n",
        encoding="utf-8"
    )

    sample_ass = temp_dir / "sample.ass"
    generate_ass_subtitles(
        srt_path=sample_srt,
        ass_output_path=sample_ass,
        video_width=video_width,
        video_height=video_height,
        placement=subtitle_placement,
        font_size_scale=subtitle_size_scale,
        margin_v=subtitle_margin_v,
    )

    # Build filter graph
    filter_graph, vout = build_video_filter_chain(
        video_width=video_width,
        video_height=video_height,
        blur_box=blur_box,
        ass_file_relative="sample.ass",
        fonts_dir_relative="fonts",
        input_tag="0:v",
        output_tag="preview_out",
    )

    cmd = [FFMPEG_BIN, "-y"]
    if has_valid_source:
        cmd.extend([
            "-ss", str(max(0.0, float(timestamp_sec))),
            "-i", str(Path(source_video).resolve()),
        ])
    else:
        # Generate elegant dark gradient backdrop
        cmd.extend([
            "-f", "lavfi",
            "-i", f"color=c=0x18181b:size={video_width}x{video_height}:duration=1",
        ])

    cmd.extend([
        "-filter_complex", filter_graph,
        "-map", f"[{vout}]",
        "-vframes", "1",
        str(output_image_path),
    ])

    res = subprocess.run(cmd, cwd=str(temp_dir), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0 or not output_image_path.exists():
        # Fallback without subtitle filter if libass had an issue
        cmd_fallback = [
            FFMPEG_BIN, "-y",
            "-f", "lavfi", "-i", f"color=c=0x18181b:size={video_width}x{video_height}:duration=1",
            "-vframes", "1",
            str(output_image_path),
        ]
        subprocess.run(cmd_fallback, cwd=str(temp_dir), stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    return output_image_path
