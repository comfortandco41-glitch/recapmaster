export type JobStatus =
  | "QUEUED"
  | "DOWNLOADING"
  | "DOWNLOADED"
  | "TRANSCRIBING"
  | "TRANSCRIBED"
  | "VOICE_GENERATING"
  | "VOICE_READY"
  | "RENDERING"
  | "RENDERED"
  | "READY"
  | "FAILED"
  | "CLEANING"
  | "DELETED";

export type SourcePlatform = "YOUTUBE" | "BILIBILI";

export type AssetType =
  | "SOURCE_VIDEO"
  | "SOURCE_AUDIO"
  | "TRANSCRIPT_JSON"
  | "SRT"
  | "RECAP_SCRIPT"
  | "VOICE_AUDIO"
  | "FINAL_VIDEO";

export interface CreateJobInput {
  url: string;
  language?: string;
  voice?: string;
  voxcpmEndpoint?: string;
  voxcpmApiKey?: string;
  geminiApiKey?: string;
  recap?: boolean;
}

export interface JobEventResponse {
  id: string;
  stage: string;
  message: string;
  progress: number;
  createdAt: string;
}

export interface JobAssetResponse {
  id: string;
  type: AssetType;
  storageKey: string;
  mimeType: string;
  sizeBytes: number | null;
  createdAt: string;
  expiresAt: string | null;
}

export interface JobDetailResponse {
  id: string;
  userId: string | null;
  sourceUrl: string;
  sourcePlatform: SourcePlatform;
  status: JobStatus;
  progress: number;
  errorCode: string | null;
  errorMessage: string | null;
  language: string;
  voice: string;
  voxcpmEndpoint?: string | null;
  geminiApiKey?: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  expiresAt: string | null;
  deletedAt: string | null;
  events: JobEventResponse[];
  assets: JobAssetResponse[];
  subtitlePlacement?: string | null;
  subtitleSize?: number | null;
  subtitleMarginV?: number | null;
  blurBoxConfig?: string | null;
  soundStyle?: string | null;
  voiceRate?: string | null;
  voicePitch?: string | null;
  bgMusicVolume?: number | null;
  playbackSpeed?: number | null;
  hasFinalVideo?: boolean;
  hasSourceVideo?: boolean;
  isPurged?: boolean;
}

export interface CreateJobResponse {
  jobId: string;
  status: JobStatus;
  message: string;
}

export interface ApiErrorResponse {
  code: string;
  stage?: string;
  message: string;
  retryable?: boolean;
}
