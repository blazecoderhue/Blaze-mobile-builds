package com.blaze.assistant.ui

import com.blaze.assistant.ui.RichReplies.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.24.9 rich replies: steps, section pictures, image groups, the deck and diagrams. */
class RichRepliesTest {
    private fun blocks(md: String, private: Boolean = false) = RichReplies.expand(AnswerCards.parse(md), private)
    private fun img(n: Int) = "![Photo $n](https://upload.wikimedia.org/p$n.jpg)"

    @Test fun plainTextStaysPlain() {
        val b = blocks("Hello there.\n\nNo pictures here.")
        assertEquals(1, b.size); assertTrue(b[0] is Block.Plain)
    }

    @Test fun stepFlow() {
        val b = blocks("Here's how:\n\n1. **Open Settings** and scroll down.\n2. **Tap Battery**: it's near the top.\n3. **Turn on Power saving**\n\nDone.")
        val steps = b.filterIsInstance<Block.Steps>().single().steps
        assertEquals(listOf("Open Settings", "Tap Battery", "Turn on Power saving"), steps.map { it.title })
        assertEquals("and scroll down.", steps[0].body)
        assertEquals("it's near the top.", steps[1].body)
        assertEquals("", steps[2].body)
        assertTrue(b.first() is Block.Plain); assertTrue(b.last() is Block.Plain)
        // A plain numbered list (no bold titles) isn't a step flow; one bold item isn't either.
        assertTrue(blocks("1. eggs\n2. milk\n3. bread").none { it is Block.Steps })
        assertTrue(blocks("1. **Only one** step").none { it is Block.Steps })
    }

    @Test fun pictureBesideItsSection() {
        val b = blocks("## Victoria Falls\n${img(1)}\nThe falls are 1.7 km wide.\nThey're on the Zambezi.\n\nMore later.")
        val it = b.filterIsInstance<Block.ImageText>().single()
        assertEquals("https://upload.wikimedia.org/p1.jpg", it.img.url)
        assertEquals("Photo 1", it.img.alt)
        assertEquals("The falls are 1.7 km wide.\nThey're on the Zambezi.", it.md)
        // A lone picture with nothing after it is just a picture.
        assertTrue(blocks("Look:\n\n${img(1)}").last() is Block.Image)
    }

    @Test fun imageGroupsRowOrDeck() {
        val three = blocks(listOf(img(1), img(2), img(3)).joinToString("\n")).filterIsInstance<Block.Images>().single()
        assertEquals(3, three.imgs.size)
        assertEquals(RichReplies.Layout.ROW, RichReplies.layout(three.imgs.size))
        val five = blocks((1..5).joinToString("\n\n") { img(it) }).filterIsInstance<Block.Images>().single()
        assertEquals(5, five.imgs.size)
        assertEquals(RichReplies.Layout.DECK, RichReplies.layout(4))
        assertEquals(RichReplies.Layout.ROW, RichReplies.layout(2))
    }

    @Test fun imageSyntax() {
        assertEquals("https://x.org/a.png", RichReplies.image("![a](https://x.org/a.png \"title\")")?.url)
        assertEquals(null, RichReplies.image("![a](javascript:alert(1))"))
        assertEquals(null, RichReplies.image("text ![a](https://x.org/a.png)"))
        assertEquals("/data/user/0/x/files/p.png", RichReplies.image("![](/data/user/0/x/files/p.png)")?.url)
    }

    @Test fun diagrams() {
        val b = blocks("Flow:\n\n```mermaid\ngraph TD\nA-->B\n```")
        val d = b.filterIsInstance<Block.Diagram>().single()
        assertEquals("mermaid", d.kind)
        assertTrue(d.url.startsWith("https://mermaid.ink/img/"))
        val payload = d.url.removePrefix("https://mermaid.ink/img/").substringBefore('?')
        assertEquals("graph TD\nA-->B", String(java.util.Base64.getUrlDecoder().decode(payload)))
        val g = blocks("```dot\ndigraph { a -> b }\n```").filterIsInstance<Block.Diagram>().single()
        assertEquals("graphviz", g.kind); assertTrue(g.url.startsWith("https://kroki.io/graphviz/png/"))
        // Kroki's payload inflates back to the source.
        val raw = java.util.Base64.getUrlDecoder().decode(g.url.substringAfterLast('/'))
        val inf = java.util.zip.Inflater(); inf.setInput(raw); val out = ByteArray(200); val n = inf.inflate(out)
        assertEquals("digraph { a -> b }", String(out, 0, n))
        // v0.26.3 private chat: still a diagram (drawn on the phone; DiagramRender.plan keeps it off the web renderers).
        val p = blocks("```mermaid\ngraph TD\nA-->B\n```", private = true).single() as Block.Diagram
        assertTrue(p.private)
        assertEquals(listOf(DiagramRender.Source.LOCAL), DiagramRender.plan(p.kind, p.private))
        // Other code stays code.
        assertTrue(blocks("```kotlin\nval a = 1\n```").single() is Block.Plain)
    }

    @Test fun deckMaths() {
        assertEquals(0f, RichReplies.tilt(0))
        assertTrue(RichReplies.tilt(1) < 0f && RichReplies.tilt(2) > 0f)
        assertTrue((1..10).all { kotlin.math.abs(RichReplies.tilt(it)) <= 7f })
        assertEquals(1, RichReplies.next(0, 5, 1)); assertEquals(4, RichReplies.next(0, 5, -1)); assertEquals(0, RichReplies.next(4, 5, 1))
        assertEquals(1, RichReplies.swipeDir(-100f, 300f)); assertEquals(-1, RichReplies.swipeDir(100f, 300f)); assertEquals(0, RichReplies.swipeDir(20f, 300f))
        assertEquals("2 / 5", RichReplies.counter(1, 5))
    }

    @Test fun flipAndRemoveAnimations() {
        assertEquals(0f, RichReplies.flip(0f, 1).first, 0.01f); assertFalse(RichReplies.flip(0f, 1).second)
        assertEquals(-90f, RichReplies.flip(0.5f, 1).first, 0.01f)
        assertFalse(RichReplies.flip(0.5f, 1).second)
        assertTrue(RichReplies.flip(0.75f, 1).second)
        assertEquals(0f, RichReplies.flip(1f, 1).first, 0.01f)
        assertEquals(90f, RichReplies.flip(0.5f, -1).first, 0.01f)
        assertTrue(RichReplies.animationsOff(0f)); assertFalse(RichReplies.animationsOff(1f)); assertFalse(RichReplies.animationsOff(0.5f))
    }

    @Test fun promptMentionsTheFormats() {
        assertTrue(RichReplies.PROMPT.contains("```mermaid"))
        assertTrue(RichReplies.PROMPT.contains("**bold titles**"))
        assertTrue(RichReplies.PROMPT.length < 6000)   // v0.26.1: the visual-answers prompt, still static (cached prefix)
    }
}
