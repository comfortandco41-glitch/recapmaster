package com.recapmaster.app.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import com.google.firebase.firestore.FirebaseFirestore

/**
 * AdsterraAdsManager handles Adsterra Direct Link (Smartlink) monetization.
 * - Opens the high-CPM Adsterra direct link using Chrome Custom Tabs for a native browser experience.
 *   (Raw WebViews are blocked by Adsterra anti-bot systems, leading to black screens / 403 errors).
 * - Enforces a strict 5-SECOND MINIMUM STAY rule: If user closes the ad in < 5s, no reward is granted.
 * - Automatically fetches updated links from Firebase Firestore (app_config/ads -> adsterra_direct_link)
 *   so the developer can update/rotate links without rebuilding the APK.
 */
object AdsterraAdsManager {
    private const val TAG = "AdsterraAdsManager"

    // Default Adsterra Smartlink (can be updated anytime via Firebase Firestore "app_config/ads" -> "adsterra_direct_link")
    var directLinkUrl: String = "https://www.profitableratecpmnetwork.com/xpas1uub?key=aadcd94e2c1bb0ab342e0f1fee4a771d"

    private var isListenerInitialized = false
    private var isWaitingForReward = false
    private var launchTimestamp: Long = 0L

    /**
     * Initializes Firestore listener to fetch live Adsterra direct link URL dynamically.
     */
    fun initialize(context: Context) {
        if (isListenerInitialized) return
        try {
            FirebaseFirestore.getInstance().collection("app_config").document("ads")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Failed to read remote ad config: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val remoteUrl = snapshot.getString("adsterra_direct_link")
                        if (!remoteUrl.isNullOrBlank()) {
                            directLinkUrl = remoteUrl.trim()
                            Log.d(TAG, "Adsterra direct link updated from Firestore: $directLinkUrl")
                        }
                    }
                }
            isListenerInitialized = true
            Log.d(TAG, "AdsterraAdsManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AdsterraAdsManager", e)
        }
    }

    /**
     * Opens the Adsterra Smartlink via Chrome Custom Tabs with a strict 5-second minimum stay verification.
     */
    fun showRewardedAd(
        activity: Activity,
        onStatusUpdate: ((String) -> Unit)? = null,
        onUserRewarded: () -> Unit,
        onDismissed: (() -> Unit)? = null,
        onFailed: ((String) -> Unit)? = null
    ) {
        initialize(activity.applicationContext)

        val targetUrl = directLinkUrl.trim()
        if (targetUrl.isBlank()) {
            onFailed?.invoke("Ad link is not configured")
            return
        }

        launchTimestamp = System.currentTimeMillis()
        isWaitingForReward = true

        onStatusUpdate?.invoke("⏳ Opening sponsor ad... Please stay on the page for at least 5 seconds!")

        val lifecycleCallback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(act: Activity) {
                if (act == activity && isWaitingForReward) {
                    isWaitingForReward = false
                    act.application.unregisterActivityLifecycleCallbacks(this)

                    val elapsedMs = System.currentTimeMillis() - launchTimestamp
                    val elapsedSeconds = elapsedMs / 1000L

                    Log.d(TAG, "User returned from ad after ${elapsedMs}ms (${elapsedSeconds}s)")

                    if (elapsedSeconds >= 5) {
                        act.runOnUiThread {
                            onStatusUpdate?.invoke("🎉 Sponsor ad viewed for ${elapsedSeconds}s! Granting +10 minutes reward...")
                            Toast.makeText(act, "🎉 +10 Minutes reward added! (အခမဲ့ ၁၀ မိနစ် ပေါင်းထည့်ပြီးပါပြီ)", Toast.LENGTH_SHORT).show()
                            onUserRewarded()
                        }
                    } else {
                        act.runOnUiThread {
                            val msg = "⚠️ ကျေးဇူးပြု၍ ကြော်ငြာကို အနည်းဆုံး ၅ စက္ကန့် ကြည့်ရှုပေးပါ (${elapsedSeconds}s သာ ကြည့်ရှုထားပါသည်)။"
                            onStatusUpdate?.invoke(msg)
                            Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
                            onDismissed?.invoke()
                        }
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(act: Activity) {
                if (act == activity) {
                    act.application.unregisterActivityLifecycleCallbacks(this)
                }
            }
        }

        activity.application.registerActivityLifecycleCallbacks(lifecycleCallback)

        try {
            val uri = Uri.parse(targetUrl)

            // Configure Dark Theme Chrome Custom Tabs
            val darkParams = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(0xFF18181B.toInt())
                .build()

            val customTabsIntent = CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(darkParams)
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()

            customTabsIntent.launchUrl(activity, uri)
        } catch (e: Exception) {
            Log.w(TAG, "Chrome Custom Tabs failed, falling back to standard ACTION_VIEW", e)
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(fallbackIntent)
            } catch (err: Exception) {
                Log.e(TAG, "Failed to open Adsterra direct link", err)
                isWaitingForReward = false
                activity.application.unregisterActivityLifecycleCallbacks(lifecycleCallback)
                activity.runOnUiThread {
                    onFailed?.invoke("Could not open ad link: ${err.message}")
                }
            }
        }
    }
}
