package com.blaze.assistant.life

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Life logs (v0.19.0): turns a stream of recognised utterances into journal chunks. A chunk closes after
 * [silenceMs] of silence, on a topic cue ("anyway", "moving on", "next thing"…), or when it reaches [maxChars] or
 * [maxMs]. Noise words the recogniser emits for clatter ("the", "huh") are dropped. Pure, unit-tested (LifeLogsTest).
 */
class LogChunker(
    val silenceMs: Long = 30_000,
    val maxChars: Int = 1_500,
    val maxMs: Long = 8 * 60_000,
) {
    data class Utterance(val start: Long, val end: Long, val text: String)
    data class Chunk(val start: Long, val end: Long, val text: String, val utterances: Int)

    private val cur = ArrayList<Utterance>()
    private var chars = 0

    val pending: Int get() = cur.size

    /** Adds one utterance; returns the chunks it closed (0, 1 or 2). */
    fun add(u0: Utterance): List<Chunk> {
        val t = clean(u0.text) ?: return emptyList()
        val u = u0.copy(text = t)
        val out = ArrayList<Chunk>()
        if (cur.isNotEmpty() && (u.start - cur.last().end >= silenceMs || topicCue(t))) close()?.let { out += it }
        cur += u; chars += t.length + 2
        if (chars >= maxChars || u.end - cur.first().start >= maxMs) close()?.let { out += it }
        return out
    }

    /** Closes the open chunk when it has been quiet for [silenceMs] by [now]. */
    fun tick(now: Long): Chunk? = if (cur.isNotEmpty() && now - cur.last().end >= silenceMs) close() else null

    /** Closes whatever is open (stop, pause). */
    fun flush(): Chunk? = close()

    private fun close(): Chunk? {
        if (cur.isEmpty()) return null
        val c = Chunk(cur.first().start, cur.last().end, cur.joinToString(" ") { sentence(it.text) }, cur.size)
        cur.clear(); chars = 0
        return c
    }

    companion object {
        private val noise = setOf("the", "a", "an", "uh", "um", "umm", "hmm", "hm", "huh", "oh", "ah", "and", "i", "but", "so", "mm", "eh", "yeah")
        private val cue = Regex("^(anyway|anyways|moving on|next thing|next topic|new topic|another thing|on another note|changing the subject|ok(?:ay)? so|alright so|right so)\\b",
            RegexOption.IGNORE_CASE)

        /** Normalised text, or null when it is only noise. */
        fun clean(raw: String): String? {
            val t = raw.trim().replace(Regex("\\s+"), " ")
            if (t.isEmpty()) return null
            val ws = t.lowercase().split(' ')
            if (ws.all { it in noise }) return null
            return t
        }

        fun topicCue(text: String) = cue.containsMatchIn(text.trim())

        private fun sentence(s: String): String {
            val t = s.trim()
            val cap = t.replaceFirstChar { it.uppercase() }
            return if (cap.last() in ".!?") cap else "$cap."
        }

        /** "stop" / "pause" when the user says so out loud ("stop logs", "blaze stop logging", "end brainstorm"). */
        fun command(text: String): String? {
            val t = text.lowercase().trim().replace(Regex("[^a-z ]"), "").replace(Regex("^(hey |ok |okay )?blaze "), "")
            if (Regex("^(stop|end|turn off|switch off|finish) (the )?(live |life )?(logs?|logging|brainstorm|brainstorming)( now)?$").matches(t)) return "stop"
            if (Regex("^(pause|hold) (the )?(live |life )?(logs?|logging|brainstorm|brainstorming)$").matches(t)) return "pause"
            return null
        }

        /** What goes in the journal: when, and that it was overheard (so the review marks what it learns as inferred). */
        fun entry(c: Chunk, brainstorm: Boolean, fmt: SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())): String =
            (if (brainstorm) "Brainstorm, the user thinking out loud" else "Heard nearby") +
                " ${fmt.format(Date(c.start))}\u2013${fmt.format(Date(c.end))} (transcribed by the phone's mic; may be other people, words may be wrong): " + c.text
    }
}
