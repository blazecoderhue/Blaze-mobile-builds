package com.blaze.assistant.terminal

import com.blaze.assistant.connect.Connectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Git inside Blaze's Alpine Linux, signed in with the user's connected GitHub account.
 * The token never goes into a command line or the output: a credential helper reads it from a
 * private file, and anything that echoes it back is scrubbed.
 */
object Git {
    const val NAME = "git"

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Map a user/agent path to its path inside Linux. Relative names live under ~/code. */
    fun linuxPath(path: String): String {
        val p = path.trim()
        return when {
            p.isEmpty() || p == "~" -> "/root/phone/code"
            p.startsWith("~/") -> "/root/phone/" + p.removePrefix("~/")
            p.startsWith("linux:") -> "/" + p.removePrefix("linux:").trimStart('/')
            p.startsWith("/") -> p
            else -> "/root/phone/code/$p"
        }
    }

    fun risky(a: JSONObject): Boolean = a.optString("action") == "raw" &&
        Regex("""(--force|-f\b|reset\s+--hard|clean\s+-[a-z]*f|branch\s+-D|push\s+.*:|filter-branch|rebase)""").containsMatchIn(a.optString("args"))

    private fun identity(token: String): Pair<String, String>? = try {
        val c = (URL("https://api.github.com/user").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Accept", "application/vnd.github+json"); connectTimeout = 10_000; readTimeout = 10_000 }
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val login = j.optString("login")
        val name = j.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: login
        val email = j.optString("email").takeIf { it.isNotBlank() && it != "null" } ?: "${j.optLong("id")}+$login@users.noreply.github.com"
        if (login.isBlank()) null else name to email
    } catch (_: Exception) { null }

    suspend fun run(a: JSONObject): String = withContext(Dispatchers.IO) {
        Terminal.setup()
        if (!Linux.installed()) {
            if (!Linux.supported()) return@withContext "Git needs Blaze's Linux, which this phone's CPU doesn't support. Use termux_run with git instead if Termux is installed."
            Linux.install()
            if (!Linux.installed()) return@withContext "Couldn't set up Linux for git: ${Linux.status.value}"
        }
        val dir = File(Terminal.home(), ".blaze").apply { mkdirs() }
        File(Terminal.home(), "code").mkdirs()
        val token = Connectors.access("github")
        val tokFile = File(dir, "gh_token")
        val helper = File(dir, "git-cred.sh")
        if (token != null) {
            tokFile.writeText(token); tokFile.setReadable(false, false); tokFile.setReadable(true, true)
            helper.writeText("#!/bin/sh\n[ \"\$1\" = get ] || exit 0\necho username=x-access-token\necho password=\$(cat /root/phone/.blaze/gh_token)\n")
            helper.setExecutable(true, false)
        } else { tokFile.delete() }
        val setup = StringBuilder("command -v git >/dev/null || apk add -q git >/dev/null 2>&1 || { echo 'Could not install git (no internet?)'; exit 1; }\n")
        if (token != null) {
            setup.append("git config --global credential.helper '/root/phone/.blaze/git-cred.sh'\n")
            setup.append("git config --global --get user.email >/dev/null || { ")
            val id = identity(token)
            if (id != null) setup.append("git config --global user.name ${q(id.first)}; git config --global user.email ${q(id.second)}; ")
            else setup.append("git config --global user.name 'Blaze'; git config --global user.email 'blaze@localhost'; ")
            setup.append("}\n")
        } else {
            setup.append("git config --global --unset credential.helper 2>/dev/null\ngit config --global --get user.email >/dev/null || { git config --global user.name 'Blaze'; git config --global user.email 'blaze@localhost'; }\n")
        }
        setup.append("git config --global init.defaultBranch main; git config --global pull.rebase true; git config --global core.pager cat\n")

        val action = a.optString("action", "status")
        val repo = a.optString("repo").trim()
        val name = repo.removeSuffix(".git").substringAfterLast('/').ifBlank { "repo" }
        val path = linuxPath(a.optString("path").ifBlank { if (action == "clone" || action == "init") name else (if (repo.isNotBlank()) name else "") })
        val g = "git -C ${q(path)}"
        val branch = a.optString("branch").trim()
        val cmd = when (action) {
            "clone" -> {
                if (repo.isBlank()) return@withContext "Give repo as owner/name or a URL."
                val url = if (repo.contains("://") || repo.startsWith("git@")) repo else "https://github.com/${repo.removeSuffix(".git")}.git"
                val depth = a.optInt("depth", 0)
                "[ -d ${q("$path/.git")} ] && { echo 'Already cloned, pulling instead.'; $g pull --autostash; } || git clone ${if (depth > 0) "--depth $depth " else ""}${if (branch.isNotBlank()) "-b ${q(branch)} " else ""}${q(url)} ${q(path)} 2>&1 && $g log --oneline -3"
            }
            "init" -> "git init ${q(path)}" + if (repo.isNotBlank()) " && $g remote add origin ${q(if (repo.contains("://")) repo else "https://github.com/${repo.removeSuffix(".git")}.git")}" else ""
            "status" -> "$g status -sb && $g log --oneline -3"
            "pull" -> "$g pull --autostash"
            "commit" -> {
                val msg = a.optString("message").ifBlank { return@withContext "A commit needs a message." }
                val files = a.optJSONArray("files")?.let { arr -> (0 until arr.length()).joinToString(" ") { q(arr.optString(it)) } }.orEmpty()
                "$g add ${files.ifBlank { "-A" }} && $g commit -m ${q(msg)} && $g log --oneline -1"
            }
            "push" -> "$g push -u origin ${if (branch.isNotBlank()) q(branch) else "HEAD"} 2>&1"
            "log" -> "$g log --oneline --decorate -n ${a.optInt("count", 15).coerceIn(1, 100)}"
            "diff" -> "$g diff --stat HEAD; echo; $g diff HEAD | head -n 400"
            "branch" -> if (branch.isBlank()) "$g branch -a" else "$g checkout -b ${q(branch)}"
            "checkout" -> if (branch.isBlank()) return@withContext "Which branch?" else "$g checkout ${q(branch)}"
            "raw" -> "$g ${a.optString("args")}"
            else -> return@withContext "Unknown git action $action."
        }
        val needsAuth = action in setOf("clone", "pull", "push") || (action == "raw" && Regex("""\b(push|pull|fetch|clone|ls-remote)\b""").containsMatchIn(a.optString("args")))
        var out = Terminal.run(setup.toString() + cmd, if (action in setOf("clone", "push", "pull", "raw")) 300 else 90, true, distro = "alpine")
        if (token != null) out = out.replace(token, "***")
        if (needsAuth && Regex("(?i)(authentication failed|could not read username|403|permission to .* denied)").containsMatchIn(out))
            out += if (token == null) "\n[hint: GitHub isn't connected in Blaze. Ask the user to connect GitHub in Settings → Connections, then retry.]"
                   else "\n[hint: GitHub refused this account. The connection may lack 'repo' access or the repo belongs to someone else; ask the user to reconnect GitHub.]"
        out.replace("/root/phone/", "~/")
    }

    fun tool(): JSONObject {
        fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", NAME)
            .put("description", "Git in Blaze's Linux, signed in as the user's connected GitHub account (private repos work; nothing to set up). " +
                "Repos live in ~/code/<name>; use file_read/file_edit/file_search on them and run_shell linux=true with cwd there to build or test. " +
                "Actions: clone, status, pull, commit (adds all files unless files given), push, log, diff, branch (list, or create+switch with branch), checkout, init, raw (any git args).")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("clone", "status", "pull", "commit", "push", "log", "diff", "branch", "checkout", "init", "raw"))))
                .put("repo", p("string", "owner/name or URL (clone, init); otherwise the repo name under ~/code"))
                .put("path", p("string", "Repo folder, default ~/code/<name>"))
                .put("message", p("string", "Commit message"))
                .put("files", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Files to commit; default all"))
                .put("branch", p("string", "Branch name"))
                .put("depth", p("integer", "Shallow clone depth; 0 = full"))
                .put("count", p("integer", "log entries"))
                .put("args", p("string", "Git arguments for action=raw, e.g. 'stash pop' or 'remote -v'")))
                .put("required", JSONArray(listOf("action")))))
    }
}
