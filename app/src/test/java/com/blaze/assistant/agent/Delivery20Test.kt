package com.blaze.assistant.agent

import com.blaze.assistant.ui.DeliveryView
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

/**
 * 0.45.20 delivery status (contract STEPS_DELIVERY_CONTRACT.md §1). Strict 5/5: every scenario runs 5 times on a fresh
 * fake gateway, fake clock and fresh tracker.
 */
class Delivery20Test {
    private fun five(f: () -> Unit) = repeat(5) { f() }

    private class World {
        val kv = HashMap<String, String>()
        var clock = 1_000_000L
        val waits = mutableListOf<Long>()
        val gw = CloudRunsTest.FakeGateway()
        val tracker = Delivery.Tracker({ kv[it] }, { k, v -> kv[k] = v })
        val raws = mutableListOf<String>()
        val states = mutableListOf<DeliveryState?>()
        var gids = listOf("g1")
        var sleepHook: (Long) -> Unit = {}
        val postTimes = mutableListOf<Long>()
        val http = object : CloudRuns.Http {
            override fun send(method: String, path: String, body: String?, headers: Map<String, String>): CloudRuns.Response {
                if (method == "POST" && path == "/runs") postTimes += clock
                return gw.send(method, path, body, headers)
            }
        }
        val client = CloudRuns.Client(http, CloudRuns.RunStore({ kv[it] }, { k, v -> kv[k] = v }, { clock }),
            sleep = { ms -> waits += ms; clock += ms; sleepHook(ms); yield() }, now = { clock }, detached = { f -> runBlocking { f() } })
        val ev = object : CloudRuns.Events {
            override fun raw(ev: JSONObject) {
                raws += ev.optString("type")
                Delivery.eventOf(ev)?.let { tracker.apply(gids, it) }
                states += tracker.state(gids[0])
            }
        }
        fun user(t: String) = JSONArray().put(JSONObject().put("role", "user").put("content", t))
        fun turn(): CloudRuns.Turn = CloudRuns.Turn("main", "t-main-1-1").also { it.gids = gids; it.firstCid = tracker.cidForSend(gids[0]) }
        /** The waits between create attempts. */
        fun backoffs(): List<Long> = postTimes.zipWithNext { a, b -> b - a }
    }

    @Test fun stateMachine_isMonotone_andReadNeverComesFromATimer() = five {
        var s = DeliveryState.SENDING
        s = Delivery.next(s, Delivery.Ev.NET_ERROR); assertEquals(DeliveryState.SENDING, s)
        s = Delivery.next(s, Delivery.Ev.POSTED); assertEquals(DeliveryState.SENT, s)
        s = Delivery.next(s, Delivery.Ev.NET_ERROR); assertEquals(DeliveryState.SENT, s)
        s = Delivery.next(s, Delivery.Ev.REJECTED); assertEquals("a late 4xx never un-sends", DeliveryState.SENT, s)
        s = Delivery.next(s, Delivery.Ev.READ); assertEquals(DeliveryState.READ, s)
        s = Delivery.next(s, Delivery.Ev.RECEIVED); assertEquals("Read stays Read", DeliveryState.READ, s)
        s = Delivery.next(s, Delivery.Ev.POSTED); assertEquals(DeliveryState.READ, s)
        assertEquals(DeliveryState.NOT_SENT, Delivery.next(DeliveryState.SENDING, Delivery.Ev.REJECTED))
        assertEquals(DeliveryState.NOT_SENT, Delivery.next(DeliveryState.SENDING, Delivery.Ev.STOPPED))
        assertEquals(DeliveryState.SENDING, Delivery.next(DeliveryState.NOT_SENT, Delivery.Ev.USER_RETRY))
        // The UI rule: Read only from a real reply / event; time passing (busy for minutes) never makes it Read.
        assertEquals(DeliveryState.SENT, DeliveryView.effective(DeliveryState.SENT, answered = false, busy = true))
        assertEquals(DeliveryState.READ, DeliveryView.effective(DeliveryState.SENT, answered = true, busy = false))
        assertEquals(DeliveryState.SENDING, DeliveryView.effective(null, answered = false, busy = true))
        assertEquals("a send left at Sending with nothing running is Not sent", DeliveryState.NOT_SENT, DeliveryView.effective(DeliveryState.SENDING, false, busy = false))
        assertEquals(DeliveryState.NOT_SENT, DeliveryView.effective(DeliveryState.NOT_SENT, false, busy = true))
        assertEquals(listOf("Sending", "Sent", "Read", "Not sent, tap to retry"), DeliveryState.values().map { Delivery.label(it) })
        // Unknown event types are ignored.
        assertNull(Delivery.eventOf(JSONObject().put("type", "something_new")))
    }

    @Test fun verdicts_andRetryAfter() = five {
        assertEquals(Delivery.Verdict.OK, Delivery.verdict(202))
        assertEquals(Delivery.Verdict.OK, Delivery.verdict(200))
        assertEquals(Delivery.Verdict.RETRY, Delivery.verdict(408))
        assertEquals(Delivery.Verdict.RETRY_AFTER, Delivery.verdict(429))
        assertEquals(Delivery.Verdict.REJECTED, Delivery.verdict(400))
        assertEquals(Delivery.Verdict.REJECTED, Delivery.verdict(422))
        for (c in listOf(404, 405, 501, 413, 401, 403, 500, 503)) assertEquals("$c keeps its old handling", Delivery.Verdict.OTHER, Delivery.verdict(c))
        assertEquals(7_000L, Delivery.retryAfterMs("7", 0))
        assertEquals(1_500L, Delivery.retryAfterMs("1.5", 0))
        assertEquals(Delivery.MAX_RETRY_AFTER_MS, Delivery.retryAfterMs("999999", 0))
        val now = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse("Fri, 23 Oct 2026 09:00:00 GMT")!!.time
        assertEquals(20_000L, Delivery.retryAfterMs("Fri, 23 Oct 2026 09:00:20 GMT", now))
        assertNull(Delivery.retryAfterMs("soon", 0)); assertNull(Delivery.retryAfterMs(null, 0))
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L), (0 until 8).map { Delivery.backoffMs(it) })
    }

    @Test fun offline_staysSending_backsOff_1_2_4_8_16_30_30_thenLandsOneMessage() = five {
        val w = World()
        w.gw.down = 8
        val t = w.turn()
        val m = runBlocking { w.client.complete(t, w.user("hi"), null, "chat", w.ev) }
        assertEquals("Hi", m.getString("content"))
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L), w.backoffs())
        // Sending the whole time it was offline: the first state change is Sent, after the 9th attempt.
        assertEquals(DeliveryState.SENT, w.states.first())
        assertEquals(9, w.gw.log.count { it == "POST /runs" })
        assertEquals("one message, one run", 1, w.gw.created)
        val cids = w.gw.bodies.filter { it.contains("client_msg_id") }.map { JSONObject(it).getString("client_msg_id") }.toSet()
        assertEquals("every attempt reused the bubble's id", setOf(t.firstCid), cids)
        assertTrue(t.waitedMs >= 121_000L)
    }

    @Test fun reconnect_retriesAtOnce() = five {
        val w = World()
        var wakes = 0L
        var slept = 0L
        runBlocking {
            // A 30 s backoff, the network comes back after 3 s: the wait ends there.
            Delivery.waitOrWake(30_000, { ms -> slept += ms; if (slept == 3_000L) wakes++ }, {}, { wakes })
        }
        assertEquals(3_000L, slept)
        // End to end: the app's reconnect hook (Delivery.reconnected) cuts the client's wait short.
        w.gw.down = 1000
        var total = 0L
        w.sleepHook = { ms -> total += ms; if (total == 20_000L) { w.gw.down = 0; Delivery.reconnected() } }
        val m = runBlocking { w.client.complete(w.turn(), w.user("hi"), null, "chat", w.ev) }
        assertEquals("Hi", m.getString("content"))
        // 1+2+4+8 = 15 s, then 5 s into the 16 s wait the network is back: the next try is at 20 s, not 31 s.
        assertTrue("${w.waits.sum()}", w.waits.sum() in 20_000L..21_000L)
        assertEquals(1, w.gw.created)
    }

    @Test fun answerLost_retrySameId_noDuplicate_andExistingRunReused() = five {
        val w = World()
        w.gw.loseNextCreate = true   // the create landed but its answer never came back
        val t = w.turn()
        runBlocking { w.client.complete(t, w.user("hi"), null, "chat", w.ev) }
        assertEquals("the retry found the same run", 1, w.gw.created)
        assertEquals(2, w.gw.log.count { it == "POST /runs" })
        assertEquals(1, w.raws.count { it == Delivery.POSTED })
        // A tap-to-retry later (the bubble Not sent) keeps the same id; an already-delivered one gets a new generation.
        val tr = Delivery.Tracker({ null }, { _, _ -> })
        val id1 = tr.cidForSend("gX"); tr.apply(listOf("gX"), Delivery.Ev.REJECTED)
        assertEquals(id1, tr.cidForSend("gX"))
        tr.apply(listOf("gX"), Delivery.Ev.POSTED)
        assertNotEquals(id1, tr.cidForSend("gX"))
    }

    @Test fun rejected4xx_isNotSent_noAutoRetry_noErrorBubbleNeeded() = five {
        val w = World()
        w.gw.createCode = 400
        try { runBlocking { w.client.complete(w.turn(), w.user("hi"), null, "chat", w.ev) }; fail("expected NotSent") }
        catch (e: Delivery.NotSent) { assertEquals(400, e.code) }
        assertEquals(1, w.gw.log.count { it == "POST /runs" })
        assertEquals(DeliveryState.NOT_SENT, w.tracker.state("g1"))
        assertTrue(w.waits.isEmpty())
    }

    @Test fun tooMany429_obeysRetryAfter() = five {
        val w = World()
        var first = true
        val http = object : CloudRuns.Http {
            override fun send(method: String, path: String, body: String?, headers: Map<String, String>): CloudRuns.Response {
                if (method == "POST" && path == "/runs" && first) { first = false
                    return CloudRuns.Response(429, "application/json", BufferedReader(StringReader("{}")), headers = mapOf("retry-after" to "7")) }
                return w.gw.send(method, path, body, headers)
            }
        }
        val c = CloudRuns.Client(http, CloudRuns.RunStore({ w.kv[it] }, { k, v -> w.kv[k] = v }, { w.clock }),
            sleep = { ms -> w.waits += ms; w.clock += ms }, now = { w.clock }, detached = { f -> runBlocking { f() } })
        runBlocking { c.complete(w.turn(), w.user("hi"), null, "chat", w.ev) }
        assertEquals(7_000L, w.waits.sum())
        assertEquals(1, w.gw.created)
    }

    @Test fun sentThenRead_fromEvents_andFromTheCreateAnswer() = five {
        val w = World()
        w.gw.brain = { r, _ ->
            r.emit("message_received", JSONObject().put("message_id", "msg_1").put("client_msg_id", r.cid))
            r.emit("status", JSONObject().put("status", "queued"))
            r.emit("message_read", JSONObject().put("message_id", "msg_1"))
            r.emit("status", JSONObject().put("status", "running"))
            w.gw.done(r, "Hello")
        }
        runBlocking { w.client.complete(w.turn(), w.user("hi"), null, "chat", w.ev) }
        val firstRead = w.states.indexOf(DeliveryState.READ)
        assertTrue(w.states.toString(), w.states.indexOf(DeliveryState.SENT) in 0 until firstRead)
        assertEquals(DeliveryState.READ, w.tracker.state("g1"))
        // A create / retry answer with read != null is Read at once; with read = null only Sent.
        val ra = Delivery.fromCreate(JSONObject().put("received", JSONObject().put("message_id", "m")).put("read", JSONObject().put("at_ms", 1)))
        assertEquals(listOf(Delivery.Ev.RECEIVED, Delivery.Ev.READ), ra)
        assertEquals(listOf(Delivery.Ev.RECEIVED), Delivery.fromCreate(JSONObject().put("received", JSONObject()).put("read", JSONObject.NULL)))
    }

    @Test fun stopWhileOffline_isNotSent_andTheTrackerSurvivesARestart() = five {
        val w = World()
        w.gw.down = 1000
        runBlocking {
            var job: kotlinx.coroutines.Job? = null
            w.sleepHook = { if (w.waits.size == 5) job?.cancel() }
            val d = async { w.client.complete(w.turn(), w.user("hi"), null, "chat", w.ev) }
            job = d
            try { d.await() } catch (_: kotlinx.coroutines.CancellationException) { }
        }
        assertEquals(DeliveryState.NOT_SENT, w.tracker.state("g1"))
        assertEquals(0, w.gw.created)
        val again = Delivery.Tracker({ w.kv[it] }, { k, v -> w.kv[k] = v })
        assertEquals(DeliveryState.NOT_SENT, again.state("g1"))
        assertEquals("tap to retry after a restart reuses the id", Delivery.cidFor("g1"), again.cidForSend("g1"))
    }

    @Test fun timedAfterSent_doesNotCountOfflineTime() = five {
        val t = CloudRuns.Turn("main", "x")
        val r = runBlocking {
            CloudRuns.timedAfterSent(t, 50) { kotlinx.coroutines.delay(120); t.sent.complete(Unit); kotlinx.coroutines.delay(10); "ok" }
        }
        assertEquals("the limit starts once sent", "ok", r)
        val t2 = CloudRuns.Turn("main", "y")
        val r2 = runBlocking { CloudRuns.timedAfterSent(t2, 30) { t2.sent.complete(Unit); kotlinx.coroutines.delay(2_000); "late" } }
        assertNull(r2)
    }

    @Suppress("unused") private fun io(): Nothing = throw IOException()
}
