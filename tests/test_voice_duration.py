import os
import sys
import unittest
import soundfile as sf
from pathlib import Path

# Add project root to path
ROOT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT_DIR))

from worker.config import WORKSPACE_DIR
from worker.db import get_connection
from worker.pipeline.voice import generate_voice_narration

class TestVoiceDurationAdjustment(unittest.TestCase):
    def setUp(self):
        self.job_id = f"test_voice_{os.urandom(4).hex()}"
        self.job_dir = WORKSPACE_DIR / self.job_id
        self.transcript_dir = self.job_dir / "transcript"
        self.transcript_dir.mkdir(parents=True, exist_ok=True)
        self.script_file = self.transcript_dir / "recap_script.txt"

        # Register test job in db
        conn = get_connection()
        cur = conn.cursor()
        cur.execute(
            """INSERT INTO jobs (id, source_url, source_platform, status, progress, language, voice, created_at)
               VALUES (?, ?, ?, 'DOWNLOADED', 20, 'en', 'default', datetime('now'))""",
            (self.job_id, "https://www.youtube.com/watch?v=test", "YOUTUBE"),
        )
        conn.commit()
        conn.close()

    def test_voice_slowdown_to_match_longer_video(self):
        """Audio (~3-4s) should slow down to match a longer video duration (8.0s)."""
        self.script_file.write_text(
            "This is a quick summary of the scene.",
            encoding="utf-8",
        )

        target_duration = 8.0
        wav_path = generate_voice_narration(
            self.job_id,
            self.script_file,
            source_duration=target_duration,
            language="en",
            voice="default",
        )

        self.assertTrue(wav_path.exists(), "voice.wav must exist")
        self.assertGreater(wav_path.stat().st_size, 1024)

        info = sf.info(str(wav_path))
        print(f"Target duration: {target_duration}s, Actual duration: {info.duration:.2f}s")
        self.assertAlmostEqual(info.duration, target_duration, delta=0.08)

    def test_voice_speedup_to_match_shorter_video(self):
        """Audio (~5-7s) should speed up to match a shorter video duration (4.0s)."""
        job_id = f"test_voice_speedup_{os.urandom(4).hex()}"
        job_dir = WORKSPACE_DIR / job_id
        t_dir = job_dir / "transcript"
        t_dir.mkdir(parents=True, exist_ok=True)
        script_file = t_dir / "recap_script.txt"
        script_file.write_text(
            "In this recap, the main character is investigating the mysterious events that have unfolded across the city.",
            encoding="utf-8",
        )

        # Register test job in db
        conn = get_connection()
        cur = conn.cursor()
        cur.execute(
            """INSERT INTO jobs (id, source_url, source_platform, status, progress, language, voice, created_at)
               VALUES (?, ?, ?, 'DOWNLOADED', 20, 'en', 'default', datetime('now'))""",
            (job_id, "https://www.youtube.com/watch?v=test", "YOUTUBE"),
        )
        conn.commit()
        conn.close()

        target_duration = 4.0
        wav_path = generate_voice_narration(
            job_id,
            script_file,
            source_duration=target_duration,
            language="en",
            voice="default",
        )

        self.assertTrue(wav_path.exists(), "voice.wav must exist")
        self.assertGreater(wav_path.stat().st_size, 1024)

        info = sf.info(str(wav_path))
        print(f"Target duration: {target_duration}s, Actual duration: {info.duration:.2f}s")
        self.assertAlmostEqual(info.duration, target_duration, delta=0.08)

    def tearDown(self):
        import shutil
        if self.job_dir.exists():
            try:
                shutil.rmtree(self.job_dir)
            except Exception:
                pass

if __name__ == "__main__":
    unittest.main()
