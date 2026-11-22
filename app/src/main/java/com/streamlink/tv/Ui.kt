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
        v.defaultFocusHighlightEnabled = false
    }

    /** Primary button: accent fill with dark text; focused draws a white ring on top. */
    fun focusRing(v: View, radiusDp: Float, ring: Int = ACCENT) {
        v.foreground = ringForeground(v.context, ring, radiusDp)
        v.defaultFocusHighlightEnabled = false
    }

    fun primaryCard(v: View, radiusDp: Float = 8f) {
        val ctx = v.context
        v.background = rounded(ctx, ACCENT, radiusDp)
        v.foreground = ringForeground(ctx, Color.WHITE, radiusDp)
        v.defaultFocusHighlightEnabled = false
    }

    /**
     * A bold score that always has room for 3 digits, so a new two-digit score is never cut to one (user, 2026-11-20).
     * [gravity]: which side of that room the digits sit on.
     */
    fun scoreText(ctx: Context, s: CharSequence, sp: Float, color: Int, gravity: Int = android.view.Gravity.START): TextView =
        text(ctx, s, sp, color, bold = true).apply {
            maxLines = 1
            this.gravity = gravity or android.view.Gravity.CENTER_VERTICAL
            minWidth = kotlin.math.ceil(paint.measureText("888")).toInt()
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

    // ---- Polished look (2026-11-20): dark-fade cards, stream count chip, team logos (Logos.kt) ----

    const val FADE_TOP = 0xFF1E242E.toInt()
    const val FADE_BOTTOM = 0xFF14181E.toInt()
    const val FADE_TOP_FOCUS = 0xFF26303D.toInt()
    const val FADE_BOTTOM_FOCUS = 0xFF1A2029.toInt()

    private fun fade(ctx: Context, top: Int, bottom: Int, radiusDp: Float) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply { cornerRadius = dp(ctx, radiusDp).toFloat() }

    /**
     * Laid over a featured card's photo: the card colour at the photo's left edge, fading out at once and gone by
     * about 55% of the way across, so only a thin edge is dark and the photo has no hard line (user, 2026-11-20).
     */
    fun photoFade(width: Float = PhotoFade.DEFAULT_WIDTH, dark: Float = PhotoFade.DEFAULT_DARK): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            PhotoFade.alphas(width, dark).map { (it shl 24) or (FADE_BOTTOM and 0xFFFFFF) }.toIntArray())

    /** Dark from the bottom up over a whole-card photo, so text at the bottom reads (sport screen side card). */
    fun photoFadeUp(): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
        FADE_BOTTOM, 0xF014181E.toInt(), 0xD814181E.toInt(), 0xA014181E.toInt(), 0x5014181E, 0x1014181E, 0x0014181E,
    ))

    /** Card look: a subtle dark-to-less-dark fade, a shade lighter when focused, with the accent ring on top. */
    fun fadeCard(v: View, radiusDp: Float = 12f) {
        val ctx = v.context
        v.background = StateListDrawable().apply {
            addState(FOCUS_STATES, fade(ctx, FADE_TOP_FOCUS, FADE_BOTTOM_FOCUS, radiusDp))
            addState(PRESSED_STATES, fade(ctx, FADE_TOP_FOCUS, FADE_BOTTOM_FOCUS, radiusDp))
            addState(intArrayOf(), fade(ctx, FADE_TOP, FADE_BOTTOM, radiusDp))
        }
        v.foreground = ringForeground(ctx, ACCENT, radiusDp)
        v.defaultFocusHighlightEnabled = false
    }

    /** The stream count pill: a small broadcast mark and the number; grey "0" when there are none. */
    fun streamChip(ctx: Context, n: Int, sp: Float = 11f): TextView {
        val color = if (n > 0) 0xFF9CCBFF.toInt() else TEXT_3
        return text(ctx, "$n", sp, color, bold = true).apply {
            val dot = android.graphics.drawable.LayerDrawable(arrayOf<Drawable>(
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(ctx, 1.5f), color); setSize(dp(ctx, 11), dp(ctx, 11)) },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(dp(ctx, 4), dp(ctx, 4)) },
            )).apply { setLayerInset(1, dp(ctx, 3.5f), dp(ctx, 3.5f), dp(ctx, 3.5f), dp(ctx, 3.5f)) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            compoundDrawablePadding = dp(ctx, 5)
            gravity = Gravity.CENTER_VERTICAL
            background = if (n > 0) rounded(ctx, 0x244DA3FF, 99f) else rounded(ctx, 0, 99f, 0xFF3A4250.toInt(), 1f)
            setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 9), dp(ctx, 3))
            contentDescription = if (n == 1) "1 stream" else "$n streams"
        }
    }
}
