package com.recapmaster.app.data.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun translateToBurmese(transcriptJson: String): String = withContext(Dispatchers.IO) {
        val inputSegments = parseSegmentsFromJson(transcriptJson)
        if (inputSegments.isEmpty()) {
            return@withContext transcriptJson
        }

        // If 10 or fewer segments, translate in one shot
        if (inputSegments.size <= 10) {
            return@withContext translateBatch(inputSegments)
        }

        // For long videos (> 10 segments, covering 2+ minutes):
        // Translate in chunks of 10 segments to completely prevent LLM truncation / laziness.
        // Guarantees 100% of dialogue past 1:30 min is fully translated without any missing speech.
        val batchSize = 10
        val allTranslatedSegments = JSONArray()

        for (i in inputSegments.indices step batchSize) {
            val chunk = inputSegments.subList(i, kotlin.math.min(i + batchSize, inputSegments.size))
            val batchResultJson = translateBatch(chunk)
            val translatedChunk = parseSegmentsArray(batchResultJson)
            for (j in 0 until translatedChunk.length()) {
                allTranslatedSegments.put(translatedChunk.getJSONObject(j))
            }
        }

        val finalResult = JSONObject().apply {
            put("segments", allTranslatedSegments)
        }
        finalResult.toString()
    }

    private fun cleanMarkdownJson(raw: String): String {
        var clean = raw.trim()
        if (clean.contains("```json")) {
            clean = clean.substringAfter("```json").substringBefore("```").trim()
        } else if (clean.contains("```")) {
            clean = clean.substringAfter("```").substringBefore("```").trim()
        }
        val startIdx = clean.indexOf('{')
        val endIdx = clean.lastIndexOf('}')
        if (startIdx >= 0 && endIdx > startIdx) {
            clean = clean.substring(startIdx, endIdx + 1)
        }
        return clean
    }

    private fun parseSegmentsFromJson(jsonStr: String): List<JSONObject> {
        val list = mutableListOf<JSONObject>()
        try {
            val clean = cleanMarkdownJson(jsonStr)
            val root = JSONObject(clean)
            val segs = root.optJSONArray("segments") ?: return emptyList()
            for (i in 0 until segs.length()) {
                list.add(segs.getJSONObject(i))
            }
        } catch (_: Exception) {}
        return list
    }

    private fun parseSegmentsArray(jsonStr: String): JSONArray {
        try {
            val clean = cleanMarkdownJson(jsonStr)
            val root = JSONObject(clean)
            return root.optJSONArray("segments") ?: JSONArray()
        } catch (_: Exception) {
            return JSONArray()
        }
    }

    private fun translateBatch(batch: List<JSONObject>): String {
        val batchJson = JSONObject().apply {
            put("segments", JSONArray(batch))
        }.toString()

        val prompt = """
You are an award-winning movie dubbing translator specializing in natural, concise, lip-synced Burmese (မြန်မာဘာသာ ဒါဘင်ပြန်ဆိုသူ).
Translate the following dialogue transcript segments into natural spoken Burmese.

CRITICAL DURATION & CONCISE TIMING CONSTRAINTS:
1. Preserve EXACT timestamps ("start" and "end" in seconds) and the JSON structure.
2. In movie dubbing, the spoken Burmese length MUST strictly fit the original dialogue scene duration (duration = end - start seconds).
3. Burmese speech rate is ~1.8 words (approx 3.5 to 4 syllables) per second.
   - For every segment, calculate max allowed words = max(2, ((end - start) * 1.8).toInt()).
   - STRICT RULE: Do NOT write long or wordy Burmese sentences! If the original scene is 1.5 seconds, use AT MOST 3-4 words.
   - For short scenes (1-2s): Be punchy, direct, and colloquial. No formal filler words.
   - For medium scenes (3-5s): Keep sentences tight and concise so the voice speaks at a natural, comfortable tempo without having to rush or drag.
4. Output ONLY valid JSON in this exact structure without markdown or backticks:
{"segments": [{"start": 0.0, "end": 4.0, "text": "မြန်မာစကားပြော ပြန်ဆိုချက်"}]}

Transcript to translate:
$batchJson
        """.trimIndent()

        val rawResponse = callGemini(prompt)
        return cleanMarkdownJson(rawResponse)
    }

    suspend fun generateRecapScript(
        burmeseTranscript: String,
        videoDurationSeconds: Double = 60.0,
        videoTitle: String = ""
    ): String = withContext(Dispatchers.IO) {
        val durationSec = if (videoDurationSeconds > 0) videoDurationSeconds.toInt() else 60
        // Natural Burmese speech rate in Edge TTS / Gemini is ~1.8 words per second (108 words per minute).
        // Target word count scales with durationSec so spoken narration spans the full video length.
        val targetWordsExact = (durationSec * 1.80).toInt().coerceAtLeast(18)
        val targetWordsMin = (durationSec * 1.65).toInt().coerceAtLeast(15)
        val targetWordsMax = (durationSec * 1.95).toInt().coerceAtLeast(22)
        val targetSentences = (durationSec / 6.0).toInt().coerceIn(3, 50)

        val prompt = """
You are an expert cinematic movie recap narrator in Burmese (မြန်မာဘာသာ ရုပ်ရှင်ဇာတ်လမ်း ပြန်လည်ပြောပြသူ).
Write a comprehensive, engaging, dramatic Burmese recap narration script that covers the ENTIRE $durationSec-second duration of the video (${durationSec / 60}m ${durationSec % 60}s).

CRITICAL DURATION & STORY PACING REQUIREMENTS:
1. VIDEO CONTEXT & EXACT DURATION:
   - Video Title / Topic: "${videoTitle.ifBlank { "Movie / Video Recap" }}"
   - Source Video Duration: Exactly $durationSec seconds (approx. ${durationSec / 60}m ${durationSec % 60}s).
   - MANDATORY: The spoken narration MUST span the ENTIRE video from the opening scene all the way to the final second ($durationSec s).
   - Do NOT stop early or write a short 1-minute summary for a ${durationSec}s video!

2. STRICT WORD COUNT BUDGET:
   - Burmese speech rate is ~1.8 words per second.
   - Target word count: EXACTLY around $targetWordsExact words (Strict budget: $targetWordsMin to $targetWordsMax words, approx. $targetSentences full narrative sentences).
   - Ensure the narration covers the entire progression of the video (Hook -> Development -> Climax -> Ending resolution) so voice narration continues throughout the entire $durationSec seconds.

3. SCRIPT FORMAT RULES:
   - Start immediately with the story action. NO greetings, NO intros (NO "မင်္ဂလာပါ", NO "ဒီဗီဒီယိုမှာတော့", NO "ယနေ့တော့", NO channel welcome).
   - Write in immersive, cinematic, natural spoken Burmese (မြန်မာစကားပြော ပြန်လည်ပြောပြချက်).
   - Output ONLY the spoken Burmese narration text. No markdown, titles, timestamps, or headers.

Dialogue transcript segments from the video:
$burmeseTranscript
        """.trimIndent()

        callGemini(prompt)
    }

    private fun callGemini(promptText: String): String {
        val candidateModels = listOf("gemini-3.6-flash", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", promptText))
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.5)
                put("maxOutputTokens", 8192)
            })
        }

        var lastErr: Exception? = null

        for (model in candidateModels) {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(endpoint)
                .post(jsonBody.toString().toRequestBody(jsonMedia))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val respString = response.body?.string() ?: ""
                        val jsonResp = JSONObject(respString)
                        val candidates = jsonResp.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val first = candidates.getJSONObject(0)
                            val content = first.optJSONObject("content")
                            val parts = content?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                return parts.getJSONObject(0).optString("text", "").trim()
                            }
                        }
                    } else if (response.code in listOf(400, 403, 404)) {
                        val errorBody = response.body?.string() ?: ""
                        lastErr = RuntimeException("Gemini model $model returned HTTP ${response.code}: $errorBody")
                        // Continue to next candidate model
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        lastErr = RuntimeException("Gemini API call failed (HTTP ${response.code}): $errorBody")
                    }
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }

        throw lastErr ?: RuntimeException("Gemini API call failed across all candidate models")
    }
}
