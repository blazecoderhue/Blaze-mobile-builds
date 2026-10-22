package com.blaze.assistant.home

import org.json.JSONObject

/**
 * v0.29.0: the Home board's pure rules: category stacks, refresh scheduling, plain-words widget requests, starter widgets
 * and the interest picker's catalogue. Unit tested (HomeLogicTest).
 */

/** Widgets of one category, iOS-notification-group style: [main] on top, [rest] peeking underneath. */
data class WidgetStack(val key: String, val category: String, val widgets: List<Widget>) {
    val main: Widget get() = widgets.first()
    val rest: List<Widget> get() = widgets.drop(1)
    val stacked: Boolean get() = widgets.size > 1
}

object Stacks {
    /**
     * Groups the visible widgets by category (case-insensitive). A stack sits where its first widget is on the board, and
     * keeps its widgets in board order. Widgets with a blank category never stack.
     */
    fun build(widgets: List<Widget>): List<WidgetStack> {
        val out = LinkedHashMap<String, MutableList<Widget>>()
        widgets.filter { !it.hidden }.sortedBy { it.order }.forEach { w ->
            val k = w.category.trim().lowercase().ifBlank { "#" + w.id }
            out.getOrPut(k) { ArrayList() } += w
        }
        return out.map { (k, l) -> WidgetStack("s:" + k, l.first().category, l) }
    }

    /** The order of widget ids after moving a whole stack from [from] to [to] (stack positions), for [HomeStore.reorder]. */
    fun moveStack(stacks: List<WidgetStack>, from: Int, to: Int): List<String> {
        if (from !in stacks.indices) return stacks.flatMap { s -> s.widgets.map { it.id } }
        val l = stacks.toMutableList()
        val s = l.removeAt(from)
        l.add(to.coerceIn(0, l.size), s)
        return l.flatMap { st -> st.widgets.map { it.id } }
    }
}

/** Which stacks are expanded. Immutable; the UI keeps one in state. */
data class StackState(val open: Set<String> = emptySet()) {
    fun isOpen(key: String) = key in open
    /** Tapping a collapsed stack expands it; tapping the main card of an open one (or "Show less") folds it. */
    fun toggle(key: String): StackState = if (key in open) StackState(open - key) else StackState(open + key)
    fun collapse(key: String): StackState = StackState(open - key)
    fun expand(key: String): StackState = StackState(open + key)
    /** Drops keys of stacks that no longer exist or no longer stack. */
    fun prune(stacks: List<WidgetStack>): StackState = StackState(open.filter { k -> stacks.any { it.key == k && it.stacked } }.toSet())

    /** Peek layers drawn under a collapsed stack: at most two, offset, scaled down and dimmed further each step. */
    data class Peek(val offsetDp: Int, val scale: Float, val alpha: Float)
    companion object {
        fun peeks(count: Int): List<Peek> = (1..(count - 1).coerceIn(0, 2)).map { i -> Peek(offsetDp = 8 * i, scale = 1f - 0.05f * i, alpha = 1f - 0.3f * i) }

        /** How far the UI pushes each peek down, as a multiple of [Peek.offsetDp]. */
        const val SHIFT = 1.6f

        /**
         * v0.45.8 ghost stack: each peek only shows as the strip below the card in front of it ([visibleDp] tall). The
         * cards are see-through glass, so a full peek drawn behind the main card used to show its edges through it.
         */
        data class Strip(val shiftDp: Float, val visibleDp: Float)

        fun strips(count: Int): List<Strip> {
            var prev = 0f
            return peeks(count).map { p -> val sh = p.offsetDp * SHIFT; Strip(sh, sh - prev).also { prev = sh } }
        }
    }
}

/** Background refresh plan (WorkManager). */
object HomeSchedule {
    const val MIN_HOURS = 1
    const val DEFAULT_HOURS = 3
    data class Plan(val hours: Int, val unmeteredOnly: Boolean, val batteryNotLow: Boolean = true)

    /** Never more often than once an hour; Wi-Fi only asks for an unmetered network. */
    fun plan(hours: Int, wifiOnly: Boolean): Plan = Plan(hours.coerceIn(MIN_HOURS, 24), wifiOnly)

    /**
     * Whether a background pass may use the network now: not on a metered network when the user chose Wi-Fi only, and
     * not on a metered network while the phone's Data Saver restricts Blaze in the background.
     */
    fun mayRun(metered: Boolean, dataSaverRestricts: Boolean, wifiOnly: Boolean): Boolean =
        !(metered && (wifiOnly || dataSaverRestricts))

    /** A widget needs fetching when it never was, or its content is older than [maxAgeMs]. */
    fun due(fetched: Long, now: Long, maxAgeMs: Long): Boolean = fetched <= 0L || now - fetched >= maxAgeMs
}

/** A widget asked for in plain words ("show me Arsenal scores"). */
data class WidgetSpec(val type: String, val title: String, val category: String, val config: JSONObject, val size: String = WidgetSize.M) {
    fun toWidget(): Widget = Widget("", type, title, category, config.toString(), size)
}

object WidgetIntent {
    private fun cap(s: String) = s.trim().split(Regex("\\s+")).joinToString(" ") { w -> if (w.length <= 3 && w.all { it.isUpperCase() }) w else w.replaceFirstChar { it.titlecase() } }

    private val currencies = setOf("USD", "EUR", "GBP", "JPY", "CNY", "INR", "ZAR", "NGN", "KES", "ZMW", "GHS", "BRL", "MXN", "CAD", "AUD", "CHF",
        "AED", "SAR", "EGP", "TRY", "RUB", "PKR", "BDT", "IDR", "PHP", "VND", "THB", "MYR", "SGD", "KRW", "UGX", "TZS", "MWK", "BWP", "XOF", "XAF", "MAD", "ETB")
    private val coins = setOf("bitcoin", "btc", "ethereum", "eth", "solana", "sol", "dogecoin", "doge", "xrp", "ripple", "cardano", "ada", "bnb", "litecoin", "ltc",
        "tether", "usdt", "toncoin", "ton", "tron", "trx", "polkadot", "dot", "shiba", "avalanche", "chainlink")

    /** Understands the common asks; null means "let Blaze work it out" (it then uses home_widget_add). */
    fun parse(text: String): WidgetSpec? {
        val t = text.trim().trimEnd('.', '!', '?')
        val l = t.lowercase()
        if (l.isBlank()) return null
        // Weather
        Regex("\\bweather(?: (?:in|for|at) (.+))?$").find(l)?.let { m ->
            val city = m.groupValues[1].trim()
            val cfg = JSONObject(); if (city.isNotBlank()) cfg.put("city", cap(city)) else cfg.put("use_location", true)
            return WidgetSpec(WidgetTypes.WEATHER, if (city.isNotBlank()) "Weather in ${cap(city)}" else "Weather", "Weather", cfg)
        }
        // Creators: "new videos from MKBHD", "MKBHD's videos", youtube links, tiktok
        Regex("(?:new )?(?:videos?|uploads|shorts) (?:from|by) (.+)$").find(l)?.let { m ->
            val who = t.substring(m.groups[1]!!.range.first).trim()
            return creator(who)
        }
        if (Regex("(youtube\\.com|youtu\\.be|tiktok\\.com|instagram\\.com|twitch\\.tv)/").containsMatchIn(l)) {
            Regex("\\S*(youtube\\.com|youtu\\.be|tiktok\\.com|instagram\\.com|twitch\\.tv)/\\S+").find(t)?.let { return creator(it.value) }
        }
        // Prices: crypto
        Regex("\\b(?:track |show |watch )?(?:the )?([a-z]+)(?: coin)? (?:price|prices)\\b").find(l)?.let { m ->
            val w = m.groupValues[1]
            if (w in coins) return crypto(w)
        }
        Regex("\\b(?:price of|track|watch) ([a-z]+)\\b").find(l)?.let { m -> if (m.groupValues[1] in coins) return crypto(m.groupValues[1]) }
        // Prices: FX "usd to zmw", "dollar to kwacha rate"
        Regex("\\b([a-z]{3}) ?(?:to|/|→|in) ?([a-z]{3})\\b").find(l)?.let { m ->
            val a = m.groupValues[1].uppercase(); val b = m.groupValues[2].uppercase()
            if (a in currencies && b in currencies) return WidgetSpec(WidgetTypes.PRICES, "$a to $b", "Finance", JSONObject().put("kind", "fx").put("base", a).put("symbols", b))
        }
        // Prices: stocks "AAPL stock", "stock price of TSLA"
        Regex("\\b([A-Za-z.]{1,6}) (?:stock|shares)\\b").find(t)?.let { m ->
            val s = m.groupValues[1].uppercase()
            if (s.lowercase() !in setOf("the", "my", "a")) return WidgetSpec(WidgetTypes.PRICES, "$s stock", "Finance", JSONObject().put("kind", "stock").put("symbols", s))
        }
        // Sports: "Arsenal scores", "scores for the Lakers", "how is Chelsea doing"
        Regex("^(?:show (?:me )?)?(?:the )?(.+?) (?:scores?|results|fixtures|games|matches)$").find(l)?.let { m ->
            val team = t.substring(m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1).trim().removePrefix("the ").removePrefix("The ")
            if (team.isNotBlank()) return WidgetSpec(WidgetTypes.SPORTS, cap(team), "Sports", JSONObject().put("team", cap(team)))
        }
        Regex("\\b(?:scores?|results|fixtures) (?:for|of) (?:the )?(.+)$").find(l)?.let { m ->
            val team = t.substring(m.groups[1]!!.range.first).trim()
            return WidgetSpec(WidgetTypes.SPORTS, cap(team), "Sports", JSONObject().put("team", cap(team)))
        }
        // GitHub
        Regex("(?:github\\.com/)?([A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100})").find(t)?.takeIf { l.contains("github") || l.contains("repo") }?.let { m ->
            GitHubFeed.repo(m.groupValues[1])?.let { r -> return WidgetSpec(WidgetTypes.GITHUB, r, "Tech", JSONObject().put("repo", r)) }
        }
        if (Regex("\\bgithub notifications\\b").containsMatchIn(l)) return WidgetSpec(WidgetTypes.GITHUB, "GitHub", "Tech", JSONObject().put("notifications", true))
        // Calendar / routines / deadlines / apps
        if (Regex("\\b(my )?(calendar|agenda|next events?|meetings)\\b").containsMatchIn(l)) return WidgetSpec(WidgetTypes.CALENDAR, "Calendar", "Today", JSONObject())
        if (Regex("\\b(next )?routines?|reminders?\\b").containsMatchIn(l)) return WidgetSpec(WidgetTypes.ROUTINES, "Next routine", "Today", JSONObject())
        if (Regex("\\b(deadlines?|exams?|assignments?|homework|due dates?)\\b").containsMatchIn(l)) return WidgetSpec(WidgetTypes.DEADLINES, "Deadlines", "Today", JSONObject())
        if (Regex("\\b(most[- ]used apps|app usage|screen time|apps i use)\\b").containsMatchIn(l)) return WidgetSpec(WidgetTypes.APPS, "Most-used apps", "Phone", JSONObject())
        // News: "news about X", "X news", "headlines"
        Regex("\\b(?:news|headlines|stories|articles) (?:about|on|from|for) (.+)$").find(l)?.let { m ->
            val q = t.substring(m.groups[1]!!.range.first).trim()
            return news(q)
        }
        Regex("^(?:show (?:me )?)?(?:the )?(?:latest )?(.+?) news$").find(l)?.let { m ->
            val q = t.substring(m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1).trim()
            return news(q)
        }
        if (Regex("^(?:top |latest )?(news|headlines)$").matches(l)) return WidgetSpec(WidgetTypes.NEWS, "Top stories", "News", JSONObject().put("query", ""))
        if (Regex("^https?://\\S+\\.(xml|rss)\\b|/feed/?$|/rss/?$").containsMatchIn(l)) return WidgetSpec(WidgetTypes.NEWS, "Feed", "News", JSONObject().put("feed", t))
        return null
    }

    fun news(q: String) = WidgetSpec(WidgetTypes.NEWS, cap(q) + " news", "News", JSONObject().put("query", q))

    fun crypto(w: String): WidgetSpec {
        val id = CoinGecko.id(w)
        return WidgetSpec(WidgetTypes.PRICES, CoinGecko.pretty(id), "Finance", JSONObject().put("kind", "crypto").put("ids", id).put("vs", "usd"))
    }

    fun creator(who: String): WidgetSpec {
        val p = YouTube.platformOf(who)
        val name = who.trim().substringBefore('?').trimEnd('/').substringAfterLast('/').removePrefix("@").ifBlank { who.trim() }
        return WidgetSpec(WidgetTypes.CREATORS, if (p == "YouTube") name else "$name on $p", "Creators", JSONObject().put("channel", who.trim()).put("platform", p))
    }
}

/** The interest picker (first open of Home) and starter widgets. */
object Interests {
    data class Chip(val id: String, val label: String, val subs: List<String> = emptyList())

    val catalogue = listOf(
        Chip("tech", "Tech", listOf("AI", "Phones", "Gadgets", "Programming", "Startups")),
        Chip("sports", "Sports", listOf("Premier League", "Champions League", "NBA", "NFL", "Formula 1", "Cricket", "Tennis", "Arsenal", "Man City", "Liverpool", "Real Madrid", "Barcelona", "Lakers")),
        Chip("music", "Music", listOf("Hip hop", "Afrobeats", "Pop", "K-pop", "Gospel", "Amapiano")),
        Chip("gaming", "Gaming", listOf("PlayStation", "Xbox", "Nintendo", "PC gaming", "Esports", "Mobile games")),
        Chip("finance", "Finance", listOf("Bitcoin", "Ethereum", "Stocks", "USD to EUR", "Personal finance")),
        Chip("news", "News", listOf("World", "Business", "Science", "Health", "Local")),
        Chip("study", "Study", listOf("Deadlines", "Exams", "Science", "Maths")),
        Chip("creators", "Creators", listOf("MKBHD", "MrBeast", "Veritasium", "Mark Rober")),
        Chip("fitness", "Fitness", listOf("Running", "Gym", "Football training", "Yoga")),
        Chip("movies", "Movies & TV", listOf("Marvel", "Anime", "Netflix")),
    )

    private val teams = setOf("Arsenal", "Man City", "Liverpool", "Real Madrid", "Barcelona", "Lakers", "Chelsea", "Man United", "Kaizer Chiefs", "Al Ahly")
    private val coinsList = setOf("Bitcoin", "Ethereum")
    private val creatorList = setOf("MKBHD", "MrBeast", "Veritasium", "Mark Rober")

    /** Widgets for the chosen chips and sub-chips (+ a typed answer), without duplicates, at most [max]. */
    fun seed(chosen: Set<String>, subs: Set<String>, typed: String, max: Int = 8): List<WidgetSpec> {
        val out = ArrayList<WidgetSpec>()
        fun add(s: WidgetSpec?) { if (s != null && out.none { it.type == s.type && it.config.toString() == s.config.toString() }) out += s }
        add(starterWeather())
        subs.filter { it in teams }.forEach { add(WidgetSpec(WidgetTypes.SPORTS, it, "Sports", JSONObject().put("team", it))) }
        subs.filter { it in coinsList }.forEach { add(WidgetIntent.crypto(it)) }
        subs.filter { it in creatorList }.forEach { add(WidgetIntent.creator("@" + it.replace(" ", ""))) }
        if ("USD to EUR" in subs) add(WidgetSpec(WidgetTypes.PRICES, "USD to EUR", "Finance", JSONObject().put("kind", "fx").put("base", "USD").put("symbols", "EUR")))
        if ("Deadlines" in subs || "Exams" in subs || "study" in chosen) add(WidgetSpec(WidgetTypes.DEADLINES, "Deadlines", "Today", JSONObject()))
        // League / topic subs become news topics.
        val topical = subs.filter { it !in teams && it !in coinsList && it !in creatorList && it !in setOf("USD to EUR", "Deadlines", "Exams", "Local") }
        topical.forEach { add(WidgetIntent.news(it)) }
        if ("Local" in subs) add(WidgetSpec(WidgetTypes.NEWS, "Top stories", "News", JSONObject().put("query", "")))
        for (c in chosen) {
            val label = catalogue.firstOrNull { it.id == c }?.label ?: continue
            if (catalogue.first { it.id == c }.subs.none { it in subs }) when (c) {
                "finance" -> add(WidgetIntent.crypto("bitcoin"))
                "news" -> add(WidgetSpec(WidgetTypes.NEWS, "Top stories", "News", JSONObject().put("query", "")))
                "study" -> {}
                else -> add(WidgetIntent.news(label))
            }
        }
        typed.split(',', ';', '\n').map { it.trim() }.filter { it.length >= 2 }.forEach { add(WidgetIntent.parse(it) ?: WidgetIntent.news(it)) }
        add(WidgetSpec(WidgetTypes.ROUTINES, "Next routine", "Today", JSONObject()))
        return out.take(max)
    }

    fun starterWeather() = WidgetSpec(WidgetTypes.WEATHER, "Weather", "Weather", JSONObject().put("use_location", true))

    /**
     * Generic starters when Blaze knows little about the user: weather, top stories for the phone's region, the next
     * routine and a "tell me what you're into" card.
     */
    fun starters(): List<WidgetSpec> = listOf(
        starterWeather(),
        WidgetSpec(WidgetTypes.NEWS, "Top stories", "News", JSONObject().put("query", "")),
        WidgetSpec(WidgetTypes.ROUTINES, "Next routine", "Today", JSONObject()),
        WidgetSpec(WidgetTypes.INTO, "Tell me what you're into", "For you", JSONObject(), WidgetSize.S),
    )

    /** Memory lines → interests as a memory sentence ("Interested in Sports: Arsenal, NBA."). */
    fun memoryLine(chosen: Set<String>, subs: Set<String>, typed: String): String {
        val parts = catalogue.filter { it.id in chosen }.map { c ->
            val s = c.subs.filter { it in subs }
            if (s.isEmpty()) c.label else c.label + ": " + s.joinToString(", ")
        } + subs.filter { s -> catalogue.none { s in it.subs } } + listOfNotNull(typed.trim().takeIf { it.isNotBlank() })
        return if (parts.isEmpty()) "" else "Interested in " + parts.joinToString("; ") + "."
    }

    /**
     * Personalised suggestions from what Blaze remembers (memory texts). Each hit carries a confidence; callers fall back
     * to [starters] when nothing is confident. Types the user removed before ([removedCategories]) are skipped.
     */
    data class Suggestion(val spec: WidgetSpec, val confidence: Double, val why: String)

    fun fromMemories(texts: List<String>, removedCategories: Set<String> = emptySet()): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        val all = texts.joinToString("\n")
        val low = all.lowercase()
        fun add(s: WidgetSpec, c: Double, why: String) {
            if (s.category.lowercase() in removedCategories.map { it.lowercase() }) return
            if (out.none { it.spec.type == s.type && it.spec.config.toString() == s.config.toString() }) out += Suggestion(s, c, why)
        }
        (teams).forEach { t -> if (Regex("\\b" + Regex.escape(t.lowercase()) + "\\b").containsMatchIn(low)) add(WidgetSpec(WidgetTypes.SPORTS, t, "Sports", JSONObject().put("team", t)), 0.8, "you follow $t") }
        Regex("(?i)\\b(?:supports?|fan of|follows?) (?:the )?([A-Z][\\w]+(?: [A-Z][\\w]+)?)").findAll(all).forEach { m ->
            val t = m.groupValues[1]; if (t !in teams) add(WidgetSpec(WidgetTypes.SPORTS, t, "Sports", JSONObject().put("team", t)), 0.6, "you follow $t")
        }
        if (Regex("\\b(bitcoin|btc|crypto)\\b").containsMatchIn(low)) add(WidgetIntent.crypto("bitcoin"), 0.7, "you mentioned crypto")
        if (Regex("\\b(ethereum|eth)\\b").containsMatchIn(low)) add(WidgetIntent.crypto("ethereum"), 0.7, "you mentioned Ethereum")
        Regex("(?i)(?:watches|subscribed to|likes) ([A-Z][\\w]+) (?:on youtube|videos)").findAll(all).forEach { m -> add(WidgetIntent.creator("@" + m.groupValues[1]), 0.6, "you watch ${m.groupValues[1]}") }
        if (Regex("\\b(student|university|college|school|exam|assignment|semester)\\b").containsMatchIn(low)) add(WidgetSpec(WidgetTypes.DEADLINES, "Deadlines", "Today", JSONObject()), 0.6, "you're studying")
        Regex("(?i)\\b(?:lives in|based in|from) ([A-Z][a-z]+(?: [A-Z][a-z]+)?)").find(all)?.let { m ->
            add(WidgetSpec(WidgetTypes.WEATHER, "Weather in ${m.groupValues[1]}", "Weather", JSONObject().put("city", m.groupValues[1]).put("use_location", true)), 0.5, "where you live")
        }
        Regex("(?i)interested in ([^.\\n]+)").findAll(all).forEach { m ->
            m.groupValues[1].split(';', ',').map { it.substringAfter(':').trim() }.filter { it.length in 2..40 }.take(4).forEach { add(WidgetIntent.parse(it) ?: WidgetIntent.news(it), 0.7, "you're into $it") }
        }
        if (Regex("\\b(github|programming|developer|coding|kotlin|android app)\\b").containsMatchIn(low)) add(WidgetIntent.news("Programming"), 0.5, "you code")
        return out.sortedByDescending { it.confidence }
    }

    /** Confident enough to seed from memory instead of generic starters. */
    fun confident(s: List<Suggestion>): Boolean = s.count { it.confidence >= 0.6 } >= 2
}
