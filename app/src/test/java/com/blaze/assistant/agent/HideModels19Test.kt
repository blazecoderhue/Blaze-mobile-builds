package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.45.19 chat fix 2 (user rule for every client): never show which model or provider replied. No "What happened" sheet or
 * (i) icon for users, no "✓ Phone stat…" tag, no model / provider ids in footers, settings, errors or fallback notices.
 * Developers only, behind DevFlags (off by default, debug builds only). Strict 5/5.
 */
class HideModels19Test {

    private fun fiveTimes(body: (Int) -> Unit) = repeat(5) { body(it) }

    /** Provider / model names from the rule, matched as words ("z.ai" and "gpt-" literally). */
    private val names = Regex(
        "(?i)\\b(mistral|ministral|mixtral|pixtral|groq|cerebras|sambanova|cohere|gemini|gemma|openrouter|nvidia|nemotron|glm|kilo|kilocode|llama|" +
            "qwen|deepseek|claude|anthropic|openai|grok|kimi|moonshot|pollinations)\\b|z\\.ai|\\bgpt-?\\d|\\bgpt\\b")

    private val metas = listOf(
        "\u2601 Blaze Cloud \u00b7 run \u00b7 mistral \u00b7 ministral-8b-latest \u00b7 cloud run",
        "\u2601 Free (Google Gemini \u00b7 gemini-3.5-flash) \u00b7 first word 1.2 s \u00b7 2.3 s",
        "\u2601 Blaze Cloud / groq / llama-3.3-70b-versatile \u00b7 2.3 s \u00b7 Memory updated",
        "openrouter/free \u00b7 Skill saved", "cerebras \u00b7 qwen-3-32b \u00b7 fallback \u00b7 Memory updated",
        "via sambanova \u00b7 DeepSeek-V3.1", "z.ai \u00b7 glm-4.5-flash", "kilo-auto/free \u00b7 cohere command-r", "nvidia/nemotron-3-super",
    )

    @Test fun footersNeverNameAModel() = fiveTimes {
        for (m in metas) for (tech in listOf(false, true)) {
            val shown = ReplyChips.visible(m, tech)
            assertFalse("$m (tech=$tech) -> $shown", names.containsMatchIn(shown))
            assertFalse(shown, ModelHide.mentions(shown))
        }
        assertEquals("Memory updated", ReplyChips.visible(metas[2], false))
        assertEquals("Skill saved", ReplyChips.visible(metas[3], true))
        // The developer flag shows everything, and only it does.
        assertEquals(metas[0], ReplyChips.visible(metas[0], false, dev = true))
    }

    @Test fun devFlagIsOffByDefaultAndDebugOnly() = fiveTimes {
        assertFalse(DevFlags.modelInfo(debugBuild = false, pref = false))
        assertFalse(DevFlags.modelInfo(debugBuild = false, pref = true))   // a release build never shows it
        assertFalse(DevFlags.modelInfo(debugBuild = true, pref = false))   // off by default
        assertTrue(DevFlags.modelInfo(debugBuild = true, pref = true))
        assertFalse(DevFlags.modelInfo())                                   // nothing turned it on here
    }

    @Test fun errorsAndNoticesNeverNameAModel() = fiveTimes {
        val raws = listOf(
            "Groq said (500): {\"error\":{\"message\":\"overloaded\"}}",
            "mistral \u00b7 ministral-8b-latest timed out after 30000 ms at https://api.mistral.ai/v1/chat/completions",
            "{\"error\":{\"code\":429,\"message\":\"Gemini quota exceeded for gemini-3.5-flash\"}} (429)",
            "OpenRouter said (404): model openrouter/free not found",
            "Cerebras couldn't finish: java.io.IOException: unexpected end of stream on https://api.cerebras.ai/",
            "The key saved under Groq is actually a Gemini key.",
            "Couldn't reach Groq (llama-3.3-70b).",
        )
        for (r in raws) {
            val shown = PlainError.of(r)
            assertFalse("$r -> $shown", names.containsMatchIn(shown))
            assertTrue(shown, shown.isNotBlank())
        }
        assertEquals("Couldn't reach Blaze.", ModelHide.scrub("Couldn't reach Groq (llama-3.3-70b)."))
        assertEquals("Nothing to hide here.", ModelHide.scrub("Nothing to hide here."))
        assertEquals("Blaze Cloud \u00b7 run \u00b7 Blaze \u00b7 cloud run", ModelHide.scrub("Blaze Cloud \u00b7 run \u00b7 mistral \u00b7 ministral-8b-latest \u00b7 cloud run"))
    }

    @Test fun whatHappenedSheetIsDeveloperOnly() = fiveTimes {
        val src = srcFile("java/com/blaze/assistant/ui/ChatScreen.kt").readText()
        val addLine = src.lines().single { it.contains("add(\"What happened\"") }
        assertTrue(addLine, addLine.contains("DevFlags.modelInfo()"))
        val dialogLine = src.lines().single { it.contains("TraceDialog(m.trace)") }
        assertTrue(dialogLine, dialogLine.contains("DevFlags.modelInfo()"))
        // The action-row chip goes through the one filter that hides receipts, the ack label and models.
        assertFalse(src.contains("ReplyChips.visible(m.meta"))
        assertTrue(src.contains("ReplyChips.shown(m.meta)"))
    }

    @Test fun promptsSayBlazeIsBlaze() = fiveTimes {
        assertTrue(Duo.ASSISTANT_RULES.contains("Never say which AI model, company or provider runs you"))
        for (p in listOf(Duo.sideSystem(null, "t", ""), Duo.allSystem(Duo.Who.HALO, null), Duo.allSystem(Duo.Who.BLAZE, null)))
            assertTrue(p.contains(Duo.IDENTITY_RULES))
    }

    // ---------------- the UI-facing strings grep ----------------

    @Test fun uiStringsNameNoProvider() = fiveTimes {
        val hits = UiStrings.literals().filter { (_, s) -> names.containsMatchIn(s) }
        assertEquals("provider / model names in UI-facing strings:\n" + hits.joinToString("\n") { "${it.first}: ${it.second}" }, 0, hits.size)
        assertTrue("the scan found the UI sources", UiStrings.literals().size > 500)
    }

    companion object {
        fun srcFile(rel: String): File = listOf(File("src/main/$rel"), File("app/src/main/$rel")).first { it.exists() }
    }
}

/**
 * The string literals the user can see: every Kotlin string in the UI packages (ui/, notify/, Blaze Phone's screens and
 * status lines) and the agent files that write user-facing lines, plus every Android string resource. Comments are
 * skipped; the developer-only "What happened" sheet holds no names either, so nothing is excluded.
 */
object UiStrings {
    private val kotlinDirs = listOf("java/com/blaze/assistant/ui", "java/com/blaze/assistant/notify")
    private val kotlinFiles = listOf(
        "java/com/blaze/assistant/browser/phone/PhoneBrowserActivity.kt", "java/com/blaze/assistant/browser/phone/PhoneBrowserTools.kt",
        "java/com/blaze/assistant/agent/Ack.kt", "java/com/blaze/assistant/agent/PlainError.kt", "java/com/blaze/assistant/agent/Friendly.kt",
    )

    private fun main(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    fun literals(): List<Pair<String, String>> {
        val root = main()
        val out = ArrayList<Pair<String, String>>()
        val files = kotlinDirs.flatMap { d -> File(root, d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() } +
            kotlinFiles.map { File(root, it) }.filter { it.isFile }
        for (f in files) for (s in kotlinStrings(f.readText())) out += f.name to s
        File(root, "res").listFiles { d -> d.isDirectory && d.name.startsWith("values") }.orEmpty().forEach { d ->
            d.listFiles { x -> x.extension == "xml" }.orEmpty().forEach { x ->
                Regex(">([^<]+)<").findAll(x.readText()).forEach { out += "${d.name}/${x.name}" to it.groupValues[1] }
            }
        }
        return out
    }

    /** The contents of every string literal in Kotlin [src] (plain and raw), comments skipped. Pure. */
    fun kotlinStrings(src: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c == '/' && i + 1 < n && src[i + 1] == '/' -> { while (i < n && src[i] != '\n') i++ }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> { val e = src.indexOf("*/", i + 2); i = if (e < 0) n else e + 2 }
                c == '\'' -> { i++; while (i < n && src[i] != '\'') { if (src[i] == '\\') i++; i++ }; i++ }
                src.startsWith("\"\"\"", i) -> { val e = src.indexOf("\"\"\"", i + 3); val end = if (e < 0) n else e; out += src.substring(i + 3, end); i = end + 3 }
                c == '"' -> {
                    val sb = StringBuilder(); i++
                    while (i < n && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < n) { sb.append(src[i]).append(src[i + 1]); i += 2; continue }
                        // ${ ... } templates may hold quotes of their own: skip the expression.
                        if (src[i] == '$' && i + 1 < n && src[i + 1] == '{') {
                            var depth = 0
                            while (i < n) { if (src[i] == '{') depth++; if (src[i] == '}') { depth--; if (depth == 0) { i++; break } }; i++ }
                            sb.append("\u0000"); continue
                        }
                        sb.append(src[i]); i++
                    }
                    out += sb.toString(); i++
                }
                else -> i++
            }
        }
        return out
    }
}
