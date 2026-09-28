import subprocess
import json
import logging
import os
import threading
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("recapmaster-worker")

app = FastAPI(
    title="RecapMaster Video Worker",
    description="High-performance YouTube stream downloader microservice for RecapMaster Web",
    version="1.0.0"
)

# Clean CORS for wildcard origin without credential conflicts
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST", "HEAD", "OPTIONS"],
    allow_headers=["*"],
    expose_headers=["Content-Disposition", "Content-Length", "Content-Type"],
)

# Render health checks use HEAD or GET requests
@app.api_route("/", methods=["GET", "HEAD"])
def health_check():
    version = subprocess.getoutput("yt-dlp --version")
    return {
        "status": "online",
        "service": "RecapMaster Render Worker",
        "yt_dlp_version": version
    }

@app.get("/info")
def get_video_info(url: str = Query(..., description="YouTube video URL")):
    if not url:
        raise HTTPException(status_code=400, detail="URL parameter required")

    cmd = [
        "yt-dlp",
        "--extractor-args", "youtube:player_client=tv,tv_embedded,mweb,ios",
        "--dump-json",
        "--no-playlist",
        "--no-check-certificates",
        url
    ]

    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
        if res.returncode != 0:
            raise HTTPException(status_code=400, detail=res.stderr.strip() or "Failed to fetch video info")
        
        data = json.loads(res.stdout)
        return {
            "success": True,
            "id": data.get("id"),
            "title": data.get("title"),
            "duration": round(data.get("duration", 0)),
            "thumbnail": data.get("thumbnail"),
            "channel": data.get("uploader") or data.get("channel"),
        }
    except Exception as e:
        logger.error(f"Error fetching video info: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/download")
def download_stream(url: str = Query(..., description="YouTube video URL")):
    if not url:
        raise HTTPException(status_code=400, detail="URL parameter required")

    logger.info(f"Starting download stream for: {url}")

    # TV & iOS player clients bypass Botguard / JS challenge on datacenter IPs
    cmd = [
        "yt-dlp",
        "--extractor-args", "youtube:player_client=tv,tv_embedded,mweb,ios",
        "--user-agent", "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebkit/538.1 (KHTML, like Gecko) SamsungBrowser/4.0 TV Safari/538.1",
        "-f", "best[ext=mp4]/18/best",
        "-o", "-",
        "--no-playlist",
        "--no-part",
        "--no-check-certificates",
    ]

    # Optional cookies if provided in Render environment variable
    cookies_data = os.environ.get("YOUTUBE_COOKIES")
    if cookies_data:
        cookie_file = "/tmp/yt_cookies.txt"
        with open(cookie_file, "w") as f:
            f.write(cookies_data)
        cmd.extend(["--cookies", cookie_file])

    cmd.append(url)

    try:
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=128 * 1024
        )

        # Background thread to continuously drain stderr to avoid deadlocks & capture logs
        stderr_logs = []
        def read_stderr():
            try:
                for line in iter(proc.stderr.readline, b""):
                    decoded = line.decode("utf-8", errors="replace")
                    stderr_logs.append(decoded)
                    if len(stderr_logs) > 60:
                        stderr_logs.pop(0)
            except Exception:
                pass

        t = threading.Thread(target=read_stderr, daemon=True)
        t.start()

        # Read first chunk to verify the stream actually started
        first_chunk = proc.stdout.read(64 * 1024)
        if not first_chunk:
            try:
                proc.kill()
            except Exception:
                pass
            full_err = "".join(stderr_logs).strip()
            logger.error(f"yt-dlp failed to produce stream. stderr:\n{full_err}")
            
            # Format clean message for user
            detail_msg = "YouTube BotGuard blocked this video on server IP. Please use the 1-Click Helper below."
            if "Sign in to confirm" in full_err:
                detail_msg = "YouTube requires login verification (BotGuard). Please use the 1-Click Helper to download."
            elif "Video unavailable" in full_err:
                detail_msg = "YouTube video is unavailable or private."

            raise HTTPException(status_code=502, detail=detail_msg)

        def iter_stream():
            try:
                yield first_chunk
                while True:
                    chunk = proc.stdout.read(64 * 1024)
                    if not chunk:
                        break
                    yield chunk
            except Exception as e:
                logger.error(f"Stream interrupted: {e}")
            finally:
                try:
                    proc.stdout.close()
                    proc.kill()
                except Exception:
                    pass

        return StreamingResponse(
            iter_stream(),
            media_type="video/mp4",
            headers={
                "Content-Disposition": 'inline; filename="recap_video.mp4"',
                "Cache-Control": "no-cache",
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Download server error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 10000))
    uvicorn.run(app, host="0.0.0.0", port=port)
