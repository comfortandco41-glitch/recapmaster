package com.recapmaster.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.recapmaster.app.data.model.TtsEngine
import com.recapmaster.app.data.model.VoiceProfile
import com.recapmaster.app.data.model.VoiceProfiles
import com.recapmaster.app.engine.BlurBoxConfig
import com.recapmaster.app.pipeline.RecapPipelineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground Service that hosts the RecapMaster AI pipeline.
 *
 * Running as a Foreground Service with FOREGROUND_SERVICE_TYPE_DATA_SYNC is
 * required on Android 14 (API 34, e.g. Samsung Galaxy S24+) to prevent the
 * OS process manager from terminating long-running CPU/network tasks.
 */
class RecapPipelineService : Service() {

    companion object {
        const val CHANNEL_ID = "recapmaster_pipeline"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START  = "com.recapmaster.app.PIPELINE_START"
        const val ACTION_CANCEL = "com.recapmaster.app.PIPELINE_CANCEL"

        const val EXTRA_URL             = "url"
        const val EXTRA_GEMINI_KEY      = "gemini_key"
        const val EXTRA_VOICE_PROFILE_ID = "voice_profile_id"
        const val EXTRA_TTS_ENGINE      = "tts_engine"
        const val EXTRA_VOICE           = "voice"
        const val EXTRA_VOICE_RATE      = "voice_rate"
        const val EXTRA_VOICE_PITCH     = "voice_pitch"
        const val EXTRA_VOICE_PROMPT    = "voice_prompt"
        const val EXTRA_SOUND_STYLE     = "sound_style"
        const val EXTRA_BURN_SUBS       = "burn_subtitles"
        const val EXTRA_SUB_PLACEMENT   = "sub_placement"
        const val EXTRA_FONT_SCALE      = "font_scale"
        const val EXTRA_MARGIN_V        = "margin_v"
        const val EXTRA_SPEED           = "speed"
        const val EXTRA_BLUR_ENABLED    = "blur_enabled"
        const val EXTRA_BLUR_X          = "blur_x"
        const val EXTRA_BLUR_Y          = "blur_y"
        const val EXTRA_BLUR_W          = "blur_w"
        const val EXTRA_BLUR_H          = "blur_h"
        const val EXTRA_BLUR_STRENGTH   = "blur_strength"

        fun buildStartIntent(
            context: Context,
            url: String,
            geminiKey: String,
            voiceProfileId: String,
            ttsEngine: String,
            voice: String,
            rate: String,
            pitch: String,
            voicePrompt: String,
            soundStyle: String,
            burnSubtitles: Boolean,
            subPlacement: String,
            fontScale: Float,
            marginV: Int,
            speed: Float,
            blurEnabled: Boolean,
            blurX: Float, blurY: Float, blurW: Float, blurH: Float, blurStrength: Int
        ) = Intent(context, RecapPipelineService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_URL,              url)
            putExtra(EXTRA_GEMINI_KEY,       geminiKey)
            putExtra(EXTRA_VOICE_PROFILE_ID, voiceProfileId)
            putExtra(EXTRA_TTS_ENGINE,       ttsEngine)
            putExtra(EXTRA_VOICE,            voice)
            putExtra(EXTRA_VOICE_RATE,       rate)
            putExtra(EXTRA_VOICE_PITCH,      pitch)
            putExtra(EXTRA_VOICE_PROMPT,     voicePrompt)
            putExtra(EXTRA_SOUND_STYLE,      soundStyle)
            putExtra(EXTRA_BURN_SUBS,        burnSubtitles)
            putExtra(EXTRA_SUB_PLACEMENT,    subPlacement)
            putExtra(EXTRA_FONT_SCALE,       fontScale)
            putExtra(EXTRA_MARGIN_V,         marginV)
            putExtra(EXTRA_SPEED,            speed)
            putExtra(EXTRA_BLUR_ENABLED,     blurEnabled)
            putExtra(EXTRA_BLUR_X,           blurX)
            putExtra(EXTRA_BLUR_Y,           blurY)
            putExtra(EXTRA_BLUR_W,           blurW)
            putExtra(EXTRA_BLUR_H,           blurH)
            putExtra(EXTRA_BLUR_STRENGTH,    blurStrength)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pipelineJob: Job? = null
    private var progressJob: Job? = null

    private val pipelineManager: RecapPipelineManager by lazy {
        (application as? RecapApplication)?.pipelineManager
            ?: RecapPipelineManager(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always promote to foreground immediately to satisfy Android 14 requirements
        startForegroundSafely("Starting pipeline...")

        when (intent?.action) {
            ACTION_CANCEL -> {
                pipelineJob?.cancel()
                progressJob?.cancel()
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val url           = intent.getStringExtra(EXTRA_URL) ?: ""
                val geminiKey     = intent.getStringExtra(EXTRA_GEMINI_KEY) ?: ""
                val profileId     = intent.getStringExtra(EXTRA_VOICE_PROFILE_ID) ?: "edge_thiha_cinematic"
                val ttsEngineStr  = intent.getStringExtra(EXTRA_TTS_ENGINE) ?: ""
                val voice         = intent.getStringExtra(EXTRA_VOICE) ?: "my-MM-ThihaNeural"
                val rate          = intent.getStringExtra(EXTRA_VOICE_RATE) ?: "+0%"
                val pitch         = intent.getStringExtra(EXTRA_VOICE_PITCH) ?: "+0Hz"
                val promptPersona = intent.getStringExtra(EXTRA_VOICE_PROMPT) ?: ""
                val soundStyle    = intent.getStringExtra(EXTRA_SOUND_STYLE) ?: "cinematic_recap"
                val burnSubs      = intent.getBooleanExtra(EXTRA_BURN_SUBS, true)
                val subPlacement  = intent.getStringExtra(EXTRA_SUB_PLACEMENT) ?: "bottom"
                val fontScale     = intent.getFloatExtra(EXTRA_FONT_SCALE, 1.0f)
                val marginV       = intent.getIntExtra(EXTRA_MARGIN_V, 30)
                val speed         = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
                val blurEnabled   = intent.getBooleanExtra(EXTRA_BLUR_ENABLED, false)
                val blurX         = intent.getFloatExtra(EXTRA_BLUR_X, 0.78f)
                val blurY         = intent.getFloatExtra(EXTRA_BLUR_Y, 0.04f)
                val blurW         = intent.getFloatExtra(EXTRA_BLUR_W, 0.18f)
                val blurH         = intent.getFloatExtra(EXTRA_BLUR_H, 0.08f)
                val blurStrength  = intent.getIntExtra(EXTRA_BLUR_STRENGTH, 16)

                if (url.isBlank()) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }

                // Resolve VoiceProfile
                val baseProfile = VoiceProfiles.findById(profileId)
                val resolvedEngine = when (ttsEngineStr) {
                    TtsEngine.GEMINI_VOICE.id -> TtsEngine.GEMINI_VOICE
                    TtsEngine.GOOGLE_CLOUD_TTS.id -> TtsEngine.GOOGLE_CLOUD_TTS
                    TtsEngine.EDGE_TTS.id -> TtsEngine.EDGE_TTS
                    else -> baseProfile.engine
                }
                val activeProfile = baseProfile.copy(
                    engine = resolvedEngine,
                    voiceId = if (voice.isNotBlank()) voice else baseProfile.voiceId,
                    rate = if (rate.isNotBlank()) rate else baseProfile.rate,
                    pitch = if (pitch.isNotBlank()) pitch else baseProfile.pitch,
                    promptPersona = if (promptPersona.isNotBlank()) promptPersona else baseProfile.promptPersona
                )

                // Collect pipeline state updates to reflect progress in foreground notification
                progressJob?.cancel()
                progressJob = serviceScope.launch {
                    try {
                        pipelineManager.state.collect { state ->
                            updateNotificationSafely(state.message)
                        }
                    } catch (_: Throwable) {}
                }

                pipelineJob?.cancel()
                pipelineJob = serviceScope.launch {
                    try {
                        pipelineManager.executePipeline(
                            videoUrl          = url,
                            geminiApiKey      = geminiKey,
                            voiceProfile      = activeProfile,
                            soundStyle        = soundStyle,
                            burnSubtitles     = burnSubs,
                            subtitlePlacement = subPlacement,
                            fontScale         = fontScale,
                            marginV           = marginV,
                            playbackSpeed     = speed,
                            blurBox           = BlurBoxConfig(
                                enabled  = blurEnabled,
                                xPct     = blurX, yPct = blurY,
                                wPct     = blurW, hPct = blurH,
                                strength = blurStrength
                            )
                        )
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    } finally {
                        progressJob?.cancel()
                        stopForegroundAndSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pipelineJob?.cancel()
        progressJob?.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely(message: String) {
        try {
            val notification = buildNotification(message)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun stopForegroundAndSelf() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "RecapMaster Pipeline",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows progress while generating your recap video"
                    setSound(null, null)
                    enableVibration(false)
                }
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎬 RecapMaster")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()

    private fun updateNotificationSafely(message: String) {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(message))
        } catch (t: Throwable) {
            // Ignore notification update errors if permission not granted
        }
    }
}
