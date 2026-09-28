import { NextRequest, NextResponse } from "next/server";
import WebSocket from "ws";
import crypto from "crypto";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

const TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
const CHROMIUM_FULL_VERSION = "143.0.3650.75";
const CHROMIUM_MAJOR_VERSION = "143";
const SEC_MS_GEC_VERSION = `1-${CHROMIUM_FULL_VERSION}`;
const WIN_EPOCH = 11644473600n;
const S_TO_NS = 1000000000n;

function generateSecMsGec(): string {
  const unixNow = BigInt(Math.floor(Date.now() / 1000));
  let ticks = unixNow + WIN_EPOCH;
  ticks -= ticks % 300n;
  const fileTimeTicks = ticks * (S_TO_NS / 100n);
  const strToHash = `${fileTimeTicks}${TRUSTED_CLIENT_TOKEN}`;
  return crypto.createHash("sha256").update(strToHash, "ascii").digest("hex").toUpperCase();
}

function synthesizeEdgeChunk(text: string, voiceName: string, rate: string, pitch: string): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const connectionId = crypto.randomUUID().replace(/-/g, "");
    const requestId = crypto.randomUUID().replace(/-/g, "");
    const secMsGec = generateSecMsGec();

    const timestamp = new Date().toUTCString();
    const wsUrl = `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1` +
      `?TrustedClientToken=${TRUSTED_CLIENT_TOKEN}` +
      `&ConnectionId=${connectionId}` +
      `&Sec-MS-GEC=${secMsGec}` +
      `&Sec-MS-GEC-Version=${SEC_MS_GEC_VERSION}`;

    const ws = new WebSocket(wsUrl, {
      headers: {
        "User-Agent": `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROMIUM_MAJOR_VERSION}.0.0.0 Safari/537.36 Edg/${CHROMIUM_MAJOR_VERSION}.0.0.0`,
        "Origin": "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
        "Pragma": "no-cache",
        "Cache-Control": "no-cache",
      },
    });

    const audioChunks: Buffer[] = [];
    let isTurnComplete = false;

    const timeout = setTimeout(() => {
      ws.close();
      if (audioChunks.length > 0) {
        resolve(Buffer.concat(audioChunks));
      } else {
        reject(new Error("Edge TTS request timed out."));
      }
    }, 25000);

    ws.on("open", () => {
      // 1. Send speech.config
      const configMsg = `X-Timestamp:${timestamp}\r\n` +
        `Content-Type:application/json; charset=utf-8\r\n` +
        `Path:speech.config\r\n\r\n` +
        `{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},` +
        `"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}`;
      ws.send(configMsg);

      // 2. Send SSML request
      const cleanText = text
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;");

      const ssml = `<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='my-MM'>` +
        `<voice name='${voiceName}'>` +
        `<prosody pitch='${pitch}' rate='${rate}'>${cleanText}</prosody>` +
        `</voice></speak>`;

      const ssmlMsg = `X-RequestId:${requestId}\r\n` +
        `Content-Type:application/ssml+xml\r\n` +
        `X-Timestamp:${timestamp}Z\r\n` +
        `Path:ssml\r\n\r\n` +
        ssml;

      ws.send(ssmlMsg);
    });

    ws.on("message", (data: WebSocket.Data, isBinary: boolean) => {
      if (isBinary) {
        const buf = Buffer.from(data as Buffer);
        // Binary message contains 2-byte header length, followed by headers, followed by raw MP3 audio
        if (buf.length >= 2) {
          const headerLen = buf.readUInt16BE(0);
          if (buf.length > headerLen + 2) {
            const audioData = buf.subarray(headerLen + 2);
            audioChunks.push(audioData);
          }
        }
      } else {
        const textMsg = data.toString();
        if (textMsg.includes("Path:turn.end")) {
          isTurnComplete = true;
          ws.close();
        }
      }
    });

    ws.on("close", () => {
      clearTimeout(timeout);
      if (audioChunks.length > 0) {
        resolve(Buffer.concat(audioChunks));
      } else if (!isTurnComplete) {
        reject(new Error("Edge TTS connection closed without audio."));
      }
    });

    ws.on("error", (err) => {
      clearTimeout(timeout);
      reject(err);
    });
  });
}

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const { text, voiceName = "my-MM-ThihaNeural", rate = "+0%", pitch = "+0Hz" } = body;

    if (!text || !text.trim()) {
      return NextResponse.json({ error: "Text is required for TTS synthesis." }, { status: 400 });
    }

    // Split text into chunks if it exceeds 1000 characters
    const sentences = text.split(/(?<=[။\n.!?])\s*/).filter((s: string) => s.trim().length > 0);
    const chunks: string[] = [];
    let currentChunk = "";

    for (const sentence of sentences) {
      if ((currentChunk + sentence).length > 800) {
        if (currentChunk) chunks.push(currentChunk.trim());
        currentChunk = sentence;
      } else {
        currentChunk += (currentChunk ? " " : "") + sentence;
      }
    }
    if (currentChunk) chunks.push(currentChunk.trim());
    if (chunks.length === 0) chunks.push(text.trim());

    const audioBuffers: Buffer[] = [];
    for (const chunk of chunks) {
      const audio = await synthesizeEdgeChunk(chunk, voiceName, rate, pitch);
      audioBuffers.push(audio);
    }

    const finalBuffer = Buffer.concat(audioBuffers);

    return new Response(finalBuffer, {
      status: 200,
      headers: {
        "Content-Type": "audio/mpeg",
        "Content-Length": finalBuffer.length.toString(),
      },
    });
  } catch (err: any) {
    console.error("[TTS API Error]", err);
    return NextResponse.json({ error: err.message || "TTS synthesis failed." }, { status: 500 });
  }
}
