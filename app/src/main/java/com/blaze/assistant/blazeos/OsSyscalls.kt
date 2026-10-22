package com.blaze.assistant.blazeos

/*
 * v0.36.0 BlazeOS native apps: the host API ("syscalls") a WebAssembly app imports from the "blazeos" module, and
 * the rules that gate each one. Pure Kotlin (no Android, no Wasm engine), unit tested in OsSyscallsTest; the engine
 * side is OsWasmRuntime.kt, the screen side OsWasmApp.kt.
 *
 * Conventions: colours are ARGB in one i32 (0xFFRRGGBB). Coordinates and sizes are f32 in dp, origin top left.
 * Strings are (pointer, length) in the app's memory, UTF-8. Text y is the baseline. Results: 0 or a length on
 * success, negative codes from [OsSys] on failure (never a trap, so an app can carry on without a permission).
 *
 *   canvas   clear(argb) · rect(x, y, w, h, radius, argb) · circle(cx, cy, r, argb)
 *            line(x1, y1, x2, y2, width, argb) · text(ptr, len, x, y, size, argb, align 0 left|1 centre|2 right)
 *            image(pathPtr, pathLen, x, y, w, h) -> code      (a png / jpg / webp in the app's package)
 *            width() -> f32 · height() -> f32
 *   storage  store_get(kPtr, kLen, bufPtr, cap) -> length | code · store_set(kPtr, kLen, vPtr, vLen) -> code
 *            store_remove(kPtr, kLen) -> code                 (needs "storage"; the app's own store, as web apps)
 *   notify   notify(titlePtr, titleLen, bodyPtr, bodyLen) -> code   (needs "notifications"; asks the first time)
 *   theme    theme_dark() -> 0|1 · theme_accent() -> argb · font_scale() -> f32
 *   time     now_ms() -> f64 (wall clock) · uptime_ms() -> f64 (monotonic) · random() -> f64 in [0, 1)
 *   log      log(ptr, len)
 *
 * What the app exports (all optional except memory): memory, init(w, h), resize(w, h), frame(t_ms f64),
 * on_touch(kind 0 down|1 move|2 up|3 cancel, x, y, pointerId), on_gesture(kind 1 swipe|2 long press, dx, dy),
 * on_key(code, down), on_back() -> 1 handled | 0, on_pause(), on_resume().
 */

/** Result codes every syscall shares. */
object OsSys {
    const val OK = 0
    const val NOT_FOUND = -1
    /** The manifest doesn't list the permission this call needs. */
    const val NOT_DECLARED = -2
    /** The user turned the permission off (Settings › Apps). */
    const val DENIED = -3
    const val BAD_ARGS = -4
    const val TOO_BIG = -5
    /** The user is being asked; the call didn't happen this time. */
    const val ASKING = 1

    const val MODULE = "blazeos"

    /** Biggest key / value / text an app can hand over in one call, bytes. */
    const val MAX_KEY = 200
    const val MAX_VALUE = 256 * 1024
    const val MAX_TEXT = 2000
    /** One app's whole store (same cap as a web app's storage.json). */
    const val MAX_STORE = 2 * 1024 * 1024
    /** Draw commands kept per frame (a runaway loop can't eat the heap). */
    const val MAX_COMMANDS = 20_000
}

/** The value types a syscall uses (mirrors Wasm's). */
enum class WType { I32, F32, F64 }

/** One host call: its name in the "blazeos" module, its signature and the permission it needs (null = none). */
data class Syscall(val name: String, val params: List<WType>, val results: List<WType>, val perm: String? = null, val group: String)

object OsSyscalls {
    private val I = WType.I32; private val F = WType.F32; private val D = WType.F64

    val all: List<Syscall> = listOf(
        Syscall("clear", listOf(I), emptyList(), group = "canvas"),
        Syscall("rect", listOf(F, F, F, F, F, I), emptyList(), group = "canvas"),
        Syscall("circle", listOf(F, F, F, I), emptyList(), group = "canvas"),
        Syscall("line", listOf(F, F, F, F, F, I), emptyList(), group = "canvas"),
        Syscall("text", listOf(I, I, F, F, F, I, I), emptyList(), group = "canvas"),
        Syscall("image", listOf(I, I, F, F, F, F), listOf(I), group = "canvas"),
        Syscall("width", emptyList(), listOf(F), group = "canvas"),
        Syscall("height", emptyList(), listOf(F), group = "canvas"),
        Syscall("store_get", listOf(I, I, I, I), listOf(I), OsPerms.STORAGE, "storage"),
        Syscall("store_set", listOf(I, I, I, I), listOf(I), OsPerms.STORAGE, "storage"),
        Syscall("store_remove", listOf(I, I), listOf(I), OsPerms.STORAGE, "storage"),
        Syscall("notify", listOf(I, I, I, I), listOf(I), OsPerms.NOTIFICATIONS, "notify"),
        Syscall("theme_dark", emptyList(), listOf(I), group = "theme"),
        Syscall("theme_accent", emptyList(), listOf(I), group = "theme"),
        Syscall("font_scale", emptyList(), listOf(F), group = "theme"),
        Syscall("now_ms", emptyList(), listOf(D), group = "time"),
        Syscall("uptime_ms", emptyList(), listOf(D), group = "time"),
        Syscall("random", emptyList(), listOf(D), group = "time"),
        Syscall("log", listOf(I, I), emptyList(), group = "log"),
    )
    private val byName = all.associateBy { it.name }
    fun get(name: String): Syscall? = byName[name]

    /** What the gate says about one call. */
    enum class Gate { ALLOW, ASK, NOT_DECLARED, DENIED }

    /**
     * May [call] run for an app that declared [declared] permissions, given the user's [decision] for the permission
     * it needs? Calls with no permission always run. A permission the manifest doesn't list is refused outright (an
     * app can't ask for what it didn't declare). One the user turned off is refused. Storage is the app's own private
     * store, so a declared, not-refused "storage" runs without a prompt (as web apps' storage does); notifications ask
     * the first time.
     */
    fun gate(call: Syscall, declared: Collection<String>, decision: OsGrants.Decision): Gate {
        val p = call.perm ?: return Gate.ALLOW
        if (p !in declared) return Gate.NOT_DECLARED
        return when (decision) {
            OsGrants.Decision.DENIED -> Gate.DENIED
            OsGrants.Decision.ALLOWED -> Gate.ALLOW
            OsGrants.Decision.ASK -> if (p == OsPerms.STORAGE) Gate.ALLOW else Gate.ASK
        }
    }

    /** The code a refused call returns. */
    fun code(g: Gate): Int = when (g) { Gate.ALLOW -> OsSys.OK; Gate.ASK -> OsSys.ASKING; Gate.NOT_DECLARED -> OsSys.NOT_DECLARED; Gate.DENIED -> OsSys.DENIED }

    /** The syscalls a module imports that BlazeOS doesn't have (or with the wrong signature): it can't start. */
    fun unknownImports(imports: List<Triple<String, List<WType>, List<WType>>>): List<String> = imports.mapNotNull { (name, p, r) ->
        val s = byName[name]
        when {
            s == null -> "$name (no such call)"
            s.params != p || s.results != r -> "$name (wrong signature)"
            else -> null
        }
    }

    /** Android key codes → the DOM-style codes apps see (arrows 37-40, Enter 13, Space 32, Esc 27, letters A-Z 65-90, digits 48-57). */
    fun keyCode(android: Int): Int = when (android) {
        21 -> 37; 19 -> 38; 22 -> 39; 20 -> 40          // DPAD left / up / right / down
        66, 160, 23 -> 13                                // Enter, numpad Enter, DPAD centre
        62 -> 32; 111 -> 27; 67 -> 8; 61 -> 9           // Space, Escape, Backspace (DEL), Tab
        in 29..54 -> 65 + (android - 29)                 // A-Z
        in 7..16 -> 48 + (android - 7)                   // 0-9
        else -> 0
    }

    /** A swipe from a drag: at least [min] dp, mostly along one axis; (dx, dy) of the drag, or null. */
    fun swipe(dx: Float, dy: Float, min: Float = 24f): Pair<Float, Float>? =
        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) < min) null else dx to dy
}

/** One thing to draw. A frame is a list of these, built by the app's thread and drawn by Compose. */
sealed class DrawCmd {
    data class Clear(val argb: Int) : DrawCmd()
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float, val argb: Int) : DrawCmd()
    data class Circle(val cx: Float, val cy: Float, val r: Float, val argb: Int) : DrawCmd()
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, val argb: Int) : DrawCmd()
    data class Text(val text: String, val x: Float, val y: Float, val size: Float, val argb: Int, val align: Int) : DrawCmd()
    data class Image(val path: String, val x: Float, val y: Float, val w: Float, val h: Float) : DrawCmd()
}

/** Input the screen hands the app's thread (delivered in order before the next frame). */
sealed class OsInput {
    data class Touch(val kind: Int, val x: Float, val y: Float, val id: Int) : OsInput()
    data class Gesture(val kind: Int, val dx: Float, val dy: Float) : OsInput()
    data class Key(val code: Int, val down: Boolean) : OsInput()
    data class Resize(val w: Float, val h: Float) : OsInput()
    object Back : OsInput()

    companion object {
        const val DOWN = 0; const val MOVE = 1; const val UP = 2; const val CANCEL = 3
        const val SWIPE = 1; const val LONG_PRESS = 2
    }
}
