package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.28.1: markdown renders as formatting everywhere (Luke's own sent message showed raw "## App", "1. **Offline mode**:"). */
class MdTest {
    private val sample = "## App\n1. **Offline mode**: works without data\n2. *Sync* with `git`\n- [Docs](https://example.com)"

    @Test fun lukesSampleHasNoRawSymbols() {
        val r = Md.render(sample, Color.Blue, Color.Gray)
        assertEquals("App\n1.  Offline mode: works without data\n2.  Sync with  git \n•  Docs", r.text)
        for (ch in listOf("#", "**", "`", "](")) assertFalse("raw $ch", r.text.contains(ch))
    }

    @Test fun boldItalicHeadingAndLinkAreStyled() {
        val r = Md.render(sample, Color.Blue, Color.Gray)
        fun at(word: String) = r.text.indexOf(word)
        assertTrue(r.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold && it.start <= at("App") && it.end >= at("App") + 3 })
        assertTrue(r.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold && it.start == at("Offline mode") && it.end == at("Offline mode") + 12 })
        assertTrue(r.spanStyles.any { it.item.fontStyle == FontStyle.Italic && it.start == at("Sync") })
        assertEquals("https://example.com", r.getStringAnnotations("URL", 0, r.length).single().item)
    }

    @Test fun copyIsPlainText() {
        assertEquals("App\n1.  Offline mode: works without data\n2.  Sync with  git \n•  Docs", Md.plain(sample))
    }

    @Test fun unfinishedMarkersWhileStreamingAreHidden() {
        assertEquals("1.  Offline mo", Md.render("1. **Offline mo", Color.Blue, Color.Gray).text)
        assertEquals("Run ls -la", Md.render("Run `ls -la", Color.Blue, Color.Gray).text)
        assertEquals("a b", Md.tidy("a b"))
        assertEquals("**a** b", Md.tidy("**a** b"))
    }

    @Test fun headingWithoutASpace() {
        assertEquals("Features", Md.render("##Features", Color.Blue, Color.Gray).text)
        assertEquals("#hashtag", Md.render("#hashtag", Color.Blue, Color.Gray).text)   // a hashtag stays a hashtag
    }

    @Test fun inlineIsOneLine() {
        assertEquals("Saved notes and Plan", Md.inline("Saved **notes**\nand *Plan*", Color.Blue, Color.Gray).text)
    }
}
