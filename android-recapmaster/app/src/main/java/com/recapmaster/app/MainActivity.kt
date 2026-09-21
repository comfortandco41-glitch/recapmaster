package com.recapmaster.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.recapmaster.app.pipeline.RecapPipelineManager
import com.recapmaster.app.ui.screens.RecapStudioScreen

class MainActivity : ComponentActivity() {

    private lateinit var pipelineManager: RecapPipelineManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipelineManager = RecapPipelineManager(applicationContext)

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF09090B)
            ) {
                RecapStudioScreen(pipelineManager)
            }
        }

        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                // Shared text from YouTube/Bilibili app
            }
        }
    }
}
