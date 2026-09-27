package com.recapmaster.app.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore

/**
 * AdsterraAdsManager handles Adsterra Direct Link (Smartlink) monetization.
 * - Opens the high-CPM Adsterra direct link using Chrome Custom Tabs for a seamless in-app experience.
 * - Automatically fetches updated links from Firebase Firestore (app_config/ads -> adsterra_direct_link)
 *   so the developer can update/rotate links without rebuilding the APK.
 * - Grants the +10 minutes reward when the user returns to the app.
 */
object AdsterraAdsManager {
    private const val TAG = "AdsterraAdsManager"

    // Default Adsterra Smartlink (can be overridden anytime via Firebase Firestore "app_config/ads")
    var directLinkUrl: String = "http://apointmrnet35.top/h/yCvbKf7BKyRC/4cf365a219af4084bcb33aee33f6e0bb/V6Qv4V4mo8aTLVf91QkAh8"

    private var isListenerInitialized = false

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

    private var onRewardGrantedCallback: (() -> Unit)? = null

    /**
     * Launches the Adsterra Smartlink via AdsterraRewardActivity (with 5-second countdown timer).
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

        onStatusUpdate?.invoke("⏳ Opening sponsor ad (5s countdown)...")
        onRewardGrantedCallback = onUserRewarded

        try {
            val intent = Intent(activity, AdsterraRewardActivity::class.java).apply {
                putExtra(AdsterraRewardActivity.EXTRA_URL, targetUrl)
                putExtra(AdsterraRewardActivity.EXTRA_COUNTDOWN_SECONDS, 5)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AdsterraRewardActivity, falling back to Custom Tabs", e)
            try {
                val uri = Uri.parse(targetUrl)
                val customTabsIntent = CustomTabsIntent.Builder().build()
                customTabsIntent.launchUrl(activity, uri)
                // Fallback grant
                onUserRewarded()
            } catch (err: Exception) {
                onRewardGrantedCallback = null
                onFailed?.invoke("Could not open ad link: ${err.message}")
            }
        }
    }

    /**
     * Called by AdsterraRewardActivity when the user completes the 5-second countdown and claims the reward.
     */
    fun notifyRewardClaimed() {
        onRewardGrantedCallback?.invoke()
        onRewardGrantedCallback = null
    }

    /**
     * Called if user quits early.
     */
    fun cancelPendingReward() {
        onRewardGrantedCallback = null
    }
}
