package com.blaze.assistant.phone

/**
 * v0.28.9: "open it for me" (about a gallery picture) opened the game "Juno: New Origins Complete Edition",
 * because the fast lane asked open_app for "it" and "Ed-it-ion" contains "it". Two rules now:
 * a command that points back at something earlier ("it", "that", "the picture", "again") never takes the
 * fast lane (the full agent knows what "it" is), and app names only match on whole words.
 */
object AppMatch {
    private val pronoun = Regex("""^(?:it|that|this|them|those|these|one|that one|this one|the one|same|the same|again|him|her|me|you|there|here)$""")
    private val referent = Regex("""\b(it|that|this|them|those|these|that one|this one|the one|same one|the picture|the image|the photo|the file|the link|the pdf|the video|the doc(ument)?|again)\b""", RegexOption.IGNORE_CASE)
    private val filler = Regex("""\b(for me|for us|please|pls|now|up|real quick|quickly|right now|again|app|application)\b""")

    /** True when a short command leans on what came before ("open it for me", "send that", "show the picture"). */
    fun refersBack(text: String): Boolean = referent.containsMatchIn(text.trim())

    /** The app name with filler dropped, or "" when nothing nameable is left ("it for me" → ""). */
    fun clean(name: String): String {
        val q = filler.replace(name.lowercase(), " ").replace(Regex("""^(the|my)\s+"""), "").replace(Regex("\\s+"), " ").trim()
        return if (q.length < 2 || pronoun.matches(q)) "" else q
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9+]+")).filter { it.isNotEmpty() }

    /** 0 exact, 1 label starts with it, 2 whole words inside the label, 3 label's words inside the ask, 4 package, null no match. */
    fun score(name: String, label: String, pkg: String): Int? {
        val q = clean(name); if (q.isEmpty()) return null
        val l = label.lowercase().trim()
        val qw = words(q); val lw = words(l)
        if (qw.isEmpty() || lw.isEmpty()) return null
        return when {
            l == q || lw == qw -> 0
            lw.take(qw.size) == qw -> 1
            lw.windowed(qw.size).any { it == qw } -> 2
            lw.joinToString("").length >= 3 && qw.windowed(lw.size).any { it == lw } -> 3
            q.replace(" ", "").length >= 4 && pkg.lowercase().split('.').any { it == q.replace(" ", "") } -> 4
            else -> null
        }
    }

    /**
     * v0.45.6 (phone 21:30: "play music" opened BlazeFX through a fuzzy "Blaze*" match): outside apps launch only on an
     * EXACT match: the package name itself, or the label equal to the ask (case, spaces and punctuation ignored; a trailing
     * "app" dropped). [apps] = (label, package). Blaze's own package ([self]) is never launched this way. Returns
     * (package, label) or null; [near] lists close labels to ask the user about instead.
     */
    fun exact(name: String, apps: List<Pair<String, String>>, self: String = ""): Pair<String, String>? {
        val raw = name.trim()
        if (raw.isEmpty()) return null
        apps.firstOrNull { it.second.equals(raw, true) && it.second != self }?.let { return it.second to it.first }
        val q = squash(clean(raw))
        if (q.length < 2) return null
        val hits = apps.filter { it.second != self && squash(it.first) == q }
        return hits.singleOrNull()?.let { it.second to it.first }
    }

    /** Close labels for "did you mean" (never launched): the old whole-word scores 1..3. */
    fun near(name: String, apps: List<Pair<String, String>>, self: String = "", max: Int = 4): List<String> =
        apps.filter { it.second != self }.mapNotNull { (l, p) -> score(name, l, p)?.takeIf { it in 1..4 }?.let { it to l } }
            .sortedBy { it.first }.map { it.second }.distinct().take(max)

    private fun squash(s: String) = s.lowercase().replace(Regex("[^a-z0-9+]"), "")

    /** v0.45.6: an ask that means one of Blaze's own screens ("Blaze Music", "Blaze settings"…): its view id, else null. */
    fun blazeView(name: String): String? {
        val q = name.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim().removeSuffix(" app").trim()
        return when (q) {
            "blaze music", "blaze player", "blaze music player", "music in blaze" -> "music"
            "blaze settings", "blaze s settings" -> "settings"
            "blaze gallery" -> "gallery"
            "blaze terminal" -> "terminal"
            "blaze projects" -> "projects"
            "blaze home" -> "home"
            "blaze design", "blaze studio" -> "design"
            "blaze", "blaze assistant" -> "home"
            else -> null
        }
    }
}
