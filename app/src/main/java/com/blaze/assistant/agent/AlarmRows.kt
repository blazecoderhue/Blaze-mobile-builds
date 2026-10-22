package com.blaze.assistant.agent

/**
 * v0.45.13: finds one of Blaze's alarms in a Clock app's alarm list, from a snapshot of the accessibility tree. Pure,
 * so AlarmRowsTest covers Samsung Clock, Google Clock and AOSP DeskClock shapes without a phone.
 *
 * Blaze's alarms carry their registry id in the label ("Demo app alarm (Blaze A2)"), so the row is found by
 * "Blaze A2" (word-bounded: A2 never matches A21). When a clock truncates or hides the label, the time ("18:15",
 * "6:15") finds it, but only when exactly one row shows that time. The row is the smallest ancestor of the label that
 * holds exactly one switch; its switch is the alarm's on/off state.
 */
object AlarmRows {
    data class Node(val text: String = "", val desc: String = "", val checkable: Boolean = false, val checked: Boolean = false,
                    val clickable: Boolean = false, val longClickable: Boolean = false, val children: List<Node> = emptyList(),
                    /** The live node behind this snapshot (AccessibilityNodeInfo on the phone); null in tests. */
                    val ref: Any? = null) {
        val words: String get() = (text + " " + desc).trim()
    }

    data class Row(val row: Node, val label: Node, val toggle: Node?, val byTime: Boolean = false) {
        val on: Boolean? get() = toggle?.checked
    }

    fun labelRe(pid: String) = Regex("(?i)\\bBlaze\\s+" + Regex.escape(pid) + "\\b")

    /** "18:15" → the ways a clock shows it: "18:15", "6:15" (12 h, with or without am/pm). */
    fun timeForms(hhmm: String): List<String> {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return listOf("%02d:%02d".format(h, m), "%d:%02d".format(h, m), "%d:%02d".format(h12, m)).distinct()
    }

    private fun hasTime(s: String, forms: List<String>) = forms.any { f -> Regex("(?<![0-9])" + Regex.escape(f) + "(?![0-9])").containsMatchIn(s) }

    /** Every node with its ancestor path (root first). */
    private fun walk(n: Node, path: List<Node>, out: MutableList<Pair<Node, List<Node>>>) {
        out += n to path
        val p = path + n
        n.children.forEach { walk(it, p, out) }
    }

    private fun checkables(n: Node): List<Node> = (if (n.checkable) listOf(n) else emptyList()) + n.children.flatMap { checkables(it) }

    private fun subtreeText(n: Node): String = (listOf(n.words) + n.children.map { subtreeText(it) }).joinToString(" ")

    private fun rowFor(hit: Node, path: List<Node>, byTime: Boolean): Row {
        // Smallest ancestor (or the node itself) that holds exactly one switch.
        for (a in (listOf(hit) + path.asReversed())) {
            val c = checkables(a)
            if (c.size == 1) return Row(a, hit, c.first(), byTime)
            if (c.size > 1) return Row(a, hit, null, byTime)
        }
        return Row(path.lastOrNull() ?: hit, hit, null, byTime)
    }

    /** The row of alarm [pid] (e.g. "A2"), else the one row showing [hhmm]; null when neither is on the screen. */
    fun find(root: Node, pid: String, hhmm: String?): Row? {
        val all = ArrayList<Pair<Node, List<Node>>>(); walk(root, emptyList(), all)
        val re = labelRe(pid)
        all.firstOrNull { re.containsMatchIn(it.first.words) }?.let { (n, p) -> return rowFor(n, p, false) }
        if (hhmm.isNullOrBlank()) return null
        val forms = timeForms(hhmm)
        val hits = all.filter { hasTime(it.first.words, forms) && it.first.children.none { c -> hasTime(subtreeText(c), forms) } }
        val rows = hits.map { (n, p) -> rowFor(n, p, true) }.distinctBy { System.identityHashCode(it.row) }
        return rows.singleOrNull()
    }

    /** True when the list was read and alarm [pid] isn't in it (and, when given, no row shows [hhmm] switched on). */
    fun gone(root: Node, pid: String, hhmm: String?): Boolean {
        val r = find(root, pid, hhmm) ?: return true
        return r.byTime && r.on == false
    }

    private val deleteRe = Regex("(?i)^\\s*(delete|remove|delete alarm|supprimer|futa|chotsa)\\s*$")

    /** The Delete button / menu item on the screen (text or description "Delete"), clickable itself or via a parent. */
    fun deleteButton(root: Node): Node? {
        val all = ArrayList<Pair<Node, List<Node>>>(); walk(root, emptyList(), all)
        val hit = all.firstOrNull { (n, _) -> deleteRe.matches(n.text) || deleteRe.matches(n.desc) } ?: return null
        return if (hit.first.clickable) hit.first else hit.second.asReversed().firstOrNull { it.clickable } ?: hit.first
    }

    /** A short readable line of what the row shows (for the report): "Demo app alarm (Blaze A2) · 18:15 · on". */
    fun describe(r: Row): String = subtreeText(r.row).replace(Regex("\\s+"), " ").trim().take(90) + " \u00b7 " +
        when (r.on) { true -> "switch on"; false -> "switch off"; null -> "no switch read" }
}
