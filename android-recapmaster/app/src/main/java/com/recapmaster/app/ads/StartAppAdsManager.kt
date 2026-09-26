package com.recapmaster.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
import com.startapp.sdk.adsbase.adlisteners.VideoListener
import com.startapp.sdk.ads.banner.Banner
import com.startapp.sdk.ads.banner.BannerListener

/**
 * StartAppAdsManager handles Start.io SDK monetization.
 * Serves REAL ads to APKs distributed via Google Drive / Direct Download!
 * App ID: 208858303
 */
object StartAppAdsManager {
    private const val TAG = "StartAppAdsManager"
    const val APP_ID = "208858303"

    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            StartAppSDK.init(context, APP_ID, false)
            StartAppSDK.enableReturnAds(false)
            StartAppAd.disableSplash()
            isInitialized = true
            Log.d(TAG, "Start.io SDK initialized with App ID: $APP_ID")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize StartAppSDK", e)
        }
    }

    /**
     * Shows an Ad to earn 20 minutes of free recap use.
     * Tries REWARDED_VIDEO first. If video has status 204 (no content in user's region),
     * it automatically falls back to AUTOMATIC (Full-page Interstitial) with 100% fill rate!
     */
    fun showRewardedAd(
        activity: Activity,
        onStatusUpdate: ((String) -> Unit)? = null,
        onUserRewarded: () -> Unit,
        onDismissed: (() -> Unit)? = null,
        onFailed: ((String) -> Unit)? = null
    ) {
        if (!isInitialized) {
            initialize(activity.applicationContext)
        }

        onStatusUpdate?.invoke("⏳ Loading ad from Start.io...")
        loadAndDisplayAd(
            activity = activity,
            adMode = StartAppAd.AdMode.REWARDED_VIDEO,
            isFallback = false,
            onStatusUpdate = onStatusUpdate,
            onUserRewarded = onUserRewarded,
            onDismissed = onDismissed,
            onFailed = onFailed
        )
    }

    private fun loadAndDisplayAd(
        activity: Activity,
        adMode: StartAppAd.AdMode,
        isFallback: Boolean,
        onStatusUpdate: ((String) -> Unit)?,
        onUserRewarded: () -> Unit,
        onDismissed: (() -> Unit)?,
        onFailed: ((String) -> Unit)?
    ) {
        val startAppAd = StartAppAd(activity)
        var rewardGranted = false

        // Listen for video completion
        startAppAd.setVideoListener(object : VideoListener {
            override fun onVideoCompleted() {
                Log.d(TAG, "Start.io video completed!")
                if (!rewardGranted) {
                    rewardGranted = true
                    activity.runOnUiThread {
                        onUserRewarded()
                    }
                }
            }
        })

        startAppAd.loadAd(adMode, object : AdEventListener {
            override fun onReceiveAd(ad: Ad) {
                onStatusUpdate?.invoke("🎬 Showing ad...")
                startAppAd.showAd(object : AdDisplayListener {
                    override fun adHidden(ad: Ad) {
                        Log.d(TAG, "Start.io ad hidden/closed")
                        // If interstitial was shown or video ended, guarantee the user gets their 20 minutes!
                        if (!rewardGranted) {
                            rewardGranted = true
                            activity.runOnUiThread {
                                onUserRewarded()
                            }
                        } else {
                            onDismissed?.invoke()
                        }
                    }

                    override fun adDisplayed(ad: Ad) {
                        Log.d(TAG, "Start.io ad displayed successfully")
                    }

                    override fun adClicked(ad: Ad) {
                        Log.d(TAG, "Start.io ad clicked")
                    }

                    override fun adNotDisplayed(ad: Ad) {
                        activity.runOnUiThread {
                            onFailed?.invoke("Ad could not be displayed")
                        }
                    }
                })
            }

            override fun onFailedToReceiveAd(ad: Ad?) {
                val err = ad?.errorMessage ?: "No ad available"
                Log.w(TAG, "Start.io load error ($adMode): $err")

                // If REWARDED_VIDEO returned 204 or failed, fallback to AUTOMATIC mode immediately!
                if (!isFallback) {
                    Log.d(TAG, "Falling back to StartAppAd.AdMode.AUTOMATIC for 100% fill rate...")
                    activity.runOnUiThread {
                        onStatusUpdate?.invoke("⏳ Buffering alternative ad...")
                    }
                    loadAndDisplayAd(
                        activity = activity,
                        adMode = StartAppAd.AdMode.AUTOMATIC,
                        isFallback = true,
                        onStatusUpdate = onStatusUpdate,
                        onUserRewarded = onUserRewarded,
                        onDismissed = onDismissed,
                        onFailed = onFailed
                    )
                } else {
                    activity.runOnUiThread {
                        onFailed?.invoke(err)
                    }
                }
            }
        })
    }

    /**
     * Creates a Start.io Banner ad view.
     */
    fun createBannerView(context: Context): View {
        if (!isInitialized) {
            initialize(context.applicationContext)
        }
        val banner = Banner(context, object : BannerListener {
            override fun onReceiveAd(view: View) {
                Log.d(TAG, "Start.io banner loaded successfully")
            }
            override fun onFailedToReceiveAd(view: View?) {
                Log.w(TAG, "Start.io banner failed to load")
            }
            override fun onImpression(view: View?) {
                Log.d(TAG, "Start.io banner impression recorded")
            }
            override fun onClick(view: View?) {}
        })
        return banner
    }
}
