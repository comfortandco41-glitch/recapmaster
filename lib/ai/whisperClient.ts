"use client";

export interface WhisperSegment {
  text: string;
  timestamp: [number, number | null];
}

export interface WhisperOutput {
  text: string;
  chunks: WhisperSegment[];
}

let transcriber: any = null;

/**
 * Loads the Whisper ONNX model in the browser via WebGPU with WASM fallback.
 */
export async function getWhisperPipeline(onProgress?: (info: any) => void) {
  if (transcriber) return transcriber;

  // Dynamically import transformers in browser to avoid bundler wasm/ts resolution errors
  const importModule = new Function("url", "return import(url)");
  const transformers = await importModule(
    "https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.3.3"
  );
  const pipeline = transformers.pipeline;

  // Check if WebGPU adapter is genuinely available on this device
  let canUseWebGPU = false;
  if (typeof navigator !== "undefined" && "gpu" in navigator) {
    try {
      const adapter = await (navigator as any).gpu.requestAdapter();
      if (adapter) {
        canUseWebGPU = true;
      }
    } catch {
      canUseWebGPU = false;
    }
  }

  // 1. Try WebGPU if adapter is available
  if (canUseWebGPU) {
    try {
      console.log("[Whisper Web] Attempting WebGPU initialization...");
      transcriber = await pipeline(
        "automatic-speech-recognition",
        "onnx-community/whisper-tiny",
        {
          device: "webgpu",
          dtype: "fp32",
          progress_callback: onProgress,
        }
      );
      return transcriber;
    } catch (gpuErr: any) {
      console.warn("[Whisper Web] WebGPU failed, falling back to WASM:", gpuErr?.message || gpuErr);
    }
  }

  // 2. Safe WASM Fallback (100% compatible with Mobile Chrome, Android, iPhone Safari)
  console.log("[Whisper Web] Initializing Whisper with CPU WASM fallback...");
  transcriber = await pipeline(
    "automatic-speech-recognition",
    "onnx-community/whisper-tiny",
    {
      device: "wasm",
      dtype: "q8",
      progress_callback: onProgress,
    }
  );

  return transcriber;
}

/**
 * Converts audio Blob to Float32Array at 16kHz for Whisper
 */
async function decodeAudioBlob(audioBlob: Blob): Promise<Float32Array> {
  const audioContext = new (window.AudioContext || (window as any).webkitAudioContext)({
    sampleRate: 16000,
  });
  const arrayBuffer = await audioBlob.arrayBuffer();
  const audioBuffer = await audioContext.decodeAudioData(arrayBuffer);
  return audioBuffer.getChannelData(0);
}

/**
 * Transcribes audio blob directly inside user's browser using Whisper WebGPU
 */
export async function transcribeAudio(
  audioBlob: Blob,
  language: string = "english",
  onProgress?: (info: any) => void
): Promise<WhisperOutput> {
  const pipeline = await getWhisperPipeline(onProgress);
  const audioData = await decodeAudioBlob(audioBlob);

  const result = await pipeline(audioData, {
    chunk_length_s: 30,
    stride_length_s: 5,
    return_timestamps: true,
    language: language === "auto" ? null : language,
    task: "transcribe",
  });

  return {
    text: result.text || "",
    chunks: (result.chunks || []).map((chunk: any) => ({
      text: chunk.text,
      timestamp: chunk.timestamp,
    })),
  };
}
