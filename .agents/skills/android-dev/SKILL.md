---
name: android-dev
description: >-
  Android development skill for the RecapMaster project. Use when the user
  asks about anything related to the Android app in android-recapmaster/,
  including: editing Kotlin/Compose UI, modifying the Chaquopy Python bridge
  (yt_downloader.py), FFmpegKit pipeline, WhisperEngine JNI, building/signing
  APKs, debugging Gradle, updating build.gradle.kts, fixing GitHub Actions
  CI/CD for APK builds, working with Edge TTS / Gemini API clients, or any
  Android-specific bugs (crashes, permissions, MediaStore, ExoPlayer, etc).
---

# Android Developer Skill — RecapMaster

## Project Overview

The Android app lives in `android-recapmaster/` and runs the **entire AI
pipeline on-device** using:

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Material3 |
| Language | Kotlin (JVM 17) |
| Python bridge | Chaquopy 3.10 (`com.chaquo.python`) |
| Video download | yt-dlp via Chaquopy Python (`yt_downloader.py`) |
| On-device transcription | Whisper.cpp JNI (`WhisperEngine.kt`) |
| Translation/Recap | Gemini REST API (`GeminiClient.kt`) |
| TTS voice | Edge-TTS WebSocket (`EdgeTtsClient.kt`) |
| Video rendering | FFmpegKit Full-GPL 8.1.7 |
| In-app preview | Media3 ExoPlayer 1.3.1 |
| Build system | Gradle 8.4, NDK 26.1.10909125, CMake 3.22.1 |
| Min SDK | 26 (Android 8.0) / Target SDK 34 |

---

## Key File Locations

```
android-recapmaster/
├── app/
│   ├── build.gradle.kts                          # Deps, Chaquopy config, ABI filters
│   ├── src/main/
│   │   ├── python/
│   │   │   └── yt_downloader.py                  # yt-dlp download bridge (Chaquopy)
│   │   ├── java/com/recapmaster/app/
│   │   │   ├── MainActivity.kt
│   │   │   ├── RecapApplication.kt
│   │   │   ├── pipeline/
│   │   │   │   └── RecapPipelineManager.kt       # Orchestrates all pipeline stages
│   │   │   ├── engine/
│   │   │   │   ├── FFmpegEngine.kt               # FFmpegKit video/audio ops
│   │   │   │   ├── WhisperEngine.kt              # JNI bridge to whisper.cpp
│   │   │   │   ├── SubtitleGenerator.kt          # .ass subtitle generation
│   │   │   │   └── BlurBoxConfig.kt              # Blur overlay config
│   │   │   ├── data/
│   │   │   │   ├── downloader/UrlDownloader.kt   # Calls yt_downloader.py via Chaquopy
│   │   │   │   ├── gemini/GeminiClient.kt        # Gemini Flash REST API
│   │   │   │   └── edgetts/EdgeTtsClient.kt      # Edge TTS WebSocket client
│   │   │   └── ui/                               # Compose screens
│   │   ├── cpp/CMakeLists.txt                    # whisper.cpp native build
│   │   └── assets/
│   │       ├── models/ggml-tiny.bin              # Whisper tiny model
│   │       └── fonts/Padauk-Regular.ttf          # Burmese subtitle font
├── build.gradle.kts                              # Root build config
└── settings.gradle.kts
```

---

## Pipeline Stages (RecapPipelineManager.kt)

```
DOWNLOADING        → yt_downloader.py (Chaquopy/yt-dlp)
EXTRACTING_AUDIO   → FFmpegEngine.extractSpeechAudio()
TRANSCRIBING       → WhisperEngine.transcribeWav()
TRANSLATING_SCRIPT → GeminiClient.translateToBurmese() + generateRecapScript()
DUBBING_VOICE      → EdgeTtsClient.synthesizeSpeech()
COMPOSING_VIDEO    → FFmpegEngine.renderFinalRecap()
COMPLETED          → exportToGallery() → MediaStore (Movies/RecapMaster/)
```

---

## CRITICAL RULES & Known Gotchas

### 1. yt-dlp / Chaquopy — NO ffmpeg inside Python sandbox

**NEVER use format combiners (+) or merge_output_format in yt_downloader.py.**
The Chaquopy Python runtime has NO access to ffmpeg binary.
Only use pre-merged single-file format selectors:

```python
# CORRECT — single pre-merged file, no muxing needed
FORMAT = (
    "best[ext=mp4][height<=720]"
    "/best[ext=mp4][height<=1080]"
    "/best[ext=mp4]"
    "/best[height<=720]"
    "/best"
)
ydl_opts = {
    "format": FORMAT,
    # DO NOT set merge_output_format
    # DO NOT set ffmpeg_location
}

# WRONG — triggers "ffmpeg is not installed" error
ydl_opts = {
    "format": "bestvideo+bestaudio",     # combiner needs ffmpeg
    "merge_output_format": "mp4",        # needs ffmpeg
}
```

Always wrap the entire yt_dlp.YoutubeDL block in try/except and return
structured JSON: {"success": bool, "error": str, ...}

### 2. FFmpegKit — for all on-device video/audio ops

All video rendering and audio extraction uses FFmpegKit (Kotlin FFmpegEngine.kt),
NOT subprocess calls. FFmpegKit is the only place ffmpeg exists on Android.

```kotlin
FFmpegKit.executeAsync(cmd) { session ->
    val returnCode = session.returnCode
    if (ReturnCode.isSuccess(returnCode)) { /* success */ }
}
```

### 3. Permissions

Required permissions in AndroidManifest.xml:
- READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE (API < 29)
- READ_MEDIA_VIDEO / READ_MEDIA_IMAGES (API >= 33)
- INTERNET & ACCESS_NETWORK_STATE
- FOREGROUND_SERVICE & FOREGROUND_SERVICE_DATA_SYNC (API >= 34)
- POST_NOTIFICATIONS (API >= 33)
- largeHeap="true" on <application>

Pipeline Execution:
The entire pipeline runs inside `RecapPipelineService` (foreground service with
`foregroundServiceType="dataSync"` and `ServiceCompat.startForeground`).
All pipeline stages must catch `Throwable` (not just `Exception`) to prevent
native `UnsatisfiedLinkError` or `OutOfMemoryError` from terminating the process.

For gallery export, always use MediaStore API (never direct /sdcard paths).
The exportToGallery() method handles this with IS_PENDING flag on Android Q+.

### 4. Chaquopy Configuration (build.gradle.kts)

```kotlin
chaquopy {
    defaultConfig {
        version = "3.10"       // Must match Python version in CI
        pip {
            install("yt-dlp")  // Add pip packages here
        }
    }
}
```

- Chaquopy Python files live in src/main/python/
- Call via: Python.getInstance().getModule("module_name").callAttr("func", args)
- Return values must be serializable (use JSON strings for complex objects)
- NEVER import server-side worker/ modules — sandbox only has src/main/python/ + pip

### 5. Native Build (Whisper.cpp JNI)

- CMakeLists.txt at src/main/cpp/CMakeLists.txt
- NDK version 26.1.10909125 — must match exactly in build.gradle.kts
- ABI filters: arm64-v8a, armeabi-v7a, x86_64
- C++ standard: c++17 with -fexceptions -frtti

### 6. WorkDir / Temp Files

- All temp files go in context.cacheDir (auto-purged by Android)
- Use File(context.cacheDir, "job_${timestamp}") for per-job isolation
- Always call workDir.deleteRecursively() in both success AND failure paths
- Final video exported to MediaStore, then temp file deleted

---

## Building the APK

### Local Build (from android-recapmaster/ directory)

```powershell
# Debug APK
.\gradlew assembleDebug

# Release APK (requires signing config)
.\gradlew assembleRelease

# Install directly to connected device
.\gradlew installDebug

# Clean build
.\gradlew clean assembleDebug
```

APK output: app/build/outputs/apk/debug/app-debug.apk

### GitHub Actions CI

Defined in .github/workflows/build-apk.yml. Triggers on push/PR to main.
Key env: Java 17, Gradle 8.4, Python 3.10, NDK 26.1.10909125, CMake 3.22.1
APK artifact: RecapMaster-debug-apk (14-day retention)

---

## Debugging Common Errors

| Error | Cause | Fix |
|---|---|---|
| "you have merging of multiple formats but ffmpeg is not installed" | yt_downloader.py uses + combiner or merge_output_format | Use best[ext=mp4] format only, remove merge_output_format |
| com.chaquo.python.PyException | Python error in Chaquopy module | Wrap in try/except, return JSON error string |
| INSTALL_FAILED_NO_MATCHING_ABIS | Wrong ABI filter | Check abiFilters in build.gradle.kts |
| UnsatisfiedLinkError | JNI library not found | Check CMakeLists.txt, NDK version, ABI |
| SecurityException on gallery save | Missing storage permission | Add permission + use MediaStore API |
| OutOfMemoryError on video | Large video loaded into heap | Stream via FFmpegKit, do not load bytes into RAM |
| Gradle sync fails on chaquopy | Python version mismatch | Match version = "3.10" with CI Python version |
| ModuleNotFoundError: yt_dlp | pip package not declared | Add install("yt-dlp") in chaquopy pip block |

---

## Dependency Versions

| Library | Version |
|---|---|
| Compose BOM | 2024.04.00 |
| FFmpegKit Full-GPL | 8.1.7 (dev.ffmpegkit-maintained) |
| Media3 ExoPlayer | 1.3.1 |
| OkHttp | 4.12.0 |
| Coroutines | 1.8.0 |
| Chaquopy | 3.10 |
| yt-dlp | latest (pip) |

---

## References

- Chaquopy Docs: https://chaquo.com/chaquopy/doc/current/
- FFmpegKit Android: https://github.com/arthenica/ffmpeg-kit
- yt-dlp format selectors: https://github.com/yt-dlp/yt-dlp#format-selection
- MediaStore API: https://developer.android.com/training/data-storage/shared/media
