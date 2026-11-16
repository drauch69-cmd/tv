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

        /** Make the biggest video or embedded player on the page fill the screen. */
        private const val FOCUS_JS = """
            (function(){
              if (window.__slFocused) return 'done';
              var best=null, area=0;
              document.querySelectorAll('video,iframe').forEach(function(e){
                var r=e.getBoundingClientRect(); var a=r.width*r.height;
                if (a>area){ area=a; best=e; }
              });
              if (!best || area < 40000) return 'none';
              window.__slFocused = true;
              best.setAttribute('style','position:fixed!important;left:0!important;top:0!important;'+
                'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;'+
                'margin:0!important;border:0!important;z-index:2147483647!important;background:#000!important');
              if (best.tagName==='IFRAME') best.setAttribute('allowfullscreen','true');
              document.documentElement.style.overflow='hidden';
              if (document.body) document.body.style.overflow='hidden';
              return best.tagName;
            })()
        """
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var focusTries = 0

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
        web.webViewClient = object : SafeWebViewClient(url) {
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
                if (res == "VIDEO" || res == "IFRAME") {
                    Report.logOnce("site-focus", "Filled the screen with the page's ${if (res == "VIDEO") "video" else "embedded player"}")
                } else if (res != "done") {
                    main.postDelayed(this, 2500)
                }
            }
        }
    }

    private fun tapCenter() {
        val target: View = customView ?: web
        if (target.width == 0) return
        val x = target.width / 2f
        val y = target.height / 2f
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (customView != null) {
                    if (event.action == KeyEvent.ACTION_UP) customCallback?.onCustomViewHidden()
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
