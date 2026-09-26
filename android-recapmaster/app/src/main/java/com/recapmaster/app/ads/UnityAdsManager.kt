package com.recapmaster.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize
import com.unity3d.services.banners.BannerErrorInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UnityAdsManager handles initialization, preloading, and showing of Rewarded & Banner Ads.
 * Game ID: 800381519
 * Placement: BP_Rewarded_Android, BP_Banner_Android
 */
object UnityAdsManager {
    private const val TAG = "UnityAdsManager"

    const val GAME_ID = "800381519"
    const val REWARDED_PLACEMENT_ID = "BP_Rewarded_Android"
    const val BANNER_PLACEMENT_ID = "BP_Banner_Android"

    // Real ads mode requested by user
    var isTestMode: Boolean = false

    private var isInitialized = false
    private val initCallbacks = mutableListOf<() -> Unit>()

    private val _isRewardedLoaded = MutableStateFlow(false)
    val isRewardedLoaded: StateFlow<Boolean> = _isRewardedLoaded.asStateFlow()

    fun initialize(context: Context, onComplete: (() -> Unit)? = null) {
        if (isInitialized) {
            onComplete?.invoke()
            return
        }

        if (onComplete != null) {
            initCallbacks.add(onComplete)
        }

        Log.d(TAG, "Initializing Unity Ads with Game ID: $GAME_ID (testMode=$isTestMode)")
        UnityAds.initialize(context.applicationContext, GAME_ID, isTestMode, object : IUnityAdsInitializationListener {
            override fun onInitializationComplete() {
                Log.d(TAG, "Unity Ads initialized successfully")
                isInitialized = true
                preloadRewardedAd(context.applicationContext)
                val callbacks = ArrayList(initCallbacks)
                initCallbacks.clear()
                callbacks.forEach { it.invoke() }
            }

            override fun onInitializationFailed(error: UnityAds.UnityAdsInitializationError?, message: String?) {
                Log.e(TAG, "Unity Ads initialization failed: $error - $message")
                initCallbacks.clear()
            }
        })
    }

    fun preloadRewardedAd(
        context: Context,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        UnityAds.load(REWARDED_PLACEMENT_ID, object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String?) {
                Log.d(TAG, "Rewarded Ad successfully loaded: $placementId")
                _isRewardedLoaded.value = true
                onSuccess?.invoke()
            }

            override fun onUnityAdsFailedToLoad(
                placementId: String?,
                error: UnityAds.UnityAdsLoadError?,
                message: String?
            ) {
                val errStr = "[$error] ${message ?: "Failed to load"}"
                Log.w(TAG, "Failed to load Rewarded Ad: $errStr")
                _isRewardedLoaded.value = false
                onError?.invoke(errStr)
            }
        })
    }

    /**
     * Show Rewarded Ad. If not loaded, loads it on demand with proper callbacks so it never hangs.
     */
    fun showRewardedAd(
        activity: Activity,
        onStatusUpdate: ((String) -> Unit)? = null,
        onUserRewarded: () -> Unit,
        onDismissed: (() -> Unit)? = null,
        onFailed: ((String) -> Unit)? = null
    ) {
        if (!isInitialized) {
            onStatusUpdate?.invoke("⏳ Connecting to Unity Ads...")
            initialize(activity.applicationContext) {
                showRewardedAd(activity, onStatusUpdate, onUserRewarded, onDismissed, onFailed)
            }
            return
        }

        if (_isRewardedLoaded.value) {
            performShow(activity, onUserRewarded, onDismissed, onFailed)
        } else {
            onStatusUpdate?.invoke("⏳ Loading live video ad...")
            preloadRewardedAd(
                context = activity.applicationContext,
                onSuccess = {
                    activity.runOnUiThread {
                        performShow(activity, onUserRewarded, onDismissed, onFailed)
                    }
                },
                onError = { err ->
                    activity.runOnUiThread {
                        onFailed?.invoke(err)
                    }
                }
            )
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
                val errStr = "[$error] ${message ?: "Show error"}"
                Log.e(TAG, "Rewarded Ad show failed: $errStr")
                _isRewardedLoaded.value = false
                preloadRewardedAd(activity.applicationContext)
                onFailed?.invoke(errStr)
            }
        })
    }

    /**
     * Creates and loads a 320x50 Banner ad view.
     * Ensures Unity Ads SDK is initialized before calling banner.load() to prevent blank banners.
     */
    fun createBannerView(activity: Activity): BannerView {
        val banner = BannerView(activity, BANNER_PLACEMENT_ID, UnityBannerSize(320, 50))
        banner.listener = object : BannerView.IListener {
            override fun onBannerLoaded(bannerView: BannerView?) {
                Log.d(TAG, "Banner ad loaded successfully: $BANNER_PLACEMENT_ID")
            }

            override fun onBannerFailedToLoad(bannerView: BannerView?, errorInfo: BannerErrorInfo?) {
                Log.w(TAG, "Banner ad failed to load ($BANNER_PLACEMENT_ID): [${errorInfo?.errorCode}] ${errorInfo?.errorMessage}")
            }

            override fun onBannerShown(bannerView: BannerView?) {
                Log.d(TAG, "Banner ad shown: $BANNER_PLACEMENT_ID")
            }

            override fun onBannerClick(bannerView: BannerView?) {
                Log.d(TAG, "Banner clicked")
            }

            override fun onBannerLeftApplication(bannerView: BannerView?) {
                Log.d(TAG, "Banner left application")
            }
        }

        // Wait until initialized before calling banner.load()
        if (isInitialized) {
            banner.load()
        } else {
            initialize(activity.applicationContext) {
                activity.runOnUiThread {
                    banner.load()
                }
            }
        }

        return banner
    }
}
