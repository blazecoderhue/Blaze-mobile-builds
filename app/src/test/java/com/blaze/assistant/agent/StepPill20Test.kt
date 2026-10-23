package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.45.20 step pills (STEPS_DELIVERY_CONTRACT §2, §3). Strict 5/5: each scenario 5 times on a fresh machine. */
class StepPill20Test {
    private fun five(f: () -> Unit) = repeat(5) { f() }
    private fun step(t: String, kind: String = "step") = JSONObject().put("type", "step").put("kind", kind).put("text", t)
    private fun ev(type: String, vararg kv: Pair<String, String>) = JSONObject().put("type", type).apply { kv.forEach { put(it.first, it.second) } }

    @Test fun clean_plainTextOnly_noNamesToolsJsonOrUrls() = five {
        assertEquals("Opening the booking page", StepPill.clean("Opening the booking page."))
        assertEquals("Reading your calendar", StepPill.clean("  Reading   your calendar  "))
        val owner = "Lack" + "son"
        val bad = listOf("Asking ${"gr" + "oq"} for an answer", "Calling web_search", "{\"tool\":\"x\"}", "Opening https://a.b/c?key=1",
            "Routing to the fast model", "Thanks $owner", "Running browser_open(url)", "Reading www.example.com", "Using <b>bold</b>",
            "Step [1/3]", "Trying llama-3.3-70b", "Writing `code`", "")
        for (b in bad) assertNull(b, StepPill.clean(b))
        val long = StepPill.clean("Refactoring the car body into its own shape and then checking every wheel twice over")!!
        assertTrue(long, long.length <= StepPill.MAX && long.endsWith("\u2026"))
        assertFalse(long.endsWith(".\u2026"))
        // Specialists are always general, never which one.
        assertEquals(StepPill.SPECIALIST_IN, StepPill.clean("Bringing in the travel-booking specialist", "specialist"))
        assertEquals(StepPill.SPECIALIST_UPDATE, StepPill.clean("Updating the flights playbook", "specialist"))
    }

    @Test fun noPill_whenTheReplyLandsWithin2s() = five {
        val m = StepPill.Machine()
        m.begin(10_000)
        m.onEvent(step("Looking that up"))
        assertNull(m.visible(10_500))
        m.onEvent(ev("delta", "text" to "Paris"))
        m.onEvent(ev("done"))
        assertNull("never shown at 1.9 s or after", m.visible(11_900))
        assertNull(m.visible(13_000))
    }

    @Test fun show_after2s_replace_thenHideOnDelta() = five {
        val m = StepPill.Machine()
        m.begin(0)
        m.onEvent(step("Opening the booking page"))
        assertNull(m.visible(1_999))
        assertEquals("Opening the booking page", m.visible(2_000))
        assertEquals(2_000L, m.dueAt())
        m.onEvent(step("Picking a table for two"))
        assertEquals("one pill, replaced", "Picking a table for two", m.visible(2_500))
        m.onEvent(step("Calling web_search"))   // unclean: dropped, the previous line stays
        assertEquals("Picking a table for two", m.visible(2_600))
        m.onEvent(ev("delta", "text" to "Booked"))
        assertNull(m.visible(3_000))
        // More work after some text: the next step shows again.
        m.onEvent(step("Checking the confirmation"))
        assertEquals("Checking the confirmation", m.visible(3_100))
    }

    @Test fun hide_onDoneFailedCancelledAndNeedsDevice() = five {
        for (end in listOf(ev("done"), ev("failed"), ev("cancelled"), ev("status", "status" to "done"), ev("status", "status" to "failed"), ev("status", "status" to "cancelled"))) {
            val m = StepPill.Machine(); m.begin(0); m.onEvent(step("Working on it now"))
            assertTrue(m.visible(5_000) != null)
            m.onEvent(end)
            assertNull(end.toString(), m.visible(5_001))
            m.onEvent(step("Late step"))
            assertNull("closed: a late step never brings it back", m.visible(6_000))
        }
        val m = StepPill.Machine(); m.begin(0); m.onEvent(step("Opening the camera"))
        m.onEvent(ev("status", "status" to "needs_device"))
        assertNull("the phone's own tool UI takes over", m.visible(4_000))
        m.onEvent(ev("status", "status" to "running"))
        m.onEvent(step("Reading the photo"))
        assertEquals("Reading the photo", m.visible(9_000))
    }

    @Test fun specialistEvents_areGeneral_andUnknownEventsIgnored() = five {
        val m = StepPill.Machine(); m.begin(0)
        m.onEvent(ev("specialty_loaded", "text" to "Bringing in a specialist", "_name" to "flights"))
        assertEquals(StepPill.SPECIALIST_IN, m.visible(3_000))
        assertEquals("specialist", m.kind)
        m.onEvent(ev("specialty_updated", "_change" to "always aisle"))
        assertEquals(StepPill.SPECIALIST_UPDATE, m.visible(3_000))
        assertFalse(m.onEvent(ev("brand_new_event", "text" to "x")))
        assertEquals(StepPill.SPECIALIST_UPDATE, m.visible(3_000))
        m.onEvent(step("Bringing in a specialist", "specialist"))
        assertEquals(StepPill.SPECIALIST_IN, m.visible(3_000))
    }

    @Test fun pillsFlow_publishesAtTheDueTime_forThePip() = five {
        var now = 0L
        StepPills.clock = { now }
        StepPills.begin("main", 0)
        StepPills.onEvent("main", step("Opening the booking page"))
        assertNull("not before 2 s", StepPills.current.value)
        now = 2_100
        // The scheduled publish runs on its own; wait for it (bounded).
        val until = System.currentTimeMillis() + 5_000
        while (StepPills.current.value == null && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals("Opening the booking page", StepPills.current.value?.text)
        StepPills.onEvent("other", step("Not this chat"))
        assertEquals("Opening the booking page", StepPills.current.value?.text)
        StepPills.end("main")
        assertNull(StepPills.current.value)
        StepPills.clock = { System.currentTimeMillis() }
    }

    @Test fun pipHook_isExposedAsStateFlow() = five {
        val f = listOf(File("src/main/java/com/blaze/assistant/agent/StepPill.kt"), File("app/src/main/java/com/blaze/assistant/agent/StepPill.kt")).first { it.exists() }.readText()
        assertTrue(f.contains("val current: StateFlow<Pill?>"))
    }
}
