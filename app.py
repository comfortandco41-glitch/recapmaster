import os
import sys
import uuid
import shutil
import traceback
from pathlib import Path

# Ensure root directory is in sys.path
ROOT_DIR = Path(__file__).resolve().parent
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

# Ensure UTF-8 output
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

import gradio as gr
from worker.db import create_job, update_job_status
from worker.main import process_job
from worker.config import WORKSPACE_DIR

VOXCPM_DEFAULT = os.getenv("VOXCPM_ENDPOINT", "https://43bd4b864d1a9c7163.gradio.live")

VOICE_CHOICES = [
    ("VoxCPM 2 AI Voice (via Colab)", "voxcpm"),
    ("Burmese Female - Nilar (Edge-TTS)", "my-MM-NilarNeural"),
    ("Burmese Male - Thiha (Edge-TTS)", "my-MM-ThihaNeural"),
]

SOUND_STYLES = [
    ("Cinematic Recap (Epic Bass & Presence)", "cinematic_recap"),
    ("Dramatic Suspense (High Tension & Thriller)", "dramatic_suspense"),
    ("Energetic Action (Punchy Dynamics)", "energetic_action"),
    ("Emotional Warmth (Intimate & Gentle)", "emotional_warmth"),
    ("Broadcast Studio (Clean Crisp Voice)", "broadcast_studio"),
]

def generate_recap(
    video_url: str,
    uploaded_file: str,
    voxcpm_endpoint: str,
    voice: str,
    sound_style: str,
    burn_subs: bool,
    progress=gr.Progress(track_tqdm=True),
):
    if not video_url and not uploaded_file:
        raise gr.Error("Please provide a video URL or upload a video file!")

    job_id = f"job_{uuid.uuid4().hex[:12]}"
    job_dir = WORKSPACE_DIR / job_id
    source_dir = job_dir / "source"
    source_dir.mkdir(parents=True, exist_ok=True)

    source_input = video_url.strip() if video_url else "local_upload"
    if uploaded_file:
        target_video = source_dir / "source.mp4"
        shutil.copy(uploaded_file, target_video)
        source_input = str(target_video)

    endpoint = (voxcpm_endpoint or "").strip() or None

    log_history = []
    def log(msg: str, prg: float = None):
        log_history.append(msg)
        if prg is not None:
            progress(prg, desc=msg)
        return "\n".join(log_history)

    yield None, None, log(f"🚀 Job Created: {job_id}", 0.05)

    try:
        # Create job in database
        create_job(
            job_id=job_id,
            source_url=source_input,
            language="my",
            voice=voice,
            sound_style=sound_style,
            voxcpm_endpoint=endpoint,
            burn_subtitles=burn_subs,
        )

        yield None, None, log("📥 Downloading / Extracting source video...", 0.15)
        # Execute the pipeline
        final_video = process_job(job_id, exit_on_error=False)

        if not final_video or not Path(final_video).exists():
            raise RuntimeError("Final video was not generated or file is missing.")

        yield final_video, final_video, log("✅ Completed Successfully! Your video is ready below.", 1.0)

    except Exception as e:
        tb = traceback.format_exc()
        err_msg = f"❌ Error: {str(e)}\n\n{tb}"
        yield None, None, log(err_msg)
        raise gr.Error(str(e))

custom_css = """
.container { max-width: 1000px; margin: auto; }
.header-box { text-align: center; padding: 20px; border-radius: 12px; background: linear-gradient(135deg, #1e1b4b 0%, #312e81 100%); color: white; margin-bottom: 20px; }
.header-box h1 { font-size: 2.2rem; font-weight: 800; margin-bottom: 8px; }
.header-box p { font-size: 1rem; opacity: 0.9; }
"""

with gr.Blocks(title="RecapMaster - AI Video Recap & Dubbing") as demo:
    gr.HTML("""
    <div class="header-box">
        <h1>🎬 RecapMaster AI</h1>
        <p>Convert YouTube, Bilibili, or Videos into Full-Length Burmese Voiceover Recaps with Grammatically Correct Subtitles</p>
    </div>
    """)

    with gr.Row():
        with gr.Column(scale=1):
            gr.Markdown("### 1. Source Video")
            url_input = gr.Textbox(
                label="Video URL",
                placeholder="https://www.youtube.com/watch?v=... or Bilibili URL",
                info="Supports YouTube, Bilibili, Shorts, or direct video links.",
            )
            file_input = gr.Video(
                label="Or Upload Video File Directly",
                sources=["upload"],
            )

            gr.Markdown("### 2. Audio & Dubbing Options")
            colab_input = gr.Textbox(
                label="VoxCPM 2 Colab Gradio Endpoint",
                value=VOXCPM_DEFAULT,
                placeholder="https://xxxx.gradio.live",
                info="Live Google Colab Gradio tunnel for VoxCPM voice cloning.",
            )

            voice_input = gr.Dropdown(
                label="Voice Selection",
                choices=[c[0] for c in VOICE_CHOICES],
                value=VOICE_CHOICES[0][0],
            )

            sound_style_input = gr.Dropdown(
                label="Sound Design & Mastering Preset",
                choices=[c[0] for c in SOUND_STYLES],
                value=SOUND_STYLES[0][0],
            )

            subs_checkbox = gr.Checkbox(
                label="Burn Burmese Subtitles (SIL Padauk Font)",
                value=True,
                info="HarfBuzz complex OpenType shaping renders 100% grammatically correct glyphs.",
            )

            generate_btn = gr.Button("⚡ Generate Recap Video", variant="primary", size="lg")

        with gr.Column(scale=1):
            gr.Markdown("### 3. Output Preview")
            output_video = gr.Video(label="Generated Movie Recap Video", autoplay=False)
            output_file = gr.File(label="Download Full HD Video")
            status_box = gr.Textbox(label="Processing Log & Status", lines=8, max_lines=16, interactive=False)

    def on_submit(url, file, colab, voice_label, style_label, burn):
        voice_val = dict(VOICE_CHOICES).get(voice_label, "voxcpm")
        style_val = dict(SOUND_STYLES).get(style_label, "cinematic_recap")
        for res in generate_recap(url, file, colab, voice_val, style_val, burn):
            yield res

    generate_btn.click(
        fn=on_submit,
        inputs=[url_input, file_input, colab_input, voice_input, sound_style_input, subs_checkbox],
        outputs=[output_video, output_file, status_box],
    )

if __name__ == "__main__":
    # Port 7860 is default for Hugging Face Spaces
    demo.queue().launch(server_name="0.0.0.0", server_port=7860)
