package com.streamlink.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONTokener
import java.io.ByteArrayInputStream

/** A video stream found on a page, plus the headers the site's own player used to fetch it. */
data class FoundStream(val url: String, val headers: Map<String, String>, val pageUrl: String)

/**
 * Loads a page in a hidden WebView, blocks popups/redirects/ad networks, nudges the page's
 * player to start, and reports the first video stream (HLS .m3u8 / DASH .mpd) it requests.
 */
class StreamSniffer(private val activity: Activity, private val holder: ViewGroup) {

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var done = true
    private var pageUrl = ""
    private var firstPageLoaded = false
    private var nudgeCount = 0
    private var onResult: ((FoundStream?) -> Unit)? = null

    private val timeout = Runnable { finish(null) }
    private val nudger = object : Runnable {
        override fun run() {
            if (done) return
            nudge()
            main.postDelayed(this, 2500)
        }
    }

    fun sniff(url: String, callback: (FoundStream?) -> Unit) {
        cancel()
        if (isStreamUrl(url) || url.lowercase().substringBefore('?').endsWith(".mp4")) {
            // The link is already a video file/stream: play it directly.
            callback(FoundStream(url, emptyMap(), url))
            return
        }
        done = false
        pageUrl = url
        firstPageLoaded = false
        nudgeCount = 0
        onResult = callback
        val wv = createWebView()
        webView = wv
        holder.addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.loadUrl(url)
        main.postDelayed(timeout, TIMEOUT_MS)
        main.postDelayed(nudger, 3000)
    }

    fun cancel() {
        done = true
        onResult = null
        main.removeCallbacks(timeout)
        main.removeCallbacks(nudger)
        destroyWebView()
    }

    private fun finish(result: FoundStream?) {
        if (done) return
        val cb = onResult
        cancel()
        cb?.invoke(result)
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        wv.stopLoading()
        wv.loadUrl("about:blank")
        holder.removeView(wv)
        wv.destroy()
    }

    private fun found(url: String, requestHeaders: Map<String, String>) {
        if (done) return
        val ua = webView?.settings?.userAgentString
        val headers = LinkedHashMap<String, String>()
        for ((k, v) in requestHeaders) {
            if (k.equals("Range", true) || k.equals("Accept-Encoding", true)) continue
            headers[k] = v
        }
        if (headers.keys.none { it.equals("User-Agent", true) } && ua != null) headers["User-Agent"] = ua
        if (headers.keys.none { it.equals("Referer", true) }) headers["Referer"] = webView?.url ?: pageUrl
        CookieManager.getInstance().getCookie(url)?.let { if (it.isNotBlank()) headers["Cookie"] = it }
        finish(FoundStream(url, headers, pageUrl))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(activity)
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true) // so window.open() reaches onCreateWindow, where it's refused
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webChromeClient = object : WebChromeClient() {
            // Popups and new tabs: always refused.
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
            // Alert/confirm/prompt boxes: silently dismissed.
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean { result?.cancel(); return true }
            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.confirm(); return true }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                request ?: return true
                val target = request.url
                val scheme = target.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return true // intent://, market://, etc.
                if (AdBlock.isBlockedHost(target.host)) return true
                if (!request.isForMainFrame) return false
                // A server redirect while the first page is still loading (e.g. the site moved) is allowed.
                if (request.isRedirect && !firstPageLoaded) {
                    pageUrl = target.toString()
                    return false
                }
                // Otherwise the page may only move around inside the site you sent.
                return !AdBlock.sameSite(target.toString(), pageUrl)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                request ?: return null
                if (AdBlock.isBlockedHost(request.url.host)) return emptyResponse()
                val u = request.url.toString()
                if (isStreamUrl(u)) {
                    val headers = HashMap(request.requestHeaders ?: emptyMap())
                    main.post { found(u, headers) }
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {}

            override fun onPageFinished(view: WebView?, url: String?) {
                firstPageLoaded = true
                nudge()
            }
        }
        return wv
    }

    /** Try to get the page's player going: unmute-safe autoplay, click common play buttons, tap the middle. */
    private fun nudge() {
        val wv = webView ?: return
        if (done) return
        nudgeCount++
        wv.evaluateJavascript(NUDGE_JS) { raw ->
            if (done || raw == null || raw == "null") return@evaluateJavascript
            try {
                val arr = JSONArray(JSONTokener(raw).nextValue() as String)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val src = o.optString("src")
                    val live = o.isNull("dur") && o.optInt("ready") >= 1
                    val long = !o.isNull("dur") && o.optDouble("dur") > 600
                    if (src.startsWith("http") && (isStreamUrl(src) || live || long)) {
                        found(src, emptyMap())
                        return@evaluateJavascript
                    }
                }
            } catch (_: Exception) {
            }
        }
        // Players inside iframes can't be scripted from here, so tap the middle of the page on
        // every other nudge. Any popup or redirect that tap triggers is blocked above.
        if (nudgeCount % 2 == 0 && wv.width > 0) tap(wv, wv.width / 2f, wv.height / 2f)
    }

    private fun tap(view: WebView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val TIMEOUT_MS = 25_000L

        fun isStreamUrl(url: String): Boolean {
            val u = url.lowercase()
            if (!u.startsWith("http")) return false
            return u.contains(".m3u8") || u.contains(".mpd")
        }

        private const val NUDGE_JS = """
            (function(){
              var out=[];
              document.querySelectorAll('video').forEach(function(v){
                try{ v.muted=true; var p=v.play(); if(p&&p.catch)p.catch(function(){}); }catch(e){}
                var s=v.currentSrc||v.src||'';
                out.push({src:s, dur:(isFinite(v.duration)?v.duration:null), ready:v.readyState});
              });
              ['.vjs-big-play-button','.jw-icon-display','.jw-display-icon-container',
               '.plyr__control--overlaid','.fp-play','.play-button','.playbtn','#play',
               'button[aria-label="Play"]','button[title="Play"]','[class*="play-btn"]']
              .forEach(function(sel){
                document.querySelectorAll(sel).forEach(function(e){ try{e.click();}catch(x){} });
              });
              return JSON.stringify(out);
            })()
        """
    }
}
