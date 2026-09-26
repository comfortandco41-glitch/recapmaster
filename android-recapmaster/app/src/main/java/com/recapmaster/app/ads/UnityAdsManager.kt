package com.recapmaster.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UnityAdsManager handles initialization, preloading, and showing of Rewarded Ads.
 * Game ID: 800381519
 * Placement: BP_Rewarded_Android
 */
object UnityAdsManager {
    private const val TAG = "UnityAdsManager"

    const val GAME_ID = "800381519"
    const val REWARDED_PLACEMENT_ID = "BP_Rewarded_Android"

    // IMPORTANT: Keep true during development / testing APK builds.
    // Unity requires test mode for unpublished APKs to prevent 100% NO_FILL errors.
    var isTestMode: Boolean = true

    private var isInitialized = false
    private var isCurrentlyLoading = false

    private val _isRewardedLoaded = MutableStateFlow(false)
    val isRewardedLoaded: StateFlow<Boolean> = _isRewardedLoaded.asStateFlow()

    fun initialize(context: Context, onComplete: (() -> Unit)? = null) {
        if (isInitialized) {
            onComplete?.invoke()
            return
        }

        Log.d(TAG, "Initializing Unity Ads with Game ID: $GAME_ID (testMode=$isTestMode)")
        UnityAds.initialize(context.applicationContext, GAME_ID, isTestMode, object : IUnityAdsInitializationListener {
            override fun onInitializationComplete() {
                Log.d(TAG, "Unity Ads initialized successfully")
                isInitialized = true
                preloadRewardedAd(context.applicationContext)
                onComplete?.invoke()
            }

            override fun onInitializationFailed(error: UnityAds.UnityAdsInitializationError?, message: String?) {
                Log.e(TAG, "Unity Ads initialization failed: $error - $message")
            }
        })
    }

    fun preloadRewardedAd(context: Context, onLoaded: (() -> Unit)? = null) {
        if (isCurrentlyLoading) return
        isCurrentlyLoading = true

        UnityAds.load(REWARDED_PLACEMENT_ID, object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String?) {
                Log.d(TAG, "Rewarded Ad successfully loaded: $placementId")
                isCurrentlyLoading = false
                _isRewardedLoaded.value = true
                onLoaded?.invoke()
            }

            override fun onUnityAdsFailedToLoad(
                placementId: String?,
                error: UnityAds.UnityAdsLoadError?,
                message: String?
            ) {
                Log.w(TAG, "Failed to load Rewarded Ad: $error - $message")
                isCurrentlyLoading = false
                _isRewardedLoaded.value = false
            }
        })
    }

    /**
     * Show Rewarded Ad safely.
     * If the ad is not preloaded yet, it automatically loads it first and shows it as soon as ready!
     */
    fun showRewardedAd(
        activity: Activity,
        onStatusUpdate: ((String) -> Unit)? = null,
        onUserRewarded: () -> Unit,
        onDismissed: (() -> Unit)? = null,
        onFailed: ((String) -> Unit)? = null
    ) {
        if (!isInitialized) {
            onStatusUpdate?.invoke("⏳ Initializing Unity Ads SDK...")
            initialize(activity.applicationContext) {
                showRewardedAd(activity, onStatusUpdate, onUserRewarded, onDismissed, onFailed)
            }
            return
        }

        // If ad is ready in memory, show immediately
        if (_isRewardedLoaded.value) {
            performShow(activity, onUserRewarded, onDismissed, onFailed)
        } else {
            // Not ready yet: load on demand and show as soon as it arrives
            onStatusUpdate?.invoke("⏳ Ad is buffering, please wait 3-5 seconds...")
            preloadRewardedAd(activity.applicationContext) {
                activity.runOnUiThread {
                    performShow(activity, onUserRewarded, onDismissed, onFailed)
                }
            }
        }
    }

    private fun performShow(
        activity: Activity,
        onUserRewarded: () -> Unit,
        onDismissed: (() -> Unit)? = null,
        onFailed: ((String) -> Unit)? = null
    ) {
        UnityAds.show(activity, REWARDED_PLACEMENT_ID, UnityAdsShowOptions(), object : IUnityAdsShowListener {
            override fun onUnityAdsShowStart(placementId: String?) {
                Log.d(TAG, "Rewarded Ad started playback: $placementId")
            }

            override fun onUnityAdsShowClick(placementId: String?) {
                Log.d(TAG, "Rewarded Ad clicked: $placementId")
            }

            override fun onUnityAdsShowComplete(
                placementId: String?,
                state: UnityAds.UnityAdsShowCompletionState?
            ) {
                Log.d(TAG, "Rewarded Ad completed with state: $state")
                _isRewardedLoaded.value = false
                preloadRewardedAd(activity.applicationContext)

                if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) {
                    onUserRewarded()
                } else {
                    onDismissed?.invoke()
                }
            }

            override fun onUnityAdsShowFailure(
                placementId: String?,
                error: UnityAds.UnityAdsShowError?,
                message: String?
            ) {
                Log.e(TAG, "Rewarded Ad show failed: $error - $message")
                _isRewardedLoaded.value = false
                preloadRewardedAd(activity.applicationContext)
                onFailed?.invoke(message ?: "Ad playback failed")
            }
        })
    }
}
