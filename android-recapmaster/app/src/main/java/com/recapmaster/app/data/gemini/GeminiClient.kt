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
You are a professional movie subtitle translator specializing in natural, fluent Burmese (မြန်မာဘာသာ).
Translate the following dialogue transcript segments into fluent, idiomatic Burmese subtitles.
Preserve exact timestamps and formatting. Output ONLY the translated JSON format:
{"segments": [{"start": 0.0, "end": 4.0, "text": "မြန်မာဘာသာပြန်စာတန်း"}]}

Transcript to translate:
$transcriptJson
        """.trimIndent()

        callGemini(prompt)
    }

    suspend fun generateRecapScript(burmeseTranscript: String, videoDurationSeconds: Double = 60.0): String = withContext(Dispatchers.IO) {
        val durationSec = if (videoDurationSeconds > 0) videoDurationSeconds.toInt() else 60
        // Natural Burmese speech rate is ~2.2 words per second (130 words per minute)
        val targetWords = (durationSec * 2.2).toInt().coerceAtLeast(80)

        val prompt = """
You are a master movie recap creator and voiceover narrator in Burmese (ရုပ်ရှင်ဇာတ်လမ်း ပြန်လည်ပြောပြသူ).
Write a continuous, engaging, cinematic movie recap narration script in Burmese based on the following transcript.

CRITICAL DURATION & STORY PACING REQUIREMENTS:
1. FULL VIDEO DURATION MATCHING:
   - The source video is exactly $durationSec seconds long.
   - You MUST generate enough narration script to span the FULL $durationSec seconds from the beginning to the very end of the video.
   - Target word count: AT LEAST $targetWords Burmese words (approximately ${(durationSec / 10).coerceAtLeast(4)} full Burmese sentences).
   - Do NOT just write a short summary. Walk the listener through each scene and dialogue beat across the entire timeline (beginning scenes, middle developments, conflicts, climax, and ending).
2. NO INTRO GREETINGS:
   - Start directly with the story action.
   - Do NOT include any greetings or intros (no "မင်္ဂလာပါ", "ဒီဗီဒီယိုမှာတော့", "ကျွန်တော့် channel မှ ကြိုဆိုပါတယ်", "ယနေ့တော့", or similar intro text).
3. CINEMATIC NARRATION STYLE:
   - Write in natural, immersive, dynamic, suspenseful Burmese (မြန်မာဘာသာ).
4. PURE TEXT OUTPUT:
   - Output ONLY the spoken narration text. No markdown, titles, timestamps, or headers.

Transcript segments across the video:
$burmeseTranscript
        """.trimIndent()

        callGemini(prompt)
    }

    private fun callGemini(promptText: String): String {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$apiKey"

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", promptText))
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
                put("maxOutputTokens", 4096)
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(jsonBody.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Empty body"
                throw RuntimeException("Gemini API call failed (HTTP ${response.code}): $errorBody")
            }

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
            throw RuntimeException("No text candidates found in Gemini response")
        }
    }
}
