import { NextRequest, NextResponse } from "next/server";
import path from "path";
import fs from "fs";
import { spawn } from "child_process";
import { Readable } from "stream";
import { validateMediaUrl } from "@/lib/validation/url";

export const dynamic = "force-dynamic";

function getYtDlpPath(): string | null {
  const isWin = process.platform === "win32";
  const binaryName = isWin ? "yt-dlp.exe" : "yt-dlp-linux";
  const localBin = path.join(process.cwd(), "bin", binaryName);

  if (fs.existsSync(localBin)) {
    if (!isWin) {
      const tmpBin = "/tmp/yt-dlp";
      try {
        if (!fs.existsSync(tmpBin) || fs.statSync(tmpBin).size !== fs.statSync(localBin).size) {
          fs.copyFileSync(localBin, tmpBin);
          fs.chmodSync(tmpBin, 0o755);
        }
        return tmpBin;
      } catch (err) {
        console.warn("Failed to copy yt-dlp to /tmp, using localBin:", err);
      }
    }
    return localBin;
  }

  // Fallback to system yt-dlp on Linux / Docker / Hugging Face
  return isWin ? null : "yt-dlp";
}

function getSpawnParams(binaryPath: string, args: string[]): { cmd: string; fullArgs: string[] } {
  if (process.platform !== "win32" && !binaryPath.endsWith(".exe")) {
    return { cmd: "python3", fullArgs: [binaryPath, ...args] };
  }
  return { cmd: binaryPath, fullArgs: args };
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
      return NextResponse.json({ error: validation.error || "Invalid YouTube video URL" }, { status: 400 });
    }

    const canonicalUrl = validation.canonicalUrl || url;
    const videoId = validation.videoId || "";

    // 1. Try yt-dlp binary (Windows exe or Linux binary)
    const binary = getYtDlpPath();
    if (binary) {
      try {
        const rawArgs = [
          "--extractor-args",
          "youtube:player_client=ios,android,web",
          "--dump-json",
          "--no-playlist",
          "--no-check-certificates",
          canonicalUrl,
        ];
        const { cmd, fullArgs } = getSpawnParams(binary, rawArgs);

        const proc = spawn(cmd, fullArgs);
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
        console.warn("yt-dlp execution error, falling back to oEmbed:", ytErr);
      }
    }

    // 2. Fallback to official Google oEmbed (works on any cloud environment)
    const oembed = await fetchOEmbedMetadata(canonicalUrl, videoId);
    if (oembed) {
      return NextResponse.json({
        success: true,
        hasYtDlp: true, // Allow user to attempt direct download
        videoId,
        title: oembed.title,
        duration: 0,
        thumbnail: oembed.thumbnail,
        channel: oembed.channel,
        canonicalUrl,
        externalDownloadUrl: `https://y2mate.is/watch?v=${videoId}`,
      });
    }

    // 3. Fallback with basic Video ID
    if (videoId) {
      return NextResponse.json({
        success: true,
        hasYtDlp: true,
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

// GET /api/youtube?url=... - Stream direct MP4 into the browser
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
          error: "Direct stream downloader binary not available in this container. Please use the 1-click download button to load the file.",
          externalDownloadUrl: `https://y2mate.is/watch?v=${validation.videoId}`,
        },
        { status: 501 }
      );
    }

    const rawArgs = [
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

    const { cmd, fullArgs } = getSpawnParams(binary, rawArgs);
    const proc = spawn(cmd, fullArgs);

    let stderrOutput = "";
    proc.stderr.on("data", (chunk) => {
      stderrOutput += chunk.toString();
    });

    // Wait for the first chunk of video data, error, or process exit
    const firstChunk = await new Promise<Buffer | null>((resolve, reject) => {
      let settled = false;

      const onData = (chunk: Buffer) => {
        if (!settled) {
          settled = true;
          proc.stdout.off("data", onData);
          proc.off("error", onError);
          proc.off("close", onClose);
          resolve(chunk);
        }
      };

      const onError = (err: Error) => {
        if (!settled) {
          settled = true;
          proc.stdout.off("data", onData);
          proc.off("error", onError);
          proc.off("close", onClose);
          reject(err);
        }
      };

      const onClose = (code: number) => {
        if (!settled) {
          settled = true;
          proc.stdout.off("data", onData);
          proc.off("error", onError);
          proc.off("close", onClose);
          resolve(null);
        }
      };

      proc.stdout.on("data", onData);
      proc.on("error", onError);
      proc.on("close", onClose);
    }).catch((err) => {
      console.warn("yt-dlp stream process failed to start:", err.message);
      return null;
    });

    if (!firstChunk) {
      console.warn("yt-dlp produced no stream data. stderr:", stderrOutput.slice(-300));
      return NextResponse.json(
        {
          error: "Cloud Serverless (Vercel) IP ကန့်သတ်ချက်ကြောင့် YouTube ဗီဒီယို stream ကို တိုက်ရိုက်ဆွဲ၍ မရနိုင်သေးပါ။ အောက်ပါ 1-Click Helper ဖြင့် ရယူနိုင်ပါသည်။",
          externalDownloadUrl: `https://y2mate.is/watch?v=${validation.videoId}`,
        },
        { status: 502 }
      );
    }

    // Put first chunk back into the stream
    proc.stdout.unshift(firstChunk);

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
