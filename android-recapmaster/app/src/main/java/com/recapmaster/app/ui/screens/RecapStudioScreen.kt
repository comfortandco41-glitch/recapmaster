package com.recapmaster.app.ui.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.border
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import com.recapmaster.app.PipelineParams
import com.recapmaster.app.data.model.TtsEngine
import com.recapmaster.app.data.model.VoiceProfile
import com.recapmaster.app.data.model.VoiceProfiles
import com.recapmaster.app.engine.BlurBoxConfig
import com.recapmaster.app.pipeline.PipelineStage
import com.recapmaster.app.pipeline.RecapPipelineManager
import kotlinx.coroutines.launch
import java.io.File

// ── Design tokens ─────────────────────────────────────────────────────────────
private val BgDeep       = Color(0xFF09090B)
private val BgCard       = Color(0xFF18181B)
private val BgCardAlt    = Color(0xFF1C1C1F)
private val Purple       = Color(0xFFA855F7)
private val PurpleLight  = Color(0xFFC084FC)
private val PurpleDim    = Color(0xFF6D28D9)
private val Cyan         = Color(0xFF38BDF8)
private val Amber        = Color(0xFFFBBF24)
private val Green        = Color(0xFF34D399)
private val GreenBg      = Color(0xFF064E3B)
private val Red          = Color(0xFFF87171)
private val RedBg        = Color(0xFF450A0A)
private val Border       = Color(0xFF27272A)
private val TextPrimary  = Color(0xFFFAFAFA)
private val TextSecondary= Color(0xFFA1A1AA)
private val TextMuted    = Color(0xFF71717A)

// ── Data ──────────────────────────────────────────────────────────────────────
private data class SoundStyleOption(val label: String, val value: String)
private val SOUND_STYLES = listOf(
    SoundStyleOption("🎬 Cinematic Recap (Epic Bass & Presence)",     "cinematic_recap"),
    SoundStyleOption("🎭 Dramatic Suspense (High Tension & Thriller)", "dramatic_suspense"),
    SoundStyleOption("⚡ Energetic Action (Punchy Dynamics)",          "energetic_action"),
    SoundStyleOption("💛 Emotional Warmth (Intimate & Gentle)",        "emotional_warmth"),
    SoundStyleOption("📻 Broadcast Studio (Clean Crisp Voice)",        "broadcast_studio"),
)

private data class BlurPreset(val label: String, val x: Float, val y: Float, val w: Float, val h: Float)
private val BLUR_PRESETS = listOf(
    BlurPreset("Full-Width Bottom (Cover Hardcoded Subs)", 0f,    0.78f, 1f,    0.20f),
    BlurPreset("Full-Width Lower Third Bar",               0f,    0.70f, 1f,    0.28f),
    BlurPreset("Full-Width Top Bar",                       0f,    0f,    1f,    0.16f),
    BlurPreset("Top-Right Logo (Bilibili / TV)",           0.78f, 0.04f, 0.18f, 0.08f),
    BlurPreset("Top-Left Logo (YouTube Icon)",             0.04f, 0.04f, 0.18f, 0.08f),
    BlurPreset("Bottom-Right (Timestamp / Brand)",         0.78f, 0.88f, 0.18f, 0.08f),
    BlurPreset("Bottom-Left (Platform Handle)",            0.04f, 0.88f, 0.18f, 0.08f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapStudioScreen(
    pipelineManager: RecapPipelineManager,
    onStartPipeline: (PipelineParams) -> Unit = {}
) {
    val context = LocalContext.current
    val pipelineState by pipelineManager.state.collectAsState()

    // ── Input state ───────────────────────────────────────────────────────────
    var urlInput       by remember { mutableStateOf("") }
    var geminiKey      by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    // ExoPlayer for inline preview & voice audition (safely initialized)
    val exoPlayer = remember {
        try {
            ExoPlayer.Builder(context).build()
        } catch (t: Throwable) {
            null
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer?.release() } }

    // Voice Profiles & Engines
    var selectedProfile by remember { mutableStateOf(VoiceProfiles.defaultProfile()) }
    var engineFilter    by remember { mutableStateOf<TtsEngine?>(null) }
    var customRateSlider by remember { mutableFloatStateOf(10f) }
    var customPitchSlider by remember { mutableFloatStateOf(-2f) }
    var customPersonaPrompt by remember { mutableStateOf("") }
    var showFineTune    by remember { mutableStateOf(false) }
    var dubbingMode     by remember { mutableStateOf("EXACT_SRT_SYNC") } // "EXACT_SRT_SYNC" | "DIALOGUE_SYNC" | "STORY_RECAP"

    // In-app voice audition state
    var isAuditioning   by remember { mutableStateOf(false) }
    var auditionMessage by remember { mutableStateOf<String?>(null) }
    val previewAudioFile = remember { File(context.cacheDir, "voice_audition_sample.wav") }

    fun auditionVoice(profile: VoiceProfile) {
        if (profile.isGemini && geminiKey.isBlank()) {
            auditionMessage = "⚠️ Enter Gemini API Key in Card 1 to test Gemini AI Voice."
            return
        }
        auditionMessage = null
        isAuditioning = true
        coroutineScope.launch {
            try {
                val resolved = profile.copy(
                    rate = "${if (customRateSlider >= 0) "+" else ""}${customRateSlider.toInt()}%",
                    pitch = "${if (customPitchSlider >= 0) "+" else ""}${customPitchSlider.toInt()}Hz",
                    promptPersona = customPersonaPrompt
                )
                val audioFile = pipelineManager.previewVoice(resolved, geminiKey.trim(), previewAudioFile)
                exoPlayer?.apply {
                    stop()
                    clearMediaItems()
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(audioFile)))
                    prepare()
                    play()
                }
                auditionMessage = "▶️ Playing sample: ${profile.name}"
            } catch (e: Throwable) {
                auditionMessage = "❌ Audition error: ${e.message}"
            } finally {
                isAuditioning = false
            }
        }
    }

    // Sound style
    var soundStyleExpanded by remember { mutableStateOf(false) }
    var soundStyle     by remember { mutableStateOf(SOUND_STYLES[0]) }


    // Blur box
    var blurEnabled    by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf<BlurPreset?>(null) }
    var blurX          by remember { mutableFloatStateOf(0.78f) }
    var blurY          by remember { mutableFloatStateOf(0.04f) }
    var blurW          by remember { mutableFloatStateOf(0.18f) }
    var blurH          by remember { mutableFloatStateOf(0.08f) }
    var blurStrength   by remember { mutableIntStateOf(16) }

    // Speed
    var playbackSpeed  by remember { mutableFloatStateOf(1.0f) }

    val isDubbing = pipelineState.stage == PipelineStage.DOWNLOADING ||
            pipelineState.stage == PipelineStage.EXTRACTING_AUDIO ||
            pipelineState.stage == PipelineStage.TRANSCRIBING ||
            pipelineState.stage == PipelineStage.TRANSLATING_SCRIPT ||
            pipelineState.stage == PipelineStage.DUBBING_VOICE

    val isComposing = pipelineState.stage == PipelineStage.COMPOSING_VIDEO
    val isDubbedReady = pipelineState.stage == PipelineStage.DUBBED_READY
    val isCompleted = pipelineState.stage == PipelineStage.COMPLETED
    val isProcessing = isDubbing || isComposing

    // Automatically load dubbed preview video into player when ready
    LaunchedEffect(pipelineState.dubbedPreviewUri) {
        pipelineState.dubbedPreviewUri?.let { uri ->
            try {
                exoPlayer?.apply {
                    stop()
                    clearMediaItems()
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                    play()
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    // Automatically load final video into player when completed
    LaunchedEffect(pipelineState.finalVideoUri) {
        pipelineState.finalVideoUri?.let { uri ->
            try {
                exoPlayer?.apply {
                    stop()
                    clearMediaItems()
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                    play()
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null, tint = Purple)
                        Column {
                            Text("RecapMaster AI Studio", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                            Text("Burmese Voiceover Recap Engine", fontSize = 10.sp, color = TextMuted)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgCard,
                    titleContentColor = TextPrimary
                )
            )
        },
        containerColor = BgDeep
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // ── Card 1: Source ────────────────────────────────────────────
            StudioCard(title = "1. Source Video", icon = Icons.Default.VideoLibrary, accentColor = Purple) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("https://youtube.com/watch?v=... or https://b23.tv/...", color = TextMuted, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = Purple) },
                    trailingIcon = if (urlInput.isNotEmpty()) {
                        { IconButton(onClick = { urlInput = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted) } }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors(focusedBorderColor = Purple)
                )

                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    placeholder = { Text("Google Gemini API Key (AIzaSy...)", color = TextMuted, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = Cyan) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors(focusedBorderColor = Cyan),
                    supportingText = { Text("Required for Burmese translation, narration script & Gemini AI Voice", fontSize = 10.sp, color = TextMuted) }
                )
            }

            // ── Card 2: Voice Profile & Dubbing ───────────────────────────
            StudioCard(title = "2. Voice Profile & Dubbing Options", icon = Icons.Default.RecordVoiceOver, accentColor = Cyan) {

                // TTS Engine filter chips
                Text("Select TTS Engine / Provider", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = engineFilter == null,
                        onClick = { engineFilter = null },
                        label = { Text("All", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan, selectedLabelColor = Color.Black)
                    )
                    FilterChip(
                        selected = engineFilter == TtsEngine.EDGE_TTS,
                        onClick = { engineFilter = TtsEngine.EDGE_TTS },
                        label = { Text("Edge (Free)", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Purple, selectedLabelColor = Color.White)
                    )
                    FilterChip(
                        selected = engineFilter == TtsEngine.GEMINI_VOICE,
                        onClick = { engineFilter = TtsEngine.GEMINI_VOICE },
                        label = { Text("Gemini AI", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Amber, selectedLabelColor = Color.Black)
                    )
                    FilterChip(
                        selected = engineFilter == TtsEngine.GOOGLE_CLOUD_TTS,
                        onClick = { engineFilter = TtsEngine.GOOGLE_CLOUD_TTS },
                        label = { Text("Google Cloud", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Green, selectedLabelColor = Color.Black)
                    )
                }

                HorizontalDivider(color = Border)

                // Voice Profile Cards
                Text("Choose Voice Persona / Speaker", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                val filteredProfiles = remember(engineFilter) {
                    if (engineFilter == null) VoiceProfiles.PRESETS else VoiceProfiles.PRESETS.filter { it.engine == engineFilter }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    filteredProfiles.forEach { profile ->
                        val isSelected = selectedProfile.id == profile.id
                        val badgeColor = when (profile.engine) {
                            TtsEngine.EDGE_TTS -> Purple
                            TtsEngine.GEMINI_VOICE -> Amber
                            TtsEngine.GOOGLE_CLOUD_TTS -> Green
                        }

                        Surface(
                            onClick = {
                                selectedProfile = profile
                                customRateSlider = profile.rate.replace("%", "").replace("+", "").toFloatOrNull() ?: 0f
                                customPitchSlider = profile.pitch.replace("Hz", "").replace("+", "").toFloatOrNull() ?: 0f
                                customPersonaPrompt = profile.promptPersona
                                auditionMessage = null
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) BgCardAlt else BgCard,
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) badgeColor else Border
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(if (profile.gender == "Male") "👨" else "👩", fontSize = 20.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(profile.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary)
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = badgeColor.copy(alpha = 0.2f)
                                        ) {
                                            Text(
                                                profile.engine.badge,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = badgeColor,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Text(profile.subtitle, fontSize = 11.sp, color = TextSecondary)
                                    if (profile.description.isNotBlank()) {
                                        Text(profile.description, fontSize = 10.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedProfile = profile
                                        customRateSlider = profile.rate.replace("%", "").replace("+", "").toFloatOrNull() ?: 0f
                                        customPitchSlider = profile.pitch.replace("Hz", "").replace("+", "").toFloatOrNull() ?: 0f
                                        customPersonaPrompt = profile.promptPersona
                                        auditionMessage = null
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = badgeColor)
                                )
                            }
                        }
                    }
                }

                // In-App Audition / Voice Preview Button
                OutlinedButton(
                    onClick = { auditionVoice(selectedProfile) },
                    enabled = !isAuditioning,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                ) {
                    if (isAuditioning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Cyan, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Synthesizing Voice Sample...", fontSize = 12.sp)
                    } else {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("🔊 Preview Voice Sample (နမူနာအသံနားဆင်ရန်)", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }

                auditionMessage?.let { msg ->
                    Text(msg, fontSize = 11.sp, color = if (msg.startsWith("❌") || msg.startsWith("⚠️")) Red else Green)
                }

                // Fine-tuning collapsible toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Border.copy(alpha = 0.3f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        Text("Voice Speed, Pitch & Persona Tuning", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                    }
                    IconButton(onClick = { showFineTune = !showFineTune }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            if (showFineTune) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = TextSecondary
                        )
                    }
                }

                AnimatedVisibility(visible = showFineTune) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Rate Slider
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Voice Speech Rate", fontSize = 11.sp, color = TextSecondary)
                            Text("${if (customRateSlider >= 0) "+" else ""}${customRateSlider.toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Cyan)
                        }
                        Slider(
                            value = customRateSlider,
                            onValueChange = { customRateSlider = it },
                            valueRange = -30f..50f,
                            steps = 15,
                            colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Border)
                        )

                        // Pitch Slider
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Voice Pitch Adjustment", fontSize = 11.sp, color = TextSecondary)
                            Text("${if (customPitchSlider >= 0) "+" else ""}${customPitchSlider.toInt()}Hz", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PurpleLight)
                        }
                        Slider(
                            value = customPitchSlider,
                            onValueChange = { customPitchSlider = it },
                            valueRange = -10f..10f,
                            steps = 19,
                            colors = SliderDefaults.colors(thumbColor = PurpleLight, activeTrackColor = PurpleLight, inactiveTrackColor = Border)
                        )

                        // If Gemini Voice: Persona Prompt
                        if (selectedProfile.isGemini) {
                            OutlinedTextField(
                                value = customPersonaPrompt,
                                onValueChange = { customPersonaPrompt = it },
                                placeholder = { Text("Gemini Narration Tone (e.g. Deep thriller suspense narrator)", fontSize = 11.sp, color = TextMuted) },
                                label = { Text("Gemini AI Voice Persona Prompt", fontSize = 10.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = textFieldColors(focusedBorderColor = Amber),
                                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = Border)

                Text("Dubbing Timing & Scene Alignment Mode", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = dubbingMode == "EXACT_SRT_SYNC",
                        onClick = { dubbingMode = "EXACT_SRT_SYNC" },
                        label = { Text("🎯 Exact SRT Sync", fontSize = 10.sp, fontWeight = if (dubbingMode == "EXACT_SRT_SYNC") FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan, selectedLabelColor = Color.Black),
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = dubbingMode == "DIALOGUE_SYNC",
                        onClick = { dubbingMode = "DIALOGUE_SYNC" },
                        label = { Text("🎬 Scene Flow", fontSize = 10.sp, fontWeight = if (dubbingMode == "DIALOGUE_SYNC") FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Green, selectedLabelColor = Color.Black),
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = dubbingMode == "STORY_RECAP",
                        onClick = { dubbingMode = "STORY_RECAP" },
                        label = { Text("📖 Story Recap", fontSize = 10.sp, fontWeight = if (dubbingMode == "STORY_RECAP") FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Purple, selectedLabelColor = Color.White),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = when (dubbingMode) {
                        "EXACT_SRT_SYNC" -> "🎯 1:1 Exact SRT sync: Burmese audio is tempo-fitted to match the EXACT duration of each original dialogue line. Starts and ends at exact timestamps."
                        "DIALOGUE_SYNC" -> "🎬 Scene Flow: Merges close utterances for natural conversational cadence while preserving scene pauses."
                        else -> "📖 Story Recap: Continuous narrator story recap explaining the movie from start to end."
                    },
                    fontSize = 10.sp,
                    color = TextMuted
                )

            }

            // ── Step 1: Start Dubbing Button ──────────────────────────────
            Button(
                onClick = {
                    try {
                        onStartPipeline(
                            PipelineParams(
                                action         = "DUB",
                                url            = urlInput.trim(),
                                geminiKey      = geminiKey.trim(),
                                voiceProfileId = selectedProfile.id,
                                ttsEngine      = selectedProfile.engine.id,
                                voice          = selectedProfile.voiceId,
                                voiceRate      = "${if (customRateSlider >= 0) "+" else ""}${customRateSlider.toInt()}%",
                                voicePitch     = "${if (customPitchSlider >= 0) "+" else ""}${customPitchSlider.toInt()}Hz",
                                voicePrompt    = customPersonaPrompt,
                                dubbingMode    = dubbingMode
                            )
                        )
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                },
                enabled = !isProcessing && urlInput.isNotBlank() && geminiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Purple, disabledContainerColor = Border)
            ) {
                if (isDubbing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Dubbing & Transcribing Video...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                } else {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("🎙️ 1. Start Video Dubbing (ဒါဘင်စတင်ပြုလုပ်မည်)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            // ── Processing Progress Banner ────────────────────────────────
            AnimatedVisibility(visible = isProcessing) {
                ElevatedCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF1E1B4B))) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("🎬 ${pipelineState.message}", color = PurpleLight, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        LinearProgressIndicator(
                            progress = { pipelineState.progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Purple,
                            trackColor = PurpleDim.copy(alpha = 0.3f)
                        )
                        if (pipelineState.logLines.size > 1) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                pipelineState.logLines.takeLast(5).forEach { line ->
                                    Text(line, fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }

            // ── Step 2: Post-Dubbing Interactive Live Studio (Subtitles & Watermark Blur) ──
            AnimatedVisibility(visible = isDubbedReady || isComposing || isCompleted) {
                StudioCard(
                    title = "2. Live Studio: Watermark & Video Tuning (Live Preview)",
                    icon = Icons.Default.Preview,
                    accentColor = Cyan
                ) {
                    Text(
                        "Video & voice are dubbed! Adjust your watermark blur box and playback speed in real-time on the video preview below before generating the final video.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )

                    // ── INTERACTIVE LIVE PREVIEW BOX WITH REAL-TIME OVERLAYS ──
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                    ) {
                        if (exoPlayer != null) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = exoPlayer
                                        useController = true
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Video player unavailable", color = TextMuted, fontSize = 12.sp)
                            }
                        }

                        // Real-time Watermark Blur Box Overlay
                        if (blurEnabled) {
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val leftPx = maxWidth * blurX
                                val topPx = maxHeight * blurY
                                val widthPx = maxWidth * blurW
                                val heightPx = maxHeight * blurH

                                Box(
                                    modifier = Modifier
                                        .offset(x = leftPx, y = topPx)
                                        .size(width = widthPx, height = heightPx)
                                        .background(Amber.copy(alpha = 0.3f))
                                        .border(1.5.dp, Amber, RoundedCornerShape(4.dp))
                                        .padding(4.dp)
                                ) {
                                    Text(
                                        text = "💧 Blur Area (${(blurW * 100).toInt()}% × ${(blurH * 100).toInt()}%)",
                                        color = Amber,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Border)

                    // ── Logo / Watermark Blur Section ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Enable Logo / Watermark Blur Box", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            Text("Delogo frosted blur over old channel logos & TV bugs", fontSize = 10.sp, color = TextMuted)
                        }
                        Switch(
                            checked = blurEnabled,
                            onCheckedChange = { blurEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Amber)
                        )
                    }

                    if (blurEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Presets
                            Text("Quick Position Presets", fontSize = 11.sp, color = TextSecondary)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BLUR_PRESETS.chunked(2).forEach { row ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        row.forEach { preset ->
                                            FilterChip(
                                                selected = selectedPreset == preset,
                                                onClick = {
                                                    selectedPreset = preset
                                                    blurX = preset.x; blurY = preset.y
                                                    blurW = preset.w; blurH = preset.h
                                                },
                                                label = { Text(preset.label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                modifier = Modifier.weight(1f),
                                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Amber, selectedLabelColor = Color.Black)
                                            )
                                        }
                                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }

                            // X & Y Sliders
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Box Left X Position", fontSize = 11.sp, color = TextSecondary)
                                Text("${(blurX * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Amber)
                            }
                            Slider(value = blurX, onValueChange = { blurX = it; selectedPreset = null }, valueRange = 0f..1f,
                                colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Border))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Box Top Y Position", fontSize = 11.sp, color = TextSecondary)
                                Text("${(blurY * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Amber)
                            }
                            Slider(value = blurY, onValueChange = { blurY = it; selectedPreset = null }, valueRange = 0f..1f,
                                colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Border))

                            // Width & Height Sliders
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Box Width", fontSize = 11.sp, color = TextSecondary)
                                Text("${(blurW * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Amber)
                            }
                            Slider(value = blurW, onValueChange = { blurW = it; selectedPreset = null }, valueRange = 0.02f..1f,
                                colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Border))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Box Height", fontSize = 11.sp, color = TextSecondary)
                                Text("${(blurH * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Amber)
                            }
                            Slider(value = blurH, onValueChange = { blurH = it; selectedPreset = null }, valueRange = 0.02f..0.5f,
                                colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Border))

                            // Blur Intensity
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Blur Radius / Intensity", fontSize = 11.sp, color = TextSecondary)
                                Text("$blurStrength", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Amber)
                            }
                            Slider(
                                value = blurStrength.toFloat(),
                                onValueChange = { blurStrength = it.toInt() },
                                valueRange = 5f..50f,
                                steps = 44,
                                colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Border)
                            )
                        }
                    }

                    HorizontalDivider(color = Border)

                    // ── Sound Design & Video Speed ──
                    Text("Sound Design & Mastering Preset", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                    ExposedDropdownMenuBox(expanded = soundStyleExpanded, onExpandedChange = { soundStyleExpanded = it }) {
                        OutlinedTextField(
                            value = soundStyle.label,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = soundStyleExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            colors = textFieldColors(focusedBorderColor = Cyan),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = TextPrimary)
                        )
                        ExposedDropdownMenu(
                            expanded = soundStyleExpanded,
                            onDismissRequest = { soundStyleExpanded = false },
                            modifier = Modifier.background(BgCard)
                        ) {
                            SOUND_STYLES.forEach { style ->
                                DropdownMenuItem(
                                    text = { Text(style.label, fontSize = 13.sp, color = TextPrimary) },
                                    onClick = { soundStyle = style; soundStyleExpanded = false }
                                )
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Playback Speed", fontSize = 11.sp, color = TextSecondary)
                        Surface(shape = RoundedCornerShape(6.dp), color = Amber.copy(alpha = 0.15f)) {
                            Text(
                                "${String.format("%.2f", playbackSpeed)}×",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Amber
                            )
                        }
                    }
                    Slider(
                        value = playbackSpeed,
                        onValueChange = { playbackSpeed = it },
                        valueRange = 0.5f..2.0f,
                        steps = 5,
                        colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Border)
                    )

                    HorizontalDivider(color = Border)

                    // ── GENERATE FINAL VIDEO BUTTON ──
                    Button(
                        onClick = {
                            try {
                                exoPlayer?.pause()
                                onStartPipeline(
                                    PipelineParams(
                                        action        = "COMPOSE",
                                        soundStyle    = soundStyle.value,
                                        burnSubtitles = false,
                                        speed         = playbackSpeed,
                                        blurEnabled   = blurEnabled,
                                        blurX         = blurX, blurY = blurY,
                                        blurW         = blurW, blurH = blurH,
                                        blurStrength  = blurStrength
                                    )
                                )
                            } catch (t: Throwable) {
                                t.printStackTrace()
                            }
                        },
                        enabled = !isComposing,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black, disabledContainerColor = Border)
                    ) {
                        if (isComposing) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Rendering Final Video (FFmpeg)...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        } else {
                            Icon(Icons.Default.MovieCreation, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🎬 2. Generate Final Video (ရုပ်ရှင်အပြီးသတ်ထုတ်ယူမည်)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }

            // ── Completion Banner with Gallery Export Info ────────────────
            AnimatedVisibility(
                visible = pipelineState.stage == PipelineStage.COMPLETED && pipelineState.finalVideoUri != null
            ) {
                ElevatedCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.elevatedCardColors(containerColor = GreenBg)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Green)
                            Text("Recap Video Ready!", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }

                        Text(
                            "Video saved to Movies/RecapMaster/ in your Gallery. Temp files cleaned up.",
                            fontSize = 11.sp,
                            color = Color(0xFFA7F3D0)
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Open in external player
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(pipelineState.finalVideoUri, "video/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Open in..."))
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Green)
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open In...", fontSize = 13.sp)
                            }

                            // Make another
                            Button(
                                onClick = { pipelineManager.reset() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Purple)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New Recap", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // ── Error Banner ───────────────────────────────────────────────
            AnimatedVisibility(visible = pipelineState.stage == PipelineStage.FAILED) {
                ElevatedCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.elevatedCardColors(containerColor = RedBg)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = Red)
                            Text("Error Occurred", fontWeight = FontWeight.Bold, color = Red, fontSize = 14.sp)
                        }
                        Text(pipelineState.error ?: "Unknown error", fontSize = 12.sp, color = Color(0xFFFCA5A5), fontFamily = FontFamily.Monospace)

                        // Full log
                        if (pipelineState.logLines.isNotEmpty()) {
                            HorizontalDivider(color = Color(0xFF7F1D1D))
                            Text("Processing Log:", fontSize = 10.sp, color = Red.copy(alpha = 0.7f))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                pipelineState.logLines.forEach { line ->
                                    Text(line, fontSize = 10.sp, color = Color(0xFFFCA5A5).copy(alpha = 0.8f), fontFamily = FontFamily.Monospace)
                                }
                            }
                        }

                        Button(
                            onClick = { pipelineManager.reset() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF991B1B))
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Try Again", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Bottom spacing
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Shared composables ─────────────────────────────────────────────────────────

@Composable
private fun StudioCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = BgCard),
        elevation = CardDefaults.elevatedCardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp))
                }
                Text(title, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 14.sp)
            }
            content()
        }
    }
}

@Composable
private fun textFieldColors(focusedBorderColor: Color) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = focusedBorderColor,
    unfocusedBorderColor = Border,
    cursorColor = focusedBorderColor,
    focusedContainerColor = BgCardAlt,
    unfocusedContainerColor = BgCardAlt
)
