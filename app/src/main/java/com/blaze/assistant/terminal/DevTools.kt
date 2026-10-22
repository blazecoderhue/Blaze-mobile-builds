package com.blaze.assistant.terminal

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Developer tools for the agent, built so a weak model can't trip over shell quoting:
 * structured file read/write/edit/list/search, run_code with auto-installed runtimes,
 * job logs and stdin, a real HTTP client, downloads, export to the phone's Downloads,
 * and a terminal health check. Paths: "~/x" or "x" = Blaze home (also /root/phone in Linux),
 * "/root/..." or "linux:/..." = inside Alpine, anything else absolute = the phone path.
 */
object DevTools {

    val names = setOf("file_read", "file_write", "file_edit", "file_list", "file_search", "file_delete",
        "run_code", "job_input", "http_request", "download_file", "export_file", "terminal_status")

    private lateinit var app: Context
    private fun home() = Terminal.home()

    fun resolve(path: String): File {
        val p = path.trim().ifEmpty { "~" }
        return when {
            p == "~" -> home()
            p.startsWith("~/") -> File(home(), p.removePrefix("~/"))
            p.startsWith("linux:") -> File(Linux.rootfs(), p.removePrefix("linux:").trimStart('/'))
            p.startsWith("/root/phone") -> File(home(), p.removePrefix("/root/phone").trimStart('/'))
            p.startsWith("/root") || p.startsWith("/usr/") || p.startsWith("/etc/") || p.startsWith("/opt/") -> File(Linux.rootfs(), p.trimStart('/'))
            p.startsWith("/") -> File(p)
            else -> File(Terminal.agentCwd(), p)
        }
    }

    private fun show(f: File): String = f.absolutePath
        .replace(home().absolutePath, "~")
        .replace(Linux.rootfs().absolutePath, "linux:")

    private fun binary(f: File): Boolean = try {
        f.inputStream().use { s -> val b = ByteArray(2048); val n = s.read(b); (0 until maxOf(n, 0)).any { b[it] == 0.toByte() } }
    } catch (_: Exception) { false }

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = withContext(Dispatchers.IO) {
        app = ctx.applicationContext
        Terminal.setup()
        try {
            when (name) {
                "file_read" -> read(a)
                "file_write" -> write(a)
                "file_edit" -> edit(a)
                "file_list" -> list(a)
                "file_search" -> search(a)
                "file_delete" -> delete(a)
                "run_code" -> runCode(a)
                "job_input" -> Terminal.jobInput(a.optInt("id"), a.optString("text"), a.optBoolean("enter", true))
                "http_request" -> http(a)
                "download_file" -> download(a)
                "export_file" -> export(a)
                "terminal_status" -> status()
                else -> "Unknown dev tool."
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Error: ${e.javaClass.simpleName}: ${e.message}" }
    }

    private fun read(a: JSONObject): String {
        val f = resolve(a.optString("path"))
        if (!f.exists()) return "No such file: ${show(f)}"
        if (f.isDirectory) return "${show(f)} is a folder; use file_list."
        if (f.length() > 20_000_000) return "${show(f)} is ${f.length() / 1_000_000} MB; read part of it with run_shell (head/tail/sed -n)."
        if (binary(f)) return "${show(f)} is a binary file (${f.length()} bytes)."
        val lines = f.readLines()
        val start = a.optInt("start_line", 1).coerceAtLeast(1)
        val count = a.optInt("lines", 400).coerceIn(1, 2000)
        val slice = lines.drop(start - 1).take(count)
        val body = slice.mapIndexed { i, l -> "${start + i}\t${l.take(500)}" }.joinToString("\n")
        val more = if (start - 1 + slice.size < lines.size) "\n[lines ${start}-${start + slice.size - 1} of ${lines.size}; pass start_line to read on]" else ""
        return "${show(f)} (${lines.size} lines)\n$body$more"
    }

    private fun write(a: JSONObject): String {
        val f = resolve(a.optString("path"))
        f.parentFile?.mkdirs()
        val text = a.optString("content")
        if (a.optBoolean("append")) f.appendText(text) else f.writeText(text)
        if (a.optBoolean("executable") || text.startsWith("#!")) f.setExecutable(true, false)
        return "${if (a.optBoolean("append")) "Appended to" else "Wrote"} ${show(f)} (${f.length()} bytes, ${f.readLines().size} lines)."
    }

    private fun edit(a: JSONObject): String {
        val f = resolve(a.optString("path"))
        if (!f.isFile) return "No such file: ${show(f)}"
        val find = a.optString("find"); val repl = a.optString("replace")
        if (find.isEmpty()) return "find is empty."
        val s = f.readText()
        val n = Regex(Regex.escape(find)).findAll(s).count()
        if (n == 0) {
            // Help the model: show the closest line so it can retry with exact text.
            val first = find.lines().first().trim()
            val near = s.lines().withIndex().firstOrNull { it.value.contains(first.take(20)) }
            return "Text not found in ${show(f)}." + (near?.let { " Closest line ${it.index + 1}: ${it.value.trim().take(200)}" } ?: " Read the file first with file_read.")
        }
        if (n > 1 && !a.optBoolean("all")) return "Found $n matches in ${show(f)}; add more surrounding text to make it unique, or set all=true."
        f.writeText(if (a.optBoolean("all")) s.replace(find, repl) else s.replaceFirst(find, repl))
        return "Edited ${show(f)}: replaced ${if (a.optBoolean("all")) n else 1} match${if (n > 1 && a.optBoolean("all")) "es" else ""}."
    }

    private fun list(a: JSONObject): String {
        val f = resolve(a.optString("path", "~"))
        if (!f.exists()) return "No such folder: ${show(f)}"
        val depth = a.optInt("depth", 1).coerceIn(1, 4)
        val out = StringBuilder("${show(f)}\n")
        var n = 0
        f.walkTopDown().maxDepth(depth).onEnter { it.name !in setOf(".git", "node_modules", "__pycache__", ".gradle") || it == f }.forEach { e ->
            if (e == f || n >= 300) return@forEach
            n++
            val rel = e.relativeTo(f).path
            out.append(if (e.isDirectory) "$rel/\n" else "$rel  ${e.length()} B\n")
        }
        if (n >= 300) out.append("[first 300 entries]")
        if (n == 0) out.append("(empty)")
        return out.toString().trimEnd()
    }

    private fun search(a: JSONObject): String {
        val root = resolve(a.optString("path", "~"))
        val pat = a.optString("pattern")
        if (pat.isEmpty()) return "pattern is empty."
        val re = try { Regex(pat, RegexOption.IGNORE_CASE) } catch (_: Exception) { Regex(Regex.escape(pat), RegexOption.IGNORE_CASE) }
        val glob = a.optString("glob").trim()
        val globRe = if (glob.isBlank()) null else Regex(glob.replace(".", "\\.").replace("*", ".*").replace("?", "."), RegexOption.IGNORE_CASE)
        val hits = StringBuilder(); var count = 0; var files = 0
        val seq = if (root.isFile) sequenceOf(root) else root.walkTopDown().onEnter { it.name !in setOf(".git", "node_modules", "__pycache__", ".gradle") || it == root }.filter { it.isFile }
        for (f in seq) {
            if (files > 3000 || count >= 100) break
            if (globRe != null && !globRe.matches(f.name)) continue
            files++
            if (re.containsMatchIn(f.name) && a.optBoolean("names_only")) { hits.append(show(f)).append('\n'); count++; continue }
            if (a.optBoolean("names_only") || f.length() > 5_000_000 || binary(f)) continue
            try { f.useLines { ls -> ls.forEachIndexed { i, l -> if (count < 100 && re.containsMatchIn(l)) { hits.append("${show(f)}:${i + 1}: ${l.trim().take(200)}\n"); count++ } } } } catch (_: Exception) { }
        }
        return if (count == 0) "No matches for \u201c$pat\u201d under ${show(root)} ($files files checked)." else hits.toString().trimEnd() + if (count >= 100) "\n[first 100 matches]" else ""
    }

    private fun delete(a: JSONObject): String {
        val f = resolve(a.optString("path"))
        if (!f.exists()) return "Nothing at ${show(f)}."
        if (f == home() || f == Linux.rootfs() || f.absolutePath.count { it == '/' } < 3) return "Refusing to delete ${show(f)}."
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (ok) "Deleted ${show(f)}." else "Couldn't delete ${show(f)}."
    }

    // ---------- run_code ----------

    private suspend fun ensureLinux(): String? {
        if (Linux.installed()) return null
        if (!Linux.supported()) return "Linux isn't available on this phone's CPU; only the phone shell (language=sh) works here."
        val r = Linux.install()
        return if (Linux.installed()) null else "Couldn't install Linux: $r"
    }

    private suspend fun runCode(a: JSONObject): String {
        val lang = a.optString("language", "python").lowercase().let { if (it in setOf("py", "python3")) "python" else if (it in setOf("js", "javascript", "nodejs")) "node" else if (it in setOf("bash", "shell")) "sh" else it }
        val code = a.optString("code")
        val timeout = a.optInt("timeout_seconds", 120).coerceIn(5, 600)
        val dir = File(home(), ".blaze/run").apply { mkdirs() }
        val ext = mapOf("python" to "py", "node" to "js", "sh" to "sh").getValue(lang.takeIf { it in setOf("python", "node", "sh") } ?: return "Supported languages: python, node, sh.")
        val f = File(dir, "snippet.$ext"); f.writeText(code)
        if (lang == "sh" && !a.optBoolean("linux")) return Terminal.run("sh ~/.blaze/run/snippet.sh", timeout, false)
        ensureLinux()?.let { return it }
        val pkgs = a.optJSONArray("packages")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        val setup = StringBuilder()
        when (lang) {
            "python" -> {
                setup.append("command -v python3 >/dev/null || apk add -q python3 py3-pip >/dev/null 2>&1; ")
                if (pkgs.isNotEmpty()) setup.append("python3 -m pip install -q --break-system-packages ${pkgs.joinToString(" ") { "'${it.replace("'", "")}'" }} 2>&1 | tail -3; ")
            }
            "node" -> {
                setup.append("command -v node >/dev/null || apk add -q nodejs npm >/dev/null 2>&1; ")
                if (pkgs.isNotEmpty()) setup.append("cd /root/phone/.blaze/run && npm install --silent ${pkgs.joinToString(" ") { "'${it.replace("'", "")}'" }} 2>&1 | tail -3; ")
            }
        }
        val runner = when (lang) { "python" -> "python3 -u snippet.py"; "node" -> "node snippet.js"; else -> "sh snippet.sh" }
        return Terminal.run("${setup}cd /root/phone/.blaze/run && $runner", timeout, true, distro = "alpine")
    }

    // ---------- network ----------

    private fun http(a: JSONObject): String {
        val url = a.optString("url")
        if (!url.startsWith("http")) return "url must start with http:// or https://"
        // v0.28.2: never send credentials in the clear to the internet (a LAN / localhost server is fine), and a
        // credentialed request to plain http doesn't follow redirects off the LAN.
        val hdrs = a.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
        val secret = com.blaze.assistant.connect.UrlSafety.carriesSecret(url, hdrs)
        if (secret) com.blaze.assistant.connect.UrlSafety.problem(url)?.let { return "Refused. $it" }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = a.optString("method", "GET").uppercase()
            connectTimeout = 15_000; readTimeout = a.optInt("timeout_seconds", 30).coerceIn(5, 120) * 1000
            instanceFollowRedirects = !(secret && com.blaze.assistant.connect.UrlSafety.isPlainHttp(url))
            setRequestProperty("User-Agent", "Blaze/1.0 (Android)")
            a.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> setRequestProperty(k, h.optString(k)) } }
        }
        val body = a.opt("body")?.let { if (it is JSONObject || it is JSONArray) it.toString() else it.toString() }.orEmpty()
        if (body.isNotEmpty() && c.requestMethod != "GET") {
            if (c.getRequestProperty("Content-Type") == null) c.setRequestProperty("Content-Type", if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) "application/json" else "application/x-www-form-urlencoded")
            c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val type = c.contentType.orEmpty()
        val shown = if (type.contains("json")) try { if (text.trimStart().startsWith("[")) JSONArray(text).toString(1) else JSONObject(text).toString(1) } catch (_: Exception) { text } else text
        a.optString("save_to").takeIf { it.isNotBlank() }?.let { p -> val f = resolve(p); f.parentFile?.mkdirs(); f.writeText(text); return "HTTP $code $type, ${text.length} chars saved to ${show(f)}." }
        return "HTTP $code $type\n" + if (shown.length > 12_000) shown.take(12_000) + "\n[cut; pass save_to to keep it all]" else shown
    }

    private fun download(a: JSONObject): String {
        val url = a.optString("url")
        val name = a.optString("path").ifBlank { "~/downloads/" + (url.substringAfterLast('/').substringBefore('?').ifBlank { "file" }) }
        val f = resolve(name); f.parentFile?.mkdirs()
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true; setRequestProperty("User-Agent", "Blaze/1.0 (Android)") }
        if (c.responseCode >= 400) return "HTTP ${c.responseCode} for $url"
        val limit = 500L * 1_000_000
        var total = 0L
        c.inputStream.use { inp -> f.outputStream().use { out -> val buf = ByteArray(64 * 1024); while (true) { val n = inp.read(buf); if (n < 0) break; total += n; if (total > limit) { out.close(); f.delete(); return "Stopped: file is over 500 MB." }; out.write(buf, 0, n) } } }
        return "Downloaded ${"%.1f".format(total / 1e6)} MB to ${show(f)}."
    }

    /** Copy a file from Blaze's private home to the phone's Downloads/Blaze so the user (and other apps) can open it. */
    private fun export(a: JSONObject): String {
        val f = resolve(a.optString("path"))
        if (!f.isFile) return "No such file: ${show(f)}"
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Blaze")
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return "Couldn't create the file in Downloads."
            app.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            if (a.optBoolean("share")) try {
                app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { }
            return "Saved to Downloads/Blaze/${f.name}${if (a.optBoolean("share")) " and opened the share sheet" else ""}."
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Blaze").apply { mkdirs() }
        return try { f.copyTo(File(dir, f.name), true); "Saved to Downloads/Blaze/${f.name}." } catch (e: Exception) { "Couldn't write to Downloads on this Android version: ${e.message}" }
    }

    private suspend fun status(): String {
        val sb = StringBuilder()
        sb.append("Phone shell: ").append(if (Terminal.busyboxOk) "sh + BusyBox" else "sh + Android toybox (BusyBox unavailable)").append(". Working folder: ").append(show(Terminal.agentCwd())).append('\n')
        sb.append("Alpine Linux: ").append(when { Linux.installed() -> "installed (${Linux.sizeMb()} MB)"; Linux.supported() -> "not installed (run_code or linux_install sets it up)"; else -> "not supported on this CPU" }).append('\n')
        if (Linux.installed()) sb.append(Terminal.run("for t in python3 pip3 node npm git curl gcc make java; do printf '%s: ' \$t; command -v \$t >/dev/null && echo yes || echo no; done; df -h /root 2>/dev/null | tail -1", 30, true, distro = "alpine").removePrefix("exit 0").trim()).append('\n')
        sb.append("Termux: ").append(if (Terminal.termuxInstalled(app)) "installed" else "not installed").append('\n')
        sb.append("Free space: ").append("%.1f".format(home().usableSpace / 1e9)).append(" GB\n")
        sb.append(Terminal.listJobs())
        return sb.toString().trim()
    }

    // ---------- tool definitions ----------

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)
    private const val PATHS = "Path: ~/x (Blaze home, = /root/phone in Linux), /root/x or linux:/x (inside Alpine), /sdcard/... (phone storage)."

    fun tools(): List<JSONObject> = listOf(
        fn("file_read", "Read a text file with line numbers. Prefer this over cat. $PATHS",
            JSONObject().put("path", p("string", "File path")).put("start_line", p("integer", "First line, default 1")).put("lines", p("integer", "How many lines, default 400")), listOf("path")),
        fn("file_write", "Create or overwrite a file with exact content (no shell quoting problems). Creates folders. Use for scripts, code, configs. $PATHS",
            JSONObject().put("path", p("string", "File path")).put("content", p("string", "Full file content")).put("append", p("boolean", "Append instead of overwrite"))
                .put("executable", p("boolean", "chmod +x")), listOf("path", "content")),
        fn("file_edit", "Replace exact text in a file (find must match once unless all=true). Read the file first. Safer than sed.",
            JSONObject().put("path", p("string", "File path")).put("find", p("string", "Exact text to replace")).put("replace", p("string", "New text")).put("all", p("boolean", "Replace every match")), listOf("path", "find", "replace")),
        fn("file_list", "List a folder (sizes, sub-folders up to depth 4). $PATHS",
            JSONObject().put("path", p("string", "Folder, default ~")).put("depth", p("integer", "1-4")), emptyList()),
        fn("file_search", "Search file contents (regex, case-insensitive) or file names under a folder, like grep -rn.",
            JSONObject().put("pattern", p("string", "Text or regex")).put("path", p("string", "Folder, default ~")).put("glob", p("string", "Only files like *.py"))
                .put("names_only", p("boolean", "Match file names instead of contents")), listOf("pattern")),
        fn("file_delete", "Delete a file or folder.", JSONObject().put("path", p("string", "Path")), listOf("path")),
        fn("run_code", "Run a Python, Node.js or sh program and get its output. Sets up Linux and the runtime automatically the first time, and pip/npm installs any packages you list. Use for calculations, data processing, scraping, file conversion, testing code.",
            JSONObject().put("language", JSONObject().put("type", "string").put("enum", JSONArray(listOf("python", "node", "sh"))))
                .put("code", p("string", "Full program")).put("packages", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "pip/npm packages to install first"))
                .put("timeout_seconds", p("integer", "Default 120, max 600")), listOf("language", "code")),
        fn("job_input", "Type into a running background job's input (answer a y/n prompt, send a command to a REPL or server console).",
            JSONObject().put("id", p("integer", "Job number")).put("text", p("string", "Text to send")).put("enter", p("boolean", "Press Enter after, default true")), listOf("id", "text")),
        fn("http_request", "Call any HTTP API: method, headers, JSON or form body. Returns status and body (JSON pretty-printed). Use for APIs; fetch_page is for reading web pages.",
            JSONObject().put("url", p("string", "URL")).put("method", p("string", "GET, POST, PUT, PATCH, DELETE")).put("headers", p("object", "Header map"))
                .put("body", p("string", "Request body (JSON text or form)")).put("save_to", p("string", "Save the response to this file instead")), listOf("url")),
        fn("download_file", "Download a file (up to 500 MB) into Blaze's home, default ~/downloads/.",
            JSONObject().put("url", p("string", "URL")).put("path", p("string", "Where to save")), listOf("url")),
        fn("export_file", "Copy a file from Blaze's home to the phone's Downloads/Blaze folder so the user can open it, optionally opening the share sheet.",
            JSONObject().put("path", p("string", "File in Blaze's home")).put("share", p("boolean", "Open the share sheet")), listOf("path")),
        fn("terminal_status", "Check the terminal: shell, Linux, which tools are installed (python, node, git...), free space, running jobs. Call before assuming a tool exists.", JSONObject(), emptyList()),
    )
}
