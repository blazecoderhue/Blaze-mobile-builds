package com.blaze.assistant.agent

import android.content.Context
import com.blaze.assistant.phone.BlazeAccessibilityService
import com.blaze.assistant.phone.PhoneTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Self-improvement, after Hermes Agent's pattern: after a finished turn a quick background review
 * decides what's worth keeping. Durable facts → Memory. Repeatable procedures → Skills (markdown
 * steps, plus recorded phone steps that can be replayed without the AI). Each chat also gets a
 * one-line summary for cross-chat search. With "Ask before saving" on, changes wait for approval.
 * Skills never bypass the approval card for risky actions.
 */
object Learning {
    data class Skill(val name: String, val description: String, val whenToUse: String, val steps: String,
                     val replay: JSONArray?, val uses: Int, val updated: Long) {
        fun toJson() = JSONObject().put("name", name).put("description", description).put("when", whenToUse)
            .put("steps", steps).put("uses", uses).put("updated", updated).apply { replay?.let { put("replay", it) } }
        companion object {
            fun from(o: JSONObject) = Skill(o.optString("name"), o.optString("description"), o.optString("when"),
                o.optString("steps"), o.optJSONArray("replay"), o.optInt("uses"), o.optLong("updated"))
        }
    }

    /** A change waiting for the user's OK (when "Ask before saving" is on). */
    data class Pending(val id: Long, val kind: String, val title: String, val op: JSONObject)

    private lateinit var app: Context
    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills
    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending

    private fun skillFile() = File(app.filesDir, "skills.json")
    private fun pendingFile() = File(app.filesDir, "learning_pending.json")

    fun init(ctx: Context) {
        app = ctx.applicationContext
        _skills.value = try { JSONArray(skillFile().readText()).let { a -> (0 until a.length()).map { Skill.from(a.getJSONObject(it)) } } } catch (_: Exception) { emptyList() }
        _pending.value = try { JSONArray(pendingFile().readText()).let { a -> (0 until a.length()).map {
            val o = a.getJSONObject(it); Pending(o.getLong("id"), o.getString("kind"), o.getString("title"), o.getJSONObject("op")) } } } catch (_: Exception) { emptyList() }
        curateIfDue()
    }

    private fun saveSkills(l: List<Skill>) {
        _skills.value = l
        try { SkillMatch.invalidate() } catch (_: Throwable) { }   // v0.26.2: learned skills are in the skills index
        try { skillFile().writeText(JSONArray(l.map { it.toJson() }).toString()) } catch (_: Exception) { }
    }

    private fun savePending(l: List<Pending>) {
        _pending.value = l
        try { pendingFile().writeText(JSONArray(l.map { JSONObject().put("id", it.id).put("kind", it.kind).put("title", it.title).put("op", it.op) }).toString()) } catch (_: Exception) { }
    }

    // ---------- Skills ----------

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()

    fun relevant(text: String, n: Int = 3): List<Skill> {
        val w = words(text); if (w.isEmpty()) return emptyList()
        return _skills.value.map { it to (words(it.name + " " + it.description + " " + it.whenToUse) intersect w).size }
            .filter { it.second >= 1 }.sortedByDescending { it.second }.take(n).map { it.first }
    }

    fun promptBlock(userText: String): String {
        val r = relevant(userText); if (r.isEmpty()) return ""
        return "\nSkills you learned earlier that may fit (follow them; run_skill replays saved phone steps fast):\n" +
            r.joinToString("\n") { "- ${it.name}: ${it.description}${if (it.replay != null) " [replayable]" else ""}\n  ${it.steps.take(400).replace("\n", "\n  ")}" } + "\n"
    }

    fun upsertSkill(name: String, description: String, whenToUse: String, steps: String, replay: JSONArray?): String {
        val n = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        if (n.isEmpty() || steps.isBlank()) return "A skill needs a name and steps."
        val old = _skills.value.firstOrNull { it.name == n }
        val s = Skill(n, description.ifBlank { old?.description ?: n }, whenToUse.ifBlank { old?.whenToUse ?: "" },
            steps, replay ?: old?.replay, old?.uses ?: 0, System.currentTimeMillis())
        saveSkills(_skills.value.filterNot { it.name == n } + s)
        return if (old == null) "Skill \"$n\" saved." else "Skill \"$n\" updated."
    }

    fun deleteSkill(name: String) = saveSkills(_skills.value.filterNot { it.name == name })
    fun clearSkills() = saveSkills(emptyList())

    /** Replays recorded phone steps without the AI. Stops at the first step that doesn't match the screen. */
    suspend fun replay(ctx: Context, name: String): String {
        val s = _skills.value.firstOrNull { it.name == name } ?: return "No skill called \"$name\"."
        val steps = s.replay ?: return "\"$name\" has no recorded steps; follow its written steps instead:\n${s.steps}"
        val svc = BlazeAccessibilityService.instance ?: return "Phone control is off, so I can't replay \"$name\"."
        saveSkills(_skills.value.map { if (it.name == name) it.copy(uses = it.uses + 1) else it })
        val log = StringBuilder()
        for (i in 0 until steps.length()) {
            val st = steps.getJSONObject(i)
            val tool = st.optString("tool"); val args = st.optJSONObject("args") ?: JSONObject()
            val label = st.optString("label")
            val res = when (tool) {
                "tap", "type_text" -> {
                    // v0.30.7: a short settle, then the label by LabelMatch (OEM synonyms, fuzzy) with a short wait for a lazy
                    // screen and off-screen rows scrolled in, instead of 1.5 s of settling and a plain substring match.
                    svc.settle(600, 150); svc.readScreen()
                    val id = svc.findByLabel(label) ?: (if (label.isNotBlank()) svc.waitFor(label, 1200, wantField = tool == "type_text") ?: svc.reveal(label, wantField = tool == "type_text") else null)
                        ?: return log.append("Step ${i + 1}: couldn't find \"$label\" on screen. Stopped; continue from here yourself.\n\n")
                        .append(svc.readScreen()).toString()
                    if (tool == "tap") {
                        if (Settings.confirmRisky && Regex("\\b(send|pay|buy|order|delete|post|transfer|confirm|book)\\b", RegexOption.IGNORE_CASE).containsMatchIn(label) &&
                            !Agent.confirm("Tap \u201c${label.take(40)}\u201d?", "Replaying the \"$name\" skill.", "Tap"))
                            return log.append("The user did NOT approve tapping \"$label\". Stopped.").toString()
                        svc.tap(id, false)
                    } else svc.type(id, args.optString("text"))
                }
                // v0.30.7: settings pages by intent replay too (recorded since this version).
                com.blaze.assistant.phone.SettingsJump.TOOL -> com.blaze.assistant.phone.SettingsJump.run(ctx, args).substringBefore(" Screen now:")
                else -> PhoneTools.run(ctx, tool, args)
            }
            log.append("Step ${i + 1} ($tool${if (label.isNotEmpty()) " \"$label\"" else ""}): $res\n")
            if (res.startsWith("Error") || res.startsWith("No ")) return log.append("Stopped here.").toString()
        }
        svc.settle(1200, 250)
        return log.append("Replay finished. Screen now:\n").append(svc.readScreen()).toString()
    }

    // ---------- Tools ----------

    val toolNames = setOf("skill_manage", "run_skill", "search_past_chats")

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun p(d: String) = JSONObject().put("type", "string").put("description", d)

    fun tools(): List<JSONObject> = listOf(
        fn("skill_manage", "Save, update or delete a reusable skill: a procedure you worked out that will help next time (e.g. how to order the user's usual ride, check a balance in their bank app).",
            JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("save", "delete"))))
                .put("name", p("short-kebab-name")).put("description", p("One line: what it does"))
                .put("when", p("When to use it")).put("steps", p("Numbered steps")), listOf("action", "name")),
        fn("run_skill", "Replay a learned skill's recorded phone steps quickly, without thinking through each tap.",
            JSONObject().put("name", p("Skill name")).put("reason", p("Only after a refusal: how this skill applies to the request")), listOf("name")),
        fn("search_past_chats", "Search earlier conversations with the user (titles, summaries and messages) to recall what was said or decided.",
            JSONObject().put("query", p("Words to look for")), listOf("query")),
    )

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = when (name) {
        "skill_manage" -> if (a.optString("action") == "delete") { deleteSkill(a.optString("name")); "Deleted." }
            else upsertSkill(a.optString("name"), a.optString("description"), a.optString("when"), a.optString("steps"), null)
        "run_skill" -> replay(ctx, a.optString("name"))
        "search_past_chats" -> searchChats(a.optString("query"))
        else -> "Unknown tool"
    }

    fun searchChats(query: String): String {
        val w = words(query); if (w.isEmpty()) return "Give me some words to search for."
        val hits = ChatStore.list().mapNotNull { m ->
            val o = ChatStore.load(m.id) ?: return@mapNotNull null
            val msgs = o.optJSONArray("messages") ?: JSONArray()
            val texts = (0 until msgs.length()).map { msgs.getJSONObject(it) }.filter { it.optString("role") != "STEP" }.map { it.optString("text") }
            val hay = (m.title + " " + o.optString("summary") + " " + texts.joinToString(" ")).lowercase()
            val score = w.count { hay.contains(it) }
            if (score == 0) null else {
                val snippet = texts.firstOrNull { t -> w.any { t.lowercase().contains(it) } }.orEmpty().take(240)
                Triple(score, m, "• ${m.title} (${java.text.DateFormat.getDateInstance().format(java.util.Date(m.updated))})" +
                    (o.optString("summary").takeIf { it.isNotBlank() }?.let { "\n  Summary: $it" } ?: "") + "\n  …$snippet")
            }
        }.sortedByDescending { it.first }.take(5)
        return if (hits.isEmpty()) "No earlier chat mentions that." else hits.joinToString("\n") { it.third }
    }

    // ---------- Background review ----------

    /**
     * Reviews one finished exchange. [trace] holds the phone actions taken (tool, args, label) so a
     * successful task can become a replayable skill. Returns a short chip like "Memory updated".
     */
    suspend fun review(exchange: String, trace: JSONArray, d: Learn.Decision, tools: List<String>): String? {
        if (!Settings.autoLearn || exchange.length < 40 || !d.reflect) return null
        // Only the saved facts that look related go in (like Desktop's store.Related), so the prompt stays small.
        val q = words(exchange)
        val related = Memory.facts.value.map { it to (words(it) intersect q).size }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(12).map { it.first }
        val known = related.joinToString("\n") { "- $it" }.ifEmpty { "(none related)" }
        val skillNames = _skills.value.joinToString(", ") { it.name }.ifEmpty { "(none)" }
        val skillRule = if (d.skill) "Skill: only if the user taught a method or this is a repeated multi-step task likely to be asked again. use_trace=true only if the recorded phone actions completed that task. Steps are numbered, with checks and pitfalls. Don't duplicate an existing skill."
            else "Do not propose a skill this time (skill: null)."
        val memRule = if (d.memory) "Memory: durable facts or preferences about the user (people, places, routines, work, goals, likes and dislikes, apps and accounts they use, how they want Blaze to behave), one short sentence each with its context. Keep the user's certainty; never turn one occurrence into a habit. If a known fact changed, use replace. Never secrets, codes, passwords, card or ID numbers, one-off task details, or things only true today. Skip anything already known. At most 2."
            else "Do not propose memories this time (memory: [])."
        val sys = """You maintain the long-term memory of Blaze, the user's phone assistant. Read the last exchange and decide what is worth keeping. Most turns have nothing: then return empty lists.
Return ONLY a JSON object:
{"memory":[{"op":"add|replace|remove","fact":"short fact","old":"text of the fact to replace/remove"}],
 "skill":null or {"name":"kebab-name","description":"one line: when to use it","when":"when to use","steps":"1. … 2. … 3. …","use_trace":true|false}}
$memRule
$skillRule"""
        val user = "Saved facts that may be related:\n$known\n\nExisting skills: $skillNames\n\nTools used: ${tools.joinToString().ifEmpty { "(none)" }}\n\nRecorded phone actions: ${if (trace.length() == 0) "(none)" else trace.toString().take(2500)}\n\nExchange:\n${exchange.takeLast(6000)}"
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", user))
        val raw = try { LlmClient.complete(msgs, null, null, null, LlmClient.Lane.BACKGROUND).optString("content") } catch (_: Exception) { return null }
        val o = try { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) } catch (_: Exception) { return null }
        val ops = mutableListOf<Pending>()
        var id = System.currentTimeMillis()
        if (d.memory) o.optJSONArray("memory")?.let { a -> for (i in 0 until minOf(a.length(), 2)) {
            val m = a.getJSONObject(i)
            val opName = m.optString("op")
            if (opName == "remove") {
                if (m.optString("old").isBlank()) continue
                ops += Pending(id++, "memory", "Forget: ${m.optString("old")}", m); continue
            }
            // Every auto-saved fact passes Desktop's filter: no secrets, account numbers, task logs or "today" facts.
            val fact = Learn.accept(m.optString("fact")) ?: continue
            m.put("fact", fact)
            ops += Pending(id++, "memory", if (opName == "replace") "Update: $fact" else "Remember: $fact", m)
        } }
        if (d.skill) o.optJSONObject("skill")?.let { s ->
            val name = s.optString("name").trim().lowercase()
            if (Learn.goodSkill(name, s.optString("description"), s.optString("steps"))) {
                if (s.optBoolean("use_trace") && trace.length() in 2..25) s.put("replay", trace)
                ops += Pending(id++, "skill", "Skill: $name — ${s.optString("description")}", s)
            }
        }
        if (ops.isEmpty()) return null
        if (Settings.learnApproval) { savePending(_pending.value + ops); return "Learned ${ops.size} thing(s) · review in Settings" }
        ops.forEach { apply(it) }
        val mem = ops.count { it.kind == "memory" }; val sk = ops.firstOrNull { it.kind == "skill" }
        return listOfNotNull(if (mem > 0) "Memory updated" else null, sk?.let { "Skill learned: ${it.op.optString("name")}" }).joinToString(" · ")
    }

    private fun apply(p: Pending) {
        val o = p.op
        when (p.kind) {
            "memory" -> when (o.optString("op")) {
                "remove" -> Memory.forget(o.optString("old"))
                "replace" -> Memory.add(o.optString("fact"), o.optString("old"))
                else -> if (Memory.facts.value.size < 250) Memory.add(o.optString("fact"))
            }
            "skill" -> upsertSkill(o.optString("name"), o.optString("description"), o.optString("when"), o.optString("steps"), o.optJSONArray("replay"))
        }
    }

    fun approve(id: Long) { _pending.value.firstOrNull { it.id == id }?.let { apply(it) }; savePending(_pending.value.filterNot { it.id == id }) }
    fun reject(id: Long) = savePending(_pending.value.filterNot { it.id == id })

    // ---------- Curator (weekly, on app start) ----------

    private fun curateIfDue() {
        val last = Settings.str("learn_curated", "0").toLongOrNull() ?: 0
        if (System.currentTimeMillis() - last < 7L * 24 * 3600_000) return
        Settings.setStr("learn_curated", System.currentTimeMillis().toString())
        // Merge skills whose names/descriptions overlap heavily: keep the most used / newest.
        val kept = mutableListOf<Skill>()
        for (s in _skills.value.sortedWith(compareByDescending<Skill> { it.uses }.thenByDescending { it.updated })) {
            val w = words(s.name + " " + s.description)
            val dup = kept.any { k -> val kw = words(k.name + " " + k.description)
                w.isNotEmpty() && (w intersect kw).size.toDouble() / (w union kw).size >= 0.7 }
            // Drop skills never used in 90 days.
            val stale = s.uses == 0 && System.currentTimeMillis() - s.updated > 90L * 24 * 3600_000
            if (!dup && !stale) kept += s
        }
        if (kept.size != _skills.value.size) saveSkills(kept)
        // Exact-duplicate memories.
        val f = Memory.facts.value
        val uniq = f.distinctBy { it.lowercase().trim() }
        if (!Mind.ready && uniq.size != f.size) { Memory.clear(); uniq.forEach { Memory.add(it) } }
    }
}
