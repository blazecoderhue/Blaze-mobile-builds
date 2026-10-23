package com.blaze.assistant.browser.phone

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.19-speed: the DOM bridge and the event waits (user rule 5) and the browser-module sweep (rule 9), strict 5/5.
 * The page side of the bridge (PhoneScripts.bridge) is JavaScript: its own checks ran on jsdom (see
 * PHONE_SPEED_STATUS.md); here the Kotlin side ([DomCache], [PhoneEngine]) runs on [SimPage]'s simulated bridge.
 */
class PhoneBridgeTest {
    private fun fiveTimes(what: String, f: (Int) -> Unit) { var ok = 0; repeat(5) { i -> f(i); ok++ }; assertEquals("$what: 5/5", 5, ok) }

    private fun node(id: String, name: String, value: String = "", role: String = "textbox", type: String = "text", sens: Boolean = false) =
        JSONObject().put("id", id).put("role", role).put("name", name).put("value", value).put("type", type).put("sens", sens).put("len", value.length)

    private fun msg(doc: String, seq: Long, full: Boolean, k: String = "TOK", build: JSONObject.() -> Unit = {}) =
        JSONObject().put("k", k).put("doc", doc).put("seq", seq).put("full", full).put("url", "https://a.test/").put("title", "A").put("ready", "complete")
            .put("net", 0).put("sy", 0).put("pw", false).put("card", false).put("focus", JSONObject.NULL).apply(build).toString()

    // ------------------------------------------------------------------ DomCache
    @Test fun domCacheAppliesDiffs_5of5() = fiveTimes("diffs") { i ->
        var now = 0L
        val c = DomCache("TOK") { now }
        val doc = "d$i"
        assertTrue(c.accept(msg(doc, 1, true) {
            put("set", JSONArray().put(node("e1", "Email")).put(node("e2", "Password", "hunter$i", type = "password", sens = true)).put(node("e3", "Go", role = "button")))
            put("order", JSONArray(listOf("e1", "e2", "e3"))); put("text", "Sign in page"); put("pw", true) }))
        assertTrue(c.usable())
        now = 50
        assertTrue(c.accept(msg(doc, 2, false) { put("set", JSONArray().put(node("e1", "Email", "me@x.test"))); put("focus", JSONObject().put("id", "e1").put("submit", "Go").put("inForm", true).put("editable", true)) }))
        assertEquals(50L, c.lastChangeAt)
        assertEquals("e1", c.focusId()); assertEquals(9, c.valueLen("e1"))
        assertEquals("Go", c.focusInfo()!!.submit)
        now = 80
        assertTrue(c.accept(msg(doc, 3, false) { put("del", JSONArray().put("e3")); put("order", JSONArray(listOf("e2", "e1"))) }))
        val s = PhonePolicy.parseSnapshot(c.snapshotJson()!!)
        assertEquals(listOf("e2", "e1"), s.nodes.map { it.id })
        assertEquals(PhonePolicy.HIDDEN, s.nodes[0].value)   // masked again in Kotlin even if the page sent it
        assertEquals("me@x.test", s.nodes[1].value)
        assertEquals(true, c.hasText("sign IN"))
        assertEquals(false, c.hasText("nope"))
        // A message that changes nothing on the page (net only) isn't a content change.
        now = 200
        assertTrue(c.accept(msg(doc, 4, false) { put("net", 1) }))
        assertEquals(80L, c.lastChangeAt); assertEquals(200L, c.activeAt()); assertTrue(c.netBusy(300)); assertFalse(c.netBusy(6_000))
    }

    @Test fun domCacheRejectsForeignStaleAndGaps_5of5() = fiveTimes("rejects") { i ->
        val c = DomCache("TOK") { 0L }
        val doc = "doc$i"
        assertFalse("wrong token", c.accept(msg(doc, 1, true, k = "EVIL")))
        assertFalse("garbage", c.accept("{not json"))
        assertFalse("a diff before any full", c.accept(msg(doc, 1, false)))
        assertTrue(c.needsResync())
        assertTrue(c.accept(msg(doc, 1, true) { put("set", JSONArray().put(node("e1", "A"))); put("order", JSONArray(listOf("e1"))); put("text", "x") }))
        assertFalse(c.needsResync())
        assertFalse("old seq", c.accept(msg(doc, 1, false)))
        assertFalse("gap", c.accept(msg(doc, 3, false)))
        assertTrue(c.needsResync()); assertFalse(c.usable()); assertNull(c.snapshotJson())
        assertTrue("a full resync (no token: it came from our own eval)", c.acceptFull(JSONObject(msg(doc, 4, true) { remove("k"); put("set", JSONArray().put(node("e1", "A"))); put("text", "x") })))
        assertTrue(c.usable())
        // Navigation: the old document's late messages are ignored; the new one starts with a full model.
        c.navStarted()
        assertFalse(c.usable())
        assertFalse("old document", c.accept(msg(doc, 5, false)))
        assertFalse("old document full", c.accept(msg(doc, 6, true)))
        assertTrue(c.accept(msg("new$i", 1, true) { put("text", "new page") }))
        assertEquals(true, c.hasText("new page"))
        // Still loading: not usable (the engine reads the DOM directly).
        assertTrue(c.accept(msg("new$i", 2, false) { put("ready", "loading") }))
        assertFalse(c.usable())
    }

    // ------------------------------------------------------------------ the engine on the simulated bridge
    private fun opened(seed: Long, url: String): Pair<SimPage, PhonePerfTest.SimDriver> {
        val p = SimPage(seed); val d = PhonePerfTest.SimDriver(p)
        runBlocking { d.open(url) }; p.drain(); p.sleeps = 0; p.evals = 0
        return p to d
    }

    @Test fun readsComeFromTheCache_noPageCall_5of5() = fiveTimes("cache read") { i ->
        val (p, d) = opened(100L + i, "https://shop.example/list?page=1")
        val t0 = p.t
        val r = runBlocking { PhoneBrowserTools.run(d, "phone_read", JSONObject(), mutableListOf()) { _, _, _ -> true } }
        assertTrue(r.contains("Page 1 of results") && r.contains("[e1]"))
        assertEquals("no evaluateJavascript for a read", 0, p.evals)
        assertEquals("no time on the page clock", t0, p.t)
    }

    @Test fun everyActionVerified_noFixedSleeps_5of5() {
        val cases = listOf(
            Triple("https://shop.example/list?page=1", """{"action":"click","target":"e250"}""", "Page 2 of results"),
            Triple("https://automationintesting.online/", """{"action":"click","target":"Book this room"}""", "Firstname"),
            Triple("https://the-internet.herokuapp.com/login", """{"action":"type","target":"Username","text":"tomsmith"}""", "Typed"),
            Triple("https://the-internet.herokuapp.com/login", """{"action":"type","target":"Password","text":"pw","submit":true}""", "Your username is invalid"))
        cases.forEach { (url, args, want) ->
            fiveTimes(args) { i ->
                val (p, d) = opened(200L + i, url)
                val r = runBlocking { PhoneBrowserTools.run(d, "phone_act", JSONObject(args), mutableListOf()) { _, _, _ -> true } }
                assertTrue("$args → $r", r.contains(want))
                assertEquals("$args: no fixed sleeps with the bridge", 0, p.sleeps)
            }
        }
    }

    @Test fun aClickThatChangesNothingReturnsAfterTheReactWindow_5of5() = fiveTimes("no-op click") { i ->
        val (p, d) = opened(300L + i, "https://the-internet.herokuapp.com/login")
        val t0 = p.t
        val r = runBlocking { PhoneBrowserTools.run(d, "phone_act", JSONObject().put("action", "click").put("target", "Footer link 5"), mutableListOf()) { _, _, _ -> true } }
        assertTrue(r, r.startsWith("Clicked"))
        val took = p.t - t0
        assertTrue("took $took ms", took <= PhoneEngine.REACT_MS + PhonePolicy.TAP_HOLD_MS + 40)
        assertEquals(0, p.sleeps)
    }

    @Test fun waitForWakesOnTheChange_notOnAPoll_5of5() = fiveTimes("wait") { i ->
        val (p, d) = opened(400L + i, "https://automationintesting.online/")
        p.at(700L + i * 100) { p.doc!!.text = "Booking Successful! " + p.doc!!.text; p.changed("dom") }
        val t0 = p.t
        val r = runBlocking { PhoneBrowserTools.run(d, "phone_wait", JSONObject().put("text", "Booking Successful").put("timeout", 10), mutableListOf()) { _, _, _ -> true } }
        assertTrue(r, r.startsWith("Condition met"))
        val took = p.t - t0
        assertTrue("took $took", took in (700L + i * 100)..(700L + i * 100 + 80))
        assertEquals("answered from the cache", 0, p.evals)
        assertEquals(0, p.sleeps)
    }

    @Test fun aMissedMessageResyncsWithOneCall_5of5() = fiveTimes("resync") { i ->
        val (p, d) = opened(500L + i, "https://the-internet.herokuapp.com/login")
        // The page changes but the message is lost (the cache sees a gap afterwards).
        p.bridge = false; p.doc!!.text = "Changed $i " + p.doc!!.text; p.bridge = true
        p.cache.accept(JSONObject(p.bridgeMessage(p.doc!!, false).toString()).put("seq", 99).toString())
        assertTrue(p.cache.needsResync())
        val r = runBlocking { d.e.read() }!!
        assertTrue(r.text.startsWith("Changed $i"))
        assertEquals("one BRIDGE_FULL call", 1, p.evals)
        assertTrue(p.cache.usable())
    }

    @Test fun withoutTheBridgeTheOldPathStillWorks_5of5() = fiveTimes("no bridge") { i ->
        val p = SimPage(600L + i).also { it.bridge = false }; val d = PhonePerfTest.SimDriver(p)
        runBlocking { d.open("https://the-internet.herokuapp.com/login") }; p.drain(); p.evals = 0
        val r = runBlocking { PhoneBrowserTools.run(d, "phone_read", JSONObject(), mutableListOf()) { _, _, _ -> true } }
        assertTrue(r.contains("Login Page"))
        assertEquals("the direct DOM read", 1, p.evals)
    }

    @Test fun typingIsPacedOnlyWhileWatched() = fiveTimes("pace") {
        assertEquals(0L, PhonePolicy.keyPaceMs(false)); assertTrue(PhonePolicy.keyPaceMs(true) > 0)
        val (p, d) = opened(700L, "https://the-internet.herokuapp.com/login")
        p.watching = true
        val t0 = p.t
        runBlocking { PhoneBrowserTools.run(d, "phone_act", JSONObject("""{"action":"type","target":"Username","text":"tomsmith"}"""), mutableListOf()) { _, _, _ -> true } }
        assertTrue(p.t - t0 >= 8 * PhonePolicy.keyPaceMs(true))
    }

    @Test fun perfTimingsExcludeTheUsersApproval_5of5() = fiveTimes("perf") { i ->
        PhonePerf.reset()
        val (p, d) = opened(800L + i, "https://automationintesting.online/")
        runBlocking { PhoneBrowserTools.run(d, "phone_act", JSONObject().put("action", "click").put("target", "Book this room"), mutableListOf()) { _, _, _ -> p.advanceTo(p.t + 60_000); true } }
        val (p50, _, n) = PhonePerf.stats(PhonePerf.ACT)!!
        assertEquals(1, n)
        assertTrue("act $p50 ms excludes the 60 s confirm", p50 < 5_000)
        assertTrue(PhonePerf.line().contains("act to verified p50"))
    }

    // ------------------------------------------------------------------ sweep (rule 9)
    /** A page that answers our locate with an id it made up (data-bzp set by the page itself). */
    private class LyingPage(val id: String) : PageHost {
        override fun now() = 0L
        override suspend fun sleep(ms: Long) {}
        override suspend fun eval(code: String): Any? = JSONObject().put("id", id).put("x", 1).put("y", 1).put("w", 1).put("h", 1).put("label", "Pay").put("role", "button")
        override fun hasPage() = true
        override fun url() = "https://a.test/"
        override suspend fun tap(x: Float, y: Float) {}
        override suspend fun drag(dy: Float) {}
        override suspend fun keys(text: String, clear: Boolean) = true
        override suspend fun key(k: String) {}
        override suspend fun viewHeight() = 100
        override suspend fun history(what: String) = ""
        override suspend fun awaitLoad(ms: Long) = true
        override fun touch() {}
    }

    @Test fun idsFromThePageAreNeverTrusted_5of5() = fiveTimes("ids") { i ->
        val bad = listOf("x\"]", "e1\"]);alert(1);//", "", "E1", "e1 e2")[i]
        assertNull(runBlocking { PhoneEngine(LyingPage(bad)).locate("Pay") })
        assertNotNull(runBlocking { PhoneEngine(LyingPage("e$i")).locate("Pay") })
        // Every script that takes an id or text quotes it as a JSON string literal.
        val evil = "a'b\"c</script>$i"
        val q = JSONObject.quote(evil)
        listOf(PhoneScripts.click(evil), PhoneScripts.type(evil, evil, false, true), PhoneScripts.markSecret(evil), PhoneScripts.valueLen(evil),
            PhoneScripts.focus(evil), PhoneScripts.select(evil, evil), PhoneScripts.rect(evil), PhoneScripts.locate(evil), PhoneScripts.find(evil)).forEach { s ->
            assertTrue(s.contains(q)); assertFalse(s.contains("'$evil'")); assertFalse(s.contains("\"$evil\""))
        }
        assertTrue(PhoneScripts.bridge("t$i").contains(JSONObject.quote("t$i")))
    }

    @Test fun noRawCallTextOnScreen_5of5() = fiveTimes("raw") { i ->
        for (n in PhonePolicy.TOOLS) assertTrue(n, n in com.blaze.assistant.agent.CallScan.SEED)
        val line = PhoneBrowserTools.describe("phone_act", JSONObject().put("action", "click").put("target", "e$i"))
        assertFalse(line, Regex("\\be\\d+\\b").containsMatchIn(line))
        assertEquals("Working on Blaze Phone", PhoneBrowserTools.describe("phone_act", JSONObject().put("action", "{\"name\":\"x\"}")))
    }

    @Test fun bridgeScriptShape() {
        val b = PhoneScripts.bridge("TOKEN")
        assertTrue(b.startsWith("/*bz:bridge*/"))
        assertTrue("main frame only", b.contains("window.top!==window"))
        assertTrue("token in the closure, not on window", b.contains("o.k=K") && !b.contains("window.K"))
        assertTrue("own attribute writes don't loop", b.contains("attributeName!=='data-bzp'"))
        assertTrue("secrets masked page-side", b.contains("(filled, hidden)"))
        assertTrue(PhoneScripts.SNAPSHOT.contains("bzNode(e,true)"))
    }
}
