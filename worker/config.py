import os
import shutil
from pathlib import Path
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env")

WORKSPACE_DIR = BASE_DIR / "storage" / "jobs"
WORKSPACE_DIR.mkdir(parents=True, exist_ok=True)

# Detect FFmpeg executable
def find_ffmpeg() -> str:
    # 1. Check environment variable
    if os.environ.get("FFMPEG_PATH") and os.path.exists(os.environ["FFMPEG_PATH"]):
        return os.environ["FFMPEG_PATH"]
    # 2. Check system PATH
    sys_ffmpeg = shutil.which("ffmpeg")
    if sys_ffmpeg:
        return sys_ffmpeg
    # 3. Check known WinGet paths
    known_paths = [
        r"C:\Users\BB\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg.Essentials_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1.1-essentials_build\bin\ffmpeg.exe",
        r"C:\Users\BB\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0-full_build\bin\ffmpeg.exe"
    ]
    for p in known_paths:
        if os.path.exists(p):
            return p
    return "ffmpeg"

def find_ffprobe() -> str:
    if os.environ.get("FFPROBE_PATH") and os.path.exists(os.environ["FFPROBE_PATH"]):
        return os.environ["FFPROBE_PATH"]
    sys_ffprobe = shutil.which("ffprobe")
    if sys_ffprobe:
        return sys_ffprobe
    known_paths = [
        r"C:\Users\BB\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg.Essentials_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1.1-essentials_build\bin\ffprobe.exe",
        r"C:\Users\BB\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0-full_build\bin\ffprobe.exe"
    ]
    for p in known_paths:
        if os.path.exists(p):
            return p
    return "ffprobe"

FFMPEG_BIN = find_ffmpeg()
FFPROBE_BIN = find_ffprobe()

# VoxCPM Provider Configuration
VOXCPM_ENDPOINT = os.environ.get("VOXCPM_ENDPOINT", "http://localhost:8000")
VOXCPM_API_KEY = os.environ.get("VOXCPM_API_KEY", "")
VOXCPM_TIMEOUT_SECONDS = int(os.environ.get("VOXCPM_TIMEOUT_SECONDS", "300"))

# Gemini API Configuration for Translation & Scripting
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY", "")
GEMINI_MODEL = (os.environ.get("GEMINI_MODEL") or "").strip()

# Resource limits
MAX_SOURCE_SIZE_MB = int(os.environ.get("MAX_SOURCE_SIZE_MB", "2000"))
MAX_VIDEO_DURATION_SECONDS = int(os.environ.get("MAX_VIDEO_DURATION_SECONDS", "7200"))
JOB_TTL_MINUTES = int(os.environ.get("JOB_TTL_MINUTES", "60"))
