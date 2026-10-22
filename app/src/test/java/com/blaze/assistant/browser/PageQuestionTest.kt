package com.blaze.assistant.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.27.1: what a pasted link, an empty box or one word means in "Ask Blaze about this page". */
class PageQuestionTest {
    // The kind of link Joey's tester pasted (a Google Lens results page; no q).
    private val lens = "https://www.google.com/search?sca_esv=1b2c3d&lns_surface=26&biw=360&bih=640&udm=26&lns_mode=un" +
        "&source=lns.web.gisbubb&vsdim=1080,1920&gsessionid=AbCdEf123&lsessionid=XyZ987&lns_pfs=1&vsrid=CJ3a1qW3z8-ABC" +
        "&vsint=CAIqDAoCCAcSAggKGAEgATojChYNAAAAPxUAAAA_HQAAgD8lAACAPzABEMQHGPMHJQAAgD8&sei=abcDEF"
    private val search = "https://www.google.com/search?q=best+budget+phones+2026&oq=best+budget+phones&sourceid=chrome-mobile&ie=UTF-8"

    @Test fun lensLinkIsALensQuestionNotAGiantBubble() {
        val i = PageQuestion.parse(lens, "https://www.google.com/search?udm=26")
        assertEquals(PageQuestion.Kind.LENS_LINK, i.kind)
        assertEquals("", i.shown)                       // the bubble shows only the link chip
        assertEquals(lens, i.link)
        assertTrue(i.prompt.contains("Google Lens"))
        assertTrue(i.prompt.contains("what the picture seems to be"))
        assertFalse(i.prompt.contains("lns_mode"))       // the raw URL never goes to the model as the question
        assertTrue(PageQuestion.chip(lens).startsWith("Google Lens"))
        assertTrue(PageQuestion.chip(lens).length < 50)
    }

    @Test fun lensDetection() {
        assertTrue(PageQuestion.isLens(lens))
        assertTrue(PageQuestion.isLens("https://lens.google.com/search?ep=gsbubb&hl=en"))
        assertTrue(PageQuestion.isLens("https://www.google.com/search?q=red+sneaker&udm=26"))
        assertTrue(PageQuestion.isLens("https://www.google.co.za/search?source=lns.web&lns_mode=text"))
        assertFalse(PageQuestion.isLens(search))
        assertFalse(PageQuestion.isLens("https://example.com/?udm=26"))
    }

    @Test fun googleSearchLinkBecomesItsQuery() {
        assertEquals("best budget phones 2026", PageQuestion.searchQuery(search))
        val i = PageQuestion.parse(search)
        assertEquals(PageQuestion.Kind.SEARCH_LINK, i.kind)
        assertEquals("best budget phones 2026", i.search)
        assertTrue(i.prompt.contains("\u201cbest budget phones 2026\u201d"))
        assertEquals("Google search \u00b7 best budget phones 2026", PageQuestion.chip(search))
    }

    @Test fun oqIsTheFallbackAndOtherEngines() {
        assertEquals("weather ndola", PageQuestion.searchQuery("https://www.google.com/search?oq=weather+ndola&aqs=chrome"))
        assertEquals("kotlin flows", PageQuestion.searchQuery("https://www.bing.com/search?q=kotlin%20flows&form=QBLH"))
        assertEquals("c++ & rust", PageQuestion.searchQuery("https://duckduckgo.com/?q=c%2B%2B+%26+rust&ia=web"))
        assertEquals("zambia", PageQuestion.searchQuery("https://search.yahoo.com/search?p=zambia"))
        assertEquals("lofi", PageQuestion.searchQuery("https://m.youtube.com/results?search_query=lofi"))
        assertNull(PageQuestion.searchQuery("https://www.google.com/maps?q=lusaka"))   // a map, not a search page
        assertNull(PageQuestion.searchQuery("https://www.youtube.com/watch?v=abc"))
        assertNull(PageQuestion.searchQuery("https://example.com/search?q=x"))
    }

    @Test fun searchWithAQuestionKeepsTheirWords() {
        val i = PageQuestion.parse("$search which is cheapest?")
        assertEquals(PageQuestion.Kind.SEARCH_LINK, i.kind)
        assertEquals("which is cheapest?", i.shown)
        assertTrue(i.prompt.contains("which is cheapest?"))
    }

    @Test fun aLinkInsideALongQuestionIsJustAQuestion() {
        val q = "Can you compare the prices on this page with what https://shop.example.com/p/123 charges for the same phone?"
        assertEquals(PageQuestion.Kind.ASK, PageQuestion.parse(q).kind)
        assertNull(PageQuestion.mostlyLink(q))
    }

    @Test fun ordinaryLinkIsFetchedAndThisPageLinkIsThePage() {
        val other = PageQuestion.parse("https://www.bbc.com/news/articles/c123")
        assertEquals(PageQuestion.Kind.LINK, other.kind)
        assertEquals("https://www.bbc.com/news/articles/c123", other.fetch)
        assertTrue(other.prompt.contains("bbc.com"))
        val same = PageQuestion.parse("https://example.com/a/b?ref=x", "https://example.com/a/b")
        assertEquals(PageQuestion.Kind.THIS_PAGE_LINK, same.kind)
        assertNull(same.fetch)
        val www = PageQuestion.parse("www.example.org/page")
        assertEquals("https://www.example.org/page", www.link)
    }

    @Test fun emptyOrPunctuationSummarises() {
        for (q in listOf("", "   ", "?", "\n")) {
            val i = PageQuestion.parse(q)
            assertEquals(PageQuestion.Kind.EMPTY, i.kind)
            assertEquals("Summarise this page", i.shown)
            assertTrue(i.prompt.startsWith("Summarise this page"))
        }
    }

    @Test fun oneWordIsAskedAboutAndCommandsMapToChips() {
        val w = PageQuestion.parse("photosynthesis")
        assertEquals(PageQuestion.Kind.WORD, w.kind)
        assertTrue(w.prompt.contains("\u201cphotosynthesis\u201d"))
        assertEquals("Summarise this page", PageQuestion.parse("tldr").prompt)
        assertEquals("Summarise this page", PageQuestion.parse("Summary").prompt)
        assertEquals(PageQuestion.Kind.GREETING, PageQuestion.parse("hi").kind)
        assertEquals(PageQuestion.Kind.ASK, PageQuestion.parse("why?").kind)
        assertEquals(PageQuestion.Kind.ASK, PageQuestion.parse("Who wrote this article?").kind)
    }

    @Test fun titleQuery() {
        assertEquals("cheap flights", PageQuestion.queryFromTitle("cheap flights - Google Search"))
        assertNull(PageQuestion.queryFromTitle("Google Search"))
        assertNull(PageQuestion.queryFromTitle("BBC News - Home"))
    }

    @Test fun shortAddress() {
        assertEquals("google.com/search", PageQuestion.shortAddress(lens))
        assertEquals("example.com/a?id=3", PageQuestion.shortAddress("https://example.com/a?id=3#top"))
        assertEquals("example.com/a", PageQuestion.shortAddress("https://example.com/a?" + "x=1&".repeat(20)))
    }
}
