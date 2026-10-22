package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.30.4 fast phone actions. The test phone trace ("stop GitHub opening links in the GitHub app": 18 steps, 4 min 25 s):
 * irrelevant skills loaded first, every old screen readout re-sent on every step, macros restarting from scratch.
 */
class FastActionsTest {

    @Test fun plainDeviceActions() {
        for (t in listOf("stop GitHub opening links in the GitHub app", "Stop github from opening links", "turn off wifi",
            "disable notifications for WhatsApp", "please turn on bluetooth", "can you allow Chrome to use location",
            "set screen timeout to 2 minutes", "don't let YouTube open links", "turn on dark mode", "open battery settings"))
            assertTrue(t, FastActions.plainDevice(t))
        for (t in listOf("build me an android app for my github repo", "write an email to my boss about the wifi", "why does my battery drain",
            "how do I stop github opening links", "research the best bluetooth speakers", "make flashcards on notifications in android",
            "open whatsapp", "hi", "", "remind me every morning to turn off wifi"))
            assertFalse(t, FastActions.plainDevice(t))
    }

    // ---------------- screen readouts: only the newest goes to the model ----------------

    /** A Samsung Settings readout like readScreen writes it ([n] role "text"). */
    private fun screen(pkgLabel: String, rows: List<String>) = "$pkgLabel (com.android.settings). Elements, use the [number]:\n" +
        rows.mapIndexed { i, r -> "[$i] item \"$r\"" }.joinToString("\n") + "\n"
    private val settingsRows = listOf("Search", "Samsung account", "Connections", "Connected devices", "Modes and Routines", "Sounds and vibration",
        "Notifications", "Display", "Wallpaper", "Themes", "Home screen", "Lock screen", "Biometrics and security", "Privacy", "Location",
        "Safety and emergency", "Accounts and backup", "Google", "Advanced features", "Digital Wellbeing and parental controls",
        "Battery and device care", "Apps", "General management", "Accessibility", "Software update", "About phone").map { "$it, settings row with a summary line under it" }

    @Test fun screenMarksAreRecognised() {
        val full = screen("Settings", settingsRows)
        assertTrue(FastActions.isScreen(full))
        assertTrue(FastActions.isScreen("Tapped [3].\n\nScreen now:\n$full"))
        assertTrue(FastActions.isScreen("Pressed back.\n\nScreen now:\nSame screen in Settings. Changed or new:\n[4] item \"x\"\nOther 20 elements unchanged, same numbers."))
        assertFalse(FastActions.isScreen("Opened GitHub."))
        assertFalse("the dead pre-v0.30.4 mark", full.contains("Foreground app:"))
        assertEquals("Tapped [3].\n[older screen omitted]", FastActions.stripScreen("Tapped [3].\n\nScreen now:\n$full"))
        assertEquals("[older screen omitted]", FastActions.stripScreen(full))
        assertTrue(FastActions.stripScreen("Stopped at step 2 (Tap “Apps”): couldn't find it.\n\n$full").startsWith("Stopped at step 2"))
    }

    @Test fun diffsKeepTheFullReadoutTheyReferTo() {
        val full = "Tapped.\n\nScreen now:\n" + screen("Settings", settingsRows)
        val diff = "Scrolled down.\n\nScreen now:\nSame screen in Settings. Changed or new:\n[30] item \"Apps\"\nOther 25 elements unchanged, same numbers."
        val c = listOf(null, full, null, full, diff, null, diff)
        assertEquals(setOf(6, 4, 3), FastActions.screensToKeep(c))
        assertEquals(setOf(3), FastActions.screensToKeep(listOf(null, full, null, full)))
        assertTrue(FastActions.screensToKeep(listOf(null, "Opened GitHub.")).isEmpty())
    }

    /** Replays the shape of the test phone trace and measures what each model call carried, before and after. */
    @Test fun lowEndTracePromptShrinks() {
        val h = JSONArray()
        fun user(t: String) = h.put(JSONObject().put("role", "user").put("content", t))
        fun call(n: String, a: String) = h.put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "c${h.length()}").put("type", "function")
                .put("function", JSONObject().put("name", n).put("arguments", a)))))
        fun tool(n: String, c: String) = h.put(JSONObject().put("role", "tool").put("tool_call_id", "c${h.length()}").put("name", n).put("content", c))
        user("stop GitHub opening links in the GitHub app")
        call("list_apps", "{}"); tool("list_apps", "140 apps:\n" + (1..140).joinToString("\n") { "App number $it (com.example.app$it)" })
        call("run_plan", "{}"); tool("run_plan", "Stopped at step 2 (Tap “Apps”): couldn't find it. Steps 1-1 worked.\n\n" + screen("Settings", settingsRows.take(14)))
        val singles = listOf("scroll", "scroll", "tap", "read_screen", "tap", "read_screen", "tap", "scroll", "scroll", "tap", "read_screen", "press_button")
        singles.forEachIndexed { i, n ->
            call(n, "{}")
            val rows = settingsRows.drop((i * 3) % 12).take(14)
            tool(n, if (n == "read_screen") screen("Settings", rows) else "Done $n.\n\nScreen now:\n" + screen("Settings", rows))
        }
        // What each model call sent before v0.30.4: every readout (the prune never matched).
        var before = 0L
        var after = 0L
        val raw = JSONArray()
        val live = JSONArray()
        for (i in 0 until h.length()) {
            raw.put(JSONObject(h.getJSONObject(i).toString()))
            live.put(JSONObject(h.getJSONObject(i).toString()))
            if (live.getJSONObject(i).optString("role") != "tool") continue
            before += raw.toString().length
            FastActions.pruneScreens(live)
            after += live.toString().length
        }
        val lastBefore = h.toString().length
        val lastAfter = live.toString().length
        println("low-end phone trace, ${singles.size + 2} tool results: history chars sent over the task ${before} → ${after} " +
            "(${100 - after * 100 / before}% less); the last call carried $lastBefore → $lastAfter chars (≈${lastBefore / 4} → ≈${lastAfter / 4} tokens)")
        assertTrue("$after vs $before", after * 10 < before * 7)
        assertTrue("$lastAfter vs $lastBefore", lastAfter * 2 < lastBefore)
        // Exactly one full readout is left, and the step lines stay.
        assertEquals(1, (0 until live.length()).count { FastActions.isScreen(live.getJSONObject(it).optString("content")) })
        assertTrue(live.toString().contains("Done press_button."))
    }

    // ---------------- batching ----------------

    @Test fun screenIsReadOnceAfterTheLastActionOfABatch() {
        val acts = setOf("tap", "type_text", "scroll", "press_button", "open_app", "do_actions")
        assertEquals(setOf(2), FastActions.screenReadAt(listOf("open_app", "tap", "tap"), acts))
        assertEquals(setOf(1), FastActions.screenReadAt(listOf("open_settings", "tap", "web_search"), acts))
        assertEquals(setOf(0), FastActions.screenReadAt(listOf("press_button"), acts))
        assertTrue(FastActions.screenReadAt(listOf("web_search", "fetch_page"), acts).isEmpty())
    }

    // ---------------- run_plan resume ----------------

    private fun step(d: String, target: String = "", app: String = "") = JSONObject().put("do", d).put("target", target).put("app", app)

    @Test fun aFailedScriptResumesInsteadOfRestarting() {
        // Macro 2 of the trace: Open Settings ✓, Tap "Choose default apps" ✓, Tap "Opening links" ✗.
        val plan = listOf(step("open_app", app = "Settings"), step("tap", "Choose default apps"), step("tap", "Opening links"), step("tap", "GitHub"), step("tap", "Don't allow"))
        val keys = plan.map { FastActions.key(it) }
        val last = FastActions.PlanRun(keys, done = 2, failed = true, pkg = "com.android.settings")
        // The same script again, still in Settings: the two done steps are skipped.
        assertEquals(2, FastActions.skipDone(keys, last, "com.android.settings"))
        // A fixed step 3 onwards is sent as is.
        val fixed = listOf(step("tap", "Open by default"), step("tap", "GitHub"))
        assertEquals(0, FastActions.skipDone(fixed.map { FastActions.key(it) }, last, "com.android.settings"))
        // Another app on screen, a run that didn't fail, or a script that differs early: nothing skipped.
        assertEquals(0, FastActions.skipDone(keys, last, "com.github.android"))
        assertEquals(0, FastActions.skipDone(keys, last.copy(failed = false), "com.android.settings"))
        assertEquals(0, FastActions.skipDone(listOf(FastActions.key(step("open_app", app = "Chrome"))) + keys.drop(1), last, "com.android.settings"))
        // Never skips the whole script.
        assertEquals(0, FastActions.skipDone(keys.take(2), last.copy(done = 2), "com.android.settings"))
        assertEquals(FastActions.key(step("tap", "Opening   Links!")), FastActions.key(step("tap", "opening links")))
        assertTrue(FastActions.resumeHint(3, 5).contains("fix only step 3"))
    }
}
