package com.streamlink.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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

    /** Card/tile/button background: [normal] fill, and when focused a lighter fill with a 3dp accent ring. */
    fun focusable(ctx: Context, normal: Int = SURFACE, focused: Int = SURFACE_FOCUS, radiusDp: Float = 12f): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, focused, radiusDp, ACCENT, 3f))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(ctx, focused, radiusDp, ACCENT, 3f))
            addState(intArrayOf(), rounded(ctx, normal, radiusDp))
        }

    /** Primary button: accent fill with dark text; focused adds a light ring. */
    fun primaryButton(ctx: Context): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, ACCENT, 8f, Color.WHITE, 3f))
        addState(intArrayOf(), rounded(ctx, ACCENT, 8f))
    }

    /** Grows a view slightly while it has focus (TV convention). Call once per view. */
    fun growOnFocus(v: View, scale: Float = 1.04f) {
        val prev = v.onFocusChangeListener
        v.setOnFocusChangeListener { view, has ->
            prev?.onFocusChange(view, has)
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(120).start()
        }
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
