package com.blaze.assistant.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBubbleTest {
    // v0.31.0: particles are monochrome (greyscale glass), no colour cast.
    @Test fun particlesAreGreyscale() {
        for (d in listOf(false, true)) {
            val c = ParticleBlue.of(d)
            assertTrue("grey", kotlin.math.abs(c.blue - c.red) < 0.05f && kotlin.math.abs(c.green - c.red) < 0.05f)
        }
    }

    // v0.31.0 borderless thread -> 0.45.20 (user spec A): messages are bubbles again, drawn by ChatBubble (ui/ChatBubbles.kt,
    // AA-checked frost in ChatUi20Test); cards (the work card) stay glass.
    @Test fun messagesAreBubblesCardsAreGlass() {
        val src = java.io.File("src/main/java/com/blaze/assistant/ui/Bubbles.kt").readText()
        val user = src.substring(src.indexOf("fun UserBubble("), src.indexOf("fun BlazeBubble("))
        val blaze = src.substring(src.indexOf("fun BlazeBubble("), src.indexOf("fun BlazeReply("))
        assertTrue("your message is a bubble on the right", user.contains("ChatBubble(Side.ME"))
        assertTrue("Blaze's message is a bubble on the left", blaze.contains("ChatBubble(Side.BLAZE"))
        assertTrue("no old see-through glass around messages", !user.contains("chatGlass(") && !blaze.contains("chatGlass("))
        assertTrue("work card is glass", java.io.File("src/main/java/com/blaze/assistant/ui/Shell.kt").readText().contains("chatGlass(c, mine = false"))
    }

    // v0.30.5: dark = faint white-tinted glass with a light hairline; light = white frost with a soft darker hairline.
    @Test fun glassFitsTheTheme() {
        for (c in listOf(LightColors, DarkColors)) {
            for (f in listOf(GlassBubble.mineFill(c), GlassBubble.blazeFill(c))) {
                assertTrue("fill ${f.alpha}", f.alpha <= GlassBubble.maxAlpha(c.dark))
                assertTrue("see-through", f.alpha < 1f && f.alpha > 0f)
                assertTrue("whitish glass", f.red > 0.6f && f.green > 0.6f)
            }
            assertTrue("cards inside see-through", GlassBubble.cardFill(c).alpha < 0.5f)
            val h = GlassBubble.hairline(c)
            assertTrue("hairline visible but soft ${h.alpha}", h.alpha in 0.08f..0.3f)
            if (c.dark) assertTrue("light hairline on dark", h.red > 0.9f) else assertTrue("dark hairline on light", h.red < 0.1f)
            val halo = GlassBubble.halo(c)
            assertTrue("halo glows", halo.blurRadius > 0f && halo.color.alpha > 0.7f)
        }
    }

    @Test fun picturesStandOutsideTheBubble() {
        val src = java.io.File("src/main/java/com/blaze/assistant/ui/Attach.kt").readText()
        val sent = src.substring(src.indexOf("fun SentBubble("), src.indexOf("fun ViewOfferChip("))
        assertTrue("no bubble glass around pictures", !sent.contains("GlassBubble.") && !sent.contains("chatGlass("))
        assertTrue("words get their own bubble", sent.contains("UserBubble(words, quoted, pos)"))
    }

    @Test fun noOpaqueCardsInsideBubbles() {
        val cards = java.io.File("src/main/java/com/blaze/assistant/ui/AnswerCards.kt").readText()
        val rich = java.io.File("src/main/java/com/blaze/assistant/ui/RichInteractive.kt").readText()
        assertTrue(!cards.contains("LocalBubble.current != null) c.inset") && !rich.contains("LocalBubble.current != null) c.inset"))
        assertTrue("reply text keeps its halo", cards.contains("GlassBubble.halo(c)"))
        // 0.45.20: words in a bubble sit on its AA frost (BubblePalette), so they need no halo of their own.
        assertTrue(java.io.File("src/main/java/com/blaze/assistant/ui/ChatBubbles.kt").readText().contains("fun fill(c: BlazeColors, mine: Boolean)"))
    }
}
