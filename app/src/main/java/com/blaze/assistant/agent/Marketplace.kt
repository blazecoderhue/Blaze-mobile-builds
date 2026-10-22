package com.blaze.assistant.agent

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v0.33.0 Agent Marketplace: skill packs made by other Blaze users. Browse = GitHub repositories tagged with the topic
 * `blaze-skill` (anyone can publish one; newest/most-starred first). Every install is reviewed first: Blaze fetches the
 * SKILL.md, scans it for red flags (asks for PINs or passwords, sends money, ignores the user's approvals, hides what it
 * does, pipes scripts from the internet) and shows a confirm card with the verdict. Packs are sandboxed by design: a skill
 * is instructions only, and every tool it leads to still goes through Blaze's normal confirmations. Share = your own
 * skill zipped to Download/Blaze/<name>.blazepack.zip, ready to send or push to GitHub with the topic. Pure scan in [review]
 * (MarketplaceTest).
 */
object Marketplace {
    const val TOOL = "skill_market"
    const val TOPIC = "blaze-skill"

    data class Review(val score: Int, val flags: List<String>) { val verdict get() = when { score >= 6 -> "unsafe"; score >= 3 -> "caution"; else -> "looks safe" } }

    private val red: List<Triple<Regex, Int, String>> = listOf(
        Triple(Regex("""(?i)\b(ask|collect|get|send|store|save)\b.{0,40}\b(pin|password|otp|cvv|card number|seed phrase|private key)\b"""), 6, "handles PINs, passwords, codes or card numbers"),
        Triple(Regex("""(?i)\b(send|transfer|pay)\b.{0,30}\b(money|k\d|zmw|\$\d|airtime|momo|mobile money)\b"""), 3, "moves money"),
        Triple(Regex("""(?i)(without (asking|confirm\w*|telling)|skip (the )?(confirm\w*|approval)|don'?t (ask|tell) the user|silently|secretly)"""), 5, "acts without the user's approval or hides it"),
        Triple(Regex("""(?i)ignore (all |any )?(previous|prior|system|other) (instructions|rules|prompts)"""), 6, "tries to override Blaze's rules"),
        Triple(Regex("""(?i)(curl|wget)[^\n|]{0,120}\|\s*(ba|z)?sh\b"""), 5, "runs a script straight from the internet"),
        Triple(Regex("""(?i)\b(upload|post|send|forward)\b.{0,40}\b(contacts|messages|sms|photos|location|chat logs?|memories)\b.{0,40}\b(to|at)\b.{0,20}(https?://|server|webhook|discord|telegram bot)"""), 6, "sends your data to an outside server"),
        Triple(Regex("""(?i)\b(delete|wipe|format|factory reset)\b.{0,30}\b(all|everything|phone|storage|files)\b"""), 4, "deletes data broadly"),
        Triple(Regex("""(?i)(base64 -d|eval\(|\\x[0-9a-f]{2}\\x[0-9a-f]{2})"""), 2, "contains hidden or encoded commands"),
    )

    fun review(md: String): Review {
        var s = 0; val f = ArrayList<String>()
        for ((re, w, why) in red) if (re.containsMatchIn(md)) { s += w; f += why }
        if (!Regex("""^\s*---[\s\S]*?\bname:""").containsMatchIn(md)) { s += 1; f += "no proper name / description header" }
        return Review(s, f.distinct())
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Blaze"); c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode >= 400) throw Exception("HTTP ${c.responseCode}")
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Agent Marketplace: browse skill packs other Blaze users published (GitHub topic blaze-skill), review one for safety, " +
            "install it (shows a confirm card with the review), or share one of the user's own skills as a .blazepack.zip.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("browse", "review", "install", "share"))))
            .put("query", JSONObject().put("type", "string").put("description", "browse: what kind of pack (tutor, farm, legal…)"))
            .put("repo", JSONObject().put("type", "string").put("description", "review/install: owner/repo or a GitHub link"))
            .put("skill", JSONObject().put("type", "string").put("description", "share: the user's skill name")))
            .put("required", JSONArray(listOf("action")))))

    private fun repoOf(s: String): String? = Regex("""(?:github\.com/)?([A-Za-z0-9-]+/[A-Za-z0-9._-]+)""").find(s.trim().removeSuffix("/"))?.groupValues?.get(1)?.removeSuffix(".git")

    suspend fun run(ctx: Context, a: JSONObject): String = withContext(Dispatchers.IO) {
        when (a.optString("action")) {
            "browse" -> try {
                val q = URLEncoder.encode("topic:$TOPIC " + a.optString("query").trim(), "UTF-8")
                val items = JSONObject(get("https://api.github.com/search/repositories?q=$q&sort=stars&per_page=15")).optJSONArray("items") ?: JSONArray()
                if (items.length() == 0) "No packs found${if (a.optString("query").isNotBlank()) " for \u201c${a.optString("query")}\u201d" else ""} yet. Anyone can publish one: a GitHub repo with a SKILL.md and the topic $TOPIC."
                else (0 until items.length()).joinToString("\n") { i -> val o = items.getJSONObject(i)
                    "${o.optString("full_name")} \u2605${o.optInt("stargazers_count")} · ${o.optString("description").take(100)} · updated ${o.optString("pushed_at").take(10)}" } +
                    "\n(Review before installing.)"
            } catch (e: Exception) { "Couldn't reach the marketplace (${e.message})." }
            "review", "install" -> {
                val repo = repoOf(a.optString("repo")) ?: return@withContext "Which pack? Give owner/repo."
                val md = try {
                    val info = JSONObject(get("https://api.github.com/repos/$repo"))
                    val branch = info.optString("default_branch", "main")
                    val tree = JSONObject(get("https://api.github.com/repos/$repo/git/trees/$branch?recursive=1")).optJSONArray("tree") ?: JSONArray()
                    val paths = (0 until tree.length()).map { tree.getJSONObject(it).optString("path") }.filter { it.endsWith("SKILL.md") }.take(8)
                    if (paths.isEmpty()) return@withContext "$repo has no SKILL.md, so it isn't a Blaze skill pack."
                    paths.joinToString("\n\n") { p -> "<!-- $p -->\n" + get("https://raw.githubusercontent.com/$repo/$branch/$p").take(40_000) }
                } catch (e: Exception) { return@withContext "Couldn't read $repo (${e.message})." }
                val r = review(md)
                val summary = "Review of $repo: ${r.verdict}" + (if (r.flags.isEmpty()) "." else ": " + r.flags.joinToString("; ") + ".")
                if (a.optString("action") == "review") return@withContext summary + "\nFirst lines:\n" + md.take(800)
                if (r.score >= 6) return@withContext "$summary\nNot installed: it fails the safety review."
                if (!Agent.confirm("Install skill pack $repo?", "$summary Skills are instructions only; anything they lead to still asks you first.", "Install", always = true))
                    return@withContext "The user declined. Not installed."
                SkillPacks.install("https://github.com/$repo")
            }
            "share" -> {
                val n = a.optString("skill").trim().lowercase()
                val p = SkillPacks.all().firstOrNull { it.name.lowercase() == n || it.dir.name == n } ?: SkillPacks.all().firstOrNull { it.name.lowercase().contains(n) && n.isNotEmpty() }
                    ?: return@withContext "No skill of yours called \u201c$n\u201d."
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Blaze").apply { mkdirs() }
                val out = File(dir, "${p.dir.name}.blazepack.zip")
                ZipOutputStream(out.outputStream()).use { z -> p.dir.walkTopDown().filter { it.isFile && it.length() < 1_000_000 }.forEach { f ->
                    z.putNextEntry(ZipEntry(p.dir.name + "/" + f.relativeTo(p.dir).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
                "Saved ${out.name} in Download/Blaze. Send it to a friend (they install it by giving Blaze the file link), or put the folder in a public GitHub repo with the topic $TOPIC to list it in the marketplace."
            }
            else -> "Unknown action."
        }
    }
}
