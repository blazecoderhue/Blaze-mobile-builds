package com.blaze.assistant.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * v0.29.2: the terminal's tabs live here, outside the screen, so shells keep running when you leave the
 * Terminal view and come back (like Termux). Each tab is a real PTY (Termux's terminal-emulator library):
 * vim, htop, nano, colours, Ctrl-C and resizing all work.
 */
object TermSessions {
    /** [busybox]: a phone tab running BusyBox ash (false = Android's own sh). */
    class Tab(val n: Int, val env: TermLogic.Env, val session: TerminalSession, val busybox: Boolean = false) {
        @Volatile var finished = false
        @Volatile var welcomed = false
        /** v0.30.2: when the tab was made (a BusyBox tab that dies within seconds is BusyBox failing to start). */
        val started = System.currentTimeMillis()
        /** v0.30.2: printed under the welcome, e.g. why this tab runs Android's shell instead of BusyBox. */
        @Volatile var note: String? = null
    }

    val tabs = mutableListOf<Tab>()
    @Volatile var current = 0
    private var counter = 0
    private var lastEnv: String? = null

    /** The open Terminal screen, told about output, titles and exits. */
    var screen: Listener? = null

    interface Listener {
        fun changed(tab: Tab?)
        fun structure()
        /** v0.30.2: [old] (a dead BusyBox tab) was swapped in place for [new]. */
        fun replaced(old: Tab, new: Tab) { structure() }
    }

    private val main = Handler(Looper.getMainLooper())

    private lateinit var app: Context

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { screen?.changed(find(s)) }
        override fun onTitleChanged(s: TerminalSession) { screen?.structure() }
        override fun onSessionFinished(s: TerminalSession) {
            val t = find(s)
            t?.finished = true
            // v0.29.8: a phone tab killed by Android's sandbox (signal 31) says why, and the next start skips BusyBox.
            if (t != null) {
                val status = try { s.exitStatus } catch (_: Exception) { 0 }
                // v0.30.2: a BusyBox tab killed by the sandbox, or dead with an error within seconds of starting, is BusyBox
                // not working on this phone: remember that for this build and swap the tab, right away, for one on
                // Android's own sh. No dead tab, no "press Enter".
                if (TermLogic.autoFallback(t.env.id, t.busybox, status, System.currentTimeMillis() - t.started)) {
                    Terminal.markBusyboxBroken()
                    main.post { respawnWithAndroidShell(t, TermLogic.fallbackNote(status)) }
                } else {
                    TermLogic.finishNote(t.env.id, t.busybox, status)?.let { note -> val b = note.toByteArray(); s.emulator?.append(b, b.size) }
                }
            }
            screen?.structure(); screen?.changed(t)
        }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String?) {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Blaze Terminal", text.orEmpty()))
        }
        override fun onPasteTextFromClipboard(s: TerminalSession?) {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString() ?: return
            s?.emulator?.paste(t)
        }
        override fun onBell(s: TerminalSession) { }
        override fun onColorsChanged(s: TerminalSession) { screen?.changed(find(s)) }
        override fun onTerminalCursorStateChange(state: Boolean) { }
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { }
        override fun logWarn(tag: String?, message: String?) { }
        override fun logInfo(tag: String?, message: String?) { }
        override fun logDebug(tag: String?, message: String?) { }
        override fun logVerbose(tag: String?, message: String?) { }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { }
        override fun logStackTrace(tag: String?, e: Exception?) { }
    }

    fun find(s: TerminalSession): Tab? = synchronized(tabs) { tabs.firstOrNull { it.session === s } }
    fun active(): Tab? = synchronized(tabs) { tabs.getOrNull(current) }

    fun envs(): List<TermLogic.Env> = TermLogic.envs(Distros.installedIds())

    /** A new tab running [env] (or the default one). */
    fun open(ctx: Context, env: TermLogic.Env? = null): Tab {
        app = ctx.applicationContext
        Terminal.init(app); Terminal.setup()
        val e = env ?: TermLogic.pick(lastEnv, Distros.installedIds(), Terminal.defaultDistro())
        lastEnv = e.id
        if (e.id != "phone") Linux.selfTest(e.id)      // v0.30.2: once per build, in the background
        val tab = spawn(e)
        synchronized(tabs) { tabs += tab; current = tabs.size - 1 }
        screen?.structure()
        return tab
    }

    /** v0.30.2: replaces a phone tab whose BusyBox couldn't start with one on Android's sh, at the same place. */
    private fun respawnWithAndroidShell(old: Tab, note: String) {
        if (synchronized(tabs) { old !in tabs }) return     // the user already closed it
        val tab = try { spawn(old.env, old.n) } catch (_: Exception) { return }
        tab.note = note
        synchronized(tabs) { val j = tabs.indexOf(old); if (j >= 0) tabs[j] = tab else { tab.session.finishIfRunning(); return } }
        screen?.replaced(old, tab)
    }

    /** A new tab (not yet listed) running [e]; [n] is its number on the chip. */
    private fun spawn(e: TermLogic.Env, n: Int = ++counter): Tab {
        val home = Terminal.home().absolutePath
        var usedBusybox = false
        val session = if (e.id != "phone" && Distros.installed(e.id)) {
            val cmd = Linux.command(null, "xterm-256color", e.id)
            TerminalSession(cmd.first(), home, cmd.toTypedArray(), TermLogic.envArray(Terminal.env(), Linux.env()), 3000, client)
        } else {
            // v0.29.8: BusyBox ash only when it passed Terminal.setup()'s probe, else Android's own sh (TermLogic.phoneLaunch).
            val bb = File(app.applicationInfo.nativeLibraryDir, "libbusybox.so").takeIf { it.exists() }?.absolutePath
            val l = TermLogic.phoneLaunch(bb, Terminal.busyboxOk)
            usedBusybox = l.busybox
            val extra = mapOf("ENV" to Terminal.phoneRc().absolutePath, "PS1" to if (l.busybox) "phone:\\w \\$ " else "phone \$ ")
            TerminalSession(l.path, home, l.argv.toTypedArray(), TermLogic.envArray(Terminal.env(), extra), 3000, client)
        }
        return Tab(n, e, session, usedBusybox)
    }

    fun select(i: Int) { synchronized(tabs) { if (i in tabs.indices) current = i }; screen?.structure() }

    fun close(tab: Tab) {
        tab.session.finishIfRunning()
        synchronized(tabs) {
            val i = tabs.indexOf(tab); tabs.remove(tab)
            if (current >= tabs.size) current = (tabs.size - 1).coerceAtLeast(0) else if (i in 0 until current) current--
        }
        screen?.structure()
    }

    fun ensureOne(ctx: Context): Tab = active() ?: open(ctx)
}
