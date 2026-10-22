package com.blaze.assistant.home

import kotlin.math.abs

/**
 * v0.30.5 (Luke: "add reminder/check-in/heads up for when the home screen [has] new stuff the user might be interested in").
 *
 * After the background Home refresh, anything new on the board since the user last looked (a new story, video, score,
 * release or deadline, or a price that moved ≥ [MOVE_PCT] %) becomes one short heads-up through Proactive.say, so quiet
 * hours, digest mode and the de-duplication there all apply. Opening Home marks everything on it as seen. The very first
 * pass only learns what is there (no flood of "new" items). At most one Home heads-up every [GAP_MS] and [PER_DAY] a day.
 * Pure except the tiny store (keys `home_seen`, `home_news_last`, `home_news_day`); HomeNewsTest covers the logic.
 */
object HomeNews {
    data class Item(val widget: String, val type: String, val text: String, val key: String)

    /** Widget types worth a heads-up (weather, calendar and routines have their own nudges; apps never are "news"). */
    val worth = setOf(WidgetTypes.NEWS, WidgetTypes.CREATORS, WidgetTypes.SPORTS, WidgetTypes.PRICES, WidgetTypes.GITHUB,
        WidgetTypes.DEADLINES, WidgetTypes.CUSTOM)
    const val MOVE_PCT = 5.0
    const val GAP_MS = 4 * 3600_000L
    const val PER_DAY = 3
    const val KEEP = 600

    fun lineKey(w: Widget, l: WidgetLine): String = "${w.id}|" + (l.url.ifBlank { l.title }).trim().lowercase().take(160)

    /** Every key on the board right now (what "seen" means after the user opens Home). */
    fun keys(widgets: List<Widget>, data: Map<String, WidgetData>): Set<String> = widgets.filter { !it.hidden }.flatMap { w ->
        val d = data[w.id] ?: return@flatMap emptyList<String>()
        d.lines.map { lineKey(w, it) } + listOfNotNull(priceKey(w, d))
    }.toSet()

    /** A price move counts once per direction and 5 % band ("w1|move|+2" = up 10-15 %). */
    fun priceKey(w: Widget, d: WidgetData): String? {
        if (w.type != WidgetTypes.PRICES) return null
        val p = pct(d.change) ?: return null
        if (abs(p) < MOVE_PCT) return null
        return "${w.id}|move|${(p / MOVE_PCT).toInt()}"
    }

    fun pct(change: String): Double? = Regex("""([+\-−]?\d+(?:[.,]\d+)?)\s*%""").find(change)?.groupValues?.get(1)
        ?.replace('−', '-')?.replace(',', '.')?.toDoubleOrNull()

    /** What is new on the board that the user hasn't seen, best first, at most [max]. */
    fun fresh(widgets: List<Widget>, data: Map<String, WidgetData>, seen: Set<String>, max: Int = 6): List<Item> {
        val out = ArrayList<Item>()
        for (w in widgets.filter { !it.hidden && it.type in worth }) {
            val d = data[w.id] ?: continue
            if (d.error.isNotBlank()) continue
            priceKey(w, d)?.takeIf { it !in seen }?.let { k ->
                out += Item(w.title, w.type, "${w.title} ${if ((pct(d.change) ?: 0.0) > 0) "is up" else "is down"} ${d.change.trim()}".trim(), k)
            }
            for (l in d.lines.take(5)) {
                val k = lineKey(w, l)
                if (k in seen || l.title.isBlank()) continue
                out += Item(w.title, w.type, l.title.trim(), k)
            }
        }
        // One per widget first, so a busy news feed doesn't crowd out a score or a release.
        val firsts = out.distinctBy { it.widget }
        return (firsts + (out - firsts.toSet())).take(max)
    }

    /** One or two short sentences: "New on your Home: Arsenal 2-1 Chelsea (Sports), and 3 more stories in Tech news." */
    fun summary(items: List<Item>): String {
        if (items.isEmpty()) return ""
        val by = items.groupBy { it.widget }
        val lead = items.first()
        val first = "\u201c${lead.text.take(80)}\u201d (${lead.widget})"
        val rest = by.entries.filter { it.key != lead.widget || it.value.size > 1 }.map { (w, l) ->
            val n = if (w == lead.widget) l.size - 1 else l.size
            "$n new in $w"
        }.filter { !it.startsWith("0 ") }.take(3)
        return "New on your Home: $first" + (if (rest.isEmpty()) "." else ", plus ${rest.joinToString(", ")}.")
    }

    /** May a Home heads-up go out now? */
    fun mayTell(now: Long, last: Long, today: String, day: String, countToday: Int): Boolean =
        now - last >= GAP_MS && (today != day || countToday < PER_DAY)

    // ---------------- store ----------------
    private fun s() = com.blaze.assistant.agent.Settings
    var enabled: Boolean get() = s().bool("home_headsups", true); set(v) = s().setBool("home_headsups", v)
    fun seen(): Set<String>? = s().str("home_seen", "\u0000").let { if (it == "\u0000") null else it.split('\n').filter { k -> k.isNotBlank() }.toSet() }
    fun saveSeen(k: Set<String>) = s().setStr("home_seen", k.toList().takeLast(KEEP).joinToString("\n"))

    /** The user opened Home: everything on it now counts as seen. */
    fun markSeen(widgets: List<Widget>, data: Map<String, WidgetData>) {
        try { saveSeen((seen().orEmpty() + keys(widgets, data))) } catch (_: Throwable) { }
    }

    /** After a background refresh: tell the user about new things, or just learn the board on the first pass. */
    fun afterRefresh(widgets: List<Widget>, data: Map<String, WidgetData>) {
        try {
            val seen = seen()
            if (seen == null) { saveSeen(keys(widgets, data)); return }
            if (!enabled) return
            val items = fresh(widgets, data, seen)
            if (items.isEmpty()) return
            val now = System.currentTimeMillis()
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now))
            val day = s().str("home_news_day", "")
            val count = if (day == today) s().str("home_news_count", "0").toIntOrNull() ?: 0 else 0
            if (!mayTell(now, s().str("home_news_last", "0").toLongOrNull() ?: 0L, today, day, count)) return
            com.blaze.assistant.proactive.Proactive.say(summary(items), "home:" + items.joinToString { it.key }.hashCode(), urgent = false, suggest = "")
            s().setStr("home_news_last", now.toString()); s().setStr("home_news_day", today); s().setStr("home_news_count", (count + 1).toString())
            saveSeen(seen + items.map { it.key })   // told once; the rest waits for the next check or the user's own look
        } catch (_: Throwable) { }
    }
}
