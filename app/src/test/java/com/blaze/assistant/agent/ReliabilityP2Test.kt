package com.blaze.assistant.agent

import com.blaze.assistant.agent.ToolVerify.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.0 P2 agent reliability: the pure parts, one block per suggestion. */
class ReliabilityP2Test {
    private fun a(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { put(it.first, it.second) } }
    private val noWait: suspend (Long, suspend () -> Boolean) -> Boolean = { _, ok -> ok() }

    // ---- #4 strong success checks ----
    private class Setting(val on: Boolean?) : ToolVerify.Probes { override suspend fun settingOn(setting: String) = on }

    @Test fun sendNeedsSentConfirmation() {
        assertEquals(Status.UNVERIFIED, ToolVerify.classify("message_contact", a(), "Opened Mum's chat in WhatsApp.").status)
        assertEquals(Status.UNVERIFIED, ToolVerify.classify("send_sms", a(), "Message queued.").status)
        assertEquals(Status.VERIFIED, ToolVerify.classify("send_sms", a(), "Sent to Mum (0977…).").status)
        // A one-shot that wasn't repeated is judged by its earlier result.
        assertEquals(Status.VERIFIED, ToolVerify.classify("message_contact", a(), OneShot.NOTE + "Sent to Mum on WhatsApp: hi").status)
    }

    @Test fun settingIsReadBack() = runBlocking {
        val args = a("setting" to "wifi", "value" to "on")
        assertEquals(Status.VERIFIED, ToolVerify.check("device_setting", args, "Wi-Fi is now on.", Setting(true), 0, noWait).status)
        // The tool said so, the system says otherwise: not verified.
        assertEquals(Status.UNVERIFIED, ToolVerify.check("device_setting", args, "Wi-Fi is now on.", Setting(false), 0, noWait).status)
        // Can't read it: the tool's own words decide (classify).
        assertEquals(Status.VERIFIED, ToolVerify.check("device_setting", args, "Wi-Fi is now on.", Setting(null), 0, noWait).status)
        assertEquals(Status.VERIFIED, ToolVerify.check("device_setting", args, "Opened Wi-Fi settings.", Setting(true), 0, noWait).status)
        assertEquals(Status.FAILED, ToolVerify.check("device_setting", args, "The user declined. The setting was NOT changed.", Setting(true), 0, noWait).status)
    }

    @Test fun weakToolsNeverProveTheClaim() {
        val setting = ActionProof.ACTIONS.first { it.id == "setting" }
        assertTrue("open_settings_page" in setting.weak && "open_settings_page" !in setting.strong)
        // Only the settings page opened (and that check passed): "I turned on Wi-Fi" is still rewritten.
        val o = ClaimCheck.review("Done, I turned on Wi-Fi.", listOf(ClaimCheck.Done("open_settings_page", Status.VERIFIED, "Settings is in front")), "turn on wifi")
        assertTrue(o is ClaimCheck.Outcome.Rewrite)
        assertTrue((o as ClaimCheck.Outcome.Rewrite).text.contains("couldn't confirm", true))
        assertEquals(setting, ActionProof.unbacked("I turned on Wi-Fi.", listOf("open_settings_page"), "turn on wifi"))
        assertNull(ActionProof.unbacked("I turned on Wi-Fi.", listOf("device_setting"), "turn on wifi"))
    }

    @Test fun intentAndOtherLanguages() {
        // "let mum know" is a send now.
        assertNotNull(ActionProof.unbacked("Sent! She knows you're late.", emptyList(), "let mum know I'm late"))
        // French and Swahili / Nyanja claims are checked.
        assertEquals("send", ActionProof.unbacked("C'est fait, j'ai envoyé le message à Maman.", emptyList(), "envoie un message à maman", listOf("message_contact"))?.id)
        assertEquals("send", ActionProof.unbacked("Nimetuma ujumbe kwa mama.", emptyList(), "mwambie mama nitachelewa", listOf("message_contact"))?.id)
        assertEquals("send", ActionProof.unbacked("Ndatumiza uthenga kwa amayi.", emptyList(), "uzani amayi", listOf("message_contact"))?.id)
        // Negated: no claim.
        assertNull(ActionProof.unbacked("Sijatuma ujumbe bado.", emptyList(), "mwambie mama", listOf("message_contact")))
        // Backed by a strong tool: fine.
        assertNull(ActionProof.unbacked("Nimetuma ujumbe kwa mama.", listOf("message_contact"), "mwambie mama", listOf("message_contact")))
    }

    // ---- #13 / #16 prompt profile ----
    @Test fun simpleProfileHasNoTerminalContent() {
        val p = PromptProfile.plan(simple = true, userText = "write a python script to rename my files")
        assertTrue(p.simple); assertFalse(p.terminal); assertFalse(p.make)
        assertFalse("blaze-coding-workflow" in p.core); assertFalse("blaze-specialists" in p.core)
        for (bad in listOf("/root", "run_shell", "run_code", "terminal", "python", "git ")) {
            assertFalse(bad, PromptProfile.MAKE_SIMPLE.contains(bad, true))
        }
        assertTrue(PromptProfile.SIMPLE_RULE.contains("everyday", true))
        // Simple: terminal / connectors groups only on a strong match.
        assertFalse(PromptProfile.simpleGroupOk("terminal", "make me a birthday card with a csv of names"))
        assertTrue(PromptProfile.simpleGroupOk("terminal", "open the terminal"))
        assertTrue(PromptProfile.simpleGroupOk("messages", "anything"))
    }

    @Test fun advancedPromptOnlyCarriesWhatTheTurnNeeds() {
        val plain = PromptProfile.plan(simple = false, userText = "what's the weather in Ndola")
        assertFalse(plain.terminal); assertFalse(plain.views); assertFalse("blaze-coding-workflow" in plain.core)
        assertTrue("blaze-specialists" in plain.core)
        val code = PromptProfile.plan(simple = false, userText = "fix the bug in my python script")
        assertTrue(code.terminal); assertTrue("blaze-coding-workflow" in code.core)
        assertTrue(PromptProfile.plan(false, "hi", setOf("terminal")).terminal)
        assertTrue(PromptProfile.plan(false, "hi", viewRouted = true).views)
        val short = PromptProfile.viewsShort(listOf("Notes", "Design"))
        assertTrue(short.length < 400 && short.contains("open_view"))
        assertTrue(PromptProfile.MAKE_SHORT.length + PromptProfile.TERMINAL_SHORT.length < 900)
    }

    // ---- #20 language fallback ----
    @Test fun nonEnglishGetsCoreActionGroups() {
        assertEquals(ToolRouter.CORE_ACTION, ToolRouter.fallback("Shimya ilaiti", emptySet()))
        assertEquals(ToolRouter.CORE_ACTION, ToolRouter.fallback("Allume la lampe torche s'il te plaît", emptySet()))
        assertTrue(ToolRouter.fallback("what is the capital of France", emptySet()).isEmpty())
        assertTrue(ToolRouter.fallback("Shimya ilaiti", setOf("device")).isEmpty())
        assertTrue(ToolRouter.fallback("ok", emptySet()).isEmpty())
    }

    // ---- #17 one model for the turn ----
    @Test fun turnModelPinsTheFirstRoute() {
        val t = TurnModel("t-main-1")
        assertEquals(mapOf(TurnModel.TURN_HEADER to "t-main-1"), t.headers())
        t.served(""); t.served("groq/openai/gpt-oss-120b")
        t.served("groq/openai/gpt-oss-120b"); t.served("mistral/mistral-medium")
        assertEquals("groq/openai/gpt-oss-120b", t.pin)
        assertEquals("groq/openai/gpt-oss-120b", t.headers()[TurnModel.PREFER_HEADER])
        assertEquals(1, t.switches)
        assertEquals("route changed 1×", t.line())
        assertEquals("ab", TurnModel.safe("a\nb"))
    }

    // ---- #18 plans only from turns that worked ----
    private fun e(tool: String, st: Status) = Receipt.Entry(tool, "", st, "", "")

    @Test fun plansNeedVerifiedActions() {
        assertTrue(PlanGate.keep(listOf(e("web_search", Status.VERIFIED), e("run_plan", Status.VERIFIED)), false, false))
        assertFalse(PlanGate.keep(listOf(e("run_plan", Status.VERIFIED), e("message_contact", Status.UNVERIFIED)), false, false))
        assertFalse(PlanGate.keep(listOf(e("run_plan", Status.VERIFIED)), nudged = true, rewrote = false))
        assertFalse(PlanGate.keep(listOf(e("run_plan", Status.VERIFIED)), nudged = false, rewrote = true))
        assertFalse(PlanGate.keep(listOf(e("web_search", Status.VERIFIED)), false, false))
        assertFalse(PlanGate.learn(listOf(e("open_app", Status.FAILED)), false))
        for (t in listOf("no, that's wrong", "That didn't work", "wrong chat", "undo", "Hapana", "awe", "ayi", "non, pas ça", "\uD83D\uDC4E"))
            assertTrue(t, PlanGate.correction(t))
        for (t in listOf("now open YouTube", "nice, thanks", "note this down", "notify me later")) assertFalse(t, PlanGate.correction(t))
    }

    // ---- #19 re-prompt only when it helps ----
    @Test fun nudgePolicy() {
        val send = ActionProof.ACTIONS.first { it.id == "send" }
        assertEquals(NudgePolicy.Way.LOAD_AND_ASK, NudgePolicy.proof(send, listOf("web_search"), emptyList()))
        assertEquals(NudgePolicy.Way.ASK, NudgePolicy.proof(send, listOf("message_contact"), listOf("find_tool")))
        assertEquals(NudgePolicy.Way.HONEST, NudgePolicy.proof(send, listOf("message_contact"), listOf("find_contact")))
        assertEquals(setOf("messages"), ToolRouter.groupsFor(listOf("send_sms")))
    }

    // ---- #36 receipts / What I did ----
    @Test fun receiptsLineAndLog() {
        val log = listOf(
            Receipt.entry("web_search", a("query" to "weather"), "1. 28C", ToolVerify.Verdict(Status.VERIFIED)),
            Receipt.entry("message_contact", a("contact" to "Mum", "text" to "late"), "Sent to Mum on WhatsApp: \u201clate\u201d", ToolVerify.Verdict(Status.VERIFIED)),
            Receipt.entry("set_alarm", a("hour" to 7, "minute" to 0), "Alarm set", ToolVerify.Verdict(Status.VERIFIED, "the system lists the next alarm at 07:00")),
            Receipt.entry("device_setting", a("setting" to "wifi", "value" to "on"), "Opened Wi-Fi settings.", ToolVerify.Verdict(Status.UNVERIFIED, "Wi-Fi still reads off")),
        )
        val line = Receipt.line(log)
        assertTrue(line, line.startsWith("\u26a0 Couldn't confirm: Wi-Fi on"))
        assertTrue(line.contains("\u2713 Sent to Mum on WhatsApp")); assertTrue(line.contains("\u2713 Alarm 07:00"))
        assertFalse(line.contains("web search", true))
        // Every chip survives the "technical details hidden" filter.
        assertEquals(line, ReplyChips.visible(line, false))
        // Stored with the message and read back.
        val back = Receipt.fromJson(Receipt.toJson(log))
        assertEquals(4, back.size); assertEquals("Wi-Fi still reads off", back[3].evidence); assertEquals(Status.UNVERIFIED, back[3].status)
        assertTrue(Receipt.describe(back).contains("Checked: the system lists the next alarm at 07:00"))
        // Secrets never go in the log.
        assertFalse(Receipt.argsSummary(a("password" to "hunter2", "user" to "me")).contains("hunter2"))
        assertEquals("", Receipt.line(listOf(log[0])))
    }

    // ---- recovery ----
    @Test fun oneShotNeverRepeats() {
        val o = OneShot()
        val args = a("contact" to "Mum", "text" to "I'm late")
        assertNull(o.earlier("message_contact", args))
        o.record("message_contact", args, "Sent to Mum on WhatsApp.")
        assertTrue(o.earlier("message_contact", a("text" to "I'm  late", "contact" to "mum"))!!.contains("Sent to Mum"))
        assertNull(o.earlier("message_contact", a("contact" to "Mum", "text" to "on my way")))
        // A clear failure isn't recorded: a real retry may run.
        o.record("send_sms", args, "Couldn't send: no signal")
        assertNull(o.earlier("send_sms", args))
        // Safe tools are never blocked.
        o.record("web_search", a("q" to "x"), "ok"); assertNull(o.earlier("web_search", a("q" to "x")))
        assertTrue(OneShot.risky("momo_pay_bill")); assertTrue(OneShot.risky("file_delete"))
    }

    @Test fun recoveryRetriesOnlySafeTransientFailures() = runBlocking {
        var n = 0
        val slept = mutableListOf<Long>()
        val r = Recovery.run("web_search", false, 5_000, { slept += it }) { n++; if (n < 3) "Error: connection reset by peer" else "1. result" }
        assertEquals("1. result", r); assertEquals(3, n); assertEquals(Recovery.BACKOFF_MS, slept)
        n = 0
        val s = Recovery.run("message_contact", true, 5_000, { }) { n++; "Error: connection reset" }
        assertEquals(1, n); assertTrue(s.startsWith("Error"))
        n = 0
        val p = Recovery.run("web_search", false, 5_000, { }) { n++; "Error: no such file" }
        assertEquals(1, n); assertEquals("Error: no such file", p)
        // Timeouts report honestly; a risky one is unverified, never claimed.
        val t = Recovery.run("message_contact", true, 50, { }) { kotlinx.coroutines.delay(1_000); "Sent" }
        assertEquals(Status.UNVERIFIED, ToolVerify.classify("message_contact", a(), t).status)
        assertTrue(t.contains("NOT tried again"))
        val u = Recovery.run("fetch_page", false, 50, { }) { kotlinx.coroutines.delay(1_000); "x" }
        assertTrue(u.startsWith("Error: fetch_page didn't finish"))
    }

    @Test fun stopGoesStraightThrough() = runBlocking {
        try { Recovery.run("web_search", false, 5_000, { }) { throw CancellationException("stop") }; throw AssertionError("expected stop") }
        catch (_: CancellationException) { }
        Unit
    }

    // ---- context ----
    @Test fun goalStaysPinned() {
        val g = GoalPin.of("continue", listOf("Book a taxi to the airport. Don't pay more than K150 and only use Yango."))
        assertTrue(g.request.startsWith("Book a taxi"))
        assertTrue(g.constraints.any { it.contains("K150") })
        val b = GoalPin.block(g)
        assertTrue(b.contains("CURRENT TASK") && b.contains("Yango"))
        assertEquals("", GoalPin.block(null))
        val h = JSONArray().put(JSONObject().put("role", "user").put("content", "first"))
            .put(JSONObject().put("role", "user").put("content", "[Blaze check] x")).put(JSONObject().put("role", "user").put("content", "second"))
        assertEquals(listOf("first", "second"), GoalPin.userTexts(h))
    }

    @Test fun shrinkSumsUpWhatItCuts() {
        val m = JSONArray().put(JSONObject().put("role", "system").put("content", "SYS" + GoalPin.block(GoalPin.of("send the report to Ana"))))
        for (i in 0 until 30) {
            m.put(JSONObject().put("role", "user").put("content", "request $i"))
            m.put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject().put("id", "c$i").put("type", "function")
                .put("function", JSONObject().put("name", "file_read").put("arguments", "{}")))))
            m.put(JSONObject().put("role", "tool").put("tool_call_id", "c$i").put("name", "file_read").put("content", "content $i"))
        }
        val s = LlmClient.shrink(m, keep = 6)
        assertTrue(s.getJSONObject(0).getString("content").contains("send the report to Ana"))
        val note = s.getJSONObject(1).getString("content")
        assertTrue(note.startsWith("[Earlier in this conversation"))
        assertTrue(note.contains("User: request 0") && note.contains("file_read"))
        assertTrue(s.length() <= 8)
    }

    @Test fun missingToolWidens() {
        val sent = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "web_search")))
        val reply = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", "set_alarm"))))
        assertEquals(setOf("set_alarm"), ToolPick.missing(reply, sent))
        assertTrue(ToolPick.missing(JSONObject(), sent).isEmpty())
    }

    // ---- #6 cloud runs: phone-step extras ride with the results ----
    @Test fun continuationFoldsNotesAndShots() {
        val base = JSONArray().put(JSONObject().put("role", "system").put("content", "s")).put(JSONObject().put("role", "user").put("content", "go"))
            .put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray()))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "Tapped Send."))
        val shot = JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "[Screenshot of the phone screen]"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,AAA"))))
        val withShot = JSONArray(base.toString()).put(shot).put(JSONObject().put("role", "user").put("content", "[The user adds, while you work: use the work number]"))
        val k = CloudRuns.continuation(withShot, listOf("c1"))!!
        assertEquals(1, k.shots.size); assertEquals(1, k.notes)
        assertTrue(k.results.getJSONObject(0).getString("content").contains("[Added after these results: [The user adds, while you work: use the work number]]"))
        // Plain results still work; an assistant message after them still means a new run.
        assertEquals(0, CloudRuns.continuation(base, listOf("c1"))!!.notes)
        assertNull(CloudRuns.continuation(JSONArray(base.toString()).put(JSONObject().put("role", "assistant").put("content", "x")), listOf("c1")))
        assertNull(CloudRuns.continuation(base, listOf("c2")))
        val req = CloudRuns.describeRequest(shot, "send it")
        assertTrue(req.toString().contains("image_url") && req.toString().contains("send it"))
    }
}
