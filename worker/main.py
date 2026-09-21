import argparse
import sys
import time
import traceback
import json
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

# Ensure UTF-8 output encoding for Burmese and foreign scripts on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from typing import Optional, Dict, Any, List
from worker.config import GEMINI_API_KEY
from worker.db import get_job, update_job_status, get_connection
from worker.pipeline.download import download_source, get_media_info
from worker.pipeline.audio import extract_audio
from worker.pipeline.whisper import transcribe_audio
from worker.pipeline.translate import translate_transcript_to_burmese
from worker.pipeline.recap import generate_recap_script
from worker.pipeline.voice import generate_voice_narration
from worker.pipeline.plan import build_render_plan
from worker.pipeline.render import render_recap_video
from worker.pipeline.cleanup import run_ttl_cleanup
from worker.providers.voxcpm import VoiceProviderError

def parse_blur_box(val: Any) -> Optional[Dict[str, Any]]:
    if not val:
        return None
    if isinstance(val, dict):
        return val
    if not isinstance(val, str):
        return None
    val = val.strip()
    if not val or val in ("null", "None", "{}"):
        return None

    import json
    try:
        data = json.loads(val)
        if isinstance(data, dict):
            return data
    except Exception:
        pass

    try:
        import ast
        py_str = val.replace("true", "True").replace("false", "False").replace("null", "None")
        data = ast.literal_eval(py_str)
        if isinstance(data, dict):
            return data
    except Exception:
        pass

    try:
        import re
        result = {}
        if re.search(r"enabled\s*[:=]\s*true", val, re.I):
            result["enabled"] = True
        elif re.search(r"enabled\s*[:=]\s*false", val, re.I):
            result["enabled"] = False

        for k in ["x_pct", "y_pct", "w_pct", "h_pct", "strength"]:
            m = re.search(r"\b" + k + r"\b\s*[:=]\s*([0-9.]+)", val)
            if m:
                v = m.group(1)
                result[k] = float(v) if "." in v else int(v)
        if result and ("x_pct" in result or result.get("enabled")):
            return result
    except Exception:
        pass

    return None

def process_job(
    job_id: str,
    exit_on_error: bool = True,
    rerender_only: bool = False,
    auto_render: bool = False,
    override_placement: Optional[str] = None,
    override_size: Optional[float] = None,
    override_margin: Optional[int] = None,
    override_blur_config: Optional[str] = None,
    override_speed: Optional[float] = None,
):
    job = get_job(job_id)
    if not job:
        print(f"Error: Job {job_id} not found.")
        if exit_on_error:
            sys.exit(1)
        raise ValueError(f"Job {job_id} not found")

    url = job["source_url"]
    lang = job.get("language") or "my"
    voice = job.get("voice") or "default"
    sound_style = job.get("sound_style") or "cinematic_recap"
    burn_subs = bool(job.get("burn_subtitles", 1))
    voxcpm_endpoint = job.get("voxcpm_endpoint")
    voxcpm_api_key = job.get("voxcpm_api_key")
    gemini_api_key = job.get("gemini_api_key") or GEMINI_API_KEY

    sub_placement = override_placement or job.get("subtitle_placement") or "bottom"
    sub_size = float(override_size if override_size is not None else (job.get("subtitle_size") or 1.0))
    sub_margin_v = override_margin if override_margin is not None else job.get("subtitle_margin_v")
    blur_box_raw = override_blur_config if override_blur_config is not None else job.get("blur_box_config")
    blur_box = parse_blur_box(blur_box_raw)

    voice_rate = job.get("voice_rate") or "+10%"
    voice_pitch = job.get("voice_pitch") or "-2Hz"
    bg_music_volume = 0.0
    playback_speed = float(override_speed if override_speed is not None else (job.get("playback_speed") or 1.0))

    job_dir = ROOT_DIR / "storage" / "jobs" / job_id
    if not job_dir.exists():
        from worker.config import WORKSPACE_DIR
        job_dir = WORKSPACE_DIR / job_id

    # Check for custom_settings.json file written by rerender API route
    settings_file = job_dir / "render" / "custom_settings.json"
    if settings_file.exists():
        try:
            with open(settings_file, "r", encoding="utf-8") as sf:
                csettings = json.load(sf)
            if csettings.get("playbackSpeed") is not None:
                playback_speed = float(csettings["playbackSpeed"])
            if csettings.get("blurBox") is not None:
                blur_box = parse_blur_box(csettings["blurBox"])
            if csettings.get("subtitlePlacement"):
                sub_placement = csettings["subtitlePlacement"]
            if csettings.get("subtitleSize") is not None:
                sub_size = float(csettings["subtitleSize"])
            if csettings.get("subtitleMarginV") is not None:
                sub_margin_v = int(csettings["subtitleMarginV"])
            print(f"[*] Loaded re-render settings from {settings_file.name}: speed={playback_speed}x, blur={bool(blur_box and blur_box.get('enabled'))}")
        except Exception as se:
            print(f"[Warning] Failed reading custom_settings.json: {se}")

    # Persist any active settings to the database
    try:
        conn = get_connection()
        cur = conn.cursor()
        cur.execute(
            """UPDATE jobs SET
               subtitle_placement = COALESCE(?, subtitle_placement),
               subtitle_size = COALESCE(?, subtitle_size),
               subtitle_margin_v = COALESCE(?, subtitle_margin_v),
               blur_box_config = COALESCE(?, blur_box_config),
               playback_speed = COALESCE(?, playback_speed)
               WHERE id = ?""",
            (
                sub_placement,
                sub_size,
                sub_margin_v,
                json.dumps(blur_box) if blur_box else None,
                playback_speed,
                job_id,
            )
        )
        conn.commit()
        conn.close()
    except Exception as e:
        print(f"[Warning] Could not persist settings to DB: {e}")

    print(f"[*] Starting Promovie Recap Pipeline for Job {job_id}")
    print(f"    Source URL: {url}")
    print(f"    Language: {lang}, Voice: {voice} (Rate: {voice_rate}, Pitch: {voice_pitch}), Sound Design: {sound_style}")
    print(f"    Subtitle Placement: {sub_placement}, Size: {sub_size}x, Blur Box: {bool(blur_box and blur_box.get('enabled'))}, Speed: {playback_speed}x")
    if voxcpm_endpoint:
        print(f"    Custom VoxCPM Colab Endpoint: {voxcpm_endpoint}")
    if gemini_api_key:
        print(f"    Gemini Translation API: Configured (Key: {gemini_api_key[:4]}...{gemini_api_key[-4:] if len(gemini_api_key) > 8 else ''})")
    else:
        print("    Gemini Translation API: Not provided (using fallback Web Translator)")

    try:
        source_dir = job_dir / "source"
        source_video = source_dir / "source.mp4"
        voice_audio = job_dir / "voice" / "voice.wav"
        
        # Check candidate paths for render plan
        render_plan = job_dir / "render" / "render-plan.json"
        if not render_plan.exists():
            render_plan = job_dir / "plan" / "render_plan.json"
        if not render_plan.exists():
            render_plan = job_dir / "render" / "render_plan.json"

        # If fast re-render requested, compose final video directly
        if rerender_only:
            if not source_video.exists():
                raise RuntimeError(f"Cannot re-render: source video missing at {source_video}")
            if not voice_audio.exists():
                raise RuntimeError(f"Cannot re-render: dubbed voice audio missing at {voice_audio}")
            if not render_plan.exists():
                render_plan = build_render_plan(job_id, source_video, voice_audio)

            print("[⚡ Fast Re-render] Re-composing video with updated subtitle, blur, and audio settings...")
            final_video = render_recap_video(
                job_id,
                source_video,
                voice_audio,
                render_plan,
                burn_subtitles=burn_subs,
                subtitle_placement=sub_placement,
                subtitle_size_scale=sub_size,
                subtitle_margin_v=sub_margin_v,
                blur_box=blur_box,
                bg_volume=bg_music_volume,
                playback_speed=playback_speed,
            )
            print(f"[OK] Fast re-render complete! Output: {final_video}")
            return final_video

        # Stage 1: Download Source
        print("[1/8] Downloading source video...")
        source_video = download_source(job_id, url)
        source_info = get_media_info(source_video)
        source_duration = float(source_info.get("format", {}).get("duration", 0.0))

        # Stage 2: Audio Extraction
        print("[2/8] Extracting speech audio...")
        source_audio = extract_audio(job_id, source_video)

        # Stage 3: Whisper Transcription (Real Dialogue from Video)
        print("[3/8] Transcribing original audio with Whisper...")
        transcript = transcribe_audio(job_id, source_audio, language="auto")

        # Stage 4: Burmese Translation (Subtitles & SRT)
        active_transcript = transcript
        if lang == "my" or lang == "burmese":
            print("[4/8] Translating real subtitles to Burmese...")
            active_transcript = translate_transcript_to_burmese(job_id, transcript, target_lang="my", api_key=gemini_api_key)
        else:
            print("[4/8] Retaining original language transcript...")

        # Stage 5: Cinematic Movie Recap Script Generation
        print(f"[5/8] Generating movie recap narration script ({lang})...")
        script_file = generate_recap_script(job_id, active_transcript, language=lang, api_key=gemini_api_key)

        # Stage 6: Voice Generation via VoxCPM 2 / Enhanced Audio Engine
        print(f"[6/8] Generating narration voice via VoxCPM 2{f' ({voxcpm_endpoint})' if voxcpm_endpoint else ''} [{sound_style}] (Rate: {voice_rate}, Pitch: {voice_pitch})...")
        voice_audio = generate_voice_narration(
            job_id,
            script_file,
            transcript_data=active_transcript,
            source_duration=source_duration,
            language=lang,
            voice=voice,
            sound_style=sound_style,
            endpoint=voxcpm_endpoint,
            api_key=voxcpm_api_key,
            voice_rate=voice_rate,
            voice_pitch=voice_pitch,
        )

        # Stage 7: Render Plan with Distributed Scenes Across Video
        print("[7/8] Building recap visual editing plan...")
        render_plan = build_render_plan(job_id, source_video, voice_audio)

        # Pause at VOICE_READY so user can review dubbed audio and configure subtitle & blur box settings
        if not auto_render and not rerender_only:
            update_job_status(
                job_id,
                status="VOICE_READY",
                progress=75,
                stage="VOICE_READY",
                event_message="Voice narration dubbed! Subtitle placement and logo blur removal settings are now ready.",
            )
            print(f"[OK] Job {job_id}: Voice & video dubbed! Paused at VOICE_READY for subtitle & blur customization.")
            return str(voice_audio)

        # Stage 8: Video Composition, Cinematic Effects & Burned Subtitles
        print(f"[8/8] Rendering final video with visual effects and subtitles via FFmpeg (Burn: {burn_subs})...")
        final_video = render_recap_video(
            job_id,
            source_video,
            voice_audio,
            render_plan,
            burn_subtitles=burn_subs,
            subtitle_placement=sub_placement,
            subtitle_size_scale=sub_size,
            subtitle_margin_v=sub_margin_v,
            blur_box=blur_box,
            bg_volume=bg_music_volume,
            playback_speed=playback_speed,
        )

        print(f"[OK] Job {job_id} completed successfully! Output: {final_video}")
        return final_video

    except VoiceProviderError as vpe:
        print(f"[!] Voice provider error on job {job_id}: {vpe}")
        update_job_status(
            job_id,
            status="FAILED",
            error_code=vpe.code,
            error_message=vpe.message,
            stage="VOICE_GENERATION",
            event_message=f"Voice provider failed: {vpe.message}",
        )
        if exit_on_error:
            sys.exit(1)
        raise
    except Exception as e:
        err_msg = str(e) or type(e).__name__
        tb = traceback.format_exc()
        print(f"[!] Pipeline error on job {job_id}:\n{tb}")
        update_job_status(
            job_id,
            status="FAILED",
            error_code="PIPELINE_ERROR",
            error_message=err_msg[:300],
            stage="FAILED",
            event_message=f"Processing failed: {err_msg[:150]}",
        )
        if exit_on_error:
            sys.exit(1)
        raise

def main():
    parser = argparse.ArgumentParser(description="Promovie Media Processing Worker")
    parser.add_argument("--job-id", type=str, help="Process a specific job ID")
    parser.add_argument("--rerender-only", action="store_true", help="Fast re-render using existing audio/plan")
    parser.add_argument("--auto-render", action="store_true", help="Automatically complete final video rendering without pausing at VOICE_READY")
    parser.add_argument("--cleanup", action="store_true", help="Run TTL cleanup for expired jobs")
    parser.add_argument("--daemon", action="store_true", help="Run background queue polling worker")
    parser.add_argument("--preview", action="store_true", help="Generate single frame preview image")
    parser.add_argument("--placement", type=str, default="bottom", help="Subtitle placement: bottom, top, middle")
    parser.add_argument("--size", type=float, default=1.0, help="Subtitle font size scale")
    parser.add_argument("--margin", type=int, default=30, help="Subtitle vertical edge margin")
    parser.add_argument("--blur-config", type=str, default="", help="JSON string for blur box configuration")
    parser.add_argument("--speed", type=float, default=None, help="Override playback speed (e.g. 0.5, 1.25)")
    parser.add_argument("--output-preview", type=str, default="", help="Target preview image path")
    args = parser.parse_args()

    if args.preview:
        import json
        from worker.pipeline.render import generate_preview_frame
        from worker.config import WORKSPACE_DIR
        source_vid = None
        if args.job_id:
            candidate = WORKSPACE_DIR / args.job_id / "source" / "source.mp4"
            if candidate.exists():
                source_vid = candidate
        blur_box = None
        if args.blur_config:
            try:
                blur_box = json.loads(args.blur_config)
            except Exception:
                pass
        out_target = Path(args.output_preview) if args.output_preview else (WORKSPACE_DIR / (args.job_id or "temp") / "preview.png")
        generate_preview_frame(
            source_video=source_vid,
            output_image_path=out_target,
            subtitle_placement=args.placement,
            subtitle_size_scale=args.size,
            subtitle_margin_v=args.margin,
            blur_box=blur_box,
        )
        print(f"PREVIEW_OK:{out_target}")
        return

    if args.cleanup:
        cleaned = run_ttl_cleanup()
        print(f"Cleaned {len(cleaned)} expired jobs: {cleaned}")
        return

    if args.job_id:
        process_job(
            args.job_id,
            rerender_only=args.rerender_only,
            auto_render=args.auto_render,
            override_placement=args.placement if "--placement" in sys.argv else None,
            override_size=args.size if "--size" in sys.argv else None,
            override_margin=args.margin if "--margin" in sys.argv else None,
            override_blur_config=args.blur_config if "--blur-config" in sys.argv and args.blur_config else None,
            override_speed=args.speed if args.speed is not None else None,
        )
        return

    if args.daemon:
        print("[*] Starting Promovie Worker Daemon...")
        while True:
            try:
                run_ttl_cleanup()
                time.sleep(10)
            except KeyboardInterrupt:
                break

    parser.print_help()

if __name__ == "__main__":
    main()
