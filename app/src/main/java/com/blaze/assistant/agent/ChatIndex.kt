package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * v0.45.0 (#1, #7): the chat list without parsing every chat.
 *
 * Before, every save and every cold start parsed every chat file (each with its full model history) just to read
 * id, title, updated, pinned and preview. Now each chat's header is kept in memory with the file's stamp
 * (lastModified:length) and in a small index file (`.chat_index`, no `.json` suffix so it is never taken for a chat or
 * synced). [list] only stats the directory: a file whose stamp matches is not read again; a file written by someone else
 * (cloud sync, an older build) has a new stamp and is re-read, so the index heals itself and can never hide a chat.
 *
 * [write] is the one atomic write (tmp file + rename, falling back to Files.move) and reports failure instead of
 * swallowing it.
 *
 * Plain java.io + org.json, so it runs in JVM unit tests (ChatIndexTest).
 */
class ChatIndex(private val dir: File) {
    private data class Entry(val meta: ChatMeta, val stamp: String)

    private val lock = Any()
    private var cache: HashMap<String, Entry>? = null
    /** How many chat files were parsed (tests check that a save or a list does not re-read every chat). */
    @Volatile var parses = 0; private set

    private val indexFile get() = File(dir, INDEX)

    private fun stampOf(f: File) = "${f.lastModified()}:${f.length()}"

    private fun loaded(): HashMap<String, Entry> {
        cache?.let { return it }
        val m = HashMap<String, Entry>()
        try {
            if (indexFile.isFile) {
                val a = JSONObject(indexFile.readText()).optJSONArray("chats") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isEmpty()) continue
                    m[id] = Entry(ChatMeta(id, o.optString("title", "Chat"), o.optLong("updated"), o.optBoolean("pinned"), o.optString("preview")), o.optString("stamp"))
                }
            }
        } catch (_: Exception) { m.clear() }   // a broken index is rebuilt from the files
        cache = m
        return m
    }

    private fun parse(f: File): ChatMeta? = try {
        parses++
        val o = JSONObject(f.readText())
        headerOf(o)
    } catch (_: Exception) { null }

    /** Every chat, pinned first then newest. Re-reads only files that changed since they were indexed. */
    fun list(): List<ChatMeta> = synchronized(lock) {
        val m = loaded()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        val seen = HashSet<String>()
        var dirty = false
        for (f in files) {
            val id = f.name.removeSuffix(".json")
            seen += id
            val st = stampOf(f)
            val e = m[id]
            if (e != null && e.stamp == st) continue
            val meta = parse(f)
            if (meta == null) { if (e != null) { m.remove(id); dirty = true }; continue }
            m[id] = Entry(meta, st); dirty = true
        }
        if (m.keys.retainAll(seen)) dirty = true
        if (dirty) writeIndex(m)
        m.values.map { it.meta }.sortedWith(ORDER)
    }

    /** Writes chat [o] atomically and updates its index row without re-reading it. False when the write failed. */
    fun save(o: JSONObject): Boolean = saveText(o, o.toString())

    /** v0.45.0 (#11): [text] is the whole file, already made (ChatJson); [head] carries its header fields. */
    fun saveText(head: JSONObject, text: String): Boolean {
        val o = head
        val id = o.optString("id").ifEmpty { return false }
        val f = File(dir, "$id.json")
        val ok = write(dir, f, text)
        synchronized(lock) {
            val m = loaded()
            if (ok) m[id] = Entry(headerOf(o), stampOf(f)) else m.remove(id)   // a failed write is re-read from disk later
            writeIndex(m)
        }
        return ok
    }

    fun forget(id: String) = synchronized(lock) { val m = loaded(); if (m.remove(id) != null) writeIndex(m) }

    fun clear() = synchronized(lock) { cache = HashMap(); try { indexFile.delete() } catch (_: Exception) { } }

    private fun writeIndex(m: Map<String, Entry>) {
        val a = JSONArray()
        for (e in m.values) a.put(JSONObject().put("id", e.meta.id).put("title", e.meta.title).put("updated", e.meta.updated)
            .put("pinned", e.meta.pinned).put("preview", e.meta.preview).put("stamp", e.stamp))
        write(dir, indexFile, JSONObject().put("v", 1).put("chats", a).toString())   // best effort: the files are the truth
    }

    companion object {
        const val INDEX = ".chat_index"
        val ORDER: Comparator<ChatMeta> = compareByDescending<ChatMeta> { it.pinned }.thenByDescending { it.updated }

        fun headerOf(o: JSONObject) = ChatMeta(o.getString("id"), o.optString("title", "Chat"), o.optLong("updated"),
            o.optBoolean("pinned"), o.optString("preview"))

        /** Test hook: makes the rename step fail so the fallback path can be checked. */
        @Volatile var renameHook: ((File, File) -> Boolean)? = null

        /**
         * Atomic write: [text] to a tmp file, then rename over [f]; if the rename fails (some FUSE paths), Files.move
         * with REPLACE_EXISTING (atomic when the filesystem allows). Returns false (and leaves the old file intact)
         * when nothing worked, e.g. the disk is full.
         */
        fun write(dir: File, f: File, text: String): Boolean {
            val tmp = File(dir, f.name + ".tmp")
            try {
                tmp.writeText(text)
            } catch (_: Exception) { try { tmp.delete() } catch (_: Exception) { }; return false }
            val renamed = try { renameHook?.invoke(tmp, f) ?: tmp.renameTo(f) } catch (_: Exception) { false }
            if (renamed) return true
            return try {
                try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                true
            } catch (_: Exception) { try { tmp.delete() } catch (_: Exception) { }; false }
        }
    }
}
