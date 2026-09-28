"use client";

import React, { useState, useEffect } from "react";
import { Play, Sparkles, CheckCircle2, X, Clock, ExternalLink, ShieldCheck } from "lucide-react";
import { AdBanner468x60 } from "@/components/AdBanner468x60";
import { Language, translations } from "@/lib/i18n/translations";

interface RewardedAdModalProps {
  isOpen: boolean;
  onClose: () => void;
  onRewardEarned: (minutes: number) => Promise<boolean>;
  adsterraUrl?: string;
  lang?: Language;
}

export function RewardedAdModal({
  isOpen,
  onClose,
  onRewardEarned,
  adsterraUrl = "https://www.profitablecpmrate.com/c47y3f5j?key=adsterra_recap_master",
  lang = "my",
}: RewardedAdModalProps) {
  const [adState, setAdState] = useState<"ready" | "watching" | "completed">("ready");
  const [countdown, setCountdown] = useState<number>(15);
  const [isClaiming, setIsClaiming] = useState<boolean>(false);
  const t = translations[lang].rewardModal;

  useEffect(() => {
    let interval: NodeJS.Timeout;
    if (adState === "watching" && countdown > 0) {
      interval = setInterval(() => {
        setCountdown((prev) => prev - 1);
      }, 1000);
    } else if (adState === "watching" && countdown === 0) {
      setAdState("completed");
    }
    return () => clearInterval(interval);
  }, [adState, countdown]);

  if (!isOpen) return null;

  const startAd = () => {
    if (adsterraUrl) {
      window.open(adsterraUrl, "_blank", "noopener,noreferrer");
    }
    setAdState("watching");
    setCountdown(15);
  };

  const handleClaim = async () => {
    setIsClaiming(true);
    const success = await onRewardEarned(10);
    setIsClaiming(false);
    if (success) {
      onClose();
      setAdState("ready");
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/85 backdrop-blur-md p-4 animate-in fade-in duration-200">
      <div className="relative w-full max-w-md rounded-3xl glass-surface border border-white/[0.12] p-6 sm:p-7 text-white shadow-ambient-glow animate-in zoom-in-95 duration-200">
        <button
          onClick={onClose}
          className="absolute top-5 right-5 p-1.5 rounded-xl hover:bg-white/[0.08] text-zinc-400 hover:text-white transition-all active:scale-95"
          title="Close Modal"
        >
          <X className="w-5 h-5" />
        </button>

        {adState === "ready" && (
          <div className="text-center space-y-5 pt-2">
            <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-amber-500/15 border border-amber-500/30 text-amber-400 shadow-ambient-glow">
              <Sparkles className="w-8 h-8" />
            </div>
            <div>
              <h3 className="text-xl font-extrabold tracking-tight text-white">
                {t.title}
              </h3>
              <p className="text-xs text-zinc-400 max-w-sm mx-auto mt-1 leading-relaxed">
                {t.desc}
              </p>
            </div>

            <div className="bg-zinc-950/70 border border-white/[0.06] rounded-2xl p-3.5 flex items-center justify-between text-xs text-zinc-300 shadow-inner">
              <span className="flex items-center gap-2">
                <Clock className="w-4 h-4 text-amber-400" />
                <span>{t.rewardLabel}</span>
                <strong className="text-amber-300 font-mono">{t.rewardValue}</strong>
              </span>
              <span className="text-[11px] font-mono text-zinc-500">{t.fairCap}</span>
            </div>

            <button
              onClick={startAd}
              className="w-full flex items-center justify-center gap-2.5 py-3.5 px-5 rounded-xl bg-gradient-to-r from-amber-500 via-amber-400 to-orange-500 hover:from-amber-400 hover:to-orange-400 text-zinc-950 font-extrabold text-sm shadow-ambient-glow transition-all duration-150 hover:-translate-y-0.5 active:scale-[0.98]"
            >
              <Play className="w-4 h-4 fill-zinc-950" />
              {t.watchBtn}
            </button>
          </div>
        )}

        {adState === "watching" && (
          <div className="text-center space-y-5 py-4">
            <div className="relative mx-auto w-24 h-24 flex items-center justify-center">
              <div className="absolute inset-0 rounded-full border-4 border-zinc-800 border-t-amber-400 animate-spin" />
              <span className="text-3xl font-extrabold font-mono text-amber-400">{countdown}s</span>
            </div>
            <div>
              <h4 className="font-bold text-lg text-white">{t.watchingTitle}</h4>
              <p className="text-xs text-zinc-400 mt-1">
                {t.watchingDesc}
              </p>
            </div>
            <div className="w-full bg-zinc-950 h-2 rounded-full overflow-hidden border border-white/[0.06]">
              <div
                className="bg-gradient-to-r from-amber-500 to-orange-500 h-full transition-all duration-1000 ease-linear rounded-full"
                style={{ width: `${((15 - countdown) / 15) * 100}%` }}
              />
            </div>

            {/* Embedded Sponsor 468x60 Banner */}
            <div className="pt-2">
              <AdBanner468x60 />
            </div>
          </div>
        )}

        {adState === "completed" && (
          <div className="text-center space-y-5 py-3">
            <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-emerald-500/15 border border-emerald-500/30 text-emerald-400 shadow-sm">
              <CheckCircle2 className="w-8 h-8" />
            </div>
            <div>
              <h3 className="text-xl font-extrabold tracking-tight text-white">
                {t.completedTitle}
              </h3>
              <p className="text-xs text-zinc-400 max-w-sm mx-auto mt-1 leading-relaxed">
                {t.completedDesc}
              </p>
            </div>
            <button
              onClick={handleClaim}
              disabled={isClaiming}
              className="w-full flex items-center justify-center gap-2 py-3.5 px-5 rounded-xl bg-gradient-to-r from-emerald-500 to-teal-500 hover:from-emerald-400 hover:to-teal-400 text-zinc-950 font-extrabold text-sm shadow-sm transition-all duration-150 hover:-translate-y-0.5 active:scale-[0.98]"
            >
              {isClaiming ? t.claiming : t.claimBtn}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
