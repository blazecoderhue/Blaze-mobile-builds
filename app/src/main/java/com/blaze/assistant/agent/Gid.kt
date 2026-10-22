package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * v0.45.0 (#37, R2): globally unique message ids.
 *
 * Every bubble gets a [ChatMessage.gid] when it is made: "<device>-<time-ordered counter>" (base 36), so two phones
 * never make the same id and one model reply split into several bubbles gives each bubble its own id. Updates go
 * through `copy()`, which keeps the id, so a bubble's id is stable for its whole life.
 *
 * Messages saved before ids existed get a deterministic id from what they are ([legacy]: role, time and text), so
 * the same old message gets the same id on every phone and on every load (migration is idempotent; nothing has to be
 * written first). Two identical old messages in one chat are told apart by their order ([fill]).
 */
object Gid {
    /** This phone's id prefix (8 base-36 chars). Set from prefs at start ([init]); a random one until then. */
    @Volatile var device: String = randomDevice(); private set
    private var last = 0L
    private val legacyRe = Regex("^l[0-9a-f]{16}(-[0-9]+)?$")

    const val PREF = "msg_gid_device"

    private fun randomDevice(): String {
        val r = java.security.SecureRandom()
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        return (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    /** Reads (or makes and stores) this phone's prefix. Safe to call more than once. */
    fun init(read: () -> String, write: (String) -> Unit) {
        try {
            val saved = read().trim()
            if (Regex("^[0-9a-z]{8}$").matches(saved)) { device = saved; return }
            write(device)
        } catch (_: Throwable) { }
    }

    /** A new id. Monotonic on this phone (time × 1000 + counter), so it also survives restarts and fast bursts. */
    @Synchronized fun next(now: Long = System.currentTimeMillis()): String {
        val v = maxOf(last + 1, now * 1000)
        last = v
        return device + "-" + java.lang.Long.toString(v, 36)
    }

    /** The id an old message (saved before ids) gets: the same on every phone and on every load. */
    fun legacy(role: String, at: Long, text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("$role\u0000$at\u0000$text".toByteArray())
        return "l" + d.take(8).joinToString("") { "%02x".format(it) }
    }

    fun isLegacy(gid: String) = legacyRe.matches(gid)

    /** Valid for a sync item id part: [A-Za-z0-9._-], at most 64 chars. */
    fun valid(gid: String) = gid.isNotEmpty() && gid.length <= 64 && gid.all { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '_' || it == '-' }

    /**
     * Assigns ids to the messages of one chat that have none (in place on the JSON, returns how many were added).
     * A duplicate legacy id in the same chat gets "-2", "-3"… in order, so the result is the same everywhere.
     */
    fun fill(messages: JSONArray): Int {
        val seen = HashSet<String>()
        var added = 0
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            var g = m.optString("gid")
            if (g.isEmpty() || !valid(g)) {
                val base = legacy(m.optString("role"), m.optLong("at"), m.optString("text"))
                g = base; var n = 2
                while (g in seen) g = base + "-" + n++
                m.put("gid", g); added++
            } else if (g in seen) {
                // Two messages with one id (a copy made by hand, or a broken merge): the later one gets a fresh id.
                g = next(); m.put("gid", g); added++
            }
            seen += g
        }
        return added
    }

    /** The same rule for parsed messages: [ids] in order, empty = none yet; returns the ids to use. */
    fun fillList(items: List<Triple<String, Long, String>>, ids: List<String>): List<String> {
        val arr = JSONArray()
        items.forEachIndexed { i, (role, at, text) -> arr.put(JSONObject().put("role", role).put("at", at).put("text", text).apply { if (ids[i].isNotEmpty()) put("gid", ids[i]) }) }
        fill(arr)
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("gid") }
    }
}

/** v0.45.0 message reactions (R1). [by] = "user", "blaze" or "halo"; [at] = when (ms). */
@androidx.compose.runtime.Immutable
data class Reaction(val emoji: String, val by: String = "user", val at: Long = System.currentTimeMillis()) {
    fun toJson(): JSONObject = JSONObject().put("e", emoji).put("by", by).put("at", at)

    companion object {
        /** The fixed small set offered in the picker (👍 / 👎 also teach Blaze; see ReactionLearn). */
        val PICKS = listOf("👍", "👎", "❤️", "😂", "😮", "🙏")
        const val UP = "👍"
        const val DOWN = "👎"

        fun fromJson(o: JSONObject?): Reaction? {
            val e = o?.optString("e").orEmpty()
            if (e.isEmpty() || e.length > 16) return null
            return Reaction(e, o!!.optString("by", "user").ifEmpty { "user" }, o.optLong("at"))
        }

        fun list(a: JSONArray?): List<Reaction> {
            if (a == null || a.length() == 0) return emptyList()
            return (0 until a.length()).mapNotNull { fromJson(a.optJSONObject(it)) }.distinctBy { it.by + "\u0000" + it.emoji }
        }

        fun json(list: List<Reaction>): JSONArray = JSONArray().apply { list.forEach { put(it.toJson()) } }

        /** Toggles [emoji] by [by]: removes it when there, else adds it. 👍 and 👎 by the same person replace each other. */
        fun toggle(list: List<Reaction>, emoji: String, by: String = "user", now: Long = System.currentTimeMillis()): List<Reaction> {
            if (list.any { it.by == by && it.emoji == emoji }) return list.filterNot { it.by == by && it.emoji == emoji }
            val opposite = when (emoji) { UP -> DOWN; DOWN -> UP; else -> null }
            return list.filterNot { it.by == by && it.emoji == opposite } + Reaction(emoji, by, now)
        }
    }
}
