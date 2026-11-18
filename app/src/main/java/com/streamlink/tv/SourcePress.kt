package com.streamlink.tv

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView

/**
 * Presses one of the page's own "source" buttons (found by structure, see assets/source-switch.js) when the
 * page's player box is empty and only starts after a button is pressed. Shared by the hidden search
 * (StreamSniffer) and the site player (SitePlayerActivity). Call from the main thread.
 */
internal object SourcePress {
    /** [index] is 0-based; [count] is how many source buttons the page has. */
    class Pressed(val index: Int, val count: Int)

    @Volatile private var script: String? = null

    private fun loadScript(context: Context): String? {
        script?.let { return it }
        return try {
            val text = context.applicationContext.assets.open("source-switch.js").bufferedReader().use { it.readText() }
            script = text
            text
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Finds the page's source buttons and presses button [wanted] (0-based; the one the page marks as active is
     * skipped in favour of the next). [stillWanted] is asked again after the find step; false means do nothing.
     * [onDone] gets what was pressed, or null when nothing was (no script, no buttons, no such button, no longer wanted).
     * If the click script fails, a tap on the button's position is tried instead.
     */
    fun press(context: Context, wv: WebView, wanted: Int, stillWanted: () -> Boolean, onDone: (Pressed?) -> Unit) {
        val js = loadScript(context)
        if (js == null) {
            onDone(null)
            return
        }
        wv.evaluateJavascript("($js)('find')") { raw ->
            val sw = LinkOrder.parseFind(raw)
            if (sw == null || !stillWanted()) {
                onDone(null)
                return@evaluateJavascript
            }
            var idx = wanted
            if (idx == sw.active) idx++
            if (idx < 0 || idx >= sw.count) {
                onDone(null)
                return@evaluateJavascript
            }
            wv.evaluateJavascript("($js)('click $idx')") { r2 ->
                val ok = try {
                    val first = MiniJson.parse(r2 ?: "null")
                    ((if (first is String) MiniJson.parse(first) else first) as? Map<*, *>)?.get("ok") == true
                } catch (_: Exception) {
                    false
                }
                if (!ok) tapAt(wv, sw, idx)
                onDone(Pressed(idx, sw.count))
            }
        }
    }

    private fun tapAt(wv: WebView, sw: Switcher, i: Int) {
        if (sw.iw <= 0f || wv.width <= 0) return
        val scale = wv.width / sw.iw
        val x = sw.xs[i] * scale
        val y = sw.ys[i] * scale
        if (x <= 0f || y <= 0f || y >= wv.height) return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        wv.dispatchTouchEvent(down)
        wv.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }
}
