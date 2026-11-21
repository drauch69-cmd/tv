package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFadeTest {
    @Test fun leftEdgeIsAlwaysSolidAndFadeEndsAtWidth() {
        val a = PhotoFade.alphas(0.5f, 0.5f)
        assertEquals(255, a.first())
        assertEquals(0, a.last())
        assertEquals(0, a[6]) // halfway across = the fade's end
        assertTrue(a[3] in 1..254)
    }

    @Test fun darkerStaysDarkerLonger() {
        val soft = PhotoFade.alphas(0.75f, 0f)
        val dark = PhotoFade.alphas(0.75f, 1f)
        assertTrue(dark[3] > soft[3])
    }

    @Test fun wideReachesFurther() {
        assertTrue(PhotoFade.alphas(1f, 0.5f)[9] > 0)
        assertEquals(0, PhotoFade.alphas(0.5f, 0.5f)[9])
    }

    @Test fun outOfRangeValuesAreClamped() {
        assertEquals(PhotoFade.alphas(0.1f, 1f).toList(), PhotoFade.alphas(-3f, 4f).toList())
    }
}
