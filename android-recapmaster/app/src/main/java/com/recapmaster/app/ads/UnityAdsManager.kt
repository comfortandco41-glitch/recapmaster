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
 * UnityAdsManager handles initialization, preloading, and showing of Rewarded & Banner ads.
 * Game ID: 800381519
 * Rewarded Placement: BP_Rewarded_Android
 * Banner Placement: BP_Banner_Android
 */
object UnityAdsManager {
    private const val TAG = "UnityAdsManager"

    const val GAME_ID = "800381519"
    const val REWARDED_PLACEMENT_ID = "BP_Rewarded_Android"
    const val BANNER_PLACEMENT_ID = "BP_Banner_Android"

    // Set to false for live production ads and revenue earnings.
    var isTestMode: Boolean = false

    private var isInitialized = false

    private val _isRewardedLoaded = MutableStateFlow(false)
    val isRewardedLoaded: StateFlow<Boolean> = _isRewardedLoaded.asStateFlow()

    /**
     * Call this in MainActivity.onCreate or Application.onCreate.
     */
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

    /**
     * Preloads the rewarded ad so it is ready instantaneously when the user clicks.
     */
    fun preloadRewardedAd(context: Context) {
        UnityAds.load(REWARDED_PLACEMENT_ID, object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String?) {
                Log.d(TAG, "Rewarded Ad successfully loaded: $placementId")
                _isRewardedLoaded.value = true
            }

            override fun onUnityAdsFailedToLoad(
                placementId: String?,
                error: UnityAds.UnityAdsLoadError?,
                message: String?
            ) {
                Log.w(TAG, "Failed to load Rewarded Ad: $error - $message")
                _isRewardedLoaded.value = false
            }
        })
    }

    /**
     * Displays the rewarded ad.
     * @param onUserRewarded Callback invoked ONLY when the user watches the entire ad.
     * @param onDismissed Callback invoked if the user closes/skips the ad before completion.
     * @param onFailed Callback invoked if the ad fails to show.
     */
    fun showRewardedAd(
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
                // Preload the next ad in the background
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
