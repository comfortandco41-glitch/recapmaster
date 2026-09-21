import { NextRequest, NextResponse } from "next/server";
import path from "path";
import fs from "fs";
import { prisma } from "@/lib/db/client";
import { dispatchJob } from "@/lib/queue/worker-runner";

interface RouteParams {
  params: {
    id: string;
  };
}

export async function POST(
  request: NextRequest,
  { params }: RouteParams
) {
  try {
    const { id } = params;
    const body = await request.json().catch(() => ({}));

    const subtitlePlacement = body.subtitlePlacement ?? "bottom";
    const subtitleSize = body.subtitleSize ?? 1.0;
    const subtitleMarginV = body.subtitleMarginV ?? 30;
    const blurBoxConfig = body.blurBox ? JSON.stringify(body.blurBox) : null;
    const burnSubtitles = body.burnSubtitles !== false;
    const soundStyle = body.soundStyle;
    const voiceRate = body.voiceRate ?? "+10%";
    const voicePitch = body.voicePitch ?? "-2Hz";
    const bgMusicVolume = body.bgMusicVolume ?? 0.15;
    const playbackSpeed = Math.max(0.25, Math.min(4.0, parseFloat(body.playbackSpeed ?? "1.0") || 1.0));

    // Check if job exists
    const existing = await prisma.job.findUnique({ where: { id } });
    if (!existing) {
      return NextResponse.json({ error: `Job '${id}' not found.` }, { status: 404 });
    }

    // Update status and visual/audio settings in database
    await prisma.job.update({
      where: { id },
      data: {
        status: "RENDERING",
        progress: 75,
        subtitlePlacement,
        subtitleSize,
        subtitleMarginV,
        blurBoxConfig,
        burnSubtitles: burnSubtitles ? 1 : 0,
        soundStyle: soundStyle || undefined,
        voiceRate,
        voicePitch,
        bgMusicVolume,
        playbackSpeed,
      },
    });

    // Persist re-render settings to custom_settings.json in job render dir
    const renderDir = path.join(process.cwd(), "storage", "jobs", id, "render");
    fs.mkdirSync(renderDir, { recursive: true });
    const settingsFile = path.join(renderDir, "custom_settings.json");
    fs.writeFileSync(
      settingsFile,
      JSON.stringify(
        {
          subtitlePlacement,
          subtitleSize,
          subtitleMarginV,
          blurBox: body.blurBox,
          playbackSpeed,
          burnSubtitles,
          soundStyle,
          voiceRate,
          voicePitch,
          bgMusicVolume,
        },
        null,
        2
      ),
      "utf-8"
    );

    // Record job event
    await prisma.jobEvent.create({
      data: {
        id: `evt_${Date.now()}_rerender`,
        jobId: id,
        stage: "RENDERING",
        message: `Re-rendering video at ${playbackSpeed}x speed with updated subtitle layout and visual blur filters...`,
        progress: 75,
      },
    });

    // Dispatch worker with fast re-render flag and direct CLI argument overrides
    dispatchJob(id, true, {
      placement: subtitlePlacement,
      size: subtitleSize,
      margin: subtitleMarginV,
      blurBox: body.blurBox,
      speed: playbackSpeed,
    });

    return NextResponse.json({
      success: true,
      message: "Fast re-render initiated.",
      jobId: id,
    });
  } catch (err: any) {
    console.error("Failed to trigger re-render:", err);
    return NextResponse.json({ error: err?.message || "Internal server error" }, { status: 500 });
  }
}
