package com.blaze.assistant.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import com.blaze.assistant.MainActivity
import com.blaze.assistant.R
import com.blaze.assistant.agent.Agent

/** System notifications: one combined ongoing status line with Stop (v0.24.4), and replies you can answer inline. */
object Notifier {
    private const val CH_WORK = "blaze_work"
    private const val CH_REPLY = "blaze_reply"
    /** v0.20.0: the background browser needs something only the user has (a sign-in, a code). */
    const val CH_ACTION = "blaze_action"
    private const val ID_ACTION = 11
    const val EXTRA_OPEN_FORM = "open_form"
    /** v0.28.3: tapping a reply from a project chat opens that chat inside Projects (never in main Chat). */
    const val EXTRA_OPEN_CHAT = "open_chat"
    private const val ID_WORK = 1
    private const val ID_REPLY = 2
    private const val ID_WAKE = 4
    private const val ID_CONFIRM = 5
    const val KEY_REPLY = "reply_text"
    private const val ACCENT = 0xFF1F4BD8.toInt()

    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // v0.24.4: update downloads ride in the one status line (with Stop).
        try {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main).launch {
                com.blaze.assistant.update.Updater.state.collect { st -> updateFrom(st) }
            }
        } catch (_: Throwable) { }
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_WORK, "Blaze status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "What Blaze is doing right now (thinking, browsing, downloading an update, waiting for you), with a Stop button"
                setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_REPLY, "Blaze replies", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Blaze's answer when it finishes while you're in another app"
            })
            nm.createNotificationChannel(NotificationChannel(CH_ACTION, "Action required", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When Blaze's background browser needs a sign-in, a code or another detail only you have"
            })
        }
    }

    private fun allowed() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** v0.28.3: opens Blaze at [chatId] when it's a project chat (MainActivity → Agent.openFromLink → Projects). */
    private fun openChat(chatId: String?): PendingIntent {
        if (chatId.isNullOrBlank() || com.blaze.assistant.agent.Projects.of(chatId) == null) return openApp()
        return PendingIntent.getActivity(app, 7102, com.blaze.assistant.IntentKey.sign(app, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_CHAT, chatId)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The Reply button: carries where the answer should go (the chat, or the heads-up text itself). */
    private fun replyIntent(code: Int, chatId: String?, context: String?): PendingIntent = PendingIntent.getBroadcast(
        app, code, Intent(app, ActionReceiver::class.java).setAction(ActionReceiver.REPLY)
            .putExtra(ActionReceiver.EXTRA_CHAT, chatId.orEmpty()).putExtra(ActionReceiver.EXTRA_CONTEXT, context.orEmpty()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    private fun broadcast(action: String, mutable: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        app, action.hashCode(), Intent(app, ActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
    )

    // ---------- v0.24.4: one combined ongoing status notification ----------
    @Volatile private var stBusy = false
    @Volatile private var stStep = ""
    @Volatile private var stNeeds = ""
    @Volatile private var stUpdatePct: Int? = null
    @Volatile private var stUpdateLabel = ""
    /** True while Blaze is on screen: agent work isn't shown (the chat shows it), downloads and needs still are. */
    @Volatile var appVisible = false

    /** The current input of the status notification (tests, diagnostics). */
    fun statusInput(): StatusNote.Input = StatusNote.Input(stBusy && !appVisible, stStep, if (appVisible) "" else stNeeds, stUpdatePct, stUpdateLabel)

    private fun refresh() {
        if (!::app.isInitialized) return
        val line = StatusNote.line(statusInput())
        if (line == null || !allowed()) { try { NotificationManagerCompat.from(app).cancel(ID_WORK) } catch (_: Exception) { }; return }
        val b = NotificationCompat.Builder(app, CH_WORK)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setColor(ACCENT)
            .setContentTitle(line.title)
            .setContentText(line.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        line.progress?.let { p -> if (p < 0) b.setProgress(0, 0, true) else b.setProgress(100, p, false) }
        if (line.stop) b.addAction(0, "Stop", broadcast(ActionReceiver.STOP))
        try { NotificationManagerCompat.from(app).notify(ID_WORK, b.build()) } catch (_: SecurityException) { }
    }

    /** Agent work in progress. [step] is the latest step, e.g. "Opening YouTube". */
    fun working(step: String) { stBusy = true; stStep = step; refresh() }

    /** Agent work finished or stopped (an update download, if any, keeps showing). */
    fun doneWorking() { stBusy = false; stStep = ""; stNeeds = ""; refresh() }

    /** "Needs you: …" in the status line (a question, a confirm, a secure form). Blank clears it. */
    fun needs(what: String) { stNeeds = what; refresh() }

    /** Update progress: [pct] 0..100, -1 indeterminate, null = no update running. */
    fun update(pct: Int?, label: String = "") { stUpdatePct = pct; stUpdateLabel = label; refresh() }

    /** The app came to the front (true) or went to the background (false). */
    fun visible(v: Boolean) { appVisible = v; refresh() }

    /** Stop: cancels agent work and any update download, then clears the line. */
    fun stopEverything() {
        try { Agent.stopAll() } catch (_: Exception) { }
        try { com.blaze.assistant.update.Updater.stop() } catch (_: Exception) { }
        stBusy = false; stStep = ""; stNeeds = ""; stUpdatePct = null; stUpdateLabel = ""
        refresh()
    }

    /** Maps the updater's state onto the status line. */
    fun updateFrom(st: com.blaze.assistant.update.Updater.State) {
        when (st) {
            is com.blaze.assistant.update.Updater.State.Checking -> update(-1, "Checking for an update…")
            is com.blaze.assistant.update.Updater.State.Downloading -> update(st.pct, "Blaze v${st.version}")
            is com.blaze.assistant.update.Updater.State.Installing -> update(-1, "Installing Blaze v${st.version}…")
            else -> if (stUpdatePct != null) update(null)
        }
    }

    /** Test hook: reset the status state. */
    internal fun resetStatus() { stBusy = false; stStep = ""; stNeeds = ""; stUpdatePct = null; stUpdateLabel = ""; appVisible = false }

    /** Blaze's reply, with an inline Reply box. */
    fun reply(raw: String, isError: Boolean, chatId: String? = null) {
        val text = com.blaze.assistant.ui.Md.plain(raw)
        if (!allowed()) return
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to Blaze").build()
        val replyAction = NotificationCompat.Action.Builder(0, "Reply", replyIntent(7101, chatId, if (chatId == null) text else null))
            .addRemoteInput(input).setAllowGeneratedReplies(true).build()
        val n = NotificationCompat.Builder(app, CH_REPLY)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setColor(ACCENT)
            .setContentTitle(if (isError) "Blaze hit a problem" else "Blaze")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openChat(chatId))
            .addAction(replyAction)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(app).notify(ID_REPLY, n) } catch (_: SecurityException) { }
    }

    /** Fallback when Android blocks opening Blaze from the background: a full-screen / heads-up prompt. */
    fun wakeHeard() {
        if (!allowed()) return
        val pi = PendingIntent.getActivity(app, 4,
            com.blaze.assistant.IntentKey.sign(app, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_LISTEN, true)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, CH_REPLY)
            .setSmallIcon(R.drawable.ic_stat_blaze).setColor(ACCENT)
            .setContentTitle("I'm listening")
            .setContentText("Tap to talk to Blaze")
            .setContentIntent(pi).setFullScreenIntent(pi, true)
            .setAutoCancel(true).setTimeoutAfter(15000)
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        try { NotificationManagerCompat.from(app).notify(ID_WAKE, n) } catch (_: SecurityException) { }
    }

    private const val ID_WAKE_BLOCKED = 6
    /** Android silences a background mic; one tap brings Blaze forward and the wake word works again. */
    fun wakeBlocked() {
        if (!allowed()) return
        val pi = PendingIntent.getActivity(app, 6, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("restart_wake", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, CH_REPLY).setSmallIcon(R.drawable.ic_stat_blaze).setColor(ACCENT)
            .setContentTitle("\u201cHey Blaze\u201d is paused by Android")
            .setContentText("Tap once to turn the wake word back on.")
            .setAutoCancel(true).setContentIntent(pi).setOnlyAlertOnce(true).build()
        try { NotificationManagerCompat.from(app).notify(ID_WAKE_BLOCKED, n) } catch (_: SecurityException) { }
    }
    fun clearWakeBlocked() = try { NotificationManagerCompat.from(app).cancel(ID_WAKE_BLOCKED) } catch (_: Exception) { }

    private const val ID_PROACTIVE = 8
    private const val CH_PROACTIVE = "blaze_proactive"

    /** Blaze speaking up on its own, with a one-tap suggestion and an inline reply. */
    fun proactive(text0: String, suggest: String, urgent: Boolean) {
        // v0.28.1: notification text never shows markdown symbols.
        val text = com.blaze.assistant.ui.Md.plain(text0)
        // Kept in the Heads-ups chat (even when notifications are off) so a reply has the context (v0.8.0).
        val chatId = try { Agent.recordHeadsUp(text) } catch (_: Exception) { null }
        if (!allowed()) return
        if (Build.VERSION.SDK_INT >= 26) app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_PROACTIVE, "Blaze heads-ups", NotificationManager.IMPORTANCE_HIGH).apply { description = "When Blaze notices something worth telling you" })
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to Blaze").build()
        val b = NotificationCompat.Builder(app, CH_PROACTIVE).setSmallIcon(R.drawable.ic_stat_blaze).setColor(ACCENT)
            .setContentTitle(if (urgent) "Blaze · important" else "Blaze").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(openApp())
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .addAction(NotificationCompat.Action.Builder(0, "Reply", replyIntent(7108, chatId, text)).addRemoteInput(input).build())
        if (suggest.isNotBlank()) {
            val pi = PendingIntent.getBroadcast(app, ("do" + suggest).hashCode(), Intent(app, ActionReceiver::class.java).setAction(ActionReceiver.DO)
                .putExtra("prompt", suggest).putExtra(ActionReceiver.EXTRA_CHAT, chatId).putExtra(ActionReceiver.EXTRA_CONTEXT, text),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, suggest.take(28), pi)
        }
        try { NotificationManagerCompat.from(app).notify(ID_PROACTIVE, b.build()) } catch (_: SecurityException) { }
    }

    private const val ID_QUESTION = 7
    fun question(q0: String) {
        val q = com.blaze.assistant.ui.Md.plain(q0)
        needs(q)
        if (!allowed()) return
        val n = NotificationCompat.Builder(app, CH_REPLY).setSmallIcon(R.drawable.ic_stat_blaze).setColor(ACCENT)
            .setContentTitle("Blaze needs an answer").setContentText(q)
            .setStyle(NotificationCompat.BigTextStyle().bigText(q))
            .setAutoCancel(true).setContentIntent(openApp()).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { NotificationManagerCompat.from(app).notify(ID_QUESTION, n) } catch (_: SecurityException) { }
    }
    fun clearQuestion() { try { needs(""); NotificationManagerCompat.from(app).cancel(ID_QUESTION) } catch (_: Exception) { } }

    fun clearReplies() = NotificationManagerCompat.from(app).cancel(ID_REPLY)

    /** Approval request with Allow / Don't buttons. */
    fun confirm(title: String, detail0: String, action: String) {
        val detail = com.blaze.assistant.ui.Md.plain(detail0)
        needs(title)
        if (!allowed()) return
        val n = NotificationCompat.Builder(app, CH_REPLY)
            .setSmallIcon(R.drawable.ic_stat_blaze).setColor(ACCENT)
            .setContentTitle(title).setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp()).setAutoCancel(true)
            .addAction(0, action, broadcast(ActionReceiver.ALLOW))
            .addAction(0, "Don't", broadcast(ActionReceiver.DENY))
            .build()
        try { NotificationManagerCompat.from(app).notify(ID_CONFIRM, n) } catch (_: SecurityException) { }
    }

    fun clearConfirm() { needs(""); NotificationManagerCompat.from(app).cancel(ID_CONFIRM) }

    /** True when Blaze may post notifications (always below Android 13; a granted permission on 13+). */
    fun canPost(): Boolean = ::app.isInitialized && allowed()

    /**
     * "Action required": tapping it opens the chat at the secure form. Never carries a value, only what's
     * needed ("Sign in to github.com"). Returns false when notifications aren't allowed (Android 13+ without
     * the permission); the form still waits in the chat.
     */
    fun actionRequired(title: String, site: String): Boolean {
        if (::app.isInitialized) needs("sign in to ${site.take(40)}")
        if (!canPost()) return false
        val open = PendingIntent.getActivity(app, ID_ACTION, com.blaze.assistant.IntentKey.sign(app, Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(EXTRA_OPEN_FORM, true)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = "Blaze is waiting for you on $site. Tap to fill it in securely."
        val n = NotificationCompat.Builder(app, CH_ACTION).setSmallIcon(R.drawable.ic_stat_blaze).setColor(ACCENT)
            .setContentTitle("Action required: ${title.take(60)}").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open).setAutoCancel(true).build()
        return try { NotificationManagerCompat.from(app).notify(ID_ACTION, n); true } catch (_: SecurityException) { false }
    }

    fun clearAction() { try { needs(""); NotificationManagerCompat.from(app).cancel(ID_ACTION) } catch (_: Exception) { } }
}

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val STOP = "com.blaze.assistant.STOP"
        const val REPLY = "com.blaze.assistant.REPLY"
        const val ALLOW = "com.blaze.assistant.ALLOW"
        const val DENY = "com.blaze.assistant.DENY"
        const val DO = "com.blaze.assistant.DO"
        /** v0.25.0: the ongoing Magic Hand notification's "Stop Magic Hand" button. */
        const val STOP_HAND = "com.blaze.assistant.STOP_HAND"
        const val EXTRA_CHAT = "reply_chat"
        const val EXTRA_CONTEXT = "reply_context"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            STOP -> Notifier.stopEverything()
            STOP_HAND -> com.blaze.assistant.phone.MagicHand.stop()
            DO -> intent.getStringExtra("prompt")?.let { p ->
                androidx.core.app.NotificationManagerCompat.from(ctx).cancel(8)
                Agent.doFromHeadsUp(intent.getStringExtra(EXTRA_CHAT), intent.getStringExtra(EXTRA_CONTEXT), p)
                Notifier.working("Working on: " + p.take(60))
            }
            ALLOW -> { Agent.answerConfirm(true); Notifier.clearConfirm() }
            DENY -> { Agent.answerConfirm(false); Notifier.clearConfirm() }
            REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                Notifier.clearReplies()
                androidx.core.app.NotificationManagerCompat.from(ctx).cancel(8)
                if (!text.isNullOrBlank()) {
                    Agent.replyFromNotification(intent.getStringExtra(EXTRA_CHAT), intent.getStringExtra(EXTRA_CONTEXT), text)
                    Notifier.working("Thinking about: " + text.take(60))
                }
            }
        }
    }
}
