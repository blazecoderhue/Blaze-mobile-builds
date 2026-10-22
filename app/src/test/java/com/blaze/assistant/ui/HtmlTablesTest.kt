package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.31.1: tables in fences / without outer pipes / ASCII grids, and HTML in replies. */
class HtmlTablesTest {
    private fun blocks(md: String) = RichReplies.expand(AnswerCards.parse(md))
    private fun tables(md: String) = blocks(md).filterIsInstance<RichReplies.Block.Table>()

    @Test fun pipeTableInsideBareFenceIsATable() {
        val md = "Here:\n\n```\n| Plan | Price |\n|---|---|\n| Basic | K50 |\n| Pro | K120 |\n```\n\nDone."
        val t = tables(md)
        assertEquals(1, t.size)
        assertEquals(listOf("Plan", "Price"), t[0].table.headers)
        assertEquals(2, t[0].table.rows.size)
        assertTrue(blocks(md).none { it is RichReplies.Block.Plain && it.seg is AnswerCards.Seg.Code })
    }

    @Test fun mdAndTextFencesToo() {
        for (lang in listOf("md", "markdown", "text", "table")) {
            assertEquals(lang, 1, tables("```$lang\nA | B\n:--|--:\n1 | 2\n```").size)
        }
        // real code stays code
        assertTrue(blocks("```kotlin\nval a = b | c\n```").any { it is RichReplies.Block.Plain && it.seg is AnswerCards.Seg.Code })
        // ```table JSON keeps working
        assertEquals(1, tables("```table\n{\"columns\":[\"A\",\"B\"],\"rows\":[[\"1\",\"2\"]]}\n```").size)
    }

    @Test fun barePipeTablesWithoutOuterPipes() {
        val t = tables("Prices:\nName | Cost | Note\n--- | :---: | ---:\nTea | K5 | hot\nCake | K12 | a \\| b\n\nEnd")
        assertEquals(1, t.size)
        assertEquals(3, t[0].table.headers.size)
        assertEquals("a | b", t[0].table.rows[1][2])
    }

    @Test fun ruleUnderALineWithAPipeIsNotATable() {
        assertTrue(tables("a | b\n---\ntext").isEmpty())
    }

    @Test fun asciiGrid() {
        val g = "```\n+------+-----+\n| Name | Age |\n+======+=====+\n| Ann  | 31  |\n| Bob  | 27  |\n+------+-----+\n```"
        val t = tables(g)
        assertEquals(1, t.size)
        assertEquals(listOf("Name", "Age"), t[0].table.headers)
        assertEquals(listOf(listOf("Ann", "31"), listOf("Bob", "27")), t[0].table.rows)
        val box = "┌──────┬─────┐\n│ City │ Pop │\n├──────┼─────┤\n│ Ndola│ 1M  │\n└──────┴─────┘"
        assertEquals(1, tables(box).size)
    }

    @Test fun htmlTableAndImage() {
        val md = "<p>Look:</p><table><caption>Plans</caption><tr><th>Plan</th><th>Price</th></tr><tr><td><b>Basic</b></td><td>K50</td></tr></table>" +
            "<img src=\"https://example.com/a.png\" alt=\"A cat\">"
        val b = blocks(md)
        val t = b.filterIsInstance<RichReplies.Block.Table>()
        assertEquals(1, t.size)
        assertEquals(listOf("Plan", "Price"), t[0].table.headers)
        assertEquals("<b>Basic</b>", t[0].table.rows[0][0])
        assertTrue(b.any { it is RichReplies.Block.Image && it.img.url == "https://example.com/a.png" })
    }

    @Test fun htmlBlocksBecomeMarkdown() {
        val n = HtmlLite.normalize("<h2>Title</h2><ul><li>One</li><li>Two</li></ul><blockquote>Quote</blockquote><hr><pre><code class=\"language-py\">x = 1 &lt; 2</code></pre>")
        assertTrue(n, n.contains("## Title"))
        assertTrue(n, n.contains("- One\n- Two"))
        assertTrue(n, n.contains("> Quote"))
        assertTrue(n, n.contains("---"))
        assertTrue(n, n.contains("```py\nx = 1 < 2\n```"))
    }

    @Test fun scriptsAreRemoved() {
        val n = HtmlLite.normalize("Hi<script>alert(1)</script><iframe src=x></iframe> there")
        assertFalse(n.contains("alert"))
        assertFalse(n.contains("iframe"))
    }

    @Test fun inlineHtmlRendersAndUnknownTagsStrip() {
        val a = Md.render("<b>bold</b> <i>it</i> <u>u</u> <span style=\"color:red\">red</span> <x-y>keep</x-y> <div>d</div> List<String> &lt;b&gt;",
            Color.White, Color.Gray)
        assertEquals("bold it u red <x-y>keep</x-y> d List<String> <b>", a.text)
        assertTrue(a.spanStyles.isNotEmpty())
        val link = Md.render("<a href=\"javascript:alert(1)\">x</a> <a href=\"https://a.b\">y</a>", Color.White, Color.Gray)
        assertEquals("x y", link.text)
        assertEquals(1, link.getStringAnnotations("URL", 0, link.length).size)
    }

    @Test fun partialTagHiddenWhileStreaming() {
        assertEquals("Hello ", Md.render("Hello <str", Color.White, Color.Gray, live = true).text)
        assertEquals("a < b", Md.render("a < b", Color.White, Color.Gray, live = true).text)
    }

    @Test fun colourIsToned() {
        assertNotNull(HtmlLite.tint("#ff0000", dark = true))
        assertNull(HtmlLite.tint("notacolour", dark = true))
    }
}
