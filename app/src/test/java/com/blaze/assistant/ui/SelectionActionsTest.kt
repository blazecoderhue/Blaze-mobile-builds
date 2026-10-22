package com.blaze.assistant.ui

import com.blaze.assistant.ui.SelectionActions.Action
import com.blaze.assistant.ui.SelectionActions.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionActionsTest {

    @Test fun urlsAreRecognised() {
        assertEquals("https://example.com/a?b=1", SelectionActions.url("https://example.com/a?b=1"))
        assertEquals("http://x.org", SelectionActions.url("  http://x.org. "))
        assertEquals("https://www.blaze.ai", SelectionActions.url("www.blaze.ai"))
        assertEquals("https://github.com/octocat/Blaze-agent", SelectionActions.url("(github.com/octocat/Blaze-agent)"))
        assertEquals("https://news.bbc.co.uk", SelectionActions.url("\u201cnews.bbc.co.uk\u201d"))
    }

    @Test fun nonUrlsAreNot() {
        assertNull(SelectionActions.url("hello world"))
        assertNull(SelectionActions.url("see https://example.com now"))
        assertNull(SelectionActions.url("joey@example.com"))
        assertNull(SelectionActions.url("notes.txt"))
        assertNull(SelectionActions.url("Main.kt"))
        assertNull(SelectionActions.url("e.g."))
        assertNull(SelectionActions.url("3.14"))
        assertNull(SelectionActions.url(""))
    }

    @Test fun kinds() {
        assertEquals(Kind.URL, SelectionActions.kind("https://a.io"))
        assertEquals(Kind.SHORT, SelectionActions.kind("serendipity"))
        assertEquals(Kind.SHORT, SelectionActions.kind("machine learning model"))
        assertEquals(Kind.SHORT, SelectionActions.kind("\u201cubiquitous.\u201d"))
        assertEquals(Kind.LONG, SelectionActions.kind("four words right here"))
        assertEquals(Kind.LONG, SelectionActions.kind("12345"))
        assertEquals(Kind.LONG, SelectionActions.kind("The quick brown fox jumps over the lazy dog."))
    }

    @Test fun orderForLongText() {
        assertEquals(listOf(Action.COPY, Action.QUOTE, Action.SELECT_ALL, Action.SEARCH, Action.ASK, Action.TRANSLATE, Action.READ_ALOUD, Action.SHARE),
            SelectionActions.forText("The quick brown fox jumps over the lazy dog."))
    }

    @Test fun shortTextGetsDefine() {
        assertEquals(listOf(Action.COPY, Action.QUOTE, Action.SELECT_ALL, Action.SEARCH, Action.ASK, Action.TRANSLATE, Action.DEFINE, Action.READ_ALOUD, Action.SHARE),
            SelectionActions.forText("serendipity"))
    }

    @Test fun urlGetsOpenLinkInsteadOfSearch() {
        val a = SelectionActions.forText("https://example.com")
        assertTrue(Action.OPEN_LINK in a)
        assertFalse(Action.SEARCH in a)
        assertFalse(Action.DEFINE in a)
        assertEquals(Action.OPEN_LINK, a[3])
    }

    @Test fun quoteAndSelectAllFollowTheScreen() {
        val a = SelectionActions.forText("hello there", allowQuote = false, allowSelectAll = false)
        assertEquals(Action.COPY, a[0]); assertEquals(Action.SEARCH, a[1])
        assertFalse(Action.QUOTE in a); assertFalse(Action.SELECT_ALL in a)
        assertEquals(listOf(Action.COPY, Action.QUOTE, Action.SELECT_ALL), SelectionActions.forText("  "))
    }

    @Test fun unknownSelectionUsesTheBox() {
        val short = SelectionActions.forUnknown(likelyShort = true)
        val long = SelectionActions.forUnknown(likelyShort = false, allowSelectAll = false)
        assertEquals(listOf(Action.COPY, Action.QUOTE, Action.SELECT_ALL, Action.SEARCH, Action.ASK, Action.TRANSLATE, Action.DEFINE, Action.READ_ALOUD, Action.SHARE), short)
        assertFalse(Action.DEFINE in long); assertFalse(Action.SELECT_ALL in long)
        assertTrue(SelectionActions.likelyShort(120f, 22f))
        assertFalse(SelectionActions.likelyShort(300f, 22f))
        assertFalse(SelectionActions.likelyShort(120f, 60f))
    }

    @Test fun resolveOnTap() {
        assertEquals(Action.OPEN_LINK, SelectionActions.resolve(Action.SEARCH, "blaze.ai"))
        assertEquals(Action.SEARCH, SelectionActions.resolve(Action.SEARCH, "best pizza ndola"))
        assertEquals(Action.ASK, SelectionActions.resolve(Action.DEFINE, "a whole long sentence of text here"))
        assertEquals(Action.DEFINE, SelectionActions.resolve(Action.DEFINE, "ephemeral"))
        assertEquals(Action.SHARE, SelectionActions.resolve(Action.SHARE, "anything"))
    }

    @Test fun browserUrl() {
        assertEquals("https://example.com/x", SelectionActions.browserUrl("https://example.com/x"))
        assertEquals("https://www.blaze.ai", SelectionActions.browserUrl("www.blaze.ai"))
        assertEquals("https://www.google.com/search?q=best+pizza+%26+pasta", SelectionActions.browserUrl("  best  pizza\n& pasta "))
        assertTrue(SelectionActions.browserUrl("a".repeat(1000)).length < 400)
    }

    @Test fun prompts() {
        assertEquals("Explain: photosynthesis", SelectionActions.askPrompt("  photosynthesis "))
        assertTrue(SelectionActions.translatePrompt("Bonjour", "English").startsWith("Translate into English"))
        assertTrue(SelectionActions.translatePrompt("Bonjour", "").startsWith("Translate into English"))
        assertTrue(SelectionActions.translatePrompt("Hi", "French").endsWith("\n\nHi"))
        assertTrue(SelectionActions.definePrompt("\u201cephemeral.\u201d").startsWith("Define \u201cephemeral\u201d"))
        val long = SelectionActions.askPrompt("x".repeat(10_000))
        assertTrue(long.length <= SelectionActions.MAX_PROMPT + 20)
    }

    @Test fun nudgeOnlyOnceWithMotionAndMore() {
        assertTrue(SelectionActions.shouldNudge(alreadyNudged = false, motionOn = true, hasMore = true))
        assertFalse(SelectionActions.shouldNudge(alreadyNudged = true, motionOn = true, hasMore = true))
        assertFalse(SelectionActions.shouldNudge(alreadyNudged = false, motionOn = false, hasMore = true))
        assertFalse(SelectionActions.shouldNudge(alreadyNudged = false, motionOn = true, hasMore = false))
    }
}
