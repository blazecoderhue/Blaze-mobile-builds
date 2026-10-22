package com.blaze.assistant.ui

import com.blaze.assistant.ui.RichReplies.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.28.1: the table card (```table and Markdown pipe tables), its copy as text / TSV and its column maths. */
class TableCardTest {
    private val json = """{"title":"Airtel data bundles","columns":["Bundle","Price","Data","Valid for"],
        "rows":[["Daily","K5","1 GB","24 hours"],["Weekly","K30","5 GB","7 days"],{"Bundle":"Monthly","Price":"K100","Data":"25 GB","Valid for":"30 days"}]}"""

    @Test fun parsesTableBlock() {
        val t = RichKit.table(json)!!
        assertEquals("Airtel data bundles", t.title)
        assertEquals(listOf("Bundle", "Price", "Data", "Valid for"), t.headers)
        assertEquals(3, t.rows.size); assertEquals(listOf("Monthly", "K100", "25 GB", "30 days"), t.rows[2])
        assertTrue(t.sticky); assertEquals(4, t.cols)
        assertFalse(RichKit.table("""{"title":"x","columns":["a"],"rows":[["1"]],"sticky":false}""")!!.sticky)
        assertNull(RichKit.table("""{"title":"empty","columns":["a","b"],"rows":[]}"""))
        assertNull(RichKit.table("not json"))
    }

    @Test fun tableFenceAndPipeTablesBecomeCards() {
        val a = RichReplies.expand(AnswerCards.parse("Here:\n\n```table\n$json\n```"))
        assertTrue(a.any { it is Block.Table && it.table.title == "Airtel data bundles" })
        val b = RichReplies.expand(AnswerCards.parse("Plans:\n\n| Plan | Price |\n|---|---|\n| Basic | K50 |\n| Pro | **K120** |\n\nDone."))
        val t = b.filterIsInstance<Block.Table>().single().table
        assertEquals(listOf("Plan", "Price"), t.headers); assertEquals(2, t.rows.size)
        assertTrue(RichReplies.PROMPT.contains("```table"))
        assertEquals("Setting out the table\u2026", RichReplies.pendingLabel("table"))
    }

    @Test fun copyAsPlainTextAndTsv() {
        val t = RichKit.TableCard("Plans", listOf("Plan", "Price"), listOf(listOf("Basic", "K50"), listOf("**Pro**", "[K120](https://x.com)")))
        assertEquals("Plans\n\nPlan   Price\nBasic  K50\nPro    K120", RichKit.tablePlain(t))
        assertEquals("Plan\tPrice\nBasic\tK50\nPro\tK120", RichKit.tableTsv(t))
        assertEquals("Airtel data bundles\n\nBundle", RichKit.readable("```table\n$json\n```").lines().take(3).joinToString("\n").substringBefore("  "))
    }

    @Test fun columnsFillWhenTheyFitAndScrollWhenNot() {
        assertEquals(listOf(100, 200), TableCardStyle.widths(listOf(50, 100), 50, 400, 300))
        // v0.30.5: wider than the card shrinks to fit (no sideways scroll, nothing cut off)
        val w = TableCardStyle.widths(listOf(300, 300, 300), 80, 200, 400)
        assertEquals(400, w.sum()); assertTrue(w.all { it >= 80 })
        val m = TableCardStyle.widths(listOf(90, 400, 400), 84, 200, 300)
        assertEquals(300, m.sum()); assertTrue(m[0] >= 84 && m[1] > m[0])
        // Too many columns for the phone: stacked rows instead of a cut-off grid.
        assertTrue(TableCardStyle.stacked(5, 320f)); assertFalse(TableCardStyle.stacked(3, 320f)); assertFalse(TableCardStyle.stacked(2, 100f))
        assertTrue(TableCardStyle.sticky(true, 4, scrolls = true))
        assertFalse(TableCardStyle.sticky(true, 2, scrolls = true))
        assertFalse(TableCardStyle.sticky(true, 4, scrolls = false))
    }
}
