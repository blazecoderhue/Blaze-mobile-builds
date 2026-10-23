package com.blaze.assistant.browser.phone

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.19 Blaze Phone browser: strict 5/5 JVM sims, one per tool. Each run drives the real tool code
 * ([PhoneBrowserTools.run]) against [DemoSite], a scripted sign-in + table-booking site (login → home → booking form →
 * review → confirmation), and must pass all 5 times. The WebView itself (real taps, the off-screen display, frames) is
 * covered by the device steps in PHONE_BROWSER_STATUS.md; Robolectric's WebView runs no JavaScript.
 */
class PhoneToolSimTest {
    /** A demo booking site behind a sign-in, with a captcha the user must solve on the review page when [captcha]. */
    class DemoSite(private val captcha: Boolean = false, private val userTakesOver: Boolean = true) : PhoneDriver {
        var page = "none"; var user = ""; var pass = ""; var signedIn = false; var party = ""; var time = ""
        var booked = false; var steps = 0; var handoffs = 0; var mode = PhonePolicy.Mode.OFF; var scrolled = 0
        val clicks = mutableListOf<String>()
        override fun hasPage() = page != "none"
        override fun mode() = mode
        override fun site() = "demo-tables.test"
        override fun step(what: String) { steps++ }
        override suspend fun open(url: String): String {
            mode = PhonePolicy.Mode.AGENT
            page = if (url.contains("login") || !signedIn) "login" else "home"
            return "Opened on Blaze Phone.\n" + PhonePolicy.render(snapshot()!!)
        }
        private fun n(id: String, role: String, name: String, value: String = "", type: String = "", card: Boolean = false, sens: Boolean = false) =
            PhonePolicy.Node(id, role, name, value, type, sensitive = sens, card = card, x = 100, y = 200, w = 80, h = 40)
        override suspend fun snapshot(): PhonePolicy.Snapshot? = when (page) {
            "login" -> PhonePolicy.Snapshot("Sign in", "https://demo-tables.test/login", "Sign in to Demo Tables",
                listOf(n("e1", "textbox", "Email", user, "email"), PhonePolicy.mask(n("e2", "textbox", "Password", pass, "password", sens = true)), n("e3", "button", "Sign in")), true)
            "home" -> PhonePolicy.Snapshot("Demo Tables", "https://demo-tables.test/", "Welcome back. Find a table.",
                listOf(n("e4", "link", "Book a table"), n("e5", "button", "Sign out"), n("e6", "link", "My bookings")), false)
            "form" -> PhonePolicy.Snapshot("Book", "https://demo-tables.test/book", "Party size and time" + "\n".repeat(scrolled),
                listOf(n("e7", "combobox", "Party size", party), n("e8", "textbox", "Time", time), n("e9", "button", "Continue")), false)
            "review" -> PhonePolicy.Snapshot("Review", "https://demo-tables.test/review", "Review your booking: $party people at $time." +
                (if (captcha && handoffs == 0) " Please verify you are human." else ""),
                listOf(n("e10", "button", "Confirm booking"), n("e11", "textbox", "Card number", card = true, sens = true)), false)
            "done" -> PhonePolicy.Snapshot("Booked", "https://demo-tables.test/done", "Your reservation is confirmed. Confirmation number: DT-${party}${time.filter { it.isDigit() }}",
                listOf(n("e5", "button", "Sign out")), false, listOf("You're booked"))
            else -> null
        }
        override suspend fun locate(target: String, field: Boolean): PhoneDriver.Target? {
            val s = snapshot() ?: return null
            val node = s.nodes.firstOrNull { it.id == target } ?: s.nodes.firstOrNull { it.name.contains(target, true) }
                ?: if (target == ":focus") s.nodes.firstOrNull { it.role == "textbox" } else null
            return node?.let { PhoneDriver.Target(it.id, it.x, it.y, it.w, it.h, it.name, it.role, "x", it.type, it.card, it.sensitive, it.role == "textbox", false) }
        }
        override suspend fun focusInfo() = PhoneDriver.FocusInfo(if (page == "login") "Sign in" else if (page == "review") "Confirm booking" else "", page == "review", page == "login", true)
        override suspend fun click(t: PhoneDriver.Target): PhoneDriver.ActResult {
            clicks += t.label
            when (t.id) {
                "e3" -> if (user.isNotBlank() && pass == "pw-demo") { signedIn = true; page = "home" }
                "e4" -> page = "form"
                "e9" -> if (party.isNotBlank() && time.isNotBlank()) page = "review"
                "e10" -> if (!captcha || handoffs > 0) { booked = true; page = "done" }
                "e5" -> { signedIn = false; page = "login" }
            }
            return PhoneDriver.ActResult(true, "Clicked \u201c${t.label}\u201d.")
        }
        override suspend fun type(t: PhoneDriver.Target, text: String, clear: Boolean, submit: Boolean): PhoneDriver.ActResult {
            val v = if (text == "{{secret:password}}") "pw-demo" else text
            when (t.id) { "e1" -> user = v; "e2" -> pass = v; "e8" -> time = v }
            if (submit && page == "login" && user.isNotBlank() && pass == "pw-demo") { signedIn = true; page = "home" }
            return PhoneDriver.ActResult(true, if (t.sens) "Typed into \u201c${t.label}\u201d (value hidden)." else "Typed \u201c$text\u201d.")
        }
        override suspend fun select(t: PhoneDriver.Target, option: String): PhoneDriver.ActResult {
            if (t.id != "e7") return PhoneDriver.ActResult(false, "not a dropdown")
            party = option.filter { it.isDigit() }; return PhoneDriver.ActResult(true, "Selected \u201c$option\u201d.")
        }
        override suspend fun key(k: String) { if (k == "enter" && page == "login" && pass == "pw-demo" && user.isNotBlank()) { signedIn = true; page = "home" } else if (k == "back" && page == "form") page = "home" }
        override suspend fun scroll(down: Boolean) = PhoneDriver.ActResult(true, "Scrolled ${if (down) "down" else "up"}.").also { if (down) scrolled++ }
        override suspend fun navigate(what: String) = PhoneDriver.ActResult(true, when (what) { "back" -> { if (page == "form") page = "home"; "Went back." }; else -> "Reloaded." })
        override suspend fun fillLogin(user: String?): PhoneDriver.ActResult { this.user = "ana@demo.test"; pass = "pw-demo"; return PhoneDriver.ActResult(true, "Filled the saved login for ana@demo.test.") }
        override suspend fun screenshot() = if (page == "none") null else "data:image/jpeg;base64,/9j/AAAA"
        override suspend fun waitFor(selector: String?, text: String?, gone: Boolean, ms: Long): String {
            val t = snapshot()?.text.orEmpty()
            val ok = text != null && t.contains(text, true)
            return if (ok != gone) "Condition met: text \u201c$text\u201d." else "Timed out after ${ms / 1000}s waiting for text \u201c$text\u201d."
        }
        override suspend fun handoff(reason: String): String {
            handoffs++
            if (!userTakesOver) return "The user didn't take over within 10 minutes. Stop here and tell them what's waiting on Blaze Phone ($reason)."
            mode = PhonePolicy.Mode.USER   // the user solves it, then Hand back
            mode = PhonePolicy.Mode.AGENT
            return "The user handed back. Fresh read:\n" + PhonePolicy.render(snapshot()!!)
        }
        override suspend fun awaitAgentTurn() = mode != PhonePolicy.Mode.USER
        override fun status() = "Blaze Phone: ${if (mode == PhonePolicy.Mode.OFF) "idle" else "Blaze is driving"}. Page: $page."
    }

    private val shots = mutableListOf<String>()
    private val asked = mutableListOf<String>()
    private var approve = true
    private fun run(d: PhoneDriver, name: String, a: JSONObject = JSONObject()) = runBlocking {
        PhoneBrowserTools.run(d, name, a, shots) { title, _, _ -> asked += title; approve }
    }
    private fun act(d: PhoneDriver, action: String, vararg kv: Pair<String, Any>) =
        run(d, "phone_act", JSONObject().put("action", action).apply { kv.forEach { put(it.first, it.second) } })

    private fun fiveTimes(name: String, body: (Int) -> Unit) {
        var pass = 0
        for (i in 1..5) { shots.clear(); asked.clear(); approve = true; body(i); pass++ }
        assertEquals("$name strict 5/5", 5, pass)
    }

    private fun signIn(d: DemoSite) {
        run(d, "phone_open", JSONObject().put("url", "https://demo-tables.test/login"))
        act(d, "fill_login"); act(d, "click", "target" to "e3")
    }

    @Test fun phone_open_5of5() = fiveTimes("phone_open") {
        val d = DemoSite()
        val r = run(d, "phone_open", JSONObject().put("url", "demo-tables.test/login"))
        assertTrue(r.startsWith("Opened on Blaze Phone."))
        assertTrue(r.contains("[e1] textbox:email \"Email\"")); assertTrue(r.contains("NOT VERIFIED"))
        assertTrue(run(d, "phone_open", JSONObject()).startsWith("Give a URL"))
    }

    @Test fun phone_read_5of5() = fiveTimes("phone_read") {
        val d = DemoSite()
        assertTrue(run(d, "phone_read").startsWith("Blaze Phone has no page open"))
        signIn(d)
        val r = run(d, "phone_read")
        assertTrue(r, r.lines()[1].startsWith("SIGNED IN")); assertTrue(r.contains("[e4] link \"Book a table\""))
        assertFalse(r.contains("pw-demo"))
    }

    @Test fun phone_act_login_and_book_with_confirm_5of5() = fiveTimes("phone_act") { i ->
        val d = DemoSite()
        run(d, "phone_open", JSONObject().put("url", "https://demo-tables.test/login"))
        // Sign in with real typing (the password from the vault placeholder), submit with Enter.
        act(d, "type", "target" to "Email", "text" to "ana@demo.test")
        val pw = act(d, "type", "target" to "e2", "text" to "{{secret:password}}", "submit" to true)
        assertFalse(pw.contains("pw-demo")); assertTrue(d.signedIn)
        act(d, "click", "target" to "Book a table")
        assertTrue(act(d, "select", "target" to "e7", "option" to "${i + 1} people").startsWith("Selected"))
        act(d, "type", "target" to "Time", "text" to "19:3$i")
        act(d, "scroll", "direction" to "down")
        act(d, "click", "target" to "Continue")
        assertEquals("review", d.page)
        // Card numbers are never typed by Blaze.
        assertTrue(act(d, "type", "target" to "e11", "text" to "4242424242424242").contains("card field"))
        // "Confirm booking" asks the user first; a no stops it.
        approve = false
        assertTrue(act(d, "click", "target" to "Confirm booking").contains("did NOT approve"))
        assertFalse(d.booked); assertEquals(1, asked.size)
        approve = true
        val done = act(d, "click", "target" to "e10")
        assertTrue(d.booked); assertEquals(2, asked.size)
        assertTrue(done, done.contains("VERIFIED: the page shows"))
        assertTrue(done.contains("reference DT-${i + 1}193$i"))
    }

    @Test fun phone_act_keys_back_and_unknowns_5of5() = fiveTimes("phone_act keys") {
        val d = DemoSite()
        run(d, "phone_open", JSONObject().put("url", "https://demo-tables.test/login"))
        act(d, "fill_login")
        // Enter on a sign-in form isn't a purchase: no card.
        act(d, "key", "key" to "enter")
        assertTrue(d.signedIn); assertTrue(asked.isEmpty())
        act(d, "click", "target" to "e4"); act(d, "back")
        assertEquals("home", d.page)
        assertTrue(act(d, "key", "key" to "f13").startsWith("Unknown key"))
        assertTrue(act(d, "dance").startsWith("Unknown action"))
        assertTrue(act(d, "click", "target" to "Nope").startsWith("No element"))
    }

    @Test fun phone_screenshot_5of5() = fiveTimes("phone_screenshot") {
        val d = DemoSite()
        assertTrue(run(d, "phone_screenshot").startsWith("Blaze Phone has no page open"))
        run(d, "phone_open", JSONObject().put("url", "https://demo-tables.test/login"))
        val r = run(d, "phone_screenshot")
        assertTrue(r.contains("attached")); assertEquals(1, shots.size); assertTrue(shots[0].startsWith("data:image/jpeg;base64,"))
        assertTrue(r.contains("NOT VERIFIED"))
    }

    @Test fun phone_wait_5of5() = fiveTimes("phone_wait") {
        val d = DemoSite()
        signIn(d)
        assertTrue(run(d, "phone_wait", JSONObject().put("text", "Welcome back")).startsWith("Condition met"))
        assertTrue(run(d, "phone_wait", JSONObject().put("text", "Confirmed").put("timeout", 1)).startsWith("Timed out after 1s"))
        assertTrue(run(d, "phone_wait", JSONObject().put("text", "Sign in to").put("gone", true)).startsWith("Condition met"))
    }

    @Test fun phone_handoff_5of5() = fiveTimes("phone_handoff") {
        val d = DemoSite(captcha = true)
        signIn(d)
        act(d, "click", "target" to "e4"); act(d, "select", "target" to "e7", "option" to "2"); act(d, "type", "target" to "e8", "text" to "20:00"); act(d, "click", "target" to "e9")
        act(d, "click", "target" to "e10")
        assertFalse(d.booked)   // the captcha held it: no false done
        val r = run(d, "phone_handoff", JSONObject().put("reason", "Solve the captcha"))
        assertTrue(r.startsWith("The user handed back. Fresh read:")); assertEquals(1, d.handoffs)
        assertTrue(act(d, "click", "target" to "e10").contains("VERIFIED: the page shows"))
        // Nobody comes: an honest stop.
        val d2 = DemoSite(userTakesOver = false); signIn(d2)
        assertTrue(run(d2, "phone_handoff", JSONObject().put("reason", "Enter your card")).contains("didn't take over"))
    }

    @Test fun phone_status_5of5() = fiveTimes("phone_status") {
        val d = DemoSite()
        assertTrue(run(d, "phone_status").contains("idle"))
        signIn(d)
        assertTrue(run(d, "phone_status").contains("Blaze is driving"))
    }

    @Test fun whileTheUserDrivesBlazeWaits() {
        val d = DemoSite(); signIn(d); d.mode = PhonePolicy.Mode.USER
        assertTrue(act(d, "click", "target" to "e4").contains("in control"))
        assertEquals("home", d.page)
        assertTrue(run(d, "phone_read").contains("in control"))
    }

    @Test fun describeLinesForTheChatSteps() {
        assertEquals("Opening opentable.com on Blaze Phone", PhoneBrowserTools.describe("phone_open", JSONObject().put("url", "https://www.opentable.com/r/x")))
        assertEquals("Asking you to take control", PhoneBrowserTools.describe("phone_handoff", JSONObject()))
        assertEquals("Tapping on Blaze Phone", PhoneBrowserTools.describe("phone_act", JSONObject().put("action", "click").put("target", "e10")))
    }
}
