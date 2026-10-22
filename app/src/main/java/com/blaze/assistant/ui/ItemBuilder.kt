package com.blaze.assistant.ui

import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Role

/**
 * v0.45.0 (#10): the chat's rows, built incrementally.
 *
 * Every streamed frame used to rebuild every row of the (endless) main chat, recompute every group position and
 * re-stabilise every row through a fresh map: O(n) work per token. Messages change only at the tail (a streamed reply, a
 * new step), and every change makes a new ChatMessage object, so the rows up to the newest *unchanged* message are
 * reused as they are. Only the tail is rebuilt, the group positions are recomputed from the row before the tail, and
 * a tail row equal to the one before keeps its old instance (so Compose skips it, as `stabilize` did).
 *
 * The result is always identical to [buildItems] on the whole list (ItemBuilderTest checks random edit sequences).
 */
internal class ItemBuilder {
    private var prevIn: List<ChatMessage> = emptyList()
    private var prevItems: List<Item> = emptyList()
    private var prevEnds: IntArray = IntArray(0)   // for each row: the message index it ends at (exclusive)
    private var prevWorking = false
    private var prevHide = false
    /** Rows reused from the previous build in the last call (tests). */
    var reused = 0; private set

    fun build(msgs: List<ChatMessage>, working: Boolean, hide: Boolean): List<Item> {
        var k = 0
        if (working == prevWorking && hide == prevHide) {
            val n = minOf(msgs.size, prevIn.size)
            while (k < n && msgs[k] === prevIn[k]) k++
        }
        // The newest message row that ends at or before the first change: everything up to it is unchanged.
        var cut = -1
        for (t in prevItems.indices.reversed()) if (prevEnds[t] <= k && prevItems[t] is Item.Msg) { cut = t; break }
        val start = if (cut >= 0) prevEnds[cut] else 0
        val prevAt0 = if (cut >= 0) (prevItems[cut] as Item.Msg).m.at else 0L

        val out = ArrayList<Item>(prevItems.size + 4)
        val ends = ArrayList<Int>(prevItems.size + 4)
        for (t in 0..cut) { out += prevItems[t]; ends += prevEnds[t] }
        val firstNew = out.size

        // ---- the tail, exactly as buildItems does it ----
        var run = mutableListOf<ChatMessage>()
        var runEnd = start
        var prevAt = prevAt0
        fun flush(live: Boolean, next: ChatMessage?) {
            if (run.isEmpty()) return
            if (!live && hide) { run = mutableListOf(); return }
            val end = WorkTime.end(run, live, next?.at ?: 0L, next != null && next.role != Role.USER, 0L)
            out += Item.Steps(run, live, end, WorkTime.start(run, prevAt)); ends += runEnd; run = mutableListOf()
        }
        for (i in start until msgs.size) {
            val m = msgs[i]
            if (m.role == Role.STEP) { run += m; runEnd = i + 1 }
            else { flush(false, m); out += Item.Msg(m); ends += i + 1; prevAt = m.at }
        }
        flush(working, null)

        // ---- group positions: rows before the tail keep theirs, except the last one or two around the seam ----
        val from = maxOf(0, firstNew - 1)          // the first row whose position can change (depends on its next row)
        val w = maxOf(0, from - 1)                 // one row of context before it
        val pos = Bubbles.positions(out.subList(w, out.size).map { it.side }, out.subList(w, out.size).map { it.at })
        for (i in from until out.size) {
            val p = pos[i - w]
            val it = out[i]
            if (i < firstNew) { if (it.pos != p) out[i] = copy(it).also { c -> c.pos = p } }
            else it.pos = p
        }

        // ---- a rebuilt row equal to the old one keeps the old instance ----
        var same = firstNew
        if (firstNew < out.size && cut + 1 < prevItems.size) {
            val old = HashMap<String, Item>()
            for (t in cut + 1 until prevItems.size) old[prevItems[t].key] = prevItems[t]
            for (i in firstNew until out.size) {
                val n = out[i]; val o = old[n.key] ?: continue
                if (equal(o, n)) { out[i] = o; same++ }
            }
        }
        reused = same
        prevIn = msgs; prevItems = out; prevEnds = ends.toIntArray(); prevWorking = working; prevHide = hide
        return out
    }

    private fun copy(i: Item): Item = when (i) {
        is Item.Msg -> Item.Msg(i.m)
        is Item.Steps -> Item.Steps(i.steps, i.live, i.endAt, i.startAt)
    }

    private fun equal(o: Item, n: Item): Boolean = o.pos == n.pos && when {
        n is Item.Msg && o is Item.Msg -> o.m === n.m || o.m == n.m
        n is Item.Steps && o is Item.Steps -> o.live == n.live && o.endAt == n.endAt && o.startAt == n.startAt &&
            o.steps.size == n.steps.size && o.steps.indices.all { o.steps[it] === n.steps[it] || o.steps[it] == n.steps[it] }
        else -> false
    }
}
