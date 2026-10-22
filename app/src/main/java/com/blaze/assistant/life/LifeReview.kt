package com.blaze.assistant.life

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.33.0: Blaze and Halo go over a transcribed life log. Blaze (structured) drafts scenes, who each voice is, and
 * the memories worth keeping; Halo (warm) reviews the draft against the transcript, fixes names and relationships,
 * adds how people felt, and returns the final version. Pure prompts / parsing / formatting (LifeLog2Test).
 */
object LifeReview {
    data class Line(val atMs: Long, val speaker: String, val text: String, val room: String)
    data class Scene(val from: String, val to: String, val setting: String, val who: List<String>, val summary: String)
    data class Memory(val kind: String, val subject: String, val text: String)
    data class Result(val scenes: List<Scene>, val speakers: Map<String, String>, val memories: List<Memory>, val todos: List<String>,
                      val title: String, val halo: String)

    private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    /** "[14:02] S1 (quiet): text". A scene-break marker after a gap of 5+ minutes. */
    fun transcript(lines: List<Line>, known: Map<String, String> = emptyMap()): String = buildString {
        var last = -1L
        for (l in lines) {
            if (last >= 0 && l.atMs - last > 5 * 60_000) append("--- (${(l.atMs - last) / 60_000} min later) ---\n")
            val who = known[l.speaker]?.let { "${l.speaker}=$it" } ?: l.speaker
            append("[").append(clock(l.atMs)).append("] ").append(who).append(" (").append(l.room).append("): ").append(l.text.trim()).append('\n')
            last = l.atMs
        }
    }.trimEnd()

    const val BLAZE_PROMPT = "You are Blaze, the structured half of a two-assistant team, reviewing a life log: everything the user's phone " +
        "heard, transcribed on the phone (words can be wrong) and split by voice (S1, S2, ... are different voices; S1=Name means that voice " +
        "was recognised from earlier days). The phone's owner is usually the voice closest to the mic: the most frequent and loudest. " +
        "Room noise is in brackets. Work out: the scenes (where they were, e.g. home, class, a taxi, a shop, church, a call on speaker; " +
        "use what is said, the noise and the time), who each voice is (\"You\" for the owner, a name when someone is addressed or named, " +
        "else a role like \"Lecturer\" or \"Shopkeeper\"; \"Unknown\" if unsure), and memories worth keeping about the user's life: people " +
        "and how they relate to the user, plans, promises the user made, decisions, preferences, facts. Skip small talk. Never invent. " +
        "Answer ONLY JSON: {\"title\":\"3-6 words\",\"speakers\":{\"S1\":\"You\"},\"scenes\":[{\"from\":\"HH:MM\",\"to\":\"HH:MM\",\"setting\":\"...\"," +
        "\"who\":[\"You\",\"...\"],\"summary\":\"one sentence\"}],\"memories\":[{\"kind\":\"person|project|decision|preference|fact|profile\"," +
        "\"subject\":\"short\",\"text\":\"one sentence\"}],\"todos\":[\"things the user said they'd do\"]}"

    const val HALO_PROMPT = "You are Halo, the warm half of a two-assistant team. Blaze drafted a review of the user's life log. Check it " +
        "against the transcript: fix wrong names or speakers, merge or split scenes if Blaze got the setting wrong, drop memories the " +
        "transcript doesn't support, and add what Blaze missed, especially relationships and how people felt (kept factual). Keep what is " +
        "right. Answer ONLY the same JSON shape, plus \"halo\":\"one short line to the user about what you changed or agreed with\"."

    fun blazeRequest(transcript: String, meta: String): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", BLAZE_PROMPT))
        .put(JSONObject().put("role", "user").put("content", "$meta\n\nTranscript:\n$transcript"))

    fun haloRequest(transcript: String, meta: String, blazeJson: String): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", HALO_PROMPT))
        .put(JSONObject().put("role", "user").put("content", "$meta\n\nTranscript:\n$transcript\n\nBlaze's draft:\n$blazeJson"))

    fun parse(raw: String?): Result? {
        val s = raw?.substringAfterLast("</think>") ?: return null
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val o = try { JSONObject(s.substring(a, b + 1)) } catch (_: Exception) { return null }
        fun strs(arr: JSONArray?) = arr?.let { x -> (0 until x.length()).map { x.optString(it).trim() }.filter { it.isNotBlank() } } ?: emptyList()
        val sp = o.optJSONObject("speakers")?.let { so -> so.keys().asSequence().associateWith { so.optString(it).trim() }.filterValues { it.isNotBlank() } } ?: emptyMap()
        val scenes = o.optJSONArray("scenes")?.let { arr -> (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let {
            Scene(it.optString("from"), it.optString("to"), it.optString("setting").trim(), strs(it.optJSONArray("who")), it.optString("summary").trim()) } } }
            ?.filter { it.summary.isNotBlank() || it.setting.isNotBlank() } ?: emptyList()
        val mems = o.optJSONArray("memories")?.let { arr -> (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let {
            Memory(it.optString("kind").trim().lowercase(), it.optString("subject").trim().take(60), it.optString("text").trim()) } } }
            ?.filter { it.text.length >= 8 }?.take(25) ?: emptyList()
        val r = Result(scenes, sp, mems, strs(o.optJSONArray("todos")).take(10), o.optString("title").trim().ifBlank { "Life log" }.take(60), o.optString("halo").trim())
        return if (r.scenes.isEmpty() && r.memories.isEmpty() && r.speakers.isEmpty()) null else r
    }

    /** A name worth remembering a voice by ("You", "Chanda", "Mum"), not a role guess. */
    fun nameable(n: String): Boolean {
        val l = n.trim().lowercase()
        if (l.isEmpty() || l.length > 30) return false
        if (l in setOf("unknown", "someone", "speaker", "other", "others", "voice", "person", "man", "woman", "child", "tv", "radio")) return false
        if (Regex("^s\\d+$").matches(l)) return false
        return true
    }

    fun format(r: Result, minutes: Long, voices: Int, saved: Int): String = buildString {
        append("**Life log: ${r.title}** (${minutes.coerceAtLeast(1)} min, $voices ${if (voices == 1) "voice" else "voices"})\n")
        if (r.speakers.isNotEmpty()) append("\n**Who was there:** ").append(r.speakers.entries.joinToString(", ") { "${it.value} (${it.key})" }).append('\n')
        if (r.scenes.isNotEmpty()) { append("\n**Scenes**\n"); r.scenes.take(8).forEach { s ->
            val t = listOf(s.from, s.to).filter { it.isNotBlank() }.joinToString("\u2013")
            append("- ").append(if (t.isNotBlank()) "$t " else "").append(s.setting.ifBlank { "somewhere" }).append(": ").append(s.summary).append('\n') } }
        if (r.todos.isNotEmpty()) { append("\n**You said you'd**\n"); r.todos.forEach { append("- $it\n") } }
        append("\nSaved $saved ${if (saved == 1) "memory" else "memories"}, each marked as inferred.")
        if (r.halo.isNotBlank()) append("\n\n_Halo: ${r.halo}_")
    }.trimEnd()
}
