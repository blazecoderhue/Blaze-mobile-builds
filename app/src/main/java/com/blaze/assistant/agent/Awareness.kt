package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.37.0 Awareness: Blaze knows what's going on in Blaze and on the phone right now, and what just happened. "Skip
 * this song", "what was the movie I was just watching", "turn this up", "what's on my screen" work without asking.
 *
 *  - [Now]: live things, one per source key (the song in Blaze Music, the video in Blaze Video, another app's media
 *    session, the Blaze screen or view in front, the BlazeOS app in front, the design open). Features call [set] /
 *    [clear] from their own callbacks; a playing item's position is worked out at read time, so nothing has to tick.
 *  - [Event]: what just happened ("watched Dune, stopped at 41:02 of 2:35:12"), newest first, kept 3 days on disk
 *    (filesDir/awareness.json) so "the movie I was just watching" still works after Blaze restarts.
 *  - [promptBlock]: a compact WHAT'S GOING ON line in every prompt's changing tail (never the cached prefix).
 *  - [TOOL] blaze_now: the full report: everything live, the last 30 events and every Blaze feature with what it's for
 *    and the tool that drives it.
 * The formatting is pure (AwarenessTest); Android readers (media sessions, BlazeOS) plug in through [readers].
 */
object Awareness {
    const val TOOL = "blaze_now"
    private const val MAX_EVENTS = 60
    private const val KEEP_MS = 3L * 24 * 3600 * 1000

    /**
     * One live thing. [state] reads naturally before the title ("Playing", "Paused", "Watching", "On screen", "Open").
     * [where] is the app or view ("Blaze Music", "Spotify", "Design"). [posMs] / [durMs] < 0 = no position; while
     * [playing], the position moves on from [at].
     */
    data class Now(val key: String, val state: String, val title: String, val where: String, val detail: String = "",
                   val posMs: Long = -1, val durMs: Long = -1, val playing: Boolean = false, val at: Long = System.currentTimeMillis())

    data class Event(val kind: String, val text: String, val at: Long)

    private val live = ConcurrentHashMap<String, Now>()
    private val events = ArrayList<Event>()
    @Volatile private var file: File? = null

    /** Extra live lines read at prompt time (Android side: other apps' media, BlazeOS). Each must be quick and never throw. */
    val readers = java.util.concurrent.CopyOnWriteArrayList<() -> List<Now>>()

    fun init(filesDir: File) {
        file = File(filesDir, "awareness.json")
        synchronized(events) { events.clear(); events.addAll(load(file!!.takeIf { it.exists() }?.readText(), System.currentTimeMillis())) }
    }

    fun set(n: Now) { live[n.key] = n }
    fun clear(key: String) { live.remove(key) }
    fun get(key: String): Now? = live[key]

    /** Something happened. The same text again within 10 minutes only moves it to the top. */
    fun did(kind: String, text: String, at: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        synchronized(events) {
            val e = Event(kind, text.take(200), at)
            events.removeAll { it.text == e.text && at - it.at < 10 * 60_000 }
            events.add(0, e)
            while (events.size > MAX_EVENTS) events.removeAt(events.size - 1)
            save()
        }
    }

    fun recent(n: Int = 30): List<Event> = synchronized(events) { events.take(n) }

    /** Everything live now: what features set, plus the readers. */
    fun nows(): List<Now> {
        val extra = readers.flatMap { r -> try { r() } catch (_: Throwable) { emptyList() } }
        val mine = live.values.toList()
        // Another app's session for something Blaze already shows (Blaze Music's own session) is dropped.
        return mine + extra.filter { x -> mine.none { it.title.equals(x.title, true) } }
    }

    // ---------------- formatting (pure) ----------------

    /** 83_000 → "1:23"; 3_723_000 → "1:02:03". */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "just now", "4 min ago", "2 h ago", "yesterday", "3 days ago". */
    fun ago(now: Long, at: Long): String {
        val d = (now - at).coerceAtLeast(0) / 1000
        return when {
            d < 60 -> "just now"
            d < 3600 -> "${d / 60} min ago"
            d < 24 * 3600 -> "${d / 3600} h ago"
            d < 48 * 3600 -> "yesterday"
            else -> "${d / 86400} days ago"
        }
    }

    /** Where a live item is now: its position moves on while it plays (capped at its length). */
    fun position(n: Now, now: Long): Long {
        if (n.posMs < 0) return -1
        val p = if (n.playing) n.posMs + (now - n.at).coerceAtLeast(0) else n.posMs
        return if (n.durMs > 0) p.coerceAtMost(n.durMs) else p
    }

    /** One live item in words: "Playing \"Song\" by X in Blaze Music, 1:12 of 3:40". */
    fun line(n: Now, now: Long): String = buildString {
        append(n.state).append(" \"").append(n.title.take(90)).append('"')
        if (n.detail.isNotBlank()) append(' ').append(n.detail.take(60))
        if (n.where.isNotBlank()) append(" in ").append(n.where)
        val p = position(n, now)
        if (p >= 0) { append(", ").append(clock(p)); if (n.durMs > 0) append(" of ").append(clock(n.durMs)) }
        if (!n.playing && n.posMs >= 0 && now - n.at > 60_000) append(" (").append(ago(now, n.at)).append(')')
    }

    /** Media first (what "this song" means), then screens; ties by most recent. */
    private fun order(list: List<Now>): List<Now> = list.sortedWith(compareByDescending<Now> { it.playing }.thenByDescending { it.posMs >= 0 }.thenByDescending { it.at })

    fun format(nows: List<Now>, events: List<Event>, now: Long, maxEvents: Int = 5): String {
        val fresh = events.filter { now - it.at < KEEP_MS }.take(maxEvents)
        if (nows.isEmpty() && fresh.isEmpty()) return ""
        return buildString {
            append("\nWHAT'S GOING ON (live, from Blaze and the phone; \"this song\", \"the video I was watching\", \"what's on my screen\" mean these; act on them with music_ops / video_ops / the media tools, details with ")
            append(TOOL).append("): ")
            if (nows.isNotEmpty()) append(order(nows).take(6).joinToString("; ") { line(it, now) }).append(". ")
            if (fresh.isNotEmpty()) append("Recently: ").append(fresh.joinToString("; ") { "${it.text} (${ago(now, it.at)})" }).append('.')
        }.trimEnd()
    }

    fun promptBlock(now: Long = System.currentTimeMillis()): String = try { format(nows(), recent(), now) } catch (_: Throwable) { "" }

    // ---------------- v0.42.0: the short version for plain chat ----------------

    /** Tool groups whose turns get the full block (they act on what's playing / on screen / on the phone). */
    val FULL_GROUPS = setOf("screen", "device", "media", "read", "logs", "callcopilot", "health")

    private val cue = Regex("""\b(this|that|the) (song|track|tune|video|show|episode|podcast|movie|page|screen|app|picture|photo|article)\b|""" +
        """\b(playing|watching|listening|on (my|the) screen|what'?s on|just (did|happened)|recently|a moment ago|earlier today)\b""", RegexOption.IGNORE_CASE)

    /** True when a turn should carry the full block: the phone / screen / media tools are on, or the words point at "now". */
    fun needsFull(userText: String, groups: Collection<String>): Boolean = groups.any { it in FULL_GROUPS } || cue.containsMatchIn(userText)

    /** Two or three short lines: what's live now (two items at most) and the latest one or two things that happened. */
    fun summary(nows: List<Now>, events: List<Event>, now: Long): String {
        val fresh = events.filter { now - it.at < 6 * 3600_000L }.take(2)
        if (nows.isEmpty() && fresh.isEmpty()) return ""
        val s = buildString {
            if (nows.isNotEmpty()) append("\nNow: ").append(order(nows).take(2).joinToString("; ") { line(it, now).take(140) }).append('.')
            if (fresh.isNotEmpty()) append("\nRecently: ").append(fresh.joinToString("; ") { "${it.text.take(100)} (${ago(now, it.at)})" }).append('.')
            append(" (Details: ").append(TOOL).append(')')
        }
        return s.take(480)
    }

    fun summaryBlock(now: Long = System.currentTimeMillis()): String = try { summary(nows(), recent(), now) } catch (_: Throwable) { "" }

    /** Every Blaze feature: what it is, and how Blaze drives it. [views] come from the Views list (name, id, description). */
    fun features(views: List<Triple<String, String, String>>, commands: String = ""): String = buildString {
        append("## Blaze apps\n")
        append("- Chat: talking to Blaze (this). Projects and Home (widgets) are its other tabs.\n")
        append("- Blaze Music: library, Store (Jamendo, Internet Archive), playlists, smart mixes, DJ, equaliser, sleep timer, lyrics. Drive it with music_ops.\n")
        append("- Blaze Video: library, Store, series and episodes, subtitles (incl. AI translation), recaps, picture-in-picture, resume where you left off. Drive it with video_ops.\n")
        append("- Gallery: photos and everything Blaze makes; clean-up, trash, editor. Drive it with gallery / gallery_ops.\n")
        append("- Camera: Photo (Night, HDR), Document, Text (prices, sums), barcodes. blaze_camera, camera_math.\n")
        append("- Browser: Blaze's own browser (browser_open / browser_show) and installed site apps.\n")
        append("- BlazeOS: the virtual phone with its own apps, App Store, Settings; native (WebAssembly) and web apps.\n")
        append("- Blaze Link: the paired PC (ask_pc).\n")
        append("- Simple / Advanced (Settings): Simple hides Terminal, MCP, BlazeRouter, AI models and developer settings from lists; every view and / command still works and you can open any of them.\n")
        append("- Home and Chat: with Swipe navigation (Settings › Navigation, the default) Blaze opens on Home (weather, news, heads-ups); the user swipes up the handle for Chat and pulls down from the top for Home; open_view view=home shows Home. Classic tabs is the old Chat | Projects | Home pill. 'surface' in Live now says which is in front.\n")
        append("- Bank mode (/bank, the bank_mode tool, Settings › Bank mode): Blaze steps back so banking apps run (phone control, the floating button, screen reading and notification reading pause). " +
            "It can turn on by itself when a banking app opens. Never read, tap or type inside a banking app. Now: " + (try { com.blaze.assistant.bank.BankMode.statusLine() } catch (_: Throwable) { "unknown." }) + "\n")
        append("- Things Blaze is doing (/doing): routines and reminders with on/off, and a Today I\u2026 diary. Look & feel (/look): living backgrounds, theme, accent, Move with music.\n")
        append("- Cloud sync (/sync, Settings › Account › Cloud sync; open_view view=sync): optional, off by default. Keeps chats, memory, settings and designs the same on the user's phones, end-to-end encrypted with a passphrase only they know. Sign-ins, keys, connected accounts and permissions never sync. Turning it on, the passphrase and deleting the cloud copy are the user's to do on that page.\n")
        if (views.isNotEmpty()) {
            append("## Views (open_view)\n")
            views.forEach { (name, id, desc) -> append("- ").append(name).append(" (/").append(id).append("): ").append(desc).append('\n') }
        }
        if (commands.isNotBlank()) append(commands)
    }

    fun report(views: List<Triple<String, String, String>>, now: Long = System.currentTimeMillis(), commands: String = ""): String = buildString {
        val ns = order(nows())
        append("## Live now\n")
        if (ns.isEmpty()) append("Nothing playing or open that Blaze can see.\n") else ns.forEach { append("- ").append(line(it, now)).append('\n') }
        append("## Recently\n")
        val ev = recent(30)
        if (ev.isEmpty()) append("Nothing yet.\n") else ev.forEach { append("- ").append(it.text).append(" (").append(ago(now, it.at)).append(")\n") }
        append(features(views, commands))
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "What's going on in Blaze and on the phone: what's playing (Blaze Music, Blaze Video, Spotify, YouTube and other apps), where a video was left, the screen and view in front, the BlazeOS app open, the design open, what the user just did, and every Blaze feature with the tool that drives it. Use it for 'this song', 'the movie I was watching', 'what am I looking at', 'what can you do'.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))

    // ---------------- disk ----------------

    fun load(text: String?, now: Long): List<Event> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONObject(text).optJSONArray("events") ?: JSONArray()
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Event(o.optString("k"), o.optString("t"), o.optLong("at")).takeIf { it.text.isNotBlank() && now - it.at < KEEP_MS }
            }.take(MAX_EVENTS)
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(list: List<Event>): String = JSONObject().put("events", JSONArray().apply {
        list.forEach { put(JSONObject().put("k", it.kind).put("t", it.text).put("at", it.at)) }
    }).toString()

    private fun save() {
        val f = file ?: return
        val text = toJson(events)
        try { val tmp = File(f.path + ".tmp"); tmp.writeText(text); tmp.renameTo(f) } catch (_: Exception) { }
    }

    /** Tests: forget everything in memory. */
    fun reset() { live.clear(); readers.clear(); synchronized(events) { events.clear() }; file = null }
}
