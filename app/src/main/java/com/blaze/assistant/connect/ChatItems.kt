package com.blaze.assistant.connect

import com.blaze.assistant.agent.Gid
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * v0.45.0 (#37): chats sync as small items instead of whole-chat blobs.
 *
 *  - kind "chat"  ([ChatHeadAdapter]): the chat's own fields (title, summary, /clear time, rooms…), the model history
 *    when it is small, and its newest [TAIL] messages, so a phone on an older Blaze (which only knows whole chats)
 *    still gets new messages and merges them the way it always did. Old whole-chat items already in the cloud copy
 *    are still read and merged (same kind, same id).
 *  - kind "msg"   ([MessageAdapter]): one message, id "<chat>:<gid>" (Gid: globally unique, stable per bubble).
 *    A new message is one small push; an edit of one message re-sends only that message. Never pushed as a delete.
 *  - kind "react" ([ReactionAdapter]): one reaction, id "<chat>:<gid>:<by>:<emoji code points>". A 👍 is a tiny item;
 *    taking it back is a delete of that item.
 *
 * Every item is still sealed on its own with AES-GCM bound to kind + id (SyncCrypto); the gateway API is unchanged.
 * [ChatSplit] parses each chat file once per change (file stamp) and keeps the per-item stamps, so a round with nothing
 * new reads no file; applies are made to the parsed copy and written once per chat at [ChatSplit.flush].
 */
object ChatItems {
    const val HEAD = SyncKinds.CHATS
    const val MSG = SyncMerge.MSG
    const val REACT = "react"
    /** Newest messages carried in the chat item for phones on an older Blaze. */
    const val TAIL = 20
    /** The model history rides along in the chat item only below this size (it is rebuilt per phone otherwise). */
    const val HISTORY_MAX = 48 * 1024
    /** Fields of a message that are this phone's own (local id, history mark) or travel separately (reactions). */
    private val LOCAL = setOf("id", "mark", "reactions", "reply_to")

    fun hash(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.take(12).joinToString("") { "%02x".format(it) }
    }

    fun msgId(chat: String, gid: String): String? = "$chat:$gid".takeIf { SyncKinds.ID_RE.matches(it) && Gid.valid(gid) }
    fun splitMsgId(id: String): Pair<String, String>? {
        val i = id.lastIndexOf(':'); if (i <= 0 || i == id.length - 1) return null
        return id.substring(0, i) to id.substring(i + 1)
    }

    fun emojiCode(e: String): String = e.codePoints().toArray().joinToString("_") { Integer.toHexString(it) }
    fun emojiOf(code: String): String? = try {
        val cps = code.split('_').map { Integer.parseInt(it, 16) }.toIntArray()
        if (cps.isEmpty() || cps.size > 8) null else String(cps, 0, cps.size)
    } catch (_: Exception) { null }

    fun reactId(chat: String, gid: String, by: String, emoji: String): String? =
        "$chat:$gid:$by:${emojiCode(emoji)}".takeIf { SyncKinds.ID_RE.matches(it) && Gid.valid(gid) && by.matches(Regex("^[a-z]{1,12}$")) }
    /** (chat, gid, by, emoji) of a reaction item id. */
    fun splitReactId(id: String): List<String>? {
        val p = id.split(':'); if (p.size < 4) return null
        val emoji = emojiOf(p.last()) ?: return null
        return listOf(p.dropLast(3).joinToString(":"), p[p.size - 3], p[p.size - 2], emoji)
    }

    /**
     * A message as it travels: the same fields on every phone whatever wrote the file (ChatJson or a sync), sorted, empty
     * ones left out, so an unchanged message always has the same stamp (no ping-pong between phones).
     */
    fun normMsg(m: JSONObject, gidOfLocal: (Long) -> String? = { null }): JSONObject {
        val out = JSONObject()
        val keys = m.keys().asSequence().filter { it !in LOCAL }.sorted().toList()
        for (k in keys) {
            val v = m.opt(k)
            when {
                v == null || v == JSONObject.NULL -> {}
                v is String && v.isEmpty() -> {}
                v is JSONArray && v.length() == 0 -> {}
                v is Number && v.toLong() == 0L && k != "id" -> {}
                k == "thinking" && v is String -> out.put(k, v.take(20000))
                else -> out.put(k, v)
            }
        }
        // A reply's "answers m7" travels as global ids (local ids differ per phone).
        m.optJSONArray("reply_to")?.let { a ->
            val gs = (0 until a.length()).mapNotNull { gidOfLocal(a.optLong(it)) }
            if (gs.isNotEmpty()) out.put("reply_gids", JSONArray(gs))
        }
        return out
    }
}

/** The chats folder parsed once per change, shared by the three chat adapters (and kept between rounds by CloudSync). */
class ChatSplit(private val dir: () -> File) {
    class Chat(var stamp: String, val o: JSONObject, val heavy: Boolean = true) {
        /** gid → the message JSON in [o]'s "messages" (same objects). */
        val byGid = LinkedHashMap<String, JSONObject>()
        var head: SyncMerge.Item? = null; var headStamp = ""
        val msgStamps = HashMap<String, String>()      // item id → stamp
        val reactStamps = HashMap<String, String>()
        fun messages(): JSONArray = o.optJSONArray("messages") ?: JSONArray().also { o.put("messages", it) }
    }

    private val chats = HashMap<String, Chat>()
    private val dirty = LinkedHashSet<String>()
    /** The edits made to each dirty chat, replayed on a fresh read if the app saved the file meanwhile. */
    private val edits = HashMap<String, MutableList<(JSONObject) -> JSONObject?>>()

    private fun file(id: String) = File(dir(), "$id.json")
    private fun stampOf(f: File) = "${f.lastModified()}:${f.length()}"
    fun syncable(id: String) = SyncKinds.ID_RE.matches(id) && !id.startsWith("private")

    /** Every chat on disk (and the ones changed by sync, not yet written). Only files whose stamp changed are read. */
    @Synchronized fun all(): Map<String, Chat> {
        val seen = HashSet<String>()
        for (f in dir().listFiles() ?: emptyArray()) {
            if (!f.isFile || !f.name.endsWith(".json")) continue
            val id = f.name.removeSuffix(".json")
            if (!syncable(id)) continue
            seen += id
            if (id in dirty) continue
            val st = stampOf(f)
            val c = chats[id]
            if (c != null && c.stamp == st) continue
            val o = try { JSONObject(f.readText()) } catch (_: Exception) { chats.remove(id); continue }
            chats[id] = index(id, Chat(st, o))
        }
        chats.keys.retainAll(seen + dirty)
        return HashMap(chats)
    }

    /** One chat's stamps (may be a [release]d copy without its messages). */
    @Synchronized fun peek(id: String): Chat? {
        if (!syncable(id)) return null
        if (id in dirty) return chats[id]
        val f = file(id)
        if (!f.isFile) { chats.remove(id); return null }
        chats[id]?.let { if (it.stamp == stampOf(f)) return it }
        return get(id)
    }

    /** One chat with its messages (read again when it was released or changed on disk). */
    @Synchronized fun get(id: String): Chat? {
        if (!syncable(id)) return null
        if (id in dirty) return chats[id]
        val f = file(id)
        if (!f.isFile) { chats.remove(id); return null }
        val st = stampOf(f)
        chats[id]?.let { if (it.stamp == st && it.heavy) return it }
        val o = try { JSONObject(f.readText()) } catch (_: Exception) { return null }
        return index(id, Chat(st, o)).also { chats[id] = it }
    }

    /** Changes chat [id] in memory ([create]: start an empty chat when there is none); written at [flush]. */
    @Synchronized fun edit(id: String, create: Boolean, change: (JSONObject) -> JSONObject?): Boolean {
        val c = get(id) ?: if (create && syncable(id)) Chat("", JSONObject().put("id", id).put("title", "").put("updated", 0).put("messages", JSONArray())) else return false
        val next = change(c.o) ?: return false
        chats[id] = index(id, Chat(c.stamp, next))
        dirty += id
        edits.getOrPut(id) { ArrayList() } += change
        return true
    }

    /** Deletes chat [id] (a tombstone from another phone). */
    @Synchronized fun delete(id: String) { file(id).delete(); chats.remove(id); dirty.remove(id); edits.remove(id) }

    /** Writes every chat changed by sync (tmp + rename) and returns their ids. */
    @Synchronized fun flush(): List<String> {
        val done = ArrayList<String>()
        for (id in dirty.toList()) {
            var c = chats[id] ?: continue
            val f = file(id)
            // The app saved this chat while the round ran: start from its new file and replay the synced edits on it,
            // so nothing it wrote is lost.
            val now = if (f.isFile) stampOf(f) else ""
            if (now != c.stamp && now.isNotEmpty()) {
                val fresh = try { JSONObject(f.readText()) } catch (_: Exception) { null }
                if (fresh != null) {
                    var o: JSONObject = fresh
                    for (e in edits[id].orEmpty()) o = e(o) ?: o
                    c = index(id, Chat(now, o)); chats[id] = c
                }
            }
            val tmp = File(dir(), f.name + ".sync.tmp")
            tmp.writeText(c.o.toString())
            if (!tmp.renameTo(f)) {
                try { java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                catch (e: Exception) { tmp.delete(); throw e }
            }
            c.stamp = stampOf(f)
            dirty.remove(id); edits.remove(id); done += id
        }
        return done
    }

    @Synchronized fun pending(): Boolean = dirty.isNotEmpty()

    /**
     * After a round: keep only the stamps of each chat, not its parsed messages (a long main chat is megabytes of
     * JSON objects). The next round reads a chat again only when it changed or one of its items has to be sent.
     */
    @Synchronized fun release() {
        for ((id, c) in chats.entries.toList()) {
            if (id in dirty || !c.heavy) continue
            val l = Chat(c.stamp, JSONObject(), heavy = false)
            l.headStamp = c.headStamp; l.msgStamps.putAll(c.msgStamps); l.reactStamps.putAll(c.reactStamps)
            chats[id] = l
        }
    }

    /** Ids, stamps and the chat item of one parsed chat. */
    private fun index(id: String, c: Chat): Chat {
        val arr = c.messages()
        Gid.fill(arr)
        val localToGid = HashMap<Long, String>()
        for (i in 0 until arr.length()) { val m = arr.optJSONObject(i) ?: continue; localToGid[m.optLong("id")] = m.optString("gid") }
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val g = m.optString("gid")
            c.byGid[g] = m
            val mid = ChatItems.msgId(id, g) ?: continue
            c.msgStamps[mid] = ChatItems.hash(ChatItems.normMsg(m) { localToGid[it] }.toString())
            val rs = m.optJSONArray("reactions") ?: continue
            for (j in 0 until rs.length()) {
                val r = rs.optJSONObject(j) ?: continue
                val rid = ChatItems.reactId(id, g, r.optString("by", "user"), r.optString("e")) ?: continue
                c.reactStamps[rid] = r.optLong("at").toString()
            }
        }
        // The chat item: its own fields, a small history, and the newest messages for older phones.
        val head = JSONObject()
        for (k in c.o.keys().asSequence().sorted()) {
            if (k == "messages" || k == "history") continue
            head.put(k, c.o.get(k))
        }
        c.o.optJSONArray("history")?.let { h -> if (h.toString().length <= ChatItems.HISTORY_MAX) head.put("history", h) }
        val tail = JSONArray()
        for (i in maxOf(0, arr.length() - ChatItems.TAIL) until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            tail.put(JSONObject(m.toString()).apply { remove("reactions"); remove("mark") })
        }
        head.put("messages", tail).put("split", 1).put("id", id)
        c.head = SyncMerge.Item(ChatItems.HEAD, id, c.o.optLong("updated"), head)
        // "updated" and "preview" move on every save; the item only counts as changed when something else did.
        c.headStamp = ChatItems.hash(JSONObject(head.toString()).apply { remove("updated"); remove("preview") }.toString())
        return c
    }

    /** The local id → gid map of a chat (for reply_to). */
    fun gidOf(c: Chat): (Long) -> String? {
        val m = HashMap<Long, String>()
        for (o in c.byGid.values) m[o.optLong("id")] = o.optString("gid")
        return { m[it] }
    }
}

/** kind "chat": the chat's own fields + newest messages; also merges whole-chat items from older Blaze builds. */
class ChatHeadAdapter(private val split: ChatSplit, private val onApplied: (String) -> Unit = {}) : SyncEngine.Adapter {
    override val kind = ChatItems.HEAD

    override fun stamps(): Map<String, String> = split.all().mapValues { it.value.headStamp }
    override fun stamp(id: String): String? = split.peek(id)?.headStamp
    override fun read(id: String): SyncMerge.Item? = split.get(id)?.head

    override fun apply(item: SyncMerge.Item) {
        if (item.deleted) { split.delete(item.id); onApplied(item.id); return }
        val incoming = JSONObject(item.data!!.toString()).put("id", item.id)
        incoming.remove("split")
        // Never overwrite: a whole chat from an older phone or a chat item with just the newest messages is merged.
        split.edit(item.id, create = true) { here ->
            val out = if (here.optJSONArray("messages")?.length() ?: 0 > 0 || here.has("title")) SyncMerge.mergeChat(here, incoming) else incoming
            out.remove("trimmed"); out.remove("split")
            out
        }
    }

    override fun flush() { split.flush().forEach(onApplied) }

    override fun shrink(item: SyncMerge.Item, fits: (SyncMerge.Item) -> Boolean): SyncMerge.Item? {
        val data = item.data ?: return null
        val c = JSONObject(data.toString()).apply { remove("history") }
        if (fits(item.copy(data = c))) return item.copy(data = c)
        val msgs = c.optJSONArray("messages") ?: JSONArray()
        for (keep in listOf(5, 1, 0)) {
            val arr = JSONArray(); for (i in maxOf(0, msgs.length() - keep) until msgs.length()) arr.put(msgs.get(i))
            val t = JSONObject(c.toString()).put("messages", arr)
            if (fits(item.copy(data = t))) return item.copy(data = t)
        }
        val bare = JSONObject(c.toString()).put("messages", JSONArray()).apply { remove("rooms") }
        return if (fits(item.copy(data = bare))) item.copy(data = bare) else null
    }
}

/** kind "msg": one message per item ("<chat>:<gid>"). */
class MessageAdapter(
    private val split: ChatSplit,
    /** True when chat [id] was deleted on this phone (a late message must not bring it back). */
    private val deletedHere: (String) -> Boolean = { false },
) : SyncEngine.Adapter {
    override val kind = ChatItems.MSG
    override val pushDeletes get() = false

    override fun stamps(): Map<String, String> {
        val out = HashMap<String, String>()
        for (c in split.all().values) out.putAll(c.msgStamps)
        return out
    }

    override fun stamp(id: String): String? {
        val (chat, _) = ChatItems.splitMsgId(id) ?: return null
        return split.peek(chat)?.msgStamps?.get(id)
    }

    override fun read(id: String): SyncMerge.Item? {
        val (chat, gid) = ChatItems.splitMsgId(id) ?: return null
        val c = split.get(chat) ?: return null
        val m = c.byGid[gid] ?: return null
        val data = ChatItems.normMsg(m, split.gidOf(c))
        return SyncMerge.Item(kind, id, m.optLong("at").takeIf { it > 0 } ?: 1L, data)
    }

    override fun apply(item: SyncMerge.Item) {
        val (chat, gid) = ChatItems.splitMsgId(item.id) ?: return
        if (item.deleted) {
            split.edit(chat, create = false) { o -> removeGid(o, gid) }
            return
        }
        if (deletedHere(chat)) return
        val d = item.data!!
        if (d.optString("role").isEmpty()) return
        split.edit(chat, create = true) { o -> put(o, gid, d) }
    }

    override fun flush() { split.flush() }   // the chat adapter's flush tells the app; this one only writes

    override fun shrink(item: SyncMerge.Item, fits: (SyncMerge.Item) -> Boolean): SyncMerge.Item? {
        val d = JSONObject((item.data ?: return null).toString()).apply { remove("thinking") }
        if (fits(item.copy(data = d))) return item.copy(data = d)
        val text = d.optString("text")
        var n = minOf(text.length, 300_000)
        while (n > 1000) {
            val t = JSONObject(d.toString()).put("text", text.take(n) + "…")
            if (fits(item.copy(data = t))) return item.copy(data = t)
            n /= 2
        }
        return null
    }

    companion object {
        private fun removeGid(o: JSONObject, gid: String): JSONObject? {
            val arr = o.optJSONArray("messages") ?: return null
            val out = JSONArray(); var hit = false
            for (i in 0 until arr.length()) { val m = arr.optJSONObject(i) ?: continue; if (m.optString("gid") == gid) hit = true else out.put(m) }
            return if (hit) o.put("messages", out) else null
        }

        /** Puts one synced message into a chat: merges with the copy here, or inserts it in time order with a fresh local id. */
        fun put(o: JSONObject, gid: String, d: JSONObject): JSONObject? {
            val arr = o.optJSONArray("messages") ?: JSONArray()
            Gid.fill(arr)
            val cleared = o.optLong(SyncMerge.CLEARED)
            val at = d.optLong("at")
            if (cleared > 0 && at in 1..cleared) return null
            val byGid = HashMap<String, Int>(); val localOf = HashMap<String, Long>()
            var maxId = 0L
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                byGid[m.optString("gid")] = i; localOf[m.optString("gid")] = m.optLong("id"); maxId = maxOf(maxId, m.optLong("id"))
            }
            fun local(src: JSONObject, id: Long, keep: JSONObject?): JSONObject {
                val m = JSONObject()
                for (k in src.keys()) if (k != "reply_gids") m.put(k, src.get(k))
                m.put("id", id).put("gid", gid)
                src.optJSONArray("reply_gids")?.let { a ->
                    val ids = (0 until a.length()).mapNotNull { localOf[a.optString(it)] }
                    if (ids.isNotEmpty()) m.put("reply_to", JSONArray(ids))
                }
                keep?.let { k -> k.opt("reactions")?.let { m.put("reactions", it) }; if (k.has("mark")) m.put("mark", k.get("mark")) }
                return m
            }
            val idx = byGid[gid]
            if (idx != null) {
                val here = arr.getJSONObject(idx)
                val hereNorm = ChatItems.normMsg(here) { null }.apply { remove("reply_gids") }
                val inNorm = JSONObject(d.toString()).apply { remove("reply_gids") }
                if (hereNorm.toString() == inNorm.toString()) return null
                if (SyncMerge.better(hereNorm, inNorm) === hereNorm) return null
                arr.put(idx, local(d, here.optLong("id"), here))
                return o.put("messages", arr)
            }
            val m = local(d, maxId + 1, null)
            // Time order; a message with no time (saved before v1.13) stays where it is.
            var pos = arr.length()
            if (at > 0) for (i in arr.length() - 1 downTo 0) {
                val t = arr.optJSONObject(i)?.optLong("at") ?: 0L
                if (t in 1..at) { pos = i + 1; break }
                if (i == 0) pos = 0
            }
            val out = JSONArray()
            for (i in 0 until arr.length()) { if (i == pos) out.put(m); out.put(arr.get(i)) }
            if (pos >= arr.length()) out.put(m)
            if (at > o.optLong("updated")) o.put("updated", at)
            return o.put("messages", out)
        }
    }
}

/** kind "react": one reaction per item ("<chat>:<gid>:<by>:<emoji>"); taking it back is a delete. */
class ReactionAdapter(private val split: ChatSplit) : SyncEngine.Adapter {
    override val kind = ChatItems.REACT

    override fun stamps(): Map<String, String> {
        val out = HashMap<String, String>()
        for (c in split.all().values) out.putAll(c.reactStamps)
        return out
    }

    override fun stamp(id: String): String? {
        val p = ChatItems.splitReactId(id) ?: return null
        return split.peek(p[0])?.reactStamps?.get(id)
    }

    override fun read(id: String): SyncMerge.Item? {
        val (chat, gid, by, emoji) = ChatItems.splitReactId(id) ?: return null
        val m = split.get(chat)?.byGid?.get(gid) ?: return null
        val rs = m.optJSONArray("reactions") ?: return null
        for (i in 0 until rs.length()) {
            val r = rs.optJSONObject(i) ?: continue
            if (r.optString("e") == emoji && r.optString("by", "user") == by) {
                val at = r.optLong("at")
                return SyncMerge.Item(kind, id, at.takeIf { it > 0 } ?: 1L, JSONObject().put("e", emoji).put("by", by).put("at", at))
            }
        }
        return null
    }

    override fun apply(item: SyncMerge.Item) {
        val (chat, gid, by, emoji) = ChatItems.splitReactId(item.id) ?: return
        split.edit(chat, create = false) { o -> set(o, gid, by, emoji, if (item.deleted) null else item.data!!.optLong("at").takeIf { it > 0 } ?: item.t) }
    }

    override fun flush() { split.flush() }

    companion object {
        /** Adds ([at] ≠ null) or removes one reaction on message [gid]; null when nothing changed (or no such message). */
        fun set(o: JSONObject, gid: String, by: String, emoji: String, at: Long?): JSONObject? {
            val arr = o.optJSONArray("messages") ?: return null
            Gid.fill(arr)
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                if (m.optString("gid") != gid) continue
                val rs = m.optJSONArray("reactions") ?: JSONArray()
                val out = JSONArray(); var had: JSONObject? = null
                for (j in 0 until rs.length()) {
                    val r = rs.optJSONObject(j) ?: continue
                    if (r.optString("e") == emoji && r.optString("by", "user") == by) had = r else out.put(r)
                }
                if (at == null) {
                    if (had == null) return null
                } else {
                    if (had != null && had.optLong("at") == at) return null
                    out.put(JSONObject().put("e", emoji).put("by", by).put("at", at))
                }
                if (out.length() == 0) m.remove("reactions") else m.put("reactions", out)
                return o
            }
            return null
        }
    }
}
