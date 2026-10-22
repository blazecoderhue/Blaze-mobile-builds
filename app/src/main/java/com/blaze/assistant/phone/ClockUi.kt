package com.blaze.assistant.phone

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.view.accessibility.AccessibilityNodeInfo
import com.blaze.assistant.agent.AlarmRows

/**
 * v0.45.13: turns off or deletes one of Blaze's alarms in the phone's own Clock app and READS IT BACK.
 *
 * Android has no API to delete another app's alarm. AlarmClock.ACTION_DISMISS_ALARM only dismisses an alarm that is
 * about to ring on some clocks and does nothing on others (Samsung Clock, 21 Oct 2026 17:13: the alarm stayed ON).
 * So, with phone control on, Blaze drives the Clock screen like a person: open the alarm list, find the row by its
 * "(Blaze A2)" label (or its time), long-press → Delete (delete), or flip its switch (off), then read the list again.
 * Only that second read decides the result ([Outcome.state]). Without phone control nothing can be read back, so the
 * dismiss intent is sent and the result says plainly it isn't confirmed.
 */
object ClockUi {
    enum class State { DELETED, GONE, OFF, ON, STILL_ON, NOT_FOUND, NO_CONTROL, NOT_CLOCK }

    data class Outcome(val state: State, val evidence: String, val trace: List<String> = emptyList())

    private fun clockPkg(p: String) = p.contains("clock", true) || p.contains("deskclock", true) || p == "com.sec.android.app.clockpackage"

    /** A snapshot of the active window as [AlarmRows.Node]s (refs keep the live nodes for clicking). */
    fun snapshot(svc: BlazeAccessibilityService): AlarmRows.Node? {
        val root = svc.rootInActiveWindow ?: return null
        fun conv(n: AccessibilityNodeInfo?, depth: Int): AlarmRows.Node? {
            if (n == null || depth > 30) return null
            val kids = (0 until n.childCount).mapNotNull { conv(n.getChild(it), depth + 1) }
            return AlarmRows.Node(n.text?.toString().orEmpty(), n.contentDescription?.toString().orEmpty(), n.isCheckable, n.isChecked,
                n.isClickable, n.isLongClickable, kids, n)
        }
        return conv(root, 0)
    }

    private fun live(n: AlarmRows.Node?) = n?.ref as? AccessibilityNodeInfo

    private suspend fun openList(ctx: Context, svc: BlazeAccessibilityService, trace: MutableList<String>): AlarmRows.Node? {
        try { ctx.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { return null }
        BlazeAccessibilityService.touched()
        svc.settle(maxMs = 2500, quietMs = 350)
        var pkg = svc.currentPkg()
        if (!clockPkg(pkg)) { kotlinx.coroutines.delay(700); pkg = svc.currentPkg() }
        trace += "front: $pkg"
        if (!clockPkg(pkg)) return null
        return snapshot(svc)
    }

    private suspend fun findWithScroll(svc: BlazeAccessibilityService, first: AlarmRows.Node, pid: String, hhmm: String?, trace: MutableList<String>): Pair<AlarmRows.Node, AlarmRows.Row?> {
        var root = first
        var row = AlarmRows.find(root, pid, hhmm)
        var n = 0
        while (row == null && n < 4) {
            if (!svc.scroll("down", null).let { !it.startsWith("Couldn't") && !it.startsWith("Error") && !it.contains("can't", true) }) break
            svc.settle(maxMs = 900, quietMs = 200)
            root = snapshot(svc) ?: break
            row = AlarmRows.find(root, pid, hhmm); n++
        }
        trace += if (row != null) "row: " + AlarmRows.describe(row) + (if (row.byTime) " (matched by time)" else "") else "row: none after $n scroll(s)"
        return root to row
    }

    private suspend fun click(svc: BlazeAccessibilityService, n: AlarmRows.Node?, long: Boolean = false): Boolean {
        val node = live(n) ?: return false
        BlazeAccessibilityService.touched()
        if (long) {
            var x: AccessibilityNodeInfo? = node
            while (x != null) { if (x.isLongClickable && x.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true; x = x.parent }
            return false
        }
        return svc.tapNode(node)
    }

    /**
     * Delete alarm [pid] ("A2") set for [hhmm] ("18:15"); when the Clock app offers no Delete it is switched off instead.
     * [deleteWanted] = false only switches it off.
     */
    suspend fun alarmOff(ctx: Context, pid: String, hhmm: String?, label: String, deleteWanted: Boolean): Outcome {
        val trace = ArrayList<String>()
        val svc = BlazeAccessibilityService.instance
        if (svc == null) {
            // Nothing can be read back: ask the clock app (works on some clocks for an alarm that is about to ring).
            try {
                ctx.startActivity(Intent(AlarmClock.ACTION_DISMISS_ALARM)
                    .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, label).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                trace += "sent ACTION_DISMISS_ALARM (label)"
            } catch (_: Exception) { trace += "no app handles ACTION_DISMISS_ALARM" }
            return Outcome(State.NO_CONTROL, "phone control is off, so nothing could read the Clock app back", trace)
        }
        val first = openList(ctx, svc, trace) ?: return Outcome(State.NOT_CLOCK, "the Clock app's alarm list didn't come to the front", trace)
        val (root0, row0) = findWithScroll(svc, first, pid, hhmm, trace)
        if (row0 == null) return if (AlarmRows.gone(root0, pid, hhmm)) Outcome(State.GONE, "the Clock app's alarm list has no alarm labelled \u201cBlaze $pid\u201d" + (hhmm?.let { " and none at $it" } ?: ""), trace)
            else Outcome(State.NOT_FOUND, "couldn't find alarm $pid in the Clock app's list", trace)
        if (deleteWanted) {
            if (click(svc, row0.row, long = true) || click(svc, row0.label, long = true)) {
                svc.settle(maxMs = 1200, quietMs = 250)
                val sel = snapshot(svc)
                val del = sel?.let { AlarmRows.deleteButton(it) }
                trace += "long-press: " + (if (del != null) "Delete shown" else "no Delete")
                if (del != null && click(svc, del)) {
                    svc.settle(maxMs = 1500, quietMs = 300)
                    // Some clocks confirm in a dialog ("Delete alarm?" → Delete).
                    snapshot(svc)?.let { s ->
                        val again = AlarmRows.deleteButton(s)
                        if (again != null && AlarmRows.find(s, pid, hhmm) != null) { click(svc, again); svc.settle(maxMs = 1200, quietMs = 250); trace += "confirmed Delete" }
                    }
                    val after = snapshot(svc)
                    if (after != null && clockPkg(svc.currentPkg()) && AlarmRows.find(after, pid, hhmm) == null) {
                        trace += "after: row gone"
                        return Outcome(State.DELETED, "the Clock app's alarm list no longer has \u201c$label\u201d" + (hhmm?.let { " ($it)" } ?: ""), trace)
                    }
                    trace += "after: row still listed"
                } else if (del == null) svc.press("back").also { svc.settle(maxMs = 800, quietMs = 200) }
            } else trace += "long-press not possible"
        }
        // Switch it off (or it's the fallback when Delete couldn't be done).
        val root1 = snapshot(svc) ?: root0
        val row1 = AlarmRows.find(root1, pid, hhmm) ?: row0
        if (row1.on == true) {
            if (click(svc, row1.toggle)) svc.settle(maxMs = 1200, quietMs = 250)
            trace += "tapped its switch"
        } else trace += "switch already " + (if (row1.on == false) "off" else "unknown")
        val after = snapshot(svc)
        val row2 = after?.let { AlarmRows.find(it, pid, hhmm) }
        trace += "after: " + (row2?.let { AlarmRows.describe(it) } ?: "row not found")
        return when {
            row2 == null && after != null -> Outcome(State.GONE, "the Clock app's alarm list no longer has \u201c$label\u201d", trace)
            row2?.on == false -> Outcome(State.OFF, "the switch of \u201c$label\u201d" + (hhmm?.let { " ($it)" } ?: "") + " reads OFF in the Clock app", trace)
            row2?.on == true -> Outcome(State.STILL_ON, "\u201c$label\u201d still reads ON in the Clock app", trace)
            else -> Outcome(State.NOT_FOUND, "couldn't read the alarm's switch back", trace)
        }
    }

    /** Switch alarm [pid] back on, read back. */
    suspend fun alarmOn(ctx: Context, pid: String, hhmm: String?, label: String): Outcome {
        val trace = ArrayList<String>()
        val svc = BlazeAccessibilityService.instance ?: return Outcome(State.NO_CONTROL, "phone control is off, so nothing could switch or read the alarm", trace)
        val first = openList(ctx, svc, trace) ?: return Outcome(State.NOT_CLOCK, "the Clock app's alarm list didn't come to the front", trace)
        val (_, row) = findWithScroll(svc, first, pid, hhmm, trace)
        row ?: return Outcome(State.NOT_FOUND, "couldn't find alarm $pid in the Clock app's list", trace)
        if (row.on == false) { click(svc, row.toggle); svc.settle(maxMs = 1200, quietMs = 250); trace += "tapped its switch" }
        val r2 = snapshot(svc)?.let { AlarmRows.find(it, pid, hhmm) }
        trace += "after: " + (r2?.let { AlarmRows.describe(it) } ?: "row not found")
        return if (r2?.on == true) Outcome(State.ON, "the switch of \u201c$label\u201d reads ON in the Clock app", trace)
            else Outcome(State.NOT_FOUND, "couldn't read its switch as on", trace)
    }

    /** Read-only: the state of alarm [pid] in the Clock list (for the self-test runner). null = can't read. */
    suspend fun read(ctx: Context, pid: String?, hhmm: String?, labelWords: String? = null): Pair<Boolean?, String>? {
        val svc = BlazeAccessibilityService.instance ?: return null
        val trace = ArrayList<String>()
        val first = openList(ctx, svc, trace) ?: return null
        val row = if (pid != null) findWithScroll(svc, first, pid, hhmm, trace).second else null
        val alt = row ?: labelWords?.let { w -> AlarmRows.find(first, "\u0000", hhmm)?.takeIf { AlarmRows.describe(it).contains(w, true) } }
        return (alt?.on) to (alt?.let { AlarmRows.describe(it) } ?: "no matching row")
    }
}
