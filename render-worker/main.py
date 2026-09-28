import subprocess
import json
import logging
import os
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
        "--extractor-args", "youtube:player_client=android,ios",
        "--dump-json",
        "--no-playlist",
        "--no-check-certificates",
        url
    ]

    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
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

    # Build command forcing android,ios player clients to bypass Botguard
    cmd = [
        "yt-dlp",
        "--extractor-args", "youtube:player_client=android,ios",
        "-f", "18/best[ext=mp4]/best",
        "-o", "-",
        "--no-playlist",
        "--no-part",
        "--no-check-certificates",
    ]

    # Optional: If user provides cookies in Render environment variable
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
            stderr=subprocess.DEVNULL,
            bufsize=128 * 1024
        )

        # Read first chunk to ensure stream is valid before sending HTTP 200 headers
        first_chunk = proc.stdout.read(64 * 1024)
        if not first_chunk:
            try:
                proc.kill()
            except Exception:
                pass
            logger.warn("yt-dlp produced 0 bytes. YouTube blocked the datacenter IP or video is restricted.")
            raise HTTPException(
                status_code=502,
                detail="YouTube data-center IP restriction (BotGuard). Please use the 1-Click Helper to download."
            )

        def iter_stream():
            try:
                yield first_chunk
                while True:
                    chunk = proc.stdout.read(64 * 1024)
                    if not chunk:
                        break
                    yield chunk
            except Exception as e:
                logger.error(f"Stream generation error: {e}")
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
        logger.error(f"Failed to start download process: {e}")
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 10000))
    uvicorn.run(app, host="0.0.0.0", port=port)
