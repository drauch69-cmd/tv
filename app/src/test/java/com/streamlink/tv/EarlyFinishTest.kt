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
    @Test fun goodDirectFinishesAtOnce() = assertEquals(setOf("a"), EarlyFinish.keep(listOf(i("a", State.GOOD_ADAPTIVE))))

    @Test fun pendingSiblingIsDropped() =
        assertEquals(setOf("a"), EarlyFinish.keep(listOf(i("a", State.GOOD_ADAPTIVE), i("b", State.PENDING))))

    @Test fun finishedSiblingIsKept() =
        assertEquals(setOf("a", "b"), EarlyFinish.keep(listOf(i("a", State.GOOD_ADAPTIVE), i("b", State.GOOD_ADAPTIVE))))

    @Test fun failedSiblingIsLeftOut() =
        assertEquals(setOf("b"), EarlyFinish.keep(listOf(i("a", State.BAD), i("b", State.GOOD_ADAPTIVE))))

    @Test fun embeddedCopyAloneNeverFinishesEarly() =
        assertNull(EarlyFinish.keep(listOf(i("a", State.GOOD_ADAPTIVE, embedded = true))))

    @Test fun possiblePlaylistAloneNeverFinishesEarly() =
        assertNull(EarlyFinish.keep(listOf(i("a", State.GOOD_ADAPTIVE, possible = true))))

    @Test fun goodEmbeddedIsKeptBesideGoodDirect() =
        assertEquals(setOf("a", "e"), EarlyFinish.keep(listOf(i("a", State.GOOD_ADAPTIVE), i("e", State.GOOD_ADAPTIVE, embedded = true))))

    @Test fun singleQualityPassKeepsWaiting() = assertNull(EarlyFinish.keep(listOf(i("a", State.GOOD_SINGLE))))

    @Test fun adaptiveFinishesAndKeepsFinishedSingle() =
        assertEquals(setOf("a", "b"), EarlyFinish.keep(listOf(i("a", State.GOOD_SINGLE), i("b", State.GOOD_ADAPTIVE))))
}
