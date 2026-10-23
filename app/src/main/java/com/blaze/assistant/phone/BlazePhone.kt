package com.blaze.assistant.phone

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Blaze Phone (spike): runs third-party APKs inside Blaze's OWN sandbox (the vendored engine in phone-engine) and drives
 * them. A guest is never installed on the phone, never gets a launcher icon, never goes through the system installer,
 * and never shows on the user's screen: it opens on a VirtualDisplay Blaze owns, its frames come to Blaze, and taps,
 * text, back and the view tree go through a small agent inside the guest's own process ([GuestAgent]). No Shizuku,
 * no ADB, no Accessibility.
 *
 *  - [installGuest] / [uninstallGuest] always show Blaze's confirm card (ConfirmGate: always = true, like any install).
 *  - RAM cap: 1 live guest on phones under 4 GB, else 2 ([BlazePhonePolicy.maxLiveGuests]); the least recently used one
 *    is stopped to make room. Guests stop on [handBack] and after 5 idle minutes.
 *  - Lazy: nothing of the engine loads until the first install/launch ([engineReady] stays false until then), so
 *    Blaze's start-up is unchanged when Blaze Phone isn't used.
 *
 * Pure enough to unit-test with fakes (BlazePhoneTest); [get] wires the real engine, screens and agents.
 */
class BlazePhone(
    private val hostPkg: String,
    private val totalRamBytes: Long,
    private val engineFactory: () -> Engine,
    private val screens: (pkg: String, w: Int, h: Int, dpi: Int) -> Screen,
    private val agents: Agents,
    private val inspect: (File) -> GuestCompat.Apk?,
    private val confirm: suspend (title: String, detail: String) -> Boolean,
    private val deviceSdk: Int,
    private val deviceAbis: List<String>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    interface Engine {
        /** Starts the engine in this process (the first call only; it waits for the engine's server process). */
        fun ensure()
        fun install(apk: File): InstallOutcome
        fun uninstall(pkg: String)
        fun isInstalled(pkg: String): Boolean
        fun installed(): List<String>
        /** Starts [pkg]'s launcher activity on [displayId]; false when it has none. */
        fun launch(pkg: String, displayId: Int): Boolean
        fun stop(pkg: String)
    }

    interface Screen {
        val displayId: Int
        val frames: StateFlow<Long>
        fun frame(): android.graphics.Bitmap?
        fun close()
    }

    /** Requests to the agent inside a guest's process (null = the guest isn't running / didn't answer). */
    interface Agents {
        fun call(pkg: String, req: JSONObject): JSONObject?
        fun forget(pkg: String) {}
    }

    data class InstallOutcome(val ok: Boolean, val pkg: String?, val message: String?)

    /** Every call's answer: [ok], a sentence for the user / model, and the data when there is some. */
    data class Result(val ok: Boolean, val message: String, val data: JSONObject? = null)

    private val lock = Mutex()
    private var engine: Engine? = null
    private val live = LinkedHashMap<String, Long>()
    private val open = HashMap<String, Screen>()
    private var reaper: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + io)

    val maxLive: Int get() = BlazePhonePolicy.maxLiveGuests(totalRamBytes)
    val engineReady: Boolean get() = engine != null
    fun liveGuests(): List<String> = synchronized(live) { live.keys.toList() }
    fun screen(pkg: String): Screen? = open[pkg]

    private fun eng(): Engine = engine ?: engineFactory().also { it.ensure(); engine = it }

    suspend fun installGuest(apk: File): Result = lock.withLock {
        if (!apk.isFile) return Result(false, "No APK at ${apk.path}.")
        val info = withContext(io) { inspect(apk) } ?: return Result(false, "${apk.name} isn't an Android app (APK) Blaze can read.")
        val verdict = GuestCompat.check(info, hostPkg, deviceSdk, deviceAbis)
        verdict.message(info.label)?.let { return Result(false, it, JSONObject().put("pkg", info.pkg).put("blocked", true)) }
        val detail = buildString {
            append("${info.label} (${info.pkg}) runs only inside Blaze Phone: it isn't installed on your phone and gets no icon in your launcher. ")
            append("It can use what Blaze is allowed to use (internet, and any permission you gave Blaze).")
            verdict.warnings.forEach { append("\n").append(it) }
        }
        if (!confirm("Install ${info.label} in Blaze Phone?", detail)) return Result(false, "Not installed: you didn't approve it.")
        val out = withContext(io) { eng().install(apk) }
        if (!out.ok) Result(false, "Couldn't install ${info.label} in Blaze Phone: ${out.message ?: "the engine refused it"}.")
        else Result(true, "Installed ${info.label} in Blaze Phone.", JSONObject().put("pkg", out.pkg ?: info.pkg).put("warnings", verdict.warnings.joinToString("\n")))
    }

    suspend fun uninstallGuest(pkg: String): Result = lock.withLock {
        val e = withContext(io) { eng() }
        if (!withContext(io) { e.isInstalled(pkg) }) return Result(false, "$pkg isn't in Blaze Phone.")
        if (!confirm("Remove $pkg from Blaze Phone?", "Deletes the app and everything it saved inside Blaze Phone. Your phone's own apps aren't touched."))
            return Result(false, "Not removed: you didn't approve it.")
        stopLocked(pkg)
        withContext(io) { e.uninstall(pkg) }
        Result(true, "Removed $pkg from Blaze Phone.")
    }

    suspend fun installed(): List<String> = lock.withLock { withContext(io) { eng().installed() } }

    /** Opens [pkg] on a new Blaze-owned display ([w]×[h] px at [dpi]), stopping older guests beyond the RAM cap. */
    suspend fun launch(pkg: String, w: Int = 720, h: Int = 1520, dpi: Int = 320): Result = lock.withLock {
        val e = withContext(io) { eng() }
        if (!withContext(io) { e.isInstalled(pkg) }) return Result(false, "$pkg isn't in Blaze Phone; install it first.")
        if (pkg in live) { touch(pkg); return Result(true, "$pkg is already open in Blaze Phone.", JSONObject().put("display", open[pkg]?.displayId ?: -1)) }
        val stopped = BlazePhonePolicy.toEvict(synchronized(live) { HashMap(live) }, pkg, maxLive)
        stopped.forEach { stopLocked(it) }
        val s = screens(pkg, w, h, dpi)
        val ok = try { withContext(io) { e.launch(pkg, s.displayId) } } catch (t: Throwable) { s.close(); return Result(false, "Couldn't open $pkg: ${t.message}") }
        if (!ok) { s.close(); return Result(false, "$pkg has no screen to open (no launcher activity).") }
        open[pkg] = s
        synchronized(live) { live[pkg] = clock() }
        startReaper()
        val note = if (stopped.isEmpty()) "" else " Closed ${stopped.joinToString()} to stay within this phone's memory (max $maxLive at once)."
        Result(true, "Opened $pkg in Blaze Phone.$note", JSONObject().put("display", s.displayId).put("stopped", stopped.joinToString(",")))
    }

    suspend fun tap(pkg: String, x: Int, y: Int): Result = drive(pkg, JSONObject().put("op", "tap").put("x", x).put("y", y)) { "Tapped ($x, $y) in $pkg." }
    suspend fun type(pkg: String, text: String): Result = drive(pkg, JSONObject().put("op", "type").put("text", text)) { "Typed into $pkg." }
    suspend fun back(pkg: String): Result = drive(pkg, JSONObject().put("op", "back")) { "Pressed back in $pkg." }
    /** The guest's top window as GuestViewTree JSON (in `data.tree`). */
    suspend fun dumpViewTree(pkg: String): Result = drive(pkg, JSONObject().put("op", "tree")) { "View tree of $pkg." }
    /** pid and PSS (KB) of the guest's process, from the agent inside it. */
    suspend fun memory(pkg: String): Result = drive(pkg, JSONObject().put("op", "hello")) { "Memory of $pkg." }

    private suspend fun drive(pkg: String, req: JSONObject, ok: () -> String): Result {
        if (synchronized(live) { pkg !in live }) return Result(false, "$pkg isn't open in Blaze Phone.")
        touch(pkg)
        val r = withContext(io) { agents.call(pkg, req) }
            ?: return Result(false, "$pkg isn't answering (it may have closed or crashed).")
        if (!r.optBoolean("ok")) {
            val why = GuestCompat.explainCrash(r.optString("crash").takeIf { it.isNotBlank() && it != "null" })
            return Result(false, "$pkg: " + (why ?: r.optString("error", "failed")) + ".")
        }
        return Result(true, ok(), r)
    }

    /** Stops [pkg] (its process, its display, its frames). */
    suspend fun stop(pkg: String): Result = lock.withLock { if (stopLocked(pkg)) Result(true, "Closed $pkg.") else Result(false, "$pkg wasn't open.") }

    /** The user hands control back to Blaze (or Blaze hands the screen back): the guest is stopped to free its memory. */
    suspend fun handBack(pkg: String): Result = stop(pkg)

    /** Stops guests nobody used for 5 minutes; returns them. */
    suspend fun reapIdle(): List<String> = lock.withLock {
        val idle = BlazePhonePolicy.idle(synchronized(live) { HashMap(live) }, clock())
        idle.forEach { stopLocked(it) }
        idle
    }

    private fun touch(pkg: String) {
        synchronized(live) { if (pkg in live) live[pkg] = clock() }
    }

    private suspend fun stopLocked(pkg: String): Boolean {
        val was = synchronized(live) { live.remove(pkg) != null }
        open.remove(pkg)?.close()
        agents.forget(pkg)
        engine?.let { e -> withContext(io) { try { e.stop(pkg) } catch (_: Throwable) { } } }
        return was
    }

    private fun startReaper() {
        if (reaper?.isActive == true) return
        reaper = scope.launch {
            while (isActive && synchronized(live) { live.isNotEmpty() }) { delay(30_000); reapIdle() }
        }
    }

    companion object {
        @Volatile private var instance: BlazePhone? = null

        /** The app's Blaze Phone. Creating it touches nothing of the engine (see [engineReady]). */
        fun get(ctx: Context): BlazePhone = instance ?: synchronized(this) {
            instance ?: create(ctx.applicationContext).also { instance = it }
        }

        private fun create(app: Context): BlazePhone {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            return BlazePhone(
                hostPkg = app.packageName,
                totalRamBytes = mi.totalMem,
                engineFactory = { BlazePhoneEngine.Real(app) },
                screens = { pkg, w, h, dpi -> GuestScreen(app, pkg, w, h, dpi) },
                agents = SlotAgents(app.packageName),
                inspect = { f -> ApkInspector.read(app, f) },
                confirm = { title, detail -> com.blaze.assistant.agent.Agent.confirm(title, detail, "Blaze Phone", always = true) },
                deviceSdk = android.os.Build.VERSION.SDK_INT,
                deviceAbis = android.os.Build.SUPPORTED_ABIS.toList(),
            )
        }
    }

    /** Finds which guest slot (":p0".."p3") runs a package by asking each slot's agent, and remembers it. */
    class SlotAgents(private val hostPkg: String, private val slots: Int = BlazePhonePolicy.ENGINE_SLOTS) : Agents {
        private val where = HashMap<String, String>()
        override fun call(pkg: String, req: JSONObject): JSONObject? {
            where[pkg]?.let { slot -> GuestAgent.call(hostPkg, slot, req)?.let { return it }; where.remove(pkg) }
            // A guest needs a moment after launch before its agent knows its package: retry for up to 10 s.
            repeat(20) {
                for (i in 0 until slots) {
                    val slot = "$hostPkg:p$i"
                    val hello = GuestAgent.call(hostPkg, slot, JSONObject().put("op", "hello"), 1500) ?: continue
                    if (hello.optString("pkg") == pkg) { where[pkg] = slot; return if (req.optString("op") == "hello") hello else GuestAgent.call(hostPkg, slot, req) }
                }
                Thread.sleep(500)
            }
            return null
        }
        override fun forget(pkg: String) { where.remove(pkg) }
    }
}
