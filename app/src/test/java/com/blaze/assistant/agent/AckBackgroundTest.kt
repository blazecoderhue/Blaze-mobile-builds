package com.blaze.assistant.agent

import com.blaze.assistant.notify.MessageThread
import com.blaze.assistant.notify.Notifier
import com.blaze.assistant.notify.StatusNote
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ack now, work in the background, report when done (HQ 22 Oct 20:35 / 20:36). */
class AckBackgroundTest {

    private val greetings = listOf("hi", "Hey Blaze", "hello!", "good morning", "thanks", "thank you", "ok", "lol", "how are you?",
        "what's up", "gn", "yo halo", "bye", "nice", "cool")

    private val tasks = listOf(
        "remind me to call mum at 9:00 tomorrow", "set an alarm for 6:30 am", "set a timer for 10 minutes", "remind me in 20 minutes to check the oven",
        "send John a message saying I'm late", "book a table for two on Friday", "download this song", "play some lofi", "turn on wifi",
        "create a note called groceries", "delete my 7am alarm", "research the best budget phones", "write an email to my landlord",
        "schedule a reminder every Monday at 8", "translate this to French", "summarise this article", "export my music to Downloads",
        "find me a cheap flight to Lusaka", "wake me up at 5", "add milk to my shopping list")

    @Test fun greetingsGetNoAck() {
        for (g in greetings) { assertFalse(g, Ack.wants(g)); assertNull(g, Ack.local(g)) }
        assertNull(Ack.local("[Scheduled routine] morning brief"))
        assertNull(Ack.local("/clear"))
    }

    @Test fun reminderAckNamesTheTimeAndNeverSaysDone() {
        assertEquals("Got it, setting that reminder for 9:00 tomorrow. I'll confirm when it's set.", Ack.local("remind me to call mum at 9:00 tomorrow"))
        assertEquals("Got it, setting that alarm for 6:30 am. I'll confirm when it's set.", Ack.local("set an alarm for 6:30 am"))
        assertEquals("Got it, starting that timer. I'll confirm when it's running.", Ack.local("set a timer for 10 minutes"))
        assertEquals("Got it, setting that reminder in 20 minutes. I'll confirm when it's set.", Ack.local("remind me in 20 minutes to check the oven"))
        assertEquals("for Friday", Ack.whenPart("book a table on friday"))
    }

    @Test fun everyTaskGetsOneSafeAck() {
        for (t in tasks) {
            val a = Ack.local(t)
            assertTrue(t, a != null)
            assertTrue(a!!, Ack.safe(a))
            assertFalse(a, a.contains("Done") || a.contains("\u2713"))
            assertTrue(Ack.chip(t).endsWith("in progress"))
        }
    }

    @Test fun safeRejectsDoneClaims() {
        for (bad in listOf("Done! Reminder set for 9.", "I've set your reminder for 9:00.", "Your alarm is set.", "All set \u2713",
            "Message sent successfully.", "I booked the table.", "The timer is now running and has been started. Completed.", "Reminder has been scheduled."))
            assertFalse(bad, Ack.safe(bad))
        for (ok in listOf("Got it, setting that reminder for 9:00 tomorrow. I'll confirm when it's set.", "On it \u2014 I'll tell you once it's sent.",
            "Looking into flights now. I'll message you when I have options."))
            assertTrue(ok, Ack.safe(ok))
        assertFalse(Ack.safe(""))
        assertFalse(Ack.safe("x".repeat(300)))
    }

    @Test fun oneAckPerTurn() {
        val g = Ack.Gate()
        assertFalse(g.take("Done, reminder set."))   // unsafe: dropped, and the gate stays open
        assertTrue(g.take("Got it, on it. I'll confirm when it's set."))
        assertFalse(g.take("Halo here, I'll handle it."))   // Halo / the gateway after the local ack: dropped
        assertTrue(g.acked)
        val late = Ack.Gate(); late.finished = true
        assertFalse(late.take("Got it."))   // the answer came first: no ack after it
    }

    @Test fun doneReceiptOnlyWhenVerified() {
        assertEquals("Done \u2713 Reminder set for Fri 9:00.", Ack.receipt("Reminder set for Fri 9:00.", verified = 1, unproven = 0, rewrote = false))
        assertEquals("I tried, but couldn't confirm it.", Ack.receipt("I tried, but couldn't confirm it.", 1, 1, false))
        assertEquals("Here's what I found.", Ack.receipt("Here's what I found.", 0, 0, false))
        assertEquals("Reminder set.", Ack.receipt("Reminder set.", 1, 0, rewrote = true))
        assertEquals("Done \u2713 x", Ack.receipt("Done \u2713 x", 1, 0, false))
    }

    @Test fun noWorkingNotificationEver() {
        assertFalse(Notifier.WORKING_NOTE)
        Notifier.resetStatus()
        Notifier.working("Opening YouTube")
        Notifier.working("Thinking about: hi")
        assertFalse(Notifier.statusInput().busy)
        assertNull(StatusNote.line(Notifier.statusInput()))
        assertFalse(MessageThread.FAILED_TITLE.contains("done", ignoreCase = true))
        assertEquals("Blaze", QuietChannel.TITLE)
    }

    // ---------------- gateway `ack` event ----------------

    private val kv = HashMap<String, String>()
    private var clock = 1_000_000L

    @Test fun gatewayAckArrivesFirstAndOnlyOnce() = runBlocking {
        CloudRuns.owned.clear()
        val gw = CloudRunsTest.FakeGateway()
        gw.brain = { r, _ ->
            r.emit("status", JSONObject().put("status", "queued"))
            r.emit("ack", JSONObject().put("text", "Got it, setting that reminder for 9:00 tomorrow. I'll confirm when it's set."))
            r.emit("ack", JSONObject().put("text", "Second ack"))
            r.emit("status", JSONObject().put("status", "running"))
            gw.done(r, "Reminder set for Fri 9:00.")
        }
        val client = CloudRuns.Client(gw, CloudRuns.RunStore({ kv[it] }, { k, v -> kv[k] = v }, { clock }), sleep = { clock += it }, now = { clock }, detached = { f -> runBlocking { f() } })
        val order = mutableListOf<String>()
        val ev = object : CloudRuns.Events {
            override fun ack(text: String) { order += "ack:$text" }
            override fun text(t: String) { order += "text" }
        }
        val t = CloudRuns.Turn("main", "t-ack-1")
        val m = client.complete(t, JSONArray().put(JSONObject().put("role", "user").put("content", "remind me at 9 tomorrow")), null, "quick", ev)
        assertEquals("Reminder set for Fri 9:00.", m.getString("content"))
        assertEquals(1, order.count { it.startsWith("ack:") })
        assertTrue(order.first().startsWith("ack:Got it"))
        assertTrue(t.acked)
        assertEquals("hello", CloudRuns.ackText(JSONObject().put("message", " hello ")))
    }

    @Test fun reducerKeepsTheFirstAck() {
        val r = CloudRuns.Reducer()
        r.apply(JSONObject().put("seq", 1).put("type", "ack").put("text", "one"))
        r.apply(JSONObject().put("seq", 2).put("type", "ack").put("text", "two"))
        assertEquals("one", r.ack)
    }

    // ---------------- killed mid-task ----------------

    @Test fun ledgerSurvivesAndSettlesHonestly() {
        var store: String? = null
        val led = TaskKeeper.Ledger({ store }, { store = it })
        led.add(TaskKeeper.Task("main", 7, "remind me at 9", 1000))
        led.add(TaskKeeper.Task("main", 7, "remind me at 9", 1001))   // same task again: one entry
        led.add(TaskKeeper.Task("p1", 3, "download it", 1000))
        assertEquals(2, TaskKeeper.Ledger({ store }, { store = it }).all().size)   // a fresh process reads it back
        led.remove("main", 7)
        assertEquals(listOf("p1"), led.all().map { it.chat })
        val t = TaskKeeper.Task("main", 7, "remind me at 9", 1000)
        assertEquals(TaskKeeper.Verdict.ANSWERED, TaskKeeper.verdict(t, answered = true, cloudPending = true, now = 2000))
        assertEquals(TaskKeeper.Verdict.WAIT, TaskKeeper.verdict(t, false, true, 2000))
        assertEquals(TaskKeeper.Verdict.REPORT, TaskKeeper.verdict(t, false, false, 2000))
        assertEquals(TaskKeeper.Verdict.DROP, TaskKeeper.verdict(t, false, false, 1000 + CloudRuns.KEEP_MS + 1))
        val line = TaskKeeper.stoppedLine("remind me to call mum at 9:00 tomorrow about the tickets and the bus and everything else")
        assertFalse(line.contains("Done") || line.contains("\u2713"))
        assertTrue(line.contains("stopped before I could confirm"))
        assertTrue(TaskKeeper.Codec.decode("garbage").isEmpty())
    }

    // ---------------- gateway 0.8.38 contract (files/from-site/RUNS_ACK_CONTRACT.md) ----------------

    @Test fun contractShapes() {
        val head = JSONObject().put("run_id", "run_1").put("status", "queued")
            .put("ack", JSONObject().put("intent", "reminder").put("line", "Got it, setting that reminder for 9:00 tomorrow. I'll confirm when it's set."))
        assertEquals("Got it, setting that reminder for 9:00 tomorrow. I'll confirm when it's set.", CloudRuns.ackText(head))
        assertEquals("", CloudRuns.ackText(JSONObject().put("run_id", "run_1").put("ack", JSONObject.NULL)))
        assertEquals("x", CloudRuns.ackText(JSONObject().put("type", "ack").put("line", "x")))
        CloudRuns.tz = { "Africa/Lusaka" }
        val body = CloudRuns.createBody("c1", JSONArray().put(JSONObject().put("role", "user").put("content", "remind me at 9")), null, "quick")
        assertEquals("Africa/Lusaka", body.getString("tz"))
    }

    @Test fun failedEventSaysTheHonestReason() = runBlocking {
        CloudRuns.owned.clear()
        val gw = CloudRunsTest.FakeGateway()
        gw.brain = { r, _ ->
            r.emit("failed", JSONObject().put("code", "models_busy").put("reason", "Every model is busy right now.").put("try", "Try again in a minute."))
            r.emit("status", JSONObject().put("status", "failed"))
        }
        val client = CloudRuns.Client(gw, CloudRuns.RunStore({ kv[it] }, { k, v -> kv[k] = v }, { clock }), sleep = { clock += it }, now = { clock }, detached = { f -> runBlocking { f() } })
        val msg = try { client.complete(CloudRuns.Turn("main", "t-f-1"), JSONArray().put(JSONObject().put("role", "user").put("content", "set an alarm")), null, "quick", object : CloudRuns.Events {}); "" }
            catch (e: LlmClient.LlmException) { e.message.orEmpty() }
        assertEquals("Every model is busy right now. Try again in a minute.", msg)
        assertFalse(msg.contains("Done"))
    }

    /** Strict 5/5: the whole ack contract, five times over (no state leaks between rounds). */
    @Test fun strictFiveOfFive() {
        repeat(5) { round ->
            val g = Ack.Gate()
            val a = Ack.local(tasks[round])!!
            assertTrue("round $round", g.take(a))
            assertFalse(g.take(a))
            assertTrue(Ack.safe(a))
            assertNull(Ack.local(greetings[round]))
            Notifier.working("step $round")
            assertNull(StatusNote.line(Notifier.statusInput()))
        }
    }
}
