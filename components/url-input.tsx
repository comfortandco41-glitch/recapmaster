"use client";

import React, { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import {
  Youtube,
  Video,
  ArrowRight,
  Loader2,
  Clipboard,
  Settings2,
  Sparkles,
  CheckCircle2,
  AlertCircle,
  Radio,
  ExternalLink,
} from "lucide-react";
import { validateMediaUrl } from "@/lib/validation/url";

export function UrlInput() {
  const router = useRouter();
  const [url, setUrl] = useState("");
  const [consentConfirmed, setConsentConfirmed] = useState(false);
  const [language, setLanguage] = useState("my");
  const [voice, setVoice] = useState("default");
  const [soundStyle, setSoundStyle] = useState("cinematic_recap");
  const [showAdvanced, setShowAdvanced] = useState(false);

  // VoxCPM 2 Colab Endpoint State
  const [voxcpmEndpoint, setVoxcpmEndpoint] = useState("");
  const [voxcpmApiKey, setVoxcpmApiKey] = useState("");
  const [colabTestStatus, setColabTestStatus] = useState<"idle" | "testing" | "success" | "error">("idle");
  const [colabTestMessage, setColabTestMessage] = useState<string | null>(null);

  const [isLoading, setIsLoading] = useState(false);
  const [clientError, setClientError] = useState<string | null>(null);

  // Load saved Colab URL from localStorage
  useEffect(() => {
    try {
      const savedEndpoint = localStorage.getItem("promovie_voxcpm_endpoint");
      if (savedEndpoint) {
        setVoxcpmEndpoint(savedEndpoint);
      }
      const savedKey = localStorage.getItem("promovie_voxcpm_apikey");
      if (savedKey) {
        setVoxcpmApiKey(savedKey);
      }
    } catch {
      // Ignore localStorage read errors in private browsing
    }
  }, []);

  // Save to localStorage when endpoint changes
  const handleEndpointChange = (val: string) => {
    setVoxcpmEndpoint(val);
    setColabTestStatus("idle");
    setColabTestMessage(null);
    try {
      localStorage.setItem("promovie_voxcpm_endpoint", val);
    } catch {}
  };

  const handleApiKeyChange = (val: string) => {
    setVoxcpmApiKey(val);
    try {
      localStorage.setItem("promovie_voxcpm_apikey", val);
    } catch {}
  };

  // Live platform preview
  const liveCheck = url ? validateMediaUrl(url) : null;

  async function handlePaste() {
    try {
      const text = await navigator.clipboard.readText();
      if (text) {
        setUrl(text.trim());
        setClientError(null);
      }
    } catch {}
  }

  async function handlePasteColab() {
    try {
      const text = await navigator.clipboard.readText();
      if (text) {
        handleEndpointChange(text.trim());
      }
    } catch {}
  }

  async function testColabEndpoint() {
    if (!voxcpmEndpoint.trim()) {
      setColabTestStatus("error");
      setColabTestMessage("Please enter your Google Colab endpoint URL first.");
      return;
    }

    setColabTestStatus("testing");
    setColabTestMessage(null);

    try {
      const res = await fetch("/api/voxcpm/test", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          endpoint: voxcpmEndpoint.trim(),
          apiKey: voxcpmApiKey.trim() || undefined,
        }),
      });

      const data = await res.json();
      if (data.success) {
        setColabTestStatus("success");
        setColabTestMessage(data.message || "Colab tunnel is active and responding.");
      } else {
        setColabTestStatus("error");
        setColabTestMessage(data.error || "Cannot reach Colab endpoint.");
      }
    } catch (err: any) {
      setColabTestStatus("error");
      setColabTestMessage("Failed to test endpoint: " + (err?.message || "Network error"));
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setClientError(null);

    const validation = validateMediaUrl(url);
    if (!validation.isValid) {
      setClientError(validation.error || "Please enter a supported YouTube or Bilibili video URL.");
      return;
    }

    if (!consentConfirmed) {
      setClientError(
        "You must confirm that you have permission or a lawful basis to process this content."
      );
      return;
    }

    setIsLoading(true);

    try {
      const response = await fetch("/api/jobs", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          url: validation.canonicalUrl || url,
          language,
          voice,
          soundStyle,
          voxcpmEndpoint: voxcpmEndpoint.trim() || undefined,
          voxcpmApiKey: voxcpmApiKey.trim() || undefined,
          recap: true,
          consentConfirmed: true,
        }),
      });

      const data = await response.json();

      if (!response.ok) {
        setClientError(data.message || "Failed to create processing job.");
        setIsLoading(false);
        return;
      }

      // Navigate to job status page
      router.push(`/jobs/${data.jobId}`);
    } catch (err) {
      console.error(err);
      setClientError("Network error: Could not reach the Promovie API.");
      setIsLoading(false);
    }
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="recap-glass rounded-2xl p-6 sm:p-8 shadow-2xl border border-zinc-800 transition-all space-y-6"
    >
      {/* 1. URL Input Bar */}
      <div>
        <label
          htmlFor="media-url"
          className="block text-xs font-semibold uppercase tracking-wider text-zinc-400 mb-2"
        >
          Paste YouTube or Bilibili URL
        </label>

        <div className="relative flex items-center">
          <input
            id="media-url"
            type="text"
            value={url}
            onChange={(e) => {
              setUrl(e.target.value);
              setClientError(null);
            }}
            placeholder="https://www.youtube.com/watch?v=... or https://www.bilibili.com/video/..."
            className="w-full rounded-xl bg-zinc-900/90 py-3.5 pl-4 pr-24 text-sm text-zinc-100 placeholder-zinc-500 border border-zinc-700/60 focus:border-purple-500 focus:outline-none focus:ring-2 focus:ring-purple-500/20 font-mono transition"
            disabled={isLoading}
          />

          <div className="absolute right-2 flex items-center gap-1.5">
            <button
              type="button"
              onClick={handlePaste}
              title="Paste from clipboard"
              className="rounded-lg p-2 text-zinc-400 hover:bg-zinc-800 hover:text-zinc-200 transition"
            >
              <Clipboard className="h-4 w-4" />
            </button>
          </div>
        </div>

        {/* Platform Indicator */}
        <div className="mt-2.5 flex items-center justify-between text-xs text-zinc-500">
          <div className="flex items-center gap-3">
            <span
              className={`inline-flex items-center gap-1 transition ${
                liveCheck?.platform === "YOUTUBE"
                  ? "text-red-400 font-semibold"
                  : "text-zinc-500"
              }`}
            >
              <Youtube className="h-3.5 w-3.5" />
              YouTube
            </span>
            <span className="text-zinc-700">•</span>
            <span
              className={`inline-flex items-center gap-1 transition ${
                liveCheck?.platform === "BILIBILI"
                  ? "text-sky-400 font-semibold"
                  : "text-zinc-500"
              }`}
            >
              <Video className="h-3.5 w-3.5" />
              Bilibili
            </span>
          </div>

          {liveCheck?.isValid && (
            <span className="text-emerald-400 text-[11px] font-medium">
              ✓ Validated {liveCheck.platform} link
            </span>
          )}
        </div>
      </div>

      {/* 2. VoxCPM 2 Google Colab Endpoint Configuration */}
      <div className="rounded-xl border border-purple-500/20 bg-purple-950/10 p-4 space-y-3">
        <div className="flex items-center justify-between">
          <label
            htmlFor="voxcpm-endpoint"
            className="text-xs font-semibold text-purple-300 flex items-center gap-1.5"
          >
            <Radio className="h-3.5 w-3.5 text-purple-400 animate-pulse" />
            VoxCPM 2 Google Colab Endpoint URL
          </label>
          <span className="text-[11px] text-zinc-400 font-mono">
            {voxcpmEndpoint ? "Custom Endpoint Active" : "Default / Fallback Active"}
          </span>
        </div>

        <div className="relative flex items-center">
          <input
            id="voxcpm-endpoint"
            type="text"
            value={voxcpmEndpoint}
            onChange={(e) => handleEndpointChange(e.target.value)}
            placeholder="https://xxxx-xx-xx.ngrok-free.app or https://xxxx.trycloudflare.com"
            className="w-full rounded-lg bg-zinc-900/90 py-2.5 pl-3 pr-20 text-xs text-zinc-100 placeholder-zinc-500 border border-zinc-700/60 focus:border-purple-500 focus:outline-none font-mono transition"
            disabled={isLoading}
          />
          <div className="absolute right-1.5 flex items-center gap-1">
            <button
              type="button"
              onClick={handlePasteColab}
              title="Paste Colab URL"
              className="rounded p-1.5 text-zinc-400 hover:bg-zinc-800 hover:text-zinc-200 transition"
            >
              <Clipboard className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              onClick={testColabEndpoint}
              disabled={colabTestStatus === "testing"}
              className="rounded bg-purple-600/30 hover:bg-purple-600/50 border border-purple-500/30 px-2 py-1 text-[11px] font-medium text-purple-200 transition disabled:opacity-50"
            >
              {colabTestStatus === "testing" ? (
                <Loader2 className="h-3 w-3 animate-spin" />
              ) : (
                "Test"
              )}
            </button>
          </div>
        </div>

        {/* Test Result Feedback */}
        {colabTestStatus === "success" && (
          <div className="flex items-center gap-1.5 text-xs text-emerald-400 bg-emerald-950/20 border border-emerald-500/20 px-3 py-1.5 rounded-md">
            <CheckCircle2 className="h-3.5 w-3.5 shrink-0" />
            <span>{colabTestMessage}</span>
          </div>
        )}
        {colabTestStatus === "error" && (
          <div className="flex items-center gap-1.5 text-xs text-amber-300 bg-amber-950/20 border border-amber-500/20 px-3 py-1.5 rounded-md">
            <AlertCircle className="h-3.5 w-3.5 shrink-0 text-amber-400" />
            <span>{colabTestMessage}</span>
          </div>
        )}

        <p className="text-[11px] text-zinc-400 leading-relaxed">
          Paste the public tunnel URL generated by your VoxCPM 2 Colab notebook. Saved in browser automatically.
        </p>
      </div>

      {/* 3. Collapsible Advanced Options */}
      <div className="border-t border-zinc-800/80 pt-4">
        <button
          type="button"
          onClick={() => setShowAdvanced(!showAdvanced)}
          className="flex items-center gap-1.5 text-xs text-zinc-400 hover:text-zinc-300 transition"
        >
          <Settings2 className="h-3.5 w-3.5" />
          <span>{showAdvanced ? "Hide settings" : "Recap settings (Voice, Language & API Key)"}</span>
        </button>

        {showAdvanced && (
          <div className="mt-4 grid grid-cols-1 sm:grid-cols-2 gap-4 rounded-xl bg-zinc-900/50 p-4 border border-zinc-800">
            <div>
              <label className="block text-xs text-zinc-400 mb-1">
                Narration Language
              </label>
              <select
                value={language}
                onChange={(e) => setLanguage(e.target.value)}
                className="w-full rounded-lg bg-zinc-800 border border-zinc-700 px-3 py-2 text-xs text-zinc-200 focus:outline-none focus:border-purple-500"
              >
                <option value="my">Burmese (မြန်မာဘာသာ) - Default</option>
                <option value="en">English</option>
                <option value="zh">Chinese (Mandarin)</option>
                <option value="es">Spanish</option>
                <option value="ja">Japanese</option>
                <option value="id">Indonesian</option>
              </select>
            </div>

            <div>
              <label className="block text-xs text-zinc-400 mb-1">
                Voice Model Preset
              </label>
              <select
                value={voice}
                onChange={(e) => setVoice(e.target.value)}
                className="w-full rounded-lg bg-zinc-800 border border-zinc-700 px-3 py-2 text-xs text-zinc-200 focus:outline-none focus:border-purple-500"
              >
                <option value="default">VoxCPM 2 Default Narrator</option>
                <option value="deep">Cinematic Deep Voice</option>
                <option value="storyteller">Dynamic Storyteller</option>
                <option value="studio">Clear Studio Voice</option>
              </select>
            </div>

            <div className="sm:col-span-2">
              <label className="block text-xs text-zinc-400 mb-1">
                VoxCPM Sound Design & Audio Style
              </label>
              <select
                value={soundStyle}
                onChange={(e) => setSoundStyle(e.target.value)}
                className="w-full rounded-lg bg-zinc-800 border border-zinc-700 px-3 py-2 text-xs text-zinc-200 focus:outline-none focus:border-purple-500"
              >
                <option value="cinematic_recap">🎬 Cinematic Movie Recap (Dynamic EQ, Warm Bass & Vocal Presence)</option>
                <option value="dramatic_suspense">⚡ Dramatic Suspense & Thriller (Deep Bass Tension & Punchy Compression)</option>
                <option value="energetic_action">🔥 Energetic Action Recap (Upbeat Pace, Bright & Punchy)</option>
                <option value="emotional_warm">❤️ Emotional & Heartfelt (Warm, Gentle Storytelling Tone)</option>
                <option value="documentary_studio">🎙️ Broadcast Studio (Clean, Crisp & Neutral)</option>
              </select>
            </div>

            <div className="sm:col-span-2">
              <label className="block text-xs text-zinc-400 mb-1">
                Optional VoxCPM API Key (Authorization Header)
              </label>
              <input
                type="password"
                value={voxcpmApiKey}
                onChange={(e) => handleApiKeyChange(e.target.value)}
                placeholder="Leave blank if your Colab endpoint does not require an API key"
                className="w-full rounded-lg bg-zinc-800 border border-zinc-700 px-3 py-2 text-xs text-zinc-200 focus:outline-none focus:border-purple-500 font-mono"
              />
            </div>
          </div>
        )}
      </div>

      {/* 4. Legal Consent Checkbox */}
      <div className="rounded-xl bg-zinc-900/40 p-4 border border-zinc-800/80">
        <label className="flex items-start gap-3 cursor-pointer">
          <input
            type="checkbox"
            checked={consentConfirmed}
            onChange={(e) => {
              setConsentConfirmed(e.target.checked);
              setClientError(null);
            }}
            className="mt-0.5 h-4 w-4 rounded border-zinc-700 bg-zinc-800 text-purple-600 focus:ring-purple-500 focus:ring-offset-zinc-900"
          />
          <span className="text-xs text-zinc-400 leading-relaxed">
            <span className="font-medium text-zinc-300">
              Rights Confirmation:
            </span>{" "}
            I confirm that I have the necessary rights or lawful basis to process
            this content. I acknowledge that editing, narration, cropping, or
            zooming does not automatically remove copyright restrictions.
          </span>
        </label>
      </div>

      {/* Error message */}
      {clientError && (
        <div className="rounded-lg bg-red-950/40 border border-red-500/30 p-3 text-xs text-red-300">
          {clientError}
        </div>
      )}

      {/* Submit Button */}
      <button
        type="submit"
        disabled={isLoading}
        className="w-full flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 py-3.5 px-6 font-semibold text-white shadow-lg shadow-purple-600/20 hover:from-purple-500 hover:to-indigo-500 active:scale-[0.99] disabled:opacity-50 transition"
      >
        {isLoading ? (
          <>
            <Loader2 className="h-4 w-4 animate-spin" />
            <span>Initializing Job...</span>
          </>
        ) : (
          <>
            <span>Start Recap</span>
            <ArrowRight className="h-4 w-4" />
          </>
        )}
      </button>
    </form>
  );
}
