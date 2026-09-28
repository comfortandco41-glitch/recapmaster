"use client";

import React, { useState, useEffect, useRef } from "react";
import { 
  Sparkles, 
  Clock, 
  Key, 
  LogIn, 
  LogOut, 
  Upload, 
  Play, 
  FileText, 
  Download, 
  Check, 
  AlertCircle,
  Copy,
  RefreshCw,
  Video,
  Volume2,
  Mic,
  Cpu,
  Layers,
  ArrowRight,
  ExternalLink,
  ShieldCheck,
  Zap,
  Globe,
  Terminal,
  ChevronDown,
  ChevronUp,
  Activity,
  CheckCircle2,
  Hourglass,
  Languages,
  Youtube,
  Link as LinkIcon
} from "lucide-react";
import { useSubscription } from "@/lib/hooks/useSubscription";
import { RewardedAdModal } from "@/components/RewardedAdModal";
import { 
  extractAudioFromVideo, 
  renderProcessedVideo, 
  renderDubbedRecapVideo, 
  getVideoMetadata 
} from "@/lib/ffmpeg/ffmpegClient";
import { synthesizeRecapAudio } from "@/lib/tts/ttsSynthesizer";
import { transcribeAudio, WhisperOutput } from "@/lib/ai/whisperClient";
import { generateRecapScript } from "@/lib/ai/geminiClient";
import { VideoPreviewBox, BlurBoxConfig, CopyrightBypassConfig } from "@/components/VideoPreviewBox";
import { StudioToolsAccordion } from "@/components/StudioToolsAccordion";
import { VOICE_PROFILES, VoiceProfile } from "@/lib/data/voiceProfiles";
import { AdBanner468x60 } from "@/components/AdBanner468x60";
import { AdNativeContainer } from "@/components/AdNativeContainer";
import { Language, translations } from "@/lib/i18n/translations";

type PipelineStage = "idle" | "extracting" | "transcribing" | "generating" | "rendering" | "completed" | "error";

interface LogEntry {
  time: string;
  text: string;
  type: "info" | "success" | "warn" | "error";
}

export function WebRecapStudio() {
  const { 
    user, 
    loginWithGoogle, 
    logout, 
    formattedTimeLeft, 
    isExpired, 
    grantAdRewardMinutes 
  } = useSubscription();

  // Language State (Myanmar / English)
  const [appLang, setAppLang] = useState<Language>("my");
  const t = translations[appLang];

  // Modals & Keys
  const [isAdModalOpen, setIsAdModalOpen] = useState(false);
  const [isKeyModalOpen, setIsKeyModalOpen] = useState(false);
  const [geminiApiKey, setGeminiApiKey] = useState("");

  // Pipeline State
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [videoMeta, setVideoMeta] = useState<{ width: number; height: number; duration: number } | null>(null);
  const [targetLanguage, setTargetLanguage] = useState<string>("my");
  const [stage, setStage] = useState<PipelineStage>("idle");
  const [progressPercent, setProgressPercent] = useState<number>(0);
  const [statusMessage, setStatusMessage] = useState<string>("");
  const [errorMessage, setErrorMessage] = useState<string>("");

  // Detailed Timing & Logs
  const [elapsedSeconds, setElapsedSeconds] = useState<number>(0);
  const [pipelineLogs, setPipelineLogs] = useState<LogEntry[]>([]);
  const [isLogsExpanded, setIsLogsExpanded] = useState<boolean>(true);
  const logsContainerRef = useRef<HTMLDivElement>(null);

  // Tools Configuration (Matching Android RecapMaster)
  const [blurConfig, setBlurConfig] = useState<BlurBoxConfig>({
    enabled: false,
    xPct: 0.78,
    yPct: 0.04,
    wPct: 0.18,
    hPct: 0.08,
    strength: 16,
  });

  const [copyrightConfig, setCopyrightConfig] = useState<CopyrightBypassConfig>({
    enabled: false,
    hflip: false,
    zoomCropPct: 0.05,
    brightness: 0.0,
    contrast: 1.05,
    saturation: 1.1,
    borderThickness: 0,
    borderColorHex: "#3b82f6",
  });

  const [selectedVoice, setSelectedVoice] = useState<VoiceProfile>(VOICE_PROFILES[0]);

  // Results
  const [transcriptData, setTranscriptData] = useState<WhisperOutput | null>(null);
  const [recapScript, setRecapScript] = useState<string>("");
  const [renderedVideoBlob, setRenderedVideoBlob] = useState<Blob | null>(null);
  const [activeTab, setActiveTab] = useState<"script" | "transcript" | "video">("script");
  const [copied, setCopied] = useState(false);

  // Load language & API key from localStorage on mount
  useEffect(() => {
    const savedLang = localStorage.getItem("app_lang") as Language;
    if (savedLang && (savedLang === "my" || savedLang === "en")) {
      setAppLang(savedLang);
    }
    const savedKey = localStorage.getItem("gemini_api_key");
    if (savedKey) setGeminiApiKey(savedKey);
  }, []);

  const handleLanguageChange = (newLang: Language) => {
    setAppLang(newLang);
    localStorage.setItem("app_lang", newLang);
  };

  // Timer interval for elapsed seconds
  useEffect(() => {
    let interval: NodeJS.Timeout | null = null;
    if (stage !== "idle" && stage !== "completed" && stage !== "error") {
      interval = setInterval(() => {
        setElapsedSeconds((prev) => prev + 1);
      }, 1000);
    }
    return () => {
      if (interval) clearInterval(interval);
    };
  }, [stage]);

  // Auto-scroll logs to bottom
  useEffect(() => {
    if (logsContainerRef.current) {
      logsContainerRef.current.scrollTop = logsContainerRef.current.scrollHeight;
    }
  }, [pipelineLogs]);

  const addPipelineLog = (text: string, type: "info" | "success" | "warn" | "error" = "info") => {
    const now = new Date();
    const timeStr = `${now.getHours().toString().padStart(2, "0")}:${now.getMinutes().toString().padStart(2, "0")}:${now.getSeconds().toString().padStart(2, "0")}`;
    setPipelineLogs((prev) => [...prev, { time: timeStr, text, type }]);
    setStatusMessage(text);
  };

  const saveApiKey = (key: string) => {
    setGeminiApiKey(key);
    localStorage.setItem("gemini_api_key", key.trim());
    setIsKeyModalOpen(false);
  };

  // YouTube State & Import
  const [inputMode, setInputMode] = useState<"file" | "youtube">("file");
  const [youtubeUrl, setYoutubeUrl] = useState<string>("");
  const [youtubeLoading, setYoutubeLoading] = useState<boolean>(false);
  const [youtubeDownloading, setYoutubeDownloading] = useState<boolean>(false);
  const [youtubeInfo, setYoutubeInfo] = useState<{
    videoId: string;
    title: string;
    duration: number;
    thumbnail: string;
    channel: string;
    canonicalUrl: string;
    hasYtDlp?: boolean;
    externalDownloadUrl?: string;
  } | null>(null);
  const [youtubeError, setYoutubeError] = useState<string>("");

  const loadVideoFile = async (file: File) => {
    setSelectedFile(file);
    setStage("idle");
    setProgressPercent(0);
    setElapsedSeconds(0);
    setPipelineLogs([]);
    setTranscriptData(null);
    setRecapScript("");
    setRenderedVideoBlob(null);
    setErrorMessage("");

    try {
      const meta = await getVideoMetadata(file);
      setVideoMeta(meta);
      setStatusMessage(
        appLang === "my"
          ? `ရွေးချယ်ထားသော ဖိုင်: ${file.name} (${meta.width}x${meta.height}, ကြာချိန် ${Math.round(meta.duration)} စက္ကန့်)`
          : `Selected: ${file.name} (${meta.width}x${meta.height}, ${Math.round(meta.duration)}s)`
      );
    } catch (err) {
      console.warn("Failed to extract video metadata:", err);
    }
  };

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      await loadVideoFile(e.target.files[0]);
    }
  };

  const fetchYoutubeInfo = async () => {
    if (!youtubeUrl.trim()) return;
    setYoutubeLoading(true);
    setYoutubeError("");
    setYoutubeInfo(null);

    try {
      const res = await fetch("/api/youtube", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url: youtubeUrl.trim() }),
      });

      const data = await res.json();
      if (!res.ok || data.error) {
        throw new Error(data.error || t.youtube.errorFetch);
      }

      setYoutubeInfo(data);
    } catch (err: any) {
      setYoutubeError(err.message || t.youtube.errorFetch);
    } finally {
      setYoutubeLoading(false);
    }
  };

  const downloadAndLoadYoutubeVideo = async () => {
    if (!youtubeUrl.trim()) return;
    setYoutubeDownloading(true);
    setYoutubeError("");

    try {
      const targetUrl = youtubeInfo?.canonicalUrl || youtubeUrl.trim();
      const workerUrl = process.env.NEXT_PUBLIC_YOUTUBE_WORKER_URL;
      const downloadEndpoint = workerUrl
        ? `${workerUrl.replace(/\/+$/, "")}/download?url=${encodeURIComponent(targetUrl)}`
        : `/api/youtube?url=${encodeURIComponent(targetUrl)}`;

      const res = await fetch(downloadEndpoint);
      const contentType = res.headers.get("content-type") || "";
      if (!res.ok || contentType.includes("application/json")) {
        const errJson = await res.json().catch(() => null);
        throw new Error(errJson?.detail || errJson?.error || t.youtube.errorDownload);
      }

      const blob = await res.blob();
      if (!blob || blob.size < 50000) {
        let errorDetail = "";
        try {
          if (blob && blob.size > 0 && blob.size < 2000) {
            errorDetail = await blob.text();
          }
        } catch {}

        throw new Error(
          errorDetail && errorDetail.length < 150
            ? errorDetail
            : (appLang === "my"
                ? "YouTube ဗီဒီယို stream အချက်အလက် မပြည့်စုံသေးပါ။ အောက်ပါ ၁-Click Helper ဖြင့် MP4 ကို အလွယ်တကူ ရယူနိုင်ပါသည်။"
                : "Video stream incomplete or blocked. Please use the 1-click helper below to download the video.")
        );
      }

      const rawTitle = youtubeInfo?.title || "youtube_video";
      const sanitizedTitle = rawTitle.replace(/[^a-zA-Z0-9_\-\s]/g, "").slice(0, 32).trim() || "youtube_video";
      const file = new File([blob], `${sanitizedTitle}.mp4`, { type: "video/mp4" });

      await loadVideoFile(file);
    } catch (err: any) {
      setYoutubeError(err.message || t.youtube.errorDownload);
    } finally {
      setYoutubeDownloading(false);
    }
  };

  const startPipeline = async () => {
    if (!selectedFile) return;

    if (isExpired) {
      setIsAdModalOpen(true);
      return;
    }

    if (!geminiApiKey) {
      setIsKeyModalOpen(true);
      return;
    }

    setErrorMessage("");
    setStage("extracting");
    setProgressPercent(10);
    setElapsedSeconds(0);
    setPipelineLogs([]);

    const isMM = appLang === "my";
    addPipelineLog(
      isMM ? `🎬 ဗီဒီယိုဖိုင် စစ်ဆေးနေပါသည်: ${selectedFile.name}` : `🎬 Inspecting video source: ${selectedFile.name}`, 
      "info"
    );

    try {
      // 0. Probed Video Metadata (Duration & Dimensions)
      const meta = await getVideoMetadata(selectedFile);
      setVideoMeta(meta);
      addPipelineLog(
        isMM
          ? `✅ ဗီဒီယို ရုပ်ထွက်: ${meta.width}x${meta.height}px, ကြာချိန်: ${Math.round(meta.duration)} စက္ကန့်`
          : `✅ Resolution: ${meta.width}x${meta.height}px, Duration: ${Math.round(meta.duration)}s`,
        "success"
      );

      // 1. FFmpeg Audio Extraction
      addPipelineLog(
        isMM
          ? "🎙️ [အဆင့် ၁/၄] FFmpeg WASM ဖြင့် 16kHz Mono PCM အသံဖိုင် စတင်ခွဲထုတ်နေပါသည်..."
          : "🎙️ [Stage 1/4] Extracting 16kHz Mono PCM dialogue audio with FFmpeg WASM...",
        "info"
      );
      const audioBlob = await extractAudioFromVideo(
        selectedFile,
        (progress) => setProgressPercent(10 + Math.round(progress * 0.2)),
        (msg) => console.log("[FFmpeg]", msg)
      );
      addPipelineLog(
        isMM
          ? `✅ အသံဖိုင် ခွဲထုတ်ပြီးစီးပါပြီ (${(audioBlob.size / 1024).toFixed(1)} KB)`
          : `✅ Dialogue audio extracted (${(audioBlob.size / 1024).toFixed(1)} KB)`,
        "success"
      );

      // 2. Whisper WebGPU Transcription
      setStage("transcribing");
      setProgressPercent(35);
      addPipelineLog(
        isMM
          ? "🧠 [အဆင့် ၂/၄] Whisper WebGPU စနစ်ဖြင့် ဗီဒီယိုထဲမှ စကားပြောများကို စတင်ဖတ်ယူနေပါသည်..."
          : "🧠 [Stage 2/4] Transcribing dialogue speech locally with Whisper WebGPU...",
        "info"
      );

      const whisperResult = await transcribeAudio(
        audioBlob,
        "auto",
        (info) => {
          if (info.status === "progress") {
            const pct = Math.round((info.loaded / (info.total || 1)) * 100);
            setStatusMessage(
              isMM ? `Whisper AI မော်ဒယ် ဒေါင်းလုဒ်ရယူနေသည်: ${pct}%` : `Downloading Whisper model: ${pct}%`
            );
          }
        }
      );

      setTranscriptData(whisperResult);
      addPipelineLog(
        isMM
          ? `✅ စကားပြောအပိုင်း (${whisperResult.chunks.length} ခု) ကို အချိန်မှတ်နှင့်တကွ အောင်မြင်စွာ ဖမ်းယူပြီးပါပြီ။`
          : `✅ Transcribed ${whisperResult.chunks.length} dialogue segments with timestamps.`,
        "success"
      );

      // Format timestamped transcript so Gemini sees chronological timeline to the final scene
      const formattedTranscript = whisperResult.chunks && whisperResult.chunks.length > 0
        ? whisperResult.chunks
            .map((c) => {
              const start = Math.floor(c.timestamp[0] || 0);
              const end = Math.floor(c.timestamp[1] || start + 2);
              const mStart = Math.floor(start / 60);
              const sStart = (start % 60).toString().padStart(2, "0");
              const mEnd = Math.floor(end / 60);
              const sEnd = (end % 60).toString().padStart(2, "0");
              return `[${mStart}:${sStart} - ${mEnd}:${sEnd}] ${c.text.trim()}`;
            })
            .join("\n")
        : whisperResult.text;

      // 3. Gemini Flash Recap Script Generation
      setStage("generating");
      setProgressPercent(68);
      addPipelineLog(
        isMM
          ? `✍️ [အဆင့် ၃/၄] Gemini 2.5 Flash သို့ ပေးပို့၍ ${Math.round(meta.duration)} စက္ကန့်စာ မြန်မာဇာတ်လမ်းဇာတ်ညွှန်း ရေးဖွဲ့နေပါသည်...`
          : `✍️ [Stage 3/4] Generating full-duration ${Math.round(meta.duration)}s recap narration with Gemini 2.5 Flash...`,
        "info"
      );

      const script = await generateRecapScript({
        transcript: formattedTranscript,
        videoDurationSeconds: meta.duration,
        videoTitle: selectedFile.name.replace(/\.[^/.]+$/, ""),
        targetLanguage,
        apiKey: geminiApiKey,
        tone: "dramatic",
      });

      setRecapScript(script);
      const wordsCount = script.trim().split(/\s+/).length;
      addPipelineLog(
        isMM
          ? `✅ ဇာတ်လမ်းအစမှ အဆုံးထိ မြန်မာဇာတ်ညွှန်း (${wordsCount} လုံး) ရေးဖွဲ့ခြင်း အောင်မြင်စွာ ပြီးဆုံးပါပြီ။`
          : `✅ Full story script (${wordsCount} words) generated with complete arc to final scene.`,
        "success"
      );

      // 4. Synthesize Narration Voiceover Audio (TTS)
      setStage("generating");
      setProgressPercent(78);
      addPipelineLog(
        isMM
          ? `🔊 [အဆင့် ၄/၄] ရွေးချယ်ထားသော "${selectedVoice.name}" အသံဖြင့် အသံဒါဘင်ဖိုင်ကို စတင်ဖန်တီးနေပါသည်...`
          : `🔊 [Stage 4/4] Synthesizing continuous voiceover with ${selectedVoice.name}...`,
        "info"
      );

      const narrationBlob = await synthesizeRecapAudio(
        script,
        selectedVoice,
        geminiApiKey
      );
      addPipelineLog(
        isMM
          ? `✅ ဒါဘင်အသံဖိုင် ဖန်တီးပြီးစီးပါပြီ (${(narrationBlob.size / 1024).toFixed(1)} KB, MP3)`
          : `✅ Narration voiceover audio synthesized (${(narrationBlob.size / 1024).toFixed(1)} KB)`,
        "success"
      );

      // 5. Render Final Dubbed Video with FFmpeg WASM
      setStage("rendering");
      setProgressPercent(85);
      addPipelineLog(
        isMM
          ? "🎞️ FFmpeg WASM ဖြင့် ဗီဒီယိုနှင့် မြန်မာအသံကို ပေါင်းစပ်ဒါဘင်သွင်းပြီး Anti-Copyright / Watermark Blur အထူးပြုလုပ်ချက်များ ထည့်သွင်းနေပါသည်..."
          : "🎞️ Merging dubbed audio & video with FFmpeg WASM, applying watermark blur & anti-copyright FX...",
        "info"
      );

      const finalDubbedBlob = await renderDubbedRecapVideo(
        selectedFile,
        narrationBlob,
        blurConfig,
        copyrightConfig,
        (pct) => {
          const currentTotal = 85 + Math.round(pct * 0.14);
          setProgressPercent(currentTotal);
        }
      );

      setRenderedVideoBlob(finalDubbedBlob);
      setActiveTab("video"); // Automatically open the dubbed video player!
      setStage("completed");
      setProgressPercent(100);
      addPipelineLog(
        isMM
          ? "🎉 ဗီဒီယို ဒါဘင်ပြုလုပ်ခြင်း ၁၀၀% အောင်မြင်စွာ ပြီးဆုံးပါပြီ! Rendered Video တက်ဘ်တွင် စစ်ဆေးကြည့်ရှုနိုင်ပါပြီ။"
          : "🎉 Dubbed recap video rendered 100% successfully! Ready to play & download in the Video tab.",
        "success"
      );
    } catch (err: any) {
      console.error("Pipeline failure:", err);
      setStage("error");
      const errStr = err.message || "An unexpected error occurred during processing.";
      setErrorMessage(errStr);
      addPipelineLog(
        isMM ? `❌ ချို့ယွင်းချက် ဖြစ်ပေါ်ပါသည်: ${errStr}` : `❌ Pipeline error: ${errStr}`, 
        "error"
      );
    }
  };

  const copyToClipboard = () => {
    const textToCopy = activeTab === "script" ? recapScript : transcriptData?.text || "";
    navigator.clipboard.writeText(textToCopy);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const downloadText = () => {
    const text = activeTab === "script" ? recapScript : transcriptData?.text || "";
    const filename = `${selectedFile?.name.replace(/\.[^/.]+$/, "") || "recap"}_${activeTab}.txt`;
    const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  };

  const downloadRenderedVideo = () => {
    if (!renderedVideoBlob) return;
    const filename = `recap_${selectedFile?.name.replace(/\.[^/.]+$/, "") || "output"}_dubbed.mp4`;
    const url = URL.createObjectURL(renderedVideoBlob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  };

  // Estimate word count & reading time
  const scriptWords = recapScript.trim() ? recapScript.trim().split(/\s+/).length : 0;
  const estimatedReadTime = scriptWords > 0 ? `${Math.ceil(scriptWords / 2.5)}s speech` : null;

  // Formatted Elapsed Time (MM:SS)
  const formatTimer = (totalSeconds: number) => {
    const mins = Math.floor(totalSeconds / 60);
    const secs = totalSeconds % 60;
    return `${mins.toString().padStart(2, "0")}:${secs.toString().padStart(2, "0")}`;
  };

  // Dynamic estimated remaining seconds calculation
  const estimatedRemaining = stage === "idle" || stage === "completed" || stage === "error"
    ? 0
    : Math.max(Math.round(((100 - progressPercent) / 100) * ((videoMeta?.duration || 60) > 90 ? 45 : 30)), 3);

  // Steps configuration
  const stepsConfig = [
    { id: "extracting" as PipelineStage, ...t.pipeline.steps.extract, icon: Cpu },
    { id: "transcribing" as PipelineStage, ...t.pipeline.steps.transcribe, icon: Mic },
    { id: "generating" as PipelineStage, ...t.pipeline.steps.script, icon: Sparkles },
    { id: "rendering" as PipelineStage, ...t.pipeline.steps.render, icon: Video },
  ];

  return (
    <div className="min-h-screen text-zinc-100 flex flex-col font-sans selection:bg-amber-500 selection:text-zinc-950 relative overflow-x-hidden">
      {/* Dynamic Atmospheric Studio Lighting Blobs (Cyan & Purple Reel Palette) */}
      <div className="ambient-glow-cyan -top-24 -left-20" />
      <div className="ambient-glow-purple top-48 -right-20" />
      <div className="ambient-glow-blue top-[650px] left-[25%]" />

      {/* Agency-Grade Top Navigation Bar */}
      <header className="border-b border-white/[0.08] glass-surface sticky top-0 z-40 px-4 sm:px-8 py-3.5 flex items-center justify-between shadow-ambient">
        <div className="flex items-center gap-3">
          <div className="relative group">
            <div className="absolute -inset-1 bg-gradient-to-r from-cyan-500 via-blue-500 to-purple-600 rounded-xl blur-sm opacity-70 group-hover:opacity-100 transition duration-300"></div>
            <img
              src="/app_logo.png"
              alt="RecapMaster Logo"
              className="relative w-10 h-10 rounded-xl object-cover border border-cyan-500/40 shadow-ambient-cyan"
            />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="font-extrabold text-base sm:text-lg tracking-tight bg-gradient-to-r from-white via-cyan-100 to-blue-200 bg-clip-text text-transparent">
                {t.nav.title}
              </h1>
              <span className="px-2 py-0.5 rounded-full text-[10px] font-mono font-bold uppercase bg-cyan-500/15 text-cyan-300 border border-cyan-500/35 shadow-sm">
                {t.nav.badge}
              </span>
            </div>
            <div className="text-[11px] font-semibold text-cyan-400 tracking-wide">
              by the AI Buddy
            </div>
          </div>
        </div>

        {/* User & Access Controls */}
        <div className="flex items-center gap-2 sm:gap-3">
          {/* 🇲🇲 / 🇬🇧 Language Switcher Toggle Pill */}
          <div className="flex p-0.5 rounded-xl bg-zinc-900/90 border border-white/[0.1] shadow-inner">
            <button
              onClick={() => handleLanguageChange("my")}
              className={`px-2.5 py-1 rounded-lg text-xs font-semibold transition-all duration-150 flex items-center gap-1.5 ${
                appLang === "my"
                  ? "bg-gradient-to-r from-cyan-500 to-blue-600 text-white shadow-ambient-cyan font-bold"
                  : "text-zinc-400 hover:text-white"
              }`}
              title="မြန်မာဘာသာသို့ ပြောင်းမည်"
            >
              <span>🇲🇲</span>
              <span className="hidden sm:inline">မြန်မာ</span>
            </button>
            <button
              onClick={() => handleLanguageChange("en")}
              className={`px-2.5 py-1 rounded-lg text-xs font-semibold transition-all duration-150 flex items-center gap-1.5 ${
                appLang === "en"
                  ? "bg-gradient-to-r from-cyan-500 to-blue-600 text-white shadow-ambient-cyan font-bold"
                  : "text-zinc-400 hover:text-white"
              }`}
              title="Switch to English"
            >
              <span>🇬🇧</span>
              <span>EN</span>
            </button>
          </div>

          {/* Remaining Time Capsule */}
          <div
            className={`flex items-center gap-2 px-3 py-1.5 rounded-xl border text-xs font-mono font-semibold transition shadow-sm ${
              isExpired
                ? "bg-red-500/10 border-red-500/30 text-red-400"
                : "bg-emerald-500/10 border-emerald-500/30 text-emerald-400"
            }`}
          >
            <Clock className="w-3.5 h-3.5" />
            <span>{formattedTimeLeft}</span>
          </div>

          {/* Earn Free Time Button */}
          <button
            onClick={() => setIsAdModalOpen(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-gradient-to-r from-purple-500/15 to-cyan-500/15 hover:from-purple-500/25 hover:to-cyan-500/25 border border-purple-500/35 text-purple-200 text-xs font-semibold transition-all duration-150 hover:-translate-y-0.5 active:scale-95 shadow-sm shadow-purple-500/10"
          >
            <Zap className="w-3.5 h-3.5 text-cyan-400" />
            <span className="hidden sm:inline">{t.nav.earnTime}</span>
            <span className="sm:hidden">{t.nav.earnTimeShort}</span>
          </button>

          {/* Gemini API Key Setting */}
          <button
            onClick={() => setIsKeyModalOpen(true)}
            className={`px-3 py-1.5 rounded-xl border text-xs font-semibold transition-all duration-150 flex items-center gap-1.5 ${
              geminiApiKey
                ? "bg-zinc-900 border-white/[0.1] text-zinc-300 hover:text-white hover:border-white/[0.2]"
                : "bg-cyan-500/15 border-cyan-500/40 text-cyan-300 hover:bg-cyan-500/25 animate-pulse"
            }`}
            title="Configure Gemini API Key"
          >
            <Key className="w-3.5 h-3.5 text-cyan-400" />
            <span className="hidden md:inline font-mono">
              {geminiApiKey ? t.nav.apiKeyConfigured : t.nav.addApiKey}
            </span>
          </button>

          {/* Google Auth Button */}
          {user ? (
            <div className="flex items-center gap-2 pl-2 border-l border-white/[0.08]">
              <div className="w-7 h-7 rounded-xl bg-gradient-to-tr from-amber-500/30 to-orange-500/30 border border-amber-500/40 flex items-center justify-center text-amber-200 text-xs font-bold uppercase shadow-sm">
                {user.displayName?.[0] || user.email?.[0] || "U"}
              </div>
              <span className="text-xs text-zinc-300 font-medium max-w-[110px] truncate hidden xl:inline">
                {user.displayName || user.email}
              </span>
              <button
                onClick={logout}
                className="p-1.5 rounded-xl hover:bg-white/[0.06] text-zinc-400 hover:text-zinc-200 transition"
                title={t.nav.signOut}
              >
                <LogOut className="w-4 h-4" />
              </button>
            </div>
          ) : (
            <button
              onClick={loginWithGoogle}
              className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-xl bg-white hover:bg-zinc-200 text-zinc-950 text-xs font-semibold transition-all active:scale-95 shadow-sm"
            >
              <LogIn className="w-3.5 h-3.5" />
              <span>{t.nav.signIn}</span>
            </button>
          )}
        </div>
      </header>

      {/* Main Studio Workspace Grid */}
      <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 lg:p-8 grid grid-cols-1 lg:grid-cols-12 gap-6 lg:gap-8 relative z-10">
        {/* Left Column: Video Viewport, Studio Tools, and Action Deck */}
        <div className="lg:col-span-6 space-y-6">
          {/* 1. File Upload / Live Video Preview Canvas */}
          <div className="p-5 rounded-2xl glass-surface shadow-card space-y-4">
            <div className="flex items-center justify-between">
              <h2 className="font-semibold text-xs text-zinc-300 uppercase tracking-wider flex items-center gap-2">
                <Video className="w-4 h-4 text-cyan-400" />
                {t.viewport.title}
              </h2>
              {selectedFile ? (
                <button
                  type="button"
                  onClick={() => {
                    setSelectedFile(null);
                    setVideoMeta(null);
                    setYoutubeInfo(null);
                  }}
                  className="text-[11px] text-cyan-400 hover:text-cyan-300 hover:underline cursor-pointer font-medium"
                >
                  {t.viewport.replaceVideo}
                </button>
              ) : (
                <div className="flex items-center p-0.5 bg-zinc-950 border border-white/[0.08] rounded-xl">
                  <button
                    type="button"
                    onClick={() => setInputMode("file")}
                    className={`flex items-center gap-1.5 px-3 py-1 rounded-lg text-[11px] font-medium transition-all ${
                      inputMode === "file"
                        ? "bg-zinc-800 text-cyan-300 shadow-sm border border-cyan-500/25"
                        : "text-zinc-400 hover:text-zinc-200"
                    }`}
                  >
                    <Upload className="w-3 h-3" />
                    <span>{t.youtube.tabUpload}</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => setInputMode("youtube")}
                    className={`flex items-center gap-1.5 px-3 py-1 rounded-lg text-[11px] font-medium transition-all ${
                      inputMode === "youtube"
                        ? "bg-gradient-to-r from-red-600/30 to-rose-600/30 text-red-300 shadow-sm border border-red-500/35"
                        : "text-zinc-400 hover:text-zinc-200"
                    }`}
                  >
                    <Youtube className="w-3 h-3 text-red-500" />
                    <span>{t.youtube.tabYoutube}</span>
                  </button>
                </div>
              )}
            </div>

            {selectedFile ? (
              <VideoPreviewBox
                videoFile={selectedFile}
                blurConfig={blurConfig}
                copyrightConfig={copyrightConfig}
                lang={appLang}
              />
            ) : inputMode === "youtube" ? (
              <div className="border border-white/[0.08] rounded-2xl p-6 bg-zinc-950/60 backdrop-blur-md space-y-4 shadow-inner">
                <div className="flex flex-col sm:flex-row gap-2">
                  <div className="relative flex-1">
                    <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-zinc-500">
                      <Youtube className="w-4 h-4 text-red-400" />
                    </div>
                    <input
                      type="text"
                      value={youtubeUrl}
                      onChange={(e) => {
                        setYoutubeUrl(e.target.value);
                        setYoutubeError("");
                      }}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") fetchYoutubeInfo();
                      }}
                      placeholder={t.youtube.inputPlaceholder}
                      className="w-full bg-zinc-900/90 border border-white/[0.1] rounded-xl pl-10 pr-4 py-2.5 text-xs text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-red-500/60 focus:ring-1 focus:ring-red-500/40 transition-all font-mono"
                    />
                  </div>
                  <button
                    type="button"
                    onClick={fetchYoutubeInfo}
                    disabled={youtubeLoading || !youtubeUrl.trim()}
                    className="px-4 py-2.5 rounded-xl text-xs font-semibold bg-red-600/25 hover:bg-red-600/35 text-red-200 border border-red-500/40 transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-1.5 shrink-0"
                  >
                    {youtubeLoading ? (
                      <>
                        <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                        <span>{t.youtube.fetching}</span>
                      </>
                    ) : (
                      <>
                        <ArrowRight className="w-3.5 h-3.5" />
                        <span>{t.youtube.fetchBtn}</span>
                      </>
                    )}
                  </button>
                </div>

                {youtubeError && (
                  <div className="p-3 rounded-xl bg-red-950/40 border border-red-500/30 text-xs text-red-300 flex items-center gap-2">
                    <AlertCircle className="w-4 h-4 shrink-0 text-red-400" />
                    <span>{youtubeError}</span>
                  </div>
                )}

                {youtubeInfo && (
                  <div className="p-4 rounded-xl border border-white/[0.08] bg-zinc-900/70 space-y-3.5 animate-in fade-in slide-in-from-top-2 duration-200">
                    <div className="flex flex-col sm:flex-row gap-3.5 items-start sm:items-center">
                      <div className="relative w-full sm:w-36 h-24 rounded-lg overflow-hidden bg-black shrink-0 border border-white/[0.08]">
                        <img
                          src={youtubeInfo.thumbnail}
                          alt={youtubeInfo.title}
                          className="w-full h-full object-cover"
                        />
                        <div className="absolute bottom-1 right-1 px-1.5 py-0.5 rounded bg-black/80 font-mono text-[10px] text-zinc-300">
                          {formatTimer(youtubeInfo.duration)}
                        </div>
                      </div>
                      <div className="flex-1 min-w-0 flex flex-col justify-between py-0.5 space-y-1">
                        <div>
                          <h4 className="text-xs font-semibold text-zinc-100 line-clamp-2 leading-snug">
                            {youtubeInfo.title}
                          </h4>
                          <p className="text-[11px] text-zinc-400 mt-1">
                            {t.youtube.author}: <span className="text-zinc-200">{youtubeInfo.channel}</span>
                          </p>
                        </div>
                        <div className="flex items-center gap-2 text-[10px] text-zinc-500 font-mono pt-1">
                          <span>{t.youtube.duration}: {youtubeInfo.duration}s</span>
                          <span>•</span>
                          <span>MP4 Progressive HD</span>
                        </div>
                      </div>
                    </div>

                    <div className="space-y-2.5 pt-1">
                      <button
                        type="button"
                        onClick={downloadAndLoadYoutubeVideo}
                        disabled={youtubeDownloading}
                        className="w-full py-3 px-4 rounded-xl text-xs font-bold bg-gradient-to-r from-red-600 via-rose-600 to-purple-600 hover:from-red-500 hover:to-purple-500 text-white shadow-ambient-glow transition-all flex items-center justify-center gap-2 disabled:opacity-50 active:scale-[0.99]"
                      >
                        {youtubeDownloading ? (
                          <>
                            <RefreshCw className="w-4 h-4 animate-spin" />
                            <span>{appLang === "my" ? "ဗီဒီယို တိုက်ရိုက် ဒေါင်းလုဒ်လုပ်နေပါသည်..." : "Downloading video directly into studio..."}</span>
                          </>
                        ) : (
                          <>
                            <Download className="w-4 h-4" />
                            <span>{appLang === "my" ? "ဗီဒီယို တိုက်ရိုက် ရယူပြီး Studio သို့ထည့်မည်" : "Direct Download & Load into Studio"}</span>
                          </>
                        )}
                      </button>

                      <div className="flex items-center justify-between text-[11px] text-zinc-400 px-1 pt-0.5">
                        <span className="flex items-center gap-1 text-zinc-400">
                          <Sparkles className="w-3 h-3 text-cyan-400" />
                          <span>{appLang === "my" ? "တိုက်ရိုက်ဒေါင်းလုဒ် မရပါက:" : "Alternative 1-click:"}</span>
                        </span>
                        <a
                          href={youtubeInfo.externalDownloadUrl || `https://y2mate.is/watch?v=${youtubeInfo.videoId}`}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="text-cyan-400 hover:text-cyan-300 hover:underline flex items-center gap-1 font-medium"
                        >
                          <span>{appLang === "my" ? "ပြင်ပမှ MP4 ဒေါင်းလုဒ်ရယူရန်" : "Download MP4 via Helper"}</span>
                          <ExternalLink className="w-3 h-3" />
                        </a>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            ) : (
              <label className="border-2 border-dashed border-zinc-800 hover:border-amber-500/40 rounded-2xl p-10 flex flex-col items-center justify-center text-center cursor-pointer transition-all duration-200 bg-zinc-950/40 hover:bg-zinc-900/30 group shadow-inner">
                <input
                  type="file"
                  accept="video/*,audio/*"
                  onChange={handleFileChange}
                  className="hidden"
                />
                <div className="w-14 h-14 rounded-2xl bg-zinc-900 border border-white/[0.08] group-hover:border-amber-500/30 group-hover:bg-amber-500/10 flex items-center justify-center text-zinc-400 group-hover:text-amber-400 transition-all duration-200 mb-3.5 shadow-sm group-hover:scale-105">
                  <Upload className="w-6 h-6" />
                </div>
                <span className="text-sm font-semibold text-zinc-200 tracking-tight">
                  {t.viewport.dropzoneTitle}
                </span>
                <span className="text-xs text-zinc-500 mt-1 max-w-xs leading-relaxed">
                  {t.viewport.dropzoneDesc}
                </span>
              </label>
            )}
          </div>

          {/* 2. Advanced Tools: Blur Box, Anti-Copyright FX, Voice Selection */}
          <StudioToolsAccordion
            blurConfig={blurConfig}
            onBlurChange={setBlurConfig}
            copyrightConfig={copyrightConfig}
            onCopyrightChange={setCopyrightConfig}
            selectedVoice={selectedVoice}
            onVoiceChange={setSelectedVoice}
            geminiApiKey={geminiApiKey}
            lang={appLang}
          />

          {/* 3. Action Deck & Pipeline Dispatcher */}
          <div className="p-5 rounded-2xl glass-surface shadow-card space-y-4">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div>
                <label className="text-xs font-semibold text-zinc-300 flex items-center gap-1.5">
                  <Globe className="w-3.5 h-3.5 text-cyan-400" />
                  {t.action.targetLangLabel}
                </label>
                <p className="text-[11px] text-zinc-500 mt-0.5">
                  {t.action.targetLangDesc}
                </p>
              </div>

              <select
                value={targetLanguage}
                onChange={(e) => setTargetLanguage(e.target.value)}
                className="bg-zinc-950 border border-white/[0.1] rounded-xl px-3.5 py-2 text-xs font-medium text-zinc-200 focus:outline-none focus:border-cyan-500 cursor-pointer shadow-inner"
              >
                <option value="my">{t.action.langBurmese}</option>
                <option value="en">{t.action.langEnglish}</option>
                <option value="zh">{t.action.langChinese}</option>
                <option value="th">{t.action.langThai}</option>
              </select>
            </div>

            <button
              onClick={startPipeline}
              disabled={!selectedFile || stage === "extracting" || stage === "transcribing" || stage === "generating" || stage === "rendering"}
              className="w-full py-4 px-6 rounded-xl bg-gradient-to-r from-cyan-500 via-blue-600 to-purple-600 hover:from-cyan-400 hover:via-blue-500 hover:to-purple-500 text-white font-extrabold text-sm flex items-center justify-center gap-2.5 transition-all duration-200 hover:-translate-y-0.5 active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed shadow-ambient-glow"
            >
              {stage === "idle" || stage === "completed" || stage === "error" ? (
                <>
                  <Play className="w-4 h-4 fill-white" />
                  {t.action.startDubbing}
                </>
              ) : (
                <>
                  <RefreshCw className="w-4 h-4 animate-spin text-white" />
                  <span>{t.action.processing}</span>
                </>
              )}
            </button>
          </div>
        </div>

        {/* Right Column: Pipeline Engine, Live Timers & Studio Output Workspace */}
        <div className="lg:col-span-6 space-y-6 flex flex-col">
          {/* Detailed Pipeline Orchestration Engine */}
          <div className="p-5 rounded-2xl glass-surface shadow-card space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="font-semibold text-xs text-zinc-300 uppercase tracking-wider flex items-center gap-2">
                  <Activity className="w-4 h-4 text-cyan-400" />
                  {t.pipeline.title}
                </h2>
                <p className="text-[11px] text-zinc-500 mt-0.5">
                  {t.pipeline.subtitle}
                </p>
              </div>

              {/* Status Badge */}
              <span className={`text-[11px] font-mono px-2.5 py-1 rounded-full font-semibold flex items-center gap-1.5 ${
                stage === "completed" 
                  ? "bg-emerald-500/15 text-emerald-300 border border-emerald-500/30"
                  : stage === "error"
                  ? "bg-red-500/15 text-red-400 border border-red-500/30"
                  : stage === "idle"
                  ? "bg-zinc-800 text-zinc-400 border border-white/[0.05]"
                  : "bg-amber-500/15 text-amber-300 border border-amber-500/30 animate-pulse"
              }`}>
                {stage === "idle" && t.pipeline.status.standby}
                {stage === "extracting" && t.pipeline.status.extracting}
                {stage === "transcribing" && t.pipeline.status.transcribing}
                {stage === "generating" && t.pipeline.status.generating}
                {stage === "rendering" && t.pipeline.status.rendering}
                {stage === "completed" && t.pipeline.status.completed}
                {stage === "error" && t.pipeline.status.error}
              </span>
            </div>

            {/* Time & Speed Metrics Dashboard */}
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5 pt-1">
              {/* Elapsed Timer */}
              <div className="p-3 rounded-xl bg-zinc-950/60 border border-white/[0.05] flex items-center gap-3">
                <div className="w-8 h-8 rounded-lg bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-amber-400">
                  <Clock className="w-4 h-4" />
                </div>
                <div>
                  <span className="text-[10px] text-zinc-400 uppercase font-mono block">{t.pipeline.elapsed}</span>
                  <span className="text-sm font-bold font-mono text-zinc-100">{formatTimer(elapsedSeconds)}</span>
                </div>
              </div>

              {/* Estimated Remaining Time */}
              <div className="p-3 rounded-xl bg-zinc-950/60 border border-white/[0.05] flex items-center gap-3">
                <div className="w-8 h-8 rounded-lg bg-cyan-500/10 border border-cyan-500/20 flex items-center justify-center text-cyan-400">
                  <Hourglass className="w-4 h-4" />
                </div>
                <div>
                  <span className="text-[10px] text-zinc-400 uppercase font-mono block">{t.pipeline.estLeft}</span>
                  <span className="text-sm font-bold font-mono text-cyan-300">
                    {stage === "completed" ? t.pipeline.estLeftDone : stage === "idle" ? "--:--" : `~${estimatedRemaining}s`}
                  </span>
                </div>
              </div>

              {/* Current Progress % */}
              <div className="p-3 rounded-xl bg-zinc-950/60 border border-white/[0.05] flex items-center gap-3 col-span-2 sm:col-span-1">
                <div className="w-8 h-8 rounded-lg bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center text-emerald-400">
                  <Zap className="w-4 h-4" />
                </div>
                <div>
                  <span className="text-[10px] text-zinc-400 uppercase font-mono block">{t.pipeline.progress}</span>
                  <span className="text-sm font-bold font-mono text-emerald-300">{progressPercent}% {t.pipeline.done}</span>
                </div>
              </div>
            </div>

            {/* Stepper Stage Cards */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5 pt-1">
              {stepsConfig.map((step, idx) => {
                const isCurrent = stage === step.id;
                const isFinished = 
                  stage === "completed" ||
                  (stage === "transcribing" && idx < 1) ||
                  (stage === "generating" && idx < 2) ||
                  (stage === "rendering" && idx < 3);

                const StepIcon = step.icon;

                return (
                  <div
                    key={step.id}
                    className={`p-3.5 rounded-xl border transition-all duration-200 flex flex-col justify-between ${
                      isCurrent
                        ? "bg-cyan-500/10 border-cyan-500/40 text-cyan-300 shadow-ambient-cyan"
                        : isFinished
                        ? "bg-emerald-500/10 border-emerald-500/30 text-emerald-400"
                        : "bg-zinc-950/40 border-white/[0.04] text-zinc-500"
                    }`}
                  >
                    <div className="flex items-center justify-between mb-2">
                      <div className="flex items-center gap-2">
                        <div className={`w-7 h-7 rounded-lg flex items-center justify-center ${
                          isCurrent
                            ? "bg-cyan-500/20 text-cyan-400 animate-pulse"
                            : isFinished
                            ? "bg-emerald-500/20 text-emerald-400"
                            : "bg-zinc-900 text-zinc-600"
                        }`}>
                          {isFinished ? (
                            <Check className="w-4 h-4" />
                          ) : (
                            <StepIcon className="w-4 h-4" />
                          )}
                        </div>
                        <span className="text-xs font-mono font-bold">{step.step}</span>
                      </div>

                      <span className="text-[10px] font-mono opacity-70 px-2 py-0.5 rounded bg-zinc-900/80 border border-white/[0.04]">
                        {step.tech}
                      </span>
                    </div>

                    <div>
                      <span className={`font-semibold text-xs block leading-tight ${isCurrent ? "text-cyan-300" : isFinished ? "text-emerald-300" : "text-zinc-200"}`}>
                        {step.title}
                      </span>
                      <span className="text-[11px] text-zinc-400 block mt-1 leading-snug">
                        {step.desc}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Live Progress Bar & Status Text */}
            <div className="space-y-2 pt-2 border-t border-white/[0.06]">
              <div className="flex items-center justify-between text-xs">
                <span className="text-zinc-300 font-medium truncate max-w-[80%] flex items-center gap-1.5">
                  {stage !== "idle" && stage !== "completed" && stage !== "error" && (
                    <span className="w-2 h-2 rounded-full bg-cyan-400 animate-ping"></span>
                  )}
                  {statusMessage}
                </span>
                <span className="font-mono text-cyan-400 font-bold">{progressPercent}%</span>
              </div>

              <div className="w-full bg-zinc-950 h-2.5 rounded-full overflow-hidden border border-white/[0.06] p-0.5">
                <div
                  className="bg-gradient-to-r from-cyan-500 via-blue-500 to-purple-500 h-full rounded-full transition-all duration-300 shadow-sm"
                  style={{ width: `${progressPercent}%` }}
                />
              </div>

              {stage === "error" && (
                <div className="p-3 rounded-xl bg-red-500/10 border border-red-500/25 text-red-300 text-xs flex items-start gap-2.5">
                  <AlertCircle className="w-4 h-4 shrink-0 mt-0.5 text-red-400" />
                  <span>{errorMessage}</span>
                </div>
              )}
            </div>

            {/* Real-Time Live Process Logs */}
            <div className="pt-2 border-t border-white/[0.06]">
              <button
                onClick={() => setIsLogsExpanded(!isLogsExpanded)}
                className="w-full flex items-center justify-between text-xs text-zinc-400 hover:text-zinc-200 transition py-1 font-mono"
              >
                <span className="flex items-center gap-2">
                  <Terminal className="w-3.5 h-3.5 text-amber-400" />
                  {t.pipeline.liveLogs}
                  {pipelineLogs.length > 0 && (
                    <span className="px-1.5 py-0.2 rounded bg-zinc-800 text-[10px] text-zinc-300">
                      {pipelineLogs.length}
                    </span>
                  )}
                </span>
                {isLogsExpanded ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
              </button>

              {isLogsExpanded && (
                <div 
                  ref={logsContainerRef}
                  className="mt-2 p-3 rounded-xl bg-zinc-950/80 border border-white/[0.05] max-h-44 overflow-y-auto space-y-1.5 font-mono text-[11px] shadow-inner"
                >
                  {pipelineLogs.length === 0 ? (
                    <div className="text-zinc-600 text-center py-4 text-xs font-sans">
                      {t.pipeline.noLogsYet}
                    </div>
                  ) : (
                    pipelineLogs.map((log, idx) => (
                      <div key={idx} className="flex items-start gap-2 leading-relaxed">
                        <span className="text-zinc-500 shrink-0">[{log.time}]</span>
                        <span className={
                          log.type === "success" 
                            ? "text-emerald-400" 
                            : log.type === "error" 
                            ? "text-red-400 font-semibold" 
                            : log.type === "warn" 
                            ? "text-amber-400" 
                            : "text-zinc-300"
                        }>
                          {log.text}
                        </span>
                      </div>
                    ))
                  )}
                </div>
              )}
            </div>
          </div>

          {/* Studio Output Workspace */}
          <div className="flex-1 p-5 rounded-2xl glass-surface shadow-card flex flex-col min-h-[440px]">
            {/* Header Tabs & Actions */}
            <div className="flex flex-wrap items-center justify-between border-b border-white/[0.08] pb-3 mb-4 gap-2">
              <div className="flex p-1 rounded-xl bg-zinc-950/80 border border-white/[0.06] gap-1">
                <button
                  onClick={() => setActiveTab("script")}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-150 ${
                    activeTab === "script"
                      ? "bg-amber-500/15 text-amber-300 border border-amber-500/30 shadow-sm"
                      : "text-zinc-400 hover:text-zinc-200"
                  }`}
                >
                  {t.output.tabScript}
                </button>
                <button
                  onClick={() => setActiveTab("transcript")}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-150 ${
                    activeTab === "transcript"
                      ? "bg-amber-500/15 text-amber-300 border border-amber-500/30 shadow-sm"
                      : "text-zinc-400 hover:text-zinc-200"
                  }`}
                >
                  {t.output.tabTranscript}
                </button>
                {renderedVideoBlob && (
                  <button
                    onClick={() => setActiveTab("video")}
                    className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-150 ${
                      activeTab === "video"
                        ? "bg-purple-500/15 text-purple-300 border border-purple-500/30 shadow-sm"
                        : "text-zinc-400 hover:text-zinc-200"
                    }`}
                  >
                    {t.output.tabVideo}
                  </button>
                )}
              </div>

              {/* Action Toolbar */}
              <div className="flex items-center gap-2">
                {activeTab === "script" && recapScript && (
                  <div className="hidden sm:flex items-center gap-2 text-[11px] font-mono text-zinc-400 mr-1">
                    <span className="px-2 py-0.5 rounded-md bg-zinc-900 border border-white/[0.06]">
                      {scriptWords} {t.output.words}
                    </span>
                    {estimatedReadTime && (
                      <span className="px-2 py-0.5 rounded-md bg-zinc-900 border border-white/[0.06] text-amber-400">
                        {estimatedReadTime}
                      </span>
                    )}
                  </div>
                )}

                {(recapScript || transcriptData) && activeTab !== "video" && (
                  <>
                    <button
                      onClick={copyToClipboard}
                      className="p-2 rounded-xl hover:bg-white/[0.06] border border-white/[0.06] text-zinc-400 hover:text-white transition-all active:scale-95"
                      title={t.output.copyTooltip}
                    >
                      {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                    </button>
                    <button
                      onClick={downloadText}
                      className="p-2 rounded-xl hover:bg-white/[0.06] border border-white/[0.06] text-zinc-400 hover:text-white transition-all active:scale-95"
                      title={t.output.downloadTxt}
                    >
                      <Download className="w-3.5 h-3.5" />
                    </button>
                  </>
                )}

                {renderedVideoBlob && activeTab === "video" && (
                  <button
                    onClick={downloadRenderedVideo}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-purple-500/20 hover:bg-purple-500/30 text-purple-200 border border-purple-500/40 text-xs font-semibold transition-all hover:-translate-y-0.5 active:scale-95 shadow-sm"
                  >
                    <Download className="w-3.5 h-3.5" />
                    {t.output.downloadMp4}
                  </button>
                )}
              </div>
            </div>

            {/* Content Display Container */}
            <div className="flex-1 bg-zinc-950/70 rounded-xl p-4 border border-white/[0.06] overflow-y-auto max-h-[460px] shadow-inner">
              {activeTab === "script" ? (
                recapScript ? (
                  <div className="space-y-4">
                    <p className="text-zinc-200 text-sm leading-relaxed whitespace-pre-wrap font-sans selection:bg-amber-500/30">
                      {recapScript}
                    </p>
                  </div>
                ) : (
                  <div className="h-full min-h-[300px] flex flex-col items-center justify-center text-zinc-600 text-xs p-6 text-center">
                    <FileText className="w-10 h-10 mb-2 opacity-30 text-zinc-500" />
                    <span className="font-semibold text-zinc-400 text-sm">
                      {t.output.scriptEmptyTitle}
                    </span>
                    <span className="text-zinc-600 max-w-xs mt-1 leading-relaxed">
                      {t.output.scriptEmptyDesc}
                    </span>
                  </div>
                )
              ) : activeTab === "transcript" ? (
                transcriptData ? (
                  <div className="space-y-2">
                    {transcriptData.chunks.map((chunk, i) => (
                      <div key={i} className="text-xs flex gap-2.5 p-2 rounded-lg bg-zinc-900/40 border border-white/[0.02]">
                        <span className="font-mono text-amber-400/90 shrink-0 font-medium text-[11px]">
                          [{Math.floor(chunk.timestamp[0])}s - {Math.floor(chunk.timestamp[1] || 0)}s]
                        </span>
                        <span className="text-zinc-300 leading-relaxed">{chunk.text}</span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="h-full min-h-[300px] flex flex-col items-center justify-center text-zinc-600 text-xs p-6 text-center">
                    <Mic className="w-10 h-10 mb-2 opacity-30 text-zinc-500" />
                    <span className="font-semibold text-zinc-400 text-sm">
                      {t.output.transcriptEmptyTitle}
                    </span>
                    <span className="text-zinc-600 max-w-xs mt-1 leading-relaxed">
                      {t.output.transcriptEmptyDesc}
                    </span>
                  </div>
                )
              ) : renderedVideoBlob ? (
                <div className="flex flex-col items-center justify-center h-full space-y-4 py-4">
                  <div className="relative w-full aspect-video rounded-xl overflow-hidden border border-white/[0.1] bg-black shadow-ambient">
                    <video
                      src={URL.createObjectURL(renderedVideoBlob)}
                      controls
                      className="w-full h-full object-contain"
                    />
                  </div>
                  <div className="flex items-center gap-3 w-full justify-center">
                    <button
                      onClick={downloadRenderedVideo}
                      className="flex items-center gap-2 px-6 py-2.5 rounded-xl bg-gradient-to-r from-purple-500 to-indigo-500 hover:from-purple-400 hover:to-indigo-400 text-white font-bold text-xs shadow-ambient-glow transition-all hover:-translate-y-0.5 active:scale-95"
                    >
                      <Download className="w-4 h-4" />
                      {t.output.downloadMp4}
                    </button>
                  </div>
                </div>
              ) : null}
            </div>
          </div>
        </div>

        {/* Sponsored Advertisements Banner & Container */}
        <div className="lg:col-span-12 grid grid-cols-1 md:grid-cols-2 gap-4 items-center pt-2">
          <AdBanner468x60 />
          <AdNativeContainer />
        </div>
      </main>

      {/* Rewarded Ad Modal */}
      <RewardedAdModal
        isOpen={isAdModalOpen}
        onClose={() => setIsAdModalOpen(false)}
        onRewardEarned={grantAdRewardMinutes}
        lang={appLang}
      />

      {/* Gemini API Key Setting Modal */}
      {isKeyModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/85 backdrop-blur-md p-4">
          <div className="w-full max-w-md glass-surface border border-white/[0.12] rounded-3xl p-6 sm:p-7 text-white space-y-5 shadow-ambient-glow animate-in fade-in zoom-in-95 duration-200">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-2xl bg-amber-500/15 border border-amber-500/30 flex items-center justify-center text-amber-400">
                <Key className="w-5 h-5" />
              </div>
              <div>
                <h3 className="font-extrabold text-base tracking-tight text-white">
                  {t.keyModal.title}
                </h3>
                <span className="text-[11px] text-zinc-400 font-mono">
                  {t.keyModal.subtitle}
                </span>
              </div>
            </div>

            <p className="text-xs text-zinc-400 leading-relaxed">
              {t.keyModal.desc}
            </p>

            <div className="space-y-1.5">
              <label className="text-[11px] font-mono uppercase tracking-wider text-zinc-400">
                {t.keyModal.inputLabel}
              </label>
              <input
                type="password"
                placeholder="AIzaSy..."
                defaultValue={geminiApiKey}
                id="gemini-key-input"
                className="w-full bg-zinc-950/80 border border-white/[0.1] focus:border-amber-500/60 rounded-xl px-4 py-3 text-sm font-mono text-zinc-100 placeholder:text-zinc-600 focus:outline-none focus:ring-2 focus:ring-amber-500/20 transition-all shadow-inner"
              />
            </div>

            <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/20 flex items-center justify-between text-xs text-amber-300">
              <span>{t.keyModal.noKey}</span>
              <a
                href="https://aistudio.google.com/app/apikey"
                target="_blank"
                rel="noreferrer"
                className="font-semibold underline flex items-center gap-1 hover:text-amber-200"
              >
                {t.keyModal.getKey} <ExternalLink className="w-3 h-3" />
              </a>
            </div>

            <div className="flex justify-end gap-2.5 pt-2">
              <button
                onClick={() => setIsKeyModalOpen(false)}
                className="px-4 py-2.5 rounded-xl text-xs font-medium text-zinc-400 hover:text-white transition"
              >
                {t.keyModal.cancel}
              </button>
              <button
                onClick={() => {
                  const input = document.getElementById("gemini-key-input") as HTMLInputElement;
                  if (input) saveApiKey(input.value);
                }}
                className="px-5 py-2.5 rounded-xl bg-gradient-to-r from-amber-500 to-orange-500 hover:from-amber-400 hover:to-orange-400 text-zinc-950 font-bold text-xs shadow-ambient-glow transition-all active:scale-95"
              >
                {t.keyModal.save}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
