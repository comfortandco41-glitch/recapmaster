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
import com.recapmaster.app.data.gemini.GeminiTtsClient
import com.recapmaster.app.data.model.VoiceProfile
import com.recapmaster.app.data.model.VoiceProfiles
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
    DUBBED_READY,        // Video & audio dubbed; waiting for user live watermark & speed tuning
    COMPOSING_VIDEO,
    COMPLETED,
    FAILED
}

data class PipelineState(
    val stage: PipelineStage = PipelineStage.IDLE,
    val progress: Float = 0.0f,
    val stageProgress: Float = 0.0f,
    val message: String = "Ready",
    val finalVideoUri: Uri? = null,
    val dubbedPreviewUri: Uri? = null,
    val previewSubtitleText: String = "",
    val error: String? = null,
    val logLines: List<String> = emptyList()
) {
    val isBusy: Boolean get() = stage != PipelineStage.IDLE &&
            stage != PipelineStage.DUBBED_READY &&
            stage != PipelineStage.COMPLETED &&
            stage != PipelineStage.FAILED
}

data class DialogueSegment(
    val start: Double,
    val end: Double,
    val text: String
)

class RecapPipelineManager(private val context: Context) {

    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state

    private val ffmpegEngine = FFmpegEngine(context)
    private val downloader = UrlDownloader(context, ffmpegEngine)
    private val whisperEngine = WhisperEngine(context)
    private val edgeTtsClient = EdgeTtsClient()
    private val geminiTtsClient = GeminiTtsClient()

    private val logBuffer = mutableListOf<String>()

    // Retained session state for interactive post-dubbing composition
    private var activeWorkDir: File? = null
    private var activeSourceVideo: File? = null
    private var activeVoiceAudio: File? = null
    private var activeBurmeseTranscript: String? = null
    private var activePreviewVideo: File? = null

    private fun log(
        msg: String,
        stage: PipelineStage? = null,
        progress: Float? = null,
        stageProgress: Float? = null
    ) {
        logBuffer.add(msg)
        val newStage = stage ?: _state.value.stage
        _state.value = _state.value.copy(
            stage = newStage,
            progress = progress ?: _state.value.progress,
            stageProgress = stageProgress ?: _state.value.stageProgress,
            message = msg,
            logLines = logBuffer.toList()
        )
    }

    suspend fun previewVoice(
        profile: VoiceProfile,
        geminiApiKey: String,
        outputFile: File
    ): File = withContext(Dispatchers.IO) {
        val sampleText = profile.previewSampleText
        if (profile.isGemini || profile.isGoogleCloud) {
            geminiTtsClient.synthesizeSpeech(
                apiKey = geminiApiKey,
                text = sampleText,
                outputFile = outputFile,
                profile = profile
            )
        } else {
            edgeTtsClient.synthesizeSpeech(
                text = sampleText,
                outputFile = outputFile,
                voiceName = profile.voiceId,
                rate = profile.rate,
                pitch = profile.pitch
            )
        }
    }

    /**
     * Phase 1: Downloads, transcribes, translates, dubs voice, and produces an instant
     * synchronized preview video for interactive live layout tuning in ExoPlayer.
     */
    suspend fun startDubbingPipeline(
        videoUrl: String,
        geminiApiKey: String,
        voiceProfile: VoiceProfile = VoiceProfiles.defaultProfile(),
        dubbingMode: String = "DIALOGUE_SYNC" // "DIALOGUE_SYNC" | "STORY_RECAP"
    ) = withContext(Dispatchers.IO) {
        logBuffer.clear()
        _state.value = PipelineState()

        val workDir = File(context.cacheDir, "job_${System.currentTimeMillis()}").apply { mkdirs() }
        val sourceVideo = File(workDir, "source.mp4")
        val extractedAudio = File(workDir, "audio_16k.wav")
        val voiceAudio = if (voiceProfile.isGemini) File(workDir, "dubbed_voice.wav") else File(workDir, "dubbed_voice.mp3")
        val previewVideo = File(workDir, "dubbed_preview.mp4")

        try {
            // Stage 1: Download
            log("📥 [1/5] Downloading video from URL...", PipelineStage.DOWNLOADING, progress = 0.05f, stageProgress = 0.15f)
            val downloadRes = downloader.downloadUrl(videoUrl, sourceVideo)
            val probedDur = ffmpegEngine.getMediaDurationSeconds(downloadRes.localFile)
            val videoDuration = if (probedDur > 0.5) probedDur else if (downloadRes.durationSeconds > 0) downloadRes.durationSeconds else 60.0
            log("✅ Downloaded: ${downloadRes.title} (${videoDuration.toInt()}s)", progress = 0.18f, stageProgress = 1.0f)

            // Stage 2: Audio Extraction
            log("🎙️ [2/5] Extracting speech audio for Whisper...", PipelineStage.EXTRACTING_AUDIO, progress = 0.22f, stageProgress = 0.15f)
            ffmpegEngine.extractSpeechAudio(downloadRes.localFile, extractedAudio)
            log("✅ Audio extracted (16kHz mono PCM)", progress = 0.32f, stageProgress = 1.0f)

            // Stage 3: On-Device Whisper Transcription
            log("🧠 [3/5] Transcribing dialogue on-device with Whisper (AI multithread accelerated)...", PipelineStage.TRANSCRIBING, progress = 0.35f, stageProgress = 0.20f)
            val modelFile = ensureWhisperModel()
            whisperEngine.loadModel(modelFile)
            val transcriptJson = whisperEngine.transcribeWav(extractedAudio, language = "auto")
            whisperEngine.release()
            log("✅ Transcription complete", progress = 0.50f, stageProgress = 1.0f)

            // Stage 4: Gemini Burmese Translation & Timing Parsing
            log("🌏 [4/5] Translating dialogue segments to Burmese via Gemini...", PipelineStage.TRANSLATING_SCRIPT, progress = 0.53f, stageProgress = 0.20f)
            val geminiClient = GeminiClient(geminiApiKey)
            val burmeseTranscript = geminiClient.translateToBurmese(transcriptJson)
            log("✅ Translation complete", progress = 0.65f, stageProgress = 1.0f)

            val dialogueSegments = parseDialogueSegments(burmeseTranscript)
            // For Exact SRT Sync: 1:1 original timestamps (only merge micro-split fragments < 0.10s)
            val srtSegments = mergeCloseDialogueSegments(dialogueSegments, minGapSeconds = 0.10)
            // For Scene Flow: only merge tightly connected utterances (< 0.20s pause) so scene cuts are never bridged
            val mergedSegments = mergeCloseDialogueSegments(dialogueSegments, minGapSeconds = 0.20)

            // Stage 5: Voice Dubbing (Exact SRT Sync vs Scene Flow vs Story Recap)
            if (dubbingMode == "EXACT_SRT_SYNC" && dialogueSegments.isNotEmpty()) {
                val segmentsToDub = if (srtSegments.isNotEmpty()) srtSegments else dialogueSegments
                log("🔊 [5/5] Synthesizing exact SRT timestamp dubbing (${segmentsToDub.size} segments) with ${voiceProfile.engine.displayName}...", PipelineStage.DUBBING_VOICE, 0.68f, 0.05f)
                synthesizeExactTimestampDubbedAudio(
                    segments = segmentsToDub,
                    videoDuration = videoDuration,
                    voiceProfile = voiceProfile,
                    geminiApiKey = geminiApiKey,
                    workDir = workDir,
                    outputAudioFile = voiceAudio,
                    onProgress = { p, stageP, msg -> log(msg, progress = p, stageProgress = stageP) }
                )
                log("✅ Exact SRT timestamp dubbing generated with dynamic non-overlapping sync", progress = 0.82f, stageProgress = 1.0f)
            } else if (dubbingMode == "DIALOGUE_SYNC" && mergedSegments.isNotEmpty()) {
                log("🔊 [5/5] Synthesizing scene-aligned dialogue (${mergedSegments.size} scenes) with ${voiceProfile.engine.displayName}...", PipelineStage.DUBBING_VOICE, 0.68f, 0.05f)
                synthesizeDialogueDubbedAudio(
                    segments = mergedSegments,
                    videoDuration = videoDuration,
                    voiceProfile = voiceProfile,
                    geminiApiKey = geminiApiKey,
                    workDir = workDir,
                    outputAudioFile = voiceAudio,
                    onProgress = { p, stageP, msg -> log(msg, progress = p, stageProgress = stageP) }
                )
                log("✅ Scene dialogue dubbed and synchronized perfectly with video cuts", progress = 0.82f, stageProgress = 1.0f)
            } else {
                // Continuous Story Recap Narration mode (or fallback when 0 speech segments detected)
                val narrationScript = geminiClient.generateRecapScript(
                    burmeseTranscript = burmeseTranscript,
                    videoDurationSeconds = videoDuration,
                    videoTitle = downloadRes.title
                )
                val dialogueText = extractAllDialogueTexts(burmeseTranscript)
                val scriptToDub = if (narrationScript.isNotBlank() && narrationScript.length >= 40) {
                    narrationScript
                } else if (dialogueText.isNotBlank()) {
                    dialogueText
                } else {
                    narrationScript
                }

                log("🔊 [5/5] Synthesizing continuous recap narration via ${voiceProfile.engine.displayName}...", PipelineStage.DUBBING_VOICE, 0.68f, 0.25f)
                val rawVoice = File(workDir, "raw_narration.${if (voiceProfile.isGemini) "wav" else "mp3"}")
                if (voiceProfile.isGemini || voiceProfile.isGoogleCloud) {
                    try {
                        geminiTtsClient.synthesizeSpeech(
                            apiKey = geminiApiKey,
                            text = scriptToDub,
                            outputFile = rawVoice,
                            profile = voiceProfile
                        )
                        log("✅ Gemini AI voice narration synthesized successfully", progress = 0.78f, stageProgress = 1.0f)
                    } catch (e: Exception) {
                        log("⚠️ Gemini Voice API warning: ${e.message}. Gracefully falling back to Edge TTS...", progress = 0.72f, stageProgress = 0.50f)
                        edgeTtsClient.synthesizeSpeech(
                            text = scriptToDub,
                            outputFile = rawVoice,
                            voiceName = "my-MM-ThihaNeural",
                            rate = voiceProfile.rate.ifBlank { "+0%" },
                            pitch = voiceProfile.pitch.ifBlank { "-2Hz" }
                        )
                        log("✅ Fallback voice narration synthesized via Edge TTS", progress = 0.78f, stageProgress = 1.0f)
                    }
                } else {
                    edgeTtsClient.synthesizeSpeech(
                        text = scriptToDub,
                        outputFile = rawVoice,
                        voiceName = voiceProfile.voiceId.ifBlank { "my-MM-ThihaNeural" },
                        rate = voiceProfile.rate.ifBlank { "+0%" },
                        pitch = voiceProfile.pitch.ifBlank { "-2Hz" }
                    )
                    log("✅ Edge TTS voice narration synthesized", progress = 0.78f, stageProgress = 1.0f)
                }

                // Dynamic Audio-Visual Pacing for Story Recap:
                // - If narration finishes early: slow down tempo to stretch to exact video length
                // - If narration is longer than video: speed up tempo to compress to exact video length
                val rawAudDur = ffmpegEngine.getMediaDurationSeconds(rawVoice)
                if (videoDuration > 1.0 && rawAudDur > 1.0) {
                    val tempoRatio = (rawAudDur / videoDuration).toFloat()
                    val paceMsg = when {
                        tempoRatio < 0.98f -> "Slowing down voice (${String.format(java.util.Locale.US, "%.2f", tempoRatio)}x) to stretch from ${String.format(java.util.Locale.US, "%.1fs", rawAudDur)} to exact video length ${String.format(java.util.Locale.US, "%.1fs", videoDuration)}"
                        tempoRatio > 1.02f -> "Speeding up voice (${String.format(java.util.Locale.US, "%.2f", tempoRatio)}x) to fit ${String.format(java.util.Locale.US, "%.1fs", rawAudDur)} into exact video length ${String.format(java.util.Locale.US, "%.1fs", videoDuration)}"
                        else -> "Narration matches video length perfectly (${String.format(java.util.Locale.US, "%.1fs", videoDuration)})"
                    }
                    log("⚡ Story Recap Pacing: $paceMsg...", progress = 0.81f)
                    ffmpegEngine.fitSegmentExactDuration(rawVoice, voiceAudio, targetDurationSeconds = videoDuration)
                } else if (rawVoice != voiceAudio) {
                    rawVoice.copyTo(voiceAudio, overwrite = true)
                }
                log("✅ Audio and visual duration synchronized to exactly ${String.format(java.util.Locale.US, "%.1fs", videoDuration)}", progress = 0.83f)
            }

            // Prepare instant synced preview video (fast stream copy)
            log("⚡ Preparing synchronized video for live watermark & blur preview...", progress = 0.85f)
            try {
                ffmpegEngine.muxPreviewDubbedVideo(downloadRes.localFile, voiceAudio, previewVideo)
            } catch (e: Throwable) {
                log("ℹ️ Preview mux note: using original video stream for preview", progress = 0.88f)
            }

            // Save active job references for live studio tuning
            activeWorkDir = workDir
            activeSourceVideo = downloadRes.localFile
            activeVoiceAudio = voiceAudio
            activeBurmeseTranscript = burmeseTranscript
            activePreviewVideo = if (previewVideo.exists() && previewVideo.length() > 0) previewVideo else downloadRes.localFile

            log("✨ Video dubbed successfully! Ready for live watermark blur & speed tuning.", PipelineStage.DUBBED_READY, 0.90f, 1.0f)
            _state.value = _state.value.copy(
                stage = PipelineStage.DUBBED_READY,
                progress = 0.90f,
                stageProgress = 1.0f,
                dubbedPreviewUri = Uri.fromFile(activePreviewVideo),
                previewSubtitleText = "",
                message = "✨ Video dubbed! You can now adjust Watermark Blur & Playback Speed."
            )

        } catch (e: Throwable) {
            workDir.deleteRecursively()
            activeWorkDir = null
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

    /**
     * Phase 2: Renders final video using watermark blur box and audio mastering.
     */
    suspend fun generateFinalVideo(
        soundStyle: String = "cinematic_recap",
        burnSubtitles: Boolean = false,
        subtitlePlacement: String = "bottom",
        fontScale: Float = 1.0f,
        marginV: Int = 30,
        playbackSpeed: Float = 1.0f,
        blurBox: BlurBoxConfig = BlurBoxConfig()
    ) = withContext(Dispatchers.IO) {
        val workDir = activeWorkDir
        val sourceVideo = activeSourceVideo
        val voiceAudio = activeVoiceAudio
        val burmeseTranscript = activeBurmeseTranscript

        if (workDir == null || sourceVideo == null || voiceAudio == null) {
            log("❌ Error: No dubbed video session found to generate. Please dub a video first.", PipelineStage.FAILED)
            return@withContext
        }

        try {
            val finalVideo = File(workDir, "final_recap.mp4")

            // Video Composition with FFmpegKit (Subtitles removed)
            log("🎬 Composing final recap video with watermark blur & audio mastering...", PipelineStage.COMPOSING_VIDEO, 0.92f)
            ffmpegEngine.renderFinalRecap(
                sourceVideo = sourceVideo,
                dubbedVoiceAudio = voiceAudio,
                assSubtitleFile = null,
                outputVideo = finalVideo,
                playbackSpeed = playbackSpeed,
                blurBox = blurBox,
                fontsDir = null,
                soundStyle = soundStyle,
                onProgress = { pct, msg ->
                    val overallProgress = 0.92f + (pct * 0.06f)
                    _state.value = _state.value.copy(
                        progress = overallProgress,
                        stageProgress = pct,
                        message = "🎬 $msg"
                    )
                }
            )
            log("✅ Video composed successfully", progress = 0.98f, stageProgress = 1.0f)

            // Save to Gallery MediaStore
            val savedUri = exportToGallery(finalVideo, "recap_${System.currentTimeMillis()}.mp4")
            workDir.deleteRecursively()
            activeWorkDir = null

            logBuffer.add("🎉 Done! Saved to Movies/RecapMaster/")
            _state.value = PipelineState(
                stage = PipelineStage.COMPLETED,
                progress = 1.0f,
                stageProgress = 1.0f,
                message = "✅ Recap video saved to Gallery!",
                finalVideoUri = savedUri,
                dubbedPreviewUri = savedUri,
                logLines = logBuffer.toList()
            )

        } catch (e: Throwable) {
            val errMsg = e.message ?: e.javaClass.simpleName
            logBuffer.add("❌ Error rendering final video: $errMsg")
            _state.value = _state.value.copy(
                stage = PipelineStage.FAILED,
                error = errMsg,
                message = "Composition error: $errMsg",
                logLines = logBuffer.toList()
            )
        }
    }

    /**
     * Backward-compatible convenience method that executes the full pipeline from end to end.
     */
    suspend fun executePipeline(
        videoUrl: String,
        geminiApiKey: String,
        voiceProfile: VoiceProfile = VoiceProfiles.defaultProfile(),
        dubbingMode: String = "DIALOGUE_SYNC",
        soundStyle: String = "cinematic_recap",
        burnSubtitles: Boolean = false,
        subtitlePlacement: String = "bottom",
        fontScale: Float = 1.0f,
        marginV: Int = 30,
        playbackSpeed: Float = 1.0f,
        blurBox: BlurBoxConfig = BlurBoxConfig()
    ) = withContext(Dispatchers.IO) {
        startDubbingPipeline(videoUrl, geminiApiKey, voiceProfile, dubbingMode)
        if (_state.value.stage == PipelineStage.DUBBED_READY) {
            generateFinalVideo(
                soundStyle = soundStyle,
                burnSubtitles = burnSubtitles,
                subtitlePlacement = subtitlePlacement,
                fontScale = fontScale,
                marginV = marginV,
                playbackSpeed = playbackSpeed,
                blurBox = blurBox
            )
        }
    }

    private fun parseDialogueSegments(transcriptJson: String): List<DialogueSegment> {
        val result = mutableListOf<DialogueSegment>()
        try {
            var clean = transcriptJson.trim()
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
            val root = org.json.JSONObject(clean)
            val segs = root.optJSONArray("segments") ?: return emptyList()
            for (i in 0 until segs.length()) {
                val obj = segs.getJSONObject(i)
                val start = obj.optDouble("start", -1.0)
                val end = obj.optDouble("end", -1.0)
                val text = obj.optString("text", "").trim()
                if (start >= 0.0 && end > start && text.isNotBlank()) {
                    result.add(DialogueSegment(start, end, text))
                }
            }
        } catch (_: Throwable) {}
        return result
    }

    private fun mergeCloseDialogueSegments(segments: List<DialogueSegment>, minGapSeconds: Double = 0.6): List<DialogueSegment> {
        if (segments.isEmpty()) return emptyList()
        val merged = mutableListOf<DialogueSegment>()
        var current = segments[0]

        for (i in 1 until segments.size) {
            val next = segments[i]
            val gap = next.start - current.end
            // If gap between sentences is small and combined length isn't too large, merge into one natural sentence
            if (gap in 0.0..minGapSeconds && (current.text.length + next.text.length) < 80) {
                current = DialogueSegment(
                    start = current.start,
                    end = next.end,
                    text = "${current.text} ${next.text}".trim()
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private suspend fun synthesizeDialogueDubbedAudio(
        segments: List<DialogueSegment>,
        videoDuration: Double,
        voiceProfile: VoiceProfile,
        geminiApiKey: String,
        workDir: File,
        outputAudioFile: File,
        onProgress: (overallProgress: Float, stageProgress: Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val segmentsDir = File(workDir, "dialogue_parts").apply { mkdirs() }
        val audioListFile = File(segmentsDir, "concat_list.txt")
        val listEntries = mutableListOf<String>()

        var currentTimeline = 0.0
        val totalSegments = segments.size

        for (i in 0 until totalSegments) {
            val seg = segments[i]
            val targetStart = seg.start
            val nextStart = if (i + 1 < totalSegments) segments[i + 1].start else videoDuration

            // 1. Frame-Accurate Absolute Anchor:
            // Pad silence from currentTimeline up to targetStart so this scene starts at its exact video second
            val preGap = targetStart - currentTimeline
            if (preGap > 0.005) {
                val silenceFile = File(segmentsDir, "silence_${i}.wav")
                ffmpegEngine.writeSilenceWav(silenceFile, preGap)
                listEntries.add("file '${silenceFile.absolutePath}'")
                currentTimeline += preGap
            }

            // 2. Synthesize segment speech
            val rawClip = File(segmentsDir, "raw_${i}.${if (voiceProfile.isGemini) "wav" else "mp3"}")
            val fittedWav = File(segmentsDir, "fitted_${i}.wav")

            val stepStagePct = (i + 1).toFloat() / totalSegments
            val stepOverallProg = 0.68f + (stepStagePct * 0.14f)
            val sceneTimestamp = String.format(java.util.Locale.US, "%.1fs", targetStart)
            onProgress(stepOverallProg, stepStagePct, "🎙️ Dubbing dialogue scene ${i + 1}/$totalSegments at $sceneTimestamp (${(stepStagePct * 100).toInt()}%)...")

            if (voiceProfile.isGemini || voiceProfile.isGoogleCloud) {
                try {
                    geminiTtsClient.synthesizeSpeech(geminiApiKey, seg.text, rawClip, voiceProfile)
                } catch (_: Exception) {
                    edgeTtsClient.synthesizeSpeech(
                        text = seg.text,
                        outputFile = rawClip,
                        voiceName = "my-MM-ThihaNeural",
                        rate = voiceProfile.rate.ifBlank { "+10%" },
                        pitch = voiceProfile.pitch.ifBlank { "-2Hz" }
                    )
                }
            } else {
                edgeTtsClient.synthesizeSpeech(
                    text = seg.text,
                    outputFile = rawClip,
                    voiceName = voiceProfile.voiceId.ifBlank { "my-MM-ThihaNeural" },
                    rate = voiceProfile.rate.ifBlank { "+10%" },
                    pitch = voiceProfile.pitch.ifBlank { "-2Hz" }
                )
            }

            // 3. Strict Ceiling Fit:
            // Available window before the NEXT scene cut (with 100ms conversational safety gap)
            // This guarantees previous narration NEVER speaks over the new scene!
            val availableSceneWindow = kotlin.math.max(0.3, (nextStart - targetStart) - 0.10)
            ffmpegEngine.fitSegmentWithCeiling(rawClip, fittedWav, maxDurationSeconds = availableSceneWindow)
            val finalPartDur = ffmpegEngine.getMediaDurationSeconds(fittedWav)

            listEntries.add("file '${fittedWav.absolutePath}'")
            currentTimeline += finalPartDur
        }

        // 4. Fill remaining tail: if gap > 4.0s, generate concluding narration so voice does not end early
        val postGap = videoDuration - currentTimeline
        if (postGap > 4.0) {
            try {
                val geminiClient = GeminiClient(geminiApiKey)
                val contextText = segments.takeLast(4).joinToString(" ") { it.text }
                val outroScript = geminiClient.generateConcludingNarration(
                    contextDialogue = contextText,
                    gapDurationSeconds = postGap - 0.2,
                    videoTitle = ""
                )
                if (outroScript.isNotBlank()) {
                    val outroRaw = File(segmentsDir, "tail_concluding_raw.${if (voiceProfile.isGemini) "wav" else "mp3"}")
                    val outroFitted = File(segmentsDir, "tail_concluding_fitted.wav")

                    if (voiceProfile.isGemini || voiceProfile.isGoogleCloud) {
                        try {
                            geminiTtsClient.synthesizeSpeech(geminiApiKey, outroScript, outroRaw, voiceProfile)
                        } catch (_: Exception) {
                            edgeTtsClient.synthesizeSpeech(
                                text = outroScript,
                                outputFile = outroRaw,
                                voiceName = "my-MM-ThihaNeural",
                                rate = voiceProfile.rate.ifBlank { "+0%" },
                                pitch = voiceProfile.pitch.ifBlank { "-2Hz" }
                            )
                        }
                    } else {
                        edgeTtsClient.synthesizeSpeech(
                            text = outroScript,
                            outputFile = outroRaw,
                            voiceName = voiceProfile.voiceId.ifBlank { "my-MM-ThihaNeural" },
                            rate = voiceProfile.rate.ifBlank { "+0%" },
                            pitch = voiceProfile.pitch.ifBlank { "-2Hz" }
                        )
                    }

                    ffmpegEngine.fitSegmentExactDuration(outroRaw, outroFitted, targetDurationSeconds = postGap - 0.1)
                    val fittedDur = ffmpegEngine.getMediaDurationSeconds(outroFitted)
                    listEntries.add("file '${outroFitted.absolutePath}'")
                    currentTimeline += fittedDur
                }
            } catch (_: Throwable) {}
        }

        val remainingTail = videoDuration - currentTimeline
        if (remainingTail > 0.02) {
            val tailSilence = File(segmentsDir, "silence_tail.wav")
            ffmpegEngine.writeSilenceWav(tailSilence, remainingTail)
            listEntries.add("file '${tailSilence.absolutePath}'")
            currentTimeline += remainingTail
        }

        // 5. Concatenate all audio and silence files into a single unified synchronized audio track
        audioListFile.writeText(listEntries.joinToString("\n") { it }, Charsets.UTF_8)
        ffmpegEngine.concatAudioFiles(audioListFile, outputAudioFile)
        outputAudioFile
    }

    private suspend fun synthesizeExactTimestampDubbedAudio(
        segments: List<DialogueSegment>,
        videoDuration: Double,
        voiceProfile: VoiceProfile,
        geminiApiKey: String,
        workDir: File,
        outputAudioFile: File,
        onProgress: (overallProgress: Float, stageProgress: Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val segmentsDir = File(workDir, "exact_parts").apply { mkdirs() }
        val audioListFile = File(segmentsDir, "concat_list.txt")
        val listEntries = mutableListOf<String>()

        var currentTimeline = 0.0
        val totalSegments = segments.size

        for (i in 0 until totalSegments) {
            val seg = segments[i]
            val targetStart = seg.start
            val targetDur = (seg.end - seg.start).coerceAtLeast(0.25)

            // 1. Frame-Accurate Absolute Anchor:
            val preGap = targetStart - currentTimeline
            if (preGap > 0.005) {
                val silenceFile = File(segmentsDir, "silence_${i}.wav")
                ffmpegEngine.writeSilenceWav(silenceFile, preGap)
                listEntries.add("file '${silenceFile.absolutePath}'")
                currentTimeline += preGap
            }

            // 2. Synthesize speech for this exact SRT segment
            val rawClip = File(segmentsDir, "raw_${i}.${if (voiceProfile.isGemini) "wav" else "mp3"}")
            val exactWav = File(segmentsDir, "exact_${i}.wav")

            val stepStagePct = (i + 1).toFloat() / totalSegments
            val stepOverallProg = 0.68f + (stepStagePct * 0.14f)
            val timeRange = String.format(java.util.Locale.US, "%.1fs–%.1fs", seg.start, seg.end)
            onProgress(stepOverallProg, stepStagePct, "🎙️ Exact SRT dubbing ${i + 1}/$totalSegments at $timeRange (${(stepStagePct * 100).toInt()}%)...")

            if (voiceProfile.isGemini || voiceProfile.isGoogleCloud) {
                try {
                    geminiTtsClient.synthesizeSpeech(geminiApiKey, seg.text, rawClip, voiceProfile)
                } catch (_: Exception) {
                    edgeTtsClient.synthesizeSpeech(
                        text = seg.text,
                        outputFile = rawClip,
                        voiceName = "my-MM-ThihaNeural",
                        rate = voiceProfile.rate.ifBlank { "+0%" },
                        pitch = voiceProfile.pitch.ifBlank { "-2Hz" }
                    )
                }
            } else {
                edgeTtsClient.synthesizeSpeech(
                    text = seg.text,
                    outputFile = rawClip,
                    voiceName = voiceProfile.voiceId.ifBlank { "my-MM-ThihaNeural" },
                    rate = voiceProfile.rate.ifBlank { "+0%" },
                    pitch = voiceProfile.pitch.ifBlank { "-2Hz" }
                )
            }

            // 3. Time-warp and pad/trim to EXACT targetDur (hard-limited, zero duration leak)
            ffmpegEngine.fitSegmentExactDuration(rawClip, exactWav, targetDur)
            val finalPartDur = ffmpegEngine.getMediaDurationSeconds(exactWav)

            listEntries.add("file '${exactWav.absolutePath}'")
            currentTimeline += finalPartDur
        }

        // 4. Fill tail: if gap > 4.0s, generate concluding narration so voice does not end early
        val postGap = videoDuration - currentTimeline
        if (postGap > 4.0) {
            try {
                val geminiClient = GeminiClient(geminiApiKey)
                val contextText = segments.takeLast(4).joinToString(" ") { it.text }
                val outroScript = geminiClient.generateConcludingNarration(
                    contextDialogue = contextText,
                    gapDurationSeconds = postGap - 0.2,
                    videoTitle = ""
                )
                if (outroScript.isNotBlank()) {
                    val outroRaw = File(segmentsDir, "tail_concluding_raw.${if (voiceProfile.isGemini) "wav" else "mp3"}")
                    val outroFitted = File(segmentsDir, "tail_concluding_fitted.wav")

                    if (voiceProfile.isGemini || voiceProfile.isGoogleCloud) {
                        try {
                            geminiTtsClient.synthesizeSpeech(geminiApiKey, outroScript, outroRaw, voiceProfile)
                        } catch (_: Exception) {
                            edgeTtsClient.synthesizeSpeech(
                                text = outroScript,
                                outputFile = outroRaw,
                                voiceName = "my-MM-ThihaNeural",
                                rate = voiceProfile.rate.ifBlank { "+0%" },
                                pitch = voiceProfile.pitch.ifBlank { "-2Hz" }
                            )
                        }
                    } else {
                        edgeTtsClient.synthesizeSpeech(
                            text = outroScript,
                            outputFile = outroRaw,
                            voiceName = voiceProfile.voiceId.ifBlank { "my-MM-ThihaNeural" },
                            rate = voiceProfile.rate.ifBlank { "+0%" },
                            pitch = voiceProfile.pitch.ifBlank { "-2Hz" }
                        )
                    }

                    ffmpegEngine.fitSegmentExactDuration(outroRaw, outroFitted, targetDurationSeconds = postGap - 0.1)
                    val fittedDur = ffmpegEngine.getMediaDurationSeconds(outroFitted)
                    listEntries.add("file '${outroFitted.absolutePath}'")
                    currentTimeline += fittedDur
                }
            } catch (_: Throwable) {}
        }

        val remainingTail = videoDuration - currentTimeline
        if (remainingTail > 0.02) {
            val tailSilence = File(segmentsDir, "silence_tail.wav")
            ffmpegEngine.writeSilenceWav(tailSilence, remainingTail)
            listEntries.add("file '${tailSilence.absolutePath}'")
            currentTimeline += remainingTail
        }

        // 5. Concatenate all audio and silence files into unified track
        audioListFile.writeText(listEntries.joinToString("\n") { it }, Charsets.UTF_8)
        ffmpegEngine.concatAudioFiles(audioListFile, outputAudioFile)
        outputAudioFile
    }

    private fun extractFirstSubtitleSnippet(burmeseTranscript: String): String {
        try {
            val root = org.json.JSONObject(burmeseTranscript)
            val segs = root.optJSONArray("segments")
            if (segs != null && segs.length() > 0) {
                for (i in 0 until segs.length()) {
                    val t = segs.getJSONObject(i).optString("text", "").trim()
                    if (t.isNotBlank()) return t
                }
            }
        } catch (_: Throwable) {}
        return "ရုပ်ရှင်ဇာတ်လမ်း ပြန်လည်ပြောပြချက် နမူနာစာတန်း"
    }

    private fun extractAllDialogueTexts(burmeseTranscript: String): String {
        try {
            val root = org.json.JSONObject(burmeseTranscript)
            val segs = root.optJSONArray("segments")
            if (segs != null && segs.length() > 0) {
                val list = mutableListOf<String>()
                for (i in 0 until segs.length()) {
                    val t = segs.getJSONObject(i).optString("text", "").trim()
                    if (t.isNotBlank()) list.add(t)
                }
                if (list.isNotEmpty()) return list.joinToString(" ")
            }
        } catch (_: Throwable) {}
        return ""
    }

    fun reset() {
        activeWorkDir?.deleteRecursively()
        activeWorkDir = null
        activeSourceVideo = null
        activeVoiceAudio = null
        activeBurmeseTranscript = null
        activePreviewVideo = null
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
                val connection = (java.net.URL(modelUrl).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 120000
                    instanceFollowRedirects = true
                }
                connection.inputStream.use { input ->
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
