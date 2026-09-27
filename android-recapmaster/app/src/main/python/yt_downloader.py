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


def _select_formats(formats: list) -> tuple:
    """
    Analyzes actual probed streams and selects the best compatible format IDs.
    Returns: (mode, video_fmt_id, audio_fmt_id)
      - "DIRECT_MUXED": Single high-definition stream with both video and audio (>= 720p).
      - "SEPARATE_HD": Separate HD video stream (<= 1080p) + separate audio stream for Kotlin FFmpeg mux.
      - "FALLBACK_MUXED": Single pre-muxed stream (< 720p, e.g. 360p / 480p progressive).
      - "FALLBACK_ANY": Fallback to whatever yt-dlp can download.
    """
    if not formats:
        return ("FALLBACK_ANY", None, None)

    # 1. Pre-muxed streams (both video and audio)
    muxed = [
        f for f in formats
        if f.get("vcodec") and f.get("vcodec") != "none"
        and f.get("acodec") and f.get("acodec") != "none"
        and f.get("format_id")
    ]

    # Check for HD pre-muxed (>= 720p)
    hd_muxed = [f for f in muxed if (f.get("height") or 0) >= 720]
    if hd_muxed:
        hd_muxed.sort(
            key=lambda f: (
                1 if f.get("ext") == "mp4" else 0,
                f.get("height") or 0,
                f.get("tbr") or 0,
            ),
            reverse=True,
        )
        return ("DIRECT_MUXED", hd_muxed[0]["format_id"], None)

    # 2. Separate video and audio streams (common on YouTube 720p/1080p)
    video_streams = [
        f for f in formats
        if f.get("vcodec") and f.get("vcodec") != "none"
        and f.get("format_id")
        and (f.get("height") or 0) > 0
    ]
    audio_streams = [
        f for f in formats
        if f.get("acodec") and f.get("acodec") != "none"
        and (not f.get("vcodec") or f.get("vcodec") == "none")
        and f.get("format_id")
    ]

    # Filter video <= 1080p
    video_1080 = [f for f in video_streams if (f.get("height") or 0) <= 1080]
    candidate_videos = video_1080 if video_1080 else video_streams

    if candidate_videos and audio_streams:
        def video_score(f):
            h264_bonus = 2 if "avc" in (f.get("vcodec") or "").lower() else (1 if f.get("ext") == "mp4" else 0)
            return (f.get("height") or 0, h264_bonus, f.get("tbr") or 0)

        candidate_videos.sort(key=video_score, reverse=True)
        best_v = candidate_videos[0]

        # Only use separate streams if video resolution is >= 480p
        if (best_v.get("height") or 0) >= 480:
            def audio_score(f):
                aac_bonus = 2 if "mp4a" in (f.get("acodec") or "").lower() or f.get("ext") == "m4a" else 0
                return (aac_bonus, f.get("abr") or f.get("tbr") or 0)

            audio_streams.sort(key=audio_score, reverse=True)
            best_a = audio_streams[0]
            return ("SEPARATE_HD", best_v["format_id"], best_a["format_id"])

    # 3. Fallback to any pre-muxed stream (e.g. 360p itag 18)
    if muxed:
        muxed.sort(
            key=lambda f: (
                1 if f.get("ext") == "mp4" else 0,
                f.get("height") or 0,
                f.get("tbr") or 0,
            ),
            reverse=True,
        )
        return ("FALLBACK_MUXED", muxed[0]["format_id"], None)

    # 4. Fallback to any valid format
    return ("FALLBACK_ANY", None, None)


def download_video(url: str, output_path: str) -> str:
    """
    Downloads YouTube / Bilibili / social media video on Android via yt-dlp running inside
    the Chaquopy Python sandbox.

    Robust Multi-Tier Strategy:
      1. Probes all stream formats with TV/Android player client priority to bypass bot challenges.
      2. If separate HD video and audio exist, downloads using their exact format IDs and
         hands off to Kotlin FFmpegKit for instant lossless stream-copy muxing.
      3. If separate download fails or is unavailable, falls back to direct single-stream download.
      4. Never throws unhandled format errors; automatically tries cascading fallbacks.
    """
    out_dir = os.path.dirname(output_path)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    base_path, _ = os.path.splitext(output_path)

    is_youtube = bool(re.search(r"(youtube\.com|youtu\.be)", url, re.I))
    is_bilibili = bool(re.search(r"(bilibili\.com|b23\.tv)", url, re.I))

    headers = {}
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
    }
    if headers:
        base_opts["http_headers"] = headers

    if is_youtube:
        # Prioritize TV and Android clients which bypass PO-token bot checks while providing full stream formats
        base_opts["extractor_args"] = {
            "youtube": {
                "player_client": ["tv", "android", "web", "mweb", "ios"],
            }
        }

    try:
        # Step 1: Probe available formats
        info = None
        try:
            with yt_dlp.YoutubeDL(base_opts) as ydl_probe:
                info = ydl_probe.extract_info(url, download=False)
        except Exception as e_probe:
            # If TV/Android extractor client failed, retry with default extractor options
            if is_youtube and "extractor_args" in base_opts:
                fallback_opts = dict(base_opts)
                del fallback_opts["extractor_args"]
                try:
                    with yt_dlp.YoutubeDL(fallback_opts) as ydl_retry:
                        info = ydl_retry.extract_info(url, download=False)
                        base_opts = fallback_opts
                except Exception:
                    raise e_probe
            else:
                raise e_probe

        if info is None:
            return json.dumps({
                "success": False,
                "error": "yt-dlp returned no metadata for this URL.",
            })

        title = info.get("title", "Recap Source")
        duration = float(info.get("duration") or 0.0)
        formats = info.get("formats", [])

        mode, vid_id, aud_id = _select_formats(formats)

        # Step 2: Separate HD Video + Audio Download (for YouTube 720p/1080p)
        if mode == "SEPARATE_HD" and vid_id and aud_id:
            try:
                # 2A: Download Video Stream
                video_outtmpl = base_path + ".video.%(ext)s"
                opts_v = dict(base_opts)
                opts_v["format"] = vid_id
                opts_v["outtmpl"] = video_outtmpl

                with yt_dlp.YoutubeDL(opts_v) as ydl_v:
                    ydl_v.download([url])

                actual_v = _find_actual_file(base_path + ".video")
                if not actual_v and os.path.isdir(out_dir):
                    for cand in os.listdir(out_dir):
                        if cand.startswith(os.path.basename(base_path) + ".video"):
                            actual_v = os.path.join(out_dir, cand)
                            break

                # 2B: Download Audio Stream
                audio_outtmpl = base_path + ".audio.%(ext)s"
                opts_a = dict(base_opts)
                opts_a["format"] = aud_id
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
            except Exception:
                # If separate streams download failed, gracefully fall through to single-stream fallback
                pass

        # Step 3: Single-file download (Pre-muxed or Fallback)
        single_opts = dict(base_opts)
        target_fmt = vid_id if (mode in ("DIRECT_MUXED", "FALLBACK_MUXED") and vid_id) else None
        if target_fmt:
            single_opts["format"] = f"{target_fmt}/best[ext=mp4]/best[vcodec!=none][acodec!=none]/best"
        else:
            single_opts["format"] = "best[ext=mp4]/best[vcodec!=none][acodec!=none]/best"

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
        if "sign in to confirm you're not a bot" in err.lower():
            err = (
                "⚠️ YouTube Bot Check: This VPN server IP is temporarily challenged by YouTube.\n"
                "💡 Quick Fixes:\n"
                "1. Click '📁 Pick Video from Gallery' to select a downloaded video directly.\n"
                "2. Switch your VPN location (e.g. Singapore -> Japan/US/Taiwan).\n"
                "3. Turn off VPN or use split-tunneling if YouTube is accessible directly."
            )
        elif "requested format is not available" in err.lower():
            err = (
                "No compatible video format was available for this URL. "
                "Try a different video or check if the video is age-restricted/private. "
                f"(yt-dlp detail: {err})"
            )
        return json.dumps({"success": False, "error": err})
    except Exception as exc:
        return json.dumps({"success": False, "error": str(exc)})
