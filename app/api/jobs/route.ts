import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { validateMediaUrl } from "@/lib/validation/url";
import { createJobRecord, listRecentJobs } from "@/lib/db/jobs";
import { dispatchJob } from "@/lib/queue/worker-runner";

const CreateJobSchema = z.object({
  url: z.string().min(1, "URL is required"),
  language: z.string().optional().default("my"),
  voice: z.string().optional().default("default"),
  soundStyle: z.string().optional().default("cinematic_recap"),
  voxcpmEndpoint: z.string().optional(),
  voxcpmApiKey: z.string().optional(),
  geminiApiKey: z.string().optional(),
  subtitlePlacement: z.string().optional().default("bottom"),
  subtitleSize: z.number().optional().default(1.0),
  subtitleMarginV: z.number().optional(),
  voiceRate: z.string().optional().default("+10%"),
  voicePitch: z.string().optional().default("-2Hz"),
  bgMusicVolume: z.number().optional().default(0.15),
  blurBox: z.object({
    enabled: z.boolean(),
    x_pct: z.number().optional(),
    y_pct: z.number().optional(),
    w_pct: z.number().optional(),
    h_pct: z.number().optional(),
    strength: z.number().optional(),
  }).optional(),
  recap: z.boolean().optional().default(true),
  consentConfirmed: z.boolean().refine((val) => val === true, {
    message:
      "You must confirm that you have the lawful rights or permission to process this content.",
  }),
});

export async function POST(request: NextRequest) {
  try {
    let body: unknown;
    try {
      body = await request.json();
    } catch {
      return NextResponse.json(
        {
          code: "INVALID_JSON",
          message: "Request body must be valid JSON.",
        },
        { status: 400 }
      );
    }

    const parseResult = CreateJobSchema.safeParse(body);
    if (!parseResult.success) {
      const firstError = parseResult.error.errors[0];
      return NextResponse.json(
        {
          code: "VALIDATION_ERROR",
          message: firstError?.message || "Invalid job request payload",
          details: parseResult.error.flatten(),
        },
        { status: 400 }
      );
    }

    const {
      url,
      language,
      voice,
      soundStyle,
      voxcpmEndpoint,
      voxcpmApiKey,
      geminiApiKey,
      subtitlePlacement,
      subtitleSize,
      subtitleMarginV,
      voiceRate,
      voicePitch,
      bgMusicVolume,
      blurBox,
    } = parseResult.data;

    // Validate media URL
    const urlValidation = validateMediaUrl(url);
    if (!urlValidation.isValid || !urlValidation.platform) {
      return NextResponse.json(
        {
          code: "UNSUPPORTED_URL",
          stage: "QUEUED",
          message: urlValidation.error || "Unsupported media URL.",
          retryable: false,
        },
        { status: 400 }
      );
    }

    // Generate unique job ID (e.g. job_20260919_a8f1)
    const timestamp = new Date().toISOString().slice(0, 10).replace(/-/g, "");
    const randomSuffix = Math.random().toString(36).substring(2, 7);
    const jobId = `job_${timestamp}_${randomSuffix}`;

    // Persist job in database
    const job = await createJobRecord({
      id: jobId,
      sourceUrl: urlValidation.canonicalUrl || url,
      sourcePlatform: urlValidation.platform,
      language,
      voice,
      soundStyle,
      voxcpmEndpoint: voxcpmEndpoint?.trim() || undefined,
      voxcpmApiKey: voxcpmApiKey?.trim() || undefined,
      geminiApiKey: geminiApiKey?.trim() || undefined,
      subtitlePlacement,
      subtitleSize,
      subtitleMarginV,
      voiceRate,
      voicePitch,
      bgMusicVolume,
      blurBoxConfig: blurBox ? JSON.stringify(blurBox) : undefined,
    });

    // Dispatch job to background media worker queue
    dispatchJob(job.id);

    return NextResponse.json(
      {
        jobId: job.id,
        status: job.status,
        message: "Job queued successfully.",
      },
      { status: 201 }
    );
  } catch (error) {
    console.error("Failed to create job:", error);
    const msg = error instanceof Error ? error.message : String(error);
    const stack = error instanceof Error ? error.stack : undefined;
    return NextResponse.json(
      {
        code: "INTERNAL_SERVER_ERROR",
        stage: "QUEUED",
        message: msg,
        details: stack,
        retryable: true,
      },
      { status: 500 }
    );
  }
}

export async function GET() {
  try {
    const jobs = await listRecentJobs(10);
    return NextResponse.json({ jobs });
  } catch (error) {
    console.error("Failed to list jobs:", error);
    return NextResponse.json(
      { code: "DB_ERROR", message: "Failed to retrieve recent jobs." },
      { status: 500 }
    );
  }
}
