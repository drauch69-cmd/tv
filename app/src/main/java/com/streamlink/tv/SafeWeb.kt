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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.view.ViewGroup
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** Shared settings for every web view the app opens: scripts on, popups and app-jumps off. */
object SafeWeb {
    @SuppressLint("SetJavaScriptEnabled", "RestrictedApi")
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
        // In every frame: window.open() hands back a harmless stand-in instead of nothing.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { WebViewCompat.addDocumentStartJavaScript(wv, POPUP_STANDIN_JS, setOf("*")) } catch (_: Exception) {}
        }
    }

    /**
     * Some players (ntv, 2026-11-21) test popups on the first tap: open one, close it at once, and if
     * window.open() returned null they decide they are in a sandboxed frame and refuse to play. A
     * refused popup looks exactly like that, so every frame gets a window.open() that opens nothing
     * and returns a closable stand-in. Links with target=_blank still reach onCreateWindow and are refused.
     */
    const val POPUP_STANDIN_JS = """
        (function(){
          try {
            if (window.__slPopupStandin) return;
            window.__slPopupStandin = 1;
            var noop = function(){};
            var make = function(){
              var w = { closed: false, opener: null, name: '', focus: noop, blur: noop, print: noop,
                        postMessage: noop, moveTo: noop, resizeTo: noop, stop: noop,
                        addEventListener: noop, removeEventListener: noop };
              w.close = function(){ w.closed = true; };
              w.location = { href: 'about:blank', assign: noop, replace: noop, reload: noop };
              w.document = { write: noop, writeln: noop, open: noop, close: noop };
              w.window = w; w.self = w;
              return w;
            };
            var open = function(){ return make(); };
            Object.defineProperty(window, 'open', { value: open, writable: true, configurable: true });
          } catch (e) {}
        })();
    """
}

/** Takes a web view out of its parent and destroys it, without calling into the page (safe after the renderer died). */
fun SafeWeb.dispose(view: WebView?) {
    view ?: return
    try { (view.parent as? ViewGroup)?.removeView(view) } catch (_: Throwable) {}
    try { view.destroy() } catch (_: Throwable) {}
}

/** Refuses popups and new windows, and silently dismisses alert/confirm/prompt boxes. */
open class SafeChromeClient(private val quiet: () -> Boolean = { false }) : WebChromeClient() {
    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
        if (!quiet()) Report.log("Blocked a popup window")
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
    /** True for a background run beside a playing stream (see StreamSniffer.quiet): nothing it blocks or follows goes into the report. */
    private val quiet: () -> Boolean = { false },
) : WebViewClient() {
    private fun say(m: String) { if (!quiet()) Report.log(m) }
    private fun sayOnce(k: String, m: String) { if (!quiet()) Report.logOnce(k, m) }
    var firstPageLoaded = false

    open fun onFrame(url: String) {}
    open fun onResource(request: WebResourceRequest) {}
    /** True to answer this request with an empty 204 (the background link finder skips media segments). */
    open fun blockResource(request: WebResourceRequest): Boolean = false
    open fun onLoaded() {}

    /**
     * The system shut down the web view's renderer (crash or low memory). The view is dead and must not be used again.
     * Default: take it off the screen and destroy it. Subclasses may override this or [onRenderProcessGone] itself.
     */
    open fun onRendererGone(view: WebView?, didCrash: Boolean) { SafeWeb.dispose(view) }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        val crashed = try { detail?.didCrash() ?: false } catch (_: Throwable) { false }
        onRendererGone(view, crashed)
        return true
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        request ?: return true
        val target = request.url
        val scheme = target.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            say("Blocked a jump to a \"$scheme:\" link")
            return true
        }
        if (AdBlock.isLocalHost(target.host)) {
            say("Blocked the page from reaching the TV itself")
            return true
        }
        if (AdBlock.isBlockedHost(target.host, allowVideoAds)) {
            sayOnce("nav-${target.host}", "Blocked redirect to ad network ${target.host}")
            return true
        }
        if (!request.isForMainFrame) {
            onFrame(target.toString())
            return false
        }
        // A server redirect while the first page is still loading (e.g. the site moved) is allowed.
        if (request.isRedirect && !firstPageLoaded) {
            siteUrl = target.toString()
            say("Site redirected to ${target.host}")
            return false
        }
        if (!AdBlock.sameSite(target.toString(), siteUrl)) {
            say("Blocked redirect to ${target.host}")
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
            sayOnce("local-$host", "Blocked the page from reaching the TV itself ($host)")
            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        if (blockAds && AdBlock.isBlockedHost(host, allowVideoAds)) {
            if (!quiet()) Report.countBlocked(host)
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
        val u = request.url.toString()
        val accept = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value ?: ""
        if (!request.isForMainFrame && accept.contains("text/html") && u.startsWith("http")) onFrame(u)
        onResource(request)
        if (blockResource(request)) return WebResourceResponse("application/octet-stream", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        return null
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {}

    override fun onPageFinished(view: WebView?, url: String?) {
        firstPageLoaded = true
        onLoaded()
    }
}
