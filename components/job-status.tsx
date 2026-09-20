"use client";

import React, { useState, useEffect, useCallback } from "react";
import Link from "next/link";
import {
  ArrowLeft,
  RefreshCw,
  ExternalLink,
  Trash2,
  CheckCircle2,
  Clock,
  Layers,
  FileVideo,
} from "lucide-react";
import { JobDetailResponse } from "@/lib/types/job";
import { JobProgress } from "./job-progress";
import { DownloadButton } from "./download-button";

interface JobStatusViewProps {
  jobId: string;
}

export function JobStatusView({ jobId }: JobStatusViewProps) {
  const [job, setJob] = useState<JobDetailResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);

  const fetchJob = useCallback(async () => {
    try {
      const res = await fetch(`/api/jobs/${jobId}`);
      if (!res.ok) {
        if (res.status === 404) {
          setError(`Job '${jobId}' was not found.`);
        } else {
          setError("Failed to fetch job status.");
        }
        setLoading(false);
        return;
      }
      const data: JobDetailResponse = await res.json();
      setJob(data);
      setError(null);
      setLoading(false);
    } catch {
      setError("Network error while checking job status.");
      setLoading(false);
    }
  }, [jobId]);

  useEffect(() => {
    fetchJob();

    // Auto-poll if job is still in-progress
    const interval = setInterval(() => {
      if (job && (job.status === "READY" || job.status === "FAILED" || job.status === "DELETED")) {
        return;
      }
      fetchJob();
    }, 2500);

    return () => clearInterval(interval);
  }, [fetchJob, job?.status]);

  async function handleManualRefresh() {
    setIsRefreshing(true);
    await fetchJob();
    setIsRefreshing(false);
  }

  async function handleCancel() {
    if (!confirm("Are you sure you want to cancel this recap job?")) return;
    try {
      await fetch(`/api/jobs/${jobId}`, { method: "DELETE" });
      await fetchJob();
    } catch (err) {
      console.error(err);
    }
  }

  if (loading && !job) {
    return (
      <div className="recap-glass rounded-2xl p-12 text-center border border-zinc-800">
        <RefreshCw className="h-8 w-8 animate-spin text-purple-400 mx-auto mb-4" />
        <p className="text-zinc-400 text-sm">Loading job status...</p>
      </div>
    );
  }

  if (error || !job) {
    return (
      <div className="recap-glass rounded-2xl p-8 border border-red-500/30 bg-red-950/20 text-center">
        <p className="text-red-300 font-medium mb-4">{error || "Job not found"}</p>
        <Link
          href="/"
          className="inline-flex items-center gap-2 text-sm text-purple-400 hover:text-purple-300"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to Dashboard
        </Link>
      </div>
    );
  }

  const isTerminal = job.status === "READY" || job.status === "FAILED" || job.status === "DELETED";

  return (
    <div className="space-y-6">
      {/* Header & Controls */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <Link
            href="/"
            className="inline-flex items-center gap-1.5 text-xs text-zinc-400 hover:text-zinc-200 mb-2 transition"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            Back to Dashboard
          </Link>
          <div className="flex items-center gap-3">
            <h1 className="text-xl font-bold text-white font-mono">{job.id}</h1>
            <span
              className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${
                job.status === "READY"
                  ? "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"
                  : job.status === "FAILED"
                  ? "bg-red-500/10 text-red-400 border border-red-500/20"
                  : "bg-purple-500/10 text-purple-400 border border-purple-500/20 animate-pulse"
              }`}
            >
              {job.status}
            </span>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={handleManualRefresh}
            disabled={isRefreshing}
            className="inline-flex items-center gap-1.5 rounded-lg bg-zinc-800/80 px-3 py-2 text-xs font-medium text-zinc-300 border border-zinc-700/60 hover:bg-zinc-700 transition"
          >
            <RefreshCw
              className={`h-3.5 w-3.5 ${isRefreshing ? "animate-spin text-purple-400" : ""}`}
            />
            Refresh
          </button>

          {!isTerminal && (
            <button
              onClick={handleCancel}
              className="inline-flex items-center gap-1.5 rounded-lg bg-zinc-800/40 px-3 py-2 text-xs font-medium text-zinc-400 border border-zinc-700/40 hover:bg-red-500/10 hover:text-red-400 hover:border-red-500/30 transition"
            >
              <Trash2 className="h-3.5 w-3.5" />
              Cancel Job
            </button>
          )}
        </div>
      </div>

      {/* Main Card: Progress & Status */}
      <div className="recap-glass rounded-2xl p-6 sm:p-8 border border-zinc-800 shadow-xl space-y-6">
        <div>
          <div className="flex items-center justify-between text-xs text-zinc-400 mb-4 pb-3 border-b border-zinc-800">
            <span className="flex items-center gap-1.5">
              <span className="font-semibold text-zinc-300">Source:</span>
              <a
                href={job.sourceUrl}
                target="_blank"
                rel="noreferrer"
                className="text-purple-400 hover:underline inline-flex items-center gap-1 truncate max-w-[260px] sm:max-w-md font-mono"
              >
                {job.sourceUrl}
                <ExternalLink className="h-3 w-3 shrink-0" />
              </a>
            </span>
            <span className="rounded bg-zinc-800 px-2 py-0.5 text-zinc-300 uppercase tracking-wider font-mono">
              {job.sourcePlatform}
            </span>
          </div>

          <JobProgress
            status={job.status}
            progress={job.progress}
            message={job.events[job.events.length - 1]?.message}
            errorCode={job.errorCode}
            errorMessage={job.errorMessage}
          />
        </div>

        {/* Video Preview & Download Area */}
        <div className="border-t border-zinc-800 pt-6">
          {job.status === "READY" ? (
            <div className="space-y-4">
              <div className="rounded-xl overflow-hidden bg-black/60 border border-zinc-800 aspect-video flex items-center justify-center relative">
                <div className="text-center p-6">
                  <FileVideo className="h-12 w-12 text-purple-400 mx-auto mb-2 opacity-80" />
                  <p className="text-sm font-semibold text-zinc-200">
                    Recap Video Render Complete
                  </p>
                  <p className="text-xs text-zinc-400 mt-1">
                    Your MP4 file has been composed with narration and subtle effects.
                  </p>
                </div>
              </div>

              <DownloadButton
                jobId={job.id}
                expiresAt={job.expiresAt}
                status={job.status}
                isReady={true}
              />
            </div>
          ) : (
            <DownloadButton
              jobId={job.id}
              expiresAt={job.expiresAt}
              status={job.status}
              isReady={false}
            />
          )}
        </div>
      </div>

      {/* Events / Audit Log Section */}
      <div className="recap-glass rounded-2xl p-6 border border-zinc-800">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-zinc-400 mb-4 flex items-center gap-2">
          <Layers className="h-4 w-4 text-purple-400" />
          Job Lifecycle Events
        </h3>

        <div className="space-y-2">
          {job.events.length === 0 ? (
            <p className="text-xs text-zinc-500 italic">No events recorded yet.</p>
          ) : (
            job.events.map((evt) => (
              <div
                key={evt.id}
                className="flex items-center justify-between text-xs py-1.5 border-b border-zinc-800/40 last:border-0"
              >
                <div className="flex items-center gap-2.5">
                  <span className="font-mono text-purple-400 font-semibold text-[11px] w-24">
                    [{evt.stage}]
                  </span>
                  <span className="text-zinc-300">{evt.message}</span>
                </div>
                <span className="text-zinc-500 font-mono text-[11px]">
                  {new Date(evt.createdAt).toLocaleTimeString()}
                </span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
