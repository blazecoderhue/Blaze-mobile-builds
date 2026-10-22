package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * v0.45.9 "an assistant, not a normal chatbot": the [context] block, the memory gate, chips gated on what the phone
 * can do, the greeting name, the persona alignment and the bundled persona v1.0.1.
 */
class Halo0459Test {
    private val lusaka = ZoneId.of("Africa/Lusaka")
    private val wedNow = ZonedDateTime.of(2026, 10, 21, 10, 15, 0, 0, lusaka)
    private val sunNow = ZonedDateTime.of(2026, 10, 25, 10, 15, 0, 0, lusaka)
    private fun weekly(title: String, mask: Int, h: Int, m: Int) = ScheduleDay.Entry(title, "reminder", ScheduleDay.Rule.Weekly(mask, LocalTime.of(h, m)))
    private val fixture = listOf(
        weekly("Church 9:00\u201312:30", ScheduleDay.bit(DayOfWeek.SUNDAY), 8, 30),
        weekly("CIT class", ScheduleDay.bit(DayOfWeek.MONDAY) or ScheduleDay.bit(DayOfWeek.WEDNESDAY), 8, 0),
        weekly("NET class", ScheduleDay.bit(DayOfWeek.TUESDAY) or ScheduleDay.bit(DayOfWeek.WEDNESDAY), 14, 0),
    )

    private fun block(now: ZonedDateTime, text: String, weather: String? = "29\u00b0C Clear, Ndola (Open-Meteo \u00b7 10:00)",
                      open: List<String> = listOf("reply to Stefano about Thursday 9:00"), newIn: String? = null, calOff: Boolean = false) =
        TurnContext.block(TurnContext.Inputs(now, weather, fixture, ScheduleDay.datesAsked(text, now.toLocalDate()), open, newIn, calOff))

    @Test fun contextBlockFormatAndSize() {
        val b = block(wedNow, "anything on for today?", newIn = TurnContext.newInLine("0.45.8", "", 0))
        val lines = b.lines()
        assertEquals("[context]", lines.first()); assertEquals("[/context]", lines.last())
        assertEquals("now: Wed 21 Oct 2026 10:15, Africa/Lusaka (UTC+02:00)", lines[1])
        assertTrue(b, "weather: 29\u00b0C Clear, Ndola (Open-Meteo \u00b7 10:00)" in lines)
        assertTrue(b, "schedule Wed 21 Oct 2026 (2 items, 1 still ahead): 08:00 \"CIT class\" (passed); 14:00 \"NET class\"" in lines)
        assertTrue(b, "next after that day (not that day): Sun 25 Oct 08:30 Church 9:00\u201312:30" in lines)
        assertTrue(b, "open: reply to Stefano about Thursday 9:00" in lines)
        assertTrue(b, "new in 0.45.8: send starts instantly; theme on every screen; full-screen Design" in lines)
        assertTrue("${b.length} chars", b.length <= TurnContext.MAX)
        assertFalse("no raw reminder list, no Sunday item on Wednesday's line", lines.first { it.startsWith("schedule") }.contains("Church"))
    }

    @Test fun sundayListsOnlyChurchAndEmptyDaysSaySo() {
        val b = block(sunNow, "so what's on for today specifically?")
        assertTrue(b, "schedule Sun 25 Oct 2026 (1 item): 08:30 \"Church 9:00\u201312:30\"" in b.lines())
        assertFalse(b, b.lines().any { it.startsWith("schedule") && ("NET" in it || "CIT" in it) })
        val sat = block(sunNow.minusDays(1), "anything on for today?")
        assertTrue(sat, "schedule Sat 24 Oct 2026: nothing scheduled" in sat.lines())
        assertTrue(TurnContext.RULE.contains("nothing scheduled"))
        assertTrue(TurnContext.RULE.contains("never guess the day"))
    }

    @Test fun manyDatesStayUnderTheCap() {
        val many = (1..12).map { weekly("Very long reminder title number $it for the whole week", ScheduleDay.EVERY_DAY, 6 + it, 0) }
        val b = TurnContext.block(TurnContext.Inputs(wedNow, "29\u00b0C Clear, Ndola (Open-Meteo \u00b7 10:00)", many,
            ScheduleDay.datesAsked("what's my schedule this week", wedNow.toLocalDate()), listOf("a", "b", "c"), "new in 0.45.8: x"))
        assertTrue("${b.length}", b.length <= TurnContext.MAX)
        assertTrue(b, b.startsWith("[context]\nnow: Wed 21 Oct 2026 10:15") && b.endsWith("[/context]"))
        assertTrue(b, b.contains("schedule Wed 21 Oct 2026 (12 items"))
    }

    @Test fun calendarOffLineAndNewInShowsOnlyFirstTurns() {
        assertTrue(block(wedNow, "View calendar", calOff = true).contains("calendar: access not allowed yet; to show it call calendar_events"))
        assertNotNull(TurnContext.newInLine("0.45.8", "0.45.8", 2))
        assertNull(TurnContext.newInLine("0.45.8", "0.45.8", TurnContext.NEW_IN_TURNS))
        assertNotNull("a new version starts over", TurnContext.newInLine("0.45.8", "0.45.7", 9))
        assertNull(TurnContext.newInLine("9.9.9", "", 0))
    }

    @Test fun cacheKeepsSixtySecondsAndInvalidates() {
        val c = TurnContext.Cache(60_000)
        var n = 0
        assertEquals("b1", c.get("k", 1000) { n++; "b${n}" })
        assertEquals("b1", c.get("k", 60_999) { n++; "b${n}" })
        assertEquals("b2", c.get("k", 61_000) { n++; "b${n}" })
        c.invalidate()
        assertEquals("b3", c.get("k", 61_001) { n++; "b${n}" })
        assertEquals("b4", c.get("other", 61_002) { n++; "b${n}" })
        assertNull(c.peek("k", 61_003))
        assertEquals(1, c.hits)
    }

    @Test fun weatherLineFromTheHomeCard() {
        assertEquals("29\u00b0C Clear, Ndola (Open-Meteo \u00b7 10:00)",
            TurnContextSource.weatherLine("29\u00b0C", "Clear \u00b7 feels 31\u00b0 \u00b7 wind 9 km/h \u00b7 Open-Meteo \u00b7 10:00", "Ndola"))
    }

    @Test fun scheduleQuestionsCarryTheCalendarTools() {
        for (t in listOf("View calendar", "View your upcoming meetings", "anything on for today?", "what's on friday", "any reminders?", "do I have class tomorrow"))
            assertTrue(t, TurnContext.scheduleAsk(t))
        for (t in listOf("hi Blaze", "doing good thanks!", "play some music", "feel free to explain"))
            assertFalse(t, TurnContext.scheduleAsk(t))
        assertTrue(TurnContext.SCHEDULE_TOOLS.containsAll(listOf("calendar_events", "list_routines")))
        assertTrue("the router picks the schedule group for plurals", "schedule" in ToolRouter.guess("View your upcoming meetings"))
        assertTrue("schedule" in ToolRouter.guess("anything on for today?"))
        assertTrue(com.blaze.assistant.phone.Skills.CALENDAR_OFF.contains("Never say you can't access calendars"))
    }

    @Test fun memoryGateStopsSmallTalkAndReadOnlyQuestions() {
        for (t in listOf("@halo hi", "hi", "hi Blaze", "ok", "thanks", "thanks!", "doing good thanks!", "how are you feeling??", "@halo how are you feeling??",
                "Hey there!", "good morning", "ok cool thanks", "lol", "zikomo", "I'm good, you?",
                "anything on for today?", "so what's on for today specifically?", "what time is it", "what's the weather", "will it rain tomorrow?"))
            assertFalse(t, MemoryGate.allows(t))
        for (t in listOf("remember my CIT class is on Mondays", "thanks! I'm vegetarian by the way", "my sister Grace lives in Kitwe",
                "I prefer replies in Bemba", "set a reminder for church on Sunday at 8:30", "well as the assistant shouldn't you be telling me?", "yes"))
            assertTrue(t, MemoryGate.allows(t))
        assertEquals(MemoryGate.REFUSED, MemoryGate.refusal("memory_save", "doing good thanks!"))
        assertEquals(MemoryGate.REFUSED, MemoryGate.refusal("remember", "anything on for today?"))
        assertNull(MemoryGate.refusal("memory_search", "anything on for today?"))
        assertNull(MemoryGate.refusal("memory_save", "remember I like jazz"))
        assertTrue(MemoryGate.isSmallTalk("doing good thanks!"))
        assertTrue(MemoryGate.isReadOnlyQuestion("anything on for today?"))
        assertFalse(MemoryGate.isReadOnlyQuestion("what time is it, and set an alarm for 7"))
    }

    @Test fun memoryGateIsWiredBeforeEveryWrite() {
        val src = listOf(File("src/main/java/com/blaze/assistant/agent/Agent.kt"), File("app/src/main/java/com/blaze/assistant/agent/Agent.kt")).first { it.exists() }.readText()
        assertTrue("before the auto-extract", Regex("MemoryGate\\.allows\\(userText\\)\\) \\{\\s*val ex = ").containsMatchIn(src))
        assertTrue("before memory_save / remember", src.contains("MemoryGate.refusal(name, lastUserText())?.let { return it }\n            return when (name) {"))
    }

    @Test fun chipsOnlyOfferWhatThePhoneCanDo() {
        val none = ChipGate.Caps(calendar = false, weather = false, music = false, sms = false, calls = false, contacts = false, email = false, location = false, phoneControl = false)
        val chips = listOf("Check today's weather", "View your upcoming meetings", "Replay the last song", "Tell me a joke", "Text Mike I'm late", "Call Mum")
        assertEquals(listOf("Tell me a joke"), ChipGate.filter(chips, none))
        assertEquals(chips, ChipGate.filter(chips, ChipGate.Caps()))
        assertEquals(listOf("Check today's weather", "Tell me a joke"), ChipGate.filter(chips, none.copy(weather = true)))
        assertTrue(ChipGate.allowed("View calendar", none.copy(calendar = true)))
        assertFalse(ChipGate.allowed("View calendar", none))
        // ReplyParts.split drops the chips the phone can't do.
        val before = ChipGate.caps
        try {
            ChipGate.caps = none.copy(weather = true)
            val p = ReplyParts.split("Hey.\n<<follow-ups: Check today's weather | View your upcoming meetings | Replay the last song>>", final = true)
            assertEquals(listOf("Check today's weather"), p.followups)
        } finally { ChipGate.caps = before }
    }

    @Test fun greetingUsesTheRealNameOrNone() {
        assertEquals("Lackson", GreetName.pick("", "Lackson Kapapa"))
        assertEquals("Ana", GreetName.pick("Ana", "Lackson Kapapa"))
        assertNull(GreetName.pick("", ""))
        assertNull(GreetName.pick(" ", "me@example.com"))
        assertNull(GreetName.pick("User", null))
        assertTrue(GreetName.RULE.contains("use no name"))
    }

    @Test fun personaLinesNeverContradictTheSharedPersona() {
        val ours = Duo.ASSISTANT_RULES + Duo.HALO_SOUL + Duo.BLAZE_SOUL + TurnContext.RULE + Duo.DUO_RULES
        assertFalse("Halo no longer 'loves feelings'", Duo.HALO_SOUL.contains("feelings"))
        assertTrue(Duo.ASSISTANT_RULES.contains("no feelings or moods"))
        for (stock in listOf("How can I assist you today?", "What would you like to do next?", "How can I help?", "I'm here to help", "How about you?"))
            assertTrue("$stock is named as banned", Duo.ASSISTANT_RULES.contains(stock))
        assertTrue(Duo.ASSISTANT_RULES.contains("Don't repeat suggestions already offered in this conversation"))
        assertTrue(Duo.sideSystem(null, "t", "").contains("ASSISTANT, NOT A CHAT BUDDY"))
        assertFalse(ours.contains("Assistant rules ("))   // the gateway adds its copy only when no message has it; the persona carries it
    }

    @Test fun bundledPersonaIsV101AndNewerWins() {
        val asset = listOf(File("src/main/assets/persona-v1.json"), File("app/src/main/assets/persona-v1.json")).first { it.exists() }.readText()
        val p = SharedPersona.parse(asset)!!
        assertEquals("1.0.1", p.version)
        assertTrue(p.system.contains("Assistant rules ("))
        val old = """{"version":"1.0.0","system":"You are Blaze, a friendly assistant."}"""
        assertEquals("1.0.1", SharedPersona.pick(old, asset)!!.version)
        val newer = """{"version":"1.0.2","system":"You are Blaze."}"""
        assertEquals("1.0.2", SharedPersona.pick(newer, asset)!!.version)
        assertTrue(SharedPersona.VersionCmp.compare("1.0.10", "1.0.9") > 0)
        assertEquals(0, SharedPersona.VersionCmp.compare("v1.0", "1.0.0"))
    }

    @Test fun startTraceStagesSayWhereTheTimeWent() {
        var t = 0L
        StartTrace.clock = { t }
        try {
            StartTrace.tap(force = true)
            t = 3_000_000; StartTrace.stage("turn")
            t = 4_610_000_000; StartTrace.stage("tools")
            StartTrace.stage("tools")   // once per tap
            t = 4_700_000_000; StartTrace.stage("prompt (run)")
            t = 4_840_000_000; StartTrace.wire()
            StartTrace.stage("late")   // nothing after the wire
            val m = StartTrace.take()!!
            assertEquals("turn 3 ms \u00b7 tools 4.61 s \u00b7 prompt (run) 4.70 s", StartTrace.stagesLine(m))
            assertEquals(4840, m.wireMs)
            val tr = TurnTrace.build(emptyMap(), "run", "cloud run", emptyList(), 0, 0, 0, 1, emptyList(), emptyList(), start = m)
            assertEquals(tr.sendStages, TurnTrace.fromJson(TurnTrace.toJson(tr))!!.sendStages)
            assertTrue(TurnTrace.rows(tr).any { it.first == "Before the wire" && it.second.contains("tools 4.61 s") })
        } finally { StartTrace.clock = { System.nanoTime() }; StartTrace.reset() }
    }

    @Test fun yesToAScheduleOfferCarriesTheScheduleTools() {
        val offer = "[Halo] I can check your Google Calendar, want me to?"
        for (y in listOf("@halo Yes", "yes", "Yes please", "sure", "ok go ahead", "yep!")) assertTrue(y, TurnContext.scheduleFollowUp(y, offer))
        assertTrue(TurnContext.scheduleFollowUp("yes", "Want me to set a reminder for 8:30?"))
        assertFalse(TurnContext.scheduleFollowUp("yes", "Want me to play some music?"))
        assertFalse(TurnContext.scheduleFollowUp("yes but tell me a joke first", offer))
        assertTrue(listOf(File("src/main/java/com/blaze/assistant/agent/ChatLane.kt"), File("app/src/main/java/com/blaze/assistant/agent/ChatLane.kt")).first { it.exists() }.readText().contains("TurnContext.scheduleFollowUp(t, lastAssistant)) return false"))
        assertEquals(setOf("calendar_events", "list_routines", "create_routine"), Duo.SIDE_SCHEDULE_TOOLS)
        // Wired: the side lane and the main @halo turn both add them.
        val src = listOf(java.io.File("src/main/java/com/blaze/assistant/agent/Agent.kt"), java.io.File("app/src/main/java/com/blaze/assistant/agent/Agent.kt")).first { it.exists() }.readText()
        assertTrue(src.contains("roomTools(if (sched) Duo.SIDE_SCHEDULE_TOOLS else emptySet())"))
        assertTrue(src.contains("TurnContext.scheduleFollowUp(lu,"))
    }
}
