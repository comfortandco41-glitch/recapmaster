import subprocess
import json
import logging
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("recapmaster-worker")

app = FastAPI(
    title="RecapMaster Video Worker",
    description="High-performance YouTube stream downloader microservice for RecapMaster Web",
    version="1.0.0"
)

# Allow requests from RecapMaster Web and localhost
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.get("/")
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
        "--extractor-args", "youtube:player_client=ios,android,web",
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

    # Format 18 = 360p/640x360 mp4 progressive (video+audio combined)
    # best[ext=mp4] = highest quality progressive mp4
    cmd = [
        "yt-dlp",
        "--extractor-args", "youtube:player_client=ios,android,web",
        "-f", "18/best[ext=mp4]/best",
        "-o", "-",
        "--no-playlist",
        "--no-check-certificates",
        url
    ]

    try:
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=64 * 1024
        )

        def iter_stream():
            try:
                while True:
                    chunk = proc.stdout.read(64 * 1024)
                    if not chunk:
                        break
                    yield chunk
            except Exception as e:
                logger.error(f"Streaming interrupted: {e}")
            finally:
                proc.stdout.close()
                proc.kill()

        return StreamingResponse(
            iter_stream(),
            media_type="video/mp4",
            headers={
                "Content-Disposition": 'inline; filename="recap_video.mp4"',
                "Access-Control-Allow-Origin": "*",
                "Cache-Control": "no-cache",
            }
        )
    except Exception as e:
        logger.error(f"Failed to start download process: {e}")
        raise HTTPException(status_code=500, detail=str(e))
