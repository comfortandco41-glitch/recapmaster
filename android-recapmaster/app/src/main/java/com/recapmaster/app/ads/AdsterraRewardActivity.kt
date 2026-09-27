package com.recapmaster.app.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

class AdsterraRewardActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_ad_url"
        const val EXTRA_COUNTDOWN_SECONDS = "extra_countdown_seconds"
    }

    private var activeWebView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUrl = intent.getStringExtra(EXTRA_URL) ?: "https://www.google.com"
        val totalCountdown = intent.getIntExtra(EXTRA_COUNTDOWN_SECONDS, 5).coerceAtLeast(3)

        setContent {
            var secondsRemaining by remember { mutableIntStateOf(totalCountdown) }
            val isRewardUnlocked = secondsRemaining <= 0
            var showExitDialog by remember { mutableStateOf(false) }

            // ⏱️ 5-Second Countdown Timer
            LaunchedEffect(Unit) {
                while (secondsRemaining > 0) {
                    delay(1000L)
                    secondsRemaining--
                }
            }

            // Prevent accidental early exit
            BackHandler {
                if (isRewardUnlocked) {
                    claimRewardAndClose()
                } else {
                    showExitDialog = true
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF09090B))
            ) {
                // ── Top Bar with Countdown Timer & Claim Button ───────────────
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF18181B),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (!isRewardUnlocked) {
                                // Counting down
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.HourglassTop,
                                        contentDescription = null,
                                        tint = Color(0xFFFBBF24), // Amber
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "⏱️ Please stay on sponsor page: ${secondsRemaining}s",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "အခမဲ့ ၁၀ မိနစ်ရရှိရန် ၅ စက္ကန့် စောင့်ပေးပါ",
                                            fontSize = 10.sp,
                                            color = Color(0xFFA1A1AA)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { showExitDialog = true },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = Color(0xFF71717A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else {
                                // Reward Unlocked!
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF34D399), // Green
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "🎉 Reward Unlocked!",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF34D399)
                                        )
                                        Text(
                                            text = "Tap button to claim +10 mins free pass",
                                            fontSize = 10.sp,
                                            color = Color.White
                                        )
                                    }
                                }

                                Button(
                                    onClick = { claimRewardAndClose() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF34D399),
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.Black
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Claim +10m (ရယူမည်)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Progress line during countdown
                        if (!isRewardUnlocked) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val progress = (totalCountdown - secondsRemaining).toFloat() / totalCountdown.toFloat()
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp),
                                color = Color(0xFFFBBF24),
                                trackColor = Color(0xFF27272A)
                            )
                        }
                    }
                }

                // ── In-App Sponsor WebView ────────────────────────────────────
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            activeWebView = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.databaseEnabled = true
                            settings.userAgentString = (
                                "Mozilla/5.0 (Linux; Android 12; Mobile) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/120.0.0.0 Mobile Safari/537.36 RecapMaster/1.0"
                            )

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    url: String?
                                ): Boolean {
                                    return false // Keep inside WebView
                                }
                            }
                            webChromeClient = WebChromeClient()
                            loadUrl(targetUrl)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            // Early exit confirmation dialog
            if (showExitDialog) {
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = {
                        Text("Exit Sponsor Ad?", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    },
                    text = {
                        Text(
                            "You must stay for $secondsRemaining more seconds to receive your +10 minutes free pass.\n\n" +
                            "ယခုထွက်ခွာပါက အခမဲ့ ၁၀ မိနစ် ရရှိမည်မဟုတ်ပါ။",
                            fontSize = 12.sp,
                            color = Color(0xFFA1A1AA)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showExitDialog = false }) {
                            Text("Stay ($secondsRemaining s)", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showExitDialog = false
                            AdsterraAdsManager.cancelPendingReward()
                            finish()
                        }) {
                            Text("Quit Early", color = Color(0xFFF87171))
                        }
                    },
                    containerColor = Color(0xFF18181B),
                    titleContentColor = Color.White
                )
            }
        }
    }

    private fun claimRewardAndClose() {
        Toast.makeText(this, "🎉 +10 Minutes reward claimed successfully!", Toast.LENGTH_SHORT).show()
        AdsterraAdsManager.notifyRewardClaimed()
        finish()
    }

    override fun onDestroy() {
        try {
            activeWebView?.apply {
                stopLoading()
                clearHistory()
                destroy()
            }
            activeWebView = null
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
