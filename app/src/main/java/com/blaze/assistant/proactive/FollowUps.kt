package com.blaze.assistant.proactive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blaze.assistant.agent.Awareness
import com.blaze.assistant.agent.LlmClient
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.notify.ChatThreads
import com.blaze.assistant.notify.Conversations
import com.blaze.assistant.proactive.FollowUpLogic.Item
import com.blaze.assistant.proactive.FollowUpLogic.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.38.0 Follow-ups, the Android side ([FollowUpLogic] holds the rules). Fed by [Conversations] (each new message in a
 * chat app's notification, the user's own replies included), the accessibility service (an app opened, Send tapped)
 * and Blaze's own replies. Asks "Are you going?" with Yes / No buttons, sets the reminder, nudges in the app, and shows
 * what's open in the prompt (Awareness) and through the follow_ups tool.
 */
object FollowUps {
    // v0.45.5 hotfix: was "followups", the key of the "Suggested follow-ups" switch (a boolean): the list overwrote the switch
    // and every turn crashed reading it (PrefCoerce). Settings.init moves an old list here once.
    private const val PREF = com.blaze.assistant.agent.PrefCoerce.FOLLOWUP_ITEMS
    private const val CH = "blaze_followups"
    const val TOOL = "follow_ups"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.blaze.assistant.device.CrashLog.guard)
    @Volatile private var app: Context? = null
    private val pending = ConcurrentHashMap<String, Long>()   // conversation → last look (debounce)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        Awareness.readers += {
            val now = System.currentTimeMillis()
            all().filter { FollowUpLogic.live(it, now) }.take(4).map { Awareness.Now("followup:" + it.id, "Follow-up", FollowUpLogic.line(it), "") }
        }
    }

    fun all(): List<Item> = FollowUpLogic.fromJson(Settings.str(PREF, "[]"))
    private fun save(list: List<Item>) {
        try { com.blaze.assistant.agent.TurnContextSource.invalidate() } catch (_: Throwable) { }   // v0.45.9: open follow-ups feed the [context] block
        val now = System.currentTimeMillis()
        Settings.setStr(PREF, FollowUpLogic.toJson(list.filter { FollowUpLogic.live(it, now) || now - it.created < 7 * 86_400_000L }.takeLast(40)))
    }
    private fun update(id: String, f: (Item) -> Item): Item? = synchronized(this) {
        var out: Item? = null
        save(all().map { if (it.id == id) f(it).also { n -> out = n } else it }); out
    }

    // ---------------- messages ----------------

    /** New messages in a chat (from Conversations). The user's own message closes that chat's follow-ups. */
    fun onMessages(pkg: String, appName: String, conv: String, group: Boolean, msgs: List<Conversations.Msg>) {
        val ctx = app ?: return
        if (JarvisLogic.Level.of(Settings.str("jarvis_level", "eager")) == JarvisLogic.Level.OFF) return
        if (msgs.any { it.self }) markReplied(pkg, conv)
        val theirs = msgs.filter { !it.self && FollowUpLogic.looksLikePlan(it.text) }
        if (theirs.isEmpty()) return
        val key = "$pkg|$conv"
        val now = System.currentTimeMillis()
        pending[key] = now
        // Wait a little so a burst of messages ("can we meet" / "tomorrow at 3" / "about the budget") is read once.
        scope.launch {
            delay(20_000)
            if (pending[key] != now) return@launch
            val recent = Conversations.find(conv)?.let { c -> synchronized(c) { c.msgs.toList().takeLast(8) } } ?: theirs
            extract(ctx, pkg, appName, conv, group, recent)
        }
    }

    private suspend fun extract(ctx: Context, pkg: String, appName: String, conv: String, group: Boolean, recent: List<Conversations.Msg>) {
        if (all().any { it.pkg == pkg && it.conv == conv && FollowUpLogic.live(it, System.currentTimeMillis()) && !it.replied }) return
        val f = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
        val nowText = SimpleDateFormat("EEEE d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())
        val sys = "You read a chat on the user's phone and decide whether it contains a plan or request that involves the user: a meeting, call, " +
            "outing, deadline, appointment or a question waiting for their answer. Now is $nowText (${java.util.TimeZone.getDefault().id}). " +
            "Reply ONLY with JSON: {\"plan\": true|false, \"topic\": \"short, specific, e.g. 'a meeting about the budget'\", " +
            "\"when\": \"as a person would say it, e.g. 'tomorrow at 15:00' or ''\", \"when_ms\": epoch milliseconds of that time or 0, " +
            "\"needs_reply\": true|false, \"ask\": \"one short question to the user, e.g. 'Are you going?' or 'Want to reply?'\"}"
        // v0.45.5 (P1): every message keeps its own sender; a group question for someone else is info only, never an ask.
        val lines = recent.map { ChatThreads.Line(it.at, it.sender, it.text, it.self) }
        val me = listOfNotNull(Settings.userName.takeIf { it.isNotBlank() })
        val decision = ChatThreads.decide(conv, group, lines, me)
        if (group && !decision.askUser) {
            Awareness.did("chat", decision.summary.take(300) + (decision.ask?.let { a -> if (a.target == ChatThreads.Target.OTHER) " (question for ${a.to}, not you)" else "" } ?: ""))
            return
        }
        val chat = ChatThreads.render(lines) { f.format(Date(it)) }
        val raw = try {
            LlmClient.complete(JSONArray().put(JSONObject().put("role", "system").put("content", sys + " " + ChatThreads.UNTRUSTED +
                " Name each person by who really said what; in a group the person who asked is the one making the plan."))
                .put(JSONObject().put("role", "user").put("content", "Chat \"$conv\" on $appName${if (group) " (group)" else ""}:\n$chat")), null, null, null, LlmClient.Lane.BACKGROUND).optString("content")
        } catch (_: Throwable) { return }
        val plan = FollowUpLogic.parsePlan(raw) ?: return
        val person = (decision.ask?.from ?: recent.lastOrNull { !it.self }?.sender)?.ifBlank { conv } ?: conv
        val item = Item("F" + System.currentTimeMillis().toString(36), pkg, appName, conv, person, plan.topic, plan.whenText, plan.whenAt,
            plan.needsReply, Status.ASKED, System.currentTimeMillis(), ask = plan.ask)
        synchronized(this) { save(all() + item) }
        Awareness.did("followup", "${item.person} on ${item.app}: ${item.topic}" + if (item.whenText.isNotBlank()) ", ${item.whenText}" else "")
        notifyAsk(ctx, item)
    }

    private fun notifyAsk(ctx: Context, i: Item) {
        val text = FollowUpLogic.askText(i)
        try { com.blaze.assistant.agent.Agent.recordHeadsUp(text) } catch (_: Throwable) { }
        if (Proactive.quietNow()) return
        fun act(a: String, code: Int) = PendingIntent.getBroadcast(ctx, (i.id + a).hashCode() + code, Intent(ctx, FollowUpReceiver::class.java).setAction(a).putExtra("id", i.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val goingWord = if (i.ask.isBlank() || i.ask.contains("going", true) || i.ask.contains("coming", true)) "Yes, I'm going" else "Yes"
        // 0.45.19: a follow-up is a text in the one Blaze thread (avatar, alerting channel), with its own buttons.
        val acts = mutableListOf(NotificationCompat.Action.Builder(0, goingWord, act(FollowUpReceiver.GOING, 1)).build(),
            NotificationCompat.Action.Builder(0, "No", act(FollowUpReceiver.NOT_GOING, 2)).build())
        if (i.needsReply) acts.add(1, NotificationCompat.Action.Builder(0, "I replied", act(FollowUpReceiver.REPLIED, 3)).build())
        com.blaze.assistant.notify.Notifier.post(text, com.blaze.assistant.notify.BlazeThread.Kind.FOLLOW_UP, context = text, extra = acts, reply = false)
    }

    fun cancelNotification(ctx: Context, id: String) { try { NotificationManagerCompat.from(ctx).cancel(9100 + Math.floorMod(id.hashCode(), 400)); com.blaze.assistant.notify.Notifier.clearReplies() } catch (_: Throwable) { } }

    // ---------------- answers ----------------

    /** Going: a reminder 30 minutes before (when there's a time), and Blaze keeps nudging until they've replied. */
    fun going(id: String): String {
        val now = System.currentTimeMillis()
        var said = ""
        update(id) { i ->
            val at = FollowUpLogic.reminderAt(i.whenAt, now)
            var set = i.reminderSet
            if (at > 0 && !i.reminderSet) try {
                com.blaze.assistant.routine.Routines.addAt("${i.topic.replaceFirstChar { it.uppercase() }} with ${i.person}",
                    "Remind the user, warmly and briefly: ${i.topic} with ${i.person} ${i.whenText}. " +
                        "Check follow_ups: if they still haven't replied to ${i.person}, offer to send a quick reply on ${i.app}.", at)
                set = true
            } catch (_: Throwable) { }
            said = if (set) "Great, I'll remind you ${SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(at))}." else "Great, noted."
            if (i.needsReply && !i.replied) said += " Don't forget to let ${i.person.substringBefore(' ')} know."
            i.copy(status = Status.GOING, reminderSet = set)
        } ?: return "No follow-up $id."
        return said
    }

    fun notGoing(id: String): String { update(id) { it.copy(status = Status.NOT_GOING) } ?: return "No follow-up $id."; return "Okay, I'll let it go." }

    fun markReplied(pkg: String, conv: String) {
        synchronized(this) {
            val list = all()
            if (list.none { it.pkg == pkg && it.conv == conv && !it.replied }) return
            save(list.map { if (it.pkg == pkg && it.conv == conv) it.copy(replied = true, status = if (it.status == Status.ASKED) Status.REPLIED else it.status) else it })
        }
        Awareness.did("replied", "replied to $conv")
    }

    fun markRepliedId(id: String) { update(id) { it.copy(replied = true, status = if (it.status == Status.ASKED) Status.REPLIED else it.status) } }

    // ---------------- in the app ----------------

    /** The user opened [pkg]: nudge about the follow-up that's still waiting for their reply. */
    fun onAppOpened(pkg: String) {
        if (app == null) return
        val now = System.currentTimeMillis()
        val i = all().firstOrNull { FollowUpLogic.shouldNudge(it, pkg, now) } ?: return
        update(i.id) { it.copy(nudgedAt = now) }
        scope.launch {
            delay(2_500)   // let the app come up first
            Proactive.say(FollowUpLogic.nudgeText(i), "followup-nudge:" + i.id + ":" + (now / 3_600_000L), suggest = "Draft a reply to ${i.person.substringBefore(' ')}")
        }
    }

    /** Send tapped in a chat app: when the open chat shows a waiting follow-up's person, it counts as replied. */
    fun onClick(pkg: String, label: String?, root: AccessibilityNodeInfo?) {
        if (!FollowUpLogic.isSend(label) || root == null) return
        val now = System.currentTimeMillis()
        val waiting = all().filter { it.pkg == pkg && !it.replied && FollowUpLogic.live(it, now) }
        for (i in waiting) {
            val seen = try { root.findAccessibilityNodeInfosByText(i.conv).isNotEmpty() || root.findAccessibilityNodeInfosByText(i.person).isNotEmpty() } catch (_: Throwable) { false }
            if (seen) markReplied(pkg, i.conv)
        }
    }

    // ---------------- tool ----------------

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Plans and messages Blaze is keeping an eye on (who, app, what, when, going?, replied?). list (default); going / not_going / replied / done with id. " +
            "Use for 'what do I need to get back to', 'yes I'm going', 'I already replied to Jack'.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("list", "going", "not_going", "replied", "done"))))
            .put("id", JSONObject().put("type", "string")))))

    fun run(args: JSONObject): String {
        val id = args.optString("id")
        return when (args.optString("action", "list")) {
            "going" -> going(id)
            "not_going" -> notGoing(id)
            "replied" -> { markRepliedId(id); "Marked as replied." }
            "done" -> { update(id) { it.copy(status = Status.DONE) } ?: return "No follow-up $id."; "Done." }
            else -> {
                val now = System.currentTimeMillis()
                all().filter { FollowUpLogic.live(it, now) }.joinToString("\n") { "[${it.id}] " + FollowUpLogic.line(it) }.ifBlank { "Nothing open." }
            }
        }
    }
}

class FollowUpReceiver : BroadcastReceiver() {
    companion object {
        const val GOING = "com.blaze.assistant.FOLLOWUP_GOING"
        const val NOT_GOING = "com.blaze.assistant.FOLLOWUP_NOT_GOING"
        const val REPLIED = "com.blaze.assistant.FOLLOWUP_REPLIED"
    }

    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getStringExtra("id") ?: return
        FollowUps.cancelNotification(ctx, id)
        val said = when (i.action) {
            GOING -> FollowUps.going(id)
            NOT_GOING -> FollowUps.notGoing(id)
            REPLIED -> { FollowUps.markRepliedId(id); "" }
            else -> ""
        }
        if (said.isNotBlank()) try { android.widget.Toast.makeText(ctx, said, android.widget.Toast.LENGTH_LONG).show() } catch (_: Throwable) { }
    }
}
