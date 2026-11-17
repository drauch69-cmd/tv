package com.streamlink.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

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

        /**
         * Sites start videos muted (browsers only allow muted autoplay) and wait for a click on the
         * speaker icon that never comes on a TV. This web view allows sound without a click, so
         * for 60 seconds keep any playing video unmuted at full volume, and nudge paused ones.
         */
        private const val UNMUTE_JS = """
            (function(){
              if (window.__slSound) return; window.__slSound = true;
              var start = Date.now();
              function fix(){
                document.querySelectorAll('video,audio').forEach(function(v){
                  try {
                    if (v.muted || v.volume < 0.05) { v.muted = false; v.volume = 1; }
                    if (v.paused && v.readyState >= 2 && !v.__slTried) {
                      v.__slTried = true; var p = v.play(); if (p && p.catch) p.catch(function(){});
                    }
                  } catch (e) {}
                });
                if (Date.now() - start < 60000) setTimeout(fix, 1000);
              }
              if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fix); else fix();
              document.addEventListener('play', function(e){
                var v = e.target; try { if (v.muted) { v.muted = false; v.volume = 1; } } catch (x) {}
              }, true);
            })();
        """

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
              var S = window.__sl || (window.__sl = {el:null, orig:null, manual:false, list:[], hid:[]});
              if (!S.hid) S.hid = [];
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
              // Hide everything on the page that is not the player or one of its ancestors: for each
              // ancestor, its siblings get visibility:hidden (never display:none, and the ancestors
              // themselves are untouched so the player is not reset). Cheap enough to re-run every tick,
              // which also catches overlays added later.
              var SKIPTAG = {SCRIPT:1, STYLE:1, LINK:1, META:1, NOSCRIPT:1, HEAD:1, TEMPLATE:1, TITLE:1};
              function restoreHidden(){
                S.hid.forEach(function(r){
                  try {
                    if (r.orig === null) r.el.removeAttribute('style'); else r.el.setAttribute('style', r.orig);
                  } catch (x) {}
                  r.el.__slH = false;
                });
                S.hid = [];
              }
              function hideOthers(){
                var e = S.el;
                if (!e || !e.isConnected) return;
                var n = e;
                while (n && n !== document.documentElement) {
                  var p = n.parentElement;
                  if (!p) break;
                  for (var c = p.firstElementChild; c; c = c.nextElementSibling) {
                    if (c === n || SKIPTAG[c.tagName]) continue;
                    if (!c.__slH) {
                      c.__slH = true;
                      S.hid.push({el:c, orig:c.getAttribute('style')});
                    }
                    if (c.style.getPropertyValue('visibility') !== 'hidden' || c.style.getPropertyPriority('visibility') !== 'important') {
                      c.style.setProperty('visibility', 'hidden', 'important');
                    }
                  }
                  n = p;
                }
              }
              function show(e){
                if (S.el && S.el !== e) {
                  if (S.orig === null) S.el.removeAttribute('style'); else S.el.setAttribute('style', S.orig);
                }
                if (S.el !== e) {
                  restoreHidden();   // before reading the new element's style: it may have been hidden as a sibling
                  S.orig = e.getAttribute('style');
                  e.setAttribute('style','position:fixed!important;left:0!important;top:0!important;'+
                    'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;'+
                    'margin:0!important;border:0!important;z-index:2147483647!important;background:#000!important');
                  if (e.tagName === 'IFRAME') e.setAttribute('allowfullscreen','true');
                  S.el = e;
                }
                document.documentElement.style.overflow = 'hidden';
                if (document.body) document.body.style.overflow = 'hidden';
                hideOthers();
              }
              window.__slSweep = function(){ hideOthers(); return S.hid.length; };
              window.__slNext = function(step){
                if (!S.list.length) return '0/0';
                var i = S.list.indexOf(S.el);
                i = ((i < 0 ? 0 : i + step) % S.list.length + S.list.length) % S.list.length;
                S.manual = true;
                show(S.list[i]);
                return (i + 1) + '/' + S.list.length + '#' + S.hid.length;
              };
              var els = [].slice.call(document.querySelectorAll('video,iframe'));
              var scored = els.map(function(e){
                var sc = (e === S.el && e.__slScore != null) ? e.__slScore : score(e);
                e.__slScore = sc; return {e:e, s:sc};
              }).filter(function(x){ return x.s > 0; }).sort(function(a,b){ return b.s - a.s; });
              S.list = scored.map(function(x){ return x.e; });
              if (!S.list.length) { hideOthers(); return 'none#' + S.hid.length; }
              if (S.manual && S.el && S.list.indexOf(S.el) >= 0) { hideOthers(); return 'manual#' + S.hid.length; }
              if (S.list[0] === S.el) { hideOthers(); return 'same#' + S.hid.length; }
              show(S.list[0]);
              return S.list[0].tagName + '|' + S.list.length + '#' + S.hid.length;
            })()
        """

        /** Cheap re-run of the top-page hiding after the player has been chosen; returns "sweep#count". */
        private const val SWEEP_JS = "window.__slSweep ? ('sweep#' + __slSweep()) : 'sweep#0'"

        /**
         * Runs in EVERY frame at document start (no Java bridge, so untrusted frames get nothing
         * from the app). Once a <video> exists, finds elements stacked above it that look like ad
         * overlays and switches them off. Deliberately conservative; an element is only touched when
         * ALL of these hold:
         *  - it (or the highest positioned ancestor that does not contain the video) is
         *    position:absolute/fixed with z-index >= 10, and covers >= 60% of the visible video;
         *  - it is not the video, not an ancestor of the video, and not player chrome (class/id
         *    looks like controls/progress/volume/poster/..., or it holds a slider/progress bar);
         *  - and it either contains a link to another domain, contains/is an <iframe>, or is a
         *    transparent click catcher (clear background, no text, no icons/buttons/images).
         * Also: a click that lands on a paused video and does not start it gets a play() nudge.
         */
        internal const val OVERLAY_JS = """
            (function(){
              if (window.__slOv) return; window.__slOv = true;
              var done = 0, MAXN = 40;
              var SKIP = /control|ctrl|toolbar|seek|progress|volume|timeline|vjs-|plyr|jw-|mejs|ytp-|shaka|dplayer|artplayer|poster|spinner|loading|subtitle|caption|cue|settings|menu|quality/i;
              var myHost = (location.hostname || '').replace(/^www\./, '');
              function cls(e){
                var c = (typeof e.className === 'string') ? e.className : ((e.getAttribute && e.getAttribute('class')) || '');
                return c + ' ' + (e.id || '');
              }
              function isPos(cs){ return cs.position === 'absolute' || cs.position === 'fixed'; }
              function sameSite(h){
                return h === myHost || h.slice(-(myHost.length + 1)) === '.' + myHost || myHost.slice(-(h.length + 1)) === '.' + h;
              }
              function hostOf(a){
                try { return new URL(a.href, location.href).hostname.replace(/^www\./, ''); } catch (e) { return ''; }
              }
              function alpha(c){
                if (!c || c === 'transparent') return 0;
                var m = /rgba?\(([^)]*)\)/.exec(c);
                if (!m) return 1;
                var x;
                if (m[1].indexOf('/') >= 0) x = parseFloat(m[1].split('/')[1]);
                else { var p = m[1].split(','); x = p.length > 3 ? parseFloat(p[3]) : 1; }
                return isNaN(x) ? 1 : x;
              }
              function overlap(r, v){
                var w = Math.min(r.right, v.right) - Math.max(r.left, v.left);
                var h = Math.min(r.bottom, v.bottom) - Math.max(r.top, v.top);
                if (w <= 0 || h <= 0) return 0;
                return (w * h) / (v.width * v.height);
              }
              function kindOf(r){
                if (r.tagName === 'IFRAME' || r.querySelector('iframe')) return 'iframe';
                var as = r.tagName === 'A' ? [r] : [];
                var q = r.querySelectorAll('a[href]');
                for (var i = 0; i < q.length && i < 60; i++) as.push(q[i]);
                for (var j = 0; j < as.length; j++) {
                  var h = hostOf(as[j]);
                  if (h && !sameSite(h)) return 'link';
                }
                var cs = getComputedStyle(r);
                if (parseFloat(cs.opacity) <= 0.05) return 'catcher';
                if (alpha(cs.backgroundColor) <= 0.05 && cs.backgroundImage === 'none' &&
                    (r.innerText || '').trim() === '' &&
                    !r.querySelector('img,svg,video,canvas,button,input,picture,audio,progress,object,embed')) return 'catcher';
                return '';
              }
              function findRoot(c, v){
                var root = null, z = -1, n = c;
                while (n && n !== document.body && n !== document.documentElement) {
                  if (n.contains(v)) break;
                  var cs = getComputedStyle(n);
                  if (isPos(cs)) {
                    root = n;
                    var zi = parseInt(cs.zIndex, 10);
                    if (!isNaN(zi) && zi > z) z = zi;
                  }
                  n = n.parentElement;
                }
                return root ? {root: root, z: z} : null;
              }
              function hide(el, kind){
                try {
                  el.style.setProperty('pointer-events', 'none', 'important');
                  el.style.setProperty('visibility', 'hidden', 'important');
                  el.style.setProperty('opacity', '0', 'important');
                } catch (e) { return; }
                el.__slHid = kind;
                done++;
                try { console.log('SL-OV:hidden:' + kind); } catch (e) {}
              }
              function handle(c, v, vr){
                if (c === v || c.contains(v) || v.contains(c)) return;
                var f = findRoot(c, v);
                if (!f || f.z < 10) return;
                var root = f.root;
                if (root.__slHid) { hide(c, root.__slHid); return; }   // a child that kept itself visible
                if (SKIP.test(cls(root)) || SKIP.test(cls(c))) return;
                if (root.querySelector('input[type=range],[role=slider],progress')) return;
                var o = Math.max(overlap(root.getBoundingClientRect(), vr), overlap(c.getBoundingClientRect(), vr));
                if (o < 0.6) return;
                var kind = kindOf(root);
                if (!kind) return;
                hide(root, kind);
              }
              var FR = [0.15, 0.5, 0.85];
              function pass(){
                if (done >= MAXN) return;
                var vs = document.getElementsByTagName('video');
                for (var vi = 0; vi < vs.length; vi++) {
                  var v = vs[vi], r = v.getBoundingClientRect();
                  var L = Math.max(0, r.left), T = Math.max(0, r.top);
                  var R = Math.min(window.innerWidth, r.right), B = Math.min(window.innerHeight, r.bottom);
                  var W = R - L, H = B - T;
                  if (W * H < 20000) continue;
                  var vr = {left: L, top: T, right: R, bottom: B, width: W, height: H};
                  for (var a = 0; a < 3; a++) for (var b = 0; b < 3; b++) {
                    var st = document.elementsFromPoint(L + W * FR[a], T + H * FR[b]);
                    var idx = st.indexOf(v);
                    for (var k = 0; k < idx; k++) handle(st[k], v, vr);   // idx <= 0: not covered
                  }
                }
              }
              setInterval(function(){ try { pass(); } catch (e) {} }, 700);

              // A click that reaches a paused video must start it. Remember whether it was paused BEFORE
              // the page's own handlers run, so a click that legitimately pauses it is never undone.
              document.addEventListener('click', function(e){
                try {
                  var vs = document.getElementsByTagName('video'), best = null, ba = 0;
                  for (var i = 0; i < vs.length; i++) {
                    var r = vs[i].getBoundingClientRect();
                    if (e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom && r.width * r.height > ba) {
                      ba = r.width * r.height; best = vs[i];
                    }
                  }
                  if (!best || !best.paused) return;
                  var t = e.target;
                  if (t && t.closest && t.closest('a[href],button,input,select,textarea,[role=button],[role=slider]')) return;
                  var v = best;
                  setTimeout(function(){
                    try {
                      if (v.paused) { if (!window.__slKeepMuted) v.muted = false; var p = v.play(); if (p && p.catch) p.catch(function(){}); }
                    } catch (x) {}
                  }, 350);
                } catch (x) {}
              }, true);
            })();
        """
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var pointer: PointerView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var focusTries = 0
    private var hintShown = false
    private var manualPick = false
    private var lastHidden = -1
    private var overlayLogs = 0
    private var pointerMode = false
    private var pointerX = -1f
    private var pointerY = -1f

    private val handler: (String, String?) -> Unit = { c, _ ->
        when (c) {
            "toggle", "play", "pause" -> tapCenter()
            "reload" -> {
                focusTries = 0
                manualPick = false
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
        // Run the unmute helper and the overlay remover inside every frame, including the player's
        // own frame from another site, which the app can't otherwise reach.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, UNMUTE_JS, setOf("*"))
            Report.log("Site player: sound helper added to every frame")
            WebViewCompat.addDocumentStartJavaScript(web, OVERLAY_JS, setOf("*"))
            Report.log("Site player: overlay remover added to every frame")
        } else {
            Report.log("Site player: this TV's WebView can't add the sound helper inside frames; update Android System WebView")
        }
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
                pointer.bringToFront()
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCallback = null
            }

            // The overlay remover reports what it hid with a fixed-format console line. Only the
            // three known kinds are accepted, so a page can't write arbitrary text into the report.
            override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                val t = m?.message()
                if (t != null && t.startsWith("SL-OV:hidden:")) {
                    val kind = t.removePrefix("SL-OV:hidden:")
                    if ((kind == "link" || kind == "iframe" || kind == "catcher") && overlayLogs++ < 8) {
                        val what = when (kind) {
                            "link" -> "ad link"
                            "iframe" -> "embedded frame"
                            else -> "invisible click catcher"
                        }
                        Report.log("Hid an overlay covering the video ($what)")
                    }
                    return true
                }
                return super.onConsoleMessage(m)
            }
        }
        web.webViewClient = object : SafeWebViewClient(url, allowVideoAds = true) {
            override fun onLoaded() {
                focusTries = 0
                manualPick = false
                lastHidden = -1
                main.removeCallbacks(focusTick)
                main.post(focusTick)
            }
        }
        root.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        pointer = PointerView(this)
        pointer.visibility = View.GONE
        root.addView(pointer, FrameLayout.LayoutParams(pointer.sizePx, pointer.sizePx))

        val host = Report.host(url)
        Report.log("Opened the site's own player")
        Session.mode = "site"
        Session.title = host
        Session.snapshot = null
        Session.commandHandler = handler
        AppStatus.text = "Playing $host (site's own player)"

        val prefs = getSharedPreferences("site_player", MODE_PRIVATE)
        if (!prefs.getBoolean("pointer_hint_shown", false)) {
            prefs.edit().putBoolean("pointer_hint_shown", true).apply()
            Toast.makeText(this, "Press ▲ for a pointer", Toast.LENGTH_LONG).show()
        }
        web.loadUrl(url)
    }

    private val focusTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            // Choose the player for the first ~11 ticks (until the user picks one by hand); after
            // that only re-hide anything the page added around it.
            val choosing = !manualPick && focusTries <= 10
            focusTries++
            web.evaluateJavascript(if (choosing) FOCUS_JS else SWEEP_JS) { r ->
                val raw = r?.trim('"') ?: "none"
                val res = raw.substringBefore('#')
                val hidden = raw.substringAfter('#', "").toIntOrNull()
                if (hidden != null && hidden != lastHidden) {
                    lastHidden = hidden
                    if (hidden > 0) Report.log("Hid $hidden other element(s) on the page around the player")
                }
                if (res.startsWith("VIDEO") || res.startsWith("IFRAME")) {
                    val count = res.substringAfter('|', "1").toIntOrNull() ?: 1
                    Report.log("Filled the screen with the page's ${if (res.startsWith("VIDEO")) "video" else "embedded player"}" +
                        if (count > 1) " (best of $count; ◀ ▶ on the remote switches)" else "")
                    if (count > 1 && !hintShown) {
                        hintShown = true
                        Toast.makeText(this@SitePlayerActivity, "Wrong part of the page? Press ◀ ▶ to switch", Toast.LENGTH_LONG).show()
                    }
                }
                if (res == "manual") manualPick = true
                main.postDelayed(this, if (choosing && !manualPick) 2500 else 3000)
            }
        }
    }

    private fun tapCenter() {
        val target: View = customView ?: web
        if (target.width == 0) return
        tapAt(target.width / 2f, target.height / 2f)
    }

    private fun tapAt(x: Float, y: Float) {
        val target: View = customView ?: web
        if (target.width == 0) return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /** ◀ ▶: cycle which part of the page fills the screen. */
    private fun cycle(step: Int) {
        web.evaluateJavascript("window.__slNext ? __slNext($step) : '0/0'") { r ->
            val raw = r?.trim('"') ?: "0/0"
            val pos = raw.substringBefore('#')
            val hidden = raw.substringAfter('#', "").toIntOrNull()
            if (pos != "0/0") {
                manualPick = true
                Report.log("Switched what fills the screen to $pos")
                if (hidden != null && hidden != lastHidden) {
                    lastHidden = hidden
                    Report.log("Now $hidden other element(s) on the page hidden around the player")
                }
                Toast.makeText(this, "Showing ${pos.replace("/", " of ")}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- Pointer mode -------------------------------------------------------------------------

    private fun setPointerMode(on: Boolean) {
        if (on == pointerMode) return
        if (on) {
            if (root.width == 0) return
            if (pointerX < 0f) {
                pointerX = root.width / 2f
                pointerY = root.height / 2f
            }
            placePointer()
            pointer.visibility = View.VISIBLE
            pointer.bringToFront()
            Toast.makeText(this, "Pointer: arrows move, OK clicks, Back exits", Toast.LENGTH_SHORT).show()
        } else {
            pointer.visibility = View.GONE
        }
        pointerMode = on
        Report.log(if (on) "Pointer mode on" else "Pointer mode off")
    }

    private fun placePointer() {
        pointer.translationX = pointerX - pointer.tipPx
        pointer.translationY = pointerY - pointer.tipPx
    }

    /** One arrow-key event in pointer mode. Step grows from ~8dp to ~40dp the longer the key is held. */
    private fun movePointer(keyCode: Int, event: KeyEvent) {
        val d = resources.displayMetrics.density
        val heldMs = (event.eventTime - event.downTime).coerceAtLeast(0L)
        val step = (8f + 32f * minOf(1f, heldMs / 1500f)) * d
        var dx = 0f
        var dy = 0f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = step
            KeyEvent.KEYCODE_DPAD_UP -> dy = -step
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = step
        }
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0f || h <= 0f) return
        pointerX = (pointerX + dx).coerceIn(0f, w - 1f)
        pointerY = (pointerY + dy).coerceIn(0f, h - 1f)
        placePointer()
        // Pushing against the top or bottom edge scrolls the page instead.
        val edge = 4f * d
        if ((dy < 0f && pointerY <= edge) || (dy > 0f && pointerY >= h - 1f - edge)) {
            web.evaluateJavascript("window.scrollBy(0, ${dy.toInt()})", null)
        }
    }

    private fun clickPointer() {
        tapAt(pointerX, pointerY)
        pointer.clicking = true
        main.postDelayed({ pointer.clicking = false }, 150)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (pointerMode) {
            when (code) {
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) setPointerMode(false)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) movePointer(code, event)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP) clickPointer()
                    return true
                }
            }
        }
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                if (customView != null) {
                    if (event.action == KeyEvent.ACTION_UP) customCallback?.onCustomViewHidden()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (customView == null) {
                    if (event.action == KeyEvent.ACTION_UP) setPointerMode(true)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (customView == null) {
                    if (event.action == KeyEvent.ACTION_UP) cycle(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
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

    /** The on-screen mouse pointer: a white arrow with a black outline; the tip is the click point. */
    private class PointerView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        val tipPx = 16f * d
        val sizePx = (48f * d).toInt()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeJoin = Paint.Join.ROUND
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2196F3.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
        }
        private val arrow = Path().apply {
            moveTo(0f, 0f); lineTo(0f, 17f * d); lineTo(4.5f * d, 13f * d); lineTo(7.5f * d, 20f * d)
            lineTo(10f * d, 19f * d); lineTo(7f * d, 12f * d); lineTo(12.5f * d, 12f * d); close()
        }

        var clicking = false
            set(v) { field = v; invalidate() }

        init {
            isFocusable = false
            isClickable = false
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(tipPx, tipPx)
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, line)
            if (clicking) canvas.drawCircle(0f, 0f, 14f * d, ring)
            canvas.restore()
        }
    }
}
