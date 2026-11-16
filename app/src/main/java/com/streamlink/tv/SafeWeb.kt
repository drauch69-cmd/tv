package com.streamlink.tv

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** Shared settings for every web view the app opens: scripts on, popups and app-jumps off. */
object SafeWeb {
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(wv: WebView) {
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true) // so window.open() reaches onCreateWindow, where it's refused
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString.replace("; wv", "")
            // Pages get nothing from the TV itself: no files, no app content, no location.
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            saveFormData = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        // Android web views tell every site which app they're in (an "X-Requested-With" header).
        // Some streaming sites refuse those requests with 403, so don't send it.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(wv.settings, emptySet())
        }
    }
}

/** Refuses popups and new windows, and silently dismisses alert/confirm/prompt boxes. */
open class SafeChromeClient : WebChromeClient() {
    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
        Report.log("Blocked a popup window")
        return false
    }

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        result?.cancel(); return true
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        result?.cancel(); return true
    }

    override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
        result?.cancel(); return true
    }

    override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        result?.confirm(); return true
    }
}

/**
 * Keeps a page on its own site: blocks app links (intent://, market://), ad/redirect networks,
 * and any main-frame navigation to a different site. Embedded frames may load freely.
 */
/**
 * [blockAds]: false in the site's own player, where ads may load (some sites refuse to play when
 * their ads are blocked); popups, off-site redirects and other apps are still blocked there.
 */
open class SafeWebViewClient(
    var siteUrl: String,
    private val allowVideoAds: Boolean = false,
    private val blockAds: Boolean = true,
) : WebViewClient() {
    var firstPageLoaded = false

    open fun onFrame(url: String) {}
    open fun onResource(request: WebResourceRequest) {}
    open fun onLoaded() {}

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        request ?: return true
        val target = request.url
        val scheme = target.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Report.log("Blocked a jump to a \"$scheme:\" link")
            return true
        }
        if (AdBlock.isLocalHost(target.host)) {
            Report.log("Blocked the page from reaching the TV itself")
            return true
        }
        if (AdBlock.isBlockedHost(target.host, allowVideoAds)) {
            Report.logOnce("nav-${target.host}", "Blocked redirect to ad network ${target.host}")
            return true
        }
        if (!request.isForMainFrame) {
            onFrame(target.toString())
            return false
        }
        // A server redirect while the first page is still loading (e.g. the site moved) is allowed.
        if (request.isRedirect && !firstPageLoaded) {
            siteUrl = target.toString()
            Report.log("Site redirected to ${target.host}")
            return false
        }
        if (!AdBlock.sameSite(target.toString(), siteUrl)) {
            Report.log("Blocked redirect to ${target.host}")
            return true
        }
        return false
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        request ?: return null
        val host = request.url.host
        val scheme = request.url.scheme?.lowercase()
        if (scheme == "file" || scheme == "content") {
            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        if (AdBlock.isLocalHost(host)) {
            Report.logOnce("local-$host", "Blocked the page from reaching the TV itself ($host)")
            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        if (blockAds && AdBlock.isBlockedHost(host, allowVideoAds)) {
            Report.countBlocked(host)
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
        val u = request.url.toString()
        val accept = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value ?: ""
        if (!request.isForMainFrame && accept.contains("text/html") && u.startsWith("http")) onFrame(u)
        onResource(request)
        return null
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {}

    override fun onPageFinished(view: WebView?, url: String?) {
        firstPageLoaded = true
        onLoaded()
    }
}
