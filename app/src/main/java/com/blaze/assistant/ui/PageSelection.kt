package com.blaze.assistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.webkit.WebView
import android.widget.PopupMenu

/**
 * v0.29.9: a web page's text selection, routed to Blaze's own selection pill (the same one the chat uses) instead of
 * the phone's floating Copy / Share bar.
 *
 * The WebView asks its view for an action mode when words are selected; [PillWebView] hands it a [PageSelectMode]
 * that draws nothing itself. The page's own callback still fills a real menu (so its Copy and Select all keep working)
 * and still tells us where the selection sits ([contentRect]); the pill is drawn by Compose on top.
 */
class PageSelectMode(private val host: View, val callback: ActionMode.Callback, private val onChange: (PageSelectMode) -> Unit) : ActionMode() {
    private val menu: Menu = PopupMenu(host.context, host).menu
    private val main = Handler(Looper.getMainLooper())
    private val reshow = Runnable { if (hidden && !finished) { hidden = false; onChange(this) } }
    /** True while the page asks the bar to step aside (scrolling, dragging a handle). */
    var hidden = false
        private set
    var finished = false
        private set

    init { type = ActionMode.TYPE_FLOATING }

    fun start(): Boolean {
        if (!callback.onCreateActionMode(this, menu)) return false
        callback.onPrepareActionMode(this, menu)
        onChange(this)
        return true
    }

    /** The selection's box in the WebView's own px (the whole view when the page can't say). */
    fun contentRect(): android.graphics.Rect {
        val r = android.graphics.Rect()
        val cb = callback
        if (cb is ActionMode.Callback2) cb.onGetContentRect(this, host, r) else r.set(0, 0, host.width, host.height)
        return r
    }

    fun has(id: Int): Boolean = menu.findItem(id) != null

    /** Runs the page's own menu item [id] (android.R.id.copy, selectAll…). False when the page doesn't offer it. */
    fun click(id: Int): Boolean {
        val item = menu.findItem(id) ?: return false
        return try { callback.onActionItemClicked(this, item) } catch (_: Exception) { false }
    }

    /** Forget this mode without telling the page (a newer selection replaced it). */
    fun retire() { finished = true; main.removeCallbacks(reshow) }

    override fun setTitle(title: CharSequence?) {}
    override fun setTitle(resId: Int) {}
    override fun setSubtitle(subtitle: CharSequence?) {}
    override fun setSubtitle(resId: Int) {}
    override fun setCustomView(view: View?) {}
    override fun invalidate() { if (finished) return; try { callback.onPrepareActionMode(this, menu) } catch (_: Exception) { }; onChange(this) }
    override fun invalidateContentRect() { if (!finished) onChange(this) }
    override fun hide(duration: Long) {
        if (finished) return
        main.removeCallbacks(reshow)
        hidden = duration != 0L
        if (hidden) main.postDelayed(reshow, if (duration < 0) 2_000L else duration.coerceAtMost(3_000L))
        onChange(this)
    }
    override fun finish() {
        if (finished) return
        finished = true; main.removeCallbacks(reshow)
        try { callback.onDestroyActionMode(this) } catch (_: Exception) { }
        onChange(this)
    }
    override fun getMenu(): Menu = menu
    override fun getTitle(): CharSequence? = null
    override fun getSubtitle(): CharSequence? = null
    override fun getCustomView(): View? = null
    override fun getMenuInflater(): MenuInflater = MenuInflater(host.context)
}

/** A WebView whose text selection shows Blaze's selection pill ([onSelection] gets the live mode, or null when it ends). */
@SuppressLint("ViewConstructor")
class PillWebView(ctx: Context, private val onSelection: (PageSelectMode?) -> Unit) : WebView(ctx) {
    var mode: PageSelectMode? = null
        private set

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        if (callback == null) return super.startActionMode(callback, type)
        mode?.retire()
        val m = PageSelectMode(this, callback) { x ->
            if (mode === x) { if (x.finished) { mode = null; onSelection(null) } else onSelection(x) }
        }
        mode = m
        return if (m.start()) m else { m.retire(); mode = null; onSelection(null); null }
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? = startActionMode(callback, ActionMode.TYPE_PRIMARY)

    /** End the selection (the page clears its highlight). */
    fun clearSelection() { mode?.finish(); mode = null }

    /** The selected words, read from the page (empty when the page can't say, e.g. inside a frame). */
    fun selectedText(done: (String) -> Unit) {
        try {
            evaluateJavascript("(function(){var s=window.getSelection?window.getSelection():null;return s?s.toString():'';})()") { raw ->
                done(PageSelectionText.decode(raw))
            }
        } catch (_: Exception) { done("") }
    }
}

/** Pure part (PageSelectionTextTest): evaluateJavascript hands back a JSON value; turn it into the plain words. */
object PageSelectionText {
    fun decode(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "null" || s == "undefined") return ""
        if (!s.startsWith("\"")) return s
        val out = StringBuilder()
        var i = 1
        while (i < s.length - 1) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length - 1) {
                val n = s[i + 1]
                when (n) {
                    'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                    'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                    '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length - 1))
                        val code = hex.toIntOrNull(16)
                        if (code != null && hex.length == 4) { out.append(code.toChar()); i += 6; continue } else out.append(n)
                    }
                    else -> out.append(n)
                }
                i += 2
            } else { out.append(ch); i++ }
        }
        return out.toString()
    }
}
