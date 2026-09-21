"use client";

import React, { useState, useEffect, useCallback, useRef } from "react";
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
  Sliders,
  Eye,
  Sparkles,
  Volume2,
  ShieldAlert,
  Loader2,
  Play,
  Gauge,
  Link2,
  Download,
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

  // Visual & Audio Adjustment Studio State
  const [subtitlePlacement, setSubtitlePlacement] = useState<string>("bottom");
  const [subtitleSize, setSubtitleSize] = useState<number>(1.0);
  const [subtitleMarginV, setSubtitleMarginV] = useState<number>(30);
  const [burnSubtitles, setBurnSubtitles] = useState<boolean>(true);

  // Logo Blur Box State
  const [blurBoxEnabled, setBlurBoxEnabled] = useState<boolean>(false);
  const [blurBoxPreset, setBlurBoxPreset] = useState<string>("top-right");
  const [blurX, setBlurX] = useState<number>(78);
  const [blurY, setBlurY] = useState<number>(4);
  const [blurW, setBlurW] = useState<number>(18);
  const [blurH, setBlurH] = useState<number>(8);
  const [blurStrength, setBlurStrength] = useState<number>(16);

  // Audio Adjustment State
  const [voiceRate, setVoiceRate] = useState<string>("+10%");
  const [voicePitch, setVoicePitch] = useState<string>("-2Hz");
  const [soundStyle, setSoundStyle] = useState<string>("cinematic_recap");
  const [bgMusicVolume, setBgMusicVolume] = useState<number>(0.0);

  // Playback Speed (audio & video linked)
  const [playbackSpeed, setPlaybackSpeed] = useState<number>(1.0);

  // Preview & Re-rendering State
  const [previewImage, setPreviewImage] = useState<string | null>(null);
  const [isPreviewLoading, setIsPreviewLoading] = useState<boolean>(false);
  const [isRerendering, setIsRerendering] = useState<boolean>(false);
  const [rerenderMessage, setRerenderMessage] = useState<string | null>(null);
  const [videoTimestamp, setVideoTimestamp] = useState<number>(Date.now());
  const [isDownloaded, setIsDownloaded] = useState<boolean>(false);

  const handleDownloadFinalVideo = () => {
    setIsDownloaded(true);
    setTimeout(async () => {
      try {
        await fetch(`/api/jobs/${jobId}/cleanup`, { method: "POST" });
        await fetchJob();
      } catch (err) {
        console.warn("Cleanup trigger error:", err);
      }
    }, 3500);
  };

  const initializedRef = useRef<boolean>(false);

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

      // Hydrate customizer controls with saved DB settings on first load
      if (!initializedRef.current && data) {
        initializedRef.current = true;
        if (data.playbackSpeed) setPlaybackSpeed(data.playbackSpeed);
        if (data.subtitlePlacement) setSubtitlePlacement(data.subtitlePlacement);
        if (data.subtitleSize) setSubtitleSize(data.subtitleSize);
        if (data.subtitleMarginV !== undefined && data.subtitleMarginV !== null) {
          setSubtitleMarginV(data.subtitleMarginV);
        }
        if (data.soundStyle) setSoundStyle(data.soundStyle);
        if (data.voiceRate) setVoiceRate(data.voiceRate);
        if (data.voicePitch) setVoicePitch(data.voicePitch);
        if (data.bgMusicVolume !== undefined && data.bgMusicVolume !== null) {
          setBgMusicVolume(data.bgMusicVolume);
        }
        if (data.blurBoxConfig) {
          try {
            const cfg = typeof data.blurBoxConfig === "string"
              ? JSON.parse(data.blurBoxConfig)
              : data.blurBoxConfig;
            if (cfg && cfg.enabled) {
              setBlurBoxEnabled(true);
              if (cfg.x_pct !== undefined) setBlurX(Math.round(cfg.x_pct * 100));
              if (cfg.y_pct !== undefined) setBlurY(Math.round(cfg.y_pct * 100));
              if (cfg.w_pct !== undefined) setBlurW(Math.round(cfg.w_pct * 100));
              if (cfg.h_pct !== undefined) setBlurH(Math.round(cfg.h_pct * 100));
              if (cfg.strength !== undefined) setBlurStrength(cfg.strength);
            }
          } catch {}
        }
      }

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

  const finalAsset = job?.assets?.find((a) => a.type === "FINAL_VIDEO");
  const fileSizeMB = finalAsset?.sizeBytes
    ? (finalAsset.sizeBytes / (1024 * 1024)).toFixed(1)
    : null;

  async function handleCancel() {
    if (!confirm("Are you sure you want to cancel this recap job?")) return;
    try {
      await fetch(`/api/jobs/${jobId}`, { method: "DELETE" });
      await fetchJob();
    } catch (err) {
      console.error(err);
    }
  }

  // Live frame preview generator
  async function handleGeneratePreview() {
    setIsPreviewLoading(true);
    setRerenderMessage(null);
    try {
      const res = await fetch(`/api/jobs/${jobId}/preview`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          placement: subtitlePlacement,
          size: subtitleSize,
          margin: subtitleMarginV,
          blurBox: blurBoxEnabled
            ? {
                enabled: true,
                x_pct: blurX / 100,
                y_pct: blurY / 100,
                w_pct: blurW / 100,
                h_pct: blurH / 100,
                strength: blurStrength,
              }
            : null,
        }),
      });

      if (!res.ok) {
        throw new Error("Failed to generate preview frame");
      }

      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      setPreviewImage(url);
    } catch (err: any) {
      console.error(err);
      setRerenderMessage("Could not generate frame preview: " + err.message);
    } finally {
      setIsPreviewLoading(false);
    }
  }

  // Trigger fast re-rendering
  async function handleRerender() {
    setIsRerendering(true);
    setRerenderMessage("Dispatching re-render engine with your updated settings...");
    try {
      const res = await fetch(`/api/jobs/${jobId}/rerender`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          subtitlePlacement,
          subtitleSize,
          subtitleMarginV,
          burnSubtitles,
          blurBox: blurBoxEnabled
            ? {
                enabled: true,
                x_pct: blurX / 100,
                y_pct: blurY / 100,
                w_pct: blurW / 100,
                h_pct: blurH / 100,
                strength: blurStrength,
              }
            : null,
          soundStyle,
          voiceRate,
          voicePitch,
          bgMusicVolume,
          playbackSpeed,
        }),
      });

      const data = await res.json();
      if (!res.ok) {
        throw new Error(data.error || "Re-render failed to start");
      }

      setRerenderMessage("⚡ Re-rendering in progress! Your final video is being composed (<10s)...");
      setVideoTimestamp(Date.now());
      fetchJob();

      // Proactively poll every 1.5s until final video is READY
      let attempts = 0;
      const pollInterval = setInterval(async () => {
        attempts++;
        try {
          const pollRes = await fetch(`/api/jobs/${jobId}`);
          if (pollRes.ok) {
            const updated = await pollRes.json();
            setJob(updated);
            if (updated.status === "READY") {
              clearInterval(pollInterval);
              setIsRerendering(false);
              setRerenderMessage("✅ Final video render complete! Ready to watch and download below.");
              setVideoTimestamp(Date.now());
              setTimeout(() => {
                document.getElementById("final-rendered-video-card")?.scrollIntoView({ behavior: "smooth", block: "nearest" });
              }, 150);
              return;
            } else if (updated.status === "FAILED") {
              clearInterval(pollInterval);
              setIsRerendering(false);
              setRerenderMessage("❌ Rendering failed: " + (updated.errorMessage || "Unknown error"));
              return;
            }
          }
        } catch {
          // ignore transient poll error
        }

        if (attempts > 40) {
          clearInterval(pollInterval);
          setIsRerendering(false);
        }
      }, 1500);

    } catch (err: any) {
      console.error(err);
      setRerenderMessage("Re-render error: " + err.message);
      setIsRerendering(false);
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

        {/* Video Player & Download Area */}
        <div className="border-t border-zinc-800 pt-6">
          {job.status === "READY" ? (
            <div className="space-y-4">
              <div className="flex items-center justify-between bg-emerald-950/30 border border-emerald-500/30 px-4 py-2.5 rounded-xl text-xs text-emerald-300">
                <span className="flex items-center gap-2 font-semibold">
                  <CheckCircle2 className="h-4 w-4 text-emerald-400" />
                  🎉 Final Video Ready! Stream below or download directly.
                </span>
                <span className="font-mono text-[11px] text-emerald-400/80">Status: READY (100%)</span>
              </div>

              <div className="rounded-xl overflow-hidden bg-black border border-zinc-800 aspect-video shadow-2xl relative">
                <video
                  key={videoTimestamp}
                  controls
                  preload="metadata"
                  src={`/api/jobs/${job.id}/download?stream=1&v=${videoTimestamp}`}
                  className="w-full h-full object-contain"
                >
                  Your browser does not support HTML5 video playback.
                </video>
              </div>

              <DownloadButton
                jobId={job.id}
                expiresAt={job.expiresAt}
                status={job.status}
                isReady={true}
                isPurged={job.isPurged || isDownloaded}
                onDownloaded={handleDownloadFinalVideo}
              />
            </div>
          ) : job.status === "RENDERING" || isRerendering ? (
            <div className="rounded-xl overflow-hidden bg-gradient-to-br from-purple-950/40 via-indigo-950/30 to-zinc-950 border border-purple-500/40 p-8 text-center space-y-4 shadow-xl">
              <div className="mx-auto w-12 h-12 rounded-2xl bg-purple-500/20 border border-purple-500/40 flex items-center justify-center">
                <Loader2 className="h-6 w-6 text-purple-300 animate-spin" />
              </div>
              <div>
                <h3 className="text-base font-bold text-white">
                  🎬 Rendering Final Video in Progress...
                </h3>
                <p className="text-xs text-purple-200/80 mt-1 max-w-md mx-auto">
                  Burning subtitles, applying blur overlay, and compositing audio with FFmpeg. Your downloadable video will appear here automatically in a few seconds.
                </p>
              </div>
              <div className="inline-flex items-center gap-2 text-xs font-mono text-purple-400 bg-purple-500/10 px-3 py-1 rounded-full border border-purple-500/20">
                <RefreshCw className="h-3 w-3 animate-spin text-purple-400" />
                <span>Status: RENDERING (Fast Re-render &lt;10s)</span>
              </div>
            </div>
          ) : job.status === "VOICE_READY" ? (
            <div className="rounded-xl overflow-hidden bg-purple-950/25 border border-purple-500/30 p-6 space-y-4 shadow-lg shadow-purple-950/30">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                <div className="flex items-center gap-3">
                  <div className="h-10 w-10 rounded-xl bg-purple-500/20 border border-purple-500/30 flex items-center justify-center shrink-0">
                    <Sparkles className="h-5 w-5 text-purple-300" />
                  </div>
                  <div>
                    <h3 className="text-sm sm:text-base font-bold text-white flex items-center gap-2">
                      🎉 Voice & Video Dubbed Successfully!
                      <span className="inline-flex h-2 w-2 rounded-full bg-green-400 animate-pulse" />
                    </h3>
                    <p className="text-xs text-purple-200/80 mt-0.5">
                      The movie recap script and dubbed audio narration are complete.
                    </p>
                  </div>
                </div>

                <button
                  onClick={handleRerender}
                  disabled={isRerendering}
                  className="inline-flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-500 hover:to-indigo-500 px-5 py-2.5 text-xs font-bold text-white shadow-lg shadow-purple-600/30 transition disabled:opacity-50 shrink-0"
                >
                  {isRerendering ? (
                    <>
                      <Loader2 className="h-4 w-4 animate-spin" />
                      <span>Rendering Final Video...</span>
                    </>
                  ) : (
                    <>
                      <Play className="h-4 w-4 fill-white" />
                      <span>🎬 Confirm & Render Final Video</span>
                    </>
                  )}
                </button>
              </div>

              {/* Dubbed Narration Audio Player */}
              <div className="rounded-lg bg-black/40 border border-purple-500/20 p-3.5 space-y-2">
                <div className="flex items-center justify-between text-xs text-purple-300">
                  <span className="flex items-center gap-1.5 font-medium">
                    <Volume2 className="h-3.5 w-3.5 text-purple-400" />
                    Preview Dubbed Voice Narration
                  </span>
                  <span className="text-[11px] text-zinc-400 font-mono">
                    Audio Dub Ready
                  </span>
                </div>
                <audio
                  controls
                  src={`/api/jobs/${job.id}/download?asset=voice`}
                  className="w-full h-8 accent-purple-500"
                />
              </div>

              <div className="text-xs text-purple-200/90 bg-purple-900/30 p-3 rounded-lg border border-purple-500/20">
                👇 <strong>Next step:</strong> Use the <strong>Studio Controls</strong> below to adjust <strong>🎨 Subtitle Typography &amp; Placement</strong>, <strong>🛡️ Blur Box</strong>, and <strong>⚡ Playback Speed</strong>, then click <strong>Confirm &amp; Render</strong>!
              </div>
            </div>
          ) : (
            <div className="rounded-xl overflow-hidden bg-zinc-950/60 border border-zinc-800/80 p-6 text-center">
              <Clock className="h-8 w-8 text-purple-400/80 mx-auto mb-2 animate-pulse" />
              <p className="text-sm font-semibold text-zinc-300">Processing Recap Video...</p>
              <p className="text-xs text-zinc-500 mt-1">
                Subtitles & logo removal settings will unlock right after voice dubbing finishes.
              </p>
            </div>
          )}
        </div>
      </div>

      {/* Visual & Audio Adjustment Studio */}
      <div className="recap-glass rounded-2xl p-6 sm:p-8 border border-purple-500/20 shadow-xl space-y-6">
        <div className="flex items-center justify-between pb-3 border-b border-zinc-800">
          <div className="flex items-center gap-2">
            <Sliders className="h-5 w-5 text-purple-400" />
            <h2 className="text-sm sm:text-base font-bold text-white">
              Studio Controls: Subtitle Placement, Logo Blur & Audio Adjustments
            </h2>
          </div>
          <span className="text-[11px] font-semibold text-purple-400 bg-purple-500/10 border border-purple-500/20 px-2 py-0.5 rounded-full">
            {job.status === "VOICE_READY" ? "Ready for Customization" : "Customizable Output"}
          </span>
        </div>

        {!(job.status === "VOICE_READY" || job.status === "READY" || job.status === "RENDERING" || job.status === "RENDERED") ? (
          <div className="rounded-xl bg-zinc-950/40 border border-zinc-800 p-8 text-center space-y-3">
            <div className="mx-auto w-12 h-12 rounded-full bg-purple-500/10 border border-purple-500/20 flex items-center justify-center">
              <Sparkles className="h-6 w-6 text-purple-400 animate-pulse" />
            </div>
            <h3 className="text-sm sm:text-base font-semibold text-zinc-200">
              🎨 Subtitle Typography & 🛡️ Logo Blur Studio Unlocks After Dubbing
            </h3>
            <p className="text-xs text-zinc-400 max-w-md mx-auto leading-relaxed">
              These controls will appear right here as soon as the narration voice and video scenes are dubbed! You will be able to position subtitles and place blur boxes over old logos before rendering.
            </p>
            <div className="inline-flex items-center gap-2 text-xs text-purple-400 bg-purple-500/10 px-3 py-1 rounded-full border border-purple-500/20">
              <Loader2 className="h-3 w-3 animate-spin" />
              <span>Step: {job.status} ({job.progress}%)</span>
            </div>
          </div>
        ) : (
          <>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {/* Subtitle Typography & Placement */}
          <div className="rounded-xl bg-zinc-900/50 p-4 border border-zinc-800/80 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-semibold text-zinc-300 uppercase tracking-wider flex items-center gap-1.5">
                <Sparkles className="h-3.5 w-3.5 text-purple-400" />
                🎨 Subtitle Typography & Placement
              </h3>
              <label className="flex items-center gap-2 cursor-pointer text-xs text-zinc-400">
                <input
                  type="checkbox"
                  checked={burnSubtitles}
                  onChange={(e) => setBurnSubtitles(e.target.checked)}
                  className="rounded border-zinc-700 bg-zinc-800 text-purple-600"
                />
                Burn Subtitles
              </label>
            </div>

            <div className="space-y-3 text-xs">
              <div>
                <label className="block text-zinc-400 mb-1">Placement / Alignment</label>
                <div className="grid grid-cols-3 gap-2">
                  {[
                    { id: "bottom", label: "Bottom" },
                    { id: "middle", label: "Middle" },
                    { id: "top", label: "Top" },
                  ].map((pos) => (
                    <button
                      key={pos.id}
                      type="button"
                      onClick={() => setSubtitlePlacement(pos.id)}
                      className={`py-2 px-3 rounded-lg border font-medium transition ${
                        subtitlePlacement === pos.id
                          ? "bg-purple-600/20 border-purple-500 text-purple-300 shadow"
                          : "bg-zinc-800/50 border-zinc-700/60 text-zinc-400 hover:text-zinc-200"
                      }`}
                    >
                      {pos.label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="block text-zinc-400 mb-1">
                  Font Size Scale: <span className="text-purple-300 font-mono font-bold">{subtitleSize}x</span>
                </label>
                <div className="grid grid-cols-4 gap-1.5">
                  {[
                    { val: 0.8, label: "0.8x Small" },
                    { val: 1.0, label: "1.0x Normal" },
                    { val: 1.25, label: "1.25x Large" },
                    { val: 1.5, label: "1.5x X-Large" },
                  ].map((s) => (
                    <button
                      key={s.val}
                      type="button"
                      onClick={() => setSubtitleSize(s.val)}
                      className={`py-1.5 px-2 rounded-lg border text-[11px] font-medium transition ${
                        subtitleSize === s.val
                          ? "bg-purple-600/20 border-purple-500 text-purple-300"
                          : "bg-zinc-800/50 border-zinc-700/60 text-zinc-400 hover:text-zinc-200"
                      }`}
                    >
                      {s.label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <div className="flex justify-between text-zinc-400 mb-1">
                  <span>Vertical Margin from Edge</span>
                  <span className="text-zinc-300 font-mono">{subtitleMarginV}px</span>
                </div>
                <input
                  type="range"
                  min="10"
                  max="100"
                  step="5"
                  value={subtitleMarginV}
                  onChange={(e) => setSubtitleMarginV(parseInt(e.target.value))}
                  className="w-full accent-purple-500 cursor-pointer"
                />
              </div>
            </div>
          </div>

          {/* Blur Box / Subtitle & Logo Cover */}
          <div className="rounded-xl bg-zinc-900/50 p-4 border border-zinc-800/80 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-semibold text-zinc-300 uppercase tracking-wider flex items-center gap-1.5">
                <ShieldAlert className="h-3.5 w-3.5 text-cyan-400" />
                🛡️ Blur Box (Cover Old Subtitles / Logos)
              </h3>
              <label className="flex items-center gap-2 cursor-pointer text-xs font-medium text-cyan-300">
                <input
                  type="checkbox"
                  checked={blurBoxEnabled}
                  onChange={(e) => setBlurBoxEnabled(e.target.checked)}
                  className="rounded border-zinc-700 bg-zinc-800 text-cyan-500"
                />
                Enable Blur Box
              </label>
            </div>

            {blurBoxEnabled ? (
              <div className="space-y-3 text-xs">
                <div>
                  <label className="block text-zinc-400 mb-1">Quick Position & Coverage Preset</label>
                  <select
                    value={blurBoxPreset}
                    onChange={(e) => {
                      const val = e.target.value;
                      setBlurBoxPreset(val);
                      if (val === "bottom-subtitles") { setBlurX(0); setBlurY(78); setBlurW(100); setBlurH(20); }
                      else if (val === "lower-third") { setBlurX(0); setBlurY(70); setBlurW(100); setBlurH(28); }
                      else if (val === "top-bar") { setBlurX(0); setBlurY(0); setBlurW(100); setBlurH(16); }
                      else if (val === "top-right") { setBlurX(78); setBlurY(4); setBlurW(18); setBlurH(8); }
                      else if (val === "top-left") { setBlurX(4); setBlurY(4); setBlurW(18); setBlurH(8); }
                      else if (val === "bottom-right") { setBlurX(78); setBlurY(88); setBlurW(18); setBlurH(8); }
                      else if (val === "bottom-left") { setBlurX(4); setBlurY(88); setBlurW(18); setBlurH(8); }
                    }}
                    className="w-full rounded-lg bg-zinc-800 border border-zinc-700 px-3 py-1.5 text-zinc-200"
                  >
                    <option value="bottom-subtitles">📺 Full-Width Bottom Subtitles (100% Width Cover)</option>
                    <option value="lower-third">📺 Full-Width Lower-Third Bar (100% Width)</option>
                    <option value="top-bar">📺 Full-Width Top Bar (100% Width)</option>
                    <option value="top-right">🏷️ Top-Right Logo (Bilibili / TV Watermark)</option>
                    <option value="top-left">🏷️ Top-Left Logo (YouTube Channel Icon)</option>
                    <option value="bottom-right">🏷️ Bottom-Right (Timestamp / Brand Tag)</option>
                    <option value="bottom-left">🏷️ Bottom-Left (Platform Handle / TikTok)</option>
                    <option value="custom">✏️ Custom Position & Size (Adjust Below)</option>
                  </select>
                </div>

                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <div className="flex items-center justify-between text-zinc-400 mb-1">
                      <span>Left X (%):</span>
                      <input
                        type="number"
                        min="0"
                        max="100"
                        value={blurX}
                        onChange={(e) => {
                          const v = Math.max(0, Math.min(100, parseInt(e.target.value) || 0));
                          setBlurX(v);
                          setBlurBoxPreset("custom");
                        }}
                        className="w-14 rounded bg-zinc-800 border border-zinc-700 px-1.5 py-0.5 text-right font-mono text-zinc-200 text-[11px]"
                      />
                    </div>
                    <input
                      type="range"
                      min="0"
                      max="100"
                      value={blurX}
                      onChange={(e) => { setBlurX(parseInt(e.target.value)); setBlurBoxPreset("custom"); }}
                      className="w-full accent-cyan-500"
                    />
                  </div>
                  <div>
                    <div className="flex items-center justify-between text-zinc-400 mb-1">
                      <span>Top Y (%):</span>
                      <input
                        type="number"
                        min="0"
                        max="100"
                        value={blurY}
                        onChange={(e) => {
                          const v = Math.max(0, Math.min(100, parseInt(e.target.value) || 0));
                          setBlurY(v);
                          setBlurBoxPreset("custom");
                        }}
                        className="w-14 rounded bg-zinc-800 border border-zinc-700 px-1.5 py-0.5 text-right font-mono text-zinc-200 text-[11px]"
                      />
                    </div>
                    <input
                      type="range"
                      min="0"
                      max="100"
                      value={blurY}
                      onChange={(e) => { setBlurY(parseInt(e.target.value)); setBlurBoxPreset("custom"); }}
                      className="w-full accent-cyan-500"
                    />
                  </div>
                </div>

                <div className="grid grid-cols-3 gap-2">
                  <div>
                    <div className="flex items-center justify-between text-zinc-400 mb-1">
                      <span>Width (%):</span>
                      <input
                        type="number"
                        min="1"
                        max="100"
                        value={blurW}
                        onChange={(e) => {
                          const v = Math.max(1, Math.min(100, parseInt(e.target.value) || 1));
                          setBlurW(v);
                          setBlurBoxPreset("custom");
                        }}
                        className="w-12 rounded bg-zinc-800 border border-zinc-700 px-1.5 py-0.5 text-right font-mono text-zinc-200 text-[11px]"
                      />
                    </div>
                    <input
                      type="range"
                      min="1"
                      max="100"
                      value={blurW}
                      onChange={(e) => { setBlurW(parseInt(e.target.value)); setBlurBoxPreset("custom"); }}
                      className="w-full accent-cyan-500"
                    />
                  </div>
                  <div>
                    <div className="flex items-center justify-between text-zinc-400 mb-1">
                      <span>Height (%):</span>
                      <input
                        type="number"
                        min="1"
                        max="100"
                        value={blurH}
                        onChange={(e) => {
                          const v = Math.max(1, Math.min(100, parseInt(e.target.value) || 1));
                          setBlurH(v);
                          setBlurBoxPreset("custom");
                        }}
                        className="w-12 rounded bg-zinc-800 border border-zinc-700 px-1.5 py-0.5 text-right font-mono text-zinc-200 text-[11px]"
                      />
                    </div>
                    <input
                      type="range"
                      min="1"
                      max="100"
                      value={blurH}
                      onChange={(e) => { setBlurH(parseInt(e.target.value)); setBlurBoxPreset("custom"); }}
                      className="w-full accent-cyan-500"
                    />
                  </div>
                  <div>
                    <div className="flex items-center justify-between text-zinc-400 mb-1">
                      <span>Blur:</span>
                      <input
                        type="number"
                        min="4"
                        max="50"
                        value={blurStrength}
                        onChange={(e) => {
                          const v = Math.max(4, Math.min(50, parseInt(e.target.value) || 16));
                          setBlurStrength(v);
                        }}
                        className="w-12 rounded bg-zinc-800 border border-zinc-700 px-1.5 py-0.5 text-right font-mono text-zinc-200 text-[11px]"
                      />
                    </div>
                    <input
                      type="range"
                      min="4"
                      max="50"
                      step="2"
                      value={blurStrength}
                      onChange={(e) => setBlurStrength(parseInt(e.target.value))}
                      className="w-full accent-cyan-500"
                    />
                  </div>
                </div>
              </div>
            ) : (
              <div className="rounded-lg bg-zinc-950/40 p-4 text-center text-xs text-zinc-500">
                Blur box is off. Check the box above to cover old subtitles, TV banners, or channel logos.
              </div>
            )}
          </div>
        </div>

        {/* Audio & Voice Naturalness Settings */}
        <div className="rounded-xl bg-zinc-900/50 p-4 border border-zinc-800/80 space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-xs font-semibold text-zinc-300 uppercase tracking-wider flex items-center gap-1.5">
              <Volume2 className="h-3.5 w-3.5 text-indigo-400" />
              Audio, Voice Pace & Sound Mastering Studio
            </h3>
            <span className="text-[11px] text-zinc-500">Removes robotic monotony with custom pace & studio EQ</span>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 text-xs">
            <div>
              <label className="block text-zinc-400 mb-1">Narration Speech Pace / Rate</label>
              <select
                value={voiceRate}
                onChange={(e) => setVoiceRate(e.target.value)}
                className="w-full rounded-lg bg-zinc-800 border border-zinc-700 px-3 py-2 text-zinc-200"
              >
                <option value="+12%">⚡ Brisk & Energetic (+12%) - Recommended for Recaps</option>
                <option value="+8%">✨ Natural Narration Pace (+8%)</option>
                <option value="+0%">Normal Standard (+0%)</option>
                <option value="-8%">Slow Storytelling (-8%)</option>
              </select>
            </div>

            <div>
              <label className="block text-zinc-400 mb-1">Vocal Pitch & Warmth</label>
              <select
                value={voicePitch}
                onChange={(e) => setVoicePitch(e.target.value)}
                className="w-full rounded-lg bg-zinc-800 border border-zinc-700 px-3 py-2 text-zinc-200"
              >
                <option value="-4Hz">🎙️ Warm Cinematic Chest Voice (-4Hz)</option>
                <option value="-2Hz">Natural Studio Pitch (-2Hz) - Default</option>
                <option value="+0Hz">Neutral Pitch (+0Hz)</option>
                <option value="+4Hz">Slightly Higher Tone (+4Hz)</option>
              </select>
            </div>

            <div>
              <label className="block text-zinc-400 mb-1">Sound Design Mastering Preset</label>
              <select
                value={soundStyle}
                onChange={(e) => setSoundStyle(e.target.value)}
                className="w-full rounded-lg bg-zinc-800 border border-zinc-700 px-3 py-2 text-zinc-200"
              >
                <option value="cinematic_recap">🎬 Cinematic Movie Recap (Dynamic EQ & Warm Bass)</option>
                <option value="dramatic_suspense">⚡ Dramatic Suspense & Thriller</option>
                <option value="energetic_action">🔥 Energetic Action Recap</option>
                <option value="emotional_warm">❤️ Emotional & Heartfelt</option>
                <option value="documentary_studio">🎙️ Broadcast Studio (Clean & Crisp)</option>
              </select>
            </div>
          </div>

          <div className="pt-2 border-t border-zinc-800/60 flex flex-col sm:flex-row sm:items-center justify-between gap-2 text-xs">
            <div className="flex items-center gap-2 text-emerald-400">
              <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
              <span className="font-semibold">Audio Output: 100% Pure Dubbed Narration Only</span>
            </div>
            <span className="text-[11px] text-zinc-400 italic">
              Original video audio is completely excluded. Final video contains exclusively the new voice track.
            </span>
          </div>
        </div>

        {/* ⚡ Playback Speed Control (Audio + Video Linked) */}
        <div className="rounded-xl bg-gradient-to-br from-amber-950/30 to-orange-950/20 p-4 border border-amber-500/20 space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-xs font-semibold text-zinc-300 uppercase tracking-wider flex items-center gap-1.5">
              <Gauge className="h-3.5 w-3.5 text-amber-400" />
              ⚡ Playback Speed
              <span className="ml-1 inline-flex items-center gap-1 text-[10px] font-medium text-amber-400/80 bg-amber-500/10 border border-amber-500/20 px-1.5 py-0.5 rounded-full">
                <Link2 className="h-2.5 w-2.5" />
                Audio & Video Linked
              </span>
            </h3>
            <span className="font-mono text-lg font-bold text-amber-300">
              {playbackSpeed.toFixed(2)}×
            </span>
          </div>

          {/* Preset Speed Buttons */}
          <div className="grid grid-cols-5 gap-2 text-xs">
            {[
              { val: 0.5,  label: "0.5×", tip: "Slow" },
              { val: 0.75, label: "0.75×", tip: "Relaxed" },
              { val: 1.0,  label: "1.0×", tip: "Normal" },
              { val: 1.25, label: "1.25×", tip: "Brisk" },
              { val: 1.5,  label: "1.5×", tip: "Fast" },
            ].map((p) => (
              <button
                key={p.val}
                type="button"
                onClick={() => setPlaybackSpeed(p.val)}
                title={p.tip}
                className={`py-2 rounded-lg border font-semibold transition ${
                  playbackSpeed === p.val
                    ? "bg-amber-500/20 border-amber-400 text-amber-200 shadow shadow-amber-500/20"
                    : "bg-zinc-800/50 border-zinc-700/60 text-zinc-400 hover:text-zinc-200 hover:border-zinc-500"
                }`}
              >
                {p.label}
              </button>
            ))}
          </div>

          {/* Fine-tune slider */}
          <div className="space-y-1">
            <div className="flex justify-between text-[11px] text-zinc-500">
              <span>0.25× (Slowest)</span>
              <span className="text-zinc-400 font-mono">{playbackSpeed.toFixed(2)}×</span>
              <span>2.0× (Fastest)</span>
            </div>
            <input
              type="range"
              min="0.25"
              max="2.0"
              step="0.05"
              value={playbackSpeed}
              onChange={(e) => setPlaybackSpeed(parseFloat(e.target.value))}
              className="w-full accent-amber-400 cursor-pointer"
            />
            <p className="text-[10px] text-zinc-500 leading-relaxed pt-0.5">
              Audio and video are speed-adjusted together. Speeds below 1× slow everything down; above 1× speeds it up.
              Final duration will scale inversely with speed.
            </p>
          </div>
        </div>

        {/* Preview Frame Display if active */}
        {previewImage && (
          <div className="rounded-xl overflow-hidden bg-black border border-purple-500/30 p-2 space-y-2">
            <div className="flex items-center justify-between px-2 pt-1 text-xs">
              <span className="font-semibold text-purple-300 flex items-center gap-1.5">
                <Eye className="h-3.5 w-3.5" />
                Live Frame Preview (Placement & Blur Box Verification)
              </span>
              <button
                type="button"
                onClick={() => setPreviewImage(null)}
                className="text-zinc-400 hover:text-zinc-200"
              >
                Close Preview
              </button>
            </div>
            <img
              src={previewImage}
              alt="Live Frame Preview"
              className="w-full max-h-[380px] object-contain rounded-lg mx-auto"
            />
          </div>
        )}

        {/* Action Buttons: Preview & Re-render */}
        <div className="flex flex-col sm:flex-row items-center gap-3 pt-2">
          <button
            type="button"
            onClick={handleGeneratePreview}
            disabled={isPreviewLoading}
            className="w-full sm:w-auto flex-1 flex items-center justify-center gap-2 rounded-xl bg-zinc-800 hover:bg-zinc-700 py-3 px-5 text-xs font-semibold text-zinc-200 border border-zinc-700 transition"
          >
            {isPreviewLoading ? (
              <Loader2 className="h-4 w-4 animate-spin text-purple-400" />
            ) : (
              <Eye className="h-4 w-4 text-purple-400" />
            )}
            <span>👁️ Preview Subtitle & Blur on Frame</span>
          </button>

          <button
            type="button"
            onClick={handleRerender}
            disabled={isRerendering}
            className="w-full sm:w-auto flex-2 flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-500 hover:to-indigo-500 py-3 px-6 text-xs font-bold text-white shadow-lg shadow-purple-600/20 active:scale-[0.99] transition disabled:opacity-50"
          >
            {isRerendering ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" />
                <span>Applying & Rendering Final Video...</span>
              </>
            ) : job.status === "VOICE_READY" ? (
              <>
                <Play className="h-4 w-4 fill-white" />
                <span>🎬 Confirm & Render Final Video (&lt;10s)</span>
              </>
            ) : (
              <>
                <Sparkles className="h-4 w-4" />
                <span>⚡ Apply Settings & Re-render Video (&lt;10s)</span>
              </>
            )}
          </button>
        </div>

        {rerenderMessage && (
          <div className="rounded-lg bg-purple-950/40 border border-purple-500/30 p-3 text-xs text-purple-200 flex items-center gap-2">
            <Sparkles className="h-4 w-4 text-purple-400 shrink-0" />
            <span>{rerenderMessage}</span>
          </div>
        )}

        {/* Active Rendering Progress Card (Directly Below Re-Render Button) */}
        {isRerendering && (
          <div className="rounded-xl overflow-hidden bg-gradient-to-br from-purple-950/60 via-indigo-950/40 to-zinc-950 border border-purple-500/50 p-6 text-center space-y-3 shadow-xl">
            <div className="mx-auto w-12 h-12 rounded-2xl bg-purple-500/20 border border-purple-500/40 flex items-center justify-center">
              <Loader2 className="h-6 w-6 text-purple-300 animate-spin" />
            </div>
            <div>
              <h4 className="text-sm font-bold text-white flex items-center justify-center gap-2">
                <span>🎬 Rendering Final Video in Progress...</span>
                <span className="inline-flex h-2 w-2 rounded-full bg-purple-400 animate-ping" />
              </h4>
              <p className="text-xs text-purple-200/80 mt-1 max-w-md mx-auto">
                Applying subtitle burn ({subtitlePlacement}), visual blur overlay{blurBoxEnabled ? " (custom box active)" : " (disabled)"}, and speed adjustment ({playbackSpeed.toFixed(2)}×) via FFmpeg.
              </p>
            </div>
            <div className="inline-flex items-center gap-2 text-xs font-mono text-purple-300 bg-purple-500/10 px-3 py-1 rounded-full border border-purple-500/20">
              <RefreshCw className="h-3 w-3 animate-spin text-purple-400" />
              <span>Fast Re-render engine active (&lt;10s) — video & download will appear below</span>
            </div>
          </div>
        )}

        {/* Final Rendered Video Player & Download Area (Directly Below Re-Render Button) */}
        {job.status === "READY" && !isRerendering && (
          <div
            id="final-rendered-video-card"
            className="rounded-2xl overflow-hidden bg-gradient-to-b from-zinc-900/95 via-zinc-900/70 to-black border-2 border-emerald-500/40 p-5 space-y-4 shadow-2xl transition-all duration-300"
          >
            {job.isPurged || isDownloaded || (job.hasFinalVideo === false) ? (
              /* Post-Download Cleaned-Up State */
              <div className="rounded-xl border border-emerald-500/40 bg-emerald-950/25 p-6 text-center space-y-3">
                <div className="mx-auto w-12 h-12 rounded-2xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center text-emerald-400">
                  <CheckCircle2 className="h-6 w-6" />
                </div>
                <div>
                  <h4 className="text-base font-bold text-white flex items-center justify-center gap-2">
                    <span>🎬 Video Successfully Downloaded</span>
                    <span className="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-500/20 text-emerald-300 border border-emerald-500/30">
                      SAVED LOCALLY
                    </span>
                  </h4>
                  <p className="text-xs text-emerald-200/80 mt-1 max-w-md mx-auto">
                    The source video file and final rendered video have been removed from the server storage to free up disk space.
                  </p>
                </div>
                <div className="inline-flex items-center gap-2 text-xs font-mono text-emerald-300 bg-emerald-500/10 px-3.5 py-1.5 rounded-full border border-emerald-500/20">
                  <Trash2 className="h-3.5 w-3.5 text-emerald-400" />
                  <span>Server storage freed: source video & final output video purged</span>
                </div>
              </div>
            ) : (
              /* Active Ready State with Video Player & Download Options */
              <>
                {/* Header Badge */}
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-zinc-800/80">
                  <div className="flex items-center gap-3">
                    <div className="h-9 w-9 rounded-xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center text-emerald-400 shrink-0">
                      <CheckCircle2 className="h-5 w-5" />
                    </div>
                    <div>
                      <h4 className="text-sm sm:text-base font-bold text-white flex items-center gap-2">
                        🎬 Final Rendered Video Ready
                        <span className="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-500/20 text-emerald-300 border border-emerald-500/30">
                          100% READY
                        </span>
                      </h4>
                      <p className="text-xs text-zinc-400 mt-0.5">
                        Your recap video is fully rendered with your custom blur box, subtitle styles, and playback speed.
                      </p>
                    </div>
                  </div>

                  {/* Status Badges */}
                  <div className="flex flex-wrap items-center gap-1.5 text-[11px]">
                    {fileSizeMB && (
                      <span className="px-2.5 py-1 rounded-lg bg-zinc-800 text-zinc-200 font-mono border border-zinc-700/80">
                        💾 {fileSizeMB} MB
                      </span>
                    )}
                    <span className="px-2.5 py-1 rounded-lg bg-amber-500/15 text-amber-300 font-mono border border-amber-500/30">
                      ⚡ {playbackSpeed.toFixed(2)}× Speed
                    </span>
                    {blurBoxEnabled && (
                      <span className="px-2.5 py-1 rounded-lg bg-purple-500/15 text-purple-300 font-mono border border-purple-500/30">
                        🛡️ Blur Box Active
                      </span>
                    )}
                  </div>
                </div>

                {/* Video Player */}
                <div className="rounded-xl overflow-hidden bg-black border border-zinc-800 aspect-video shadow-2xl relative group">
                  <video
                    key={`final-player-studio-${videoTimestamp}`}
                    controls
                    preload="metadata"
                    src={`/api/jobs/${job.id}/download?stream=1&v=${videoTimestamp}`}
                    className="w-full h-full object-contain"
                  >
                    Your browser does not support HTML5 video playback.
                  </video>
                </div>

                {/* Download Action Buttons */}
                <div className="space-y-2.5 pt-1">
                  <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-3">
                    <a
                      href={`/api/jobs/${job.id}/download`}
                      download={`recap_${job.id}.mp4`}
                      onClick={handleDownloadFinalVideo}
                      className="flex-1 inline-flex items-center justify-center gap-2.5 rounded-xl bg-gradient-to-r from-emerald-600 via-teal-600 to-green-600 hover:from-emerald-500 hover:to-green-500 px-6 py-3.5 text-sm font-bold text-white shadow-lg shadow-emerald-950/50 transition active:scale-[0.98]"
                    >
                      <Download className="h-5 w-5" />
                      <span>📥 Download Final Video (.mp4)</span>
                    </a>

                    <a
                      href={`/api/jobs/${job.id}/download?stream=1&v=${videoTimestamp}`}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center justify-center gap-2 rounded-xl bg-zinc-800 hover:bg-zinc-700 px-4 py-3 text-xs font-semibold text-zinc-200 border border-zinc-700 transition"
                      title="Open video in a new browser tab for full screen inspection"
                    >
                      <ExternalLink className="h-4 w-4 text-purple-400" />
                      <span>Open in Tab</span>
                    </a>

                    <a
                      href={`/api/jobs/${job.id}/download?asset=voice`}
                      download={`voice_${job.id}.wav`}
                      className="inline-flex items-center justify-center gap-2 rounded-xl bg-zinc-800 hover:bg-zinc-700 px-4 py-3 text-xs font-semibold text-zinc-200 border border-zinc-700 transition"
                      title="Download the synthesized narration audio track only"
                    >
                      <Volume2 className="h-4 w-4 text-amber-400" />
                      <span>Audio Track (.wav)</span>
                    </a>
                  </div>

                  <p className="text-[11px] text-zinc-400 text-center flex items-center justify-center gap-1.5 pt-1">
                    <Trash2 className="h-3 w-3 text-amber-400" />
                    <span>To save server disk space, the original source file and final video are automatically removed after download.</span>
                  </p>

                  {job.expiresAt && (
                    <div className="flex items-center justify-between text-[11px] text-zinc-500 px-1 pt-1">
                      <span className="flex items-center gap-1.5">
                        <Clock className="h-3 w-3 text-amber-400" />
                        Temporary render assets auto-expire after TTL
                      </span>
                      <span className="font-mono text-zinc-400">
                        Expires: {new Date(job.expiresAt).toLocaleTimeString()}
                      </span>
                    </div>
                  )}
                </div>
              </>
            )}
          </div>
        )}
        </>
        )}
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
