package com.blaze.assistant.notify

/**
 * 0.45.19 phone tasks (user testing 0.45.18, Fri 23 Oct 09:22): Blaze texts like a messaging app with its own icon.
 *
 * The bug: the get-to-know-you check-in ("Did 'lovely' hit different today…") came as a plain notification (no B
 * avatar, no MessagingStyle, no quick replies) on another channel and arrived silently, while the task result two
 * lines below had the avatar and the conversation look. Every Blaze message now goes through ONE builder
 * (Notifier.post): the same MessagingStyle conversation with one Person "Blaze" (the app icon as its avatar), one
 * conversation shortcut, one notification id (so the shade shows one Blaze thread), quick replies that fit the
 * message ([QuickReplies]) and an inline Reply, on one channel that alerts (IMPORTANCE_HIGH: heads-up, sound and
 * vibration as the user's system settings say). Do Not Disturb and silent mode are honoured: nothing bypasses them.
 *
 * Channels: the messages used to land on four channels (replies HIGH, heads-ups HIGH, follow-ups HIGH, check-ins /
 * calls DEFAULT = no heads-up). A channel's importance can't be raised by the app once created, so the messages move
 * to a NEW channel id and the old ones are deleted ([CHANNEL], [OLD_CHANNELS]). Pure (BlazeThreadTest).
 */
object BlazeThread {
    /** The one channel every Blaze message posts on (IMPORTANCE_HIGH). New id: old channels can't be raised. */
    const val CHANNEL = "blaze_messages_v2"
    const val CHANNEL_NAME = "Blaze messages"
    const val CHANNEL_DESC = "Blaze's messages: answers, finished tasks, check-ins and heads-ups. Sound and vibration follow your phone's settings."
    /** Channels the messages used to post on; deleted once [CHANNEL] exists (their posts moved to it). */
    val OLD_CHANNELS = listOf("blaze_reply", "blaze_proactive", "blaze_calls", "blaze_followups")
    /** The long-lived conversation shortcut the thread is attached to (Android 11+ conversation section). */
    const val SHORTCUT = "blaze_conversation"
    /** The one notification id of the Blaze thread (was the replies' id). */
    const val NOTIF_ID = 2
    const val PERSON_KEY = "blaze"
    const val PERSON_NAME = "Blaze"

    enum class Kind { ACK, REPLY, RESULT, FAILED, CHECK_IN, HEADS_UP, QUESTION, FOLLOW_UP, CALL_OFFER }

    /** One line of the thread. */
    data class Msg(val at: Long, val text: String, val kind: Kind)

    /** Channels to delete after [CHANNEL] is created: the old message channels that still exist. */
    fun toDelete(existing: Collection<String>): List<String> = OLD_CHANNELS.filter { it in existing && it != CHANNEL }

    /** The collapsed title. A failure says so honestly; everything else is just "Blaze", like a text. */
    fun title(kind: Kind, urgent: Boolean = false): String = when {
        kind == Kind.FAILED -> MessageThread.FAILED_TITLE
        urgent -> "Blaze \u00b7 important"
        else -> PERSON_NAME
    }

    /** Adds [m] to [thread] (oldest first), keeping at most [keep] lines. Returns the lines to show. */
    fun push(thread: ArrayDeque<Msg>, m: Msg, keep: Int = MessageThread.KEEP): List<Msg> {
        val t = m.text.trim()
        if (t.isEmpty()) return thread.toList()
        // the same line twice in a row (a re-post) shows once
        if (thread.lastOrNull()?.text == t) thread.removeLast()
        thread.addLast(m.copy(text = t.take(1000)))
        while (thread.size > keep) thread.removeFirst()
        return thread.toList()
    }
}

/**
 * Quick replies under a Blaze message (the RemoteInput choices, sent back as the user's reply). They must fit the
 * message: a toggle that is now on gets "Turn it off" / "Thanks", a failure "Try again", a yes/no question Yes / No.
 * Android's own generated replies are switched off (they offered "Weird" / "Great!" under a result). Pure.
 */
object QuickReplies {
    const val MAX = 3
    /** Words the system's generated replies offered under a result; Blaze never offers them. */
    val NEVER = setOf("weird", "great!", "great", "lol", "haha", "cool", "nice", "ok", "\ud83d\udc4d")

    private val stateOn = Regex("(?i)\\b([\\p{L}][\\p{L}\\p{N} .'\u2011-]{1,30}?)\\s+(?:is|are)\\s+(?:now\\s+|already\\s+|back\\s+)?(on|off|enabled|disabled)\\b")
    private val turnedOn = Regex("(?i)\\bturned\\s+(?:it\\s+|([\\p{L}][\\p{L}\\p{N} .'-]{1,30}?)\\s+)?(on|off)\\b")
    private val uncheckedRe = Regex("(?i)\\bcouldn'?t (?:check|confirm|verify)\\b")
    private val failRe = Regex("(?i)\\b(couldn'?t|could not|can'?t|cannot|failed|didn'?t work|wasn'?t able|unable|not confirmed|hit a problem|went wrong|still reads|still shows|tried to [^.]{0,60}\\bbut)\\b")
    private val timeSet = Regex("(?i)\\b(alarm|timer|reminder)s?\\b.*\\b(set|starts?|running|for \\d)")
    private val ackRe = Regex("(?i)^(got it|on it|okay|ok)[,.!]? .*(i'?ll|i will) (message|confirm|let you know|tell you)")
    private val messageSent = Regex("(?i)\\b(sent|drafted|draft is ready|replied)\\b")
    private val playing = Regex("(?i)\\b(now playing|playing|paused)\\b")

    /** The quick replies for Blaze's message [raw] of [kind] (at most [MAX]; empty = Reply box only). */
    fun forMessage(raw: String, kind: BlazeThread.Kind = BlazeThread.Kind.REPLY): List<String> {
        val text = raw.trim().removePrefix("Done \u2713").trim()
        if (text.isEmpty()) return emptyList()
        val out: List<String> = when {
            kind == BlazeThread.Kind.ACK || ackRe.containsMatchIn(text) -> emptyList()
            kind == BlazeThread.Kind.CALL_OFFER -> listOf("Not now")
            // honest fallback ("I turned it on but couldn't check it; it should be on")
            uncheckedRe.containsMatchIn(text) && turnedOn.containsMatchIn(text) -> listOf("Check it again", "Thanks")
            kind == BlazeThread.Kind.FAILED || failRe.containsMatchIn(text) -> listOf("Try again", "What happened?")
            text.trimEnd().endsWith("?") -> questionReplies(text, kind)
            stateOn.containsMatchIn(text) -> stateOn.findAll(text).last().let { m -> listOf(flip(m.groupValues[2]), "Thanks") }
            turnedOn.containsMatchIn(text) -> turnedOn.findAll(text).last().let { m -> listOf(flip(m.groupValues[2]), "Thanks") }
            timeSet.containsMatchIn(text) -> listOf("Cancel it", "Thanks")
            playing.containsMatchIn(text) -> listOf("Pause", "Thanks")
            messageSent.containsMatchIn(text) -> listOf("Thanks")
            kind == BlazeThread.Kind.RESULT -> listOf("Thanks")
            else -> listOf("Thanks", "Tell me more")
        }
        return out.filter { it.lowercase() !in NEVER }.distinct().take(MAX)
    }

    private fun flip(state: String): String = when (state.lowercase()) {
        "on", "enabled" -> "Turn it off"
        else -> "Turn it on"
    }

    /** A question: Yes / No / Later for a yes-no one, its options for a short either-or, else a way to park it. */
    private fun questionReplies(text: String, kind: BlazeThread.Kind): List<String> {
        val chips = try { com.blaze.assistant.agent.SimpleHome.replyChips(text) } catch (_: Throwable) { emptyList() }
        if (chips.isNotEmpty()) return chips.map { it.label }.filter { it != "Do it for me" }.take(MAX)
        val q = com.blaze.assistant.agent.SimpleHome.lastQuestion(text).removeSuffix("?").trim()
        val opts = eitherOr(q)
        if (opts.isNotEmpty()) return opts
        return if (kind == BlazeThread.Kind.CHECK_IN || kind == BlazeThread.Kind.HEADS_UP) listOf("Ask me later", "Skip this one")
        else listOf("Not sure", "Later")
    }

    /** "Coffee or tea" -> [Coffee, Tea]; options longer than a chip (or more than three) give none. */
    fun eitherOr(q: String): List<String> {
        val parts = q.split(Regex("(?i),?\\s+or\\s+|,\\s+")).map { it.trim().trim(',', '.') }.filter { it.isNotEmpty() }
        if (parts.size !in 2..MAX) return emptyList()
        val lead = Regex("(?i)^(do you (want|prefer)|would you (like|prefer)|should i (use|pick|get)?|which( one)?|is it|are you|did|was it|what about)\\s+")
        val clean = parts.mapIndexed { i, p -> (if (i == 0) p.replace(lead, "") else p.replace(Regex("(?i)^(is it|maybe)\\s+"), "")).trim() }
        if (clean.any { it.isEmpty() || it.length > 22 || it.split(' ').size > 3 }) return emptyList()
        return clean.map { it.replaceFirstChar { c -> c.uppercase() } }
    }
}
