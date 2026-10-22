package com.blaze.assistant.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Autonomy helpers: the pieces that make Blaze keep going on its own instead of giving up early.
 *
 *  - Refusal gate: a "can't do that" answer is sent back once with a nudge to try other tools first.
 *  - Failure check: if the last action failed, the model must retry another way or verify before
 *    it reports success.
 *  - find_tool: search every tool (loaded or not) by what it does, and load the matching groups.
 *  - Goals: a small task notebook (process IDs N1, N2…) so long jobs have steps, a deadline and
 *    can be resumed in a later chat or by a routine.
 *  - Standing permissions: the user can say "you don't need to ask before sending messages" once;
 *    money never gets a standing permission.
 */
object Autonomy {

    // ---------- Refusal gate / verifier ----------

    private val refusal = Regex("""\b(i can'?t|i cannot|i'?m (not able|unable)|i am (not able|unable)|i don'?t have (a way|access|the ability|any tool)|there'?s no (tool|way)|not possible for me|beyond my|i'?m not capable|isn'?t something i can)\b""", RegexOption.IGNORE_CASE)

    fun looksLikeRefusal(text: String): Boolean = text.length < 900 && refusal.containsMatchIn(text)

    const val REFUSAL_NUDGE = "[Blaze check] Before telling the user you can't: think of three other ways with your tools " +
        "(find_tool or load_tools for more tools, the background browser, run_shell in the Linux terminal, phone screen control with run_plan, " +
        "skills, web_search). Try the most promising one now. Only if they all truly fail, say briefly what you tried and the one thing the user can do."

    private val failed = Regex("""^(Error|Couldn't|No element|No app|Stopped at step|Failed|The user did NOT)""")
    fun lastFailed(results: List<String>): Boolean = results.lastOrNull()?.let { failed.containsMatchIn(it.trim()) } == true

    const val VERIFY_NUDGE = "[Blaze check] Your last action failed. Don't report success. Try another way (different target, scroll, a deep link, " +
        "the browser, or the terminal), or check the screen/result to confirm what actually happened, then answer."

    // ---------- find_tool ----------

    fun findTool(all: JSONArray, query: String): Pair<String, Set<String>> {
        val q = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        if (q.isEmpty()) return "Say what you need the tool to do." to emptySet()
        // v0.26.2: ranked like Desktop's tools index (bm25 over name ×10, description ×3, group ×2); the old word score is the fallback.
        val docs = (0 until all.length()).mapNotNull { i -> all.optJSONObject(i)?.optJSONObject("function")?.let { f ->
            SkillIndex.Doc(f.optString("name"), listOf(f.optString("name"), f.optString("description"), ToolRouter.groupOf(f.optString("name")).orEmpty())) } }
        val ranked = SkillIndex.tools(docs).search(query, 8).map { (d, _) -> Triple(d.key, d.fields[1], 1) }
        val scored = ranked.ifEmpty { scoreTools(all, q) }
        if (scored.isEmpty()) return "No tool matches \u201c$query\u201d. Consider run_shell (Linux), the browser tools, or run_plan on the screen." to emptySet()
        val groups = scored.mapNotNull { ToolRouter.groupOf(it.first) }.toSet()
        return ("Matching tools (now loaded): " + scored.joinToString("\n") { "${it.first}: ${it.second.take(110)}" }) to groups
    }

    private fun scoreTools(all: JSONArray, q: Set<String>): List<Triple<String, String, Int>> {
        return (0 until all.length()).mapNotNull { i ->
            val f = all.optJSONObject(i)?.optJSONObject("function") ?: return@mapNotNull null
            val name = f.optString("name"); val desc = f.optString("description")
            val words = (name.replace('_', ' ') + " " + desc).lowercase().split(Regex("[^a-z0-9]+")).toSet()
            val sc = q.count { w -> words.any { it.startsWith(w) || w.startsWith(it) && it.length > 3 } } + if (q.any { name.contains(it) }) 2 else 0
            if (sc == 0) null else Triple(name, desc, sc)
        }.sortedByDescending { it.third }.take(8)
    }

    fun findToolDef(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", "find_tool")
        .put("description", "Search ALL your tools (including ones not loaded) by what you need to do, and load the matches. Use it before ever saying you can't do something.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("need", JSONObject().put("type", "string").put("description", "What you need to do, in plain words")))
            .put("required", JSONArray(listOf("need")))))

    // ---------- Goals (task notebook) ----------

    data class Goal(val id: String, var title: String, var steps: MutableList<String>, var done: MutableSet<Int>,
                    var due: Long, var notes: String, var status: String, val created: Long)

    private lateinit var file: File
    private val goals = mutableListOf<Goal>()
    private val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

    fun init(ctx: Context) {
        file = File(ctx.applicationContext.filesDir, "goals.json")
        try {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                val st = o.optJSONArray("steps") ?: JSONArray(); val dn = o.optJSONArray("done") ?: JSONArray()
                goals += Goal(o.getString("id"), o.optString("title"), (0 until st.length()).map { st.getString(it) }.toMutableList(),
                    (0 until dn.length()).map { dn.getInt(it) }.toMutableSet(), o.optLong("due"), o.optString("notes"),
                    o.optString("status", "open"), o.optLong("created"))
            }
        } catch (_: Exception) { }
    }

    @Synchronized private fun save() {
        if (!::file.isInitialized) return
        try { file.writeText(JSONArray(goals.map { JSONObject().put("id", it.id).put("title", it.title).put("steps", JSONArray(it.steps))
            .put("done", JSONArray(it.done.toList())).put("due", it.due).put("notes", it.notes).put("status", it.status).put("created", it.created) }).toString()) }
        catch (_: Exception) { }
    }

    @Synchronized fun states(): Map<String, String> = goals.associate { it.id to it.status }
    @Synchronized fun remove(id: String) { goals.removeAll { it.id == id }; save() }
    @Synchronized fun setStatus(id: String, st: String) { goals.firstOrNull { it.id == id }?.status = st; save() }

    private fun line(g: Goal) = "${g.id} ${g.title} (${g.done.size}/${g.steps.size} steps${if (g.due > 0) ", due " + fmt.format(Date(g.due)) else ""})"

    /** For the system prompt: unfinished goals in one short line. */
    fun promptLine(): String = synchronized(this) {
        val open = goals.filter { it.status == "open" }
        if (open.isEmpty()) "" else "\nUnfinished goals: " + open.take(5).joinToString("; ") { line(it) } + ". Pick them up when relevant (goal action=show for steps)."
    }

    /** Open goals due within the next 24 hours, for background checks. */
    fun dueSoon(): String = synchronized(this) {
        val now = System.currentTimeMillis()
        goals.filter { it.status == "open" && it.due in now..(now + 86_400_000L) }.joinToString("; ") { line(it) }
    }

    private fun parseDue(s: String): Long {
        if (s.isBlank()) return 0
        for (p in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd")) try { return SimpleDateFormat(p, Locale.US).parse(s.trim())!!.time } catch (_: Exception) { }
        Regex("""(\d{1,2}):(\d{2})""").find(s)?.let { return Processes.nextAt(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        return 0
    }

    @Synchronized fun goal(a: JSONObject): String {
        val action = a.optString("action", "list")
        val id = a.optString("id").trim().uppercase()
        val g = goals.firstOrNull { it.id == id }
        return when (action) {
            "add" -> {
                val title = a.optString("title").ifBlank { return "Give the goal a title." }
                val steps = a.optJSONArray("steps")?.let { s -> (0 until s.length()).map { s.getString(it) } } ?: emptyList()
                val nid = Processes.nextId("goal")
                val ng = Goal(nid, title.take(80), steps.toMutableList(), mutableSetOf(), parseDue(a.optString("due")), a.optString("note"), "open", System.currentTimeMillis())
                goals += ng; save()
                Processes.register("goal", ng.title, nid, ng.due, JSONObject().put("steps", steps.size), id = nid)
                "Goal ${line(ng)} saved."
            }
            "list" -> goals.filter { it.status == "open" }.joinToString("\n") { line(it) }.ifBlank { "No open goals." }
            "show" -> g?.let { x -> line(x) + "\n" + x.steps.mapIndexed { i, s -> "${if (i in x.done) "✓" else "·"} ${i + 1}. $s" }.joinToString("\n") +
                (if (x.notes.isNotBlank()) "\nNotes: ${x.notes.takeLast(800)}" else "") } ?: "No goal $id."
            "step_done" -> g?.let { x -> val n = a.optInt("step") - 1; if (n in x.steps.indices) x.done.add(n)
                if (x.steps.isNotEmpty() && x.done.size == x.steps.size) x.status = "done"; save(); line(x) + if (x.status == "done") " Finished!" else "" } ?: "No goal $id."
            "add_step" -> g?.let { x -> x.steps.add(a.optString("title")); save(); line(x) } ?: "No goal $id."
            "note" -> g?.let { x -> x.notes = (x.notes + "\n" + a.optString("note")).trim().takeLast(3000); save(); "Noted on ${x.id}." } ?: "No goal $id."
            "done" -> g?.let { x -> x.status = "done"; save(); "Goal ${x.id} done." } ?: "No goal $id."
            else -> "Unknown action."
        }
    }

    fun goalTool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", "goal")
        .put("description", "Task notebook for anything that takes several steps, a deadline, or more than one session. add (title, steps, due), show, step_done (step number), add_step, note, done, list. IDs start with N.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("add", "list", "show", "step_done", "add_step", "note", "done"))))
            .put("id", JSONObject().put("type", "string"))
            .put("title", JSONObject().put("type", "string"))
            .put("steps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            .put("step", JSONObject().put("type", "integer"))
            .put("due", JSONObject().put("type", "string").put("description", "yyyy-MM-dd HH:mm or HH:mm"))
            .put("note", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action")))))

    // ---------- Standing permissions ----------

    /** Categories the user can pre-approve. Money is deliberately not one of them. */
    val categories = mapOf(
        "send" to Regex("""\b(send|message|reply|post|publish|share)\b""", RegexOption.IGNORE_CASE),
        "delete" to Regex("""\b(delete|remove)\b""", RegexOption.IGNORE_CASE),
        "commands" to Regex("""\b(run this command|command)\b""", RegexOption.IGNORE_CASE),
        "book" to Regex("""\b(book|subscribe|confirm)\b""", RegexOption.IGNORE_CASE),
    )
    private val money = Regex("""\b(pay|buy|purchase|order|checkout|check out|transfer|withdraw|mobile money|momo|dial)\b""", RegexOption.IGNORE_CASE)

    /** True when the user gave a standing OK for this kind of action (never for money). */
    fun preApproved(title: String, detail: String): Boolean {
        val t = "$title $detail"
        if (money.containsMatchIn(t)) return false
        return categories.any { (k, r) -> Settings.bool("allow_$k", false) && r.containsMatchIn(title) }
    }

    fun permissionTool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", "standing_permission")
        .put("description", "Only when the user explicitly says you don't need to ask (or must ask again) before a kind of action. Categories: send (messages/posts), delete, commands (terminal), book. Payments and money always ask.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("category", JSONObject().put("type", "string").put("enum", JSONArray(categories.keys.toList())))
            .put("allow", JSONObject().put("type", "string").put("enum", JSONArray(listOf("on", "off")))))
            .put("required", JSONArray(listOf("category", "allow")))))

    fun setPermission(a: JSONObject): String {
        val c = a.optString("category"); if (c !in categories) return "Unknown category."
        val on = a.optString("allow") == "on"
        Settings.setBool("allow_$c", on)
        return if (on) "OK: I won't ask before $c actions any more (payments still always ask)." else "OK: I'll ask before $c actions again."
    }

    fun promptBlock(): String = """
AUTONOMY: you finish things. Plan briefly, act, then check the result proves it worked before you say it's done. If a step fails, try a different way (another target, a deep link, the background browser, run_shell) before giving up. Never say you can't until find_tool and at least two other approaches have failed. For work with several steps or a deadline, keep a goal (task notebook) and tick steps off. Ask the user only when you're truly blocked or something risky needs their OK. When the user mentions something they must do later (a bill, a call, a deadline), offer in one short line to set a reminder."""
}
