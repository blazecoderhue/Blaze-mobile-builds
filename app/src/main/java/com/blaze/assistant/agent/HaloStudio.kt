package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.33.0 Halo Persona Studio: tune Halo with sliders (warmth, humour, playfulness, formality, length), a language mix
 * (English with Bemba / Nyanja / any other), and boundaries ("never swear", "don't call me babe"); save several personas
 * and switch between them. The active one is added to Halo's soul on every Halo turn ([extra]). Pure text in [describe]
 * (HaloStudioTest). Settings › Advanced › Personality, or the halo_persona tool.
 */
object HaloStudio {
    const val TOOL = "halo_persona"

    data class P(val name: String, val warmth: Int = 7, val humour: Int = 5, val play: Int = 6, val formality: Int = 3, val length: Int = 4,
                 val langs: Map<String, Int> = emptyMap(), val boundaries: String = "", val voice: String = "")

    val DEFAULT = P("Halo")

    fun toJson(p: P): JSONObject = JSONObject().put("name", p.name).put("warmth", p.warmth).put("humour", p.humour).put("play", p.play)
        .put("formality", p.formality).put("length", p.length).put("langs", JSONObject(p.langs.mapValues { it.value as Any })).put("boundaries", p.boundaries).put("voice", p.voice)

    fun fromJson(o: JSONObject): P = P(o.optString("name", "Halo"), o.optInt("warmth", 7).c(), o.optInt("humour", 5).c(), o.optInt("play", 6).c(),
        o.optInt("formality", 3).c(), o.optInt("length", 4).c(),
        o.optJSONObject("langs")?.let { j -> j.keys().asSequence().associateWith { j.optInt(it).coerceIn(0, 100) }.filterValues { it > 0 } } ?: emptyMap(),
        o.optString("boundaries"), o.optString("voice"))

    private fun Int.c() = coerceIn(0, 10)

    private fun level(v: Int, lo: String, mid: String, hi: String) = when { v <= 3 -> lo; v <= 6 -> mid; else -> hi }

    /** The instruction block for persona [p] ("" for the untouched default). */
    fun describe(p: P): String {
        if (p == DEFAULT.copy(name = p.name)) return ""
        val parts = ArrayList<String>()
        parts += level(p.warmth, "cool and matter-of-fact", "friendly", "very warm and affectionate (kept respectful)")
        parts += level(p.humour, "serious, almost no jokes", "an occasional light joke", "funny, jokes often")
        parts += level(p.play, "calm and steady", "lively", "bubbly and playful")
        parts += level(p.formality, "casual, like texting a friend", "relaxed but polished", "formal and proper")
        parts += level(p.length, "very short replies", "medium replies", "rich, detailed replies")
        val sb = StringBuilder("\nHALO PERSONA \u201c${p.name}\u201d (the user tuned this; follow it): be ${parts.joinToString(", ")}.")
        val other = p.langs.filterKeys { !it.equals("English", true) }.filterValues { it > 0 }
        if (other.isNotEmpty()) sb.append(" Language mix: mostly English, with " + other.entries.joinToString(" and ") { "${it.key} in about ${it.value}% of sentences" } +
            " (natural code-switching, the way people in Zambia text; keep it simple and correct, and switch fully to English if the user seems not to follow).")
        if (p.boundaries.isNotBlank()) sb.append(" Boundaries (never break these): ${p.boundaries.trim().take(400)}.")
        return sb.toString()
    }

    fun all(): List<P> = try { JSONArray(Settings.str("halo_personas", "[]")).let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } } } catch (_: Exception) { emptyList() }
    fun active(): P = all().firstOrNull { it.name == Settings.str("halo_persona_active") } ?: DEFAULT
    fun extra(): String = try { describe(active()) } catch (_: Throwable) { "" }

    fun save(p: P, activate: Boolean = true) {
        val l = all().filterNot { it.name.equals(p.name, true) } + p
        Settings.setStr("halo_personas", JSONArray(l.map { toJson(it) }).toString())
        if (activate) Settings.setStr("halo_persona_active", p.name)
    }
    fun switch(name: String): Boolean { val p = all().firstOrNull { it.name.equals(name, true) } ?: return false; Settings.setStr("halo_persona_active", p.name); return true }
    fun delete(name: String) { Settings.setStr("halo_personas", JSONArray(all().filterNot { it.name.equals(name, true) }.map { toJson(it) }).toString())
        if (Settings.str("halo_persona_active").equals(name, true)) Settings.setStr("halo_persona_active", "") }
    fun reset() = Settings.setStr("halo_persona_active", "")

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Halo Persona Studio: change how Halo talks (0-10 sliders warmth, humour, play, formality, length; language mix like " +
            "{\"Bemba\":30}; boundaries), save named personas and switch. Use when the user asks Halo to be funnier, speak Nyanja, stop calling them something…")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("set", "switch", "list", "delete", "reset"))))
            .put("name", JSONObject().put("type", "string"))
            .put("warmth", JSONObject().put("type", "integer")).put("humour", JSONObject().put("type", "integer")).put("play", JSONObject().put("type", "integer"))
            .put("formality", JSONObject().put("type", "integer")).put("length", JSONObject().put("type", "integer"))
            .put("langs", JSONObject().put("type", "object").put("description", "language -> % of sentences, e.g. {\"Bemba\":30,\"Nyanja\":10}"))
            .put("boundaries", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action")))))

    fun run(a: JSONObject): String = when (a.optString("action")) {
        "list" -> all().joinToString("\n") { (if (it.name == active().name) "• " else "  ") + it.name + describe(it).substringAfter("follow it): ", "").take(160) }.ifBlank { "Only the default Halo." }
        "switch" -> if (switch(a.optString("name"))) "Halo is now \u201c${active().name}\u201d." else "No persona called ${a.optString("name")}."
        "delete" -> { delete(a.optString("name")); "Deleted." }
        "reset" -> { reset(); "Halo is back to default." }
        else -> {
            val base = all().firstOrNull { it.name.equals(a.optString("name"), true) } ?: active()
            val j = toJson(base)
            listOf("warmth", "humour", "play", "formality", "length", "boundaries").forEach { k -> if (a.has(k)) j.put(k, a.get(k)) }
            a.optJSONObject("langs")?.let { j.put("langs", it) }
            j.put("name", a.optString("name").ifBlank { if (base.name == "Halo") "My Halo" else base.name })
            val p = fromJson(j); save(p)
            "Saved and switched to \u201c${p.name}\u201d." + describe(p).substringAfter(":")
        }
    }
}
