package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.45.9 (user, 12:12): "save this file" after an HTML reply took 56 minutes and ended with "I don't have the capability
 * to save files... open a text editor on your computer". Blaze runs on a phone and can save: save_file writes the last
 * code block in the chat (or the content / filename given) to Downloads/Blaze through MediaStore (API 29+, no storage
 * permission), with the right extension; below API 29 it opens the share / save sheet. It returns the saved path and
 * Blaze confirms that path.
 *
 * Pure parts (block extraction, extension, name, the "save this" cue) are tested in SaveFileTest; the Android write is in
 * [SaveFileAndroid].
 */
object SaveFile {
    const val TOOL = "save_file"

    /** Prompt rule (full lane and chat lane). */
    const val RULE = "\nYOU RUN ON THE USER'S PHONE: never give desktop or computer advice (no \"open a text editor on your computer\", " +
        "no \"copy it into Notepad\"), and never say you can't save files. To save code or text you wrote (\"save this\", \"save the file\", " +
        "\"download it\"), call save_file (no arguments needed for the last code block) and tell the user the path it returns. "

    data class Block(val lang: String, val code: String)

    private val fence = Regex("```[ \\t]*([A-Za-z0-9_+#.-]*)[^\\n]*\\n([\\s\\S]*?)```")

    /** The last fenced code block in [text], or null. */
    fun lastBlock(text: String): Block? = fence.findAll(text).lastOrNull()?.let { m ->
        Block(m.groupValues[1].lowercase(), m.groupValues[2].trimEnd('\n', '\r', ' ') + "\n")
    }?.takeIf { it.code.isNotBlank() }

    /** A bare HTML document with no fence ("<!DOCTYPE html>…</html>"). */
    private val bareHtml = Regex("(?is)(<!doctype html.*?</html>|<html[\\s>].*?</html>)")

    /** The last code in a list of assistant texts (newest last): a fenced block, else a bare HTML document. */
    fun lastCode(texts: List<String>): Block? {
        for (t in texts.asReversed()) {
            lastBlock(t)?.let { return it }
            bareHtml.find(t)?.let { return Block("html", it.value.trim() + "\n") }
        }
        return null
    }

    private val extOf = mapOf(
        "html" to "html", "htm" to "html", "xhtml" to "html", "js" to "js", "javascript" to "js", "jsx" to "jsx", "ts" to "ts", "typescript" to "ts",
        "tsx" to "tsx", "py" to "py", "python" to "py", "python3" to "py", "css" to "css", "scss" to "scss", "json" to "json", "svg" to "svg",
        "md" to "md", "markdown" to "md", "txt" to "txt", "text" to "txt", "plaintext" to "txt", "kotlin" to "kt", "kt" to "kt", "java" to "java",
        "c" to "c", "cpp" to "cpp", "c++" to "cpp", "cs" to "cs", "csharp" to "cs", "go" to "go", "rust" to "rs", "rs" to "rs", "rb" to "rb",
        "ruby" to "rb", "php" to "php", "sh" to "sh", "bash" to "sh", "shell" to "sh", "zsh" to "sh", "sql" to "sql", "xml" to "xml",
        "yaml" to "yaml", "yml" to "yaml", "toml" to "toml", "csv" to "csv", "swift" to "swift", "dart" to "dart", "lua" to "lua", "r" to "r",
        "ini" to "ini", "dockerfile" to "dockerfile", "vue" to "vue", "svelte" to "svelte",
    )

    private val mimeOf = mapOf("html" to "text/html", "js" to "text/javascript", "css" to "text/css", "json" to "application/json",
        "svg" to "image/svg+xml", "md" to "text/markdown", "txt" to "text/plain", "py" to "text/x-python", "xml" to "text/xml", "csv" to "text/csv")

    /** The extension for [lang], or one guessed from [code] ("txt" when nothing fits). */
    fun extFor(lang: String, code: String): String {
        extOf[lang.lowercase().trim()]?.let { return it }
        val c = code.trimStart()
        return when {
            Regex("(?i)^(<!doctype html|<html)").containsMatchIn(c) -> "html"
            c.startsWith("<svg") || (c.startsWith("<?xml") && c.contains("<svg")) -> "svg"
            c.startsWith("{") || c.startsWith("[") -> if (runCatching { if (c.startsWith("{")) JSONObject(c) else JSONArray(c) }.isSuccess) "json" else "txt"
            Regex("(?m)^(def |import |from \\w+ import |print\\()").containsMatchIn(c) -> "py"
            Regex("(?m)^(const |let |function |import .* from )").containsMatchIn(c) -> "js"
            Regex("(?m)^[.#]?[a-zA-Z][\\w-]*\\s*\\{[^}]*:[^}]*\\}").containsMatchIn(c) -> "css"
            c.startsWith("#!/") && c.contains("sh") -> "sh"
            else -> "txt"
        }
    }

    fun mimeFor(ext: String): String = mimeOf[ext] ?: if (ext in setOf("kt", "java", "c", "cpp", "go", "rs", "rb", "php", "sh", "ts", "tsx", "jsx", "yaml", "toml")) "text/plain" else "text/plain"

    /** A safe file name: letters, digits, dashes; never empty. */
    fun slug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifBlank { "blaze-file" }

    /**
     * The file name: [given] when the user / model named it (its extension kept, or [ext] added), else the HTML <title>,
     * else "blaze-<lang>" (e.g. blaze-python.py).
     */
    fun nameFor(given: String, lang: String, code: String, ext: String = extFor(lang, code)): String {
        val g = given.trim().substringAfterLast('/').substringAfterLast('\\')
        if (g.isNotBlank()) {
            val dot = g.lastIndexOf('.')
            return if (dot > 0 && dot < g.length - 1) slug(g.substring(0, dot)) + "." + g.substring(dot + 1).lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { ext }
            else slug(g) + "." + ext
        }
        if (ext == "html" || ext == "svg") Regex("(?is)<title[^>]*>(.*?)</title>").find(code)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?.let { return slug(it) + "." + ext }
        val label = when { lang.isNotBlank() -> lang.lowercase(); ext == "txt" -> "text"; else -> ext }
        return "blaze-" + slug(label) + "." + ext
    }

    /** "save this", "save the file", "download it", "save it as page.html", "export the code". */
    private val strong = Regex("""(?i)\b(save|download|export)\b\s+(?:this|that|it|the (?:file|code|page|html|script)|(?:it|this) as\b|to (?:my )?(?:phone|downloads?))|\bsave (?:the |this |that )?(?:file|code|page|html|script)\b|\bsave as\b|^\s*(save|download)( it| this| that)?[.!]?\s*$""")

    /** True when [text] asks to save / download something Blaze wrote. */
    fun wantsSave(text: String): Boolean = strong.containsMatchIn(text.trim())

    /** The tool schema. */
    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", TOOL)
        .put("description", "Save a file to the phone's Downloads folder (Downloads/Blaze) and return its path. With no arguments it saves the last code block in this chat " +
            "(HTML, JS, Python, CSS, JSON, SVG, Markdown, text…) with the right extension, named from the HTML <title> or the language. Give content (and optionally filename) to save something else. " +
            "Use for \"save this\", \"save the file\", \"download it\". Always tell the user the returned path.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("filename", JSONObject().put("type", "string").put("description", "File name, e.g. solar-system.html (optional)"))
            .put("content", JSONObject().put("type", "string").put("description", "What to save; leave out to save the last code block in the chat"))
            .put("language", JSONObject().put("type", "string").put("description", "Language of content, e.g. html, python (optional)")))))

    data class Plan(val name: String, val mime: String, val content: String)

    /** What to save for [args], given the chat's assistant texts (newest last); null = nothing to save. */
    fun plan(args: JSONObject, assistantTexts: List<String>): Plan? {
        val given = args.optString("content")
        val lang0 = args.optString("language")
        val b = if (given.isNotBlank()) (lastBlock(given) ?: Block(lang0.lowercase(), given)) else lastCode(assistantTexts) ?: return null
        val lang = lang0.ifBlank { b.lang }
        val fname = args.optString("filename")
        val ext0 = extFor(lang, b.code)
        val name = nameFor(fname, lang, b.code, ext0)
        return Plan(name, mimeFor(name.substringAfterLast('.', ext0)), b.code)
    }

    const val NOTHING = "Nothing to save: there's no code block in this chat yet. Ask the user what to save, or pass content (and a filename) to save_file."
    fun savedLine(path: String) = "Saved to $path. Tell the user it's saved at that path (they can open it from Files → Downloads)."

    private val savedRe = Regex("""^Saved to (\S+?)\. Tell the user""")

    /** The path in a save_file result ([savedLine]), else null (nothing saved / an error). */
    fun pathIn(result: String): String? = savedRe.find(result.trim())?.groupValues?.get(1)

    /**
     * v0.45.9 replay fix (live 0.8.7: after save_file the model's last step said only its follow-up chips, 5 of 5): the final
     * answer of a turn that saved a file always names the saved path. A reply that already has it is left alone; otherwise
     * "Saved to <path>." goes first (the chips line stays last).
     */
    fun withPath(reply: String, path: String): String {
        if (path.isBlank() || EmptyReply.visible(reply).contains(path)) return reply
        val r = reply.trim()
        return if (r.isEmpty()) "Saved to $path." else if (EmptyReply.visible(r).isEmpty()) "Saved to $path.\n$r" else "Saved to $path. $r"
    }
}
