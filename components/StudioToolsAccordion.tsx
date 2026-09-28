"use client";

import React, { useState } from "react";
import { 
  Shield, 
  Sparkles, 
  Mic, 
  Sliders, 
  FlipHorizontal, 
  Volume2, 
  Square,
  Check, 
  ChevronDown, 
  ChevronUp,
  Layers,
  Palette
} from "lucide-react";
import { BlurBoxConfig, CopyrightBypassConfig } from "@/components/VideoPreviewBox";
import { VOICE_PROFILES, VoiceProfile } from "@/lib/data/voiceProfiles";
import { auditionVoiceProfile } from "@/lib/tts/webTtsClient";
import { Language, translations } from "@/lib/i18n/translations";

interface StudioToolsAccordionProps {
  blurConfig: BlurBoxConfig;
  onBlurChange: (cfg: BlurBoxConfig) => void;
  copyrightConfig: CopyrightBypassConfig;
  onCopyrightChange: (cfg: CopyrightBypassConfig) => void;
  selectedVoice: VoiceProfile;
  onVoiceChange: (voice: VoiceProfile) => void;
  geminiApiKey: string;
  lang?: Language;
}

export function StudioToolsAccordion({
  blurConfig,
  onBlurChange,
  copyrightConfig,
  onCopyrightChange,
  selectedVoice,
  onVoiceChange,
  geminiApiKey,
  lang = "my",
}: StudioToolsAccordionProps) {
  const [activeTab, setActiveTab] = useState<"blur" | "copyright" | "voice">("blur");
  const [isAuditioning, setIsAuditioning] = useState(false);
  const t = translations[lang].tools;

  const presetsList = [
    { label: t.presets.bottomSubs, x: 0, y: 0.78, w: 1, h: 0.20 },
    { label: t.presets.lowerThird, x: 0, y: 0.70, w: 1, h: 0.28 },
    { label: t.presets.topBar, x: 0, y: 0, w: 1, h: 0.16 },
    { label: t.presets.topRightLogo, x: 0.78, y: 0.04, w: 0.18, h: 0.08 },
    { label: t.presets.topLeftLogo, x: 0.04, y: 0.04, w: 0.18, h: 0.08 },
    { label: t.presets.bottomRight, x: 0.78, y: 0.88, w: 0.18, h: 0.08 },
    { label: t.presets.bottomLeft, x: 0.04, y: 0.88, w: 0.18, h: 0.08 },
  ];

  const applyPreset = (preset: typeof presetsList[0]) => {
    onBlurChange({
      ...blurConfig,
      enabled: true,
      xPct: preset.x,
      yPct: preset.y,
      wPct: preset.w,
      hPct: preset.h,
    });
  };

  const handleAudition = async () => {
    setIsAuditioning(true);
    await auditionVoiceProfile(selectedVoice, geminiApiKey);
    setTimeout(() => setIsAuditioning(false), 2000);
  };

  return (
    <div className="rounded-2xl glass-surface overflow-hidden shadow-card border border-white/[0.09] text-sm transition-all duration-200">
      {/* High-Intent Tab Selectors */}
      <div className="grid grid-cols-3 border-b border-white/[0.08] bg-zinc-950/70 p-1.5 gap-1.5 text-xs font-semibold">
        <button
          onClick={() => setActiveTab("blur")}
          className={`py-2 px-3 rounded-xl flex items-center justify-center gap-1.5 transition-all duration-200 ${
            activeTab === "blur"
              ? "bg-gradient-to-r from-cyan-500/20 to-blue-500/15 text-cyan-300 border border-cyan-500/35 shadow-sm shadow-cyan-500/10"
              : "text-zinc-400 hover:text-zinc-200 hover:bg-white/[0.04]"
          }`}
        >
          <Shield className="w-3.5 h-3.5 text-cyan-400" />
          <span>{t.tabBlur}</span>
        </button>

        <button
          onClick={() => setActiveTab("copyright")}
          className={`py-2 px-3 rounded-xl flex items-center justify-center gap-1.5 transition-all duration-200 ${
            activeTab === "copyright"
              ? "bg-gradient-to-r from-purple-500/20 to-indigo-500/15 text-purple-200 border border-purple-500/35 shadow-sm shadow-purple-500/10"
              : "text-zinc-400 hover:text-zinc-200 hover:bg-white/[0.04]"
          }`}
        >
          <Sparkles className="w-3.5 h-3.5 text-purple-400" />
          <span>{t.tabCopyright}</span>
        </button>

        <button
          onClick={() => setActiveTab("voice")}
          className={`py-2 px-3 rounded-xl flex items-center justify-center gap-1.5 transition-all duration-200 ${
            activeTab === "voice"
              ? "bg-gradient-to-r from-cyan-500/20 to-teal-500/15 text-cyan-200 border border-cyan-500/35 shadow-sm shadow-cyan-500/10"
              : "text-zinc-400 hover:text-zinc-200 hover:bg-white/[0.04]"
          }`}
        >
          <Mic className="w-3.5 h-3.5 text-cyan-400" />
          <span>{t.tabVoice}</span>
        </button>
      </div>

      {/* Tab 1: Watermark & Subtitle Blur Box */}
      {activeTab === "blur" && (
        <div className="p-5 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <span className="font-semibold text-zinc-200 flex items-center gap-2">
                <Shield className="w-4 h-4 text-cyan-400" />
                {t.blurTitle}
              </span>
              <p className="text-[11px] text-zinc-400 mt-0.5">
                {t.blurDesc}
              </p>
            </div>
            <label className="relative inline-flex items-center cursor-pointer">
              <input
                type="checkbox"
                checked={blurConfig.enabled}
                onChange={(e) => onBlurChange({ ...blurConfig, enabled: e.target.checked })}
                className="sr-only peer"
              />
              <div className="w-10 h-5 bg-zinc-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-gradient-to-r peer-checked:from-cyan-500 peer-checked:to-blue-600 shadow-inner"></div>
            </label>
          </div>

          {/* Quick Presets */}
          <div className="space-y-2 pt-1">
            <label className="text-[10px] text-zinc-400 font-mono uppercase tracking-wider">
              {t.presetsLabel}
            </label>
            <div className="grid grid-cols-2 gap-2">
              {presetsList.map((preset, i) => (
                <button
                  key={i}
                  onClick={() => applyPreset(preset)}
                  className="px-2.5 py-2 rounded-xl bg-zinc-900/80 hover:bg-zinc-800/90 border border-white/[0.07] hover:border-cyan-500/40 text-left text-xs text-zinc-300 hover:text-white transition-all duration-150 hover:-translate-y-0.5 active:scale-[0.98] truncate"
                  title={preset.label}
                >
                  {preset.label}
                </button>
              ))}
            </div>
          </div>

          {/* Sliders Grid */}
          <div className="space-y-3 pt-3 border-t border-white/[0.06]">
            <div className="flex items-center justify-between text-xs">
              <span className="text-zinc-400">{t.posX}</span>
              <div className="flex items-center gap-3">
                <span className="font-mono text-zinc-300 text-[11px] w-10 text-right">{Math.round(blurConfig.xPct * 100)}%</span>
                <input
                  type="range"
                  min="0"
                  max="0.9"
                  step="0.01"
                  value={blurConfig.xPct}
                  onChange={(e) => onBlurChange({ ...blurConfig, xPct: parseFloat(e.target.value) })}
                  className="w-32 accent-amber-500 cursor-pointer"
                />
              </div>
            </div>

            <div className="flex items-center justify-between text-xs">
              <span className="text-zinc-400">{t.posY}</span>
              <div className="flex items-center gap-3">
                <span className="font-mono text-zinc-300 text-[11px] w-10 text-right">{Math.round(blurConfig.yPct * 100)}%</span>
                <input
                  type="range"
                  min="0"
                  max="0.9"
                  step="0.01"
                  value={blurConfig.yPct}
                  onChange={(e) => onBlurChange({ ...blurConfig, yPct: parseFloat(e.target.value) })}
                  className="w-32 accent-amber-500 cursor-pointer"
                />
              </div>
            </div>

            <div className="flex items-center justify-between text-xs">
              <span className="text-zinc-400">{t.boxWidth}</span>
              <div className="flex items-center gap-3">
                <span className="font-mono text-zinc-300 text-[11px] w-10 text-right">{Math.round(blurConfig.wPct * 100)}%</span>
                <input
                  type="range"
                  min="0.05"
                  max="1.0"
                  step="0.01"
                  value={blurConfig.wPct}
                  onChange={(e) => onBlurChange({ ...blurConfig, wPct: parseFloat(e.target.value) })}
                  className="w-32 accent-amber-500 cursor-pointer"
                />
              </div>
            </div>

            <div className="flex items-center justify-between text-xs">
              <span className="text-zinc-400">{t.boxHeight}</span>
              <div className="flex items-center gap-3">
                <span className="font-mono text-zinc-300 text-[11px] w-10 text-right">{Math.round(blurConfig.hPct * 100)}%</span>
                <input
                  type="range"
                  min="0.02"
                  max="0.5"
                  step="0.01"
                  value={blurConfig.hPct}
                  onChange={(e) => onBlurChange({ ...blurConfig, hPct: parseFloat(e.target.value) })}
                  className="w-32 accent-amber-500 cursor-pointer"
                />
              </div>
            </div>

            <div className="flex items-center justify-between text-xs">
              <span className="text-zinc-400">{t.blurStrength}</span>
              <div className="flex items-center gap-3">
                <span className="font-mono text-amber-400 text-[11px] w-10 text-right font-semibold">{blurConfig.strength}px</span>
                <input
                  type="range"
                  min="4"
                  max="36"
                  step="2"
                  value={blurConfig.strength}
                  onChange={(e) => onBlurChange({ ...blurConfig, strength: parseInt(e.target.value) })}
                  className="w-32 accent-amber-500 cursor-pointer"
                />
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Tab 2: Anti-Copyright Bypass Tools */}
      {activeTab === "copyright" && (
        <div className="p-5 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <span className="font-semibold text-zinc-200 flex items-center gap-2">
                <Sparkles className="w-4 h-4 text-purple-400" />
                {t.copyrightTitle}
              </span>
              <p className="text-[11px] text-zinc-400 mt-0.5">
                {t.copyrightDesc}
              </p>
            </div>
            <label className="relative inline-flex items-center cursor-pointer">
              <input
                type="checkbox"
                checked={copyrightConfig.enabled}
                onChange={(e) => onCopyrightChange({ ...copyrightConfig, enabled: e.target.checked })}
                className="sr-only peer"
              />
              <div className="w-10 h-5 bg-zinc-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-gradient-to-r peer-checked:from-purple-500 peer-checked:to-indigo-500 shadow-inner"></div>
            </label>
          </div>

          <div className="space-y-3 pt-1">
            {/* Mirror Flip */}
            <label className="flex items-center justify-between p-3 rounded-xl bg-zinc-900/80 border border-white/[0.07] cursor-pointer hover:border-purple-500/40 transition-all duration-150">
              <span className="text-xs text-zinc-300 flex items-center gap-2">
                <FlipHorizontal className="w-4 h-4 text-purple-400" />
                {t.hflip}
              </span>
              <input
                type="checkbox"
                checked={copyrightConfig.hflip}
                onChange={(e) => onCopyrightChange({ ...copyrightConfig, hflip: e.target.checked })}
                className="w-4 h-4 accent-purple-500 rounded cursor-pointer"
              />
            </label>

            {/* Zoom Crop */}
            <div className="flex items-center justify-between text-xs p-2">
              <span className="text-zinc-400">{t.zoomCrop} ({Math.round(copyrightConfig.zoomCropPct * 100)}%)</span>
              <input
                type="range"
                min="0"
                max="0.25"
                step="0.01"
                value={copyrightConfig.zoomCropPct}
                onChange={(e) => onCopyrightChange({ ...copyrightConfig, zoomCropPct: parseFloat(e.target.value) })}
                className="w-32 accent-purple-500 cursor-pointer"
              />
            </div>

            {/* Brightness */}
            <div className="flex items-center justify-between text-xs p-2">
              <span className="text-zinc-400">{t.brightness} ({Math.round(copyrightConfig.brightness * 100)}%)</span>
              <input
                type="range"
                min="-0.2"
                max="0.2"
                step="0.02"
                value={copyrightConfig.brightness}
                onChange={(e) => onCopyrightChange({ ...copyrightConfig, brightness: parseFloat(e.target.value) })}
                className="w-32 accent-purple-500 cursor-pointer"
              />
            </div>

            {/* Contrast */}
            <div className="flex items-center justify-between text-xs p-2">
              <span className="text-zinc-400">{t.contrast} ({Math.round(copyrightConfig.contrast * 100)}%)</span>
              <input
                type="range"
                min="0.8"
                max="1.3"
                step="0.05"
                value={copyrightConfig.contrast}
                onChange={(e) => onCopyrightChange({ ...copyrightConfig, contrast: parseFloat(e.target.value) })}
                className="w-32 accent-purple-500 cursor-pointer"
              />
            </div>

            {/* Saturation */}
            <div className="flex items-center justify-between text-xs p-2">
              <span className="text-zinc-400">{t.saturation} ({Math.round(copyrightConfig.saturation * 100)}%)</span>
              <input
                type="range"
                min="0.8"
                max="1.5"
                step="0.05"
                value={copyrightConfig.saturation}
                onChange={(e) => onCopyrightChange({ ...copyrightConfig, saturation: parseFloat(e.target.value) })}
                className="w-32 accent-purple-500 cursor-pointer"
              />
            </div>

            {/* Border */}
            <div className="flex items-center justify-between text-xs p-2 pt-2 border-t border-white/[0.06]">
              <span className="text-zinc-400">{t.border} ({copyrightConfig.borderThickness}px)</span>
              <div className="flex items-center gap-2">
                <input
                  type="color"
                  value={copyrightConfig.borderColorHex}
                  onChange={(e) => onCopyrightChange({ ...copyrightConfig, borderColorHex: e.target.value })}
                  className="w-6 h-6 rounded bg-transparent cursor-pointer border border-white/[0.1]"
                  title="Border Color"
                />
                <input
                  type="range"
                  min="0"
                  max="20"
                  step="2"
                  value={copyrightConfig.borderThickness}
                  onChange={(e) => onCopyrightChange({ ...copyrightConfig, borderThickness: parseInt(e.target.value) })}
                  className="w-24 accent-purple-500 cursor-pointer"
                />
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Tab 3: Narration Voice Profiles */}
      {activeTab === "voice" && (
        <div className="p-5 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <span className="font-semibold text-zinc-200 flex items-center gap-2">
                <Mic className="w-4 h-4 text-cyan-400" />
                {t.voiceTitle}
              </span>
              <p className="text-[11px] text-zinc-400 mt-0.5">
                {t.voiceDesc}
              </p>
            </div>
            <button
              onClick={handleAudition}
              disabled={isAuditioning}
              className="px-3 py-1.5 rounded-xl bg-cyan-500/15 hover:bg-cyan-500/25 text-cyan-200 border border-cyan-500/35 text-xs font-semibold flex items-center gap-1.5 transition-all duration-150 hover:-translate-y-0.5 active:scale-95 shadow-sm shadow-cyan-500/10"
            >
              <Volume2 className="w-3.5 h-3.5" />
              <span>{isAuditioning ? t.auditioning : t.audition}</span>
            </button>
          </div>

          {/* Voice Cards */}
          <div className="space-y-2 pt-1">
            {VOICE_PROFILES.map((profile) => (
              <div
                key={profile.id}
                onClick={() => onVoiceChange(profile)}
                className={`p-3 rounded-xl border cursor-pointer transition-all duration-150 flex items-center justify-between ${
                  selectedVoice.id === profile.id
                    ? "bg-gradient-to-r from-cyan-500/15 to-teal-500/10 border-cyan-500/45 text-white shadow-sm shadow-cyan-500/10"
                    : "bg-zinc-900/60 border-white/[0.06] hover:bg-zinc-900 hover:border-white/[0.14] text-zinc-300"
                }`}
              >
                <div>
                  <div className="font-semibold text-xs flex items-center gap-2">
                    <span>{profile.name}</span>
                    <span className="text-[9px] px-1.5 py-0.5 rounded font-mono uppercase bg-zinc-800/90 text-cyan-300 border border-cyan-500/20">
                      {profile.engine}
                    </span>
                  </div>
                  <p className="text-[11px] text-zinc-400 mt-0.5 leading-relaxed">{profile.subtitle}</p>
                </div>
                {selectedVoice.id === profile.id && (
                  <div className="w-5 h-5 rounded-full bg-cyan-500/20 border border-cyan-500/50 flex items-center justify-center">
                    <Check className="w-3.5 h-3.5 text-cyan-300" />
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
