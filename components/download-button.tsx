"use client";

import React, { useState, useEffect } from "react";
import { Download, Clock, AlertTriangle, ExternalLink } from "lucide-react";

interface DownloadButtonProps {
  jobId: string;
  expiresAt: string | null;
  status: string;
  isReady: boolean;
}

export function DownloadButton({
  jobId,
  expiresAt,
  status,
  isReady,
}: DownloadButtonProps) {
  const [timeLeft, setTimeLeft] = useState<string>("");
  const [isExpired, setIsExpired] = useState<boolean>(false);

  useEffect(() => {
    if (!expiresAt) return;

    function calculateTime() {
      const target = new Date(expiresAt!).getTime();
      const now = Date.now();
      const diff = target - now;

      if (diff <= 0) {
        setIsExpired(true);
        setTimeLeft("Expired");
        return;
      }

      const minutes = Math.floor(diff / (1000 * 60));
      const seconds = Math.floor((diff % (1000 * 60)) / 1000);
      setTimeLeft(`${minutes}m ${seconds < 10 ? "0" : ""}${seconds}s`);
    }

    calculateTime();
    const interval = setInterval(calculateTime, 1000);
    return () => clearInterval(interval);
  }, [expiresAt]);

  if (!isReady) {
    return (
      <div className="flex items-center gap-2 text-sm text-zinc-500">
        <Clock className="h-4 w-4 animate-spin-slow" />
        <span>Download unlocks once video rendering finishes.</span>
      </div>
    );
  }

  if (isExpired) {
    return (
      <div className="flex items-center gap-2 rounded-lg border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-300">
        <AlertTriangle className="h-4 w-4 text-red-400" />
        <span>This download has expired and temporary assets have been cleared.</span>
      </div>
    );
  }

  return (
    <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
      <a
        href={`/api/jobs/${jobId}/download`}
        download={`recap_${jobId}.mp4`}
        className="inline-flex items-center gap-2 rounded-lg bg-gradient-to-r from-purple-600 to-indigo-600 px-6 py-3 font-semibold text-white shadow-lg shadow-purple-500/20 transition hover:from-purple-500 hover:to-indigo-500 active:scale-95"
      >
        <Download className="h-4 w-4" />
        Download Final Recap MP4
      </a>

      {timeLeft && (
        <div className="flex items-center gap-1.5 text-xs text-zinc-400 bg-zinc-800/80 px-3 py-2 rounded-md border border-zinc-700/50">
          <Clock className="h-3.5 w-3.5 text-amber-400" />
          <span>Expires in:</span>
          <span className="font-mono text-zinc-200 font-semibold">{timeLeft}</span>
        </div>
      )}
    </div>
  );
}
