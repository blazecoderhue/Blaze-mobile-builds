package com.blaze.assistant.browser.phone

import com.blaze.assistant.browser.SiteDomain
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.45.19-speed (user rule 8: "users never memorise URLs"): turns what the user calls a site ("the booking site",
 * "herokuapp login page", "my bank", "the-internet login") into the right page.
 *
 *  1. Sites the user used before win: Blaze Phone's own visits, Blaze's browser history and saved logins. "my bank",
 *     "the booking site" (a kind of site, no name) are answered from history only; with nothing there, Blaze asks
 *     which one instead of guessing.
 *  2. Otherwise a web search: ads and look-alike / SEO-spam results are dropped ("github-login.net",
 *     "loginguide-hub.com", "wikiHow: how to log in"), the official domain (a host label equal to the name) wins.
 *  3. Then the sub-page the words ask for (login, sign up, orders, account, cart…): a result on the same site whose
 *     path is that page, or else the page-level step (open the site, tap its "Log in" link) in [PhoneBrowserTools].
 *
 * Pure Kotlin: search results come in through a function, so the strict cases run on fixtures (SiteResolverTest).
 */
object SiteResolver {
    data class Result(val title: String, val url: String, val snippet: String = "", val ad: Boolean = false)
    data class Visit(val url: String, val title: String = "", val count: Int = 1, val lastAt: Long = 0L)
    /** [url] null = not resolved: [ask] is the question for the user. [subpage]: still to reach on the page itself. */
    data class Resolution(val url: String?, val subpage: String?, val why: String, val ask: String? = null,
                          val fromHistory: Boolean = false, val searched: Boolean = false)

    // ------------------------------------------------------------------ words
    private class Sub(val name: String, val query: Regex, val path: Regex, val link: Regex, val searchWord: String, strip: Regex? = null) {
        /** What to take out of the words once matched (only the sub-page's own words: "create a reddit account" keeps "reddit"). */
        val strip: Regex = strip ?: query
    }
    private val SUBS = listOf(
        Sub("signup", Regex("""(?i)\b(sign ?up|signup|register|registration|create(?:\s+\w+){0,3}\s+account|join)\b"""),
            Regex("""(?i)(sign-?up|signup|sign_up|register|/join\b|create-?account|registration)"""),
            Regex("""(?i)^\s*(sign ?up|register|create (?:an? )?account|join(?: now| free)?)\b"""), "sign up",
            Regex("""(?i)\b(sign ?up|signup|register|registration|create|account|join)\b""")),
        Sub("login", Regex("""(?i)\b(log ?in(?:to)?|login|sign ?in(?:to)?|signin|sign-in|log ?on|logon)\b"""),
            Regex("""(?i)(log-?in|login|sign-?in|signin|sign_in|logon|/auth\b|/ap/signin|/session\b|/account/login)"""),
            Regex("""(?i)^\s*(log ?in|sign ?in|login|signin|my account)\b"""), "login"),
        Sub("orders", Regex("""(?i)\b(orders?|order history|purchases)\b"""),
            Regex("""(?i)(order-history|order_history|/orders\b|your-orders|/gp/css/order|/purchases)"""),
            Regex("""(?i)\b(your orders|my orders|orders|order history|returns & orders)\b"""), "orders"),
        Sub("bookings", Regex("""(?i)\b(my bookings|my reservations|my trips)\b"""),
            Regex("""(?i)(/bookings|/reservations|/trips)"""), Regex("""(?i)\b(my bookings|bookings|reservations|my trips)\b"""), "my bookings"),
        Sub("cart", Regex("""(?i)\b(cart|basket)\b"""), Regex("""(?i)(/cart|/basket)"""), Regex("""(?i)\b(cart|basket)\b"""), "cart"),
        Sub("checkout", Regex("""(?i)\bcheck ?out\b"""), Regex("""(?i)/checkout"""), Regex("""(?i)\bcheck ?out\b"""), "checkout"),
        Sub("account", Regex("""(?i)\b(account|settings|profile)\b"""), Regex("""(?i)(/account|/settings|/profile|/my-?account)"""),
            Regex("""(?i)\b(my account|account|profile|settings)\b"""), "account"),
    )

    private class Kind(val name: String, val query: Regex, val match: Regex, val noun: String)
    private val KINDS = listOf(
        Kind("bank", Regex("""(?i)\b(bank|banking|bank's)\b"""),
            Regex("""(?i)(bank|banking|credit union|zanaco|stanbic|absa|\bfnb\b|barclays|hsbc|chase|wells ?fargo|citibank|natwest|lloyds|santander|ecobank|indo zambia|atlas mara)"""), "bank"),
        Kind("booking", Regex("""(?i)\b(booking|book|booked|bookings|reservation|reserve|reserved|hotel|hotels|room|rooms|b&b|stay|stays)\b"""),
            Regex("""(?i)(\bbook(?:ing|ings|er|ed)?\b|booker|reserv|hotel|\bb&b\b|\bbnb\b|\brooms?\b|airbnb)"""), "booking site"),
        Kind("shop", Regex("""(?i)\b(shop|shopping|store)\b"""), Regex("""(?i)(\bshop|store\b|amazon|ebay|takealot|jumia|aliexpress)"""), "shop"),
        Kind("mail", Regex("""(?i)\b(e-?mail|mail|inbox)\b"""), Regex("""(?i)(mail|outlook|inbox)"""), "email"),
        Kind("school", Regex("""(?i)\b(school|university|uni|college|course|class|student portal)\b"""),
            Regex("""(?i)(\.edu\b|\.ac\.|school|university|college|moodle|canvas|blackboard)"""), "school site"),
    )

    private val FILLER = setOf("the", "a", "an", "my", "our", "your", "that", "this", "those", "page", "pages", "site", "sites", "website", "websites",
        "web", "homepage", "home", "official", "open", "go", "goto", "to", "take", "me", "please", "on", "for", "in", "into", "of", "link", "url",
        "online", "i", "use", "used", "usually", "where", "are", "is", "at", "with", "from", "one", "app", "portal", "blaze", "phone", "can", "you",
        "find", "get", "show", "visit", "it", "s", "up", "now", "again", "and", "or", "thing", "screen", "form", "log", "sign")

    /** A URL or a bare domain (opened as it is, no resolving). */
    fun isUrlish(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.contains(' ')) return false
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t) || t.startsWith("about:")) return true
        return Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(t) && Regex("\\.[a-zA-Z]{2,}(?::\\d+)?(/|$)").containsMatchIn(t)
    }

    data class Intent(val brand: List<String>, val subpage: String?, val kind: String?)

    fun intent(q0: String): Intent {
        var q = " " + q0.lowercase().replace('\u2019', '\'').replace(Regex("'s\\b"), "") + " "
        val sub = SUBS.firstOrNull { it.query.containsMatchIn(q) }
        if (sub != null) q = sub.strip.replace(q, " ")
        val kind = KINDS.firstOrNull { it.query.containsMatchIn(q) }
        val toks = Regex("[a-z0-9][a-z0-9.\\-]*[a-z0-9]|[a-z0-9]").findAll(q).map { it.value.trim('.', '-') }.filter { it.isNotEmpty() }.toList()
        val brand = toks.filter { it !in FILLER && (kind == null || !kind.query.matches(it)) }
        return Intent(brand, sub?.name, kind?.name)
    }

    private fun sub(name: String?) = SUBS.firstOrNull { it.name == name }

    /** Does [url]'s path look like sub-page [name]? */
    fun pathIs(name: String?, url: String): Boolean {
        val s = sub(name) ?: return false
        val path = url.substringAfter("://").let { p -> p.indexOf('/').let { if (it < 0) "" else p.substring(it) } }
        return s.path.containsMatchIn(path)
    }

    /** The link / button words that lead to sub-page [name] on a site's page. */
    fun linkRe(name: String?): Regex? = sub(name)?.link

    /** Is the page already sub-page [name]? (A login page shows a password field.) */
    fun onSubpage(name: String?, url: String, passwordVisible: Boolean): Boolean = when (name) {
        null -> true
        "login" -> passwordVisible || pathIs(name, url)
        else -> pathIs(name, url)
    }

    // ------------------------------------------------------------------ search results
    /** Parses [com.blaze.assistant.agent.Web.search]'s numbered list ("1. title\n   url\n   snippet"). */
    fun parseSearch(text: String): List<Result> {
        val out = ArrayList<Result>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val m = Regex("^\\s*\\d+\\.\\s+(.*)$").find(lines[i])
            if (m != null && i + 1 < lines.size && lines[i + 1].trim().startsWith("http")) {
                val title = m.groupValues[1].trim(); val url = lines[i + 1].trim()
                val snip = lines.getOrNull(i + 2)?.trim()?.takeIf { it.isNotEmpty() && !Regex("^\\d+\\.\\s").containsMatchIn(it) }.orEmpty()
                out += Result(title, url, snip, ad = isAd(title, url)); i += 2
            } else i++
        }
        return out
    }

    private val adUrl = Regex("""(?i)(duckduckgo\.com/y\.js|googleadservices\.com|doubleclick\.net|/aclk\?|bing\.com/aclick|[?&](ad_domain|ad_provider|gclid|msclkid)=)""")
    private val adTitle = Regex("""(?i)^\s*(ad|ads|sponsored|promoted)\s*[·:|\-]""")
    fun isAd(title: String, url: String): Boolean = adUrl.containsMatchIn(url) || adTitle.containsMatchIn(title)

    private val spamWords = Regex("""(?i)(login|logins|signin|sign-in|loginhelp|help-?center|helpdesk|support|guide|howto|how-to|tutorial|reviews?|status|isdown|downdetector|free|deals?|coupons?|promo|tips|portal-?guide|account-?help|official-?site|-help)""")
    private val infoHosts = setOf("wikihow.com", "quora.com", "youtube.com", "reddit.com", "wikipedia.org", "medium.com", "pinterest.com", "facebook.com",
        "techwiser.com", "lifewire.com", "makeuseof.com", "howtogeek.com", "tiktok.com", "x.com", "twitter.com", "instagram.com", "linkedin.com", "trustpilot.com")

    private fun norm(t: String) = t.lowercase().replace(Regex("[^a-z0-9.\\-]"), "")
    private fun hostLabels(host: String) = host.split('.').filter { it.isNotEmpty() }
    private fun sld(host: String) = SiteDomain.registrable(host).substringBefore('.')

    /** The result's host is the brand's own (a label equal to the name, or the very domain the user named). */
    fun official(url: String, brand: List<String>): Boolean {
        val host = SiteDomain.hostOf(url); if (host.isEmpty()) return false
        val labels = hostLabels(host); val reg = SiteDomain.registrable(host)
        return brand.any { b0 -> val b = norm(b0)
            if (b.contains('.')) SiteDomain.registrable(b) == reg || host == b || host.endsWith(".$b") else b in labels || labels.any { it.replace("-", "") == b.replace("-", "") } }
    }

    /** "github-login.net", "paypal-help-center.support": the name plus extra words in someone else's domain. */
    fun lookalike(url: String, brand: List<String>): Boolean {
        if (official(url, brand)) return false
        val s = sld(SiteDomain.hostOf(url))
        return brand.any { b -> val n = norm(b).substringBefore('.'); n.length >= 3 && s.contains(n) && s != n }
    }

    fun spam(url: String, brand: List<String>): Boolean {
        if (official(url, brand)) return false
        val host = SiteDomain.hostOf(url)
        return spamWords.containsMatchIn(sld(host)) || Regex("""(?i)/(how-to|.*-login-guide|.*login-help)""").containsMatchIn(url.substringAfter(host))
    }

    fun info(url: String, brand: List<String>): Boolean = !official(url, brand) && SiteDomain.registrable(SiteDomain.hostOf(url)) in infoHosts

    // ------------------------------------------------------------------ history
    fun decodeVisits(json: String?): List<Visit> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Visit(it.optString("u"), it.optString("t"), it.optInt("n", 1), it.optLong("at")) }.filter { it.url.startsWith("http") }
    } catch (_: Exception) { emptyList() }

    /** Records a Blaze Phone visit (newest first, one entry per URL, at most [max]). */
    fun addVisit(json: String?, url: String, title: String, now: Long, max: Int = 150): String {
        if (!url.startsWith("http")) return json ?: "[]"
        val u = url.substringBefore('#').take(300)
        val old = decodeVisits(json)
        val prev = old.firstOrNull { it.url == u }
        val list = listOf(Visit(u, title.take(120).ifBlank { prev?.title.orEmpty() }, (prev?.count ?: 0) + 1, now)) + old.filter { it.url != u }
        return JSONArray().apply { list.take(max).forEach { put(JSONObject().put("u", it.url).put("t", it.title).put("n", it.count).put("at", it.lastAt)) } }.toString()
    }

    private data class SiteUse(val site: String, val best: Visit, val count: Int, val lastAt: Long, val all: List<Visit>)

    private fun bySite(visits: List<Visit>): List<SiteUse> = visits.filter { SiteDomain.hostOf(it.url).isNotEmpty() }
        .groupBy { SiteDomain.registrable(SiteDomain.hostOf(it.url)) }
        .map { (site, vs) -> SiteUse(site, vs.maxWith(compareBy<Visit>({ it.count }, { it.lastAt })), vs.sumOf { it.count }, vs.maxOf { it.lastAt }, vs) }
        .sortedWith(compareByDescending<SiteUse> { it.count }.thenByDescending { it.lastAt })

    /** The visit to open on a site: one already on the sub-page if there is one, else the most used. */
    private fun pick(u: SiteUse, subpage: String?): Visit =
        (if (subpage != null) u.all.filter { pathIs(subpage, it.url) }.maxWithOrNull(compareBy<Visit>({ it.count }, { it.lastAt })) else null) ?: u.best

    private fun host(url: String) = SiteDomain.hostOf(url)

    // ------------------------------------------------------------------ resolve
    suspend fun resolve(query: String, history: List<Visit>, search: suspend (String) -> List<Result>): Resolution {
        val q = query.trim()
        if (isUrlish(q)) return Resolution(if (q.contains("://")) q else "https://$q", null, "a web address")
        val it0 = intent(q)
        val sites = bySite(history)

        // A kind of site with no name ("my bank", "the booking site"): only the user's own history can say which.
        if (it0.brand.isEmpty() && it0.kind != null) {
            val kind = KINDS.first { it.name == it0.kind }
            val hits = sites.filter { u -> u.all.any { v -> kind.match.containsMatchIn(host(v.url)) || kind.match.containsMatchIn(v.title) } }
            if (hits.isEmpty()) return Resolution(null, it0.subpage, "no ${kind.noun} in history",
                ask = "Ask the user which ${kind.noun} they mean (its name is enough). Blaze has no ${kind.noun} in their history, and it doesn't guess one.")
            val top = hits[0]
            if (hits.size > 1 && hits[1].count * 2 > top.count)
                return Resolution(null, it0.subpage, "several ${kind.noun}s in history",
                    ask = "Ask the user which ${kind.noun}: " + hits.take(3).joinToString(" or ") { "${it.site} (${it.best.title.ifBlank { it.site }.take(40)})" } + ".")
            val v = pick(top, it0.subpage)
            return Resolution(v.url, if (onSubpage(it0.subpage, v.url, false)) null else it0.subpage,
                "the ${kind.noun} from the user's history: ${top.site}" + (v.title.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""), fromHistory = true)
        }
        if (it0.brand.isEmpty()) return Resolution(null, it0.subpage, "no site name", ask = "Ask the user which site they mean (a name is enough).")

        // A named site the user used before.
        val known = sites.filter { u -> u.all.any { v -> official(v.url, it0.brand) } }
        if (known.isNotEmpty()) {
            val top = known[0]; val v = pick(top, it0.subpage)
            val extra = it0.brand.filterNot { b -> official("https://$b.x/", listOf(b)) && hostLabels(host(v.url)).contains(norm(b)) }
            if (it0.subpage == null || pathIs(it0.subpage, v.url)) {
                // Extra words ("the-internet dropdown") must be in the path, or we search the site for them.
                if (extra.none { e -> !hostLabels(host(v.url)).contains(norm(e)) } || extra.all { e -> v.url.contains(norm(e), true) || hostLabels(host(v.url)).contains(norm(e)) })
                    return Resolution(v.url, null, "a site the user used before: ${host(v.url)}" + (v.title.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""), fromHistory = true)
            }
        }

        // Search, skipping ads and look-alikes, preferring the official domain (and sites the user used before).
        val results = try { search((it0.brand + listOfNotNull(sub(it0.subpage)?.searchWord)).joinToString(" ")) } catch (_: Exception) { emptyList() }
        val usedSites = sites.map { it.site }.toSet()
        var skipped = 0
        data class Scored(val r: Result, val score: Int, val off: Boolean)
        val scored = results.mapIndexedNotNull { i, r ->
            if (r.ad || isAd(r.title, r.url) || !r.url.startsWith("http") || lookalike(r.url, it0.brand) || spam(r.url, it0.brand)) { skipped++; return@mapIndexedNotNull null }
            val off = official(r.url, it0.brand)
            var s = 20 - 2 * i
            if (off) s += 50
            if (info(r.url, it0.brand)) s -= 40
            if (SiteDomain.registrable(host(r.url)) in usedSites) s += 30
            if (it0.subpage != null && pathIs(it0.subpage, r.url)) s += 25
            val labels = hostLabels(host(r.url))
            it0.brand.filter { norm(it) !in labels }.forEach { e -> if (r.url.substringAfter(host(r.url)).contains(norm(e), true)) s += 15 }
            if (it0.brand.any { r.title.contains(it, true) }) s += 5
            if (r.url.startsWith("https://")) s += 2
            Scored(r, s, off)
        }.sortedByDescending { it.score }
        val best = scored.firstOrNull()
        if (best == null) {
            known.firstOrNull()?.let { u -> val v = pick(u, it0.subpage)
                return Resolution(v.url, if (onSubpage(it0.subpage, v.url, false)) null else it0.subpage, "a site the user used before: ${u.site}", fromHistory = true, searched = true) }
            it0.brand.firstOrNull { it.contains('.') && isUrlish(it) }?.let { d ->
                return Resolution("https://${d.removePrefix("www.")}/", it0.subpage, "the domain the user named (search found nothing)") }
            return Resolution(null, it0.subpage, "search found nothing", ask = "Ask the user for the site's name or address: a search for \u201c$q\u201d found nothing usable.", searched = true)
        }
        val trusted = best.off || SiteDomain.registrable(host(best.r.url)) in usedSites
        if (!trusted) {
            val alts = scored.take(3).map { host(it.r.url) }.distinct()
            if (scored.size > 1 && scored[1].score >= best.score - 10 && alts.size > 1)
                return Resolution(null, it0.subpage, "no official site found", ask = "Ask the user which site they mean: ${alts.joinToString(" or ")}.", searched = true)
        }
        // The sub-page: this result if it is one, else another result on the same site that is.
        var url = best.r.url
        val site = SiteDomain.registrable(host(url))
        if (it0.subpage != null && !pathIs(it0.subpage, url))
            scored.firstOrNull { SiteDomain.registrable(host(it.r.url)) == site && pathIs(it0.subpage, it.r.url) }?.let { url = it.r.url }
        val left = if (it0.subpage != null && !pathIs(it0.subpage, url)) it0.subpage else null
        val why = (if (best.off) "official site " else if (trusted) "a site the user used before " else "best search result ") + host(url) +
            (if (skipped > 0) " (skipped $skipped ad / look-alike / SEO result${if (skipped > 1) "s" else ""})" else "") +
            (it0.subpage?.let { s -> if (left == null) ", $s page" else ", $s page still to reach from there" } ?: "")
        return Resolution(url, left, why, searched = true)
    }
}
