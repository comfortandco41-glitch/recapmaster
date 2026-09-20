import { NextRequest, NextResponse } from "next/server";
import { getJobById, deleteJobRecord } from "@/lib/db/jobs";

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
