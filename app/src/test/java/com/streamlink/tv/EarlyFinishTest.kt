package com.streamlink.tv

import com.streamlink.tv.EarlyFinish.Item
import com.streamlink.tv.EarlyFinish.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EarlyFinishTest {
    private fun i(u: String, s: State, embedded: Boolean = false, possible: Boolean = false) = Item(u, embedded, possible, s)

    @Test fun nothingCheckedYetKeepsWaiting() = assertNull(EarlyFinish.keep(listOf(i("a", State.PENDING))))
    @Test fun failedFirstKeepsWaiting() = assertNull(EarlyFinish.keep(listOf(i("a", State.BAD))))
    @Test fun goodDirectFinishesAtOnce() = assertEquals(setOf("a"), EarlyFinish.keep(listOf(i("a", State.GOOD))))

    @Test fun pendingSiblingIsDropped() =
        assertEquals(setOf("a"), EarlyFinish.keep(listOf(i("a", State.GOOD), i("b", State.PENDING))))

    @Test fun finishedSiblingIsKept() =
        assertEquals(setOf("a", "b"), EarlyFinish.keep(listOf(i("a", State.GOOD), i("b", State.GOOD))))

    @Test fun failedSiblingIsLeftOut() =
        assertEquals(setOf("b"), EarlyFinish.keep(listOf(i("a", State.BAD), i("b", State.GOOD))))

    @Test fun embeddedCopyAloneNeverFinishesEarly() =
        assertNull(EarlyFinish.keep(listOf(i("a", State.GOOD, embedded = true))))

    @Test fun possiblePlaylistAloneNeverFinishesEarly() =
        assertNull(EarlyFinish.keep(listOf(i("a", State.GOOD, possible = true))))

    @Test fun goodEmbeddedIsKeptBesideGoodDirect() =
        assertEquals(setOf("a", "e"), EarlyFinish.keep(listOf(i("a", State.GOOD), i("e", State.GOOD, embedded = true))))
}
