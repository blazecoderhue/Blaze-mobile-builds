package com.blaze.assistant.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * Blaze Phone (spike): a guest app's window as a tree Blaze can read, captured in-process inside the guest (no
 * Accessibility). [serialize] is the wire / tool format (BlazePhoneTest pins it):
 *
 * ```
 * {"v":1,"pkg":"org.schabi.newpipe","window":"MainActivity","w":1080,"h":2340,"count":42,"truncated":false,
 *  "root":{"cls":"FrameLayout","id":"content","b":[0,0,1080,2340],"flags":["clickable"],"text":"…","desc":"…",
 *          "children":[…]}}
 * ```
 * Bounds are display pixels [left, top, right, bottom] on Blaze's VirtualDisplay (the same space tap() takes). Keys
 * that would be empty are left out. At most [MAX_NODES] nodes and [MAX_DEPTH] levels; text cut at [MAX_TEXT] chars.
 */
object GuestViewTree {
    const val VERSION = 1
    const val MAX_NODES = 600
    const val MAX_DEPTH = 30
    const val MAX_TEXT = 160

    data class Node(
        val cls: String,
        val id: String? = null,
        val text: String? = null,
        val desc: String? = null,
        val bounds: IntArray = IntArray(4),
        val clickable: Boolean = false,
        val editable: Boolean = false,
        val focused: Boolean = false,
        val checked: Boolean = false,
        val selected: Boolean = false,
        val scrollable: Boolean = false,
        val enabled: Boolean = true,
        val children: List<Node> = emptyList(),
    )

    fun serialize(root: Node?, pkg: String, window: String, width: Int, height: Int): String {
        var count = 0
        var truncated = false
        fun node(n: Node, depth: Int): JSONObject? {
            if (count >= MAX_NODES) { truncated = true; return null }
            count++
            val o = JSONObject().put("cls", n.cls)
            n.id?.takeIf { it.isNotBlank() }?.let { o.put("id", it) }
            n.text?.takeIf { it.isNotBlank() }?.let { o.put("text", cut(it)) }
            n.desc?.takeIf { it.isNotBlank() }?.let { o.put("desc", cut(it)) }
            o.put("b", JSONArray().apply { n.bounds.forEach { put(it) } })
            val flags = JSONArray()
            if (n.clickable) flags.put("clickable")
            if (n.editable) flags.put("editable")
            if (n.focused) flags.put("focused")
            if (n.checked) flags.put("checked")
            if (n.selected) flags.put("selected")
            if (n.scrollable) flags.put("scrollable")
            if (!n.enabled) flags.put("disabled")
            if (flags.length() > 0) o.put("flags", flags)
            if (n.children.isNotEmpty()) {
                if (depth >= MAX_DEPTH) truncated = true
                else {
                    val kids = JSONArray()
                    for (c in n.children) node(c, depth + 1)?.let { kids.put(it) } ?: break
                    if (kids.length() > 0) o.put("children", kids)
                }
            }
            return o
        }
        val r = root?.let { node(it, 0) }
        return JSONObject().put("v", VERSION).put("pkg", pkg).put("window", window).put("w", width).put("h", height)
            .put("count", count).put("truncated", truncated).put("root", r ?: JSONObject.NULL).toString()
    }

    private fun cut(s: String): String = s.replace('\n', ' ').let { if (it.length <= MAX_TEXT) it else it.take(MAX_TEXT - 1) + "…" }

    /** Captures [root] (a guest window's decor view) on the guest's main thread. Invisible views are skipped. */
    fun capture(root: android.view.View): Node? = captureView(root, 0)

    private fun captureView(v: android.view.View, depth: Int): Node? {
        if (v.visibility != android.view.View.VISIBLE || depth > MAX_DEPTH) return null
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        val id = if (v.id != android.view.View.NO_ID && v.id ushr 24 != 0) try { v.resources.getResourceEntryName(v.id) } catch (_: Exception) { null } else null
        val tv = v as? android.widget.TextView
        val kids = (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).mapNotNull { captureView(g.getChildAt(it), depth + 1) } }.orEmpty()
        return Node(
            cls = v.javaClass.simpleName.ifBlank { v.javaClass.name },
            id = id,
            text = tv?.text?.toString(),
            desc = v.contentDescription?.toString(),
            bounds = intArrayOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height),
            clickable = v.isClickable || v.isLongClickable,
            editable = tv != null && tv.onCheckIsTextEditor(),
            focused = v.isFocused,
            checked = (v as? android.widget.Checkable)?.isChecked == true,
            selected = v.isSelected,
            scrollable = v.canScrollVertically(1) || v.canScrollVertically(-1) || v.canScrollHorizontally(1) || v.canScrollHorizontally(-1),
            enabled = v.isEnabled,
            children = kids,
        )
    }
}
