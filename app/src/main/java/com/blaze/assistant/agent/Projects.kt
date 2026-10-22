package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Projects on the phone: a name, optional instructions, and the chats that belong to it.
 * Kept in prefs ("projects_json") so updates never lose them. Chats themselves stay in ChatStore;
 * a project only lists their ids, so deleting a project never deletes a chat.
 */
data class Project(val id: String, val name: String, val instructions: String = "", val chats: List<String> = emptyList(), val updated: Long = 0L,
                   /** Pinned brief (v0.15.0): purpose, rules, key files, in progress. Capped at [Projects.BRIEF_TOKENS] (v0.28.5); Blaze edits it with update_brief. */
                   val brief: String = "",
                   /** v0.28.5: older notes (earlier briefs, the full text of a brief that was trimmed to fit). Searchable with project_docs_search, never pinned. */
                   val notes: List<ProjectNote> = emptyList())

/** One archived note of a project ([at] = epoch ms). */
data class ProjectNote(val at: Long, val title: String, val text: String)

object Projects {
    private const val KEY = "projects_json"
    private val _all = MutableStateFlow<List<Project>>(emptyList())
    val all: StateFlow<List<Project>> = _all
    /** The project new chats go into ("" = plain chat). */
    val current = MutableStateFlow("")

    fun load() {
        val raw = try { parse(Settings.str(KEY, "[]")) } catch (_: Exception) { emptyList() }
        // v0.28.5: a brief over the cap is moved whole into the project's notes (searchable), and the pinned brief is its top part.
        val fixed = migrate(raw, System.currentTimeMillis())
        if (fixed != raw) save(fixed) else _all.value = raw
    }

    /** For tests: the project list without touching prefs. */
    fun use(list: List<Project>) { _all.value = list }

    fun parse(json: String): List<Project> {
        val a = JSONArray(json.ifBlank { "[]" })
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ch = o.optJSONArray("chats") ?: JSONArray()
            Project(o.optString("id"), o.optString("name"), o.optString("instructions"),
                (0 until ch.length()).map { ch.optString(it) }.filter { it.isNotBlank() }, o.optLong("updated"), o.optString("brief"),
                (o.optJSONArray("notes") ?: JSONArray()).let { n -> (0 until n.length()).mapNotNull { n.optJSONObject(it) }
                    .map { ProjectNote(it.optLong("at"), it.optString("title"), it.optString("text")) }.filter { it.text.isNotBlank() } })
        }.filter { it.id.isNotBlank() }
    }

    fun toJson(list: List<Project>): String = JSONArray().apply {
        list.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("instructions", p.instructions)
            .put("chats", JSONArray(p.chats)).put("updated", p.updated).put("brief", p.brief)
            .apply { if (p.notes.isNotEmpty()) put("notes", JSONArray().apply { p.notes.forEach { n -> put(JSONObject().put("at", n.at).put("title", n.title).put("text", n.text)) } }) }) }
    }.toString()

    private fun save(list: List<Project>) { _all.value = list; try { Settings.setStr(KEY, toJson(list)) } catch (_: Exception) { } }

    fun create(name: String): Project {
        val p = Project("p" + System.currentTimeMillis().toString(36), name.trim().ifBlank { "New project" }, updated = System.currentTimeMillis())
        save(listOf(p) + _all.value); return p
    }
    fun rename(id: String, name: String) = save(_all.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
    fun setInstructions(id: String, text: String) = save(_all.value.map { if (it.id == id) it.copy(instructions = text) else it })
    /** v0.28.5: the brief's hard cap, in BlazeDb.tokens (bytes / 4), enforced on every save. */
    const val BRIEF_TOKENS = 600
    /** Old name kept for callers: the cap in bytes. */
    const val BRIEF_MAX = BRIEF_TOKENS * 4
    /** What the prompt may pin for a project's instructions + brief together (the header and the lookup line are extra). */
    const val PIN_TOKENS = 600
    /** At most this many archived notes per project (oldest dropped first) and this much text in each. */
    const val MAX_NOTES = 30
    const val NOTE_MAX_CHARS = 40_000

    fun tokens(s: String): Int = com.blaze.assistant.store.BlazeDb.tokens(s)

    /** [s] cut to at most [maxTok] tokens on a character boundary (never splits a surrogate pair). */
    fun cutTokens(s: String, maxTok: Int): String {
        if (tokens(s) <= maxTok) return s
        val maxBytes = maxTok * 4
        var bytes = 0; var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i); val n = String(Character.toChars(cp)).toByteArray().size
            if (bytes + n > maxBytes - 3) break
            bytes += n; i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }

    /** Squeezes whitespace without changing words: trailing spaces, runs of spaces, 2+ blank lines. */
    fun squeeze(s: String): String = s.replace("\r\n", "\n").lines().joinToString("\n") { it.trimEnd().replace(Regex("(?<=\\S)[ \\t]{2,}"), " ") }
        .replace(Regex("\n{3,}"), "\n\n").trim()

    /**
     * The top of [text] within [maxTok] tokens: whole lines first (so purpose and rules, written first, survive whole),
     * then the cut line by characters, ending in "…" when anything was left out.
     */
    fun head(text: String, maxTok: Int): String {
        val t = text.trim()
        if (tokens(t) <= maxTok) return t
        val out = StringBuilder()
        for (line in t.lines()) {
            val next = if (out.isEmpty()) line else "\n" + line
            if (tokens(out.toString() + next + " …") > maxTok) {
                if (out.isEmpty() || tokens(out.toString()) < maxTok / 2) out.append(cutTokens(next, maxTok - tokens(out.toString()) - 2).trimEnd())
                break
            }
            out.append(next)
        }
        return out.toString().trimEnd() + " …"
    }

    /** A brief that fits the cap: squeezed, then its top part. [trimmed] is true when words were left out. */
    data class Fit(val text: String, val trimmed: Boolean, val tokensIn: Int)
    fun fit(text: String, maxTok: Int = BRIEF_TOKENS): Fit {
        val raw = text.trim()
        if (tokens(raw) <= maxTok) return Fit(raw, false, tokens(raw))
        val sq = squeeze(raw)
        if (tokens(sq) <= maxTok) return Fit(sq, false, tokens(raw))
        return Fit(head(sq, maxTok), true, tokens(raw))
    }

    private fun stamp(at: Long): String = try { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(at)) } catch (_: Exception) { at.toString() }

    private fun withNote(p: Project, note: ProjectNote): Project =
        p.copy(notes = (p.notes + note.copy(text = note.text.take(NOTE_MAX_CHARS))).takeLast(MAX_NOTES))

    /** v0.28.5 migration: every brief over the cap keeps its whole text as a note and pins its top part. No text is lost. */
    fun migrate(list: List<Project>, now: Long): List<Project> = list.map { p ->
        if (tokens(p.brief) <= BRIEF_TOKENS) p
        else withNote(p, ProjectNote(now, "Full brief (kept when the 600-token brief cap came in, ${stamp(now)})", p.brief)).copy(brief = fit(p.brief).text)
    }

    /** The editor: whatever is typed or pasted, the saved brief never passes the cap (cut at the cap, as the field shows). */
    fun setBrief(id: String, text: String) = save(_all.value.map { if (it.id == id) it.copy(brief = cutTokens(text, BRIEF_TOKENS), updated = System.currentTimeMillis()) else it })

    /**
     * update_brief (Blaze's brief-updater): the new brief is squeezed and, if still over the cap, trimmed to its top part
     * with the whole text kept as a note; the brief it replaces is kept as a note too. Returns the reply for the model.
     */
    fun updateBrief(id: String, text: String, now: Long = System.currentTimeMillis()): String {
        val p = get(id) ?: return "No such project."
        val f = fit(text)
        var q = p
        if (p.brief.isNotBlank() && p.brief.trim() != f.text) q = withNote(q, ProjectNote(now, "Earlier brief (replaced ${stamp(now)})", p.brief))
        if (f.trimmed) q = withNote(q, ProjectNote(now, "Full brief as written (trimmed to fit, ${stamp(now)})", text.trim()))
        q = q.copy(brief = f.text, updated = now)
        save(_all.value.map { if (it.id == id) q else it })
        return if (!f.trimmed) "Brief for ${p.name} updated (${tokens(f.text)} / $BRIEF_TOKENS tokens). Its top part is pinned in every chat of the project; the brief it replaced is kept as a searchable note."
        else "That brief was ${f.tokensIn} tokens; the cap is $BRIEF_TOKENS. I kept its top part (${tokens(f.text)} tokens) and saved the whole text as a searchable note. " +
            "Call update_brief again with a tighter version (purpose and rules first, short lines, details left to the notes) if anything important was cut."
    }

    /** Old callers: the brief is now part of [promptLine] (pinned within [PIN_TOKENS]); nothing extra. */
    fun briefBlock(@Suppress("UNUSED_PARAMETER") chatId: String): String = ""

    fun delete(id: String) { save(_all.value.filter { it.id != id }); if (current.value == id) current.value = ""; try { ProjectDocs.dropProject(id) } catch (_: Throwable) { } }

    /** Puts [chatId] in [projectId] ("" = take it out of every project). */
    fun assign(chatId: String, projectId: String) {
        if (chatId.isBlank()) return
        save(_all.value.map { p ->
            val without = p.chats - chatId
            if (p.id == projectId) p.copy(chats = listOf(chatId) + without, updated = System.currentTimeMillis()) else p.copy(chats = without)
        })
    }

    fun of(chatId: String): Project? = _all.value.firstOrNull { chatId in it.chats }
    fun get(id: String): Project? = _all.value.firstOrNull { it.id == id }

    /** The pinned project block for the system prompt (v0.28.5: within [PIN_TOKENS] + a header and one lookup line). */
    fun promptLine(chatId: String): String { val p = of(chatId) ?: return ""; return pinned(p) }

    const val LOOKUP_LINE = "Full instructions, full brief and older notes for this project are searchable: project_docs_search(query), then project_docs_read(id). Search them before asking the user for project details."

    /**
     * What a project pins every turn: its name, the top of its instructions and the top of its brief (purpose + rules first)
     * within [PIN_TOKENS] together, and one line saying the rest is searchable. Pure (PromptBudgetTest).
     */
    fun pinned(p: Project, budget: Int = PIN_TOKENS): String {
        val ins = p.instructions.trim(); val brief = p.brief.trim()
        val sb = StringBuilder("\nPROJECT: ").append(p.name.take(80)).append('.')
        if (ins.isEmpty() && brief.isEmpty() && p.notes.isEmpty()) return sb.toString()
        // Instructions get up to a third when there's a brief (more if the brief is short); the brief gets the rest.
        val insBudget = when { ins.isEmpty() -> 0; brief.isEmpty() -> budget; else -> minOf(tokens(ins), maxOf(budget / 3, budget - tokens(brief))) }
        val insPart = if (ins.isEmpty()) "" else head(ins, insBudget)
        val briefPart = if (brief.isEmpty()) "" else head(brief, (budget - tokens(insPart)).coerceAtLeast(0))
        if (insPart.isNotEmpty()) sb.append("\nPROJECT INSTRUCTIONS (top part)\n").append(insPart)
        if (briefPart.isNotEmpty()) sb.append("\nPROJECT BRIEF (pinned; update_brief edits it)\n").append(briefPart)
        sb.append("\n").append(LOOKUP_LINE).append('\n')
        return sb.toString()
    }
}
