package com.blaze.assistant.proactive

/**
 * v0.27.0: which app visits count towards a "You open X around 19:00 most days" offer. Pure Kotlin (HabitFilterTest).
 *
 * Before this, every window change counted, so a keyboard popping up, the home screen, the notification shade, the
 * permission dialog or a chat bubble floating over another app could become an offer ("open Gboard for you?").
 * Now only an app the user opened on purpose counts: it has a launcher icon, it isn't a keyboard, a home app, part
 * of the system UI, a settings/installer/permission screen or Blaze itself, it filled the screen as a normal app
 * window, and the user stayed in it for at least [MIN_DWELL_MS].
 */
object HabitFilter {
    /** Shorter visits than this (a keyboard flash, a dialog, a wrong tap) never count. */
    const val MIN_DWELL_MS = 4_000L

    /** A window smaller than this share of the screen (a chat head, a picture-in-picture video) is an overlay. */
    const val MIN_SCREEN_SHARE = 0.5f

    /** Why a visit does or doesn't count (OK = it counts). */
    enum class Why { OK, BLANK, SELF, KEYBOARD, HOME, SYSTEM, NOT_LAUNCHABLE, OVERLAY, BRIEF }

    /** System packages that are never a habit even when they have an icon (Settings, the installer, the shade). */
    val systemPackages: Set<String> = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.settings.intelligence",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.intentresolver", "com.android.internal.app", "com.android.shell",
        "com.android.launcher3", "com.google.android.apps.nexuslauncher",
        "com.samsung.android.app.galaxyfinder", "com.sec.android.app.launcher", "com.miui.home", "com.huawei.android.launcher",
        "com.android.providers.media", "com.android.documentsui.picker",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending.billing",
        "com.android.inputsettings", "com.samsung.android.honeyboard", "com.touchtype.swiftkey",
        "com.google.android.inputmethod.latin", "com.android.inputmethod.latin",
    )

    /** Fallback when the package manager can't tell (no icon info): names that are keyboards / system UI. */
    private val systemName = Regex("""(^|\.)(launcher|systemui|inputmethod|keyboard|honeyboard|swiftkey|packageinstaller|permissioncontroller|intentresolver|lockscreen|keyguard)(\.|$)""", RegexOption.IGNORE_CASE)

    /**
     * What Android knows about one package, looked up once and cached by [HabitApps].
     * [launchable] = it has a launcher (home screen) icon.
     */
    data class AppFacts(val pkg: String, val label: String, val launchable: Boolean)

    /** Whether the package itself may ever be a habit (no visit details). */
    fun eligible(pkg: String, selfPkg: String, keyboards: Set<String>, homes: Set<String>, launchable: Boolean?): Why {
        val p = pkg.trim().lowercase()
        if (p.isEmpty()) return Why.BLANK
        if (p == selfPkg.lowercase() || p.startsWith(selfPkg.lowercase() + ":")) return Why.SELF
        if (p in keyboards.map { it.lowercase() }) return Why.KEYBOARD
        if (p in homes.map { it.lowercase() }) return Why.HOME
        if (p in systemPackages || systemName.containsMatchIn(p)) return Why.SYSTEM
        if (launchable == false) return Why.NOT_LAUNCHABLE
        return Why.OK
    }

    /** One finished visit: the package rules plus the window it showed and how long the user stayed. */
    fun verdict(pkg: String, selfPkg: String, keyboards: Set<String>, homes: Set<String>, launchable: Boolean?,
                overlay: Boolean, dwellMs: Long): Why {
        val w = eligible(pkg, selfPkg, keyboards, homes, launchable)
        if (w != Why.OK) return w
        if (overlay) return Why.OVERLAY
        if (dwellMs < MIN_DWELL_MS) return Why.BRIEF
        return Why.OK
    }

    /** Windows that sit on top of the app instead of replacing it: the shade, system dialogs, Play services sheets. */
    private val passingSystem = setOf("android", "com.android.systemui", "com.android.intentresolver",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.google.android.gms")

    /** True for a package whose windows pass over the app in front (a keyboard, the system UI) without ending its visit. */
    fun passes(pkg: String, keyboards: Set<String>): Boolean {
        val p = pkg.trim().lowercase()
        return p in passingSystem || keyboards.any { it.equals(p, ignoreCase = true) } ||
            Regex("""(^|\.)(inputmethod|keyboard|honeyboard|swiftkey|systemui)(\.|$)""").containsMatchIn(p)
    }

    // Mirrors of android.view.accessibility.AccessibilityWindowInfo types (testable on the JVM).
    const val WINDOW_APPLICATION = 1

    /**
     * True when the window isn't the user's app filling the screen: a keyboard, system or accessibility-overlay window,
     * or a normal app window covering less than [MIN_SCREEN_SHARE] of the screen. Unknown window (type < 0) = not an overlay.
     */
    fun isOverlay(windowType: Int, winW: Int, winH: Int, screenW: Int, screenH: Int): Boolean {
        if (windowType < 0) return false
        if (windowType != WINDOW_APPLICATION) return true
        if (winW <= 0 || winH <= 0 || screenW <= 0 || screenH <= 0) return false
        return winW.toFloat() * winH < MIN_SCREEN_SHARE * screenW.toFloat() * screenH
    }

    /** The app's human name for the offer: its label, else the last part of the package made readable. */
    fun label(appLabel: String?, pkg: String): String {
        val l = appLabel?.trim().orEmpty()
        if (l.isNotEmpty() && !l.equals(pkg, ignoreCase = true) && !pkgLike.matches(l)) return l
        val tail = pkg.substringAfterLast('.').ifBlank { pkg }
        return tail.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private val pkgLike = Regex("""^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$""")

    /**
     * Tracks the app in front so a visit is only counted once the user leaves it (then its length is known).
     * Keyboards, the system UI and overlays coming and going on top don't end the visit underneath.
     */
    class Visits {
        private var pkg = ""
        private var since = 0L

        data class Visit(val pkg: String, val start: Long, val dwellMs: Long)

        /**
         * A window came to the front at [now]. [passing] = a keyboard, the system UI or an overlay (a chat head, a
         * picture-in-picture video, a small dialog window) that sits on top of the app instead of replacing it.
         * Returns the visit that just ended, or null.
         */
        fun onWindow(newPkg: String, now: Long, passing: Boolean): Visit? {
            if (passing || newPkg.isBlank() || newPkg == pkg) return null
            val ended = if (pkg.isNotEmpty()) Visit(pkg, since, (now - since).coerceAtLeast(0)) else null
            pkg = newPkg; since = now
            return ended
        }

        /** The visit in front, without ending it (for tests). */
        val current: String get() = pkg
    }
}
