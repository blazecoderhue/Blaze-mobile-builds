package com.blaze.assistant.browser.phone

import org.json.JSONArray
import org.json.JSONObject
import java.util.PriorityQueue
import kotlin.random.Random

/**
 * 0.45.19-speed perf harness: a simulated Blaze Phone page on a virtual clock. It answers the engine's page scripts
 * (recognised by their leading bz:name marker comment) from a page model, charges each call what it costs on a phone
 * (cost model below), and plays page events (navigation start/finish, fetches, re-renders) at their scheduled times,
 * so the real [PhoneEngine] code is timed exactly, run after run, with seeded jitter.
 *
 * Cost model (ms, a mid-range phone; the same for every engine version):
 *  - evaluateJavascript round trip: 4 + U(0, 8)
 *  - page-side scan for a full DOM read: 0.003 per DOM node + 0.05 per interactive candidate + a layout flush U(2, 6)
 *    + innerText 0.002 per DOM node + 0.3 per KB of text; JSON over the bridge 0.08 per KB
 *  - tap: finger down to up [PhonePolicy.TAP_HOLD_MS], the page handles it 16 later; keys: 1 per character +
 *    [PhonePolicy.keyPaceMs] per character, the value lands 5 later
 *  - navigation: starts 30-300 after the tap, takes 400-2000 (phone_open: starts at +20, 500-2000)
 *  - an in-page button that fetches: request 100-600, then the re-render 20-80
 */
class SimPage(seed: Long) : PageHost {
    val rnd = Random(seed)
    var t = 0L
        private set
    private var evNo = 0L
    private class Ev(val at: Long, val no: Long, val fn: () -> Unit)
    private val q = PriorityQueue<Ev>(compareBy<Ev>({ it.at }, { it.no }))

    fun at(delay: Long, fn: () -> Unit) { q.add(Ev(t + delay, evNo++, fn)) }
    fun advanceTo(x: Long) {
        while (true) { val e = q.peek() ?: break; if (e.at > x) break; q.poll(); if (e.at > t) t = e.at; e.fn() }
        if (x > t) t = x
    }
    /** Lets every scheduled page event happen (the page settles completely). */
    fun drain() { while (true) { val e = q.peek() ?: break; advanceTo(e.at) } }
    fun pending(): Boolean = q.isNotEmpty()
    fun nextAt(): Long? = q.peek()?.at

    // ------------------------------------------------------------------ page model
    class El(val id: String, val role: String, var name: String, var value: String = "", val type: String = "",
             var visible: Boolean = true, val sens: Boolean = false, val options: List<String> = emptyList(),
             val inForm: Boolean = false, val submitLabel: String = "", var onClick: (() -> Unit)? = null, var href: String = "",
             var onEnter: (() -> Unit)? = null)

    class Doc(val url: String, val title: String, val domSize: Int, var text: String, val els: MutableList<El> = mutableListOf())

    var doc: Doc? = null
    var focused: String? = null
    var scrollY = 0
    /** Mirrors the WebView's `loaded` deferred: completed / reset by onPageStarted / completed by onPageFinished. */
    var loadedDone = true
    var navigating = false
    var watching = false
    /** Called on every page change (the bridge sim hooks in here): navstart, navfinish, dom, value, focus, scroll, netstart, netend. */
    var onChange: ((String) -> Unit)? = null
    /** CPU spent inside the simulator itself (excluded from the engine's own CPU). */
    var simNanos = 0L

    fun el(id: String): El? = doc?.els?.firstOrNull { it.id == id }
    fun changed(what: String) { bridgeEvent(what); onChange?.invoke(what) }

    // ------------------------------------------------------------------ the DOM bridge (0.45.19-speed)
    /** Simulates the injected bridge: each page change is batched 30 ms, scanned (page time: [scanCost]) and posted (2 ms). */
    var bridge = true
    val cache = DomCache("TOK") { t }
    private var sigN = 0L
    private var docId = "d0"
    private var docNo = 0
    private var bSeq = 0L
    private var prevNodes: Map<String, String> = emptyMap()
    private var prevOrder = ""
    private var prevText: String? = null
    private var flushAt = -1L
    private var inflight = 0
    private var forceFull = true
    /** Messages the bridge posted (for the bridge tests). */
    var posted = 0

    private fun bump() { sigN++ }

    private fun bridgeEvent(what: String) {
        when (what) {
            "navstart" -> { cache.navStarted(); bump() }
            "navfinish" -> { docId = "d" + (++docNo); bSeq = 0; prevNodes = emptyMap(); prevOrder = ""; prevText = null; inflight = 0; forceFull = true; bump(); schedFlush() }
            "netstart" -> { inflight++; schedFlush() }
            "netend" -> { inflight = maxOf(0, inflight - 1); schedFlush() }
            else -> schedFlush()
        }
    }

    private fun schedFlush() {
        if (!bridge || flushAt >= 0) return
        flushAt = t + 30
        at(30) {
            val d = doc
            if (d == null) { flushAt = -1; return@at }
            val msg = bridgeMessage(d, forceFull)
            forceFull = false
            val cost = scanCost(d, msg?.toString()?.length ?: 0)
            at(cost + 2) { flushAt = -1; if (msg != null) { posted++; if (cache.accept(msg.toString())) bump() } }
        }
    }

    private fun focusJson(): Any {
        val f = focused?.let { el(it) } ?: return JSONObject.NULL
        return JSONObject().put("id", f.id).put("submit", f.submitLabel).put("inForm", f.inForm).put("editable", f.role == "textbox")
    }

    fun bridgeNode(e: El): JSONObject = nodeJson(e).apply { remove("x"); remove("y"); remove("w"); remove("h"); if (e.role == "textbox") put("len", e.value.length) }

    /** What the injected script would post now ([full]: everything), or null when nothing changed. */
    fun bridgeMessage(d: Doc, full: Boolean, withToken: Boolean = true): JSONObject? {
        val o = JSONObject().put("doc", docId).put("seq", bSeq + 1).put("full", full).put("url", d.url).put("title", d.title)
            .put("net", inflight).put("ready", "complete").put("sy", scrollY)
        val cur = LinkedHashMap<String, String>(); val set = JSONArray()
        d.els.filter { it.visible }.take(250).forEach { e -> val n = bridgeNode(e); val k = n.toString(); cur[e.id] = k; if (full || prevNodes[e.id] != k) set.put(n) }
        val del = JSONArray(); if (!full) prevNodes.keys.filter { it !in cur }.forEach { del.put(it) }
        val order = cur.keys.joinToString(",")
        var changed = full
        if (set.length() > 0) { o.put("set", set); changed = true }
        if (del.length() > 0) { o.put("del", del); changed = true }
        if (full || order != prevOrder) { o.put("order", JSONArray(cur.keys.toList())); changed = true }
        if (full || d.text != prevText) { o.put("text", d.text.take(12000)); changed = true }
        if (full) o.put("headings", JSONArray())
        o.put("pw", d.els.any { it.visible && it.type == "password" }).put("card", false).put("focus", focusJson())
        prevNodes = cur; prevOrder = order; prevText = d.text
        // The real script also posts when only net / focus / scroll changed; so does this one.
        bSeq++
        if (withToken) o.put("k", "TOK")
        return o
    }

    override fun dom(): DomCache? = if (bridge && doc != null) cache else null
    override fun signal(): Long = sigN
    override suspend fun awaitSignal(after: Long, ms: Long): Boolean {
        val end = t + ms
        while (sigN == after) { val e = q.peek() ?: break; if (e.at > end) break; advanceTo(e.at) }
        if (sigN == after) advanceTo(end)
        return sigN != after
    }
    override fun loading(): Boolean = !loadedDone
    override fun watching(): Boolean = watching

    /** Starts a navigation to [make] (start after [startIn], finish [dur] later), like a tap on a link. */
    fun navigate(startIn: Long, dur: Long, make: () -> Doc) {
        at(startIn) {
            navigating = true; loadedDone = false; changed("navstart")
            at(dur) { doc = make(); focused = null; scrollY = 0; navigating = false; loadedDone = true; changed("navfinish") }
        }
    }

    /** phone_open: loadUrl (the deferred is reset before the load, as PhoneBrowser.open does). */
    fun open(make: () -> Doc) { loadedDone = false; bump(); navigate(20, rnd.nextLong(500, 2001), make) }

    // ------------------------------------------------------------------ costs
    private fun rtt() = 4 + rnd.nextLong(0, 9)
    fun scanCost(d: Doc, bytes: Int): Long {
        val cands = d.els.count { it.visible }
        val kb = d.text.length / 1024.0
        return (d.domSize * 0.003 + cands * 0.05 + rnd.nextLong(2, 7) + d.domSize * 0.002 + kb * 0.3 + bytes / 1024.0 * 0.08).toLong()
    }

    // ------------------------------------------------------------------ PageHost
    override fun now() = t
    /** Fixed sleeps the engine asked for, and page calls it made (the bridge tests assert on these). */
    var sleeps = 0
    var evals = 0
    override suspend fun sleep(ms: Long) { sleeps++; advanceTo(t + ms) }
    override fun hasPage() = doc != null
    override fun url() = doc?.url.orEmpty()
    override fun touch() {}
    override suspend fun viewHeight() = 2000

    override suspend fun eval(code: String): Any? {
        evals++
        val n0 = System.nanoTime()
        val name = Regex("^/\\*bz:([a-z]+)\\*/").find(code)?.groupValues?.get(1) ?: return null
        val args = Regex("\\)\\((.*)\\)\\s*$", RegexOption.DOT_MATCHES_ALL).find(code)?.groupValues?.get(1)?.let { a ->
            if (a.isBlank()) JSONArray() else try { JSONArray("[$a]") } catch (_: Exception) { JSONArray() } } ?: JSONArray()
        val r = rtt()
        advanceTo(t + r / 2)
        var extra = 0L
        val out: Any? = run {
            val d = doc ?: return@run null
            when (name) {
                "snapshot" -> snapshotJson(d).also { extra = scanCost(d, it.toString().length) }
                "find" -> { extra = (d.domSize * 0.002 + d.els.size * 0.02).toLong()
                    val qq = args.optString(0).lowercase().trim(); val field = args.optBoolean(1)
                    d.els.filter { it.visible && (!field || it.role == "textbox" || it.role == "combobox") && it.name.lowercase().contains(qq) }
                        .minByOrNull { it.name.length }?.id }
                "rect" -> el(args.optString(0))?.takeIf { it.visible }?.let { lastTapTarget = it.id; rectJson(it) }
                "focused" -> focused ?: d.els.firstOrNull { it.visible && it.role == "textbox" }?.id
                "focus" -> { val f = focused?.let { el(it) }
                    JSONObject().put("tag", if (f != null) "input" else "").put("inForm", f?.inForm == true).put("submit", f?.submitLabel.orEmpty())
                        .put("card", false).put("editable", f?.role == "textbox") }
                "click" -> el(args.optString(0))?.let { e -> at(5) { e.onClick?.invoke() }; "ok" } ?: "missing"
                "type" -> el(args.optString(0))?.let { e -> e.value = if (args.optBoolean(3)) args.optString(1) else e.value + args.optString(1); changed("value"); "ok" } ?: "missing"
                "mark" -> "ok"
                "len" -> el(args.optString(0))?.value?.length ?: -1
                "focusel" -> { focused = args.optString(0); changed("focus"); "ok" }
                "select" -> el(args.optString(0))?.let { e ->
                    val hit = e.options.firstOrNull { it.equals(args.optString(1), true) } ?: e.options.firstOrNull { it.contains(args.optString(1), true) }
                    if (e.options.isEmpty()) "notselect" else if (hit == null) "nooption:" + e.options.joinToString(" | ")
                    else { e.value = hit; changed("value"); e.onClick?.invoke(); "ok:$hit" } } ?: "missing"
                "wait" -> { val sel = args.optString(0); val txt = args.optString(1); val gone = args.optBoolean(2)
                    var ok = loadedDone
                    if (sel.isNotBlank()) ok = d.els.any { it.visible && ("#" + it.id == sel || it.name == sel) }
                    if (txt.isNotBlank()) ok = d.text.contains(txt, true) || d.els.any { it.visible && it.name.contains(txt, true) }
                    if (gone) ok = !ok
                    if (ok) "yes" else "no" }
                "locate" -> { extra = (d.domSize * 0.002 + d.els.size * 0.02).toLong()
                    val qq = args.optString(0).lowercase().trim(); val field = args.optBoolean(1)
                    d.els.filter { it.visible && (!field || it.role == "textbox" || it.role == "combobox") && it.name.lowercase().contains(qq) }
                        .minByOrNull { it.name.length }?.let { lastTapTarget = it.id; rectJson(it) } }
                "full" -> if (!bridge) null else bridgeMessage(d, true, withToken = false)?.also { extra = scanCost(d, it.toString().length) }
                "sy" -> scrollY.toString()
                "scrollby" -> { scrollY += 1000; changed("scroll"); scrollY.toString() }
                else -> extraEval(name, args, d).also { extra = extraCost }
            }
        }
        advanceTo(t + r - r / 2 + extra)
        simNanos += System.nanoTime() - n0
        return out
    }

    /** Scripts a newer engine adds (the bridge sim sets these). */
    var extraEval: (String, JSONArray, Doc) -> Any? = { _, _, _ -> null }
    var extraCost = 0L

    fun nodeJson(e: El): JSONObject = JSONObject().put("id", e.id).put("role", e.role).put("name", e.name)
        .put("value", if (e.sens && e.value.isNotEmpty()) PhonePolicy.HIDDEN else e.value).put("type", e.type).put("ac", "").put("key", e.name)
        .put("sens", e.sens).put("marked", false).put("disabled", false).put("x", 100).put("y", 200).put("w", 200).put("h", 60).put("inView", true)
        .apply { if (e.href.isNotEmpty()) put("href", e.href) }

    fun snapshotJson(d: Doc): JSONObject {
        val nodes = JSONArray(); d.els.filter { it.visible }.take(250).forEach { nodes.put(nodeJson(it)) }
        return JSONObject().put("title", d.title).put("url", d.url).put("text", d.text.take(12000)).put("nodes", nodes)
            .put("pw", d.els.any { it.visible && it.type == "password" }).put("headings", JSONArray())
    }

    fun rectJson(e: El): JSONObject = JSONObject().put("id", e.id).put("x", 100).put("y", 200).put("w", 200).put("h", 60).put("label", e.name)
        .put("role", e.role).put("tag", if (e.role == "textbox") "input" else "button").put("type", e.type).put("card", false).put("sens", e.sens)
        .put("editable", e.role == "textbox").put("disabled", false).put("covered", false).put("coveredBy", "")

    /** Which element a tap lands on: the last one the engine located (it always locates before it taps). */
    var lastTapTarget: String? = null

    override suspend fun tap(x: Float, y: Float) {
        advanceTo(t + PhonePolicy.TAP_HOLD_MS)
        val target = lastTapTarget
        at(16) { target?.let { el(it) }?.let { e -> if (e.role == "textbox" || e.role == "combobox") { focused = e.id; changed("focus") } else e.onClick?.invoke() } }
    }

    override suspend fun drag(dy: Float) { advanceTo(t + 220); at(30) { scrollY += dy.toInt(); changed("scroll") } }

    override suspend fun keys(text: String, clear: Boolean): Boolean {
        val pace = PhonePolicy.keyPaceMs(watching)
        val fid = focused
        advanceTo(t + text.length * (1 + pace))
        at(5) { fid?.let { el(it) }?.let { e -> e.value = if (clear) text else e.value + text; changed("value") } }
        return true
    }

    override suspend fun key(k: String) {
        advanceTo(t + 1)
        if (k == "enter") { val f = focused?.let { el(it) }; at(10) { f?.onEnter?.invoke() } }
    }

    override suspend fun history(what: String): String { loadedDone = true; return "Nothing to go back to." }

    override suspend fun awaitLoad(ms: Long): Boolean {
        val end = t + ms
        while (!loadedDone) { val e = q.peek() ?: break; if (e.at > end) break; advanceTo(e.at) }
        if (!loadedDone) advanceTo(end)
        return loadedDone
    }
}
