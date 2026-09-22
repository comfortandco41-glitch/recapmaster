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

        // Request runtime permissions required on Android 13/14
        requestRuntimePermissions()

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF09090B)
            ) {
                RecapStudioScreen(
                    pipelineManager = pipelineManager,
                    onStartPipeline = { params -> startPipelineSafely(params) }
                )
            }
        }

        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun startPipelineSafely(params: PipelineParams) {
        val serviceIntent = RecapPipelineService.buildStartIntent(
            context       = this,
            url           = params.url,
            geminiKey     = params.geminiKey,
            voice         = params.voice,
            soundStyle    = params.soundStyle,
            burnSubtitles = params.burnSubtitles,
            subPlacement  = params.subPlacement,
            fontScale     = params.fontScale,
            marginV       = params.marginV,
            speed         = params.speed,
            blurEnabled   = params.blurEnabled,
            blurX         = params.blurX,
            blurY         = params.blurY,
            blurW         = params.blurW,
            blurH         = params.blurH,
            blurStrength  = params.blurStrength
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
                    pipelineManager.executePipeline(
                        videoUrl          = params.url,
                        geminiApiKey      = params.geminiKey,
                        voiceName         = params.voice,
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
    val url: String,
    val geminiKey: String,
    val voice: String,
    val soundStyle: String,
    val burnSubtitles: Boolean,
    val subPlacement: String,
    val fontScale: Float,
    val marginV: Int,
    val speed: Float,
    val blurEnabled: Boolean,
    val blurX: Float, val blurY: Float,
    val blurW: Float, val blurH: Float,
    val blurStrength: Int
)
