package com.blaze.assistant.agent

/**
 * v0.45.9 replay fix (live gateway, Sunday 25 Oct with "Church 9:00–12:30" at 08:30 in the [context] block): 1 in 5
 * final answers to "anything on for today?" still said "Nothing scheduled", and some named Monday's CIT class as
 * Sunday's. The phone already knows that date's items ([ScheduleDay] → the block's `schedule <date> (N items):` line),
 * so the final answer of a schedule ask is checked against them on the phone:
 *  - it claims the day is empty while the line has items, or
 *  - it leaves out an item that is on the line, or
 *  - it names an item that is only on another day (the `next after that day` line) without saying which day,
 * then the answer is rebuilt from the line: "Today (Sun 25 Oct): 08:30 Church 9:00–12:30." The follow-up chips line
 * stays last. Answers that already name every item, empty answers, and turns that aren't schedule asks are left alone. Pure.
 */
object ScheduleTruth {
    /** label "Sun 25 Oct 2026", (time, title); [passed] = indexes of items the line marks (passed) (v0.45.13). */
    data class Day(val label: String, val items: List<Pair<String, String>>, val passed: Set<Int> = emptySet()) {
        val ahead: List<Pair<String, String>> get() = items.filterIndexed { i, _ -> i !in passed }
        val over: List<Pair<String, String>> get() = items.filterIndexed { i, _ -> i in passed }
    }

    private val lineRe = Regex("""^schedule (\w{3} \d{1,2} \w{3} \d{4}) \((\d+) items?(?:, \d+ still ahead)?\): (.+)$""")
    private val itemRe = Regex("""(\d{2}:\d{2}(?:\u2013\d{2}:\d{2})?|all day) "([^"]+)"((?: \([^)]*\))*)""")
    private val nextRe = Regex("""^next after that day \(not that day\): \w{3} \d{1,2} \w{3} (?:\d{2}:\d{2}(?:\u2013\d{2}:\d{2})? )?(.+)$""")
    private val emptyRe = Regex("""(?i)\b(nothing(?: is)? (?:scheduled|planned|on|booked|lined up)|no (?:events|plans|items|reminders|meetings|classes)\b|you(?:'re| are) (?:free|clear)|(?:an? )?(?:empty|clear|free) (?:day|schedule|calendar)|nothing (?:for )?today)""")
    private val follow = Regex("""<{1,2}/?follow[- ]?ups?:[^>]*>{0,2}""", RegexOption.IGNORE_CASE)
    private val dayWord = Regex("""(?i)\b(mon|tue|wed|thu|fri|sat|sun)\w*\b|\btomorrow\b|\bnext\b""")

    /** The dated schedule lines with items in a [context] block (empty when the block has none). */
    fun days(block: String): List<Day> = block.lines().mapNotNull { l ->
        val m = lineRe.find(l.trim()) ?: return@mapNotNull null
        val found = itemRe.findAll(m.groupValues[3]).toList()
        val items = found.map { it.groupValues[1] to it.groupValues[2] }
        val passed = found.mapIndexedNotNull { i, f -> if (f.groupValues[3].contains("(passed)")) i else null }.toSet()
        if (items.isEmpty()) null else Day(m.groupValues[1], items, passed)
    }

    /** The title on the block's "next after that day" line, if any. */
    fun nextTitle(block: String): String? = block.lines().firstNotNullOfOrNull { nextRe.find(it.trim())?.groupValues?.get(1)?.trim() }

    /** Words that identify a title in a reply ("Church 9:00–12:30" → "church"; "CIT class" → "cit"). */
    private fun key(title: String): String = title.split(Regex("[^\\p{L}\\p{N}]+")).firstOrNull { it.length >= 2 && it.any(Char::isLetter) }?.lowercase() ?: title.lowercase()

    private fun mentions(text: String, title: String): Boolean = Regex("(?i)\\b" + Regex.escape(key(title)) + "\\b").containsMatchIn(text)

    /** "18:15" / "6:15" / "6:15 pm" in [text] for the item time "18:15" (or the start of "18:00–19:00"). */
    private fun timeIn(text: String, time: String): Boolean {
        if (time == "all day") return false
        val (h, m) = time.take(5).split(":").map { it.toInt() }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return listOf("%02d:%02d".format(h, m), "%d:%02d".format(h, m), "%d:%02d".format(h12, m), "%d.%02d".format(h12, m)).distinct()
            .any { Regex("(?<![0-9])" + Regex.escape(it) + "(?![0-9])").containsMatchIn(text) }
    }

    /**
     * v0.45.13 (phone 17:12: "Demo app check reminder at 18:30" counted as naming "Demo app alarm" 18:15 too): an item
     * is mentioned by its key word only when no other item that day shares it; else its time has to be there too.
     */
    private fun named(text: String, d: Day, item: Pair<String, String>): Boolean {
        val k = key(item.second)
        val shared = d.items.count { key(it.second) == k } > 1
        return if (shared) mentions(text, item.second) && timeIn(text, item.first) else mentions(text, item.second) || timeIn(text, item.first)
    }

    private val remainingAsk = Regex("""(?i)\b(else|left|still|remaining|upcoming|next|later|rest of|to do|other than)\b""")
    private val pastWords = Regex("""(?i)\b(earlier|passed|already|this morning|was at|were at|done|over|finished|had)\b""")

    /** True when [reply] (visible words) contradicts [days]: claims empty, misses an item, or borrows [next] without a day. */
    fun wrong(reply: String, days: List<Day>, next: String?, userText: String = ""): Boolean {
        val text = reply.replace(follow, " ")
        if (days.isEmpty()) return false
        val remaining = remainingAsk.containsMatchIn(userText)
        // v0.45.13: "nothing else today" is right when everything left on the line has passed.
        if (emptyRe.containsMatchIn(text)) {
            if (!(remaining && days.all { it.ahead.isEmpty() })) return true
        }
        // Items still ahead must all be named; a passed one may be left out only on a "what's left" ask.
        if (days.any { d -> d.ahead.any { !named(text, d, it) } }) return true
        // v0.45.13 replay round 1 (0.45.9 strict "anything on for today?" at 10:14, 0/5): the passed 08:00 class was left
        // out of a plain "what's on" answer. A plain ask names every item, the passed ones as earlier.
        if (!remaining && days.any { d -> d.over.any { !named(text, d, it) } }) return true
        // A passed item offered as still to do ("You have a CIT class at 10:15 today" at 17:11).
        if (remaining && days.any { d -> d.over.any { named(text, d, it) && !pastWords.containsMatchIn(text) } }) return true
        if (next != null && days.none { d -> d.items.any { (_, t) -> key(t) == key(next) } } && mentions(text, next)) {
            // "...and CIT class on Monday" is fine; "Today you have Church and CIT class" is not.
            val i = Regex("(?i)\\b" + Regex.escape(key(next)) + "\\b").find(text)!!.range.first
            if (!dayWord.containsMatchIn(text.substring(i, minOf(text.length, i + 40)))) return true
        }
        return false
    }

    private fun item(p: Pair<String, String>) = if (p.first == "all day") "${p.second} (all day)" else "${p.first} ${p.second}"

    /** "Today (Sun 25 Oct): 08:30 Church 9:00–12:30." for one day; one line per day for several. [remaining] = only what's ahead. */
    fun answer(days: List<Day>, todayLabel: String?, remaining: Boolean = false): String = days.joinToString("\n") { d ->
        val short = d.label.substringBeforeLast(' ')
        val head = if (d.label == todayLabel) "Today ($short)" else short
        when {
            d.over.isEmpty() -> head + ": " + d.items.joinToString("; ") { item(it) } + "."
            d.ahead.isEmpty() -> head + ": nothing else is on. Earlier: " + d.over.joinToString("; ") { item(it) } + " (already passed)."
            remaining -> head + ", still ahead: " + d.ahead.joinToString("; ") { item(it) } + "."
            else -> head + ", still ahead: " + d.ahead.joinToString("; ") { item(it) } + ". Earlier: " + d.over.joinToString("; ") { item(it) } + " (passed)."
        }
    }

    /**
     * The final answer for a schedule ask: [reply] unchanged when it agrees with [block], else the answer rebuilt from
     * the block (follow-up chips kept). [scheduleAsk] = the turn asked about a day's schedule.
     */
    fun fix(reply: String, block: String, scheduleAsk: Boolean, userText: String = ""): String {
        // An empty answer is EmptyReply's (asked again, then a plain failure), never turned into a schedule.
        if (!scheduleAsk || EmptyReply.visible(reply).isEmpty()) return reply
        val ds = days(block)
        if (ds.isEmpty() || !wrong(reply, ds, nextTitle(block), userText)) return reply
        val today = Regex("""(?m)^now: (\w{3} \d{1,2} \w{3} \d{4})""").find(block)?.groupValues?.get(1)
        val chips = follow.find(reply)?.value
        return answer(ds, today, remainingAsk.containsMatchIn(userText)) + (chips?.let { "\n\n$it" } ?: "")
    }
}
