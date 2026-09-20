import type { Metadata } from "next";
import Link from "next/link";
import { Clapperboard, Sparkles } from "lucide-react";
import "./globals.css";

export const metadata: Metadata = {
  title: "Promovie — AI Video Recap Engine",
  description:
    "Transform supported YouTube and Bilibili videos into narrated recap videos with automated transcription and audio-visual composition.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className="dark">
      <body className="flex min-h-screen flex-col bg-zinc-950 text-zinc-100 antialiased selection:bg-purple-600 selection:text-white">
        {/* Ambient Top Glow */}
        <div className="pointer-events-none fixed inset-x-0 top-0 h-64 bg-gradient-to-b from-purple-900/15 via-transparent to-transparent -z-10" />

        {/* Global Navigation Header */}
        <header className="sticky top-0 z-40 border-b border-zinc-800/80 bg-zinc-950/80 backdrop-blur-md">
          <div className="mx-auto flex h-16 max-w-5xl items-center justify-between px-4 sm:px-6">
            <Link href="/" className="flex items-center gap-2.5 group">
              <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-tr from-purple-600 to-indigo-500 shadow-md shadow-purple-600/30 group-hover:scale-105 transition">
                <Clapperboard className="h-5 w-5 text-white" />
              </div>
              <div className="flex flex-col">
                <span className="font-bold tracking-tight text-white flex items-center gap-1.5">
                  PROMOVIE
                  <span className="rounded bg-purple-500/10 px-1.5 py-0.5 text-[10px] font-semibold text-purple-400 border border-purple-500/20 uppercase">
                    Recap Engine
                  </span>
                </span>
                <span className="text-[11px] text-zinc-500 font-medium">
                  YouTube & Bilibili Recap Pipeline
                </span>
              </div>
            </Link>

            <div className="flex items-center gap-3">
              <div className="hidden sm:flex items-center gap-1.5 rounded-full border border-zinc-800 bg-zinc-900/60 px-3 py-1 text-xs text-zinc-400">
                <Sparkles className="h-3 w-3 text-purple-400" />
                <span>VoxCPM 2 Ready</span>
              </div>
            </div>
          </div>
        </header>

        {/* Main Content Area */}
        <main className="flex-1 mx-auto w-full max-w-5xl px-4 sm:px-6 py-8 sm:py-12">
          {children}
        </main>

        {/* Footer */}
        <footer className="border-t border-zinc-900 bg-zinc-950 py-6 text-center text-xs text-zinc-500">
          <div className="mx-auto max-w-5xl px-4 sm:px-6 space-y-2">
            <p className="leading-relaxed">
              Promovie Recap is a creative media transformation tool. Users are responsible for having the necessary rights or lawful basis to process source material.
            </p>
            <p className="text-zinc-600 font-mono text-[11px]">
              Promovie v0.1.0 • Phase 1 Foundation
            </p>
          </div>
        </footer>
      </body>
    </html>
  );
}
