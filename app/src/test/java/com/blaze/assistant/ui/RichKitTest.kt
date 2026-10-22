package com.blaze.assistant.ui

import com.blaze.assistant.ui.RichReplies.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.26.1 visual answers: chart, choices, form, tool and compare blocks, their fallbacks and messages. */
class RichKitTest {
    private fun blocks(md: String, private: Boolean = false) = RichReplies.expand(AnswerCards.parse(md), private)
    private fun fence(lang: String, body: String) = "Here you go:\n\n```$lang\n$body\n```\n\nThat's it."

    @Test fun barAndLineCharts() {
        val c = blocks(fence("chart", """{"type":"bar","title":"Sales","labels":["Jan","Feb","Mar"],"series":[{"name":"2025","values":[12,19,"7"]},{"name":"2026","values":[14,21]}],"unit":"K"}"""))
            .filterIsInstance<Block.Chart>().single().chart
        assertEquals(RichKit.ChartType.BAR, c.type); assertEquals("Sales", c.title)
        assertEquals(listOf("Jan", "Feb", "Mar"), c.labels)
        assertEquals(2, c.series.size); assertEquals(7.0, c.series[0].values[2], 0.0)
        assertTrue(c.series[1].values[2].isNaN())   // missing values are gaps, not zeros
        assertEquals("Feb · 2026: 21 K", RichKit.tipText(c, 1, 1))
        val l = RichKit.chart("""{"type":"line","data":[{"label":"2022","value":3.5},{"label":"2023","value":4.1}]}""")!!
        assertEquals(RichKit.ChartType.LINE, l.type); assertEquals(listOf("2022", "2023"), l.labels)
        assertEquals("2023: 4.10", RichKit.tipText(l, 0, 1))
        // Object-style data and plain values work too.
        assertEquals(listOf("A", "B"), RichKit.chart("""{"data":{"A":1,"B":2}}""")!!.labels)
        assertEquals(listOf("1", "2", "3"), RichKit.chart("""{"type":"bar","values":[1,2,3]}""")!!.labels)
    }

    @Test fun pieAndScatter() {
        val p = RichKit.chart("""{"type":"pie","labels":["Rent","Food","Transport"],"series":[{"values":[50,30,20]}]}""")!!
        assertEquals(RichKit.ChartType.PIE, p.type)
        assertEquals("Food: 30 (30%)", RichKit.tipText(p, 0, 1))
        assertEquals(0, RichKit.sliceAt(10f, p.series[0].values)); assertEquals(1, RichKit.sliceAt(200f, p.series[0].values)); assertEquals(2, RichKit.sliceAt(359f, p.series[0].values))
        // More than 8 slices fold into "Other".
        val big = RichKit.chart("""{"type":"pie","data":{${(1..12).joinToString(",") { "\"s$it\":$it" }}}}""")!!
        assertEquals(RichKit.MAX_SLICES, big.labels.size); assertEquals("Other", big.labels.last())
        assertNull(RichKit.chart("""{"type":"pie","values":[0,0]}"""))
        val s = RichKit.chart("""{"type":"scatter","xLabel":"Hours","yLabel":"Score","points":[[1,50],[2,60,"Ann"],{"x":3,"y":71}]}""")!!
        assertEquals(RichKit.ChartType.SCATTER, s.type); assertEquals(3, s.points[0].size)
        assertEquals("Ann: Hours 2, Score 60", RichKit.tipText(s, 0, 1))
        assertEquals(1, RichKit.nearest(52f, 48f, listOf(0f to 0f, 50f to 50f, 200f to 200f), 20f))
        assertNull(RichKit.nearest(500f, 500f, listOf(0f to 0f), 20f))
    }

    @Test fun axisAndLabels() {
        val (lo, hi, step) = RichKit.niceAxis(3.0, 97.0)
        assertEquals(0.0, lo, 0.0); assertEquals(100.0, hi, 0.0); assertEquals(25.0, step, 0.0)
        val z = RichKit.niceAxis(5.0, 5.0); assertTrue(z.second > z.first)
        assertEquals("12K", RichKit.short(12000.0)); assertEquals("3.5M", RichKit.short(3_500_000.0)); assertEquals("40", RichKit.short(40.0))
        assertEquals(0, RichKit.barIndex(5f, 300f, 3)); assertEquals(2, RichKit.barIndex(299f, 300f, 3)); assertEquals(-1, RichKit.barIndex(5f, 300f, 0))
    }

    @Test fun choices() {
        val c = blocks(fence("choices", """{"prompt":"Which one?","options":["Budget","Mid-range",{"label":"Best camera","send":"Show me the best camera phones"}]}"""))
            .filterIsInstance<Block.Choices>().single().choices
        assertEquals("Which one?", c.prompt)
        assertEquals(listOf("Budget", "Mid-range", "Best camera"), c.options.map { it.label })
        assertEquals("Show me the best camera phones", c.options[2].send)   // what a tap sends
        assertEquals("Budget", c.options[0].send)
        // ```buttons is the same block; a bare JSON array and plain lines work too.
        assertTrue(blocks(fence("buttons", """["Yes","No"]""")).any { it is Block.Choices })
        assertEquals(listOf("Yes", "No"), RichKit.choices("- Yes\n- No")!!.options.map { it.label })
        // At most 6, duplicates dropped.
        assertEquals(RichKit.MAX_CHOICES, RichKit.choices((1..9).joinToString(",", "[", "]") { "\"o$it\"" })!!.options.size)
        assertEquals(1, RichKit.choices("""["Yes","yes"]""")!!.options.size)
    }

    @Test fun formAndItsMessage() {
        val f = blocks(fence("form", """{"title":"Trip plan","fields":[{"id":"city","label":"City","type":"text","placeholder":"Lusaka"},
            {"id":"days","label":"Days","type":"number","default":3},{"id":"budget","label":"Budget","type":"select","options":["Low","Mid","High"],"default":"Mid"},
            {"id":"kids","label":"With kids","type":"toggle"}],"submit":"Plan it"}""")).filterIsInstance<Block.Form>().single().form
        assertEquals(4, f.fields.size); assertEquals("Plan it", f.submit)
        assertEquals(RichKit.FieldType.SELECT, f.fields[2].type); assertEquals("Mid", f.fields[2].default)
        assertEquals("no", f.fields[3].default)
        assertEquals("Trip plan: City: Livingstone · Days: 3 · Budget: High · With kids: yes",
            RichKit.formMessage(f, mapOf("city" to "Livingstone", "budget" to "High", "kids" to "yes")))
        // Blank fields are left out; nothing filled sends the title.
        assertEquals("Trip plan: Days: 3 · Budget: Mid · With kids: no", RichKit.formMessage(f, mapOf("city" to "  ")))
        val empty = RichKit.form("""{"fields":[{"label":"Name"}],"submit":"Go"}""")!!
        assertEquals("Go", RichKit.formMessage(empty, emptyMap()))
        assertEquals("-12.5", RichKit.numberInput("-1,2.5x")); assertEquals("3.14", RichKit.numberInput("3.1.4"))
        assertNull(RichKit.form("""{"fields":[]}"""))
    }

    @Test fun billSplitterTool() {
        val t = blocks(fence("tool", """{"title":"Bill splitter","inputs":[{"id":"bill","label":"Bill","type":"number","default":450,"prefix":"K"},
            {"id":"people","label":"People","type":"slider","min":1,"max":12,"step":1,"default":3},{"id":"tip","label":"Tip","type":"slider","min":0,"max":25,"default":10,"suffix":"%"}],
            "outputs":[{"label":"Tip","formula":"bill*tip/100","prefix":"K"},{"label":"Each pays","formula":"bill*(1+tip/100)/people","prefix":"K"}]}"""))
            .filterIsInstance<Block.Tool>().single().tool
        assertEquals(3, t.inputs.size); assertTrue(t.outputs.last().big)
        assertEquals(listOf("K45.00", "K165.00"), RichKit.compute(t, RichKit.defaults(t)))
        assertEquals(listOf("K45.00", "K247.50"), RichKit.compute(t, RichKit.defaults(t) + ("people" to 2.0)))
        assertEquals(listOf("K45.00", ""), RichKit.compute(t, RichKit.defaults(t) + ("people" to 0.0)))   // ÷0 → blank
        assertEquals("10%", RichKit.showInput(t.inputs[2], 10.0)); assertEquals("K450", RichKit.showInput(t.inputs[0], 450.0))
        assertEquals(4.0, RichKit.snap(3.6, t.inputs[1]), 0.0); assertEquals(12.0, RichKit.snap(40.0, t.inputs[1]), 0.0)
        // Select inputs carry numeric values.
        val s = RichKit.tool("""{"inputs":[{"id":"freq","type":"select","options":[{"label":"Monthly","value":12},{"label":"Weekly","value":52}]}],"outputs":[{"label":"Per year","formula":"freq*100","decimals":0}]}""")!!
        assertEquals("Monthly", RichKit.showInput(s.inputs[0], 12.0)); assertEquals(listOf("1,200"), RichKit.compute(s, RichKit.defaults(s)))
    }

    @Test fun toolRejectsUnsafeOrBrokenFormulas() {
        // Outputs with unknown names or functions are dropped; with none left there's no tool (shown as code instead).
        assertNull(RichKit.tool("""{"inputs":[{"id":"a"}],"outputs":[{"label":"x","formula":"Runtime.exec(a)"}]}"""))
        val t = RichKit.tool("""{"inputs":[{"id":"a","default":2}],"outputs":[{"label":"bad","formula":"b*2"},{"label":"ok","formula":"a*2","decimals":0}]}""")!!
        assertEquals(listOf("ok"), t.outputs.map { it.label })
        // Input ids must be simple names and not function names.
        assertNull(RichKit.tool("""{"inputs":[{"id":"max"},{"id":"a b"}],"outputs":[{"formula":"1"}]}"""))
    }

    @Test fun compare() {
        val c = blocks(fence("compare", """{"title":"iPhone 16 vs Galaxy S25","columns":["iPhone 16","Galaxy S25"],
            "rows":[{"label":"Screen","values":["6.1 in","6.2 in"]},["Battery","3561 mAh","4000 mAh"]],"verdict":"S25 for battery.","pick":"Galaxy S25"}"""))
            .filterIsInstance<Block.Compare>().single().compare
        assertEquals(listOf("iPhone 16", "Galaxy S25"), c.columns)
        assertEquals(listOf("Screen", "Battery"), c.rows.map { it.label }); assertEquals("4000 mAh", c.rows[1].values[1])
        assertEquals(1, c.pick); assertEquals("S25 for battery.", c.verdict)
        val o = RichKit.compare("""{"columns":[{"name":"A","values":{"Price":"K10","Size":"S"}},{"name":"B","values":{"Price":"K12"}}]}""")!!
        assertEquals(setOf("Price", "Size"), o.rows.map { it.label }.toSet())   // key order follows the JSON library
        assertEquals("", o.rows.single { it.label == "Size" }.values[1])
        assertNull(RichKit.compare("""{"columns":["Only one"],"rows":[["x","y"]]}"""))
    }

    @Test fun malformedJsonFallsBackToCode() {
        for (lang in listOf("chart", "form", "tool", "compare", "choices")) {
            val b = blocks(fence(lang, """{"type": "bar", "labels": [oops"""))
            assertTrue(lang, b.any { it is Block.Plain && (it.seg as? AnswerCards.Seg.Code)?.lang == lang })
            assertFalse(lang, b.any { it is Block.Pending })
        }
        // Wrong shapes and wrong types don't crash either.
        assertTrue(blocks(fence("chart", """{"type":"radar","values":[1]}""")).any { it is Block.Plain })
        assertTrue(blocks(fence("chart", "[1,2,3]")).any { it is Block.Plain })
        assertTrue(blocks(fence("tool", """{"inputs":"nope","outputs":5}""")).any { it is Block.Plain })
        assertTrue(blocks(fence("form", "null")).any { it is Block.Plain })
        // Lenient bits a model often gets wrong are accepted: trailing commas and smart quotes.
        assertNotNull(RichKit.chart("""{"labels":["a","b",],"series":[{"values":[1,2],}],}"""))
        assertNotNull(RichKit.choices("[\u201cYes\u201d, \u201cNo\u201d]"))
    }

    @Test fun partialJsonWhileStreaming() {
        // An unclosed fence with half the JSON is a quiet placeholder, never raw JSON and never a crash.
        val half = """{"type":"bar","labels":["Jan","Feb"],"series":[{"name":"Sales","val"""
        for (lang in listOf("chart", "form", "tool", "compare", "choices")) {
            val b = blocks("Look:\n\n```$lang\n$half")
            val p = b.filterIsInstance<Block.Pending>().single()
            assertEquals(RichKit.LANGS[lang], p.kind); assertTrue(p.fallback is Block.Plain)
            for (cut in 1 until half.length step 7) RichReplies.expand(AnswerCards.parse("```$lang\n" + half.take(cut)))   // never throws
        }
        // A whole JSON body before the closing fence already draws.
        assertTrue(blocks("```chart\n{\"values\":[1,2]}").any { it is Block.Chart })
        // A diagram waits for its closing fence instead of fetching a half diagram.
        val d = blocks("```mermaid\nflowchart LR\nA-->").single()
        assertTrue(d is Block.Pending && d.fallback is Block.Diagram)
        assertEquals("Drawing a chart\u2026", RichReplies.pendingLabel("chart"))
    }

    @Test fun privateChatStillDrawsOfflineBlocks() {
        // Charts and tools are drawn on the phone (nothing leaves it), so Private keeps them; since v0.26.3 Mermaid too
        // (bundled mermaid.js), while Graphviz & co. never go to a web renderer from a private chat.
        assertTrue(blocks(fence("chart", """{"values":[1,2]}"""), private = true).any { it is Block.Chart })
        val m = blocks("```mermaid\ngraph TD\nA-->B\n```", private = true).single() as Block.Diagram
        assertEquals(listOf(DiagramRender.Source.LOCAL), DiagramRender.plan(m.kind, m.private))
        val g = blocks("```dot\ndigraph { a -> b }\n```", private = true).single() as Block.Diagram
        assertTrue(DiagramRender.plan(g.kind, g.private).isEmpty())
    }

    @Test fun copyAsReadableText() {
        val md = "Split:\n\n```tool\n{\"title\":\"Bill splitter\",\"inputs\":[{\"id\":\"bill\",\"label\":\"Bill\",\"default\":450,\"prefix\":\"K\"},{\"id\":\"n\",\"label\":\"People\",\"default\":3}],\"outputs\":[{\"label\":\"Each pays\",\"formula\":\"bill/n\",\"prefix\":\"K\"}]}\n```\n\n" +
            "```chart\n{\"title\":\"Sales\",\"labels\":[\"Jan\",\"Feb\"],\"series\":[{\"values\":[1,2]}]}\n```\n\n```kotlin\nval a = 1\n```"
        val r = RichKit.readable(md)
        assertTrue(r, r.contains("Each pays: K150.00")); assertTrue(r.contains("- Jan: 1")); assertTrue(r.contains("val a = 1"))
        assertFalse(r.contains("\"formula\""))
        // Broken blocks are copied as they are.
        assertEquals("```chart\n{oops\n```", RichKit.readable("```chart\n{oops\n```"))
    }

    @Test fun promptPicksFormatsOnItsOwn() {
        val p = RichReplies.PROMPT
        for (w in listOf("```chart", "```compare", "```tool", "```choices", "```form", "```mermaid", "how do solar panels work", "Inverter", "plain text", "without being asked"))
            assertTrue(w, p.contains(w))
        assertTrue(p.contains("Don't write files") && p.contains("preview_server") && p.contains("use_skill"))
        assertTrue(p.length < 6000)
    }
}
