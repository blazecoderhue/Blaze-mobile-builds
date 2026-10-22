package com.blaze.assistant.agent

import com.blaze.assistant.agent.ToolVerify.Status
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.45.13 round 2 (strict rerun rpS on gateway 0.8.15):
 *  (1) settings-value #5: open_settings_page{display} twice in one step -> no follow-up named. Identical calls count once.
 *  (2) the same run: a bare tap{element:2} (no want, no read-back) let "Dark mode is on." stand. It can't prove it now.
 *  (3) terminal-ls 3/5: codestral wrote ```sh ls``` as text. "run ls in the terminal" names run_shell.
 */
class PhoneRound2Test {
    private fun call(id: String, name: String, args: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", args))

    private fun turn(user: String, vararg steps: List<Pair<String, String>>): JSONArray = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", "s"))
        put(JSONObject().put("role", "user").put("content", user))
        var k = 0
        for (st in steps) {
            val calls = JSONArray(); st.forEach { (n, a) -> calls.put(call("c${k++}", n, a)) }
            put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls))
            for (i in 0 until calls.length()) put(JSONObject().put("role", "tool").put("tool_call_id", calls.getJSONObject(i).getString("id")).put("content", "ok"))
        }
    }

    private val names = listOf("open_settings_page", "tap", "read_screen", "processes", "process", "run_shell", "open_view", "device_setting")

    @Test fun duplicateCallsStillGetTheFollowUp() {
        val ask = "open display settings and turn dark mode on"
        val dup = turn(ask, listOf("open_settings_page" to "{\"page\":\"display\"}", "open_settings_page" to "{\"page\":\"display\"}"))
        assertEquals("tap", ToolTruth.choiceName(dup, names))
        // same arguments in another key order / spacing are identical too
        val reordered = turn(ask, listOf("open_settings_page" to "{\"page\":\"display\",\"app\":\"x\"}", "open_settings_page" to "{ \"app\": \"x\", \"page\": \"display\" }"))
        assertEquals("tap", ToolTruth.choiceName(reordered, names))
        // two DIFFERENT calls in the step: not "exactly the key tool"
        val two = turn(ask, listOf("open_settings_page" to "{\"page\":\"display\"}", "open_settings_page" to "{\"page\":\"sound\"}"))
        assertNull(ToolTruth.choiceName(two, names))
        val mixed = turn(ask, listOf("open_settings_page" to "{\"page\":\"display\"}", "read_screen" to "{}"))
        assertNull(ToolTruth.choiceName(mixed, names))
        // named once: after a tap already ran, auto again
        val after = turn(ask, listOf("open_settings_page" to "{\"page\":\"display\"}"), listOf("tap" to "{\"text\":\"Dark mode\",\"want\":\"on\"}"))
        assertNull(ToolTruth.choiceName(after, names))
        val alarm = turn("turn off the QA wake alarm", listOf("processes" to "{}", "processes" to ""))
        assertEquals("process", ToolTruth.choiceName(alarm, names))
        assertEquals(listOf("a", "b"), ToolTruth.distinctCalls(JSONArray().put(call("1", "a", "{}")).put(call("2", "a", "")).put(call("3", "b", "{}"))))
    }

    @Test fun bareTapCantProveDarkModeOn() {
        val ask = "open display settings and turn dark mode on"
        val bareTap = ToolVerify.classify("tap", JSONObject().put("element", 2), "Tapped \"Dark mode\".")
        val done = listOf(ClaimCheck.Done("open_settings_page", Status.VERIFIED, "Settings is in front"), ClaimCheck.Done("tap", bareTap.status, bareTap.why))
        for (reply in listOf("Dark mode is on.", "I turned dark mode on.", "Done, dark mode is now on.")) {
            val o = ClaimCheck.review(reply, done, ask)
            assertTrue("$reply -> $o", o is ClaimCheck.Outcome.Rewrite)
            val t = (o as ClaimCheck.Outcome.Rewrite).text
            assertTrue(t, t.contains("couldn't confirm") && t.contains("wasn't asked to turn it on") && t.contains("read the switch back as on"))
            assertFalse(t, Regex("(?i)dark mode is (now )?on\\.").containsMatchIn(t))
        }
        // a tap asked for on and read back on still proves it
        val good = ToolVerify.classify("tap", JSONObject().put("text", "Dark mode").put("want", "on"), "Tapped \"Dark mode\". " + ActCheck.checked("the switch \u201cDark mode\u201d now reads on"))
        val ok = listOf(ClaimCheck.Done("open_settings_page", Status.VERIFIED, "Settings is in front"), ClaimCheck.Done("tap", good.status, good.why))
        assertEquals(ClaimCheck.Outcome.Ok, ClaimCheck.review("Dark mode is on.", ok, ask))
        assertEquals(ClaimCheck.Outcome.Ok, ClaimCheck.review("I turned dark mode on.", ok, ask))
        // read back off but the reply says on: not proof, and the line says what was read
        val wrong = listOf(ClaimCheck.Done("tap", Status.VERIFIED, "the switch \u201cDark mode\u201d now reads off"))
        val w = ClaimCheck.review("Dark mode is on.", wrong, ask)
        assertTrue(w is ClaimCheck.Outcome.Rewrite && (w as ClaimCheck.Outcome.Rewrite).text.contains("read back off, not on"))
        // honest replies and questions stay as they are
        assertEquals(ClaimCheck.Outcome.Ok, ClaimCheck.review("I tapped Dark mode but couldn't confirm it's on.", done, ask))
        assertTrue(ClaimCheck.stateClaims("Wi-Fi is on.", "is wifi on?").isEmpty())
        assertEquals(listOf("Dark mode is on."), ClaimCheck.stateClaims("Dark mode is on.", ask))
        assertEquals(true, ClaimCheck.switchRead("the switch \u201cDark mode\u201d now reads on"))
        assertNull(ClaimCheck.switchRead(""))
    }

    @Test fun shellAsksNameRunShell() {
        val tools = listOf("run_shell", "open_view", "desktop_task", "web_search")
        for (u in listOf("run ls in the terminal", "@halo run ls in the terminal", "run ls -la in the terminal", "please run git status in the shell",
                "execute python3 hello.py in the terminal", "run `df -h`", "run ls", "ls", "ls -la ~/projects", "pwd", "git status", "can you run whoami in the terminal?"))
            assertEquals(u, "run_shell", ToolTruth.choiceFor(u, emptyList(), tools))
        for (u in listOf("open the terminal", "open Terminal", "hi Blaze", "find my phone", "run to the shop", "what's the weather", "list my files", "cat videos please"))
            assertTrue(u, ToolTruth.choiceFor(u, emptyList(), tools) != "run_shell")
        assertEquals("open_view", ToolTruth.choiceFor("open the terminal", emptyList(), tools))
        // Simple mode (no run_shell in the list): never named
        assertNull(ToolTruth.choiceFor("run ls in the terminal", emptyList(), listOf("open_view")))
        // the request's tool_choice is the named function
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "run ls in the terminal"))
        val sent = JSONArray().apply { tools.forEach { put(JSONObject().put("type", "function").put("function", JSONObject().put("name", it))) } }
        assertEquals("run_shell", (ToolTruth.choice(msgs, sent) as JSONObject).getJSONObject("function").getString("name"))
    }
}
