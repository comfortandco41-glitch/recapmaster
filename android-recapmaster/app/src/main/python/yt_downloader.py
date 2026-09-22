import json
import os
import yt_dlp


def download_video(url: str, output_path: str) -> str:
    """
    Downloads YouTube or Bilibili video directly on Android via yt-dlp.

    IMPORTANT: ffmpeg is NOT available inside the Python/Chaquopy sandbox on
    Android.  yt-dlp would normally merge a separate video+audio stream using
    ffmpeg, which causes the error:
        "you have merging of multiple formats but ffmpeg is not installed"

    We therefore only request pre-merged single-file formats (no '+' combiner)
    so yt-dlp can download and write the file directly without any muxing step.

    Returns JSON string: { success, title, duration, filePath } or { success:false, error }.
    """
    out_dir = os.path.dirname(output_path)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    # Format selector: only single-file (pre-merged) formats — no '+' combiner.
    # Priority: mp4 up to 720p → any mp4 → any single-file up to 720p → absolute best.
    FORMAT = (
        "best[ext=mp4][height<=720]"
        "/best[ext=mp4][height<=1080]"
        "/best[ext=mp4]"
        "/best[height<=720]"
        "/best"
    )

    ydl_opts = {
        "format": FORMAT,
        # Do NOT set merge_output_format — no ffmpeg available on Android.
        "outtmpl": output_path,
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        # Abort immediately if yt-dlp would need ffmpeg for merging
        "abort_on_error": False,
        "http_headers": {
            "User-Agent": (
                "Mozilla/5.0 (Linux; Android 10; Mobile) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/120.0.0.0 Mobile Safari/537.36"
            ),
        },
    }

    if "bilibili.com" in url or "b23.tv" in url:
        ydl_opts["http_headers"]["Referer"] = "https://www.bilibili.com"

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            title = info.get("title", "Recap Source")
            duration = float(info.get("duration") or 0.0)

            # yt-dlp may append an extension even when outtmpl has none
            actual_path = output_path
            if not os.path.exists(actual_path):
                for ext in [".mp4", ".mkv", ".webm", ".m4v", ".avi"]:
                    cand = output_path + ext
                    if os.path.exists(cand):
                        actual_path = cand
                        break

            if not os.path.exists(actual_path):
                return json.dumps({
                    "success": False,
                    "error": "Download appeared to succeed but output file was not found on disk.",
                })

            return json.dumps({
                "success": True,
                "title": title,
                "duration": duration,
                "filePath": actual_path,
            })

    except Exception as exc:
        return json.dumps({
            "success": False,
            "error": str(exc),
        })
