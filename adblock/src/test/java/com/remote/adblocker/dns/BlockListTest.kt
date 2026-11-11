package com.remote.adblocker.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockListTest {

    @Test
    fun blocksListedDomainsAndTheirSubdomains() {
        val list = BlockList(setOf("doubleclick.net", "ads.example.com"))
        assertTrue(list.isBlocked("doubleclick.net"))
        assertTrue(list.isBlocked("googleads.g.doubleclick.net"))
        assertTrue(list.isBlocked("ADS.Example.com."))
        assertFalse(list.isBlocked("example.com"))
        assertFalse(list.isBlocked("notdoubleclick.net"))
        assertFalse(list.isBlocked("net"))
        assertFalse(list.isBlocked(""))
    }

    @Test
    fun allowListWins() {
        val list = BlockList(setOf("example.com"), allowed = setOf("good.example.com"))
        assertTrue(list.isBlocked("ads.example.com"))
        assertFalse(list.isBlocked("good.example.com"))
        assertFalse(list.isBlocked("cdn.good.example.com"))
    }

    @Test
    fun parsesHostsFilesAndPlainLists() {
        val text = """
            # comment
            127.0.0.1 localhost
            0.0.0.0 0.0.0.0
            0.0.0.0 Ads.Example.com   # trailing comment
            127.0.0.1	tracker.example.org
            plain.example.net
            ::1 ip6-localhost
            192.168.1.1 router.example.com
            not a domain line
            nodot
            bad/host.example.com
        """.trimIndent()
        val hosts = BlockList.parse(text.reader().buffered())
        assertEquals(setOf("ads.example.com", "tracker.example.org", "plain.example.net"), hosts)
    }
}
