package com.tvcleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsUpdaterTest {
    @Test fun acceptsServerNames() {
        assertEquals("dns.adguard-dns.com", Dns.cleanHost(" DNS.AdGuard-DNS.com. "))
        assertEquals("one.one.one.one", Dns.cleanHost("one.one.one.one"))
        assertEquals("dns.google", Dns.cleanHost("dns.google"))
    }

    @Test fun refusesAddressesAndJunk() {
        listOf("8.8.8.8", "1.1.1.1", "https://dns.google", "dns", "", "a..b", "-bad.com", "dns.google/x", "x y.com", "host.123")
            .forEach { assertNull(it, Dns.cleanHost(it)) }
    }

    @Test fun presetsAreValid() {
        Dns.PRESETS.mapNotNull { it.host }.forEach { assertEquals(it, Dns.cleanHost(it)) }
    }

    @Test fun modes() {
        assertEquals(Dns.OFF, Dns.parseMode("off\n"))
        assertEquals(Dns.HOST, Dns.parseMode("hostname"))
        assertEquals(Dns.AUTO, Dns.parseMode("null"))
        assertEquals(Dns.AUTO, Dns.parseMode(null))
    }

    @Test fun buildNumber() {
        assertEquals(42, Updater.parseBuild("Build 42"))
        assertEquals(7, Updater.parseBuild("TV Cleaner\nBuild 7 notes"))
        assertNull(Updater.parseBuild("no number"))
        assertNull(Updater.parseBuild(null))
    }
}
