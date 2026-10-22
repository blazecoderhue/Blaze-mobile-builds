package com.blaze.assistant.voice

import android.content.Context
import com.blaze.assistant.voice.myvoice.MyVoice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.33.0 Voice Clone Notes: "read this article / PDF / chat to me in my voice". Long text is cleaned (links, markdown,
 * reference brackets, page furniture) and split at sentence ends into pieces the speech engines handle, then read in
 * the user's cloned voice (My voice, fully offline) when it's set up, otherwise the phone's voice at the chosen speed.
 * "stop" stops. Pure cleaning / chunking in [clean] / [chunks] (ReadAloudTest).
 */
object ReadAloud {
    const val TOOL = "read_aloud"

    fun clean(raw: String): String = raw
        .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""https?://\S+"""), "")
        .replace(Regex("""\[(\d+|citation needed|edit)]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""[*_`#>|]+"""), " ")
        .replace(Regex("""(?m)^\s*(page \d+( of \d+)?|\d+)\s*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""-\n(\p{Ll})"""), "$1")
        .replace(Regex("""[ \t]+"""), " ").replace(Regex("""\n{2,}"""), "\n\n").trim()

    /** Sentence-aligned pieces of at most [max] chars (a single over-long sentence is cut at a space). */
    fun chunks(text: String, max: Int = 600): List<String> {
        val sentences = Regex("""(?<=[.!?…])\s+|\n{2,}""").split(text).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>(); val sb = StringBuilder()
        fun flush() { if (sb.isNotBlank()) out += sb.toString().trim(); sb.setLength(0) }
        for (s0 in sentences) {
            var s = s0
            while (s.length > max) { val cut = s.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max; flush(); out += s.substring(0, cut).trim(); s = s.substring(cut).trim() }
            if (sb.length + s.length + 1 > max) flush()
            if (sb.isNotEmpty()) sb.append(' '); sb.append(s)
        }
        flush(); return out
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Read long text aloud: an article (url), a text/markdown file on the phone, or text you pass (e.g. a chat you read with notif_read, " +
            "a PDF's text you extracted). voice=mine uses the user's cloned voice (offline, set up in Settings › Voice › My voice). action stop stops.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("read", "stop"))))
            .put("text", JSONObject().put("type", "string")).put("url", JSONObject().put("type", "string")).put("path", JSONObject().put("type", "string"))
            .put("voice", JSONObject().put("type", "string").put("enum", JSONArray(listOf("mine", "blaze"))))
            .put("speed", JSONObject().put("type", "number").put("description", "0.5-2.5, phone voice only")))
            .put("required", JSONArray(listOf("action")))))

    suspend fun run(ctx: Context, a: JSONObject): String {
        if (a.optString("action") == "stop") { stop(); return "Stopped reading." }
        val raw = when {
            a.optString("text").isNotBlank() -> a.optString("text")
            a.optString("url").isNotBlank() -> try { com.blaze.assistant.agent.Web.fetch(a.optString("url"), 60_000) } catch (e: Exception) { return "Couldn't open that page (${e.message})." }
            a.optString("path").isNotBlank() -> {
                val f = File(a.optString("path").replace("/root/phone/", "/storage/emulated/0/"))
                if (!f.exists() || f.length() > 3_000_000) return "Can't read that file (missing or too big)."
                if (!f.name.matches(Regex("""(?i).+\.(txt|md|markdown|html?|csv|srt|vtt)$"""))) return "I can read text files directly; for a PDF, extract its text first and pass it as text."
                f.readText()
            }
            else -> return "What should I read?"
        }
        val parts = chunks(clean(raw))
        if (parts.isEmpty()) return "There's no readable text there."
        val mine = a.optString("voice") == "mine" || (a.optString("voice").isBlank() && MyVoice.chosen())
        val useMine = mine && MyVoice.packReady() && MyVoice.voiceReady()
        stop()
        a.optDouble("speed").takeIf { !it.isNaN() }?.let { com.blaze.assistant.agent.Settings.setStr("tts_rate", it.coerceIn(0.5, 2.5).toString()); Voice.applyPrefs() }
        parts.forEachIndexed { i, p ->
            if (useMine) MyVoice.speak(p, "read-$i", flush = i == 0) else Voice.speak(p, queue = i > 0)
        }
        val mins = (parts.sumOf { it.length } / 900.0).let { if (it < 1) "under a minute" else "about ${it.toInt()} min" }
        return "Reading ${parts.size} part${if (parts.size == 1) "" else "s"} ($mins) in " + (if (useMine) "your voice." else "Blaze's voice.") +
            if (mine && !useMine) " (Your voice isn't set up yet: Settings › Voice › My voice.)" else ""
    }

    fun stop() { try { MyVoice.stop() } catch (_: Throwable) { }; try { Voice.stopSpeaking() } catch (_: Throwable) { } }
}
