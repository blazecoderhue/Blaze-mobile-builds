package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * v0.45.9 pass check, part 1: writes the requests the app would send for the user's reported turns, built by the
 * app's own code (bundled persona v1.0.1 first, byte-identical; the app's second message with the name line, the
 * assistant rules, the context rule, Halo's identity on @halo turns and the [context] block last; the schedule tools on
 * schedule turns), with the clock fixed and fixture reminders / calendar. tools/halo_replay.py sends them to the live
 * gateway turn by turn (history = the real replies) and writes the transcript. Output: app/build/qa/replay-0459.json.
 *
 * Not in these requests: the rest of the full agent prompt (~27k chars of phone / tool rules, skills, awareness),
 * which can't be built off the phone. The parts that decide tone, names, dates and memory are the real ones.
 */
class HaloReplayRequestTest {
    private val zone = ZoneId.of("Africa/Lusaka")
    private fun weekly(title: String, mask: Int, h: Int, m: Int) = ScheduleDay.Entry(title, "reminder", ScheduleDay.Rule.Weekly(mask, LocalTime.of(h, m)))
    private val reminders = listOf(
        weekly("Church 9:00\u201312:30", ScheduleDay.bit(DayOfWeek.SUNDAY), 8, 30),
        weekly("CIT class", ScheduleDay.bit(DayOfWeek.MONDAY) or ScheduleDay.bit(DayOfWeek.WEDNESDAY), 8, 0),
        weekly("NET class", ScheduleDay.bit(DayOfWeek.TUESDAY) or ScheduleDay.bit(DayOfWeek.WEDNESDAY), 14, 0),
    )
    private val weather = "29\u00b0C Clear, Ndola (Open-Meteo \u00b7 10:00)"
    private val open = listOf("reply to Stefano about Thursday 9:00")

    private fun persona(): String {
        val f = listOf(File("src/main/assets/persona-v1.json"), File("app/src/main/assets/persona-v1.json")).first { it.exists() }
        return SharedPersona.parse(f.readText())!!.system
    }

    /** The app's second system message for one turn (Agent.systemPrompt's persona/name/rules parts + requestMessages' tail). */
    private fun second(user: String, now: ZonedDateTime, halo: Boolean, extra: List<ScheduleDay.Entry> = emptyList(), calOff: Boolean = false): String {
        val name = GreetName.pick("", "Sam Banda")
        val block = TurnContext.block(TurnContext.Inputs(now, weather, reminders + extra, ScheduleDay.datesAsked(user, now.toLocalDate()), open,
            TurnContext.newInLine("0.45.8", "", 0), calOff))
        return SharedPersona.contextLine(name, "") + (if (name != null) "The user's name is $name. " else "") + GreetName.RULE +
            "\nHOW YOU TALK: short, warm and sure of yourself, like a sharp friend texting back. Lead with the answer. One or two sentences unless they ask for more." +
            Duo.ASSISTANT_RULES + TurnContext.RULE + SaveFile.RULE + ToolTruth.RULE +
            "\nAt the very end of each final answer (never when calling tools), add one line exactly like <<follow-ups: first | second | third>> with three short next requests (max 6 words each) written as the user would say them." +
            Duo.awareness(if (halo) Duo.Who.HALO else Duo.Who.BLAZE) + "\n" + block
    }

    private fun tool(name: String, desc: String, props: JSONObject = JSONObject()) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props)))

    // v0.45.9 (ToolTruth): the real request shape carries the full registered list on every agent turn.
    private val tools: JSONArray by lazy { ToolTruthTest().registry().first }

    /** v0.45.13: the list the phone really registers (registry + PhoneTools), as one request sends it: capped to ≤128 for this turn (ToolTruth.cap). */
    private val phoneAll: JSONArray by lazy {
        val t = JSONArray(); val all = tools; for (i in 0 until all.length()) t.put(all.get(i))
        try { val p = com.blaze.assistant.phone.PhoneTools.definitions(); for (i in 0 until p.length()) t.put(p.get(i)) } catch (_: Throwable) { }
        ToolTruth.clean(t)
    }
    private fun wire(msgs: JSONArray): JSONArray = ToolTruth.cap(msgs, phoneAll)

    private fun turn(user: String, now: ZonedDateTime, halo: Boolean, withTools: Boolean, extra: List<ScheduleDay.Entry> = emptyList(), calOff: Boolean = false, lastReply: String = "", said: List<String> = listOf(lastReply).filter { it.isNotBlank() }) =
        JSONObject().put("user", user).put("now", now.toString()).put("halo", halo).put("system2", second(user, now, halo, extra, calOff))
            .put("memory_write_allowed", MemoryGate.allows(user)).put("schedule_ask", TurnContext.scheduleAsk(user))
            .put("schedule_follow_up", TurnContext.scheduleFollowUp(user, lastReply))
            .apply { // v0.45.9: the first step's tool_choice, exactly as LlmClient sends it (ToolTruth.choice); v0.45.13: the phone's real list, capped
                val msgs = JSONArray().apply { said.forEach { put(JSONObject().put("role", "assistant").put("content", it)) }; put(JSONObject().put("role", "user").put("content", user)) }
                val sent = if (withTools || TurnContext.scheduleAsk(user) || TurnContext.scheduleFollowUp(user, lastReply)) wire(msgs) else JSONArray()
                put("tools", sent)
                put("tool_choice", if (sent.length() > 0) ToolTruth.choice(msgs, sent) else "auto")
                put("tool_choice_follow", JSONObject(ToolTruth.follow(user)))
            }

    /**
     * v0.45.13 phone-control sweep 0, model side: the strict PhoneCases (plus the 17:13 "remove the alarm" turn, once
     * with a read-back that confirms and once with one that doesn't) as the app sends them: the same second message
     * plus the verify rule (ToolVerify.PROMPT_RULE), and the full tool list WITH the phone-control tools. tools/halo_replay.py
     * answers the phone tools with fixtures shaped like the app's tagged results. Output: app/build/qa/replay-phone.json.
     */
    @Test fun writePhoneReplayRequests() {
        val now = ZonedDateTime.of(2026, 10, 21, 17, 13, 0, 0, zone)
        val phone = JSONArray().also { t -> val all = tools; for (i in 0 until all.length()) t.put(all.get(i)) }
        val missed = ArrayList<String>()
        try { val p = com.blaze.assistant.phone.PhoneTools.definitions(); for (i in 0 until p.length()) phone.put(p.get(i)) } catch (e: Throwable) { missed += "PhoneTools (${e.javaClass.simpleName})" }
        val clean = ToolTruth.clean(phone)
        val names = ToolTruth.names(clean)
        val alarm = ScheduleDay.Entry("Demo app alarm", "alarm", ScheduleDay.Rule.Once(ZonedDateTime.of(2026, 10, 21, 18, 15, 0, 0, zone).toInstant()))
        fun pturn(user: String, said: List<String> = emptyList()) = JSONObject().put("user", user).put("now", now.toString()).put("halo", false)
            .put("system2", second(user, now, false, listOf(alarm)) + ToolVerify.PROMPT_RULE)
            .put("memory_write_allowed", MemoryGate.allows(user)).put("schedule_ask", TurnContext.scheduleAsk(user)).put("schedule_follow_up", false)
            .apply {   // v0.45.13: ≤128 tools (ToolTruth.cap), the named first step and the named second step (tool_choice_follow)
                val msgs = JSONArray().apply { said.forEach { put(JSONObject().put("role", "assistant").put("content", it)) }; put(JSONObject().put("role", "user").put("content", user)) }
                val sent = ToolTruth.cap(msgs, clean)
                put("tools", sent).put("tool_choice", ToolTruth.choice(msgs, sent)).put("tool_choice_follow", JSONObject(ToolTruth.follow(user)))
            }
        val scen = JSONArray()
        for (c in PhoneCases.ALL) scen.put(JSONObject().put("id", "phone-" + c.id).put("case", c.id).put("proof", JSONArray(c.proof.toList()))
            .put("must_not_say", JSONArray(c.mustNotSay)).put("turns", JSONArray().put(pturn(c.prompt))))
        // The user's 17:13 turn, both ways the Clock read-back can go.
        for (variant in listOf("confirmed", "not_confirmed"))
            scen.put(JSONObject().put("id", "phone-remove-alarm-1713-$variant").put("case", "alarm-off").put("variant", variant)
                .put("proof", JSONArray(listOf("process"))).put("must_not_say", JSONArray(if (variant == "not_confirmed") listOf("removed", "deleted", "turned off", "is off", "cancelled") else emptyList()))
                .put("turns", JSONArray().put(pturn("remove the alarm"))))
        assertTrue("phone tools in the list: $missed", names.containsAll(listOf("set_alarm", "set_timer", "compose_sms", "search_in_app", "tap", "read_screen", "open_app", "process", "processes", "add_calendar_event", "add_contact", "device_setting", "open_settings_page")))
        val out = JSONObject().put("persona", persona()).put("persona_version", "1.0.1").put("kind", "model-side").put("scenarios", scen)
        val dir = File(System.getProperty("user.dir"), "build/qa").apply { mkdirs() }
        File(dir, "replay-phone.json").writeText(out.toString(1))
    }

    @Test fun writeReplayRequests() {
        val wed = ZonedDateTime.of(2026, 10, 21, 10, 12, 0, 0, zone)
        val sun = ZonedDateTime.of(2026, 10, 25, 10, 14, 0, 0, zone)
        val wedSched = ZonedDateTime.of(2026, 10, 21, 10, 14, 0, 0, zone)
        val lab = ScheduleDay.Entry("Lab group meeting", "event", ScheduleDay.Rule.Span(ZonedDateTime.of(2026, 10, 21, 15, 0, 0, 0, zone).toInstant(),
            ZonedDateTime.of(2026, 10, 21, 16, 0, 0, 0, zone).toInstant()))
        val scenarios = JSONArray()
            .put(JSONObject().put("id", "halo-4-turns").put("turns", JSONArray()
                .put(turn("@halo hi", wed, true, true))
                .put(turn("@halo how are you feeling??", wed, true, true))
                .put(turn("doing good thanks!", wed.plusMinutes(1), false, true))
                .put(turn("well as the assistant shouldn't you be telling me?", wed.plusMinutes(1), false, true))))
            .put(JSONObject().put("id", "schedule-wednesday").put("calendar_result", com.blaze.assistant.phone.Skills.eventsResult(emptyList())).put("turns", JSONArray()
                .put(turn("anything on for today?", wedSched, false, true))
                .put(turn("so what's on for today specifically?", wedSched.plusMinutes(1), false, true))))
            .put(JSONObject().put("id", "schedule-sunday").put("calendar_result", com.blaze.assistant.phone.Skills.eventsResult(emptyList())).put("turns", JSONArray()
                .put(turn("anything on for today?", sun, false, true))
                .put(turn("so what's on for today specifically?", sun.plusMinutes(1), false, true))))
            .put(JSONObject().put("id", "hi-blaze").put("turns", JSONArray().put(turn("hi Blaze", wed.plusMinutes(15), false, true))))
            // "View calendar": granted → the phone's calendar_events returns the fixture event; not granted → the tool shows
            // Android's one-tap prompt and (denied) returns Skills.CALENDAR_OFF.
            .put(JSONObject().put("id", "view-calendar-granted").put("calendar_result", com.blaze.assistant.phone.Skills.eventsResult(listOf("Wed 21 Oct 15:00\u201316:00: Lab group meeting @ Room 4")))
                .put("turns", JSONArray().put(turn("View calendar", wed.plusMinutes(16), false, true, listOf(lab)))))
            .put(JSONObject().put("id", "view-calendar-not-granted").put("calendar_result", com.blaze.assistant.phone.Skills.CALENDAR_OFF)
                .put("turns", JSONArray().put(turn("View calendar", wed.plusMinutes(16), false, true, calOff = true))))
        // 10:30 phone case: "@halo Yes" to Halo's own calendar offer got "I currently don't have the ability to access or view your calendar."
        val offer = "[Halo] I can check your Google Calendar, want me to?"
        scenarios.put(JSONObject().put("id", "halo-yes-to-calendar-offer")
            .put("calendar_result", com.blaze.assistant.phone.Skills.eventsResult(listOf("Wed 21 Oct 15:00\u201316:00: Lab group meeting @ Room 4")))
            .put("seed_history", JSONArray().put(JSONObject().put("role", "user").put("content", "@halo what's my day looking like?"))
                .put(JSONObject().put("role", "assistant").put("content", offer)))
            .put("turns", JSONArray().put(turn("@halo Yes", wed.plusMinutes(18), true, false, listOf(lab), lastReply = offer))))
        // v0.45.9 strict tool cases (user 12:14) and "save this file" after an HTML reply (user 12:12).
        val htmlReply = "Here's a 3D solar system page:\n```html\n<!DOCTYPE html>\n<html><head><title>Solar System 3D</title>" +
            "<script src=\"https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.min.js\"></script></head><body><script>const scene = new THREE.Scene();</script></body></html>\n```"
        fun one(id: String, user: String, seed: JSONArray? = null) = JSONObject().put("id", id).apply { if (seed != null) put("seed_history", seed) }
            .put("turns", JSONArray().put(turn(user, wed.plusMinutes(20), false, true, said = seed?.let { h -> (0 until h.length()).map { h.getJSONObject(it) }.filter { it.getString("role") == "assistant" }.map { it.getString("content") } } ?: emptyList())))
        scenarios.put(one("terminal-ls", "run ls in the terminal"))
            .put(one("linux-browser", "open example.com in the Linux browser"))
            .put(one("open-settings", "open Settings"))
            .put(one("open-design", "open Design view"))
            .put(one("save-after-html", "save this file", JSONArray().put(JSONObject().put("role", "user").put("content", "make me a three.js solar system as one html file"))
                .put(JSONObject().put("role", "assistant").put("content", htmlReply))))
        // v0.45.9 gate (user via HQ 15:37): every registered tool family live 5/5, incl. reminders, search, memory,
        // a turn after earlier tool calls in the history, and Halo choosing a tool on her own turn.
        fun halo(id: String, user: String) = JSONObject().put("id", id).put("turns", JSONArray().put(turn(user, wed.plusMinutes(21), true, true)))
        val lsCall = JSONObject().put("id", "call_ls1").put("type", "function").put("function", JSONObject().put("name", "run_shell").put("arguments", "{\"command\":\"ls\"}"))
        val priorTools = JSONArray().put(JSONObject().put("role", "user").put("content", "run ls in the terminal"))
            .put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(lsCall)))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call_ls1").put("content", "$ ls\nDownload\nphone\nprojects\nnotes.txt\n(exit 0)"))
            .put(JSONObject().put("role", "assistant").put("content", "The terminal shows Download, phone, projects and notes.txt."))
        scenarios.put(one("reminder-create", "remind me to call mum at 18:00 today"))
            .put(one("reminders-list", "what reminders do I have set?"))
            .put(one("web-search", "search the web for today's US dollar to kwacha exchange rate"))
            .put(one("memory-recall", "what do you remember about my exams?"))
            .put(one("memory-save", "remember that my NET exam is on 3 November"))
            .put(one("prior-tool-history", "now list what's inside projects", priorTools))
            .put(halo("halo-tool-terminal", "@halo run ls in the terminal"))
            .put(halo("halo-tool-search", "@halo search the web for Zambia news today"))
        assertTrue(MemoryGate.allows("remember that my NET exam is on 3 November"))
        assertTrue(SaveFile.plan(JSONObject(), listOf(htmlReply))!!.name == "solar-system-3d.html")
        assertTrue(TurnContext.scheduleFollowUp("@halo Yes", offer))
        // v0.45.9: the first step names the one tool these asks need.
        fun choiceOf(id: String) = (0 until scenarios.length()).map { scenarios.getJSONObject(it) }.first { it.getString("id") == id }.getJSONArray("turns").getJSONObject(0).opt("tool_choice").toString()
        assertTrue(choiceOf("save-after-html").contains("save_file"))
        assertTrue(choiceOf("view-calendar-not-granted").contains("calendar_events"))
        assertTrue(choiceOf("halo-yes-to-calendar-offer").contains("calendar_events"))
        assertTrue(choiceOf("terminal-ls").contains("run_shell") && choiceOf("hi-blaze") == "auto")  // v0.45.13 round 2: named run_shell
        assertTrue(choiceOf("reminder-create").contains("create_routine") && choiceOf("reminders-list").contains("list_routines"))
        assertTrue(choiceOf("memory-save").contains("memory_save") && choiceOf("memory-recall").contains("memory_search"))
        assertTrue(choiceOf("web-search") == "auto" && choiceOf("prior-tool-history") == "auto" && choiceOf("halo-tool-terminal").contains("run_shell"))
        assertTrue(choiceOf("linux-browser").contains("desktop_task") && choiceOf("open-settings").contains("open_view") && choiceOf("open-design").contains("open_view"))
        val out = JSONObject().put("persona", persona()).put("persona_version", "1.0.1").put("scenarios", scenarios)
        val dir = File(System.getProperty("user.dir"), "build/qa").apply { mkdirs() }
        File(dir, "replay-0459.json").writeText(out.toString(1))
        // Sanity: the date lines are right before anything goes to a model.
        val s = (0 until scenarios.length()).flatMap { i -> scenarios.getJSONObject(i).getJSONArray("turns").let { t -> (0 until t.length()).map { t.getJSONObject(it).getString("system2") } } }.joinToString("\n")
        assertTrue(s.contains("schedule Wed 21 Oct 2026 (2 items, 1 still ahead): 08:00 \"CIT class\" (passed); 14:00 \"NET class\""))
        assertTrue(s.contains("schedule Sun 25 Oct 2026 (1 item): 08:30 \"Church 9:00\u201312:30\""))
        assertTrue(!persona().isBlank() && persona().contains("Assistant rules ("))
    }
}
