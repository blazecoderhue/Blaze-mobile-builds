package com.blaze.assistant.voice.myvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {
    @Test fun splitsSentences() {
        val c = SpeechChunker.chunks("The weather is sunny today. Tomorrow brings rain in the afternoon! Should I set a reminder?")
        assertEquals(listOf("The weather is sunny today.", "Tomorrow brings rain in the afternoon!", "Should I set a reminder?"), c)
    }

    @Test fun keepsAbbreviationsAndNumbers() {
        val c = SpeechChunker.chunks("Dr. Banda moved your visit to 3.5 hours from now. Mr. Phiri is coming too.")
        assertEquals(listOf("Dr. Banda moved your visit to 3.5 hours from now.", "Mr. Phiri is coming too."), c)
    }

    @Test fun joinsTinyPieces() {
        val c = SpeechChunker.chunks("Okay. Sure. I booked the table for eight people at seven tonight.")
        assertEquals(1, c.size)
        assertTrue(c[0].startsWith("Okay. Sure."))
    }

    @Test fun firstPieceIsShort() {
        val long = "I looked through all of your messages from this week, including the ones from the bank, the school and the landlord, and found three that need a reply."
        val c = SpeechChunker.chunks(long)
        assertTrue(c.size >= 2)
        assertTrue(c[0].length <= 91)
        assertEquals(long.replace(" ", ""), c.joinToString("").replace(" ", ""))
    }

    @Test fun longSentencesAreCut() {
        val words = (1..80).joinToString(" ") { "word$it" }
        val c = SpeechChunker.chunks(words, firstMax = 90, max = 120)
        assertTrue(c.all { it.length <= 121 })
        assertEquals(words, c.joinToString(" "))
    }

    @Test fun linesAndBlanks() {
        assertEquals(emptyList<String>(), SpeechChunker.chunks("   \n  "))
        assertEquals(listOf("First line of the list", "Second line of the list"), SpeechChunker.chunks("First line of the list\nSecond line of the list"))
        assertEquals(emptyList<String>(), SpeechChunker.chunks("... !!"))
    }
}
