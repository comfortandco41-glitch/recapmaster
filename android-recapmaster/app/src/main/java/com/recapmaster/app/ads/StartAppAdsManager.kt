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
     * Shows a Rewarded Video Ad.
     * 1 ad watch = 20 minutes free use.
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

        onStatusUpdate?.invoke("⏳ Loading video ad from Start.io...")
        val rewardedAd = StartAppAd(activity)

        // Video completion listener
        rewardedAd.setVideoListener(object : VideoListener {
            override fun onVideoCompleted() {
                Log.d(TAG, "Start.io Rewarded Video completed!")
                activity.runOnUiThread {
                    onUserRewarded()
                }
            }
        })

        // Load and display
        rewardedAd.loadAd(StartAppAd.AdMode.REWARDED_VIDEO, object : AdEventListener {
            override fun onReceiveAd(ad: Ad) {
                onStatusUpdate?.invoke("🎬 Playing ad...")
                rewardedAd.showAd(object : AdDisplayListener {
                    override fun adHidden(ad: Ad) {
                        onDismissed?.invoke()
                    }
                    override fun adDisplayed(ad: Ad) {
                        Log.d(TAG, "Start.io ad displayed")
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
                val err = ad?.errorMessage ?: "No ad available right now"
                Log.w(TAG, "Start.io failed to receive ad: $err")
                activity.runOnUiThread {
                    onFailed?.invoke(err)
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
            override fun onClick(view: View?) {}
        })
        return banner
    }
}
