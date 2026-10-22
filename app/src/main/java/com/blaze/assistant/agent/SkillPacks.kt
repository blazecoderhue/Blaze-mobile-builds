package com.blaze.assistant.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Skill packs in the Claude/Anthropic "Agent Skills" format: a folder with a SKILL.md
 * (front matter with name + description, then instructions) and optional scripts/ and
 * references/ files. They live in ~/skills/<name>/ so run_shell can execute their scripts.
 *
 * Progressive loading keeps prompts small: only "name: description" lines go into the system
 * prompt; the full SKILL.md is read with use_skill when a task matches, and extra files only
 * with skill_file. Install from a GitHub folder, a raw SKILL.md URL, or a .zip. Each skill is a
 * K process, so it can be listed and deleted by ID. The older tap-replay skills still work too.
 */
object SkillPacks {
    data class Pack(val name: String, val description: String, val dir: File)

    private fun root(): File = File(com.blaze.assistant.terminal.Terminal.home(), "skills").apply { mkdirs() }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifBlank { "skill" }

    private fun frontMatter(md: String): Map<String, String> {
        val m = Regex("""^\s*---\s*\n(.*?)\n---""", RegexOption.DOT_MATCHES_ALL).find(md) ?: return emptyMap()
        return m.groupValues[1].lines().mapNotNull { l ->
            val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim().trim('"', '\'')
        }.toMap()
    }

    fun all(): List<Pack> = (root().listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
        val f = File(d, "SKILL.md").takeIf { it.exists() } ?: return@mapNotNull null
        val fm = try { frontMatter(f.readText()) } catch (_: Exception) { emptyMap() }
        Pack(fm["name"] ?: d.name, fm["description"].orEmpty(), d)
    }.sortedBy { it.name }

    private fun find(name: String): Pack? { val s = slug(name); return all().firstOrNull { slug(it.name) == s || it.dir.name == s } }

    private fun register(p: Pack) {
        if (Processes.idFor("skill", p.dir.name) == null)
            Processes.register("skill", p.name, p.dir.name, props = JSONObject().put("about", p.description.take(160)))
    }

    /**
     * System prompt block. With a handful of skills, names and one-line descriptions; with more,
     * the ones that match this request in full plus the rest by name only (like Blaze Desktop).
     * Skills switched off in Settings are left out.
     * v0.26.2: no longer sent each turn (SkillMatch.lookupBlock replaces it); kept for /skills-style listings.
     */
    fun promptBlock(query: String = ""): String {
        // v0.26.1: an in-chat diagram / chart leaves out the file-route skills (VisualRoute).
        val l = all().filter { SkillLibrary.isOn(it.dir.name) && !VisualRoute.skipSkill(query, it.dir.name) }; if (l.isEmpty()) return ""
        fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
        val head = "\nSKILLS (call use_skill to read one before doing a matching task): "
        if (l.size <= 12 || query.isBlank()) return head + l.take(40).joinToString("; ") { "${it.name}: ${it.description.take(120)}" }
        val q = words(query)
        val hits = l.map { it to (words(it.name.replace('-', ' ') + " " + it.description) intersect q).size }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(6).map { it.first }
        val rest = l - hits.toSet()
        return head + (if (hits.isNotEmpty()) hits.joinToString("; ") { "${it.name}: ${it.description.take(140)}" } + ". Also: " else "") +
            rest.take(40).joinToString(", ") { it.name }
    }

    fun use(name: String): String {
        val p = find(name) ?: return "No skill \u201c$name\u201d. Installed: ${all().joinToString { it.name }.ifBlank { "none" }}."
        if (!SkillLibrary.isOn(p.dir.name)) return "The skill ${p.name} is switched off in Settings \u203a Skill library. Do the task without it."
        val body = File(p.dir, "SKILL.md").readText()
        val files = p.dir.walkTopDown().filter { it.isFile && it.name != "SKILL.md" }.map { it.relativeTo(p.dir).path }.take(30).toList()
        return body.take(12_000) + (if (files.isNotEmpty()) "\n\nFiles in this skill (folder ~/skills/${p.dir.name}/; read with skill_file, run scripts with run_shell): ${files.joinToString()}" else "")
    }

    fun file(name: String, path: String): String {
        val p = find(name) ?: return "No skill \u201c$name\u201d."
        val f = File(p.dir, path).canonicalFile
        if (!f.path.startsWith(p.dir.canonicalPath) || !f.isFile) return "No file $path in ${p.name}."
        return f.readText().take(15_000)
    }

    fun write(name: String, description: String, instructions: String): String {
        if (name.isBlank() || instructions.isBlank()) return "A skill needs a name and instructions."
        if (CoreSkills.isCore(slug(name))) return "${slug(name)} is a core skill and can't be overwritten. Save yours under another name."
        val d = File(root(), slug(name)).apply { mkdirs() }
        File(d, "SKILL.md").writeText("---\nname: ${slug(name)}\ndescription: ${description.replace("\n", " ").take(300)}\n---\n\n$instructions\n")
        val p = Pack(slug(name), description, d); register(p)
        SkillMatch.invalidate()
        return "Skill ${p.name} saved [ID ${Processes.idFor("skill", d.name)}]."
    }

    // ---------- Workflows (v0.24.3, core skill blaze-workflow-builder) ----------

    private fun lines(v: String): List<String> = v.lines().map { it.trim().replace(Regex("""^(?:[-*•]\s+|\d{1,2}[.)]\s+)"""), "").trim() }.filter { it.isNotEmpty() }

    /**
     * A reusable workflow as a SKILL.md: front matter (name, description = the trigger, tags so the per-turn lookup
     * finds it, `workflow: true`, `tested:`), then Trigger, numbered Steps and Checks. Pure (CoreSkillsTest).
     */
    fun workflowMd(name: String, trigger: String, steps: String, checks: String, tags: List<String>, tested: Boolean): String {
        val n = slug(name)
        val tagList = (tags + trigger.lowercase().split(Regex("[,;/]")).map { it.trim() }.filter { it.length in 3..40 })
            .map { it.replace(Regex("[\\[\\],\"']"), "").trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(12)
        return buildString {
            append("---\nname: $n\n")
            append("description: Workflow. Use when: ${trigger.replace("\n", " ").trim().take(240)}\n")
            if (tagList.isNotEmpty()) append("tags: [${tagList.joinToString(", ")}]\n")
            append("workflow: true\ntested: $tested\n---\n\n")
            append("# ${n.replace('-', ' ').replaceFirstChar { it.uppercase() }}\n\n")
            append("## Trigger\n${trigger.trim()}\n\n## Steps\n")
            lines(steps).forEachIndexed { i, l -> append("${i + 1}. $l\n") }
            val c = lines(checks)
            if (c.isNotEmpty()) { append("\n## Checks\n"); c.forEach { append("- $it\n") } }
            append("\n## Notes\n- Follow the steps in order; verify each check before saying it's done. If a step fails twice, stop and report.\n")
            if (!tested) append("- Not tested yet: follow it once, fix what was wrong, then save again with tested=true.\n")
        }
    }

    fun saveWorkflow(name: String, trigger: String, steps: String, checks: String, tags: List<String>, tested: Boolean): String {
        if (name.isBlank() || trigger.isBlank() || lines(steps).isEmpty()) return "A workflow needs a name, a trigger and at least one step."
        if (CoreSkills.isCore(slug(name))) return "${slug(name)} is a core skill. Pick another name for the workflow."
        val d = File(root(), slug(name)).apply { mkdirs() }
        val existed = File(d, "SKILL.md").exists()
        File(d, "SKILL.md").writeText(workflowMd(name, trigger, steps, checks, tags, tested))
        register(Pack(slug(name), "Workflow. Use when: $trigger", d))
        SkillLibrary.setOn(d.name, true)
        SkillMatch.invalidate()
        return "Workflow ${slug(name)} ${if (existed) "updated" else "saved"} [ID ${Processes.idFor("skill", d.name)}]" +
            (if (tested) ". It's marked tested." else ". Test it once by following the steps, then save again with tested=true.") +
            " It's looked up automatically when a request matches its trigger."
    }

    fun delete(dirName: String) = SkillLibrary.delete(dirName)

    // ---------- Install ----------

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Blaze")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode >= 400) throw Exception("HTTP ${c.responseCode} for $url")
        return c.inputStream.use { it.readBytes() }
    }

    suspend fun install(urlRaw: String): String = withContext(Dispatchers.IO) {
        val url = urlRaw.trim()
        try {
            when {
                url.endsWith(".zip", true) -> installZip(get(url))
                url.endsWith("SKILL.md", true) -> {
                    val raw = url.replace("github.com/", "raw.githubusercontent.com/").replace("/blob/", "/")
                    val md = String(get(raw))
                    val fm = frontMatter(md)
                    val name = fm["name"] ?: raw.substringBeforeLast("/SKILL.md").substringAfterLast('/')
                    val d = File(root(), slug(name)).apply { mkdirs() }
                    File(d, "SKILL.md").writeText(md)
                    register(Pack(fm["name"] ?: name, fm["description"].orEmpty(), d))
                    "Installed skill ${fm["name"] ?: name}."
                }
                url.contains("github.com/") -> installGithub(url)
                else -> "Give me a GitHub folder link, a SKILL.md link, or a .zip."
            }
        } catch (e: Exception) { "Couldn't install: ${e.message}" }
    }

    /** github.com/owner/repo[/tree/branch/path]: every folder with a SKILL.md under it is installed. */
    private fun installGithub(url: String): String {
        val parts = url.substringAfter("github.com/").trimEnd('/').split('/')
        if (parts.size < 2) return "That doesn't look like a GitHub repo link."
        val owner = parts[0]; val repo = parts[1].removeSuffix(".git")
        val ref = if (parts.getOrNull(2) == "tree") parts.getOrNull(3) else null
        val path = if (parts.getOrNull(2) == "tree") parts.drop(4).joinToString("/") else ""
        val installed = mutableListOf<String>()
        var calls = 0
        fun pull(p: String, into: File, depth: Int) {
            if (depth > 4 || calls > 40) return
            calls++
            val api = "https://api.github.com/repos/$owner/$repo/contents/$p" + (ref?.let { "?ref=$it" } ?: "")
            val a = JSONArray(String(get(api)))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val name = o.getString("name")
                if (o.getString("type") == "file") {
                    if (o.optLong("size") > 400_000) continue
                    File(into, name).apply { parentFile?.mkdirs() }.writeBytes(get(o.getString("download_url")))
                } else if (o.getString("type") == "dir") pull(o.getString("path"), File(into, name), depth + 1)
            }
        }
        val staging = File(root(), ".staging-" + System.currentTimeMillis()).apply { mkdirs() }
        try {
            pull(path, staging, 0)
            staging.walkTopDown().filter { it.name == "SKILL.md" }.toList().forEach { md ->
                val fm = frontMatter(md.readText())
                val name = slug(fm["name"] ?: md.parentFile!!.name.takeIf { it != staging.name } ?: repo)
                val dest = File(root(), name)
                dest.deleteRecursively()
                md.parentFile!!.copyRecursively(dest, overwrite = true)
                register(Pack(fm["name"] ?: name, fm["description"].orEmpty(), dest))
                installed += name
            }
        } finally { staging.deleteRecursively() }
        return if (installed.isEmpty()) "No SKILL.md found there." else "Installed: ${installed.joinToString()}."
    }

    private fun installZip(bytes: ByteArray): String {
        val staging = File(root(), ".staging-" + System.currentTimeMillis()).apply { mkdirs() }
        try {
            ZipInputStream(bytes.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val f = File(staging, e.name).canonicalFile
                    if (f.path.startsWith(staging.canonicalPath)) {
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.writeBytes(z.readBytes()) }
                    }
                    e = z.nextEntry
                }
            }
            val names = staging.walkTopDown().filter { it.name == "SKILL.md" }.toList().map { md ->
                val fm = frontMatter(md.readText())
                val name = slug(fm["name"] ?: md.parentFile!!.name)
                val dest = File(root(), name); dest.deleteRecursively()
                md.parentFile!!.copyRecursively(dest, overwrite = true)
                register(Pack(fm["name"] ?: name, fm["description"].orEmpty(), dest)); name
            }
            return if (names.isEmpty()) "No SKILL.md in that zip." else "Installed: ${names.joinToString()}."
        } finally { staging.deleteRecursively() }
    }

    // ---------- Starter pack ----------

    private val starters = listOf(
        Triple("sign-in-flows", "Signing in to websites and apps quickly and safely (Google sign-in, Continue as, cookie banners)", """
# Sign-in flows
1. Prefer Blaze's background browser for websites; use the screen only when the site needs the user's own app session.
2. Before you start, switch on watchers: watch preset=cookies and preset=popups, and preset=google_signin if the site offers Google.
3. For "Choose an account", ask the user which account with ask_user unless they named one.
4. Emails, passwords, phone numbers, API keys and codes come from browser_secure_input (a secure form in the chat; saved logins fill in by themselves). Never ask for them in chat, never invent or guess one; you only see {{secret:...}} placeholders.
5. Captchas and approvals in another app: ask the user (ask_user) or offer browser_show so they can do that step, then continue.
7. If the site says the account can't use a password (e.g. GitHub's "does not support password sign-in"), stop asking for it: offer Continue with Google, a passkey or the live browser with ask_user.
6. Check you are really signed in (an account name, avatar or dashboard) before saying it worked.
"""),
        Triple("research-report", "Researching a question on the web and giving a short, sourced answer", """
# Research report
1. Run 2-3 web_search queries with different wording; open the 2-3 best results with fetch_page.
2. Prefer official and recent sources; note dates.
3. Answer in a few sentences first, then the key facts with the site names.
4. For anything long, save it to a file in ~/reports/ with run_shell and say where it is.
"""),
        Triple("files-and-downloads", "Downloading, organising, unzipping or converting files on the phone", """
# Files and downloads
1. Use run_shell (BusyBox: wget, unzip, tar, find, du) in ~ for downloads and file work; run long downloads with background=true.
2. Phone storage is at /sdcard (Download, DCIM, Documents) when storage access is granted.
3. Never delete anything outside ~ without the user's OK; show what will be removed first.
4. Report the final path and size when done.
"""),
        Triple("phone-task-speed", "Doing multi-step tasks inside phone apps as fast as possible (actions in apps, not questions about the phone's state)", """
# Fast phone tasks
1. Use run_plan with the whole script at once, targets by their visible words.
2. Set watchers for pop-ups you expect (preset=popups) so they never cost a step.
3. Prefer deep links and open_url (e.g. maps, WhatsApp, YouTube search URLs) over tapping through menus.
4. After a failure, read the changed elements and send a short new run_plan from where it stopped.
"""),
    )

    fun ensureStarters() {
        try {
            if (Settings.bool("skill_starters_done", false)) return
            for ((n, d, body) in starters) if (find(n) == null) write(n, d, body.trimIndent())
            Settings.setBool("skill_starters_done", true)
        } catch (_: Exception) { }
    }

    // ---------- Tools ----------

    val names = setOf("use_skill", "find_skill", "skill_file", "install_skill", "write_skill", "save_workflow")

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun str(d: String = "") = JSONObject().put("type", "string").apply { if (d.isNotBlank()) put("description", d) }

    fun tools(): List<JSONObject> = listOf(
        fn("use_skill", "Read a skill's full instructions (SKILL.md) before a task it covers. Only for a skill that is about what the user asked; unrelated skills are refused.",
            JSONObject().put("name", str()).put("reason", str("Only after a refusal: how this skill applies to the request")), listOf("name")),
        fn("find_skill", "Search all installed and learned skills by what you need to do (best matches first, with descriptions). Use it when the skills listed this turn don't fit.",
            JSONObject().put("query", str("What the task is about, in plain words")), listOf("query")),
        fn("skill_file", "Read an extra file from a skill (reference or script).", JSONObject().put("name", str()).put("path", str()), listOf("name", "path")),
        fn("install_skill", "Install skills from a GitHub folder/repo link, a SKILL.md link, or a .zip URL.", JSONObject().put("url", str()), listOf("url")),
        fn("write_skill", "Save a procedure you worked out as a skill (SKILL.md) for next time.",
            JSONObject().put("name", str()).put("description", str("When to use it, one line")).put("instructions", str("Markdown steps")), listOf("name", "description", "instructions")),
        fn("save_workflow", "Save a multi-step or recurring task as a reusable workflow (a user skill with trigger, steps and checks). It is looked up automatically next time a request matches. Save again with the same name to fix or mark it tested.",
            JSONObject().put("name", str("short-kebab-name")).put("trigger", str("When to use it, in the user's words"))
                .put("steps", str("Numbered steps, one action each, with the tool to use")).put("checks", str("How to know it worked, one per line"))
                .put("tags", JSONObject().put("type", "array").put("items", str()).put("description", "Words people would type for it"))
                .put("tested", JSONObject().put("type", "boolean").put("description", "true once you followed it and it worked")),
            listOf("name", "trigger", "steps")),
    )

    /** Models sometimes send steps as an array: accept both. */
    private fun textOrList(a: JSONObject, k: String): String =
        a.optJSONArray(k)?.let { arr -> (0 until arr.length()).joinToString("\n") { arr.optString(it) } } ?: a.optString(k)

    suspend fun run(name: String, a: JSONObject): String = when (name) {
        "use_skill" -> use(a.optString("name"))
        "find_skill" -> SkillMatch.find(a.optString("query").ifBlank { a.optString("name") })
        "skill_file" -> file(a.optString("name"), a.optString("path"))
        "install_skill" -> install(a.optString("url")).also { SkillMatch.invalidate(); com.blaze.assistant.connect.SkillSync.localChange() }
        "write_skill" -> write(a.optString("name"), a.optString("description"), a.optString("instructions")).also { com.blaze.assistant.connect.SkillSync.localChange() }
        "save_workflow" -> saveWorkflow(a.optString("name"), a.optString("trigger"), textOrList(a, "steps"), textOrList(a, "checks"),
            a.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: emptyList(), a.optBoolean("tested", false))
            .also { com.blaze.assistant.connect.SkillSync.localChange() }
        else -> "Unknown tool"
    }
}
