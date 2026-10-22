package com.blaze.assistant.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * v0.45.8 before/after bench of the start of a reply: Send tap → working state set, and Send tap → the chat request's
 * first byte on the wire, for the OLD send path (0.30.6 burst wait, the on-device model in front of every short message
 * online, the session token decrypted per request) and the NEW one.
 *
 * The gateway is a fake on a local socket (the Android test classpath has no MockWebServer); it notes when the first byte
 * of each request arrives. The slow prep is faked: [FAKE_MODEL_MS] stands for the on-device fast-lane model (process start
 * plus inference; the real one can take seconds), [FAKE_DECRYPT_MS] for an Android Keystore decrypt of the session token.
 * The routing, the burst wait, FastLane.useModel, QuickRules and StartTrace are the real code. The working mark is when the
 * state is set; on a phone it is drawn on the next frame (~16 ms at 60 Hz), which only the What-happened sheet can show.
 * The numbers are written to app/build/bench/start-0458.txt.
 */
class SendPathTest {
    private lateinit var server: ServerSocket
    @Volatile private var firstByteAt = -1L
    private var port = 0

    @Before fun up() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { try { sock.use { serve(it) } } catch (_: Exception) { } }
            }
        }
    }

    @After fun down() { RapidMessages.START_NOW = true; StartTrace.reset(); try { server.close() } catch (_: Exception) { } }

    private fun serve(sock: Socket) {
        val raw = sock.getInputStream()
        val b = raw.read()
        if (b < 0) return
        if (firstByteAt < 0) firstByteAt = System.nanoTime()
        val inp = raw.bufferedReader(Charsets.ISO_8859_1)
        var len = 0
        while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("Content-Length:", true)) len = l.substringAfter(':').trim().toInt() }
        repeat(len) { inp.read() }
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: [DONE]\n\n".toByteArray()
        sock.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            write(body); flush()
        }
    }

    class Run(val workingMs: Double, val wireMs: Double, val serverMs: Double)

    private var memoToken: String? = null

    /** One typed Send of [text] (burst = typed in the chat), through the old or the new path. */
    /** v0.45.9: when set, the [context] block is fetched (cache) and put in the request body, as Agent.requestMessages does. */
    private var contextFor: ((String) -> String)? = null

    private fun send(old: Boolean, text: String, modelReady: Boolean = true, offline: Boolean = false, coldToken: Boolean = false): Run {
        RapidMessages.START_NOW = !old
        if (old || coldToken) memoToken = null
        firstByteAt = -1
        StartTrace.reset()
        StartTrace.tap(force = true)
        val tap = StartTrace.cur!!.tap
        var working = false
        fun startTurn() {
            if (!working) { working = true; StartTrace.visible() }
            // The fast lane: the instant rules (real), then the on-device model (faked) when the path lets it run.
            val rule = QuickRules.parse(text)
            assertEquals("bench message must fall through the rules", null, rule)
            val model = if (old) modelReady else FastLane.useModel(offline) && modelReady
            if (model) Thread.sleep(FAKE_MODEL_MS)
            // The session token: decrypted per request before, kept in memory now.
            val token = if (old) decrypt() else (memoToken ?: decrypt().also { memoToken = it })
            val c = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json")
            ReplyLanguage.header("fr")?.let { (k, v) -> c.setRequestProperty(k, v) }
            val ctxMsg = contextFor?.let { f -> "{\"role\":\"system\",\"content\":" + org.json.JSONObject.quote(f(text)) + "}," }.orEmpty()
            val bytes = "{\"model\":\"blaze\",\"stream\":true,\"messages\":[$ctxMsg{\"role\":\"user\",\"content\":\"$text\"}]}".toByteArray()
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { StartTrace.wire(); it.write(bytes) }
            assertEquals(200, c.responseCode)
            StartTrace.headers()
            c.inputStream.use { it.readBytes() }
            StartTrace.first()
            c.disconnect()
        }
        when (RapidMessages.route(busy = false, waiting = false, burst = true)) {
            RapidMessages.Route.NOW -> { working = true; StartTrace.visible(); startTurn() }
            RapidMessages.Route.BURST -> {
                // The 0.30.6 burst: the orb at once, then wait for more (the user isn't typing again here: GRACE_MS).
                working = true; StartTrace.visible()
                val burstAt = System.currentTimeMillis()
                while (true) { val w = RapidMessages.burstWait(System.currentTimeMillis(), burstAt, 0L, false); if (w <= 0) break; Thread.sleep(w) }
                startTurn()
            }
            else -> error("unexpected route")
        }
        val m = StartTrace.take()!!
        fun ms(t: Long) = (t - tap) / 1e6
        return Run(ms(m.visible), ms(m.wire), ms(firstByteAt))
    }

    private fun decrypt(): String { Thread.sleep(FAKE_DECRYPT_MS); return "tok" }

    private fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]

    private fun bench(old: Boolean, modelReady: Boolean, coldToken: Boolean = false, n: Int = 5): Triple<Double, Double, Double> {
        send(old, WARM, modelReady)   // warm the JIT and the socket path
        val runs = (1..n).map { send(old, MSG, modelReady, coldToken = coldToken) }
        return Triple(median(runs.map { it.workingMs }), median(runs.map { it.wireMs }), median(runs.map { it.serverMs }))
    }

    @Test fun startOfReplyBeforeAfter() {
        val oldModel = bench(old = true, modelReady = true)
        val oldNoModel = bench(old = true, modelReady = false)
        val newModel = bench(old = false, modelReady = true)
        val newNoModel = bench(old = false, modelReady = false)
        val newCold = bench(old = false, modelReady = true, coldToken = true)
        val now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        fun row(name: String, r: Triple<Double, Double, Double>) = "%-56s working %7.1f ms   on the wire %7.1f ms (fake gateway got byte 1 at %7.1f ms)".format(java.util.Locale.US, name, r.first, r.second, r.third)
        val text = listOf(
            "Blaze Android 0.45.8 start-of-reply bench (SendPathTest, JVM, $now)",
            "Median of 5 typed Sends after one warm-up. Fake gateway on 127.0.0.1; fake slow prep: on-device model $FAKE_MODEL_MS ms, token decrypt $FAKE_DECRYPT_MS ms.",
            "Real code: RapidMessages.route/legacyRoute/burstWait, FastLane.useModel, QuickRules.parse, StartTrace, ReplyLanguage.header.",
            "Working = state set (a phone draws it on the next frame). Phone numbers come from the What-happened sheet, not from this bench.",
            "",
            row("OLD (0.45.7) fast-lane model downloaded", oldModel),
            row("OLD (0.45.7) no fast-lane model", oldNoModel),
            row("NEW (0.45.8) fast-lane model downloaded", newModel),
            row("NEW (0.45.8) no fast-lane model", newNoModel),
            row("NEW (0.45.8) first Send after app start (token decrypt)", newCold),
            "",
            "Targets: working < ${StartTrace.VISIBLE_TARGET_MS} ms, on the wire < ${StartTrace.WIRE_TARGET_MS} ms.",
        ).joinToString("\n") + "\n"
        val dir = File(System.getProperty("user.dir"), "build/bench").apply { mkdirs() }
        File(dir, "start-0458.txt").writeText(text)
        println(text)
        for (r in listOf(newModel, newNoModel, newCold)) {
            assertTrue("working ${r.first} ms", r.first < StartTrace.VISIBLE_TARGET_MS)
            assertTrue("on the wire ${r.second} ms", r.second < StartTrace.WIRE_TARGET_MS)
            assertTrue("server got it ${r.third} ms", r.third < StartTrace.WIRE_TARGET_MS)
        }
        // The old path waited at least the burst grace before the request left.
        assertTrue(oldNoModel.second >= RapidMessages.GRACE_MS)
        assertTrue(oldModel.second >= RapidMessages.GRACE_MS + FAKE_MODEL_MS)
    }

    /**
     * v0.45.9: the [context] block on the send path. Warm = the block was built in the background (typing / app open) and
     * the send only reads the 60 s cache; cold = the send builds it itself (first send, or right after a change). The
     * build is local only (fixture schedule here; on the phone: routines, alarms, calendar query, cached Home weather).
     */
    @Test fun contextBlockKeepsTheWireFast() {
        val zone = java.time.ZoneId.of("Africa/Lusaka")
        val fixture = listOf(
            ScheduleDay.Entry("Church 9:00\u201312:30", "reminder", ScheduleDay.Rule.Weekly(64, java.time.LocalTime.of(8, 30))),
            ScheduleDay.Entry("CIT class", "reminder", ScheduleDay.Rule.Weekly(1 or 4, java.time.LocalTime.of(8, 0))),
            ScheduleDay.Entry("NET class", "reminder", ScheduleDay.Rule.Weekly(2 or 4, java.time.LocalTime.of(14, 0))))
        val cache = TurnContext.Cache()
        val now = java.time.ZonedDateTime.of(2026, 10, 21, 10, 15, 0, 0, zone)
        fun build(text: String): String { val d = ScheduleDay.datesAsked(text, now.toLocalDate())
            return TurnContext.block(TurnContext.Inputs(now, "29\u00b0C Clear, Ndola (Open-Meteo \u00b7 10:00)", fixture, d, listOf("reply to Stefano about Thursday 9:00"), null)) }
        fun cached(text: String): String = cache.get(TurnContext.key(now, ScheduleDay.datesAsked(text, now.toLocalDate())), System.currentTimeMillis()) { build(text) }
        val base = bench(old = false, modelReady = false, n = 7)
        // Warm: the background warm-up already built today's block.
        cached(MSG); cached(WARM)
        contextFor = { t -> cached(t) }
        val warm = bench(old = false, modelReady = false, n = 7)
        // Cold: every send builds the block (cache invalidated before each).
        contextFor = { t -> cache.invalidate(); cached(t) }
        val cold = bench(old = false, modelReady = false, n = 7)
        contextFor = null
        val buildNs = (1..200).map { val t0 = System.nanoTime(); build("anything on for today?"); System.nanoTime() - t0 }.sorted()
        val blk = build("anything on for today?")
        val nowIso = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        fun row(name: String, r: Triple<Double, Double, Double>) = "%-50s working %6.2f ms   on the wire %6.2f ms (fake gateway got byte 1 at %6.2f ms)".format(java.util.Locale.US, name, r.first, r.second, r.third)
        val text = listOf(
            "Blaze Android 0.45.9 send path with the [context] block (SendPathTest.contextBlockKeepsTheWireFast, JVM, $nowIso)",
            "Median of 7 typed Sends after one warm-up, new 0.45.8 path, no fast-lane model. Fake gateway on 127.0.0.1.",
            "Real code: TurnContext.block / Cache / key, ScheduleDay.itemsOn / datesAsked / line, StartTrace, RapidMessages, FastLane.useModel.",
            "",
            row("0.45.8 path, no context block", base),
            row("0.45.9 warm (block from the 60 s cache)", warm),
            row("0.45.9 cold (block built on Send)", cold),
            "Block build alone: p50 %.3f ms, p95 %.3f ms (200 runs, fixture: 3 routines, 1 weather line, 1 open task)".format(java.util.Locale.US, buildNs[100] / 1e6, buildNs[190] / 1e6),
            "Block size: ${blk.length} chars (cap ${TurnContext.MAX})",
            "",
            blk,
            "",
            "Target: warm on-the-wire < 5 ms. Phone numbers come from the What-happened sheet (Send → on the wire, Before the wire), not from this bench.",
        ).joinToString("\n") + "\n"
        val dir = File(System.getProperty("user.dir"), "build/bench").apply { mkdirs() }
        File(dir, "start-0459.txt").writeText(text)
        println(text)
        assertTrue("warm on the wire ${warm.second} ms", warm.second < 5.0)
        assertTrue("block ${blk.length} chars", blk.length <= TurnContext.MAX)
    }

    @Test fun newRoutingNeverWaits() {
        RapidMessages.START_NOW = true
        for (burst in listOf(true, false)) assertEquals(RapidMessages.Route.NOW, RapidMessages.route(false, false, burst))
        assertFalse(FastLane.useModel(offline = false))
        assertTrue(FastLane.useModel(offline = true))
        RapidMessages.START_NOW = false
        assertEquals(RapidMessages.Route.BURST, RapidMessages.route(false, false, true))
    }

    @Test fun replyLanguageHeader() {
        assertEquals(null, ReplyLanguage.header("auto"))
        assertEquals(null, ReplyLanguage.header(null))
        assertEquals(ReplyLanguage.HEADER to "fr", ReplyLanguage.header("fr"))
    }

    @Test fun traceRowsRoundTrip() {
        var t = 0L
        StartTrace.clock = { t }
        try {
            StartTrace.reset(); StartTrace.tap(force = true)
            t = 12_000_000; StartTrace.visible()
            t = 140_000_000; StartTrace.wire()
            t = 600_000_000; StartTrace.headers()
            t = 1_210_000_000; StartTrace.first()
            val m = StartTrace.take()!!
            assertEquals("Send \u2192 working 12 ms \u00b7 on the wire 140 ms \u00b7 headers 600 ms \u00b7 first word 1.21 s", StartTrace.line(m))
            val tr = TurnTrace.build(emptyMap(), "Blaze Cloud", "agent", emptyList(), 900, 600, 2000, 1, emptyList(), emptyList(), 0, m)
            val back = TurnTrace.fromJson(TurnTrace.toJson(tr))!!
            assertEquals(12L, back.sendVisibleMs); assertEquals(140L, back.sendWireMs); assertEquals(1210L, back.sendFirstMs)
            val rows = TurnTrace.rows(back).toMap()
            assertEquals("12 ms", rows["Send \u2192 working"]); assertEquals("140 ms", rows["Send \u2192 on the wire"])
            assertEquals("1.21 s", rows["Send \u2192 first token"])
        } finally { StartTrace.clock = { System.nanoTime() }; StartTrace.reset() }
    }

    private companion object {
        const val FAKE_MODEL_MS = 1_500L
        const val FAKE_DECRYPT_MS = 25L
        const val MSG = "what should I cook tonight with rice and beans"
        const val WARM = "tell me something about the history of Lusaka"
    }
}
