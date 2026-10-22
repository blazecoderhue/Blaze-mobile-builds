package com.blaze.assistant.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.27.1: the page-ask prompt never invites talk about markup and handles thin, picture, sign-in and long pages. */
class PageAskTest {
    private val lensUrl = "https://www.google.com/search?udm=26&lns_mode=un&source=lns.web.gisbubb&vsrid=abc"
    private val lensCapture = PageAsk.Capture("Google Search", lensUrl, "Google\nAll  Images  Visual matches\nSign in",
        labels = listOf("Sign in", "Google apps"), images = 9, alts = listOf("Nike Air Max 90 white sneaker", "Air Max 90 on foot"))

    @Test fun systemForbidsMarkupTalkAndBlame() {
        val s = PageAsk.system()
        assertTrue(s.contains("Never mention HTML, markup"))
        assertTrue(s.contains("<body>"))
        assertTrue(s.contains("the text you provided"))
        assertTrue(s.contains("Never say the user gave you too little"))
        assertTrue(s.contains("Always give a useful answer"))
        assertTrue(s.contains("Only as a last resort"))
        assertTrue(s.contains("never ask for or repeat passwords"))
    }

    @Test fun languageRule() {
        val s = PageAsk.system("French")
        assertTrue(s.contains("Answer in the language the user writes in"))
        assertTrue(s.contains("answer in French"))
    }

    @Test fun pageBlockIsWhatAPersonSees() {
        val b = PageAsk.pageBlock(lensCapture)
        assertTrue(b.contains("Google Lens"))
        assertTrue(b.contains("Nike Air Max 90 white sneaker"))
        assertTrue(b.contains("9 on the page"))
        assertFalse(b.contains("Elements:"))
        assertFalse(b.contains("[0]"))
        assertFalse(b.contains("<"))
        assertFalse(b.contains("lns_mode=un&"))     // the address is compacted, not a 2,000-char dump
    }

    @Test fun thinCaptureGetsAPlainNoteNotAnError() {
        assertTrue(PageAsk.thin(lensCapture))
        assertTrue(PageAsk.mostlyPictures(lensCapture))
        assertFalse(PageAsk.signInWall(lensCapture))   // a "Sign in" button on Google is not a wall
        val b = PageAsk.pageBlock(lensCapture)
        assertTrue(b.contains("Only a little of this page could be read"))
        assertTrue(b.contains("mostly pictures"))
    }

    @Test fun emptyCapture() {
        val b = PageAsk.pageBlock(PageAsk.Capture.NONE)
        assertTrue(b.contains("couldn't read this page"))
        val blank = PageAsk.pageBlock(PageAsk.Capture("Loading", "https://x.com/a", ""))
        assertTrue(blank.contains("(none could be read)"))
        assertTrue(blank.contains("Only a little"))
    }

    @Test fun signInWall() {
        assertTrue(PageAsk.signInWall(PageAsk.Capture("Log in", "https://x.com/login", "Log in to continue", password = true)))
        assertTrue(PageAsk.signInWall(PageAsk.Capture("Sign in", "https://x.com/i/flow", "Sign in to X. Continue with Google.",
            labels = listOf("Field: Phone, email, or username", "Next"))))
        val article = PageAsk.Capture("News", "https://n.com/a", "word ".repeat(400) + "Sign in", labels = listOf("Field: Email", "Subscribe"))
        assertFalse(PageAsk.signInWall(article))
    }

    @Test fun longPagesKeepStartAndEnd() {
        val text = (1..3000).joinToString("\n") { "Line $it of the article." }
        val c = PageAsk.Capture("Long read", "https://n.com/long", text)
        val b = PageAsk.pageBlock(c)
        assertTrue(b.length <= PageAsk.PAGE_BUDGET + 200)
        assertTrue(b.contains("Line 1 of the article."))
        assertTrue(b.contains("Line 3000 of the article."))
        assertTrue(b.contains("middle part of the page is skipped"))
        assertTrue(b.contains("only its start and end"))
        assertEquals("short", PageAsk.trimText("short", 100))
    }

    @Test fun webSearchOnlyWhenItHelps() {
        val lensQ = PageQuestion.parse(lensUrl, lensUrl)
        // A Lens link without words: nothing to search unless the page itself has a query.
        assertNull(PageAsk.searchFor(lensQ, lensCapture))
        val titled = lensCapture.copy(title = "white sneaker - Google Search")
        assertEquals("white sneaker", PageAsk.searchFor(lensQ, titled))
        val s = PageQuestion.parse("https://www.google.com/search?q=ndola+weather")
        assertEquals("ndola weather", PageAsk.searchFor(s, lensCapture))
        val rich = PageAsk.Capture("Article", "https://n.com/a", "word ".repeat(500))
        assertNull(PageAsk.searchFor(PageQuestion.parse("Who wrote this?"), rich))
        val thinSearch = PageAsk.Capture("x", "https://www.google.com/search?q=lusaka+hotels", "Google")
        assertEquals("lusaka hotels", PageAsk.searchFor(PageQuestion.parse(""), thinSearch))
    }

    @Test fun messagesCarryRulesPageWebAndRewrittenQuestion() {
        val q = PageQuestion.parse(lensUrl, lensUrl)
        val m = PageAsk.messages(lensCapture, listOf(true to q.prompt), "WEB RESULTS for \u201cx\u201d:\n1. A", "English")
        assertEquals(2, m.length())
        val sys = m.getJSONObject(0).getString("content")
        assertTrue(sys.contains("Never mention HTML"))
        assertTrue(sys.contains("PAGE ON THE USER'S SCREEN"))
        assertTrue(sys.contains("WEB RESULTS"))
        val user = m.getJSONObject(1)
        assertEquals("user", user.getString("role"))
        assertTrue(user.getString("content").contains("Google Lens"))
        assertFalse(user.getString("content").contains("vsrid"))
    }

    @Test fun chipsStay() {
        assertEquals(listOf("Summarise this page", "Explain it simply", "What are the key points?"), PageAsk.chips)
    }
}
