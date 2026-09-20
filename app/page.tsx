"use client";

import React, { useEffect, useState } from "react";
import Link from "next/link";
import { Sparkles, Video, History, ArrowUpRight, Film } from "lucide-react";
import { UrlInput } from "@/components/url-input";
import { CopyrightNotice } from "@/components/copyright-notice";

interface RecentJobSummary {
  id: string;
  sourceUrl: string;
  sourcePlatform: "YOUTUBE" | "BILIBILI";
  status: string;
  progress: number;
  language: string;
  voice: string;
  createdAt: string;
}

export default function HomePage() {
  const [recentJobs, setRecentJobs] = useState<RecentJobSummary[]>([]);
  const [loadingJobs, setLoadingJobs] = useState(true);

  useEffect(() => {
    async function fetchRecent() {
      try {
        const res = await fetch("/api/jobs");
        if (res.ok) {
          const data = await res.json();
          setRecentJobs(data.jobs || []);
        }
      } catch (err) {
        console.error("Failed to load recent jobs:", err);
      } finally {
        setLoadingJobs(false);
      }
    }
    fetchRecent();
  }, []);

  return (
    <div className="space-y-10">
      {/* Hero Section */}
      <section className="text-center space-y-4 max-w-2xl mx-auto pt-4 sm:pt-8">
        <div className="inline-flex items-center gap-2 rounded-full border border-purple-500/20 bg-purple-500/10 px-3.5 py-1 text-xs font-semibold text-purple-300">
          <Sparkles className="h-3.5 w-3.5" />
          <span>Automated Video Recap Pipeline</span>
        </div>

        <h1 className="text-4xl sm:text-5xl font-extrabold tracking-tight text-white leading-tight">
          Turn your video into a{" "}
          <span className="bg-gradient-to-r from-purple-400 via-indigo-300 to-cyan-400 bg-clip-text text-transparent">
            compelling recap
          </span>
        </h1>

        <p className="text-sm sm:text-base text-zinc-400 leading-relaxed max-w-xl mx-auto">
          Paste a YouTube or Bilibili URL. Promovie extracts audio, transcribes with Whisper,
          synthesizes narration via VoxCPM 2, and renders an edited recap video.
        </p>
      </section>

      {/* Main Input Form */}
      <section className="max-w-2xl mx-auto">
        <UrlInput />
      </section>

      {/* Copyright and Legal Notice */}
      <section className="max-w-2xl mx-auto">
        <CopyrightNotice />
      </section>

      {/* Recent Jobs Section */}
      <section className="max-w-2xl mx-auto space-y-4 pt-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-zinc-400 flex items-center gap-2">
            <History className="h-4 w-4 text-purple-400" />
            Recent Processing Jobs
          </h2>
        </div>

        {loadingJobs ? (
          <div className="rounded-xl border border-zinc-800 bg-zinc-900/40 p-6 text-center text-xs text-zinc-500">
            Loading recent jobs...
          </div>
        ) : recentJobs.length === 0 ? (
          <div className="rounded-xl border border-zinc-800 bg-zinc-900/30 p-8 text-center text-xs text-zinc-500">
            <Film className="h-8 w-8 text-zinc-700 mx-auto mb-2" />
            No recap jobs created yet. Paste a video link above to get started.
          </div>
        ) : (
          <div className="space-y-2">
            {recentJobs.map((job) => (
              <Link
                key={job.id}
                href={`/jobs/${job.id}`}
                className="group flex items-center justify-between rounded-xl border border-zinc-800/80 bg-zinc-900/40 p-4 hover:border-purple-500/30 hover:bg-zinc-900/80 transition"
              >
                <div className="space-y-1 min-w-0 pr-4">
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-xs font-semibold text-zinc-200">
                      {job.id}
                    </span>
                    <span className="rounded bg-zinc-800 px-1.5 py-0.5 text-[10px] font-mono text-zinc-400 uppercase">
                      {job.sourcePlatform}
                    </span>
                  </div>
                  <p className="text-xs text-zinc-500 truncate font-mono max-w-xs sm:max-w-md">
                    {job.sourceUrl}
                  </p>
                </div>

                <div className="flex items-center gap-3 shrink-0">
                  <div className="text-right">
                    <span
                      className={`inline-block rounded-full px-2 py-0.5 text-[10px] font-semibold ${
                        job.status === "READY"
                          ? "bg-emerald-500/10 text-emerald-400"
                          : job.status === "FAILED"
                          ? "bg-red-500/10 text-red-400"
                          : "bg-purple-500/10 text-purple-400"
                      }`}
                    >
                      {job.status} ({job.progress}%)
                    </span>
                    <p className="text-[10px] text-zinc-600 mt-0.5">
                      {new Date(job.createdAt).toLocaleTimeString()}
                    </p>
                  </div>
                  <ArrowUpRight className="h-4 w-4 text-zinc-500 group-hover:text-purple-400 transition" />
                </div>
              </Link>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
