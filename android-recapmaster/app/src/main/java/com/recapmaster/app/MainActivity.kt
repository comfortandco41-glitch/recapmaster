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
import com.recapmaster.app.pipeline.RecapPipelineManager
import com.recapmaster.app.ui.screens.RecapStudioScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

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

        // Initialize Unity Ads SDK
        com.recapmaster.app.ads.UnityAdsManager.initialize(applicationContext)

        // Request runtime permissions required on Android 13/14
        requestRuntimePermissions()

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF09090B)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        RecapStudioScreen(
                            pipelineManager = pipelineManager,
                            onStartPipeline = { params -> startPipelineSafely(params) }
                        )
                    }
                    // 📱 Unity Banner Ad (BP_Banner_Android)
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        factory = {
                            com.recapmaster.app.ads.UnityAdsManager.createBannerView(this@MainActivity)
                        }
                    )
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
            android.widget.Toast.makeText(this, "⚠️ Free access expired! Please watch a video ad to get 20 minutes of free use.", android.widget.Toast.LENGTH_LONG).show()
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
            dubbingMode      = params.dubbingMode
        )

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
    val dubbingMode: String = "EXACT_SRT_SYNC" // "EXACT_SRT_SYNC" | "DIALOGUE_SYNC" | "STORY_RECAP"
)
