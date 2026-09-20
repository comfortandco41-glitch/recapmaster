import argparse
import sys
import time
import traceback
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

# Ensure UTF-8 output encoding for Burmese and foreign scripts on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from worker.db import get_job, update_job_status
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

def process_job(job_id: str, exit_on_error: bool = True):
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

    print(f"[*] Starting Promovie Recap Pipeline for Job {job_id}")
    print(f"    Source URL: {url}")
    print(f"    Language: {lang}, Voice: {voice}, Sound Design Style: {sound_style}, Burn Subtitles: {burn_subs}")
    if voxcpm_endpoint:
        print(f"    Custom VoxCPM Colab Endpoint: {voxcpm_endpoint}")

    try:
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
            active_transcript = translate_transcript_to_burmese(job_id, transcript, target_lang="my")
        else:
            print("[4/8] Retaining original language transcript...")

        # Stage 5: Cinematic Movie Recap Script Generation
        print(f"[5/8] Generating movie recap narration script ({lang})...")
        script_file = generate_recap_script(job_id, active_transcript, language=lang)

        # Stage 6: Voice Generation via VoxCPM 2
        print(f"[6/8] Generating narration voice via VoxCPM 2{f' ({voxcpm_endpoint})' if voxcpm_endpoint else ''} [{sound_style}]...")
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
        )

        # Stage 7: Render Plan with Distributed Scenes Across Video
        print("[7/8] Building recap visual editing plan...")
        render_plan = build_render_plan(job_id, source_video, voice_audio)

        # Stage 8: Video Composition, Cinematic Effects & Burned Subtitles
        print(f"[8/8] Rendering final video with visual effects and subtitles via FFmpeg (Burn: {burn_subs})...")
        final_video = render_recap_video(job_id, source_video, voice_audio, render_plan, burn_subtitles=burn_subs)

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
    parser.add_argument("--cleanup", action="store_true", help="Run TTL cleanup for expired jobs")
    parser.add_argument("--daemon", action="store_true", help="Run background queue polling worker")
    args = parser.parse_args()

    if args.cleanup:
        cleaned = run_ttl_cleanup()
        print(f"Cleaned {len(cleaned)} expired jobs: {cleaned}")
        return

    if args.job_id:
        process_job(args.job_id)
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
