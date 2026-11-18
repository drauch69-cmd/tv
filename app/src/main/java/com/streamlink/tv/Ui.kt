package com.streamlink.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * The shared look (see the "StreamLink UI mockups" canvas): dark background, one blue accent for
 * focus, red only for LIVE. Sizes are dp/sp for a 960x540 dp TV screen (the mockups' 1280 px x 0.75).
 */
object Ui {
    const val BG = 0xFF0E1116.toInt()
    const val SURFACE = 0xFF171B22.toInt()
    const val SURFACE_FOCUS = 0xFF1B2230.toInt()
    const val CHIP = 0xFF232A35.toInt()
    const val BORDER = 0xFF2A303A.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val TEXT_2 = 0xFFA3ABB7.toInt()   // secondary, passes 4.5:1 on BG and SURFACE
    const val TEXT_3 = 0xFF8A93A0.toInt()   // hints
    const val ACCENT = 0xFF4DA3FF.toInt()
    const val ON_ACCENT = 0xFF0E1116.toInt()
    const val LIVE = 0xFFD93A40.toInt()
    const val LIVE_TEXT = 0xFFFF8A8E.toInt() // "2 live" in text
    const val NEUTRAL_BADGE = 0xFF2E3A4D.toInt()
    const val WARN = 0xFFFFC66B.toInt()

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())

    fun rounded(ctx: Context, color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeDp > 0f) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    private val FOCUS_STATES = intArrayOf(android.R.attr.state_focused)
    private val PRESSED_STATES = intArrayOf(android.R.attr.state_pressed)

    /** Fill only (no ring): [normal] fill, lighter [focused] fill. The ring is the foreground, see [focusCard]. */
    fun focusable(ctx: Context, normal: Int = SURFACE, focused: Int = SURFACE_FOCUS, radiusDp: Float = 12f): Drawable =
        StateListDrawable().apply {
            addState(FOCUS_STATES, rounded(ctx, focused, radiusDp))
            addState(PRESSED_STATES, rounded(ctx, focused, radiusDp))
            addState(intArrayOf(), rounded(ctx, normal, radiusDp))
        }

    /**
     * Ring drawn on top of the content; nothing when not focused. GradientDrawable strokes a rectangle inset by half
     * the stroke width, so the whole ring lies inside the view's bounds.
     */
    private fun ringForeground(ctx: Context, ring: Int, radiusDp: Float): Drawable {
        val shape = GradientDrawable().apply {
            setColor(0)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setStroke(dp(ctx, RING_DP), ring)
        }
        return StateListDrawable().apply {
            addState(FOCUS_STATES, shape)
            addState(PRESSED_STATES, shape)
            addState(intArrayOf(), ColorDrawable(0))
        }
    }

    const val RING_DP = 3f

    /**
     * Focus look for every focusable card/tile/button: the view never changes size; its fill brightens and an accent
     * ring is drawn ON TOP as a foreground stroke inside the view's own bounds (nothing can clip it).
     */
    fun focusCard(v: View, normal: Int = SURFACE, focused: Int = SURFACE_FOCUS, radiusDp: Float = 12f) {
        val ctx = v.context
        v.background = focusable(ctx, normal, focused, radiusDp)
        v.foreground = ringForeground(ctx, ACCENT, radiusDp)
        if (android.os.Build.VERSION.SDK_INT >= 26) v.defaultFocusHighlightEnabled = false
    }

    /** Primary button: accent fill with dark text; focused draws a white ring on top. */
    fun focusRing(v: View, radiusDp: Float, ring: Int = ACCENT) {
        v.foreground = ringForeground(v.context, ring, radiusDp)
        if (android.os.Build.VERSION.SDK_INT >= 26) v.defaultFocusHighlightEnabled = false
    }

    fun primaryCard(v: View, radiusDp: Float = 8f) {
        val ctx = v.context
        v.background = rounded(ctx, ACCENT, radiusDp)
        v.foreground = ringForeground(ctx, Color.WHITE, radiusDp)
        if (android.os.Build.VERSION.SDK_INT >= 26) v.defaultFocusHighlightEnabled = false
    }

    fun text(ctx: Context, s: CharSequence, sp: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        }

    /** A small pill: red "LIVE", or a neutral "IN 12 MIN" / "7:30 PM". */
    fun badge(ctx: Context, s: String, live: Boolean, sp: Float = 11f): TextView =
        text(ctx, s.uppercase(), sp, if (live) Color.WHITE else TEXT, bold = true).apply {
            letterSpacing = 0.06f
            background = rounded(ctx, if (live) LIVE else NEUTRAL_BADGE, 5f)
            setPadding(dp(ctx, 7), dp(ctx, 3), dp(ctx, 7), dp(ctx, 3))
            gravity = Gravity.CENTER
        }

    /** Section label like "LIVE NOW" / "LATER". */
    fun sectionLabel(ctx: Context, s: String, color: Int = TEXT_2): TextView =
        text(ctx, s.uppercase(), 11f, color, bold = true).apply { letterSpacing = 0.08f }
}
