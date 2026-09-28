import { FFmpeg } from "@ffmpeg/ffmpeg";
import { fetchFile, toBlobURL } from "@ffmpeg/util";

let ffmpegInstance: FFmpeg | null = null;
let isLoaded = false;
const ffmpegLogHistory: string[] = [];
let activeLogCallback: ((msg: string) => void) | null = null;

export async function getFFmpeg(onLog?: (msg: string) => void): Promise<FFmpeg> {
  if (onLog) {
    activeLogCallback = onLog;
  }
  if (ffmpegInstance && isLoaded) {
    return ffmpegInstance;
  }

  const ffmpeg = new FFmpeg();
  ffmpeg.on("log", ({ message }) => {
    ffmpegLogHistory.push(message);
    if (ffmpegLogHistory.length > 100) ffmpegLogHistory.shift();
    console.log("[FFmpeg]", message);
    if (activeLogCallback) activeLogCallback(message);
  });

  // Load singlethreaded FFmpeg WASM core from unpkg
  const baseURL = "https://unpkg.com/@ffmpeg/core@0.12.6/dist/umd";
  await ffmpeg.load({
    coreURL: await toBlobURL(`${baseURL}/ffmpeg-core.js`, "text/javascript"),
    wasmURL: await toBlobURL(`${baseURL}/ffmpeg-core.wasm`, "application/wasm"),
  });

  ffmpegInstance = ffmpeg;
  isLoaded = true;
  return ffmpeg;
}

/**
 * Accurately extracts video metadata (width, height, and duration in seconds) in the browser.
 */
export function getVideoMetadata(file: File | Blob): Promise<{ width: number; height: number; duration: number }> {
  return new Promise((resolve) => {
    if (typeof window === "undefined") {
      resolve({ width: 1280, height: 720, duration: 60 });
      return;
    }
    const video = document.createElement("video");
    video.preload = "metadata";
    video.muted = true;
    (video as any).playsInline = true;
    const url = URL.createObjectURL(file);
    video.src = url;

    let finished = false;
    const finish = (w?: number, h?: number, dur?: number) => {
      if (!finished) {
        finished = true;
        try {
          URL.revokeObjectURL(url);
          video.src = "";
          video.load();
        } catch {}
        const validW = w && !isNaN(w) && w > 0 ? Math.round(w) : 1280;
        const validH = h && !isNaN(h) && h > 0 ? Math.round(h) : 720;
        const validDur = dur && !isNaN(dur) && isFinite(dur) && dur > 0 ? dur : 60;
        resolve({
          width: validW % 2 === 0 ? validW : validW - 1,
          height: validH % 2 === 0 ? validH : validH - 1,
          duration: validDur,
        });
      }
    };

    video.onloadedmetadata = () => {
      finish(video.videoWidth, video.videoHeight, video.duration);
    };

    video.onerror = () => {
      finish(1280, 720, 60);
    };

    // Safety timeout in case metadata event stalls in background worker
    setTimeout(() => {
      finish(video.videoWidth || 1280, video.videoHeight || 720, video.duration || 60);
    }, 2000);
  });
}

export async function getVideoDimensions(file: File | Blob): Promise<{ width: number; height: number }> {
  const meta = await getVideoMetadata(file);
  return { width: meta.width, height: meta.height };
}

/**
 * Builds a robust FFmpeg filtergraph mirroring Android FFmpegEngine.kt:
 * - Anti-Copyright: hflip, zoomCrop with even pixels, color EQ, border drawbox
 * - Blur Box: crop + avgblur + overlay using exact integer coordinates (replaces faulty delogo)
 */
export function buildVideoFilterGraph(
  vidW: number,
  vidH: number,
  blurConfig?: { enabled: boolean; xPct: number; yPct: number; wPct: number; hPct: number; strength: number },
  copyrightConfig?: { enabled: boolean; hflip: boolean; zoomCropPct: number; brightness: number; contrast: number; saturation: number; borderThickness: number; borderColorHex: string }
): { filterGraph: string; outputTag: string } | null {
  const parts: string[] = [];
  let currentV = "0:v";

  // 1. Copyright Bypass filters
  if (copyrightConfig?.enabled) {
    const preFilters: string[] = [];
    if (copyrightConfig.hflip) {
      preFilters.push("hflip");
    }
    if (copyrightConfig.zoomCropPct > 0) {
      const z = 1 + Math.min(Math.max(copyrightConfig.zoomCropPct, 0.01), 0.35);
      let cw = Math.round(vidW / z);
      let ch = Math.round(vidH / z);
      if (cw % 2 !== 0) cw -= 1;
      if (ch % 2 !== 0) ch -= 1;
      cw = Math.max(cw, 16);
      ch = Math.max(ch, 16);
      const cx = Math.max(Math.round((vidW - cw) / 2), 0);
      const cy = Math.max(Math.round((vidH - ch) / 2), 0);
      preFilters.push(`crop=w=${cw}:h=${ch}:x=${cx}:y=${cy},scale=${vidW}:${vidH}`);
    }
    const b = Number(copyrightConfig.brightness.toFixed(3));
    const c = Number(copyrightConfig.contrast.toFixed(3));
    const s = Number(copyrightConfig.saturation.toFixed(3));
    if (Math.abs(b) > 0.005 || Math.abs(c - 1) > 0.01 || Math.abs(s - 1) > 0.01) {
      preFilters.push(`eq=brightness=${b}:contrast=${c}:saturation=${s}`);
    }
    if (copyrightConfig.borderThickness > 0) {
      const hex = copyrightConfig.borderColorHex.replace("#", "") || "3b82f6";
      const t = Math.min(Math.max(copyrightConfig.borderThickness, 1), 30);
      preFilters.push(`drawbox=x=0:y=0:w=${vidW}:h=${vidH}:color=0x${hex}:t=${t}`);
    }

    if (preFilters.length > 0) {
      parts.push(`[${currentV}]${preFilters.join(",")}[v_cr]`);
      currentV = "v_cr";
    }
  }

  // 2. Watermark / Subtitle Blur Box (avgblur with integer pixel coordinates)
  if (blurConfig?.enabled) {
    const xPct = Math.min(Math.max(blurConfig.xPct > 1 ? blurConfig.xPct / 100 : blurConfig.xPct, 0), 1);
    const yPct = Math.min(Math.max(blurConfig.yPct > 1 ? blurConfig.yPct / 100 : blurConfig.yPct, 0), 1);
    const wPct = Math.min(Math.max(blurConfig.wPct > 1 ? blurConfig.wPct / 100 : blurConfig.wPct, 0.01), 1);
    const hPct = Math.min(Math.max(blurConfig.hPct > 1 ? blurConfig.hPct / 100 : blurConfig.hPct, 0.01), 1);

    let bw = Math.min(Math.max(Math.round(wPct * vidW), 4), vidW);
    let bh = Math.min(Math.max(Math.round(hPct * vidH), 4), vidH);
    if (bw % 2 !== 0) bw -= 1;
    if (bh % 2 !== 0) bh -= 1;
    bw = Math.max(bw, 4);
    bh = Math.max(bh, 4);

    const maxX = Math.max(vidW - bw, 0);
    const maxY = Math.max(vidH - bh, 0);
    const bx = Math.min(Math.max(Math.round(xPct * vidW), 0), maxX);
    const by = Math.min(Math.max(Math.round(yPct * vidH), 0), maxY);

    const str = Math.min(Math.max(Math.round(blurConfig.strength || 15), 3), 40);

    parts.push(
      `[${currentV}]split=2[v_base][v_crop];` +
      `[v_crop]crop=w=${bw}:h=${bh}:x=${bx}:y=${by},avgblur=sizeX=${str}:sizeY=${str}[v_blurred];` +
      `[v_base][v_blurred]overlay=x=${bx}:y=${by}[v_blur]`
    );
    currentV = "v_blur";
  }

  if (parts.length === 0) {
    return null;
  }

  return {
    filterGraph: parts.join(";"),
    outputTag: currentV,
  };
}

/**
 * Extracts 16kHz Mono 16-bit PCM WAV from any video file in the browser.
 * Perfectly formatted for Whisper WebGPU input.
 */
export async function extractAudioFromVideo(
  videoFile: File,
  onProgress?: (progress: number) => void,
  onLog?: (msg: string) => void
): Promise<Blob> {
  const ffmpeg = await getFFmpeg(onLog);

  if (onProgress) {
    ffmpeg.on("progress", ({ progress }) => {
      onProgress(Math.round(progress * 100));
    });
  }

  const inputName = `input_${Date.now()}.mp4`;
  const outputName = `audio_${Date.now()}.wav`;

  await ffmpeg.writeFile(inputName, await fetchFile(videoFile));

  // Extract 16000Hz, 1 channel (mono), 16-bit PCM WAV
  const code = await ffmpeg.exec([
    "-i", inputName,
    "-vn",
    "-ar", "16000",
    "-ac", "1",
    "-c:a", "pcm_s16le",
    outputName,
  ]);

  if (code !== 0) {
    console.error("[FFmpeg Audio Extract Error]", ffmpegLogHistory.slice(-10).join("\n"));
  }

  const data = await ffmpeg.readFile(outputName);
  await ffmpeg.deleteFile(inputName);
  await ffmpeg.deleteFile(outputName);

  const uint8 = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  return new Blob([uint8.buffer as ArrayBuffer], { type: "audio/wav" });
}

/**
 * Renders final dubbed video: merges video with narration audio and applies active filters.
 */
export async function renderDubbedRecapVideo(
  videoFile: File,
  narrationAudioBlob: Blob,
  blurConfig?: { enabled: boolean; xPct: number; yPct: number; wPct: number; hPct: number; strength: number },
  copyrightConfig?: { enabled: boolean; hflip: boolean; zoomCropPct: number; brightness: number; contrast: number; saturation: number; borderThickness: number; borderColorHex: string },
  onProgress?: (progress: number) => void,
  onLog?: (msg: string) => void
): Promise<Blob> {
  const ffmpeg = await getFFmpeg(onLog);

  if (onProgress) {
    ffmpeg.on("progress", ({ progress }) => {
      onProgress(Math.round(progress * 100));
    });
  }

  if (!narrationAudioBlob || narrationAudioBlob.size < 50) {
    throw new Error(`Narration audio track is empty (${narrationAudioBlob?.size || 0} bytes). Check TTS connection.`);
  }

  const dims = await getVideoDimensions(videoFile);
  console.log(`[FFmpeg Dubbing] Video dimensions: ${dims.width}x${dims.height}`);

  const filterGraphResult = buildVideoFilterGraph(dims.width, dims.height, blurConfig, copyrightConfig);

  const isWav = narrationAudioBlob.type.includes("wav");
  const videoInputName = `v_in_${Date.now()}.mp4`;
  const audioInputName = `a_in_${Date.now()}.${isWav ? "wav" : "mp3"}`;
  const outputName = `dubbed_out_${Date.now()}.mp4`;

  console.log(`[FFmpeg Dubbing] Writing input files (Video: ${videoFile.size}B, Audio: ${narrationAudioBlob.size}B)...`);
  await ffmpeg.writeFile(videoInputName, await fetchFile(videoFile));
  await ffmpeg.writeFile(audioInputName, await fetchFile(narrationAudioBlob));

  const args: string[] = [
    "-i", videoInputName,
    "-i", audioInputName,
  ];

  if (filterGraphResult) {
    args.push(
      "-filter_complex", filterGraphResult.filterGraph,
      "-map", `[${filterGraphResult.outputTag}]`,
      "-map", "1:a:0",
      "-c:v", "libx264",
      "-preset", "ultrafast",
      "-crf", "26"
    );
  } else {
    args.push(
      "-map", "0:v:0",
      "-map", "1:a:0",
      "-c:v", "libx264",
      "-preset", "ultrafast",
      "-crf", "28"
    );
  }

  args.push(
    "-c:a", "aac",
    "-b:a", "128k",
    "-movflags", "+faststart",
    outputName
  );

  console.log("[FFmpeg Dubbing] Executing:", args.join(" "));
  const exitCode = await ffmpeg.exec(args);

  if (exitCode !== 0) {
    const errorSnippet = ffmpegLogHistory.slice(-15).join("\n");
    console.error("[FFmpeg Dubbing Failed]", errorSnippet);
    throw new Error(`FFmpeg exited with error code ${exitCode}. Details:\n${errorSnippet}`);
  }

  const data = await ffmpeg.readFile(outputName);
  await ffmpeg.deleteFile(videoInputName);
  await ffmpeg.deleteFile(audioInputName);
  await ffmpeg.deleteFile(outputName);

  if (!data || data.length === 0) {
    const errorSnippet = ffmpegLogHistory.slice(-15).join("\n");
    throw new Error(`FFmpeg completed but generated a 0-byte video. Logs:\n${errorSnippet}`);
  }

  console.log(`[FFmpeg Dubbing] Successfully rendered dubbed video: ${data.length} bytes`);
  const uint8 = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  return new Blob([uint8.buffer as ArrayBuffer], { type: "video/mp4" });
}

export async function renderProcessedVideo(
  videoFile: File,
  blurConfig?: { enabled: boolean; xPct: number; yPct: number; wPct: number; hPct: number; strength: number },
  copyrightConfig?: { enabled: boolean; hflip: boolean; zoomCropPct: number; brightness: number; contrast: number; saturation: number; borderThickness: number; borderColorHex: string },
  onProgress?: (progress: number) => void,
  onLog?: (msg: string) => void
): Promise<Blob> {
  const ffmpeg = await getFFmpeg(onLog);

  if (onProgress) {
    ffmpeg.on("progress", ({ progress }) => {
      onProgress(Math.round(progress * 100));
    });
  }

  const dims = await getVideoDimensions(videoFile);
  const filterGraphResult = buildVideoFilterGraph(dims.width, dims.height, blurConfig, copyrightConfig);

  const inputName = `render_in_${Date.now()}.mp4`;
  const outputName = `render_out_${Date.now()}.mp4`;

  await ffmpeg.writeFile(inputName, await fetchFile(videoFile));

  const args: string[] = ["-i", inputName];

  if (filterGraphResult) {
    args.push(
      "-filter_complex", filterGraphResult.filterGraph,
      "-map", `[${filterGraphResult.outputTag}]`,
      "-map", "0:a?",
      "-c:v", "libx264",
      "-preset", "ultrafast",
      "-crf", "26",
      "-c:a", "copy",
      "-movflags", "+faststart",
      outputName
    );
  } else {
    args.push(
      "-c:v", "copy",
      "-c:a", "copy",
      "-movflags", "+faststart",
      outputName
    );
  }

  console.log("[FFmpeg Process Video] Executing:", args.join(" "));
  const exitCode = await ffmpeg.exec(args);

  if (exitCode !== 0) {
    const errorSnippet = ffmpegLogHistory.slice(-15).join("\n");
    throw new Error(`FFmpeg process video failed (${exitCode}):\n${errorSnippet}`);
  }

  const data = await ffmpeg.readFile(outputName);
  await ffmpeg.deleteFile(inputName);
  await ffmpeg.deleteFile(outputName);

  const uint8 = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  return new Blob([uint8.buffer as ArrayBuffer], { type: "video/mp4" });
}
