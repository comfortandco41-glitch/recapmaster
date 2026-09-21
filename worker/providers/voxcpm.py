import os
import requests
import json
import base64
import subprocess
import shutil
from pathlib import Path
from typing import Dict, Any, Optional

from worker.config import VOXCPM_ENDPOINT, VOXCPM_API_KEY, VOXCPM_TIMEOUT_SECONDS, FFMPEG_BIN

class VoiceProviderError(Exception):
    def __init__(self, code: str, message: str, retryable: bool = True):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable

class VoiceProvider:
    def generate_speech(
        self,
        text: str,
        output_path: Path,
        language: str = "en",
        voice: str = "default",
    ) -> Dict[str, Any]:
        raise NotImplementedError

class VoxCPMProvider(VoiceProvider):
    """
    VoxCPM 2 Voice Generation Provider communicating with an external
    inference endpoint (Google Colab Gradio, FastAPI, or dedicated GPU server).
    """

    def __init__(
        self,
        endpoint: Optional[str] = None,
        api_key: Optional[str] = None,
        timeout: Optional[int] = None,
    ):
        self.endpoint = (endpoint or VOXCPM_ENDPOINT).rstrip("/")
        self.api_key = api_key or VOXCPM_API_KEY
        self.timeout = timeout or VOXCPM_TIMEOUT_SECONDS

    def generate_speech(
        self,
        text: str,
        output_path: Path,
        language: str = "en",
        voice: str = "default",
        rate: Optional[str] = None,
        pitch: Optional[str] = None,
        volume: Optional[str] = None,
    ) -> Dict[str, Any]:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        if output_path.exists() and output_path.stat().st_size > 1024:
            return {"status": "success", "file": str(output_path), "cached": True}

        # If user explicitly chose an Edge Neural voice (not "default" or "voxcpm"), use Edge TTS directly
        is_edge_voice = voice and voice not in ("default", "voxcpm", "standard") and ("Neural" in voice or voice.startswith("my-") or voice.startswith("en-"))
        if is_edge_voice or not self.endpoint:
            return self._fallback_tts(
                text=text,
                output_path=output_path,
                language=language,
                voice=voice,
                rate=rate,
                pitch=pitch,
                volume=volume,
            )

        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, audio/wav, audio/mpeg, text/event-stream, */*",
        }
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        # Strategy 1: Check if endpoint is a Gradio 5/6 App (typical in Google Colab)
        if "gradio" in self.endpoint or self._is_gradio_endpoint():
            try:
                return self._call_gradio_generate(text, output_path, headers)
            except Exception as ge:
                print(f"[VoxCPM] Gradio call error: {ge}. Trying standard REST / fallback...")

        # Strategy 2: Standard REST candidates (/generate, /api/generate, /v1/audio/speech, etc.)
        candidate_paths = [
            "",  # user provided exact path
            "/generate",
            "/api/generate",
            "/v1/audio/speech",
            "/tts",
        ]

        last_error = None
        for path in candidate_paths:
            target_url = self.endpoint if not path else f"{self.endpoint}{path}"
            payload = {
                "text": text,
                "language": language,
                "voice": voice,
            }

            try:
                resp = requests.post(target_url, json=payload, headers=headers, timeout=self.timeout)
                if resp.status_code == 200:
                    return self._process_audio_response(resp, output_path)
                elif resp.status_code == 404:
                    continue
                else:
                    last_error = f"HTTP {resp.status_code}: {resp.text[:150]}"
            except VoiceProviderError:
                raise
            except requests.exceptions.Timeout:
                raise VoiceProviderError(
                    code="VOICE_PROVIDER_TIMEOUT",
                    message=f"VoxCPM endpoint timed out after {self.timeout}s.",
                    retryable=True,
                )
            except requests.exceptions.ConnectionError:
                return self._fallback_tts(text, output_path, language, voice=voice, rate=rate, pitch=pitch, volume=volume)
            except Exception as e:
                last_error = str(e)

        # Fallback to local Edge synthesizer if external server failed
        print(f"[VoxCPM] Remote endpoint returned ({last_error or 'endpoint unreachable'}). Falling back to Edge TTS studio voice...")
        return self._fallback_tts(text, output_path, language, voice=voice, rate=rate, pitch=pitch, volume=volume)

    def _is_gradio_endpoint(self) -> bool:
        try:
            r = requests.get(f"{self.endpoint}/gradio_api/info", timeout=3)
            return r.status_code == 200
        except Exception:
            return False

    def _call_gradio_generate(self, text: str, output_path: Path, headers: Dict[str, str]) -> Dict[str, Any]:
        """
        Executes Gradio 5/6 API protocol used by VoxCPM 2 Google Colab notebooks.
        """
        call_url = f"{self.endpoint}/gradio_api/call/generate"
        payload = {
            "data": [
                text,       # text
                "",         # control
                None,       # audio (no reference audio for generic narration)
                False,      # use_prompt_text
                "",         # prompt_text
                2.0,        # cfg_value
                True,       # normalize
                False,      # denoise
                6,          # inference_timesteps (optimized for speed & quality)
                True,       # retry_badcase
                0,          # consistency_seed
            ]
        }

        call_res = requests.post(call_url, json=payload, headers=headers, timeout=15)
        if call_res.status_code != 200:
            raise RuntimeError(f"Gradio call returned HTTP {call_res.status_code}: {call_res.text[:200]}")

        event_id = call_res.json().get("event_id")
        if not event_id:
            raise RuntimeError("Missing event_id in Gradio call response.")

        # Stream SSE event until completion
        stream_url = f"{self.endpoint}/gradio_api/call/generate/{event_id}"
        stream_res = requests.get(stream_url, stream=True, timeout=self.timeout)
        if stream_res.status_code != 200:
            raise RuntimeError(f"Gradio event stream returned HTTP {stream_res.status_code}")

        file_url = None
        for line in stream_res.iter_lines(decode_unicode=True):
            if not line:
                continue
            if line.startswith("data:"):
                data_str = line[5:].strip()
                try:
                    parsed = json.loads(data_str)
                    if isinstance(parsed, list) and len(parsed) > 0:
                        first_item = parsed[0]
                        if isinstance(first_item, dict) and "url" in first_item:
                            file_url = first_item["url"]
                            break
                        elif isinstance(first_item, str) and first_item.startswith("http"):
                            file_url = first_item
                            break
                except Exception:
                    pass

        if not file_url:
            raise RuntimeError("Could not locate audio file URL in Gradio event stream.")

        # Download the audio file
        audio_download = requests.get(file_url, timeout=30)
        if audio_download.status_code != 200 or len(audio_download.content) < 100:
            raise RuntimeError(f"Failed to download audio from {file_url}: HTTP {audio_download.status_code}")

        with open(output_path, "wb") as f:
            f.write(audio_download.content)

        return self._normalize_wav(output_path)

    def _process_audio_response(self, resp: requests.Response, output_path: Path) -> Dict[str, Any]:
        content_type = resp.headers.get("Content-Type", "")

        # 1. Binary audio stream
        if "audio" in content_type or resp.content.startswith(b"RIFF"):
            with open(output_path, "wb") as f:
                f.write(resp.content)
        else:
            # 2. JSON response ({"audio_url": ...} or {"audio_base64": ...})
            try:
                data = resp.json()
                if "audio_base64" in data:
                    raw_bytes = base64.b64decode(data["audio_base64"])
                    with open(output_path, "wb") as f:
                        f.write(raw_bytes)
                elif "audio_url" in data:
                    audio_res = requests.get(data["audio_url"], timeout=60)
                    with open(output_path, "wb") as f:
                        f.write(audio_res.content)
                elif "data" in data and isinstance(data["data"], list) and len(data["data"]) > 0:
                    item = data["data"][0]
                    target_link = item.get("url") if isinstance(item, dict) else item
                    audio_res = requests.get(target_link, timeout=60)
                    with open(output_path, "wb") as f:
                        f.write(audio_res.content)
                else:
                    raise ValueError("Unrecognized JSON audio structure")
            except Exception as e:
                raise VoiceProviderError(
                    code="VOICE_PROVIDER_MALFORMED_RESPONSE",
                    message=f"Failed to decode audio: {e}",
                    retryable=False,
                )

        return self._normalize_wav(output_path)

    def _fallback_tts(
        self,
        text: str,
        output_path: Path,
        language: str,
        voice: str = "default",
        rate: Optional[str] = None,
        pitch: Optional[str] = None,
        volume: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Resilient local fallback using Edge TTS neural voices when external Colab endpoint is offline,
        with studio mastering to eliminate robotic artifacts.
        """
        import sys
        temp_mp3 = output_path.with_suffix(".mp3")
        
        # Select appropriate natural voice for the language if not explicitly provided
        selected_voice = voice
        if not selected_voice or selected_voice in ("default", "voxcpm", "standard"):
            if language in ("my", "burmese"):
                selected_voice = "my-MM-NilarNeural"  # Nilar is rich and natural for narration
            elif language in ("zh", "chinese", "mandarin"):
                selected_voice = "zh-CN-YunxiNeural"
            elif language in ("es", "spanish"):
                selected_voice = "es-ES-AlvaroNeural"
            elif language in ("ja", "japanese"):
                selected_voice = "ja-JP-KeitaNeural"
            elif language in ("id", "indonesian"):
                selected_voice = "id-ID-ArdiNeural"
            else:
                selected_voice = "en-US-ChristopherNeural"

        # Rate and pitch adjustments to make speech sound natural and human
        actual_rate = rate or "+10%"
        actual_pitch = pitch or "-2Hz"
        actual_vol = volume or "+0%"

        print(f"[Voice Studio] Using Edge Neural TTS ({selected_voice}) [Rate: {actual_rate}, Pitch: {actual_pitch}]...")
        cmd = [
            sys.executable,
            "-m", "edge_tts",
            "--text", text,
            "--voice", selected_voice,
            f"--rate={actual_rate}",
            f"--pitch={actual_pitch}",
            f"--volume={actual_vol}",
            "--write-media", str(temp_mp3),
        ]
        try:
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
            if not temp_mp3.exists() or temp_mp3.stat().st_size == 0:
                raise RuntimeError(f"Edge TTS produced empty audio: {res.stderr}")

            conv_cmd = [
                FFMPEG_BIN,
                "-y",
                "-i", str(temp_mp3),
                "-ac", "1",
                "-ar", "24000",
                str(output_path),
            ]
            subprocess.run(conv_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
            if temp_mp3.exists():
                temp_mp3.unlink()
        except Exception as e:
            if temp_mp3.exists():
                try:
                    temp_mp3.unlink()
                except Exception:
                    pass
            raise VoiceProviderError(
                code="VOICE_SYNTHESIS_FAILED",
                message=f"Narration voice generation failed on both VoxCPM and Edge TTS: {e}",
                retryable=True,
            )

        return self._normalize_wav(output_path)

    def _normalize_wav(self, wav_path: Path) -> Dict[str, Any]:
        norm_path = wav_path.parent / "voice_norm.wav"
        # Studio vocal enhancement: gentle highpass to eliminate sub-rumble, warm low-mid presence,
        # smooth de-harshing, gentle broadcast compression, and EBU R128 loudness normalization
        vocal_filter = (
            "highpass=f=75,lowpass=f=12000,"
            "equalizer=f=220:t=q:w=1.2:g=2.5,"
            "equalizer=f=3300:t=q:w=1.5:g=1.8,"
            "acompressor=threshold=0.12:ratio=3:attack=15:release=120,"
            "loudnorm=I=-16:TP=-1.5:LRA=10"
        )
        cmd = [
            FFMPEG_BIN,
            "-y",
            "-i", str(wav_path),
            "-filter:a", vocal_filter,
            "-ac", "1",
            "-ar", "24000",
            str(norm_path),
        ]
        try:
            subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
            if norm_path.exists() and norm_path.stat().st_size > 0:
                shutil.move(str(norm_path), str(wav_path))
        except Exception:
            pass

        return {
            "localPath": str(wav_path),
            "sizeBytes": wav_path.stat().st_size,
        }
