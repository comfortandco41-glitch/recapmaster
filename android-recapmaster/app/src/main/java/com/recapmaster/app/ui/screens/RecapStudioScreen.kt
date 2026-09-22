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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import com.recapmaster.app.PipelineParams
import com.recapmaster.app.engine.BlurBoxConfig
import com.recapmaster.app.pipeline.PipelineStage
import com.recapmaster.app.pipeline.RecapPipelineManager

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

    // Voice
    var selectedVoice  by remember { mutableStateOf("my-MM-ThihaNeural") }

    // Sound style
    var soundStyleExpanded by remember { mutableStateOf(false) }
    var soundStyle     by remember { mutableStateOf(SOUND_STYLES[0]) }

    // Subtitle
    var burnSubtitles  by remember { mutableStateOf(true) }
    var subtitlePlacement by remember { mutableStateOf("bottom") }
    var fontScale      by remember { mutableFloatStateOf(1.0f) }
    var marginV        by remember { mutableIntStateOf(30) }

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

    // ExoPlayer for inline preview (safely initialized)
    val exoPlayer = remember {
        try {
            ExoPlayer.Builder(context).build()
        } catch (_: Throwable) {
            null
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer?.release() } }

    val isProcessing = pipelineState.stage != PipelineStage.IDLE &&
            pipelineState.stage != PipelineStage.COMPLETED &&
            pipelineState.stage != PipelineStage.FAILED

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
                    supportingText = { Text("Required for Burmese translation & recap narration", fontSize = 10.sp, color = TextMuted) }
                )
            }

            // ── Card 2: Audio & Dubbing ───────────────────────────────────
            StudioCard(title = "2. Audio & Dubbing Options", icon = Icons.Default.VolumeUp, accentColor = Cyan) {

                // Voice chips
                Text("Burmese Voice (Microsoft Edge TTS)", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedVoice == "my-MM-ThihaNeural",
                        onClick = { selectedVoice = "my-MM-ThihaNeural" },
                        label = { Text("👨 Thiha (Male)", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Purple, selectedLabelColor = Color.White)
                    )
                    FilterChip(
                        selected = selectedVoice == "my-MM-NilarNeural",
                        onClick = { selectedVoice = "my-MM-NilarNeural" },
                        label = { Text("👩 Nilar (Female)", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Purple, selectedLabelColor = Color.White)
                    )
                }

                HorizontalDivider(color = Border)

                // Sound style dropdown
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

                HorizontalDivider(color = Border)

                // Speed slider
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Playback Speed", fontSize = 12.sp, color = TextSecondary)
                    Surface(shape = RoundedCornerShape(6.dp), color = Amber.copy(alpha = 0.15f)) {
                        Text(
                            "${String.format("%.2f", playbackSpeed)}×",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Amber
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
                Text("Audio and video playback are synchronized", fontSize = 10.sp, color = TextMuted)
            }

            // ── Card 3: Subtitle Layout ───────────────────────────────────
            StudioCard(title = "3. Subtitle Layout", icon = Icons.Default.Subtitles, accentColor = Green) {

                // Burn toggle
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Burn Burmese Subtitles (Padauk Font)", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("HarfBuzz OpenType shaping for correct Burmese glyphs", fontSize = 10.sp, color = TextMuted)
                    }
                    Switch(
                        checked = burnSubtitles,
                        onCheckedChange = { burnSubtitles = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Green)
                    )
                }

                AnimatedVisibility(visible = burnSubtitles) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDivider(color = Border)

                        // Placement chips
                        Text("Subtitle Placement / Position", fontSize = 12.sp, color = TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("bottom" to "Bottom", "top" to "Top", "middle" to "Middle").forEach { (value, label) ->
                                FilterChip(
                                    selected = subtitlePlacement == value,
                                    onClick = { subtitlePlacement = value },
                                    label = { Text(label, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Green, selectedLabelColor = Color.Black)
                                )
                            }
                        }

                        // Font scale
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Font Size Scale", fontSize = 12.sp, color = TextSecondary)
                            Text(String.format("%.2f×", fontScale), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Green)
                        }
                        Slider(
                            value = fontScale,
                            onValueChange = { fontScale = it },
                            valueRange = 0.7f..1.6f,
                            steps = 17,
                            colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green, inactiveTrackColor = Border)
                        )
                        Text("1.0 = standard · 1.3 = large headline · 0.8 = compact", fontSize = 10.sp, color = TextMuted)

                        // Vertical margin
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Vertical Edge Margin", fontSize = 12.sp, color = TextSecondary)
                            Text("${marginV}px", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Green)
                        }
                        Slider(
                            value = marginV.toFloat(),
                            onValueChange = { marginV = it.toInt() },
                            valueRange = 10f..120f,
                            steps = 21,
                            colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green, inactiveTrackColor = Border)
                        )
                    }
                }
            }

            // ── Card 4: Logo / Blur Removal ───────────────────────────────
            StudioCard(title = "4. Logo / Watermark Blur Removal", icon = Icons.Default.BlurOn, accentColor = Amber) {

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Enable Logo / Watermark Blur Box", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("Frosted blur over old logos, TV bugs, or hardcoded subs", fontSize = 10.sp, color = TextMuted)
                    }
                    Switch(
                        checked = blurEnabled,
                        onCheckedChange = { blurEnabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Amber)
                    )
                }

                AnimatedVisibility(visible = blurEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDivider(color = Border)

                        // Quick presets
                        Text("Quick Position Presets", fontSize = 12.sp, color = TextSecondary)
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
                                    // Fill empty slot if odd number
                                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }

                        HorizontalDivider(color = Border)
                        Text("Custom Coordinates", fontSize = 12.sp, color = TextSecondary)

                        // X position
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Box Left X", fontSize = 12.sp, color = TextSecondary)
                            Text("${(blurX * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Amber)
                        }
                        Slider(value = blurX, onValueChange = { blurX = it; selectedPreset = null }, valueRange = 0f..1f,
                            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Border))

                        // Y position
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Box Top Y", fontSize = 12.sp, color = TextSecondary)
                            Text("${(blurY * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Amber)
                        }
                        Slider(value = blurY, onValueChange = { blurY = it; selectedPreset = null }, valueRange = 0f..1f,
                            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Border))

                        // Width
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Box Width", fontSize = 12.sp, color = TextSecondary)
                            Text("${(blurW * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Amber)
                        }
                        Slider(value = blurW, onValueChange = { blurW = it; selectedPreset = null }, valueRange = 0.02f..1f,
                            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Border))

                        // Height
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Box Height", fontSize = 12.sp, color = TextSecondary)
                            Text("${(blurH * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Amber)
                        }
                        Slider(value = blurH, onValueChange = { blurH = it; selectedPreset = null }, valueRange = 0.02f..0.5f,
                            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Border))

                        // Blur intensity
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Blur Intensity / Radius", fontSize = 12.sp, color = TextSecondary)
                            Text("$blurStrength", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Amber)
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
            }

            // ── Generate Button ────────────────────────────────────────────
            Button(
                onClick = {
                    // Delegate to MainActivity which starts the ForegroundService.
                    // Heavy work (download, Whisper, FFmpegKit) MUST NOT run in
                    // rememberCoroutineScope — Android 14 kills it when backgrounded.
                    try {
                        onStartPipeline(
                            PipelineParams(
                                url           = urlInput.trim(),
                                geminiKey     = geminiKey.trim(),
                                voice         = selectedVoice,
                                soundStyle    = soundStyle.value,
                                burnSubtitles = burnSubtitles,
                                subPlacement  = subtitlePlacement,
                                fontScale     = fontScale,
                                marginV       = marginV,
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
                enabled = !isProcessing && urlInput.isNotBlank() && geminiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Purple, disabledContainerColor = Border)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Processing On-Device...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                } else {
                    Icon(Icons.Default.Bolt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("⚡ Generate Recap Video", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            // ── Progress Banner ────────────────────────────────────────────
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
                        // Stage log lines
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

            // ── Completion Banner with Inline Player ──────────────────────
            AnimatedVisibility(
                visible = pipelineState.stage == PipelineStage.COMPLETED && pipelineState.finalVideoUri != null
            ) {
                ElevatedCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.elevatedCardColors(containerColor = GreenBg)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Green)
                            Text("Recap Video Ready!", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }

                        // Inline ExoPlayer video preview
                        if (exoPlayer != null) {
                            pipelineState.finalVideoUri?.let { uri ->
                                LaunchedEffect(uri) {
                                    try {
                                        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
                                        exoPlayer.prepare()
                                    } catch (_: Throwable) {}
                                }
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            player = exoPlayer
                                            useController = true
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            }
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
