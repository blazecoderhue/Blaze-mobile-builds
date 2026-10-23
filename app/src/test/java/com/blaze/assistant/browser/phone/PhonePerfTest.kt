package com.blaze.assistant.browser.phone

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.19-speed: the Blaze Phone perf harness. Times the real tool + engine code ([PhoneBrowserTools.run] over
 * [PhoneEngine]) on [SimPage] (virtual clock, phone cost model, seeded jitter) for two measures:
 *  - page read: phone_read on a loaded page (small login page, medium booking page, big shop page);
 *  - act-to-verified: phone_act until the tool returns with the change visible (link to the next page, an in-page
 *    button that fetches, typing, a dropdown, typing a password + Enter that signs in).
 * Prints `PERF …` lines (CI keeps them in the test XML). Engine CPU is measured on the JVM, minus the simulator's own,
 * and counted ×3 for a phone.
 */
class PhonePerfTest {
    object Sites {
        private fun pad(base: String, kb: Int): String { val sb = StringBuilder(base); var i = 0
            while (sb.length < kb * 1024) sb.append("\nLine ${i++}: lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor.")
            return sb.toString() }

        fun login(p: SimPage, error: Boolean = false): SimPage.Doc {
            val d = SimPage.Doc("https://the-internet.herokuapp.com/login", "The Internet", 150,
                pad((if (error) "Your username is invalid!\n" else "") + "Login Page\nThis is where you can log into the secure area.", 1))
            val user = SimPage.El("e1", "textbox", "Username", type = "text", inForm = true, submitLabel = "Login")
            val pass = SimPage.El("e2", "textbox", "Password", type = "password", sens = true, inForm = true, submitLabel = "Login")
            val submit: () -> Unit = {
                val ok = user.value == "tomsmith" && pass.value.isNotEmpty()
                p.navigate(p.rnd.nextLong(30, 151), p.rnd.nextLong(400, 1501)) { if (ok) secure() else login(p, true) }
            }
            pass.onEnter = submit; user.onEnter = submit
            d.els += user; d.els += pass
            d.els += SimPage.El("e3", "button", "Login", type = "submit", onClick = submit)
            d.els += SimPage.El("e4", "link", "Elemental Selenium", href = "http://elementalselenium.com/")
            (5..12).forEach { d.els += SimPage.El("e$it", "link", "Footer link $it", href = "https://the-internet.herokuapp.com/x$it") }
            return d
        }

        fun secure() = SimPage.Doc("https://the-internet.herokuapp.com/secure", "The Internet", 120,
            "Secure Area\nYou logged into a secure area!\nWelcome to the Secure Area.").apply { els += SimPage.El("e1", "link", "Logout") }

        fun rooms(p: SimPage): SimPage.Doc {
            val d = SimPage.Doc("https://automationintesting.online/", "Restful-booker-platform demo", 900, pad("Welcome to Shady Meadows B&B\nRooms", 6))
            (1..60).forEach { d.els += SimPage.El("e$it", "link", "Room $it details", href = "https://automationintesting.online/room/$it") }
            val form = listOf(SimPage.El("e70", "textbox", "Firstname", type = "text", visible = false, inForm = true, submitLabel = "Book"),
                SimPage.El("e71", "textbox", "Lastname", type = "text", visible = false, inForm = true, submitLabel = "Book"),
                SimPage.El("e72", "textbox", "Email", type = "email", visible = false, inForm = true, submitLabel = "Book"),
                SimPage.El("e73", "textbox", "Phone", type = "tel", visible = false, inForm = true, submitLabel = "Book"),
                SimPage.El("e74", "combobox", "Room type", type = "select-one", visible = false, options = listOf("Single", "Double", "Suite")),
                SimPage.El("e75", "button", "Book", type = "submit", visible = false))
            form[4].onClick = { val v = form[4].value; p.at(p.rnd.nextLong(50, 201)) { d.text = d.text + "\nPrice for $v: 150"; p.changed("dom") } }
            d.els += form
            d.els += SimPage.El("e65", "button", "Book this room", onClick = {
                p.changed("netstart")
                p.at(p.rnd.nextLong(100, 601)) {
                    p.changed("netend")
                    p.at(p.rnd.nextLong(20, 81)) { form.forEach { it.visible = true }; d.text = "Book this room\nFirstname Lastname Email Phone\n" + d.text; p.changed("dom") }
                }
            })
            return d
        }

        fun shop(p: SimPage, page: Int): SimPage.Doc {
            val d = SimPage.Doc("https://shop.example/list?page=$page", "Shop page $page", 4000, pad("Page $page of results\nProducts", 12))
            (1..249).forEach { d.els += SimPage.El("e$it", "link", "Product ${page * 1000 + it}, a very good thing", href = "https://shop.example/p/$it") }
            d.els += SimPage.El("e250", "link", "Next page", href = "https://shop.example/list?page=${page + 1}",
                onClick = { p.navigate(p.rnd.nextLong(40, 301), p.rnd.nextLong(500, 2001)) { shop(p, page + 1) } })
            return d
        }
    }

    /** PhoneDriver over the real engine on a simulated page (the same wiring as PhoneBrowser.Driver). */
    open class SimDriver(val p: SimPage) : PhoneDriver {
        open val e: PhoneEngine = PhoneEngine(p)
        override fun hasPage() = p.hasPage()
        override fun mode() = PhonePolicy.Mode.AGENT
        override fun site() = PhonePolicy.siteOf(p.url())
        override fun step(what: String) {}
        override suspend fun open(url: String): String {
            p.open { when { url.contains("login") -> Sites.login(p); url.contains("automationintesting") -> Sites.rooms(p); else -> Sites.shop(p, 1) } }
            e.afterOpen()
            return "Opened on Blaze Phone.\n" + (e.read()?.let { PhonePolicy.render(it) } ?: "Couldn't read")
        }
        override suspend fun snapshot() = e.read()
        override suspend fun locate(target: String, field: Boolean) = e.locate(target, field)
        override suspend fun focusInfo() = e.focusInfo()
        override suspend fun click(t: PhoneDriver.Target) = e.click(t)
        override suspend fun type(t: PhoneDriver.Target, text: String, clear: Boolean, submit: Boolean) = e.type(t, text, clear, submit)
        override suspend fun select(t: PhoneDriver.Target, option: String) = e.select(t, option)
        override suspend fun key(k: String) = e.pressKey(k)
        override suspend fun scroll(down: Boolean) = e.scroll(down)
        override suspend fun navigate(what: String) = e.navigate(what)
        override suspend fun fillLogin(user: String?) = PhoneDriver.ActResult(false, "n/a")
        override suspend fun screenshot(): String? = null
        override suspend fun waitFor(selector: String?, text: String?, gone: Boolean, ms: Long) = e.waitFor(selector, text, gone, ms)
        override suspend fun handoff(reason: String) = "n/a"
        override suspend fun awaitAgentTurn() = true
        override fun status() = ""
        override fun now() = p.t
    }

    data class Sample(val ms: Double, val ok: Boolean)

    companion object {
        const val CPU_PHONE_FACTOR = 3.0
        /** From the baseline re-run (fixture fix), see PHONE_SPEED_STATUS.md. Updated once that run is in. */
        const val BEFORE_ACT_P50 = 783.3
        const val BEFORE_ACT_P95 = 2398.1
        fun pct(xs: List<Double>, p: Double): Double { if (xs.isEmpty()) return 0.0; val s = xs.sorted(); return s[(Math.ceil(p * s.size).toInt() - 1).coerceIn(0, s.size - 1)] }
        fun fmt(d: Double) = String.format(java.util.Locale.ROOT, "%.1f", d)
    }

    private val shots = mutableListOf<String>()

    /** Makes the driver for a fresh page (a newer engine wires its bridge here). */
    open fun driver(p: SimPage): PhoneDriver = SimDriver(p)

    /** One measured tool call on a fresh page that was opened and fully settled first (not timed). */
    private var bridge = true

    private fun sample(seed: Long, url: String, prep: (SimPage) -> Unit, tool: String, args: JSONObject, check: (SimPage, String) -> Boolean): Sample = runBlocking {
        val p = SimPage(seed).also { it.bridge = bridge }
        val d = driver(p)
        d.open(url); p.drain(); prep(p); p.drain()
        val t0 = p.t; val s0 = p.simNanos; val c0 = System.nanoTime()
        val out = PhoneBrowserTools.run(d, tool, args, shots) { _, _, _ -> true }
        val cpu = (System.nanoTime() - c0 - (p.simNanos - s0)) / 1e6
        Sample((p.t - t0) + cpu * CPU_PHONE_FACTOR, check(p, out))
    }

    private val readCases: List<Triple<String, String, String>> = listOf(
        Triple("read-login", "https://the-internet.herokuapp.com/login", "Login Page"),
        Triple("read-booking", "https://automationintesting.online/", "Shady Meadows"),
        Triple("read-shop", "https://shop.example/list?page=1", "Page 1 of results"))

    private class ActCase(val name: String, val url: String, val prep: (SimPage) -> Unit, val args: String, val check: (SimPage, String) -> Boolean)
    private val actCases = listOf(
        ActCase("act-link-navigates", "https://shop.example/list?page=1", {}, """{"action":"click","target":"e250"}""") { _, o -> o.contains("Page 2 of results") },
        ActCase("act-button-fetches", "https://automationintesting.online/", {}, """{"action":"click","target":"Book this room"}""") { _, o -> o.contains("Firstname") },
        ActCase("act-type", "https://the-internet.herokuapp.com/login", {}, """{"action":"type","target":"Username","text":"tomsmith"}""") { p, o -> o.startsWith("Typed") && p.el("e1")?.value == "tomsmith" },
        ActCase("act-select", "https://automationintesting.online/", { p -> p.el("e65")?.onClick?.invoke() }, """{"action":"select","target":"Room type","option":"Double"}""") { p, o -> o.startsWith("Selected") && p.el("e74")?.value == "Double" },
        ActCase("act-submit-signs-in", "https://the-internet.herokuapp.com/login", { p -> p.el("e1")?.value = "tomsmith" },
            """{"action":"type","target":"Password","text":"SuperSecretPassword!","submit":true}""") { _, o -> o.contains("You logged into a secure area") },
    )

    private fun runAll(n: Int, seedBase: Long): Map<String, List<Sample>> {
        val out = LinkedHashMap<String, List<Sample>>()
        readCases.forEachIndexed { ci, c ->
            out[c.first] = (0 until n).map { i -> sample(seedBase + ci * 1000L + i, c.second, {}, "phone_read", JSONObject()) { _, o -> o.contains(c.third) } }
        }
        actCases.forEachIndexed { ci, c ->
            out[c.name] = (0 until n).map { i -> sample(seedBase + 50_000L + ci * 1000L + i, c.url, c.prep, "phone_act", JSONObject(c.args), c.check) }
        }
        return out
    }

    /** Runs the harness and prints the PERF lines; returns (read p50, read p95, act p50, act p95, act verified rate, read verified rate). */
    fun measure(label: String, n: Int = 40, withBridge: Boolean = true): DoubleArray {
        bridge = withBridge
        runAll(5, 9_000_000L)   // JIT warm-up, not counted
        val r = runAll(n, 1L)
        val reads = r.filterKeys { it.startsWith("read-") }.values.flatten()
        val acts = r.filterKeys { it.startsWith("act-") }.values.flatten()
        r.forEach { (k, v) -> val ms = v.map { it.ms }
            println("PERF [$label] case=$k n=${v.size} p50=${fmt(pct(ms, .5))} p95=${fmt(pct(ms, .95))} verified=${v.count { it.ok }}/${v.size}") }
        val res = doubleArrayOf(pct(reads.map { it.ms }, .5), pct(reads.map { it.ms }, .95), pct(acts.map { it.ms }, .5), pct(acts.map { it.ms }, .95),
            acts.count { it.ok }.toDouble() / acts.size, reads.count { it.ok }.toDouble() / reads.size)
        println("PERF [$label] TOTAL read p50=${fmt(res[0])} p95=${fmt(res[1])} n=${reads.size} | act-to-verified p50=${fmt(res[2])} p95=${fmt(res[3])} n=${acts.size} " +
            "verified=${acts.count { it.ok }}/${acts.size} (reads verified=${reads.count { it.ok }}/${reads.size})")
        return res
    }

    /**
     * Before (0.45.19 at a82f7aa, the same harness, CI run recorded in PHONE_SPEED_STATUS.md): page read p50 27.3 / p95 70.2 ms;
     * act-to-verified p50 / p95 in [BEFORE_ACT_P50] / [BEFORE_ACT_P95]. After: reads from the bridge cache, event waits.
     */
    @Test fun perfAfterIsFasterAndAlwaysVerified() {
        val r = measure("bridge")
        assertEquals("every act verified (5 cases × 40)", 1.0, r[4], 0.0)
        assertEquals("every read verified", 1.0, r[5], 0.0)
        assertTrue("read p95 ${r[1]} under 10 ms (was 70.2)", r[1] < 10.0)
        assertTrue("read p50 ${r[0]} under 5 ms (was 27.3)", r[0] < 5.0)
        assertTrue("act p95 ${r[3]} faster than before $BEFORE_ACT_P95", r[3] < BEFORE_ACT_P95)
        assertTrue("act p50 ${r[2]} faster than before $BEFORE_ACT_P50", r[2] < BEFORE_ACT_P50)
    }

    /** Pages where the bridge didn't inject: the direct DOM read and the old timed waits still work. */
    @Test fun perfWithoutBridgeStillWorks() {
        val r = measure("no-bridge", n = 10, withBridge = false)
        assertTrue("reads verified", r[5] == 1.0)
        assertEquals(5, actCases.size)
        assertEquals(3, readCases.size)
    }

    @Test fun fixtureIdsAreUnique() {
        val p = SimPage(1)
        listOf(Sites.login(p), Sites.secure(), Sites.rooms(p), Sites.shop(p, 1)).forEach { d ->
            assertEquals(d.url, d.els.size, d.els.map { it.id }.toSet().size)
        }
    }
}
