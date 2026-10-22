package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Marketer & seller pack (v0.17.0): the brand kit (same fields as Blaze Desktop's studio Brand, kept
 * in ~/.blaze/brand.json so skills, the terminal and Desktop's format line up) and the weekly store
 * digest, a Schedule job that runs the weekly-digest skill.
 */
data class Brand(val name: String = "", val source: String = "", val paper: String = "", val ink: String = "",
                 val accents: List<String> = emptyList(), val fonts: List<String> = emptyList(), val radius: String = "",
                 val logo: String = "", val notes: String = "") {
    val empty get() = name.isBlank() && paper.isBlank() && ink.isBlank() && accents.isEmpty() && fonts.isEmpty() && radius.isBlank() && logo.isBlank() && notes.isBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        fun p(k: String, v: String) { if (v.isNotBlank()) put(k, v) }
        p("name", name); p("source", source); p("paper", paper); p("ink", ink)
        if (accents.isNotEmpty()) put("accents", JSONArray(accents)); if (fonts.isNotEmpty()) put("fonts", JSONArray(fonts))
        p("radius", radius); p("logo", logo); p("notes", notes)
    }

    /** The block skills and design prompts carry (worded like Desktop's Brand.Prompt). */
    fun prompt(): String = if (empty) "" else buildString {
        append("BRAND KIT (follow it in every design and post unless the user says otherwise")
        if (source.isNotBlank()) append("; from $source")
        append(")\n")
        if (name.isNotBlank()) append("- Name: $name\n")
        if (paper.isNotBlank() || ink.isNotBlank()) append("- Background ${paper.ifBlank { "-" }}, text ${ink.ifBlank { "-" }}\n")
        if (accents.isNotEmpty()) append("- Accent colours: ${accents.joinToString(", ")} (first is primary)\n")
        if (fonts.isNotEmpty()) append("- Fonts: ${fonts.joinToString(", ")}\n")
        if (radius.isNotBlank()) append("- Corner radius: $radius\n")
        if (logo.isNotBlank()) append("- Logo: $logo\n")
        if (notes.isNotBlank()) append("- Voice and notes: $notes\n")
    }

    companion object {
        private fun list(o: JSONObject, k: String): List<String> = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() } }
            ?: o.optString(k).split(',').map { it.trim() }.filter { it.isNotBlank() }
        fun from(o: JSONObject) = Brand(o.optString("name"), o.optString("source"), o.optString("paper"), o.optString("ink"),
            list(o, "accents"), list(o, "fonts"), o.optString("radius"), o.optString("logo"), o.optString("notes"))
        private val hex = Regex("^#?[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$")
        /** "#1f4bd8", "1F4BD8" → "#1f4bd8"; anything else (a name like "navy") is kept as typed. */
        fun color(s: String): String { val t = s.trim(); return if (hex.matches(t)) "#" + t.removePrefix("#").lowercase() else t }
    }
}

object Seller {
    @Volatile var brandFileOverride: File? = null
    private fun brandFile(): File = brandFileOverride ?: File(com.blaze.assistant.terminal.Terminal.home(), ".blaze/brand.json")

    fun brand(): Brand = try { brandFile().takeIf { it.isFile }?.let { Brand.from(JSONObject(it.readText())) } ?: Brand() } catch (_: Exception) { Brand() }

    fun saveBrand(b: Brand) {
        val f = brandFile(); f.parentFile?.mkdirs()
        val clean = b.copy(paper = Brand.color(b.paper), ink = Brand.color(b.ink), accents = b.accents.map { Brand.color(it) })
        File(f.path + ".tmp").apply { writeText(clean.toJson().toString(2)) }.renameTo(f)
    }

    /** Merge the fields given in [a] into the saved kit (blank = unchanged, "-" = clear). */
    fun merge(old: Brand, a: JSONObject): Brand {
        fun s(k: String, cur: String) = if (!a.has(k)) cur else a.optString(k).trim().let { if (it == "-") "" else it.ifBlank { cur } }
        fun l(k: String, cur: List<String>): List<String> {
            if (!a.has(k)) return cur
            val v = Brand.from(JSONObject().put(k, a.opt(k))).let { if (k == "accents") it.accents else it.fonts }
            return if (a.optString(k).trim() == "-") emptyList() else v.ifEmpty { cur }
        }
        return Brand(s("name", old.name), s("source", old.source), s("paper", old.paper), s("ink", old.ink), l("accents", old.accents),
            l("fonts", old.fonts), s("radius", old.radius), s("logo", old.logo), s("notes", old.notes))
    }

    const val TOOL = "brand_kit"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "The user's brand kit (store or business look and voice), used for posts, ads, listings and designs. get: read it; set: change only the fields given (\"-\" clears one).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("get", "set"))))
            .put("name", JSONObject().put("type", "string")).put("paper", JSONObject().put("type", "string").put("description", "Background colour, e.g. #fcfcfc"))
            .put("ink", JSONObject().put("type", "string").put("description", "Text colour"))
            .put("accents", JSONObject().put("type", "string").put("description", "Accent colours, comma separated, primary first"))
            .put("fonts", JSONObject().put("type", "string").put("description", "Fonts, comma separated, headings first"))
            .put("radius", JSONObject().put("type", "string")).put("logo", JSONObject().put("type", "string").put("description", "Logo path or URL"))
            .put("notes", JSONObject().put("type", "string").put("description", "Voice, audience, words to use or avoid"))
            .put("source", JSONObject().put("type", "string").put("description", "Where it came from (store URL)")))
            .put("required", JSONArray(listOf("action")))))

    fun run(a: JSONObject): String = if (a.optString("action") == "set") {
        val b = merge(brand(), a); saveBrand(b)
        "Brand kit saved.\n" + brand().prompt()
    } else brand().let { if (it.empty) "No brand kit yet. Ask for the store name, colours, fonts and voice (or read them from the store's website), then brand_kit set." else it.prompt() }

    // ---------------- weekly digest ----------------

    const val DIGEST_NAME = "Weekly store digest"
    const val DIGEST_PROMPT = "Use the weekly-digest skill: sum up last week's store orders and the top social posts, and suggest the next 3 posts. Keep it short."

    fun digestOn(): Boolean = com.blaze.assistant.routine.Routines.all.value.any { it.name == DIGEST_NAME && it.enabled }

    /** Switch the digest job on (Monday 08:00) or off. */
    fun setDigest(on: Boolean, hour: Int = 8, minute: Int = 0) {
        val R = com.blaze.assistant.routine.Routines
        R.all.value.filter { it.name == DIGEST_NAME }.forEach { R.delete(it.id) }
        if (on) R.add(DIGEST_NAME, DIGEST_PROMPT, hour, minute, R.parseDays("mon"))
    }
}
