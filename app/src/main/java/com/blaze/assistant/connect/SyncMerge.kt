package com.blaze.assistant.connect

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.40.0 cloud sync, the merge rules (pure, unit-tested in SyncMergeTest).
 *
 *  - Every item: last writer wins by the item's own change time ([Item.t], ms). A tie goes to the copy whose content
 *    sorts higher, so two phones always settle on the same one.
 *  - A delete is an item too (a tombstone): it wins over an older copy and loses to a newer one.
 *  - Chats are never "won": both copies are merged. Messages are a union keyed by who said it and when
 *    ([msgKey]), so two phones writing to the same conversation never lose a message. The chat's other fields
 *    (title, summary, model history…) come from the newer copy, and a field only the older copy has is kept. A /clear on either phone ([CLEARED]) hides every
 *    message up to that moment on both.
 */
object SyncMerge {
    const val CHAT = "chat"
    /** v0.45.0 (#37): one message of a chat, keyed "<chat>:<gid>" (ChatItems). */
    const val MSG = "msg"
    /** Chat field: messages at or before this time (ms) were cleared off the screen with /clear. */
    const val CLEARED = "cleared_at"

    /** One synced thing. [data] is null for a tombstone. */
    data class Item(val kind: String, val id: String, val t: Long, val data: JSONObject?) {
        val deleted get() = data == null
        /** The plaintext that goes into the blob. */
        fun envelope(): String = JSONObject().put("v", 1).put("kind", kind).put("id", id).put("t", t)
            .apply { if (data == null) put("del", true) else put("data", data) }.toString()
        companion object {
            fun parse(kind: String, id: String, plain: String): Item {
                val o = JSONObject(plain)
                require(o.optInt("v", 1) <= 1) { "made by a newer Blaze" }
                // The envelope repeats kind + id (the AAD already binds them); a mismatch means a broken blob.
                require(o.optString("kind", kind) == kind && o.optString("id", id) == id) { "item mismatch" }
                return Item(kind, id, o.optLong("t"), if (o.optBoolean("del")) null else o.optJSONObject("data") ?: JSONObject())
            }
        }
    }

    sealed class Outcome {
        /** Keep what this phone has (and push it). */
        object KeepLocal : Outcome()
        /** Take the other copy as it is. */
        object TakeRemote : Outcome()
        /** A merge of both: apply it here and push it. */
        data class Merged(val item: Item) : Outcome()
    }

    private fun weight(i: Item): String = i.data?.toString() ?: ""

    /** Last writer wins; chats merge (see the header). */
    fun resolve(local: Item, remote: Item): Outcome {
        if (local.kind == MSG && !local.deleted && !remote.deleted) {
            // One message edited on two phones: the fuller copy wins (a reply that finished streaming, a chip added).
            val b = better(local.data!!, remote.data!!)
            return when {
                same(local.data, remote.data) -> Outcome.TakeRemote
                b === local.data -> Outcome.KeepLocal
                else -> Outcome.TakeRemote
            }
        }
        if (local.kind == CHAT && !local.deleted && !remote.deleted) {
            val m = mergeChat(local.data!!, remote.data!!)
            val item = Item(CHAT, local.id, maxOf(local.t, remote.t), m)
            return when {
                same(m, withIds(remote.data)) -> Outcome.TakeRemote
                same(m, withIds(local.data)) -> Outcome.KeepLocal
                else -> Outcome.Merged(item)
            }
        }
        return when {
            local.t > remote.t -> Outcome.KeepLocal
            remote.t > local.t -> Outcome.TakeRemote
            weight(local) > weight(remote) -> Outcome.KeepLocal
            else -> Outcome.TakeRemote
        }
    }

    private fun same(a: JSONObject, b: JSONObject): Boolean = canon(a) == canon(b)
    /** A chat copy with the derived ids its old messages get on load (so "already has everything" still compares equal). */
    private fun withIds(o: JSONObject): JSONObject {
        val arr = o.optJSONArray("messages") ?: return o
        val c = JSONObject(o.toString()); val a = c.getJSONArray("messages")
        if (com.blaze.assistant.agent.Gid.fill(a) == 0) return o
        return c
    }
    private fun canon(o: JSONObject): String {
        val keys = o.keys().asSequence().sorted().toList()
        return keys.joinToString(",", "{", "}") { k -> "\"$k\":" + when (val v = o.get(k)) { is JSONObject -> canon(v); else -> v.toString() } }
    }

    // ---------------- chats ----------------

    /** Who said it and when: the same message on two phones has the same key, different messages (almost) never do. */
    fun msgKey(m: JSONObject): String {
        val at = m.optLong("at")
        val role = m.optString("role")
        return if (at > 0) "$role@$at" else "$role#${m.optLong("id")}#${m.optString("text").hashCode()}"
    }

    /** The fuller of two copies of one message (a reply that finished streaming beats its first half). */
    internal fun better(a: JSONObject, b: JSONObject): JSONObject {
        val la = a.optString("text").length; val lb = b.optString("text").length
        if (la != lb) return if (la > lb) a else b
        val sa = a.toString().length; val sb = b.toString().length
        if (sa != sb) return if (sa > sb) a else b
        // Same size: content order, so both phones pick the same copy.
        return if (canon(a) >= canon(b)) a else b
    }

    /** A message's own global id, or "" for one saved before ids (or given a derived one: those match by role@at). */
    private fun explicitGid(m: JSONObject): String =
        m.optString("gid").takeIf { it.isNotEmpty() && !com.blaze.assistant.agent.Gid.isLegacy(it) }.orEmpty()

    /**
     * Merges two copies of a chat file (ChatStore JSON): messages are the union by [msgKey], ordered by time; ids are
     * kept unless two different messages share one, then the later one gets a fresh id. Other fields come from the
     * copy with the newer "updated".
     */
    fun mergeChat(a: JSONObject, b: JSONObject): JSONObject {
        val newer = if (b.optLong("updated") > a.optLong("updated")) b else a
        val older = if (newer === a) b else a
        // Newer copy's fields over the older one's: a field the newer copy lacks (a trimmed copy has no model history) stays.
        val out = JSONObject(older.toString())
        for (k in newer.keys()) out.put(k, newer.get(k))
        val cleared = maxOf(a.optLong(CLEARED), b.optLong(CLEARED))
        val byKey = LinkedHashMap<String, JSONObject>()
        // v0.45.0 (#37): messages with a global id match by it (two bubbles of one reply may share role@at); a copy without
        // one (an older Blaze, or saved before ids) matches by role@at, joining the id'd message with the same role@at.
        // Reactions are kept from [a] (this phone's copy); they travel as their own items.
        val reactA = HashMap<String, Any?>()   // key → this phone's reactions (null = it has the message, without any)
        val gidAt = HashMap<String, String>()
        fun keyOf(m: JSONObject): String {
            val g = explicitGid(m)
            if (g.isNotEmpty()) return "g:$g"
            val k = msgKey(m)
            return gidAt[k] ?: k
        }
        val all = ArrayList<Pair<JSONObject, Boolean>>()   // (message, from a)
        for (src in listOf(older, newer)) {
            val arr = src.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until arr.length()) { val m = arr.optJSONObject(i) ?: continue; all += m to (src === a) }
        }
        for ((m, _) in all) { val g = explicitGid(m); if (g.isNotEmpty()) gidAt.putIfAbsent(msgKey(m), "g:$g") }
        for ((m, fromA) in all.sortedBy { if (explicitGid(it.first).isNotEmpty()) 0 else 1 }) {
            val k = keyOf(m)
            if (fromA && (m.has("reactions") || !reactA.containsKey(k))) reactA[k] = m.opt("reactions")
            val prev = byKey[k]
            var pick = if (prev == null) m else better(prev, m)
            // Never lose the global id when the fuller copy came from a phone that didn't keep it.
            val g = (prev?.let { explicitGid(it) }.orEmpty()).ifEmpty { explicitGid(m) }
            if (g.isNotEmpty() && explicitGid(pick) != g) pick = JSONObject(pick.toString()).put("gid", g)
            byKey[k] = pick
        }
        // This phone's reactions win for a message it has (a removal must not come back from the other copy).
        for ((k, m) in byKey.entries.toList()) {
            if (!reactA.containsKey(k)) continue
            val r = reactA[k]
            if (r == m.opt("reactions")) continue
            byKey[k] = JSONObject(m.toString()).apply { if (r == null) remove("reactions") else put("reactions", r) }
        }
        val list = byKey.values.filter { cleared <= 0 || it.optLong("at") <= 0 || it.optLong("at") > cleared }
            .sortedWith(compareBy<JSONObject>({ it.optLong("at").takeIf { t -> t > 0 } ?: Long.MAX_VALUE }, { it.optLong("id") }))
        val used = HashSet<Long>()
        var next = (list.maxOfOrNull { it.optLong("id") } ?: 0L) + 1
        val arr = JSONArray()
        for (m in list) {
            var id = m.optLong("id")
            val copy = JSONObject(m.toString())
            if (!used.add(id)) { id = next++; used += id; copy.put("id", id) }
            arr.put(copy)
        }
        com.blaze.assistant.agent.Gid.fill(arr)
        out.put("messages", arr)
        if (cleared > 0) out.put(CLEARED, cleared)
        out.put("updated", maxOf(a.optLong("updated"), b.optLong("updated")))
        return out
    }

    // ---------------- size ----------------

    /**
     * A chat too big for one item keeps its newest messages: first the model history goes, then older messages, until
     * [fits] says yes. Returns null when even the newest message alone doesn't fit. The union on the other phone never
     * drops what it already has, so trimming here loses nothing there.
     */
    fun trimChat(chat: JSONObject, fits: (JSONObject) -> Boolean): JSONObject? {
        if (fits(chat)) return chat
        val c = JSONObject(chat.toString()).apply { remove("history") }
        if (fits(c)) return c
        val msgs = c.optJSONArray("messages") ?: return null
        var keep = msgs.length()
        while (keep > 1) {
            keep = maxOf(1, keep * 3 / 4)
            val arr = JSONArray(); for (i in msgs.length() - keep until msgs.length()) arr.put(msgs.get(i))
            val t = JSONObject(c.toString()).put("messages", arr).put("trimmed", true)
            if (fits(t)) return t
        }
        val one = JSONObject(c.toString()).put("messages", JSONArray().apply { if (msgs.length() > 0) put(msgs.get(msgs.length() - 1)) }).put("trimmed", true)
        return if (fits(one)) one else null
    }
}
