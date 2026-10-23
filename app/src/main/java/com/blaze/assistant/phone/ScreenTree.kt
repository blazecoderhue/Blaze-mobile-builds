package com.blaze.assistant.phone

/**
 * 0.45.19 phone tasks (user, 23 Oct: "screen reading is too slow"): an event-driven cached accessibility tree.
 *
 * Before: every read_screen walked the whole active window again (one binder call per node the system's own
 * accessibility cache doesn't hold), reused only when NO event at all had arrived since the last walk
 * (ScreenReadCache). Any event (a clock tick, a ripple, a progress bar) threw the whole walk away.
 *
 * Now the walk is kept per subtree, keyed by the node's identity (window + accessibility node id). Accessibility
 * events mark only their source's subtree (and its ancestors) dirty: TYPE_WINDOW_CONTENT_CHANGED / VIEW_SCROLLED /
 * VIEW_TEXT_CHANGED / VIEW_SELECTED… re-walk just that subtree; TYPE_WINDOW_STATE_CHANGED, an event without a source
 * or too many events since the last read re-walk everything; events of other windows and of nodes outside the output
 * are ignored. A read splices the clean subtrees' lines
 * from the cache and only touches the dirty path. A cold walk of a big window may fetch the root's subtrees in parallel
 * (per-thread accessibility clients make that safe on Android 8+; [parallel] is a kill switch).
 *
 * The output format is unchanged (the same lines in the same pre-order, the same 200-element cap), so element numbers,
 * diffs and every prompt that reads them work as before. Pure and generic over the node type (ScreenTreeTest,
 * ScreenReadBench run it on synthetic Settings / heavy-app trees with a simulated binder cost per child fetch).
 */
class ScreenTree<N : Any>(private val a: Access<N>) {
    /** How the walker reads a node. On the phone this wraps AccessibilityNodeInfo; in tests a fake tree. */
    interface Access<N> {
        fun key(n: N): Long
        fun visible(n: N): Boolean
        fun text(n: N): String
        fun desc(n: N): String
        fun hint(n: N): String
        fun cls(n: N): String
        fun clickable(n: N): Boolean
        fun editable(n: N): Boolean
        fun scrollable(n: N): Boolean
        fun checkable(n: N): Boolean
        fun checked(n: N): Boolean
        fun selected(n: N): Boolean
        fun enabled(n: N): Boolean
        fun childCount(n: N): Int
        /** The binder call (when the system cache doesn't hold the child). */
        fun child(n: N, i: Int): N?
    }

    companion object {
        const val MAX_ITEMS = 200
        const val MAX_DEPTH = 40
        /** More events than this since the last read: re-walk everything instead of tracking each source. */
        const val MAX_TRACKED = 40
        /** A full re-walk at least this often (ms), in case an event was missed. */
        const val MAX_AGE_MS = 30_000L
        /** And every this many reads. */
        const val FULL_EVERY = 12

        /** The element's role word, as read_screen has always printed it. */
        fun role(cls0: String, editable: Boolean, clickable: Boolean): String {
            val c = cls0.substringAfterLast('.').ifEmpty { "View" }
            return when {
                editable || c.contains("EditText") -> "field"
                c.contains("Switch") || c.contains("Toggle") -> "switch"
                c.contains("CheckBox") -> "check"
                c.contains("Radio") -> "radio"
                c.contains("Button") || (c.contains("Image") && clickable) -> "btn"
                c.contains("Image") -> "img"
                c.contains("Recycler") || c.contains("ListView") || c.contains("ScrollView") || c.contains("Pager") -> "list"
                c.contains("Web") -> "web"
                c == "TextView" -> if (clickable) "link" else "text"
                clickable -> "item"
                else -> "text"
            }
        }
    }

    /** Counters for speed_stats and the benchmark. */
    var fullWalks = 0; private set
    var partialWalks = 0; private set
    var reusedSubtrees = 0; private set
    var childFetches = 0L; private set

    /** Turn off to walk the root's subtrees one after the other (kill switch for parallel cold walks). */
    @Volatile var parallel = true
    /** Parallel cold walks only when the root has at least this many children with something under them. */
    var parallelMin = 3

    /** A walked subtree: its lines in pre-order and its children's keys. [complete] = nothing was cut by the item cap. */
    private class Sub<N>(val node: N, val items: List<Pair<N, String>>, val kids: List<Long>, val complete: Boolean = true)

    /** Where one walk writes: the shared cache (sequential) or a worker's own maps (parallel cold walk). */
    private inner class Ctx(val subs: MutableMap<Long, Sub<N>>, val parents: MutableMap<Long, Long>, var left: Int, val shared: Boolean) {
        var fetches = 0L
        var reused = 0
    }

    private val cache = HashMap<Long, Sub<N>>()
    private val parentOf = HashMap<Long, Long>()
    private val dirty = HashSet<Long>()
    /** Event sources: their whole subtree is re-walked (a subtree change can touch anything under them). */
    private val deep = HashSet<Long>()
    private var allDirty = true
    private var rootKey = Long.MIN_VALUE
    private var tracked = 0
    private var readsSinceFull = 0
    private var lastFullAt = 0L
    private val lock = Any()

    /** Something in the window changed under [source] (null = unknown source: everything). */
    fun onContentChanged(source: Long?) {
        synchronized(lock) {
            if (allDirty) return
            // no source or too many events: everything
            if (source == null || ++tracked > MAX_TRACKED) { allDirty = true; return }
            // a node this cache never walked sits in a part that isn't in the output (inside an invisible node, past the
            // 200-element cap, deeper than the depth limit): it can't change the read. A node that is added sends its
            // parent's subtree change, and a cut part is walked afresh as soon as the output has room for it.
            if (source != rootKey && source !in parentOf) { tracked--; return }
            deep.add(source)
            var k: Long? = source
            var hops = 0
            while (k != null && hops < MAX_DEPTH + 2) { dirty.add(k); k = parentOf[k]; hops++ }
        }
    }

    /** A new window / activity / dialog: everything is re-walked. */
    fun onWindowChanged() { synchronized(lock) { allDirty = true } }

    /** Forget everything (service stopped, bank mode). */
    fun clear() { synchronized(lock) { cache.clear(); parentOf.clear(); dirty.clear(); deep.clear(); allDirty = true; rootKey = Long.MIN_VALUE } }

    /** The read_screen line for [n], or null when it isn't listed (not interesting, or a bare wrapper of one labelled child). */
    fun line(n: N): String? = lineOf(n, null)

    private fun lineOf(n: N, ctx: Ctx?): String? {
        val text = a.text(n).trim(); val desc = a.desc(n).trim(); val hint = a.hint(n).trim()
        val clickable = a.clickable(n); val editable = a.editable(n); val scrollable = a.scrollable(n); val checkable = a.checkable(n)
        val interesting = text.isNotEmpty() || desc.isNotEmpty() || clickable || editable || scrollable || checkable
        if (!interesting) return null
        if (text.isEmpty() && desc.isEmpty() && !editable && !scrollable && !checkable && a.childCount(n) == 1) {
            val c = a.child(n, 0)
            if (ctx != null) ctx.fetches++ else childFetches++
            if (c != null && !a.clickable(c) && (a.text(c).isNotBlank() || a.desc(c).isNotBlank())) return null
        }
        val r = role(a.cls(n), editable, clickable)
        val sb = StringBuilder(r)
        if (text.isNotEmpty()) sb.append(" \"").append(text.take(120)).append("\"")
        if (desc.isNotEmpty() && desc != text) sb.append(" (").append(desc.take(80)).append(")")
        if (hint.isNotEmpty() && hint != text) sb.append(" hint \"").append(hint.take(60)).append("\"")
        if (checkable) sb.append(if (a.checked(n)) " on" else " off")
        if (scrollable) sb.append(" scrolls")
        if (a.selected(n)) sb.append(" selected")
        if (!a.enabled(n)) sb.append(" disabled")
        if (clickable && (r == "text" || r == "img")) sb.append(" tappable")
        return sb.toString()
    }

    /** The old full walk (every read, every node): kept for the benchmark's "before" and as the fallback. */
    fun walkFull(root: N?, out: MutableList<Pair<N, String>>, depth: Int = 0) {
        if (root == null || depth > MAX_DEPTH || out.size >= MAX_ITEMS) return
        if (!a.visible(root)) return
        line(root)?.let { out.add(root to it) }
        for (i in 0 until a.childCount(root)) walkFull(a.child(root, i)?.also { childFetches++ }, out, depth + 1)
    }

    /**
     * The cached read: the same list [walkFull] gives, re-walking only dirty subtrees. [now] = a clock in ms (for the
     * safety re-walk). [pool] = threads for a parallel cold walk (null = sequential). Pre-order, capped at [MAX_ITEMS].
     */
    fun read(root: N, now: Long, pool: java.util.concurrent.ExecutorService? = null): List<Pair<N, String>> = synchronized(lock) {
        val rk = a.key(root)
        val full = allDirty || rk != rootKey || cache[rk] == null || readsSinceFull >= FULL_EVERY || now - lastFullAt > MAX_AGE_MS
        if (full) {
            cache.clear(); parentOf.clear(); dirty.clear(); deep.clear()
            fullWalks++; readsSinceFull = 0; lastFullAt = now
        } else { partialWalks++; readsSinceFull++ }
        val ctx = Ctx(cache, parentOf, MAX_ITEMS, shared = true)
        val sub = build(root, rk, 0, null, if (full && parallel) pool else null, ctx, false)
        childFetches += ctx.fetches; reusedSubtrees += ctx.reused
        rootKey = rk; allDirty = false; dirty.clear(); deep.clear(); tracked = 0
        if (sub.items.size > MAX_ITEMS) sub.items.subList(0, MAX_ITEMS) else sub.items
    }

    /** One subtree: from the cache when clean, else walked (its clean children spliced from the cache). */
    private fun build(n: N, k: Long, depth: Int, parent: Long?, pool: java.util.concurrent.ExecutorService?, ctx: Ctx, under: Boolean): Sub<N> {
        parent?.let { ctx.parents[k] = it }
        val isDeep = under || (ctx.shared && k in deep)
        // a clean subtree is spliced from the cache; one cut by the item cap only while what it holds still fills the cap
        if (ctx.shared && !isDeep && k !in dirty) cache[k]?.let { if (it.complete || it.items.size >= ctx.left) { ctx.reused++; ctx.left -= it.items.size; return it } }
        // the item cap is reached: stop here (legacy stopped too); cached as cut, so a later read walks it when needed
        if (ctx.left <= 0) return Sub(n, emptyList<Pair<N, String>>(), emptyList(), complete = false).also { ctx.subs[k] = it }
        if (depth > MAX_DEPTH || !a.visible(n)) return Sub(n, emptyList<Pair<N, String>>(), emptyList()).also { ctx.subs[k] = it }
        val items = ArrayList<Pair<N, String>>()
        lineOf(n, ctx)?.let { items.add(n to it); ctx.left-- }
        val count = a.childCount(n)
        val kids = ArrayList<Long>(count)
        val children = ArrayList<N>(count)
        for (i in 0 until count) {
            if (ctx.left <= 0 && pool == null) break
            val c = a.child(n, i) ?: continue
            ctx.fetches++; children.add(c); kids.add(a.key(c))
        }
        var complete = children.size == count
        val subs: List<Sub<N>> = if (pool != null && depth == 0 && children.size >= parallelMin) {
            // cold walk of a big window: the root's subtrees in parallel (each with its own budget), spliced back in order
            val futures = children.mapIndexed { i, c ->
                pool.submit(java.util.concurrent.Callable<Pair<Sub<N>, Ctx>> {
                    val w = Ctx(HashMap(), HashMap(), MAX_ITEMS, shared = false)
                    build(c, kids[i], depth + 1, k, null, w, true) to w
                })
            }
            futures.map { f ->
                val (s, w) = f.get()
                ctx.subs.putAll(w.subs); ctx.parents.putAll(w.parents); ctx.fetches += w.fetches
                s
            }
        } else children.mapIndexed { i, c -> build(c, kids[i], depth + 1, k, null, ctx, isDeep) }
        for (s in subs) {
            if (!s.complete) complete = false
            if (items.size >= MAX_ITEMS * 2) { complete = false; break }
            items.addAll(s.items)
        }
        if (pool != null) ctx.left = MAX_ITEMS - items.size
        val out = Sub(n, items as List<Pair<N, String>>, kids, complete)
        ctx.subs[k] = out
        return out
    }
}
