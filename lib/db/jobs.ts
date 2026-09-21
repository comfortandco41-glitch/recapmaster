import { prisma } from "./client";
import { JobStatus, SourcePlatform, AssetType } from "../types/job";

export interface CreateJobParams {
  id: string;
  sourceUrl: string;
  sourcePlatform: SourcePlatform;
  userId?: string;
  language?: string;
  voice?: string;
  voxcpmEndpoint?: string;
  voxcpmApiKey?: string;
  soundStyle?: string;
  expiresInMinutes?: number;
  subtitlePlacement?: string;
  subtitleSize?: number;
  subtitleMarginV?: number;
  blurBoxConfig?: string;
  voiceRate?: string;
  voicePitch?: string;
  bgMusicVolume?: number;
  geminiApiKey?: string;
}

export async function createJobRecord(params: CreateJobParams) {
  const ttlMinutes = params.expiresInMinutes ?? 60;
  const expiresAt = new Date(Date.now() + ttlMinutes * 60 * 1000);

  return await prisma.$transaction(async (tx) => {
    const job = await tx.job.create({
      data: {
        id: params.id,
        userId: params.userId ?? "anonymous",
        sourceUrl: params.sourceUrl,
        sourcePlatform: params.sourcePlatform,
        status: "QUEUED",
        progress: 0,
        language: params.language ?? "en",
        voice: params.voice ?? "default",
        voxcpmEndpoint: params.voxcpmEndpoint,
        voxcpmApiKey: params.voxcpmApiKey,
        expiresAt,
      },
    });

    try {
      await tx.$executeRawUnsafe(
        "UPDATE jobs SET sound_style = ?, subtitle_placement = ?, subtitle_size = ?, subtitle_margin_v = ?, blur_box_config = ?, voice_rate = ?, voice_pitch = ?, bg_music_volume = ?, gemini_api_key = ? WHERE id = ?",
        params.soundStyle ?? "cinematic_recap",
        params.subtitlePlacement ?? "bottom",
        params.subtitleSize ?? 1.0,
        params.subtitleMarginV ?? null,
        params.blurBoxConfig ?? null,
        params.voiceRate ?? "+10%",
        params.voicePitch ?? "-2Hz",
        params.bgMusicVolume ?? 0.15,
        params.geminiApiKey ?? null,
        job.id
      );
    } catch (rawErr) {
      console.warn("Could not set visual/audio settings via raw SQL:", rawErr);
    }

    await tx.jobEvent.create({
      data: {
        id: `evt_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
        jobId: job.id,
        stage: "QUEUED",
        message: "Job created and queued for processing",
        progress: 0,
      },
    });

    return job;
  });
}

export async function getJobById(jobId: string) {
  return await prisma.job.findUnique({
    where: { id: jobId },
    include: {
      assets: {
        orderBy: { createdAt: "desc" },
      },
      events: {
        orderBy: { createdAt: "asc" },
      },
    },
  });
}

export async function updateJobStatus(
  jobId: string,
  status: JobStatus,
  options?: {
    progress?: number;
    errorCode?: string;
    errorMessage?: string;
    stage?: string;
    eventMessage?: string;
  }
) {
  const now = new Date();
  const updateData: {
    status: string;
    progress?: number;
    errorCode?: string | null;
    errorMessage?: string | null;
    startedAt?: Date;
    completedAt?: Date;
  } = {
    status,
  };

  if (typeof options?.progress === "number") {
    updateData.progress = options.progress;
  }
  if (options?.errorCode !== undefined) {
    updateData.errorCode = options.errorCode;
  }
  if (options?.errorMessage !== undefined) {
    updateData.errorMessage = options.errorMessage;
  }
  if (status === "DOWNLOADING") {
    updateData.startedAt = now;
  }
  if (status === "READY" || status === "FAILED") {
    updateData.completedAt = now;
  }

  return await prisma.$transaction(async (tx) => {
    const job = await tx.job.update({
      where: { id: jobId },
      data: updateData,
    });

    if (options?.eventMessage || options?.stage) {
      await tx.jobEvent.create({
        data: {
          id: `evt_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
          jobId,
          stage: options.stage ?? status,
          message: options.eventMessage ?? `Status transitioned to ${status}`,
          progress: options.progress ?? job.progress,
        },
      });
    }

    return job;
  });
}

export async function addJobEvent(
  jobId: string,
  stage: string,
  message: string,
  progress: number
) {
  return await prisma.jobEvent.create({
    data: {
      id: `evt_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
      jobId,
      stage,
      message,
      progress,
    },
  });
}

export async function addJobAsset(
  jobId: string,
  type: AssetType,
  storageKey: string,
  mimeType: string,
  sizeBytes?: number
) {
  return await prisma.jobAsset.create({
    data: {
      id: `ast_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
      jobId,
      type,
      storageKey,
      mimeType,
      sizeBytes,
    },
  });
}

export async function listRecentJobs(limit = 10) {
  return await prisma.job.findMany({
    take: limit,
    orderBy: { createdAt: "desc" },
    select: {
      id: true,
      sourceUrl: true,
      sourcePlatform: true,
      status: true,
      progress: true,
      language: true,
      voice: true,
      createdAt: true,
      expiresAt: true,
    },
  });
}

export async function deleteJobRecord(jobId: string) {
  return await prisma.job.update({
    where: { id: jobId },
    data: {
      status: "DELETED",
      deletedAt: new Date(),
    },
  });
}
