package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.29.9: the page's selected words come back from evaluateJavascript as a JSON value. */
class PageSelectionTextTest {
    @Test fun decodesJsonStrings() {
        assertEquals("hello world", PageSelectionText.decode("\"hello world\""))
        assertEquals("line one\nline two", PageSelectionText.decode("\"line one\\nline two\""))
        assertEquals("say \"hi\"", PageSelectionText.decode("\"say \\\"hi\\\"\""))
        assertEquals("caf\u00e9", PageSelectionText.decode("\"caf\\u00e9\""))
        assertEquals("a\\b", PageSelectionText.decode("\"a\\\\b\""))
    }

    @Test fun emptyAndNull() {
        assertEquals("", PageSelectionText.decode(null))
        assertEquals("", PageSelectionText.decode("null"))
        assertEquals("", PageSelectionText.decode("\"\""))
        assertEquals("", PageSelectionText.decode("  "))
    }

    @Test fun pillListForPageWords() {
        // The same list the chat shows: Quote only when the window can hand it to the chat box.
        val acts = SelectionActions.forText("serendipity", allowQuote = true, allowSelectAll = true).map { it.label }
        assertEquals(listOf("Copy", "Quote", "Select all", "Search", "Ask Blaze", "Translate", "Define", "Read aloud", "Share"), acts)
    }
}
