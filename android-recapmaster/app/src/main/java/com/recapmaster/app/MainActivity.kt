package com.recapmaster.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.recapmaster.app.engine.BlurBoxConfig
import com.recapmaster.app.engine.CopyrightBypassConfig
import com.recapmaster.app.pipeline.RecapPipelineManager
import com.recapmaster.app.ui.screens.RecapStudioScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri

class MainActivity : ComponentActivity() {

    private lateinit var pipelineManager: RecapPipelineManager

    // Runtime permission launcher (POST_NOTIFICATIONS, media access on Android 13+)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* proceed regardless of grant status */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Always obtain the shared singleton from RecapApplication
        pipelineManager = (application as? RecapApplication)?.pipelineManager
            ?: RecapPipelineManager(applicationContext)

        // Initialize user license & subscription manager
        com.recapmaster.app.auth.UserSubscriptionManager.init(applicationContext)

        // Initialize Google Analytics for Firebase
        com.google.firebase.analytics.FirebaseAnalytics.getInstance(applicationContext)

        // Initialize Start.io Ads SDK & Adsterra Direct Link Manager
        com.recapmaster.app.ads.StartAppAdsManager.initialize(applicationContext)
        com.recapmaster.app.ads.AdsterraAdsManager.initialize(applicationContext)

        // Initialize Firebase Cloud Messaging topic subscriptions
        com.recapmaster.app.fcm.RecapFirebaseMessagingService.subscribeToDefaultTopics()

        // Check for app updates via Firestore (app_config/update)
        com.recapmaster.app.update.AppUpdateManager.checkForUpdates(applicationContext)

        // Request runtime permissions required on Android 13/14
        requestRuntimePermissions()

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF09090B)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 📱 Top Banner Ad (Adsterra 468x60 Banner)
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        factory = { ctx ->
                            com.recapmaster.app.ads.AdsterraBannerView.createTopBannerView(ctx)
                        }
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        RecapStudioScreen(
                            pipelineManager = pipelineManager,
                            onStartPipeline = { params -> startPipelineSafely(params) }
                        )
                    }
                    // 📱 Bottom Banner Ad (Adsterra Container Banner)
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        factory = { ctx ->
                            com.recapmaster.app.ads.AdsterraBannerView.createBottomBannerView(ctx)
                        }
                    )

                    // 🚀 In-App Update Dialog (Driven live from Firestore app_config/update)
                    val updateInfo by com.recapmaster.app.update.AppUpdateManager.updateState.collectAsState()
                    if (updateInfo != null && updateInfo!!.isAvailable) {
                        val info = updateInfo!!
                        AlertDialog(
                            onDismissRequest = {
                                if (!info.isForceUpdate) {
                                    com.recapmaster.app.update.AppUpdateManager.dismissUpdate()
                                }
                            },
                            icon = {
                                Icon(
                                    Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(32.dp)
                                )
                            },
                            title = {
                                Text(
                                    text = "🚀 New Update Available (v${info.latestVersionName})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Color.White
                                )
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = info.releaseNotes,
                                        fontSize = 12.sp,
                                        color = Color(0xFFA1A1AA),
                                        lineHeight = 16.sp
                                    )
                                    if (info.isForceUpdate) {
                                        Text(
                                            text = "⚠️ Please update to continue using RecapMaster (အသုံးပြုရန် Update လုပ်ပေးပါ).",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFBBF24)
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        try {
                                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl.trim()))
                                            startActivity(browserIntent)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF38BDF8),
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Download Update (ဒေါင်းလုဒ်ဆွဲရန်)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            },
                            dismissButton = if (!info.isForceUpdate) {
                                {
                                    TextButton(onClick = { com.recapmaster.app.update.AppUpdateManager.dismissUpdate() }) {
                                        Text("Later", color = Color(0xFF71717A))
                                    }
                                }
                            } else null,
                            containerColor = Color(0xFF18181B)
                        )
                    }
                }
            }
        }

        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun startPipelineSafely(params: PipelineParams) {
        val currentUser = com.recapmaster.app.auth.AuthManager.currentUser.value
        if (currentUser == null) {
            android.widget.Toast.makeText(this, "⚠️ Please sign in with your Google account first.", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val sub = com.recapmaster.app.auth.UserSubscriptionManager.subscription.value
        if (sub != null && sub.isExpired) {
            android.widget.Toast.makeText(this, "⚠️ Free access expired! Please watch a video ad to get 10 minutes of free use.", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val serviceIntent = RecapPipelineService.buildStartIntent(
            context          = this,
            action           = params.action,
            url              = params.url,
            geminiKey        = params.geminiKey,
            voiceProfileId   = params.voiceProfileId,
            ttsEngine        = params.ttsEngine,
            voice            = params.voice,
            rate             = params.voiceRate,
            pitch            = params.voicePitch,
            voicePrompt      = params.voicePrompt,
            soundStyle       = params.soundStyle,
            burnSubtitles    = params.burnSubtitles,
            subPlacement     = params.subPlacement,
            fontScale        = params.fontScale,
            marginV          = params.marginV,
            speed            = params.speed,
            blurEnabled      = params.blurEnabled,
            blurX            = params.blurX,
            blurY            = params.blurY,
            blurW            = params.blurW,
            blurH            = params.blurH,
            blurStrength     = params.blurStrength,
            dubbingMode      = params.dubbingMode,
            bypassEnabled    = params.bypassEnabled,
            bypassHflip      = params.bypassHflip,
            bypassZoom       = params.bypassZoom,
            bypassBrightness = params.bypassBrightness,
            bypassContrast   = params.bypassContrast,
            bypassSaturation = params.bypassSaturation,
            bypassNoise      = params.bypassNoise,
            bypassBorderWidth = params.bypassBorderWidth,
            bypassBorderColor = params.bypassBorderColor
        )

        if (params.url.startsWith("content://")) {
            try {
                grantUriPermission(packageName, android.net.Uri.parse(params.url), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            // Robust fallback to in-process coroutine if system restricts foreground service
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    when (params.action) {
                        "DUB" -> {
                            val baseProfile = com.recapmaster.app.data.model.VoiceProfiles.findById(params.voiceProfileId)
                            val resolvedProfile = baseProfile.copy(
                                voiceId = if (params.voice.isNotBlank()) params.voice else baseProfile.voiceId,
                                rate = if (params.voiceRate.isNotBlank()) params.voiceRate else baseProfile.rate,
                                pitch = if (params.voicePitch.isNotBlank()) params.voicePitch else baseProfile.pitch,
                                promptPersona = if (params.voicePrompt.isNotBlank()) params.voicePrompt else baseProfile.promptPersona
                            )
                            pipelineManager.startDubbingPipeline(
                                videoUrl     = params.url,
                                geminiApiKey = params.geminiKey,
                                voiceProfile = resolvedProfile,
                                dubbingMode  = params.dubbingMode
                            )
                        }
                        "COMPOSE" -> {
                            pipelineManager.generateFinalVideo(
                                soundStyle        = params.soundStyle,
                                burnSubtitles     = params.burnSubtitles,
                                subtitlePlacement = params.subPlacement,
                                fontScale         = params.fontScale,
                                marginV           = params.marginV,
                                playbackSpeed     = params.speed,
                                blurBox           = BlurBoxConfig(
                                    enabled  = params.blurEnabled,
                                    xPct     = params.blurX, yPct = params.blurY,
                                    wPct     = params.blurW, hPct = params.blurH,
                                    strength = params.blurStrength
                                ),
                                copyrightBypass   = CopyrightBypassConfig(
                                    enabled         = params.bypassEnabled,
                                    hflip           = params.bypassHflip,
                                    zoomCropPct     = params.bypassZoom,
                                    brightness      = params.bypassBrightness,
                                    contrast        = params.bypassContrast,
                                    saturation      = params.bypassSaturation,
                                    noise           = params.bypassNoise,
                                    borderThickness = params.bypassBorderWidth,
                                    borderColorHex  = params.bypassBorderColor
                                )
                            )
                        }
                        else -> {
                            val baseProfile = com.recapmaster.app.data.model.VoiceProfiles.findById(params.voiceProfileId)
                            val resolvedProfile = baseProfile.copy(
                                voiceId = if (params.voice.isNotBlank()) params.voice else baseProfile.voiceId,
                                rate = if (params.voiceRate.isNotBlank()) params.voiceRate else baseProfile.rate,
                                pitch = if (params.voicePitch.isNotBlank()) params.voicePitch else baseProfile.pitch,
                                promptPersona = if (params.voicePrompt.isNotBlank()) params.voicePrompt else baseProfile.promptPersona
                            )
                            pipelineManager.executePipeline(
                                videoUrl          = params.url,
                                geminiApiKey      = params.geminiKey,
                                voiceProfile      = resolvedProfile,
                                soundStyle        = params.soundStyle,
                                burnSubtitles     = params.burnSubtitles,
                                subtitlePlacement = params.subPlacement,
                                fontScale         = params.fontScale,
                                marginV           = params.marginV,
                                playbackSpeed     = params.speed,
                                blurBox           = BlurBoxConfig(
                                    enabled  = params.blurEnabled,
                                    xPct     = params.blurX, yPct = params.blurY,
                                    wPct     = params.blurW, hPct = params.blurH,
                                    strength = params.blurStrength
                                ),
                                copyrightBypass   = CopyrightBypassConfig(
                                    enabled         = params.bypassEnabled,
                                    hflip           = params.bypassHflip,
                                    zoomCropPct     = params.bypassZoom,
                                    brightness      = params.bypassBrightness,
                                    contrast        = params.bypassContrast,
                                    saturation      = params.bypassSaturation,
                                    noise           = params.bypassNoise,
                                    borderThickness = params.bypassBorderWidth,
                                    borderColorHex  = params.bypassBorderColor
                                )
                            )
                        }
                    }
                } catch (err: Throwable) {
                    err.printStackTrace()
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()

        // POST_NOTIFICATIONS & READ_MEDIA_VIDEO on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        }

        // READ/WRITE_EXTERNAL_STORAGE for legacy Android < 10
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (needed.isNotEmpty()) {
            try {
                requestPermissionLauncher.launch(needed.toTypedArray())
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                // Future enhancement: pass sharedText to RecapStudioScreen
            }
        }
    }
}

/** Typed parameter bundle passed from the UI to the service starter */
data class PipelineParams(
    val action: String = "FULL", // "DUB" | "COMPOSE" | "FULL"
    val url: String = "",
    val geminiKey: String = "",
    val voiceProfileId: String = "edge_thiha_cinematic",
    val ttsEngine: String = "edge",
    val voice: String = "my-MM-ThihaNeural",
    val voiceRate: String = "+0%",
    val voicePitch: String = "+0Hz",
    val voicePrompt: String = "",
    val soundStyle: String = "cinematic_recap",
    val burnSubtitles: Boolean = false,
    val subPlacement: String = "bottom",
    val fontScale: Float = 1.0f,
    val marginV: Int = 30,
    val speed: Float = 1.0f,
    val blurEnabled: Boolean = false,
    val blurX: Float = 0.78f, val blurY: Float = 0.04f,
    val blurW: Float = 0.18f, val blurH: Float = 0.08f,
    val blurStrength: Int = 16,
    val dubbingMode: String = "STORY_RECAP", // Dedicated Story Recap Mode
    val bypassEnabled: Boolean = false,
    val bypassHflip: Boolean = false,
    val bypassZoom: Float = 0f,
    val bypassBrightness: Float = 0f,
    val bypassContrast: Float = 1.0f,
    val bypassSaturation: Float = 1.0f,
    val bypassNoise: Int = 0,
    val bypassBorderWidth: Int = 0,
    val bypassBorderColor: String = "#000000"
)
