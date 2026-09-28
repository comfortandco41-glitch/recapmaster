import { NextRequest, NextResponse } from "next/server";
import path from "path";
import fs from "fs";
import { spawn } from "child_process";
import { Readable } from "stream";
import { validateMediaUrl } from "@/lib/validation/url";

export const dynamic = "force-dynamic";

function getYtDlpPath(): string | null {
  const isWin = process.platform === "win32";
  const binaryName = isWin ? "yt-dlp.exe" : "yt-dlp";
  const localBin = path.join(process.cwd(), "bin", binaryName);
  if (fs.existsSync(localBin)) {
    return localBin;
  }
  return null;
}

async function fetchOEmbedMetadata(url: string, videoId?: string) {
  try {
    const oembedUrl = `https://www.youtube.com/oembed?url=${encodeURIComponent(url)}&format=json`;
    const res = await fetch(oembedUrl);
    if (!res.ok) return null;
    const data = await res.json();
    return {
      title: data.title || "YouTube Video",
      channel: data.author_name || "YouTube Creator",
      thumbnail: data.thumbnail_url || (videoId ? `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg` : ""),
    };
  } catch (err) {
    console.warn("oEmbed fetch failed:", err);
    return null;
  }
}

// POST /api/youtube - Fetch video info (100% resilient on Vercel Serverless)
export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { url } = body;

    if (!url || typeof url !== "string") {
      return NextResponse.json({ error: "Missing or invalid URL" }, { status: 400 });
    }

    const validation = validateMediaUrl(url);
    if (!validation.isValid) {
      return NextResponse.json({ error: validation.error || "Invalid YouTube video URL" }, { status: 400 });
    }

    const canonicalUrl = validation.canonicalUrl || url;
    const videoId = validation.videoId || "";

    // 1. Try local yt-dlp binary if available (e.g. Local machine or VPS)
    const binary = getYtDlpPath();
    if (binary) {
      try {
        const args = [
          "--extractor-args",
          "youtube:player_client=ios,android,web",
          "--dump-json",
          "--no-playlist",
          "--no-check-certificates",
          canonicalUrl,
        ];

        const proc = spawn(binary, args);
        let stdoutData = "";
        let stderrData = "";

        proc.stdout.on("data", (chunk) => { stdoutData += chunk.toString(); });
        proc.stderr.on("data", (chunk) => { stderrData += chunk.toString(); });

        const exitCode = await new Promise<number | null>((resolve) => {
          proc.on("close", resolve);
          proc.on("error", () => resolve(-1));
        });

        if (exitCode === 0 && stdoutData) {
          const info = JSON.parse(stdoutData);
          return NextResponse.json({
            success: true,
            hasYtDlp: true,
            videoId: info.id || videoId,
            title: info.title || "YouTube Video",
            duration: Math.round(info.duration || 0),
            thumbnail: info.thumbnail || (info.thumbnails && info.thumbnails[0]?.url) || (videoId ? `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg` : ""),
            channel: info.uploader || info.channel || "",
            canonicalUrl,
            externalDownloadUrl: `https://y2mate.is/watch?v=${videoId}`,
          });
        }
      } catch (ytErr) {
        console.warn("Local yt-dlp failed, falling back to oEmbed:", ytErr);
      }
    }

    // 2. Serverless Cloud Fallback (Vercel): Pure HTTP oEmbed (Zero external binary, never blocked)
    const oembed = await fetchOEmbedMetadata(canonicalUrl, videoId);
    if (oembed) {
      return NextResponse.json({
        success: true,
        hasYtDlp: false,
        videoId,
        title: oembed.title,
        duration: 0,
        thumbnail: oembed.thumbnail,
        channel: oembed.channel,
        canonicalUrl,
        externalDownloadUrl: `https://y2mate.is/watch?v=${videoId}`,
      });
    }

    // 3. Fallback with video ID
    if (videoId) {
      return NextResponse.json({
        success: true,
        hasYtDlp: false,
        videoId,
        title: `YouTube Video (${videoId})`,
        duration: 0,
        thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
        channel: "YouTube",
        canonicalUrl,
        externalDownloadUrl: `https://y2mate.is/watch?v=${videoId}`,
      });
    }

    return NextResponse.json(
      { error: "Could not retrieve YouTube video details. Please verify the link is accessible." },
      { status: 404 }
    );
  } catch (err: any) {
    console.error("YouTube info API error:", err);
    return NextResponse.json({ error: err.message || "Failed to process YouTube request" }, { status: 500 });
  }
}

// GET /api/youtube?url=... - Stream video stream (when running with yt-dlp)
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
    if (!binary) {
      return NextResponse.json(
        {
          error: "Cloud serverless environment does not support direct streaming due to platform execution limits. Please use the 1-Click download helper button or our Android App to import your video.",
          externalDownloadUrl: `https://y2mate.is/watch?v=${validation.videoId}`,
        },
        { status: 501 }
      );
    }

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
      console.warn("yt-dlp stream spawn error:", err.message);
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
