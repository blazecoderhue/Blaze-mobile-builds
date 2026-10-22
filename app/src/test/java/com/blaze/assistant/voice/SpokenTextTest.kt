package com.blaze.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTextTest {
    private fun say(s: String) = SpokenText.forSpeech(s)

    @Test fun plainReplyIsUnchanged() {
        assertEquals("Playing Daily Mix 1 (Clams Casino and more) shuffled.", say("Playing Daily Mix 1 (Clams Casino and more) shuffled."))
    }

    @Test fun markdownSymbolsAreNotRead() {
        val out = say("## Tonight\n**Action** picks:\n- *Mad Max: Fury Road*\n- `John Wick`\n\n> Enjoy!")
        assertFalse(out, out.any { it in "*#`>_|" })
        assertTrue(out, out.contains("Mad Max: Fury Road"))
        assertTrue(out, out.contains("John Wick"))
        assertTrue(out, out.startsWith("Tonight."))
    }

    @Test fun linksSayTheirWordsAndUrlsAreDropped() {
        assertEquals("See the trailer here.", say("See the [trailer](https://youtu.be/x) here https://example.com/a?b=1."))
    }

    @Test fun statusAndToolLinesAreNeverRead() {
        val out = say("TOOLS: [{\"name\":\"media_search\"}]\n⚡ Blaze itself\n(step) Looking at your series\nYour library has no series grouped.")
        assertEquals("Your library has no series grouped.", out)
    }

    @Test fun thinkingCodeAndMarkersAreDropped() {
        val out = say("<think>user wants a movie</think>[re:m7] Here you go:\n```kotlin\nval x = 1\n```\nDone.")
        assertFalse(out, out.contains("think") || out.contains("val x") || out.contains("re:m7"))
        assertTrue(out, out.contains("Here you go") && out.endsWith("Done."))
    }

    @Test fun tablesBecomeShortPhrases() {
        val out = say("| Film | Year |\n|---|---|\n| Heat | 1995 |")
        assertFalse(out, out.contains("|") || out.contains("---"))
        assertTrue(out, out.contains("Heat, 1995"))
    }

    @Test fun streamingWaitsForTheRealReply() {
        assertFalse(SpokenText.streamOk("Looking at your series. One moment.", noTools = false))
        assertTrue(SpokenText.streamOk("Short.", noTools = true))
        assertTrue(SpokenText.streamOk("x".repeat(SpokenText.STREAM_MIN_CHARS), noTools = false))
    }
}
