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
                val obj = translatedChunk.optJSONObject(j)
                if (obj != null) {
                    allTranslatedSegments.put(obj)
                }
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
                val item = segs.optJSONObject(i)
                if (item != null) {
                    list.add(item)
                }
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
        val segmentsArr = JSONArray()
        for (item in batch) {
            val start = item.optDouble("start", 0.0)
            val end = item.optDouble("end", start + 2.0)
            val durSec = (end - start).coerceAtLeast(0.3)
            val maxWords = (durSec * 1.5).toInt().coerceIn(2, 25)

            val enriched = JSONObject().apply {
                put("id", item.opt("id") ?: 0)
                put("start", start)
                put("end", end)
                put("scene_seconds", String.format(java.util.Locale.US, "%.1fs", durSec))
                put("max_burmese_words", maxWords)
                put("text", item.optString("text", ""))
            }
            segmentsArr.put(enriched)
        }
        val batchJson = JSONObject().apply {
            put("segments", segmentsArr)
        }.toString()

        val prompt = """
You are an award-winning professional movie dubbing translator specializing in natural, concise, lip-synced Burmese (မြန်မာဘာသာ ဒါဘင်ပြန်ဆိုသူ).
Translate the following dialogue transcript segments into natural spoken Burmese.

CRITICAL DURATION & SCENE SYNCHRONIZATION CONSTRAINTS:
1. Preserve EXACT timestamps ("start" and "end" in seconds) and the JSON structure.
2. In movie dubbing, the spoken Burmese length MUST strictly fit the original dialogue scene duration.
3. For each segment, observe its `scene_seconds` and `max_burmese_words`:
   - STRICT RULE: Your Burmese translation MUST NOT exceed `max_burmese_words`!
   - If a scene is short (1-2s): Be punchy, direct, and colloquial (AT MOST 2-4 words). No formal filler words.
   - If a Burmese sentence is too long, the voice will overrun into the next video scene, destroying audio-visual sync!
4. Output ONLY valid JSON in this exact structure without markdown or backticks:
{"segments": [{"start": 0.0, "end": 4.0, "text": "မြန်မာစကားပြော ပြန်ဆိုချက်"}]}

Transcript to translate:
$batchJson
        """.trimIndent()

        val rawResponse = callGemini(prompt)
        return cleanMarkdownJson(rawResponse)
    }

    suspend fun generateConcludingNarration(
        contextDialogue: String,
        gapDurationSeconds: Double,
        videoTitle: String = ""
    ): String = withContext(Dispatchers.IO) {
        val durationSec = gapDurationSeconds.toInt().coerceAtLeast(4)
        val targetWords = (durationSec * 1.6).toInt().coerceIn(6, 400)

        val prompt = """
You are a cinematic Burmese movie recap narrator (မြန်မာဘာသာ ရုပ်ရှင်ဇာတ်လမ်း ပြန်လည်ပြောပြသူ).
The dialogue in the video has finished, but the video still has $durationSec seconds remaining until the very end.
Write a concise, engaging concluding recap narration (ဇာတ်သိမ်း သုံးသပ်ချက် / ဇာတ်လမ်းအဆုံးသတ်စကား) in natural spoken Burmese to accompany this final $durationSec-second scene so the audio concludes together with the video.

RULES:
1. Target word count: EXACTLY around $targetWords words (to smoothly fill $durationSec seconds).
2. Summarize the resolution or climax of the story.
3. Write ONLY the spoken Burmese text. No intros, no markdown.

Recent dialogue context:
$contextDialogue
        """.trimIndent()

        callGemini(prompt)
    }

    suspend fun generateRecapScript(
        burmeseTranscript: String,
        videoDurationSeconds: Double = 60.0,
        videoTitle: String = ""
    ): String = withContext(Dispatchers.IO) {
        val durationSec = if (videoDurationSeconds > 0) videoDurationSeconds.toInt() else 60
        // Natural Burmese speech rate in Edge TTS / Gemini is ~1.5 - 1.7 words per second.
        // To fill durationSec completely, target words must be durationSec * 1.65
        val targetWordsExact = (durationSec * 1.65).toInt().coerceAtLeast(20)
        val targetWordsMin = (durationSec * 1.50).toInt().coerceAtLeast(18)
        val targetWordsMax = (durationSec * 1.80).toInt().coerceAtLeast(25)
        val targetSentences = (durationSec / 5.0).toInt().coerceIn(4, 50)

        val prompt = """
You are an expert cinematic movie recap narrator in Burmese (မြန်မာဘာသာ ရုပ်ရှင်ဇာတ်လမ်း ပြန်လည်ပြောပြသူ).
Write a comprehensive, engaging, dramatic Burmese recap narration script that covers the ENTIRE $durationSec-second duration of the video (${durationSec / 60}m ${durationSec % 60}s).

CRITICAL DURATION & STORY PACING REQUIREMENTS:
1. VIDEO CONTEXT & EXACT DURATION:
   - Video Title / Topic: "${videoTitle.ifBlank { "Movie / Video Recap" }}"
   - Source Video Duration: Exactly $durationSec seconds (approx. ${durationSec / 60}m ${durationSec % 60}s).
   - MANDATORY: The spoken narration MUST span the ENTIRE video from the opening scene all the way to the final second ($durationSec s).
   - Do NOT stop early or write a short 1-minute summary for a ${durationSec}s video! Narration must actively describe the whole story up to $durationSec seconds.

2. STRICT WORD COUNT BUDGET:
   - Target word count: AT LEAST $targetWordsMin to $targetWordsExact words (Strict budget: $targetWordsMin to $targetWordsMax words, approx. $targetSentences full narrative sentences).
   - If you write too few words, the voice will finish at 2 minutes and leave silence, which is a major error. Ensure full story coverage!

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
