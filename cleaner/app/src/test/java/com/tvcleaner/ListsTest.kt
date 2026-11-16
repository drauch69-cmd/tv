package com.tvcleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListsTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun parsesIndexAndFindsOnnBox() {
        val idx = Lists.parseIndex(res("index.json"))!!
        assertTrue(idx.devices.size >= 3)
        assertNotNull(idx.files["protected"])
        val tv = TvInfo("onn", "onn", "onn. 4K Streaming Box", "yoc", 34)
        assertEquals("onn-4k-streaming-box-2023", Lists.bestMatch(idx.devices, tv)?.id)
        assertEquals(3, Lists.byMaker(idx.devices, tv).size)
        // A model name that fits nothing: no guess, the user picks.
        assertNull(Lists.bestMatch(idx.devices, TvInfo("onn", "onn", "XYZ999", "x", 34)))
    }

    @Test
    fun parsesOnnList() {
        val l = Lists.parseList(res("onn.json"))!!
        assertEquals(34, l.sdk)
        assertEquals("Google TV 14", l.testedOs)
        assertTrue(l.groups.any { g -> g.apps.any { it.pkg == "com.google.android.play.games" } })
        assertTrue(l.groups.any { it.title == "Preloaded apps" })
        assertTrue(l.groups.flatMap { it.apps }.first { it.pkg == "com.google.android.apps.tv.dreamx" }.optional)
    }

    @Test
    fun protectsWhatTheTvNeeds() {
        val self = "com.tvcleaner"
        val homes = Protect.protectedHomes(mapOf("com.google.android.apps.tv.launcherx" to true))
        assertNotNull(Protect.reason("com.google.android.apps.tv.launcherx", self, homes))
        assertNotNull(Protect.reason("com.google.android.tungsten.setupwraith", self, homes))
        assertNotNull(Protect.reason("com.google.android.gms", self, homes))
        assertNotNull(Protect.reason("com.google.android.tv.remote.service", self, homes))
        assertNotNull(Protect.reason(self, self, homes))
        assertNotNull(Protect.reason(Protect.SHIZUKU, self, homes))
        assertNull(Protect.reason("com.google.android.play.games", self, homes))
        assertNull(Protect.reason("com.google.android.feedback", self, homes))
        assertNull(Protect.reason("com.android.providers.calendar", self, homes))
        // Even with your own home screen installed, both stay locked.
        val withProjectivy = Protect.protectedHomes(mapOf("com.google.android.apps.tv.launcherx" to true, "com.spocky.projengmenu" to false))
        assertNotNull(Protect.reason("com.google.android.apps.tv.launcherx", self, withProjectivy))
        assertNotNull(Protect.reason("com.spocky.projengmenu", self, withProjectivy))
    }

    @Test
    fun readsProtectedAndWarnings() {
        val (keep, warn) = Lists.parseProtected(res("protected.json"))
        assertTrue("org.droidtv.settings" in keep)
        assertTrue("com.google.android.apps.mediashell" in warn)
    }

    @Test
    fun shortensTitles() {
        assertEquals("Stock home", Lists.cleanTitle("Stock home. launcher/build-and-install.sh disables launcherx"))
        assertEquals("Preloaded apps", Lists.cleanTitle("Preloaded apps"))
    }
}
