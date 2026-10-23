package com.blaze.assistant.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Blaze's "hands": reads what is on screen and taps / types / scrolls.
 * The user turns it on in Settings > Accessibility > Blaze phone control.
 */
class BlazeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: BlazeAccessibilityService? = null
            private set

        /** v0.45.1: something may have changed the screen (an app opened, a tool ran): the next read walks the tree again. */
        fun touched() { instance?.acted() }

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(ctx.packageName + "/")
        }
    }

    /** Elements from the last read_screen, addressed by their number. */
    private val elements = mutableListOf<AccessibilityNodeInfo?>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        com.blaze.assistant.device.CrashLog.write("Phone control connected")
        try { com.blaze.assistant.bank.BankMode.a11yBack() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.error("bank restore reset", e) }
        com.blaze.assistant.voice.WakeWordService.start(this)
        if (com.blaze.assistant.agent.Settings.orbPinned) com.blaze.assistant.ui.Overlay.showOrb(this)
        // v0.43.0: the user switched phone control back on by hand while Bank mode was on: Bank mode ends.
        if (com.blaze.assistant.bank.BankMode.active && !com.blaze.assistant.bank.BankMode.state.value.auto) com.blaze.assistant.bank.BankMode.turnOff(this)
    }

    /** v0.43.0: true when the app in front is on the user's bank list. Blaze never reads or touches banking apps. */
    private fun bankInFront(): Boolean = try {
        com.blaze.assistant.bank.BankMode.isBank(rootInActiveWindow?.packageName?.toString().orEmpty())
    } catch (_: Throwable) { false }

    /** Time of the last screen change, so actions can wait for the UI to settle instead of sleeping. */
    @Volatile private var lastEventAt = 0L
    // v0.45.1: event counters (not times: two events can share a millisecond).
    @Volatile private var eventSeq = 0L
    @Volatile private var otherEventSeq = 0L
    @Volatile private var lastWindowPkg = ""

    /** v0.25.0: only observed while Magic Hand is on (Back twice closes it). Never consumed: always false. */
    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event != null) try { MagicHand.onKey(event) } catch (_: Throwable) { }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        lastEventAt = android.os.SystemClock.uptimeMillis()
        // v0.45.1: Blaze's own orb / overlay redraws don't change another app's tree (ScreenReadCache).
        eventSeq++
        if (event.packageName?.toString() != packageName) otherEventSeq++
        // 0.45.19: the cached tree re-walks only what changed (ScreenTree); events of other windows don't touch it.
        try { treeEvent(event) } catch (_: Throwable) { tree.onWindowChanged() }
        // v0.43.0 Bank mode: a banking app in front turns it on (this service then switches itself off); nothing in a
        // banking app is watched, remembered or recorded.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = event.packageName?.toString().orEmpty()
            if (p.isNotEmpty() && p != packageName) {
                try { com.blaze.assistant.bank.BankMode.onFront(this, p) } catch (_: Throwable) { }
                if (try { com.blaze.assistant.bank.BankMode.isBank(p) } catch (_: Throwable) { false }) { lastWindowPkg = p; return }
            }
        } else if (try { com.blaze.assistant.bank.BankMode.isBank(event.packageName?.toString().orEmpty()) } catch (_: Throwable) { false }) return
        try { Watchers.onEvent(this, event.packageName?.toString().orEmpty()) } catch (_: Exception) { }
        // v0.24.7 Magic Hand: watches re-read when their app comes up; teach mode records the user's own taps / typing.
        try { MagicHand.onEvent(this, event) } catch (_: Exception) { }
        // v0.38.0 follow-ups: Send tapped in a chat app with the waiting person's chat open counts as a reply.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) try {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != packageName) com.blaze.assistant.proactive.FollowUps.onClick(pkg,
                (event.contentDescription ?: event.text?.joinToString(" "))?.toString(), rootInActiveWindow)
        } catch (_: Throwable) { }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != lastWindowPkg && pkg != packageName) {
                lastWindowPkg = pkg
                com.blaze.assistant.agent.ScreenMemory.onWindow(this, pkg)
                try { com.blaze.assistant.proactive.Triggers.onApp(pkg, appLabel(pkg)) } catch (_: Exception) { }
                try { com.blaze.assistant.proactive.FollowUps.onAppOpened(pkg) } catch (_: Throwable) { }
            }
            // v0.27.0: habit offers only from apps opened on purpose (no keyboards, home, system UI, overlays, brief visits).
            if (pkg.isNotEmpty()) try {
                val dm = resources.displayMetrics
                com.blaze.assistant.proactive.HabitApps.onWindow(this, pkg, { com.blaze.assistant.proactive.HabitApps.windowOf(this, event) },
                    dm.widthPixels, dm.heightPixels)
            } catch (_: Exception) { }
        }
    }

    /**
     * Wait until the screen has been quiet for [quietMs] (no content changes), at most [maxMs].
     * Much faster than a fixed sleep when the app reacts quickly.
     */
    suspend fun settle(maxMs: Long = 1500, quietMs: Long = 250) {
        val start = android.os.SystemClock.uptimeMillis()
        val pkg = lastWindowPkg
        // Learned per app: how long this app usually takes to go quiet. Fast apps stop waiting early.
        val learned = settleAvg[pkg]
        val cap = if (learned != null && (settleN[pkg] ?: 0) >= 3) minOf(maxMs, maxOf(350L, (learned * 2.2).toLong())) else maxMs
        val quiet = if (learned != null && learned < 300) minOf(quietMs, 150L) else quietMs
        kotlinx.coroutines.delay(60)
        while (true) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastEventAt >= quiet || now - start >= cap) {
                val took = (lastEventAt - start).coerceIn(0, maxMs)
                if (pkg.isNotEmpty()) {
                    settleAvg[pkg] = settleAvg[pkg]?.let { it * 0.7 + took * 0.3 } ?: took.toDouble()
                    settleN[pkg] = (settleN[pkg] ?: 0) + 1
                }
                return
            }
            kotlinx.coroutines.delay(40)
        }
    }
    /**
     * 0.45.19: wait until the next accessibility event or [maxMs], whichever comes first (verification polls on events,
     * not on fixed sleeps). Returns true when an event arrived.
     */
    suspend fun awaitEvent(maxMs: Long): Boolean {
        val seq = eventSeq
        val end = android.os.SystemClock.uptimeMillis() + maxMs
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (eventSeq != seq) return true
            kotlinx.coroutines.delay(15)
        }
        return eventSeq != seq
    }
    private val settleAvg = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val settleN = java.util.concurrent.ConcurrentHashMap<String, Int>()
    override fun onInterrupt() {}

    override fun onDestroy() {
        Pointer.done()
        MagicHand.stop()
        com.blaze.assistant.ui.Overlay.hideOrb(this, unpin = false)
        instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        com.blaze.assistant.device.CrashLog.write("Phone control disconnected (unbound by Android)")
        MagicHand.stop()
        instance = null
        return super.onUnbind(intent)
    }

    // ---------- Seeing ----------

    /** Screenshot of the default display, downscaled, as a data:image/jpeg URL. Android 11+. */
    suspend fun screenshot(): String? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        if (bankInFront()) return null
        // v0.33.3: the hardware buffer is copied, scaled (720 px long side) and compressed (JPEG 60) off the main thread.
        val buf = suspendCancellableCoroutine<ScreenshotResult?> { cont ->
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(r: ScreenshotResult) { if (cont.isActive) cont.resume(r) else r.hardwareBuffer.close() }
                        override fun onFailure(code: Int) { if (cont.isActive) cont.resume(null) }
                    })
            } catch (_: Exception) { if (cont.isActive) cont.resume(null) }
        } ?: return null
        val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val hw = android.graphics.Bitmap.wrapHardwareBuffer(buf.hardwareBuffer, buf.colorSpace) ?: return@withContext null
                val scale = com.blaze.assistant.agent.VisionFast.scaleFor(hw.width, hw.height)
                // Scale straight from the hardware bitmap when possible (one software copy at the small size).
                val small = try {
                    if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(hw, (hw.width * scale).toInt().coerceAtLeast(1), (hw.height * scale).toInt().coerceAtLeast(1), true)
                        .let { if (it.config == android.graphics.Bitmap.Config.HARDWARE) it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else it }
                    else hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                } catch (_: Exception) {
                    val sw = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(sw, (sw.width * scale).toInt().coerceAtLeast(1), (sw.height * scale).toInt().coerceAtLeast(1), true) else sw
                } ?: return@withContext null
                java.io.ByteArrayOutputStream().also { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, com.blaze.assistant.agent.VisionFast.JPEG_QUALITY, it) }
            } catch (_: Exception) { null } finally { try { buf.hardwareBuffer.close() } catch (_: Exception) { } }
        } ?: return null
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    // ---------- Reading ----------

    // Stable element numbers: an element keeps its number across reads of the same screen, so the
    // model can act on numbers it already has and after-action reads only need to send the changes.
    private var keyToId = HashMap<String, Int>()
    private var lastLines = HashMap<Int, String>()
    private var lastReadPkg = ""
    private var lastReadAt = 0L

    // v0.45.1 speed: the last tree walk, reused by a read right after it when nothing can have changed (ScreenReadCache):
    // same window, no accessibility event since, no action by Blaze since, at most ScreenReadCache.MAX_AGE_MS old.
    // waitFor / findTarget / anyField / visibleWords / look_at_screen used to walk the whole tree (one IPC per node)
    // several times in a row on a screen that hadn't moved.
    private class Walk(val windowId: Int, val pkg: String, val eventMark: Long, val actMark: Long, val at: Long,
                       val found: List<Pair<AccessibilityNodeInfo, String>>)
    private var lastWalk: Walk? = null
    @Volatile private var actSeq = 0L
    /** Reads served from [lastWalk] / full walks, since start (speed_stats). */
    @Volatile var walksReused = 0; private set
    @Volatile var walksDone = 0; private set
    @Volatile var walkMsTotal = 0L; private set

    internal fun acted() { actSeq++ }

    /** The newest event that can matter for a read of [pkg]: any event when Blaze itself is in front, else other apps' only. */
    private fun eventMark(pkg: String): Long = if (pkg == packageName) eventSeq else otherEventSeq

    private fun role(n: AccessibilityNodeInfo): String {
        val c = n.className?.toString()?.substringAfterLast('.') ?: "View"
        return when {
            n.isEditable || c.contains("EditText") -> "field"
            c.contains("Switch") || c.contains("Toggle") -> "switch"
            c.contains("CheckBox") -> "check"
            c.contains("Radio") -> "radio"
            c.contains("Button") || (c.contains("Image") && n.isClickable) -> "btn"
            c.contains("Image") -> "img"
            c.contains("Recycler") || c.contains("ListView") || c.contains("ScrollView") || c.contains("Pager") -> "list"
            c.contains("Web") -> "web"
            c == "TextView" -> if (n.isClickable) "link" else "text"
            n.isClickable -> "item"
            else -> "text"
        }
    }

    // ---------- 0.45.19: event-driven cached accessibility tree ----------

    private object NodeAccess : ScreenTree.Access<AccessibilityNodeInfo> {
        override fun key(n: AccessibilityNodeInfo): Long = (n.windowId.toLong() shl 32) xor (n.hashCode().toLong() and 0xffffffffL)
        override fun visible(n: AccessibilityNodeInfo) = n.isVisibleToUser
        override fun text(n: AccessibilityNodeInfo) = n.text?.toString().orEmpty()
        override fun desc(n: AccessibilityNodeInfo) = n.contentDescription?.toString().orEmpty()
        override fun hint(n: AccessibilityNodeInfo) = if (android.os.Build.VERSION.SDK_INT >= 26) n.hintText?.toString().orEmpty() else ""
        override fun cls(n: AccessibilityNodeInfo) = n.className?.toString().orEmpty()
        override fun clickable(n: AccessibilityNodeInfo) = n.isClickable
        override fun editable(n: AccessibilityNodeInfo) = n.isEditable
        override fun scrollable(n: AccessibilityNodeInfo) = n.isScrollable
        override fun checkable(n: AccessibilityNodeInfo) = n.isCheckable
        override fun checked(n: AccessibilityNodeInfo) = n.isChecked
        override fun selected(n: AccessibilityNodeInfo) = n.isSelected
        override fun enabled(n: AccessibilityNodeInfo) = n.isEnabled
        override fun childCount(n: AccessibilityNodeInfo) = n.childCount
        override fun child(n: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? = n.getChild(i)
    }
    private val tree = ScreenTree(NodeAccess)
    /** The window the cached tree belongs to (events of other windows are ignored). */
    @Volatile private var treeWindow = -1
    /** Threads for a parallel cold walk of a big window (per-thread accessibility clients). */
    private val treePool: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(3) { r -> Thread(r, "blaze-a11y-walk").apply { isDaemon = true } }
    }
    /** Cached-tree counters (speed_stats). */
    val treeStats: String get() = "tree full ${tree.fullWalks}, partial ${tree.partialWalks}, subtrees reused ${tree.reusedSubtrees}, fetches ${tree.childFetches}"

    private fun treeEvent(e: AccessibilityEvent) {
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> tree.onWindowChanged()
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                if (treeWindow < 0 || e.windowId != treeWindow) return
                val src = e.source
                tree.onContentChanged(src?.let { NodeAccess.key(it) })
            }
            else -> { }
        }
    }

    /** Full read. With [diff], a read of the same screen returns only what changed. */
    fun readScreen(diff: Boolean = false): String {
        val root = rootInActiveWindow ?: run { elements.clear(); return "I can't see the screen right now (no active window)." }
        if (bankInFront()) { elements.clear(); lastWalk = null; tree.clear(); return com.blaze.assistant.bank.BankModeLogic.REFUSE }
        val pkg = root.packageName?.toString() ?: "unknown"
        val t0 = android.os.SystemClock.uptimeMillis()
        val w = lastWalk
        val found: List<Pair<AccessibilityNodeInfo, String>> =
            if (w != null && com.blaze.assistant.agent.ScreenReadCache.reusable(w.windowId == root.windowId && w.pkg == pkg,
                    eventMark(pkg) != w.eventMark, actSeq != w.actMark, t0 - w.at)) { walksReused++; w.found }
            else {
                val evMark = eventMark(pkg); val acMark = actSeq   // taken before the walk: a change during it means "walk again"
                // 0.45.19: the cached tree (only the subtrees events marked dirty are walked again); the old full walk is the fallback.
                val list: List<Pair<AccessibilityNodeInfo, String>> = try {
                    treeWindow = root.windowId
                    tree.read(root, t0, treePool)
                } catch (e: Throwable) {
                    tree.clear()
                    ArrayList<Pair<AccessibilityNodeInfo, String>>().also { walk(root, it, 0) }
                }
                list.also {
                    val t1 = android.os.SystemClock.uptimeMillis()
                    walksDone++; walkMsTotal += t1 - t0
                    lastWalk = Walk(root.windowId, pkg, evMark, acMark, t1, it) }
            }
        // Keys: what the element is and says (not where), with a counter for repeats.
        val seen = HashMap<String, Int>()
        val keys = found.map { (n, line) ->
            val base = role(n) + "|" + (n.text ?: "") + "|" + (n.contentDescription ?: "") + "|" + (n.viewIdResourceName ?: "")
            val k = seen.getOrDefault(base, 0); seen[base] = k + 1
            "$base#$k"
        }
        val now = android.os.SystemClock.uptimeMillis()
        val samePkg = pkg == lastReadPkg && now - lastReadAt < 120_000
        val overlap = if (samePkg) keys.count { it in keyToId } else 0
        val keep = samePkg && found.isNotEmpty() && overlap * 10 >= found.size * 3
        val newMap = HashMap<String, Int>()
        val used = HashSet<Int>()
        if (keep) keys.forEach { k -> keyToId[k]?.let { if (used.add(it)) newMap[k] = it } }
        var next = if (keep) (keyToId.values.maxOrNull() ?: -1) + 1 else 0
        keys.forEach { k -> if (k !in newMap) { while (next in used) next++; newMap[k] = next; used.add(next); next++ } }
        elements.clear()
        val lines = HashMap<Int, String>()
        found.forEachIndexed { i, (n, line) ->
            val id = newMap[keys[i]]!!
            while (elements.size <= id) elements.add(null)
            elements[id] = n
            lines[id] = "[$id] $line"
        }
        val prevLines = lastLines
        keyToId = newMap; lastLines = lines; lastReadPkg = pkg; lastReadAt = now
        val sb = StringBuilder()
        val ordered = found.indices.map { newMap[keys[it]]!! }
        if (diff && keep && prevLines.isNotEmpty()) {
            val changed = ordered.filter { prevLines[it] != lines[it] }
            val gone = prevLines.keys.filter { it !in lines }
            if (changed.isEmpty() && gone.isEmpty()) return "Same screen in ${appLabel(pkg)}, nothing changed."
            if (changed.size + gone.size < found.size / 2 + 1) {
                sb.append("Same screen in ").append(appLabel(pkg)).append(". Changed or new:\n")
                changed.forEach { lines[it]?.takeIf { l -> !com.blaze.assistant.agent.TapLoop.bareRow(l) }?.let { l -> sb.append(l).append("\n") } }
                if (gone.isNotEmpty()) sb.append("Gone: ").append(gone.take(20).joinToString(" ") { "[$it]" }).append("\n")
                sb.append("Other ").append(found.size - changed.size).append(" elements unchanged, same numbers.")
                return sb.toString()
            }
        }
        sb.append(appLabel(pkg)).append(" (").append(pkg).append("). Elements, use the [number]:\n")
        // v0.30.7: unlabelled clickable rows ("[12] item") are left out: their labelled children follow, and a tap on a
        // child climbs to the clickable row anyway. Their numbers stay valid.
        ordered.forEach { lines[it]?.takeIf { l -> !com.blaze.assistant.agent.TapLoop.bareRow(l) }?.let { l -> sb.append(l).append("\n") } }
        if (found.isEmpty()) sb.append("(nothing readable on screen)\n")
        val out = sb.toString()
        return if (out.length <= 6_000) out else out.take(6_000) + "\n…(more below; scroll to see it)"
    }

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<Pair<AccessibilityNodeInfo, String>>, depth: Int) {
        if (node == null || depth > 40 || out.size >= 200) return
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim().orEmpty() else ""
        val interesting = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable ||
            node.isEditable || node.isScrollable || node.isCheckable
        // Skip a bare clickable wrapper whose only job is to hold one labelled child: the child is listed.
        val wrapper = text.isEmpty() && desc.isEmpty() && !node.isEditable && !node.isScrollable && !node.isCheckable &&
            node.childCount == 1 && node.getChild(0)?.let { c -> !c.isClickable && (!c.text.isNullOrBlank() || !c.contentDescription.isNullOrBlank()) } == true
        if (interesting && !wrapper) {
            val r = role(node)
            val sb = StringBuilder(r)
            if (text.isNotEmpty()) sb.append(" \"").append(text.take(120)).append("\"")
            if (desc.isNotEmpty() && desc != text) sb.append(" (").append(desc.take(80)).append(")")
            if (hint.isNotEmpty() && hint != text) sb.append(" hint \"").append(hint.take(60)).append("\"")
            if (node.isCheckable) sb.append(if (node.isChecked) " on" else " off")
            if (node.isScrollable) sb.append(" scrolls")
            if (node.isSelected) sb.append(" selected")
            if (!node.isEnabled) sb.append(" disabled")
            if (node.isClickable && (r == "text" || r == "img")) sb.append(" tappable")
            out.add(node to sb.toString())
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), out, depth + 1)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun element(id: Int): AccessibilityNodeInfo? = elements.getOrNull(id)

    // ---------- Acting ----------

    /** The visible words of element [id], used to spot risky taps like "Send" or "Pay". */
    /** After read_screen: the element whose text or description matches [label] best (for task replay). */
    fun findByLabel(label: String): Int? {
        val l = label.trim().lowercase(); if (l.isEmpty()) return null
        val labels = elements.indices.map { if (elements[it] == null) "" else labelOf(it).trim().lowercase() }
        labels.indexOfFirst { it == l }.takeIf { it >= 0 }?.let { return it }
        return labels.indexOfFirst { it.isNotEmpty() && (it.contains(l) || l.contains(it)) }.takeIf { it >= 0 }
    }

    fun labelOf(id: Int): String {
        val n = element(id) ?: return ""
        val own = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).joinToString(" ").trim()
        if (own.isNotEmpty()) return own
        for (i in 0 until n.childCount) n.getChild(i)?.let { c ->
            val t = listOfNotNull(c.text?.toString(), c.contentDescription?.toString()).joinToString(" ").trim()
            if (t.isNotEmpty()) return t
        }
        return n.viewIdResourceName?.substringAfter('/')?.replace('_', ' ') ?: ""
    }

    // ---------- v1.9: by-label targeting for planned scripts ----------

    private fun labelsOf(n: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        if (android.os.Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        n.viewIdResourceName?.substringAfter('/')?.replace('_', ' ')?.takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    /**
     * Fresh read, then the element best matching [label]: exact text, then word-start, then
     * contains; editable fields win when [wantField], clickable things otherwise.
     */
    fun findTarget(label: String, wantField: Boolean = false): Int? {
        readScreen()
        if (label.isBlank()) return null
        // v0.30.4: LabelMatch (normalised words, OEM synonyms like Samsung "Apps" / Pixel "Apps & notifications",
        // fuzzy spelling, never "Don't allow" for "Allow" or a destructive row for a plain target).
        var best = -1; var bestScore = 0
        elements.forEachIndexed { i, n ->
            if (n == null) return@forEachIndexed
            val ls = labelsOf(n) + labelOf(i)
            var sc = ls.maxOfOrNull { LabelMatch.score(label, it) } ?: 0
            if (sc < LabelMatch.MIN) return@forEachIndexed
            if (wantField && n.isEditable) sc += 25
            if (!wantField && n.isClickable) sc += 10
            if (!n.isEnabled) sc -= 40
            if (sc > bestScore) { bestScore = sc; best = i }
        }
        return best.takeIf { it >= 0 && bestScore >= LabelMatch.MIN }
    }

    // ---------- v0.30.4 fast actions: find anywhere in the tree, scroll it into view ----------

    private fun nodeLabel(n: AccessibilityNodeInfo): String =
        listOfNotNull(n.text?.toString(), n.contentDescription?.toString(),
            if (android.os.Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null).firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /** Every labelled node in the window, visible or not (RecyclerViews keep a few rows past the edge, ScrollViews all). */
    private fun labelled(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, scrollers: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (n == null || depth > 45 || out.size >= 1500) return
        if (nodeLabel(n).isNotEmpty()) out += n
        if (n.isScrollable) scrollers += n
        for (i in 0 until n.childCount) labelled(n.getChild(i), out, scrollers, depth + 1)
    }

    /** The node anywhere in the active window that best matches [label], or null. */
    fun locate(label: String, wantField: Boolean = false): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>(); val scrollers = ArrayList<AccessibilityNodeInfo>()
        labelled(rootInActiveWindow ?: return null, nodes, scrollers)
        var best: AccessibilityNodeInfo? = null; var bs = 0
        for (n in nodes) {
            var s = LabelMatch.score(label, nodeLabel(n))
            if (s < LabelMatch.MIN) continue
            if (wantField && n.isEditable) s += 10
            if (!n.isVisibleToUser) s -= 5
            if (!n.isEnabled) s -= 30
            if (s > bs) { bs = s; best = n }
        }
        return best?.takeIf { bs >= LabelMatch.MIN }
    }

    /** The biggest visible scrollable list on screen (Settings' list), or null. */
    private fun mainScroller(): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>(); val scrollers = ArrayList<AccessibilityNodeInfo>()
        labelled(rootInActiveWindow ?: return null, nodes, scrollers)
        val r = Rect()
        return scrollers.filter { it.isVisibleToUser }.maxByOrNull { it.getBoundsInScreen(r); r.width().toLong() * r.height() }
    }

    private fun visibleSig(): Int = (rootInActiveWindow?.let { r ->
        val nodes = ArrayList<AccessibilityNodeInfo>(); labelled(r, nodes, ArrayList()); nodes.filter { it.isVisibleToUser }.joinToString("|") { nodeLabel(it) }
    } ?: "").hashCode()

    /** How many scrolls the last [reveal] needed (for the step trace). */
    @Volatile var lastRevealScrolls = 0

    /**
     * The element number for [label] on screen: as is, by synonym or fuzzy match, or after bringing an off-screen row
     * into view (ACTION_SHOW_ON_SCREEN, else scrolling the main list down, then up, until it shows or the list stops
     * moving). One call replaces the old "scroll, look, scroll, look" model turns. Null when it isn't anywhere.
     */
    suspend fun reveal(label: String, wantField: Boolean = false, maxScrolls: Int = 8): Int? {
        lastRevealScrolls = 0
        findTarget(label, wantField)?.let { return it }
        suspend fun bringIn(): Int? {
            val n = locate(label, wantField) ?: return null
            if (n.isVisibleToUser) return findTarget(label, wantField)
            acted()
            if (n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) { settle(450, 120); findTarget(label, wantField)?.let { return it } }
            return null
        }
        bringIn()?.let { return it }
        for (dir in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            var sig = visibleSig()
            for (k in 0 until maxScrolls) {
                val list = mainScroller() ?: return null
                acted()
                if (!list.performAction(dir)) break
                lastRevealScrolls++
                settle(450, 120)
                findTarget(label, wantField)?.let { return it }
                bringIn()?.let { return it }
                val now = visibleSig(); if (now == sig) break; sig = now
            }
        }
        return null
    }

    /** Waits up to [ms] for [label] to show up (apps load lazily). */
    suspend fun waitFor(label: String, ms: Long, wantField: Boolean = false): Int? {
        val end = android.os.SystemClock.uptimeMillis() + ms
        while (true) {
            findTarget(label, wantField)?.let { return it }
            if (android.os.SystemClock.uptimeMillis() > end) return null
            kotlinx.coroutines.delay(150)
        }
    }

    /** The focused text field, or the first one on screen. */
    fun anyField(): Int? {
        readScreen()
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && f.isEditable) { val i = elements.indexOfFirst { it == f }; if (i >= 0) return i }
        return elements.indexOfFirst { it?.isEditable == true }.takeIf { it >= 0 }
    }

    /** What the focused (or first) text field holds now, "" when there is none. */
    fun fieldText(): String { val i = anyField() ?: return ""; return element(i)?.text?.toString().orEmpty() }

    /** Presses the keyboard's Enter/Search on the current field. */
    suspend fun pressEnter(): Boolean { val id = anyField() ?: return false; return submitIme(elements[id] ?: return false) }

    /** Package in front right now. */
    fun currentPkg(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    /** Words visible on screen (for checking a step worked, and short "what changed" notes). */
    fun visibleWords(): Set<String> {
        readScreen()
        return elements.filterNotNull().flatMap { labelsOf(it) }.map { it.take(80) }.toSet()
    }

    /** A soft ring where Blaze is about to tap, so you can watch it work. */
    fun flash(id: Int) {
        if (!com.blaze.assistant.agent.Settings.bool("show_taps", true) || com.blaze.assistant.agent.Settings.bool("speed_mode", false)) return
        val n = element(id) ?: return
        val r = Rect(); n.getBoundsInScreen(r)
        if (r.width() <= 2 || r.height() <= 2) return
        try {
            val wm = getSystemService(android.view.WindowManager::class.java)
            val pad = (8 * resources.displayMetrics.density).toInt()
            val v = object : android.view.View(this) {
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE; strokeWidth = 3 * resources.displayMetrics.density; color = 0xFF6B8CFF.toInt() }
                val f = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x226B8CFF }
                override fun onDraw(c: android.graphics.Canvas) {
                    val h = p.strokeWidth
                    val rr = 12 * resources.displayMetrics.density   // v0.24.8: rounded tap highlight
                    c.drawRoundRect(h, h, width - h, height - h, rr, rr, f); c.drawRoundRect(h, h, width - h, height - h, rr, rr, p)
                }
            }
            val lp = android.view.WindowManager.LayoutParams(r.width() + pad * 2, r.height() + pad * 2,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = r.left - pad; y = r.top - pad }
            android.os.Handler(mainLooper).post { try { wm.addView(v, lp); v.animate().alpha(0f).setStartDelay(260).setDuration(320).withEndAction { try { wm.removeView(v) } catch (_: Exception) { } }.start() } catch (_: Exception) { } }
        } catch (_: Exception) { }
    }

    fun currentApp(): String? = rootInActiveWindow?.packageName?.toString()?.let { appLabel(it) }

    /** True if the screen changed (any accessibility event) since [since], waiting up to [ms]. */
    private suspend fun changedSince(since: Long, ms: Long): Boolean {
        val end = android.os.SystemClock.uptimeMillis() + ms
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (lastEventAt > since) return true
            kotlinx.coroutines.delay(40)
        }
        return lastEventAt > since
    }

    private fun centerOf(n: AccessibilityNodeInfo): Pair<Float, Float>? {
        val r = Rect(); n.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        r.intersect(0, 0, dm.widthPixels, dm.heightPixels)
        if (r.width() <= 2 || r.height() <= 2) return null
        return r.exactCenterX() to r.exactCenterY()
    }

    /**
     * Tap an element. Uses the accessibility click on the element or its nearest clickable parent,
     * then checks the screen actually reacted; if not (custom views often ignore it), it touches
     * the middle of the element like a finger would.
     */
    suspend fun tap(id: Int, long: Boolean = false): String {
        if (bankInFront()) return com.blaze.assistant.bank.BankModeLogic.REFUSE
        // v0.30.7: a tap that flipped a switch remembers which way it ended up (plan memory replays it with want=…).
        val before = try { switchState(id) } catch (_: Exception) { null }
        lastToggle = null
        val r = tapRaw(id, long)
        if (before != null && (r.startsWith("Tapped"))) try {
            val after = switchState(id)
            if (after != null && after != before) lastToggle = after
        } catch (_: Exception) { }
        return r
    }

    /** v0.30.7: the switch state the last [tap] left behind, when it flipped one (null = not a switch / didn't change). */
    @Volatile var lastToggle: Boolean? = null

    /**
     * v0.30.7: whether element [id] is a switch / checkbox that is on, or sits in a settings row with one (a row's title
     * text → its clickable row → the switch inside). Null when there's no switch there, or the element went away.
     */
    fun switchState(id: Int): Boolean? {
        val node = element(id) ?: return null
        if (!node.refresh() || !node.isVisibleToUser) return null
        if (node.isCheckable) return node.isChecked
        var row: AccessibilityNodeInfo? = node
        var hops = 0
        while (row != null && !row.isClickable && hops < 4) { row = row.parent; hops++ }
        val base = row?.takeIf { it.isClickable && !it.isScrollable } ?: return null
        return checkableIn(base, 0)?.isChecked
    }

    private fun checkableIn(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 4) return null
        if (n.isCheckable) return n
        for (i in 0 until n.childCount) checkableIn(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    private suspend fun tapRaw(id: Int, long: Boolean): String {
        val node = element(id) ?: return "No element [$id]. Call read_screen again to get fresh numbers."
        node.refresh()
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var n: AccessibilityNodeInfo? = node
        var target: AccessibilityNodeInfo? = null
        while (n != null) {
            if (if (long) n.isLongClickable else n.isClickable) { target = n; break }
            n = n.parent
        }
        // v0.21.0 visible pointer: glide there, re-check the target is still where it was (orient), ripple, then act.
        aimAt(node, id, long)?.let { return it }
        val before = android.os.SystemClock.uptimeMillis()
        acted()
        if (target != null && target.isEnabled && target.performAction(action)) {
            if (changedSince(before, 700)) return "Tapped [$id]."
        }
        val pt = centerOf(node) ?: target?.let { centerOf(it) } ?: return "Couldn't tap [$id]: it's off screen. Scroll first."
        return if (gestureTap(pt.first, pt.second, if (long) 800 else 60)) "Tapped [$id] by touch."
        else "Couldn't tap [$id]."
    }

    /**
     * The pointer's part of a tap: null = go ahead; a message = don't tap (the user tapped Stop, or the target went
     * away or moved during the glide and is aimed at again once). The pointer never pauses a run (v0.45.14).
     */
    private suspend fun aimAt(node: AccessibilityNodeInfo, id: Int, long: Boolean): String? {
        val p0 = centerOf(node) ?: return null
        val stopped = "Stopped before tapping [$id]: the user tapped Stop. Nothing was tapped."
        if (!Pointer.aim(this, p0.first, p0.second)) return stopped
        node.refresh()
        val p1 = if (node.isVisibleToUser) centerOf(node) else null
        if (p1 == null) return "The target [$id] went away while Blaze was aiming (the screen changed). Nothing was tapped; look again."
        val d = resources.displayMetrics.density
        if (Pointer.moved(p0.first, p0.second, p1.first, p1.second, d) && !Pointer.aim(this, p1.first, p1.second)) return stopped
        if (!Pointer.ripple(long)) return stopped
        return null
    }

    /** v0.24.4: every agent gesture shows the pointer first: glide to (x, y), ripple. False = the user tapped Stop. */
    private suspend fun pointTo(x: Float, y: Float, long: Boolean = false): Boolean = Pointer.aim(this, x, y) && Pointer.ripple(long)

    /** Tap a node found outside read_screen (watchers): accessibility click up the parent chain, else a touch. */
    suspend fun tapNode(node: AccessibilityNodeInfo): Boolean {
        centerOf(node)?.let { if (!pointTo(it.first, it.second)) return false }
        var n: AccessibilityNodeInfo? = node
        acted()
        while (n != null) { if (n.isClickable && n.isEnabled) { if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; break }; n = n.parent }
        val pt = centerOf(node) ?: return false
        return gestureTap(pt.first, pt.second, 60)
    }

    /**
     * Flip a Quick Settings tile (Wi-Fi, Bluetooth, Airplane...) like a person would: pull down the
     * shade, find the tile by any of its names, tap only if it isn't already in the wanted state, close.
     * Works on any skin because it matches labels, not positions. Null when the tile can't be found.
     */
    suspend fun toggleQuickTile(names: List<String>, want: Boolean?): String? {
        fun norm(s: CharSequence?) = s?.toString()?.lowercase()?.replace('\u2011', '-')?.replace('\u2010', '-')?.trim().orEmpty()
        val keys = names.map { norm(it) }
        fun find(): AccessibilityNodeInfo? {
            val roots = (try { windows.mapNotNull { it.root } } catch (_: Exception) { emptyList() }) + listOfNotNull(rootInActiveWindow)
            for (r in roots) {
                val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(r) }
                var seen = 0
                while (stack.isNotEmpty() && seen++ < 3000) {
                    val n = stack.removeLast()
                    val label = norm(n.contentDescription).ifEmpty { norm(n.text) }
                    if (label.isNotEmpty() && keys.any { label == it || label.startsWith("$it,") || label.startsWith("$it ") }) {
                        var c: AccessibilityNodeInfo? = n
                        while (c != null && !c.isClickable) c = c.parent
                        return c ?: n
                    }
                    for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
                }
            }
            return null
        }
        fun state(n: AccessibilityNodeInfo): Boolean? {
            if (n.isCheckable) return n.isChecked
            val sd = if (android.os.Build.VERSION.SDK_INT >= 30) norm(n.stateDescription) else ""
            val all = "$sd ${norm(n.contentDescription)}"
            return when { Regex("\\b(on|enabled|connected)\\b").containsMatchIn(all) -> true; Regex("\\b(off|disabled)\\b").containsMatchIn(all) -> false; else -> null }
        }
        acted()
        if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) return null
        var tile: AccessibilityNodeInfo? = null
        repeat(8) { if (tile == null) { delay(250); tile = find() } }
        val t = tile ?: run { performGlobalAction(GLOBAL_ACTION_BACK); return null }
        val before = state(t)
        val name = names.first()
        if (want != null && before == want) { performGlobalAction(GLOBAL_ACTION_BACK); return "$name is already ${if (want) "on" else "off"}." }
        if (!tapNode(t)) { performGlobalAction(GLOBAL_ACTION_BACK); return null }
        delay(600)
        val after = find()?.let { it.refresh(); state(it) }
        performGlobalAction(GLOBAL_ACTION_BACK)
        return when {
            after != null -> "$name ${if (after) "on" else "off"}."
            want != null -> "$name ${if (want) "on" else "off"}."
            else -> "Flipped $name."
        }
    }

    /** Fresh read, then tap the element whose words match [label] (synonyms, off-screen rows scrolled in). Immune to stale numbers. */
    suspend fun tapText(label: String, long: Boolean = false): String {
        if (bankInFront()) return com.blaze.assistant.bank.BankModeLogic.REFUSE
        val id = reveal(label) ?: return "No element matching \"$label\" on this screen, even after scrolling through it. Read the screen for the real labels."
        val scrolled = lastRevealScrolls
        val name = labelOf(id).ifBlank { label }.take(40)
        return tap(id, long).replace("[$id]", "\"$name\"") + if (scrolled > 0) " (scrolled $scrolled× to find it)" else ""
    }

    suspend fun type(id: Int, text: String, submit: Boolean = false): String {
        var node = element(id) ?: return "No element [$id]. Call read_screen again."
        // v0.24.4: the pointer shows which field gets the text before Blaze focuses it (a non-field goes through tap()).
        if (node.isEditable) centerOf(node)?.let { if (!pointTo(it.first, it.second)) return "Stopped before typing into [$id]: the user tapped Stop. Nothing was typed." }
        if (!node.isEditable) {
            // Search icons/bars often turn into a real field after a tap.
            tap(id)
            settle(700, 150)
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) node = focused
            else {
                readScreen()
                elements.firstOrNull { it?.isEditable == true }?.let { node = it }
            }
        }
        acted()
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Couldn't type into [$id]; it may not be a text field."
        if (!submit) return "Typed into [$id]."
        return "Typed into [$id] and " + (if (submitIme(node)) "pressed Enter/Search." else "couldn't press Enter; tap the search/send button.")
    }

    /** Presses the keyboard's Enter / Search key on a field (Android 11+). */
    private suspend fun submitIme(node: AccessibilityNodeInfo): Boolean {
        acted()
        settle(300, 100)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            node.refresh()
            if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        }
        return false
    }

    suspend fun scroll(direction: String, id: Int?): String {
        acted()
        val forward = direction.lowercase() in listOf("down", "right", "forward")
        val target = id?.let { element(it) } ?: elements.firstOrNull { it?.isScrollable == true }
        if (target != null) {
            centerOf(target)?.let { if (!pointTo(it.first, it.second)) return "Stopped before scrolling: the user tapped Stop." }
            var n: AccessibilityNodeInfo? = target
            while (n != null) {
                if (n.isScrollable && n.performAction(
                        if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    )
                ) return "Scrolled $direction."
                n = n.parent
            }
        }
        // Fall back to a swipe in the middle of the screen.
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val dy = dm.heightPixels * 0.3f
        val dx = dm.widthPixels * 0.3f
        val ok = when (direction.lowercase()) {
            "down" -> swipe(cx, cy + dy, cx, cy - dy)
            "up" -> swipe(cx, cy - dy, cx, cy + dy)
            "right" -> swipe(cx + dx, cy, cx - dx, cy)
            else -> swipe(cx - dx, cy, cx + dx, cy)
        }
        return if (ok) "Swiped to scroll $direction." else "Couldn't scroll."
    }

    fun press(button: String): String {
        acted()
        val action = when (button.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent_apps" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return "Unknown button '$button'."
        }
        return if (performGlobalAction(action)) "Pressed $button." else "Couldn't press $button."
    }

    private suspend fun gestureTap(x: Float, y: Float, durationMs: Long): Boolean {
        Pointer.ownGesture(durationMs)
        val path = Path().apply { moveTo(x, y) }
        return dispatch(GestureDescription.StrokeDescription(path, 0, durationMs))
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val ms = if (com.blaze.assistant.agent.Settings.bool("speed_mode", false)) 180L else 350L
        if (!pointTo(x1, y1)) return false
        Pointer.showPath(this, x1, y1, x2, y2, ms)
        Pointer.ownGesture(ms)
        return dispatch(GestureDescription.StrokeDescription(path, 0, ms)).also { if (it) Pointer.jump(x2, y2) }
    }

    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            acted()
            val g = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
}
