import os
import sys
import unittest
import subprocess
from pathlib import Path

# Add project root to path
ROOT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT_DIR))

from worker.config import FFMPEG_BIN, WORKSPACE_DIR
from worker.db import update_job_status, get_job, record_asset
from worker.pipeline.audio import extract_audio
from worker.pipeline.whisper import transcribe_audio
from worker.pipeline.recap import generate_recap_script
from worker.pipeline.voice import generate_voice_narration
from worker.pipeline.plan import build_render_plan
from worker.pipeline.render import render_recap_video
from worker.pipeline.cleanup import cleanup_job
from worker.pipeline.download import get_media_info

class TestMediaPipeline(unittest.TestCase):
    def setUp(self):
        self.job_id = f"test_job_{os.urandom(4).hex()}"
        self.job_dir = WORKSPACE_DIR / self.job_id
        self.source_dir = self.job_dir / "source"
        self.source_dir.mkdir(parents=True, exist_ok=True)
        self.source_file = self.source_dir / "source.mp4"

        # Generate a small 6-second synthetic test video with audio using FFmpeg
        cmd = [
            FFMPEG_BIN,
            "-y",
            "-f", "lavfi",
            "-i", "testsrc=size=1280x720:rate=30",
            "-f", "lavfi",
            "-i", "sine=frequency=1000:duration=6",
            "-t", "6",
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            str(self.source_file),
        ]
        subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)

        # Insert job record into database
        from worker.db import get_connection
        conn = get_connection()
        cur = conn.cursor()
        cur.execute(
            """INSERT INTO jobs (id, source_url, source_platform, status, progress, language, voice, created_at)
               VALUES (?, ?, ?, 'DOWNLOADED', 20, 'en', 'default', datetime('now'))""",
            (self.job_id, "https://www.youtube.com/watch?v=test", "YOUTUBE"),
        )
        conn.commit()
        conn.close()

    def test_complete_media_pipeline(self):
        print(f"\n--- Testing Complete Recap Pipeline on {self.job_id} ---")

        # 1. Audio extraction
        audio_wav = extract_audio(self.job_id, self.source_file)
        self.assertTrue(audio_wav.exists(), "audio.wav must exist")
        self.assertGreater(audio_wav.stat().st_size, 1000, "audio.wav must have content")

        audio_info = get_media_info(audio_wav)
        self.assertEqual(audio_info["streams"][0]["sample_rate"], "16000", "Audio must be 16kHz")
        self.assertEqual(audio_info["streams"][0]["channels"], 1, "Audio must be mono")

        # 2. Whisper transcription
        transcript = transcribe_audio(self.job_id, audio_wav, language="en")
        self.assertIn("segments", transcript)
        srt_file = self.job_dir / "transcript" / "transcript.srt"
        self.assertTrue(srt_file.exists(), "transcript.srt must exist")
        self.assertGreater(srt_file.stat().st_size, 10)

        # 3. Recap script generation
        script_file = generate_recap_script(self.job_id, transcript, language="en")
        self.assertTrue(script_file.exists(), "recap_script.txt must exist")
        with open(script_file, "r", encoding="utf-8") as f:
            script_text = f.read()
        self.assertGreater(len(script_text), 5)

        # 4. Voice narration (VoxCPM / voice provider)
        voice_wav = generate_voice_narration(self.job_id, script_file, language="en", voice="default")
        self.assertTrue(voice_wav.exists(), "voice.wav must exist")
        self.assertGreater(voice_wav.stat().st_size, 1000)

        # 5. Render plan
        plan_file = build_render_plan(self.job_id, self.source_file, voice_wav)
        self.assertTrue(plan_file.exists(), "render-plan.json must exist")

        # 6. Video composition and rendering
        final_mp4 = render_recap_video(self.job_id, self.source_file, voice_wav, plan_file)
        self.assertTrue(final_mp4.exists(), "final.mp4 must exist")
        self.assertGreater(final_mp4.stat().st_size, 10000, "final.mp4 must be valid size")

        # Verify output streams and duration
        final_info = get_media_info(final_mp4)
        streams = final_info.get("streams", [])
        has_v = any(s.get("codec_type") == "video" for s in streams)
        has_a = any(s.get("codec_type") == "audio" for s in streams)
        duration = float(final_info.get("format", {}).get("duration", 0))

        self.assertTrue(has_v, "Final video must contain video stream")
        self.assertTrue(has_a, "Final video must contain audio stream")
        self.assertGreater(duration, 0.5, "Duration must be greater than 0")

        # Verify job status in database is READY
        job = get_job(self.job_id)
        self.assertEqual(job["status"], "READY", "Job status must be READY")
        self.assertEqual(job["progress"], 100, "Progress must be 100%")

        print(f"[OK] Render verified: Duration={duration:.2f}s, Size={final_mp4.stat().st_size} bytes")

        # 7. Cleanup verification
        cleanup_job(self.job_id)
        self.assertFalse(self.job_dir.exists(), "Job workspace directory must be deleted after cleanup")
        deleted_job = get_job(self.job_id)
        self.assertEqual(deleted_job["status"], "DELETED", "Job status must be DELETED after cleanup")
        print("[OK] Cleanup verified: All temporary media files and directories purged")

if __name__ == "__main__":
    unittest.main()
