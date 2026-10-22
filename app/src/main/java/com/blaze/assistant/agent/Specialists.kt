package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Specialists: focused sub-agents Blaze can hand a piece of work to. Each has its own prompt and
 * only the tools it needs, so it stays on task, uses fewer tokens, and several can run at once.
 */
object Specialists {
    /**
     * [skills] = bundled skill packs it loads (v0.24.3; empty = SpecialistMatch.skills for its id).
     * v0.26.2: [playbook] = its field playbook (custom ones; built-ins read assets/specialists/<id>.md, see Playbooks),
     * [madeBy] = "user" (Settings) or "blaze" (create_specialist). Both are optional in the saved JSON.
     */
    data class Spec(val id: String, val name: String, val prompt: String, val groups: Set<String>, val custom: Boolean = false,
                    val skills: List<String> = emptyList(), val playbook: String = "", val madeBy: String = "") {
        val byBlaze: Boolean get() = madeBy == BLAZE
    }

    const val BLAZE = "blaze"
    const val USER = "user"

    val builtIn = listOf(
        Spec("researcher", "Researcher", "You research on the web. Search, open the 2-4 best sources, cross-check, and return a tight answer with the key facts, numbers and source links. No filler.", setOf("web", "memory")),
        Spec("operator", "Phone operator", "You operate the Android phone through its apps to finish one task. Prefer direct tools (search_in_app, set_alarm…) over tapping. Batch steps with do_actions. Report exactly what you did and what you saw.", setOf("phone", "comms", "device")),
        Spec("writer", "Writer", "You write and edit: messages, emails, posts, summaries, scripts. Match the user's voice, keep it clear and short unless asked otherwise. Return only the finished text.", setOf("memory")),
        Spec("planner", "Planner", "You plan: break goals into steps, schedule, check the calendar and routines, and set reminders. Return a short, ordered plan and what you scheduled.", setOf("comms", "memory", "routines", "web")),
        Spec("coder", "Coder", "You write, run and debug code and shell commands in Blaze's terminal. Run things to check them; keep output short; explain fixes in one line each.", setOf("terminal", "web")),
        Spec("inbox", "Inbox manager", "You handle the user's mail, messages, notifications and connected apps: find, summarise, draft replies. Never send without approval.", setOf("connectors", "comms", "notifications", "memory")),
        // v0.24.3 (blaze-specialists): matched per turn by SpecialistMatch, each with its own skill packs.
        Spec("shopper", "Shopper", "You find and compare products for one shopping goal: real listings, prices with currency, delivery to the user's country, stock, seller rating and the link. Respect the price limit as a hard filter. Never buy or check out; return the options and your one pick with the reason.", setOf("web", "browser", "memory")),
        Spec("booker", "Booker", "You find bookings (flights, buses, hotels, tables, appointments) for one target: times, prices, availability, cancellation rules and the link. Never pay or confirm a booking; return the options that fit the limits and your pick.", setOf("web", "browser", "memory", "routines")),
        Spec("tutor", "Tutor", "You help the user learn one topic: explain simply with an example, check understanding, and turn key facts into flashcards (Learn) when asked. Never hand over answers to graded work; teach the method instead.", setOf("web", "memory", "study")),
        Spec("designer", "Designer", "You make one visual thing for real with the design tool (3D, pages, UI, posters, slides, diagrams): clear hierarchy, real content, no lorem ipsum. Return what you made and how to open it.", setOf("design", "web", "memory")),
    )

    fun custom(): List<Spec> = parse(Settings.str("specialists", "[]"))

    /**
     * The `specialists` pref → specs. Old entries (id, name, prompt, groups) load as before; v0.26.2 adds the
     * optional "playbook" and "made_by" keys. Broken entries are skipped, never the whole list. Pure.
     */
    fun parse(json: String): List<Spec> = try {
        val a = JSONArray(json.ifBlank { "[]" })
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull {
            val id = it.optString("id").trim(); val name = it.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) null
            else Spec(id, name, it.optString("prompt"),
                it.optString("groups", "web,memory").split(',').map { g -> g.trim() }.filter { g -> g.isNotEmpty() }.toSet(), true,
                it.optString("skills").split(',').map { k -> k.trim() }.filter { k -> k.isNotEmpty() },
                it.optString("playbook"), it.optString("made_by", USER).ifBlank { USER })
        }
    } catch (_: Exception) { emptyList() }

    /** Specs → the pref's JSON (the old keys exactly as before, plus playbook / made_by / skills when set). Pure. */
    fun toJson(list: List<Spec>): String = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().put("id", s.id).put("name", s.name).put("prompt", s.prompt).put("groups", s.groups.joinToString(",")).apply {
                if (s.playbook.isNotBlank()) put("playbook", s.playbook)
                put("made_by", s.madeBy.ifBlank { USER })
                if (s.skills.isNotEmpty()) put("skills", s.skills.joinToString(","))
            })
        }
    }.toString()

    fun all() = builtIn + custom()
    fun byId(id: String) = all().firstOrNull { it.id == id || it.name.equals(id, true) }

    fun saveCustom(list: List<Spec>) = Settings.setStr("specialists", toJson(list))

    /** Saves one custom specialist (replacing the one with its id). */
    fun upsert(s: Spec) = saveCustom(custom().filterNot { it.id == s.id } + s.copy(custom = true))

    fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)

    // ---------------- create_specialist (v0.26.2) ----------------

    data class Made(val ok: Boolean, val spec: Spec?, val message: String)

    /**
     * Checks and builds a specialist Blaze wants to create. The playbook must pass [Playbooks.validate] (against
     * the field = name + prompt, and every other playbook so it isn't a copy); on failure the model is told exactly
     * what to fix, up to [Playbooks.MAX_TRIES] tries ([attempt] is 1-based). Ids of built-ins and of the user's
     * own specialists are never overwritten; a Blaze-made one with the same id is updated. Pure.
     */
    fun make(id0: String, name0: String, prompt0: String, groups0: List<String>, playbook: String, existing: List<Spec>,
             otherPlaybooks: List<String>, attempt: Int = 1, skills: List<String> = emptyList()): Made {
        val name = name0.trim().take(40); val prompt = prompt0.trim()
        val id = slug(id0.ifBlank { name })
        val left = Playbooks.MAX_TRIES - attempt
        fun no(msg: String) = Made(false, null, "Not saved. $msg" + (if (left > 0) " Fix it and call create_specialist again ($left tr${if (left == 1) "y" else "ies"} left)."
            else " That was the last try: do the task yourself without a new specialist."))
        if (name.length < 3) return no("Give it a name (e.g. \"Farming advisor\").")
        if (id.isEmpty()) return no("Give it an id (short-kebab-name).")
        if (builtIn.any { it.id == id || it.name.equals(name, true) }) return no("$id is a built-in specialist; use it with delegate instead of creating one.")
        val clash = existing.firstOrNull { it.id == id || it.name.equals(name, true) }
        if (clash != null && clash.custom && !clash.byBlaze) return no("The user already has a specialist \"${clash.name}\"; use it with delegate, or pick another name and id.")
        if (prompt.length < 40) return no("The prompt is too short (${prompt.length} characters): say in two or three sentences what it does, how, and what it returns.")
        Playbooks.secrets(prompt).takeIf { it.isNotEmpty() }?.let { return no("The prompt contains what looks like ${it.joinToString(" and ")}; remove it.") }
        val wanted = groups0.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val bad = wanted.filter { it !in groupNames }
        if (bad.isNotEmpty()) return no("Unknown tool group(s): ${bad.joinToString()}. Use some of: ${groupNames.joinToString()}.")
        val groups = wanted.ifEmpty { listOf("web", "memory") }.toSet()
        val check = Playbooks.validate(playbook, "$name $id $prompt", otherPlaybooks)
        if (!check.ok) return no("\n" + check.feedback())
        val spec = Spec(id, name, prompt, groups, true, skills, playbook.trim(), BLAZE)
        return Made(true, spec, "Saved the new specialist $name ($id, tools: ${groups.joinToString(", ")}) with its playbook. " +
            "It is in Settings \u203a Specialists (made by Blaze) and matched in later turns. Use it now with delegate specialist=$id.")
    }

    fun createTool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "create_specialist")
        .put("description", "Create and save a NEW specialist when a task needs a field none of the existing ones covers (check the delegate list first; " +
            "never for small work you can do yourself). It needs a field playbook in markdown with exactly these sections: " +
            Playbooks.SECTIONS.joinToString(", ") { "## ${it.title}" } + ". The playbook is checked before saving: every section present and substantial, " +
            "at least ${Playbooks.MIN_CHECKS} \"- [ ]\" self-check items, specific to the field (its own terms, tools, standards, failure modes), not a copy, no secrets. " +
            "If it's rejected you're told exactly what to fix; send the whole playbook again (${Playbooks.MAX_TRIES} tries).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("id", JSONObject().put("type", "string").put("description", "short-kebab-id, e.g. farming-advisor"))
            .put("name", JSONObject().put("type", "string").put("description", "Shown name, e.g. Farming advisor"))
            .put("prompt", JSONObject().put("type", "string").put("description", "2-4 sentences: what it does, how, what it returns, what it never does"))
            .put("groups", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string").put("enum", JSONArray(groupNames)))
                .put("description", "Tool groups it may use (only what it needs)"))
            .put("playbook", JSONObject().put("type", "string").put("description", "The field playbook (markdown, 2,000-4,000 characters, the six sections)")))
            .put("required", JSONArray(listOf("name", "prompt", "groups", "playbook")))))

    val groupNames = listOf("web", "phone", "comms", "connectors", "memory", "routines", "terminal", "device", "notifications", "browser", "study", "design")

    private val phone = setOf("open_app", "read_screen", "look_at_screen", "tap", "type_text", "scroll", "press_button", "open_url", "dial",
        "compose_sms", "set_alarm", "set_timer", "wait", "do_actions", "search_in_app", "run_skill", "open_settings")
    fun group(tool: String): String = when {
        tool in Web.names -> "web"
        tool.startsWith("browser_") -> "browser"
        tool == "flashcards" -> "study"
        tool == Designs.TOOL -> "design"
        tool in phone -> "phone"
        tool in com.blaze.assistant.phone.Skills.names -> "comms"
        tool.startsWith("mcp_") || tool.startsWith("gmail") || tool.startsWith("gcal") || tool.startsWith("drive") || tool.startsWith("gcontacts")
            || tool.startsWith("github") || tool.startsWith("spotify") || tool.startsWith("notion") || tool.startsWith("slack") || tool == "share_to_app" -> "connectors"
        tool in setOf("remember", "forget", "recall_memory", "search_past_chats", "search_screen_memory") -> "memory"
        tool.endsWith("_routine") || tool == "list_routines" || tool.endsWith("_trigger") || tool == "list_triggers" -> "routines"
        tool.startsWith("terminal") || tool == "run_shell" || tool == "git" || tool in com.blaze.assistant.terminal.DevTools.names -> "terminal"
        tool == "device_status" -> "device"
        tool.contains("notification") -> "notifications"
        else -> "other"
    }

    fun filter(all: JSONArray, s: Spec): JSONArray = JSONArray().apply {
        for (i in 0 until all.length()) {
            val t = all.getJSONObject(i)
            val n = t.getJSONObject("function").getString("name")
            if (n == "delegate") continue
            if (group(n) in s.groups) put(t)
        }
    }

    fun tool(): JSONObject {
        val list = all().joinToString("; ") { "${it.id} (${it.name})" }
        return JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "delegate")
            .put("description", "Hand work to specialists: $list. It does NOT block: each task starts as a background specialist with its own card (Steer / Stop) and the chat keeps going; " +
                "each result reaches you on its own as soon as it lands, and you then write a short follow-up. Use it only when it helps: independent parts that can run in parallel, or big research. " +
                "One task per target (compare 3 shops = 3 Shopper tasks), never two specialists on the same target. Each task is a tight, self-contained brief (they don't see this chat): " +
                "the goal, what to return, limits, and to stop and report after two failed tries. Small or step-by-step work you do yourself. " +
                "For two or more tasks give scope: one line telling the user who you're sending and why. wait=true only when you can't continue without the answers (it blocks).")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("tasks", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("specialist", JSONObject().put("type", "string"))
                    .put("task", JSONObject().put("type", "string"))
                    .put("title", JSONObject().put("type", "string").put("description", "2-5 word card title")))
                    .put("required", JSONArray(listOf("specialist", "task")))))
                .put("scope", JSONObject().put("type", "string").put("description", "One line for the user, e.g. \"Sending Researcher and Shopper to compare solar kits.\""))
                .put("wait", JSONObject().put("type", "boolean").put("description", "Block until all finish (rarely needed)")))
                .put("required", JSONArray(listOf("tasks")))))
    }
}
