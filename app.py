import os
import sys
import uuid
import shutil
import json
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
from worker.db import create_job, update_job_status, update_job_render_settings, get_job
from worker.main import process_job
from worker.config import WORKSPACE_DIR
from worker.pipeline.render import generate_preview_frame

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

SUBTITLE_PLACEMENTS = [
    ("Bottom (Standard cinematic)", "bottom"),
    ("Top (Header / Upper bar)", "top"),
    ("Middle (Center screen)", "middle"),
]

LOGO_PRESETS = {
    "Custom Coordinates": None,
    "Full-Width Bottom Subtitles (Cover Hardcoded Subtitles)": {"x": 0, "y": 78, "w": 100, "h": 20},
    "Full-Width Lower Third Bar": {"x": 0, "y": 70, "w": 100, "h": 28},
    "Full-Width Top Bar": {"x": 0, "y": 0, "w": 100, "h": 16},
    "Top-Right Logo (Bilibili / TV Watermark)": {"x": 78, "y": 4, "w": 18, "h": 8},
    "Top-Left Logo (YouTube Channel Icon)": {"x": 4, "y": 4, "w": 18, "h": 8},
    "Bottom-Right (Timestamp / Brand Tag)": {"x": 78, "y": 88, "w": 18, "h": 8},
    "Bottom-Left (Platform Handle)": {"x": 4, "y": 88, "w": 18, "h": 8},
}

def make_blur_box_dict(enabled: bool, x: float, y: float, w: float, h: float, strength: int) -> dict:
    return {
        "enabled": bool(enabled),
        "x_pct": float(x) / 100.0,
        "y_pct": float(y) / 100.0,
        "w_pct": float(w) / 100.0,
        "h_pct": float(h) / 100.0,
        "strength": int(strength),
    }

def generate_recap(
    video_url: str,
    uploaded_file: str,
    voxcpm_endpoint: str,
    gemini_api_key: str,
    voice: str,
    sound_style: str,
    burn_subs: bool,
    sub_placement: str,
    sub_size: float,
    sub_margin_v: int,
    blur_enabled: bool,
    blur_x: float,
    blur_y: float,
    blur_w: float,
    blur_h: float,
    blur_strength: int,
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
    active_gemini_key = (gemini_api_key or "").strip() or None
    blur_config = make_blur_box_dict(blur_enabled, blur_x, blur_y, blur_w, blur_h, blur_strength)

    log_history = []
    def log(msg: str, prg: float = None):
        log_history.append(msg)
        if prg is not None:
            progress(prg, desc=msg)
        return "\n".join(log_history)

    yield None, None, log(f"🚀 Job Created: {job_id}", 0.05), job_id

    try:
        # Create job in database with visual settings
        create_job(
            job_id=job_id,
            source_url=source_input,
            language="my",
            voice=voice,
            sound_style=sound_style,
            voxcpm_endpoint=endpoint,
            burn_subtitles=burn_subs,
            subtitle_placement=sub_placement,
            subtitle_size=float(sub_size),
            subtitle_margin_v=int(sub_margin_v),
            blur_box_config=json.dumps(blur_config),
            gemini_api_key=active_gemini_key,
        )

        yield None, None, log("📥 Downloading / Extracting source video...", 0.15), job_id

        # Execute the pipeline with GPU acceleration
        final_video = process_job(job_id, exit_on_error=False, auto_render=True)

        if not final_video or not Path(final_video).exists():
            raise RuntimeError("Final video was not generated or file is missing.")

        yield final_video, final_video, log("✅ Completed Successfully! Your video is ready below.", 1.0), job_id

    except Exception as e:
        tb = traceback.format_exc()
        err_msg = f"❌ Error: {str(e)}\n\n{tb}"
        yield None, None, log(err_msg), job_id
        raise gr.Error(str(e))

def fast_rerender(
    current_job_id: str,
    burn_subs: bool,
    sub_placement: str,
    sub_size: float,
    sub_margin_v: int,
    blur_enabled: bool,
    blur_x: float,
    blur_y: float,
    blur_w: float,
    blur_h: float,
    blur_strength: int,
    progress=gr.Progress(track_tqdm=True),
):
    if not current_job_id or not current_job_id.strip():
        raise gr.Error("No previous job found to re-render. Please generate a recap video first.")

    job_id = current_job_id.strip()
    job = get_job(job_id)
    if not job:
        raise gr.Error(f"Job '{job_id}' not found in database.")

    blur_config = make_blur_box_dict(blur_enabled, blur_x, blur_y, blur_w, blur_h, blur_strength)

    # Update settings in DB
    update_job_render_settings(
        job_id=job_id,
        subtitle_placement=sub_placement,
        subtitle_size=float(sub_size),
        subtitle_margin_v=int(sub_margin_v),
        blur_box_config=json.dumps(blur_config),
        burn_subtitles=burn_subs,
    )

    progress(0.2, desc="⚡ Fast re-rendering video composition with new visual layout...")
    final_video = process_job(job_id, exit_on_error=False, rerender_only=True)
    if not final_video or not Path(final_video).exists():
        raise RuntimeError("Re-rendered video was not generated.")

    progress(1.0, desc="✅ Fast re-render complete!")
    return final_video, final_video, f"✅ Fast re-render complete for {job_id} with updated subtitles and blur box!"

def live_preview(
    uploaded_file: str,
    sub_placement: str,
    sub_size: float,
    sub_margin_v: int,
    blur_enabled: bool,
    blur_x: float,
    blur_y: float,
    blur_w: float,
    blur_h: float,
    blur_strength: int,
):
    preview_path = WORKSPACE_DIR / "temp_preview" / "live_preview.png"
    blur_config = make_blur_box_dict(blur_enabled, blur_x, blur_y, blur_w, blur_h, blur_strength)

    source = Path(uploaded_file) if uploaded_file and Path(uploaded_file).exists() else None
    out_img = generate_preview_frame(
        source_video=source,
        output_image_path=preview_path,
        subtitle_placement=sub_placement,
        subtitle_size_scale=float(sub_size),
        subtitle_margin_v=int(sub_margin_v),
        blur_box=blur_config,
    )
    return str(out_img)

custom_css = """
.container { max-width: 1200px; margin: auto; }
.header-box { text-align: center; padding: 24px; border-radius: 16px; background: linear-gradient(135deg, #1e1b4b 0%, #312e81 50%, #4338ca 100%); color: white; margin-bottom: 24px; border: 1px solid rgba(139, 92, 246, 0.3); box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.3); }
.header-box h1 { font-size: 2.3rem; font-weight: 800; margin-bottom: 8px; letter-spacing: -0.02em; }
.header-box p { font-size: 1.05rem; opacity: 0.92; }
.section-badge { display: inline-block; font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.05em; font-weight: 700; color: #a78bfa; background: rgba(167, 139, 250, 0.1); border: 1px solid rgba(167, 139, 250, 0.2); padding: 2px 8px; border-radius: 6px; margin-bottom: 8px; }
"""

with gr.Blocks(title="RecapMaster - AI Video Recap & Subtitle Studio", css=custom_css) as demo:
    active_job_id = gr.State("")

    gr.HTML("""
    <div class="header-box">
        <h1>🎬 RecapMaster AI Studio</h1>
        <p>Convert YouTube, Bilibili, or Videos into Full-Length Burmese Voiceover Recaps with Grammatically Correct Subtitles & Logo Removal</p>
    </div>
    """)

    with gr.Row():
        # Left Column: Inputs & Visual Customization Controls
        with gr.Column(scale=6):
            with gr.Group():
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

            with gr.Group():
                gr.Markdown("### 2. Audio & Dubbing Options")
                colab_input = gr.Textbox(
                    label="VoxCPM 2 Colab Gradio Endpoint",
                    value=VOXCPM_DEFAULT,
                    placeholder="https://xxxx.gradio.live",
                    info="Live Google Colab Gradio tunnel for VoxCPM voice cloning.",
                )

                gemini_api_key_input = gr.Textbox(
                    label="Google Gemini API Key (Burmese Translation & Movie Recap Narration)",
                    placeholder="AIzaSy... (leave blank to use fallback web translator)",
                    type="password",
                    info="Powers Gemini 2.5/2.0 Flash for natural Burmese dialogue translation and captivating recap narration.",
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

            # Section 3: Subtitle Placement, Size & Logo Blur Box Customization
            with gr.Accordion("🎨 Subtitle Layout & Logo Removal Blur Box (Adjust Before Final Render)", open=True):
                gr.Markdown("Customize subtitle placement, typography size, or apply a frosted blur box over old logos.")

                with gr.Row():
                    subs_checkbox = gr.Checkbox(
                        label="Burn Burmese Subtitles (Padauk Font)",
                        value=True,
                        info="HarfBuzz complex OpenType shaping renders 100% grammatically correct glyphs.",
                        scale=1,
                    )
                    sub_placement_input = gr.Dropdown(
                        label="Subtitle Placement / Position",
                        choices=[c[0] for c in SUBTITLE_PLACEMENTS],
                        value=SUBTITLE_PLACEMENTS[0][0],
                        scale=1,
                    )

                with gr.Row():
                    sub_size_input = gr.Slider(
                        label="Subtitle Font Size Scale",
                        minimum=0.7,
                        maximum=1.6,
                        value=1.0,
                        step=0.05,
                        info="Scale factor (1.0 = standard, 1.3 = large headline, 0.8 = compact)",
                    )
                    sub_margin_input = gr.Slider(
                        label="Vertical Edge Margin (px)",
                        minimum=10,
                        maximum=120,
                        value=30,
                        step=5,
                        info="Distance from screen edge in pixels",
                    )

                gr.Markdown("---")
                gr.Markdown("#### 🛡️ Remove Old Logo / Watermark Blur Box")

                blur_enabled_input = gr.Checkbox(
                    label="Enable Logo / Watermark Blur Box",
                    value=False,
                    info="Applies a smooth frosted blur box over old channel logos, watermarks, or TV bugs.",
                )

                preset_dropdown = gr.Dropdown(
                    label="Quick Position Presets",
                    choices=list(LOGO_PRESETS.keys()),
                    value="Custom Coordinates",
                    info="Select a preset or customize coordinates below.",
                )

                with gr.Row():
                    blur_x_input = gr.Slider(
                        label="Box Left Position X (%)",
                        minimum=0,
                        maximum=100,
                        value=78,
                        step=1,
                    )
                    blur_y_input = gr.Slider(
                        label="Box Top Position Y (%)",
                        minimum=0,
                        maximum=100,
                        value=4,
                        step=1,
                    )

                with gr.Row():
                    blur_w_input = gr.Slider(
                        label="Box Width (%)",
                        minimum=2,
                        maximum=100,
                        value=18,
                        step=1,
                    )
                    blur_h_input = gr.Slider(
                        label="Box Height (%)",
                        minimum=2,
                        maximum=80,
                        value=8,
                        step=1,
                    )
                    blur_strength_input = gr.Slider(
                        label="Blur Intensity / Radius",
                        minimum=5,
                        maximum=50,
                        value=16,
                        step=1,
                    )

                preview_btn = gr.Button("👁️ Preview Subtitle & Blur on Video Frame", variant="secondary")

            with gr.Row():
                generate_btn = gr.Button("⚡ Generate Recap Video", variant="primary", size="lg", scale=2)
                rerender_btn = gr.Button("🔄 Re-render With New Settings (<10s)", variant="secondary", size="lg", scale=1)

        # Right Column: Visual Preview & Output Media
        with gr.Column(scale=5):
            gr.Markdown("### 3. Visual Preview & Final Output")

            preview_image = gr.Image(
                label="Live Frame Preview (Subtitles & Logo Blur)",
                interactive=False,
            )

            output_video = gr.Video(label="Generated Movie Recap Video", autoplay=False)
            output_file = gr.File(label="Download Full HD Video")
            status_box = gr.Textbox(label="Processing Log & Status", lines=6, max_lines=14, interactive=False)

    # Preset selection handler
    def on_preset_select(choice):
        coords = LOGO_PRESETS.get(choice)
        if coords:
            return (
                True, # enable blur box
                coords["x"],
                coords["y"],
                coords["w"],
                coords["h"],
            )
        return gr.update(), gr.update(), gr.update(), gr.update(), gr.update()

    preset_dropdown.change(
        fn=on_preset_select,
        inputs=[preset_dropdown],
        outputs=[blur_enabled_input, blur_x_input, blur_y_input, blur_w_input, blur_h_input],
    )

    # Live frame preview button
    def on_preview_click(
        vid_file,
        placement_label,
        sub_size,
        sub_margin,
        blur_enabled,
        bx,
        by,
        bw,
        bh,
        b_strength,
    ):
        placement_val = dict(SUBTITLE_PLACEMENTS).get(placement_label, "bottom")
        return live_preview(
            vid_file,
            placement_val,
            sub_size,
            sub_margin,
            blur_enabled,
            bx,
            by,
            bw,
            bh,
            b_strength,
        )

    preview_btn.click(
        fn=on_preview_click,
        inputs=[
            file_input,
            sub_placement_input,
            sub_size_input,
            sub_margin_input,
            blur_enabled_input,
            blur_x_input,
            blur_y_input,
            blur_w_input,
            blur_h_input,
            blur_strength_input,
        ],
        outputs=[preview_image],
    )

    # Main submit button
    def on_submit(
        url,
        file,
        colab,
        gemini_key,
        voice_label,
        style_label,
        burn,
        placement_label,
        sub_size,
        sub_margin,
        blur_enabled,
        bx,
        by,
        bw,
        bh,
        b_strength,
    ):
        voice_val = dict(VOICE_CHOICES).get(voice_label, "voxcpm")
        style_val = dict(SOUND_STYLES).get(style_label, "cinematic_recap")
        placement_val = dict(SUBTITLE_PLACEMENTS).get(placement_label, "bottom")

        for video_res, file_res, log_res, j_id in generate_recap(
            url,
            file,
            colab,
            gemini_key,
            voice_val,
            style_val,
            burn,
            placement_val,
            sub_size,
            sub_margin,
            blur_enabled,
            bx,
            by,
            bw,
            bh,
            b_strength,
        ):
            yield video_res, file_res, log_res, j_id

    generate_btn.click(
        fn=on_submit,
        inputs=[
            url_input,
            file_input,
            colab_input,
            gemini_api_key_input,
            voice_input,
            sound_style_input,
            subs_checkbox,
            sub_placement_input,
            sub_size_input,
            sub_margin_input,
            blur_enabled_input,
            blur_x_input,
            blur_y_input,
            blur_w_input,
            blur_h_input,
            blur_strength_input,
        ],
        outputs=[output_video, output_file, status_box, active_job_id],
    )

    # Fast re-render button
    def on_rerender(
        j_id,
        burn,
        placement_label,
        sub_size,
        sub_margin,
        blur_enabled,
        bx,
        by,
        bw,
        bh,
        b_strength,
    ):
        placement_val = dict(SUBTITLE_PLACEMENTS).get(placement_label, "bottom")
        return fast_rerender(
            j_id,
            burn,
            placement_val,
            sub_size,
            sub_margin,
            blur_enabled,
            bx,
            by,
            bw,
            bh,
            b_strength,
        )

    rerender_btn.click(
        fn=on_rerender,
        inputs=[
            active_job_id,
            subs_checkbox,
            sub_placement_input,
            sub_size_input,
            sub_margin_input,
            blur_enabled_input,
            blur_x_input,
            blur_y_input,
            blur_w_input,
            blur_h_input,
            blur_strength_input,
        ],
        outputs=[output_video, output_file, status_box],
    )

if __name__ == "__main__":
    is_colab = "google.colab" in sys.modules or os.getenv("COLAB_GPU") is not None
    share_mode = is_colab or os.getenv("SHARE", "true").lower() == "true"
    demo.queue().launch(server_name="0.0.0.0", server_port=7860, share=share_mode)
