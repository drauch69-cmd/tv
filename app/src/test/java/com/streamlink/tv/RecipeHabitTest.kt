package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeHabitTest {
    private val now = 1_800_000_000_000L

    private fun learned(at: Long = now) = SiteHabits.Habit("player", 0.5f, 0.4f, 1, 1, 4, 6.5f, 0, at)

    @Test fun recipeLineRoundTrips() {
        val h = SiteHabits.Habit("player", 0.523f, 0.41f, 2, 1, 4, 6.5f, 0, now)
        val line = SiteHabits.recipeLine("www.Example.com", h)
        assertEquals("""{"host":"example.com","habit":{"tap":"player","x":0.523,"y":0.410,"taps":2,"button":2,"buttons":4,"secs":6.5}}""", line)
        val r = ListRecipes.parse("""{"sites":[$line]}""")
        assertEquals(1, r.size)
        val back = r[0].habit!!
        assertNull(r[0].item)
        assertEquals("player", back.tapKind)
        assertEquals(0.523f, back.fx, 0.0001f)
        assertEquals(0.41f, back.fy, 0.0001f)
        assertEquals(2, back.taps)
        assertEquals(1, back.srcIndex)
        assertEquals(4, back.srcCount)
        assertEquals(6.5f, back.secs, 0.01f)
        assertTrue(back.shipped)
    }

    @Test fun recipeLineWithOnlyAButtonOrOnlyATap() {
        val src = SiteHabits.Habit("", 0f, 0f, 0, 0, 3, 4f, 0, now)
        assertEquals("""{"host":"a.example","habit":{"button":1,"buttons":3,"secs":4.0}}""", SiteHabits.recipeLine("a.example", src))
        val tap = SiteHabits.Habit("page", 0.5f, 0.5f, 1, -1, 0, 3f, 0, now)
        assertEquals("""{"host":"a.example","habit":{"tap":"page","x":0.500,"y":0.500,"taps":1,"secs":3.0}}""", SiteHabits.recipeLine("a.example", tap))
    }

    @Test fun badHabitBlocksAreSkipped() {
        for (bad in listOf(
            """{"tap":"player","x":0,"y":0.5}""", // x not strictly inside
            """{"tap":"player","x":0.5}""", // no y
            """{"tap":"elsewhere","x":0.5,"y":0.5}""",
            """{"button":3,"buttons":2}""",
            """{"button":0,"buttons":2}""",
            """{}""",
        )) {
            assertTrue(bad, ListRecipes.parse("""{"sites":[{"host":"a.example","habit":$bad}]}""").isEmpty())
        }
        // a bad habit next to a row recipe keeps the row recipe, without a habit
        val r = ListRecipes.parse("""{"sites":[{"host":"a.example","item":"li","habit":{"tap":"x"}}]}""")
        assertEquals(1, r.size)
        assertNull(r[0].habit)
    }

    @Test fun learnedWinsOverShipped() {
        val shipped = SiteHabits.fromRecipe(mapOf("button" to 2.0, "buttons" to 3.0))!!
        val l = learned()
        assertEquals(l, SiteHabits.pick(l, shipped, 0, now))
        // expired learned habit: the shipped one is used, marked shipped and fresh
        val old = learned(at = now - SiteHabits.EXPIRE_MS - 1)
        val p = SiteHabits.pick(old, shipped, 0, now)!!
        assertTrue(p.shipped)
        assertEquals(1, p.srcIndex)
        assertEquals(now, p.at)
        assertTrue(SiteHabits.usable(p, now))
        assertEquals(1, SiteHabits.pick(null, shipped, 1, now)!!.srcIndex)
        // two misses since the app started: not used any more
        assertNull(SiteHabits.pick(null, shipped, SiteHabits.DROP_AFTER_MISSES, now))
        assertNull(SiteHabits.pick(null, null, 0, now))
    }

    @Test fun learnedHabitIsNotShipped() {
        assertFalse(learned().shipped)
    }
}
