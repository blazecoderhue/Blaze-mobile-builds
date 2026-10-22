package com.blaze.assistant.phone

/**
 * Magic Hand safety (v0.25.0), pure part (MagicHandSafetyTest). After a low-end phone got stuck in teach mode
 * (Home / Back / Recents dead, "Blaze isn't responding"), these are the rules every Magic Hand window and event
 * path follows:
 *  - window flags: only the hand and the dock take touches, and never touches outside themselves
 *    (NOT_TOUCH_MODAL always); the highlight layer never takes any; the dock sits above the navigation bar;
 *  - teach mode records only the app being taught: System UI, Android, launchers, screenshot tools, keyboards and
 *    Blaze itself are ignored, and labels that are really class names ("MainUiViewForStandAlone$7") are dropped;
 *  - escape hatches: Back twice, a long-press on the dock, the "Stop Magic Hand" notification action, and a
 *    watchdog that ends Magic Hand when the UI thread stalls for over 3 s or after 10 minutes without a touch.
 */
object MagicHandSafety {
    // Mirrors of android.view.WindowManager.LayoutParams flag values, so the rules are testable on the JVM.
    const val FLAG_NOT_FOCUSABLE = 0x00000008
    const val FLAG_NOT_TOUCHABLE = 0x00000010
    const val FLAG_NOT_TOUCH_MODAL = 0x00000020
    const val FLAG_LAYOUT_IN_SCREEN = 0x00000100
    const val FLAG_LAYOUT_NO_LIMITS = 0x00000200
    const val FLAG_WATCH_OUTSIDE_TOUCH = 0x00040000

    /** BOX (v0.26.0) = the movable, resizable grab rectangle: a window just around the box, only while grabbing. */
    /** DRAW (v0.27.0) = Grab mode's drawing layer: the screen above the navigation bar, touchable only while Grab is on. */
    enum class Win { HIGHLIGHT, HAND, DOCK, BOX, DRAW }

    /**
     * The flags for each Magic Hand window. [typing] = the dock's name field needs the keyboard (teach review only).
     * [active] = the grab box is showing (otherwise its window takes no touches at all).
     */
    fun flags(w: Win, typing: Boolean = false, active: Boolean = true): Int = when (w) {
        Win.HIGHLIGHT -> FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_IN_SCREEN
        Win.HAND -> FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_NO_LIMITS
        Win.DOCK -> FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH or (if (typing) 0 else FLAG_NOT_FOCUSABLE)
        Win.BOX -> FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS or
            (if (active) 0 else FLAG_NOT_TOUCHABLE)
        Win.DRAW -> FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_IN_SCREEN or (if (active) 0 else FLAG_NOT_TOUCHABLE)
    }

    /** True when a window with [flags] could swallow touches meant for other apps or the system bars. */
    fun blocksOthers(flags: Int): Boolean {
        val touchable = flags and FLAG_NOT_TOUCHABLE == 0
        val focusable = flags and FLAG_NOT_FOCUSABLE == 0
        val modal = flags and FLAG_NOT_TOUCH_MODAL == 0
        // A touchable window that is focusable and touch-modal takes every touch on the screen.
        return touchable && focusable && modal
    }

    /** Where the hand may sit: inside the screen and above the navigation bar (its top-left corner, in px). */
    fun clamp(x: Int, y: Int, size: Int, screenW: Int, screenH: Int, bottomInset: Int, topInset: Int = 0): Pair<Int, Int> {
        val maxX = (screenW - size).coerceAtLeast(0)
        val maxY = (screenH - bottomInset - size).coerceAtLeast(topInset)
        return x.coerceIn(0, maxX) to y.coerceIn(topInset, maxY)
    }

    /** The hand's first spot: the middle of the screen (a little above centre), never under the dock or the nav bar. */
    fun startSpot(size: Int, screenW: Int, screenH: Int, bottomInset: Int, topInset: Int = 0): Pair<Int, Int> =
        clamp(screenW / 2 - size / 2, (screenH - bottomInset) * 2 / 5 - size / 2, size, screenW, screenH, bottomInset, topInset)

    // ---------- teach filter ----------
    private val alwaysIgnored = setOf(
        "android", "com.android.systemui", "com.android.launcher", "com.android.launcher3", "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher", "com.miui.home", "com.huawei.android.launcher", "com.oppo.launcher",
        "com.samsung.android.app.smartcapture", "com.samsung.android.honeyboard", "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey", "com.samsung.android.app.cocktailbarservice", "com.samsung.android.app.aodservice",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.intentresolver",
    )
    private val ignoredParts = listOf("launcher", "screenshot", "screencapture", "smartcapture", "inputmethod", "keyboard", "systemui")

    /**
     * True for events teach mode must skip. [launchers] / [imes] are the phone's own home apps and keyboards
     * (looked up when teaching starts), so any brand's launcher and keyboard are ignored too.
     */
    fun ignoreForTeach(pkg: String, ownPkg: String, launchers: Set<String> = emptySet(), imes: Set<String> = emptySet()): Boolean {
        val p = pkg.trim().lowercase()
        if (p.isEmpty() || p == ownPkg.lowercase() || p.startsWith(ownPkg.lowercase() + ":")) return true
        if (p in alwaysIgnored || p in launchers || p in imes) return true
        return ignoredParts.any { it in p }
    }

    private val classLike = Regex("""^[A-Za-z_][\w]*([.$][\w]+)*$""")

    /** A label a person would recognise, or "" (class names like "MainUiViewForStandAlone$7" or "FrameLayout" are not). */
    fun humanLabel(raw: String?): String {
        val t = raw.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return ""
        if ('$' in t) return ""
        if (t.startsWith("android.") || t.startsWith("com.") || t.startsWith("androidx.")) return ""
        // One CamelCase word with two or more humps and no spaces is a class name, not a button label.
        if (' ' !in t && classLike.matches(t) && Regex("[a-z][A-Z]").findAll(t).count() >= 2) return ""
        if (t.endsWith("Layout") || t.endsWith("View") || t.endsWith("Activity") || t.endsWith("Fragment")) return if (' ' in t) t else ""
        return t.take(80)
    }

    // ---------- escape hatches ----------
    const val BACK_TWICE_MS = 700L
    const val STALL_MS = 3_000L
    const val IDLE_MS = 10 * 60_000L

    /** Back twice within [BACK_TWICE_MS] exits. Feed it each Back press; true = exit now. */
    class BackTwice {
        private var last = -1L
        fun press(now: Long): Boolean {
            val hit = last >= 0 && now - last <= BACK_TWICE_MS
            last = if (hit) -1L else now
            return hit
        }
    }

    enum class Verdict { OK, STALLED, IDLE }

    /** The watchdog's call: the UI thread last answered at [beatAt], the user last touched Magic Hand at [touchAt]. */
    fun watchdog(now: Long, beatAt: Long, touchAt: Long): Verdict = when {
        now - beatAt > STALL_MS -> Verdict.STALLED
        now - touchAt > IDLE_MS -> Verdict.IDLE
        else -> Verdict.OK
    }

    const val TEACH_INTRO = "Teach Blaze a routine: tap Start, do the task once in any app, tap Done. Blaze saves it as a skill it can repeat for you, after asking."
    const val STALLED_NOTE = "Magic Hand closed because Blaze stopped responding."
    const val IDLE_NOTE = "Magic Hand closed after 10 minutes without use."
}
