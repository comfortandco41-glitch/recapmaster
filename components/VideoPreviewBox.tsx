"use client";

import React, { useRef, useState, useEffect } from "react";
import { Eye, Shield, Film, Play, Pause, Volume2, VolumeX, Maximize2, Sparkles, Sliders } from "lucide-react";
import { Language, translations } from "@/lib/i18n/translations";

export interface BlurBoxConfig {
  enabled: boolean;
  xPct: number;
  yPct: number;
  wPct: number;
  hPct: number;
  strength: number;
}

export interface CopyrightBypassConfig {
  enabled: boolean;
  hflip: boolean;
  zoomCropPct: number; // 0.0 to 0.25 (e.g. 0.05 = 5%)
  brightness: number; // -0.2 to +0.2
  contrast: number; // 0.8 to 1.3
  saturation: number; // 0.8 to 1.5
  borderThickness: number; // 0 to 20 px
  borderColorHex: string;
}

interface VideoPreviewBoxProps {
  videoFile: File | null;
  blurConfig: BlurBoxConfig;
  copyrightConfig: CopyrightBypassConfig;
  lang?: Language;
}

export function VideoPreviewBox({
  videoFile,
  blurConfig,
  copyrightConfig,
  lang = "my",
}: VideoPreviewBoxProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [isMuted, setIsMuted] = useState(false);
  const [videoDimensions, setVideoDimensions] = useState<{ width: number; height: number; duration: number } | null>(null);
  const [currentTime, setCurrentTime] = useState(0);

  const t = translations[lang].viewport;

  useEffect(() => {
    if (videoFile) {
      const url = URL.createObjectURL(videoFile);
      setVideoUrl(url);
      setIsPlaying(false);
      return () => URL.revokeObjectURL(url);
    } else {
      setVideoUrl(null);
      setVideoDimensions(null);
    }
  }, [videoFile]);

  const togglePlay = () => {
    if (!videoRef.current) return;
    if (isPlaying) {
      videoRef.current.pause();
      setIsPlaying(false);
    } else {
      videoRef.current.play();
      setIsPlaying(true);
    }
  };

  const toggleMute = () => {
    if (!videoRef.current) return;
    videoRef.current.muted = !isMuted;
    setIsMuted(!isMuted);
  };

  const handleLoadedMetadata = () => {
    if (videoRef.current) {
      setVideoDimensions({
        width: videoRef.current.videoWidth,
        height: videoRef.current.videoHeight,
        duration: videoRef.current.duration,
      });
    }
  };

  const handleTimeUpdate = () => {
    if (videoRef.current) {
      setCurrentTime(videoRef.current.currentTime);
    }
  };

  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, "0")}`;
  };

  // Compute live CSS filters for Anti-Copyright Bypass preview
  const videoFilterStyle: React.CSSProperties = copyrightConfig.enabled
    ? {
        transform: `${copyrightConfig.hflip ? "scaleX(-1)" : "scaleX(1)"} scale(${
          1 + copyrightConfig.zoomCropPct
        })`,
        filter: `brightness(${1 + copyrightConfig.brightness}) contrast(${
          copyrightConfig.contrast
        }) saturate(${copyrightConfig.saturation})`,
        border: copyrightConfig.borderThickness > 0
          ? `${copyrightConfig.borderThickness}px solid ${copyrightConfig.borderColorHex}`
          : "none",
        transition: "all 0.15s ease-out",
      }
    : {};

  return (
    <div className="relative w-full rounded-2xl glass-surface overflow-hidden shadow-ambient flex flex-col group border border-white/[0.09]">
      {/* Subtle Studio Ambient Backlight Glow */}
      <div className="absolute -inset-1 bg-gradient-to-r from-cyan-500/15 via-blue-500/10 to-purple-500/15 rounded-2xl blur-xl opacity-60 group-hover:opacity-100 transition-opacity duration-500 pointer-events-none -z-10" />

      {/* Top HUD Status Bar */}
      <div className="px-4 py-2.5 bg-zinc-950/70 border-b border-white/[0.07] flex items-center justify-between text-xs font-mono">
        <div className="flex items-center gap-2">
          <span className="relative flex h-2 w-2">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500"></span>
          </span>
          <span className="text-zinc-200 font-medium tracking-wide truncate max-w-[200px] sm:max-w-xs">
            {videoFile ? videoFile.name : t.liveIndicator}
          </span>
        </div>

        {videoDimensions && (
          <div className="flex items-center gap-2 text-[11px] text-zinc-400">
            <span className="px-2 py-0.5 rounded-md bg-zinc-900/90 border border-white/[0.07] text-zinc-300 font-mono">
              {videoDimensions.width}×{videoDimensions.height}
            </span>
            <span className="px-2 py-0.5 rounded-md bg-zinc-900/90 border border-cyan-500/30 text-cyan-300 font-semibold font-mono">
              {formatTime(currentTime)} / {formatTime(videoDimensions.duration)}
            </span>
          </div>
        )}
      </div>

      {/* Live Video Canvas Area */}
      <div className="relative w-full aspect-video bg-[#05060a] flex items-center justify-center overflow-hidden">
        {videoUrl ? (
          <>
            <video
              ref={videoRef}
              src={videoUrl}
              className="w-full h-full object-contain"
              style={videoFilterStyle}
              onPlay={() => setIsPlaying(true)}
              onPause={() => setIsPlaying(false)}
              onLoadedMetadata={handleLoadedMetadata}
              onTimeUpdate={handleTimeUpdate}
              loop
              playsInline
            />

            {/* Interactive Live Blur Box Overlay */}
            {blurConfig.enabled && (
              <div
                className="absolute pointer-events-none border-2 border-cyan-400 rounded-md flex items-start justify-end p-1 transition-all duration-75 shadow-[0_0_24px_rgba(56,189,248,0.35)]"
                style={{
                  left: `${blurConfig.xPct * 100}%`,
                  top: `${blurConfig.yPct * 100}%`,
                  width: `${blurConfig.wPct * 100}%`,
                  height: `${blurConfig.hPct * 100}%`,
                  backdropFilter: `blur(${blurConfig.strength}px)`,
                  WebkitBackdropFilter: `blur(${blurConfig.strength}px)`,
                  background: "rgba(56, 189, 248, 0.1)",
                }}
              >
                <span className="bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-extrabold text-[9px] px-1.5 py-0.5 rounded shadow-sm uppercase tracking-wider font-mono">
                  Blur Zone ({blurConfig.strength}px)
                </span>
              </div>
            )}

            {/* Floating Glass Video Controls Bar */}
            <div className="absolute bottom-3 inset-x-3 rounded-xl glass-surface-subtle p-2 px-3 flex items-center justify-between opacity-0 group-hover:opacity-100 transition-all duration-200 transform translate-y-1 group-hover:translate-y-0 shadow-lg border border-white/[0.1]">
              <div className="flex items-center gap-2">
                <button
                  onClick={togglePlay}
                  className="p-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-white transition-all active:scale-95"
                  title={isPlaying ? "Pause" : "Play"}
                >
                  {isPlaying ? <Pause className="w-3.5 h-3.5" /> : <Play className="w-3.5 h-3.5" />}
                </button>
                <button
                  onClick={toggleMute}
                  className="p-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-white transition-all active:scale-95"
                  title={isMuted ? "Unmute" : "Mute"}
                >
                  {isMuted ? <VolumeX className="w-3.5 h-3.5" /> : <Volume2 className="w-3.5 h-3.5" />}
                </button>
                <span className="text-[11px] font-mono text-zinc-300 ml-1">
                  {formatTime(currentTime)}
                </span>
              </div>

              <div className="flex items-center gap-1.5">
                {blurConfig.enabled && (
                  <span className="text-[10px] bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 px-2 py-0.5 rounded-full font-medium flex items-center gap-1 font-mono">
                    <Shield className="w-2.5 h-2.5 text-cyan-400" />
                    {t.blurActive}
                  </span>
                )}
                {copyrightConfig.enabled && (
                  <span className="text-[10px] bg-purple-500/20 text-purple-300 border border-purple-500/40 px-2 py-0.5 rounded-full font-medium flex items-center gap-1 font-mono">
                    <Sparkles className="w-2.5 h-2.5 text-purple-400" />
                    {t.fxActive}
                  </span>
                )}
              </div>
            </div>
          </>
        ) : (
          <div className="flex flex-col items-center justify-center text-zinc-500 text-xs p-8 text-center">
            <div className="w-14 h-14 rounded-2xl bg-gradient-to-tr from-zinc-900 to-zinc-800 border border-white/[0.08] flex items-center justify-center mb-3 shadow-inner">
              <Film className="w-7 h-7 text-amber-400/80" />
            </div>
            <span className="font-bold text-zinc-200 text-sm tracking-tight">
              {t.emptyTitle}
            </span>
            <span className="text-xs text-zinc-500 max-w-sm mt-1 leading-relaxed">
              {t.emptyDesc}
            </span>
          </div>
        )}
      </div>
    </div>
  );
}
