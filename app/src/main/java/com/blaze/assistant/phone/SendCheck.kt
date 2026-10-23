package com.blaze.assistant.phone

/**
 * v0.28.7: honest sends. Blazeinchue asked Blaze to find his older brother Gabriel and send him a cat picture it had
 * just made. It said it saw no Gabriel, then opened WhatsApp and claimed "Message sent" while nothing was sent,
 * and in the end it sent the words "orange cat" instead of the picture.
 *
 * Pure logic, unit-tested in SendCheckTest:
 *  - [norm] / [score] / [pick]: contact names with emoji and hearts ("Gabriele❤️") still match "Gabriel", typos
 *    match by edit distance, and several matches come back as a question for the user, never as "not found".
 *  - [imageArg]: "last", "latest" or "the image you made" mean the newest Gallery picture.
 *  - [chatOpenFor] / [verdict]: Send counts as done only when the screen showed that person's chat before the tap
 *    and the draft (or the picture preview) is gone after it. Otherwise the tool says it couldn't confirm.
 */
object SendCheck {

    /** Lowercase letters/digits and single spaces; emoji, hearts and punctuation dropped. */
    fun norm(s: String): String = s.lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        .replace(Regex("\\s+"), " ").trim()

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    /** How well a saved contact name fits what the user said (0 = not at all, 100 = exact). */
    fun score(query: String, name: String): Int {
        val q = norm(query); val n = norm(name)
        if (q.isEmpty() || n.isEmpty()) return 0
        if (q == n) return 100
        val qt = q.split(' '); val nt = n.split(' ')
        if (qt.all { w -> nt.any { it == w } }) return 90
        if (qt.all { w -> nt.any { it.startsWith(w) } }) return 80
        if (n.contains(q)) return 70
        // A typo in one word: "gabrial" ~ "gabriel" (only for words of 4+ letters).
        if (qt.all { w -> w.length >= 4 && nt.any { t -> t.length >= 4 && lev(w, t.take(w.length + 1)) <= if (w.length >= 6) 2 else 1 } }) return 55
        return 0
    }

    data class Pick(val one: Pair<String, String>? = null, val choices: List<Pair<String, String>> = emptyList())

    /** One clear contact, or the choices to put to the user. Same number twice counts once. */
    fun pick(query: String, contacts: List<Pair<String, String>>): Pick {
        val ranked = contacts.map { it to score(query, it.first) }.filter { it.second > 0 }
            .distinctBy { it.first.second.filter(Char::isDigit).takeLast(9) }
            .sortedByDescending { it.second }
        if (ranked.isEmpty()) return Pick()
        if (ranked.size == 1) return Pick(one = ranked[0].first)
        val top = ranked[0].second
        val best = ranked.filter { it.second == top }
        // Only a name saved exactly as said wins outright; "Gabriel" fitting both "Gabriele❤️" and "Gabriel Bro" is a question.
        if (best.size == 1 && top == 100) return Pick(one = best[0].first)
        return Pick(choices = ranked.take(6).map { it.first })
    }

    /** The tool line when several contacts fit: tells the model to ask, with the exact options. */
    fun ambiguous(query: String, choices: List<Pair<String, String>>): String =
        "Couldn't pick one contact for \u201c$query\u201d. Several fit: " +
            choices.joinToString("; ") { "${it.first} (${it.second})" } +
            ". Ask the user which one with ask_user (use these exact names as the options), then call message_contact again with the chosen name or number. Nothing was sent."

    fun notFound(query: String): String =
        "Couldn't find \u201c$query\u201d in the phone's contacts (WhatsApp uses the same contacts). Check recent_notifications for a chat from them, " +
            "or ask the user for the number or the name exactly as saved. Nothing was sent."

    private val lastWords = Regex("""^(last|latest|newest|recent|that|this|it|the (image|picture|photo|pic)( you (just )?(made|generated|created|drew))?|generated|just made)$""", RegexOption.IGNORE_CASE)

    /** The Gallery file an `image` argument means, from (name, time) items. "last"-style words → the newest. */
    fun imageArg(arg: String, items: List<Pair<String, Long>>): String? {
        val a = arg.trim().substringAfterLast('/')
        if (a.isEmpty()) return null
        if (lastWords.matches(arg.trim())) return items.maxByOrNull { it.second }?.first
        items.firstOrNull { it.first == a }?.let { return it.first }
        return items.filter { it.first.contains(a, true) }.maxByOrNull { it.second }?.first
    }

    /** Does the screen look like [who]'s chat (their name or the end of their number is showing)? */
    fun chatOpenFor(who: String, number: String, words: Collection<String>): Boolean {
        val first = norm(who).split(' ').firstOrNull { it.length >= 2 } ?: ""
        val tail = number.filter(Char::isDigit).takeLast(7)
        return words.any { w ->
            val n = norm(w)
            (first.isNotEmpty() && n.split(' ').any { it == first || (first.length >= 4 && it.startsWith(first)) }) ||
                (tail.length >= 7 && w.filter(Char::isDigit).contains(tail))
        }
    }

    enum class Verdict { SENT, UNSURE }

    /**
     * After Send was tapped. [text] the message (or caption), [image] whether a picture preview was being sent,
     * [fieldText] what the message box holds now ("" when empty or gone), [words] the visible screen.
     */
    fun verdict(text: String, image: Boolean, fieldText: String, words: Collection<String>): Verdict {
        val draft = norm(text)
        if (image) {
            // The preview screen has a caption box and its own Send; once sent, WhatsApp returns to the chat.
            val stillPreview = words.any { Regex("(?i)add a caption|caption|view once").containsMatchIn(it) }
            return if (stillPreview) Verdict.UNSURE else Verdict.SENT
        }
        if (draft.isEmpty()) return Verdict.UNSURE
        val f = norm(fieldText)
        if (f.isNotEmpty() && (f == draft || f.contains(draft.take(24)))) return Verdict.UNSURE
        return Verdict.SENT
    }
}
