package com.blaze.assistant.agent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The bundled skill library, ported from Blaze Desktop v0.7.0 / CLI v0.5.0: the skills that make
 * sense on a phone ship in assets/skills (SKILL.md + index.json with version and sha256) and are
 * seeded into ~/skills on start.
 *
 * Seeding is version-aware and never overwrites your work: a bundled skill is written when it's
 * missing, upgraded only when its SKILL.md still matches what Blaze last wrote (you haven't edited
 * it) and the shipped version is newer, and never brought back after you delete it (Restore does).
 * The manifest is ~/skills/.bundled.json. Skills can be switched off without deleting them.
 */
object SkillLibrary {
    data class Bundled(val name: String, val version: Int, val sha: String, val files: List<String> = listOf("SKILL.md"))
    /** [core] = one of [CoreSkills.NAMES] (v0.24.3): always on, can't be switched off or deleted, shown first. */
    data class Row(val name: String, val description: String, val bundled: Boolean, val on: Boolean,
                   val edited: Boolean, val installed: Boolean, val update: Boolean, val core: Boolean = false)

    private lateinit var app: Context
    private fun root() = File(com.blaze.assistant.terminal.Terminal.home(), "skills").apply { mkdirs() }
    private fun manifestFile() = File(root(), ".bundled.json")

    private fun sha(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.replace("\r\n", "\n").toByteArray()).joinToString("") { "%02x".format(it) }

    fun bundled(): List<Bundled> = try {
        val a = JSONObject(app.assets.open("skills/index.json").bufferedReader().use { it.readText() }).getJSONArray("skills")
        (0 until a.length()).map { a.getJSONObject(it) }.map { o -> Bundled(o.getString("name"), o.optInt("version", 1), o.optString("sha256"),
            o.optJSONArray("files")?.let { f -> (0 until f.length()).map { f.getString(it) } }?.filter { it.isNotBlank() && ".." !in it }?.ifEmpty { null } ?: listOf("SKILL.md")) }
    } catch (_: Exception) { emptyList() }

    private fun asset(name: String) = app.assets.open("skills/$name/SKILL.md").bufferedReader().use { it.readText() }

    /** name → {version, sha, deleted} as last written by Blaze. */
    private fun manifest(): JSONObject = try { JSONObject(manifestFile().readText()) } catch (_: Exception) { JSONObject() }
    private fun saveManifest(m: JSONObject) { runCatching { manifestFile().writeText(m.toString(1)) } }

    private fun install(b: Bundled, m: JSONObject) {
        val text = asset(b.name)
        val dir = File(root(), b.name).apply { mkdirs() }
        File(dir, "SKILL.md").writeText(text)
        // Extra files (templates, starters, tokens) ship next to SKILL.md since v0.8.0; read them with skill_file.
        for (rel in b.files) if (rel != "SKILL.md") runCatching {
            val out = File(dir, rel); out.parentFile?.mkdirs()
            app.assets.open("skills/${b.name}/$rel").use { inp -> out.outputStream().use { inp.copyTo(it) } }
        }
        m.put(b.name, JSONObject().put("version", b.version).put("sha", sha(text)))
    }

    /** Called on start (off the main thread). */
    fun seed(ctx: Context) {
        app = ctx.applicationContext
        try {
            val m = manifest()
            for (b in bundled()) {
                val rec = m.optJSONObject(b.name)
                val f = File(root(), "${b.name}/SKILL.md")
                when {
                    CoreSkills.isCore(b.name) && (rec?.optBoolean("deleted") == true || !f.exists()) -> install(b, m)   // core: always there
                    rec?.optBoolean("deleted") == true -> {}                       // you deleted it: stay gone
                    !f.exists() -> if (rec == null) install(b, m) else m.put(b.name, rec.put("deleted", true))
                    rec == null -> {                                               // a skill of the same name you made yourself
                        if (sha(f.readText()) == b.sha) m.put(b.name, JSONObject().put("version", b.version).put("sha", b.sha))
                    }
                    b.version > rec.optInt("version") && sha(f.readText()) == rec.optString("sha") -> install(b, m)
                }
            }
            saveManifest(m)
        } catch (_: Exception) { }
    }

    fun init(ctx: Context) { app = ctx.applicationContext }

    // ---------- on / off ----------

    private fun offSet(): Set<String> = Settings.str("skills_off").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun isOn(dirName: String) = CoreSkills.isCore(dirName) || dirName !in offSet()
    fun setOn(dirName: String, on: Boolean) {
        if (CoreSkills.isCore(dirName) && !on) return                       // core skills can't be switched off
        val s = offSet().toMutableSet(); if (on) s -= dirName else s += dirName
        Settings.setStr("skills_off", s.sorted().joinToString(","))
        SkillMatch.invalidate()   // v0.26.2: the skills index is rebuilt on toggle
    }

    // ---------- delete / restore ----------

    fun delete(dirName: String) {
        if (CoreSkills.isCore(dirName)) return                              // core skills can't be deleted (Restore original resets edits)
        File(root(), dirName).deleteRecursively()
        SkillMatch.invalidate()
        com.blaze.assistant.connect.SkillSync.localChange()
        val m = manifest()
        m.optJSONObject(dirName)?.let { m.put(dirName, it.put("deleted", true)); saveManifest(m) }
        setOn(dirName, true)
    }

    /** Puts a bundled skill back as shipped (also undoes your edits to it). */
    fun restore(name: String): Boolean {
        val b = bundled().firstOrNull { it.name == name } ?: return false
        val m = manifest(); install(b, m); saveManifest(m); setOn(name, true)
        return true
    }

    fun rows(): List<Row> {
        val m = manifest()
        val packs = SkillPacks.all()
        val bundled = bundled()
        val out = packs.map { p ->
            val b = bundled.firstOrNull { it.name == p.dir.name }
            val rec = m.optJSONObject(p.dir.name)
            val cur = runCatching { sha(File(p.dir, "SKILL.md").readText()) }.getOrDefault("")
            val edited = b != null && rec != null && cur != rec.optString("sha")
            Row(p.dir.name, p.description, b != null, isOn(p.dir.name), edited, true,
                b != null && rec != null && b.version > rec.optInt("version"), CoreSkills.isCore(p.dir.name))
        }
        val missing = bundled.filter { b -> packs.none { it.dir.name == b.name } }.map { b ->
            Row(b.name, "", true, false, false, false, false, CoreSkills.isCore(b.name))
        }
        return out + missing
    }
}
