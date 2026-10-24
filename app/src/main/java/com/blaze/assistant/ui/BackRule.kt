package com.blaze.assistant.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import android.view.Window
import com.blaze.assistant.agent.ChatNav
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * 0.45.19 Blaze Phone UI fix 7: ONE rule for the system Back button. From Blaze Phone and from every other window,
 * view, sheet and page Blaze has, Back returns to the Blaze chat in one press. The chat itself is the root (Back there
 * does what it always did: a private chat asks before leaving, otherwise Android leaves Blaze).
 *
 * How it stays central (future screens get it without doing anything):
 *  - Inside the main window, the chat shell publishes what is in front ([shellNow]) and how to get back to the chat
 *    ([toChat]); every view is reached through the shell's own state (settings pages, Views, sheets, Projects, Home,
 *    Design, Gallery, Search, menus), so a new one is just another value of that state and [front] already names it.
 *  - Every Blaze activity's window gets a key interceptor when it is created ([install], from BlazeApp), before any
 *    Compose BackHandler or onBackPressed sees the key; an activity that needs a last step before leaving (Blaze Phone
 *    hands back, Video keeps a playing video in picture-in-picture) implements [ToChat]. A new activity is covered the
 *    moment it is in the manifest.
 *  - Exempt on purpose ([OWN_BACK], [EXEMPT_ACTIVITIES]): the app lock (Back never skips it), first-run setup, a live
 *    call (Back hangs up, which lands on the chat), and two result-returning helpers (permission prompt, camera).
 * The app does not opt in to android:enableOnBackInvokedCallback, so Back still arrives as a key event on Android 13+
 * (BackRuleTest checks the manifest).
 */
object BackRule {
    const val CHAT = "chat"
    const val MAIN_ACTIVITY = "com.blaze.assistant.MainActivity"

    /** Everything the chat shell can show over (or instead of) the main chat. */
    data class Shell(
        val onboarding: Boolean = false, val inCall: Boolean = false,
        val tab: String = ChatNav.CHAT, val inProjectChat: Boolean = false, val swipeHome: Boolean = false,
        val settingsPage: String? = null, val view: String = "",
        val viewsSheet: Boolean = false, val plusMenu: Boolean = false, val arc: Boolean = false,
        val avatarMenu: Boolean = false, val search: Boolean = false, val gallery: Boolean = false,
        val room: Boolean = false, val themePicker: Boolean = false,
    )

    enum class Action { TO_CHAT, OWN }

    /** The id of what is in front (what Back leaves). */
    fun front(s: Shell): String = when {
        s.onboarding -> "setup"
        s.inCall -> "call"
        s.themePicker -> "themes"
        s.settingsPage != null -> "page:" + s.settingsPage.ifBlank { "settings" }
        s.room -> "meeting-room"
        s.gallery -> "gallery"
        s.search -> "search"
        s.avatarMenu -> "profile-menu"
        s.arc -> "talk-arc"
        s.plusMenu -> "plus-menu"
        s.viewsSheet -> "views-sheet"
        s.view.isNotEmpty() -> "view:" + s.view
        s.inProjectChat -> "project-chat"
        s.tab == ChatNav.PROJECTS -> "projects"
        s.tab == ChatNav.HOME || s.swipeHome -> "home"
        else -> CHAT
    }

    /** Fronts that keep their own Back. Everything else goes to the chat. */
    val OWN_BACK = setOf(CHAT, "setup", "call")

    fun action(s: Shell): Action = if (front(s) in OWN_BACK) Action.OWN else Action.TO_CHAT

    /** Views.special screens that are their own activity (ChatScreen.openView opens exactly these; BackRuleTest checks). */
    val SPECIAL_ACTIVITY = mapOf(
        "phone" to "com.blaze.assistant.blazeos.BlazeOsActivity",
        "browser" to "com.blaze.assistant.browser.BrowserActivity",
        "phonebrowser" to "com.blaze.assistant.browser.phone.PhoneBrowserActivity",
        "terminal" to "com.blaze.assistant.terminal.BlazeTerminalActivity",
        "music" to "com.blaze.assistant.mediaapps.MusicActivity",
        "video" to "com.blaze.assistant.mediaapps.VideoActivity",
    )
    /** Views.special screens drawn by the chat shell itself. */
    val SPECIAL_SHELL = setOf("gallery", "home", "projects", "design")

    /** What the shell shows after opening [v] from [base] (null = [v] is its own activity, see [SPECIAL_ACTIVITY]). */
    fun shellFor(v: BlazeView, base: Shell = Shell()): Shell? = when (v.special) {
        "gallery" -> base.copy(gallery = true)
        "home" -> base.copy(tab = ChatNav.HOME)
        "projects" -> base.copy(tab = ChatNav.PROJECTS)
        "design" -> base.copy(tab = ChatNav.CHAT, view = "design")
        in SPECIAL_ACTIVITY.keys -> null
        else -> base.copy(settingsPage = v.page ?: "")
    }

    /** Activities that return a result to the chat that opened them (Back cancels; the chat is under them anyway). */
    val EXEMPT_ACTIVITIES = mapOf(
        "com.blaze.assistant.device.PermissionActivity" to "transparent permission prompt; Back cancels it and returns to the screen that asked",
        "com.blaze.assistant.ui.CameraActivity" to "Blaze Camera for a photo the chat asked for; Back cancels the capture and returns to the chat",
    )

    /** Does Back in this activity go straight to the chat? (MainActivity: only when the shell's front isn't the chat.) */
    fun activityGoesToChat(className: String): Boolean =
        className.startsWith("com.blaze.assistant.") && className != MAIN_ACTIVITY && className !in EXEMPT_ACTIVITIES

    /** An activity's last step before Back leaves it for the chat. Without it: finish and bring the chat forward. */
    interface ToChat { fun backToChat() }

    /** The main window: whether Back belongs to the shell now, and how to go to the chat. */
    interface Shelled { fun backTakesToChat(): Boolean; fun backToChat() }

    // ------------------------------------------------------------------ runtime (main window)
    /** What the chat shell has in front, read at the moment Back is pressed (set by ChatScreen). */
    @Volatile var shellNow: () -> Shell = { Shell() }
    @Volatile var toChat: (() -> Unit)? = null

    /** MainActivity: Back goes to the chat now (the shell has something in front and knows how to close it). */
    fun shellTakesBack(locked: Boolean): Boolean = !locked && toChat != null && action(shellNow()) == Action.TO_CHAT

    // ------------------------------------------------------------------ the interceptor
    /** Back keys pressed down in a window that takes them (so the matching up is taken too). */
    private val armed = java.util.WeakHashMap<Activity, Boolean>()

    /** Pure decision for one key event: null = let it through; true = consume (and act when [act] says so). */
    data class KeyStep(val consume: Boolean, val act: Boolean)
    fun keyStep(keyCode: Int, down: Boolean, up: Boolean, canceled: Boolean, repeat: Int, takes: Boolean, wasArmed: Boolean): KeyStep = when {
        keyCode != KeyEvent.KEYCODE_BACK -> KeyStep(false, false)
        down -> KeyStep(takes, false)
        up -> KeyStep(wasArmed, wasArmed && !canceled && repeat == 0)
        else -> KeyStep(wasArmed, false)
    }

    private fun takesBack(a: Activity): Boolean = when (a) {
        is Shelled -> a.backTakesToChat()
        else -> activityGoesToChat(a.javaClass.name)
    }

    private fun goToChat(a: Activity) {
        when (a) {
            is Shelled -> a.backToChat()
            is ToChat -> a.backToChat()
            else -> com.blaze.assistant.mediaapps.BackToChat.go(a)
        }
    }

    /** True when [e] was taken (Back on its way to the chat). */
    fun onKey(a: Activity, e: KeyEvent, takes: (Activity) -> Boolean = ::takesBack, go: (Activity) -> Unit = ::goToChat): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_BACK) return false
        val down = e.action == KeyEvent.ACTION_DOWN; val up = e.action == KeyEvent.ACTION_UP
        val st = keyStep(e.keyCode, down, up, e.isCanceled, e.repeatCount, if (down) takes(a) else false, armed[a] == true)
        if (down) { if (st.consume) armed[a] = true else armed.remove(a) }
        if (up) armed.remove(a)
        if (st.act) try { go(a) } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BackRule.go", t) }
        return st.consume
    }

    /** Wraps [a]'s window so Back reaches [onKey] before anything else in it (Compose BackHandlers, onBackPressed). */
    fun wrap(a: Activity) {
        if (a !is Shelled && !activityGoesToChat(a.javaClass.name)) return
        wrapWith(a, ::takesBack, ::goToChat)
    }

    /** [wrap] with the decision and the action passed in (BackRuleWindowTest drives real key events through it). */
    fun wrapWith(a: Activity, takes: (Activity) -> Boolean, go: (Activity) -> Unit): Boolean {
        val w = a.window ?: return false
        val orig = w.callback ?: return false
        if (Proxy.isProxyClass(orig.javaClass)) return false
        w.callback = Proxy.newProxyInstance(Window.Callback::class.java.classLoader, arrayOf(Window.Callback::class.java)) { _, m, args ->
            if (m.name == "dispatchKeyEvent" && args != null && args.size == 1 && args[0] is KeyEvent && onKey(a, args[0] as KeyEvent, takes, go)) return@newProxyInstance true
            try { if (args == null) m.invoke(orig) else m.invoke(orig, *args) } catch (t: InvocationTargetException) { throw t.targetException ?: t }
        } as Window.Callback
        return true
    }

    /** From BlazeApp.onCreate: every Blaze activity gets the rule when it is created. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { try { wrap(a) } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BackRule.wrap", t) } }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { armed.remove(a) }
        })
    }
}
