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

  // Allow WebGPU device detection
  const hasWebGPU = typeof navigator !== "undefined" && "gpu" in navigator;
  const device = hasWebGPU ? "webgpu" : "wasm";

  console.log(`[Whisper Web] Initializing Whisper pipeline with device: ${device}`);

  transcriber = await pipeline(
    "automatic-speech-recognition",
    "onnx-community/whisper-tiny",
    {
      device: device as any,
      dtype: device === "webgpu" ? "fp32" : "q8",
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
