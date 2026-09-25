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
$transcriptJson
        """.trimIndent()

        callGemini(prompt)
    }

    suspend fun generateRecapScript(
        burmeseTranscript: String,
        videoDurationSeconds: Double = 60.0,
        videoTitle: String = ""
    ): String = withContext(Dispatchers.IO) {
        val durationSec = if (videoDurationSeconds > 0) videoDurationSeconds.toInt() else 60
        // Natural Burmese speech rate in Edge TTS / Gemini is ~1.8 words per second (108 words per minute).
        // To match an exact 1-minute video, the script must be strictly ~105-112 words so audio finishes with visual.
        val targetWordsExact = (durationSec * 1.80).toInt().coerceAtLeast(18)
        val targetWordsMin = (durationSec * 1.65).toInt().coerceAtLeast(15)
        val targetWordsMax = (durationSec * 1.95).toInt().coerceAtLeast(22)
        val targetSentences = (durationSec / 6.0).toInt().coerceIn(3, 30)

        val prompt = """
You are an expert cinematic movie recap narrator in Burmese (မြန်မာဘာသာ ရုပ်ရှင်ဇာတ်လမ်း ပြန်လည်ပြောပြသူ).
Write a concise, engaging, dramatic Burmese recap narration script that EXACTLY matches the $durationSec-second duration of the video.

CRITICAL DURATION & STORY PACING REQUIREMENTS:
1. VIDEO CONTEXT & EXACT DURATION:
   - Video Title / Topic: "${videoTitle.ifBlank { "Movie / Video Recap" }}"
   - Source Video Duration: Exactly $durationSec seconds (approx. ${durationSec / 60}m ${durationSec % 60}s).
   - MANDATORY: The spoken narration MUST fit the exact $durationSec-second visual timeline.
   - If the video is 1 minute ($durationSec s), the Burmese narration MUST conclude precisely around $durationSec seconds. Do NOT make it overly long or add unnecessary filler!

2. STRICT WORD COUNT BUDGET:
   - Burmese speech rate is ~1.8 words per second.
   - Target word count: EXACTLY around $targetWordsExact words (Strict budget: $targetWordsMin to $targetWordsMax words, ~ $targetSentences complete sentences).
   - Do NOT write more than $targetWordsMax words, otherwise audio will overrun the video!
   - Do NOT write fewer than $targetWordsMin words, otherwise narration will finish prematurely!
   - Ensure the story has a complete arc (Beginning hook -> Core action -> Climax/Ending) neatly condensed into this exact word budget so audio and video conclude at the exact same moment.

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
