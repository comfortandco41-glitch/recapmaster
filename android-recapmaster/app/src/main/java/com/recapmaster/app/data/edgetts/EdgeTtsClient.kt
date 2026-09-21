package com.recapmaster.app.data.edgetts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EdgeTtsClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val wsUrl = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaheadwork/v1?trustedclienttoken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    suspend fun synthesizeSpeech(
        text: String,
        outputFile: File,
        voiceName: String = "my-MM-ThihaNeural", // Default Burmese Male, or my-MM-NilarNeural (Female)
        rate: String = "+0%",
        pitch: String = "+0Hz"
    ): File = withContext(Dispatchers.IO) {
        val audioBytes = fetchAudioStream(text, voiceName, rate, pitch)
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            fos.write(audioBytes)
        }
        outputFile
    }

    private suspend fun fetchAudioStream(
        text: String,
        voiceName: String,
        rate: String,
        pitch: String
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val dateFormat = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z (zzzz)", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = dateFormat.format(Date())

        val audioStream = ByteArrayOutputStream()

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkgikbmlofdgahkg")
            .build()

        val webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 1. Send speech.config
                val configMsg = "Path:speech.config\r\nX-Timestamp:$timestamp\r\nContent-Type:application/json; charset=utf-8\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                        "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                webSocket.send(configMsg)

                // 2. Build SSML for Burmese Edge TTS
                val escapedText = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='my-MM'>" +
                        "<voice name='$voiceName'>" +
                        "<prosody rate='$rate' pitch='$pitch'>$escapedText</prosody>" +
                        "</voice></speak>"

                val ssmlMsg = "Path:ssml\r\nX-RequestId:$requestId\r\nX-Timestamp:$timestamp\r\nContent-Type:application/ssml+xml\r\n\r\n$ssml"
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, "Completed")
                    if (continuation.isActive) {
                        continuation.resume(audioStream.toByteArray())
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

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (continuation.isActive) {
                    continuation.resumeWithException(t)
                }
            }
        })

        continuation.invokeOnCancellation {
            webSocket.close(1001, "Cancelled")
        }
    }
}
