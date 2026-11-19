package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelFilterTest {
    @Test fun matchupsAreTwoSided() {
        assertTrue(ChannelFilter.isMatchup("Commanders vs Giants"))
        assertTrue(ChannelFilter.isMatchup("Commanders VS. Giants HD"))
        assertTrue(ChannelFilter.isMatchup("Giants at Commanders"))
        assertTrue(ChannelFilter.isMatchup("Giants @ Commanders"))
        assertTrue(ChannelFilter.isMatchup("Ohio State v Michigan"))
        assertTrue(ChannelFilter.isMatchup("Team A versus Team B"))
    }

    @Test fun channelsAreNotMatchups() {
        assertFalse(ChannelFilter.isMatchup("NFL Network"))
        assertFalse(ChannelFilter.isMatchup("NBA TV - HD"))
        assertFalse(ChannelFilter.isMatchup("vs Giants")) // nothing on the left
        assertFalse(ChannelFilter.isMatchup("Giants vs")) // nothing on the right
        assertFalse(ChannelFilter.isMatchup("Giants vs 24")) // no letters on the right
        assertFalse(ChannelFilter.isMatchup("Atlanta Falcons")) // "at" only as a whole token
        assertFalse(ChannelFilter.isMatchup(""))
        assertTrue(ChannelFilter.isChannel("Fox Footy"))
        assertFalse(ChannelFilter.isChannel("Fox vs Footy"))
        assertFalse(ChannelFilter.isChannel("  "))
    }

    @Test fun wholeWordsIgnoreCase() {
        val w = ChannelFilter.DEFAULT_WORDS
        assertTrue(ChannelFilter.matchesWords("NBA TV", w))
        assertTrue(ChannelFilter.matchesWords("espn 2 HD", w))
        assertTrue(ChannelFilter.matchesWords("US | ESPN+", w))
        assertTrue(ChannelFilter.matchesWords("NFL-Network", w))
        assertFalse(ChannelFilter.matchesWords("Sunbanba", w))
        assertFalse(ChannelFilter.matchesWords("NFLX Originals", w))
        assertFalse(ChannelFilter.matchesWords("Fox Footy", w))
        assertFalse(ChannelFilter.matchesWords("ESPN", emptyList()))
    }

    @Test fun redZoneAlsoMatchesWithASpace() {
        val w = ChannelFilter.DEFAULT_WORDS
        assertTrue(ChannelFilter.matchesWords("NFL RedZone", listOf("RedZone")))
        assertTrue(ChannelFilter.matchesWords("Sunday Red Zone", listOf("RedZone")))
        assertTrue(ChannelFilter.matchesWords("redzone", listOf("RedZone")))
        assertTrue(ChannelFilter.matchesWords("Red Zone", w))
        assertFalse(ChannelFilter.matchesWords("Red Zones", listOf("RedZone")))
        // a plain lower-case word gets no optional space
        assertFalse(ChannelFilter.matchesWords("Sky Sports", listOf("skysports")))
    }

    @Test fun wordsWithSpacesAndSpecials() {
        assertTrue(ChannelFilter.matchesWords("Sky   Sports Golf", listOf("sky sports")))
        assertFalse(ChannelFilter.matchesWords("Anything", listOf("(", "   ", ".*")))
        ChannelFilter.matchesWords("C++ TV", listOf("C++", "[a", "\\")) // regex characters never crash
    }

    @Test fun parseWords() {
        assertEquals(listOf("ESPN", "Red Zone", "NBA"), ChannelFilter.parseWords(" ESPN , Red  Zone,\nNBA,, espn;"))
        assertEquals(emptyList<String>(), ChannelFilter.parseWords("  , \n"))
        assertEquals(ChannelFilter.MAX_WORDS, ChannelFilter.parseWords((1..50).joinToString(",") { "w$it" }).size)
        assertEquals(ChannelFilter.MAX_WORD_LENGTH, ChannelFilter.parseWords("x".repeat(100)).single().length)
    }

    @Test fun inLiveNow() {
        val w = listOf("ESPN")
        assertTrue(ChannelFilter.inLiveNow("Anything vs Else", true, w))
        assertTrue(ChannelFilter.inLiveNow("ESPN 2", false, w))
        assertFalse(ChannelFilter.inLiveNow("ESPN vs Fox", false, w)) // two-sided and unmatched: not a channel
        assertFalse(ChannelFilter.inLiveNow("Fox Footy", false, w))
    }
}
