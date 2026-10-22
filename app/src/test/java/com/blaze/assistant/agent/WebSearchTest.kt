package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.32.0: a bot check is a block, not "no results"; Bing RSS parses. */
class WebSearchTest {
    @Test fun botCheckIsBlocked() {
        assertTrue(Web.blocked("<div class=\"anomaly-modal__title\">Unfortunately, bots use DuckDuckGo too.</div><form id=\"challenge-form\">"))
        assertFalse(Web.blocked("<div class=\"result__body\">x</div>"))
    }
    @Test fun bingRss() {
        val xml = "<rss><channel><title>Bing</title><link>http://www.bing.com:80/search?q=x</link>" +
            "<item><title>morluto/rea: Reverse engineer anything</title><link>https://github.com/morluto/rea</link><description>REA connects your agent</description></item>" +
            "<item><title>REA</title><link>https://rea.tools/</link><description>Find out how software works.</description></item></channel></rss>"
        val r = Web.parseRss(xml, 6)!!
        assertTrue(r.startsWith("1. morluto/rea"))
        assertTrue(r.contains("https://rea.tools/"))
        assertEquals(null, Web.parseRss("<rss></rss>", 6))
    }
}
