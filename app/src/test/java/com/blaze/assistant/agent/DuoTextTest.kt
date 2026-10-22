package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.43.0: tool calls written as text in Halo x Blaze messages (Halo's whole reply was "browser_show()"). */
class DuoTextTest {
    private val room = setOf("web_search", "fetch_page", "recall_memory")

    @Test fun findsThePhoneCase() {
        val c = DuoText.find("browser_show()")
        assertEquals(1, c.size); assertEquals("browser_show", c[0].name); assertEquals(0, c[0].args.length())
        assertTrue(DuoText.onlyCalls("browser_show()"))
        assertEquals("", DuoText.strip("browser_show()"))
    }

    @Test fun pythonStyleArgsAndVariants() {
        val c = DuoText.find("web_search(query=\"weather Lusaka\", count=3)")[0]
        assertEquals("web_search", c.name); assertEquals("weather Lusaka", c.args.getString("query")); assertEquals(3L, c.args.getLong("count"))
        assertEquals("desktop", DuoText.find("`desktop(action='status')`", known = listOf("desktop"))[0].name)
        assertEquals("status", DuoText.find("desktop(action='status')", known = listOf("desktop"))[0].args.getString("action"))
        assertEquals("open_view", DuoText.find("open_view {\"view\": \"linux\"}")[0].name)
        assertEquals("linux", DuoText.find("open_view {\"view\": \"linux\"}")[0].args.getString("view"))
        assertEquals("browser_show", DuoText.find("{\"name\": \"browser_show\", \"arguments\": {}}")[0].name)
        // Older forms ToolLeak knows still count.
        assertEquals("find_tool", DuoText.find("[/find_tool need=\"stock tracking\"]")[0].name)
        assertEquals("browser_show", DuoText.find("<tool_call>{\"name\":\"browser_show\",\"arguments\":{}}</tool_call>")[0].name)
    }

    @Test fun normalTextIsLeftAlone() {
        listOf("Sure! f(x) = 2x + 1, so f(3) is 7.", "Call me (maybe) later.", "Yes, it's running (I checked).", "print() works", "Hello(there)",
            "That's the plan. [[to: user]]", "Use `ls -la` in the terminal.").forEach {
            assertTrue(it, DuoText.find(it).isEmpty())
            assertEquals(it, DuoText.strip(it))
        }
        // Code the user asked for, inside a fence, is code.
        val code = "Here you go:\n```python\ndef add_one(x):\n    return x + 1\n\nadd_one(2)\n```"
        assertTrue(DuoText.find(code).isEmpty())
    }

    @Test fun stripKeepsTheWordsAround() {
        assertEquals("Let me show you the browser.", DuoText.strip("Let me show you the browser.\nbrowser_show()"))
        assertFalse(DuoText.onlyCalls("Let me show you the browser.\nbrowser_show()"))
    }

    @Test fun streamingHidesAHalfWrittenCall() {
        assertEquals("", DuoText.strip("browser_sh", final = false))
        assertEquals("", DuoText.strip("browser_show(", final = false))
        assertEquals("Okay.", DuoText.strip("Okay.\nbrowser_show(", final = false))
        assertEquals("Hello there", DuoText.strip("Hello there", final = false))
    }

    @Test fun decide() {
        // @all chat: no tools run there. Only syntax -> ask once for plain words, then a plain fallback.
        assertEquals(DuoText.Decision.Retry, DuoText.decide("browser_show()", emptySet(), retried = false))
        val fb = DuoText.decide("browser_show()", emptySet(), retried = true)
        assertTrue(fb is DuoText.Decision.Fallback)
        val t = (fb as DuoText.Decision.Fallback).text
        assertFalse(t.contains("(")); assertFalse(t.contains("_")); assertTrue(t.contains("@blaze"))
        // Words around a call: show just the words.
        assertEquals(DuoText.Decision.Show("I'll pull it up."), DuoText.decide("I'll pull it up.\nbrowser_show()", emptySet(), retried = false))
        // Meeting room: a research tool written as text becomes a real call.
        val run = DuoText.decide("web_search(query=\"best phones 2026\")", room, retried = false)
        assertTrue(run is DuoText.Decision.Run)
        assertEquals("web_search", (run as DuoText.Decision.Run).calls[0].name)
        assertEquals("best phones 2026", run.calls[0].args.getString("query"))
        // An unknown tool in the room is not run.
        assertEquals(DuoText.Decision.Retry, DuoText.decide("browser_show()", room, retried = false))
        // Plain text passes straight through.
        assertEquals(DuoText.Decision.Show("All good here."), DuoText.decide("All good here.", room, retried = false))
    }

    @Test fun storedLeakShowsAPlainLine() {
        assertEquals(ToolLeak.STORED_EMPTY, ToolLeak.bubbleText("browser_show()"))
        assertEquals("Hi there", ToolLeak.bubbleText("Hi there"))
    }

    @Test fun parallelPromptsAndLines() {
        val s = Duo.parallelSystem(Duo.Who.HALO, "Sam")
        assertTrue(s.contains("AT THE SAME TIME")); assertTrue(s.contains("HONESTY"))
        assertTrue(Duo.lateLine(Duo.Who.HALO).contains("@halo"))
        assertTrue(Duo.PARALLEL_LIMIT_MS in 20_000L..60_000L)
        assertTrue(Duo.DUO_RULES.contains("no tools run in this chat"))
    }
}
