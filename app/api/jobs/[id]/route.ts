import { NextRequest, NextResponse } from "next/server";
import { getJobById, deleteJobRecord } from "@/lib/db/jobs";
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
    if (!id) {
      return NextResponse.json(
        { code: "BAD_REQUEST", message: "Job ID is required" },
        { status: 400 }
      );
    }

    const job = await getJobById(id);
    if (!job) {
      return NextResponse.json(
        { code: "NOT_FOUND", message: `Job '${id}' was not found.` },
        { status: 404 }
      );
    }

    const finalVideoPath = path.join(process.cwd(), "storage", "jobs", id, "render", "final.mp4");
    const sourcePath = path.join(process.cwd(), "storage", "jobs", id, "source");
    const hasFinalVideo = fs.existsSync(finalVideoPath);
    const hasSourceVideo = fs.existsSync(sourcePath);
    const isPurged = !hasFinalVideo && (job.status === "READY" || job.events.some((e) => e.stage === "CLEANUP_POST_DOWNLOAD"));

    return NextResponse.json({
      id: job.id,
      userId: job.userId,
      sourceUrl: job.sourceUrl,
      sourcePlatform: job.sourcePlatform,
      status: job.status,
      progress: job.progress,
      errorCode: job.errorCode,
      errorMessage: job.errorMessage,
      language: job.language,
      voice: job.voice,
      subtitlePlacement: job.subtitlePlacement ?? "bottom",
      subtitleSize: job.subtitleSize ?? 1.0,
      subtitleMarginV: job.subtitleMarginV ?? 30,
      blurBoxConfig: job.blurBoxConfig ?? null,
      soundStyle: job.soundStyle ?? "cinematic_recap",
      voiceRate: job.voiceRate ?? "+10%",
      voicePitch: job.voicePitch ?? "-2Hz",
      bgMusicVolume: job.bgMusicVolume ?? 0.15,
      playbackSpeed: job.playbackSpeed ?? 1.0,
      hasFinalVideo,
      hasSourceVideo,
      isPurged,
      createdAt: job.createdAt.toISOString(),
      startedAt: job.startedAt?.toISOString() ?? null,
      completedAt: job.completedAt?.toISOString() ?? null,
      expiresAt: job.expiresAt?.toISOString() ?? null,
      deletedAt: job.deletedAt?.toISOString() ?? null,
      events: job.events.map((e) => ({
        id: e.id,
        stage: e.stage,
        message: e.message,
        progress: e.progress,
        createdAt: e.createdAt.toISOString(),
      })),
      assets: job.assets.map((a) => ({
        id: a.id,
        type: a.type,
        storageKey: a.storageKey,
        mimeType: a.mimeType,
        sizeBytes: a.sizeBytes,
        createdAt: a.createdAt.toISOString(),
        expiresAt: a.expiresAt?.toISOString() ?? null,
      })),
    });
  } catch (error) {
    console.error("Failed to fetch job:", error);
    return NextResponse.json(
      { code: "INTERNAL_ERROR", message: "Failed to retrieve job details." },
      { status: 500 }
    );
  }
}

export async function DELETE(
  _request: NextRequest,
  { params }: RouteParams
) {
  try {
    const { id } = params;
    const existing = await getJobById(id);
    if (!existing) {
      return NextResponse.json(
        { code: "NOT_FOUND", message: `Job '${id}' not found.` },
        { status: 404 }
      );
    }

    await deleteJobRecord(id);
    return NextResponse.json({
      message: `Job '${id}' cancelled and marked for deletion.`,
    });
  } catch (error) {
    console.error("Failed to delete job:", error);
    return NextResponse.json(
      { code: "INTERNAL_ERROR", message: "Failed to cancel job." },
      { status: 500 }
    );
  }
}
