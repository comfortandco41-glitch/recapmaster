"use client";

import React from "react";
import { CheckCircle2, Circle, Loader2, AlertCircle } from "lucide-react";
import { JobStatus } from "@/lib/types/job";

interface Step {
  id: string;
  label: string;
  activeStatuses: JobStatus[];
  completedStatuses: JobStatus[];
}

const STEPS: Step[] = [
  {
    id: "download",
    label: "Source downloaded",
    activeStatuses: ["DOWNLOADING"],
    completedStatuses: [
      "DOWNLOADED",
      "TRANSCRIBING",
      "TRANSCRIBED",
      "VOICE_GENERATING",
      "VOICE_READY",
      "RENDERING",
      "RENDERED",
      "READY",
    ],
  },
  {
    id: "audio",
    label: "Audio extracted",
    activeStatuses: ["DOWNLOADED"],
    completedStatuses: [
      "TRANSCRIBING",
      "TRANSCRIBED",
      "VOICE_GENERATING",
      "VOICE_READY",
      "RENDERING",
      "RENDERED",
      "READY",
    ],
  },
  {
    id: "whisper",
    label: "Whisper transcription & SRT",
    activeStatuses: ["TRANSCRIBING"],
    completedStatuses: [
      "TRANSCRIBED",
      "VOICE_GENERATING",
      "VOICE_READY",
      "RENDERING",
      "RENDERED",
      "READY",
    ],
  },
  {
    id: "voice",
    label: "Voice generation (VoxCPM 2)",
    activeStatuses: ["VOICE_GENERATING"],
    completedStatuses: ["VOICE_READY", "RENDERING", "RENDERED", "READY"],
  },
  {
    id: "render",
    label: "Video composition & visual effects",
    activeStatuses: ["RENDERING"],
    completedStatuses: ["RENDERED", "READY"],
  },
  {
    id: "final",
    label: "Finalizing recap video",
    activeStatuses: ["RENDERED"],
    completedStatuses: ["READY"],
  },
];

interface JobProgressProps {
  status: JobStatus;
  progress: number;
  message?: string;
  errorCode?: string | null;
  errorMessage?: string | null;
}

export function JobProgress({
  status,
  progress,
  message,
  errorCode,
  errorMessage,
}: JobProgressProps) {
  const isFailed = status === "FAILED";
  const isReady = status === "READY";

  return (
    <div className="space-y-6">
      {/* Visual step list */}
      <div className="space-y-3.5">
        {STEPS.map((step) => {
          const isCompleted = step.completedStatuses.includes(status);
          const isActive = step.activeStatuses.includes(status);

          let icon = <Circle className="h-4 w-4 text-zinc-600" />;
          let textColor = "text-zinc-500";

          if (isCompleted) {
            icon = <CheckCircle2 className="h-4 w-4 text-emerald-400" />;
            textColor = "text-zinc-200";
          } else if (isActive && !isFailed) {
            icon = <Loader2 className="h-4 w-4 animate-spin text-purple-400" />;
            textColor = "text-purple-300 font-medium";
          } else if (isFailed && isActive) {
            icon = <AlertCircle className="h-4 w-4 text-red-400" />;
            textColor = "text-red-400 font-medium";
          }

          return (
            <div key={step.id} className="flex items-center gap-3">
              <div className="flex items-center justify-center">{icon}</div>
              <span className={`text-sm ${textColor}`}>{step.label}</span>
              {isActive && !isFailed && (
                <span className="ml-auto inline-flex items-center rounded bg-purple-500/10 px-2 py-0.5 text-xs text-purple-400 animate-pulse">
                  In progress
                </span>
              )}
            </div>
          );
        })}
      </div>

      {/* Progress Bar */}
      <div className="space-y-2">
        <div className="flex items-center justify-between text-xs">
          <span className="text-zinc-400">
            {message || (isReady ? "Ready for download" : `Current status: ${status}`)}
          </span>
          <span className="font-mono font-semibold text-purple-400">
            {progress}%
          </span>
        </div>

        <div className="h-2.5 w-full overflow-hidden rounded-full bg-zinc-800/80 p-0.5 ring-1 ring-zinc-700/50">
          <div
            className={`h-full rounded-full transition-all duration-500 ${
              isFailed
                ? "bg-red-500"
                : isReady
                ? "bg-emerald-500"
                : "bg-gradient-to-r from-purple-600 via-indigo-500 to-cyan-400"
            }`}
            style={{ width: `${Math.min(100, Math.max(progress, isReady ? 100 : 3))}%` }}
          />
        </div>
      </div>

      {/* Failure banner */}
      {isFailed && (
        <div className="rounded-lg border border-red-500/30 bg-red-950/20 p-4 text-sm text-red-300">
          <div className="flex items-start gap-2.5">
            <AlertCircle className="h-5 w-5 shrink-0 text-red-400" />
            <div>
              <p className="font-medium text-red-200">
                Processing Encountered an Issue
              </p>
              <p className="mt-1 text-xs text-red-300/80">
                {errorMessage || "An unexpected error occurred during processing."}
              </p>
              {errorCode && (
                <p className="mt-1 font-mono text-[11px] text-red-400/60">
                  Code: {errorCode}
                </p>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
