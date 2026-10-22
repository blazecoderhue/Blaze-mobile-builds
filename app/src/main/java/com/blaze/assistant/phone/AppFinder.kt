package com.blaze.assistant.phone

/**
 * v0.33.3, pure (AppFinderTest): finding the user's installed apps by what they ask for.
 * On a low-end phone, "a car rally game" got "none installed (closest is Bus Fever Party!)" although Rally Horizon was
 * installed: list_apps only kept apps whose label contained the whole filter text. Now the ask is split into words,
 * matched case-insensitively against the label's words AND the package name's parts, with partial words ("rally" →
 * "Rally Horizon", "horiz" → "Horizon"), and category words ("game", "music", "maps"…) match the app's Android
 * category (ApplicationInfo.category, or the old "is a game" flag). Also: which asks mean "my apps" (never a skill
 * like product-listing), and resolving "open / play X" to an installed package before anything else.
 */
object AppFinder {
    /** Android's ApplicationInfo.CATEGORY_* values (kept here so the pure part needs no Android). */
    const val CAT_UNDEFINED = -1
    const val CAT_GAME = 0
    const val CAT_AUDIO = 1
    const val CAT_VIDEO = 2
    const val CAT_IMAGE = 3
    const val CAT_SOCIAL = 4
    const val CAT_NEWS = 5
    const val CAT_MAPS = 6
    const val CAT_PRODUCTIVITY = 7

    data class App(val label: String, val pkg: String, val category: Int = CAT_UNDEFINED) {
        val isGame: Boolean get() = category == CAT_GAME
    }

    data class Hit(val app: App, val score: Int)

    private val categoryWords: Map<String, Int> = mapOf(
        "game" to CAT_GAME, "games" to CAT_GAME, "gaming" to CAT_GAME,
        "music" to CAT_AUDIO, "audio" to CAT_AUDIO, "podcast" to CAT_AUDIO, "podcasts" to CAT_AUDIO, "radio" to CAT_AUDIO,
        "video" to CAT_VIDEO, "videos" to CAT_VIDEO, "movie" to CAT_VIDEO, "movies" to CAT_VIDEO,
        "photo" to CAT_IMAGE, "photos" to CAT_IMAGE, "gallery" to CAT_IMAGE, "camera" to CAT_IMAGE, "picture" to CAT_IMAGE, "pictures" to CAT_IMAGE,
        "social" to CAT_SOCIAL, "chat" to CAT_SOCIAL, "messaging" to CAT_SOCIAL,
        "news" to CAT_NEWS,
        "map" to CAT_MAPS, "maps" to CAT_MAPS, "navigation" to CAT_MAPS, "gps" to CAT_MAPS,
        "productivity" to CAT_PRODUCTIVITY, "office" to CAT_PRODUCTIVITY, "notes" to CAT_PRODUCTIVITY,
    )

    private val stop = setOf("a", "an", "the", "my", "me", "i", "any", "some", "on", "in", "of", "for", "to", "is", "it", "that", "this",
        "app", "apps", "application", "applications", "installed", "install", "phone", "do", "have", "got", "which", "what", "called",
        "named", "open", "launch", "start", "play", "run", "please", "find", "show", "list", "there", "one", "with", "and", "or", "you", "can")

    /** Package parts that say nothing ("com", "android", "app"…). */
    private val pkgNoise = setOf("com", "org", "net", "io", "android", "app", "apps", "mobile", "free", "lite", "pro", "game", "games", "co", "gp", "inc")

    fun words(s: String): List<String> = s.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
        .split(Regex("[^\\p{L}\\p{N}+]+")).filter { it.isNotEmpty() }

    /** The ask's name words (stop words and category words removed) and its category, if it names one. */
    fun parse(query: String): Pair<List<String>, Int?> {
        val w = words(query)
        val cat = w.firstNotNullOfOrNull { categoryWords[it] }
        return w.filter { it !in stop && it !in categoryWords }.distinct() to cat
    }

    private fun pkgParts(pkg: String): List<String> = pkg.lowercase().split('.', '_', '-').filter { it.length >= 2 && it !in pkgNoise }

    /** How well one name word matches one label / package word: 3 whole word, 2 prefix either way, 1 inside, 0 none. */
    fun wordScore(q: String, w: String): Int = when {
        q == w -> 3
        q.length >= 3 && w.startsWith(q) -> 2
        w.length >= 4 && q.startsWith(w) -> 2
        q.length >= 4 && w.contains(q) -> 1
        else -> 0
    }

    /**
     * Score for one app, or 0 for no match. Each name word scores its best match in the label (×2) or the package (×1);
     * a named category adds 3 when it matches the app and is required when the ask has no name words ("my games").
     */
    fun score(query: String, app: App): Int {
        val (names, cat) = parse(query)
        val lw = words(app.label); val pw = pkgParts(app.pkg)
        var s = 0; var matched = 0
        for (q in names) {
            val inLabel = lw.maxOfOrNull { wordScore(q, it) } ?: 0
            val inPkg = pw.maxOfOrNull { wordScore(q, it) } ?: 0
            val best = maxOf(inLabel * 2, inPkg)
            if (best > 0) { matched++; s += best }
        }
        val catHit = cat != null && app.category == cat
        if (names.isEmpty()) return if (catHit) 3 else 0
        if (matched == 0) return 0
        // The whole label said in the ask ("open rally horizon") beats a single shared word.
        if (lw.isNotEmpty() && lw.all { w -> names.any { it == w } }) s += 4
        if (catHit) s += 3
        return s
    }

    /** Matching apps, best first (ties: shorter label first, then A to Z). Empty query = every app A to Z. */
    fun search(query: String, apps: List<App>, limit: Int = 30): List<Hit> {
        val (names, cat) = parse(query)
        if (names.isEmpty() && cat == null) return apps.sortedBy { it.label.lowercase() }.map { Hit(it, 0) }.take(limit)
        return apps.map { Hit(it, score(query, it)) }.filter { it.score > 0 }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.app.label.length }.thenBy { it.app.label.lowercase() })
            .take(limit)
    }

    /**
     * The installed app "open / play / launch X" means, or null. A package name in the text (e.g. from a quoted app tile:
     * "package com.foo.rally") wins outright when installed; otherwise the best match if it is clearly the one.
     */
    fun resolve(query: String, apps: List<App>): App? {
        pkgIn(query)?.let { p -> apps.firstOrNull { it.pkg.equals(p, true) }?.let { return it } }
        val hits = search(query, apps, limit = 3)
        val top = hits.firstOrNull() ?: return null
        val (names, _) = parse(query)
        if (names.isEmpty()) return null                      // "play a game": which one is the user's call
        val second = hits.getOrNull(1)
        // Clear winner: every name word matched something, or well ahead of the next one.
        val allMatched = names.all { q -> (words(top.app.label) + pkgParts(top.app.pkg)).any { wordScore(q, it) > 0 } }
        return if (allMatched || second == null || top.score >= second.score + 3) top.app else null
    }

    private val pkgRe = Regex("""\b([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+){1,8})\b""")

    /** A package-looking id in the text ("com.foo.rally"), not a web host ("github.com" has no 3 parts and ends in a TLD). */
    fun pkgIn(text: String): String? = pkgRe.findAll(text).map { it.groupValues[1] }
        .firstOrNull { p -> val parts = p.split('.'); parts.size >= 3 && parts.none { it.isEmpty() } && !p.startsWith("www.") && !p.contains("http") }

    /** The id in a Play Store details link (play.google.com/store/apps/details?id=…), or null. */
    fun playId(url: String): String? = Regex("""play\.google\.com/store/apps/details\?(?:[^#]*&)?id=([A-Za-z0-9_.]+)""").find(url)?.groupValues?.get(1)
        ?: Regex("""^market://details\?(?:[^#]*&)?id=([A-Za-z0-9_.]+)""").find(url)?.groupValues?.get(1)

    private val appsAsk = Regex(
        """\b(?:list|show|see|what|which|all|tell me)\b.{0,30}\b(?:my|installed|the)?\s*(?:apps|applications|games)\b|""" +
            """\b(?:my|installed)\s+(?:apps|applications|games)\b|\bapps?\s+(?:on|in)\s+(?:my|this)\s+(?:phone|device)\b|""" +
            """\bdo i have\b.{0,40}\b(?:app|game)s?\b""",
        RegexOption.IGNORE_CASE)

    /** True when the ask is about the apps on the user's phone ("list my apps", "which games do I have?"). */
    fun isAppsAsk(text: String): Boolean = appsAsk.containsMatchIn(text) &&
        !Regex("""\b(?:shopify|listing|product|store listing|dropship)""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** The line a quoted / attached app tile carries into the chat, so the agent knows the real package. */
    fun tileLine(a: App): String = "App: ${a.label} (package ${a.pkg}, installed on this phone; open it with open_app)"

    /** The ```apps card (label + package per tile) that the chat draws as a tappable grid. */
    fun fence(title: String, apps: List<App>, max: Int = 200): String {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
        return "```apps\n{\"title\":${q(title)},\"apps\":[" + apps.take(max).joinToString(",") {
            "{\"label\":${q(it.label)},\"pkg\":${q(it.pkg)}${if (it.isGame) ",\"game\":true" else ""}}"
        } + "]}\n```"
    }

    private val claimsList = Regex("""\b(?:here(?:'s| is| are)|below|these are|showing)\b.{0,40}\b(?:apps?|applications|games|list)\b""", RegexOption.IGNORE_CASE)

    /**
     * The reply with the app card added when list_apps ran this turn and the reply doesn't carry the card itself but says it
     * shows a list ("Here's your app list") or the user asked for their apps. [fence] null = list_apps didn't run.
     */
    fun ensureCard(reply: String, fence: String?, userText: String): String {
        if (fence == null || reply.contains("```apps")) return reply
        if (!claimsList.containsMatchIn(reply) && !isAppsAsk(userText)) return reply
        return reply.trimEnd() + "\n\n" + fence
    }
}
