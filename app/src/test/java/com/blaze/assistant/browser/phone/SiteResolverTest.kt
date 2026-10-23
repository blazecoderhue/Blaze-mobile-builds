package com.blaze.assistant.browser.phone

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.19-speed, user rule 8: vague names resolve to the right page. Strict 5/5: every case runs five variants (other
 * wordings, and the search results rotated so the right one sits at a different rank with an ad on top) and all five
 * must give exactly the expected page, or the expected question when only the user can say. Search results are
 * fixtures in the exact text format [com.blaze.assistant.agent.Web.search] returns (parsed by [SiteResolver.parseSearch]).
 */
class SiteResolverTest {
    private fun results(vararg r: Pair<String, String>) = r.toList()

    private val SEARCH: Map<String, List<Pair<String, String>>> = mapOf(
        "herokuapp" to results(
            "Ad · Heroku: Cloud Application Platform, free tier" to "https://duckduckgo.com/y.js?ad_domain=heroku.com&u3=x",
            "Herokuapp Login Guide 2026, Step by Step" to "https://loginguide-hub.com/herokuapp-login",
            "Heroku | Login" to "https://id.heroku.com/login",
            "The Internet" to "https://the-internet.herokuapp.com/login",
            "Heroku Apps, herokuapp.com" to "https://www.herokuapp.com/"),
        "the-internet" to results(
            "The Internet" to "https://the-internet.herokuapp.com/login",
            "the-internet login tutorial" to "https://the-internet-tutorials.com/login",
            "Login - Wikipedia" to "https://en.wikipedia.org/wiki/Login",
            "The Internet" to "https://the-internet.herokuapp.com/",
            "Ad · Faster internet today" to "https://www.bing.com/aclick?ld=abc&u=x",
            "The Internet: Dropdown List" to "https://the-internet.herokuapp.com/dropdown",
            "Dropdown tutorial, the-internet-dropdown-tutorial.com" to "https://the-internet-dropdown-tutorial.com/"),
        "github" to results(
            "Ad · GitHub Enterprise trial" to "https://github.com/enterprise?ad_provider=bing",
            "GitHub Login, Sign in to GitHub" to "https://github-login.net/",
            "How to Log in to GitHub: 7 Steps - wikiHow" to "https://www.wikihow.com/Log-in-to-GitHub",
            "Sign in to GitHub · GitHub" to "https://github.com/login",
            "GitHub: Let's build from here" to "https://github.com/"),
        "booking.com" to results(
            "Booking.com | Official site | The best hotels" to "https://www.booking.com/",
            "Booking.com login help, sign in issues" to "https://booking.com-login.help/",
            "Sign in or create an account | Booking.com" to "https://account.booking.com/sign-in",
            "Ad · Cheap hotels, compare prices" to "https://duckduckgo.com/y.js?ad_domain=trivago.com",
            "Booking.com - Wikipedia" to "https://en.wikipedia.org/wiki/Booking.com"),
        "netflix" to results(
            "Ad · Netflix, Watch TV Shows Online, Free Trial" to "https://duckduckgo.com/y.js?ad_domain=netflix.com",
            "Netflix Login: Step by Step Guide" to "https://www.loginsnet.com/netflix-login",
            "Can't log in to Netflix? : r/netflix" to "https://www.reddit.com/r/netflix/comments/abc/cant_log_in/",
            "Netflix - Sign In" to "https://www.netflix.com/login",
            "Netflix Help Center" to "https://help.netflix.com/en/node/412"),
        "amazon" to results(
            "Amazon.com: Online Shopping for Electronics" to "https://www.amazon.com/",
            "Amazon Deals Today, 70% off" to "https://amazon-deals-today.shop/",
            "Your Orders - Amazon.com" to "https://www.amazon.com/gp/css/order-history",
            "How to Find Your Amazon Order History - wikiHow" to "https://www.wikihow.com/Find-Your-Amazon-Order-History",
            "Amazon.com Sign-In" to "https://www.amazon.com/ap/signin"),
        "reddit" to results(
            "Reddit - Dive into anything" to "https://www.reddit.com/",
            "Sign up - Reddit" to "https://www.reddit.com/register/",
            "How to Make a Reddit Account (2026)" to "https://www.youtube.com/watch?v=abc",
            "Reddit sign up free, reddit-signup.org" to "https://reddit-signup.org/",
            "Ad · Join the conversation" to "https://duckduckgo.com/y.js?ad_domain=quora.com"),
        "paypal" to results(
            "Ad · PayPal Business" to "https://duckduckgo.com/y.js?ad_domain=paypal.com",
            "PayPal Help Center, Contact" to "https://paypal-help-center.support/",
            "PayPal Login Guide" to "https://www.loginsnet.com/paypal",
            "PayPal: Pay, Send Money & Accept Payments" to "https://www.paypal.com/",
            "PayPal - Wikipedia" to "https://en.wikipedia.org/wiki/PayPal"),
        "acme" to results(
            "Acme Rooms | Book direct" to "https://acme.test/",
            "Acme Rooms reviews, acme-rooms-reviews.com" to "https://acme-rooms-reviews.com/"),
    )

    /** The search fixture for [q], as Web.search prints it: rotated by [variant] with an ad put first. */
    private fun searchText(q: String, variant: Int): String {
        val key = SEARCH.keys.sortedByDescending { it.length }.firstOrNull { q.lowercase().contains(it) } ?: return "No results"
        val list = SEARCH.getValue(key)
        val rot = list.drop(variant % list.size) + list.take(variant % list.size)
        val withAd = listOf("Ad · Sponsored result" to "https://duckduckgo.com/y.js?ad_domain=example.com&u3=$variant") + rot
        return withAd.mapIndexed { i, (t, u) -> "${i + 1}. $t\n   $u\n   snippet for $t\n" }.joinToString("")
    }

    private val HISTORY = listOf(
        SiteResolver.Visit("https://automationintesting.online/", "Restful-booker-platform demo", 4, 1_000),
        SiteResolver.Visit("https://github.com/", "GitHub", 10, 2_000),
        SiteResolver.Visit("https://the-internet.herokuapp.com/login", "The Internet", 3, 1_500),
        SiteResolver.Visit("https://mail.google.com/mail/u/0/", "Inbox - Gmail", 20, 3_000))
    private val BANK = SiteResolver.Visit("https://online.zanaco.co.zm/login", "Zanaco Internet Banking", 7, 500)
    private val GITHUB_LOGIN = SiteResolver.Visit("https://github.com/login", "Sign in to GitHub", 5, 2_500)

    private class Case(val name: String, val variants: List<String>, val expect: String?, val history: List<SiteResolver.Visit> = emptyList(),
                       val noSearch: Boolean = false, val askHas: String? = null, val subpageLeft: String? = null)

    private val CASES = listOf(
        Case("herokuapp login page", listOf("herokuapp login page", "the herokuapp login", "log into herokuapp", "herokuapp sign in page", "open the herokuapp login form"),
            "https://the-internet.herokuapp.com/login"),
        Case("the-internet login", listOf("the-internet login", "the-internet login page", "log into the-internet", "the-internet sign in", "open the-internet login"),
            "https://the-internet.herokuapp.com/login"),
        Case("the booking site (history)", listOf("the booking site", "booking site", "that booking website", "the site I book rooms on", "open the booking site"),
            "https://automationintesting.online/", HISTORY, noSearch = true),
        Case("my bank (history)", listOf("my bank", "my bank's website", "open my bank", "my banking site", "log into my bank"),
            "https://online.zanaco.co.zm/login", HISTORY + BANK, noSearch = true),
        Case("my bank, none known: ask", listOf("my bank", "my bank's website", "open my bank", "my banking site", "log into my bank"),
            null, HISTORY, noSearch = true, askHas = "bank"),
        Case("github login (look-alike, wikiHow, ad)", listOf("github login", "github sign in", "log into github", "the github login page", "GitHub signin"),
            "https://github.com/login"),
        Case("booking.com sign in", listOf("booking.com sign in", "booking.com login", "sign in to booking.com", "booking.com log in page", "Booking.com signin"),
            "https://account.booking.com/sign-in"),
        Case("netflix login (SEO spam, reddit, ad)", listOf("netflix login", "netflix sign in", "log into netflix", "the netflix login page", "Netflix signin"),
            "https://www.netflix.com/login"),
        Case("amazon orders", listOf("amazon orders", "my amazon orders", "amazon order history", "amazon your orders page", "open my amazon orders"),
            "https://www.amazon.com/gp/css/order-history"),
        Case("reddit sign up", listOf("reddit sign up", "reddit register", "create a reddit account", "reddit signup page", "join reddit"),
            "https://www.reddit.com/register/"),
        Case("the-internet dropdown page", listOf("the-internet dropdown page", "the-internet dropdown", "open the-internet dropdown", "dropdown page on the-internet", "the-internet's dropdown page"),
            "https://the-internet.herokuapp.com/dropdown"),
        Case("paypal (look-alike help centre)", listOf("paypal", "the paypal site", "open paypal", "paypal website", "PayPal homepage"),
            "https://www.paypal.com/"),
        Case("an address is opened as it is", listOf("the-internet.herokuapp.com/login", "https://the-internet.herokuapp.com/login", " the-internet.herokuapp.com/login",
            "the-internet.herokuapp.com/login ", "https://the-internet.herokuapp.com/login "), "https://the-internet.herokuapp.com/login", noSearch = true),
        Case("github login, used before", listOf("github login", "github sign in", "log into github", "the github login page", "GitHub signin"),
            "https://github.com/login", HISTORY + GITHUB_LOGIN, noSearch = true),
        Case("acme sign in: home, then the page-level login step", listOf("acme sign in", "acme login", "log into acme", "the acme login page", "Acme signin"),
            "https://acme.test/", subpageLeft = "login"),
    )

    private fun resolve(q: String, c: Case, variant: Int): SiteResolver.Resolution = runBlocking {
        SiteResolver.resolve(q, c.history) { query ->
            if (c.noSearch) throw AssertionError("searched for \u201c$query\u201d although the answer was known")
            SiteResolver.parseSearch(searchText(query, variant))
        }
    }

    @Test fun vagueNamesResolve_eachCase5of5() {
        assertTrue("at least 10 cases", CASES.size >= 10)
        val fails = ArrayList<String>()
        CASES.forEach { c ->
            assertEquals(c.name, 5, c.variants.size)
            var ok = 0
            c.variants.forEachIndexed { i, q ->
                val r = resolve(q, c, i)
                val good = if (c.expect == null) r.url == null && r.ask != null && r.ask!!.contains(c.askHas.orEmpty(), true)
                    else r.url == c.expect && r.subpage == c.subpageLeft
                if (good) ok++ else fails += "${c.name} / \u201c$q\u201d (variant $i) \u2192 url=${r.url} subpage=${r.subpage} why=${r.why} ask=${r.ask}"
            }
            println("RESOLVER case=\u201c${c.name}\u201d $ok/5 \u2192 ${c.expect ?: "asks the user"}")
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun parseSearchReadsWebSearchOutput() {
        val r = SiteResolver.parseSearch(searchText("github login", 0))
        assertEquals(6, r.size)
        assertTrue(r[0].ad); assertTrue(r[1].ad)
        assertEquals("https://github-login.net/", r[2].url)
        assertFalse(r[4].ad)
    }

    @Test fun spamLookalikeAndOfficial() {
        val b = listOf("github")
        assertTrue(SiteResolver.official("https://github.com/login", b))
        assertTrue(SiteResolver.lookalike("https://github-login.net/", b))
        assertFalse(SiteResolver.lookalike("https://github.com/", b))
        assertTrue(SiteResolver.spam("https://www.loginsnet.com/github", b))
        assertTrue(SiteResolver.official("https://the-internet.herokuapp.com/", listOf("herokuapp")))
        assertTrue(SiteResolver.official("https://account.booking.com/sign-in", listOf("booking.com")))
        assertFalse(SiteResolver.official("https://booking.com-login.help/", listOf("booking.com")))
        assertTrue(SiteResolver.isAd("Ad · x", "https://x.com/"))
        assertTrue(SiteResolver.isAd("x", "https://duckduckgo.com/y.js?ad_domain=a.com"))
    }

    @Test fun visitsAreKeptPerUrlNewestFirst() {
        var j: String? = null
        j = SiteResolver.addVisit(j, "https://a.test/x#frag", "A", 1)
        j = SiteResolver.addVisit(j, "https://b.test/", "B", 2)
        j = SiteResolver.addVisit(j, "https://a.test/x", "", 3)
        val v = SiteResolver.decodeVisits(j)
        assertEquals(listOf("https://a.test/x", "https://b.test/"), v.map { it.url })
        assertEquals(2, v[0].count); assertEquals("A", v[0].title)
        assertEquals(SiteResolver.addVisit(null, "javascript:alert(1)", "x", 1), "[]")
    }

    // ------------------------------------------------------------------ the phone_open flow (page-level sub-page step)
    /** A site whose home page links to its login page (the search has no deep link). */
    class AcmeSite(private val res: suspend (String) -> SiteResolver.Resolution) : PhoneDriver {
        var page = "none"; val opened = mutableListOf<String>(); val clicked = mutableListOf<String>()
        override fun hasPage() = page != "none"
        override fun mode() = PhonePolicy.Mode.AGENT
        override fun site() = "acme.test"
        override fun step(what: String) { assertFalse("no raw ids in step lines: $what", Regex("\\be\\d+\\b").containsMatchIn(what)) }
        override suspend fun resolve(query: String) = res(query)
        override suspend fun open(url: String): String { opened += url; page = if (url.contains("/login")) "login" else "home"; return "Opened on Blaze Phone.\n" + PhonePolicy.render(snapshot()!!) }
        override suspend fun snapshot(): PhonePolicy.Snapshot? = when (page) {
            "home" -> PhonePolicy.Snapshot("Acme Rooms", "https://acme.test/", "Welcome to Acme Rooms",
                listOf(PhonePolicy.Node("e1", "link", "Rooms", ""), PhonePolicy.Node("e2", "link", "Sign up", ""), PhonePolicy.Node("e3", "link", "Log in", "")), false)
            "login" -> PhonePolicy.Snapshot("Log in", "https://acme.test/account/login", "Log in to Acme",
                listOf(PhonePolicy.Node("e4", "textbox", "Email", "", type = "email"), PhonePolicy.Node("e5", "textbox", "Password", "", type = "password", sensitive = true)), true)
            else -> null
        }
        override suspend fun locate(target: String, field: Boolean) = snapshot()?.nodes?.firstOrNull { it.id == target }?.let {
            PhoneDriver.Target(it.id, 10, 10, 10, 10, it.name, it.role, "a", it.type, false, false, false, false) }
        override suspend fun focusInfo() = PhoneDriver.FocusInfo("", false, false, false)
        override suspend fun click(t: PhoneDriver.Target): PhoneDriver.ActResult { clicked += t.label; if (t.id == "e3") page = "login"; return PhoneDriver.ActResult(true, "Clicked \u201c${t.label}\u201d.") }
        override suspend fun type(t: PhoneDriver.Target, text: String, clear: Boolean, submit: Boolean) = PhoneDriver.ActResult(false, "")
        override suspend fun select(t: PhoneDriver.Target, option: String) = PhoneDriver.ActResult(false, "")
        override suspend fun key(k: String) {}
        override suspend fun scroll(down: Boolean) = PhoneDriver.ActResult(true, "")
        override suspend fun navigate(what: String) = PhoneDriver.ActResult(true, "")
        override suspend fun fillLogin(user: String?) = PhoneDriver.ActResult(false, "")
        override suspend fun screenshot(): String? = null
        override suspend fun waitFor(selector: String?, text: String?, gone: Boolean, ms: Long) = ""
        override suspend fun handoff(reason: String) = ""
        override suspend fun awaitAgentTurn() = true
        override fun status() = ""
    }

    @Test fun phoneOpenByName_goesToTheSubpage_5of5() {
        val c = CASES.last()
        c.variants.forEachIndexed { i, q ->
            val d = AcmeSite { query -> SiteResolver.resolve(query, emptyList()) { s -> SiteResolver.parseSearch(searchText(s, i)) } }
            val out = runBlocking { PhoneBrowserTools.run(d, "phone_open", JSONObject().put("url", q), mutableListOf()) { _, _, _ -> true } }
            assertEquals(q, listOf("https://acme.test/"), d.opened)
            assertEquals(q, listOf("Log in"), d.clicked)
            assertEquals(q, "login", d.page)
            assertTrue(q, out.contains("Went to the login page") && out.contains("Password"))
        }
    }

    @Test fun phoneOpenByName_asksInsteadOfGuessing_5of5() {
        listOf("my bank", "my bank's website", "open my bank", "my banking site", "log into my bank").forEach { q ->
            val d = AcmeSite { query -> SiteResolver.resolve(query, HISTORY) { throw AssertionError("no search for a kind of site") } }
            val out = runBlocking { PhoneBrowserTools.run(d, "phone_open", JSONObject().put("url", q), mutableListOf()) { _, _, _ -> true } }
            assertTrue(out, out.startsWith("Couldn't tell which site") && out.contains("Ask the user which bank"))
            assertTrue(d.opened.isEmpty())
        }
    }

    @Test fun anAddressSkipsTheResolver_5of5() {
        repeat(5) {
            val d = AcmeSite { throw AssertionError("an address is opened as it is") }
            runBlocking { PhoneBrowserTools.run(d, "phone_open", JSONObject().put("url", "acme.test/account/login"), mutableListOf()) { _, _, _ -> true } }
            assertEquals(listOf("acme.test/account/login"), d.opened)
        }
    }

    @Test fun intentWords() {
        assertEquals(SiteResolver.Intent(listOf("reddit"), "signup", null), SiteResolver.intent("create a reddit account"))
        assertEquals(SiteResolver.Intent(emptyList(), null, "booking"), SiteResolver.intent("the booking site"))
        assertEquals(SiteResolver.Intent(listOf("booking.com"), "login", "booking"), SiteResolver.intent("sign in to booking.com"))
        assertNull(SiteResolver.intent("paypal").subpage)
        assertNotNull(SiteResolver.intent("log into my bank").kind)
    }
}
