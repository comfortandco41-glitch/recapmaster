import json
import os
import re
import yt_dlp


def _find_actual_file(base_path: str) -> str | None:
    """
    yt-dlp often writes the file with a different extension than requested.
    Search for the actual output file by trying common video/audio extensions.
    """
    if os.path.exists(base_path) and os.path.getsize(base_path) > 0:
        return base_path
    for ext in [".mp4", ".webm", ".mkv", ".m4v", ".avi", ".mov", ".flv", ".3gp", ".m4a", ".aac", ".mp3", ".opus"]:
        cand = base_path + ext
        if os.path.exists(cand) and os.path.getsize(cand) > 0:
            return cand
    base_noext, _ = os.path.splitext(base_path)
    for ext in [".mp4", ".webm", ".mkv", ".m4v", ".avi", ".mov", ".flv", ".m4a", ".aac", ".mp3", ".opus"]:
        cand = base_noext + ext
        if cand != base_path and os.path.exists(cand) and os.path.getsize(cand) > 0:
            return cand
    return None


def download_video(url: str, output_path: str) -> str:
    """
    Downloads YouTube / Bilibili / social media video on Android via yt-dlp running inside
    the Chaquopy Python sandbox.

    High-Quality Strategy:
      1. Probe formats to check if high-definition pre-muxed (video+audio) >= 720p is available.
         If so, download directly in one shot (common for TikTok, Facebook, Twitter, Bilibili, etc.).
      2. If pre-muxed format is low resolution (< 720p, like YouTube's 360p progressive stream)
         but high-definition video-only streams (1080p / 720p) exist:
         Download the best HD video stream (<= 1080p) and best audio stream separately.
         Return both paths so FFmpegKit in Kotlin can mux them instantly with -c:v copy.
      3. Fallback: Download best available single stream.
    """
    out_dir = os.path.dirname(output_path)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    base_path, _ = os.path.splitext(output_path)

    is_youtube = bool(re.search(r"(youtube\.com|youtu\.be)", url, re.I))
    is_bilibili = bool(re.search(r"(bilibili\.com|b23\.tv)", url, re.I))

    headers = {
        "User-Agent": (
            "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
        ),
    }
    if is_bilibili:
        headers["Referer"] = "https://www.bilibili.com"
        headers["User-Agent"] = (
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Mobile Safari/537.36"
        )

    base_opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "abort_on_error": False,
        "continuedl": True,
        "http_headers": headers,
    }
    if is_youtube:
        base_opts["extractor_args"] = {
            "youtube": {
                "player_client": ["android", "web", "ios"],
            }
        }

    try:
        # Step 1: Probe available formats
        with yt_dlp.YoutubeDL(base_opts) as ydl_probe:
            info = ydl_probe.extract_info(url, download=False)
            if info is None:
                return json.dumps({
                    "success": False,
                    "error": "yt-dlp returned no info for this URL.",
                })

            title = info.get("title", "Recap Source")
            duration = float(info.get("duration") or 0.0)
            formats = info.get("formats", [])

            # Check if pre-muxed >= 720p is available
            has_hd_muxed = any(
                f.get("vcodec") != "none"
                and f.get("acodec") != "none"
                and (f.get("height") or 0) >= 720
                for f in formats
            )

            # Check if separate video stream >= 720p is available
            has_hd_video = any(
                f.get("vcodec") != "none"
                and (f.get("height") or 0) >= 720
                for f in formats
            )

            has_audio_stream = any(
                f.get("acodec") != "none"
                for f in formats
            )

        # Step 2: If no HD pre-muxed, but separate HD video + audio exists (e.g. YouTube):
        if not has_hd_muxed and has_hd_video and has_audio_stream:
            # Download HD video stream
            video_outtmpl = base_path + ".video.%(ext)s"
            opts_v = dict(base_opts)
            opts_v["format"] = (
                "bestvideo[height<=1080][ext=mp4]"
                "/bestvideo[height<=1080]"
                "/bestvideo[ext=mp4]"
                "/bestvideo"
            )
            opts_v["outtmpl"] = video_outtmpl

            with yt_dlp.YoutubeDL(opts_v) as ydl_v:
                ydl_v.download([url])

            actual_v = _find_actual_file(base_path + ".video")
            if not actual_v and os.path.isdir(out_dir):
                for cand in os.listdir(out_dir):
                    if cand.startswith(os.path.basename(base_path) + ".video"):
                        actual_v = os.path.join(out_dir, cand)
                        break

            # Download audio stream
            audio_outtmpl = base_path + ".audio.%(ext)s"
            opts_a = dict(base_opts)
            opts_a["format"] = "bestaudio[ext=m4a]/bestaudio"
            opts_a["outtmpl"] = audio_outtmpl

            with yt_dlp.YoutubeDL(opts_a) as ydl_a:
                ydl_a.download([url])

            actual_a = _find_actual_file(base_path + ".audio")
            if not actual_a and os.path.isdir(out_dir):
                for cand in os.listdir(out_dir):
                    if cand.startswith(os.path.basename(base_path) + ".audio"):
                        actual_a = os.path.join(out_dir, cand)
                        break

            if actual_v and actual_a:
                return json.dumps({
                    "success": True,
                    "title": title,
                    "duration": duration,
                    "filePath": actual_v,
                    "audioPath": actual_a,
                    "needsMux": True,
                })

        # Step 3: Direct single-file download (pre-muxed or fallback)
        single_opts = dict(base_opts)
        single_opts["format"] = (
            "best[ext=mp4][height>=1080][vcodec!=none][acodec!=none]"
            "/best[height>=1080][vcodec!=none][acodec!=none]"
            "/best[ext=mp4][height>=720][vcodec!=none][acodec!=none]"
            "/best[height>=720][vcodec!=none][acodec!=none]"
            "/best[ext=mp4][vcodec!=none][acodec!=none]"
            "/best[vcodec!=none][acodec!=none]"
            "/best"
        )
        single_opts["outtmpl"] = base_path + ".%(ext)s"

        with yt_dlp.YoutubeDL(single_opts) as ydl_single:
            ydl_single.download([url])

        actual_path = _find_actual_file(base_path + ".mp4")
        if actual_path is None:
            chosen_ext = info.get("ext", "")
            if chosen_ext:
                actual_path = _find_actual_file(base_path + "." + chosen_ext)
        if actual_path is None and os.path.isdir(out_dir):
            for fname in os.listdir(out_dir):
                fpath = os.path.join(out_dir, fname)
                if os.path.isfile(fpath) and os.path.getsize(fpath) > 1024:
                    actual_path = fpath
                    break

        if actual_path is None:
            return json.dumps({
                "success": False,
                "error": f"Download seemed to complete but no output file was found on disk near: {base_path}",
            })

        return json.dumps({
            "success": True,
            "title": title,
            "duration": duration,
            "filePath": actual_path,
            "audioPath": None,
            "needsMux": False,
            "ext": os.path.splitext(actual_path)[1].lstrip("."),
        })

    except yt_dlp.utils.DownloadError as de:
        err = str(de)
        if "requested format is not available" in err.lower():
            err = (
                "No compatible video format was available for this URL. "
                "Try a different video or check if the video is age-restricted/private. "
                f"(yt-dlp detail: {err})"
            )
        return json.dumps({"success": False, "error": err})
    except Exception as exc:
        return json.dumps({"success": False, "error": str(exc)})

