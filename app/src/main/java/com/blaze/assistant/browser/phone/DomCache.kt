package com.blaze.assistant.browser.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.45.19-speed: Blaze Phone's cached model of the page, kept current by the injected bridge
 * ([PhoneScripts.bridge]) as diffs. Reads come from here (no page round trip, no screenshot); a missed message or a new
 * document makes it unusable until a full model arrives (the engine then asks the bridge for one, or reads the DOM the
 * old way). Thread-safe: messages arrive on the main thread, the engine reads from its own coroutine.
 *
 * Message (JSON): k (token), doc (document id), seq, full, url, title, ready, sy, net, pw, card, focus,
 * set (nodes), del (ids), order (ids), text, headings.
 */
class DomCache(private val token: String, private val clock: () -> Long) {
    private var doc: String? = null
    private var staleDoc: String? = null
    private var seq = 0L
    private var hasFull = false
    private var needResync = false
    private val nodes = HashMap<String, JSONObject>()
    private var order: List<String> = emptyList()
    private var text = ""
    private var title = ""
    private var url = ""
    private var headings = JSONArray()
    private var pw = false
    private var card = false
    private var sy = 0
    private var focus: JSONObject? = null
    private var net = 0
    private var netAt = 0L
    private var ready = "loading"

    /** When the page's content last changed (host clock). */
    @Volatile var lastChangeAt = 0L
        private set
    /** Messages applied so far (any kind). */
    @Volatile var applied = 0L
        private set

    /** A message from the bridge. False when it isn't ours (token), is stale, or shows a gap (then [needsResync]). */
    @Synchronized fun accept(raw: String?): Boolean {
        if (raw.isNullOrEmpty() || raw.length > 4_000_000) return false
        val o = try { JSONObject(raw) } catch (_: Exception) { return false }
        if (o.optString("k") != token) return false
        return apply(o)
    }

    /** A full model the engine fetched itself ([PhoneScripts.BRIDGE_FULL]); no token (it came back from our own eval). */
    @Synchronized fun acceptFull(o: JSONObject): Boolean = o.optBoolean("full") && apply(o)

    private fun apply(o: JSONObject): Boolean {
        val d = o.optString("doc"); val s = o.optLong("seq"); val full = o.optBoolean("full")
        if (d.isEmpty() || d == staleDoc) return false
        if (d != doc) {
            if (!full) { needResync = true; return false }
            doc = d; seq = 0; hasFull = false; nodes.clear(); order = emptyList(); text = ""; headings = JSONArray(); focus = null
        }
        if (full) {
            if (hasFull && s <= seq) return false
            nodes.clear()
        } else {
            if (s <= seq) return false
            if (!hasFull || s != seq + 1) { needResync = true; return false }
        }
        seq = s
        o.optJSONArray("set")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { n -> n.optString("id").takeIf { it.isNotEmpty() }?.let { nodes[it] = n } } }
        o.optJSONArray("del")?.let { a -> for (i in 0 until a.length()) nodes.remove(a.optString(i)) }
        o.optJSONArray("order")?.let { a -> order = (0 until a.length()).map { a.optString(it) } }
        if (o.has("text")) text = o.optString("text")
        o.optJSONArray("headings")?.let { headings = it }
        if (o.has("url")) url = o.optString("url")
        if (o.has("title")) title = o.optString("title")
        if (o.has("pw")) pw = o.optBoolean("pw")
        if (o.has("card")) card = o.optBoolean("card")
        if (o.has("sy")) sy = o.optInt("sy")
        if (o.has("ready")) ready = o.optString("ready")
        focus = o.optJSONObject("focus")
        val n = o.optInt("net", net)
        if (n != net) { net = n; netAt = clock() }
        if (full || o.has("set") || o.has("del") || o.has("order") || o.has("text") || o.has("headings")) lastChangeAt = clock()
        if (full) { hasFull = true; needResync = false }
        applied++
        return true
    }

    /** onPageStarted: the document is going away; its messages are ignored from now on. */
    @Synchronized fun navStarted() {
        staleDoc = doc; doc = null; hasFull = false; needResync = false; nodes.clear(); order = emptyList(); text = ""; focus = null
        net = 0; lastChangeAt = clock(); applied++
    }

    /** A new WebView (or the renderer restarted): forget everything. */
    @Synchronized fun clear() { navStarted(); staleDoc = null }

    @Synchronized fun usable(): Boolean = hasFull && !needResync && ready != "loading"
    @Synchronized fun needsResync(): Boolean = needResync

    /** The page as [PhoneScripts.SNAPSHOT] returns it (for [PhonePolicy.parseSnapshot]); null when not usable. */
    @Synchronized fun snapshotJson(): JSONObject? {
        if (!usable()) return null
        val a = JSONArray()
        val seen = HashSet<String>()
        for (id in order) nodes[id]?.let { if (seen.add(id)) a.put(it) }
        if (seen.size < nodes.size) nodes.keys.sorted().forEach { id -> if (seen.add(id)) a.put(nodes[id]) }
        return JSONObject().put("title", title).put("url", url).put("text", text).put("nodes", a).put("pw", pw).put("headings", headings)
    }

    @Synchronized fun focusId(): String? = focus?.optString("id")?.takeIf { it.isNotEmpty() }
    @Synchronized fun focusInfo(): PhoneDriver.FocusInfo? = if (!usable()) null else
        PhoneDriver.FocusInfo(focus?.optString("submit").orEmpty(), card, focus?.optBoolean("inForm") == true, focus?.optBoolean("editable") == true)
    /** The field's value length as the page last reported it (secrets included: only the length). */
    @Synchronized fun valueLen(id: String): Int? = nodes[id]?.let { if (it.has("len")) it.optInt("len") else null }
    @Synchronized fun scrollY(): Int? = if (hasFull) sy else null
    @Synchronized fun url(): String = url

    /** Is [q] in the page text (as the page's innerText)? Null when the cached text was cut (then ask the page). */
    @Synchronized fun hasText(q: String): Boolean? {
        if (!usable()) return null
        if (text.contains(q, true)) return true
        return if (text.length >= 12_000) null else false
    }

    /** Requests in flight (fetch / XHR), ignoring ones that ran longer than [longMs] (long polls). */
    @Synchronized fun netBusy(now: Long, longMs: Long = 5_000): Boolean = net > 0 && now - netAt < longMs

    /** The last sign of activity: a content change, or a request starting / finishing (a response is usually rendered next). */
    @Synchronized fun activeAt(): Long = maxOf(lastChangeAt, netAt)

    @Synchronized fun nodeCount(): Int = nodes.size
}
