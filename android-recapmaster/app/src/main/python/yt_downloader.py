import json
import os
import re
import yt_dlp


def _find_actual_file(base_path: str) -> str | None:
    """
    yt-dlp often writes the file with a different extension than requested.
    Search for the actual output file by trying common video extensions.
    """
    if os.path.exists(base_path) and os.path.getsize(base_path) > 0:
        return base_path
    for ext in [".mp4", ".webm", ".mkv", ".m4v", ".avi", ".mov", ".flv", ".3gp"]:
        cand = base_path + ext
        if os.path.exists(cand) and os.path.getsize(cand) > 0:
            return cand
    # Also check without the original extension in case outtmpl had one
    base_noext, _ = os.path.splitext(base_path)
    for ext in [".mp4", ".webm", ".mkv", ".m4v", ".avi", ".mov", ".flv"]:
        cand = base_noext + ext
        if cand != base_path and os.path.exists(cand) and os.path.getsize(cand) > 0:
            return cand
    return None


def download_video(url: str, output_path: str) -> str:
    """
    Downloads YouTube / Bilibili video on Android via yt-dlp running inside the
    Chaquopy Python sandbox.

    KEY CONSTRAINT: ffmpeg is NOT available in the Chaquopy sandbox. yt-dlp
    must never attempt to merge separate video+audio streams, because that
    requires ffmpeg. We use a carefully ordered format selector that only picks
    pre-muxed single-file formats (no '+' combiner, no merge_output_format).

    YouTube-specific: We tell yt-dlp to use the iOS extractor client, which
    YouTube serves as fully pre-muxed .mp4 files at up to 1080p — no merging
    required at all.

    Returns a JSON string: {"success": true, "title": ..., "duration": ...,
    "filePath": ...} on success, or {"success": false, "error": ...} on failure.
    """
    out_dir = os.path.dirname(output_path)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    # Strip the extension from output_path so yt-dlp can add its own.
    # If we hard-code .mp4 but yt-dlp picks a .webm format, it renames and
    # the file-finder below handles it. Using %(ext)s avoids confusion.
    base_path, _ = os.path.splitext(output_path)
    outtmpl = base_path + ".%(ext)s"

    # -----------------------------------------------------------------------
    # Format selector — NO '+' combiners, NO merge_output_format.
    #
    # Strategy:
    #   1. mp4 pre-muxed ≤720p  (iOS client serves these natively)
    #   2. mp4 pre-muxed ≤1080p
    #   3. any pre-muxed mp4
    #   4. any pre-muxed ≤720p  (webm/mkv accepted — FFmpegEngine handles them)
    #   5. any pre-muxed ≤1080p
    #   6. absolute best single-stream (last resort, no merge)
    # -----------------------------------------------------------------------
    FORMAT = (
        "best[ext=mp4][height<=720][vcodec!=none][acodec!=none]"
        "/best[ext=mp4][height<=1080][vcodec!=none][acodec!=none]"
        "/best[ext=mp4][vcodec!=none][acodec!=none]"
        "/best[height<=720][vcodec!=none][acodec!=none]"
        "/best[height<=1080][vcodec!=none][acodec!=none]"
        "/best[vcodec!=none][acodec!=none]"
        "/best"
    )

    is_youtube = bool(re.search(r"(youtube\.com|youtu\.be)", url, re.I))
    is_bilibili = bool(re.search(r"(bilibili\.com|b23\.tv)", url, re.I))

    ydl_opts = {
        "format": FORMAT,
        "outtmpl": outtmpl,
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        # Never let yt-dlp abort just because it wants to merge
        "abort_on_error": False,
        # Allow downloading even if the container is not mp4
        "continuedl": True,
        "http_headers": {
            "User-Agent": (
                "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
            ),
        },
    }

    # YouTube: force the iOS player client — returns fully pre-muxed mp4
    # streams up to 1080p without needing any ffmpeg merge.
    if is_youtube:
        ydl_opts["extractor_args"] = {
            "youtube": {
                "player_client": ["ios", "android", "web"],
                "skip": ["dash", "hls"],  # skip DASH/HLS which require merging
            }
        }

    # Bilibili: add required Referer header
    if is_bilibili:
        ydl_opts["http_headers"]["Referer"] = "https://www.bilibili.com"
        ydl_opts["http_headers"]["User-Agent"] = (
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Mobile Safari/537.36"
        )

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)

            if info is None:
                return json.dumps({
                    "success": False,
                    "error": "yt-dlp returned no info for this URL.",
                })

            title = info.get("title", "Recap Source")
            duration = float(info.get("duration") or 0.0)

            # Find actual downloaded file
            actual_path = _find_actual_file(base_path + ".mp4")
            if actual_path is None:
                # Try with the ext yt-dlp chose
                chosen_ext = info.get("ext", "")
                if chosen_ext:
                    actual_path = _find_actual_file(base_path + "." + chosen_ext)
            if actual_path is None:
                # Broad scan of the directory
                parent = os.path.dirname(base_path)
                if os.path.isdir(parent):
                    for fname in os.listdir(parent):
                        fpath = os.path.join(parent, fname)
                        if os.path.isfile(fpath) and os.path.getsize(fpath) > 1024:
                            actual_path = fpath
                            break

            if actual_path is None:
                return json.dumps({
                    "success": False,
                    "error": (
                        "Download seemed to complete but no output file was found on disk. "
                        f"Expected near: {base_path}"
                    ),
                })

            return json.dumps({
                "success": True,
                "title": title,
                "duration": duration,
                "filePath": actual_path,
                "ext": os.path.splitext(actual_path)[1].lstrip("."),
            })

    except yt_dlp.utils.DownloadError as de:
        err = str(de)
        # Give a clearer message for the common format-not-available case
        if "requested format is not available" in err.lower():
            err = (
                "No pre-muxed video format was available for this URL. "
                "Try a different video or check if the video is age-restricted/private. "
                f"(yt-dlp detail: {err})"
            )
        return json.dumps({"success": False, "error": err})

    except Exception as exc:
        return json.dumps({"success": False, "error": str(exc)})
