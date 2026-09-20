import unittest
from unittest.mock import patch, MagicMock
from pathlib import Path
import sys
import requests

ROOT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT_DIR))

from worker.providers.voxcpm import VoxCPMProvider, VoiceProviderError

class TestVoxCPMProvider(unittest.TestCase):
    def setUp(self):
        self.provider = VoxCPMProvider(
            endpoint="https://colab-instance.ngrok-free.app",
            api_key="test_secret_key",
            timeout=5,
        )
        self.output_path = Path("storage/test_voice/test_voice.wav")
        self.output_path.parent.mkdir(parents=True, exist_ok=True)

    def tearDown(self):
        if self.output_path.exists():
            self.output_path.unlink()
        if self.output_path.parent.exists():
            try:
                self.output_path.parent.rmdir()
            except OSError:
                pass

    @patch("worker.providers.voxcpm.requests.post")
    def test_voxcpm_success_binary_audio(self, mock_post):
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.headers = {"Content-Type": "audio/wav"}
        # Provide minimal RIFF WAV header
        mock_resp.content = b"RIFF" + b"\x00" * 100
        mock_post.return_value = mock_resp

        result = self.provider.generate_speech(
            text="This is a test narration for Promovie recap.",
            output_path=self.output_path,
            language="en",
            voice="default",
        )

        self.assertTrue(self.output_path.exists())
        self.assertIn("localPath", result)
        mock_post.assert_called_once()

    @patch("worker.providers.voxcpm.requests.post")
    def test_voxcpm_timeout_raises_retryable_error(self, mock_post):
        mock_post.side_effect = requests.exceptions.Timeout("Connection timed out")

        with self.assertRaises(VoiceProviderError) as ctx:
            self.provider.generate_speech(
                text="Timeout test",
                output_path=self.output_path,
            )

        self.assertEqual(ctx.exception.code, "VOICE_PROVIDER_TIMEOUT")
        self.assertTrue(ctx.exception.retryable)

    @patch("worker.providers.voxcpm.requests.post")
    def test_voxcpm_500_error_raises_retryable(self, mock_post):
        mock_resp = MagicMock()
        mock_resp.status_code = 503
        mock_resp.text = "Service Unavailable"
        mock_post.return_value = mock_resp

        with self.assertRaises(VoiceProviderError) as ctx:
            self.provider.generate_speech(
                text="Error test",
                output_path=self.output_path,
            )

        self.assertEqual(ctx.exception.code, "VOICE_PROVIDER_ERROR")
        self.assertTrue(ctx.exception.retryable)

    @patch("worker.providers.voxcpm.requests.post")
    def test_voxcpm_malformed_response_raises_non_retryable(self, mock_post):
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.headers = {"Content-Type": "application/json"}
        mock_resp.json.return_value = {"error": "unknown_format"}
        mock_resp.content = b'{"error": "unknown_format"}'
        mock_post.return_value = mock_resp

        with self.assertRaises(VoiceProviderError) as ctx:
            self.provider.generate_speech(
                text="Malformed test",
                output_path=self.output_path,
            )

        self.assertEqual(ctx.exception.code, "VOICE_PROVIDER_MALFORMED_RESPONSE")
        self.assertFalse(ctx.exception.retryable)

if __name__ == "__main__":
    unittest.main()
