package com.recapmaster.app.ui.screens

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.recapmaster.app.auth.AuthManager
import com.recapmaster.app.auth.UserSubscriptionManager
import com.recapmaster.app.auth.UserSubscription
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
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
import android.graphics.Paint as AndroidPaint
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter as AndroidColorFilter
import android.view.View
import android.view.LayoutInflater
import com.recapmaster.app.R
import com.recapmaster.app.PipelineParams
import com.recapmaster.app.data.model.TtsEngine
import com.recapmaster.app.data.model.VoiceProfile
import com.recapmaster.app.data.model.VoiceProfiles
import com.recapmaster.app.engine.BlurBoxConfig
import com.recapmaster.app.pipeline.PipelineStage
import com.recapmaster.app.pipeline.RecapPipelineManager
import kotlinx.coroutines.launch
import java.io.File

// ── Color Matrix Generator for Real-Time Live Preview ─────────────────────────
private fun buildBypassColorMatrix(brightness: Float, contrast: Float, saturation: Float): AndroidColorMatrix {
    val cm = AndroidColorMatrix()
    cm.setSaturation(saturation.coerceIn(0f, 3f))

    val scale = contrast.coerceIn(0.1f, 3f)
    val translate = (128f * (1f - scale)) + (brightness * 255f)

    val cbMatrix = AndroidColorMatrix(floatArrayOf(
        scale, 0f,    0f,    0f, translate,
        0f,    scale, 0f,    0f, translate,
        0f,    0f,    scale, 0f, translate,
        0f,    0f,    0f,    1f, 0f
    ))

    cbMatrix.postConcat(cm)
    return cbMatrix
}

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

private val PRESET_BORDER_COLORS = listOf(
    "#000000" to "Black",
    "#FFFFFF" to "White",
    "#F59E0B" to "Gold",
    "#06B6D4" to "Cyan",
    "#8B5CF6" to "Purple",
    "#EF4444" to "Red",
    "#10B981" to "Green",
    "#0F172A" to "Navy"
)
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

    // ── Authentication & Subscription State ─────────────────────────────────
    val currentUser by AuthManager.currentUser.collectAsState()
    val subscription by UserSubscriptionManager.subscription.collectAsState()
    val isSubLoading by UserSubscriptionManager.isLoading.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var isSigningIn by remember { mutableStateOf(false) }
    var authMessage by remember { mutableStateOf<String?>(null) }
    var profileMenuExpanded by remember { mutableStateOf(false) }

    val isUserLoggedIn = currentUser != null
    val isExpired = isUserLoggedIn && subscription != null && subscription!!.isExpired

    val webClientId = remember(context) { AuthManager.getWebClientId(context) }
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isSigningIn = false
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (!idToken.isNullOrBlank()) {
                isSigningIn = true
                AuthManager.signInWithGoogleIdToken(
                    idToken = idToken,
                    onSuccess = { user ->
                        isSigningIn = false
                        authMessage = "✅ Logged in as ${user.displayName ?: user.email ?: "User"}"
                    },
                    onError = { err ->
                        isSigningIn = false
                        authMessage = "❌ Sign-in failed: $err"
                    }
                )
            } else {
                authMessage = "⚠️ Google ID token missing. Please re-download google-services.json from Firebase."
            }
        } catch (e: ApiException) {
            authMessage = "⚠️ Google Sign-In (${e.statusCode}): ${e.message}"
        } catch (e: Throwable) {
            authMessage = "❌ Sign-In error: ${e.message}"
        }
    }

    // ── Input state ───────────────────────────────────────────────────────────
    var urlInput          by remember { mutableStateOf("") }
    var selectedVideoUri  by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoName by remember { mutableStateOf<String?>(null) }
    var geminiKey         by remember { mutableStateOf("") }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedVideoUri = uri
            var name = "Selected Video"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val queryName = cursor.getString(nameIndex)
                        if (!queryName.isNullOrBlank()) {
                            name = queryName
                        }
                    }
                }
            } catch (_: Throwable) {}
            selectedVideoName = name
            urlInput = "" // Clear URL input when picking local video
        }
    }

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
    var dubbingMode     by remember { mutableStateOf("STORY_RECAP") } // Dedicated Story Recap Mode

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

    // Copyright Bypass & Anti-Detection FX
    var bypassEnabled     by remember { mutableStateOf(false) }
    var bypassHflip       by remember { mutableStateOf(false) }
    var bypassZoom        by remember { mutableFloatStateOf(0f) }
    var bypassBrightness  by remember { mutableFloatStateOf(0f) }
    var bypassContrast    by remember { mutableFloatStateOf(1.0f) }
    var bypassSaturation  by remember { mutableFloatStateOf(1.0f) }
    var bypassNoise       by remember { mutableIntStateOf(0) }
    var bypassBorderWidth by remember { mutableIntStateOf(0) }
    var bypassBorderColor by remember { mutableStateOf("#000000") }
    var customHexInput    by remember { mutableStateOf("#000000") }

    val isDubbing = pipelineState.stage == PipelineStage.DOWNLOADING ||
            pipelineState.stage == PipelineStage.EXTRACTING_AUDIO ||
            pipelineState.stage == PipelineStage.TRANSCRIBING ||
            pipelineState.stage == PipelineStage.TRANSLATING_SCRIPT ||
            pipelineState.stage == PipelineStage.DUBBING_VOICE

    val isComposing = pipelineState.stage == PipelineStage.COMPOSING_VIDEO
    val isDubbedReady = pipelineState.stage == PipelineStage.DUBBED_READY
    val isCompleted = pipelineState.stage == PipelineStage.COMPLETED
    val isProcessing = isDubbing || isComposing
    val canRenderFinal = isUserLoggedIn && !isExpired && !isComposing
    val inStudioMode = isDubbedReady || isComposing || isCompleted || pipelineState.dubbedPreviewUri != null || pipelineState.finalVideoUri != null
    var studioTab by remember { mutableIntStateOf(0) }
    var isPreviewMinimized by remember { mutableStateOf(false) }

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
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(id = com.recapmaster.app.R.drawable.app_logo),
                            contentDescription = "App Logo",
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Column {
                            Text("RecapMaster", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                            Text("by the AI Buddy", fontSize = 11.sp, color = Cyan, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                actions = {
                    val user = currentUser
                    if (user != null) {
                        Box {
                            IconButton(onClick = { profileMenuExpanded = true }) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isExpired) Red else if (subscription?.isAdmin == true) Amber else Purple,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = user.displayName?.take(1)?.uppercase() ?: user.email?.take(1)?.uppercase() ?: "U",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = profileMenuExpanded,
                                onDismissRequest = { profileMenuExpanded = false },
                                modifier = Modifier.background(BgCardAlt)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(user.displayName ?: "Google User", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                                            Text(user.email ?: "", fontSize = 11.sp, color = TextMuted)
                                            if (subscription != null) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                val statusText = if (subscription!!.isAdmin) "👑 Admin (Unlimited)"
                                                    else if (subscription!!.isExpired) "⛔ Access Expired"
                                                    else "⚡ Time Left: ${subscription!!.formattedRemainingTime}"
                                                val statusColor = if (subscription!!.isExpired) Red else Green
                                                Text(statusText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = statusColor)
                                                Text("Exp: ${subscription!!.formattedExpiry}", fontSize = 9.sp, color = TextMuted)
                                            }
                                        }
                                    },
                                    onClick = {},
                                    leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Cyan) }
                                )
                                HorizontalDivider(color = Border)
                                DropdownMenuItem(
                                    text = { Text("Copy My User ID (UID)", color = Cyan, fontSize = 12.sp) },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Cyan) },
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(user.uid))
                                        authMessage = "📋 User ID copied: ${user.uid}\nSend this to Admin to extend your access."
                                        profileMenuExpanded = false
                                    }
                                )
                                HorizontalDivider(color = Border)
                                DropdownMenuItem(
                                    text = { Text("Sign Out", color = Red, fontSize = 12.sp) },
                                    leadingIcon = { Icon(Icons.Default.ExitToApp, contentDescription = null, tint = Red) },
                                    onClick = {
                                        profileMenuExpanded = false
                                        AuthManager.signOut(context) {
                                            authMessage = "Logged out successfully."
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                if (webClientId.isNullOrBlank()) {
                                    authMessage = "⚠️ Google Web Client ID not found. Please re-download google-services.json from Firebase Console after adding SHA-1."
                                }
                                val client = AuthManager.getGoogleSignInClient(context, webClientId)
                                googleSignInLauncher.launch(client.signInIntent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            if (isSigningIn) {
                                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            } else {
                                Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF4285F4))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sign In", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgCard,
                    titleContentColor = TextPrimary
                )
            )
        },
        bottomBar = {
            if (inStudioMode) {
                Surface(
                    color = BgCard,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        if (pipelineState.stage == PipelineStage.COMPLETED && pipelineState.finalVideoUri != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(pipelineState.finalVideoUri, "video/mp4")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Open in..."))
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Green)
                                ) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open Gallery", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { pipelineManager.reset() },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("New Recap", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (!isUserLoggedIn) {
                                        authMessage = "⚠️ Please sign in with your Google account first."
                                        return@Button
                                    }
                                    if (isExpired) {
                                        authMessage = "❌ Free access expired. Please watch an ad to get +10 minutes of free use."
                                        return@Button
                                    }
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
                                                blurStrength  = blurStrength,
                                                bypassEnabled     = bypassEnabled,
                                                bypassHflip       = bypassHflip,
                                                bypassZoom        = bypassZoom,
                                                bypassBrightness  = bypassBrightness,
                                                bypassContrast    = bypassContrast,
                                                bypassSaturation  = bypassSaturation,
                                                bypassNoise       = bypassNoise,
                                                bypassBorderWidth = bypassBorderWidth,
                                                bypassBorderColor = bypassBorderColor
                                            )
                                        )
                                    } catch (t: Throwable) {
                                        t.printStackTrace()
                                    }
                                },
                                enabled = canRenderFinal,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black, disabledContainerColor = Border)
                            ) {
                                if (isComposing) {
                                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Rendering Final Video (FFmpeg)...", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                } else if (!isUserLoggedIn) {
                                    Icon(Icons.Default.Lock, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("🔐 Sign In Required to Render", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                } else if (isExpired) {
                                    Icon(Icons.Default.TimerOff, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("⛔ Access Expired — Contact Admin", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                } else {
                                    Icon(Icons.Default.MovieCreation, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("🎬 2. Generate Final Video (ရုပ်ရှင်အပြီးသတ်ထုတ်ယူမည်)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = BgDeep
    ) { padding ->
        if (inStudioMode) {
            val parsedBorderColor = remember(bypassBorderColor) {
                try {
                    val cleanHex = if (bypassBorderColor.startsWith("#")) bypassBorderColor else "#$bypassBorderColor"
                    Color(android.graphics.Color.parseColor(cleanHex))
                } catch (_: Throwable) {
                    Color.Black
                }
            }
            val previewBorderDp = if (bypassEnabled && bypassBorderWidth > 0) {
                (bypassBorderWidth / 2.5f).coerceIn(3f, 18f).dp
            } else 0.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Auth notification banner
                AnimatedVisibility(visible = authMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BgCardAlt,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(authMessage ?: "", fontSize = 11.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                            IconButton(onClick = { authMessage = null }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextMuted, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }

                // ── 1. PINNED LIVE PREVIEW DOCK (FIXED AT TOP, NEVER SCROLLS OFF) ──
                Card(
                    shape = RoundedCornerShape(0.dp),
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    elevation = CardDefaults.cardElevation(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Cyan.copy(alpha = 0.15f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = 0.4f))
                                ) {
                                    Text("🎬 Live Studio Preview", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                                if (bypassEnabled) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Green.copy(alpha = 0.15f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Green.copy(alpha = 0.4f))
                                    ) {
                                        Text("🛡️ FX Active", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = { isPreviewMinimized = !isPreviewMinimized },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPreviewMinimized) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                                        contentDescription = if (isPreviewMinimized) "Expand Video" else "Minimize Video",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                OutlinedButton(
                                    onClick = { pipelineManager.reset() },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text("↺ New", fontSize = 10.sp)
                                }
                            }
                        }

                        // Preview Box with Solid Inset Framing Border
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (isPreviewMinimized) 115.dp else 210.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (previewBorderDp > 0.dp) parsedBorderColor else BgCardAlt)
                                .then(
                                    if (previewBorderDp > 0.dp) Modifier.border(1.dp, Border, RoundedCornerShape(10.dp)) else Modifier
                                )
                                .padding(previewBorderDp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(if (previewBorderDp > 0.dp) 3.dp else 10.dp))
                                    .background(Color.Black)
                            ) {
                                if (exoPlayer != null) {
                                    AndroidView(
                                        factory = { ctx ->
                                            val pv = LayoutInflater.from(ctx).inflate(
                                                R.layout.player_view_texture,
                                                null,
                                                false
                                            ) as PlayerView
                                            pv.player = exoPlayer
                                            pv.useController = true
                                            pv
                                        },
                                        update = { pv ->
                                            pv.player = exoPlayer
                                            if (bypassEnabled && (bypassBrightness != 0f || bypassContrast != 1f || bypassSaturation != 1f)) {
                                                val cm = buildBypassColorMatrix(bypassBrightness, bypassContrast, bypassSaturation)
                                                val paint = AndroidPaint().apply {
                                                    colorFilter = AndroidColorFilter(cm)
                                                }
                                                pv.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                                                pv.videoSurfaceView?.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                                            } else {
                                                pv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                                pv.videoSurfaceView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                scaleX = (if (bypassEnabled && bypassHflip) -1f else 1f) * (if (bypassEnabled) 1f + bypassZoom else 1f)
                                                scaleY = if (bypassEnabled) 1f + bypassZoom else 1f
                                            }
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Video player unavailable", color = TextMuted, fontSize = 12.sp)
                                    }
                                }

                                // Active Bypass Badges
                                if (bypassEnabled && !isPreviewMinimized) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color.Black.copy(alpha = 0.70f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = 0.5f)),
                                        modifier = Modifier.padding(6.dp).align(Alignment.TopStart)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text("🛡️", fontSize = 9.sp)
                                            val summary = buildString {
                                                if (bypassHflip) append("Flip ")
                                                if (bypassZoom > 0f) append("Z:+${(bypassZoom * 100).toInt()}% ")
                                                if (bypassBrightness != 0f) append("B:${if (bypassBrightness > 0) "+" else ""}${(bypassBrightness * 100).toInt()}% ")
                                                if (bypassContrast != 1f) append("C:${String.format(java.util.Locale.US, "%.1f", bypassContrast)}x ")
                                                if (bypassSaturation != 1f) append("S:${String.format(java.util.Locale.US, "%.1f", bypassSaturation)}x ")
                                                if (bypassNoise > 0) append("N:${bypassNoise} ")
                                                if (bypassBorderWidth > 0) append("Border:${bypassBorderWidth}px")
                                            }.trim()
                                            Text(if (summary.isEmpty()) "Bypass Active" else summary, color = Cyan, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }

                                // Watermark Blur Box Overlay
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
                                                text = "💧 Blur (${(blurW * 100).toInt()}%×${(blurH * 100).toInt()}%)",
                                                color = Amber,
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 2. STUDIO TABS NAVIGATION ROW ──
                Surface(
                    color = BgCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                ) {
                    ScrollableTabRow(
                        selectedTabIndex = studioTab,
                        containerColor = BgCard,
                        contentColor = Cyan,
                        edgePadding = 8.dp,
                        divider = {}
                    ) {
                        listOf(
                            "🛡️ Anti-Copyright" to 0,
                            "💧 Watermark Blur" to 1,
                            "🎵 Audio & Speed" to 2,
                            "📋 Info & Logs" to 3
                        ).forEach { (title, idx) ->
                            Tab(
                                selected = studioTab == idx,
                                onClick = { studioTab = idx },
                                text = {
                                    Text(
                                        title,
                                        fontSize = 11.sp,
                                        fontWeight = if (studioTab == idx) FontWeight.Bold else FontWeight.Medium,
                                        color = if (studioTab == idx) Cyan else TextSecondary
                                    )
                                }
                            )
                        }
                    }
                }

                // ── 3. SCROLLABLE TAB CONTROLS (DIRECTLY BENEATH PREVIEW) ──
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (studioTab) {
                        0 -> {
                            // ── TAB 0: Anti-Copyright Bypass Controls ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("🛡️ Copyright Bypass & Anti-Detection", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Surface(shape = RoundedCornerShape(4.dp), color = Cyan.copy(alpha = 0.15f)) {
                                            Text("NEW", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                        }
                                    }
                                    Text("Flip, Zoom, Color, Noise & Border ချိန်ညှိမှုများ (Live Preview တိုက်ရိုက်ပြသပါသည်)", fontSize = 10.sp, color = TextMuted)
                                }
                                Switch(
                                    checked = bypassEnabled,
                                    onCheckedChange = { bypassEnabled = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Cyan)
                                )
                            }

                            if (bypassEnabled) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(BgCardAlt)
                                        .border(1.dp, Cyan.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                ) {
                                    // Quick Presets Row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                bypassHflip = true
                                                bypassZoom = 0.05f
                                                bypassBrightness = 0.03f
                                                bypassContrast = 1.08f
                                                bypassSaturation = 1.10f
                                                bypassNoise = 8
                                                bypassBorderWidth = 14
                                                bypassBorderColor = "#000000"
                                                customHexInput = "#000000"
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = 0.5f)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("⚡ Recommended Preset", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                bypassHflip = false
                                                bypassZoom = 0f
                                                bypassBrightness = 0f
                                                bypassContrast = 1.0f
                                                bypassSaturation = 1.0f
                                                bypassNoise = 0
                                                bypassBorderWidth = 0
                                                bypassBorderColor = "#000000"
                                                customHexInput = "#000000"
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("🔄 Reset", fontSize = 10.sp)
                                        }
                                    }

                                    HorizontalDivider(color = Border.copy(alpha = 0.5f))

                                    // 1. Horizontal Flip
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("↔️ Horizontal Flip (ဘယ်ညာလှန်လှည့်ခြင်း)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                            Text("ရုပ်ရှင်ဘယ်ညာပြောင်းပြန်လှန်၍ Video Fingerprint ကို ဖြတ်တောက်မည်", fontSize = 9.sp, color = TextMuted)
                                        }
                                        Switch(
                                            checked = bypassHflip,
                                            onCheckedChange = { bypassHflip = it },
                                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Cyan)
                                        )
                                    }

                                    // 2. Zoom & Crop
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("🔍 Center Zoom & Crop (အနားသတ်ဖြတ်တောက်ပြီးချဲ့ခြင်း)", fontSize = 11.sp, color = TextSecondary)
                                            Text("+${(bypassZoom * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Cyan)
                                        }
                                        Slider(
                                            value = bypassZoom,
                                            onValueChange = { bypassZoom = it },
                                            valueRange = 0f..0.25f,
                                            steps = 24,
                                            colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Border)
                                        )
                                    }

                                    // 3. Brightness Adjustment
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("☀️ Brightness (အလင်းအမှောင်ချိန်ညှိမှု)", fontSize = 11.sp, color = TextSecondary)
                                            Text("${if (bypassBrightness >= 0) "+" else ""}${(bypassBrightness * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Amber)
                                        }
                                        Slider(
                                            value = bypassBrightness,
                                            onValueChange = { bypassBrightness = it },
                                            valueRange = -0.20f..0.20f,
                                            steps = 39,
                                            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Border)
                                        )
                                    }

                                    // 4. Contrast Adjustment
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("🌓 Contrast (အရောင်ပြတ်သားမှု)", fontSize = 11.sp, color = TextSecondary)
                                            Text("${String.format(java.util.Locale.US, "%.2f", bypassContrast)}×", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PurpleLight)
                                        }
                                        Slider(
                                            value = bypassContrast,
                                            onValueChange = { bypassContrast = it },
                                            valueRange = 0.80f..1.30f,
                                            steps = 24,
                                            colors = SliderDefaults.colors(thumbColor = PurpleLight, activeTrackColor = PurpleLight, inactiveTrackColor = Border)
                                        )
                                    }

                                    // 5. Saturation Adjustment
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("🎨 Saturation (အရောင်ရင့်ဖျော့မှု)", fontSize = 11.sp, color = TextSecondary)
                                            Text("${String.format(java.util.Locale.US, "%.2f", bypassSaturation)}×", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Green)
                                        }
                                        Slider(
                                            value = bypassSaturation,
                                            onValueChange = { bypassSaturation = it },
                                            valueRange = 0.80f..1.40f,
                                            steps = 29,
                                            colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green, inactiveTrackColor = Border)
                                        )
                                    }

                                    // 6. Noise / Film Grain
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("📺 Digital Noise Grain (လက်ဗွေဖျက် အစက်အပြောက်)", fontSize = 11.sp, color = TextSecondary)
                                            Text(if (bypassNoise == 0) "Off" else "$bypassNoise", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (bypassNoise > 0) Cyan else TextMuted)
                                        }
                                        Slider(
                                            value = bypassNoise.toFloat(),
                                            onValueChange = { bypassNoise = it.toInt() },
                                            valueRange = 0f..20f,
                                            steps = 19,
                                            colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Border)
                                        )
                                    }

                                    // 7. Border Thickness & Manual Color Selection
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("🖼️ Border Thickness (ဘောင်အကျယ်)", fontSize = 11.sp, color = TextSecondary)
                                            Text(if (bypassBorderWidth == 0) "No Border" else "$bypassBorderWidth px", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Amber)
                                        }
                                        Slider(
                                            value = bypassBorderWidth.toFloat(),
                                            onValueChange = { bypassBorderWidth = it.toInt() },
                                            valueRange = 0f..36f,
                                            steps = 17,
                                            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Border)
                                        )

                                        if (bypassBorderWidth > 0) {
                                            Text("Border Color Palette & Manual Adjustment (ဘောင်အရောင်ရွေးချယ်ရန်)", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Medium)

                                            // Palette circles
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                PRESET_BORDER_COLORS.forEach { (hex, name) ->
                                                    val c = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Throwable) { Color.Black }
                                                    val isSelected = bypassBorderColor.equals(hex, ignoreCase = true)
                                                    Box(
                                                        modifier = Modifier
                                                            .size(30.dp)
                                                            .clip(CircleShape)
                                                            .background(c)
                                                            .border(
                                                                width = if (isSelected) 2.5.dp else 1.dp,
                                                                color = if (isSelected) Cyan else Border,
                                                                shape = CircleShape
                                                            )
                                                            .clickable {
                                                                bypassBorderColor = hex
                                                                customHexInput = hex
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (isSelected) {
                                                            Icon(
                                                                Icons.Default.Check,
                                                                contentDescription = name,
                                                                tint = if (hex == "#FFFFFF") Color.Black else Color.White,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            // Manual Hex Input with live swatch preview
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = customHexInput,
                                                    onValueChange = { input ->
                                                        customHexInput = input
                                                        val clean = if (input.startsWith("#")) input else "#$input"
                                                        if (clean.length == 7) {
                                                            try {
                                                                android.graphics.Color.parseColor(clean)
                                                                bypassBorderColor = clean
                                                            } catch (_: Throwable) {}
                                                        }
                                                    },
                                                    label = { Text("Manual Color Hex (e.g. #FF5722)", fontSize = 10.sp) },
                                                    singleLine = true,
                                                    modifier = Modifier.weight(1f),
                                                    colors = textFieldColors(focusedBorderColor = Cyan),
                                                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                                )

                                                // Swatch box
                                                Box(
                                                    modifier = Modifier
                                                        .size(44.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(parsedBorderColor)
                                                        .border(1.5.dp, Border, RoundedCornerShape(8.dp))
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            // ── TAB 1: Watermark Blur Controls ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
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
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(BgCardAlt)
                                        .border(1.dp, Amber.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                ) {
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
                        }
                        2 -> {
                            // ── TAB 2: Audio & Playback Speed Controls ──
                            Column(
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BgCardAlt)
                                    .border(1.dp, Border, RoundedCornerShape(10.dp))
                                    .padding(14.dp)
                            ) {
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

                                HorizontalDivider(color = Border.copy(alpha = 0.5f))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Video Playback Speed", fontSize = 11.sp, color = TextSecondary)
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
                            }
                        }
                        3 -> {
                            // ── TAB 3: Pipeline Info, Progress & Logs ──
                            val currentStageIndex = when (pipelineState.stage) {
                                PipelineStage.DOWNLOADING -> 1
                                PipelineStage.EXTRACTING_AUDIO -> 2
                                PipelineStage.TRANSCRIBING -> 3
                                PipelineStage.TRANSLATING_SCRIPT -> 4
                                PipelineStage.DUBBING_VOICE -> 5
                                PipelineStage.DUBBED_READY -> 5
                                PipelineStage.COMPOSING_VIDEO -> 6
                                PipelineStage.COMPLETED -> 6
                                else -> 0
                            }
                            val stagePct = (pipelineState.stageProgress * 100).toInt().coerceIn(0, 100)
                            val totalPct = (pipelineState.progress * 100).toInt().coerceIn(0, 100)

                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = BgCardAlt),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Pipeline Status", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (pipelineState.stage == PipelineStage.COMPLETED) GreenBg else PurpleDim.copy(alpha = 0.3f)
                                        ) {
                                            Text(
                                                text = pipelineState.stage.name,
                                                color = if (pipelineState.stage == PipelineStage.COMPLETED) Green else PurpleLight,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    // Stepper pills
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        listOf(
                                            1 to "Download",
                                            2 to "Audio",
                                            3 to "Whisper",
                                            4 to "Gemini",
                                            5 to "Dubbing",
                                            6 to "Render"
                                        ).forEach { (idx, name) ->
                                            val isDone = currentStageIndex > idx || pipelineState.stage == PipelineStage.COMPLETED
                                            val isActive = currentStageIndex == idx
                                            val pillBg = when {
                                                isDone -> Green.copy(alpha = 0.15f)
                                                isActive -> Cyan.copy(alpha = 0.20f)
                                                else -> BgCard
                                            }
                                            val pillColor = when {
                                                isDone -> Green
                                                isActive -> Cyan
                                                else -> TextMuted
                                            }
                                            val pillBorder = when {
                                                isDone -> Green.copy(alpha = 0.5f)
                                                isActive -> Cyan
                                                else -> Border
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = pillBg,
                                                border = androidx.compose.foundation.BorderStroke(1.dp, pillBorder),
                                                modifier = Modifier.padding(horizontal = 1.dp)
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                                                ) {
                                                    Text(
                                                        text = if (isDone) "✓" else if (isActive) "$stagePct%" else "$idx",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = pillColor
                                                    )
                                                    Text(name, fontSize = 8.sp, color = pillColor)
                                                }
                                            }
                                        }
                                    }

                                    Text(pipelineState.message, color = TextPrimary, fontSize = 11.sp)

                                    if (pipelineState.logLines.isNotEmpty()) {
                                        HorizontalDivider(color = Border)
                                        Text("Console Logs:", fontSize = 10.sp, color = TextMuted)
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            pipelineState.logLines.takeLast(6).forEach { line ->
                                                Text(line, fontSize = 9.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedButton(
                                        onClick = { pipelineManager.reset() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Start New Recap (ဒါဘင်အသစ်ပြန်စတင်ရန်)", fontSize = 11.sp)
                                    }
                                }
                            }

                            if (pipelineState.stage == PipelineStage.COMPLETED && pipelineState.finalVideoUri != null) {
                                ElevatedCard(shape = RoundedCornerShape(14.dp), colors = CardDefaults.elevatedCardColors(containerColor = GreenBg)) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Green)
                                            Text("Recap Video Ready in Gallery!", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                        }
                                        Text("Movies/RecapMaster/ တွင် အောင်မြင်စွာ သိမ်းဆည်းပြီးပါပြီ။", fontSize = 11.sp, color = Color(0xFFA7F3D0))
                                    }
                                }
                            }

                            if (pipelineState.stage == PipelineStage.FAILED) {
                                ElevatedCard(shape = RoundedCornerShape(14.dp), colors = CardDefaults.elevatedCardColors(containerColor = RedBg)) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Default.Error, contentDescription = null, tint = Red)
                                            Text("Render Error", fontWeight = FontWeight.Bold, color = Red, fontSize = 13.sp)
                                        }
                                        Text(pipelineState.error ?: "Unknown error", fontSize = 11.sp, color = Color(0xFFFCA5A5), fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

            // ── Auth Status Banner (Notifications / Alerts) ───────────────
            AnimatedVisibility(visible = authMessage != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = BgCardAlt,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(authMessage ?: "", fontSize = 11.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                        IconButton(onClick = { authMessage = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextMuted, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // ── 1. User Not Logged In: Mandatory Login Gate ───────────────
            if (!isUserLoggedIn) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BgCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Purple.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = PurpleLight,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            "🔐 Sign In to Use RecapMaster",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                        Text(
                            "RecapMaster ကို အသုံးပြုရန် Google အကောင့်ဖြင့် ဝင်ရောက်ပေးပါ။ ကြော်ငြာ ၁ ခု ကြည့်ရုံဖြင့် ၁၀ မိနစ် အသုံးပြုခွင့် ရရှိပါမည် (1 Ad = 10 Mins Access)။",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = {
                                if (webClientId.isNullOrBlank()) {
                                    authMessage = "⚠️ Google Web Client ID not found. Please re-download google-services.json from Firebase Console after adding SHA-1."
                                }
                                val client = AuthManager.getGoogleSignInClient(context, webClientId)
                                googleSignInLauncher.launch(client.signInIntent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            if (isSigningIn) {
                                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Signing in...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color(0xFF4285F4))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Sign In with Google (အကောင့်ဝင်မည်)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // ── 2. User Logged In But Expired: Expiration Notice ───────────
            if (isExpired) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BgCard,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Red.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.TimerOff,
                            contentDescription = null,
                            tint = Red,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            "⏳ Access Expired (အချိန်ကုန်ဆုံးသွားပါပြီ)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Red
                        )
                        Text(
                            "သင့်အကောင့်၏ အခမဲ့အသုံးပြုခွင့် ကုန်ဆုံးသွားပါပြီ။ ဆက်လက်အသုံးပြုရန် အောက်ပါ ခလုတ်ကို နှိပ်၍ ကြော်ငြာကြည့်ပြီး +၁၀ မိနစ် ရယူနိုင်ပါသည် (သို့မဟုတ် Admin ထံ သက်တမ်းတိုးခိုင်းနိုင်ပါသည်)။",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )

                        // UID Copy Box
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BgDeep,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Your User ID (UID):", fontSize = 9.sp, color = TextMuted)
                                    Text(currentUser!!.uid, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Cyan)
                                }
                                Button(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(currentUser!!.uid))
                                        authMessage = "📋 User ID copied to clipboard! Send this to Admin to extend your access."
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Cyan.copy(alpha = 0.2f), contentColor = Cyan),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // 🎬 Adsterra Rewarded Sponsor Ad: Free 10-Minute Pass
                        Button(
                            onClick = {
                                val activity = context as? android.app.Activity
                                if (activity != null) {
                                    authMessage = "⏳ Opening sponsor ad..."
                                    com.recapmaster.app.ads.AdsterraAdsManager.showRewardedAd(
                                        activity = activity,
                                        onStatusUpdate = { status -> authMessage = status },
                                        onUserRewarded = {
                                            authMessage = "🎉 Sponsor ad visited! Adding 10 minutes of free access..."
                                            UserSubscriptionManager.grantAdRewardMinutes(currentUser, 10) { success ->
                                                authMessage = if (success) {
                                                    "✅ Success! +10 Minutes added. You can start recap generation now!"
                                                } else {
                                                    "⚠️ Error updating time in Firestore. Please try again."
                                                }
                                            }
                                        },
                                        onDismissed = {
                                            authMessage = "⚠️ Ad closed."
                                        },
                                        onFailed = { err ->
                                            authMessage = "⚠️ Ad error: $err"
                                        }
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800), contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("🎬 Watch Ad to Unlock +10 Mins (ကြော်ငြာကြည့်ပြီး ၁၀ မိနစ်ရယူပါ)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        // Refresh Status Button
                        OutlinedButton(
                            onClick = {
                                UserSubscriptionManager.refresh(currentUser)
                                authMessage = "🔄 Checking updated access status from Firebase..."
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextSecondary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Refresh Status", fontSize = 11.sp)
                        }
                    }
                }
            }

            // ── 3. Active User: Time Remaining Status Banner ───────────────
            if (isUserLoggedIn && subscription != null && !isExpired) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Green.copy(alpha = 0.10f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Green.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                if (subscription!!.isAdmin) Icons.Default.Shield else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Green,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                val titleText = if (subscription!!.isAdmin) "👑 Admin Account: Unlimited Access"
                                    else if (subscription!!.isMaxRewardReached) "⏱️ Free Access: 24h Max Limit Reached"
                                    else "⏱️ Free Access: ${subscription!!.formattedRemainingTime} remaining"
                                Text(titleText, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Green)
                                Text("Expires: ${subscription!!.formattedExpiry}", fontSize = 9.sp, color = TextMuted)
                            }
                        }

                        if (!subscription!!.isAdmin) {
                            val isMaxReached = subscription!!.isMaxRewardReached
                            Button(
                                onClick = {
                                    if (isMaxReached) {
                                        authMessage = "⚠️ Maximum 24-hour limit reached! You already have full access. (အများဆုံး ၂၄ နာရီအထိသာ ကြိုတင်တိုးနိုင်ပါသည်)"
                                        return@Button
                                    }
                                    val activity = context as? android.app.Activity
                                    if (activity != null) {
                                        authMessage = "⏳ Opening sponsor ad..."
                                        com.recapmaster.app.ads.AdsterraAdsManager.showRewardedAd(
                                            activity = activity,
                                            onStatusUpdate = { status -> authMessage = status },
                                            onUserRewarded = {
                                                UserSubscriptionManager.grantAdRewardMinutes(currentUser, 10) { success ->
                                                    authMessage = if (success) "🎉 +10 Minutes added to your time!" else "⚠️ Max 24h limit reached (အများဆုံး ၂၄ နာရီသာ ရရှိနိုင်ပါသည်)."
                                                }
                                            },
                                            onDismissed = {
                                                authMessage = "⚠️ Ad closed."
                                            },
                                            onFailed = { err ->
                                                authMessage = "⚠️ Ad error: $err"
                                            }
                                        )
                                    }
                                },
                                enabled = !isMaxReached,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isMaxReached) Color(0xFF27272A) else Color(0xFFFF9800),
                                    contentColor = if (isMaxReached) Color(0xFF71717A) else Color.Black,
                                    disabledContainerColor = Color(0xFF27272A),
                                    disabledContentColor = Color(0xFF71717A)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(if (isMaxReached) "Max (24h)" else "+10m (Ad)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // ── Card 1: Source ────────────────────────────────────────────
            StudioCard(title = "1. Source Video", icon = Icons.Default.VideoLibrary, accentColor = Purple) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        if (it.isNotBlank()) {
                            selectedVideoUri = null
                            selectedVideoName = null
                        }
                    },
                    placeholder = { Text("https://youtube.com/watch?v=... or https://b23.tv/...", color = TextMuted, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = Purple) },
                    trailingIcon = if (urlInput.isNotEmpty()) {
                        {
                            IconButton(onClick = { urlInput = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                            }
                        }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors(focusedBorderColor = Purple)
                )

                // ── Divider with OR ──────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Border)
                    Text("  OR (သို့မဟုတ်)  ", fontSize = 10.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Border)
                }

                // ── Selected Local Video Card ────────────────────────────────
                if (selectedVideoUri != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Green.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Green.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Green, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = selectedVideoName ?: "Selected Video",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "⚡ Local video ready (No YouTube download or VPN required!)",
                                        fontSize = 10.sp,
                                        color = Green
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    selectedVideoUri = null
                                    selectedVideoName = null
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = TextMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // ── Pick from Gallery / Storage Button ────────────────────────
                OutlinedButton(
                    onClick = { videoPickerLauncher.launch("video/*") },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selectedVideoUri != null) Green else PurpleLight,
                        containerColor = if (selectedVideoUri != null) Green.copy(alpha = 0.06f) else Color.Transparent
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (selectedVideoUri != null) Green.copy(alpha = 0.6f) else Purple.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(
                        if (selectedVideoUri != null) Icons.Default.Check else Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedVideoUri != null) "📁 Change Video (ဖုန်းထဲမှ Video ပြောင်းရန်)" else "📁 Pick Video from Gallery / Storage (ဖုန်းထဲမှ Video ရွေးရန်)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

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

                // 🔑 Clickable link for obtaining Gemini API Key
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Don't have an API Key? (Key မရှိသေးပါက)",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                    TextButton(
                        onClick = {
                            try {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/api-keys"))
                                context.startActivity(browserIntent)
                            } catch (_: Exception) {}
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = Cyan,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Get Free Gemini Key ↗ (အခမဲ့ရယူရန်)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Cyan
                        )
                    }
                }
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

                // Dedicated Story Recap Dubbing Mode Banner
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Purple.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Purple.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = PurpleLight,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "📖 Dubbing Mode: Movie Story Recap (ဇာတ်လမ်းပြန်ပြောခြင်း)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Gemini AI analyzes the entire video, writes a captivating Burmese movie recap story script, and narrates it from start to finish.",
                                fontSize = 10.sp,
                                color = TextSecondary,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

            }

            // ── Step 1: Start Dubbing Button ──────────────────────────────
            val hasSource = urlInput.isNotBlank() || selectedVideoUri != null
            val canStartDubbing = isUserLoggedIn && !isExpired && !isProcessing && hasSource && geminiKey.isNotBlank()

            Button(
                onClick = {
                    if (!isUserLoggedIn) {
                        authMessage = "⚠️ Please sign in with your Google account first."
                        return@Button
                    }
                    if (isExpired) {
                        authMessage = "❌ Free access expired. Please watch an ad to get +10 minutes of free use."
                        return@Button
                    }
                    try {
                        val finalSource = selectedVideoUri?.toString() ?: urlInput.trim()
                        onStartPipeline(
                            PipelineParams(
                                action         = "DUB",
                                url            = finalSource,
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
                enabled = canStartDubbing,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Purple, disabledContainerColor = Border)
            ) {
                if (isDubbing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Dubbing & Transcribing Video...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                } else if (!isUserLoggedIn) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("🔐 Sign In Required to Dub Video (အကောင့်ဝင်ပါ)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                } else if (isExpired) {
                    Icon(Icons.Default.TimerOff, contentDescription = null, tint = Red)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("⛔ Access Expired (ကြော်ငြာကြည့်ပြီး အချိန်ရယူပါ)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                } else {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("🎙️ 1. Start Video Dubbing (ဒါဘင်စတင်ပြုလုပ်မည်)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            // ── Processing Progress Banner ────────────────────────────────
            AnimatedVisibility(visible = isProcessing) {
                val currentStageIndex = when (pipelineState.stage) {
                    PipelineStage.DOWNLOADING -> 1
                    PipelineStage.EXTRACTING_AUDIO -> 2
                    PipelineStage.TRANSCRIBING -> 3
                    PipelineStage.TRANSLATING_SCRIPT -> 4
                    PipelineStage.DUBBING_VOICE -> 5
                    PipelineStage.DUBBED_READY -> 5
                    PipelineStage.COMPOSING_VIDEO -> 6
                    PipelineStage.COMPLETED -> 6
                    else -> 0
                }

                val stagePct = (pipelineState.stageProgress * 100).toInt().coerceIn(0, 100)
                val totalPct = (pipelineState.progress * 100).toInt().coerceIn(0, 100)

                val stageName = when (pipelineState.stage) {
                    PipelineStage.DOWNLOADING -> "Stage 1/5: Downloading Video"
                    PipelineStage.EXTRACTING_AUDIO -> "Stage 2/5: Extracting Audio"
                    PipelineStage.TRANSCRIBING -> "Stage 3/5: Transcribing (Whisper)"
                    PipelineStage.TRANSLATING_SCRIPT -> "Stage 4/5: Translating (Gemini)"
                    PipelineStage.DUBBING_VOICE -> "Stage 5/5: Dubbing Voiceover"
                    PipelineStage.COMPOSING_VIDEO -> "Composing Final Video (FFmpeg)"
                    else -> "Processing..."
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131127)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PurpleDim.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Header with Stage Name & Percentage Badges
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Cyan
                                )
                                Text(stageName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Cyan.copy(alpha = 0.15f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = "Stage $stagePct%",
                                        color = Cyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Purple.copy(alpha = 0.15f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Purple.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = "Total $totalPct%",
                                        color = PurpleLight,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // Active Stage Progress Bar (Prominent)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Active Stage Progress", fontSize = 10.sp, color = TextMuted)
                                Text("$stagePct%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Cyan)
                            }
                            LinearProgressIndicator(
                                progress = { pipelineState.stageProgress },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = Cyan,
                                trackColor = Cyan.copy(alpha = 0.15f)
                            )
                        }

                        // Total Progress Bar
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Total Overall Progress", fontSize = 10.sp, color = TextMuted)
                                Text("$totalPct%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PurpleLight)
                            }
                            LinearProgressIndicator(
                                progress = { pipelineState.progress },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(2.5.dp)),
                                color = Purple,
                                trackColor = PurpleDim.copy(alpha = 0.25f)
                            )
                        }

                        // Multi-Stage Stepper Pills
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf(
                                1 to "Download",
                                2 to "Audio",
                                3 to "Whisper",
                                4 to "Gemini",
                                5 to "Dubbing",
                                6 to "Render"
                            ).forEach { (idx, name) ->
                                val isDone = currentStageIndex > idx || pipelineState.stage == PipelineStage.COMPLETED
                                val isActive = currentStageIndex == idx
                                val pillBg = when {
                                    isDone -> Green.copy(alpha = 0.15f)
                                    isActive -> Cyan.copy(alpha = 0.20f)
                                    else -> BgCard
                                }
                                val pillColor = when {
                                    isDone -> Green
                                    isActive -> Cyan
                                    else -> TextMuted
                                }
                                val pillBorder = when {
                                    isDone -> Green.copy(alpha = 0.5f)
                                    isActive -> Cyan
                                    else -> Border
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = pillBg,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, pillBorder),
                                    modifier = Modifier.padding(horizontal = 1.dp)
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = if (isDone) "✓" else if (isActive) "$stagePct%" else "$idx",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = pillColor
                                        )
                                        Text(name, fontSize = 8.sp, color = pillColor)
                                    }
                                }
                            }
                        }

                        // Current log line & details
                        Text(pipelineState.message, color = TextPrimary, fontSize = 11.sp)

                        if (pipelineState.logLines.size > 1) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                pipelineState.logLines.takeLast(3).forEach { line ->
                                    Text(line, fontSize = 9.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }

            // ── Error Banner (Phase 1) ──
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
                                pipelineState.logLines.takeLast(6).forEach { line ->
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
