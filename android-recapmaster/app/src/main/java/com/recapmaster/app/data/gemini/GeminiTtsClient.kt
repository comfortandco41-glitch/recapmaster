package com.recapmaster.app.data.gemini

import android.util.Base64
import com.recapmaster.app.data.model.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class GeminiTtsClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun synthesizeSpeech(
        apiKey: String,
        text: String,
        outputFile: File,
        profile: VoiceProfile
    ): File = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API Key is required for Gemini AI Voice dubbing." }

        val audioBytes = if (profile.isGoogleCloud) {
            synthesizeGoogleCloudTts(apiKey, text, profile.voiceId)
        } else {
            synthesizeGeminiAudio(apiKey, text, profile)
        }

        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            fos.write(audioBytes)
        }
        outputFile
    }

    /**
     * Synthesizes audio using Google Gemini Multimodal Audio Generation (Gemini 3.6 Flash)
     */
    private fun synthesizeGeminiAudio(apiKey: String, fullText: String, profile: VoiceProfile): ByteArray {
        val voiceName = if (profile.voiceId.isNotBlank()) profile.voiceId else "Charon"
        val chunks = splitTextIntoChunks(fullText, maxChunkLength = 1200)

        val pcmStreams = ByteArrayOutputStream()

        for ((index, chunk) in chunks.withIndex()) {
            val audioData = synthesizeGeminiChunkWithFallback(apiKey, chunk, voiceName, profile.promptPersona)
            val rawPcm = extractOrStripPcm(audioData)
            pcmStreams.write(rawPcm)
        }

        val allPcm = pcmStreams.toByteArray()
        if (allPcm.isEmpty()) {
            throw RuntimeException("Gemini TTS synthesis returned empty audio data")
        }

        // Wrap raw 24kHz 16-bit mono PCM in standard canonical RIFF/WAV header
        return pcmToWav(allPcm, sampleRate = 24000, channels = 1, bitsPerSample = 16)
    }

    private fun synthesizeGeminiChunkWithFallback(
        apiKey: String,
        chunkText: String,
        voiceName: String,
        promptPersona: String
    ): ByteArray {
        // Use gemini-3.6-flash model
        val models = listOf("gemini-3.6-flash")
        var lastException: Exception? = null

        for (model in models) {
            try {
                return callGeminiAudioApi(apiKey, model, chunkText, voiceName, promptPersona)
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: RuntimeException("Gemini Audio synthesis failed with gemini-3.6-flash")
    }

    private fun callGeminiAudioApi(
        apiKey: String,
        modelName: String,
        text: String,
        voiceName: String,
        promptPersona: String
    ): ByteArray {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        val styleInstruction = if (promptPersona.isNotBlank()) {
            "Voice style directive: $promptPersona."
        } else {
            "Voice style: natural, engaging, cinematic Burmese movie recap narrator."
        }

        val promptText = """
You are a professional movie recap narrator.
CRITICAL INSTRUCTIONS:
1. Speak aloud the following Burmese narration text fluently and expressively in Burmese.
2. $styleInstruction
3. Speak ONLY the exact script text provided below. Do NOT add any greetings, intro, or commentary.

Script to read:
$text
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", promptText))
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply {
                    put("AUDIO")
                })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", voiceName)
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(jsonBody.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw RuntimeException("Gemini Audio API error HTTP ${response.code}: $errBody")
            }

            val respString = response.body?.string() ?: ""
            val jsonResp = JSONObject(respString)
            val candidates = jsonResp.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val first = candidates.getJSONObject(0)
                val content = first.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null) {
                            val base64Data = inlineData.optString("data", "")
                            if (base64Data.isNotBlank()) {
                                return Base64.decode(base64Data, Base64.DEFAULT)
                            }
                        }
                    }
                }
            }
            throw RuntimeException("No audio inlineData found in Gemini response: $respString")
        }
    }

    /**
     * Synthesizes audio using Google Cloud Text-to-Speech REST API (my-MM-Standard-A)
     */
    private fun synthesizeGoogleCloudTts(apiKey: String, fullText: String, voiceId: String): ByteArray {
        val endpoint = "https://texttospeech.googleapis.com/v1/text:synthesize?key=$apiKey"
        val chunks = splitTextIntoChunks(fullText, maxChunkLength = 1000)

        val output = ByteArrayOutputStream()

        for (chunk in chunks) {
            val jsonBody = JSONObject().apply {
                put("input", JSONObject().put("text", chunk))
                put("voice", JSONObject().apply {
                    put("languageCode", "my-MM")
                    put("name", if (voiceId.isNotBlank()) voiceId else "my-MM-Standard-A")
                })
                put("audioConfig", JSONObject().apply {
                    put("audioEncoding", "MP3")
                })
            }

            val request = Request.Builder()
                .url(endpoint)
                .post(jsonBody.toString().toRequestBody(jsonMedia))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    throw RuntimeException("Google Cloud TTS error HTTP ${response.code}: $errBody")
                }
                val respString = response.body?.string() ?: ""
                val json = JSONObject(respString)
                val base64Audio = json.optString("audioContent", "")
                if (base64Audio.isNotBlank()) {
                    val bytes = Base64.decode(base64Audio, Base64.DEFAULT)
                    output.write(bytes)
                } else {
                    throw RuntimeException("Empty audioContent returned by Google Cloud TTS")
                }
            }
        }

        return output.toByteArray()
    }

    /**
     * Splits long scripts into coherent segments respecting Burmese sentence boundaries (။)
     */
    private fun splitTextIntoChunks(text: String, maxChunkLength: Int = 1200): List<String> {
        val clean = text.trim()
        if (clean.length <= maxChunkLength) return listOf(clean)

        val chunks = mutableListOf<String>()
        val sentences = clean.split(Regex("(?<=[။\n.!?])\\s*")).filter { it.isNotBlank() }

        var currentChunk = StringBuilder()
        for (sentence in sentences) {
            if (currentChunk.length + sentence.length > maxChunkLength && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
            }
            currentChunk.append(sentence).append(" ")
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return if (chunks.isEmpty()) listOf(clean) else chunks
    }

    /**
     * If data has a 44-byte WAV header, strip it to extract the raw PCM payload for concatenation.
     */
    private fun extractOrStripPcm(audioData: ByteArray): ByteArray {
        if (audioData.size >= 44 &&
            audioData[0] == 'R'.code.toByte() &&
            audioData[1] == 'I'.code.toByte() &&
            audioData[2] == 'F'.code.toByte() &&
            audioData[3] == 'F'.code.toByte()
        ) {
            // Find "data" chunk
            var offset = 12
            while (offset + 8 <= audioData.size) {
                val tag = String(audioData, offset, 4)
                val chunkLen = ((audioData[offset + 4].toInt() and 0xFF)) or
                        ((audioData[offset + 5].toInt() and 0xFF) shl 8) or
                        ((audioData[offset + 6].toInt() and 0xFF) shl 16) or
                        ((audioData[offset + 7].toInt() and 0xFF) shl 24)

                if (tag == "data") {
                    val dataStart = offset + 8
                    val dataEnd = minOf(dataStart + chunkLen, audioData.size)
                    return audioData.copyOfRange(dataStart, dataEnd)
                }
                offset += 8 + chunkLen
            }
            // Fallback: standard 44 byte header strip
            return audioData.copyOfRange(44, audioData.size)
        }
        return audioData
    }

    /**
     * Synthesizes a standard 44-byte RIFF/WAVE header for linear PCM audio.
     */
    private fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int = 24000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // Subchunk1Size (16 for PCM)
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // AudioFormat 1 = PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = blockAlign.toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val wavData = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, wavData, 0, 44)
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.size)
        return wavData
    }
}
