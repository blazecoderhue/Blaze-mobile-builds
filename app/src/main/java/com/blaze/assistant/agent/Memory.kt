package com.blaze.assistant.agent

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Long-term facts Blaze keeps about the user, across chats.
 *
 * Since v0.18.0 (one mind) the memories table (blaze.db since v0.26.3, mind.db before) is the source of truth and this object is a view of it:
 * the Memory screen, remember / recall_memory / forget and the learning review all go through [Mind]. The old
 * prefs list ("memory") is imported once and then kept as a mirror of the table's lines, so a downgrade or a
 * backup export still carries every fact. If the memory store can't open, everything works on the old list as before.
 */
object Memory {
    private lateinit var prefs: SharedPreferences
    private val _facts = MutableStateFlow<List<String>>(emptyList())
    val facts: StateFlow<List<String>> = _facts
    @Volatile private var ids: List<Long> = emptyList()

    private fun legacy(): List<String> = try {
        val a = JSONArray(prefs.getString("memory", "[]")); (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList() }

    /** [mergeLegacy]: also fold the prefs list into the table (after a backup was restored). */
    fun init(ctx: Context, mergeLegacy: Boolean = false) {
        prefs = ctx.getSharedPreferences("blaze", Context.MODE_PRIVATE)
        val d = Mind.db()
        if (d == null) { _facts.value = legacy(); return }
        try {
            if (d.meta("mig_memory") != "1") { Mind.importFacts(d, legacy()); d.setMeta("mig_memory", "1") }
            else if (mergeLegacy) Mind.importFacts(d, legacy())
        } catch (_: Exception) { }
        Mind.onChange = { reload() }
        reload()
    }

    /** Re-reads the table (oldest first, like the old list) and mirrors it to prefs. */
    fun reload() {
        val d = Mind.db() ?: return
        val l = try { d.all() } catch (_: Exception) { return }
        ids = l.map { it.id }
        _facts.value = l.map { it.text }
        try { prefs.edit().putString("memory", JSONArray(l.map { it.text }).toString()).apply() } catch (_: Exception) { }
    }

    private fun persist(list: List<String>) {
        _facts.value = list
        prefs.edit().putString("memory", JSONArray(list).toString()).apply()
    }

    private fun matches(text: String, q: String): Boolean { val t = text.lowercase(); return t.contains(q) || q.contains(t) }

    /** Saves [fact]. [replaces] (words from an older fact) removes the fact it supersedes first. */
    fun add(fact: String, replaces: String = ""): String {
        val f = fact.trim().removeSuffix(".")
        if (f.isEmpty()) return "Nothing to remember."
        // Keys, passwords, codes and card/account numbers are never kept, even when asked (Desktop v0.7 rule).
        if (Learn.secret(f)) return "Not saved: that looks like a password, code or account number, which Blaze never keeps in memory."
        val d = Mind.db()
        if (d != null) {
            val r = replaces.trim().lowercase()
            var gone = 0
            if (r.length >= 3) try { d.all().filter { matches(it.text, r) }.forEach { d.delete(it.id); gone++ } } catch (_: Exception) { }
            val (k, s) = Mind.guessKind(f)
            val (out, _) = Mind.save(k, s, f)
            if (gone > 0) reload()
            return when (out) {
                "same" -> if (gone > 0) "Updated: $f" else "Already remembered."
                "updated" -> "Updated: $f"
                "saved" -> if (gone > 0) "Updated: $f" else "Remembered: $f"
                else -> "Nothing to remember."
            }
        }
        val gone = replaces.trim().takeIf { it.length >= 3 }?.let { r ->
            val q = r.lowercase(); val keep = _facts.value.filterNot { matches(it, q) }
            (_facts.value.size - keep.size).also { if (it > 0) persist(keep) } } ?: 0
        if (_facts.value.any { it.equals(f, true) }) return "Already remembered."
        // A newer version of the same fact replaces the old one ("lives in Ndola" -> "lives in Lusaka").
        val w = words(f)
        val old = _facts.value.firstOrNull { o -> val ow = words(o)
            w.size >= 3 && ow.size >= 3 && (w intersect ow).size.toDouble() / (w union ow).size >= 0.6 }
        if (old != null) { persist(_facts.value.map { if (it == old) f else it }); return "Updated: $f" }
        persist(_facts.value + f); return if (gone > 0) "Updated: $f" else "Remembered: $f"
    }

    /** The user asked to forget it: removes every saved line that matches. */
    fun forget(query: String): String {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return "Nothing matched \"$query\"."
        // v0.45.3: "#12" is an id, not words to match: memory_delete removes lines by id (with one confirm card).
        if (MemoryDelete.isIdQuery(q)) return "forget matches words, not ids. To remove #ids, call memory_delete with ids=[" +
            MemoryDelete.idsOf(q).joinToString(",") + "]."
        val d = Mind.db()
        if (d != null) {
            val hit = try { d.all().filter { matches(it.text, q) || matches(it.subject + ": " + it.text, q) } } catch (_: Exception) { emptyList() }
            hit.forEach { d.delete(it.id) }
            reload()
            return if (hit.isEmpty()) "Nothing matched \"$query\"." else "Forgot ${hit.size} item(s)."
        }
        val keep = _facts.value.filterNot { matches(it, q) }
        val n = _facts.value.size - keep.size
        persist(keep)
        return if (n == 0) "Nothing matched \"$query\"." else "Forgot $n item(s)."
    }

    fun remove(index: Int) {
        val d = Mind.db()
        if (d != null) { ids.getOrNull(index)?.let { d.delete(it) }; reload(); return }
        persist(_facts.value.filterIndexed { i, _ -> i != index })
    }

    /** "Forget all" on the Memory screen (the user's explicit request). */
    fun clear() {
        val d = Mind.db()
        if (d != null) { try { d.all().forEach { d.delete(it.id) } } catch (_: Exception) { }; reload(); return }
        persist(emptyList())
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()

    /**
     * Memory for the prompt. With one mind: the pinned core and the subject index only (everything else is looked
     * up). Without the memory store: the old list (small memories whole, big ones only what fits the request).
     */
    /** v0.41.0 speed: inside one agent turn the Mind block is read once, not on every model call (a 6-step task read it 6 times). */
    private val mindBlock = PromptMemo<String>(60_000)

    /** Set by the agent loop for the turn it runs (0 = not in a turn: always read fresh). */
    @Volatile var turnKey: Long = 0L

    fun promptBlock(query: String = ""): String {
        if (Mind.ready) {
            val turn = turnKey
            if (turn == 0L) return Mind.promptBlock()
            // A new turn, another store, or a memory saved through Mind rebuilds it.
            return mindBlock.get(Triple(turn, System.identityHashCode(Mind.db()), Mind.version)) { Mind.promptBlock() }
        }
        val all = _facts.value
        if (all.isEmpty()) return ""
        val chosen = if (all.size <= 30) all else {
            val q = words(query)
            val scored = all.withIndex().map { (i, f) -> Triple(i, f, (words(f) intersect q).size) }
            val top = scored.filter { it.third > 0 }.sortedByDescending { it.third }.take(18).map { it.first }.toSet()
            val recent = (all.size - 8 until all.size).toSet()
            all.filterIndexed { i, _ -> i in top || i in recent }
        }
        val more = all.size - chosen.size
        return "\nWhat you remember about the user (use quietly, don't recite):\n" + chosen.joinToString("\n") { "- $it" } +
            (if (more > 0) "\n($more more facts saved; call recall_memory to search them.)" else "") + "\n"
    }

    fun recall(query: String): String {
        if (Mind.ready) return Mind.run("memory_search", JSONObject().put("query", query))
        if (MemoryIntent.meansAll(query)) return MemoryIntent.formatPlain(_facts.value)
        val q = words(query)
        val hits = _facts.value.filter { (words(it) intersect q).isNotEmpty() || it.contains(query, true) }
        return if (hits.isEmpty()) "Nothing saved about \"$query\"." else hits.take(25).joinToString("\n") { "- $it" }
    }

    fun tools(): List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "remember")
            .put("description", "Save a lasting fact or preference about the user when they say 'remember'. Prefer memory_save, which also takes the kind and subject.")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("fact", JSONObject().put("type", "string").put("description", "Short fact, e.g. 'Sister is Mary, lives in Ndola'"))
                    .put("replaces", JSONObject().put("type", "string").put("description", "Optional: words from an older saved fact this one replaces (it is removed)")))
                .put("required", JSONArray(listOf("fact"))))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "recall_memory")
            .put("description", "Search everything you've saved about the user (same as memory_search). query=all lists every saved line.")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("query"))))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "forget")
            .put("description", "Delete a remembered fact when the user asks you to forget it.")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("fact", JSONObject().put("type", "string").put("description", "Words from the fact to forget")))
                .put("required", JSONArray(listOf("fact")))))
    )
}
