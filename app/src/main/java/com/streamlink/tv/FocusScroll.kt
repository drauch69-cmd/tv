package com.streamlink.tv

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.HorizontalScrollView
import android.widget.ScrollView

/**
 * Scroll views for screens with focusable cards that grow and draw a ring. They never clip to their padding
 * (the padding is room for the grown card), and when focus scrolls they keep [Ui.FOCUS_PAD_DP] of that room
 * visible around the card, so the scaled outline is never cut at the edge.
 */
class FocusScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ScrollView(ctx, attrs) {
    init { clipToPadding = false }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val p = Ui.dp(context, Ui.FOCUS_PAD_DP)
        rect.inset(-p, -p)
        return super.computeScrollDeltaToGetChildRectOnScreen(rect)
    }
}

class FocusHScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : HorizontalScrollView(ctx, attrs) {
    init { clipToPadding = false }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val p = Ui.dp(context, Ui.FOCUS_PAD_DP)
        rect.inset(-p, -p)
        return super.computeScrollDeltaToGetChildRectOnScreen(rect)
    }
}
