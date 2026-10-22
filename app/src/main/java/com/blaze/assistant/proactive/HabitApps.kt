package com.blaze.assistant.proactive

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.27.0: the Android side of [HabitFilter]. Looks up (and caches) which packages are keyboards, home apps and
 * launchable apps, follows the app in front through [HabitFilter.Visits], and hands only real, deliberate visits to
 * [Habits]. Called from BlazeAccessibilityService on every window change; all lookups are cheap after the first.
 */
object HabitApps {
    private val facts = ConcurrentHashMap<String, HabitFilter.AppFacts>()
    @Volatile private var keyboards: Set<String> = emptySet()
    @Volatile private var homes: Set<String> = emptySet()
    @Volatile private var listsAt = 0L
    private val visits = HabitFilter.Visits()

    /** Keyboards and home apps change rarely; re-read them every 10 minutes. */
    private fun lists(ctx: Context) {
        val now = SystemClock.uptimeMillis()
        if (listsAt != 0L && now - listsAt < 600_000) return
        listsAt = now
        keyboards = try {
            ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.inputMethodList
                ?.map { it.packageName.lowercase() }?.toSet() ?: emptySet()
        } catch (_: Throwable) { emptySet() }
        homes = try {
            ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .mapNotNull { it.activityInfo?.packageName?.lowercase() }.toSet()
        } catch (_: Throwable) { emptySet() }
    }

    fun facts(ctx: Context, pkg: String): HabitFilter.AppFacts = facts.getOrPut(pkg) {
        val pm = ctx.packageManager
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { null }
        val launchable = try { pm.getLaunchIntentForPackage(pkg) != null } catch (_: Throwable) { false }
        HabitFilter.AppFacts(pkg, HabitFilter.label(label, pkg), launchable)
    }

    /**
     * A TYPE_WINDOW_STATE_CHANGED from [pkg]. [window] gives the event window's type and size ((−1, 0, 0) if unknown).
     * When this ends a visit that counts, [Habits.onApp] records it at the hour the visit started.
     */
    fun onWindow(ctx: Context, pkg: String, window: () -> Triple<Int, Int, Int>, screenW: Int, screenH: Int, now: Long = System.currentTimeMillis()) {
        if (pkg.isBlank() || pkg == visits.current) return   // same app, new screen: nothing to look up
        lists(ctx)
        val (windowType, winW, winH) = window()
        val overlay = HabitFilter.isOverlay(windowType, winW, winH, screenW, screenH)
        val ended = visits.onWindow(pkg, now, passing = overlay || HabitFilter.passes(pkg, keyboards)) ?: return
        val f = facts(ctx, ended.pkg)
        val why = HabitFilter.verdict(ended.pkg, ctx.packageName, keyboards, homes, f.launchable, overlay = false, dwellMs = ended.dwellMs)
        if (why == HabitFilter.Why.OK) Habits.onApp(ended.pkg, f.label, ended.start)
    }

    /** The window an event came from: its type and size, or (−1, 0, 0) when Android doesn't say. */
    fun windowOf(svc: android.accessibilityservice.AccessibilityService, e: AccessibilityEvent): Triple<Int, Int, Int> = try {
        val w = svc.windows.firstOrNull { it.id == e.windowId }
        if (w == null) Triple(-1, 0, 0) else {
            val r = android.graphics.Rect(); w.getBoundsInScreen(r)
            Triple(w.type, r.width(), r.height())
        }
    } catch (_: Throwable) { Triple(-1, 0, 0) }
}
