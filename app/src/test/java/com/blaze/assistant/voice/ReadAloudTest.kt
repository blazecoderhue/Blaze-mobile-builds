package com.blaze.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudTest {
    @Test fun cleansAndChunks() {
        assertEquals("See the docs now.", ReadAloud.clean("See [the docs](https://x.y) https://a.b now.[12]").replace(Regex("\\s+"), " ").replace(" .", "."))
        val text = (1..40).joinToString(" ") { "Sentence number $it is here." }
        val c = ReadAloud.chunks(text, 120)
        assertTrue(c.all { it.length <= 120 }); assertTrue(c.all { it.endsWith(".") })
        assertEquals(text, c.joinToString(" "))
        val long = ReadAloud.chunks("word ".repeat(300).trim(), 100)
        assertTrue(long.all { it.length <= 100 }); assertEquals(300, long.joinToString(" ").split(" ").size)
    }
}
