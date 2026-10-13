package com.swift.browser.extensionengine

import org.junit.Assert.*
import org.junit.Test

class HostPatternTest {

    @Test
    fun testAllUrlsMatching() {
        val hp = HostPattern("<all_urls>")
        assertTrue(hp.matchesUrl("https://google.com/search"))
        assertTrue(hp.matchesUrl("http://example.org/test"))
        assertTrue(hp.matchesUrl("https://sub.domain.co.uk/path"))
    }

    @Test
    fun testWildcardSubdomainMatching() {
        val hp = HostPattern("https://*.example.com/*")

        assertTrue(hp.matchesUrl("https://sub.example.com/page"))
        assertTrue(hp.matchesUrl("https://a.b.c.example.com/"))
        assertTrue(hp.matchesUrl("https://example.com/"))

        assertFalse(hp.matchesUrl("http://sub.example.com/page")) // HTTP vs HTTPS mismatch
        assertFalse(hp.matchesUrl("https://badexample.com/page")) // Boundary domain mismatch
        assertFalse(hp.matchesUrl("https://example.org/page"))
    }

    @Test
    fun testSpecificDomainAndPathMatching() {
        val hp = HostPattern("https://github.com/swift/*")

        assertTrue(hp.matchesUrl("https://github.com/swift/browser"))
        assertFalse(hp.matchesUrl("https://github.com/other/repo"))
    }

    @Test
    fun testWildcardSchemeMatching() {
        val hp = HostPattern("*://*.youtube.com/*")

        assertTrue(hp.matchesUrl("https://www.youtube.com/watch?v=123"))
        assertTrue(hp.matchesUrl("https://youtube.com/watch?v=123"))
        assertTrue(hp.matchesUrl("http://m.youtube.com/watch?v=123"))

        assertFalse(hp.matchesUrl("https://example.com/"))
        assertFalse(hp.matchesUrl("ftp://youtube.com/"))
    }

    @Test
    fun testUniversalWildcardScheme() {
        val hp = HostPattern("*://*/*")

        assertTrue(hp.matchesUrl("http://example.com/"))
        assertTrue(hp.matchesUrl("https://sub.domain.co.uk/path"))
        assertFalse(hp.matchesUrl("ftp://example.com/"))
        assertFalse(hp.matchesUrl("file:///test"))
    }
}
