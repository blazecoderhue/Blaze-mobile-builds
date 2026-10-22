package com.blaze.assistant.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.blaze.assistant.agent.Agent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notification brain: with notification access on, Blaze keeps the last ~150 notifications
 * in memory only (never written to disk) so it can answer "what did I miss?" and reply to chats
 * through the notification's own reply action.
 */
class NotificationBrain : NotificationListenerService() {

    data class Item(val key: String, val time: Long, val app: String, val title: String, val text: String,
                    val reply: Notification.Action?)

    companion object {
        @Volatile var instance: NotificationBrain? = null
        private val items = ArrayDeque<Item>()
        private val otp = Regex("\\b(code|otp|verification|passcode|pin)\\b", RegexOption.IGNORE_CASE)

        fun enabled(ctx: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ComponentName(ctx, NotificationBrain::class.java).flattenToString())
        }

        fun settingsIntent() = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val toolNames = setOf("recent_notifications", "reply_to_notification")

        fun tools(): List<JSONObject> = listOf(
            JSONObject().put("type", "function").put("function", JSONObject().put("name", "recent_notifications")
                .put("description", "List notifications the user got recently (messages, apps). Use for 'what did I miss', 'any messages from X'. Items with a reply action can be answered with reply_to_notification.")
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("app", JSONObject().put("type", "string").put("description", "Optional app filter, e.g. WhatsApp"))
                    .put("hours", JSONObject().put("type", "number").put("description", "How far back, default 6"))))),
            JSONObject().put("type", "function").put("function", JSONObject().put("name", "reply_to_notification")
                .put("description", "Reply to a chat notification (WhatsApp, Messages, Telegram…) using its built-in reply box. The user approves first.")
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("number", JSONObject().put("type", "integer").put("description", "The [n] from recent_notifications"))
                    .put("text", JSONObject().put("type", "string").put("description", "Reply text")))
                    .put("required", JSONArray(listOf("number", "text"))))),
        )

        private var lastList: List<Item> = emptyList()

        /** Notifications posted after [since] (for proactive checks). */
        fun since(since: Long): List<Item> = synchronized(items) { items.filter { it.time > since } }

        fun list(app: String?, hours: Double): String {
            if (instance == null) return "Notification access is off. Ask the user to turn it on in Blaze → Settings → Phone control."
            val since = System.currentTimeMillis() - (hours * 3_600_000).toLong()
            val l = synchronized(items) { items.toList() }.asReversed()
                .filter { it.time >= since && (app.isNullOrBlank() || it.app.contains(app, true)) }.take(40)
            lastList = l
            if (l.isEmpty()) return "No notifications in that window."
            val f = SimpleDateFormat("HH:mm", Locale.getDefault())
            return l.mapIndexed { i, it -> "[$i] ${f.format(Date(it.time))} ${it.app} · ${it.title}: ${it.text.take(160)}${if (it.reply != null) " (can reply)" else ""}" }.joinToString("\n")
        }

        /** Latest live chat notification from [name] (optionally in [app]) that has a reply box. */
        fun replyable(name: String, app: String?): Item? = synchronized(items) { items.toList() }.asReversed().firstOrNull {
            it.reply != null && it.title.contains(name, true) && (app.isNullOrBlank() || it.app.contains(app, true)) }

        /** Reply through a notification's own reply box (no confirm; the caller confirms). */
        fun sendReply(ctx: Context, it: Item, text: String): Boolean {
            val action = it.reply ?: return false
            val ri = action.remoteInputs?.firstOrNull() ?: return false
            val intent = Intent(); val b = Bundle(); b.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, b)
            return try { action.actionIntent.send(ctx, 0, intent); true } catch (_: PendingIntent.CanceledException) { false }
        }

        suspend fun reply(ctx: Context, n: Int, text: String): String {
            val it = lastList.getOrNull(n) ?: return "Call recent_notifications first and use its number."
            val action = it.reply ?: return "That notification has no reply box."
            if (!Agent.confirm("Reply to ${it.title}?", "\u201c$text\u201d (in ${it.app})", "Send", always = true)) return "The user declined. Nothing was sent."
            val ri = action.remoteInputs?.firstOrNull() ?: return "That notification has no reply box."
            val intent = Intent(); val b = Bundle(); b.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, b)
            return try { action.actionIntent.send(ctx, 0, intent); "Replied to ${it.title} in ${it.app}." }
            catch (e: PendingIntent.CanceledException) { "That notification is gone, so I couldn't reply." }
        }
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }
    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val raw = text
        if (otp.containsMatchIn(title + " " + text) || Sensitive.isSensitive(text, title)) text = Sensitive.HIDDEN
        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        // v0.33.0 Scam Shield: checked on the raw text, before codes are hidden.
        try { ScamShield.check(this, sbn.packageName, app, title, raw) } catch (_: Throwable) { }
        // v0.33.0 Money Radar (opt-in): receipts, bills, bundles parsed from money / bundle SMS; only the parsed fields are kept.
        try { MoneyRadar.onSms(this, sbn.packageName, title, raw, sbn.postTime) } catch (_: Throwable) { }
        // Notification replies (v0.19.0): chat apps keep a per-conversation log (codes and bank messages never stored).
        try { Conversations.onPosted(this, sbn.packageName, app, n) } catch (_: Throwable) { }
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
        synchronized(items) {
            items.removeAll { it.key == sbn.key && it.text == text }
            items.addLast(Item(sbn.key, sbn.postTime, app, title, text, reply))
            while (items.size > 150) items.removeFirst()
        }
        try { com.blaze.assistant.proactive.Triggers.onNotification(this, app, title, text) } catch (_: Exception) { }
    }
}
