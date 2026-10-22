package com.blaze.assistant.blazeos

import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.FunctionImport
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.dylibso.chicory.wasm.types.ValType
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/*
 * v0.36.0 BlazeOS native apps: one WebAssembly app running in the Chicory interpreter (pure JVM: no NDK, nothing
 * per CPU). The syscalls in OsSyscalls.kt are bound here as host functions; each one passes the permission gate
 * before it does anything. Everything Android-specific (the app's store on the BlazeOS disk, banners, permission
 * prompts, theme) goes through [OsWasmHost], so the whole runtime runs in a plain JVM unit test (OsWasmRuntimeTest
 * plays the real Snake module).
 *
 * Threading: [load], [step] and [deliver] are called from the app's own thread only (OsWasmApp.kt runs that loop);
 * the screen reads [frame] (an immutable list, swapped whole after each frame) and pushes input into [input].
 * Kill = interrupt that thread: Chicory checks the interrupt flag in loops and throws ChicoryInterruptedException.
 *
 * Memory: Chicory's ByteArrayMemory uses VarHandle (ART has it only from Android 13), so memories are made with
 * ByteBufferMemory. A module may not ask for more than [maxPages] pages (64 KiB each); memory.grow past it fails
 * inside the app (returns -1) instead of taking the phone's RAM.
 */

/** What a native app reaches outside its sandbox. Android implementation: OsWasmApp.kt (AndroidWasmHost). */
interface OsWasmHost {
    val appId: String
    val label: String
    /** The permissions the app's manifest lists. */
    val declared: List<String>
    fun decide(perm: String): OsGrants.Decision
    /** The app's whole store as JSON text (null = empty). Same file as a web app's storage.json. */
    fun readStore(): String?
    fun writeStore(json: String)
    /** Shows a BlazeOS banner (the gate already passed). */
    fun notify(title: String, body: String)
    /** Asks the user for [perm] (BlazeOS's own prompt); [then] runs with the answer, on any thread. */
    fun ask(perm: String, then: (Boolean) -> Unit)
    fun dark(): Boolean
    fun accent(): Int
    fun fontScale(): Float
    /** True when [path] is an image file inside the app's package. */
    fun hasImage(path: String): Boolean
    fun log(msg: String)
}

class OsWasmRuntime(private val host: OsWasmHost, private val maxPages: Int = DEFAULT_MAX_PAGES) {
    companion object {
        /** 16 MiB of app memory by default (256 pages); BlazeOS's budget can lower it on small phones. */
        const val DEFAULT_MAX_PAGES = 256
        /** Biggest .wasm accepted, bytes. */
        const val MAX_MODULE = 8 * 1024 * 1024

        private fun vt(t: WType): ValType = when (t) { WType.I32 -> ValType.I32; WType.F32 -> ValType.F32; WType.F64 -> ValType.F64 }
        private fun wt(t: ValType): WType? = when (t) { ValType.I32 -> WType.I32; ValType.F32 -> WType.F32; ValType.F64 -> WType.F64; else -> null }

        fun f32(v: Long): Float = java.lang.Float.intBitsToFloat(v.toInt())
        fun f32(v: Float): Long = java.lang.Float.floatToRawIntBits(v).toLong() and 0xFFFFFFFFL
        fun f64(v: Double): Long = java.lang.Double.doubleToRawLongBits(v)
        fun i32(v: Int): Long = v.toLong()

        /** Why [bytes] can't run as a BlazeOS app, or null when it can (a quick check before anything starts). */
        fun check(bytes: ByteArray, maxPages: Int = DEFAULT_MAX_PAGES): String? {
            if (bytes.size > MAX_MODULE) return "The app is too big (${bytes.size / 1024} KB; the limit is ${MAX_MODULE / 1024 / 1024} MB)"
            if (bytes.size < 8 || bytes[0] != 0.toByte() || bytes[1] != 'a'.code.toByte() || bytes[2] != 's'.code.toByte() || bytes[3] != 'm'.code.toByte())
                return "This isn't a WebAssembly app"
            val m = try { Parser.parse(bytes) } catch (e: Exception) { return "The app is damaged (${e.message?.take(80)})" }
            return problems(m, maxPages)
        }

        private fun problems(m: WasmModule, maxPages: Int): String? {
            val imps = m.importSection()
            val calls = ArrayList<Triple<String, List<WType>, List<WType>>>()
            for (i in 0 until imps.importCount()) {
                val imp = imps.getImport(i)
                if (imp !is FunctionImport) return "It imports ${imp.module()}.${imp.name()}, which BlazeOS doesn't give apps"
                if (imp.module() != OsSys.MODULE) return "It needs ${imp.module()}.${imp.name()} (not a BlazeOS call)"
                val t = m.typeSection().getType(imp.typeIndex())
                val p = t.params().map { wt(it) ?: return "${imp.name()} uses a type BlazeOS doesn't pass" }
                val r = t.returns().map { wt(it) ?: return "${imp.name()} uses a type BlazeOS doesn't pass" }
                calls += Triple(imp.name(), p, r)
            }
            OsSyscalls.unknownImports(calls).firstOrNull()?.let { return "It calls $it" }
            val mem = m.memorySection().orElse(null)
            if (mem == null || mem.memoryCount() == 0) return "It has no memory to draw text from"
            val init = mem.getMemory(0).limits().initialPages()
            if (init > maxPages) return "It wants ${init * 64 / 1024} MB of memory; BlazeOS gives an app ${maxPages * 64 / 1024} MB"
            return null
        }
    }

    /** The last finished frame (drawn by the screen). */
    @Volatile var frame: List<DrawCmd> = emptyList(); private set
    /** Bumped after each frame, so the screen knows to redraw. */
    @Volatile var frameNo: Long = 0; private set
    /** Input for the app, delivered on its thread before the next frame. */
    val input = ConcurrentLinkedQueue<OsInput>()
    /** Lines the app logged (newest last, capped). */
    val logs = ArrayDeque<String>()
    /** Why the app stopped (a trap, a bad module), or null while fine. */
    @Volatile var error: String? = null; private set
    /** Calls the gate refused, by name (shown in the app's info so a developer sees what's missing). */
    val refused = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private var instance: Instance? = null
    private var memory: Memory? = null
    private val exports = HashMap<String, ExportFunction?>()
    private var building = ArrayList<DrawCmd>()
    @Volatile private var width = 360f
    @Volatile private var height = 640f
    private val started = System.nanoTime()
    private val rng = java.util.Random()
    private val storeLock = Any()

    val running get() = instance != null && error == null

    /** Parses, checks and instantiates [bytes], then calls init(w, h). False (with [error]) when it can't start. */
    fun load(bytes: ByteArray, w: Float, h: Float): Boolean {
        width = w; height = h
        check(bytes, maxPages)?.let { error = it; return false }
        return try {
            val m = Parser.parse(bytes)
            val lim = m.memorySection().get().getMemory(0).limits()
            val max = minOf(lim.maximumPages(), maxPages).coerceAtLeast(lim.initialPages())
            val inst = Instance.builder(m)
                .withImportValues(ImportValues.builder().withFunctions(hostFunctions()).build())
                .withMemoryLimits(MemoryLimits(lim.initialPages(), max))
                .withMemoryFactory { l -> ByteBufferMemory(l) }
                .build()
            instance = inst
            memory = inst.memory()
            call("init", f32(w), f32(h))
            error == null
        } catch (e: Throwable) {
            fail(e); false
        }
    }

    private fun export(name: String): ExportFunction? = exports.getOrPut(name) {
        try { instance?.export(name) } catch (_: Exception) { null }
    }

    /** Calls an export if the app has it; a trap stops the app with a readable reason. */
    private fun call(name: String, vararg args: Long): LongArray? {
        if (error != null) return null
        val f = export(name) ?: return null
        return try { f.apply(*args) } catch (e: Throwable) { fail(e); null }
    }

    private fun fail(e: Throwable) {
        if (e is InterruptedException || e.javaClass.simpleName == "ChicoryInterruptedException") { error = "Closed"; return }
        error = when (e) {
            is OutOfMemoryError -> "The app ran out of memory"
            is StackOverflowError -> "The app recursed too deeply"
            else -> "The app stopped: " + (e.message ?: e.javaClass.simpleName).take(160)
        }
    }

    /** Hands queued input to the app (in order). Back returns through [onBack]. */
    fun deliver(onBack: (Boolean) -> Unit = {}) {
        while (true) {
            val ev = input.poll() ?: break
            when (ev) {
                is OsInput.Touch -> call("on_touch", i32(ev.kind), f32(ev.x), f32(ev.y), i32(ev.id))
                is OsInput.Gesture -> call("on_gesture", i32(ev.kind), f32(ev.dx), f32(ev.dy))
                is OsInput.Key -> call("on_key", i32(ev.code), i32(if (ev.down) 1 else 0))
                is OsInput.Resize -> { width = ev.w; height = ev.h; call("resize", f32(ev.w), f32(ev.h)) }
                OsInput.Back -> onBack((call("on_back")?.firstOrNull() ?: 0L).toInt() != 0)
            }
        }
    }

    /** Runs one frame at time [tMs] and publishes what it drew. */
    fun step(tMs: Double) {
        if (!running) return
        building = ArrayList(building.size.coerceAtLeast(16))
        call("frame", f64(tMs))
        frame = building
        frameNo++
    }

    fun pause() { call("on_pause") }
    fun resume() { call("on_resume") }

    /** Drops the instance (its memory goes with it). */
    fun close() { instance = null; memory = null; exports.clear(); frame = emptyList() }

    /** Pages of memory the app uses now. */
    fun pages(): Int = try { memory?.pages() ?: 0 } catch (_: Exception) { 0 }

    // ---------------- syscalls ----------------

    private fun str(ptr: Long, len: Long, max: Int): String? {
        val p = ptr.toInt(); val n = len.toInt()
        val mem = memory ?: return null
        if (p < 0 || n < 0 || n > max || p.toLong() + n > Memory.bytes(mem.pages()).toLong()) return null
        return String(mem.readBytes(p, n), Charsets.UTF_8)
    }

    private fun draw(c: DrawCmd) { if (building.size < OsSys.MAX_COMMANDS) building.add(c) }

    /** Runs [body] when the gate lets [name] through; otherwise returns the refusal code (and counts it). */
    private inline fun gated(name: String, body: () -> Int): Int {
        val call = OsSyscalls.get(name)!!
        val g = OsSyscalls.gate(call, host.declared, call.perm?.let { host.decide(it) } ?: OsGrants.Decision.ALLOWED)
        if (g == OsSyscalls.Gate.ALLOW) return body()
        refused.merge(name, 1, Int::plus)
        return OsSyscalls.code(g)
    }

    private fun store(): JSONObject = synchronized(storeLock) { try { JSONObject(host.readStore() ?: "{}") } catch (_: Exception) { JSONObject() } }

    private fun impl(name: String, a: LongArray): LongArray? = when (name) {
        "clear" -> { draw(DrawCmd.Clear(a[0].toInt())); null }
        "rect" -> { draw(DrawCmd.Rect(f32(a[0]), f32(a[1]), f32(a[2]), f32(a[3]), f32(a[4]), a[5].toInt())); null }
        "circle" -> { draw(DrawCmd.Circle(f32(a[0]), f32(a[1]), f32(a[2]), a[3].toInt())); null }
        "line" -> { draw(DrawCmd.Line(f32(a[0]), f32(a[1]), f32(a[2]), f32(a[3]), f32(a[4]), a[5].toInt())); null }
        "text" -> { str(a[0], a[1], OsSys.MAX_TEXT)?.let { draw(DrawCmd.Text(it, f32(a[2]), f32(a[3]), f32(a[4]), a[5].toInt(), a[6].toInt().coerceIn(0, 2))) }; null }
        "image" -> {
            val p = str(a[0], a[1], 200)
            val code = when {
                p == null || !OsManifest.safePath(p) -> OsSys.BAD_ARGS
                !host.hasImage(p) -> OsSys.NOT_FOUND
                else -> { draw(DrawCmd.Image(p, f32(a[2]), f32(a[3]), f32(a[4]), f32(a[5]))); OsSys.OK }
            }
            longArrayOf(i32(code))
        }
        "width" -> longArrayOf(f32(width))
        "height" -> longArrayOf(f32(height))
        "store_get" -> longArrayOf(i32(gated(name) {
            val k = str(a[0], a[1], OsSys.MAX_KEY) ?: return@gated OsSys.BAD_ARGS
            val o = store()
            if (!o.has(k)) return@gated OsSys.NOT_FOUND
            val v = o.optString(k).toByteArray(Charsets.UTF_8)
            val buf = a[2].toInt(); val cap = a[3].toInt()
            val mem = memory ?: return@gated OsSys.BAD_ARGS
            if (buf < 0 || cap < 0 || buf.toLong() + cap > Memory.bytes(mem.pages()).toLong()) return@gated OsSys.BAD_ARGS
            if (v.size > cap) return@gated OsSys.TOO_BIG
            mem.write(buf, v)
            v.size
        }))
        "store_set" -> longArrayOf(i32(gated(name) {
            val k = str(a[0], a[1], OsSys.MAX_KEY) ?: return@gated OsSys.BAD_ARGS
            val v = str(a[2], a[3], OsSys.MAX_VALUE) ?: return@gated OsSys.BAD_ARGS
            synchronized(storeLock) {
                val o = store().put(k, v)
                val text = o.toString()
                if (text.length > OsSys.MAX_STORE) return@gated OsSys.TOO_BIG
                host.writeStore(text)
            }
            OsSys.OK
        }))
        "store_remove" -> longArrayOf(i32(gated(name) {
            val k = str(a[0], a[1], OsSys.MAX_KEY) ?: return@gated OsSys.BAD_ARGS
            synchronized(storeLock) { val o = store(); if (o.remove(k) == null) return@gated OsSys.NOT_FOUND; host.writeStore(o.toString()) }
            OsSys.OK
        }))
        "notify" -> {
            val t = str(a[0], a[1], 400); val b = str(a[2], a[3], 1000)
            val call = OsSyscalls.get(name)!!
            val g = OsSyscalls.gate(call, host.declared, host.decide(OsPerms.NOTIFICATIONS))
            val code = when {
                t == null || b == null -> OsSys.BAD_ARGS
                g == OsSyscalls.Gate.ALLOW -> { host.notify(t.take(80), b.take(200)); OsSys.OK }
                g == OsSyscalls.Gate.ASK -> { host.ask(OsPerms.NOTIFICATIONS) { ok -> if (ok) host.notify(t.take(80), b.take(200)) }; OsSys.ASKING }
                else -> { refused.merge(name, 1, Int::plus); OsSyscalls.code(g) }
            }
            longArrayOf(i32(code))
        }
        "theme_dark" -> longArrayOf(i32(if (host.dark()) 1 else 0))
        "theme_accent" -> longArrayOf(i32(host.accent()))
        "font_scale" -> longArrayOf(f32(host.fontScale()))
        "now_ms" -> longArrayOf(f64(System.currentTimeMillis().toDouble()))
        "uptime_ms" -> longArrayOf(f64((System.nanoTime() - started) / 1e6))
        "random" -> longArrayOf(f64(rng.nextDouble()))
        "log" -> {
            str(a[0], a[1], OsSys.MAX_TEXT)?.let { line ->
                synchronized(logs) { logs.addLast(line); while (logs.size > 200) logs.removeFirst() }
                host.log(line)
            }
            null
        }
        else -> null
    }

    private fun hostFunctions(): List<HostFunction> = OsSyscalls.all.map { s ->
        HostFunction(OsSys.MODULE, s.name, FunctionType.of(s.params.map { vt(it) }, s.results.map { vt(it) })) { _, args -> impl(s.name, args) }
    }
}
