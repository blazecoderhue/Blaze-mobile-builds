package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.45.6 jank: what a chat row costs when it (re)enters the screen, before (parse every time, as when LazyColumn had
 * dropped the row's `remember`) and after (ParseMemo), plus the per-token cost of a streaming reply. Numbers are written
 * to build/bench/parse-0456.txt and quoted in the CHANGELOG. JVM timings on the build machine, not phone frame times.
 */
class ParseMemoTest {
    private fun reply(i: Int) = buildString {
        append("## Plan $i\n\nHere's what I found about **item $i**, with a [link](https://example.com/$i) and `code`.\n\n")
        for (k in 1..6) append("- Point $k for reply $i: some *emphasis* and plain words to wrap across the line\n")
        append("\n| Name | Value | Note |\n|---|---|---|\n")
        for (k in 1..5) append("| row $k | ${k * i} | ok |\n")
        append("\n```kotlin\nfun f$i(x: Int) = x * $i\nprintln(f$i(2))\n```\n\n")
        append("A closing paragraph with more text, numbers ${i * 3}, and a final sentence for reply $i.\n")
    }

    private val accent = Color(0xFF4DA8FF); private val codeBg = Color(0x22FFFFFF)

    /** The work AnswerBody + MdText do for one settled reply. */
    private fun rowWork(src: String): Int {
        val segs = AnswerCards.parse(src)
        val blocks = RichReplies.expand(segs, false)
        var n = blocks.size
        for (s in segs) if (s is AnswerCards.Seg.Text) n += Md.render(s.md, accent, codeBg).length
        return n
    }

    private fun rowWorkMemo(src: String): Int {
        val segs = ParseMemo.memo(ParseMemo.segs, src, false) { AnswerCards.parse(src) }
        val blocks = ParseMemo.memo(ParseMemo.blocks, "false|true|" + src, false) { RichReplies.expand(segs, false) }
        var n = blocks.size
        for (s in segs) if (s is AnswerCards.Seg.Text) n += ParseMemo.memo(ParseMemo.md, "${accent.value}|${codeBg.value}|" + s.md, false) { Md.render(s.md, accent, codeBg) }.length
        return n
    }

    @Test fun memoReturnsTheSameResultAndEvicts() {
        val m = MemoLru<String, List<Int>>(2)
        val a = m.get("a") { listOf(1) }
        assertSame(a, m.get("a") { listOf(9) })
        m.get("b") { listOf(2) }; m.get("c") { listOf(3) }
        assertEquals(2, m.size())
        assertEquals(listOf(9), m.get("a") { listOf(9) })   // evicted, made again
        var calls = 0
        repeat(3) { ParseMemo.memo(MemoLru<String, Int>(4), "x", live = true) { calls++; 1 } }
        assertEquals("live (streaming) texts are never cached", 3, calls)
        val src = reply(1)
        assertEquals(AnswerCards.parse(src), ParseMemo.memo(ParseMemo.segs, src, false) { AnswerCards.parse(src) })
    }

    @Test fun benchScrollReentryAndStreaming() {
        val replies = (1..60).map { reply(it) }
        repeat(2) { replies.forEach { rowWork(it) } }   // JIT warm-up
        ParseMemo.segs.clear(); ParseMemo.blocks.clear(); ParseMemo.md.clear()
        replies.forEach { rowWorkMemo(it) }             // first sight fills the memo

        fun timeMs(rounds: Int, f: () -> Unit): Double { val t = System.nanoTime(); repeat(rounds) { f() }; return (System.nanoTime() - t) / 1e6 / rounds }
        // Scrolling back through 60 replies (each row re-enters once).
        val before = timeMs(5) { replies.forEach { rowWork(it) } }
        val after = timeMs(5) { replies.forEach { rowWorkMemo(it) } }
        assertTrue("memo hits: ${ParseMemo.segs.hits}", ParseMemo.segs.hits >= 60)

        // Streaming: a ~4 KB reply arriving 4 characters at a time; each frame re-parses the whole text (unchanged in 0.45.6).
        val long = (1..4).joinToString("\n\n") { reply(it) }
        val perToken = ArrayList<Long>()
        var i = 4
        while (i <= long.length) { val t = System.nanoTime(); rowWork(long.substring(0, i)); perToken += System.nanoTime() - t; i += 4 }
        perToken.sort()
        val p50 = perToken[perToken.size / 2] / 1e6; val p95 = perToken[(perToken.size * 95) / 100] / 1e6

        val out = String.format(java.util.Locale.US,
            "scroll re-entry, 60 replies (%d chars avg): before %.2f ms, after %.3f ms per pass (%.1fx)\n" +
            "per row: before %.3f ms, after %.4f ms\n" +
            "streaming %d-char reply, %d frames: full re-parse per frame p50 %.3f ms, p95 %.3f ms (JVM, build machine)\n",
            replies.sumOf { it.length } / replies.size, before, after, before / maxOf(after, 1e-6), before / 60, after / 60, long.length, perToken.size, p50, p95)
        println(out)
        File("build/bench").apply { mkdirs() }.resolve("parse-0456.txt").writeText(out)
        assertTrue("memo must be faster: $out", after < before)
    }
}
