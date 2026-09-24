# RecapMaster Android AI Studio

Standalone Android mobile application that executes the complete movie recap pipeline directly on-device with **on-device Whisper**, **on-device FFmpeg**, **embedded Python yt-dlp (Chaquopy)**, **Gemini Flash translation**, **Google Gemini AI Voice & Edge TTS Burmese dubbing**, and **Voice Profiles**.

---

## Architecture

| Component | Technology | Execution |
| :--- | :--- | :--- |
| **YouTube & Bilibili Downloader** | `yt-dlp` via **Chaquopy** (`com.chaquo.python`) | On-Device Python |
| **Speech Recognition** | **whisper.cpp** via Android NDK / JNI (`libwhisper.so`) | 100% Offline On-Device CPU (NEON) |
| **Translation & Script** | **Google Gemini Flash REST API** | Cloud HTTPS |
| **Burmese Voice Dubbing** | **Google Gemini AI Voice (API Key)** + **Microsoft Edge TTS** + **Google Cloud TTS** | Cloud REST / WebSocket |
| **Voice Profiles & Audition** | Curated persona profiles (Thiha, Nilar, Charon, Puck, Kore, Fenrir, Aoede) + In-App Audio Audition | Dynamic In-App |
| **Video Composition** | **FFmpeg-Kit Full GPL** (`libass` + Padauk font) | On-Device GPU/CPU |
| **Audio Isolation** | Stream mapping `-map 1:a:0` / `[1:a]atempo` | **100% Pure Dubbed Narration Only** (0:a excluded) |
| **Post-Download Cleanup** | Android Scoped Storage (`MediaStore.Video`) | Auto-clears source & temp cache |

---

## How to Get the `.apk` File to Test

### Option 1: Automated GitHub Actions Build (Recommended - 0 Setup Required)

1. Push this repository to your GitHub account (public or private):
   ```bash
   git add .
   git commit -m "Add RecapMaster Android application with Gemini TTS & Voice Profiles"
   git push origin main
   ```
2. Navigate to your repository on **GitHub** > **Actions** tab.
3. Select the workflow **"Build RecapMaster Android APK"** and click **Run workflow** (or wait for the push trigger).
4. In ~3–5 minutes, the build will finish. Under the **Artifacts** section at the bottom of the summary page, download:
   👉 **`RecapMaster-debug-apk`**
5. On your Android phone, tap the downloaded `.apk` file and tap **Install**.

---

### Option 2: Local Windows Android Studio Build

1. Install **[Android Studio](https://developer.android.com/studio)**.
2. In Android Studio, go to **Tools > SDK Manager > SDK Tools**:
   - Check **NDK (Side by side)** (version `26.1.10909125` or newer).
   - Check **CMake** (version `3.22.1` or newer).
3. Click **File > Open** and select the `android-recapmaster` directory.
4. Let Gradle sync project dependencies.
5. In the top menu, select **Build > Build Bundle(s) / APK(s) > Build APK(s)** (or run `./gradlew assembleDebug` in the terminal).
6. The generated APK will be located at:
   `android-recapmaster/app/build/outputs/apk/debug/app-debug.apk`
7. Transfer the APK to your phone via USB cable or Google Drive and install.

---

## Features on Mobile
- 🔗 **YouTube & Bilibili URL Downloader**: Paste any link or share directly from the YouTube/Bilibili app using the Android Share menu.
- 🎙️ **On-Device Whisper**: Transcribes dialogue directly on your phone's processor with quantized GGUF weights.
- 🤖 **Gemini AI Voice TTS (using Gemini API Key)**: Uses Gemini 2.5/2.0 Flash native multimodal audio synthesis with emotional, cinematic narrator cadence.
- 🇲🇲 **Microsoft Edge TTS & Google Cloud TTS**: Fast, free Edge neural dubbing (Thiha & Nilar) plus Google Cloud TTS options.
- 🎭 **Curated Voice Profiles**:
  - **Thiha (သီဟ)**: Burmese Male • Fast-paced cinematic recap
  - **Nilar (နီလာ)**: Burmese Female • Expressive drama & suspense
  - **Gemini Charon (ချာရွန်)**: Gemini AI • Deep thriller & gritty narration
  - **Gemini Puck (ပတ်ခ်)**: Gemini AI • Punchy action & anime cadence
  - **Gemini Kore (ကိုရီ)**: Gemini AI • Warm & captivating emotional storyteller
  - **Gemini Fenrir (ဖန်ရီယာ)**: Gemini AI • Authoritative blockbuster presence
  - **Gemini Aoede (အေးဒီး)**: Gemini AI • Melodic mystery & horror
  - **Google Standard**: Google Cloud Burmese neural voice
- 🔊 **In-App Voice Preview / Audition**: Listen to sample audio of any voice profile directly in the app before starting full video generation.
- 🎛️ **Voice Speed, Pitch & Persona Tuning**: Adjust speech rate (-30% to +50%), pitch (-10Hz to +10Hz), and enter custom Gemini voice persona instructions.
- ⚡ **Linked Playback Speed (0.5× – 2.0×)**: Video pacing and dubbed voice stretch synchronously.
- 🛡️ **Watermark & Logo Blur Box**: Interactive sliders to obscure source channel logos or burned hardcoded subtitles.
- 💾 **Auto Cleanup**: Saves final video directly to phone Gallery (`Movies/RecapMaster`) and automatically deletes the downloaded YouTube/Bilibili source file to free up device storage.
