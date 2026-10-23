package com.blaze.assistant.phone

import com.blaze.assistant.agent.ConfirmGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Blaze Phone spike: confirm gating, RAM cap, lazy engine, view-tree format and honest errors (no device needed). */
class BlazePhoneTest {
    private val gib = 1024L * 1024 * 1024
    private val host = "com.blaze.assistant"

    private class FakeEngine : BlazePhone.Engine {
        val installedPkgs = linkedSetOf<String>()
        val calls = ArrayList<String>()
        override fun ensure() { calls += "ensure" }
        override fun install(apk: File): BlazePhone.InstallOutcome { calls += "install"; installedPkgs += "org.example.app"; return BlazePhone.InstallOutcome(true, "org.example.app", null) }
        override fun uninstall(pkg: String) { calls += "uninstall:$pkg"; installedPkgs -= pkg }
        override fun isInstalled(pkg: String) = pkg in installedPkgs
        override fun installed() = installedPkgs.toList()
        override fun launch(pkg: String, displayId: Int): Boolean { calls += "launch:$pkg@$displayId"; return true }
        override fun stop(pkg: String) { calls += "stop:$pkg" }
    }

    private class FakeScreen(override val displayId: Int) : BlazePhone.Screen {
        var closed = false
        override val frames = MutableStateFlow(0L)
        override fun frame(): android.graphics.Bitmap? = null
        override fun close() { closed = true }
    }

    private class Env(ram: Long, val apk: GuestCompat.Apk? = GuestCompat.Apk("org.example.app", "Example"), var approve: Boolean = true,
                      var agentReply: (String, JSONObject) -> JSONObject? = { _, _ -> JSONObject().put("ok", true) }) {
        var factoryCalls = 0
        val engine = FakeEngine()
        val asked = ArrayList<String>()
        val screens = ArrayList<FakeScreen>()
        var now = 1_000_000L
        val phone = BlazePhone(
            hostPkg = "com.blaze.assistant", totalRamBytes = ram,
            engineFactory = { factoryCalls++; engine },
            screens = { _, _, _, _ -> FakeScreen(100 + screens.size).also { screens += it } },
            agents = object : BlazePhone.Agents { override fun call(pkg: String, req: JSONObject) = agentReply(pkg, req) },
            inspect = { apk },
            confirm = { title, _ -> asked += title; approve },
            deviceSdk = 34, deviceAbis = listOf("arm64-v8a", "armeabi-v7a"),
            clock = { now }, io = Dispatchers.Unconfined,
        )
    }

    private fun apkFile(): File = File.createTempFile("guest", ".apk").apply { writeBytes(byteArrayOf(1)); deleteOnExit() }

    // ---- confirm gating ------------------------------------------------------------------------------------------

    @Test fun installAsksFirstAndDoesNothingWhenRefused() = runBlocking {
        val e = Env(6 * gib, approve = false)
        val r = e.phone.installGuest(apkFile())
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("didn't approve"))
        assertEquals(listOf("Install Example in Blaze Phone?"), e.asked)
        assertEquals("engine never started when the user said no", 0, e.factoryCalls)
        assertFalse(e.engine.calls.contains("install"))
    }

    @Test fun installRunsOnlyAfterTheUserApproves() = runBlocking {
        val e = Env(6 * gib, approve = true)
        val r = e.phone.installGuest(apkFile())
        assertTrue(r.message, r.ok)
        assertEquals(1, e.asked.size)
        assertEquals(listOf("ensure", "install"), e.engine.calls)
    }

    @Test fun uninstallAsksFirst() = runBlocking {
        val e = Env(6 * gib)
        e.phone.installGuest(apkFile())
        e.approve = false
        assertFalse(e.phone.uninstallGuest("org.example.app").ok)
        assertFalse(e.engine.calls.any { it.startsWith("uninstall") })
        e.approve = true
        assertTrue(e.phone.uninstallGuest("org.example.app").ok)
        assertTrue(e.engine.calls.contains("uninstall:org.example.app"))
        assertEquals(3, e.asked.size)
        assertTrue(e.asked.last().startsWith("Remove org.example.app"))
    }

    @Test fun installIsAnAlwaysAskAction() {
        // BlazePhone passes always = true to Agent.confirm: no standing OK or Autopilot skips the card.
        for (pre in listOf(false, true)) for (auto in listOf(false, true))
            assertEquals(ConfirmGate.Decision.SHOW, ConfirmGate.decide(always = true, destructive = false, preApproved = pre, autopilot = auto, tainted = false))
        assertEquals(ConfirmGate.PHONE_APP, ConfirmGate.sourceOf(BlazePhoneTool.NAME))
    }

    // ---- RAM cap ---------------------------------------------------------------------------------------------------

    @Test fun ramCapPolicy() {
        // What phones actually report (totalMem): "3 GB" ~2.8 GiB, "4 GB" ~3.7 GiB, "6 GB" ~5.6 GiB.
        assertEquals(3, BlazePhonePolicy.nominalGb((2.8 * gib).toLong()))
        assertEquals(4, BlazePhonePolicy.nominalGb((3.7 * gib).toLong()))
        assertEquals(1, BlazePhonePolicy.maxLiveGuests((2.8 * gib).toLong()))
        assertEquals(1, BlazePhonePolicy.maxLiveGuests((1.9 * gib).toLong()))
        assertEquals(2, BlazePhonePolicy.maxLiveGuests((3.7 * gib).toLong()))
        assertEquals(2, BlazePhonePolicy.maxLiveGuests((5.6 * gib).toLong()))
        assertEquals(2, BlazePhonePolicy.maxLiveGuests(12 * gib))
        assertEquals(1, BlazePhonePolicy.maxLiveGuests(0))
        // LRU eviction beyond the cap; nothing when already live or under the cap.
        assertEquals(listOf("a"), BlazePhonePolicy.toEvict(mapOf("a" to 1L, "b" to 5L), "c", 2))
        assertEquals(listOf("a", "b"), BlazePhonePolicy.toEvict(mapOf("b" to 5L, "a" to 1L), "c", 1))
        assertEquals(emptyList<String>(), BlazePhonePolicy.toEvict(mapOf("a" to 1L), "a", 1))
        assertEquals(emptyList<String>(), BlazePhonePolicy.toEvict(mapOf("a" to 1L), "c", 2))
    }

    @Test fun smallPhoneRunsOneGuestAtATime() = runBlocking {
        val e = Env((2.8 * gib).toLong())
        e.engine.installedPkgs += listOf("a", "b")
        assertTrue(e.phone.launch("a").ok)
        e.now += 1000
        val r = e.phone.launch("b")
        assertTrue(r.message, r.ok)
        assertTrue(r.message, r.message.contains("Closed a"))
        assertEquals(listOf("b"), e.phone.liveGuests())
        assertTrue(e.engine.calls.contains("stop:a"))
        assertTrue("a's display released", e.screens[0].closed)
        assertEquals(listOf("launch:a@100", "launch:b@101"), e.engine.calls.filter { it.startsWith("launch") })
    }

    @Test fun biggerPhoneKeepsTwo() = runBlocking {
        val e = Env(6 * gib)
        e.engine.installedPkgs += listOf("a", "b", "c")
        e.phone.launch("a"); e.now += 10; e.phone.launch("b"); e.now += 10
        assertEquals(listOf("a", "b"), e.phone.liveGuests())
        e.phone.tap("a", 1, 1)   // a is now the most recent
        e.now += 10
        e.phone.launch("c")
        assertEquals(listOf("a", "c"), e.phone.liveGuests().sorted())
    }

    @Test fun idleAndHandBackStopGuests() = runBlocking {
        val e = Env(6 * gib)
        e.engine.installedPkgs += listOf("a", "b")
        e.phone.launch("a"); e.phone.launch("b")
        e.now += BlazePhonePolicy.IDLE_MS - 1
        assertEquals(emptyList<String>(), e.phone.reapIdle())
        e.phone.back("b")
        e.now += 2
        assertEquals(listOf("a"), e.phone.reapIdle())
        assertTrue(e.engine.calls.contains("stop:a"))
        assertTrue(e.phone.handBack("b").ok)
        assertEquals(emptyList<String>(), e.phone.liveGuests())
        assertTrue(e.screens.all { it.closed })
        assertFalse(e.phone.tap("b", 1, 1).ok)
    }

    // ---- lazy init -------------------------------------------------------------------------------------------------

    @Test fun engineStartsOnlyWhenUsed() = runBlocking {
        val e = Env(6 * gib)
        assertEquals(2, e.phone.maxLive)
        assertEquals(emptyList<String>(), e.phone.liveGuests())
        assertFalse(e.phone.tap("x", 1, 1).ok)
        assertFalse(e.phone.engineReady)
        assertEquals(0, e.factoryCalls)
        e.phone.installed()
        assertTrue(e.phone.engineReady)
        e.phone.installed()
        assertEquals("one engine per app", 1, e.factoryCalls)
        assertEquals(1, e.engine.calls.count { it == "ensure" })
    }

    @Test fun onlyEngineProcessesStartTheEngine() {
        assertEquals(BlazePhonePolicy.Proc.MAIN, BlazePhonePolicy.proc(host, host))
        assertEquals(BlazePhonePolicy.Proc.MAIN, BlazePhonePolicy.proc(null, host))
        assertEquals(BlazePhonePolicy.Proc.ENGINE_SERVER, BlazePhonePolicy.proc("$host:black", host))
        assertEquals(BlazePhonePolicy.Proc.GUEST, BlazePhonePolicy.proc("$host:p0", host))
        assertEquals(BlazePhonePolicy.Proc.GUEST, BlazePhonePolicy.proc("$host:p3", host))
        assertEquals(BlazePhonePolicy.Proc.OTHER, BlazePhonePolicy.proc("$host:p4", host))
        assertEquals(BlazePhonePolicy.Proc.OTHER, BlazePhonePolicy.proc("$host:remote", host))
        assertFalse(BlazePhonePolicy.isEngineProcess(host, host))
        assertTrue(BlazePhonePolicy.isEngineProcess("$host:black", host))
    }

    @Test fun mainProcessNeverReferencesTheEngine() {
        // Only BlazePhoneEngine.kt names engine classes; BlazeApp reaches it only in engine processes.
        val main = File("src/main/java")
        val users = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("top.niunaijun") }.map { it.name }.toList()
        assertEquals(listOf("BlazePhoneEngine.kt"), users)
        val app = File(main, "com/blaze/assistant/BlazeApp.kt").readText()
        val onCreate = app.substringAfter("override fun onCreate() {")
        assertTrue("the engine-process exit is the first thing onCreate does",
            onCreate.trimStart().startsWith("super.onCreate()\n        if (engineProcess) { com.blaze.assistant.phone.BlazePhoneEngine.create(); return }"))
        assertEquals("BlazePhoneEngine is called only behind engineProcess", 2, Regex("BlazePhoneEngine\\.").findAll(app).count())
        // The engine's manifest adds no permission (or feature) to Blaze.
        val m = File("../phone-engine/src/main/AndroidManifest.xml").readText()
        assertFalse(m.contains("<uses-permission")); assertFalse(m.contains("<uses-feature")); assertFalse(m.contains("usesCleartextTraffic"))
        assertEquals(4, Regex("android:name=\"\\.proxy\\.ProxyActivity\\\$P\\d\"").findAll(m).count())
        assertFalse("no exported guest activity", Regex("""<activity[^>]*android:exported="true"""").containsMatchIn(m))
    }

    // ---- view tree -------------------------------------------------------------------------------------------------

    @Test fun viewTreeFormat() {
        val tree = GuestViewTree.Node("FrameLayout", id = "content", bounds = intArrayOf(0, 0, 720, 1520), children = listOf(
            GuestViewTree.Node("TextView", text = "Trending", bounds = intArrayOf(16, 40, 300, 90)),
            GuestViewTree.Node("ImageButton", id = "search", desc = "Search", clickable = true, bounds = intArrayOf(640, 40, 704, 104)),
            GuestViewTree.Node("EditText", text = "", editable = true, focused = true, enabled = false, bounds = intArrayOf(0, 120, 720, 200)),
        ))
        val o = JSONObject(GuestViewTree.serialize(tree, "org.schabi.newpipe", "MainActivity", 720, 1520))
        assertEquals(1, o.getInt("v")); assertEquals("org.schabi.newpipe", o.getString("pkg")); assertEquals("MainActivity", o.getString("window"))
        assertEquals(720, o.getInt("w")); assertEquals(1520, o.getInt("h")); assertEquals(4, o.getInt("count")); assertFalse(o.getBoolean("truncated"))
        val root = o.getJSONObject("root")
        assertEquals(setOf("cls", "id", "b", "children"), root.keySet())
        assertEquals("[0,0,720,1520]", root.getJSONArray("b").toString())
        val kids = root.getJSONArray("children")
        assertEquals(setOf("cls", "text", "b"), kids.getJSONObject(0).keySet())
        assertEquals("[\"clickable\"]", kids.getJSONObject(1).getJSONArray("flags").toString())
        assertEquals("Search", kids.getJSONObject(1).getString("desc"))
        assertEquals("[\"editable\",\"focused\",\"disabled\"]", kids.getJSONObject(2).getJSONArray("flags").toString())
        assertFalse("empty text left out", kids.getJSONObject(2).has("text"))
    }

    @Test fun viewTreeLimits() {
        val many = GuestViewTree.Node("LinearLayout", children = List(GuestViewTree.MAX_NODES + 50) { GuestViewTree.Node("TextView", text = "x".repeat(500)) })
        val o = JSONObject(GuestViewTree.serialize(many, "p", "w", 1, 1))
        assertEquals(GuestViewTree.MAX_NODES, o.getInt("count"))
        assertTrue(o.getBoolean("truncated"))
        val t = o.getJSONObject("root").getJSONArray("children").getJSONObject(0).getString("text")
        assertEquals(GuestViewTree.MAX_TEXT, t.length); assertTrue(t.endsWith("…"))
        assertTrue(JSONObject(GuestViewTree.serialize(null, "p", "w", 1, 1)).isNull("root"))
    }

    // ---- honest errors ---------------------------------------------------------------------------------------------

    private fun check(apk: GuestCompat.Apk) = GuestCompat.check(apk, host, 34, listOf("arm64-v8a", "armeabi-v7a"))

    @Test fun unsupportedAppsSayWhy() {
        assertEquals("Google Maps can't run in Blaze Phone: it needs Google Play services (it's a Google app), which Blaze Phone doesn't have.",
            check(GuestCompat.Apk("com.google.android.apps.maps", "Google Maps")).message("Google Maps"))
        assertTrue(check(GuestCompat.Apk("x", permissions = setOf("com.android.vending.CHECK_LICENSE"))).message("Paid")!!.contains("needs Google Play licensing"))
        assertTrue(check(GuestCompat.Apk("x", nativeAbis = setOf("x86", "x86_64"))).message("Emu")!!.contains("built only for x86/x86_64 processors"))
        assertTrue(check(GuestCompat.Apk("x", splitsRequired = true)).message("Split")!!.contains("split bundle"))
        assertTrue(check(GuestCompat.Apk("x", minSdk = 35)).message("New")!!.contains("needs Android API 35"))
        assertTrue(check(GuestCompat.Apk(host)).message("Blaze")!!.contains("is Blaze itself"))
        assertTrue(check(GuestCompat.Apk("x", hasLauncher = false, servicePermissions = setOf("android.permission.BIND_VPN_SERVICE"))).message("Vpn")!!.contains("a VPN"))
        // Works, with an honest warning on the card.
        val gms = check(GuestCompat.Apk("x", metaData = setOf(GuestCompat.GMS_META), nativeAbis = setOf("arm64-v8a", "x86")))
        assertTrue(gms.ok); assertTrue(gms.warnings.single().startsWith("Uses Google Play services"))
        assertTrue(check(GuestCompat.Apk("org.schabi.newpipe", "NewPipe", minSdk = 21, nativeAbis = setOf("arm64-v8a", "armeabi-v7a"))).let { it.ok && it.warnings.isEmpty() })
    }

    @Test fun blockedApkNeverReachesTheConfirmCard() = runBlocking {
        val e = Env(6 * gib, apk = GuestCompat.Apk("com.google.android.youtube", "YouTube"))
        val r = e.phone.installGuest(apkFile())
        assertFalse(r.ok)
        assertTrue(r.message, r.message.startsWith("YouTube can't run in Blaze Phone: it needs Google Play services"))
        assertTrue(e.asked.isEmpty()); assertEquals(0, e.factoryCalls)
        val bad = Env(6 * gib, apk = null)
        assertTrue(bad.phone.installGuest(apkFile()).message.contains("isn't an Android app"))
        assertTrue(bad.phone.installGuest(File("/nonexistent/x.apk")).message.startsWith("No APK at"))
    }

    @Test fun crashesAreExplained() {
        assertEquals("needs Google Play services, which Blaze Phone doesn't have",
            GuestCompat.explainCrash("java.lang.IllegalStateException: com.google.android.gms.common.GooglePlayServicesNotAvailableException"))
        assertNotNull(GuestCompat.explainCrash("java.lang.UnsatisfiedLinkError: dlopen failed: couldn't find \"libfoo.so\""))
        assertTrue(GuestCompat.explainCrash("Play Integrity API error -1")!!.contains("Play Integrity"))
        assertNull(GuestCompat.explainCrash("java.lang.NullPointerException at a.b.c"))
        assertNull(GuestCompat.explainCrash(null))
    }

    @Test fun agentErrorsReachTheUser() = runBlocking {
        val e = Env(6 * gib, agentReply = { _, _ -> JSONObject().put("ok", false).put("error", "no text field has focus (tap one first)") })
        e.engine.installedPkgs += "a"; e.phone.launch("a")
        assertEquals("a: no text field has focus (tap one first).", e.phone.type("a", "hi").message)
        e.agentReply = { _, _ -> JSONObject().put("ok", false).put("error", "x").put("crash", "GooglePlayServicesNotAvailableException") }
        assertEquals("a: needs Google Play services, which Blaze Phone doesn't have.", e.phone.dumpViewTree("a").message)
        e.agentReply = { _, _ -> null }
        assertTrue(e.phone.back("a").message.contains("isn't answering"))
    }
}
