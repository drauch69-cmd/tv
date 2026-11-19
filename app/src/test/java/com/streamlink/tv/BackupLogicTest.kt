package com.streamlink.tv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.streamlink.tv.ServerMemory.LifeKind
import com.streamlink.tv.ServerMemory.Lifetime
import com.streamlink.tv.ServerMemory.Sample

class BackupLogicTest {
    private val S = 1000L
    private val M = 60 * S

    @Before @After fun clean() { BackupWatch.reset(); AutoSwitch.resetAllForTest() }

    private fun play(url: String) = Source(url, emptyMap(), listOf(""), "play", verified = true)
    private fun page(url: String, player: String = "") = Source(url, emptyMap(), emptyList(), "page", verified = false, openPage = true, playerUrl = player)

    // ---- lifetime ----

    private fun smp(age: Long, ok: Boolean, at: Long = 1000L) = Sample(age, ok, at)

    @Test fun lifetimeShortNeedsTwoEarlyFailures() {
        val one = ServerMemory.lifetimeEstimate(listOf(smp(100 * S, false)), 2000L)
        assertEquals(LifeKind.UNKNOWN, one.kind)
        val two = ServerMemory.lifetimeEstimate(listOf(smp(40 * S, true), smp(100 * S, false), smp(150 * S, false)), 2000L)
        assertEquals(LifeKind.SHORT, two.kind)
        assertEquals(40 * S, two.maxOkMs)
    }

    @Test fun lifetimeShortWithoutOkUsesHalfTheYoungestFailure() {
        val e = ServerMemory.lifetimeEstimate(listOf(smp(100 * S, false), smp(200 * S, false)), 2000L)
        assertEquals(LifeKind.SHORT, e.kind)
        assertEquals(50 * S, e.maxOkMs)
    }

    @Test fun lifetimeContradictionIsUnknown() {
        val e = ServerMemory.lifetimeEstimate(listOf(smp(100 * S, false), smp(120 * S, false), smp(5 * M, true)), 2000L)
        assertEquals(LifeKind.UNKNOWN, e.kind)
    }

    @Test fun lifetimeLongFromOldSuccess() {
        val e = ServerMemory.lifetimeEstimate(listOf(smp(30 * M, true)), 2000L)
        assertEquals(LifeKind.LONG, e.kind)
        assertEquals(LifeKind.UNKNOWN, ServerMemory.lifetimeEstimate(listOf(smp(3 * M, true)), 2000L).kind)
    }

    @Test fun lifetimeIgnoresExpiredAndTinyAges() {
        val old = smp(100 * S, false, at = 0L)
        val now = ServerMemory.EXPIRE_MS + 10
        assertEquals(LifeKind.UNKNOWN, ServerMemory.lifetimeEstimate(listOf(old, old), now).kind)
        assertEquals(LifeKind.UNKNOWN, ServerMemory.lifetimeEstimate(listOf(smp(5 * S, false), smp(8 * S, false)), 2000L).kind)
        assertTrue(ServerMemory.withSample(emptyMap(), "h", 5 * S, false, 10L).isEmpty())
    }

    @Test fun samplesStoredCappedAndRoundTrip() {
        var map: Map<String, ServerMemory.Entry> = emptyMap()
        for (i in 1..20) map = ServerMemory.withSample(map, "h", (30 + i) * S, i % 2 == 0, 1000L + i)
        assertEquals(ServerMemory.MAX_SAMPLES, map.getValue("h").samples.size)
        val back = ServerMemory.fromJson(ServerMemory.toJson(map))
        assertEquals(map.getValue("h").samples, back.getValue("h").samples)
    }

    @Test fun shortcutSkippedOnlyForOldEntriesOfShortServers() {
        val short = Lifetime(LifeKind.SHORT, 60 * S)
        assertTrue(LastAddress.skipShortcut(short, 61 * S))
        assertFalse(LastAddress.skipShortcut(short, 30 * S))
        assertFalse(LastAddress.skipShortcut(Lifetime(LifeKind.UNKNOWN), 10 * M))
        assertFalse(LastAddress.skipShortcut(Lifetime(LifeKind.LONG, 20 * M), 10 * M))
    }

    // ---- choice ----

    @Test fun pickPrefersPagesThenOtherSiteThenStreams() {
        val src = listOf(
            play("https://cdn.a.com/x.m3u8"), play("https://cdn.b.com/y.m3u8"),
            page("https://a.com/watch?s=2"), page("https://other.com/g"), page("https://third.net/g"),
        )
        val picks = BackupChoice.pick(src, 0, "https://www.a.com/watch", { false })
        assertEquals(listOf(3, 4), picks.map { it.index }) // other sites first, this site's numbered address after
        assertTrue(picks.all { it.kind == BackupKind.PAGE })
    }

    @Test fun pickFillsWithPlayableAndSkipsDeadAndPlaying() {
        val src = listOf(play("https://cdn.a.com/x.m3u8"), play("https://cdn.a.com/y.m3u8"), play("https://cdn.b.com/z.m3u8"), page("https://other.com/g"))
        val picks = BackupChoice.pick(src, 0, "https://a.com/w", { it.contains("other.com") })
        assertEquals(listOf(2, 1), picks.map { it.index }) // different stream server first
        assertTrue(picks.all { it.kind == BackupKind.STREAM })
        assertEquals(1, BackupChoice.pick(src, 0, "https://a.com/w", { false }, max = 1).size)
        assertTrue(BackupChoice.pick(listOf(play("https://c/x.m3u8")), 0, "https://a.com", { false }).isEmpty())
    }

    @Test fun pickUsesPlayerUrlForDeadness() {
        val src = listOf(play("https://cdn/x.m3u8"), page("https://o.com/a", player = "https://p.com/frame"))
        assertTrue(BackupChoice.pick(src, 0, "https://a.com", { it == "https://p.com/frame" }).isEmpty())
    }

    // ---- scheduling ----

    private val healthy = BackupTiming.Health(true, 15 * S, 5 * M, false)

    @Test fun healthRules() {
        assertTrue(BackupTiming.healthy(healthy))
        assertFalse(BackupTiming.healthy(healthy.copy(recovering = true)))
        assertFalse(BackupTiming.healthy(healthy.copy(playing = false)))
        assertFalse(BackupTiming.healthy(healthy.copy(aheadMs = 4 * S, calmMs = 10 * S)))
        assertTrue(BackupTiming.healthy(healthy.copy(aheadMs = 4 * S, calmMs = 61 * S)))
        assertFalse(BackupTiming.healthy(healthy.copy(aheadMs = 1 * S, calmMs = 5 * M)))
    }

    @Test fun intervalFromLifetime() {
        assertEquals(10 * M, BackupTiming.interval(Lifetime(LifeKind.UNKNOWN)))
        assertEquals(10 * M, BackupTiming.interval(Lifetime(LifeKind.LONG, 30 * M)))
        assertEquals(3 * M, BackupTiming.interval(Lifetime(LifeKind.SHORT, 60 * S)))
        assertEquals(3 * M, BackupTiming.interval(Lifetime(LifeKind.SHORT, 180 * S)))
        assertEquals(4 * M, BackupTiming.interval(Lifetime(LifeKind.SHORT, 6 * M)))
    }

    @Test fun backoffDoublesToCap() {
        assertEquals(20 * M / 2, BackupTiming.backoff(10 * M, 0) + 0 * M) // no misses: base
        assertEquals(15 * M, BackupTiming.backoff(10 * M, 1))
        assertEquals(3 * M, BackupTiming.backoff(90 * S, 1))
        assertEquals(6 * M, BackupTiming.backoff(90 * S, 2))
        assertEquals(15 * M, BackupTiming.backoff(90 * S, 6))
    }

    @Test fun firstRunAfterThirtySeconds() {
        val p = BackupPacing()
        p.sync(listOf("a", "b"), 1000L)
        assertNull(p.next(1000L + 29 * S, healthy))
        assertEquals("a", p.next(1000L + 30 * S, healthy))
        p.begin()
        assertNull(p.next(1000L + 40 * S, healthy)) // one run at a time
        p.end("a", true, 10 * M, 1000L + 45 * S)
        assertEquals("b", p.next(1000L + 46 * S, healthy))
        assertNull(p.next(1000L + 46 * S, healthy.copy(recovering = true)))
    }

    @Test fun missesBackOffAndFoundResets() {
        val p = BackupPacing()
        p.sync(listOf("a"), 0L)
        p.begin(); p.end("a", false, 90 * S, 40 * S)
        assertEquals(40 * S + 3 * M, p.nextAtFor("a"))
        p.begin(); p.end("a", false, 90 * S, 300 * S)
        assertEquals(300 * S + 6 * M, p.nextAtFor("a"))
        p.begin(); p.end("a", true, 90 * S, 1000 * S)
        assertEquals(1000 * S + 90 * S, p.nextAtFor("a"))
        assertEquals(0, p.missesFor("a"))
    }

    @Test fun stallDuringOrJustAfterRunTurnsOffForTheWatch() {
        val p = BackupPacing()
        p.sync(listOf("a"), 0L)
        p.begin()
        assertTrue(p.stall(50 * S))
        assertTrue(BackupWatch.off)
        assertNull(p.next(10 * M, healthy))
        BackupWatch.reset()
        val q = BackupPacing()
        q.sync(listOf("a"), 0L)
        q.begin(); q.end("a", true, 10 * M, 100 * S)
        assertTrue(q.stall(100 * S + 5 * S))
        BackupWatch.reset()
        val r = BackupPacing()
        r.sync(listOf("a"), 0L)
        r.begin(); r.end("a", true, 10 * M, 100 * S)
        assertFalse(r.stall(100 * S + 6 * S))
        assertFalse(BackupWatch.off)
    }

    @Test fun autoSwitchResetBringsKeeperBack() {
        BackupWatch.off = true
        AutoSwitch.reset()
        assertFalse(BackupWatch.off)
    }

    // ---- recovery decision ----

    private val unknown = Lifetime(LifeKind.UNKNOWN)

    @Test fun freshnessByLifetime() {
        assertTrue(BackupRecovery.fresh(100 * S, unknown))
        assertFalse(BackupRecovery.fresh(121 * S, unknown))
        assertTrue(BackupRecovery.fresh(60 * S, Lifetime(LifeKind.SHORT, 60 * S)))
        assertFalse(BackupRecovery.fresh(61 * S, Lifetime(LifeKind.SHORT, 60 * S)))
        assertTrue(BackupRecovery.fresh(20 * M, Lifetime(LifeKind.LONG, 30 * M)))
    }

    @Test fun decisionFreshStaleNone() {
        val fresh = BackupRecovery.Known("a", 30 * S, unknown)
        val fresher = BackupRecovery.Known("b", 10 * S, unknown)
        val stale = BackupRecovery.Known("c", 9 * M, unknown)
        assertEquals(BackupRecovery.Decision(BackupRecovery.Plan.SWITCH_FAST, "b"), BackupRecovery.decide(listOf(fresh, fresher), 0, true))
        assertEquals(BackupRecovery.Plan.PARALLEL, BackupRecovery.decide(listOf(stale), 0, true).plan)
        assertEquals(BackupRecovery.Plan.PARALLEL, BackupRecovery.decide(emptyList(), 2, true).plan)
        assertEquals(BackupRecovery.Plan.TODAY, BackupRecovery.decide(emptyList(), 0, true).plan)
        assertEquals(BackupRecovery.Plan.TODAY, BackupRecovery.decide(listOf(fresh), 3, false).plan)
    }

    // ---- race and episode ----

    @Test fun episodeFirstClaimWins() {
        val e = RecoveryEpisode()
        assertEquals(0, e.current())
        assertTrue(e.claim(0))
        val id = e.begin()
        assertTrue(e.markRaced()); assertFalse(e.markRaced())
        assertTrue(e.claim(id))
        assertFalse(e.claim(id))
        e.end()
        assertFalse(e.claim(id))
        val id2 = e.begin()
        assertFalse(e.claim(id))
        assertTrue(e.claim(id2))
    }

    @Test fun quickRaceReturnsFirstSuccess() {
        val r = QuickRace.first(listOf(1, 2, 3), 2000L) { if (it == 2) "ok" else { Thread.sleep(300); null } }
        assertEquals(2, r?.first)
        assertNull(QuickRace.first(listOf(1, 2), 2000L) { _ -> null as String? })
        assertNull(QuickRace.first(listOf(1), 100L) { Thread.sleep(600); "late" })
        assertNull(QuickRace.first(emptyList<Int>(), 100L) { "x" })
    }

    @Test fun preFoundIsOneShotAndExpires() {
        val set = StreamSet("p", listOf(play("https://c/x.m3u8")))
        PreFound.put("u", set, 1000L)
        assertNull(PreFound.take("other", 1001L))
        assertNotNull(PreFound.take("u", 1001L)) // wrong-url take above must not have consumed it
        assertNull(PreFound.take("u", 1002L))
        PreFound.put("u", set, 1000L)
        assertNull(PreFound.take("u", 1000L + PreFound.MAX_AGE_MS + 1))
    }

    // ---- dead streams ----

    @Test fun goneSourcesAreNotPlayable() {
        val gone = Source("https://s/a/index.m3u8", emptyMap(), listOf(""), "x", verified = false, note = DeadStream.goneNote(404))
        val refused = gone.copy(note = "server said 403; tried: a")
        assertTrue(DeadStream.isGone(gone))
        assertFalse(DeadStream.isGone(refused))
        assertFalse(DeadStream.isGone(gone.copy(verified = true)))
        assertEquals(listOf(refused), DeadStream.playable(listOf(gone, refused)))
        assertNull(LinkOrder.firstPlayable(listOf(gone)))
        assertEquals(1, LinkOrder.firstPlayable(listOf(gone, refused)))
        assertNull(LinkOrder.nextPlayable(listOf(refused, gone), 0))
        assertTrue(DeadStream.isGoneCode(410)); assertFalse(DeadStream.isGoneCode(403))
    }

    @Test fun samePathIgnoresQuery() {
        assertTrue(DeadStream.samePath("https://h/a/index.m3u8?t=1", "https://h/a/index.m3u8?t=2"))
        assertFalse(DeadStream.samePath("https://h/a/index.m3u8", "https://h/b/index.m3u8"))
    }
}
