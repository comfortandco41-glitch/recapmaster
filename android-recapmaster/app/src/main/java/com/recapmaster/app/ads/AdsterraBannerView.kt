package com.recapmaster.app.ads

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

/**
 * AdsterraBannerView handles rendering Adsterra web banner scripts inside lightweight WebViews.
 * 
 * Banner 1 (Top / Responsive Iframe Banner 468x60):
 * <script>
 *   atOptions = {
 *     'key' : 'ac8f4628502ab8549fae442b11e255b4',
 *     'format' : 'iframe',
 *     'height' : 60,
 *     'width' : 468,
 *     'params' : {}
 *   };
 * </script>
 * <script src="https://www.highrevenueformat.com/ac8f4628502ab8549fae442b11e255b4/invoke.js"></script>
 *
 * Banner 2 (Bottom / Responsive Container Banner):
 * <script async="async" data-cfasync="false" src="https://pl31530651.profitableratecpmnetwork.com/be176ee18658a59ec1bb93481f400eab/invoke.js"></script>
 * <div id="container-be176ee18658a59ec1bb93481f400eab"></div>
 */
object AdsterraBannerView {
    private const val TAG = "AdsterraBannerView"

    private const val TOP_BANNER_BASE_URL = "https://www.highrevenueformat.com"
    private const val BOTTOM_BANNER_BASE_URL = "https://pl31530651.profitableratecpmnetwork.com"

    private const val TOP_BANNER_SNIPPET = """
        <div id="banner-box">
            <script type="text/javascript">
                atOptions = {
                    'key' : 'ac8f4628502ab8549fae442b11e255b4',
                    'format' : 'iframe',
                    'height' : 60,
                    'width' : 468,
                    'params' : {}
                };
            </script>
            <script type="text/javascript" src="https://www.highrevenueformat.com/ac8f4628502ab8549fae442b11e255b4/invoke.js"></script>
        </div>
    """

    private const val BOTTOM_BANNER_SNIPPET = """
        <div id="container-box">
            <script async="async" data-cfasync="false" src="https://pl31530651.profitableratecpmnetwork.com/be176ee18658a59ec1bb93481f400eab/invoke.js"></script>
            <div id="container-be176ee18658a59ec1bb93481f400eab"></div>
        </div>
    """

    /**
     * Builds Top Banner View (468x60 iframe, auto-scaled to fit mobile screen).
     */
    fun createTopBannerView(context: Context): View {
        val html = wrapWithHtmlBoilerplate(TOP_BANNER_SNIPPET, isIframe468 = true)
        return createBannerWebView(context, html, TOP_BANNER_BASE_URL)
    }

    /**
     * Builds Bottom Banner View (Container banner).
     */
    fun createBottomBannerView(context: Context): View {
        val html = wrapWithHtmlBoilerplate(BOTTOM_BANNER_SNIPPET, isIframe468 = false)
        return createBannerWebView(context, html, BOTTOM_BANNER_BASE_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBannerWebView(
        context: Context,
        fullHtml: String,
        baseUrl: String
    ): WebView {
        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x00000000) // Transparent background
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return handleAdClick(view?.context, url)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url.isNullOrBlank()) return false
                    return handleAdClick(view?.context, url)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val tempWebView = WebView(view?.context ?: context)
                    tempWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                            val targetUrl = request?.url?.toString() ?: return false
                            handleAdClick(view?.context ?: context, targetUrl)
                            return true
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(v: WebView?, targetUrl: String?): Boolean {
                            if (!targetUrl.isNullOrBlank()) {
                                handleAdClick(view?.context ?: context, targetUrl)
                            }
                            return true
                        }
                    }
                    transport.webView = tempWebView
                    resultMsg.sendToTarget()
                    return true
                }
            }
        }

        webView.loadDataWithBaseURL(baseUrl, fullHtml, "text/html", "UTF-8", null)
        return webView
    }

    private fun handleAdClick(context: Context?, url: String): Boolean {
        if (context == null || url.isBlank() || url.startsWith("about:") || url.startsWith("javascript:")) {
            return false
        }
        try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                Log.d(TAG, "Opening ad click URL: $url")
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(
                        CustomTabColorSchemeParams.Builder()
                            .setToolbarColor(0xFF18181B.toInt())
                            .build()
                    )
                    .setShowTitle(true)
                    .build()
                customTabsIntent.launchUrl(context, uri)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Custom tabs launch failed, falling back to ACTION_VIEW", e)
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (err: Exception) {
                Log.e(TAG, "Failed to launch intent for ad click: $url", err)
            }
        }
        return false
    }

    private fun wrapWithHtmlBoilerplate(adSnippet: String, isIframe468: Boolean): String {
        val scalingScript = if (isIframe468) {
            """
            <script type="text/javascript">
                function adjustScale() {
                    var el = document.getElementById('banner-box');
                    if (!el) return;
                    var clientW = window.innerWidth || document.documentElement.clientWidth || document.body.clientWidth;
                    if (clientW > 0 && clientW < 468) {
                        var factor = clientW / 468;
                        el.style.transform = 'scale(' + factor + ')';
                        el.style.transformOrigin = 'center center';
                    }
                }
                window.addEventListener('resize', adjustScale);
                window.addEventListener('DOMContentLoaded', adjustScale);
                window.addEventListener('load', adjustScale);
            </script>
            """.trimIndent()
        } else {
            ""
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <style>
                    * { box-sizing: border-box; }
                    html, body {
                        margin: 0;
                        padding: 0;
                        width: 100%;
                        height: 100%;
                        background-color: transparent;
                        overflow: hidden;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                    }
                    #banner-box, #container-box {
                        width: 100%;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                    }
                </style>
                $scalingScript
            </head>
            <body onload="${if (isIframe468) "adjustScale()" else ""}">
                $adSnippet
            </body>
            </html>
        """.trimIndent()
    }
}
