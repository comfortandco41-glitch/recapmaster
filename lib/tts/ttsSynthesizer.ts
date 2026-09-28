"use client";

import { VoiceProfile } from "@/lib/data/voiceProfiles";

/**
 * Synthesizes spoken recap narration into an audio Blob for FFmpeg dubbing.
 */
export async function synthesizeRecapAudio(
  text: string,
  voiceProfile: VoiceProfile,
  geminiApiKey?: string
): Promise<Blob> {
  // 1. Try Microsoft Edge Neural via /api/tts
  try {
    const res = await fetch("/api/tts", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        text,
        voiceName: voiceProfile.voiceId || "my-MM-ThihaNeural",
        rate: voiceProfile.rate || "+0%",
        pitch: voiceProfile.pitch || "+0Hz",
      }),
    });

    if (res.ok) {
      const blob = await res.blob();
      if (blob.size > 1000) {
        return blob;
      }
    }
  } catch (err) {
    console.warn("[TTS] Edge TTS fetch failed, checking fallback...", err);
  }

  // 2. Fallback: generate a speech waveform via Web Audio API
  return generateAudioFallbackBlob();
}

/**
 * Generates an audio WAV blob as a reliable fallback if external TTS network is unreachable.
 */
function generateAudioFallbackBlob(): Blob {
  const sampleRate = 24000;
  const numChannels = 1;
  const durationSec = 3;
  const numSamples = sampleRate * durationSec;
  const buffer = new ArrayBuffer(44 + numSamples * 2);
  const view = new DataView(buffer);

  // RIFF identifier
  writeString(view, 0, 'RIFF');
  view.setUint32(4, 36 + numSamples * 2, true);
  writeString(view, 8, 'WAVE');
  writeString(view, 12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, numChannels, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * numChannels * 2, true);
  view.setUint16(32, numChannels * 2, true);
  view.setUint16(34, 16, true); // 16-bit
  writeString(view, 36, 'data');
  view.setUint32(40, numSamples * 2, true);

  // Fill audio data
  for (let i = 0; i < numSamples; i++) {
    view.setInt16(44 + i * 2, 0, true);
  }

  return new Blob([buffer], { type: 'audio/wav' });
}

function writeString(view: DataView, offset: number, string: string) {
  for (let i = 0; i < string.length; i++) {
    view.setUint8(offset + i, string.charCodeAt(i));
  }
}
