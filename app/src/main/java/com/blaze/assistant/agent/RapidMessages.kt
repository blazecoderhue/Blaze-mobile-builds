package com.blaze.assistant.agent

/**
 * v0.30.6 rapid messages. Luke: "Let's make blaze like hark where I can send messages after messages with no pause and
 * blaze highlights the one it's responding to and such (or replies to rapid succession questions that are related with
 * one message)".
 *
 * - The composer never blocks. A message sent while Blaze works shows at once and joins the running turn at its next
 *   step (between tool calls); one sent just after the turn ended starts the next turn. Typed messages a moment apart
 *   while Blaze is idle are batched into one turn ([burstWait]). Nothing is dropped or reordered, never two turns at once.
 * - The model sees every user message with a short id (`[m7] …`) and starts each reply with `[re:m7]` / `[re:m7,m8]`
 *   ([PROMPT_RULE]). The marker is never shown ([display]); it says which message(s) the reply answers ([split]), so a
 *   reply that isn't right under its message gets a quote chip above it ([chipMap]).
 *
 * Pure Kotlin, unit-tested in RapidMessagesTest.
 */
object RapidMessages {
    /** Wait after the last send while you're still typing (a keystroke within this long), so the burst becomes one turn. */
    const val BURST_MS = 1_500L
    /** Wait after a send when you aren't typing: just enough for a double-tap send or a pasted second line. */
    const val GRACE_MS = 600L
    /** Never wait longer than this after the last send, however long you keep typing. */
    const val BURST_MAX_MS = 6_000L
    /** Characters of the quoted message shown in a quote chip. */
    const val CHIP_CHARS = 60
    /** At most this many quote chips above one reply. */
    const val MAX_CHIPS = 3

    /** The system-prompt rule (static text, so it stays in the cached prefix). */
    const val PROMPT_RULE = "\nRAPID MESSAGES: every user message starts with its id, like [m7]. The user may send several in a row, or more while you work " +
        "(those say \"sent while you were working\"): read them all before acting. A later message that corrects or changes an earlier one wins. " +
        "Related messages get ONE combined reply; unrelated ones get separate replies in the same answer. Start each reply with a marker naming the " +
        "message(s) it answers, at the start of a line: [re:m7] or [re:m7,m8]. The marker is hidden from the user; never write the [m7] ids anywhere else."

    private const val MID_TURN = "sent while you were working"

    /** The short id the model sees for chat message [id]. */
    fun tag(id: Long): String = "m$id"

    /** A user message as the model sees it: `[m7] text` ([midTurn]: `[m7 · sent while you were working] text`). */
    fun label(id: Long, text: String, midTurn: Boolean = false): String =
        "[" + tag(id) + (if (midTurn) " \u00b7 $MID_TURN" else "") + "] " + text

    /** True when a user message gets an id: the main agent's chats, not background agents or scheduled routines. */
    fun shouldTag(lead: Boolean, text: String): Boolean = lead && !text.trimStart().startsWith("[Scheduled routine")

    /** An on-device answer (time, battery, "hide the orb") made while a turn ran: one user-side note, so the history never ends on an assistant line mid-task. */
    fun answeredNote(labelled: String, reply: String): String =
        if (reply.isBlank()) labelled
        else "$labelled\n[Blaze already answered this one on the phone: \"${reply.take(400)}\". Don't answer it again.]"

    private val idTagRe = Regex("""(?m)^\[m\d+(?: \u00b7 [^\]\n]*)?\] ?""")

    /** [text] without the ids [label] put in front of user messages (for the chat lane, which has no rule about them). */
    fun untag(text: String): String = idTagRe.replace(text, "")

    // [re:m7] / [re: m7, m8] / [RE:7] ; and a bare [m7] the model echoed at the start of a line.
    private val markerRe = Regex("""\[\s*re\s*:\s*(m?\d+(?:\s*,\s*m?\d+)*)\s*\]""", RegexOption.IGNORE_CASE)
    // v0.33.0 also a bare list at line start: [m97,m99] / [m97, 99] (seen leaking into replies).
    private val lineMarkerRe = Regex("""(?m)^[ \t]*(?:\[\s*re\s*:\s*(m?\d+(?:\s*,\s*m?\d+)*)\s*\]|\[\s*(m\d+(?:\s*,\s*m?\d+)*)\s*\])[ \t]*""", RegexOption.IGNORE_CASE)
    /** v0.33.0 A bare id list anywhere in a reply ([m97] / [m97,m99]): never shown. */
    private val bareRe = Regex("""[ \t]?\[\s*m\d+(?:\s*,\s*m?\d+)*\s*\]""", RegexOption.IGNORE_CASE)
    // While streaming: "[", "[r", "[re", "[re:m1", "[re:m1,", "[m12" at the very end is held back until it's whole.
    private val openRe = Regex("""\[(?:\s*r(?:e(?:\s*:[\sm\d,]*)?)?|\s*m[\d,\sm]*)?$""", RegexOption.IGNORE_CASE)

    private fun ids(list: String): List<Long> =
        list.split(',').mapNotNull { it.trim().removePrefix("m").removePrefix("M").toLongOrNull() }.distinct()

    /** One reply bubble: the message ids it answers ([refs], may be empty) and its words. */
    data class Part(val refs: List<Long>, val text: String)

    /** What a reply shows: every marker removed (and, while [final] is false, an unfinished one at the end held back). */
    fun display(text: String, final: Boolean = true): String {
        if (text.isEmpty() || !text.contains('[')) return text
        var t = lineMarkerRe.replace(text, "")
        t = markerRe.replace(t, "")
        t = bareRe.replace(t, "")
        if (!final) openRe.find(t)?.let { t = t.substring(0, it.range.first) }
        return tidy(t)
    }

    private fun tidy(t: String): String = t.replace(Regex("""[ \t]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()

    /**
     * Splits a final reply at its line-start markers into bubbles. Ids not in [known] (the chat's user messages) are dropped;
     * a marker with no words after it lends its ids to the next one. No marker → one part with no refs, text unchanged.
     */
    fun split(text: String, known: Set<Long>): List<Part> {
        val ms = lineMarkerRe.findAll(text).toList()
        if (ms.isEmpty()) return listOf(Part(emptyList(), display(text)))
        val out = ArrayList<Part>()
        val head = tidy(markerRe.replace(text.substring(0, ms.first().range.first), ""))
        if (head.isNotEmpty()) out += Part(emptyList(), head)
        var carry = emptyList<Long>()
        ms.forEachIndexed { i, m ->
            val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val refs = (carry + ids(raw).filter { it in known }).distinct()
            val end = if (i + 1 < ms.size) ms[i + 1].range.first else text.length
            val body = tidy(bareRe.replace(markerRe.replace(text.substring(m.range.last + 1, end), ""), ""))
            if (body.isEmpty()) carry = refs
            else { out += Part(refs, body); carry = emptyList() }
        }
        if (out.isEmpty()) return listOf(Part(carry, ""))
        if (carry.isNotEmpty()) out[out.size - 1] = out.last().let { it.copy(refs = (it.refs + carry).distinct()) }
        return out
    }

    /**
     * Quote chips per Blaze reply id: a reply that answers several messages, or one message that isn't the message right
     * above it (other messages or replies came in between), quotes them. Only user messages still in [list] count. One pass.
     */
    fun chipMap(list: List<ChatMessage>): Map<Long, List<Long>> {
        if (list.none { it.replyTo.isNotEmpty() }) return emptyMap()
        val users = list.filter { it.role == Role.USER }.map { it.id }.toHashSet()
        val out = HashMap<Long, List<Long>>()
        var prev: ChatMessage? = null
        for (m in list) {
            if (m.role == Role.STEP) continue
            if (m.role == Role.BLAZE && m.replyTo.isNotEmpty()) {
                val q = m.replyTo.filter { it in users }.distinct()
                val show = when {
                    q.isEmpty() -> false
                    q.size > 1 -> true
                    else -> prev?.id != q[0]
                }
                if (show) out[m.id] = q.take(MAX_CHIPS)
            }
            prev = m
        }
        return out
    }

    /** The quoted message in a chip: one line, no "> quote" lines, at most [CHIP_CHARS] characters. */
    fun snippet(text: String): String {
        val lines = untag(text).lines()
        val body = lines.dropWhile { it.startsWith(">") }.joinToString(" ").ifBlank { lines.joinToString(" ") { it.removePrefix(">") } }
        val one = body.replace(Regex("""\s+"""), " ").trim()
        return Scripts.clip(one, CHIP_CHARS)  // v0.45.7: never cuts a letter, tone mark or emoji in half
    }

    /**
     * How long to keep a burst open (ms; 0 = start the turn now). [lastSendAt] = the burst's newest message, [typingAt] =
     * the composer's last keystroke, [typing] = the composer holds text. Typing again after a send holds the turn until
     * you pause for [BURST_MS]; otherwise only [GRACE_MS]. Never more than [BURST_MAX_MS] after the last send.
     */
    fun burstWait(now: Long, lastSendAt: Long, typingAt: Long, typing: Boolean): Long {
        val hardEnd = lastSendAt + BURST_MAX_MS
        if (now >= hardEnd) return 0L
        val end = if (typing && typingAt >= lastSendAt) maxOf(lastSendAt + BURST_MS, typingAt + BURST_MS) else lastSendAt + GRACE_MS
        return (minOf(end, hardEnd) - now).coerceAtLeast(0L)
    }

    /** Where a new message goes. */
    enum class Route { NOW, BURST, JOIN_BURST_NOW, MID_TURN }

    /**
     * [busy] = a turn is running, [waiting] = a burst is open (messages waiting, no turn yet), [burst] = this send may wait
     * for more (typed in the chat; not voice, Magic Hand, routines or background agents).
     */
    fun route(busy: Boolean, waiting: Boolean, burst: Boolean): Route = if (!START_NOW) legacyRoute(busy, waiting, burst) else when {
        // v0.45.8 (user 08:52, "seconds between Send and Blaze starting"): a typed message no longer waits GRACE_MS..BURST_MAX_MS
        // for more. The first message goes on the wire at once; messages sent right after join the running turn at its next
        // step (MID_TURN), or start the next turn when it ends. [burst] no longer delays anything.
        busy -> Route.MID_TURN
        waiting -> Route.JOIN_BURST_NOW
        else -> Route.NOW
    }

    /** v0.45.8: typed messages start their turn at once (false = the 0.30.6 burst wait, kept for the before/after bench). */
    @Volatile var START_NOW = true

    /** The 0.30.6 routing (a typed message opened a burst and waited [burstWait] before its turn started). */
    fun legacyRoute(busy: Boolean, waiting: Boolean, burst: Boolean): Route = when {
        busy -> Route.MID_TURN
        waiting && !burst -> Route.JOIN_BURST_NOW
        waiting || burst -> Route.BURST
        else -> Route.NOW
    }
}
