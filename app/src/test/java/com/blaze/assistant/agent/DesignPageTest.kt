package com.blaze.assistant.agent

import com.blaze.assistant.agent.DesignPage.Frame
import com.blaze.assistant.agent.DesignPage.Handle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.35.0: the Page model behind the native design studio (geometry, colour, check, file round trip). */
class DesignPageTest {
    private val f = Frame(100.0, 100.0, 200.0, 100.0)

    @Test fun hexForms() {
        assertEquals(0xFFFF0000L, DesignPage.parseHex("#f00"))
        assertEquals(0x88FF0000L, DesignPage.parseHex("#F008"))
        assertEquals(0xFF4DA8FFL, DesignPage.parseHex("4DA8FF"))
        assertEquals(0x594DA8FFL, DesignPage.parseHex("#4DA8FF59"))
        assertNull(DesignPage.parseHex("#12"))
        assertNull(DesignPage.parseHex("blue"))
        for (h in listOf("#4DA8FF", "#0B0B0E", "#4DA8FF59")) assertEquals(h, DesignPage.toHex(DesignPage.parseHex(h)!!))
    }

    @Test fun hsvRoundTrip() {
        assertEquals(0xFFFF0000L, DesignPage.hsvToArgb(0.0, 1.0, 1.0))
        assertEquals(0xFF00FF00L, DesignPage.hsvToArgb(120.0, 1.0, 1.0))
        val c = DesignPage.parseHex("#4DA8FF")!!
        val hsv = DesignPage.argbToHsv(c)
        assertEquals(c, DesignPage.hsvToArgb(hsv[0], hsv[1], hsv[2]))
    }

    @Test fun contrastIsWcag() {
        assertEquals(21.0, DesignPage.contrast(0xFF000000L, 0xFFFFFFFFL), 0.01)
        assertEquals(1.0, DesignPage.contrast(0xFF777777L, 0xFF777777L), 0.01)
    }

    @Test fun moveAndResize() {
        assertEquals(Frame(130.0, 90.0, 200.0, 100.0), DesignPage.drag(f, Handle.MOVE, 30.0, -10.0))
        assertEquals(Frame(100.0, 100.0, 250.0, 120.0), DesignPage.drag(f, Handle.BR, 50.0, 20.0))
        assertEquals(Frame(120.0, 110.0, 180.0, 90.0), DesignPage.drag(f, Handle.TL, 20.0, 10.0))
        assertEquals(Frame(100.0, 100.0, 240.0, 100.0), DesignPage.drag(f, Handle.R, 40.0, 99.0))
        assertEquals(Frame(100.0, 80.0, 200.0, 120.0), DesignPage.drag(f, Handle.T, 99.0, -20.0))
    }

    @Test fun resizeNeverCollapsesOrFlips() {
        val r = DesignPage.drag(f, Handle.BR, -500.0, -500.0)
        assertTrue(r.w >= DesignPage.MIN_SIZE && r.h >= DesignPage.MIN_SIZE)
        assertEquals(100.0, r.x, 0.0)
        val l = DesignPage.drag(f, Handle.L, 500.0, 0.0)
        assertEquals(DesignPage.MIN_SIZE, l.w, 0.0)
        assertEquals(300.0 - DesignPage.MIN_SIZE, l.x, 0.0)   // the right edge stays put
    }

    @Test fun moveKeepsPartOnThePage() {
        val m = DesignPage.drag(f, Handle.MOVE, 5000.0, 5000.0, bounds = 1080.0 to 1350.0)
        assertTrue("some of it stays reachable", m.x < 1080 && m.y < 1350)
        val n = DesignPage.drag(f, Handle.MOVE, -5000.0, -5000.0, bounds = 1080.0 to 1350.0)
        assertTrue(n.x + n.w > 0 && n.y + n.h > 0)
    }

    @Test fun reformatKeepsElementsOnThePage() {
        val d = DesignPage.starter("Hello")
        val story = DesignPage.reformat(d, DesignPage.format("story"))
        assertEquals("story", story.format)
        assertEquals(1080 to 1920, story.w to story.h)
        assertEquals(d.els.size, story.els.size)
        story.els.forEach { assertTrue(it.id, it.x >= 0 && it.y >= 0 && it.x + it.w <= story.w + 0.5 && it.y + it.h <= story.h + 0.5) }
    }

    @Test fun bringToFrontPaintsLast() {
        val d = DesignPage.starter("Hello")
        assertEquals("eyebrow", DesignPage.bringToFront(d, "eyebrow").painted().last().id)
    }

    @Test fun critiqueFlagsLowContrastAndOffPage() {
        val d = DesignPage.starter("Hello")
        assertTrue(DesignPage.critique(d).score >= 85)
        val grey = d.replace(d.el("body")!!.copy(color = "#1A1A1E"))
        val c = DesignPage.critique(grey)
        assertTrue(c.score < 100); assertEquals("body", c.weakest)
        val off = d.replace(d.el("eyebrow")!!.copy(x = 5000.0))
        assertTrue(DesignPage.critique(off).note.contains("off the page"))
    }

    @Test fun htmlRoundTrip() {
        val d = DesignPage.starter("Launch <night> & \"more\"")
        val html = DesignPage.render(d)
        assertTrue(html.contains("id=\"blaze-page\""))
        assertEquals(d, DesignPage.fromHtml(html))
        assertNull(DesignPage.fromHtml("<html><body>plain</body></html>"))
        assertTrue("no script injection through text", !html.contains("</script><script"))
    }

    @Test fun mergeAddsChangesRemoves() {
        val d = DesignPage.starter("Hello")
        val set = JSONArray().put(JSONObject().put("id", "headline").put("color", "#FFD166"))
            .put(JSONObject().put("id", "badge").put("type", "rect").put("fill", "#FF0000"))
        val (m, summary) = DesignPage.merge(d, set, listOf("eyebrow"))
        assertEquals("#FFD166", m.el("headline")!!.color)
        assertEquals("Hello", m.el("headline")!!.text)
        assertNotNull(m.el("badge")); assertNull(m.el("eyebrow"))
        assertTrue(summary, summary.contains("added badge") && summary.contains("changed headline") && summary.contains("removed eyebrow"))
    }

    @Test fun studioSourcesNeverNameTheOtherAssistant() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val files = listOf("agent/DesignPage.kt", "ui/DesignStudio.kt", "ui/ColorWheel.kt", "ui/DesignView.kt")
            .map { File(root, "com/blaze/assistant/$it") }
        val word = String(charArrayOf('h', 'a', 'r', 'k'))
        files.forEach { assertTrue(it.name, !it.readText().lowercase().contains(word)) }
    }
}
