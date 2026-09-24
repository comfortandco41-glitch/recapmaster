package com.recapmaster.app.data.edgetts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EdgeTtsClient {

    companion object {
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private const val CHROMIUM_MAJOR_VERSION = "143"
        private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
        private const val WIN_EPOCH = 11644473600L
        private const val S_TO_NS = 1_000_000_000L

        /**
         * Generates the dynamic Sec-MS-GEC token required by Microsoft Edge TTS.
         * The token is calculated from Windows file time epoch rounded down to 5 minutes,
         * concatenated with the trusted client token, and SHA-256 hashed.
         */
        fun generateSecMsGec(): String {
            val unixNow = System.currentTimeMillis() / 1000L
            var ticks = unixNow + WIN_EPOCH
            ticks -= (ticks % 300L)
            val fileTimeTicks = ticks * (S_TO_NS / 100L) // 10,000,000
            val strToHash = "$fileTimeTicks$TRUSTED_CLIENT_TOKEN"
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(strToHash.toByteArray(Charsets.US_ASCII))
            return hashBytes.joinToString("") { "%02X".format(it) }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun synthesizeSpeech(
        text: String,
        outputFile: File,
        voiceName: String = "my-MM-ThihaNeural", // Default Burmese Male, or my-MM-NilarNeural (Female)
        rate: String = "+0%",
        pitch: String = "+0Hz"
    ): File = withContext(Dispatchers.IO) {
        val chunks = splitTextIntoChunks(text, maxChunkLength = 350)
        val combinedAudio = ByteArrayOutputStream()

        for (chunk in chunks) {
            val trimmed = chunk.trim()
            if (trimmed.isEmpty()) continue

            var chunkAudio: ByteArray? = null
            var lastErr: Exception? = null

            // Retry up to 2 times per chunk
            for (attempt in 1..2) {
                try {
                    chunkAudio = fetchAudioStream(trimmed, voiceName, rate, pitch)
                    if (chunkAudio.isNotEmpty()) break
                } catch (e: Exception) {
                    lastErr = e
                    kotlinx.coroutines.delay(300)
                }
            }

            if (chunkAudio != null && chunkAudio.isNotEmpty()) {
                combinedAudio.write(chunkAudio)
            } else if (lastErr != null && chunks.size == 1) {
                throw lastErr
            }
        }

        val allBytes = combinedAudio.toByteArray()
        if (allBytes.isEmpty()) {
            throw IllegalStateException("Edge TTS finished but returned no audio data")
        }

        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            fos.write(allBytes)
        }
        outputFile
    }

    private fun splitTextIntoChunks(text: String, maxChunkLength: Int = 350): List<String> {
        val cleaned = text.trim()
        if (cleaned.length <= maxChunkLength) return listOf(cleaned)

        // Split on Burmese sentence delimiter '။', newline, or punctuation
        val regex = Regex("(?<=[။\n.!?])\\s*")
        val sentences = cleaned.split(regex).map { it.trim() }.filter { it.isNotEmpty() }

        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (sentence in sentences) {
            if (currentChunk.isNotEmpty() && (currentChunk.length + sentence.length > maxChunkLength)) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
            }
            if (sentence.length > maxChunkLength) {
                // If a single sentence exceeds maxChunkLength, split by Burmese comma '၊' or space
                val subParts = sentence.split(Regex("(?<=[,၊ ])\\s*"))
                for (part in subParts) {
                    if (currentChunk.isNotEmpty() && (currentChunk.length + part.length > maxChunkLength)) {
                        chunks.add(currentChunk.toString().trim())
                        currentChunk = StringBuilder()
                    }
                    if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                    currentChunk.append(part)
                }
            } else {
                if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                currentChunk.append(sentence)
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return if (chunks.isEmpty()) listOf(cleaned) else chunks
    }

    private suspend fun fetchAudioStream(
        text: String,
        voiceName: String,
        rate: String,
        pitch: String
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val secMsGec = generateSecMsGec()

        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val timestamp = dateFormat.format(Date())

        val wsUrl = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                "&ConnectionId=$connectionId" +
                "&Sec-MS-GEC=$secMsGec" +
                "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

        val audioStream = ByteArrayOutputStream()

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR_VERSION.0.0.0")
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .addHeader("Pragma", "no-cache")
            .addHeader("Cache-Control", "no-cache")
            .build()

        val webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 1. Send speech.config
                val configMsg = "X-Timestamp:$timestamp\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                        "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                webSocket.send(configMsg)

                // 2. Build SSML for Burmese Edge TTS
                val escapedText = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='my-MM'>" +
                        "<voice name='$voiceName'>" +
                        "<prosody rate='$rate' pitch='$pitch'>$escapedText</prosody>" +
                        "</voice></speak>"

                val ssmlMsg = "X-RequestId:$requestId\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${timestamp}Z\r\n" +
                        "Path:ssml\r\n\r\n" +
                        ssml
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, "Completed")
                    if (continuation.isActive) {
                        val result = audioStream.toByteArray()
                        if (result.isEmpty()) {
                            continuation.resumeWithException(IllegalStateException("Edge TTS finished but returned no audio data"))
                        } else {
                            continuation.resume(result)
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size >= 2) {
                    val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    val audioStart = headerLen + 2
                    if (data.size > audioStart) {
                        audioStream.write(data, audioStart, data.size - audioStart)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (continuation.isActive) {
                    val result = audioStream.toByteArray()
                    if (result.isNotEmpty()) {
                        continuation.resume(result)
                    } else if (code != 1000) {
                        continuation.resumeWithException(IllegalStateException("WebSocket closed unexpectedly: $code $reason"))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (continuation.isActive) {
                    val responseDetail = response?.let { " (HTTP ${it.code}: ${it.message})" } ?: ""
                    continuation.resumeWithException(Exception("Edge TTS failed$responseDetail: ${t.message}", t))
                }
            }
        })

        continuation.invokeOnCancellation {
            webSocket.close(1001, "Cancelled")
        }
    }
}
