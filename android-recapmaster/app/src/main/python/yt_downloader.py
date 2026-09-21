import json
import os
import yt_dlp

def download_video(url: str, output_path: str) -> str:
    """
    Downloads YouTube or Bilibili video directly on Android via yt-dlp.
    Returns JSON string with metadata: title, duration, output_path, status.
    """
    out_dir = os.path.dirname(output_path)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    ydl_opts = {
        'format': 'bestvideo[height<=720]+bestaudio/best[height<=720]/best',
        'outtmpl': output_path,
        'merge_output_format': 'mp4',
        'quiet': True,
        'no_warnings': True,
        'noplaylist': True,
        'http_headers': {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        }
    }

    if "bilibili.com" in url or "b23.tv" in url:
        ydl_opts['http_headers']['Referer'] = 'https://www.bilibili.com'

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
        title = info.get('title', 'Recap Source')
        duration = float(info.get('duration') or 0.0)

        # In case yt-dlp added an extension like .mp4 or .mkv
        actual_path = output_path
        if not os.path.exists(actual_path):
            for ext in ['.mp4', '.mkv', '.webm']:
                cand = output_path + ext
                if os.path.exists(cand):
                    actual_path = cand
                    break

        return json.dumps({
            'success': True,
            'title': title,
            'duration': duration,
            'filePath': actual_path
        })
