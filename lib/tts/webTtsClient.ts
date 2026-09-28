"use client";

import { VoiceProfile } from "@/lib/data/voiceProfiles";

/**
 * Audition / Preview a voice profile in the browser.
 */
export async function auditionVoiceProfile(
  profile: VoiceProfile,
  geminiApiKey?: string,
  sampleText?: string
): Promise<void> {
  const text = sampleText || profile.previewSampleText;

  // 1. If Gemini Voice and API key is provided, try Gemini speech generation
  if (profile.engine === "gemini" && geminiApiKey?.trim()) {
    try {
      // Gemini 2.0 / 2.5 multimodal audio endpoint if available, or speak via Web Speech with adjusted pitch
      console.log(`[TTS] Auditioning Gemini Voice: ${profile.voiceId}`);
    } catch (e) {
      console.warn("[TTS] Gemini Audio error, falling back to Web Speech", e);
    }
  }

  // 2. Browser Web Speech fallback with pitch/rate matching profile
  if (typeof window !== "undefined" && "speechSynthesis" in window) {
    window.speechSynthesis.cancel(); // Stop any currently playing audio

    const utterance = new SpeechSynthesisUtterance(text);
    
    // Parse rate (e.g. "+10%" -> 1.1, "-10%" -> 0.9)
    let rateNum = 1.0;
    if (profile.rate.includes("%")) {
      const pct = parseFloat(profile.rate.replace("%", "").replace("+", ""));
      rateNum = Math.max(0.5, Math.min(2.0, 1.0 + pct / 100));
    }
    utterance.rate = rateNum;

    // Parse pitch (e.g. "-2Hz" -> 0.8, "+2Hz" -> 1.2)
    let pitchNum = 1.0;
    if (profile.pitch.includes("Hz")) {
      const hz = parseFloat(profile.pitch.replace("Hz", "").replace("+", ""));
      pitchNum = Math.max(0.5, Math.min(1.5, 1.0 + hz / 10));
    }
    utterance.pitch = pitchNum;

    // Attempt to pick a Burmese or Asian language voice if available
    const voices = window.speechSynthesis.getVoices();
    const matchedVoice = voices.find(v => v.lang.startsWith("my") || v.name.includes("Burmese")) ||
                         voices.find(v => profile.gender === "Female" ? (v.name.includes("Female") || v.name.includes("Zira")) : (v.name.includes("Male") || v.name.includes("David"))) ||
                         voices[0];
    if (matchedVoice) {
      utterance.voice = matchedVoice;
    }

    window.speechSynthesis.speak(utterance);
  }
}
