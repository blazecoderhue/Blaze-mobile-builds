package com.blaze.assistant.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Long-chat engine (v0.15.0), a port of Blaze Desktop's internal/longchat: Blaze never re-reads
 * the whole chat.
 *
 *  - Rolling summary: when the un-summarised part of a chat is over [budget] (12k tokens on free
 *    models, or 60% of the context window when that is smaller), everything before the last
 *    [KEEP_TAIL] messages folds into a state note (Goal, Decisions, Facts, Open threads, Files
 *    touched). The note is updated, never redone from scratch.
 *  - search_chat / read_turn: every message goes into an SQLite FTS4 index (filesDir/longchat.db),
 *    so exact older text is one search away.
 *  - Big outputs aside: tool results over [ASIDE_OVER] characters are saved to
 *    ~/.blaze/artifacts/<id>.txt; the model sees the head, the tail and artifact:<id>.
 *  - Token meter: [tokens] of what was sent, shown in the live work card.
 *
 * Everything here except [Index] is plain JSON logic so it is unit-tested on the JVM.
 */
object LongChat {
    const val KEEP_TAIL = 12
    const val FREE_BUDGET = 12_000
    const val SUMMARY_MAX = 3_200
    const val ASIDE_OVER = 4_000
    private const val HEAD_LINES = 40
    private const val TAIL_LINES = 20

    const val SUMMARISE_PROMPT = "You keep the state note of a long chat between a user and Blaze, an assistant on their Android phone. " +
        "Update the note with the new turns below. Keep exactly these sections: Goal, Decisions (with why), Facts (names, numbers, paths and commands exactly), " +
        "Open threads, Files touched. At most 800 tokens. Drop chit-chat and tool noise. Never invent anything. Reply with the note only."

    const val PROMPT_LINE = "\nLONG CHATS\n- Older turns of this chat may be folded into a summary. If you need something older than the summary, call search_chat " +
        "(then read_turn for the full message). Never ask the user to repeat something they already said.\n" +
        "- A tool result that ends with artifact:<id> was set aside because it was long: read_artifact reads the lines you need.\n" +
        "- In a project, update_brief keeps the project's brief (purpose and rules first, then key files, in progress; hard cap 600 tokens, only its top part is pinned). Update it when something lasting changes; details go to the searchable notes (project_docs_search).\n"

    /** Tools whose results always stay whole (reading them back must not be set aside again). */
    val keepWhole = setOf("use_skill", "skill_file", "read_artifact", "read_turn", "search_chat", "update_brief", "ask_user")

    /** A chat's long-chat state: the rolling note and the first history index it doesn't cover. */
    data class State(val summary: String = "", val upto: Int = 0)

    /** Plain text of a message's content (string, content parts, or null for a bare tool call). */
    fun text(m: JSONObject): String {
        val c = m.opt("content")
        val base = when (c) {
            is String -> c
            is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.takeIf { p -> p.optString("type") == "text" }?.optString("text") }.joinToString("\n")
            else -> ""
        }
        val calls = m.optJSONArray("tool_calls") ?: return base
        val sb = StringBuilder(base)
        for (i in 0 until calls.length()) {
            val f = calls.optJSONObject(i)?.optJSONObject("function") ?: continue
            sb.append("\n[calls ").append(f.optString("name")).append(' ').append(clip(f.optString("arguments"), 200)).append(']')
        }
        return sb.toString()
    }

    /** Prompt size estimate: 4 characters a token, plus a little per message and per call. */
    fun tokens(ms: JSONArray, from: Int = 0): Int {
        var n = 0
        for (i in from until ms.length()) {
            val m = ms.optJSONObject(i) ?: continue
            val c = m.opt("content")
            n += when (c) {
                is String -> c.length / 4
                is JSONArray -> (0 until c.length()).sumOf { k -> c.optJSONObject(k)?.let { p -> if (p.optString("type") == "image_url") 800 else p.optString("text").length / 4 } ?: 0 }
                else -> 0
            } + 4
            val calls = m.optJSONArray("tool_calls")
            if (calls != null) for (k in 0 until calls.length()) calls.optJSONObject(k)?.optJSONObject("function")?.let { f ->
                n += (f.optString("name").length + f.optString("arguments").length) / 4 }
        }
        return n
    }

    /** Context window for a model id: Settings › context_tokens when set, else a guess from the name (0 = unknown). */
    fun contextWindow(model: String, override: Int = 0): Int {
        if (override > 0) return override
        val m = model.lowercase()
        Regex("(\\d+)k\\b").find(m)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 2..2048) return it * 1024 }
        return when {
            m.contains("8192") -> 8192
            m.contains("32768") -> 32768
            m.contains("gemini") -> 1_000_000
            m.contains("gemma") -> 8192
            listOf("gpt-4o", "gpt-4.1", "llama-3", "llama3", "llama-4", "qwen", "deepseek", "mistral", "kimi", "glm", "claude", "auto/", "command-r").any { m.contains(it) } -> 128_000
            else -> 0
        }
    }

    /** When compaction starts for a model with this context window (0 = unknown). */
    fun budget(contextWindow: Int): Int = if (contextWindow > 0 && contextWindow * 6 / 10 < FREE_BUDGET) contextWindow * 6 / 10 else FREE_BUDGET

    /** The meter's limit: the model's context window, or 32k when unknown (like Desktop). */
    fun meterLimit(contextWindow: Int) = if (contextWindow > 0) contextWindow else 32_768

    /** "in 4.2k · out 0.6k" */
    fun meter(inTok: Int, outTok: Int): String = "in ${k(inTok)} · out ${k(outTok)}"
    fun k(n: Int): String = if (n < 1000) "$n" else "%.1fk".format(java.util.Locale.US, n / 1000.0)
    fun high(inTok: Int, limit: Int) = limit > 0 && inTok * 10 >= limit * 8

    private fun role(h: JSONArray, i: Int) = h.optJSONObject(i)?.optString("role").orEmpty()

    /** Where the word-for-word tail starts: [keep] (KEEP_TAIL) from the end, moved back to a user message. */
    fun tailStart(h: JSONArray, keep: Int = KEEP_TAIL): Int {
        var i = h.length() - keep
        if (i <= 0) return 0
        while (i > 0 && role(h, i) != "user") i--
        return i
    }

    /** True when the chat should fold older turns into the summary now. */
    fun needsCompaction(h: JSONArray, st: State, budget: Int): Boolean {
        val upto = if (st.upto > h.length()) 0 else st.upto
        return tailStart(h) > upto && tokens(h, upto) > budget
    }

    /** The new state after [summary] covers everything before the tail. */
    fun folded(h: JSONArray, summary: String, keep: Int = KEEP_TAIL): State {
        val s = summary.trim().let { if (it.length > SUMMARY_MAX) it.take(SUMMARY_MAX) else it }
        return State(s, tailStart(h, keep))
    }

    /** First history index to send given the state: never a tool result or a call cut from its user turn. */
    fun sendFrom(h: JSONArray, st: State): Int {
        var from = if (st.upto > h.length()) 0 else st.upto
        while (from < h.length() && from > 0 && role(h, from) != "user") from++
        return from
    }

    /** The system text for the summary. */
    fun summaryBlock(st: State): String = if (st.summary.isBlank()) "" else
        "\nEARLIER IN THIS CHAT (summary of turns 0-${(st.upto - 1).coerceAtLeast(0)}; use search_chat or read_turn for exact text, never ask the user to repeat)\n${st.summary}\n"

    /** A chunk of history rendered for the summariser (tool output clipped). */
    fun transcript(h: JSONArray, from: Int, to: Int): String {
        val b = StringBuilder()
        for (i in from until minOf(to, h.length())) {
            val m = h.optJSONObject(i) ?: continue
            var c = text(m)
            if (m.optString("role") == "tool" && c.length > 600) c = c.take(400) + " … " + c.takeLast(150)
            if (c.isBlank()) continue
            b.append('[').append(i).append(' ').append(m.optString("role")).append("] ").append(c).append('\n')
        }
        return b.toString()
    }

    fun summariseRequest(prev: String, h: JSONArray, from: Int, to: Int): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", SUMMARISE_PROMPT))
        .put(JSONObject().put("role", "user").put("content", "CURRENT NOTE:\n" + prev.ifBlank { "(none yet)" } + "\n\nNEW TURNS:\n" + transcript(h, from, to)))

    private fun clip(s: String, n: Int) = if (s.length <= n) s else s.substring(0, Scripts.safeCut(s, n)) + "…"  // v0.45.7: whole letters

    // ---------------- artifacts ----------------

    @Volatile var artifactDirOverride: File? = null
    private fun artifactDir(): File = artifactDirOverride ?: File(com.blaze.assistant.terminal.Terminal.home(), ".blaze/artifacts")
    private val idRe = Regex("^[a-f0-9]{12}$")

    /** Saves a big tool result and returns what the model sees instead. Small results come back unchanged. */
    fun aside(res: String): String {
        if (res.length <= ASIDE_OVER) return res
        val id = MessageDigest.getInstance("SHA-1").digest(res.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        try {
            val d = artifactDir().apply { mkdirs() }
            val f = File(d, "$id.txt")
            if (!f.exists()) f.writeText(res)
        } catch (_: Exception) { return res }
        val lines = res.split("\n")
        val n = lines.size
        val b = StringBuilder()
        if (n <= HEAD_LINES + TAIL_LINES) {
            b.append(res.take(2400)).append("\n… [${res.length - 3200} more characters] …\n").append(res.takeLast(800))
        } else {
            b.append(lines.take(HEAD_LINES).joinToString("\n")).append("\n… [${n - HEAD_LINES - TAIL_LINES} lines not shown] …\n")
                .append(lines.takeLast(TAIL_LINES).joinToString("\n"))
        }
        b.append("\n\nartifact:$id ($n lines, ${res.length} characters). The full output is saved; read_artifact {id, from, lines} reads any part of it.")
        return b.toString()
    }

    /** Lines [from, from+count) of an artifact (1-based), numbered. */
    fun readArtifact(id0: String, from0: Int, count0: Int): String {
        val id = id0.trim().removePrefix("artifact:").trim()
        if (!idRe.matches(id)) return "Error: unknown artifact \u201c$id0\u201d."
        val f = File(artifactDir(), "$id.txt")
        if (!f.isFile) return "Error: artifact $id is gone."
        val lines = f.readText().split("\n")
        val from = from0.coerceAtLeast(1)
        val count = if (count0 <= 0 || count0 > 400) 200 else count0
        if (from > lines.size) return "Error: artifact $id has ${lines.size} lines."
        val end = minOf(lines.size, from - 1 + count)
        val sb = StringBuilder()
        for (i in from - 1 until end) sb.append(i + 1).append(": ").append(lines[i].take(2000)).append('\n')
        if (end < lines.size) sb.append("… ${lines.size - end} more lines (read_artifact from=${end + 1}).")
        return sb.toString().take(24_000)
    }

    // ---------------- FTS query ----------------

    /** User words → a safe FTS4 MATCH query (each word as a prefix term, OR'd). "" when nothing searchable. */
    fun ftsQuery(q: String): String = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.distinct().take(8)
        .joinToString(" OR ") { "$it*" }

    // ---------------- the index ----------------

    data class Hit(val chat: String, val turn: Int, val role: String, val snippet: String)

    /** FTS4 index of every chat message (filesDir/longchat.db). Android's SQLite ships FTS3/4. */
    class Index(ctx: Context) : SQLiteOpenHelper(ctx, "longchat.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            try { db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS chat_fts USING fts4(chat_id, turn_id, role, content, notindexed=chat_id, notindexed=turn_id, notindexed=role)") }
            catch (_: Exception) { db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS chat_fts USING fts4(chat_id, turn_id, role, content)") }
            db.execSQL("CREATE TABLE IF NOT EXISTS chat_state(chat_id TEXT PRIMARY KEY, indexed INTEGER NOT NULL DEFAULT 0)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { }

        fun indexed(chat: String): Int = readableDatabase.rawQuery("SELECT indexed FROM chat_state WHERE chat_id=?", arrayOf(chat)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0 }

        /** Adds history[from until h.length] for [chat]; a shorter history than indexed is re-indexed. */
        @Synchronized fun sync(chat: String, h: JSONArray) {
            if (PrivateChat.isPrivateId(chat)) return   // v0.24.4: private chats are never indexed
            var from = indexed(chat)
            if (from > h.length()) { forget(chat); from = 0 }
            if (from >= h.length()) return
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (i in from until h.length()) {
                    val m = h.optJSONObject(i) ?: continue
                    val t = text(m).take(6000)
                    if (t.isBlank()) continue
                    db.insert("chat_fts", null, ContentValues().apply {
                        put("chat_id", chat); put("turn_id", i.toString()); put("role", m.optString("role")); put("content", t) })
                }
                db.insertWithOnConflict("chat_state", null, ContentValues().apply { put("chat_id", chat); put("indexed", h.length()) }, SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }

        @Synchronized fun forget(chat: String) {
            writableDatabase.delete("chat_fts", "chat_id=?", arrayOf(chat))
            writableDatabase.delete("chat_state", "chat_id=?", arrayOf(chat))
        }

        fun search(query: String, chat: String?, limit: Int): List<Hit> {
            val q = ftsQuery(query); if (q.isEmpty()) return emptyList()
            val sql = "SELECT chat_id, turn_id, role, snippet(chat_fts, '[', ']', '…', 3, 24) FROM chat_fts WHERE chat_fts MATCH ?" +
                (if (chat != null) " AND chat_id=?" else "") + " LIMIT ?"
            val args = if (chat != null) arrayOf(q, chat, limit.toString()) else arrayOf(q, limit.toString())
            return readableDatabase.rawQuery(sql, args).use { c ->
                val out = ArrayList<Hit>()
                while (c.moveToNext()) out += Hit(c.getString(0), c.getString(1).toIntOrNull() ?: 0, c.getString(2), c.getString(3))
                out
            }
        }
    }

    @Volatile private var index: Index? = null
    fun init(ctx: Context) { if (index == null) index = try { Index(ctx.applicationContext) } catch (_: Throwable) { null } }
    fun db(): Index? = index

    /** Search one chat (or all, with backfill of chats never indexed), formatted for the model. */
    fun searchText(query: String, chat: String, all: Boolean, limit: Int, load: (String) -> JSONArray?, chats: () -> List<String>): String {
        val d = index ?: return "Search isn't available on this phone."
        if (ftsQuery(query).isEmpty()) return "Give me some words to search for."
        return try {
            if (all) chats().forEach { id -> if (d.indexed(id) == 0) load(id)?.let { d.sync(id, it) } }
            else load(chat)?.let { d.sync(chat, it) }
            val hits = d.search(query, if (all) null else chat, limit.coerceIn(1, 20))
            if (hits.isEmpty()) "Nothing in ${if (all) "your chats" else "this chat"} matches \u201c$query\u201d."
            else hits.joinToString("\n") { h ->
                val id = if (all && h.chat != chat) "${h.chat}#${h.turn}" else "${h.turn}"
                "turn $id (${h.role}): ${h.snippet.replace('\n', ' ')}"
            } + "\nread_turn {id} returns a whole message."
        } catch (e: Exception) { "Search failed: ${e.message}" }
    }

    /** One message in full by turn id ("12" in this chat, or "<chat>#12"). */
    fun readTurn(id: String, chat: String, load: (String) -> JSONArray?): String {
        val (c, t) = if ('#' in id) id.substringBefore('#') to id.substringAfter('#') else chat to id.trim().removePrefix("turn").trim()
        val n = t.toIntOrNull() ?: return "Error: turn ids are numbers (from search_chat)."
        val h = load(c) ?: return "Error: that chat is gone."
        val m = h.optJSONObject(n) ?: return "Error: no turn $n (this chat has ${h.length()})."
        return "[${m.optString("role")}] " + text(m).take(12_000)
    }

    // ---------------- tools ----------------

    val names = setOf("search_chat", "read_turn", "read_artifact", "update_brief")

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun s(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun i(d: String) = JSONObject().put("type", "integer").put("description", d)

    fun tools(): List<JSONObject> = listOf(
        fn("search_chat", "Search this chat's earlier messages (including ones folded into the summary) and get exact snippets with their turn ids. Use it before asking the user to repeat anything. all_chats=true searches every chat.",
            JSONObject().put("query", s("Words to look for")).put("limit", i("Max hits, default 8")).put("all_chats", JSONObject().put("type", "boolean")), listOf("query")),
        fn("read_turn", "Read one earlier message in full, by the turn id search_chat gave.", JSONObject().put("id", s("Turn id, e.g. 12 or <chat>#12")), listOf("id")),
        fn("read_artifact", "Read lines of a long tool output that was set aside (artifact:<id>).",
            JSONObject().put("id", s("The artifact id")).put("from", i("First line, 1-based (default 1)")).put("lines", i("How many lines (default 200, max 400)")), listOf("id")),
        fn("update_brief", "Replace this project's brief: purpose and rules first, then key files and what's in progress. Hard cap 600 tokens (longer text is trimmed to its top part and kept whole as a searchable note); its top part is pinned in every chat of the project. Write the whole brief each time.",
            JSONObject().put("brief", s("The whole brief")), listOf("brief")),
    )
}
