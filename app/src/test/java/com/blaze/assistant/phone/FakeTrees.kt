package com.blaze.assistant.phone

import java.util.concurrent.atomic.AtomicLong

/**
 * 0.45.19: synthetic accessibility trees for ScreenTreeTest / ScreenReadBench, shaped like what read_screen sees:
 * a Samsung Settings page (~300 nodes: toolbar, a RecyclerView of rows, each row a clickable layout with an icon,
 * title, summary and sometimes a Switch, wrapped in frames) and a heavy app (~1500 nodes: a feed of cards with
 * avatars, names, text, image grids and action buttons). [ipcNanos] = simulated binder cost of one child fetch.
 */
class FakeNode(val key: Long, var cls: String, var text: String = "", var desc: String = "", var clickable: Boolean = false,
               var checkable: Boolean = false, var checked: Boolean = false, var scrollable: Boolean = false, var editable: Boolean = false,
               var visible: Boolean = true, var enabled: Boolean = true) {
    val children = mutableListOf<FakeNode>()
    var parent: FakeNode? = null
    fun add(c: FakeNode): FakeNode { c.parent = this; children.add(c); return c }
    fun count(): Int = 1 + children.sumOf { it.count() }
    fun all(): List<FakeNode> = listOf(this) + children.flatMap { it.all() }
}

class FakeAccess(var ipcNanos: Long = 0) : ScreenTree.Access<FakeNode> {
    val fetches = AtomicLong()
    private fun spin(ns: Long) { if (ns <= 0) return; val end = System.nanoTime() + ns; while (System.nanoTime() < end) { } }
    override fun key(n: FakeNode) = n.key
    override fun visible(n: FakeNode) = n.visible
    override fun text(n: FakeNode) = n.text
    override fun desc(n: FakeNode) = n.desc
    override fun hint(n: FakeNode) = ""
    override fun cls(n: FakeNode) = n.cls
    override fun clickable(n: FakeNode) = n.clickable
    override fun editable(n: FakeNode) = n.editable
    override fun scrollable(n: FakeNode) = n.scrollable
    override fun checkable(n: FakeNode) = n.checkable
    override fun checked(n: FakeNode) = n.checked
    override fun selected(n: FakeNode) = false
    override fun enabled(n: FakeNode) = n.enabled
    override fun childCount(n: FakeNode) = n.children.size
    override fun child(n: FakeNode, i: Int): FakeNode? { fetches.incrementAndGet(); spin(ipcNanos); return n.children.getOrNull(i) }
}

object FakeTrees {
    private var next = 1L
    private fun id() = next++

    /** A Settings page like Samsung's Display page: ~300 nodes. */
    fun settings(rows: Int = 29): FakeNode {
        val root = FakeNode(id(), "android.widget.FrameLayout")
        val deco = root.add(FakeNode(id(), "android.widget.LinearLayout"))
        val bar = deco.add(FakeNode(id(), "android.view.ViewGroup"))
        bar.add(FakeNode(id(), "android.widget.ImageButton", desc = "Navigate up", clickable = true))
        bar.add(FakeNode(id(), "android.widget.TextView", text = "Display"))
        bar.add(FakeNode(id(), "android.widget.ImageButton", desc = "Search", clickable = true))
        val content = deco.add(FakeNode(id(), "android.widget.FrameLayout"))
        val list = content.add(FakeNode(id(), "androidx.recyclerview.widget.RecyclerView", scrollable = true))
        for (r in 0 until rows) {
            val frame = list.add(FakeNode(id(), "android.widget.FrameLayout"))
            val row = frame.add(FakeNode(id(), "android.widget.LinearLayout", clickable = true))
            val iconBox = row.add(FakeNode(id(), "android.widget.FrameLayout"))
            iconBox.add(FakeNode(id(), "android.widget.ImageView"))
            val texts = row.add(FakeNode(id(), "android.widget.RelativeLayout"))
            texts.add(FakeNode(id(), "android.widget.TextView", text = if (r == 0) "Use Dark theme" else "Setting row $r"))
            texts.add(FakeNode(id(), "android.widget.TextView", text = "Summary for row $r, explains what it does"))
            val wf = row.add(FakeNode(id(), "android.widget.LinearLayout"))
            if (r % 2 == 0) wf.add(FakeNode(id(), "android.widget.Switch", checkable = true, checked = r % 4 == 0))
            else wf.add(FakeNode(id(), "android.view.View"))
            row.add(FakeNode(id(), "android.view.View"))
        }
        return root
    }

    /** A heavy app screen (social feed): ~1500 nodes. */
    fun heavy(cards: Int = 54): FakeNode {
        val root = FakeNode(id(), "android.widget.FrameLayout")
        val top = root.add(FakeNode(id(), "android.view.ViewGroup"))
        for (t in listOf("Home", "Search", "Reels", "Shop", "Profile")) top.add(FakeNode(id(), "android.widget.FrameLayout", desc = t, clickable = true)).add(FakeNode(id(), "android.widget.ImageView"))
        val feed = root.add(FakeNode(id(), "androidx.recyclerview.widget.RecyclerView", scrollable = true))
        for (c in 0 until cards) {
            val card = feed.add(FakeNode(id(), "android.view.ViewGroup"))
            val head = card.add(FakeNode(id(), "android.widget.LinearLayout"))
            head.add(FakeNode(id(), "android.widget.ImageView", desc = "Profile picture", clickable = true))
            val nm = head.add(FakeNode(id(), "android.widget.LinearLayout"))
            nm.add(FakeNode(id(), "android.widget.TextView", text = "user_$c", clickable = true))
            nm.add(FakeNode(id(), "android.widget.TextView", text = "${c + 1}h"))
            head.add(FakeNode(id(), "android.widget.ImageView", desc = "More options", clickable = true))
            val body = card.add(FakeNode(id(), "android.widget.FrameLayout"))
            val grid = body.add(FakeNode(id(), "android.view.ViewGroup"))
            for (g in 0 until 4) grid.add(FakeNode(id(), "android.widget.FrameLayout")).add(FakeNode(id(), "android.widget.ImageView"))
            body.add(FakeNode(id(), "android.widget.TextView", text = "Caption $c with a few words about the post"))
            val acts = card.add(FakeNode(id(), "android.widget.LinearLayout"))
            for (a in listOf("Like", "Comment", "Share", "Save")) acts.add(FakeNode(id(), "android.widget.FrameLayout", desc = a, clickable = true)).add(FakeNode(id(), "android.widget.ImageView"))
            acts.add(FakeNode(id(), "android.view.View"))
        }
        return root
    }
}
