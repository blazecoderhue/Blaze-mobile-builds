package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.1 content-hash tool cache + speculative prefetch rules. */
class ToolCacheTest {
    private fun q(s: String) = JSONObject().put("query", s)
    private fun u(s: String) = JSONObject().put("url", s)

    @Test fun sameLookupSameKey() {
        assertEquals(ToolCache.key("web_search", q("Weather  Ndola ")), ToolCache.key("web_search", q("weather ndola")))
        assertEquals(ToolCache.key("fetch_page", u("https://a.com/x#top")), ToolCache.key("fetch_page", u("https://a.com/x/")))
        assertNotEquals(ToolCache.key("web_search", q("a")), ToolCache.key("fetch_page", u("a")))
        assertEquals(64, ToolCache.key("web_search", q("x")).length)
    }

    @Test fun freshnessByMeaning() {
        assertEquals(ToolCache.FRESH_TTL, ToolCache.ttlMs("web_search", q("zambia news today")))
        assertEquals(ToolCache.FRESH_TTL, ToolCache.ttlMs("web_search", q("kwacha exchange rate")))
        assertEquals(ToolCache.SEARCH_TTL, ToolCache.ttlMs("web_search", q("how tall is mount everest")))
        assertEquals(ToolCache.PAGE_TTL, ToolCache.ttlMs("fetch_page", u("https://en.wikipedia.org/wiki/Ndola")))
        assertEquals(ToolCache.FRESH_PAGE_TTL, ToolCache.ttlMs("fetch_page", u("https://bbc.com/news/world")))
        assertEquals(0, ToolCache.ttlMs("web_search", q("  ")))
        assertEquals(0, ToolCache.ttlMs("fetch_page", u("file:///etc/passwd")))
        assertEquals(0, ToolCache.ttlMs("send_sms", q("x")))
    }

    @Test fun onlyRealAnswersStored() {
        assertFalse(ToolCache.storable("Error: HTTP 503 from x"))
        assertFalse(ToolCache.storable("No results for \"x\" on DuckDuckGo or Bing. Try different words."))
        assertFalse(ToolCache.storable("short"))
        assertTrue(ToolCache.storable("1. Ndola - Wikipedia\n   https://en.wikipedia.org/wiki/Ndola\n   Ndola is the third largest city"))
    }

    @Test fun refreshSkipsCache() {
        assertTrue(ToolCache.bypass("check again please"))
        assertTrue(ToolCache.bypass("refresh the scores"))
        assertFalse(ToolCache.bypass("what is the capital of zambia"))
    }

    @Test fun resultUrlsFromSearchList() {
        val r = "1. A\n   https://a.com/1\n   snip\n2. B\n   https://duckduckgo.com/y.js?x\n   s\n3. C\n   https://c.com/doc.pdf\n4. D\n   https://d.com/\n5. E\n   https://e.com"
        assertEquals(listOf("https://a.com/1", "https://d.com/"), ToolCache.resultUrls(r))
    }

    @Test fun storeExpiresAndEvicts() {
        var now = 1_000L
        val s = ToolCache.Store(max = 2) { now }
        s.put("a", "A"); s.put("b", "B")
        assertNotNull(s.get("a", 100))
        s.put("c", "C")                       // b is the least recently used → evicted
        assertNull(s.get("b", 100))
        assertNotNull(s.get("a", 100))
        now += 101
        assertNull(s.get("a", 100))           // expired
        assertEquals(2, s.hits)
        val copy = ToolCache.Store { now }.apply { load(s.toJson()) }
        assertEquals(s.size(), copy.size())
    }

    @Test fun cachedNoteSaysAge() {
        assertTrue(ToolCache.note(30_000).contains("under a minute"))
        assertTrue(ToolCache.note(12 * 60_000).contains("12 min"))
    }
}
