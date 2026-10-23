package com.blaze.assistant.browser.phone

import com.blaze.assistant.browser.phone.PhonePolicy.Gate
import com.blaze.assistant.browser.phone.PhonePolicy.Mode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.45.19 Blaze Phone browser: the pure rules (PhonePolicy) the engine and the tools follow. */
class PhoneBrowserTest {
    // ------------------------------------------------------------------ sessions persist per site
    @Test fun oneProfilePerSiteAcrossSubdomainsAndRuns() {
        val a = PhonePolicy.sessionFor("https://accounts.example.com/login", true)
        val b = PhonePolicy.sessionFor("https://www.example.com/book?x=1", true)
        assertEquals("example.com", a.site)
        assertEquals(a.profile, b.profile)                       // same site → same cookies (sign-in kept)
        assertEquals("blaze-phone-example_com", a.profile)
        assertTrue(a.isolated)
        // Same input, same name, every run: the session survives restarts.
        assertEquals(a, PhonePolicy.sessionFor("https://accounts.example.com/login", true))
        assertNotEquals(a.profile, PhonePolicy.sessionFor("https://opentable.co.uk/", true).profile)
        assertEquals("blaze-phone-opentable_co_uk", PhonePolicy.sessionFor("https://www.opentable.co.uk/r/x", true).profile)
    }

    @Test fun profilesAreApartFromTheUsersBrowser() {
        val s = PhonePolicy.sessionFor("https://example.com", true)
        assertTrue(s.profile!!.startsWith(PhonePolicy.PROFILE_PREFIX))
        assertNotEquals("blaze-private", s.profile)   // BrowserEngine's private-tab profile
        assertNotEquals("blazeos", s.profile)         // BlazeOS's
        assertNotEquals("Default", s.profile)
        assertTrue(Regex("^[a-z0-9_-]+$").matches(s.profile!!))
        assertTrue(PhonePolicy.profileName("x".repeat(200)).length <= PhonePolicy.PROFILE_PREFIX.length + 60)
        assertEquals(PhonePolicy.SHARED_PROFILE, PhonePolicy.profileName(""))
    }

    @Test fun withoutProfilesOneSharedPersistentSession() {
        val s = PhonePolicy.sessionFor("https://example.com", false)
        assertNull(s.profile); assertFalse(s.isolated)
        assertFalse(PhonePolicy.needsNewWebView(s, "https://other.org", false))
    }

    @Test fun anotherSiteGetsItsOwnWebViewTheSameSiteReusesIt() {
        val cur = PhonePolicy.sessionFor("https://example.com", true)
        assertTrue(PhonePolicy.needsNewWebView(null, "https://example.com", true))
        assertFalse(PhonePolicy.needsNewWebView(cur, "https://shop.example.com/cart", true))
        assertTrue(PhonePolicy.needsNewWebView(cur, "https://other.org", true))
        assertFalse(PhonePolicy.needsNewWebView(cur, "about:blank", true))
    }

    @Test fun signedInSitesListForSignOut() {
        var s: String? = null
        s = PhonePolicy.Sites.add(s, "example.com"); s = PhonePolicy.Sites.add(s, "opentable.com"); s = PhonePolicy.Sites.add(s, "example.com")
        assertEquals(listOf("example.com", "opentable.com"), PhonePolicy.Sites.decode(s))
        assertEquals(listOf("opentable.com"), PhonePolicy.Sites.decode(PhonePolicy.Sites.remove(s, "example.com")))
        assertEquals(emptyList<String>(), PhonePolicy.Sites.decode("not json"))
        assertEquals(PhonePolicy.Sites.decode(s), PhonePolicy.Sites.decode(PhonePolicy.Sites.add(s, "")))
    }

    // ------------------------------------------------------------------ DOM snapshot masking
    private fun node(id: String, role: String, name: String, value: String, type: String = "", extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("id", id).put("role", role).put("name", name).put("value", value).put("type", type).apply(extra)

    private fun snap(vararg n: JSONObject, text: String = "Sign in", pw: Boolean = false) =
        JSONObject().put("title", "Demo").put("url", "https://demo.test/login").put("text", text).put("pw", pw).put("nodes", JSONArray(n.toList()))

    @Test fun passwordsCodesAndCardValuesNeverReachTheAgent() {
        val o = snap(
            node("e1", "textbox", "Email", "ana@example.com", "email"),
            node("e2", "textbox", "Password", "hunter2!", "password"),
            node("e3", "textbox", "One-time code", "123456", "text") { put("key", "otp") },
            node("e4", "textbox", "Card number", "4242424242424242", "text") { put("ac", "cc-number") },
            node("e5", "textbox", "Promo", "SAVE10", "text") { put("marked", true) },
            node("e6", "textbox", "Note", "window seat", "text"), pw = true)
        val s = PhonePolicy.parseSnapshot(o)
        val text = PhonePolicy.render(s)
        listOf("hunter2!", "123456", "4242424242424242", "SAVE10").forEach { assertFalse("$it leaked", text.contains(it)) }
        assertTrue(text.contains("ana@example.com")); assertTrue(text.contains("window seat"))
        assertEquals(PhonePolicy.HIDDEN, s.nodes[1].value)
        assertTrue(s.nodes[3].card); assertTrue(text.contains("(card field: the user fills it)"))
        assertTrue(s.passwordVisible)
    }

    @Test fun elementIdsAndRolesAreKept() {
        val s = PhonePolicy.parseSnapshot(snap(node("e7", "button", "Book now", ""), node("e9", "link", "Help", "") { put("href", "https://demo.test/help") }))
        val t = PhonePolicy.render(s)
        assertTrue(t, t.contains("[e7] button \"Book now\""))
        assertTrue(t.contains("[e9] link \"Help\" \u2192 https://demo.test/help"))
    }

    @Test fun scrubRunsOnEveryString() {
        val s = PhonePolicy.parseSnapshot(snap(node("e1", "textbox", "Token", "sk-live-SECRET"), text = "your key sk-live-SECRET")) { it.replace("sk-live-SECRET", "[hidden]") }
        assertFalse(PhonePolicy.render(s).contains("sk-live-SECRET"))
    }

    // ------------------------------------------------------------------ no false done
    @Test fun confirmationWithReferenceIsVerified() {
        val v = PhonePolicy.verify("Thank you! Your reservation is confirmed.\nConfirmation number: TBL-48213\nTable for 2 at 19:30")
        assertTrue(v.confirmed); assertEquals("TBL-48213", v.ref)
        assertTrue(v.line().startsWith("VERIFIED:"))
    }

    @Test fun explicitConfirmationTextIsVerifiedWithoutANumber() {
        assertTrue(PhonePolicy.verify("You're all booked! We've sent the details to your email.").confirmed)
        assertTrue(PhonePolicy.verify("Thanks for your order. It's on its way.").confirmed)
    }

    @Test fun formsErrorsAndPendingPagesAreNotDone() {
        // An order form has "order number" words but proves nothing.
        assertFalse(PhonePolicy.verify("Track your order. Order number: ________ Email: ______").confirmed)
        // Error wins over confirmation words.
        val e = PhonePolicy.verify("Your booking could not be confirmed. Payment declined, please try again.")
        assertFalse(e.confirmed); assertTrue(e.blocker != null); assertTrue(e.line().startsWith("NOT VERIFIED"))
        assertFalse(PhonePolicy.verify("Review your booking. Confirm booking").confirmed)
        assertFalse(PhonePolicy.verify("Booking pending confirmation from the restaurant").confirmed)
        val none = PhonePolicy.verify("Choose a time")
        assertTrue(none.line().contains("Don't say it's done"))
    }

    @Test fun signedInIsReadFromTheAccountControlsNotABooking() {
        val nodes = listOf(PhonePolicy.Node("e1", "button", "Sign out", ""), PhonePolicy.Node("e2", "link", "My bookings", ""))
        val v = PhonePolicy.verify("Welcome back, Ana", nodes = nodes)
        assertTrue(v.loggedIn); assertFalse(v.confirmed); assertTrue(v.line().startsWith("SIGNED IN"))
        assertFalse(PhonePolicy.verify("Sign in", nodes = listOf(PhonePolicy.Node("e1", "button", "Sign in", "")), passwordVisible = true).loggedIn)
    }

    @Test fun everyReadCarriesTheVerificationLine() {
        val r = PhonePolicy.render(PhonePolicy.parseSnapshot(snap(text = "Choose a date")))
        assertTrue(r.lines()[1].startsWith("NOT VERIFIED"))
    }

    // ------------------------------------------------------------------ confirm gate on submit / pay
    @Test fun payBookSendSubmitAskFirst() {
        listOf("Book now", "Pay \$42.00", "Place order", "Confirm booking", "Complete purchase", "Reserve", "Submit", "Send message", "Delete account", "Checkout").forEach {
            assertEquals(it, Gate.CONFIRM, PhonePolicy.gate("click", it))
        }
        listOf("Next", "Search", "19:30", "Show more", "Menu", "Sign in", "Continue to date").forEach { assertEquals(it, Gate.PASS, PhonePolicy.gate("click", it)) }
    }

    @Test fun linksThatOnlyOpenAPageDontAskButtonsDo() {
        val link = PhonePolicy.Node("e4", "link", "Book a table", "")
        assertEquals(Gate.PASS, PhonePolicy.gate("click", "Book a table", link))
        assertEquals(Gate.CONFIRM, PhonePolicy.gate("click", "Book a table", PhonePolicy.Node("e4", "button", "Book a table", "")))
        assertEquals(Gate.CONFIRM, PhonePolicy.gate("click", "Pay now", PhonePolicy.Node("e5", "link", "Pay now", "")))
        assertEquals(Gate.CONFIRM, PhonePolicy.gate("click", "Next", PhonePolicy.Node("e6", "button", "Next", "", "submit"), pageHasCard = true))
    }

    @Test fun cardFieldsAreNeverTypedByBlaze() {
        val card = PhonePolicy.Node("e4", "textbox", "Card number", "", "text", card = true, sensitive = true)
        assertEquals(Gate.REFUSE, PhonePolicy.gate("type", "Card number", card))
        assertEquals(Gate.PASS, PhonePolicy.gate("type", "Email", PhonePolicy.Node("e1", "textbox", "Email", "")))
    }

    @Test fun enterThatSubmitsAPaymentOrBookingAsks() {
        assertEquals(Gate.CONFIRM, PhonePolicy.gate("key", "enter", null, pageHasCard = true))
        assertEquals(Gate.CONFIRM, PhonePolicy.gate("key", "enter", null, isSubmit = true))
        assertEquals(Gate.PASS, PhonePolicy.gate("key", "enter"))
        assertEquals(Gate.PASS, PhonePolicy.gate("key", "tab", null, pageHasCard = true))
        assertEquals(Gate.CONFIRM, PhonePolicy.gate("type", "Pay now", PhonePolicy.Node("e1", "textbox", "Name", ""), isSubmit = true))
        assertEquals(Gate.PASS, PhonePolicy.gate("scroll", "Pay"))
        assertEquals(Gate.PASS, PhonePolicy.gate("navigate", "Checkout"))
    }

    // ------------------------------------------------------------------ idle release
    @Test fun releasedAfterFiveIdleMinutesNeverWhileTheUserDrives() {
        val t0 = 1_000_000L
        assertFalse(PhonePolicy.shouldRelease(Mode.AGENT, t0, t0 + PhonePolicy.IDLE_MS - 1))
        assertTrue(PhonePolicy.shouldRelease(Mode.AGENT, t0, t0 + PhonePolicy.IDLE_MS))
        assertFalse(PhonePolicy.shouldRelease(Mode.USER, t0, t0 + 60 * 60_000L))
        assertFalse(PhonePolicy.shouldRelease(Mode.WAITING, t0, t0 + 60 * 60_000L))
        assertFalse(PhonePolicy.shouldRelease(Mode.OFF, t0, t0 + 60 * 60_000L))
        assertEquals(5 * 60_000L, PhonePolicy.IDLE_MS)
    }

    // ------------------------------------------------------------------ low-RAM mode, frames
    @Test fun lowRamModeUnderFourGigabytes() {
        val gib = 1L shl 30
        assertTrue(PhonePolicy.config((2.8 * gib).toLong(), false).lowRam)    // a "3 GB" phone
        assertFalse(PhonePolicy.config((3.7 * gib).toLong(), false).lowRam)   // a "4 GB" phone
        assertFalse(PhonePolicy.config(8 * gib, false).lowRam)
        assertTrue(PhonePolicy.config(8 * gib, true).lowRam)                  // Android's low-RAM flag
        val low = PhonePolicy.config(2 * gib, false); val hi = PhonePolicy.config(8 * gib, false)
        assertTrue(low.scale < hi.scale); assertTrue(low.activeFps <= hi.activeFps); assertTrue(low.shotMaxWidth < hi.shotMaxWidth)
    }

    @Test fun framesAreThrottledToFivePerSecondAtMost() {
        listOf(PhonePolicy.config(2L shl 30, false), PhonePolicy.config(8L shl 30, false)).forEach { c ->
            assertTrue(PhonePolicy.frameInterval(c, true) >= 200)
            assertTrue(PhonePolicy.frameInterval(c, false) >= PhonePolicy.frameInterval(c, true))
        }
        val g = PhonePolicy.FrameGate()
        assertTrue(g.allow(1000, 200)); assertFalse(g.allow(1100, 200)); assertTrue(g.allow(1200, 200))
        // 1 s of 60 Hz images → at most 5 frames
        val g2 = PhonePolicy.FrameGate(); var n = 0
        for (i in 0 until 60) if (g2.allow(5000L + i * 16, 200)) n++
        assertTrue("$n frames", n <= 5)
    }

    @Test fun theOffscreenDisplayKeepsThePhonesCssWidth() {
        val full = PhonePolicy.displaySize(1080, 2400, 420, 1f)
        val half = PhonePolicy.displaySize(1080, 2400, 420, 0.5f)
        // CSS px = px / (dpi/160): the same page width at any scale.
        assertEquals(full.width * 160.0 / full.dpi, half.width * 160.0 / half.dpi, 2.0)
        assertTrue(half.width.toLong() * half.height * 3 < full.width.toLong() * full.height)   // a quarter of the pixels
    }

    @Test fun waitsAreBounded() {
        assertEquals(10_000L, PhonePolicy.waitMs(null)); assertEquals(500L, PhonePolicy.waitMs(0.0)); assertEquals(30_000L, PhonePolicy.waitMs(600.0))
    }
}
