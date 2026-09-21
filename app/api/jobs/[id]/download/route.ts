import { NextRequest, NextResponse } from "next/server";
import { getJobById } from "@/lib/db/jobs";
import { cleanupPostDownload } from "@/lib/cleanup";
import path from "path";
import fs from "fs";

interface RouteParams {
  params: {
    id: string;
  };
}

export async function GET(
  _request: NextRequest,
  { params }: RouteParams
) {
  try {
    const { id } = params;
    const job = await getJobById(id);

    if (!job) {
      return NextResponse.json(
        { code: "NOT_FOUND", message: `Job '${id}' was not found.` },
        { status: 404 }
      );
    }

    const assetType = _request.nextUrl.searchParams.get("asset");

    // Support streaming dubbed narration audio before or after video rendering
    if (assetType === "voice" || assetType === "audio") {
      const allowedVoiceStatuses = ["VOICE_READY", "RENDERING", "RENDERED", "READY"];
      if (!allowedVoiceStatuses.includes(job.status)) {
        return NextResponse.json(
          {
            code: "VOICE_NOT_READY",
            message: `Voice audio is still being synthesized (current status: ${job.status}).`,
          },
          { status: 409 }
        );
      }

      const voicePath = path.join(process.cwd(), "storage", "jobs", id, "voice", "voice.wav");
      if (!fs.existsSync(voicePath)) {
        return NextResponse.json(
          {
            code: "VOICE_FILE_NOT_FOUND",
            message: "Dubbed voice audio file not found on disk.",
          },
          { status: 404 }
        );
      }

      const stat = fs.statSync(voicePath);
      const fileStream = fs.createReadStream(voicePath);
      const webStream = new ReadableStream({
        start(controller) {
          fileStream.on("data", (chunk) => controller.enqueue(chunk));
          fileStream.on("end", () => controller.close());
          fileStream.on("error", (err) => controller.error(err));
        },
      });

      return new Response(webStream, {
        headers: {
          "Content-Type": "audio/wav",
          "Content-Length": stat.size.toString(),
          "Content-Disposition": `inline; filename="dubbed_voice_${id}.wav"`,
          "Accept-Ranges": "bytes",
        },
      });
    }

    if (job.status !== "READY") {
      return NextResponse.json(
        {
          code: "JOB_NOT_READY",
          message: `Job is currently in '${job.status}' state (progress: ${job.progress}%). Final download is only accessible when status is 'READY'.`,
          status: job.status,
          progress: job.progress,
        },
        { status: 409 }
      );
    }

    const fallbackPath = path.join(process.cwd(), "storage", "jobs", id, "render", "final.mp4");
    const finalAsset = job.assets.find((a) => a.type === "FINAL_VIDEO");
    
    if (!finalAsset && !fs.existsSync(fallbackPath)) {
      return NextResponse.json(
        {
          code: "ASSET_NOT_FOUND",
          message: "Rendered final video asset could not be located.",
        },
        { status: 404 }
      );
    }

    // Check expiration
    if (finalAsset?.expiresAt && finalAsset.expiresAt < new Date()) {
      return NextResponse.json(
        {
          code: "DOWNLOAD_EXPIRED",
          message: "This recap video download has expired.",
        },
        { status: 410 }
      );
    }

    // Check local filesystem storage
    const localPath = finalAsset
      ? path.join(process.cwd(), "storage", finalAsset.storageKey.replace(/^jobs\//, "jobs/"))
      : fallbackPath;

    if (!fs.existsSync(localPath)) {
      const hasDownloadCleanupEvent = job.events?.some((e) => e.stage === "CLEANUP_POST_DOWNLOAD");
      if (hasDownloadCleanupEvent) {
        return NextResponse.json(
          {
            code: "DOWNLOADED_AND_PURGED",
            message: "This recap video has already been downloaded and purged from server storage to free up disk space.",
          },
          { status: 410 }
        );
      }

      return NextResponse.json(
        {
          code: "ASSET_NOT_FOUND",
          message: "Rendered final video asset could not be located.",
        },
        { status: 404 }
      );
    }

    if (fs.existsSync(localPath)) {
      const isStream = _request.nextUrl.searchParams.get("stream") === "1";
      const disposition = isStream ? "inline" : `attachment; filename="recap_${id}.mp4"`;
      const stat = fs.statSync(localPath);
      const fileSize = stat.size;

      // Handle HTTP Range header for media players seeking / streaming
      const rangeHeader = _request.headers.get("range");
      if (rangeHeader && isStream) {
        const parts = rangeHeader.replace(/bytes=/, "").split("-");
        const start = parseInt(parts[0], 10);
        const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
        const chunkSize = end - start + 1;
        const fileStream = fs.createReadStream(localPath, { start, end });
        const webStream = new ReadableStream({
          start(controller) {
            fileStream.on("data", (chunk) => {
              try {
                controller.enqueue(chunk);
              } catch {
                fileStream.destroy();
              }
            });
            fileStream.on("end", () => {
              try { controller.close(); } catch {}
            });
            fileStream.on("error", (err) => {
              try { controller.error(err); } catch {}
            });
          },
          cancel() {
            fileStream.destroy();
          },
        });

        return new Response(webStream, {
          status: 206,
          headers: {
            "Content-Range": `bytes ${start}-${end}/${fileSize}`,
            "Accept-Ranges": "bytes",
            "Content-Length": chunkSize.toString(),
            "Content-Type": "video/mp4",
            "Content-Disposition": disposition,
          },
        });
      }

      let downloadCompleted = false;
      const fileStream = fs.createReadStream(localPath);
      const webStream = new ReadableStream({
        start(controller) {
          fileStream.on("data", (chunk) => {
            try {
              controller.enqueue(chunk);
            } catch {
              fileStream.destroy();
            }
          });
          fileStream.on("end", () => {
            downloadCompleted = true;
            try { controller.close(); } catch {}
          });
          fileStream.on("error", (err) => {
            try { controller.error(err); } catch {}
          });
          fileStream.on("close", () => {
            // If user performed an attachment download (not in-browser player stream)
            if (downloadCompleted && !isStream) {
              console.log(`[Download] File delivery complete for job ${id}. Scheduling post-download cleanup...`);
              setTimeout(() => {
                cleanupPostDownload(id).catch((cleanupErr) => {
                  console.error(`[Post-Download Cleanup] Error removing files for ${id}:`, cleanupErr);
                });
              }, 1500);
            }
          });
        },
        cancel() {
          fileStream.destroy();
        },
      });

      return new Response(webStream, {
        status: 200,
        headers: {
          "Content-Type": "video/mp4",
          "Content-Length": fileSize.toString(),
          "Content-Disposition": disposition,
          "Accept-Ranges": "bytes",
        },
      });
    }

    // Return download metadata if file is hosted externally
    return NextResponse.json({
      downloadUrl: finalAsset ? `/storage/${finalAsset.storageKey}` : `/api/jobs/${id}/download?stream=1`,
      expiresAt: finalAsset?.expiresAt,
      sizeBytes: finalAsset?.sizeBytes,
    });
  } catch (error) {
    console.error("Failed to process download request:", error);
    return NextResponse.json(
      { code: "INTERNAL_ERROR", message: "Failed to process download." },
      { status: 500 }
    );
  }
}
