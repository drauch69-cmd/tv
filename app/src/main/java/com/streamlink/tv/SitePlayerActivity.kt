package com.streamlink.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Fallback: the site's own player, fullscreen, with the same popup/redirect blocking as the
 * hidden page loader. Used when no stream can be played directly.
 *
 * The video (or the frame holding it) is blown up to fill the screen. OK / play-pause taps the
 * middle of the player; Back returns to the link screen.
 */
class SitePlayerActivity : Activity() {

    companion object {
        private const val EXTRA_URL = "url"

        fun intent(ctx: Context, url: String): Intent =
            Intent(ctx, SitePlayerActivity::class.java).putExtra(EXTRA_URL, url)

        /**
         * Pick the part of the page most likely to be the video player and make it fill the screen.
         * Prefers real <video> elements and TV-shaped frames whose address or name suggests a
         * player; strongly avoids chat, comments and ads. Re-run safely; returns "TAG|count",
         * "same", "manual" or "none". Also defines __slNext(step) for the remote's ◀ ▶ keys.
         */
        private const val FOCUS_JS = """
            (function(){
              var S = window.__sl || (window.__sl = {el:null, orig:null, manual:false, list:[]});
              function hint(e){
                var c = (typeof e.className === 'string') ? e.className : '';
                var s = ((e.src||'')+' '+(e.id||'')+' '+c+' '+(e.title||'')+' '+(e.name||'')).toLowerCase();
                var k = 1;
                if (/chat|comment|discord|social|banner|sponsor|advert|(^|[^a-z])ads?([^a-z]|$)|popup/.test(s)) k *= 0.05;
                if (/player|embed|stream|video|live|hls|m3u8|watch/.test(s)) k *= 2;
                return k;
              }
              function score(e){
                var r = e.getBoundingClientRect(); var a = r.width * r.height;
                if (a < 40000) return 0;
                var ar = r.width / Math.max(1, r.height);
                var shape = (ar > 1.2 && ar < 2.5) ? 1 : 0.15;   // chat boxes are tall and narrow
                return a * shape * (e.tagName === 'VIDEO' ? 3 : 1) * hint(e);
              }
              function show(e){
                if (S.el && S.el !== e) {
                  if (S.orig === null) S.el.removeAttribute('style'); else S.el.setAttribute('style', S.orig);
                }
                if (S.el !== e) {
                  S.orig = e.getAttribute('style');
                  e.setAttribute('style','position:fixed!important;left:0!important;top:0!important;'+
                    'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;'+
                    'margin:0!important;border:0!important;z-index:2147483647!important;background:#000!important');
                  if (e.tagName === 'IFRAME') e.setAttribute('allowfullscreen','true');
                  S.el = e;
                }
                document.documentElement.style.overflow = 'hidden';
                if (document.body) document.body.style.overflow = 'hidden';
              }
              window.__slNext = function(step){
                if (!S.list.length) return '0/0';
                var i = S.list.indexOf(S.el);
                i = ((i < 0 ? 0 : i + step) % S.list.length + S.list.length) % S.list.length;
                S.manual = true;
                show(S.list[i]);
                return (i + 1) + '/' + S.list.length;
              };
              var els = [].slice.call(document.querySelectorAll('video,iframe'));
              var scored = els.map(function(e){
                var sc = (e === S.el && e.__slScore != null) ? e.__slScore : score(e);
                e.__slScore = sc; return {e:e, s:sc};
              }).filter(function(x){ return x.s > 0; }).sort(function(a,b){ return b.s - a.s; });
              S.list = scored.map(function(x){ return x.e; });
              if (!S.list.length) return 'none';
              if (S.manual && S.el && S.list.indexOf(S.el) >= 0) return 'manual';
              if (S.list[0] === S.el) return 'same';
              show(S.list[0]);
              return S.list[0].tagName + '|' + S.list.length;
            })()
        """
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var focusTries = 0
    private var hintShown = false

    private val handler: (String, String?) -> Unit = { c, _ ->
        when (c) {
            "toggle", "play", "pause" -> tapCenter()
            "reload" -> {
                focusTries = 0
                web.reload()
            }
            "stop" -> finish()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        web = WebView(this)
        SafeWeb.configure(web)
        web.isFocusable = false
        web.isFocusableInTouchMode = false
        web.setBackgroundColor(Color.BLACK)
        web.webChromeClient = object : SafeChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                customView?.let { root.removeView(it) }
                customView = view
                customCallback = callback
                root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCallback = null
            }
        }
        web.webViewClient = object : SafeWebViewClient(url, allowVideoAds = true, blockAds = false) {
            override fun onLoaded() {
                focusTries = 0
                main.removeCallbacks(focusTick)
                main.post(focusTick)
            }
        }
        root.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val host = Report.host(url)
        Report.log("Opened the site's own player")
        Session.mode = "site"
        Session.title = host
        Session.snapshot = null
        Session.commandHandler = handler
        AppStatus.text = "Playing $host (site's own player)"
        web.loadUrl(url)
    }

    private val focusTick = object : Runnable {
        override fun run() {
            if (focusTries++ > 10) return
            web.evaluateJavascript(FOCUS_JS) { r ->
                val res = r?.trim('"') ?: "none"
                if (res.startsWith("VIDEO") || res.startsWith("IFRAME")) {
                    val count = res.substringAfter('|', "1").toIntOrNull() ?: 1
                    Report.log("Filled the screen with the page's ${if (res.startsWith("VIDEO")) "video" else "embedded player"}" +
                        if (count > 1) " (best of $count; ◀ ▶ on the remote switches)" else "")
                    if (count > 1 && !hintShown) {
                        hintShown = true
                        Toast.makeText(this@SitePlayerActivity, "Wrong part of the page? Press ◀ ▶ to switch", Toast.LENGTH_LONG).show()
                    }
                }
                if (res != "manual") main.postDelayed(this, 2500)
            }
        }
    }

    /** ◀ ▶: cycle which part of the page fills the screen. */
    private fun cycle(step: Int) {
        web.evaluateJavascript("window.__slNext ? __slNext($step) : '0/0'") { r ->
            val pos = r?.trim('"') ?: "0/0"
            if (pos != "0/0") {
                Report.log("Switched what fills the screen to $pos")
                Toast.makeText(this, "Showing ${pos.replace("/", " of ")}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (customView != null) {
                    if (event.action == KeyEvent.ACTION_UP) customCallback?.onCustomViewHidden()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (customView == null) {
                    if (event.action == KeyEvent.ACTION_UP) cycle(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.action == KeyEvent.ACTION_UP) tapCenter()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (Session.commandHandler === handler) {
            Session.commandHandler = null
            Session.mode = "idle"
            AppStatus.text = "Waiting for a link"
        }
        web.stopLoading()
        web.loadUrl("about:blank")
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
