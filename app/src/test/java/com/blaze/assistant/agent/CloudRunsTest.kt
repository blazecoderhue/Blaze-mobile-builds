package com.blaze.assistant.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import java.io.StringReader

/** v0.42.0 Cloud runs: the client against a fake gateway (same shapes as gateway/src/runs.ts), plus the pure parts. */
class CloudRunsTest {

    /** An in-memory gateway: runs, numbered events, SSE answers, and switches for cuts and dead connections. */
    class FakeGateway : CloudRuns.Http {
        class Run(val id: String, val cid: String, val messages: JSONArray) {
            val events = mutableListOf<JSONObject>()
            var status = "queued"
            var pending = JSONArray()
            var results: JSONArray? = null
            fun emit(type: String, data: JSONObject = JSONObject()): JSONObject {
                val ev = JSONObject(data.toString()).put("seq", events.size + 1).put("type", type)
                events += ev
                if (type == "status") status = data.optString("status")
                return ev
            }
        }

        val runs = LinkedHashMap<String, Run>()
        val log = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var createCode = 202
        /** The next N sends throw before anything arrives. */
        var down = 0
        /** The next create lands, but its answer is lost on the way back. */
        var loseNextCreate = false
        /** After the next stream answer, the network goes away for this many sends. */
        var downAfterNext = 0
        var created = 0
        /** The next stream answer stops after this many event frames, with no `end` (then an IOException). */
        var cutAfter = -1
        /** The model: called with the run on create (results = null) and after tool results. */
        var brain: (Run, JSONArray?) -> Unit = { r, _ -> r.emit("status", JSONObject().put("status", "running")); done(r, "Hi") }

        fun done(r: Run, reply: String) {
            r.emit("delta", JSONObject().put("step", 0).put("text", reply))
            r.emit("message", JSONObject().put("step", 0).put("message", JSONObject().put("role", "assistant").put("content", reply)))
            r.emit("done", JSONObject().put("reply", reply).put("provider", "p").put("model", "m"))
            r.emit("status", JSONObject().put("status", "done"))
        }

        private fun sse(head: JSONObject?, r: Run, after: Long): CloudRuns.Response {
            val sb = StringBuilder()
            if (head != null) sb.append("event: run\ndata: $head\n\n")
            var n = 0
            var cut = false
            for (ev in r.events.filter { it.getLong("seq") > after }) {
                if (cutAfter >= 0 && n == cutAfter) { cut = true; break }
                sb.append("id: ${ev.getLong("seq")}\nevent: ${ev.getString("type")}\ndata: $ev\n\n").append(": ping\n\n")
                n++
            }
            val ended = r.status in listOf("done", "failed", "cancelled", "needs_device")
            if (cut) cutAfter = -1 else if (ended) sb.append("event: end\ndata: {\"status\":\"${r.status}\"}\n\n")
            val text = sb.toString()
            if (downAfterNext > 0) { down = downAfterNext; downAfterNext = 0 }
            val reader: Reader = if (!cut) StringReader(text) else object : Reader() {
                val inner = StringReader(text)
                override fun read(cbuf: CharArray, off: Int, len: Int): Int { val k = inner.read(cbuf, off, len); if (k < 0) throw IOException("connection reset"); return k }
                override fun close() = inner.close()
            }
            return CloudRuns.Response(200, "text/event-stream", BufferedReader(reader))
        }

        private fun json(code: Int, o: JSONObject) = CloudRuns.Response(code, "application/json", BufferedReader(StringReader(o.toString())))

        override fun send(method: String, path: String, body: String?, headers: Map<String, String>): CloudRuns.Response {
            log += "$method $path" + (headers["Last-Event-ID"]?.let { " @$it" } ?: "")
            body?.let { bodies += it }
            if (down > 0) { down--; throw IOException("no network") }
            val m = Regex("^/runs/(run_[a-z0-9]+)(?:/(stream|tool_results|cancel))?(?:\\?after=(\\d+))?$").find(path)
            if (method == "POST" && path == "/runs") {
                if (createCode !in 200..299) return json(createCode, JSONObject().put("error", JSONObject().put("code", "nope")))
                val b = JSONObject(body!!)
                val cid = b.getString("client_msg_id")
                val existing = runs.values.firstOrNull { it.cid == cid }
                val r = existing ?: Run("run_" + "%024x".format(runs.size + 1), cid, b.getJSONArray("messages")).also {
                    runs[it.id] = it; created++; it.emit("status", JSONObject().put("status", "queued")); brain(it, null)
                }
                if (loseNextCreate) { loseNextCreate = false; throw IOException("answer lost") }
                return sse(JSONObject().put("run_id", r.id).put("status", r.status).put("existing", existing != null), r, 0)
            }
            if (m == null) return json(404, JSONObject())
            val r = runs[m.groupValues[1]] ?: return json(404, JSONObject().put("error", JSONObject().put("code", "no_run")))
            return when (m.groupValues[2]) {
                "stream" -> sse(null, r, headers["Last-Event-ID"]?.toLong() ?: 0)
                "tool_results" -> {
                    if (r.status != "needs_device") return json(409, JSONObject())
                    val res = JSONObject(body!!).getJSONArray("results")
                    r.results = res
                    val after = r.events.size.toLong()
                    r.emit("tool_results", JSONObject().put("step", 0).put("ids", JSONArray().put(res.getJSONObject(0).getString("tool_call_id"))))
                    r.emit("status", JSONObject().put("status", "queued"))
                    brain(r, res)
                    sse(JSONObject().put("run_id", r.id).put("status", r.status).put("already", false), r, after)
                }
                "cancel" -> { r.emit("status", JSONObject().put("status", "cancelled")); json(200, JSONObject().put("ok", true)) }
                else -> if (method == "DELETE") { runs.remove(r.id); json(200, JSONObject().put("ok", true)) }
                    else {
                        val after = m.groupValues[3].toLongOrNull() ?: 0
                        val o = JSONObject().put("status", r.status).put("events", JSONArray(r.events.filter { it.getLong("seq") > after }))
                        if (r.status == "done") o.put("reply", r.events.last { it.getString("type") == "done" }.getString("reply"))
                        if (r.status == "needs_device") o.put("pending_tool_calls", r.pending)
                        json(200, o)
                    }
            }
        }
    }

    private val kv = HashMap<String, String>()
    private var clock = 1_000_000L
    private lateinit var gw: FakeGateway
    private lateinit var client: CloudRuns.Client
    private val texts = mutableListOf<String>()
    private val lines = mutableListOf<String>()
    private val ev = object : CloudRuns.Events {
        override fun text(t: String) { texts += t }
        override fun status(line: String) { lines += line }
    }

    @Before fun setUp() {
        kv.clear(); texts.clear(); lines.clear(); CloudRuns.owned.clear()
        gw = FakeGateway()
        val store = CloudRuns.RunStore({ kv[it] }, { k, v -> kv[k] = v }, { clock })
        client = CloudRuns.Client(gw, store, sleep = { clock += it }, now = { clock }, detached = { f -> runBlocking { f() } })
    }

    private fun user(text: String) = JSONArray().put(JSONObject().put("role", "system").put("content", "sys")).put(JSONObject().put("role", "user").put("content", text))

    @Test fun lifecycle_createStreamDoneDelete() = runBlocking {
        gw.brain = { r, _ ->
            r.emit("status", JSONObject().put("status", "running"))
            r.emit("delta", JSONObject().put("step", 0).put("text", "Hel"))
            r.emit("delta", JSONObject().put("step", 0).put("text", "lo"))
            r.emit("done", JSONObject().put("reply", "Hello").put("provider", "p").put("model", "m"))
            r.emit("status", JSONObject().put("status", "done"))
        }
        val t = CloudRuns.Turn("main", "t-main-7-1")
        val m = client.complete(t, user("hi"), null, "quick", ev)
        assertEquals("Hello", m.getString("content"))
        assertFalse(m.has("tool_calls"))
        assertEquals(listOf("Hel", "Hello"), texts)
        assertEquals(CloudRuns.SENDING, lines.first())
        assertTrue(CloudRuns.QUEUED in lines && CloudRuns.RUNNING in lines)
        assertTrue(client.store.all().isEmpty())
        assertTrue(gw.log.any { it.startsWith("DELETE /runs/") })
        assertTrue(gw.runs.isEmpty())
        val sent = JSONObject(gw.bodies.first())
        assertEquals("t-main-7-1:1", sent.getString("client_msg_id"))
        assertEquals("quick", sent.getString("task"))
        assertFalse(sent.has("tools"))
        assertNull(t.runId)
    }

    @Test fun resumesAfterACutWithLastEventId() = runBlocking {
        gw.brain = { r, _ ->
            r.emit("status", JSONObject().put("status", "running"))
            for (w in listOf("one ", "two ", "three")) r.emit("delta", JSONObject().put("step", 0).put("text", w))
            r.emit("done", JSONObject().put("reply", "one two three"))
            r.emit("status", JSONObject().put("status", "done"))
        }
        gw.cutAfter = 3   // status queued, running, "one " … then the line drops
        gw.downAfterNext = 1   // and the first reconnect finds no network
        val m = client.complete(CloudRuns.Turn("main", "t-1"), user("count"), null, "chat", ev)
        assertEquals("one two three", m.getString("content"))
        assertTrue(gw.log.toString(), gw.log.any { it.endsWith("/stream @3") })
        assertTrue(CloudRuns.OFFLINE in lines)
        // Each piece applied once: no doubled words after the reconnect.
        assertEquals("one two three", texts.last())
        assertEquals(1, gw.log.count { it == "POST /runs" })
    }

    @Test fun resetDropsThatStepsPartialText() = runBlocking {
        gw.brain = { r, _ ->
            r.emit("delta", JSONObject().put("step", 0).put("text", "Half an ans"))
            r.emit("reset", JSONObject().put("step", 0))
            r.emit("delta", JSONObject().put("step", 0).put("text", "Whole answer."))
            r.emit("done", JSONObject().put("reply", "Whole answer."))
            r.emit("status", JSONObject().put("status", "done"))
        }
        val t = CloudRuns.Turn("main", "t-2")
        val m = client.complete(t, user("q"), null, "chat", ev)
        assertEquals(listOf("Half an ans", "", "Whole answer."), texts)
        assertEquals("Whole answer.", m.getString("content"))
        assertTrue(t.streaming)
    }

    @Test fun needsDeviceRoundTrip() = runBlocking {
        val call = JSONObject().put("id", "c1").put("type", "function").put("function", JSONObject().put("name", "battery").put("arguments", "{}"))
        gw.brain = { r, res ->
            r.emit("status", JSONObject().put("status", "running"))
            if (res == null) {
                r.emit("delta", JSONObject().put("step", 0).put("text", "Checking."))
                r.pending = JSONArray().put(call)
                r.emit("tool_calls", JSONObject().put("step", 0).put("calls", r.pending).put("where", "device"))
                r.emit("status", JSONObject().put("status", "needs_device"))
            } else {
                r.emit("delta", JSONObject().put("step", 1).put("text", "Battery is at 81%."))
                r.emit("done", JSONObject().put("reply", "Battery is at 81%."))
                r.emit("status", JSONObject().put("status", "done"))
            }
        }
        val t = CloudRuns.Turn("main", "t-3")
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "battery")))
        val msgs = user("battery?")
        val first = client.complete(t, msgs, tools, "agent", ev)
        assertEquals("Checking.", first.getString("content"))
        assertEquals("c1", first.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals(CloudRuns.ON_PHONE, lines.last())
        val saved = client.store.get(t.runId!!)!!
        assertEquals("needs_device", saved.status)
        assertEquals(listOf("c1"), saved.pending)
        assertTrue(JSONObject(gw.bodies.first()).has("tools"))
        // The agent runs the tool and asks again: the results go to the same run.
        msgs.put(first).put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "81%"))
        val second = client.complete(t, msgs, tools, "agent", ev)
        assertEquals("Battery is at 81%.", second.getString("content"))
        assertEquals(1, gw.log.count { it == "POST /runs" })
        assertTrue(gw.log.any { it.endsWith("/tool_results") })
        assertEquals("81%", gw.bodies.map { JSONObject(it) }.first { it.has("results") }.getJSONArray("results").getJSONObject(0).getString("content"))
        assertEquals("Battery is at 81%.", texts.last())
    }

    @Test fun anotherWayCancelsTheWaitingRunAndStartsANewOne() = runBlocking {
        val call = JSONObject().put("id", "c9").put("type", "function").put("function", JSONObject().put("name", "x").put("arguments", "{}"))
        gw.brain = { r, _ ->
            if (r.cid.endsWith(":1")) { r.pending = JSONArray().put(call); r.emit("tool_calls", JSONObject().put("step", 0).put("calls", r.pending)); r.emit("status", JSONObject().put("status", "needs_device")) }
            else gw.done(r, "Fresh")
        }
        val t = CloudRuns.Turn("main", "turn-4")
        val msgs = user("do x")
        client.complete(t, msgs, null, "agent", ev)
        val firstRun = t.runId!!
        msgs.put(JSONObject().put("role", "user").put("content", "actually, never mind"))
        val m = client.complete(t, msgs, null, "chat", ev)
        assertEquals("Fresh", m.getString("content"))
        assertTrue(gw.log.contains("POST /runs/$firstRun/cancel"))
        assertEquals("turn-4:2", JSONObject(gw.bodies.last { it.contains("client_msg_id") }).getString("client_msg_id"))
    }

    @Test fun idempotentResendGetsTheSameRun() = runBlocking {
        gw.loseNextCreate = true
        val m = client.complete(CloudRuns.Turn("main", "turn-5"), user("hi"), null, "chat", ev)
        assertEquals("Hi", m.getString("content"))
        assertEquals(1, gw.created)
        val cids = gw.bodies.filter { it.contains("client_msg_id") }.map { JSONObject(it).getString("client_msg_id") }
        assertEquals(listOf("turn-5:1", "turn-5:1"), cids)
        assertEquals(1, gw.log.count { it.startsWith("DELETE") })
    }

    @Test fun olderGatewayFallsBackAndRemembers() = runBlocking {
        gw.createCode = 404
        try { client.complete(CloudRuns.Turn("main", "t-6"), user("hi"), null, "chat", ev); fail("expected fallback") }
        catch (f: CloudRuns.Fallback) { assertTrue(f.why.contains("404")) }
        assertTrue(client.store.isOff())
        clock += CloudRuns.OFF_MS + 1
        assertFalse(client.store.isOff())
    }

    @Test fun unreachableBeforeARunFallsBackForThisTurn() = runBlocking {
        gw.down = 3
        try { client.complete(CloudRuns.Turn("main", "t-7"), user("hi"), null, "chat", ev); fail("expected fallback") }
        catch (_: CloudRuns.Fallback) { }
        assertFalse(client.store.isOff())
        gw.createCode = 503
        try { client.complete(CloudRuns.Turn("main", "t-7b"), user("hi"), null, "chat", ev); fail("expected fallback") }
        catch (_: CloudRuns.Fallback) { }
        assertFalse(client.store.isOff())
    }

    @Test fun longOfflineKeepsTheReplySafeAndThePickUpDeliversIt() = runBlocking {
        gw.brain = { r, _ -> r.emit("status", JSONObject().put("status", "running")); r.emit("delta", JSONObject().put("step", 0).put("text", "Par")) }
        // The first answer lands (cut after 2 frames), then the network goes away.
        gw.cutAfter = 2
        gw.downAfterNext = 1000
        client.maxOfflineMs = 60_000
        val t = CloudRuns.Turn("main", "t-8")
        try {
            client.complete(t, user("hi"), null, "chat", ev); fail("expected the safe line")
        } catch (e: LlmClient.LlmException) { assertEquals(CloudRuns.SAFE, e.message) }
        val saved = client.store.all().single()
        assertEquals("running", saved.status)
        // Back online: the run finished meanwhile; the pick-up gets the reply.
        gw.down = 0
        val run = gw.runs[saved.run]!!
        run.emit("delta", JSONObject().put("step", 0).put("text", "t"))
        run.emit("done", JSONObject().put("reply", "Part"))
        run.emit("status", JSONObject().put("status", "done"))
        CloudRuns.owned.clear()
        val r = client.resume(saved)
        assertEquals(CloudRuns.Resumed.Reply("Part"), r)
        assertTrue(client.store.all().isEmpty())
    }

    @Test fun pickUpCancelsARunThatWaitedForThePhone() = runBlocking {
        val e = CloudRuns.RunStore.Entry("run_000000000000000000000001", "t:1", "main", 4, "needs_device", listOf("c1"))
        client.store.save(e)
        assertEquals(CloudRuns.Resumed.Interrupted, client.resume(e))
        assertTrue(gw.log.contains("POST /runs/${e.run}/cancel"))
        assertTrue(client.store.all().isEmpty())
    }

    @Test fun failedRunsBecomePlainLinesOrTheLocalLoop() = runBlocking {
        gw.brain = { r, _ -> r.emit("error", JSONObject().put("code", "models_busy").put("message", "x")); r.emit("status", JSONObject().put("status", "failed")) }
        try { client.complete(CloudRuns.Turn("main", "t-9"), user("hi"), null, "chat", ev); fail() }
        catch (e: LlmClient.LlmException) { assertEquals(CloudRuns.BUSY_ALL, e.message) }
        gw.brain = { r, _ -> r.emit("error", JSONObject().put("code", "model_rejected").put("message", "x")); r.emit("status", JSONObject().put("status", "failed")) }
        try { client.complete(CloudRuns.Turn("main", "t-10"), user("hi"), null, "chat", ev); fail() }
        catch (_: CloudRuns.Fallback) { }
        assertEquals(413, CloudRuns.failure("too_large")!!.code)
        assertNull(CloudRuns.failure("internal_error"))
    }

    // ---------------- pure parts ----------------

    @Test fun sseFrames() {
        val p = CloudRuns.Sse()
        val got = mutableListOf<CloudRuns.Frame>()
        for (l in listOf("event: run", "data: {\"run_id\":\"r\"}", "", ": ping", "", "id: 7", "event: delta", "data: {\"a\":", "data: 1}", "", "event: end", "data: {}", "\r"))
            p.line(l)?.let { got += it }
        assertEquals(3, got.size)
        assertEquals(CloudRuns.Frame(null, "run", "{\"run_id\":\"r\"}"), got[0])
        assertEquals(CloudRuns.Frame(7L, "delta", "{\"a\":\n1}"), got[1])
        assertEquals("end", got[2].event)
    }

    @Test fun reducerIgnoresRepeatsAndKeepsStepsApart() {
        val r = CloudRuns.Reducer()
        fun e(seq: Int, type: String, vararg kv: Pair<String, Any>) = JSONObject().put("seq", seq).put("type", type).apply { kv.forEach { put(it.first, it.second) } }
        assertTrue(r.apply(e(1, "delta", "step" to 0, "text" to "a")))
        assertFalse(r.apply(e(1, "delta", "step" to 0, "text" to "a")))
        r.apply(e(2, "delta", "step" to 1, "text" to "b"))
        assertEquals("ab", r.text)
        r.apply(e(3, "reset", "step" to 1))
        assertEquals("a", r.text)
        r.apply(e(4, "status", "status" to "waiting", "retry_in_ms" to 4200))
        assertEquals("Busy right now, trying again in 5s\u2026", CloudRuns.line(r.status, r.retryInMs))
        r.apply(e(5, "tool_calls", "step" to 0, "calls" to JSONArray(), "where" to "cloud"))
        assertNull(r.calls)
        r.window()
        assertEquals("", r.text)
        assertEquals(5, r.lastSeq)
    }

    @Test fun toolResultsMatchOnlyWhenNewest() {
        val m = JSONArray().put(JSONObject().put("role", "user").put("content", "x"))
            .put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "a").put("content", "1"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "b").put("content", "2"))
        val res = CloudRuns.resultsFor(m, listOf("a", "b"))!!
        assertEquals("1", res.getJSONObject(0).getString("content"))
        assertEquals("b", res.getJSONObject(1).getString("tool_call_id"))
        assertNull(CloudRuns.resultsFor(m, listOf("a", "c")))
        m.put(JSONObject().put("role", "user").put("content", "a screenshot"))
        assertNull(CloudRuns.resultsFor(m, listOf("a", "b")))
        assertNull(CloudRuns.resultsFor(m, emptyList()))
    }

    @Test fun clientIdsAreSafe() {
        assertEquals("t-main_chat-12:1", CloudRuns.safeId("t-main chat-12:1"))
        assertEquals(4, CloudRuns.safeId("a").length)
        assertTrue(CloudRuns.safeId("x".repeat(300)).length <= 128)
        assertTrue(CloudRuns.noRuns(404) && CloudRuns.noRuns(405) && !CloudRuns.noRuns(503))
    }

    @Test fun storeSurvivesAReload() {
        val s1 = CloudRuns.RunStore({ kv[it] }, { k, v -> kv[k] = v }, { clock })
        s1.save(CloudRuns.RunStore.Entry("run_1", "c:1", "main", 3, "running"))
        s1.update("run_1") { it.copy(seq = 9) }
        val s2 = CloudRuns.RunStore({ kv[it] }, { k, v -> kv[k] = v }, { clock })
        assertEquals(9L, s2.get("run_1")!!.seq)
        clock += CloudRuns.KEEP_MS + 1
        assertTrue(s2.all().isEmpty())
    }

    // ---------------- Idempotency-Key on plain chat completions ----------------

    @Test fun plainCallsResendTheSameKeyOnlyWhenUnanswered() = runBlocking {
        val keys = mutableListOf<String>()
        var n = 0
        val m = Cloud.drive(true, call = { a ->
            keys += a.key
            when (n++) {
                0 -> throw LlmClient.LlmException("couldn't reach", 0, unanswered = true)
                1 -> JSONObject().put("role", "assistant").put("content", "")   // empty: a new request next
                else -> JSONObject().put("role", "assistant").put("content", "ok")
            }
        }, served = { "" }, sleep = {}, keyBase = "kb")
        assertEquals("ok", m.getString("content"))
        assertEquals(listOf("kb-1", "kb-1", "kb-2"), keys)
    }

    @Test fun inProgressIsWorthWaitingFor() {
        assertTrue(Cloud.retryable(409, "still being answered [in_progress]"))
        assertFalse(Cloud.retryable(409, "conflict"))
    }

    // ---------------- prompt slimming ----------------

    @Test fun awarenessSummaryIsShortAndFullOnlyWhenRelevant() {
        val now = 10_000_000L
        val nows = (1..5).map { Awareness.Now("k$it", "Playing", "Song number $it with a fairly long title ".repeat(3), "Blaze Music", at = now - it) }
        val evs = (1..9).map { Awareness.Event("did", "Opened something interesting number $it", now - it * 60_000L) }
        val full = Awareness.format(nows, evs, now)
        val short = Awareness.summary(nows, evs, now)
        assertTrue(short.length <= 480)
        assertTrue(short.length < full.length)
        assertTrue(short.lines().filter { it.isNotBlank() }.size <= 3)
        assertEquals("", Awareness.summary(emptyList(), emptyList(), now))
        assertFalse(Awareness.needsFull("what is the capital of France", setOf("google")))
        assertTrue(Awareness.needsFull("who sings this song", emptySet()))
        assertTrue(Awareness.needsFull("hello", setOf("screen")))
        assertNotNull(short)
    }
}
