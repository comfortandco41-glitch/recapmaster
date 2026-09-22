package com.recapmaster.app.pipeline

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.recapmaster.app.data.downloader.UrlDownloader
import com.recapmaster.app.data.edgetts.EdgeTtsClient
import com.recapmaster.app.data.gemini.GeminiClient
import com.recapmaster.app.engine.BlurBoxConfig
import com.recapmaster.app.engine.FFmpegEngine
import com.recapmaster.app.engine.SubtitleGenerator
import com.recapmaster.app.engine.WhisperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

enum class PipelineStage {
    IDLE,
    DOWNLOADING,
    EXTRACTING_AUDIO,
    TRANSCRIBING,
    TRANSLATING_SCRIPT,
    DUBBING_VOICE,
    COMPOSING_VIDEO,
    COMPLETED,
    FAILED
}

data class PipelineState(
    val stage: PipelineStage = PipelineStage.IDLE,
    val progress: Float = 0.0f,
    val message: String = "Ready",
    val finalVideoUri: Uri? = null,
    val error: String? = null,
    val logLines: List<String> = emptyList()
)

class RecapPipelineManager(private val context: Context) {

    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state

    private val downloader = UrlDownloader()
    private val ffmpegEngine = FFmpegEngine(context)
    private val whisperEngine = WhisperEngine(context)
    private val edgeTtsClient = EdgeTtsClient()

    private val logBuffer = mutableListOf<String>()

    private fun log(msg: String, stage: PipelineStage? = null, progress: Float? = null) {
        logBuffer.add(msg)
        _state.value = _state.value.copy(
            stage = stage ?: _state.value.stage,
            progress = progress ?: _state.value.progress,
            message = msg,
            logLines = logBuffer.toList()
        )
    }

    suspend fun executePipeline(
        videoUrl: String,
        geminiApiKey: String,
        voiceName: String = "my-MM-ThihaNeural",
        soundStyle: String = "cinematic_recap",
        burnSubtitles: Boolean = true,
        subtitlePlacement: String = "bottom",
        fontScale: Float = 1.0f,
        marginV: Int = 30,
        playbackSpeed: Float = 1.0f,
        blurBox: BlurBoxConfig = BlurBoxConfig()
    ) = withContext(Dispatchers.IO) {
        logBuffer.clear()
        _state.value = PipelineState()

        val workDir = File(context.cacheDir, "job_${System.currentTimeMillis()}").apply { mkdirs() }
        val sourceVideo = File(workDir, "source.mp4")
        val extractedAudio = File(workDir, "audio_16k.wav")
        val voiceAudio = File(workDir, "dubbed_voice.mp3")
        val assSubtitles = File(workDir, "subtitles.ass")
        val finalVideo = File(workDir, "final_recap.mp4")

        try {
            // Stage 1: Download
            log("📥 [1/6] Downloading video from URL...", PipelineStage.DOWNLOADING, 0.05f)
            val downloadRes = downloader.downloadUrl(videoUrl, sourceVideo)
            log("✅ Downloaded: ${downloadRes.title} (${downloadRes.durationSeconds.toInt()}s)", progress = 0.18f)

            // Stage 2: Audio Extraction
            log("🎙️ [2/6] Extracting speech audio for Whisper...", PipelineStage.EXTRACTING_AUDIO, 0.22f)
            ffmpegEngine.extractSpeechAudio(downloadRes.localFile, extractedAudio)
            log("✅ Audio extracted (16kHz mono PCM)", progress = 0.32f)

            // Stage 3: On-Device Whisper Transcription
            log("🧠 [3/6] Transcribing dialogue on-device with Whisper...", PipelineStage.TRANSCRIBING, 0.35f)
            val modelFile = ensureWhisperModel()
            whisperEngine.loadModel(modelFile)
            val transcriptJson = whisperEngine.transcribeWav(extractedAudio, language = "auto")
            whisperEngine.release()
            log("✅ Transcription complete", progress = 0.50f)

            // Stage 4: Gemini Burmese Translation & Recap Script
            log("🌏 [4/6] Translating & generating Burmese recap narration via Gemini...", PipelineStage.TRANSLATING_SCRIPT, 0.53f)
            val geminiClient = GeminiClient(geminiApiKey)
            val burmeseTranscript = geminiClient.translateToBurmese(transcriptJson)
            val narrationScript = geminiClient.generateRecapScript(burmeseTranscript)
            log("✅ Burmese recap script generated", progress = 0.65f)

            // Stage 5: Edge TTS Voice Dubbing
            log("🔊 [5/6] Synthesizing Burmese voice ($voiceName) via Edge TTS...", PipelineStage.DUBBING_VOICE, 0.68f)
            edgeTtsClient.synthesizeSpeech(
                text = narrationScript,
                outputFile = voiceAudio,
                voiceName = voiceName,
                rate = "+10%",
                pitch = "-2Hz"
            )
            log("✅ Voice narration synthesized", progress = 0.78f)

            // Subtitle Generation (.ass)
            val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
            ensurePadaukFont(fontsDir)
            val assFileToUse: File? = if (burnSubtitles) {
                SubtitleGenerator.generateAssFile(
                    transcriptJson = burmeseTranscript,
                    outputAssFile = assSubtitles,
                    placement = subtitlePlacement,
                    fontScale = fontScale,
                    marginV = marginV
                )
            } else null

            // Stage 6: Video Composition
            log("🎬 [6/6] Composing final video with ${soundStyle.replace("_", " ")} audio style...", PipelineStage.COMPOSING_VIDEO, 0.82f)
            ffmpegEngine.renderFinalRecap(
                sourceVideo = downloadRes.localFile,
                dubbedVoiceAudio = voiceAudio,
                assSubtitleFile = assFileToUse,
                outputVideo = finalVideo,
                playbackSpeed = playbackSpeed,
                blurBox = blurBox,
                fontsDir = fontsDir,
                soundStyle = soundStyle
            )
            log("✅ Video composed successfully", progress = 0.96f)

            // Save to Gallery
            val savedUri = exportToGallery(finalVideo, "recap_${System.currentTimeMillis()}.mp4")
            workDir.deleteRecursively()

            logBuffer.add("🎉 Done! Saved to Movies/RecapMaster/")
            _state.value = PipelineState(
                stage = PipelineStage.COMPLETED,
                progress = 1.0f,
                message = "✅ Recap video saved to Gallery!",
                finalVideoUri = savedUri,
                logLines = logBuffer.toList()
            )

        } catch (e: Exception) {
            workDir.deleteRecursively()
            val errMsg = e.message ?: e.javaClass.simpleName
            logBuffer.add("❌ Error: $errMsg")
            _state.value = PipelineState(
                stage = PipelineStage.FAILED,
                progress = 0f,
                message = "Failed at stage: ${_state.value.stage.name}",
                error = errMsg,
                logLines = logBuffer.toList()
            )
        }
    }

    fun reset() {
        logBuffer.clear()
        _state.value = PipelineState()
    }

    private fun ensureWhisperModel(): File {
        val modelFile = File(context.filesDir, "ggml-tiny.bin")
        if (!modelFile.exists() || modelFile.length() < 1000) {
            try {
                context.assets.open("models/ggml-tiny.bin").use { input ->
                    FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                val modelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
                val connection = java.net.URL(modelUrl).openConnection()
                connection.getInputStream().use { input ->
                    FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                }
            }
        }
        return modelFile
    }

    private fun ensurePadaukFont(fontsDir: File) {
        val fontFile = File(fontsDir, "Padauk-Regular.ttf")
        if (!fontFile.exists() || fontFile.length() < 1000) {
            try {
                context.assets.open("fonts/Padauk-Regular.ttf").use { input ->
                    FileOutputStream(fontFile).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun exportToGallery(videoFile: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RecapMaster")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw RuntimeException("Could not create MediaStore entry")

        resolver.openOutputStream(uri).use { outStream ->
            if (outStream == null) throw RuntimeException("Could not open output stream to gallery")
            FileInputStream(videoFile).use { inStream -> inStream.copyTo(outStream) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return uri
    }
}
