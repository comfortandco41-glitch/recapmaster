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
You are a professional movie subtitle & dubbing translator specializing in natural, fluent, lip-synced Burmese (မြန်မာဘာသာ ဒါဘင်ပြန်ဆိုသူ).
Translate the following dialogue transcript segments into fluent, idiomatic Burmese.

CRITICAL DURATION & TIMING CONSTRAINTS:
1. Preserve EXACT timestamps ("start" and "end" in seconds) and segment array structure.
2. In movie dubbing, the spoken Burmese duration must fit within the scene window (duration = end - start seconds).
3. Burmese speech averages ~2.5 words (approx 4-6 syllables) per second.
   - For short scenes (1-3s): Keep the Burmese concise, punchy, and direct. Avoid verbose formal prefixes or filler words.
   - For longer scenes (4-8s): Match the natural conversational flow.
4. Output ONLY valid JSON in this exact structure without markdown or backticks:
{"segments": [{"start": 0.0, "end": 4.0, "text": "မြန်မာဘာသာပြန်စာတန်း"}]}

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
        // Natural Burmese speech rate is ~2.2 words per second (130 words per minute)
        // Burmese sentences average 10-15 words, each taking ~5-7 seconds to speak naturally.
        val targetSentences = (durationSec / 5.5).toInt().coerceAtLeast(10)
        val targetWords = (durationSec * 2.2).toInt().coerceAtLeast(120)

        val prompt = """
You are an expert cinematic movie recap narrator in Burmese (မြန်မာဘာသာ ရုပ်ရှင်ဇာတ်လမ်း ပြန်လည်ပြောပြသူ).
Write a continuous, engaging, dramatic, and detailed Burmese recap narration script that covers the ENTIRE duration of the video.

CRITICAL DURATION & STORY PACING REQUIREMENTS:
1. VIDEO CONTEXT & FULL DURATION:
   - Video Title / Topic: "${videoTitle.ifBlank { "Movie / Video Recap" }}"
   - Source Video Duration: Exactly $durationSec seconds (approx. ${durationSec / 60}m ${durationSec % 60}s).
   - MANDATORY: The narration MUST span the ENTIRE $durationSec seconds from start to the very end of the video.
   - Do NOT stop early! Even if the dialogue transcript ends early or only covers the first part of the video, you MUST continue narrating the complete storyline, character actions, emotional reactions, dramatic turning point, climax, and heartwarming/concluding moral message all the way to the final seconds ($durationSec s).

2. REQUIRED SCRIPT LENGTH & STRUCTURE:
   - Target word count: AT LEAST $targetWords Burmese words (minimum $targetSentences complete Burmese sentences ending with '။').
   - Structure the narration across 3 distinct timeline acts so the story flows continuously:
     * Beginning (0s - 30%): Introduce the scene, characters, setting, and initial conflict or encounter.
     * Middle (30% - 70%): Describe the unfolding drama, actions, challenges, emotions, and key interactions.
     * Climax & Ending (70% - 100%): Describe the peak emotional moment, resolution, bond, gratitude, and final conclusion right up to the end of the video ($durationSec seconds).

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
