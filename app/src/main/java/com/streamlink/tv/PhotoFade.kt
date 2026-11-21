package com.streamlink.tv

import kotlin.math.pow

/**
 * The dark fade over the featured card's photo, set per sport from the phone (user, 2026-11-20).
 * [width]: how far into the photo the fade reaches (fraction of the photo's width).
 * [dark]: how dark it stays on the way (0 = falls away quickly, 1 = stays dark most of the way).
 * The photo's own left edge is always fully dark, so it meets the card with no seam.
 */
object PhotoFade {
    const val DEFAULT_WIDTH = 0.75f
    const val DEFAULT_DARK = 0.5f
    const val MIN_WIDTH = 0.1f
    const val STOPS = 13

    /** The curve's steepness for [dark]: 3.5 (quick) down to 0.6 (stays dark). The default 0.5 gives about 2. */
    fun power(dark: Float): Double = 3.5 - 2.9 * dark.coerceIn(0f, 1f)

    /** Alpha (0..255) at [STOPS] evenly spaced points from the photo's left edge to its right edge. */
    fun alphas(width: Float, dark: Float, stops: Int = STOPS): IntArray {
        val w = width.coerceIn(MIN_WIDTH, 1f)
        val k = power(dark)
        return IntArray(stops) { i ->
            val t = (i.toDouble() / (stops - 1)) / w
            if (t >= 1.0) 0 else (255 * (1 - t).pow(k)).toInt().coerceIn(0, 255)
        }
    }
}
