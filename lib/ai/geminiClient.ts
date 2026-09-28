"use client";

// Candidate Gemini flagship Flash models in priority order
export const LATEST_GEMINI_MODELS = [
  "gemini-2.5-flash",     // Google's flagship multimodal Flash
  "gemini-2.0-flash",     // Fast, stable, high token capacity
  "gemini-1.5-flash",     // Universally active fallback
  "gemini-3.7-flash",
  "gemini-3.8-flash",
];

let cachedWorkingModel: string | null = null;

export interface GenerateRecapOptions {
  transcript: string;
  videoDurationSeconds?: number;
  videoTitle?: string;
  targetLanguage?: string; // "my" (Burmese), "en" (English), "zh" (Chinese), etc.
  apiKey: string;
  tone?: "dramatic" | "humorous" | "informative";
  preferredModel?: string;
}

/**
 * Executes a Gemini request by dynamically attempting the latest models
 * (gemini-2.5-flash -> gemini-2.0-flash -> gemini-1.5-flash).
 * Direct browser fetch with zero server compute and zero hosting cost.
 */
export async function callGeminiApi(
  prompt: string, 
  apiKey: string, 
  preferredModel?: string
): Promise<string> {
  const cleanKey = apiKey.trim();
  if (!cleanKey) {
    throw new Error("Gemini API key is required. Please add your key in Settings.");
  }

  // Build candidate model list
  const candidates: string[] = [];
  if (preferredModel?.trim()) {
    candidates.push(preferredModel.trim());
  }
  if (cachedWorkingModel && !candidates.includes(cachedWorkingModel)) {
    candidates.push(cachedWorkingModel);
  }
  for (const m of LATEST_GEMINI_MODELS) {
    if (!candidates.includes(m)) {
      candidates.push(m);
    }
  }

  let lastError: string = "";

  for (const model of candidates) {
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${cleanKey}`;
    const payload = {
      contents: [
        {
          parts: [{ text: prompt }],
        },
      ],
      generationConfig: {
        temperature: 0.4,
        maxOutputTokens: 8192, // Full 8192 tokens prevents mid-sentence truncation
      },
    };

    try {
      const response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
      });

      if (response.ok) {
        const data = await response.json();
        const candidate = data?.candidates?.[0];
        const candidateText = candidate?.content?.parts?.[0]?.text;
        if (candidateText) {
          cachedWorkingModel = model; // Cache the verified active model
          console.log(`[Gemini Web] Successfully executed with model: ${model} (finishReason: ${candidate?.finishReason})`);
          return candidateText.trim();
        }
      }

      // If 404 (model not found / deprecated) or 400, try next candidate
      const errorText = await response.text();
      let errorJson;
      try {
        errorJson = JSON.parse(errorText);
      } catch {}
      lastError = errorJson?.error?.message || `HTTP ${response.status}: ${errorText.slice(0, 150)}`;
      console.warn(`[Gemini Web] Model ${model} failed (${response.status}), falling back to next model...`);
    } catch (err: any) {
      lastError = err?.message || String(err);
      console.warn(`[Gemini Web] Network attempt for ${model} failed, trying next...`);
    }
  }

  throw new Error(`Gemini API failed across available models. Last error: ${lastError}`);
}

/**
 * Generates an engaging movie/video recap narration script that covers the
 * ENTIRE video duration from the first scene to the final ending.
 * Mirrors the architecture of Android RecapMaster (GeminiClient.kt).
 */
export async function generateRecapScript({
  transcript,
  videoDurationSeconds = 60,
  videoTitle = "",
  targetLanguage = "my",
  apiKey,
  tone = "dramatic",
  preferredModel,
}: GenerateRecapOptions): Promise<string> {
  const durationSec = videoDurationSeconds > 0 ? Math.round(videoDurationSeconds) : 60;
  // Natural Burmese speech rate in Edge TTS / Gemini is ~1.5 - 1.7 words per second.
  // To fill durationSec completely, target words must be durationSec * 1.65
  const targetWordsExact = Math.max(Math.round(durationSec * 1.65), 25);
  const targetWordsMin = Math.max(Math.round(durationSec * 1.45), 20);
  const targetWordsMax = Math.max(Math.round(durationSec * 1.85), 30);
  const targetSentences = Math.min(Math.max(Math.round(durationSec / 5.0), 4), 60);

  let prompt = "";

  if (targetLanguage === "my") {
    prompt = `You are an expert cinematic movie recap narrator in Burmese (မြန်မာဘာသာ ရုပ်ရှင်ဇာတ်လမ်း ပြန်လည်ပြောပြသူ).
Write a comprehensive, engaging, dramatic Burmese recap narration script that covers the ENTIRE ${durationSec}-second duration of the video (${Math.floor(durationSec / 60)}m ${durationSec % 60}s).

CRITICAL DURATION & STORY PACING REQUIREMENTS:
1. VIDEO CONTEXT & EXACT DURATION:
   - Video Title / Topic: "${videoTitle || "Movie / Video Recap"}"
   - Source Video Duration: Exactly ${durationSec} seconds (${Math.floor(durationSec / 60)}m ${durationSec % 60}s).
   - MANDATORY: The spoken narration MUST span the ENTIRE video from the opening scene all the way to the final second (${durationSec}s).
   - Do NOT stop early or write a short summary! Narration must actively describe the whole story up to ${durationSec} seconds.
   - You MUST translate and narrate the complete story from beginning to end until the original transcript is COMPLETELY FINISHED. Include all plot points, twists, characters, climax, and the final resolution/ending.

2. STRICT WORD COUNT BUDGET:
   - Target word count: AT LEAST ${targetWordsMin} to ${targetWordsExact} words (Strict budget: ${targetWordsMin} to ${targetWordsMax} words, approx. ${targetSentences} full narrative sentences).
   - If you write too few words, the voice narration will finish early and leave silence, which is a major error. Ensure 100% full story coverage!

3. SCRIPT FORMAT RULES:
   - Start immediately with the story action. NO greetings, NO intros (NO "မင်္ဂလာပါ", NO "ဒီဗီဒီယိုမှာတော့", NO "ယနေ့တော့", NO channel welcome).
   - Write in immersive, cinematic, natural spoken Burmese (မြန်မာစကားပြော ပြန်လည်ပြောပြချက်).
   - Output ONLY the spoken Burmese narration text. No markdown, titles, timestamps, or headers.

Original transcript / dialogue from the video:
${transcript}`;
  } else {
    // English or other languages
    prompt = `You are an expert cinematic movie recap narrator.
Write a comprehensive, engaging, dramatic recap narration script that covers the ENTIRE ${durationSec}-second duration of the video (${Math.floor(durationSec / 60)}m ${durationSec % 60}s).

CRITICAL DURATION & STORY PACING REQUIREMENTS:
1. Video Title: "${videoTitle || "Video Recap"}"
2. Video Duration: Exactly ${durationSec} seconds (${Math.floor(durationSec / 60)}m ${durationSec % 60}s).
3. MANDATORY: The spoken narration MUST span the ENTIRE video from the opening scene all the way to the final second (${durationSec}s).
4. Do NOT stop early. You MUST translate and narrate the complete story until the original transcript is COMPLETELY FINISHED to the final conclusion.
5. Strict word count: AT LEAST ${targetWordsMin} to ${targetWordsExact} words (approx. ${targetSentences} full narrative sentences).
6. Output ONLY the spoken narration text. No markdown, titles, timestamps, or intros.

Original transcript / dialogue from the video:
${transcript}`;
  }

  let script = await callGeminiApi(prompt, apiKey, preferredModel);

  // Check if output was cut off mid-sentence (missing closing punctuation)
  const trimmed = script.trim();
  const hasProperEnding = 
    trimmed.endsWith("။") || trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?");

  if (!hasProperEnding && trimmed.length > 50) {
    console.log("[Gemini Web] Script was cut off mid-sentence, requesting seamless continuation...");
    const continuationPrompt = `You were writing a movie recap narration. Your script was cut off mid-sentence at:
"...${trimmed.slice(-120)}"

Please continue writing the narration seamlessly from that exact point until the original video transcript and story is completely finished to the final ending.
Output ONLY the remaining narration text without repeating what was already written:`;

    try {
      const continuationText = await callGeminiApi(continuationPrompt, apiKey, preferredModel);
      if (continuationText && continuationText.trim()) {
        script = trimmed + " " + continuationText.trim();
      }
    } catch (contErr) {
      console.warn("[Gemini Web] Continuation request failed, using primary output:", contErr);
    }
  }

  return script.trim();
}
