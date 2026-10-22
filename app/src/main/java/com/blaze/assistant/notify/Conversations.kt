package com.blaze.assistant.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Mind
import com.blaze.assistant.agent.MindLogs
import com.blaze.assistant.agent.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notification replies (v0.19.0). With notification access on, every chat-app notification (WhatsApp, SMS,
 * Telegram, Messenger, Signal…) is read with its MessagingStyle history: who sent what, in which conversation.
 *
 *  - Each conversation keeps a rolling log in the journal (kind "notif", conv "notif:<app>:<name>"), so Blaze has
 *    more than the last few lines a notification carries. Senders are linked to people memories when Blaze reads it.
 *  - Blaze drafts a reply and sends it through the notification's own reply box (RemoteInput), without opening the
 *    app. Sending always shows a confirm card, unless the user switched on auto-reply for that conversation
 *    (Settings › Notification replies).
 *  - Codes and bank messages ([Sensitive]) are never stored or shown.
 *
 * Tools: notif_conversations, notif_read, notif_reply. The pure parts (keys, dedupe, rendering) are unit-tested.
 */
object Conversations {
    data class Msg(val at: Long, val sender: String, val text: String, val self: Boolean = false) {
        val sig get() = "${at / 1000}|${sender.lowercase()}|${text.trim()}"
    }

    class Conv(val key: String, val app: String, val pkg: String) {
        @Volatile var title: String = ""
        @Volatile var group: Boolean = false
        @Volatile var reply: Notification.Action? = null
        @Volatile var updated: Long = 0
        val msgs = ArrayDeque<Msg>()
        val seen = LinkedHashSet<String>()
    }

    const val KEEP_IN_MEMORY = 40
    const val THIN = 4
    private const val PREF_AUTO = "notif_auto"
    private const val PREF_LOG = "notif_log"

    /** Known chat apps; anything else counts only when it posts a MessagingStyle or a "msg" category notification. */
    val messengers = mapOf(
        "com.whatsapp" to "wa", "com.whatsapp.w4b" to "wab", "org.telegram.messenger" to "tg", "org.telegram.messenger.web" to "tg",
        "org.thunderdog.challegram" to "tg", "com.facebook.orca" to "fb", "com.facebook.mlite" to "fb", "org.thoughtcrime.securesms" to "signal",
        "com.google.android.apps.messaging" to "sms", "com.samsung.android.messaging" to "sms", "com.android.mms" to "sms",
        "com.instagram.android" to "ig", "com.snapchat.android" to "snap", "com.discord" to "discord", "com.viber.voip" to "viber",
        "com.google.android.apps.dynamite" to "gchat", "com.microsoft.teams" to "teams", "com.slack" to "slack", "com.imo.android.imoim" to "imo",
    )

    // ---------------- pure ----------------

    fun appCode(pkg: String): String = messengers[pkg] ?: pkg.substringAfterLast('.').lowercase().filter { it.isLetterOrDigit() }.take(12).ifBlank { "app" }

    /** "Gloria (3 messages)" → "Gloria"; "Family: Mutale" (WhatsApp group title) → "Family". */
    fun cleanTitle(raw: String, group: Boolean): String {
        var t = raw.trim()
        t = t.replace(Regex("\\s*\\(\\d+ (?:new )?messages?\\)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*·\\s*\\d+ (?:new )?messages?$", RegexOption.IGNORE_CASE), "").trim()
        if (group && t.contains(": ")) t = t.substringBefore(": ").trim()
        return t
    }

    fun slug(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40).ifBlank { "chat" }

    fun key(pkg: String, title: String): String = appCode(pkg) + ":" + slug(title)

    fun journalConv(key: String) = "notif:$key"

    fun isMessaging(pkg: String, category: String?, hasMessagingStyle: Boolean): Boolean =
        pkg in messengers || hasMessagingStyle || category == Notification.CATEGORY_MESSAGE

    /** The messages in [incoming] that aren't in [seen] yet (MessagingStyle repeats the history with every post). */
    fun fresh(seen: Set<String>, incoming: List<Msg>): List<Msg> {
        val out = ArrayList<Msg>(); val local = HashSet<String>()
        for (m in incoming) { val s = m.sig; if (m.text.isBlank() || s in seen || !local.add(s)) continue; out += m }
        return out
    }

    /** Strips codes and bank messages; returns what may be stored. */
    fun safe(incoming: List<Msg>, title: String = ""): List<Msg> = incoming.filterNot { Sensitive.isSensitive(it.text, title) }

    fun render(msgs: List<Msg>, fmt: SimpleDateFormat = SimpleDateFormat("EEE HH:mm", Locale.getDefault())): String =
        msgs.joinToString("\n") { "${fmt.format(Date(it.at))} ${if (it.self) "You" else it.sender}: ${it.text.take(600)}" }

    /** True when there's too little to reply well; Blaze then asks the user or opens the chat to read more. */
    fun thin(n: Int) = n < THIN

    fun autoKeys(raw: String): Set<String> = try {
        val a = JSONArray(raw.ifBlank { "[]" }); (0 until a.length()).map { a.getString(it) }.toSet()
    } catch (_: Exception) { emptySet() }

    // ---------------- state ----------------

    private val convs = LinkedHashMap<String, Conv>()

    val logOn: Boolean get() = try { Settings.bool(PREF_LOG, true) } catch (_: Throwable) { true }
    fun setLog(v: Boolean) = Settings.setBool(PREF_LOG, v)

    fun auto(): Set<String> = try { autoKeys(Settings.str(PREF_AUTO)) } catch (_: Throwable) { emptySet() }
    fun isAuto(key: String) = key in auto()
    fun setAuto(key: String, on: Boolean) {
        val s = auto().toMutableSet(); if (on) s += key else s -= key
        Settings.setStr(PREF_AUTO, JSONArray(s.toList()).toString())
    }

    fun list(): List<Conv> = synchronized(convs) { convs.values.sortedByDescending { it.updated } }

    fun find(q: String): Conv? {
        val s = q.trim(); if (s.isEmpty()) return null
        val l = list()
        return l.firstOrNull { it.key.equals(s, true) } ?: l.firstOrNull { it.title.equals(s, true) }
            ?: l.firstOrNull { it.title.contains(s, true) } ?: l.firstOrNull { it.key.contains(slug(s)) }
    }

    /** From NotificationBrain: one posted chat notification. */
    fun onPosted(ctx: Context, pkg: String, app: String, n: Notification) {
        val ex = n.extras ?: return
        val style = try { androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) } catch (_: Throwable) { null }
        if (!isMessaging(pkg, n.category, style != null)) return
        val group = style?.isGroupConversation ?: false
        val rawTitle = style?.conversationTitle?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val title = cleanTitle(rawTitle, group || style?.conversationTitle != null)
        if (title.isBlank()) return
        val incoming = ArrayList<Msg>()
        if (style != null) {
            for (m in style.messages) {
                val person = m.person
                val self = person == null || person.name == null || person.name?.toString() == style.user.name?.toString()
                val who = if (self) "You" else person?.name?.toString().orEmpty().ifBlank { title }
                val text = m.text?.toString().orEmpty()
                if (text.isNotBlank()) incoming += Msg(m.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis(), who, text, self)
            }
        } else {
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            // v0.45.5: no MessagingStyle: a group title "Cyp home: Elena" or text "Elena: Pronto" still names the real sender.
            val (_, who, body) = ChatThreads.splitLegacy(rawTitle, text)
            if (body.isNotBlank()) incoming += Msg(n.`when`.takeIf { it > 0 } ?: System.currentTimeMillis(), who.ifBlank { title }, body)
        }
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        add(pkg, app, title, group, incoming, reply)
    }

    /** Adds messages to a conversation (and the journal). Returns how many were new. Public for tests. */
    fun add(pkg: String, app: String, title: String, group: Boolean, incoming: List<Msg>, reply: Notification.Action?): Int {
        val key = key(pkg, title)
        val c = synchronized(convs) { convs.getOrPut(key) { Conv(key, app, pkg) } }
        c.title = title; c.group = group
        if (reply != null) c.reply = reply
        val ok = safe(incoming, title)
        val add = synchronized(c) {
            val f = fresh(c.seen, ok)
            f.forEach { m -> c.seen += m.sig; c.msgs.addLast(m) }
            while (c.msgs.size > KEEP_IN_MEMORY) c.msgs.removeFirst()
            while (c.seen.size > 400) c.seen.remove(c.seen.first())
            f
        }
        if (add.isNotEmpty()) c.updated = maxOf(c.updated, add.maxOf { it.at })
        // v0.38.0 follow-ups: plans in new messages; the user's own reply closes that chat's follow-ups.
        if (add.isNotEmpty()) try { com.blaze.assistant.proactive.FollowUps.onMessages(pkg, app, title, group, add) } catch (_: Throwable) { }
        else if (incoming.isNotEmpty()) c.updated = maxOf(c.updated, System.currentTimeMillis())
        synchronized(convs) { while (convs.size > 80) { val old = convs.values.minByOrNull { it.updated }?.key ?: break; convs.remove(old) } }
        if (logOn) Mind.db()?.let { d ->
            for (m in add) try {
                MindLogs.append(d, journalConv(key), if (m.self) "You" else "${m.sender} ($app)", m.text, m.at / 1000, MindLogs.NOTIF)
            } catch (_: Exception) { }
        }
        return add.size
    }

    /** For tests and Settings: forget the in-memory state. */
    fun reset() { synchronized(convs) { convs.clear() } }

    // ---------------- tools ----------------

    val toolNames = setOf("notif_conversations", "notif_read", "notif_reply")

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)

    fun tools(): List<JSONObject> = listOf(
        fn("notif_conversations", "Chat conversations the user got messages in (WhatsApp, SMS, Telegram, Messenger…), newest first, from notifications. Each has an id for notif_read / notif_reply and says whether it can be answered from the notification.",
            JSONObject().put("app", str("Optional app filter, e.g. WhatsApp")).put("hours", JSONObject().put("type", "number").put("description", "How far back, default 24")), emptyList()),
        fn("notif_read", "Read one chat conversation: the recent messages from its notifications plus the longer rolling log Blaze kept, and what memory says about the sender. If it says context is thin, ask the user (ask_user) or open the chat with phone control and read the screen before drafting a reply.",
            JSONObject().put("conversation", str("Id from notif_conversations, or the person's / group's name")).put("limit", JSONObject().put("type", "integer").put("description", "Messages, default 30")),
            listOf("conversation")),
        fn("notif_reply", "Send a reply in a chat conversation through its notification's reply box, without opening the app. The user sees a confirm card first (unless they turned on auto-reply for this conversation). Draft the text in the user's voice; read the conversation first.",
            JSONObject().put("conversation", str("Id from notif_conversations, or the name")).put("text", str("The reply")), listOf("conversation", "text")),
    )

    private val clock get() = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

    fun run(ctx: Context, name: String, args: JSONObject): String = when (name) {
        "notif_conversations" -> conversations(args.optString("app").ifBlank { null }, args.optDouble("hours", 24.0))
        "notif_read" -> read(args.optString("conversation"), args.optInt("limit", 30))
        else -> "Unknown tool"
    }

    private fun off() = NotificationBrain.instance == null

    fun conversations(app: String?, hours: Double): String {
        if (off()) return "Notification access is off. Ask the user to turn it on in Blaze \u203a Settings \u203a Notification replies."
        val since = System.currentTimeMillis() - (hours * 3_600_000).toLong()
        val l = list().filter { it.updated >= since && (app.isNullOrBlank() || it.app.contains(app, true) || appCode(it.pkg) == app.lowercase()) }
        if (l.isEmpty()) return "No chat messages in that window."
        val auto = auto()
        return l.take(25).joinToString("\n") { c ->
            val last = synchronized(c) { c.msgs.lastOrNull() }
            "${c.key} · ${c.app} · ${c.title}${if (c.group) " (group)" else ""} · ${clock.format(Date(c.updated))}" +
                (last?.let { " · last: ${if (it.self) "You" else it.sender}: ${it.text.take(80)}" } ?: "") +
                (if (c.reply != null) " · can reply" else " · no reply box") + (if (c.key in auto) " · auto-reply on" else "")
        }
    }

    fun read(q: String, limit: Int): String {
        val c = find(q)
        val d = Mind.db()
        val logged: List<Mind.Turn> = if (d != null && (c != null || q.contains(':'))) {
            try { MindLogs.recent(d, journalConv(c?.key ?: q.trim()), limit.coerceIn(5, 100)) } catch (_: Exception) { emptyList() }
        } else emptyList()
        if (c == null && logged.isEmpty()) return "No conversation matches \u201c$q\u201d. Call notif_conversations for the ids."
        val live = c?.let { synchronized(it) { it.msgs.toList() } } ?: emptyList()
        val msgs = if (logged.isNotEmpty()) logged.map { t ->
            val self = t.role == "You"
            Msg(t.at * 1000, if (self) "You" else t.role.substringBefore(" (").trim(), t.content, self)
        } else live.takeLast(limit)
        val title = c?.title ?: q
        val sb = StringBuilder()
        sb.append("Conversation ${c?.key ?: q} · ${c?.app ?: ""} · $title${if (c?.group == true) " (group)" else ""}\n")
        sb.append(render(msgs.takeLast(limit.coerceIn(5, 100)), clock))
        // Link senders to what memory knows about them.
        val senders = (msgs.filterNot { it.self }.map { it.sender } + title).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(4)
        val mem = senders.flatMap { s -> try { d?.search(s, "person", 2) ?: emptyList() } catch (_: Exception) { emptyList() } }.distinctBy { it.id }
        if (mem.isNotEmpty()) sb.append("\nMemory about them:\n").append(mem.joinToString("\n") { Mind.line(it) })
        else sb.append("\nNothing saved about ${senders.firstOrNull() ?: title} yet; save who they are to the user with memory_save once you know.")
        if (thin(msgs.size)) sb.append("\nContext is thin (${msgs.size} message${if (msgs.size == 1) "" else "s"}). Before drafting a reply, ask the user what to say (ask_user with 2-3 draft options), or open the chat in ${c?.app ?: "the app"} with phone control and read the screen for more.")
        if (c != null && c.reply == null) sb.append("\nThis conversation has no live reply box right now (the notification was dismissed). To answer, open the app with phone control, or use message_contact.")
        if (c != null && isAuto(c.key) && !c.group) sb.append("\nThe user turned on auto-reply for this conversation: notif_reply sends without a card.")
        if (c?.group == true) {
            val lines = msgs.map { ChatThreads.Line(it.at, it.sender, it.text, it.self) }
            val me = listOfNotNull(try { Settings.userName.takeIf { it.isNotBlank() } } catch (_: Throwable) { null })
            ChatThreads.lastAsk(lines, true, me)?.let { a -> sb.append("\nLatest question: ${a.from} asked " +
                when (a.target) { ChatThreads.Target.OTHER -> "${a.to} (not the user)"; ChatThreads.Target.USER -> "the user"; else -> "the group" } +
                (if (a.answered) ", already answered." else ".") + " Never reply into a group without the user's OK on the exact text.") }
        }
        sb.append("\n").append(ChatThreads.UNTRUSTED)
        return sb.toString()
    }

    /** Sends [text] through the conversation's reply box. Confirm card first unless auto-reply is on for it. */
    suspend fun reply(ctx: Context, q: String, text: String): String {
        if (off()) return "Notification access is off. Ask the user to turn it on in Blaze \u203a Settings \u203a Notification replies."
        val c = find(q) ?: return "No conversation matches \u201c$q\u201d. Call notif_conversations for the ids."
        val action = c.reply ?: return "${c.title}'s notification has no live reply box (it was dismissed). Open ${c.app} with phone control instead, or use message_contact."
        val t = text.trim(); if (t.isEmpty()) return "Nothing to send."
        if (Sensitive.isSensitive(t)) return "Not sent: that reply looks like it contains a code or bank details. Blaze never sends those from a notification."
        // v0.45.5: a group never gets an automatic reply; the card always shows the exact text first.
        val auto = ChatThreads.canAutoSend(c.group, isAuto(c.key))
        if (!auto && !Agent.confirm("Reply to ${c.title}?", "\u201c$t\u201d\n(in ${c.app}, from the notification)", "Send", always = true))
            return "The user declined. Nothing was sent."
        val inputs = action.remoteInputs ?: return "That notification has no reply box."
        val intent = Intent(); val b = Bundle()
        inputs.forEach { ri -> if (ri.allowFreeFormInput) b.putCharSequence(ri.resultKey, t) }
        RemoteInput.addResultsToIntent(inputs, intent, b)
        return try {
            action.actionIntent.send(ctx, 0, intent)
            add(c.pkg, c.app, c.title, c.group, listOf(Msg(System.currentTimeMillis(), "You", t, true)), null)
            "Replied to ${c.title} in ${c.app}${if (auto) " (auto-reply)" else ""}."
        } catch (_: PendingIntent.CanceledException) { "That notification is gone, so I couldn't reply. Open ${c.app} with phone control instead." }
    }
}
