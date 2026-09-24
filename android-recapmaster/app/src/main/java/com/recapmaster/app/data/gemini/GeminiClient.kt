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

    suspend fun generateRecapScript(burmeseTranscript: String): String = withContext(Dispatchers.IO) {
        val prompt = """
You are an expert movie recap storyteller and scriptwriter in Burmese (ရုပ်ရှင်ဇာတ်လမ်းပြောပြသူ).
Write a continuous, engaging, cinematic movie recap narration script in Burmese based on the following transcript.

CRITICAL INSTRUCTIONS:
1. Start DIRECTLY with the story action. Do NOT add ANY introduction (e.g. do NOT say "မင်္ဂလာပါ", "ဒီနေ့မှာတော့", "ကျွန်တော့် channel မှ ကြိုဆိုပါတယ်").
2. Write ONLY in natural, fluent Burmese (မြန်မာဘာသာ).
3. Keep the pacing dynamic, suspenseful, and emotional.
4. Output ONLY the pure narration script text without any titles or markdown headers.

Transcript:
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
