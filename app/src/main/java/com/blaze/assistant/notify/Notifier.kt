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
    /** 0.45.19: the one alerting channel of the Blaze thread (was "blaze_reply"). */
    private const val CH_REPLY = BlazeThread.CHANNEL
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
            // 0.45.19: every Blaze message (replies, results, check-ins, heads-ups) on ONE alerting channel; the old
            // message channels (one of them DEFAULT = no heads-up, and a channel can't be raised once made) are deleted.
            nm.createNotificationChannel(NotificationChannel(BlazeThread.CHANNEL, BlazeThread.CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = BlazeThread.CHANNEL_DESC
                setShowBadge(true)
                // sound and vibration: the channel defaults, i.e. what the phone's own settings say. No DND bypass.
            })
            try {
                val existing = nm.notificationChannels.map { it.id }
                BlazeThread.toDelete(existing).forEach { nm.deleteNotificationChannel(it) }
            } catch (_: Throwable) { }
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

    /**
     * Agent work in progress. Ack-and-background (HQ 22 Oct 20:36): Blaze no longer posts a "thinking / working"
     * notification; the ack and the Done / failed message arrive as text-style notifications ([reply]) instead.
     * Kept as a no-op so old callers compile; [WORKING_NOTE] guards it (AckTest).
     */
    @Suppress("UNUSED_PARAMETER")
    fun working(step: String) { if (WORKING_NOTE) { stBusy = true; stStep = step; refresh() } }

    /** False: no working notification is ever shown for agent work. */
    const val WORKING_NOTE = false

    /** Agent work finished or stopped (an update download, if any, keeps showing). */
    fun doneWorking() { stBusy = false; stStep = ""; stNeeds = ""; refresh() }

    /** "Needs you: …" in the status line (a question, a confirm, a secure form). Blank clears it. */
    fun needs(what: String) { stNeeds = what; refresh() }

    /** Update progress: [pct] 0..100, -1 indeterminate, null = no update running. */
    fun update(pct: Int?, label: String = "") { stUpdatePct = pct; stUpdateLabel = label; refresh() }

    /** The app came to the front (true) or went to the background (false). */
    fun visible(v: Boolean) { appVisible = v; if (v) clearThread(); refresh() }

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

    /** The last few lines Blaze sent while you were away (one conversation thread in the shade), cleared on open. */
    private val thread = ArrayDeque<BlazeThread.Msg>()

    /** Called when the user opens Blaze or the notification is dismissed: the next text starts a fresh thread. */
    fun clearThread() = synchronized(thread) { thread.clear() }

    /** Blaze as a Person: its name, a bot, and the app icon as its avatar (not a letter "B"). */
    @Volatile private var blazePerson: androidx.core.app.Person? = null
    @Volatile private var avatar: androidx.core.graphics.drawable.IconCompat? = null
    @Volatile private var shortcutPushed = false

    private fun avatarIcon(): androidx.core.graphics.drawable.IconCompat? {
        avatar?.let { return it }
        val icon = try {
            val d = ContextCompat.getDrawable(app, R.mipmap.ic_launcher) ?: return null
            val size = 192
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            // adaptive icons draw past their bounds (the safe zone is the middle 66 %): round crop like a contact photo
            val path = android.graphics.Path().apply { addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW) }
            c.clipPath(path)
            val pad = if (Build.VERSION.SDK_INT >= 26 && d is android.graphics.drawable.AdaptiveIconDrawable) -size / 6 else 0
            d.setBounds(pad, pad, size - pad, size - pad)
            d.draw(c)
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)
        } catch (_: Throwable) { null }
        avatar = icon
        return icon
    }

    private fun blaze(): androidx.core.app.Person = blazePerson ?: androidx.core.app.Person.Builder()
        .setName(BlazeThread.PERSON_NAME).setKey(BlazeThread.PERSON_KEY).setBot(true).setImportant(true)
        .apply { avatarIcon()?.let { setIcon(it) } }.build().also { blazePerson = it }

    /** The long-lived conversation shortcut the thread hangs on (Android 11+ puts it in the Conversations section). */
    private fun ensureShortcut() {
        if (shortcutPushed) return
        try {
            val sc = androidx.core.content.pm.ShortcutInfoCompat.Builder(app, BlazeThread.SHORTCUT)
                .setShortLabel(BlazeThread.PERSON_NAME).setLongLabel("Chat with Blaze")
                .setLongLived(true).setPerson(blaze())
                .setIcon(avatarIcon() ?: androidx.core.graphics.drawable.IconCompat.createWithResource(app, R.mipmap.ic_launcher))
                .setIntent(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                .setCategories(setOf("com.blaze.assistant.category.CONVERSATION"))
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(app, sc)
            shortcutPushed = true
        } catch (_: Throwable) { }
    }

    /**
     * 0.45.19: THE builder for every Blaze message (ack, reply, Done ✓, honest failure, check-in, heads-up, question,
     * follow-up, call offer). One MessagingStyle conversation with one Person "Blaze" (app-icon avatar), one shortcut,
     * one notification id, quick replies that fit ([QuickReplies]) plus an inline Reply, on the alerting channel.
     * [extra] = buttons beyond Reply (a suggestion, Going / No, Call me). [tap] = what tapping opens (default: the chat).
     */
    fun post(raw: String, kind: BlazeThread.Kind, chatId: String? = null, context: String? = null, urgent: Boolean = false,
             extra: List<NotificationCompat.Action> = emptyList(), tap: PendingIntent? = null, reply: Boolean = true) {
        if (!::app.isInitialized) return
        val text = com.blaze.assistant.ui.Md.plain(raw)
        if (text.isBlank() || !allowed()) return
        val lines = synchronized(thread) { BlazeThread.push(thread, BlazeThread.Msg(System.currentTimeMillis(), text, kind)) }
        val chips = QuickReplies.forMessage(text, kind)
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to Blaze")
            .apply { if (chips.isNotEmpty()) setChoices(Array<CharSequence>(chips.size) { chips[it] }) }.build()
        val replyA = NotificationCompat.Action.Builder(0, "Reply", replyIntent(7101, chatId, context ?: if (chatId == null) text else null))
            .addRemoteInput(input)
            // Android's generated replies offered "Weird" / "Great!" under a result: only Blaze's own choices.
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build()
        ensureShortcut()
        val me = androidx.core.app.Person.Builder().setName("You").setKey("me").build()
        val b = NotificationCompat.Builder(app, CH_REPLY)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setColor(ACCENT)
            .setContentTitle(BlazeThread.title(kind, urgent))
            .setContentText(text)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(tap ?: openChat(chatId))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShortcutId(BlazeThread.SHORTCUT)
        if (reply) b.addAction(replyA)
        extra.take(2).forEach { b.addAction(it) }
        try {
            val style = NotificationCompat.MessagingStyle(me)
            lines.forEach { m -> style.addMessage(m.text, m.at, blaze()) }
            b.setStyle(style)
        } catch (_: Throwable) {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        try { NotificationManagerCompat.from(app).notify(BlazeThread.NOTIF_ID, b.build()) } catch (_: SecurityException) { } catch (_: Throwable) { }
    }

    /**
     * Blaze's message (its ack, its reply, its Done ✓ or an honest failure) as a text from Blaze: MessagingStyle,
     * with an inline Reply box. Ack-and-background (HQ 22 Oct 20:36). 0.45.19: through [post].
     */
    fun reply(raw: String, isError: Boolean, chatId: String? = null) {
        val t = raw.trim()
        val kind = when {
            isError -> BlazeThread.Kind.FAILED
            t.startsWith(com.blaze.assistant.agent.Ack.DONE) -> BlazeThread.Kind.RESULT
            t.startsWith("Got it, ") -> BlazeThread.Kind.ACK
            else -> BlazeThread.Kind.REPLY
        }
        post(raw, kind, chatId)
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

    /** Blaze speaking up on its own, with a one-tap suggestion and an inline reply. 0.45.19: one Blaze thread ([post]). */
    fun proactive(text0: String, suggest: String, urgent: Boolean, kind: BlazeThread.Kind = BlazeThread.Kind.HEADS_UP) {
        // v0.28.1: notification text never shows markdown symbols.
        val text = com.blaze.assistant.ui.Md.plain(text0)
        // Kept in the Heads-ups chat (even when notifications are off) so a reply has the context (v0.8.0).
        val chatId = try { Agent.recordHeadsUp(text) } catch (_: Exception) { null }
        if (!::app.isInitialized || !allowed()) return
        val extra: List<NotificationCompat.Action> = if (suggest.isBlank()) emptyList() else {
            val pi = PendingIntent.getBroadcast(app, ("do" + suggest).hashCode(), Intent(app, ActionReceiver::class.java).setAction(ActionReceiver.DO)
                .putExtra("prompt", suggest).putExtra(ActionReceiver.EXTRA_CHAT, chatId).putExtra(ActionReceiver.EXTRA_CONTEXT, text),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            listOf(NotificationCompat.Action.Builder(0, suggest.take(28), pi).build())
        }
        post(text, kind, chatId, context = text, urgent = urgent, extra = extra)
    }

    private const val ID_QUESTION = 7
    fun question(q0: String) {
        val q = com.blaze.assistant.ui.Md.plain(q0)
        needs(q)
        // 0.45.19: a question from Blaze is a text in the Blaze thread too (the answer goes to the chat that asked).
        // No inline Reply: the turn that asked is waiting on the answer in the app (tap opens it, as before).
        post(q, BlazeThread.Kind.QUESTION, tap = openApp(), reply = false)
    }
    fun clearQuestion() {
        try {
            needs("")
            NotificationManagerCompat.from(app).cancel(ID_QUESTION)
            // 0.45.19: the question was a line in the Blaze thread: once answered, it leaves the shade
            val wasLast = synchronized(thread) { (thread.lastOrNull()?.kind == BlazeThread.Kind.QUESTION).also { if (it) thread.removeLast() } }
            if (wasLast) NotificationManagerCompat.from(app).cancel(BlazeThread.NOTIF_ID)
        } catch (_: Exception) { }
    }

    fun clearReplies() = try { NotificationManagerCompat.from(app).cancel(BlazeThread.NOTIF_ID); NotificationManagerCompat.from(app).cancel(ID_PROACTIVE) } catch (_: Throwable) { }

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
    fun actionRequired(title: String, site: String, text0: String? = null): Boolean {
        if (::app.isInitialized) needs("sign in to ${site.take(40)}")
        if (!canPost()) return false
        val open = PendingIntent.getActivity(app, ID_ACTION, com.blaze.assistant.IntentKey.sign(app, Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(EXTRA_OPEN_FORM, true)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = text0 ?: "Blaze is waiting for you on $site. Tap to fill it in securely."
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
                Notifier.clearReplies()
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
