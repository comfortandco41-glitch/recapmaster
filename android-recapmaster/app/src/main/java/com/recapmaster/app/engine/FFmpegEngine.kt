package com.recapmaster.app.engine

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BlurBoxConfig(
    val enabled: Boolean = false,
    val xPct: Float = 0.78f,
    val yPct: Float = 0.04f,
    val wPct: Float = 0.18f,
    val hPct: Float = 0.08f,
    val strength: Int = 16
)

class FFmpegEngine(private val context: Context) {

    /**
     * Extracts 16 kHz Mono PCM audio for Whisper on-device speech transcription.
     */
    suspend fun extractSpeechAudio(sourceVideo: File, outputWav: File): File = withContext(Dispatchers.IO) {
        outputWav.parentFile?.mkdirs()
        val cmd = "-y -i \"${sourceVideo.absolutePath}\" -vn -acodec pcm_s16le -ar 16000 -ac 1 \"${outputWav.absolutePath}\""
        executeFfmpeg(cmd)
        if (!outputWav.exists() || outputWav.length() == 0L) {
            throw RuntimeException("Audio extraction failed: output file is empty")
        }
        outputWav
    }

    /**
     * Composes the final video:
     * - Applies playback speed scaling (0.5x - 2.0x) on both video and audio.
     * - Applies custom blur box (watermark/logo removal).
     * - Burns Burmese Padauk subtitles with HarfBuzz shaping.
     * - Maps EXCLUSIVELY the newly dubbed narration voice (1:a) with ZERO source audio (0:a completely excluded).
     */
    suspend fun renderFinalRecap(
        sourceVideo: File,
        dubbedVoiceAudio: File,
        assSubtitleFile: File?,
        outputVideo: File,
        playbackSpeed: Float = 1.0f,
        blurBox: BlurBoxConfig = BlurBoxConfig(),
        fontsDir: File? = null
    ): File = withContext(Dispatchers.IO) {
        outputVideo.parentFile?.mkdirs()

        val speed = playbackSpeed.coerceIn(0.25f, 4.0f)
        val hasSpeed = kotlin.math.abs(speed - 1.0f) > 0.01f
        val hasBlur = blurBox.enabled
        val hasSubs = assSubtitleFile != null && assSubtitleFile.exists()

        // Build video filter chain
        val videoParts = mutableListOf<String>()
        var currentV = "0:v"

        if (hasSpeed) {
            val speedFilter = String.format(java.util.Locale.US, "[%s]setpts=PTS/%.4f[v_speed]", currentV, speed)
            videoParts.add(speedFilter)
            currentV = "v_speed"
        }

        if (hasBlur) {
            val bx = (blurBox.xPct * 1280).toInt().coerceAtLeast(0)
            val by = (blurBox.yPct * 720).toInt().coerceAtLeast(0)
            val bw = (blurBox.wPct * 1280).toInt().let { if (it % 2 != 0) it - 1 else it }.coerceAtLeast(4)
            val bh = (blurBox.hPct * 720).toInt().let { if (it % 2 != 0) it - 1 else it }.coerceAtLeast(4)
            val strength = blurBox.strength.coerceIn(3, 50)

            val nextV = if (hasSubs) "v_blur" else "v_out"
            videoParts.add(
                "[$currentV]split=2[v_base][v_crop];" +
                "[v_crop]crop=w=$bw:h=$bh:x=$bx:y=$by,avgblur=sizeX=$strength:sizeY=$strength[v_blurred];" +
                "[v_base][v_blurred]overlay=x=$bx:y=$by[$nextV]"
            )
            currentV = nextV
        }

        if (hasSubs) {
            val fontArg = if (fontsDir != null && fontsDir.exists()) ":fontsdir='${fontsDir.absolutePath}'" else ""
            videoParts.add("[$currentV]ass='${assSubtitleFile!!.absolutePath}'$fontArg[v_out]")
            currentV = "v_out"
        }

        val hasVideoFilter = videoParts.isNotEmpty()
        val videoFilterStr = videoParts.joinToString(";")

        // Build atempo chain for dubbed voice (1:a)
        val atempoStr = when {
            speed in 0.5f..2.0f -> String.format(java.util.Locale.US, "atempo=%.4f", speed)
            speed > 2.0f -> String.format(java.util.Locale.US, "atempo=2.0,atempo=%.4f", speed / 2.0f)
            else -> String.format(java.util.Locale.US, "atempo=0.5,atempo=%.4f", speed * 2.0f)
        }

        // Build command ensuring 100% PURE DUBBED AUDIO (source audio 0:a completely excluded)
        val cmd = if (hasSpeed) {
            val audioSpeedPart = "[1:a]$atempoStr[a_out]"
            val fullFilter = if (hasVideoFilter) "$videoFilterStr;$audioSpeedPart" else audioSpeedPart
            "-y -i \"${sourceVideo.absolutePath}\" -i \"${dubbedVoiceAudio.absolutePath}\" " +
                    "-filter_complex \"$fullFilter\" " +
                    "-map \"[${if (hasVideoFilter) currentV else "0:v:0"}]\" -map \"[a_out]\" " +
                    "-c:v libx264 -preset ultrafast -crf 23 -pix_fmt yuv420p -c:a aac -b:a 192k \"${outputVideo.absolutePath}\""
        } else if (hasVideoFilter) {
            "-y -i \"${sourceVideo.absolutePath}\" -i \"${dubbedVoiceAudio.absolutePath}\" " +
                    "-filter_complex \"$videoFilterStr\" " +
                    "-map \"[$currentV]\" -map 1:a:0 " +
                    "-c:v libx264 -preset ultrafast -crf 23 -pix_fmt yuv420p -c:a aac -b:a 192k \"${outputVideo.absolutePath}\""
        } else {
            "-y -i \"${sourceVideo.absolutePath}\" -i \"${dubbedVoiceAudio.absolutePath}\" " +
                    "-map 0:v:0 -map 1:a:0 " +
                    "-c:v libx264 -preset ultrafast -crf 23 -pix_fmt yuv420p -c:a aac -b:a 192k \"${outputVideo.absolutePath}\""
        }

        executeFfmpeg(cmd)

        if (!outputVideo.exists() || outputVideo.length() == 0L) {
            throw RuntimeException("Video rendering failed: output file is empty")
        }

        outputVideo
    }

    private suspend fun executeFfmpeg(cmd: String) = suspendCancellableCoroutine<Unit> { cont ->
        val session = FFmpegKit.executeAsync(cmd) { completedSession ->
            if (ReturnCode.isSuccess(completedSession.returnCode)) {
                if (cont.isActive) cont.resume(Unit)
            } else {
                val failMsg = completedSession.failStackTrace ?: completedSession.allLogsAsString
                if (cont.isActive) cont.resumeWithException(RuntimeException("FFmpeg execution failed: $failMsg"))
            }
        }

        cont.invokeOnCancellation {
            session.cancel()
        }
    }
}
