import { NextRequest, NextResponse } from "next/server";
import { getJobById } from "@/lib/db/jobs";
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

    const finalAsset = job.assets.find((a) => a.type === "FINAL_VIDEO");
    if (!finalAsset) {
      return NextResponse.json(
        {
          code: "ASSET_NOT_FOUND",
          message: "Rendered final video asset could not be located.",
        },
        { status: 404 }
      );
    }

    // Check expiration
    if (finalAsset.expiresAt && finalAsset.expiresAt < new Date()) {
      return NextResponse.json(
        {
          code: "DOWNLOAD_EXPIRED",
          message: "This recap video download has expired.",
        },
        { status: 410 }
      );
    }

    // Check local filesystem storage
    const localPath = path.join(process.cwd(), "storage", finalAsset.storageKey.replace(/^jobs\//, "jobs/"));
    if (fs.existsSync(localPath)) {
      const stat = fs.statSync(localPath);
      const fileStream = fs.createReadStream(localPath);
      // Convert node stream to web ReadableStream
      const webStream = new ReadableStream({
        start(controller) {
          fileStream.on("data", (chunk) => controller.enqueue(chunk));
          fileStream.on("end", () => controller.close());
          fileStream.on("error", (err) => controller.error(err));
        },
      });

      return new Response(webStream, {
        headers: {
          "Content-Type": "video/mp4",
          "Content-Length": stat.size.toString(),
          "Content-Disposition": `attachment; filename="recap_${id}.mp4"`,
        },
      });
    }

    // Return download metadata if file is hosted externally
    return NextResponse.json({
      downloadUrl: `/storage/${finalAsset.storageKey}`,
      expiresAt: finalAsset.expiresAt,
      sizeBytes: finalAsset.sizeBytes,
    });
  } catch (error) {
    console.error("Failed to process download request:", error);
    return NextResponse.json(
      { code: "INTERNAL_ERROR", message: "Failed to process download." },
      { status: 500 }
    );
  }
}
