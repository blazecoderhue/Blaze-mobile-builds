package com.blaze.assistant.agent

/**
 * v0.26.2 Hark-style orchestration: `delegate` hands out specialist tasks without blocking the turn. Each task
 * becomes a background agent running as that specialist (BgAgents, its AgentCard with Steer / Stop / Open), the
 * chat stays usable, and each result posts into the chat as soon as it lands (its card moves to the bottom and
 * the main Blaze writes a short follow-up from it).
 *
 * This file is the pure part (DispatchTest): which queued agent may start ([pick]), which briefs are
 * duplicates ([plan]), the one-line scope note, the browser lease, and the platform message a result wakes
 * the chat with.
 */
object Dispatch {
    /** Network-only specialists are just API calls: up to this many at once, even on a 3 GB phone. */
    const val NET_CAP = 3
    /** Tasks one delegate call may hand out. */
    const val MAX_TASKS = 6
    /** Two briefs sharing this much of their words are the same target. */
    const val SAME_TARGET = 0.75

    enum class Kind { NETWORK, PHONE, GENERAL }

    /** A specialist that drives the phone screen takes turns (the phone lock); everything else it does is network work. */
    fun kind(specialistGroups: Set<String>?): Kind = when {
        specialistGroups == null -> Kind.GENERAL
        "phone" in specialistGroups -> Kind.PHONE
        else -> Kind.NETWORK
    }

    /**
     * May an agent of [k] start while [running] (kinds of the agents running now) run? General agents keep the
     * old RAM cap ([generalCap]: 1, or 2 on 6 GB phones); phone specialists share that cap and never run two at
     * once; network specialists have their own [netCap].
     */
    fun canRun(k: Kind, running: List<Kind>, generalCap: Int, netCap: Int = NET_CAP): Boolean {
        val heavy = running.count { it != Kind.NETWORK }
        return when (k) {
            Kind.NETWORK -> running.count { it == Kind.NETWORK } < netCap
            Kind.PHONE -> running.none { it == Kind.PHONE } && heavy < generalCap
            Kind.GENERAL -> heavy < generalCap
        }
    }

    /** Index of the first queued agent (oldest first) that may start now, or -1. Later ones can pass a blocked one. */
    fun pick(queued: List<Kind>, running: List<Kind>, generalCap: Int, netCap: Int = NET_CAP): Int =
        queued.indexOfFirst { canRun(it, running, generalCap, netCap) }

    // ---------------- briefs ----------------

    data class Brief(val specialist: String, val task: String, val title: String = "")
    data class Plan(val accepted: List<Brief>, val rejected: List<Pair<Brief, String>>)

    private fun words(s: String): Set<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

    fun similar(a: String, b: String): Double {
        val x = words(a); val y = words(b); if (x.isEmpty() || y.isEmpty()) return 0.0
        val i = x.count { it in y }; return i.toDouble() / (x.size + y.size - i)
    }

    /**
     * The briefs to start: blank ones, ones over [MAX_TASKS], and duplicates (the same target twice in this call,
     * or a brief a running specialist of the same kind already has) are rejected with the reason.
     */
    fun plan(briefs: List<Brief>, live: List<Brief>, max: Int = MAX_TASKS): Plan {
        val ok = ArrayList<Brief>(); val no = ArrayList<Pair<Brief, String>>()
        for (b in briefs) {
            val dupe = ok.firstOrNull { similar(it.task, b.task) >= SAME_TARGET }
            val running = live.firstOrNull { it.specialist == b.specialist && similar(it.task, b.task) >= SAME_TARGET }
            when {
                b.task.isBlank() -> no += b to "empty brief"
                dupe != null -> no += b to "same target as another task in this call (one specialist per target)"
                running != null -> no += b to "a ${b.specialist} is already working on this; steer it with message_agent instead"
                ok.size >= max -> no += b to "more than $max tasks in one call"
                else -> ok += b
            }
        }
        return Plan(ok, no)
    }

    /** A 2-5 word card title from a brief ("Goal: price of … at Game" → "Price of … at Game"). */
    fun title(task: String): String {
        val first = task.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            .replace(Regex("^(goal|task|brief)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        val w = first.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val t = w.take(6).joinToString(" ").trimEnd(',', '.', ':', ';')
        return (if (w.size > 6) "$t…" else t).replaceFirstChar { it.uppercase() }.take(48)
    }

    /**
     * The one-line scope note shown before multi-specialist work (two or more tasks): the model's own [scope]
     * line when it gave one, else "Sending Researcher and Shopper ×2 to work on this at the same time…". "" for one task.
     */
    fun scopeLine(scope: String, names: List<String>): String {
        if (names.size < 2) return ""
        val own = scope.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (own.length >= 12) return own.take(180)
        val counts = LinkedHashMap<String, Int>(); names.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val parts = counts.map { (n, c) -> if (c > 1) "$n ×$c" else n }
        val who = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        return "Sending $who to work on this at the same time. Results land here as each one finishes."
    }

    /** What delegate returns to the model right away. */
    fun startedLine(started: List<Pair<String, String>>, rejected: List<Pair<Brief, String>>, queued: Int, scopeShown: Boolean = false): String = buildString {
        if (started.isNotEmpty()) append("Started ").append(started.joinToString(", ") { (id, name) -> "$id ($name)" }).append(". ")
        if (queued > 0) append("$queued of them wait for a free slot. ")
        if (started.isNotEmpty()) append("They work in the background with their cards in the chat; each result reaches you on its own as it lands. " +
            "Don't wait, poll or repeat the work. " +
            (if (scopeShown) "The scope line is already in the chat, so don't repeat it; carry on or end your turn. " else "Say one short line to the user, then carry on or end your turn. ") +
            "Steer one with message_agent, stop one with cancel_agent.")
        rejected.forEach { (b, why) -> append("\nNot started (${b.specialist}: ${b.task.take(60)}): $why.") }
        if (started.isEmpty() && rejected.isEmpty()) append("Error: give tasks=[{specialist, task}].")
    }.trim()

    // ---------------- results ----------------

    const val RESULT_PREFIX = "[Specialist result"

    /** The platform message a specialist's result wakes the chat with ([othersLive] = its siblings still working). */
    fun resultMessage(id: String, name: String, title: String, status: String, steps: Int, seconds: Long, result: String, othersLive: Int): String = buildString {
        append("$RESULT_PREFIX · $id · $name · $status · $steps step(s) · ${seconds}s]\n")
        append("This is a platform message, not the user. Your $name specialist finished \u00ab$title\u00bb; its card with the result is now the latest thing in the chat. ")
        append("Write a short follow-up from it (one to three sentences): the key finding and what it means for the user's request, in your own words. Don't paste it.\n")
        append("<specialist_result id=\"$id\" specialist=\"$name\" status=\"$status\">\n")
        append(if (status == "done") result.take(8000) else "It could not finish: " + result.take(3000))
        append("\n</specialist_result>")
        if (othersLive > 0) append("\n$othersLive other specialist(s) from this chat are still working. Don't wait for them and don't merge everything yet; their results arrive on their own.")
        else append("\nThat was the last one: if several specialists worked on this, end with the combined answer or your pick in one or two lines.")
    }

    /** Moves the first item matching [match] to the end (a finished specialist's card drops to the bottom of the chat). */
    fun <T> moveToEnd(list: List<T>, match: (T) -> Boolean, update: (T) -> T = { it }): List<T> {
        val i = list.indexOfFirst(match); if (i < 0) return list
        return list.filterIndexed { j, _ -> j != i } + update(list[i])
    }

    // ---------------- browser lease ----------------

    /**
     * Blaze's background browser has one current tab, so two agents clicking in it at once would trip over each
     * other. The first agent that uses a browser_ tool holds it until it finishes; others are told to use
     * web_search / fetch_page meanwhile. The main chat is never blocked.
     */
    class Lease {
        @Volatile var holder: String? = null; private set

        /** null = go ahead; else the message for the agent that has to wait. */
        @Synchronized fun take(agent: String, live: (String) -> Boolean = { true }): String? {
            val h = holder
            if (h == null || h == agent || !live(h)) { holder = agent; return null }
            return "Blaze's browser is in use by agent $h right now. Use web_search and fetch_page for this step instead (they work in parallel), or finish without the browser."
        }

        @Synchronized fun release(agent: String) { if (holder == agent) holder = null }
    }

    val browser = Lease()
    /** 0.45.19: Blaze Phone has one screen too; the first agent to use a phone_ tool holds it. */
    val phone = Lease()
}
