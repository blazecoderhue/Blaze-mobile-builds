package com.blaze.assistant.agent

import com.blaze.assistant.agent.ToolVerify.Status
import com.blaze.assistant.voice.WakeNote
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * v0.45.13 phone-control sweep 0: the user's 17:11-17:14 bugs on 0.45.9 and the verify-after-act rule. JVM only: these
 * cover the logic, not a phone (the device evidence is the in-app /phoneqa runner).
 */
class PhoneControlSweepTest {
    private fun a(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { put(it.first, it.second) } }
    private val noWait: suspend (Long, suspend () -> Boolean) -> Boolean = { _, ok -> ok() }

    private class Fake(val fg: String? = null, val timer: Boolean? = null, val cal: Boolean? = null, val contact: Boolean? = null,
                       val playing: String? = null, val words: String? = null, val clock: Boolean? = null, val alarm: Pair<Int, Int>? = null) : ToolVerify.Probes {
        override suspend fun foreground() = fg
        override suspend fun timerRunning() = timer
        override suspend fun calendarHas(title: String, startMs: Long) = cal
        override suspend fun contactHas(name: String, phone: String) = contact
        override suspend fun nowPlaying(pkg: String) = playing
        override suspend fun screenWords() = words
        override suspend fun clockAlarmOn(pid: String, hhmm: String) = clock
        override suspend fun nextAlarm() = alarm
    }
    private fun check(name: String, args: JSONObject, result: String, p: ToolVerify.Probes) = runBlocking { ToolVerify.check(name, args, result, p, 0, noWait) }

    // ---------- A1: "remove the alarm" → "Alarm removed." while the Clock app still showed it ON ----------

    @Test fun alarmDeleteIsOnlyDoneWhenReadBack() {
        // 0.45.9's result for process(delete) was classed verified by default: the root of the fake success.
        val old = "Asked the clock app to delete alarm A2 (label \u201cDemo app alarm (Blaze A2)\u201d). If it shows a list, it's the one with that label."
        assertEquals(Status.UNVERIFIED, ToolVerify.classify("process", a("id" to "A2", "action" to "delete"), old).status)
        val off = "Turned off alarm A2 (18:15). " + ActCheck.checked("the switch of \u201cDemo app alarm (Blaze A2)\u201d (18:15) reads OFF in the Clock app")
        assertEquals(Status.VERIFIED, ToolVerify.classify("process", a("id" to "A2", "action" to "delete"), off).status)
        val still = "I tried to turn off alarm A2. " + ActCheck.notConfirmed("\u201cDemo app alarm (Blaze A2)\u201d still reads ON in the Clock app", "switch it off in the Clock app")
        val v = ToolVerify.classify("process", a("id" to "A2", "action" to "delete"), still)
        assertEquals(Status.UNVERIFIED, v.status); assertTrue(v.why, v.why.contains("still reads ON"))
        // Blaze's own things (a routine) are still done by their own words.
        assertEquals(Status.VERIFIED, ToolVerify.classify("process", a("id" to "R4", "action" to "delete"), "Deleted routine R4 \u201cGym\u201d.").status)
        // The final reply: "Alarm removed." is a claim now, and an unconfirmed delete can't back it.
        val done = listOf(ClaimCheck.Done("processes", Status.VERIFIED), ClaimCheck.Done("process", Status.UNVERIFIED, "the Clock app wasn't read back"))
        val r = ClaimCheck.review("Alarm removed.", done, "remove the alarm")
        assertTrue(r.toString(), r is ClaimCheck.Outcome.Rewrite)
        assertTrue((r as ClaimCheck.Outcome.Rewrite).text, r.text.contains("couldn't confirm"))
        assertTrue(ClaimCheck.review("Alarm removed.", listOf(ClaimCheck.Done("processes", Status.VERIFIED)), "remove the alarm") is ClaimCheck.Outcome.Unbacked)
        assertEquals(ClaimCheck.Outcome.Ok, ClaimCheck.review("Turned off the 18:15 alarm; it reads off in the Clock app.",
            listOf(ClaimCheck.Done("process", Status.VERIFIED, "the switch reads OFF")), "remove the alarm"))
        // "set an alarm" replies are not alarm-off claims.
        assertTrue(ActionProof.claims("Alarm set for 06:40. It'll go off tomorrow.", ActionProof.ACTIONS.first { it.id == "alarm_off" }).isEmpty())
    }

    private fun n(text: String = "", desc: String = "", checkable: Boolean = false, checked: Boolean = false, clickable: Boolean = false, kids: List<AlarmRows.Node> = emptyList()) =
        AlarmRows.Node(text, desc, checkable, checked, clickable, clickable, kids)

    /** Samsung Clock 17:14: header, then one card "Demo app alarm (Blaze A2)" · 18:15 · Wed, Oct 21 · switch ON. */
    private fun samsung(on: Boolean, extra: List<AlarmRows.Node> = emptyList()) = n(kids = listOf(
        n("Alarm in 1 hour 1 minute"), n("Wed, Oct 21, 18:15"),
        n(kids = (listOf(n(clickable = true, kids = listOf(n("Demo app alarm (Blaze A2)"), n("18:15"), n("Wed, Oct 21"), n(desc = "Alarm switch", checkable = true, checked = on, clickable = true))))
            + extra))))

    @Test fun clockRowsAreFoundByLabelThenTime() {
        val r = AlarmRows.find(samsung(true), "A2", "18:15")
        assertNotNull(r); assertEquals(true, r!!.on); assertFalse(r.byTime)
        assertEquals(false, AlarmRows.find(samsung(false), "A2", "18:15")!!.on)
        // A2 never matches A21; by time only when one row shows it.
        val a21 = n(clickable = true, kids = listOf(n("Gym (Blaze A21)"), n("06:00"), n(checkable = true, checked = true, clickable = true)))
        assertEquals("Demo app alarm (Blaze A2)", AlarmRows.find(samsung(true, listOf(a21)), "A2", null)!!.label.text)
        val noLabel = n(kids = listOf(n(clickable = true, kids = listOf(n("6:15 PM"), n(checkable = true, checked = true)))))
        assertTrue(AlarmRows.find(noLabel, "clock@18:15", "18:15")!!.byTime)
        val two = n(kids = listOf(n(kids = listOf(n("18:15"), n(checkable = true, checked = true))), n(kids = listOf(n("18:15"), n(checkable = true, checked = false)))))
        assertNull("two rows at 18:15: can't tell which", AlarmRows.find(two, "clock@18:15", "18:15"))
        assertTrue(AlarmRows.gone(n(kids = listOf(n("No alarms"))), "A2", "18:15"))
        assertFalse(AlarmRows.gone(samsung(true), "A2", "18:15"))
        assertNotNull(AlarmRows.deleteButton(n(kids = listOf(n(clickable = true, kids = listOf(n("Delete")))))))
        assertTrue(AlarmRows.timeForms("18:15").containsAll(listOf("18:15", "6:15")))
    }

    // ---------- A2: the time said must be the time set ----------

    @Test fun replyEchoesTheTimeReallySet() {
        assertEquals(listOf("18:15"), TimeEcho.setTimes(listOf("Alarm set for 18:15, label \u201cDemo app alarm (Blaze A2)\u201d. [ID A2]")))
        assertEquals("Your alarm is set for 18:15. Your class is at 08:00.", TimeEcho.fix("Your alarm is set for 18:30. Your class is at 08:00.", listOf("18:15")))
        assertEquals("Alarm set for 18:15.", TimeEcho.fix("Alarm set for 6:15 pm.".replace("6:15 pm", "18:15"), listOf("18:15")))
        assertEquals("I'll wake you at 06:40.", TimeEcho.fix("I'll wake you at 6:45 am.", listOf("06:40")))
        assertEquals("two alarms: left alone", "Alarms at 7:00 and 7:30.", TimeEcho.fix("Alarms at 7:00 and 7:30.", listOf("07:00", "07:30")))
    }

    // ---------- A3: a 10:15 class that had passed was raised at 17:11 ----------

    private val zone = ZoneId.of("Africa/Lusaka")
    private val wed = LocalDate.of(2026, 10, 21)
    private fun at(h: Int, m: Int) = ZonedDateTime.of(wed, LocalTime.of(h, m), zone)
    private val entries = listOf(
        ScheduleDay.Entry("CIT class", "reminder", ScheduleDay.Rule.Weekly(ScheduleDay.bit(java.time.DayOfWeek.WEDNESDAY), LocalTime.of(10, 15))),
        ScheduleDay.Entry("Demo app check", "reminder", ScheduleDay.Rule.Once(at(18, 30).toInstant())),
        ScheduleDay.Entry("Demo app alarm", "alarm", ScheduleDay.Rule.Once(at(18, 15).toInstant())))

    @Test fun passedItemsAreMarkedAndNotOfferedAsUpcoming() {
        val block = TurnContext.block(TurnContext.Inputs(at(17, 11), schedule = entries))
        assertTrue(block, block.contains("schedule Wed 21 Oct 2026 (3 items, 2 still ahead): 10:15 \"CIT class\" (passed); 18:15 \"Demo app alarm\" (alarm); 18:30 \"Demo app check\""))
        // Without a clock (another day) nothing is marked.
        assertFalse(ScheduleDay.contextLine(wed, ScheduleDay.itemsOn(wed, zone, entries)).contains("passed"))
        val ask = "anything else to do today?"
        assertTrue(TurnContext.scheduleAsk(ask))
        // The 17:11 reply: a passed class offered as today's to-do.
        val fixed = ScheduleTruth.fix("You have a CIT class at 10:15 today.", block, true, ask)
        assertTrue(fixed, fixed.contains("still ahead: 18:15 Demo app alarm") && fixed.contains("18:30 Demo app check") && !fixed.contains("CIT"))
        // The 17:12 reply named the reminder only; the alarm shares its first word, so its time must be there too.
        assertTrue(ScheduleTruth.wrong("You have a Demo app check reminder at 18:30 today.", ScheduleTruth.days(block), null, ask))
        val good = "Still ahead today: your Demo app alarm at 18:15 and the Demo app check reminder at 18:30. The CIT class at 10:15 already passed."
        assertEquals(good, ScheduleTruth.fix(good, block, true, ask))
        // Late evening: everything passed, "nothing else" is right.
        val late = TurnContext.block(TurnContext.Inputs(at(21, 0), schedule = entries))
        assertEquals("Nothing else is on today.", ScheduleTruth.fix("Nothing else is on today.", late, true, ask))
    }

    @Test fun plainWhatsOnKeepsPassedItemsAsEarlier() {
        // v0.45.13 replay round 1: "anything on for today?" at 10:14 answered "You have NET class at 2:00 PM today." 5 of 5.
        val e = listOf(
            ScheduleDay.Entry("CIT class", "reminder", ScheduleDay.Rule.Weekly(ScheduleDay.bit(java.time.DayOfWeek.WEDNESDAY), LocalTime.of(8, 0))),
            ScheduleDay.Entry("NET class", "reminder", ScheduleDay.Rule.Weekly(ScheduleDay.bit(java.time.DayOfWeek.WEDNESDAY), LocalTime.of(14, 0))))
        val block = TurnContext.block(TurnContext.Inputs(at(10, 14), schedule = e))
        val ask = "anything on for today?"
        val fixed = ScheduleTruth.fix("You have NET class at 2:00 PM today.", block, true, ask)
        assertEquals("Today (Wed 21 Oct), still ahead: 14:00 NET class. Earlier: 08:00 CIT class (passed).", fixed)
        val good = "You had CIT class at 08:00 this morning, and NET class is at 14:00."
        assertEquals(good, ScheduleTruth.fix(good, block, true, ask))
        // "what's left" may leave the passed class out
        assertEquals("Still ahead: NET class at 14:00.", ScheduleTruth.fix("Still ahead: NET class at 14:00.", block, true, "anything else to do today?"))
    }

    // ---------- A4: "Memory updated" on read-only questions ----------

    @Test fun memoryGateCoversTheSeventeenElevenMessages() {
        for (t in listOf("anything else to do today?", "that was in the morning so we're good", "okay yea but anything other than those that we setup together?",
                "No, that's all", "never mind", "what's left for today?", "do I have anything tomorrow?", "is there anything on friday?"))
            assertFalse(t, MemoryGate.allows(t))
        // Durable facts and real requests still save.
        for (t in listOf("remember my CIT class is on Mondays", "my sister Grace lives in Kitwe", "set a reminder for church on Sunday at 8:30",
                "well as the assistant shouldn't you be telling me?", "yes", "I prefer replies in Bemba", "remove the alarm"))
            assertTrue(t, MemoryGate.allows(t))
    }

    // ---------- A5: "Listening for Hey Blaze: Offline" while online ----------

    @Test fun wakeNotificationSaysWhatItIsDoing() {
        for (st in WakeNote.State.values()) {
            assertFalse(st.name, WakeNote.claimsNetwork(WakeNote.text(st)))
            assertFalse(st.name, WakeNote.claimsNetwork(WakeNote.title(st)))
        }
        assertEquals(WakeNote.State.LISTENING, WakeNote.of(listening = true, paused = false, sleeping = false, micBlocked = false))
        assertEquals(WakeNote.State.MIC_BLOCKED, WakeNote.of(listening = true, paused = false, sleeping = false, micBlocked = true))
        assertEquals(WakeNote.State.SLEEPING, WakeNote.of(listening = false, paused = false, sleeping = true, micBlocked = false))
        val src = listOf(File("src/main/java/com/blaze/assistant/voice/WakeWordService.kt"), File("app/src/main/java/com/blaze/assistant/voice/WakeWordService.kt")).first { it.exists() }.readText()
        assertFalse("the fixed 'Offline.' line is gone", src.contains("\"Offline."))
        assertTrue("re-posted on state changes", src.contains("notify(ID, build(st))"))
    }

    // ---------- B: verify-after-act ----------

    @Test fun openingAnAppIsNeverTheGoal() {
        val opened = listOf(ClaimCheck.Done("open_app", Status.VERIFIED, "com.google.android.youtube is in front"))
        assertTrue(GoalCheck.openedOnly("play Despacito by Luis Fonsi on YouTube", "Done! It's playing now.", opened))
        assertFalse("open alone is the goal", GoalCheck.openedOnly("open YouTube", "Done, YouTube is open.", opened))
        assertFalse("a real proof", GoalCheck.openedOnly("play Despacito on YouTube", "It's playing now.",
            opened + ClaimCheck.Done("search_in_app", Status.VERIFIED, "YouTube is playing \u201cDespacito\u201d")))
        assertFalse("honest about it", GoalCheck.openedOnly("open display settings and turn dark mode on", "I opened Display settings but couldn't find the switch.", opened))
    }

    @Test fun eachCaseHasAReadBack() {
        assertEquals(Status.VERIFIED, check("set_timer", a("seconds" to 120), "Timer started for 2 min. [ID T1]", Fake(timer = true)).status)
        assertEquals(Status.UNVERIFIED, check("set_timer", a("seconds" to 120), "Timer started for 2 min. [ID T1]", Fake(timer = false)).status)
        assertEquals(Status.UNVERIFIED, check("set_timer", a("seconds" to 120), "Timer started for 2 min. [ID T1]", ToolVerify.NoProbes).status)
        val ev = a("title" to "QA review", "start" to "2026-10-22 15:00", "end" to "2026-10-22 15:30")
        assertEquals(Status.VERIFIED, check("add_calendar_event", ev, "Added \"QA review\" on Thu 22 Oct 15:00. [ID E1]", Fake(cal = true)).status)
        assertEquals(Status.FAILED, check("add_calendar_event", ev, "Added \"QA review\" on Thu 22 Oct 15:00. [ID E1]", Fake(cal = false)).status)
        assertEquals(Status.UNVERIFIED, check("add_calendar_event", ev, "Opened the calendar with the event filled in; the user taps Save.", Fake(cal = false)).status)
        assertEquals(Status.VERIFIED, check("add_contact", a("name" to "QA Tester", "phone" to "0977123456"), "Saved contact QA Tester.", Fake(contact = true)).status)
        val sms = a("number" to "0977000000", "message" to "QA draft test")
        val d = check("compose_sms", sms, "Message drafted; the user needs to press send.", Fake(fg = "com.samsung.android.messaging", words = "to 0977000000 qa draft test send"))
        assertEquals(Status.VERIFIED, d.status); assertTrue(d.why.contains("NOT been sent"))
        assertEquals(Status.UNVERIFIED, check("compose_sms", sms, "Message drafted; the user needs to press send.", Fake(fg = "com.samsung.android.messaging", words = "conversations")).status)
        val yt = a("app" to "YouTube", "query" to "Despacito Luis Fonsi", "play" to true)
        val p = check("search_in_app", yt, "Opened YouTube search for Despacito.", Fake(fg = "com.google.android.youtube", playing = "Luis Fonsi - Despacito ft. Daddy Yankee"))
        assertEquals(Status.VERIFIED, p.status)
        assertEquals(Status.UNVERIFIED, check("search_in_app", yt, "Opened YouTube search for Despacito.", Fake(fg = "com.google.android.youtube", playing = "")).status)
        assertEquals(ClaimCheck.Outcome.Ok, ClaimCheck.review("Despacito is playing now.", listOf(ClaimCheck.Done("search_in_app", p.status, p.why)), "play Despacito on YouTube"))
        val nav = a("place" to "Levy Junction Mall")
        assertEquals(Status.VERIFIED, check("navigate_to", nav, "Opened directions.", Fake(fg = "com.google.android.apps.maps", words = "levy junction mall 12 min 4.2 km start")).status)
        assertEquals(Status.UNVERIFIED, check("navigate_to", nav, "Opened directions.", Fake(fg = "com.google.android.apps.maps", words = "search here")).status)
        // set_alarm hidden behind an earlier alarm: the Clock list proves it.
        assertEquals(Status.VERIFIED, check("set_alarm", a("hour" to 6, "minute" to 40), "Alarm set for 06:40, label \u201cQA wake (Blaze A5)\u201d. [ID A5]", Fake(alarm = 6 to 0, clock = true)).status)
        // A tap on a switch read back on proves a setting claim.
        val t = ToolVerify.classify("tap", a("text" to "Dark mode", "want" to "on"), "Tapped \"Dark mode\". " + ActCheck.checked("the switch \u201cDark mode\u201d now reads on"))
        assertEquals(Status.VERIFIED, t.status)
        assertEquals(ClaimCheck.Outcome.Ok, ClaimCheck.review("I turned dark mode on.", listOf(ClaimCheck.Done("open_settings_page", Status.VERIFIED, "Settings is in front"), ClaimCheck.Done("tap", t.status, t.why)), "open display settings and turn dark mode on"))
    }

    @Test fun strictJudgeAndReport() {
        val draft = PhoneCases.byId("draft-only")!!
        assertFalse(PhoneCases.judge(draft, listOf("compose_sms" to Status.VERIFIED), "Sent! Your message is on its way.").pass)
        assertTrue(PhoneCases.judge(draft, listOf("compose_sms" to Status.VERIFIED), "Drafted it in Messages. It hasn't been sent; tap send when you're ready.").pass)
        val off = PhoneCases.byId("alarm-off")!!
        assertFalse(PhoneCases.judge(off, listOf("process" to Status.UNVERIFIED), "Alarm removed.").pass)
        assertEquals(11, PhoneCases.ALL.size)
        val run = PhoneQaReport.Run("timer", 1, "set a timer for 2 minutes", listOf("req-1"), "agent", "m", "p",
            listOf(PhoneQaReport.Tool("set_timer", "{}", "VERIFIED", "running")), 800, 2400, "before", "after", true, "notification", true, "proof", "Timer set for 2 min.", "c1")
        val bad = run.copy(run = 2, verified = false)
        assertEquals(listOf(PhoneQaReport.Summary("timer", 1, 2)), PhoneQaReport.summary(listOf(run, bad)))
        val md = PhoneQaReport.markdown(listOf(run, bad), mapOf("app" to "0.45.13"))
        assertTrue(md.contains("| timer | 1 | 2 | FAIL |") && md.contains("DEVICE evidence"))
        val js = JSONObject(PhoneQaReport.json(listOf(run), mapOf("app" to "0.45.13")))
        assertEquals("device", js.getString("kind")); assertEquals("req-1", js.getJSONArray("runs").getJSONObject(0).getJSONArray("request_ids").getString(0))
    }

    @Test fun sourceKeepsTheRules() {
        fun src(p: String) = listOf(File("src/main/java/com/blaze/assistant/$p"), File("app/src/main/java/com/blaze/assistant/$p")).first { it.exists() }.readText()
        val pr = src("agent/Processes.kt")
        assertTrue(pr.contains("ClockUi.alarmOff(ctx, p.id"))
        assertFalse("no drop before the read-back", pr.contains("dismissAlarm(ctx, p); drop(p.id)"))
        assertTrue(src("agent/ToolVerify.kt").contains("Opening an app or a settings page is only the first step"))
        assertTrue(src("phone/PhoneTools.kt").contains("Alarm set for \${"))
        assertTrue(src("ui/CommandRunner.kt").contains("\"phoneqa\" ->"))
    }
}
