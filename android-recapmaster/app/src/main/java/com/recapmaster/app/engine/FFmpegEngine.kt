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
     * Maps a sound style preset name to an FFmpeg audio filter chain.
     * These simulate the server-side Python mastering presets using FFmpegKit.
     */
    private fun soundStyleAudioFilter(soundStyle: String): String = when (soundStyle) {
        // Epic bass boost + presence lift for movie trailers
        "cinematic_recap" ->
            "equalizer=f=80:t=o:w=2:g=5,equalizer=f=3000:t=o:w=1.5:g=3," +
            "acompressor=threshold=-18dB:ratio=4:attack=5:release=100:makeup=3dB," +
            "loudnorm=I=-16:LRA=11:TP=-1.5"
        // High tension, clipped highs, tight compression
        "dramatic_suspense" ->
            "equalizer=f=200:t=o:w=2:g=-2,equalizer=f=5000:t=o:w=2:g=4," +
            "acompressor=threshold=-20dB:ratio=6:attack=2:release=60:makeup=4dB," +
            "loudnorm=I=-14:LRA=8:TP=-1.5"
        // Punchy mid-boost, fast transients
        "energetic_action" ->
            "equalizer=f=100:t=o:w=2:g=4,equalizer=f=2000:t=o:w=2:g=3," +
            "acompressor=threshold=-22dB:ratio=5:attack=1:release=40:makeup=5dB," +
            "loudnorm=I=-14:LRA=7:TP=-1"
        // Warm low-mids, soft highs, gentle compression
        "emotional_warmth" ->
            "equalizer=f=250:t=o:w=2:g=3,equalizer=f=8000:t=o:w=1.5:g=-2," +
            "acompressor=threshold=-24dB:ratio=2.5:attack=10:release=200:makeup=2dB," +
            "loudnorm=I=-18:LRA=14:TP=-2"
        // Flat, clean, broadcast standard
        "broadcast_studio" ->
            "highpass=f=80,lowpass=f=16000," +
            "acompressor=threshold=-20dB:ratio=3:attack=5:release=100:makeup=2dB," +
            "loudnorm=I=-16:LRA=11:TP=-1.5"
        // Default: gentle normalisation only
        else ->
            "loudnorm=I=-16:LRA=11:TP=-1.5"
    }

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
     * Rapidly muxes source video with dubbed audio without re-encoding video stream.
     * Takes ~1 second and produces an instant synchronized preview file for ExoPlayer.
     */
    suspend fun muxPreviewDubbedVideo(
        sourceVideo: File,
        dubbedVoiceAudio: File,
        outputVideo: File
    ): File = withContext(Dispatchers.IO) {
        outputVideo.parentFile?.mkdirs()
        val cmd = "-y -i \"${sourceVideo.absolutePath}\" -i \"${dubbedVoiceAudio.absolutePath}\" " +
                "-map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 128k -shortest \"${outputVideo.absolutePath}\""
        executeFfmpeg(cmd)
        if (!outputVideo.exists() || outputVideo.length() == 0L) {
            throw RuntimeException("Preview mux failed: output file is empty")
        }
        outputVideo
    }

    /**
     * Composes the final recap video:
     * - Applies playback speed scaling (0.25x–4.0x) on both video and audio.
     * - Applies custom blur box for watermark/logo removal.
     * - Burns Burmese Padauk subtitles (.ass file) with HarfBuzz shaping.
     * - Replaces source audio 100% with dubbed narration voice.
     * - Applies sound style EQ/mastering filter chain.
     */
    suspend fun renderFinalRecap(
        sourceVideo: File,
        dubbedVoiceAudio: File,
        assSubtitleFile: File?,
        outputVideo: File,
        playbackSpeed: Float = 1.0f,
        blurBox: BlurBoxConfig = BlurBoxConfig(),
        fontsDir: File? = null,
        soundStyle: String = "cinematic_recap"
    ): File = withContext(Dispatchers.IO) {
        outputVideo.parentFile?.mkdirs()

        val speed = playbackSpeed.coerceIn(0.25f, 4.0f)
        val hasSpeed = kotlin.math.abs(speed - 1.0f) > 0.01f
        val hasBlur = blurBox.enabled
        val hasSubs = assSubtitleFile != null && assSubtitleFile.exists()
        val audioEq = soundStyleAudioFilter(soundStyle)

        // ── Video filter chain ────────────────────────────────────────────
        val videoParts = mutableListOf<String>()
        var currentV = "0:v"

        if (hasSpeed) {
            val f = String.format(java.util.Locale.US, "[%s]setpts=PTS/%.4f[v_speed]", currentV, speed)
            videoParts.add(f)
            currentV = "v_speed"
        }

        if (hasBlur) {
            val bx = (blurBox.xPct * 1280).toInt().coerceAtLeast(0)
            val by = (blurBox.yPct * 720).toInt().coerceAtLeast(0)
            val bw = (blurBox.wPct * 1280).toInt().let { if (it % 2 != 0) it - 1 else it }.coerceAtLeast(4)
            val bh = (blurBox.hPct * 720).toInt().let { if (it % 2 != 0) it - 1 else it }.coerceAtLeast(4)
            val str = blurBox.strength.coerceIn(3, 50)
            val nextV = if (hasSubs) "v_blur" else "v_out"
            videoParts.add(
                "[$currentV]split=2[v_base][v_crop];" +
                "[v_crop]crop=w=$bw:h=$bh:x=$bx:y=$by,avgblur=sizeX=$str:sizeY=$str[v_blurred];" +
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

        // ── Audio filter chain (dubbed voice + sound style EQ) ───────────
        // Build atempo chain (handles speeds outside 0.5–2.0 range by chaining)
        val atempoStr: String = when {
            speed >= 0.5f && speed <= 2.0f ->
                String.format(java.util.Locale.US, "atempo=%.4f", speed)
            speed > 2.0f ->
                String.format(java.util.Locale.US, "atempo=2.0,atempo=%.4f", speed / 2.0f)
            else ->
                String.format(java.util.Locale.US, "atempo=0.5,atempo=%.4f", speed * 2.0f)
        }

        // ── Assemble final FFmpeg command ─────────────────────────────────
        val cmd: String = if (hasSpeed) {
            val audioPart = "[1:a]${atempoStr},${audioEq}[a_out]"
            val fullFilter = if (hasVideoFilter) "$videoFilterStr;$audioPart" else audioPart
            "-y -i \"${sourceVideo.absolutePath}\" -i \"${dubbedVoiceAudio.absolutePath}\" " +
                "-filter_complex \"$fullFilter\" " +
                "-map \"[${if (hasVideoFilter) currentV else "0:v:0"}]\" -map \"[a_out]\" " +
                "-c:v libx264 -preset ultrafast -crf 23 -pix_fmt yuv420p " +
                "-c:a aac -b:a 192k \"${outputVideo.absolutePath}\""
        } else if (hasVideoFilter) {
            val audioPart = "[1:a]${audioEq}[a_out]"
            val fullFilter = "$videoFilterStr;$audioPart"
            "-y -i \"${sourceVideo.absolutePath}\" -i \"${dubbedVoiceAudio.absolutePath}\" " +
                "-filter_complex \"$fullFilter\" " +
                "-map \"[$currentV]\" -map \"[a_out]\" " +
                "-c:v libx264 -preset ultrafast -crf 23 -pix_fmt yuv420p " +
                "-c:a aac -b:a 192k \"${outputVideo.absolutePath}\""
        } else {
            val audioPart = "[1:a]${audioEq}[a_out]"
            "-y -i \"${sourceVideo.absolutePath}\" -i \"${dubbedVoiceAudio.absolutePath}\" " +
                "-filter_complex \"$audioPart\" " +
                "-map 0:v:0 -map \"[a_out]\" " +
                "-c:v libx264 -preset ultrafast -crf 23 -pix_fmt yuv420p " +
                "-c:a aac -b:a 192k \"${outputVideo.absolutePath}\""
        }

        executeFfmpeg(cmd)

        if (!outputVideo.exists() || outputVideo.length() == 0L) {
            throw RuntimeException("Video rendering failed: output file is empty or missing")
        }

        outputVideo
    }

    private suspend fun executeFfmpeg(cmd: String) = suspendCancellableCoroutine<Unit> { cont ->
        val session = FFmpegKit.executeAsync(cmd) { completedSession ->
            if (ReturnCode.isSuccess(completedSession.returnCode)) {
                if (cont.isActive) cont.resume(Unit)
            } else {
                val failMsg = completedSession.failStackTrace
                    ?: completedSession.allLogsAsString
                    ?: "Unknown FFmpeg error"
                if (cont.isActive) cont.resumeWithException(RuntimeException("FFmpeg failed: $failMsg"))
            }
        }
        cont.invokeOnCancellation { session.cancel() }
    }
}
