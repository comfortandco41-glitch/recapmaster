import { NextRequest, NextResponse } from "next/server";
import path from "path";
import fs from "fs";
import { spawn } from "child_process";
import { Readable } from "stream";
import { validateMediaUrl } from "@/lib/validation/url";

export const dynamic = "force-dynamic";

function getYtDlpPath(): string {
  const isWin = process.platform === "win32";
  const binaryName = isWin ? "yt-dlp.exe" : "yt-dlp";
  const localBin = path.join(process.cwd(), "bin", binaryName);
  if (fs.existsSync(localBin)) {
    return localBin;
  }
  return binaryName;
}

// POST /api/youtube - Fetch video info
export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { url } = body;

    if (!url || typeof url !== "string") {
      return NextResponse.json({ error: "Missing or invalid URL" }, { status: 400 });
    }

    const validation = validateMediaUrl(url);
    if (!validation.isValid) {
      return NextResponse.json({ error: validation.error || "Invalid video URL" }, { status: 400 });
    }

    const binary = getYtDlpPath();
    const args = [
      "--extractor-args",
      "youtube:player_client=ios,android,web",
      "--dump-json",
      "--no-playlist",
      "--no-check-certificates",
      validation.canonicalUrl || url,
    ];

    const proc = spawn(binary, args);

    let stdoutData = "";
    let stderrData = "";

    proc.stdout.on("data", (chunk) => {
      stdoutData += chunk.toString();
    });

    proc.stderr.on("data", (chunk) => {
      stderrData += chunk.toString();
    });

    const exitCode = await new Promise<number | null>((resolve) => {
      proc.on("close", resolve);
      proc.on("error", () => resolve(-1));
    });

    if (exitCode !== 0 || !stdoutData) {
      console.error("yt-dlp error:", stderrData);
      return NextResponse.json(
        { error: "Failed to extract YouTube video information. The video may be private, age-restricted, or region-locked." },
        { status: 500 }
      );
    }

    try {
      const info = JSON.parse(stdoutData);
      return NextResponse.json({
        success: true,
        videoId: info.id,
        title: info.title || "YouTube Video",
        duration: Math.round(info.duration || 0),
        thumbnail: info.thumbnail || (info.thumbnails && info.thumbnails[0]?.url) || "",
        channel: info.uploader || info.channel || "",
        canonicalUrl: validation.canonicalUrl || url,
      });
    } catch (parseErr) {
      return NextResponse.json({ error: "Failed to parse YouTube video metadata" }, { status: 500 });
    }
  } catch (err: any) {
    console.error("YouTube info API error:", err);
    return NextResponse.json({ error: err.message || "Internal server error" }, { status: 500 });
  }
}

// GET /api/youtube?url=... - Stream video stream
export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const rawUrl = searchParams.get("url");

    if (!rawUrl) {
      return NextResponse.json({ error: "URL query parameter is required" }, { status: 400 });
    }

    const validation = validateMediaUrl(rawUrl);
    if (!validation.isValid) {
      return NextResponse.json({ error: validation.error || "Invalid video URL" }, { status: 400 });
    }

    const binary = getYtDlpPath();
    const args = [
      "--extractor-args",
      "youtube:player_client=ios,android,web",
      "-f",
      "18/best[ext=mp4]/best",
      "-o",
      "-",
      "--no-playlist",
      "--no-check-certificates",
      validation.canonicalUrl || rawUrl,
    ];

    const proc = spawn(binary, args);

    proc.on("error", (err) => {
      console.warn("yt-dlp stream spawn error (likely serverless environment):", err.message);
    });

    proc.stderr.on("data", (chunk) => {
      const msg = chunk.toString();
      if (msg.includes("ERROR")) {
        console.error("yt-dlp stream error:", msg);
      }
    });

    const webStream = Readable.toWeb(proc.stdout) as ReadableStream<Uint8Array>;

    return new Response(webStream, {
      headers: {
        "Content-Type": "video/mp4",
        "Content-Disposition": 'inline; filename="youtube_video.mp4"',
        "Cache-Control": "no-cache",
      },
    });
  } catch (err: any) {
    console.error("YouTube download stream error:", err);
    return NextResponse.json({ error: err.message || "Failed to download video stream" }, { status: 500 });
  }
}
