package com.blaze.assistant.terminal

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.widget.FrameLayout
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.29.2 Blaze Terminal: a full-screen, Termux-style terminal. Black screen, monospace text, Termux's two
 * extra-keys rows (ESC / ― HOME ↑ END PGUP · ⇥ CTRL ALT ← ↓ → PGDN), tabs along the top, pinch to zoom,
 * long-press to select and copy, paste from the menu. Tabs run the user's Alpine Linux (PRoot) or the phone shell.
 * v0.29.8: Blaze colours (blue accent, rounded key caps), ⋮ and + open a Blaze bottom sheet (TermSheetHost) instead of a
 * white Android pop-up, and a phone tab shows tappable example commands above the keys.
 */
class BlazeTerminalActivity : ComponentActivity(), TermSessions.Listener {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, BlazeTerminalActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        private var fontPx = 0
    }

    private lateinit var term: TerminalView
    private lateinit var tabRow: LinearLayout
    private val keyViews = mutableMapOf<String, TextView>()
    private var ctrl = false
    private var alt = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // v0.45.8: every colour comes from the picked theme (TermPalette, 4.5:1 or more against the ground); set in applyTheme().
    private var pal = com.blaze.assistant.ui.TermPalette.of(com.blaze.assistant.ui.LivingThemes.CLASSIC, true)
    private var ink = pal.fg
    private var dim = pal.dim
    private var accent = pal.accent
    private var keyBg = pal.keyBg
    private var keyEdge = pal.keyEdge
    private var barBg = pal.barBg
    private lateinit var root: LinearLayout
    private lateinit var examples: LinearLayout
    private lateinit var keys: LinearLayout
    private lateinit var topBar: LinearLayout
    private val sheet = TermSheet.State()
    private lateinit var examplesBar: HorizontalScrollView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = barBg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        if (fontPx == 0) fontPx = (resources.displayMetrics.scaledDensity * 13).toInt().coerceIn(TermLogic.MIN_FONT, TermLogic.MAX_FONT)

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        // Tabs + menu along the top.
        val top = LinearLayout(this).also { topBar = it }.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(2)) }
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(chip("+", false) { newTabMenu() }.also { it.contentDescription = "New tab" })
        top.addView(chip("⋮", false) { moreMenu() }.also { it.contentDescription = "More: theme, paste, text size" })
        root.addView(top)

        term = TerminalView(this, null).apply {
            setBackgroundColor(Color.BLACK)
            setTextSize(fontPx)
            setTypeface(Typeface.MONOSPACE)
            isFocusable = true; isFocusableInTouchMode = true
            keepScreenOn = true
        }
        term.setTerminalViewClient(viewClient)
        root.addView(term, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // v0.29.8: tappable example commands (phone tabs only), so the phone shell explains itself.
        examples = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        examplesBar = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(barBg); addView(examples) }
        root.addView(examplesBar)

        keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(barBg); setPadding(dp(4), dp(3), dp(4), dp(4)) }
        root.addView(keys)

        // The Blaze sheet floats over everything (scrim + rounded card); it draws nothing while closed.
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { TermSheetHost(sheet) }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
        com.blaze.assistant.ui.MotionTokens.refresh(this)

        TermSessions.screen = this
        val tab = TermSessions.ensureOne(this)
        applyTheme()
        attach(tab)
        structure()
        term.requestFocus()
        // v0.45.8: one shared theme state: a switch made here, in Chat, Music or anywhere else repaints the terminal.
        scope.launch {
            kotlinx.coroutines.flow.combine(com.blaze.assistant.ui.Look.theme,
                androidx.compose.runtime.snapshotFlow { com.blaze.assistant.ui.ThemeMode.mode.value }) { a, b -> a to b }
                .collect { applyTheme() }
        }
    }

    /** v0.45.8: paints the bars, keys, sheet and the emulator's colours (ANSI, text, ground, cursor) from the theme. */
    private fun applyTheme() {
        val p = com.blaze.assistant.ui.TermPalette.of(com.blaze.assistant.ui.Look.theme.value, com.blaze.assistant.ui.AppTheme.darkNow())
        pal = p; ink = p.fg; dim = p.dim; accent = p.accent; keyBg = p.keyBg; keyEdge = p.keyEdge; barBg = p.barBg
        TermSheet.Palette.set(p)
        TermColors.apply(p)
        try {
            window.statusBarColor = p.bg; window.navigationBarColor = p.barBg
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !p.dark; isAppearanceLightNavigationBars = !p.dark
            }
        } catch (_: Throwable) { }
        root.setBackgroundColor(p.bg); topBar.setBackgroundColor(p.bg); term.setBackgroundColor(p.bg)
        examplesBar.setBackgroundColor(p.barBg); keys.setBackgroundColor(p.barBg)
        examples.removeAllViews()
        for ((label, cmd) in TermLogic.PHONE_EXAMPLES) examples.addView(chip(label, false) { runExample(cmd) })
        keys.removeAllViews(); keyViews.clear()
        for (row in TermLogic.ROWS) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in row) r.addView(extraKey(k), LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            keys.addView(r)
        }
        paintToggles()
        // The + and ⋮ chips at the end of the top bar.
        for (i in 0 until topBar.childCount) (topBar.getChildAt(i) as? TextView)?.let { it.setTextColor(ink); it.background = pill(keyBg, keyEdge) }
        if (::tabRow.isInitialized) structure()
        term.invalidate()
    }

    override fun onResume() {
        super.onResume()
        TermSessions.screen = this
        TermSessions.active()?.let { attach(it) }
        structure()
    }

    override fun onDestroy() {
        if (TermSessions.screen === this) TermSessions.screen = null
        super.onDestroy()
    }

    private fun attach(tab: TermSessions.Tab) {
        term.attachSession(tab.session)
        term.onScreenUpdated()
    }

    // ---- TermSessions.Listener ----
    override fun changed(tab: TermSessions.Tab?) {
        if (tab == null || tab === TermSessions.active()) term.onScreenUpdated()
    }

    /** v0.30.2: a phone tab whose BusyBox couldn't start was swapped for one on Android's sh: show the new one. */
    override fun replaced(old: TermSessions.Tab, new: TermSessions.Tab) {
        if (TermSessions.active() === new) attach(new)
        structure()
    }

    override fun structure() {
        tabRow.removeAllViews()
        val tabs = synchronized(TermSessions.tabs) { TermSessions.tabs.toList() }
        tabs.forEachIndexed { i, t ->
            val sel = i == TermSessions.current
            val c = chip(TermLogic.tabLabel(t.n, t.env, t.session.title, t.finished), sel) {
                if (TermSessions.current != i) { TermSessions.select(i); attach(t) }
            }
            c.setOnLongClickListener { confirmClose(t); true }
            tabRow.addView(c)
        }
        TermSessions.active()?.let { if (term.currentSession !== it.session) attach(it) }
        if (::examplesBar.isInitialized) examplesBar.visibility = if (TermSessions.active()?.env?.id == "phone") View.VISIBLE else View.GONE
    }

    /** Types an example command into the active tab and runs it (a finished tab is reopened first). */
    private fun runExample(cmd: String) {
        var t = TermSessions.active() ?: return
        if (t.finished) { val env = t.env; TermSessions.close(t); t = TermSessions.open(this, env); attach(t) }
        t.session.write(cmd + "\r")
        term.requestFocus()
    }

    // ---- UI pieces ----
    private fun pill(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat(); setColor(fill); setStroke(dp(1), stroke)
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        typeface = Typeface.MONOSPACE; textSize = 13f
        setTextColor(if (selected) pal.bg else ink)   // v0.45.8: the ground colour reads 4.5:1 on the accent
        background = pill(if (selected) accent else keyBg, if (selected) accent else keyEdge)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun extraKey(k: TermLogic.Key) = TextView(this).apply {
        text = k.label; gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE; textSize = 14f
        setTextColor(ink); background = keyCap(false)
        if (k.toggle.isNotEmpty()) keyViews[k.toggle] = this
        var repeating: Runnable? = null
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.background = keyCap(true)
                    if (com.blaze.assistant.ui.MotionTokens.enabled) v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(com.blaze.assistant.ui.MotionTokens.ms(com.blaze.assistant.ui.MotionTokens.SHORT) / 2).start()
                    press(k)
                    if (k.repeat) {
                        val r = object : Runnable { override fun run() { press(k); main.postDelayed(this, 60) } }
                        repeating = r; main.postDelayed(r, 400)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.background = keyCap(k.toggle == "ctrl" && ctrl || k.toggle == "alt" && alt)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(com.blaze.assistant.ui.MotionTokens.ms(com.blaze.assistant.ui.MotionTokens.SHORT)).start()
                    repeating?.let { main.removeCallbacks(it) }; repeating = null
                }
            }
            true
        }
    }

    private fun press(k: TermLogic.Key) {
        val s = term.currentSession ?: return
        when {
            k.toggle == "ctrl" -> { ctrl = !ctrl; paintToggles() }
            k.toggle == "alt" -> { alt = !alt; paintToggles() }
            k.code != 0 -> {
                var mod = 0
                if (ctrl) mod = mod or com.termux.terminal.KeyHandler.KEYMOD_CTRL
                if (alt) mod = mod or com.termux.terminal.KeyHandler.KEYMOD_ALT
                term.handleKeyCode(k.code, mod)
                if (!k.repeat) resetToggles()
            }
            k.text.isNotEmpty() -> {
                if (ctrl || alt) { term.inputCodePoint(k.text.codePointAt(0), ctrl, alt); resetToggles() }
                else s.write(k.text)
            }
        }
    }

    /** A rounded Blaze key cap; [on] = held, or a CTRL/ALT that's switched on (Blaze blue). */
    private fun keyCap(on: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(if (on) Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent)) else keyBg)
        setStroke(dp(1), if (on) accent else keyEdge)
    }

    private fun paintToggles() {
        keyViews["ctrl"]?.let { it.setTextColor(if (ctrl) accent else ink); it.background = keyCap(ctrl) }
        keyViews["alt"]?.let { it.setTextColor(if (alt) accent else ink); it.background = keyCap(alt) }
    }

    private fun resetToggles() { if (ctrl || alt) { ctrl = false; alt = false; paintToggles() } }

    private fun showKeyboard() {
        term.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(term, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun newTabMenu() {
        val envs = TermSessions.envs()
        val items = envs.map { e ->
            if (e.id == "phone") TermSheet.Item(e.id, "Phone shell", "This phone's own command line: ls, cd, top, ping… Nothing to install.", "phone")
            else TermSheet.Item(e.id, e.label, "Full Linux: ${e.hint.firstOrNull { it.startsWith("Install") }?.removePrefix("Install:")?.trim() ?: "install packages"}", "linux")
        } + TermSheet.Item("install", "Install a Linux…", "Ubuntu, Debian, Arch, Alpine… for apt, python, git and more", "install")
        sheet.show("New tab", "Pick what the new tab runs", items) { item ->
            val e = envs.firstOrNull { it.id == item.id }
            if (e != null) { attach(TermSessions.open(this, e)); showKeyboard() } else installMenu()
        }
    }

    /** Every distro this phone can run, with its size; installed ones can be removed (long-press style: second dialog). */
    private fun installMenu() {
        if (!Linux.supported()) { Toast.makeText(this, "Linux isn't supported on this phone.", Toast.LENGTH_LONG).show(); return }
        val cpu = TermLogic.cpu(android.os.Build.SUPPORTED_ABIS.firstOrNull()) ?: "aarch64"
        val list = Distros.installable()
        val labels = list.map { d ->
            val size = TermLogic.sizeLine(d, cpu)
            "${d.env().label}  ·  " + if (Distros.installed(d.id)) "installed" else size
        }
        dialog().setTitle("Linux on this phone").setItems(labels.toTypedArray()) { _, i ->
            val d = list[i]
            if (Distros.installed(d.id)) {
                dialog().setTitle(d.env().label)
                    .setItems(arrayOf("Open a new tab", "Remove ${d.name}")) { _, j ->
                        if (j == 0) { attach(TermSessions.open(this, d.env())); showKeyboard() }
                        else dialog().setMessage("Remove ${d.name} and everything installed in it?")
                            .setPositiveButton("Remove") { _, _ ->
                                synchronized(TermSessions.tabs) { TermSessions.tabs.filter { it.env.id == d.id } }.forEach { TermSessions.close(it) }
                                scope.launch { withContext(Dispatchers.IO) { Distros.uninstall(d.id) }; Toast.makeText(this@BlazeTerminalActivity, "${d.name} removed", Toast.LENGTH_SHORT).show()
                                    TermSessions.active()?.let { attach(it) } ?: attach(TermSessions.open(this@BlazeTerminalActivity)) }
                            }.setNegativeButton("Cancel", null).show()
                    }.show()
            } else install(d)
        }.show()
    }

    private fun install(d: TermLogic.Distro) {
        val text = TextView(this).apply { setPadding(dp(24), dp(16), dp(24), dp(8)); textSize = 14f; this.text = "Starting…" }
        val dlg = dialog().setTitle("Installing ${d.env().label}").setView(text)
            .setNegativeButton("Hide", null).setCancelable(true).show()
        val watch = scope.launch {
            val flow = if (d.id == "alpine") Linux.status else Distros.status
            flow.collect { text.text = it.ifBlank { "Starting…" } }
        }
        scope.launch {
            val r = Distros.install(d.id)
            watch.cancel()
            if (Distros.installed(d.id)) {
                dlg.dismiss()
                Toast.makeText(this@BlazeTerminalActivity, r.take(160), Toast.LENGTH_SHORT).show()
                attach(TermSessions.open(this@BlazeTerminalActivity, d.env())); showKeyboard()
            } else text.text = r
        }
    }

    /** v0.45.8: the theme switcher (the same options as everywhere else; picking one changes every screen). */
    private fun themeMenu() {
        val cur = com.blaze.assistant.ui.Look.theme.value
        val items = com.blaze.assistant.ui.AppTheme.options.map { th ->
            TermSheet.Item(th.id.ifEmpty { "classic" }, th.name + if (th.id == cur) "  \u2713" else "", th.sub, "theme")
        }
        sheet.show("Theme", "Changes Blaze everywhere: chat, Home, Music, Video, Design and here", items) { item ->
            com.blaze.assistant.ui.AppTheme.pick(if (item.id == "classic") com.blaze.assistant.ui.LivingThemes.CLASSIC else item.id)
        }
    }

    private fun moreMenu() {
        val tab = TermSessions.active()
        val items = buildList {
            add(TermSheet.Item("theme", "Theme", com.blaze.assistant.ui.LivingThemes.of(com.blaze.assistant.ui.Look.theme.value).name + " \u00b7 the same theme as the rest of Blaze", "theme"))
            add(TermSheet.Item("paste", "Paste", "Type what's on the clipboard", "paste"))
            add(TermSheet.Item("copy", "Copy all text", "Everything in this tab, to the clipboard", "copy"))
            add(TermSheet.Item("keyboard", "Keyboard", "Show the on-screen keyboard", "keyboard"))
            add(TermSheet.Item("bigger", "Bigger text", "Or pinch out on the terminal", "bigger"))
            add(TermSheet.Item("smaller", "Smaller text", "Or pinch in", "smaller"))
            if (tab?.env?.id == "phone") add(TermSheet.Item("help", "What can I type here?", "Runs help: every tool in the phone shell", "help"))
            add(TermSheet.Item("restart", "Restart this tab", "Stop what's running and start fresh", "restart"))
            add(TermSheet.Item("close", "Close this tab", "", "close", danger = true))
        }
        val title = tab?.let { TermLogic.tabLabel(it.n, it.env, it.session.title, it.finished) } ?: "Terminal"
        sheet.show(title, tab?.env?.label.orEmpty(), items) { item ->
            val s = term.currentSession
            when (item.id) {
                "theme" -> themeMenu()
                "paste" -> s?.let { pasteInto(it) }
                "copy" -> s?.emulator?.screen?.transcriptText?.let { txt ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Blaze Terminal", txt)); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
                "keyboard" -> showKeyboard()
                "bigger" -> setFont(TermLogic.zoom(fontPx, 1.15f))
                "smaller" -> setFont(TermLogic.zoom(fontPx, 1 / 1.15f))
                "help" -> runExample("help")
                "restart" -> tab?.let { val env = it.env; TermSessions.close(it); attach(TermSessions.open(this, env)) }
                "close" -> tab?.let { confirmClose(it) }
            }
        }
    }

    @Deprecated("Back closes the Blaze sheet first")
    override fun onBackPressed() {
        if (sheet.open) { sheet.close(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** Dark Android dialogs for the remaining confirmations, so nothing flashes white over the terminal. */
    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun pasteInto(s: TerminalSession) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        s.emulator?.paste(t)
    }

    private fun setFont(px: Int) { fontPx = px; term.setTextSize(px) }

    private fun confirmClose(t: TermSessions.Tab) {
        if (t.finished) { closeTab(t); return }
        dialog().setMessage("Close tab ${t.n}? Anything running in it stops.")
            .setPositiveButton("Close") { _, _ -> closeTab(t) }.setNegativeButton("Cancel", null).show()
    }

    private fun closeTab(t: TermSessions.Tab) {
        TermSessions.close(t)
        val next = TermSessions.active()
        if (next == null) finish() else attach(next)
    }

    // ---- Termux's view client ----
    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) { setFont(TermLogic.zoom(fontPx, scale)); return 1.0f }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) {
            val t = TermSessions.active()
            if (t != null && t.finished) { val env = t.env; TermSessions.close(t); attach(TermSessions.open(this@BlazeTerminalActivity, env)) }
            showKeyboard()
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) { }
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (keyCode == KeyEvent.KEYCODE_ENTER && TermSessions.active()?.finished == true) {
                TermSessions.active()?.let { val env = it.env; TermSessions.close(it); attach(TermSessions.open(this@BlazeTerminalActivity, env)) }
                return true
            }
            return false
        }
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey() = ctrl
        override fun readAltKey() = alt
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            if (ctrl || alt) main.post { resetToggles() }
            return false
        }
        override fun onEmulatorSet() {
            val t = TermSessions.active() ?: return
            if (!t.welcomed) {
                t.welcomed = true
                val b = (TermLogic.welcome(t.env, t.busybox) + t.note.orEmpty()).toByteArray()
                t.session.emulator?.append(b, b.size)
                term.onScreenUpdated()
            }
        }
        override fun logError(tag: String?, message: String?) { }
        override fun logWarn(tag: String?, message: String?) { }
        override fun logInfo(tag: String?, message: String?) { }
        override fun logDebug(tag: String?, message: String?) { }
        override fun logVerbose(tag: String?, message: String?) { }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { }
        override fun logStackTrace(tag: String?, e: Exception?) { }
    }
}
