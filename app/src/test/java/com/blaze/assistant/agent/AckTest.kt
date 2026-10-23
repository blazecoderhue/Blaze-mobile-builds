package com.blaze.assistant.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Ack now, work in the background, report when done (HQ 22 Oct 20:35 / 20:36). */
class AckTest {

    private val tasks = listOf(
        "remind me to call mum at 9 tomorrow", "set an alarm for 6:30 am", "set a timer for 10 minutes",
        "wake me up at 7", "remind me in 20 minutes to check the oven", "send a text to Sam saying I'm late",
        "book a table for two on friday", "download this video", "turn on wifi", "play some jazz",
        "find me a cheap flight to Lusaka", "write an email to my landlord", "schedule a meeting with Ana on monday at 3pm",
        "delete the photos from yesterday", "translate this to French", "research the best budget phones",
    )
    private val chat = listOf("hi", "hey Blaze", "good morning", "thanks", "thank you!", "ok", "lol", "how are you?",
        "what's the capital of Zambia?", "who won the game last night?", "why is the sky blue")

    @Test fun tasksGetAnAckChatDoesNot() {
        tasks.forEach { assertNotNull(it, Ack.local(it)) }
        chat.forEach { assertNull(it, Ack.local(it)) }
        assertNull(Ack.local("[Scheduled routine] morning brief"))
        assertNull(Ack.local("/clear"))
    }

    @Test fun anAckNeverSaysDone() {
        tasks.forEach { t -> val a = Ack.local(t)!!; assertTrue("$t -> $a", Ack.safe(a)); assertFalse(a, a.contains("\u2713")) }
        listOf("Done! Reminder set for 9:00.", "I've set your alarm for 6:30.", "Your reminder is set.", "Message sent.",
            "All set, alarm for 7.", "I sent the text.", "Timer started successfully.", "The table has been booked.", "Wi-Fi is now on.")
            .forEach { assertFalse(it, Ack.safe(it)) }
        listOf("Got it, setting that reminder for 9:00 tomorrow. I'll confirm when it's set.",
            "Sending it now. I'll confirm once it's sent.", "Booking a table for two on Friday.", "Turning on dark mode.")
            .forEach { assertTrue(it, Ack.safe(it)) }
        // 0.45.19: placeholders are never an ack.
        listOf("Got it, I'm on it. I'll message you here when it's done.", "On it, I'll let you know once it's sent.",
            "Booking that now. I'll message you when it's booked.", "Got it \u2014 passing that straight to Blaze.")
            .forEach { assertFalse(it, Ack.safe(it)) }
    }

    @Test fun reminderAckNamesTheTime() {
        assertEquals("Setting a reminder for 9:00 tomorrow.", Ack.local("remind me to call mum at 9 tomorrow"))
        assertEquals("Setting an alarm for 6:30 am.", Ack.local("set an alarm for 6:30 am"))
        assertEquals("Starting a timer.", Ack.local("set a timer"))
        assertEquals("in 10 minutes", Ack.whenPart("set a timer in 10 minutes"))
        assertEquals("for Friday", Ack.whenPart("book a table on friday"))
        assertEquals("Reminder \u00b7 in progress", Ack.chip("remind me at 9"))
    }

    @Test fun localAckIsInstantAndNeedsNoNetwork() {
        val t0 = System.nanoTime()
        repeat(50) { tasks.forEach { Ack.local(it) } }
        val perMs = (System.nanoTime() - t0) / 1e6 / (50 * tasks.size)
        assertTrue("ack took $perMs ms", perMs < 5.0)
        assertEquals(2_000L, Ack.FAST_MS)   // 0.45.19: a task done within ~2 s gets no ack at all, only its result
    }

    @Test fun oneAckPerTurnWhoeverSendsFirst() {
        val g = Ack.Gate()
        assertFalse(g.take("Got it, I'm on it. I'll message you here when it's done."))   // 0.45.19: a placeholder never shows
        assertTrue(g.take("Turning on dark mode."))
        assertFalse(g.take("On it, I'll confirm when it's set."))          // the gateway's ack after the local one
        assertFalse(g.take("Halo here, I'll confirm when it's set."))      // the duo: one ack only
        val g2 = Ack.Gate()
        assertFalse(g2.take("Done, reminder set."))                         // an unsafe ack is never shown
        g2.finished = true
        assertFalse(g2.take("Got it, I'll confirm when it's set."))         // the turn already answered
        assertFalse(g2.acked)
    }

    @Test fun doneOnlyWhenEveryActionWasVerified() {
        assertEquals("Done \u2713 Reminder set for Fri 9:00.", Ack.receipt("Reminder set for Fri 9:00.", verified = 1, unproven = 0, rewrote = false))
        assertEquals("I tried to set the reminder, but it didn't work.", Ack.receipt("I tried to set the reminder, but it didn't work.", 1, 1, false))
        assertEquals("Reminder set.", Ack.receipt("Reminder set.", verified = 0, unproven = 0, rewrote = false))   // a claim with nothing behind it
        assertEquals("x", Ack.receipt("x", 1, 0, rewrote = true))
        assertEquals("Done \u2713 a", Ack.receipt("Done \u2713 a", 2, 0, false))
    }

    @Test fun ackFramesWithOrWithoutType() {
        val a = CloudRuns.frameEvent(CloudRuns.Frame(3, "ack", "{\"text\":\"On it, I'll confirm when it's set.\",\"task\":\"reminder\"}"))
        assertEquals("ack", a.getString("type"))
        assertEquals("On it, I'll confirm when it's set.", CloudRuns.ackText(a))
        val plain = CloudRuns.frameEvent(CloudRuns.Frame(4, "ack", "Got it, on it."))
        assertEquals("Got it, on it.", CloudRuns.ackText(plain))
        val d = CloudRuns.frameEvent(CloudRuns.Frame(5, "delta", JSONObject().put("type", "delta").put("seq", 5).put("step", 0).put("text", "x").toString()))
        assertEquals("delta", d.getString("type"))
        val r = CloudRuns.Reducer()
        assertTrue(r.apply(JSONObject().put("seq", 1).put("type", "ack").put("text", "first")))
        assertTrue(r.apply(JSONObject().put("seq", 2).put("type", "ack").put("text", "second")))
        assertEquals("first", r.ack)
    }

    // ---- through the real client against the in-memory gateway ----

    private val kv = HashMap<String, String>()
    private var clock = 1_000_000L
    private lateinit var gw: CloudRunsTest.FakeGateway
    private lateinit var client: CloudRuns.Client
    private val acks = mutableListOf<String>()
    private val ev = object : CloudRuns.Events { override fun ack(text: String) { acks += text } }

    @Before fun setUp() {
        kv.clear(); acks.clear(); CloudRuns.owned.clear()
        gw = CloudRunsTest.FakeGateway()
        client = CloudRuns.Client(gw, CloudRuns.RunStore({ kv[it] }, { k, v -> kv[k] = v }, { clock }), sleep = { clock += it }, now = { clock },
            detached = { f -> runBlocking { f() } })
    }

    private fun user(text: String) = JSONArray().put(JSONObject().put("role", "user").put("content", text))

    @Test fun gatewayAckArrivesOnceBeforeTheReply() = runBlocking {
        gw.brain = { r, _ ->
            r.emit("ack", JSONObject().put("text", "Got it, setting that reminder for 9:00 tomorrow. I'll confirm when it's set."))
            r.emit("ack", JSONObject().put("text", "duplicate"))
            r.emit("status", JSONObject().put("status", "running"))
            gw.done(r, "Reminder set for Fri 9:00.")
        }
        val t = CloudRuns.Turn("main", "t-ack-1")
        val m = client.complete(t, user("remind me at 9 tomorrow"), null, "agent", ev)
        assertEquals("Reminder set for Fri 9:00.", m.getString("content"))
        assertEquals(listOf("Got it, setting that reminder for 9:00 tomorrow. I'll confirm when it's set."), acks)
        assertTrue(t.acked)
    }

    @Test fun noAckForAGreeting() = runBlocking {
        val t = CloudRuns.Turn("main", "t-ack-2")
        client.complete(t, user("hi"), null, "quick", ev)
        assertTrue(acks.isEmpty())
        assertNull(Ack.local("hi"))
    }

    // ---- killed mid-task: completes or reports, never a false done ----

    @Test fun killedMidTaskIsAnsweredOrReportedHonestly() {
        val store = HashMap<String, String>()
        val led = TaskKeeper.Ledger({ store["l"] }, { store["l"] = it })
        val t = TaskKeeper.Task("main", 7, "remind me at 9", 1_000)
        led.add(t); led.add(t)
        assertEquals(1, led.all().size)                                   // a second ack for the same message isn't a new task
        assertEquals(TaskKeeper.Verdict.ANSWERED, TaskKeeper.verdict(t, answered = true, cloudPending = true, now = 2_000))
        assertEquals(TaskKeeper.Verdict.WAIT, TaskKeeper.verdict(t, answered = false, cloudPending = true, now = 2_000))
        assertEquals(TaskKeeper.Verdict.REPORT, TaskKeeper.verdict(t, answered = false, cloudPending = false, now = 2_000))
        assertEquals(TaskKeeper.Verdict.DROP, TaskKeeper.verdict(t, false, false, now = 1_000 + CloudRuns.KEEP_MS + 1))
        val line = TaskKeeper.stoppedLine("remind me at 9")
        assertTrue(line.contains("stopped before I could confirm"))
        assertFalse(line.contains("\u2713")); assertFalse(Ack.safe(line) && line.contains("Done"))
        led.remove("main", 7)
        assertTrue(led.all().isEmpty())
        assertTrue(TaskKeeper.Codec.decode("not json").isEmpty())
    }

    @Test fun aRunWaitingForThePhoneWhenKilledIsReportedNotDone() = runBlocking {
        // The pick-up after a restart: a run left waiting on the phone comes back Interrupted (the CUT_SHORT line).
        gw.brain = { r, res ->
            if (res == null) {
                r.pending = JSONArray().put(JSONObject().put("id", "c1").put("type", "function").put("function", JSONObject().put("name", "set_alarm").put("arguments", "{}")))
                r.emit("tool_calls", JSONObject().put("step", 0).put("where", "device").put("calls", r.pending))
                r.emit("status", JSONObject().put("status", "needs_device"))
            }
        }
        val t = CloudRuns.Turn("main", "t-ack-3")
        val m = client.complete(t, user("set an alarm for 7"), JSONArray().put(JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", "set_alarm").put("parameters", JSONObject()))), "agent", ev)
        assertTrue(m.has("tool_calls"))
        CloudRuns.owned.clear()                                           // the process died
        val e = client.store.all().single()
        val r = client.resume(e)
        assertTrue(r is CloudRuns.Resumed.Interrupted)
        assertFalse(CloudRuns.CUT_SHORT.contains("\u2713"))
    }

    // ---- no working notification, ever ----

    @Test fun noWorkingNotification() {
        assertFalse(com.blaze.assistant.notify.Notifier.WORKING_NOTE)
        com.blaze.assistant.notify.Notifier.resetStatus()
        com.blaze.assistant.notify.Notifier.working("Opening YouTube")
        com.blaze.assistant.notify.Notifier.working("Thinking about: remind me")
        val i = com.blaze.assistant.notify.Notifier.statusInput()
        assertFalse(i.busy)
        assertNull(com.blaze.assistant.notify.StatusNote.line(i))
        assertTrue(com.blaze.assistant.notify.MessageThread.KEEP in 2..8)
        assertFalse(com.blaze.assistant.notify.MessageThread.FAILED_TITLE.contains("Done"))
        assertEquals("Blaze", QuietChannel.TITLE)                          // the only background note (old Android): no "working" words
    }
}
