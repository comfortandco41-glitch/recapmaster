package com.recapmaster.app.ui.screens

import android.content.Intent
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
                                                    else "⚡ Trial: ${subscription!!.remainingDays}d ${subscription!!.remainingHours}h left"
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
                            "RecapMaster ကို အသုံးပြုရန် Google အကောင့်ဖြင့် ဝင်ရောက်ပေးပါ။ အကောင့်အသစ်တိုင်းအတွက် ၇ ရက် အကန့်အသတ်မရှိ (7 Days Unlimited Trial) အလိုအလျောက် ရရှိပါမည်။",
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
                            "⏳ 7-Day Access Expired (၇ ရက် ကုန်ဆုံးသွားပါပြီ)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Red
                        )
                        Text(
                            "သင့်အကောင့်၏ ၇ ရက် အခမဲ့အသုံးပြုခွင့်သည် ${subscription!!.formattedExpiry} တွင် ကုန်ဆုံးသွားပါပြီ။ ဆက်လက်အသုံးပြုနိုင်ရန် Admin ထံသို့ သင့် User ID (UID) ပေးပို့၍ သက်တမ်းတိုးခိုင်းပေးပါ။",
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

                        // 🎬 Start.io Rewarded Ad: Free 20-Minute Pass
                        Button(
                            onClick = {
                                val activity = context as? android.app.Activity
                                if (activity != null) {
                                    authMessage = "⏳ Loading video ad..."
                                    com.recapmaster.app.ads.StartAppAdsManager.showRewardedAd(
                                        activity = activity,
                                        onStatusUpdate = { status -> authMessage = status },
                                        onUserRewarded = {
                                            authMessage = "🎉 Ad complete! Adding 20 minutes of free access..."
                                            UserSubscriptionManager.grantAdRewardMinutes(currentUser, 20) { success ->
                                                authMessage = if (success) {
                                                    "✅ Success! +20 Minutes added. You can start recap generation now!"
                                                } else {
                                                    "⚠️ Error updating time in Firestore. Please try again."
                                                }
                                            }
                                        },
                                        onDismissed = {
                                            authMessage = "⚠️ Ad closed before completion. Please watch the full video to unlock 20 minutes."
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
                            Text("🎬 Watch Ad to Unlock +20 Mins (ကြော်ငြာကြည့်ပြီး ၂၀ မိနစ်ရယူပါ)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                                    else "⏱️ Free Access: ${subscription!!.formattedRemainingTime} remaining"
                                Text(titleText, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Green)
                                Text("Expires: ${subscription!!.formattedExpiry}", fontSize = 9.sp, color = TextMuted)
                            }
                        }

                        if (!subscription!!.isAdmin) {
                            Button(
                                onClick = {
                                    val activity = context as? android.app.Activity
                                    if (activity != null) {
                                        authMessage = "⏳ Loading video ad..."
                                        com.recapmaster.app.ads.StartAppAdsManager.showRewardedAd(
                                            activity = activity,
                                            onStatusUpdate = { status -> authMessage = status },
                                            onUserRewarded = {
                                                UserSubscriptionManager.grantAdRewardMinutes(currentUser, 20) { success ->
                                                    authMessage = if (success) "🎉 +20 Minutes added to your time!" else "⚠️ Error updating time."
                                                }
                                            },
                                            onDismissed = {
                                                authMessage = "⚠️ Ad closed before completion."
                                            },
                                            onFailed = { err ->
                                                authMessage = "⚠️ Ad error: $err"
                                            }
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800), contentColor = Color.Black),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("+20m (Ad)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

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
            val canStartDubbing = isUserLoggedIn && !isExpired && !isProcessing && urlInput.isNotBlank() && geminiKey.isNotBlank()

            Button(
                onClick = {
                    if (!isUserLoggedIn) {
                        authMessage = "⚠️ Please sign in with your Google account first."
                        return@Button
                    }
                    if (isExpired) {
                        authMessage = "❌ Your 7-day access has expired. Please contact admin to extend your account."
                        return@Button
                    }
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
                    Text("⛔ 7-Day Trial Expired (သက်တမ်းတိုးရန် လိုအပ်သည်)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                    val canRenderFinal = isUserLoggedIn && !isExpired && !isComposing

                    Button(
                        onClick = {
                            if (!isUserLoggedIn) {
                                authMessage = "⚠️ Please sign in with your Google account first."
                                return@Button
                            }
                            if (isExpired) {
                                authMessage = "❌ Your 7-day access has expired. Please contact admin to extend your account."
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
                                        blurStrength  = blurStrength
                                    )
                                )
                            } catch (t: Throwable) {
                                t.printStackTrace()
                            }
                        },
                        enabled = canRenderFinal,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black, disabledContainerColor = Border)
                    ) {
                        if (isComposing) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Rendering Final Video (FFmpeg)...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        } else if (!isUserLoggedIn) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🔐 Sign In Required to Render (အကောင့်ဝင်ပါ)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        } else if (isExpired) {
                            Icon(Icons.Default.TimerOff, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("⛔ Access Expired — Contact Admin (သက်တမ်းတိုးပါ)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
