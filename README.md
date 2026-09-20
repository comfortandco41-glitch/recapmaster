---
title: RecapMaster Video AI
emoji: 🎬
colorFrom: purple
colorTo: indigo
sdk: gradio
app_file: app.py
pinned: false
---

# RecapMaster - AI Movie & Video Recap Generator

Automated cinematic video recap and narration platform with synchronized multilingual voiceover, smart dialogue pacing, and burned Myanmar Unicode subtitles.

## Features
- **Full Video Length Preservation**: Generates full-length recaps matching the original video duration.
- **Timestamp-Synchronized Dialogue**: Narration speech aligns with dialogue timestamps without audio overlap.
- **VoxCPM 2 Voice Integration**: Connects to Google Colab VoxCPM 2 Gradio inference endpoint with Edge Neural fallback.
- **Sound Design Styles**: 5 cinematic mastering presets (Cinematic Recap, Dramatic Thriller, Energetic Action, Emotional Warmth, Broadcast Studio).
- **Flawless Subtitle Typography**: Burned subtitles rendered with SIL Padauk font and HarfBuzz OpenType complex script shaping.

## Environment Variables
- `VOXCPM_ENDPOINT`: Your Google Colab VoxCPM Gradio URL (e.g. `https://xxxx.gradio.live`)
