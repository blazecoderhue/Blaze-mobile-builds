package com.blaze.assistant.connect

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.blaze.assistant.agent.Agent
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub tools behind the android-app-builder skill: make the repo, watch the Actions build
 * (with the compiler errors when it fails) and install the APK the build released. The phone
 * can't run Android's build tools, so GitHub Actions builds every push (see the skill's
 * template workflow), and Blaze reads the result back here.
 */
object GitHubApps {
    private const val API = "https://api.github.com"
    private fun h(t: String) = mapOf("Authorization" to "Bearer $t", "Accept" to "application/vnd.github+json", "User-Agent" to "Blaze-Android")

    private fun getJson(url: String, t: String): Pair<Int, String> = Http.req("GET", url, null, h(t))

    /** The token's scopes from GitHub's X-OAuth-Scopes header ("" when unknown). */
    fun scopes(t: String): Set<String> = try {
        val c = URL("$API/user").openConnection() as HttpURLConnection
        h(t).forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.responseCode
        val s = c.getHeaderField("X-OAuth-Scopes").orEmpty()
        c.disconnect()
        s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    } catch (_: Exception) { emptySet() }

    suspend fun createRepo(a: JSONObject, t: String): String {
        val name = a.optString("name").trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')
        if (name.isBlank()) return "Give the repo a name."
        val public = a.optBoolean("public", false)
        if (!Agent.confirm("Create a ${if (public) "public" else "private"} GitHub repo \u201c$name\u201d?", a.optString("description"), "Create", always = true)) return "The user declined."
        val (c, b) = Http.req("POST", "$API/user/repos", JSONObject().put("name", name).put("description", a.optString("description"))
            .put("private", !public).put("auto_init", false).toString(), h(t))
        if (c == 422 && b.contains("already exists")) return "A repo called $name already exists on this account. Pick another name, or clone that one if it's the same project."
        if (c !in 200..299) return "Couldn't create the repo ($c): ${b.take(200)}"
        val r = JSONObject(b)
        val sc = scopes(t)
        val wf = if (sc.isEmpty() || "workflow" in sc) "" else
            "\nNote: this GitHub connection can't add GitHub Actions files (no \"workflow\" permission). Ask the user to disconnect and reconnect GitHub in Settings › Connections, then push."
        return "Created ${r.optString("full_name")} (${if (public) "public" else "private"}): ${r.optString("html_url")}\nClone it with git (action clone, repo ${r.optString("full_name")}).$wf"
    }

    suspend fun ci(a: JSONObject, t: String): String {
        val repo = a.optString("repo").trim().removeSuffix(".git").substringAfter("github.com/")
        if (!repo.contains('/')) return "Give repo as owner/name."
        val branch = a.optString("branch").trim()
        val wait = a.optInt("wait_seconds", 120).coerceIn(0, 150)
        val until = System.currentTimeMillis() + wait * 1000L
        var run: JSONObject? = null
        while (true) {
            val (c, b) = getJson("$API/repos/$repo/actions/runs?per_page=1" + if (branch.isNotBlank()) "&branch=" + Http.enc(branch) else "", t)
            if (c == 404) return "No repo $repo, or no access to it."
            if (c !in 200..299) return "Couldn't read Actions ($c): ${b.take(160)}"
            run = JSONObject(b).optJSONArray("workflow_runs")?.optJSONObject(0)
            if (run == null && System.currentTimeMillis() > until) return "No Actions run yet in $repo. Check .github/workflows/build.yml was pushed to the default branch."
            if (run != null && run.optString("status") == "completed") break
            if (System.currentTimeMillis() > until) break
            delay(15_000)
        }
        val r = run ?: return "No Actions run yet."
        val head = "Run #${r.optInt("run_number")} \u00b7 ${r.optString("display_title").take(70)} \u00b7 commit ${r.optString("head_sha").take(7)}"
        if (r.optString("status") != "completed") return "$head is still ${r.optString("status").replace('_', ' ')}. Call github_ci again to keep waiting. ${r.optString("html_url")}"
        val concl = r.optString("conclusion")
        if (concl == "success") return "$head \u2713 succeeded. If the workflow publishes a release, install it with github_install_apk. ${r.optString("html_url")}"
        if (concl == "cancelled") return "$head was cancelled (usually a newer push replaced it). Call github_ci again for the newest run."
        // Failed: find the failing job and step, then pull the errors out of its log.
        val (jc, jb) = getJson("$API/repos/$repo/actions/runs/${r.optLong("id")}/jobs", t)
        val jobs = if (jc in 200..299) JSONObject(jb).optJSONArray("jobs") ?: JSONArray() else JSONArray()
        val job = (0 until jobs.length()).map { jobs.getJSONObject(it) }.firstOrNull { it.optString("conclusion") == "failure" }
        val steps = job?.optJSONArray("steps")
        val failedStep = (0 until (steps?.length() ?: 0)).map { steps!!.getJSONObject(it) }.firstOrNull { it.optString("conclusion") == "failure" }?.optString("name").orEmpty()
        val errors = job?.let { logErrors(repo, it.optLong("id"), t) }.orEmpty()
        return "$head \u2715 failed" + (if (failedStep.isNotBlank()) " at \u201c$failedStep\u201d" else "") + ".\n" +
            (if (errors.isNotBlank()) "Errors:\n$errors" else "No compiler errors found in the log; open ${r.optString("html_url")}") +
            "\nFix the files these errors point at, commit, push, then call github_ci again."
    }

    /** The job log has a redirect to a signed URL; pull out compiler and Gradle errors. */
    private fun logErrors(repo: String, jobId: Long, t: String): String = try {
        val c = URL("$API/repos/$repo/actions/jobs/$jobId/logs").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        h(t).forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        val code = c.responseCode
        val loc = c.getHeaderField("Location"); c.disconnect()
        val text = if (code in 300..399 && loc != null) URL(loc).readText() else ""
        val lines = text.lines().map { it.replace(Regex("^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z\\s?"), "") }
        val hit = Regex("""^e: |error:|What went wrong|Unresolved reference|AAPT|Manifest merger|Execution failed|Could not resolve|\berror\b.*\.kt""", RegexOption.IGNORE_CASE)
        val picked = LinkedHashSet<String>()
        lines.forEachIndexed { i, l -> if (hit.containsMatchIn(l)) for (j in i..minOf(i + 2, lines.lastIndex)) picked += lines[j].trimEnd() }
        val out = picked.filter { it.isNotBlank() && !it.startsWith("##[group]") && !it.contains("BLAZE_ERRORS") }.take(60).joinToString("\n")
        (if (out.isNotBlank()) out else lines.filter { it.isNotBlank() }.takeLast(30).joinToString("\n")).take(5000)
    } catch (e: Exception) { "" }

    suspend fun installApk(ctx: Context, a: JSONObject, t: String): String {
        val repo = a.optString("repo").trim().removeSuffix(".git").substringAfter("github.com/")
        if (!repo.contains('/')) return "Give repo as owner/name."
        val (c, b) = getJson("$API/repos/$repo/releases/latest", t)
        if (c == 404) return "$repo has no release yet. Wait for the build (github_ci) to succeed first."
        if (c !in 200..299) return "Couldn't read releases ($c): ${b.take(160)}"
        val rel = JSONObject(b)
        val assets = rel.optJSONArray("assets") ?: JSONArray()
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
            ?: return "The latest release (${rel.optString("tag_name")}) has no .apk file."
        // v0.28.2: installing an app always asks first (Android's installer asks again).
        if (!Agent.confirm("Install ${repo.substringAfter('/')} ${rel.optString("tag_name")}?", "Download ${asset.optString("name")} from github.com/$repo and open Android's installer.", "Install", always = true))
            return "The user declined. Nothing was downloaded or installed."
        val dir = File(com.blaze.assistant.terminal.Terminal.home(), "apps").apply { mkdirs() }
        val out = File(dir, repo.substringAfter('/') + ".apk")
        // The asset API redirects to a signed download URL that must not get the token.
        val conn = URL(asset.optString("url")).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        h(t).forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.connectTimeout = 15_000; conn.readTimeout = 60_000
        val code = conn.responseCode
        val input = if (code in 300..399) { val loc = conn.getHeaderField("Location"); conn.disconnect(); URL(loc).openStream() }
            else if (code in 200..299) conn.inputStream else return "Download failed ($code)."
        input.use { i -> out.outputStream().use { i.copyTo(it) } }
        if (out.length() < 10_000) return "The download looks broken (${out.length()} bytes)."
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Downloaded ${out.name}. Android needs permission for Blaze to install apps: I opened that setting. Once the user allows it, call github_install_apk again."
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Downloaded ${rel.optString("tag_name")} (${out.length() / 1024} KB) and opened Android's installer. The user taps Install. If it says the app conflicts with an installed one, uninstall the old copy first (it was signed with a different key)."
    }
}
