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
    val error: String? = null
)

class RecapPipelineManager(private val context: Context) {

    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state

    private val downloader = UrlDownloader()
    private val ffmpegEngine = FFmpegEngine(context)
    private val whisperEngine = WhisperEngine(context)
    private val edgeTtsClient = EdgeTtsClient()

    suspend fun executePipeline(
        videoUrl: String,
        geminiApiKey: String,
        voiceName: String = "my-MM-ThihaNeural",
        playbackSpeed: Float = 1.0f,
        blurBox: BlurBoxConfig = BlurBoxConfig(),
        subtitlePlacement: String = "bottom",
        fontScale: Float = 1.0f
    ) = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "job_${System.currentTimeMillis()}").apply { mkdirs() }
        val sourceVideo = File(workDir, "source.mp4")
        val extractedAudio = File(workDir, "audio_16k.wav")
        val voiceAudio = File(workDir, "dubbed_voice.mp3")
        val assSubtitles = File(workDir, "subtitles.ass")
        val finalVideo = File(workDir, "final_recap.mp4")

        try {
            // Stage 1: Download YouTube or Bilibili video
            updateState(PipelineStage.DOWNLOADING, 0.15f, "Downloading video from URL...")
            val downloadRes = downloader.downloadUrl(videoUrl, sourceVideo)

            // Stage 2: Audio Extraction for Whisper
            updateState(PipelineStage.EXTRACTING_AUDIO, 0.30f, "Extracting speech audio for transcription...")
            ffmpegEngine.extractSpeechAudio(downloadRes.localFile, extractedAudio)

            // Stage 3: On-Device Whisper Transcription
            updateState(PipelineStage.TRANSCRIBING, 0.45f, "Transcribing dialogue on-device with Whisper...")
            val modelFile = ensureWhisperModel()
            whisperEngine.loadModel(modelFile)
            val transcriptJson = whisperEngine.transcribeWav(extractedAudio, language = "auto")
            whisperEngine.release()

            // Stage 4: Gemini Burmese Translation & Recap Script
            updateState(PipelineStage.TRANSLATING_SCRIPT, 0.60f, "Translating & generating Burmese movie recap narration...")
            val geminiClient = GeminiClient(geminiApiKey)
            val burmeseTranscript = geminiClient.translateToBurmese(transcriptJson)
            val narrationScript = geminiClient.generateRecapScript(burmeseTranscript)

            // Stage 5: Edge TTS Burmese Voice Dubbing
            updateState(PipelineStage.DUBBING_VOICE, 0.75f, "Synthesizing Burmese narration voiceover ($voiceName)...")
            edgeTtsClient.synthesizeSpeech(
                text = narrationScript,
                outputFile = voiceAudio,
                voiceName = voiceName
            )

            // Subtitle Generation (.ass)
            val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
            ensurePadaukFont(fontsDir)
            SubtitleGenerator.generateAssFile(
                transcriptJson = burmeseTranscript,
                outputAssFile = assSubtitles,
                placement = subtitlePlacement,
                fontScale = fontScale
            )

            // Stage 6: Video Composition (Pure Dubbed Audio + Blur + Speed + Subtitles)
            updateState(PipelineStage.COMPOSING_VIDEO, 0.90f, "Composing final video with visual effects & pure dubbed audio...")
            ffmpegEngine.renderFinalRecap(
                sourceVideo = downloadRes.localFile,
                dubbedVoiceAudio = voiceAudio,
                assSubtitleFile = assSubtitles,
                outputVideo = finalVideo,
                playbackSpeed = playbackSpeed,
                blurBox = blurBox,
                fontsDir = fontsDir
            )

            // Save to Public Android Gallery / Movies folder
            val savedUri = exportToGallery(finalVideo, "recap_${System.currentTimeMillis()}.mp4")

            // Automatic Post-Download Cleanup: delete source video and temp workDir
            workDir.deleteRecursively()

            _state.value = PipelineState(
                stage = PipelineStage.COMPLETED,
                progress = 1.0f,
                message = "✅ Video rendered and saved to Gallery! Source & temporary files purged.",
                finalVideoUri = savedUri
            )

        } catch (e: Exception) {
            workDir.deleteRecursively()
            _state.value = PipelineState(
                stage = PipelineStage.FAILED,
                progress = 0f,
                message = "Failed: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }

    private fun updateState(stage: PipelineStage, progress: Float, msg: String) {
        _state.value = PipelineState(stage = stage, progress = progress, message = msg)
    }

    private fun ensureWhisperModel(): File {
        val modelFile = File(context.filesDir, "ggml-tiny.bin")
        if (!modelFile.exists() || modelFile.length() < 1000) {
            // Check if bundled in assets
            try {
                context.assets.open("models/ggml-tiny.bin").use { input ->
                    FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                // If not pre-bundled in APK assets, download tiny model on first run
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
            FileInputStream(videoFile).use { inStream ->
                inStream.copyTo(outStream)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return uri
    }
}
