package com.blaze.assistant.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadFeedTest {
    @Test fun labels() {
        assertEquals("fonts.gstatic.com › inter.woff2", LoadFeed.label("https://fonts.gstatic.com/s/inter/v1/inter.woff2?v=3"))
        assertEquals("bbc.com", LoadFeed.label("https://www.bbc.com/"))
        assertEquals("cdn.x.io › aaaaaaaaaaaa…bbbbbbbbb.js", LoadFeed.label("https://cdn.x.io/" + "a".repeat(20) + "b".repeat(15) + ".js").let { it })
        assertEquals("font", LoadFeed.kind("https://a.b/c.woff2?x"))
        assertEquals("image", LoadFeed.kind("https://a.b/c.webp"))
    }

    @Test fun lines() {
        assertEquals("", LoadFeed.line(100, 5, "x", "bbc.com", 0, 0, 1000))
        assertEquals("Connecting to bbc.com · 2s", LoadFeed.line(5, 0, "", "bbc.com", 0, 0, 2000))
        assertEquals("Pulling 1 file · bbc.com › a.css", LoadFeed.line(30, 1, "bbc.com › a.css", "bbc.com", 0, 0, 500))
        assertEquals("Pulling 23 files · cdn › x.js · 4s", LoadFeed.line(60, 23, "cdn › x.js", "bbc.com", 0, 3500, 4000))
        val slow = LoadFeed.line(60, 23, "cdn › x.js", "bbc.com", 0, 1000, 1000 + LoadFeed.STALL_MS)
        assertTrue(slow, slow.startsWith("Slow network · still waiting on bbc.com · 23 files so far"))
    }
}
