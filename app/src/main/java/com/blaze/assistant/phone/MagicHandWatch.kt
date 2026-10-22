package com.blaze.assistant.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * Magic Hand "watch this" (v0.24.7), pure part (MagicHandTest): a watch is a value the user held the hand on (a
 * price, score, status) in some app, found again by its view id or its child-index path from the window root. This
 * file holds the data, its JSON (stored in filesDir/magic_watches.json by MagicHandWatches) and the diff rules that
 * decide whether a re-read value changed enough to notify.
 */
object MagicHandWatch {
    data class Watch(
        val id: String,
        val pkg: String,
        val app: String,
        val label: String,
        val path: List<Int>,
        val viewId: String,
        val className: String,
        val value: String,
        val created: Long,
        val lastChecked: Long = 0L,
        val lastChanged: Long = 0L,
        val previous: String = "",
    )

    data class Change(val changed: Boolean, val from: String, val to: String, val direction: Int = 0, val unreadable: Boolean = false) {
        /** "Price went down: K1,299 → K1,099" style line for the notification. */
        fun summary(label: String): String {
            val what = label.ifBlank { "Value" }
            return when {
                unreadable -> "$what couldn't be read"
                !changed -> "$what is still $to"
                direction > 0 -> "$what went up: $from \u2192 $to"
                direction < 0 -> "$what went down: $from \u2192 $to"
                else -> "$what changed: $from \u2192 $to"
            }
        }
    }

    /** Whitespace collapsed, trimmed, case-folded, invisible marks dropped: formatting noise never counts as a change. */
    fun normalize(s: String): String = s.replace('\u00a0', ' ').replace(Regex("[\u200b-\u200f\u2060\ufeff]"), "")
        .replace(Regex("\\s+"), " ").trim().lowercase()

    /** The first number in [s] (thousands separators allowed, '.' or ',' decimal), or null. "K 1,299.50" → 1299.5. */
    fun number(s: String): Double? {
        val m = Regex("""[-+]?\d[\d,. ]*""").find(s.replace('\u00a0', ' ')) ?: return null
        var t = m.value.trim().replace(" ", "")
        if (t.isEmpty()) return null
        val lastDot = t.lastIndexOf('.'); val lastComma = t.lastIndexOf(',')
        t = when {
            lastDot >= 0 && lastComma >= 0 -> if (lastDot > lastComma) t.replace(",", "") else t.replace(".", "").replace(',', '.')
            lastComma >= 0 -> if (t.length - lastComma - 1 == 3 && t.count { it == ',' } >= 1) t.replace(",", "") else t.replace(',', '.')
            lastDot >= 0 && t.count { it == '.' } > 1 -> t.replace(".", "")
            else -> t
        }
        return t.trimEnd('.').toDoubleOrNull()
    }

    /**
     * Did the watched value change? A blank or missing re-read is "unreadable", never a change (the app may not be
     * showing it). Numbers that are equal ignoring formatting aren't a change; otherwise any difference after
     * [normalize] is. [direction] is +1 / -1 when both sides are numbers.
     */
    fun diff(old: String, new: String?): Change {
        val n = new?.trim().orEmpty()
        if (n.isEmpty()) return Change(false, old, old, unreadable = true)
        val textSame = normalize(old) == normalize(n)
        if (textSame) return Change(false, old, n)
        val numRe = Regex("""[-+]?\d[\d,. ]*\d|[-+]?\d""")
        val na = numRe.findAll(old).mapNotNull { number(it.value) }.toList()
        val nb = numRe.findAll(n).mapNotNull { number(it.value) }.toList()
        if (na.isNotEmpty() && nb.isNotEmpty()) {
            val restA = normalize(numRe.replace(old, "#")); val restB = normalize(numRe.replace(n, "#"))
            if (na == nb && restA == restB) return Change(false, old, n)
            val dir = if (na.size == 1 && nb.size == 1) nb[0].compareTo(na[0]).coerceIn(-1, 1) else 0
            return Change(true, old, n, direction = dir)
        }
        return Change(true, old, n)
    }

    /** Minimum gap between re-reads of one watch, whether from WorkManager or the app coming to the front. */
    const val MIN_GAP_MS = 60 * 60_000L
    /** The periodic check interval (WorkManager's own floor is 15 min; Blaze uses an hour to be kind to the battery). */
    const val CHECK_HOURS = 1L

    fun due(w: Watch, now: Long, gapMs: Long = MIN_GAP_MS): Boolean = now - w.lastChecked >= gapMs

    /** After a re-read: the updated watch and what changed. */
    fun apply(w: Watch, read: String?, now: Long): Pair<Watch, Change> {
        val c = diff(w.value, read)
        val nw = if (c.changed) w.copy(value = c.to, previous = w.value, lastChecked = now, lastChanged = now) else w.copy(lastChecked = now)
        return nw to c
    }

    fun toJson(w: Watch): JSONObject = JSONObject()
        .put("id", w.id).put("pkg", w.pkg).put("app", w.app).put("label", w.label)
        .put("path", JSONArray().apply { w.path.forEach { put(it) } })
        .put("viewId", w.viewId).put("className", w.className).put("value", w.value)
        .put("created", w.created).put("lastChecked", w.lastChecked).put("lastChanged", w.lastChanged).put("previous", w.previous)

    fun fromJson(o: JSONObject): Watch? {
        val id = o.optString("id"); val pkg = o.optString("pkg")
        if (id.isBlank() || pkg.isBlank()) return null
        val p = o.optJSONArray("path") ?: JSONArray()
        return Watch(id, pkg, o.optString("app"), o.optString("label"), (0 until p.length()).map { p.optInt(it) },
            o.optString("viewId"), o.optString("className"), o.optString("value"), o.optLong("created"),
            o.optLong("lastChecked"), o.optLong("lastChanged"), o.optString("previous"))
    }

    fun listToJson(list: List<Watch>): String = JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()
    fun listFromJson(s: String): List<Watch> = try {
        val a = JSONArray(s); (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }
    } catch (_: Exception) { emptyList() }
}
