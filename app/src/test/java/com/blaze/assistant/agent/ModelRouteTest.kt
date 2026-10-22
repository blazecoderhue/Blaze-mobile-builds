package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRouteTest {
    @Test fun actionTurnsAreHeavy() {
        assertTrue(ModelRoute.heavy("start the linux desktop and open dillo"))
        assertTrue(ModelRoute.heavy("open WhatsApp"))
        assertTrue(ModelRoute.heavy("set an alarm for 6"))
        assertTrue(ModelRoute.heavy("fix the bug in my script", setOf("terminal")))
        assertTrue(ModelRoute.heavy("hmm", toolsSoFar = 2))
        assertTrue(ModelRoute.heavy("whatever", bulk = true))
    }

    @Test fun plainChatIsNot() {
        assertFalse(ModelRoute.heavy("hi"))
        assertFalse(ModelRoute.heavy("what is the capital of Zambia?"))
        assertFalse(ModelRoute.heavy("thanks!"))
        assertFalse(ModelRoute.heavy("how many moons does Mars have"))
        assertFalse(ModelRoute.heavy(""))
    }

    @Test fun heavyToolTurnsGoToTheAgentLineupEvenWhenLongOrCode() {
        assertEquals(ModelRoute.Pick("agent", true), ModelRoute.task("long", heavy = true, hasTools = true, chars = 60_000))
        assertEquals(ModelRoute.Pick("agent", true), ModelRoute.task("code", heavy = true, hasTools = true))
        assertEquals(ModelRoute.Pick("agent", true), ModelRoute.task("agent", heavy = true, hasTools = true))
    }

    @Test fun visionChatAndHugeRequestsKeepTheirLane() {
        assertEquals("vision", ModelRoute.task("vision", heavy = true, hasTools = true).task)
        assertEquals(ModelRoute.Pick("quick", false), ModelRoute.task("quick", heavy = false, hasTools = false))
        assertEquals(ModelRoute.Pick("chat", false), ModelRoute.task("chat", heavy = true, hasTools = false))
        assertEquals(ModelRoute.Pick("long", false), ModelRoute.task("long", heavy = true, hasTools = true, chars = ModelRoute.AGENT_MAX_CHARS + 1))
        assertEquals(ModelRoute.Pick("agent", false), ModelRoute.task("agent", heavy = false, hasTools = true))
    }

    @Test fun weakRoutesGoLastOnlyOnStrongTurnsAndFailuresComeFirst() {
        assertEquals(emptyList<String>(), ModelRoute.avoid(emptyList(), false))
        assertEquals(listOf("groq/x"), ModelRoute.avoid(listOf("groq/x"), false))
        val a = ModelRoute.avoid(listOf("gemini/gemini-flash-latest"), true)
        assertEquals("gemini/gemini-flash-latest", a.first())
        assertTrue(a.containsAll(ModelRoute.WEAK_FOR_TOOLS))
        assertTrue(ModelRoute.avoid((1..10).map { "p/m$it" }, true).size <= ModelRoute.AVOID_MAX)
        // The strong lineup itself is never avoided.
        assertFalse(ModelRoute.WEAK_FOR_TOOLS.any { it.contains("gpt-oss-120b") || it.contains("kimi") || it == "gemini/gemini-flash-latest" })
    }
}
