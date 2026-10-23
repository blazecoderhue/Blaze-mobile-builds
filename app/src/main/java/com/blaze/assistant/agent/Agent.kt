package com.blaze.assistant.agent

import android.content.Context
import com.blaze.assistant.notify.Notifier
import com.blaze.assistant.phone.PhoneTools
import com.blaze.assistant.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

enum class Role { USER, BLAZE, STEP, ERROR }

/** [files] are attachment names shown under a user message. */
@androidx.compose.runtime.Immutable   // v0.31.1: finished messages compare by value, so their rows skip recomposition
data class ChatMessage(val id: Long, val role: Role, val text: String, val files: List<String> = emptyList(),
                       val thinking: String = "", val followups: List<String> = emptyList(), val meta: String = "",
                       /** Pictures, beats and other media Blaze made for this reply (absolute paths). */
                       val media: List<String> = emptyList(),
                       /** When it was added (ms); 0 for chats saved before v1.13. */
                       val at: Long = System.currentTimeMillis(),
                       /** v0.26.1: how long the model's reasoning streamed (ms), for "Thought it through · 4 s"; 0 = not measured. */
                       val thinkMs: Long = 0L,
                       /** v0.28.4: on the first step of a turn, when that turn really started (ms; a retry or a wake starts a new one); 0 = unknown. */
                       val startAt: Long = 0L,
                       /** v0.30.6 rapid messages: the user messages this reply answers (from its [re:m7] marker); empty = not said. */
                       val replyTo: List<Long> = emptyList(),
                       /** v0.31.2 Halo x Blaze: who said this reply ("halo"; "" or "blaze" = Blaze). */
                       val speaker: String = "",
                       /** v0.45.0 "What I did": the turn's action receipts (Receipt.toJson), stored with the reply; "" = none. */
                       val receipt: String = "",
                       /** v0.45.0 (#37, R2): globally unique, stable id of this bubble (Gid); copy() keeps it. */
                       val gid: String = Gid.next(),
                       /** v0.45.0 reactions (R1): saved with the message, synced as their own small items. */
                       val reactions: List<Reaction> = emptyList(),
                       /** v0.45.5 "What happened": model, route, tools, timings, errors and request id of this reply (TurnTrace JSON); "" = none. */
                       val trace: String = "")

/** Splits a raw reply into what's shown, hidden reasoning (<think> tags) and follow-up suggestions. */
object ReplyParts {
    private val thinkRe = Regex("<(think|thought|thinking)>([\\s\\S]*?)(</\\1>|$)", RegexOption.IGNORE_CASE)
    // Tolerant: "<<follow-ups: a | b>>", "<</follow-ups: a | b>>", "<follow ups: a|b>", "<<followups:a|b" at the end.
    private val fuRe = Regex("<{1,2}\\s*/?\\s*follow[\\s_-]?ups?\\s*:([\\s\\S]*?)(>{1,2}|$)", RegexOption.IGNORE_CASE)

    data class Parts(val text: String, val thinking: String, val followups: List<String>)

    fun split(raw0: String, final: Boolean = false, keepCalls: Boolean = false): Parts {
        // v0.23.2: leaked channel / template tokens and reasoning leads go first (shared with streaming and storage).
        val clean = ReplyClean.clean(raw0, final)
        // v0.28.1: tool calls written as text ("[/find_tool need=…]") never reach the screen, speech or history;
        // only the gatekeeper reads them (keepCalls) to run them as real calls.
        // v0.44.0: function-style calls (`browser_show()`, `open_app("X")`, a bare JSON call) are hidden too (TextCalls).
        // 0.45.19: CallScan first (multi-line Python calls, JSON calls mid-prose, fenced tool blocks, cut-off calls).
        val raw = if (keepCalls) clean.text else TextCalls.strip(ToolLeak.strip(CallScan.clean(clean.text, final), final), final)
        val thinking = (listOf(clean.thinking) + thinkRe.findAll(raw).map { it.groupValues[2].trim() }).filter { it.isNotBlank() }.joinToString("\n\n")
        // v0.45.3: Autopilot's hidden <<done>> / <<continue>> marker is never shown, spoken or kept (a half-typed one waits).
        var text = AutoContinue.strip(thinkRe.replace(raw, ""), final)
        val fu = fuRe.find(text)
        val followups = fu?.takeIf { it.groupValues[2].isNotEmpty() || (final && it.groupValues[1].contains('|')) }?.groupValues?.get(1)
            ?.split('|')?.map { it.trim().trim('"', '-', '•', ' ', '<', '>', '/') }?.filter { it.isNotEmpty() && ChipGate.allowed(it) }?.take(3) ?: emptyList()
        if (fu != null) text = text.substring(0, fu.range.first)
        // Hide a half-typed "<<follow-ups" / "</follow" while streaming.
        val cut = text.lastIndexOf('<')
        if (cut >= 0 && text.length - cut < 18 && Regex("^<{1,2}\\s*/?\\s*(f(o(l(l(o(w[\\s_-]?(u(ps?)?)?)?)?)?)?)?)?$", RegexOption.IGNORE_CASE).matches(text.substring(cut).trimEnd()))
            text = text.substring(0, cut)
        return Parts(text.trim(), thinking, followups)
    }
}

enum class Mood { IDLE, THINKING, ACTING }

/** An action waiting for the user's OK (send a text, place a call, tap "Pay"). */
data class PendingConfirm(val title: String, val detail: String, val action: String,
                          val answer: kotlinx.coroutines.CompletableDeferred<Boolean>,
                          /** v0.45.3: the card also offers "Allow for this chat" and "Always allow this command" (Normal mode commands). */
                          val grants: Boolean = false,
                          val grant: java.util.concurrent.atomic.AtomicReference<CommandPolicy.Grant> = java.util.concurrent.atomic.AtomicReference(CommandPolicy.Grant.ONCE))

/** A question Blaze asks mid-task, with tap-to-answer [options] (the UI always adds "type my own"). */
data class PendingQuestion(val question: String, val options: List<String>, val chat: String,
                           val answer: kotlinx.coroutines.CompletableDeferred<String>)

/** Something the user attached: [parts] are OpenAI content parts (image_url / text). */
data class Attachment(val name: String, val parts: List<JSONObject>, val thumb: String? = null)

/** The brain: keeps the conversation and runs the think → act → look loop. */object Agent {
    private const val MAX_STEPS = 30
    private const val SHOT_NOTE = "[Screenshot of the phone screen]"
    // v0.30.4: screen readouts are found by FastActions.isScreen (the old "Foreground app:" mark hadn't matched any
    // readout since v1.9.7, so old screens were never dropped from the prompt).
    /** Read-only tools that are safe to run side by side. */
    private val parallelSafe = setOf("web_search", "fetch_page", "recall_memory", "memory_search", "search_past_chats", "gmail_search", "gmail_read",
        "gcal_events", "calendar_events", "drive_search", "gcontacts_search", "find_contact", "recent_notifications", "read_messages",
        "recent_calls", "device_status", "battery_level", "github_issues", "github_my_prs", "github_notifications", "github_search_repos",
        "notion_search", "slack_read", "slack_channels", "search_screen_memory", "spotify_now_playing", BlazeDocs.TOOL_SEARCH, BlazeDocs.TOOL_READ,
        ProjectDocs.TOOL_SEARCH, ProjectDocs.TOOL_READ, com.blaze.assistant.home.HomeTools.LIST)

    private val screenActions = setOf("tap", "type_text", "scroll", "press_button", "open_app", "open_url", "search_in_app")
    private val ids = AtomicLong(1)
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.blaze.assistant.device.CrashLog.guard)

    /** Text or a file shared into Blaze from another app, waiting in the message box for the user's Send. */
    data class SharedDraft(val text: String, val attachment: Attachment? = null)
    val sharedDraft = MutableStateFlow<SharedDraft?>(null)

    /** Messages of the chat on screen. */
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _mood = MutableStateFlow(Mood.IDLE)
    val mood: StateFlow<Mood> = _mood

    /** The model's reasoning so far in the current turn (shown live under the brain). */
    private val _thinking = MutableStateFlow("")
    val thinking: StateFlow<String> = _thinking
    /** What Blaze is doing right now, in plain words ("Searching the web for …"); "" when idle. Never a command. */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    /** Ack-and-background: the task chip's label while an acknowledged task runs on the chat on screen ("" = none). */
    private val _taskChip = MutableStateFlow("")
    val taskChip: StateFlow<String> = _taskChip
    /**
     * v0.31.0: the id of the reply that is streaming in right now on the chat on screen (-1 = none). The thread draws the
     * water droplet riding that reply's last word and the soft fade on its newest words while this matches.
     */
    private val _streamingId = MutableStateFlow(-1L)
    val streamingId: StateFlow<Long> = _streamingId
    /** v0.31.2 Halo x Blaze: replies streaming side by side (@all, parallel team phases); empty = none. */
    private val _duoLive = MutableStateFlow<Set<Long>>(emptySet())
    val duoLive: StateFlow<Set<Long>> = _duoLive
    /** v0.32.0: the meeting rooms of the chat on screen (room card id → its messages). */
    private val _rooms = MutableStateFlow<Map<Long, List<ChatMessage>>>(emptyMap())
    val rooms: StateFlow<Map<Long, List<ChatMessage>>> = _rooms
    /** v0.32.0 split: Halo is talking with you while Blaze works (the drop detached, the ring stays with you). */
    private val _split = MutableStateFlow(false)
    val split: StateFlow<Boolean> = _split
    /** v0.32.0: Halo's side replies streaming right now (the main working drop stays with Blaze). */
    private val _sideLive = MutableStateFlow<Set<Long>>(emptySet())
    val sideLive: StateFlow<Set<Long>> = _sideLive
    /** v0.32.0: marks coroutines of Halo's side lane (their tool steps never land in Blaze's work card). */
    internal val sideFlag = ThreadLocal<Boolean>()
    /** v0.32.0: the meeting room open on screen (-1 = none). */
    val roomOpen = MutableStateFlow(-1L)

    /** Token meter of the chat on screen (v0.15.0): what the last model call sent and got back. */
    data class Meter(val inTok: Int, val outTok: Int, val limit: Int) {
        val text get() = LongChat.meter(inTok, outTok)
        val high get() = LongChat.high(inTok, limit)
    }
    private val _meter = MutableStateFlow<Meter?>(null)
    val meter: StateFlow<Meter?> = _meter

    /** v0.30.6 rapid messages: ids of the user messages the visible chat is working on (their bubbles get a soft highlight). */
    private val _focus = MutableStateFlow<Set<Long>>(emptySet())
    val focus: StateFlow<Set<Long>> = _focus
    /** v0.30.6: the chat composer's last keystroke and whether it holds text (an open burst waits while you type). */
    @Volatile private var typingAt = 0L
    @Volatile private var typingText = false
    fun typing(hasText: Boolean) {
        // v0.45.1 speed: the first key press opens (or refreshes) the connection to Blaze Cloud, so DNS + TLS are done
        // before Send (LlmClient.prewarm is at most once a minute per host and runs on its own thread).
        if (hasText && !typingText) LlmClient.prewarm()
        typingAt = System.currentTimeMillis(); typingText = hasText
    }

    /** Ids of chats that are working right now (shown as a live dot in the chat list). */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busyChats: StateFlow<Set<String>> = _busy
    private fun refreshBusy() { _busy.value = sessions.values.filter { it.mood != Mood.IDLE }.map { it.id }.toSet() }

    private val _confirm = MutableStateFlow<PendingConfirm?>(null)
    val pendingConfirm: StateFlow<PendingConfirm?> = _confirm

    /**
     * Ask the user to approve something risky. Shows a card in the chat and the orb panel,
     * plus a notification with Allow / Don't when Blaze isn't on screen. Times out as "no".
     */
    /** v0.45.3: Normal-mode command allowances ("Allow for this chat" / "Always allow this command"; CommandPolicy). */
    val cmdGrants = CommandPolicy.Grants({ try { Settings.str("cmd_allow_always", "") } catch (_: Throwable) { "" } },
        { v -> try { Settings.setStr("cmd_allow_always", v) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("cmdGrants.save", e) } })

    /** v0.45.3: the confirm card with the allowance choices; the user's answer (NO when refused or timed out). */
    suspend fun confirmGrant(title0: String, detail0: String, action: String): CommandPolicy.Grant {
        val r = java.util.concurrent.atomic.AtomicReference(CommandPolicy.Grant.ONCE)
        return if (confirm(title0, detail0, action, always = false, grants = r)) r.get() else CommandPolicy.Grant.NO
    }

    suspend fun confirm(title0: String, detail0: String, action: String, always: Boolean = false,
                        grants: java.util.concurrent.atomic.AtomicReference<CommandPolicy.Grant>? = null): Boolean {
        val tag = kotlin.coroutines.coroutineContext[BgAgents.Tag]
        val title = if (tag != null) "${tag.id} · $title0" else title0
        // v0.28.2 (ConfirmGate): [always] = outbound in the user's name (texts, calls, messages, email, posts, money,
        // deletes, installs, phone settings): the card always shows. A turn that read untrusted content (web, messages,
        // notifications, the screen, shared files…) also loses standing OKs and Autopilot for destructive actions.
        val source = kotlin.coroutines.coroutineContext[ConfirmGate.Taint]?.source
        when (ConfirmGate.decide(always, Modes.destructive(title0, detail0), !always && Autonomy.preApproved(title0, detail0), Modes.autopilot, source != null)) {
            ConfirmGate.Decision.STANDING_OK -> { cur.note("Standing OK: $title", false); return true }
            // Autopilot: the user is away, so ordinary confirms pass. Destructive ones still wait for their tap.
            ConfirmGate.Decision.AUTOPILOT_OK -> { (if (tag != null) null else cur)?.note("Autopilot approved: $title0", false); return true }
            ConfirmGate.Decision.SHOW -> { }
        }
        val detail = ConfirmGate.detail(detail0, source)
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        tag?.let { BgAgents.setWaiting(it.id, true) }
        _confirm.value = if (grants != null) PendingConfirm(title, detail, action, d, true, grants) else PendingConfirm(title, detail, action, d)
        if (!inForeground) Notifier.confirm(title, detail, action)
        val ok = kotlinx.coroutines.withTimeoutOrNull(120_000) { d.await() } ?: false
        tag?.let { BgAgents.setWaiting(it.id, false) }
        _confirm.value = null
        Notifier.clearConfirm()
        cur.note(if (ok) "You approved: $title" else "Not approved: $title", false)
        return ok
    }

    fun answerConfirm(ok: Boolean, grant: CommandPolicy.Grant = CommandPolicy.Grant.ONCE) {
        _confirm.value?.let { p -> if (ok) p.grant.set(grant); p.answer.complete(ok) }
    }

    private val _question = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _question
    private val askLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Pause the task and ask the user. In Blaze it's a card in the chat; over another app the
     * orb panel pops up with the question so the task continues right where it is.
     */
    suspend fun askUser(question0: String, options: List<String>, chat: String): String = askLock.withLock {
        val tag = kotlin.coroutines.coroutineContext[BgAgents.Tag]
        val question = if (tag != null) "${tag.id} asks: $question0" else question0
        val d = kotlinx.coroutines.CompletableDeferred<String>()
        tag?.let { BgAgents.setWaiting(it.id, true) }
        _question.value = PendingQuestion(question, options.filter { it.isNotBlank() }.take(5), chat, d)
        var popped = false
        if (!inForeground && !overlayOpen) {
            if (com.blaze.assistant.ui.Overlay.canDrawOverApps(app)) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Overlay.openPanel(app, false) }; popped = true
            } else Notifier.question(question)
        }
        if (Voice.lastTurnByVoice && Voice.shouldSpeak()) Voice.speak(question)
        // Autopilot: the card stays up for two minutes in case they're there, then Blaze decides itself.
        val ap = Modes.autopilot
        // v0.38.0: Autopilot is the default now, so the user is often right there: two minutes before deciding alone.
        val a = kotlinx.coroutines.withTimeoutOrNull(if (ap) 120_000L else 15 * 60_000L) { d.await() }
        tag?.let { BgAgents.setWaiting(it.id, false) }
        _question.value = null
        Notifier.clearQuestion()
        if (popped && a != null) kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Overlay.closePanel() }
        a ?: if (ap) Modes.AUTO_PREFIX + Modes.AUTO_REPLY else "(The user didn't answer within 15 minutes. Stop and say what you need.)"
    }

    fun answerQuestion(text: String) { _question.value?.answer?.complete(text) }

    // ---------- v0.20.0: secure forms for the background browser ----------
    private val _form = MutableStateFlow<com.blaze.assistant.browser.SecureInput.Request?>(null)
    val pendingForm: StateFlow<com.blaze.assistant.browser.SecureInput.Request?> = _form
    private val formLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var formSession: Session? = null

    /**
     * Shows the secure form card and waits (15 minutes at most). When Blaze isn't on screen an
     * "Action required" notification opens the chat at the card. Null = cancelled or no answer.
     */
    suspend fun askSecure(req: com.blaze.assistant.browser.SecureInput.Request, from: Session?): com.blaze.assistant.browser.SecureInput.Answer? = formLock.withLock {
        val tag = kotlin.coroutines.coroutineContext[BgAgents.Tag]
        tag?.let { BgAgents.setWaiting(it.id, true) }
        formSession = from
        _form.value = req
        if (!inForeground) Notifier.actionRequired(req.title, req.site)
        // v0.30.5 (Luke: "the form wouldn't close until I force quit"): the task being stopped while the card was up
        // cancelled this wait, and the clean-up below never ran, so the card stayed with a Cancel that answered nobody.
        // The clean-up is now in finally, and Cancel / Stop remove the card themselves.
        try {
            kotlinx.coroutines.withTimeoutOrNull(15 * 60_000L) { req.answer.await() }
        } finally {
            tag?.let { BgAgents.setWaiting(it.id, false) }
            clearForm(req)
        }
    }

    private fun clearForm(req: com.blaze.assistant.browser.SecureInput.Request) {
        if (_form.value?.id == req.id) { _form.value = null; formSession = null; Notifier.clearAction() }
    }

    /** Fill in / Cancel. The card goes at once, even if nothing is waiting on it any more. */
    fun answerForm(a: com.blaze.assistant.browser.SecureInput.Answer?) {
        val r = _form.value ?: return
        r.answer.complete(a)
        clearForm(r)
    }

    /** Stopping a chat also takes down the form it was waiting on. */
    private fun dropFormOf(s: Session) {
        val r = _form.value ?: return
        if (formSession === s || formSession == null) { r.answer.complete(null); clearForm(r) }
    }

    /** The "Action required" notification was tapped: show the chat that's waiting on the form. */
    fun showFormChat() { formSession?.let { s -> if (s !== cur) show(s) } }

    private val risky = Regex("\\b(send|pay|buy|purchase|order|checkout|check out|delete|remove|post|publish|transfer|confirm|subscribe|place order|book)\\b", RegexOption.IGNORE_CASE)

    private val _chats = MutableStateFlow<List<ChatMeta>>(emptyList())
    val chats: StateFlow<List<ChatMeta>> = _chats

    private val _chatId = MutableStateFlow("")
    val chatId: StateFlow<String> = _chatId

    /** Every chat that's open or still working, by id. */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
    @Volatile private var cur: Session = Session("boot")

    /** v0.28.4: when the open chat's current turn started (the live work card's clock before its first step). */
    val turnStartAt: Long get() = cur.turnAt

    /** v0.45.0 (#12): idle chats leave memory (SessionTrim); they reload from disk when opened again. */
    private fun trimSessions(keep: Int = SessionTrim.KEEP, minIdleMs: Long = SessionTrim.MIN_IDLE_MS) {
        try {
            val now = System.currentTimeMillis()
            val c = cur
            val cands = sessions.values.map { SessionTrim.Cand(it.id, it === c, it.busy || it.sideBusy, it.waiting, it.isPrivate || it.saved, it.touchedAt) }
            for (id in SessionTrim.evict(cands, now, keep, minIdleMs)) sessions[id]?.let { s -> if (s !== cur && !s.busy) sessions.remove(id, s) }
        } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: val now System.currentTimeMillis", e) }
    }

    private fun show(s: Session) {
        cur = s
        sessions[s.id] = s
        _chatId.value = s.id
        _messages.value = s.msgs
        _mood.value = s.mood
        _thinking.value = s.thinking
        _status.value = s.status
        _taskChip.value = s.taskChip
        _streamingId.value = s.streamId
        _duoLive.value = s.duoLive
        _rooms.value = s.rooms
        _split.value = s.split
        _sideLive.value = s.sideLive
        _meter.value = s.meter
        _focus.value = s.focus
        // v0.28.3: only the main thread is remembered as the chat to reopen (project chats live in Projects).
        if (!s.isPrivate) Settings.lastChatId = Mind.MAIN
        _private.value = s.isPrivate
        // Forget idle chats in the background; working ones stay until they finish. v0.45.0 (#12): only once their newest
        // save is on disk, so coming straight back never reloads an older copy.
        trimSessions(keep = 0, minIdleMs = 0)
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        try { SharedPersona.init(app) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: SharedPersona.init", e) }   // v0.45.5 (#10): bundled v1 now, daily refresh in the background
        // v0.45.9: the [context] block (time, cached weather, today's schedule, open tasks), warmed off the main thread on typing (LlmClient.prewarm) so Send finds it ready.
        try { TurnContextSource.init(app) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: TurnContextSource.init", e) }
        // v0.45.4: messages held by a Sign in card go out by themselves once the Blaze account lands.
        scope.launch {
            var wasIn = com.blaze.assistant.connect.BlazeAccount.signedIn()
            com.blaze.assistant.connect.BlazeAccount.view.collect { v ->
                val nowIn = v.user != null
                if (SignInGate.release(wasIn, nowIn, signInHolds.size())) sendHeld()
                wasIn = nowIn
            }
        }
        // v0.26.2: the built-in specialists' field playbooks ship in assets/specialists/<id>.md.
        Playbooks.loader = { id -> runCatching { app.assets.open("specialists/$id.md").bufferedReader().use { it.readText() } }.getOrNull() }
        com.blaze.assistant.browser.Vault.dir = app.filesDir
        ChatStore.init(app)
        // v0.45.0 (#37): this phone's message-id prefix (made once, kept in prefs).
        Gid.init({ Settings.str(Gid.PREF) }, { Settings.setStr(Gid.PREF, it) })
        TurnFeedback.init(java.io.File(app.filesDir, "turn_feedback.json"))   // v0.45.0 reactions → learning
        // v0.45.0 (#7): a failed save says so once per chat (storage full, or the phone wouldn't write), instead of losing it silently.
        ChatStore.onSaveFailed = { id, reason ->
            if (saveWarned.add(id)) scope.launch { sessions[id]?.add(Role.BLAZE, "Couldn't save this chat: $reason. Free some space and I'll keep trying on the next message.") }
        }
        LongChat.init(app)
        try { com.blaze.assistant.media.Media.init(app) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: Media.initapp", e) }
        refreshChats()
        // One mind (v0.18.0): one continuous main conversation. v0.28.3: main Chat always opens on the main thread.
        // Builds before saved the last project chat as the chat to reopen, which put a project chat in main Chat;
        // that pref is repaired here (project chats now open only inside Projects, see ChatNav). Nothing is deleted.
        if (Settings.lastChatId.let { it.isNotEmpty() && it != Mind.MAIN }) Settings.lastChatId = Mind.MAIN
        show(mainSession())
        // One-time, idempotent import of every old chat into the journal (chat files are never changed).
        io.launch {
            val d = Mind.db() ?: return@launch
            // v0.45.0 (#1): the chat list is only built when the import still has to run (it used to be built on every start).
            try { Mind.migrate(d, { ChatStore.list().map { it.id } }, { ChatStore.load(it) }, emptyList()) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: Mind.migrated ChatStore.list.map it.id C", e) }
        }
        // v0.42.0 Cloud runs: replies that finished while Blaze was closed or offline land in their chat, now and
        // whenever the phone gets a connection again.
        pickUpCloudRuns()
        try {
            app.getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { pickUpCloudRuns() }
            })
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: app.getSystemServiceandroid.net.Connecti", e) }
    }

    /** Chats that already showed the "couldn't save" line (once each, until the app restarts). */
    private val saveWarned = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private val pickingUp = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * v0.42.0: follows every saved Cloud run that no live turn owns to its end and puts the reply in its chat (with the
     * usual notification when Blaze isn't on screen). A run that was waiting for the phone is stopped with one plain line.
     * A chat that is busy with a new turn is left for the next pick-up.
     */
    fun pickUpCloudRuns() {
        if (!pickingUp.compareAndSet(false, true)) return
        io.launch {
            try {
                val c = CloudRuns.client()
                for (e in c.store.all()) {
                    if (e.run in CloudRuns.owned) continue
                    val s = kotlinx.coroutines.withContext(Dispatchers.Main) { load(e.chat) }
                    if (s == null) { c.cancel(e.run); continue }
                    if (s.busy) continue
                    val r = c.resume(e)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        // v0.45.0 (#12): the chat may have left memory (or been reopened) while the run was followed: use the live copy.
                        val ls = load(e.chat) ?: s
                        when (r) {
                            is CloudRuns.Resumed.Reply -> ls.cloudReply(r.text)
                            is CloudRuns.Resumed.Interrupted -> { ls.add(Role.ERROR, CloudRuns.CUT_SHORT); ls.persist() }
                            is CloudRuns.Resumed.Failed -> { ls.add(Role.ERROR, r.line); ls.persist() }
                            is CloudRuns.Resumed.Later -> Unit
                        }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Throwable) {
            } finally { pickingUp.set(false); settleTasks() }
        }
    }

    /** Ack-and-background: true while any chat's turn (an acknowledged task included) is still running. */
    fun anyTaskRunning(): Boolean = sessions.values.any { it.job?.isActive == true }

    private val settling = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Ack-and-background: settles acknowledged tasks left from before a restart ([TaskKeeper.Ledger]). One that got its
     * reply is crossed off; one whose Cloud run is still being followed waits; any other gets one honest line (and the
     * text-style notification), never a "done".
     */
    fun settleTasks() {
        if (!::app.isInitialized || !settling.compareAndSet(false, true)) return
        scope.launch {
            try {
                val led = TaskKeeper.ledger(app)
                val pending = try { CloudRuns.client().store.all().map { it.chat }.toSet() } catch (_: Throwable) { emptySet() }
                for (t in led.all()) {
                    val s = load(t.chat)
                    if (s == null) { led.remove(t.chat, t.user); continue }
                    if (s.job?.isActive == true) continue
                    val after = s.msgs.dropWhile { it.id != t.user }.drop(1)
                    val answered = after.any { (it.role == Role.BLAZE && it.meta != Ack.META) || it.role == Role.ERROR }
                    when (TaskKeeper.verdict(t, answered, t.chat in pending, System.currentTimeMillis())) {
                        TaskKeeper.Verdict.WAIT -> Unit
                        TaskKeeper.Verdict.REPORT -> { s.add(Role.ERROR, TaskKeeper.stoppedLine(t.said)); s.persist(); s.announce(); led.remove(t.chat, t.user) }
                        else -> led.remove(t.chat, t.user)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Throwable) {
            } finally { settling.set(false) }
        }
    }

    /** The one main conversation (id "main"), live or from disk, created the first time. */
    private fun mainSession(): Session {
        val s = load(Mind.MAIN) ?: Session(Mind.MAIN)
        s.title = "Blaze"; s.titleSource = Titles.MANUAL; s.keep = true
        return s
    }

    /** True for the main conversation or a project thread; everything else is archived (read-only). */
    fun isLive(id: String): Boolean = id == Mind.MAIN || Projects.of(id) != null

    private fun newId() = UUID.randomUUID().toString().take(12)

    /** The application context (for code outside the agent that needs one, e.g. Blaze Link sends). */
    fun appContext(): Context = app

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.blaze.assistant.device.CrashLog.guard)
    private val ioLock = kotlinx.coroutines.sync.Mutex()

    /** Reads chat files in the background so the UI never waits on disk. */
    fun refreshChats() { io.launch { val l = ChatStore.list(); _chats.value = l } }

    // ---------- v0.24.4: private chat ----------
    private val _private = MutableStateFlow(false)
    /** True while the visible chat is a private one. */
    val privateChat: StateFlow<Boolean> = _private
    /** The thread to go back to when the private chat closes. */
    @Volatile private var beforePrivate: String = Mind.MAIN

    /** Opens a new private chat (memory only). The chat you were in is saved and stays as it was. */
    fun enterPrivate() {
        if (cur.isPrivate) return
        cur.persist()
        beforePrivate = cur.id
        val s = Session(PrivateChat.PREFIX + newId())
        s.title = "Private"; s.titleSource = Titles.MANUAL
        s.add(Role.STEP, "Private chat \u00b7 nothing here is saved or learned, and it disappears when you leave")
        show(s)
    }

    /** Closes the private chat (stops it if it's working) and throws it away. Nothing was ever saved. */
    fun leavePrivate() {
        val p = cur
        if (!p.isPrivate) return
        p.stop()
        p.msgs = emptyList()
        sessions.remove(p.id)
        val back = beforePrivate
        val s = (if (back != Mind.MAIN && !PrivateChat.isPrivateId(back)) load(back) else null) ?: mainSession()
        show(s)
        sessions.remove(p.id)
    }

    /** A card drawn by the app (e.g. the update card), marked by its meta. Not sent to the model. */
    fun card(text: String, meta: String) {
        val s = cur
        val id = s.add(Role.BLAZE, text)
        s.msgs = s.msgs.map { if (it.id == id) it.copy(meta = meta) else it }
    }

    /** v0.24.4: true when the visible chat already has a card with this meta (one update card at a time). */
    fun hasCard(meta: String): Boolean = cur.msgs.any { it.meta == meta }

    /** v0.24.4: removes app cards with this meta from every open chat (the update card when it finishes). */
    fun removeCard(meta: String) {
        val all = (sessions.values + cur).toSet()
        all.forEach { s -> if (s.msgs.any { it.meta == meta }) { s.msgs = s.msgs.filterNot { it.meta == meta }; try { s.persist() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: s.persist", e) } } }
    }

    /** A line from the app itself (command results, help). Shown, not sent to the model. */
    fun note(text: String, asReply: Boolean = false) { cur.note(text, asReply) }

    /** The whole visible chat as plain text. */
    fun transcript(): String = cur.msgs.filter { it.role != Role.STEP }.joinToString("\n\n") {
        (if (it.role == Role.USER) "You: " else "Blaze: ") + it.text }

    /** Retry the newest answer. */
    fun retryLast() { cur.msgs.lastOrNull { it.role == Role.BLAZE || it.role == Role.ERROR }?.let { retry(it.id) } }

    /** [untrusted]: where outside content in [text] came from (Magic Hand's screen text), see ConfirmGate. */
    fun send(text: String, byVoice: Boolean = false, attachments: List<Attachment> = emptyList(), untrusted: String? = null) {
        // v0.45.8: the reply's start is timed from here (the chat screen's Send already marked it; voice and others mark now).
        StartTrace.tap()
        // "Shut down", "stop", "be quiet"… are handled on-device, even mid-task.
        val self = if (attachments.isEmpty()) SelfControl.match(text) else null
        if (self != null) {
            val s = cur
            com.blaze.assistant.device.Haptics.tick(app)
            if (self != "quiet") s.selfTurn(text.trim(), SelfControl.run(app, self), byVoice)
            else SelfControl.run(app, self)
            return
        }
        // Time, date, battery: answered on the phone instantly.
        if (attachments.isEmpty() && _question.value == null) {
            SelfControl.quick(app, text)?.let { r -> com.blaze.assistant.device.Haptics.tick(app); cur.selfTurn(text.trim(), r, byVoice, "⚡ Instant"); return }
        }
        // v0.45.6: Blaze Music transport and Blaze's own screens run in-app first (no model, no approval, no screen control).
        if (attachments.isEmpty() && _question.value == null && untrusted == null) {
            InAppRoute.match(text)?.let { r ->
                val s = cur
                com.blaze.assistant.device.Haptics.tick(app)
                when (r) {
                    is InAppRoute.Route.Music -> scope.launch {
                        val reply = try { com.blaze.assistant.mediaapps.MusicTools.transport(app, r.action) }
                            catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: in-app music", e); "Couldn't reach Blaze Music: " + (e.message ?: "unknown error") + "." }
                        s.selfTurn(text.trim(), reply, byVoice, "♫ Blaze Music")
                    }
                    is InAppRoute.Route.View -> {
                        val v = com.blaze.assistant.ui.Views.find(r.id)
                        if (v != null) com.blaze.assistant.ui.Views.open(v.id, if (v.id == "design") (try { Designs.current()?.path } catch (_: Exception) { null }).orEmpty() else "")
                        s.selfTurn(text.trim(), if (v != null) "Opened ${v.name}." else "Couldn't find that screen.", byVoice, "⚡ Blaze itself")
                    }
                }
                return
            }
        }
        // Typing or speaking while Blaze waits on a question answers it.
        if (_question.value != null && text.isNotBlank()) { answerQuestion(text.trim()); return }
        // v0.28.x Blaze Camera: "scan this document", "read this sign", "look at this", "escanea este documento"… open the
        // camera in that mode at once (no model guess); the capture comes back into this chat as the answer (fromCamera).
        if (attachments.isEmpty() && untrusted == null && cur.job?.isActive != true && !cur.waiting) {
            CameraIntent.match(text)?.let { mode ->
                val s = cur
                if (com.blaze.assistant.device.BlazeCamera.open(app, mode, com.blaze.assistant.device.CameraModes.Origin.REQUEST, s.id, text.trim())) {
                    com.blaze.assistant.device.Haptics.tick(app)
                    s.selfTurn(text.trim(), com.blaze.assistant.device.CameraModes.openingLine(mode), byVoice, "\uD83D\uDCF7 Blaze Camera")
                    return
                }
            }
        }
        // v0.30.6 rapid messages: the composer never blocks. Typed messages may open a short burst (one turn for messages
        // sent a moment apart); while a turn runs they join it at its next step. Voice and Magic Hand start at once.
        typingText = false   // the composer was just emptied by this send
        val s = cur
        s.send(text, byVoice, attachments, untrusted = untrusted, burst = !byVoice && untrusted == null && s.agentId == null)
    }

    /**
     * v0.28.x Blaze Camera: a capture (photo / document / text read / scanned code) goes into the chat that asked for it as
     * the next message, so Blaze answers about it in that turn's thread. Falls back to the chat on screen.
     */
    fun fromCamera(chatId: String?, text: String, attachments: List<Attachment>) {
        scope.launch {
            val s = chatId?.takeIf { it.isNotBlank() }?.let { sessions[it] ?: load(it) } ?: cur
            sessions[s.id] = s
            if (s !== cur) show(s)
            if (s.busy) s.job?.join()
            s.send(text, attachments = attachments)
        }
    }
    fun retry(messageId: Long) = cur.retry(messageId)

    /** v0.45.4: chats waiting on sign-in (their Sign in card ids). */
    internal val signInHolds = SignInGate.Holds()

    /** v0.45.4: signed in: each held chat whose Sign in card is still the newest message runs its turn again (the card goes). */
    private fun sendHeld() {
        for (h in signInHolds.takeAll()) {
            val s = sessions[h.chatId] ?: continue
            val last = s.msgs.lastOrNull()
            if (!SignInGate.stillWaiting(last?.id, last?.meta, h.cardId, s.busy)) continue
            s.retry(h.cardId)   // keeps the user's message, drops the card, starts the turn
            s.persist()
        }
    }
    fun stop() = cur.stop()

    /** Runs a model call and gives up (null) only if no streamed chunk arrives for [limitMs]. */
    /** v0.43.0: one companion missed the @all time limit ([Duo.PARALLEL_LIMIT_MS]). */
    class DuoLate(val who: Duo.Who) : Exception("${who.display} didn't answer in time.")

    /**
     * v0.45.0 (#3, #5): the guarded call runs as a child of the caller (Stop cancels it, and LlmClient closes its socket)
     * with its own CallState, so "silent" means silent for THIS call: another chat streaming no longer keeps a hung call
     * alive. Its usage, route and timing are handed to the turn when it ends.
     */
    internal suspend fun idleGuard(limitMs: Long, block: suspend () -> JSONObject): JSONObject? = kotlinx.coroutines.supervisorScope {
        val start = System.currentTimeMillis()
        val st = CallState.current().fork()
        val d = async(Dispatchers.IO + st.element()) { block() }
        try {
            var silent = false
            while (!d.isCompleted) {
                kotlinx.coroutines.delay(500)
                if (System.currentTimeMillis() - maxOf(start, st.chunkAt.get()) > limitMs) { silent = true; break }
            }
            if (silent) { d.cancel(); null } else d.await()
        } finally { if (!d.isCompleted) d.cancel(); st.publish() }
    }
    fun stopAll() = sessions.values.forEach { it.stop() }

    /** A background failure becomes an error line in the chat instead of a crash. */
    fun reportError(text: String) {
        val s = cur
        s.add(Role.ERROR, text)
        try { s.mood = Mood.IDLE } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: s.mood Mood.IDLE", e) }
        try { s.persist() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: s.persist", e) }
    }

    /** Open a chat. If it's still working in the background you see it live. */
    fun open(id: String) { load(id)?.let { show(it) } }

    /**
     * v0.28.3: put [id] on screen for the shell (ChatNav.visibleChat): the main thread on the Chat tab, or a project
     * chat inside Projects. A project chat that has no messages yet (just made with + New chat) is created fresh.
     * A private chat on screen is never thrown away by a switch back to main (leaving it has its own confirm).
     */
    fun showChat(id: String) {
        if (id.isBlank() || id == cur.id) return
        if (id == Mind.MAIN) { if (!cur.isPrivate) show(mainSession()); return }
        if (cur.isPrivate) return
        cur.persist()
        show(load(id) ?: Session(id))
    }

    /** v0.28.3: a new, empty project chat's id (it joins [projectId] with its first message; see ChatScreen.send). */
    fun newChatId(): String = newId()

    /** v0.28.3: a deep link / notification asked for this chat: only the main thread or a project chat open. */
    fun openFromLink(id: String) { if (id.isNotBlank() && isLive(id)) open(id) }

    /** v0.45.0 (#2): a chat file turned into messages (P5 #26: the parser now lives in ChatJson, outside Agent). */
    internal fun parseChat(o: JSONObject): ParsedChat = ChatJson.parse(o)

    /** v0.45.0 (#2): the main chat, read and parsed on a background thread from the first line of app start. */
    @Volatile private var mainPreload: java.util.concurrent.FutureTask<Pair<String, ParsedChat?>>? = null

    /**
     * v0.45.0 (#2): starts reading the main conversation on a background thread while the other ~40 subsystems start,
     * so Agent.init only waits for whatever is left of it (usually nothing). The file's stamp is checked when it is used:
     * if anything wrote the file in between, it is read again (never an older copy).
     */
    fun preload(ctx: Context) {
        try {
            ChatStore.init(ctx.applicationContext)
            val t = java.util.concurrent.FutureTask {
                val st = ChatStore.stamp(Mind.MAIN)
                st to (ChatStore.load(Mind.MAIN)?.let { o -> try { parseChat(o) } catch (_: Exception) { null } })
            }
            mainPreload = t
            Thread(t, "blaze-preload").apply { priority = Thread.NORM_PRIORITY + 1 }.start()
        } catch (_: Throwable) { mainPreload = null }
    }

    private fun takePreload(id: String): ParsedChat? {
        if (id != Mind.MAIN) return null
        val t = mainPreload ?: return null
        mainPreload = null
        return try {
            val (st, p) = t.get(20, java.util.concurrent.TimeUnit.SECONDS)
            if (p != null && st == ChatStore.stamp(id)) p else null
        } catch (_: Throwable) { null }
    }

    /** A chat's session: the live one if it's open or working, else read from disk. */
    private fun load(id: String): Session? {
        sessions[id]?.let { return it }
        val p = takePreload(id) ?: (ChatStore.load(id) ?: return null).let { o -> parseChat(o) }
        val o = p.o
        val s = Session(id)
        s.title = o.optString("title"); s.pinned = o.optBoolean("pinned"); s.summary = o.optString("summary")
        s.lcSummary = o.optString("lc_summary"); s.lcUpto = o.optInt("lc_upto"); s.ctxFrom = o.optInt("ctx_from")
        if (o.has("meter_in")) s.meter = Meter(o.optInt("meter_in"), o.optInt("meter_out"), o.optInt("meter_limit", 32_768))
        s.titleSource = o.optString("title_source"); s.titleTurns = o.optInt("title_turns"); s.keep = o.optBoolean("keep")
        s.clearedAt = o.optLong(com.blaze.assistant.connect.SyncMerge.CLEARED)
        s.resume = StopResume.fromJson(o.optJSONObject("resume"))   // v0.45.0 (#38)
        o.optJSONArray("tips_pending")?.let { a -> for (i in 0 until a.length()) s.unseenTips += a.getString(i) }
        val h = o.optJSONArray("history") ?: JSONArray()
        for (i in 0 until h.length()) s.history.put(h.get(i))
        s.historyMark.putAll(p.marks)
        ids.set(maxOf(ids.get(), p.maxId + 1))
        s.loadRooms(o.optJSONObject("rooms"))   // v0.32.0
        s.msgs = p.msgs
        return s
    }

    /**
     * Send [text] into chat [chatId] (or a new chat) without switching the screen, wait for the
     * answer and return (chatId, reply). Used by the dashboard.
     */
    /** [untrusted]: where the text came from when it isn't the user on this phone (ConfirmGate), e.g. the Wi-Fi dashboard. */
    suspend fun askIn(chatId: String?, text: String, untrusted: String? = null): Pair<String, String> {
        val s = chatId?.let { load(it) } ?: Session(newId()).also { it.title = "Dashboard · " + text.take(30) }
        sessions[s.id] = s
        if (s.busy) s.job?.join()
        val before = s.msgs.size
        kotlinx.coroutines.withContext(Dispatchers.Main) { s.send(text, untrusted = untrusted) }
        s.job?.join()
        val reply = s.msgs.drop(before).lastOrNull { it.role == Role.BLAZE || it.role == Role.ERROR }?.text.orEmpty()
        return s.id to reply
    }

    /**
     * v0.45.13 phone self-test (PhoneQa): one real turn in a fresh chat titled [title]; returns the chat id, the reply
     * bubble (its trace and receipt hold model, route, request ids, timings and every tool with its verdict) and every
     * message the turn added. The chat is kept (it's the evidence) unless [keep] is false.
     */
    suspend fun askFresh(title: String, text: String, keep: Boolean = true): Triple<String, ChatMessage?, List<ChatMessage>> {
        val s = Session(newId()).also { it.title = title }
        sessions[s.id] = s
        kotlinx.coroutines.withContext(Dispatchers.Main) { s.send(text) }
        s.job?.join()
        val added = s.msgs.toList()
        val reply = added.lastOrNull { it.role == Role.BLAZE || it.role == Role.ERROR }
        s.persist()
        if (!keep) { ChatStore.delete(s.id); sessions.remove(s.id) }
        refreshChats()
        return Triple(s.id, reply, added)
    }

    /**
     * In a project: start a new thread there (the one you leave keeps working if it was busy). Everywhere else
     * (one mind): a fresh context window on the main conversation; nothing is deleted and Blaze looks things up.
     */
    fun clear(inProject: Boolean = Projects.of(cur.id) != null) {
        cur.persist()
        if (inProject) { show(Session(newId())); return }
        val m = if (cur.id == Mind.MAIN) cur else mainSession()
        show(m)
        m.freshContext(null)
    }

    /** v0.24.0 /clear: the last clear of the visible thread, kept in memory only so the snackbar can undo it. */
    @Volatile private var lastClear: ClearScreen.Snapshot? = null

    /**
     * /clear: empties the visible thread so the chat starts empty. Nothing is forgotten: the history stays in the
     * chat file, the journal (search_chat) and the long-chat index; Blaze starts a new context window there.
     */
    fun clearScreen(): ClearScreen.Result {
        val s = cur
        if (s.busy) return ClearScreen.Result.BUSY
        if (ClearScreen.isEmpty(s.msgs)) return ClearScreen.Result.EMPTY
        lastClear = ClearScreen.Snapshot(s.id, s.msgs, s.ctxFrom, s.lcSummary, s.lcUpto, s.history.length(), System.currentTimeMillis())
        clearedBefore = s.clearedAt
        s.clearedAt = System.currentTimeMillis()
        val w = ClearScreen.window(s.history.length())
        s.ctxFrom = w.ctxFrom; s.lcSummary = w.lcSummary; s.lcUpto = w.lcUpto
        s.msgs = emptyList()
        s.persist(force = true)
        return ClearScreen.Result.CLEARED
    }

    /** v0.40.0: the chat's clear time before the last /clear, put back by Undo. */
    @Volatile private var clearedBefore = 0L

    /**
     * v0.40.0 cloud sync: chat [id] changed on another phone (CloudSync merged it into the file). An open, idle chat is
     * read again so the new messages show; a chat that is working keeps going and the next round merges again.
     */
    fun reloadFromSync(id: String) {
        val s = sessions[id]
        if (s == null || s.isPrivate) { refreshChats(); return }
        // v0.45.0 (#37): merged into the open chat (SyncMergeIn), busy or not; nothing in memory is replaced or lost.
        io.launch(Dispatchers.Main) {
            val live = sessions[id] ?: run { refreshChats(); return@launch }
            val p = kotlinx.coroutines.withContext(Dispatchers.IO) {
                ChatStore.load(id)?.let { o -> try { parseChat(o) } catch (_: Exception) { null } }
            } ?: return@launch
            if (sessions[id] !== live) return@launch
            val r = SyncMergeIn.merge(live.msgs, p.msgs, live.clearedAt, p.o.optLong(com.blaze.assistant.connect.SyncMerge.CLEARED),
                live.busy || live.waiting, live.pendingReacts()) { ids.getAndIncrement() }
            if (live.title.isEmpty()) p.o.optString("title").takeIf { it.isNotEmpty() }?.let { live.title = it }
            if (r != null) { live.msgs = r.msgs; live.clearedAt = r.clearedAt; live.persist(force = true) }
            refreshChats()
        }
    }

    /**
     * v0.45.0 reactions: toggles [emoji] by the user on message [msgId] of the chat on screen (also a meeting-room
     * message). Saved with the chat (cheap: ChatIndex + per-message JSON cache) and synced as its own small item.
     * 👍 / 👎 on Blaze's reply also teach Blaze (TurnFeedback). Returns false when there is no such message.
     */
    fun react(msgId: Long, emoji: String, by: String = "user"): Boolean {
        val s = cur
        val m = s.msgs.firstOrNull { it.id == msgId }
        if (m != null) {
            if (m.role == Role.STEP) return false
            val next = Reaction.toggle(m.reactions, emoji, by)
            s.msgs = s.msgs.map { if (it.id == msgId) it.copy(reactions = next) else it }
            s.markReacted(m.gid)
            s.persist()
            if (by == "user" && m.role == Role.BLAZE && !s.isPrivate) {
                val added = next.any { it.by == by && it.emoji == emoji }
                try { TurnFeedback.react(m.gid, next.firstOrNull { it.by == by && (it.emoji == Reaction.UP || it.emoji == Reaction.DOWN) }?.emoji) }
                catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: TurnFeedback.react", e) }
                if (added && (emoji == Reaction.UP || emoji == Reaction.DOWN)) s.noteReaction(msgId, emoji)
            }
            return true
        }
        for ((room, list) in s.rooms) {
            val rm = list.firstOrNull { it.id == msgId } ?: continue
            val next = Reaction.toggle(rm.reactions, emoji, by)
            s.rooms = s.rooms + (room to list.map { if (it.id == msgId) it.copy(reactions = next) else it })
            s.persist()
            return true
        }
        return false
    }

    /** True while the last /clear can still be undone (same chat, nothing sent since, within a few seconds). */
    fun canUndoClear(): Boolean = ClearScreen.canUndo(lastClear, cur.id, cur.msgs, cur.history.length(), System.currentTimeMillis())

    /** Puts the cleared messages and context window back. */
    fun undoClear(): Boolean {
        val snap = lastClear ?: return false
        val s = cur
        if (!ClearScreen.canUndo(snap, s.id, s.msgs, s.history.length(), System.currentTimeMillis())) return false
        s.ctxFrom = snap.ctxFrom; s.lcSummary = snap.lcSummary; s.lcUpto = snap.lcUpto
        s.msgs = ClearScreen.restored(snap, s.msgs)
        s.clearedAt = clearedBefore
        lastClear = null
        s.persist(force = true)
        return true
    }

    /** The model's context window (Settings › context_tokens, else a guess from its name; 0 = unknown). */
    fun ctxWindow(): Int = LongChat.contextWindow(Settings.model, Settings.str("context_tokens", "0").toIntOrNull() ?: 0)
    /** When older turns fold into the rolling summary. */
    fun budget(): Int = LongChat.budget(ctxWindow())

    fun deleteChat(id: String) {
        io.launch { try { LongChat.db()?.forget(id) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: LongChat.db.forgetid", e) } }
        sessions[id]?.stop()
        sessions.remove(id)
        ChatStore.delete(id)
        // v0.28.3: a deleted project chat leaves its project, and the screen falls back to the main thread (never a blank chat).
        if (Projects.of(id) != null) Projects.assign(id, "")
        if (id == cur.id) show(mainSession())
        refreshChats()
    }

    fun renameChat(id: String, t: String) {
        ChatStore.rename(id, t)
        sessions[id]?.let { it.title = t.trim().ifEmpty { "Chat" }; it.titleSource = Titles.MANUAL }
        refreshChats()
    }
    fun pinChat(id: String, p: Boolean) { ChatStore.setPinned(id, p); sessions[id]?.pinned = p; refreshChats() }

    /**
     * A reply typed into a notification's Reply box. It goes back to the conversation that notification
     * came from, so Blaze knows what it said: the chat that posted it ([chatId]; heads-ups live in the
     * Heads-ups chat since v0.8.0), or, for an old heads-up with no chat, a new chat that opens with
     * Blaze's [context] message. The model is always told which message is being answered.
     */
    fun replyFromNotification(chatId: String?, context: String?, text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val ctx = context?.takeIf { it.isNotBlank() }
        scope.launch {
            val s = chatId?.takeIf { it.isNotBlank() }?.let { load(it) } ?: run {
                if (ctx == null) { send(t); return@launch }
                Session(newId()).also { n ->
                    n.title = "Heads-up \u00b7 " + ctx.lineSequence().first().take(30)
                    n.add(Role.BLAZE, ctx)
                }
            }
            sessions[s.id] = s
            if (!inForeground && s !== cur) show(s)
            if (s.busy) s.job?.join()
            s.send(t, notificationContext = ctx)
        }
    }

    /** The one chat every heads-up (tips, briefs, routine and job alerts) is kept in, so replies have context. */
    private fun headsUpChatId(): String {
        val saved = Settings.str("headsup_chat")
        if (saved.isNotBlank() && (sessions.containsKey(saved) || ChatStore.load(saved) != null)) return saved
        return newId().also { Settings.setStr("headsup_chat", it) }
    }

    /**
     * Records a heads-up Blaze posted as a notification (v0.8.0): it shows in the Heads-ups chat and the
     * model is told about it with the next message there, so replying from the notification (or later in
     * the app) continues that conversation instead of starting a blank one. Returns the chat's id.
     */
    fun recordHeadsUp(text: String): String {
        val id = headsUpChatId()
        val stamp = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        scope.launch {
            val s = load(id) ?: Session(id).also { it.title = "Heads-ups"; it.titleSource = Titles.MANUAL }
            s.keep = true
            sessions[s.id] = s
            val mid = s.add(Role.BLAZE, text)
            s.addMeta(mid, "Heads-up \u00b7 $stamp")
            s.unseenTips += "$stamp: $text"
            while (s.unseenTips.size > 20) s.unseenTips.removeAt(0)
            s.persist()
            refreshChats()
        }
        return id
    }

    /** The one-tap suggestion on a heads-up: runs in the Heads-ups chat, with the heads-up as context. */
    fun doFromHeadsUp(chatId: String?, context: String?, prompt: String) = replyFromNotification(chatId ?: headsUpChatId(), context, prompt)

    /** A scheduled routine: runs in its own background chat and reports by notification. */
    fun runRoutine(name: String, prompt: String) {
        val s = Session(newId())
        s.title = "⟳ $name"
        sessions[s.id] = s
        s.send("[Scheduled routine \"$name\". The user isn't watching; do it and reply in 1-3 short sentences that read well as a notification.]\n$prompt")
    }

    /** Run a prompt in a hidden background chat and get the final answer (proactive checks, dashboard). */
    suspend fun ask(title: String, prompt: String, visible: Boolean = false): String {
        val s = Session(newId())
        s.title = title
        sessions[s.id] = s
        s.send(prompt)
        s.job?.join()
        val out = s.msgs.lastOrNull { it.role == Role.BLAZE || it.role == Role.ERROR }?.text.orEmpty()
        if (!visible) { ChatStore.delete(s.id); sessions.remove(s.id); refreshChats() }
        return out
    }

    /** Messages of any chat (for the dashboard). */
    fun messagesOf(id: String): List<ChatMessage>? = load(id)?.msgs

    fun lastStep(): String = cur.status.ifBlank { cur.msgs.lastOrNull { it.role == Role.STEP }?.text ?: Friendly.between(null) }

    // ---------- Background agents ----------

    /** An agent's own chat: live if it's working, else read back from disk. */
    fun agentSession(bg: BgAgents.Bg): Session {
        val s = sessions[bg.chat] ?: load(bg.chat) ?: Session(bg.chat)
        s.agentId = bg.id; s.agentPlan = bg.plan; s.ctxTag = BgAgents.Tag(bg.id)
        s.specialistId = bg.specialist.ifBlank { null }
        s.title = "\u2699 ${bg.id} · " + (BgAgents.spec(bg)?.let { "${it.name} · " } ?: "") + bg.title
        sessions[s.id] = s
        return s
    }

    fun stopAgent(bg: BgAgents.Bg) { sessions[bg.chat]?.stop() }

    /** 0.45.19: stops the turn running in chat [id] (Blaze Phone's picture-in-picture Stop). False when nothing ran there. */
    fun stopChat(id: String): Boolean {
        val s = sessions[id] ?: return false
        val was = s.mood != Mood.IDLE
        s.stop(); refreshBusy()
        return was
    }

    /** "/agent <task>": the card goes in the open chat, and the result wakes it. */
    fun startAgentFromUser(task: String) {
        val s = cur
        val bg = BgAgents.start(task, "", s.id)
        s.add(Role.USER, "/agent $task")
        s.history.put(JSONObject().put("role", "user").put("content", "[I handed this to background agent ${bg.id}: $task]"))
        s.history.put(JSONObject().put("role", "assistant").put("content", "Started background agent ${bg.id}."))
        val mid = s.add(Role.BLAZE, "Background agent ${bg.id}: ${bg.title}")
        s.msgs = s.msgs.map { if (it.id == mid) it.copy(meta = BgAgents.CARD + bg.id) else it }
        s.persist()
    }

    /** An agent finished: its result wakes the chat that started it (queued behind a turn in progress). */
    fun agentFinished(bg: BgAgents.Bg) {
        refreshChats()
        val p = sessions[bg.parent] ?: load(bg.parent) ?: return
        sessions[p.id] = p
        // v0.26.2: a specialist's result posts as it lands: its card (now showing the result) moves to the bottom of the chat,
        // then the main Blaze writes a short follow-up from it.
        if (bg.specialist.isNotBlank()) {
            p.msgs = Dispatch.moveToEnd(p.msgs, { it.meta == BgAgents.CARD + bg.id }, { it.copy(at = System.currentTimeMillis()) })
            p.persist()
        }
        p.wake(BgAgents.resultMessage(bg))
    }

    // ---------- Specialists (v0.26.2) ----------

    /**
     * One review call: the specialist's draft against its playbook's Self-check. v0.45.2 (VerifyPolicy (d)): on the quick
     * route with a time limit; a call that times out or fails gives checked=false (the result says it wasn't checked),
     * never a silent pass. Strict checks: the usual route with a longer limit.
     */
    suspend fun reviewDraft(spec: Specialists.Spec, playbook: String, task: String, draft: String): Playbooks.Review {
        val strict = VerifyPolicy.strict()
        val msgs = Playbooks.reviewMessages(spec.name, playbook, task, draft)
        val r = try {
            if (strict) kotlinx.coroutines.withTimeoutOrNull(45_000) { LlmClient.complete(msgs, null, null, null, LlmClient.Lane.BACKGROUND) }
            else kotlinx.coroutines.withContext(CallState().also { it.lite = true }.element()) {
                kotlinx.coroutines.withTimeoutOrNull(VerifyPolicy.MODEL_CHECK_MS) { LlmClient.complete(msgs, null, null, null, LlmClient.Lane.BACKGROUND) } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        return if (r == null) Playbooks.Review(true, emptyList(), checked = false) else Playbooks.parseReview(r.optString("content"))
    }

    private val playbookLock = kotlinx.coroutines.sync.Mutex()

    /**
     * A custom specialist without a playbook gets one written by the model, validated like create_specialist's
     * (a few tries), and saved with it. Returns the playbook ("" if none passed; it's tried again next time).
     */
    suspend fun ensurePlaybook(spec: Specialists.Spec): String = playbookLock.withLock {
        Specialists.byId(spec.id)?.playbook?.takeIf { it.isNotBlank() }?.let { return@withLock it }
        val others = Specialists.builtIn.map { Playbooks.builtIn(it.id) } + Specialists.custom().filter { it.id != spec.id }.map { it.playbook }
        var problems = emptyList<String>(); var prev = ""
        repeat(Playbooks.MAX_TRIES) {
            val raw = try { LlmClient.complete(Playbooks.generateMessages(spec.name, spec.prompt, problems, prev), null, null, null, LlmClient.Lane.BACKGROUND).optString("content") }
                catch (e: CancellationException) { throw e } catch (_: Exception) { return@withLock "" }
            val pb = Playbooks.cleanGenerated(raw)
            val c = Playbooks.validate(pb, "${spec.name} ${spec.id} ${spec.prompt}", others)
            if (c.ok) {
                val now = Specialists.byId(spec.id) ?: spec
                if (now.custom) Specialists.upsert(now.copy(playbook = pb))
                return@withLock pb
            }
            problems = c.problems; prev = pb
        }
        ""
    }

    // ---------- Prompt ----------

    /** v0.45.5 (#7b): the phone's last location fix for get_weather when the model sent no lat/lon (null = none / no permission). */
    @Suppress("MissingPermission")
    private fun lastFix(): Pair<Double, Double>? = try {
        if (app.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) null
        else app.getSystemService(android.location.LocationManager::class.java)?.let { lm ->
            lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }.maxByOrNull { it.time } }
            ?.let { it.latitude to it.longitude }
    } catch (_: Throwable) { null }

    private fun personaText(): String =
        if (Settings.personaId == Personas.CUSTOM && Settings.customPersona.isNotBlank())
            "You are Blaze, an AI assistant on the user's Android phone. " + Settings.customPersona
        else Personas.byId(Settings.personaId).prompt

    /** v0.45.1: the live tail of the system prompt, kept per chat for the steps of one turn (PromptParts.LiveSnap). */
    private val liveSnap = PromptParts.LiveSnap<List<PromptParts.Part>>()
    /** The newest system prompt's parts (sizes for the speed log and the token ledger). */
    @Volatile internal var lastPromptParts: List<PromptParts.Part> = emptyList()

    private fun systemPrompt(userText: String, skipSkills: Set<String> = emptySet(), chatId: String = _chatId.value, fullAware: Boolean = true,
                             groups: Set<String> = emptySet(), viewRouted: Boolean = false, turnKey: Long = 0L): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val name = Settings.greetName()
        val control = Settings.phoneControl
        val persona = if (Settings.personaId == Personas.CUSTOM && Settings.customPersona.isNotBlank())
            "You are Blaze, an AI assistant on the user's Android phone. " + Settings.customPersona
        else Personas.byId(Settings.personaId).prompt
        // v0.45.0 (#13, #16): Simple users get no terminal / coding content; turn-specific blocks only when the turn needs them.
        val prof = PromptProfile.plan(SimpleMode.simple, userText, groups, viewRouted = viewRouted)
        val memBlock = Memory.promptBlock(userText)
        val parts = PromptParts.Builder()
        // v0.45.1 speed: named parts with a stability tier (PromptParts). The text of every part is what it always was;
        // only the order changes (stable → per-turn → live), and the live tail is kept for the steps of one turn.
        val liveFns = LinkedHashMap<String, () -> String>()
        fun tail(n: String, f: () -> String) { liveFns[n] = f; parts.live(n, PromptParts.SLOT) }
        parts.parts.clear()
        parts.apply {
            // v0.45.5 (#10): with the default personality the shared persona is the FIRST system message (SharedPersona);
            // this one is the app's own context. A personality the user picked keeps its words here.
            if (SharedPersona.applies(Settings.personaId) && SharedPersona.get() != null) s("persona", SharedPersona.contextLine(name, "")) else s("persona", "$persona ")
            if (name != null) s("name", "The user's name is $name. ")
            s("names", GreetName.RULE)   // v0.45.9
            // v0.45.7 Settings › Language: one stable line in this (second) message; the persona message stays byte-stable.
            s("lang", ReplyLanguage.line(ReplyLanguage.current()))
            if (prof.simple) s("simple", PromptProfile.SIMPLE_RULE)
            slow("connectors", com.blaze.assistant.connect.Connectors.promptBlock())
            s("talk", "\nHOW YOU TALK: short, warm and sure of yourself, like a sharp friend texting back. Lead with the answer. One or two sentences unless they ask for more. Never say \"let me know if there's anything else\", \"feel free to\", \"I'd be happy to\" or similar filler, and never ask the user to share personal details for your memory. ")
            s("style", AnswerStyle.RULE)
            s("format", AnswerStyle.FORMAT)   // v0.31.1: HTML tags, tables never in code fences, rich blocks open in glass windows
            s("self", "\nYou are Blaze and you can control yourself with blaze_self (close/go quiet, stop, orb, wake word, read-aloud, model, personality, settings, your setup and crash log). When the user wants help setting you up, or asks what you can do or what's installed, call blaze_self setup_check and guide them one step at a time. If the user asks you to shut down, close, go away or stop, do that with blaze_self, never a phone setting. ")
            s("remember", "\nWhen the user tells you something lasting about themselves (names, preferences, plans, decisions), save it quietly with the remember tool. Never save greetings, moods, small talk, thanks or questions. ")
            s("again", "\nIf the user says \"again\", \"do it again\" or \"same for X\", repeat your last actions from this chat (adapted to X) without asking. ")
            // v0.30.6 rapid messages: [m7] ids on user messages, [re:m7] markers on replies (static, so it stays in the cached prefix).
            s("rapid", RapidMessages.PROMPT_RULE)
            s("ask", "\nIf you're unsure what the user wants, or there are several options (accounts, contacts, items, times), don't guess and don't end your turn: call ask_user with short options, then carry on with the answer. ")
            if (control) {
                s("control", """
BACKGROUND FIRST: never take over the user's screen when a background tool can do it. Web questions → web_search/fetch_page; sites you must click, fill or log in to → browser_open/browser_click/browser_type (Blaze's own background browser; browser_show only when the user must see or take over). When a page needs the user's email, phone, username + password, API key or a one-time code, call browser_secure_input (a secure form in the chat; you get {{secret:name}} placeholders, never the values) instead of asking in chat or showing the browser. If a page says the account can't sign in with a password, don't loop on the password: offer Continue with Google, a passkey or the live browser with ask_user. Browser tools never need phone control. Messaging someone → message_contact (never open WhatsApp on screen yourself); a picture Blaze makes → generate_image, then message_contact with image="last"; several contacts match → ask_user with the exact names; only a result starting "Sent to" means it was sent. Email → gmail_search/gmail_read. Calendar → gcal_events or calendar tools. Texts, calls, contacts → the SMS/call/contact tools. Notifications → recent_notifications. Music → spotify tools. ${PromptProfile.filesLine(prof.simple)} Only if no background tool exists or it isn't connected, use the screen; when a background tool isn't connected, offer to connect it (blaze_self open "settings connections") instead of silently driving the app.
You can control the phone. SPEED MATTERS: the user is watching.
${Orient.RULE}
- For anything in an app (not Settings: use open_settings), call run_plan ONCE with the whole script, using the visible words on buttons and fields as targets (e.g. open_app YouTube → tap "Search" → type "lofi hip hop" submit → tap the first result's title). You know how common apps look; you don't need to read the screen first. Add optional:true for popups you might need to dismiss, and expect:"..." when a step must lead somewhere.
- Element numbers stay the same while the screen stays the same; after an action you often get only the changes ("Same screen… Changed or new"), so keep using the numbers you have.
- For flows with predictable pop-ups (sign-in, cookie banners, "Not now"), set watchers with watch (or a preset) so the phone reacts instantly instead of spending your steps.
- run_plan only comes back when it's done or stuck. If stuck, it returns the screen with element numbers: fix it with a short new run_plan, or tap/type_text by number.
- Use single tap/type_text/scroll only for one-off fixes. If read_screen shows little (games, maps, video, custom apps), call look_at_screen.
- Prefer direct tools when they fit: search_in_app for "play/search X on YouTube/Spotify/Play Store/Maps", set_alarm, set_timer, dial, compose_sms. open_url only for real web addresses; web_search to look something up yourself.
${FastActions.RULE.trimStart('\n')}
- Before anything irreversible or that spends money or contacts someone (sending, posting, paying, deleting, calling), confirm first unless the user clearly told you to do exactly that.
When you finish, say what you did in one short sentence.
""".trimIndent())
            } else {
                s("control", "Phone control is turned off in settings, so you can only chat.")
            }
            s("pim", "\nYou can also read and send texts, check calls, read and add calendar events, look up contacts, toggle the flashlight and volume, and schedule routines with your tools. Sending a text or placing a call asks the user for approval automatically.")
            if (Settings.followups) s("followups", "\nAt the very end of each final answer (never when calling tools), add one line exactly like <<follow-ups: first | second | third>> with three short next requests (max 6 words each) written as the user would say them. Skip it for routines.")
            s("load_tools", "\nIf a tool you need isn't in your list, call load_tools with its group, then carry on.")
            s("weather", GatewayWeather.SYSTEM_LINE)   // v0.45.5 (#7b): one source for weather, chat and Home alike
            // v0.45.9: Blaze is on a phone and can save files; no capability refusals for registered tools.
            s("phone_save", SaveFile.RULE + ToolTruth.RULE)
            // v0.28.1: models sometimes wrote "[/find_tool need=…]" as the reply instead of calling the tool.
            s("leak", "\n" + ToolLeak.PROMPT_RULE)
            // v0.43.0: Blaze told a user the Linux desktop and Dillo were running when nothing listened on its port.
            s("verify", "\nVERIFY BEFORE CLAIMING: say something is running, open, started, sent or done only when a tool result in this turn confirms it. " +
                "For the Linux desktop only a desktop result starting VERIFIED counts; FAILED or NOT running means tell the user plainly it isn't working and offer desktop action=repair. " +
                "An app you asked the desktop to open counts as open only after a screenshot shows its window. Earlier messages saying it worked are not proof.")
            // v0.28.4 blaze-docs: one static line + the repo URL; the docs themselves are looked up (blaze_docs_search), never pasted here.
            s("docs", BlazeDocs.PROMPT_LINE)
            s("ids", "\nEverything you create that keeps running (alarms, timers, routines, triggers, calendar events, jobs) gets an ID like R4. To change, stop or delete one, call processes to find the ID, then process(id, action). Only use action=details when the user asks for specifics.")
            // v0.24.3: the core skills' pinned rules (voice, task workflow, views, specialists, workflows, coding), every turn,
            // read from the shipped assets and static, so they stay in the cached prefix.
            if (CoreSkills.loader == null) CoreSkills.loader = {
                CoreSkills.NAMES.mapNotNull { n -> runCatching { n to app.assets.open("skills/$n/SKILL.md").bufferedReader().use { it.readText() } }.getOrNull() }.toMap()
            }
            turn("core_skills", CoreSkills.promptBlock(prof.core))
            // v0.23.0: the VIEWS block. v0.45.0 (#16): in full only when a view is routed this turn; else one line.
            turn("views", if (prof.views) ViewRouter.PROMPT else PromptProfile.viewsShort(ViewRouter.specs.map { it.name }))
            // v0.44.0: every tool result carries [verified=…]; success may only be claimed on verified=true (ToolVerify).
            s("tool_verify", ToolVerify.PROMPT_RULE)
            // v0.24.9 rich replies / v0.26.1 visual answers (diagrams, charts, compare, tools, choices, forms; picked
            // automatically). Static text, so it stays in the cached prefix; left out only when Visual answers is off.
            if (Settings.bool("rich_replies", true)) s("rich", com.blaze.assistant.ui.RichReplies.PROMPT)
            // Speed plan step 2: everything above stays identical turn after turn, so providers can
            // reuse their cached prefix. Everything that changes (clock, memory, device) goes last.
            // v0.45.1: the tiers finish that job (PromptParts.assemble puts every LIVE part after the rest).
            tail("time") { "\n\nCURRENT LOCAL TIME: $now (${java.util.TimeZone.getDefault().id}). This is exact: answer any time, date or day question straight from it, never say you can't see the time. " }
            tail("memory") { memBlock }
            s("learn", Learn.rules(Settings.autoLearn))
            tail("device") { com.blaze.assistant.device.DeviceStatus.promptLine(app) }
            // v0.37.0: what's playing / on screen / just happened (changing tail only). v0.42.0: the full block only when the
            // turn can use it (phone, screen or media tools, or words like "this song"); otherwise a 2–3 line summary.
            tail("awareness") { if (fullAware) Awareness.promptBlock() else Awareness.summaryBlock() }
            s("phone_state", PhoneState.RULE)
            // v0.45.1: "in the line above" became "in the phone line near the end": this rule now sits in the stable part.
            s("know", "\nKNOW BEFORE YOU ASK: never ask the user something you can find out yourself. Their phone make, model, Android version and skin are in the phone line near the end of this prompt; " +
                "check installed apps with list_apps (e.g. whether Secure Folder, Termux or a bank app is there), where they are with get_location, settings and screen state with your phone tools, time and battery from 'Phone now', " +
                "and facts about them from memory. Answer for THIS phone specifically (exact menu names for its make and Android version), not a list of options for every brand. " +
                "Ask only for things that live in their head: preferences, choices, consent.")
            // v0.45.0 (#13, #16): TERMINAL / MAKE THINGS in full only on coding or content-making turns in Advanced;
            // Simple gets one plain line and no paths or commands.
            if (prof.terminal) {
                turn("terminal", "\nTERMINAL: check terminal_status before assuming a tool exists. Write files with file_write/file_edit (never echo or heredocs), read with file_read, find with file_search. " +
                    "Run programs with run_code (it installs Python/Node and packages itself). Anything over ~30 s or a server goes in background (run_shell background=true), then terminal_jobs id=N / job_input; preview web servers with preview_server; repeat scripts on a schedule with schedule_command. " +
                    "Use the git tool for repos (already signed in to their GitHub). Call APIs with http_request. On an error, read it, fix the cause and retry (up to 3 times) before telling the user; a [hint] line tells you what to try. Give the user results with export_file.")
                turn("make", "\nMAKE THINGS IN THE BACKGROUND, NOT ON THE SCREEN: you CAN create images (generate_image, free) and you have a real Linux terminal with Python. " +
                    "For anything that produces or processes content (pictures, beats and music, charts, documents, conversions, edits, calculations, data, downloads), do it in the terminal with run_code instead of opening apps and tapping. " +
                    "Beats: run_code python `import sys; sys.path.insert(0,'/root/phone/blaze_lib'); from beats import make; print(make(style='trap', bpm=140, bars=8, key='A', out='/root/phone/beat.wav'))` (styles trap, boombap, afro, house, lofi; vary seed). " +
                    "Save outputs under /root/phone/ (that is ~ for the file tools): new pictures, audio and video files there are added to the user's Gallery and shown in the chat automatically. " +
                    "Only drive the screen when the task truly lives inside another app (sending in WhatsApp, a setting with no tool). Never tell the user you can't make images or music. " +
                    "DO IT, DON'T DIRECT: when something needs a permission or a setting, YOU fix it: ask with the tool that triggers Android's own pop-up, open the exact screen with blaze_self open, and when phone control is on, tap through it yourself (find the switch, flip it, come back). " +
                    "Never hand the user a numbered list of where to tap in Settings; only if you tried and truly can't, say the ONE thing they must tap. Never ask them to type terminal commands: run them yourself. " +
                    "For terminal work use Blaze's own Linux (run_code / run_shell) first; Termux only if the user asks for it. " +
                    "generate_image is ONLY for art and photos; charts, graphs, tables, PDFs and documents are always made with run_code. If the terminal fails, say so plainly; never substitute an AI picture.")
            } else turn("make", if (prof.simple) PromptProfile.MAKE_SIMPLE else PromptProfile.MAKE_SHORT + PromptProfile.TERMINAL_SHORT)
            tail("setup") { SelfSetup.promptLine(app) }
            // v0.26.2: learned skills are in the one skills lookup below (SkillMatch.lookupBlock), not a block of their own.
            tail("processes") { Processes.countLine().takeIf { it.isNotBlank() }?.let { "\nTracked now: $it (IDs via processes)." }.orEmpty() }
            slow("desktop", com.blaze.assistant.link.DesktopLink.promptLine())
            slow("about", SelfKnowledge.promptBlock(app)); slow("project", Projects.promptLine(chatId))
            s("calls", "\nCALLS: \"let's get on a call\", \"call me\" or \"let's talk\" with no person named means a voice call with YOU: call_control start_blaze_call. Never phone a contact unless they name who. " +
                "Loudspeaker, mute, hang up, answer, hold, call volume and keypad presses during any call go through call_control, never the Settings app.")
            if (com.blaze.assistant.voice.Voice.inCall) tail("in_call") { " You are on a voice call with the user right now (Blaze's call screen): \"I can't hear you\" means call_control speaker on or louder." }
            s("autonomy", Autonomy.promptBlock()); tail("goals") { Autonomy.promptLine() }
            // v0.30.4: no skills lookup for a plain device / settings action (it only ever pulled in unrelated skills).
            if (!FastActions.plainDevice(userText)) turn("skills", SkillMatch.lookupBlock(userText, skipSkills))
            // v0.30.5: tasks the user has Blaze do often, with no skill yet, come first for a skill.
            if (Settings.autoLearn) tail("frequent") { SkillGuard.Freq.promptLine(SkillGuard.Freq.load()) }
            // v0.45.9: the assistant rules and how to use the [context] block (static). The block itself goes at the very
            // end of this message (requestMessages), after the Halo identity and turn hints (Site persona v1.0.1).
            s("assistant", Duo.ASSISTANT_RULES)
            s("context_rule", TurnContext.RULE)
        }
        val stable = Settings.bool("prompt_stable_order", true)
        // The live tail: built once per turn (and again when what it is built from changes, or after 5 min).
        fun buildLive() = liveFns.map { (n, f) -> PromptParts.Part(n, PromptParts.Tier.LIVE, f()) }
        val livePs = if (stable && turnKey != 0L)
            liveSnap.get(chatId, listOf(turnKey, fullAware, userText, memBlock.hashCode(), com.blaze.assistant.voice.Voice.inCall, liveFns.keys.toList())) { buildLive() }
        else buildLive()
        val all = PromptParts.withLive(parts.parts.toList(), livePs)
        lastPromptParts = all
        return PromptParts.assemble(all, stable)
    }

    private suspend fun tools(): JSONArray {
        val t = JSONArray()
        Memory.tools().forEach { t.put(it) }
        if (Mind.ready) Mind.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.device.DeviceStatus.tool())
        t.put(com.blaze.assistant.mediaapps.store.JamendoSource.tool())
        t.put(SelfControl.tool())
        t.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "ask_user")
            .put("description", "Pause and ask the user a question with 2-4 short tap-able options (an 'own answer' choice is added automatically). Use when a real choice is theirs (which account, which contact, which item) or the request is ambiguous. Never use it for facts you can check yourself (phone model, Android version, installed apps, settings, battery). The task waits right where it is and continues with the answer.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("question", JSONObject().put("type", "string"))
                .put("options", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))))
                .put("required", JSONArray(listOf("question", "options"))))))
        Web.tools().forEach { t.put(it) }
        com.blaze.assistant.media.Media.tools().forEach { t.put(it) }
        Processes.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.phone.Watchers.tool())
        SkillPacks.tools().forEach { t.put(it) }
        com.blaze.assistant.phone.AndroidControl.tools().forEach { t.put(it) }
        t.put(Autonomy.findToolDef()); t.put(Autonomy.goalTool()); t.put(Autonomy.permissionTool())
        com.blaze.assistant.proactive.Triggers.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.phone.Ussd.tool())
        t.put(com.blaze.assistant.phone.Messaging.tool())
        com.blaze.assistant.browser.BrowserTools.definitions().forEach { t.put(it) }
        com.blaze.assistant.browser.phone.PhoneBrowserTools.definitions().forEach { t.put(it) }   // 0.45.19 Blaze Phone browser
        if (Settings.bool("terminal_tools", true)) com.blaze.assistant.terminal.Terminal.tools(app).forEach { t.put(it) }
        if (Settings.bool("terminal_tools", true)) com.blaze.assistant.terminal.DevTools.tools().forEach { t.put(it) }
        if (Settings.bool("terminal_tools", true)) t.put(com.blaze.assistant.terminal.Git.tool())
        com.blaze.assistant.phone.PimEdit.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.device.SelfCheck.tool())
        t.put(com.blaze.assistant.phone.Location.tool())
        t.put(GatewayWeather.tool())   // v0.45.5 (#7b)
        t.put(SaveFile.tool())   // v0.45.9: save the last code block to Downloads
        if (Settings.bool("specialists_on", true)) { t.put(Specialists.tool()); t.put(Specialists.createTool()) }
        Learning.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.update.Updater.tool())
        t.put(com.blaze.assistant.ui.Views.tool())
        t.put(SelfKnowledge.tool())
        t.put(Awareness.tool())
        t.put(com.blaze.assistant.proactive.Jarvis.tool())
        t.put(com.blaze.assistant.bank.BankMode.tool())   // v0.43.0 Bank mode
        t.put(com.blaze.assistant.proactive.FollowUps.tool())
        BlazeDocs.tools().forEach { t.put(it) }
        // v0.28.5: a project's full instructions / brief / notes as lookups; stripped from every chat that isn't in a project.
        ProjectDocs.tools().forEach { t.put(it) }
        // v0.29.0 Home board: Blaze adds / changes / removes / lists the user's widgets.
        com.blaze.assistant.home.HomeTools.tools().forEach { t.put(it) }
        t.put(BlazeRouter.tool())
        t.put(com.blaze.assistant.desktop.LinuxDesktop.tool())
        // v0.39.0: the hands-free desktop loop, and every / command for Blaze itself.
        t.put(com.blaze.assistant.desktop.DesktopAgent.tool())
        t.put(CommandRegistry.tool())
        if (Settings.longChat || Mind.ready) LongChat.tools().forEach { t.put(it) }
        t.put(Flashcards.tool())
        if (Designs.templateLoader == null) Designs.templateLoader = { app.assets.open("skills/three-d-design/assets/mockup.html").bufferedReader().use { it.readText() } }
        t.put(Designs.tool())
        t.put(Seller.tool())
        if (Settings.screenMemory) t.put(ScreenMemory.tool())
        if (com.blaze.assistant.notify.NotificationBrain.instance != null) com.blaze.assistant.notify.NotificationBrain.tools().forEach { t.put(it) }
        if (com.blaze.assistant.notify.NotificationBrain.instance != null) com.blaze.assistant.notify.Conversations.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.life.LifeLogs.tool())
        t.put(com.blaze.assistant.mediaapps.MediaFetch.tool())
        t.put(com.blaze.assistant.mediaapps.VideoTools.tool())   // v0.34.0
        t.put(com.blaze.assistant.mediaapps.MusicTools.tool())   // v0.34.0
        t.put(com.blaze.assistant.media.GalleryOps.tool())   // v0.34.0
        t.put(com.blaze.assistant.device.CameraMathTool.tool())   // v0.34.0
        t.put(com.blaze.assistant.notify.MoneyRadar.tool())
        t.put(com.blaze.assistant.phone.SafeWalk.tool())
        t.put(com.blaze.assistant.device.PhoneHealth.tool())
        t.put(TimeCapsule.tool())
        t.put(HaloStudio.tool())
        t.put(Marketplace.tool())
        t.put(com.blaze.assistant.mediaapps.BeatLab.tool())
        t.put(com.blaze.assistant.mediaapps.ClipStudio.tool())
        t.put(DuoWorkspace.tool())
        t.put(com.blaze.assistant.phone.CallCopilot.tool())
        t.put(com.blaze.assistant.mediaapps.CoverArt.tool())
        t.put(com.blaze.assistant.voice.ReadAloud.tool())
        t.put(com.blaze.assistant.notify.GroupBrain.tool())
        t.put(com.blaze.assistant.phone.CallControl.tool())
        if (Settings.bool("bg_agents", true)) BgAgents.tools().forEach { t.put(it) }
        if (com.blaze.assistant.link.DesktopLink.paired) t.put(com.blaze.assistant.link.DesktopLink.tool())
        com.blaze.assistant.connect.Connectors.tools().forEach { t.put(it) }
        try { com.blaze.assistant.connect.Mcp.tools().forEach { t.put(it) } } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: Mcp.tools.fo", e) }
        if (Settings.phoneControl) t.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "run_plan")
            .put("description", "FASTEST way to do anything in an app or settings page. Give the whole script at once; Blaze runs it on-device in a few seconds, finding things by their visible text (no element numbers, no need to read the screen first), checking each step on the phone, and only comes back when done or stuck. Steps can open a settings page by intent (do=open_settings page=… app=…).")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("steps", JSONObject().put("type", "array")
                .put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("do", JSONObject().put("type", "string").put("enum", JSONArray(listOf("open_app", "open_settings", "tap", "long_press", "type", "enter", "scroll", "back", "home", "wait", "search_in_app"))))
                    .put("app", JSONObject().put("type", "string").put("description", "open_app / search_in_app: app name; open_settings: the app whose page it is (optional)"))
                    .put("page", JSONObject().put("type", "string").put("description", "open_settings: the page, same names as the open_settings tool (open_by_default, app_notifications, wifi, …)"))
                    .put("want", JSONObject().put("type", "string").put("enum", JSONArray(listOf("on", "off"))).put("description", "tap on a switch: the state it should end in; skipped when it already is"))
                    .put("target", JSONObject().put("type", "string").put("description", "tap/type: the visible text, label or hint of the element, e.g. Search, Send, Message"))
                    .put("text", JSONObject().put("type", "string").put("description", "type: what to type"))
                    .put("submit", JSONObject().put("type", "boolean").put("description", "type: press Enter/Search after"))
                    .put("query", JSONObject().put("type", "string")).put("play", JSONObject().put("type", "boolean"))
                    .put("direction", JSONObject().put("type", "string")).put("seconds", JSONObject().put("type", "number"))
                    .put("expect", JSONObject().put("type", "string").put("description", "optional text that should be visible after this step"))
                    .put("optional", JSONObject().put("type", "boolean").put("description", "skip instead of stopping if it fails (e.g. dismissing a popup)")))
                    .put("required", JSONArray(listOf("do")))))).put("required", JSONArray(listOf("steps"))))))
        if (Settings.phoneControl) t.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "do_actions")
            .put("description", "Run several phone actions in a row in one go (e.g. tap the search box, type, tap a result), using element numbers from the latest screen. Stops at the first one that fails. The new screen is returned at the end.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("steps", JSONObject().put("type", "array")
                .put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("tap", "type_text", "scroll", "press_button", "wait"))))
                    .put("element", JSONObject().put("type", "integer")).put("text", JSONObject().put("type", "string"))
                    .put("direction", JSONObject().put("type", "string")).put("button", JSONObject().put("type", "string"))
                    .put("seconds", JSONObject().put("type", "number")))
                    .put("required", JSONArray(listOf("action")))))).put("required", JSONArray(listOf("steps"))))))
        com.blaze.assistant.routine.Routines.tools().forEach { t.put(it) }
        com.blaze.assistant.phone.Skills.definitions().forEach { t.put(it) }
        if (Settings.phoneControl) { val p = PhoneTools.definitions(); for (i in 0 until p.length()) t.put(p.get(i)) }
        // v0.30.4: straight to a settings page by intent (in no tool group, so it's always on hand).
        if (Settings.phoneControl) t.put(com.blaze.assistant.phone.SettingsJump.tool())
        // v0.45.9: one schema per name, valid names and object parameters (gateway 0.8.2 checks calls against this list).
        return ToolTruth.clean(ToolGuide.apply(t)).also { try { BlazeDocs.noteTools(it) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: BlazeDocs.noteToolsit", e) } }
    }

    /** v0.28.4: the real tool list (for the blaze-docs tool reference, indexed at startup). */
    suspend fun toolCatalog(): JSONArray = tools()

    /** True while Blaze's own screen is visible. */
    @Volatile var inForeground = false

    /** True while the assistant overlay or floating orb is open (replies show there, not as notifications). */
    @Volatile var overlayOpen = false



    /**
     * One chat with its own agent. Several can run at once: leaving a chat (or starting a new
     * one) never stops it or sends its answer to the wrong place.
     */
    class Session(val id: String) {
        /** v0.24.4: a private chat (memory only: nothing saved or learned). */
        val isPrivate: Boolean get() = PrivateChat.isPrivateId(id)
        /** Set when this chat is a background agent's (B1…). */
        var agentId: String? = null
        /** v0.26.2: the specialist this background agent runs as (its prompt, playbook and tools). */
        var specialistId: String? = null
        val specialist: Specialists.Spec? get() = specialistId?.let { try { Specialists.byId(it) } catch (_: Throwable) { null } }
        /** create_specialist tries per id this session. */
        val createTries = HashMap<String, Int>()
        var agentPlan = false
        var ctxTag: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
        /** Instructions the user (or the lead) sent while this chat works; read at the next step. */
        val steer = java.util.concurrent.ConcurrentLinkedQueue<String>()
        /** v0.28.2: what untrusted content this turn has read (ConfirmGate); rides in the turn's coroutine context. */
        val taint = ConfirmGate.Taint()
        /** Agent results waiting for the turn in progress to end. */
        val wakes = java.util.concurrent.ConcurrentLinkedQueue<String>()
        /**
         * v0.30.6 rapid messages: a message the model hasn't seen yet, sent while this chat worked or inside an open burst
         * (its bubble is already in the chat). [raw] = an on-device answer made meanwhile, kept as one user-side history line.
         */
        private class Inbound(val id: Long, val text: String, val attachments: List<Attachment>, val byVoice: Boolean,
                              val untrusted: String?, val raw: JSONObject? = null)
        /** v0.30.6: what arrived for this chat, in order. Read at the running turn's next step, or it starts the next turn. */
        private val inbox = java.util.concurrent.ConcurrentLinkedQueue<Inbound>()
        /** v0.30.6: the open burst (messages a moment apart become one turn); null when none. */
        private var burstJob: Job? = null
        @Volatile private var burstAt = 0L
        /** v0.30.6: messages are waiting for a turn (an open burst, or sent just as a turn ended). */
        val waiting: Boolean get() = burstJob?.isActive == true || inbox.isNotEmpty()
        /** v0.30.6: the first user message of the turn in progress (or the last one); 0 = unknown (a chat just loaded). */
        @Volatile var turnFrom = 0L
        /** v0.42.0: this turn's prompt carries the full Awareness block (else the short summary). */
        @Volatile var awareFull = true
        private var _focusV: Set<Long> = emptySet()
        /** v0.30.6: the user messages this chat is working on right now (see [Agent.focus]). */
        var focus: Set<Long>
            get() = _focusV
            set(v) { _focusV = v; if (this === cur) _focus.value = v }
        /** Speed plan step 11: text of the current step already handed to TTS while it streamed. */
        var spokenPrefix = ""
        var streamedVoice = false
        /** v0.41.0: the round being streamed has no tools (chat lane), so its text is the reply. */
        var streamNoTools = false
        /** Something of the round being streamed was already spoken. */
        var roundSpoke = false
        var title = ""
        /** "" (first prompt), "auto" (named by Titles) or "manual" (renamed by you: never retitled). */
        var titleSource = ""
        /** User turns when the title was last evaluated. */
        var titleTurns = 0
        var pinned = false
        /** One-line summary written with the title (used by search_past_chats). */
        var summary = ""
        /** Long-chat rolling summary and the first history index it doesn't cover (v0.15.0). */
        var lcSummary = ""
        var lcUpto = 0
        /** One mind (v0.18.0): first history index of this context window ("Fresh start" moves it; nothing is deleted). */
        var ctxFrom = 0
        /** The ladder rung the last request used (diagnostics, tests). */
        @Volatile var lastRung = Mind.Rung.RECENT
        /** v0.26.0 auto-compaction: tokens of the last request's system prompt + tools (an estimate until the first call). */
        @Volatile var overheadTok = 6_000
        /** A provider refused this chat as too long: send the summary + tail from now on (not saved; a refusal sets it again). */
        @Volatile var squeezed = false
        private var _meterV: Meter? = null
        var meter: Meter?
            get() = _meterV
            set(v) { _meterV = v; if (this === cur) _meter.value = v }
        /** Heads-ups posted into this chat as notifications that the model hasn't been told about yet. */
        val unseenTips = java.util.Collections.synchronizedList(mutableListOf<String>())
        /** Saved even before the user writes in it (the Heads-ups chat). */
        var keep = false
        /** v0.40.0 cloud sync: when this chat was last cleared with /clear (ms); synced copies hide what came before. */
        var clearedAt = 0L
        /** Tool names called this turn, in order (for Learn.decide). */
        val turnTools = java.util.Collections.synchronizedList(mutableListOf<String>())
        /** v0.33.3: the ```apps card of this turn's last list_apps (added to the reply when it says it shows a list). */
        @Volatile var turnAppsCard: String? = null
        /** v0.23.0: the view this turn must use (keyword classifier) and the skills looked up for it. */
        @Volatile var turnView: ViewRouter.Spec? = null
        @Volatile var turnSkills: SkillMatch.Result = SkillMatch.Result.NONE
        @Volatile var turnSpecs: List<Specialists.Spec> = emptyList()
        /** v0.23.2: tool calls that succeeded this turn (the claim guard checks replies against them). */
        val turnOk = java.util.Collections.synchronizedList(mutableListOf<ClaimGuard.Call>())
        /** v0.44.0: every tool call this turn with its verified status (ClaimCheck reviews the final reply against it). */
        val turnDone = java.util.Collections.synchronizedList(mutableListOf<ClaimCheck.Done>())
        /** v0.45.13: the alarm / timer results of this turn (TimeEcho checks the reply's time against them). */
        val turnSetResults = java.util.Collections.synchronizedList(mutableListOf<String>())
        /** v0.45.13: GoalCheck's one "opening is only step 1" nudge per turn. */
        @Volatile var goalNudged = false
        /** v0.45.0 (#13, #16): the tool groups on for the call being built (PromptProfile picks the prompt blocks from them). */
        @Volatile var promptGroups: Set<String> = emptySet()
        /** v0.45.1: this turn's start (0 = none): the system prompt's live tail is kept per turn (PromptParts.LiveSnap). */
        @Volatile var promptTurn: Long = 0L
        /** v0.45.0 "What I did": every tool call this turn with its args summary, result, status and evidence (Receipt). */
        val turnLog: MutableList<Receipt.Entry> get() = turnBook.log
        /** v0.45.2: this turn's receipts + verification stats (a background check flips the reply's receipt through it). */
        @Volatile var turnBook = VerifyBook()
        /** v0.45.0 recovery: risky one-shot actions that already ran this turn (never repeated). */
        val oneShot = OneShot()
        /** v0.45.3: no tool spins doing nothing (LoopGuard). */
        val loopGuard = LoopGuard()
        /** v0.45.0 (#18): the request whose phone plan the last turn saved (dropped when the next message says it was wrong). */
        @Volatile var lastPlanKey: String? = null
        /** v0.45.0 context: this task's goal and constraints, pinned into every step's system prompt (GoalPin). */
        @Volatile var turnGoal: GoalPin.Goal? = null
        /** v0.45.0 (#12): last time this chat was used (sent, answered, saved), for trimming idle chats from memory. */
        @Volatile var touchedAt: Long = System.currentTimeMillis()
        /** v0.45.0 (#12): the newest save of this chat has reached the disk (no write queued or failed). */
        val saved: Boolean get() = savedSeq == persistSeq.get()
        @Volatile private var savedSeq = 0L
        /** v0.45.0 reactions: messages whose reactions changed here and aren't saved yet (sync must not undo them). */
        private val reacted = java.util.Collections.synchronizedMap(HashMap<String, Long>())
        fun markReacted(gid: String) { reacted[gid] = persistSeq.get() + 1 }
        fun pendingReacts(): Set<String> = synchronized(reacted) { reacted.filterValues { it > savedSeq }.keys.toSet() }
        /** v0.45.0 reactions (R4): told to the model once, on the next turn, as a hidden line ("[The user reacted 👎 to m7]"). */
        private val reactNotes = java.util.Collections.synchronizedList(ArrayList<String>())
        fun noteReaction(msgId: Long, emoji: String) { reactNotes += "[The user reacted $emoji to m$msgId]"; while (reactNotes.size > 4) reactNotes.removeAt(0) }
        fun takeReactionNotes(): String = synchronized(reactNotes) { reactNotes.joinToString("\n").also { reactNotes.clear() } }
        /** v0.45.0 (#5): the streaming gate of the step running now; Stop closes it so nothing lands after "Stopped.". */
        @Volatile var liveGate: StreamGate? = null
        /** v0.45.0 (#38): the task a Stop (or an error) interrupted, saved with the chat until it's resumed or replaced. */
        @Volatile var resume: StopResume.Pending? = null
        /** v0.45.0 (#38): set by startTurn for "Continue where you stopped": the interrupted task's goal, pinned again. */
        @Volatile var resumeGoal: GoalPin.Goal? = null
        /** v0.23.2: the last turn made, changed or opened a design (so a short edit next is a follow-on). */
        @Volatile var lastTurnDesign = false
        /** v0.23.2: the follow-on hint for this turn ("" = none). */
        @Volatile var turnDesignNote = ""
        /** v0.45.2: this turn is about the Linux desktop (DesktopRoute): desktop tools on, the steering hint in the prompt. */
        @Volatile var turnDesk = false
        /** The text of the last [n] history messages (DesktopRoute: was the desktop just talked about?). */
        fun recentText(n: Int): String = buildString {
            for (i in maxOf(0, history.length() - n) until history.length()) (history.optJSONObject(i)?.opt("content") as? String)?.let { append(it.take(600)).append('\n') }
        }
        val history = JSONArray()
        val historyMark = HashMap<Long, Int>()
        val trace = JSONArray()
        /** v0.30.7: this turn's phone steps that worked (plan memory), and whether the turn touched the screen yet. */
        val planRec = TapLoop.Recorder()
        @Volatile var screenActed = false
        @Volatile var currentStep = 0
        var job: Job? = null

        private var _msgs: List<ChatMessage> = emptyList()
        var msgs: List<ChatMessage>
            get() = _msgs
            set(v) { _msgs = v; if (this === cur) _messages.value = v }
        private var _mood = Mood.IDLE
        var mood: Mood
            get() = _mood
            set(v) {
                _mood = v; if (this === cur) Agent._mood.value = v; refreshBusy()
                if (v == Mood.IDLE && streamId >= 0) streamId = -1L   // v0.31.0: a stopped / failed turn never leaves a live droplet
                if (v == Mood.IDLE) { status = ""; focus = emptySet() } else if (status.isBlank()) status = Friendly.between(null)
            }
        /** Ack-and-background (HQ 22 Oct): this turn's one ack ([Ack.Gate]); null between turns. */
        @Volatile var ackGate: Ack.Gate? = null
        private var _taskChip = ""
        var taskChip: String
            get() = _taskChip
            set(v) { _taskChip = v; if (this === cur) Agent._taskChip.value = v }
        private var _status = ""
        /** The plain-words status line for this chat (see [Agent.status]). */
        var status: String
            get() = _status
            set(v) { _status = v; if (this === cur) Agent._status.value = v }
        /** v0.31.0: the reply streaming in on this chat (-1 = none), mirrored to [Agent.streamingId] while on screen. */
        private var _streamId = -1L
        var streamId: Long
            get() = _streamId
            set(v) { _streamId = v; if (this === cur) Agent._streamingId.value = v }
        /** v0.26.1: times the reasoning for the "Thought it through · 4 s" line. */
        val thinkClock = ThinkingLabel.Clock()
        private var _thinking = ""
        var thinking: String
            get() = _thinking
            set(v) { if (v != _thinking) thinkClock.update(v); _thinking = v; if (this === cur) Agent._thinking.value = v }

        val busy get() = job?.isActive == true

        /** v0.31.2 Halo x Blaze: who speaks in the turn in progress (Halo on "@halo" turns and in duo lanes). */
        @Volatile var voice: Duo.Who = Duo.Who.BLAZE
        private var _duoLiveV: Set<Long> = emptySet()
        /** v0.31.2: duo replies streaming right now (mirrored to [Agent.duoLive] while on screen). */
        var duoLive: Set<Long>
            get() = _duoLiveV
            set(v) { _duoLiveV = v; if (this === cur) _duoLive.value = v }
        private fun speakerOf(who: Duo.Who) = if (who == Duo.Who.HALO) Duo.Who.HALO.id else ""
        private var _roomsV: Map<Long, List<ChatMessage>> = emptyMap()
        /** v0.32.0: meeting rooms in this chat (card id → messages); saved with the chat. */
        var rooms: Map<Long, List<ChatMessage>>
            get() = _roomsV
            set(v) { _roomsV = v; if (this === cur) _rooms.value = v }
        /** v0.32.0: while ≥ 0, step lines (tool use) go into this meeting room instead of the main chat. */
        @Volatile var roomSink = -1L
        private var _splitV = false
        var split: Boolean
            get() = _splitV
            set(v) { _splitV = v; if (this === cur) _split.value = v }
        private var _sideLiveV: Set<Long> = emptySet()
        var sideLive: Set<Long>
            get() = _sideLiveV
            set(v) { _sideLiveV = v; if (this === cur) _sideLive.value = v }
        /** v0.32.0 split: Halo's side lane while Blaze works. */
        private var sideJob: Job? = null
        val sideBusy: Boolean get() = sideJob?.isActive == true
        private val sideQueue = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, String>>()
        /** What Halo and you said on the side; joins the history once neither lane is running (never mid tool-call). */
        private val sideHist = ArrayList<JSONObject>()
        private val sideIds = java.util.Collections.synchronizedSet(HashSet<Long>())
        /** Shows Halo's tool steps as a hint in her bubble. */
        @Volatile private var sideStep: ((String) -> Unit)? = null
        /** v0.32.0: a solo Blaze turn is running its tool loop (Halo is free to take the conversation). */
        @Volatile var soloRunning = false
        /** v0.32.0: when the duo last handed the floor to the user in a CHAT (0 = not waiting); untagged replies go back to them. */
        @Volatile var duoChatOpenAt = 0L

        fun note(text: String, asReply: Boolean) { add(if (asReply) Role.BLAZE else Role.STEP, text) }

        /**
         * The request this turn is about. v0.30.6: when several messages make up the turn (a burst, or ones sent while it
         * worked) they're all of it, joined in order, so routing (skills, tools, the chat lane) sees the whole ask.
         */
        fun lastUserText(): String {
            val from = turnFrom
            if (from > 0) {
                val t = msgs.filter { it.role == Role.USER && it.id >= from && it.id !in sideIds }
                if (t.size > 1) return t.joinToString("\n") { it.text }
            }
            return msgs.lastOrNull { it.role == Role.USER && it.id !in sideIds }?.text.orEmpty()
        }

        /** v0.45.0 (#38): tidies the history after an interrupted turn and keeps the task (goal + receipts) to resume. */
        private fun keepForResume() {
            try {
                val t = StopResume.tidy(history)
                val done = synchronized(turnLog) { StopResume.doneLines(turnLog.toList()) }
                resume = StopResume.pending(turnGoal, done, t.interrupted)
            } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: val t StopResume.tidyhistory", e) }
        }

        fun stop() {
            if (this === cur) _confirm.value?.answer?.complete(false)
            // v0.30.6: Stop also closes an open burst. What was sent meanwhile stays in the conversation, but starts nothing.
            burstJob?.cancel(); burstJob = null
            settleInbox()
            dropFormOf(this)
            sideQueue.clear(); sideJob?.cancel(); sideJob = null   // v0.32.0
            liveGate?.close(); liveGate = null   // v0.45.0 (#5): nothing streamed after Stop reaches the chat
            job?.cancel()
            job = null
            mood = Mood.IDLE
        }

        /** v0.30.6: a waiting message as the model sees it ([midTurn] = it arrived while a turn ran). */
        private fun putInbound(m: Inbound, midTurn: Boolean, prefix: String = "") {
            m.raw?.let { history.put(it); return }
            historyMark[m.id] = history.length()
            taint.mark(m.untrusted ?: if (m.attachments.isNotEmpty()) ConfirmGate.SHARED_FILE else null)
            // v0.45.0 reactions (R4): 👍 / 👎 given since the last turn, told once as a hidden line (no turn is started for them).
            val rn = if (!midTurn && agentId == null) takeReactionNotes() else ""
            val said = (if (rn.isNotEmpty()) rn + "\n" else "") + prefix + (if (RapidMessages.shouldTag(agentId == null, m.text)) RapidMessages.label(m.id, m.text, midTurn) else m.text)
            val content: Any = if (m.attachments.isEmpty()) said else JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", said))
                m.attachments.forEach { a -> a.parts.forEach { put(it) } }
            }
            history.put(JSONObject().put("role", "user").put("content", content))
        }

        /** v0.30.6: Stop with messages waiting: they go into the history as plain user turns (nothing runs). */
        private fun settleInbox() {
            val left = generateSequence { inbox.poll() }.toList()
            if (left.isEmpty()) return
            left.forEach { putInbound(it, midTurn = false) }
            persist()
        }

        /** v0.30.6: at a step of the running turn, what arrived meanwhile joins before the next model call. */
        private fun takeInbox(midTurn: Boolean) {
            val got = generateSequence { inbox.poll() }.toList()
            if (got.isEmpty()) return
            got.forEach { putInbound(it, midTurn) }
            got.filter { it.raw == null }.map { it.id }.takeIf { it.isNotEmpty() }?.let { n -> focus = focus + n }
            persist()
        }

        /** v0.30.6: starts the next turn with everything waiting, unless a turn runs (it reads them at its next step) or a burst is open. */
        fun drain() {
            if (job?.isActive == true || burstJob?.isActive == true) return
            val items = generateSequence { inbox.poll() }.toList()
            if (items.isEmpty()) return
            if (items.all { it.raw != null }) { items.forEach { putInbound(it, midTurn = false) }; persist(); return }
            startTurn(items, null, ack = false)
        }
        /** The generic step just added for the running tool; a tool's own step line replaces its text. */
        var autoStepId = -1L

        /**
         * v0.28.4 (Luke's "Worked · 4 steps · 97m 45s" for 33 s of steps): when the turn in progress started. The work card
         * used to time a turn from the message before its steps, so a Retry or a background agent's wake ran the clock
         * from a message sent long before. The first step of each turn now carries this ([ChatMessage.startAt]).
         */
        @Volatile var turnAt = 0L
        @Volatile private var turnStamped = true

        fun beginTurn(at: Long = System.currentTimeMillis()) { turnAt = at; turnStamped = false }

        fun add(role: Role, text0: String, files: List<String> = emptyList()): Long {
            // v0.32.0: the model sometimes copies the history's [Blaze] / [Halo] tags into its narration; never shown.
            val text = if (role == Role.STEP || role == Role.BLAZE) Duo.clean(text0).ifEmpty { text0 } else text0
            if (role == Role.STEP && autoStepId >= 0 && msgs.lastOrNull()?.id == autoStepId) {
                val sid = autoStepId; autoStepId = -1L
                msgs = msgs.map { if (it.id == sid) it.copy(text = text) else it }
                if (mood != Mood.IDLE) status = text
                return sid
            }
            if (role == Role.STEP && sideFlag.get() == true) { sideStep?.invoke(text); return -1L }   // v0.32.0 Halo's side lane
            autoStepId = -1L
            val id = ids.getAndIncrement()
            // v0.32.0: tool steps of a meeting room show in the room, not the main chat.
            val sink = roomSink
            if (role == Role.STEP && sink >= 0) {
                rooms = rooms + (sink to (rooms[sink].orEmpty() + ChatMessage(id, Role.STEP, text, speaker = voice.id)))
                if (mood != Mood.IDLE) status = "${voice.display}: $text"
                return id
            }
            val start = if (role == Role.STEP && !turnStamped && turnAt > 0) { turnStamped = true; turnAt } else 0L
            msgs = msgs + ChatMessage(id, role, if (role == Role.ERROR) PlainError.of(text) else text, files, startAt = start,
                speaker = if (role == Role.BLAZE) speakerOf(voice) else "")
            if (role == Role.STEP && mood != Mood.IDLE) status = text
            if (role == Role.STEP && !inForeground && mood != Mood.IDLE) Notifier.working(if (currentStep > 0) "Step $currentStep · $text" else text)
            return id
        }

        /** A step with its exact call kept for the expanded view. */
        fun addStep(text: String, exact: String): Long {
            val id = add(Role.STEP, text)
            msgs = msgs.map { if (it.id == id) it.copy(meta = exact) else it }
            autoStepId = id
            return id
        }

        fun replace(id: Long, role: Role, text: String) {
            // v0.45.0 (#10): a streamed frame replaces the newest message: one array copy, not a mapped copy of every message.
            val cur0 = msgs
            val last = cur0.lastIndex
            if (last >= 0 && cur0[last].id == id) {
                msgs = ArrayList(cur0).also { it[last] = cur0[last].copy(role = role, text = text) }
                return
            }
            msgs = cur0.map { if (it.id == id) it.copy(role = role, text = text) else it }
        }

        /** Media made during this turn; shown under the final reply. */
        val pendingMedia = mutableListOf<String>()

        fun addReply(liveId: Long, raw: String, thinking: String, fu: List<String> = emptyList(), meta: String = "", receipt: String = "", how: String = "") {
            val media = synchronized(pendingMedia) { pendingMedia.toList().also { pendingMedia.clear() } }
            val p = ReplyParts.split(raw, final = true).let { if (it.followups.isEmpty() && fu.isNotEmpty()) it.copy(followups = fu) else it }
            val think = listOf(thinking, p.thinking).filter { it.isNotBlank() }.joinToString("\n\n")
            val tms = if (think.isNotBlank()) thinkClock.ms() else 0L
            // v0.30.6 rapid messages: "[re:m7] …" / "[re:m7,m8] …" say which message(s) each reply answers; separate answers
            // to unrelated messages become separate bubbles. The reasoning line goes on the first, chips, media and
            // follow-ups on the last. A reply with no marker is one bubble, as before.
            val known = msgs.filter { it.role == Role.USER }.map { it.id }.toSet()
            val parts = RapidMessages.split(p.text, known)
            parts.forEachIndexed { i, part ->
                // v0.26.2: never a bare "Done." (the loop stops empty answers before this; a canned turn always has text).
                val text = Duo.clean(part.text).ifEmpty { "I didn't get an answer that time. Tap Retry." }
                val isFirst = i == 0
                val isLast = i == parts.size - 1
                val th = if (isFirst) think else ""
                val ms = if (isFirst) tms else 0L
                val fu2 = if (isLast) p.followups else emptyList()
                val meta2 = if (isLast) meta else ""
                val media2 = if (isLast) media else emptyList()
                val rc2 = if (isLast) receipt else ""
                val tr2 = if (isLast) how else ""
                if (isFirst && liveId >= 0) msgs = msgs.map {
                    if (it.id == liveId) it.copy(role = Role.BLAZE, text = text, thinking = th, followups = fu2, meta = meta2, media = media2, thinkMs = ms, replyTo = part.refs, speaker = speakerOf(voice), receipt = rc2, trace = tr2) else it }
                else {
                    val id = ids.getAndIncrement()
                    msgs = msgs + ChatMessage(id, Role.BLAZE, text, thinking = th, followups = fu2, meta = meta2, media = media2, thinkMs = ms, replyTo = part.refs, speaker = speakerOf(voice), receipt = rc2, trace = tr2)
                }
            }
        }

        /** Adds a chip (e.g. "Memory updated") to a reply's footer. */
        /** v0.45.2: replace a reply's receipt (and its chips in the meta line) after a background check ended. */
        fun updateReceipt(id: Long, log: List<Receipt.Entry>) {
            val line = Receipt.line(log)
            msgs = msgs.map { m -> if (m.id != id) m else m.copy(receipt = Receipt.toJson(log),
                meta = (m.meta.split(" \u00b7 ").filter { p -> p.isNotBlank() && !Receipt.isChip(p) } + listOf(line).filter { it.isNotBlank() }).joinToString(" \u00b7 ")) }
            try { persist() } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: updateReceipt persist", e) }
        }

        /** v0.45.2: a background check ended: during the turn it updates what the final claim check reads; after it, the reply's receipt. */
        fun flipReceipt(book: VerifyBook, li: Int, di: Int, v: ToolVerify.Verdict) {
            synchronized(book.log) {
                if (li !in book.log.indices) return
                val after = book.msgId >= 0
                book.log[li] = VerifyPolicy.flip(book.log[li], v, after)
                if (!after && book === turnBook) synchronized(turnDone) { if (di in turnDone.indices) turnDone[di] = turnDone[di].copy(status = v.status, why = v.why) }
                if (after) updateReceipt(book.msgId, book.log.toList())
            }
        }

        /** v0.45.2: after Blaze worked in the Linux desktop, a "View desktop" chip in the chat (when it runs and isn't on screen). */
        fun offerDesktopView() {
            if (agentId != null) return
            try {
                if (com.blaze.assistant.desktop.LinuxDesktop.state.value.phase == com.blaze.assistant.desktop.DesktopPlan.Phase.RUNNING &&
                    com.blaze.assistant.ui.Views.showing.value != "linux") com.blaze.assistant.ui.Views.offer.value = com.blaze.assistant.ui.Views.Offer("linux")
            } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: offerDesktopView", e) }
        }

        fun addMeta(id: Long, chip: String) {
            msgs = msgs.map { if (it.id == id) it.copy(meta = listOf(it.meta, chip).filter { s -> s.isNotBlank() }.joinToString(" · ")) else it }
        }

        /** v0.32.0: the meeting rooms as saved ({"<card id>": [{id, role, text, speaker, at}]}). */
        private fun roomsJson(): JSONObject = JSONObject().apply {
            rooms.forEach { (rid, list) -> put(rid.toString(), JSONArray().apply { list.forEach { m ->
                put(JSONObject().put("id", m.id).put("role", m.role.name).put("text", m.text.take(40000)).put("speaker", m.speaker).put("at", m.at)
                    .put("gid", m.gid).apply { if (m.reactions.isNotEmpty()) put("reactions", Reaction.json(m.reactions)) }) } }) }
        }

        fun loadRooms(o: JSONObject?) {
            if (o == null) return
            val out = HashMap<Long, List<ChatMessage>>()
            o.keys().forEach { k -> val a = o.optJSONArray(k) ?: return@forEach
                Gid.fill(a)   // v0.45.0 (#37): rooms saved before global ids get deterministic ones
                out[k.toLongOrNull() ?: return@forEach] = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { m ->
                    try { ChatMessage(m.getLong("id"), Role.valueOf(m.getString("role")), m.optString("text"), speaker = m.optString("speaker"), at = m.optLong("at"),
                        gid = m.getString("gid"), reactions = Reaction.list(m.optJSONArray("reactions"))) } catch (_: Exception) { null } } } }
            rooms = out
            out.values.flatten().maxOfOrNull { it.id }?.let { mx -> ids.set(maxOf(ids.get(), mx + 1)) }
        }

        /** The note a fresh context window carries in its history (never shown as a bubble). */
        fun freshNote(note: String) = "[New context window. Where we were:]\n\n$note"

        /** One mind: a fresh context window on this thread. [note] (a state note) is carried into it. Nothing is deleted. */
        fun freshContext(note: String?) {
            if (busy) return
            ctxFrom = history.length(); lcSummary = ""; lcUpto = ctxFrom
            // v0.26.2: silent. No "Fresh start" bubble in the chat; the new context window and the carried note stay internal.
            if (!note.isNullOrBlank()) history.put(JSONObject().put("role", "assistant").put("content", freshNote(note)))
            persist()
        }

        /** Save the current chat to disk (screenshots are dropped to keep files small). */
        fun persist(force: Boolean = false) {
            // force (v0.24.0 /clear): an emptied thread is saved too, so cleared messages don't come back on restart.
            if (isPrivate) return   // v0.24.4: private chats are never written anywhere
            if (!force && msgs.none { it.role == Role.USER } && !(keep && msgs.isNotEmpty())) return
            // v0.30.5 (Luke: "sometimes when you send a new message the app kind of glitches and then unfreezes"): the whole
            // chat (every message, the full model history, pictures stripped by copying) was turned into JSON on the main
            // thread at every send, which grows with the one long main conversation. Only a cheap snapshot is taken here;
            // the JSON is built and written in the background, newest snapshot last (ioLock keeps saves in order).
            val snapMsgs = msgs
            val snapHist = ArrayList<JSONObject>(history.length()).apply { for (i in 0 until history.length()) add(history.getJSONObject(i)) }
            val marks = HashMap(historyMark)
            val firstUser = snapMsgs.firstOrNull { it.role == Role.USER }?.text ?: snapMsgs.firstOrNull()?.text ?: title
            if (title.isEmpty()) title = firstUser.lineSequence().first().take(48)
            val preview = snapMsgs.lastOrNull { it.role == Role.BLAZE }?.text ?: firstUser
            val head = JSONObject().put("id", id).put("title", title).put("pinned", pinned)
                .put("updated", System.currentTimeMillis()).put("preview", preview.take(90))
                .put("summary", summary)
                .put("lc_summary", lcSummary).put("lc_upto", lcUpto).put("ctx_from", ctxFrom)
                .apply { meter?.let { m -> put("meter_in", m.inTok).put("meter_out", m.outTok).put("meter_limit", m.limit) } }
                .put("title_source", titleSource).put("title_turns", titleTurns).put("keep", keep)
                .put("tips_pending", JSONArray(synchronized(unseenTips) { unseenTips.toList() }))
                .apply { if (rooms.isNotEmpty()) put("rooms", roomsJson()) }
                .apply { if (clearedAt > 0) put(com.blaze.assistant.connect.SyncMerge.CLEARED, clearedAt) }
                .apply { resume?.let { put("resume", it.toJson()) } }   // v0.45.0 (#38)
            if (this === cur && id == Mind.MAIN) Settings.lastChatId = id
            val cid = id
            touchedAt = System.currentTimeMillis()
            val seq = persistSeq.incrementAndGet()
            io.launch {
                ioLock.withLock {
                    if (seq != persistSeq.get()) return@withLock   // a newer snapshot is queued: it writes everything
                    // v0.45.0 (#11): each message's JSON is made once and reused by later saves (ChatJson); same file format.
                    val arr = ChatJson.messages(snapMsgs, marks)
                    // A history entry changed under us (rare). v0.45.0 (#7): this save used to be dropped; now the copy is
                    // retried (the entries are the live ones, so a retry sees the finished change) and only then given up, logged.
                    var hist: JSONArray? = null
                    for (attempt in 0 until 3) {
                        try { val h2 = JSONArray(); for (h in snapHist) h2.put(stripImages(h)); hist = h2; break }
                        catch (_: Exception) { kotlinx.coroutines.delay(20L * (attempt + 1)) }
                    }
                    if (hist == null) { com.blaze.assistant.device.CrashLog.write("ERROR chat save skipped ($cid): history kept changing"); return@withLock }
                    if (ChatStore.saveText(head, ChatJson.file(head, arr, hist!!))) savedSeq = seq
                    _chats.value = ChatStore.list()   // v0.45.0 (#1): cheap now (index; only changed files are read)
                    try { LongChat.db()?.sync(cid, hist) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: LongChat.db.synccid hist", e) }
                    // One mind: every turn goes into the journal (searchable, queued for the idle review).
                    Mind.journal(cid, hist)
                }
            }
        }
        private val persistSeq = java.util.concurrent.atomic.AtomicLong(0)

        /** Images become a short note in saved history; the words stay. */
        fun stripImages(m: JSONObject): JSONObject {
            val c = m.opt("content") as? JSONArray ?: return m
            val out = JSONArray(); var dropped = 0
            for (i in 0 until c.length()) {
                val p = c.getJSONObject(i)
                if (p.optString("type") == "image_url") dropped++ else out.put(p)
            }
            if (dropped == 0) return m
            out.put(JSONObject().put("type", "text").put("text", "[$dropped image(s) were shared here earlier]"))
            return JSONObject(m.toString()).put("content", out)
        }

        // ---------- Turns ----------

        /** A self-command handled on-device: shown and remembered like a normal exchange. */
        fun selfTurn(text: String, reply: String, byVoice: Boolean, chip: String = "⚡ Blaze itself") {
            // v0.30.6: while a turn runs (or a burst is open) the history mustn't get lines in the middle of a tool call:
            // the exchange waits in the inbox, in order, as one user-side note ("Blaze already answered this one").
            val defer = busy || waiting
            val uid = add(Role.USER, text)
            if (defer) inbox.add(Inbound(uid, text, emptyList(), false, null, raw = JSONObject().put("role", "user")
                .put("content", RapidMessages.answeredNote(if (RapidMessages.shouldTag(agentId == null, text)) RapidMessages.label(uid, text, midTurn = busy) else text, reply))))
            else history.put(JSONObject().put("role", "user").put("content", text))
            if (reply.isNotBlank()) {
                val id = add(Role.BLAZE, reply)
                addMeta(id, chip)
                if (!defer) history.put(JSONObject().put("role", "assistant").put("content", reply))
                if (byVoice && Voice.shouldSpeak() && !reply.startsWith("Going quiet")) Voice.speak(reply)
            }
            // A turn that ends without another step (an on-device answer, a fast-lane reply) still hands it over.
            if (defer && busy) scope.launch { job?.join(); drain() }
            persist()
        }

        /**
         * v0.30.6 rapid messages: never drops a message. While a turn runs, the message shows at once and joins that turn
         * at its next step; with [burst] (typed in the chat) it waits a moment for more ([RapidMessages.burstWait]) so a burst
         * is one turn; otherwise it starts a turn now. Never two turns at once in one chat.
         */
        fun send(text: String, byVoice: Boolean = false, attachments: List<Attachment> = emptyList(), notificationContext: String? = null,
                 untrusted: String? = null, burst: Boolean = false) {
            if (text.isBlank() && attachments.isEmpty()) return
            val shown = text.trim().ifEmpty { "Take a look at this." }
            val busyNow = job?.isActive == true
            // v0.32.0 split: Blaze is busy with a task, so Halo takes this one (unless it's "@blaze …": that steers Blaze).
            if (busyNow && soloRunning && attachments.isEmpty() && notificationContext.isNullOrBlank() && agentId == null && specialistId == null &&
                Settings.bool("duo_on", true) && Settings.bool("duo_split", true) && SelfControl.match(shown) == null) {
                val r = Duo.route(shown, false)
                if (!(r.explicit && r.mode == Duo.Mode.BLAZE)) { sideSend(shown, r.text); return }
            }
            when (RapidMessages.route(busyNow, !busyNow && waiting, burst && notificationContext.isNullOrBlank())) {
                // v0.45.8: the working state is set before anything else, so it is drawn on the frame after Send.
                RapidMessages.Route.NOW -> { mood = Mood.THINKING; startTurn(listOf(Inbound(post(shown, attachments), shown, attachments, byVoice, untrusted)), notificationContext, ack = true) }
                RapidMessages.Route.MID_TURN -> {
                    queue(shown, attachments, byVoice, untrusted)
                    // If the turn ends before its next step, this starts the next one (after it, never beside it).
                    scope.launch { job?.join(); drain() }
                }
                RapidMessages.Route.JOIN_BURST_NOW -> {
                    queue(shown, attachments, byVoice, untrusted)
                    burstJob?.cancel(); burstJob = null
                    drain()
                }
                RapidMessages.Route.BURST -> {
                    queue(shown, attachments, byVoice, untrusted)
                    burstAt = System.currentTimeMillis()
                    // Instant acknowledgment while the burst is open: the orb, the working card and the highlight.
                    // (the work card times from now, not from the last turn)
                    if (mood == Mood.IDLE) { beginTurn(); mood = Mood.THINKING }
                    LlmClient.prewarm()
                    if (burstJob?.isActive != true) burstJob = scope.launch {
                        while (true) {
                            val w = RapidMessages.burstWait(System.currentTimeMillis(), burstAt, typingAt, typingText)
                            if (w <= 0L) break
                            kotlinx.coroutines.delay(w)
                        }
                        burstJob = null
                        drain()
                    }
                }
            }
        }

        /** v0.30.6: the user's bubble, shown at once. Files with a thumbnail first, so the bubble pairs thumbnails with their names (v0.24.6). */
        private fun post(shown: String, attachments: List<Attachment>): Long {
            val id = add(Role.USER, shown, attachments.sortedBy { if (it.thumb != null) 0 else 1 }.map { it.name })
            attachments.mapNotNull { it.thumb }.takeIf { it.isNotEmpty() }?.let { th -> msgs = msgs.map { if (it.id == id) it.copy(media = th) else it } }
            return id
        }

        /** v0.30.6: a message that waits for the running turn's next step or for the burst to close. */
        private fun queue(shown: String, attachments: List<Attachment>, byVoice: Boolean, untrusted: String?) {
            val id = post(shown, attachments)
            inbox.add(Inbound(id, shown, attachments, byVoice, untrusted))
            focus = focus + id
            com.blaze.assistant.device.Haptics.tick(app)
            persist()   // so it shows in the chat list right away, even if you leave
        }

        /**
         * Starts a turn with [items] (their bubbles are already in the chat), in order; v0.30.6: one or several messages.
         * [ack] = tick now (a queued message already ticked when it was sent).
         */
        private fun startTurn(items0: List<Inbound>, notificationContext: String?, ack: Boolean) {
            // v0.31.2 Halo x Blaze: "@halo …", "@blaze …", "@all …" or a team job with no tag. The tags are taken out of
            // what the model reads (the bubble keeps them). Background agents, routines and notification replies stay Blaze.
            val users0 = items0.filter { it.raw == null }
            val duoOk = agentId == null && specialistId == null && notificationContext.isNullOrBlank() && users0.isNotEmpty() &&
                users0.none { it.text.startsWith("[Scheduled routine") } && Settings.bool("duo_on", true)
            val route = if (!duoOk) Duo.Route(Duo.Mode.BLAZE, "", false)
                else Duo.route(users0.joinToString("\n") { it.text }, teamOn = Settings.bool("duo_team", true) && users0.all { it.attachments.isEmpty() })
            val items = if (!route.explicit) items0 else items0.map { m ->
                if (m.raw != null) m else Inbound(m.id, Duo.route(m.text, false).text, m.attachments, m.byVoice, m.untrusted) }
            voice = if (route.mode == Duo.Mode.HALO) Duo.Who.HALO else Duo.Who.BLAZE
            // v0.32.0 open conversation: a meeting room or a duo chat that handed you the floor takes your untagged reply.
            val said0 = users0.joinToString("\n") { it.text }
            val freeReply = duoOk && !route.explicit && route.mode != Duo.Mode.TEAM && !Duo.isCommand(said0) && users0.all { it.attachments.isEmpty() }
            val resumeRoom = if (freeReply) waitingRoom() else null
            val resumeChat = freeReply && resumeRoom == null && !ToolTruth.toolShaped(said0) && duoChatOpenAt > 0 && System.currentTimeMillis() - duoChatOpenAt < 2 * 3600_000L
            if (route.explicit) {
                // A tag starts something new: a room left waiting is closed, an open duo chat ends.
                waitingRoom()?.let { rid -> setCard(rid, Duo.cardMeta(Duo.STOPPED)) }
                if (route.mode != Duo.Mode.ALL) duoChatOpenAt = 0L
            }
            val duoKind: Duo.Kind? = when {
                resumeRoom != null -> Duo.Kind.ROOM
                resumeChat -> Duo.Kind.CHAT
                route.mode == Duo.Mode.TEAM -> Duo.Kind.ROOM
                // v0.45.9: the duo chat sends no tools, so a tool-shaped @all request goes to the full lane.
                route.mode == Duo.Mode.ALL -> Duo.kindFor(route.text).takeUnless { it == Duo.Kind.CHAT && ToolTruth.toolShaped(route.text) }
                else -> null
            }
            if (duoKind != null) voice = Duo.Who.BLAZE
            val duoMode = if (duoKind != null) Duo.Mode.ALL else null
            val users = items.filter { it.raw == null }
            if (users.isEmpty()) return
            val single = users.size == 1
            val attachments = users.flatMap { it.attachments }
            val byVoice = users.any { it.byVoice }
            // v0.28.2: a new turn starts clean; what came with the message (a shared file, Magic Hand's screen text) counts as read.
            taint.clear()
            Voice.lastTurnByVoice = byVoice
            val shown = if (single) users[0].text else users.joinToString("\n") { it.text }
            // v0.33.1 adaptive thinking budget: an on-phone classifier (hard / easy cues, maths, code), not just the length.
            // v0.45.0 (#3): this turn's own model-call state (flags, timing, usage), never shared with another chat or agent.
            val callState = CallState().also { it.quick = ThinkBudget.quick(shown, attachments.size) }
            turnFrom = users[0].id
            beginTurn(msgs.lastOrNull { it.id == users[0].id }?.at ?: System.currentTimeMillis())
            // Heads-ups Blaze posted into this chat as notifications, and the one being replied to: the model
            // sees what Blaze had said, so "yes", "remind me later" or "who's that?" make sense (v0.8.0).
            val tips = synchronized(unseenTips) { unseenTips.toList().also { unseenTips.clear() } }
            // v0.45.0 (#38): "Continue where you stopped" (or "continue" / "resume") picks up the interrupted task with its goal
            // and what was already done; anything else the user says starts something new and the stopped task is let go.
            val resumed = resume?.takeIf { single && agentId == null && StopResume.isResume(shown) }
            resumeGoal = resumed?.goal
            resume = null
            val prefix = buildString {
                if (resumed != null) append(StopResume.note(resumed)).append("\n")
                if (tips.isNotEmpty()) append("[Heads-ups you sent the user as phone notifications since their last message here (they also show in this chat):\n" +
                    tips.takeLast(12).joinToString("\n") { "- ${it.take(600)}" } + "]\n")
                if (!notificationContext.isNullOrBlank()) append("[The user is replying from the notification of this message you sent them: \"${notificationContext.take(1500)}\"]\n")
                if (isNotEmpty()) append("\n")
            }
            var first = true
            for (m in items) {
                if (m.raw == null) { putInbound(m, midTurn = false, prefix = if (first) prefix else ""); first = false }
                else putInbound(m, midTurn = false)
            }
            focus = users.map { it.id }.toSet()
            persist()   // so it shows in the chat list right away, even if you leave
            val routine = shown.startsWith("[Scheduled routine")
            // Instant acknowledgment: the orb and a tick react before any thinking happens.
            mood = Mood.THINKING
            LlmClient.prewarm()   // speed plan step 10: open the connection while the fast lane checks
            if (!routine && ack) com.blaze.assistant.device.Haptics.tick(app)
            // Ack now, work in the background (HQ 22 Oct 20:35): one plain message from Blaze within a second, never "done".
            val gate = Ack.Gate().also { ackGate = it }
            taskChip = ""
            val turnUser = users[0].id
            if (!routine && agentId == null && specialistId == null) Ack.local(shown)?.let { a ->
                scope.launch {
                    // A fast-lane command answers in a few hundred ms: its reply is the receipt, no ack in front of it.
                    kotlinx.coroutines.delay(Ack.LOCAL_DELAY_MS)
                    if (job?.isActive == true) showAck(gate, a, turnUser, shown)
                }
            }
            if (byVoice && !routine) scope.launch {
                // No-hang voice without words: v0.41.0 speaks only the final reply, never "On it." or the work steps
                // ("Looking at your series"). A soft tick after a second, then one every 9 s of long work.
                kotlinx.coroutines.delay(1100)
                var waited = 0
                while (job?.isActive == true && waited < 600) {
                    if (waited % 9 == 0 && Voice.state.value == Voice.State.IDLE) com.blaze.assistant.device.Sounds.working()
                    kotlinx.coroutines.delay(1000); waited++
                }
            }
            job = scope.launch(ctxTag + taint + callState.element()) {
                // v0.31.2: @all and team jobs run on the duo lanes (both companions, no tool loop).
                if (duoMode != null) {
                    if (!FastLane.online(app)) {
                        add(Role.ERROR, "You're offline, so Blaze and Halo can't team up right now. Direct commands like \u201copen WhatsApp\u201d still work.")
                        mood = Mood.IDLE; persist(); return@launch
                    }
                    runConversation(duoKind!!, shown.let { s -> if (route.explicit) Duo.route(s, false).text else s }, resumeRoom ?: -1L, users.first().id)
                    return@launch
                }
                // v0.27.x: "show me your memories" / "what do you know about me" is answered straight from the memory table:
                // grouped, with how to change or delete a line. No model call, no tool loop, no Design.
                if (single && attachments.isEmpty() && !routine && agentId == null && notificationContext.isNullOrBlank() && MemoryIntent.isListRequest(shown)) {
                    mood = Mood.ACTING
                    add(Role.STEP, "Looking through what I remember")
                    val reply = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        try { if (Mind.db() != null) MemoryIntent.format(Mind.all()) else MemoryIntent.formatPlain(Memory.facts.value) }
                        catch (_: Exception) { MemoryIntent.formatPlain(Memory.facts.value) }
                    }
                    history.put(JSONObject().put("role", "assistant").put("content", reply))
                    addReply(-1, reply, "")
                    mood = Mood.IDLE
                    persist(); announce()
                    return@launch
                }
                // Fast lane: direct commands run on-device in well under a second.
                val offline = !FastLane.online(app)
                // Plan mode and background agents skip the fast lane: it acts on the phone without the tool gate.
                // v0.45.8: online, the fast lane only uses its instant rules and the intent cache (FastLane.useModel); the
                // on-device model (a process start plus inference, seconds) runs only offline, so it never delays the request.
                if (single && attachments.isEmpty() && !routine && !Modes.plan && !agentPlan && agentId == null && notificationContext.isNullOrBlank()) {
                    mood = Mood.THINKING
                    val fast = try { kotlinx.coroutines.withContext(ConfirmGate.Direct) { FastLane.tryHandle(app, shown, offline) { t, d, a -> confirm(t, d, a) } } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    // "Draw a lion in sunglasses": straight to the free image service, no model call needed.
                    val pic = if (fast == null && !offline && !Settings.lowData) com.blaze.assistant.media.Media.directPrompt(shown) else null
                    if (pic != null) {
                        mood = Mood.ACTING
                        add(Role.STEP, "Drawing \u201c${pic.take(60)}\u201d")
                        val t0 = System.currentTimeMillis()
                        val r = com.blaze.assistant.media.Media.generate(pic)
                        val reply = r.fold({ f -> synchronized(pendingMedia) { pendingMedia += f.absolutePath }; "Here you go. It's saved in your Gallery." },
                            { "I couldn't reach the image service just now (${it.message}). Try again in a moment." })
                        history.put(JSONObject().put("role", "assistant").put("content", if (r.isSuccess) "[Generated an image of: $pic, shown to the user and saved in their Gallery]" else reply))
                        addReply(-1, reply, "", meta = "\uD83C\uDFA8 free image · %.1f s".format((System.currentTimeMillis() - t0) / 1000.0))
                        mood = Mood.IDLE
                        persist(); announce()
                        return@launch
                    }
                    if (fast != null) {
                        history.put(JSONObject().put("role", "assistant").put("content", fast.reply))
                        addReply(-1, fast.reply, "", meta = if (fast.ms < 60) "⚡ instant · ${fast.ms} ms" else "⚡ on-device · ${fast.ms} ms")
                        mood = Mood.IDLE
                        persist(); announce()
                        // Not a child of this job: a child would keep the turn "busy" for 5 minutes and block every new prompt.
                        scope.launch { kotlinx.coroutines.delay(5 * 60_000 + 5_000); FastLane.trimIfIdle() }
                        return@launch
                    }
                }
                if (offline && !Settings.customBase.contains("127.0.0.1") && !Settings.customBase.contains("localhost")) {
                    add(Role.ERROR, "You're offline. I can still do direct commands like \u201copen WhatsApp\u201d, \u201cflashlight on\u201d or \u201cset a timer for 5 minutes\u201d.")
                    mood = Mood.IDLE; persist(); return@launch
                }
                soloRunning = voice == Duo.Who.BLAZE
                try { runLoop() } finally { soloRunning = false }
                // v0.31.2: Halo's solo answer is marked as Halo's in the history, so Blaze knows who said it.
                if (voice == Duo.Who.HALO) tagLast(Duo.Who.HALO)
            }
            job?.invokeOnCompletion { scope.launch { flushSide() } }   // v0.32.0: Halo's side talk joins the history after
        }

        /** v0.31.2: prefixes the newest plain assistant line in the history with [Halo] / [Blaze] (once). */
        private fun tagLast(who: Duo.Who) {
            for (i in history.length() - 1 downTo 0) {
                val h = history.optJSONObject(i) ?: continue
                if (h.optString("role") == "user") return
                val c = h.opt("content") as? String ?: continue
                if (h.optString("role") != "assistant" || h.has("tool_calls") || c.isBlank()) continue
                if (!c.startsWith(Duo.tag(who))) h.put("content", Duo.tag(who) + c)
                persist(); return
            }
        }

        // ---------------------------------------------------------------- v0.31.2 Halo x Blaze duo lanes

        // v0.45.0: the old uncalled runDuo / duoAll / duoTeam / duoSay / duoType lanes were removed (docs/removed.md);
        // the live @all is runConversation -> duoBoth.

        /** The newest meeting room waiting for the user's answer (its card id), or null. */
        fun waitingRoom(): Long? = msgs.lastOrNull { it.meta.startsWith(Duo.ROOM_CARD) }?.takeIf { Duo.cardState(it.meta) == Duo.WAITING }?.id

        private fun setCard(room: Long, meta: String) { msgs = msgs.map { if (it.id == room) it.copy(meta = meta) else it }; persist() }
        private fun roomAdd(room: Long, m: ChatMessage) { rooms = rooms + (room to (rooms[room].orEmpty() + m)) }
        private fun roomEdit(room: Long, id: Long, text: String) { rooms = rooms + (room to rooms[room].orEmpty().map { if (it.id == id) it.copy(text = text) else it }) }
        private fun roomDrop(room: Long, id: Long) { rooms = rooms + (room to rooms[room].orEmpty().filterNot { it.id == id }) }

        /** The room so far as transcript lines (the user's messages, Blaze's and Halo's; tool steps left out). */
        private fun roomLines(room: Long): List<Duo.RoomLine> = rooms[room].orEmpty().mapNotNull { m ->
            when (m.role) { Role.USER -> Duo.RoomLine(Settings.userName.ifBlank { "User" } + " (the user)", m.text)
                Role.BLAZE -> Duo.RoomLine(Duo.Who.of(m.speaker).display, m.text); else -> null } }

        /** One duo message streaming in, in the main chat ([room] < 0) or in a meeting room. */
        private inner class LiveMsg(val who: Duo.Who, val room: Long, val side: Boolean = false) {
            var id = -1L
            /** The bubble only holds a tool hint so far ("Searching the web…"). */
            var hinted = false
            fun show(t: String) {
                if (t.isBlank()) return
                hinted = false
                if (id < 0) {
                    id = ids.getAndIncrement()
                    val m = ChatMessage(id, Role.BLAZE, t, speaker = who.id)
                    if (room >= 0) roomAdd(room, m) else msgs = msgs + m
                    if (side) sideLive = sideLive + id else {
                        duoLive = duoLive + id
                        status = if (room >= 0) "${who.display} is talking in the meeting room" else "${who.display} is writing"
                    }
                } else if (room >= 0) roomEdit(room, id, t) else msgs = msgs.map { if (it.id == id) it.copy(text = t) else it }
            }
            fun hint(step: String) { if (id < 0 || hinted) { show("_${step.trimEnd('.', '\u2026')}\u2026_"); hinted = true } }
            fun end(final: String) {
                if (final.isBlank()) { if (id >= 0) { if (room >= 0) roomDrop(room, id) else msgs = msgs.filterNot { it.id == id } } }
                else show(final)
                if (id >= 0) { if (side) sideLive = sideLive - id else duoLive = duoLive - id }
            }
        }

        /** Shown text of a raw duo message (control tags, leaked reasoning and speaker tags out). */
        private fun duoVisible(raw: String, final: Boolean): String =
            // v0.43.0: function-call text (`browser_show()`, `[/tool k=v]`) never reaches the bubble, not even mid-stream.
            Duo.clean(DuoText.strip(RapidMessages.display(ReplyParts.split(Duo.stripSignal(raw, final), final = final).text, final = final), final, Duo.ROOM_TOOLS))

        /** The room's research tools (read-only), from the full tool list. */
        private suspend fun roomTools(extra: Set<String> = emptySet()): JSONArray? = try {
            val all = kotlinx.coroutines.withContext(Dispatchers.Default) { tools() }
            JSONArray().apply { for (i in 0 until all.length()) all.optJSONObject(i)?.let { t ->
                t.optJSONObject("function")?.optString("name").let { n -> if (n in Duo.ROOM_TOOLS || n in extra) put(t) } } }.takeIf { it.length() > 0 }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

        /**
         * One duo message: streams what's visible to [onShow] (cumulative), runs room tools between rounds (max 4).
         * Returns the raw final content (control tag included).
         */
        private suspend fun duoCall(who: Duo.Who, req: JSONArray, tools: JSONArray?, room: Long, side: Boolean = false, hardMs: Long = 0L, onShow: (String) -> Unit): String {
            val limit = Settings.str("model_timeout_s", "100").toLongOrNull()?.coerceIn(30, 600)?.times(1000) ?: 100_000L
            val shots = mutableListOf<String>()
            var rounds = 0
            var nudged = false
            while (true) {
                val useTools = tools != null && rounds < 4
                val gate = StreamGate(scope) { partial -> val seen = duoVisible(partial, final = false); if (seen.isNotEmpty()) onShow(seen) }
                val onText: ((String) -> Unit)? = if (Settings.streamReplies) { p -> gate.offer(p) } else null
                val reply = try {
                    val call: suspend () -> JSONObject? = { Agent.idleGuard(limit) { LlmClient.complete(req, if (useTools) tools else null, onText, null, LlmClient.Lane.MAIN) } }
                    // v0.43.0: @all has a hard cap per companion (the idle guard alone is shared by both streams, so one kept the other alive).
                    (if (hardMs > 0) (kotlinx.coroutines.withTimeoutOrNull(hardMs) { call() } ?: throw DuoLate(who)) else call())
                        ?: throw LlmClient.LlmException("${who.display} went quiet. Tap Retry.")
                } finally { gate.flush(); gate.close() }
                reply.remove("_reasoning")
                try { Usage.record(req, reply, LlmClient.lastUsage) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: Usage.recordreq reply LlmClient.lastUsag", e) }
                if (useTools) try {
                    if ((reply.optJSONArray("tool_calls")?.length() ?: 0) == 0) (reply.opt("content") as? String)?.let { txt ->
                        (CallScan.toCalls(txt, tools!!) ?: ToolCalls.fromText(txt, tools) ?: ToolLeak.toCalls(txt, tools))?.let { (c, rest) -> reply.put("tool_calls", c); reply.put("content", if (rest.isBlank()) JSONObject.NULL else CallScan.clean(ToolLeak.strip(rest))) } }
                    ToolCalls.normalize(reply, tools!!)
                } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: if reply.optJSONArraytool_calls.length 0", e) }
                if ((reply.optJSONArray("tool_calls")?.length() ?: 0) == 0) {
                    // v0.43.0: a message that is (or holds) a tool call written as text: run it if it's allowed here, else
                    // ask once for a plain answer, else a plain fallback line. Raw function syntax is never shown.
                    val raw = (reply.opt("content") as? String).orEmpty()
                    val allowed = if (useTools) ToolCalls.names(tools!!).filter { it in Duo.ROOM_TOOLS || (side && it in Duo.SIDE_SCHEDULE_TOOLS) }.toSet() else emptySet()
                    when (val d = DuoText.decide(Duo.stripSignal(raw, true), allowed, Duo.ROOM_TOOLS + allowed, nudged, who.display)) {
                        is DuoText.Decision.Show -> {
                            duoVisible(raw, final = true).takeIf { it.isNotEmpty() }?.let(onShow)
                            return raw
                        }
                        is DuoText.Decision.Retry -> {
                            nudged = true
                            req.put(JSONObject().put("role", "assistant").put("content", raw.ifBlank { "(empty)" }))
                            req.put(JSONObject().put("role", "user").put("content", DuoText.NUDGE))
                            continue
                        }
                        is DuoText.Decision.Fallback -> { onShow(d.text); return d.text + "\n[[to: user]]" }
                        is DuoText.Decision.Run -> {
                            val arr = JSONArray()
                            d.calls.forEachIndexed { i, c -> arr.put(JSONObject().put("id", "duo_text_$i").put("type", "function")
                                .put("function", JSONObject().put("name", c.name).put("arguments", c.args.toString()))) }
                            reply.put("tool_calls", arr); reply.put("content", if (d.rest.isBlank()) JSONObject.NULL else d.rest)
                        }
                    }
                }
                val calls = reply.optJSONArray("tool_calls")
                if (!useTools || calls == null || calls.length() == 0) {
                    val raw = (reply.opt("content") as? String).orEmpty()
                    duoVisible(raw, final = true).takeIf { it.isNotEmpty() }?.let(onShow)
                    return raw
                }
                rounds++
                req.put(reply)
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val fn = call.optJSONObject("function") ?: continue
                    val name = fn.optString("name")
                    val args = try { JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                    val result = if (name !in Duo.ROOM_TOOLS && !(side && name in Duo.SIDE_SCHEDULE_TOOLS && tools?.let { name in ToolCalls.names(it) } == true)) "Error: only research tools work in the meeting room (${Duo.ROOM_TOOLS.joinToString()})."
                    else if (side) {
                        // Halo's side lane: never touches Blaze's voice or work card (her steps show as a hint in her bubble).
                        try { runTool(name, args, shots) } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                    } else {
                        voice = who; roomSink = room
                        try { runTool(name, args, shots) } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                        finally { roomSink = -1L }
                    }
                    req.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id", "d$rounds$i")).put("name", name).put("content", result.take(8000)))
                }
            }
        }

        /**
         * Both get the same request at once; the first to produce a visible character takes the floor and is shown, the
         * other is cancelled (it answers next, having read the winner). Returns (winner, raw text).
         */
        private suspend fun duoRace(build: (Duo.Who) -> JSONArray, tools: JSONArray?, room: Long): Triple<Duo.Who, String, LiveMsg> = kotlinx.coroutines.coroutineScope {
            val lock = Any()
            var first: Duo.Who? = null
            val won = kotlinx.coroutines.CompletableDeferred<Duo.Who>()
            val lives = Duo.Who.values().associateWith { LiveMsg(it, room) }
            fun claim(w: Duo.Who): Boolean = synchronized(lock) { if (first == null) { first = w; won.complete(w) }; first == w }
            val jobs = Duo.Who.values().associateWith { w ->
                async {
                    try { duoCall(w, build(w), tools, room) { t -> if (claim(w)) lives.getValue(w).show(t) }.also { if (duoVisible(it, true).isNotEmpty()) claim(w) } }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("duo race ${w.id}", e); "" }
                }
            }
            val watch = launch {
                jobs.values.forEach { j -> try { j.await() } catch (e: CancellationException) { } }
                if (!won.isCompleted) won.completeExceptionally(LlmClient.LlmException("Blaze and Halo couldn't reach the model just now. Tap Retry."))
            }
            val w = won.await()
            jobs.getValue(w.other).cancel()
            val raw = jobs.getValue(w).await()
            watch.cancel()
            Triple(w, raw, lives.getValue(w))
        }

        /** The conversation so far as [who] sees it: its own lines are "assistant", its partner's arrive as "user" lines. */
        private fun duoContext(who: Duo.Who, max: Int): JSONArray {
            val c = Duo.context(history, maxTurns = max, maxChars = 2500)
            val out = JSONArray()
            for (i in 0 until c.length()) {
                val m = c.getJSONObject(i); val t = m.optString("content")
                out.put(if (m.optString("role") == "assistant" && t.startsWith(Duo.tag(who.other))) JSONObject().put("role", "user").put("content", t) else m)
            }
            if (out.length() > 0 && out.getJSONObject(out.length() - 1).optString("role") == "assistant")
                out.put(JSONObject().put("role", "user").put("content", "(Your turn, ${who.display}.)"))
            return out
        }

        // ---------------------------------------------------------------- v0.32.0 split: Halo free while Blaze works

        /** Your message while Blaze is busy: Halo answers it on the side (one at a time, in order). */
        private fun sideSend(shown: String, text: String) {
            val id = post(shown, emptyList())
            sideIds += id
            sideQueue.add(id to text.ifBlank { shown })
            com.blaze.assistant.device.Haptics.tick(app)
            persist()
            if (sideJob?.isActive == true) return
            sideJob = scope.launch(ctxTag + Agent.sideFlag.asContextElement(true) + CallState().element()) {
                split = true
                try {
                    while (true) { val (mid, t) = sideQueue.poll() ?: break; haloSide(mid, t) }
                } finally { split = false; sideLive = emptySet(); sideStep = null; persist() }
            }
            sideJob?.invokeOnCompletion { scope.launch { flushSide() } }
        }

        private suspend fun haloSide(mid: Long, text: String) {
            val user = JSONObject().put("role", "user").put("content", text)
            synchronized(sideHist) { sideHist.add(user) }
            val req = JSONArray().put(JSONObject().put("role", "system").put("content",
                Duo.sideSystem(Settings.greetName(), lastUserText(), status) + TurnContext.RULE + "\n" + TurnContextSource.block(text)))
            try { val c = duoContext(Duo.Who.HALO, 12); for (i in 0 until c.length()) req.put(c.get(i)) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: val c duoContextDuo.Who.HALO 12 for i in", e) }
            synchronized(sideHist) { sideHist.forEach { req.put(it) } }
            val live = LiveMsg(Duo.Who.HALO, -1L, side = true)
            sideStep = { st -> live.hint(st) }
            // v0.45.9: a schedule ask, or a yes to Halo's / Blaze's schedule offer ("@halo Yes"), gets the calendar and reminder tools.
            val lastReply = msgs.lastOrNull { it.role == Role.BLAZE && it.id < mid }?.text.orEmpty()
            val sched = TurnContext.scheduleAsk(text) || TurnContext.scheduleFollowUp(text, lastReply)
            // v0.45.9 (ToolTruth): the side lane runs only research tools, so a tool-shaped message goes to Blaze's full lane
            // (it joins the running task at its next step, or starts the next turn), never "I can't" from Halo.
            if (!sched && ToolTruth.toolShaped(text) && !ToolTruth.chatOnly(text)) {
                live.end("")
                synchronized(sideHist) { sideHist.remove(user) }
                sideIds -= mid
                inbox.add(Inbound(mid, text, emptyList(), false, null))
                focus = focus + mid
                scope.launch { job?.join(); drain() }
                persist()
                return
            }
            val raw = try { duoCall(Duo.Who.HALO, req, roomTools(if (sched) Duo.SIDE_SCHEDULE_TOOLS else emptySet()), -1L, side = true) { live.show(it) } }
                catch (e: CancellationException) { live.end(""); throw e }
                catch (e: Exception) { live.end(""); add(Role.ERROR, e.message ?: "Halo lost the connection."); return }
            val shownText = duoVisible(raw.replace(Regex("(?i)\\[\\[\\s*relay\\s*\\]\\]"), ""), final = true)
            live.end(shownText)
            if (Duo.isRelay(raw)) {
                // For Blaze: it joins Blaze's running task at its next step (or starts Blaze's next turn).
                synchronized(sideHist) { sideHist.remove(user) }
                sideIds -= mid
                inbox.add(Inbound(mid, text, emptyList(), false, null))
                focus = focus + mid
                scope.launch { job?.join(); drain() }
            }
            if (shownText.isNotEmpty()) synchronized(sideHist) { sideHist.add(JSONObject().put("role", "assistant").put("content", Duo.tag(Duo.Who.HALO) + shownText)) }
            persist()
        }

        /** Once neither lane runs, Halo's side talk joins the history (never in the middle of Blaze's tool calls). */
        private fun flushSide() {
            if (job?.isActive == true || sideJob?.isActive == true) return
            val got = synchronized(sideHist) { sideHist.toList().also { sideHist.clear() } }
            if (got.isEmpty()) return
            got.forEach { history.put(it) }
            persist()
        }

        /** The question a room message puts to the user, for the main chat. */
        private fun questionOf(text: String): String {
            val tail = text.takeLast(900)
            val qs = Regex("[^.!?\\n]*\\?").findAll(tail).map { it.value.trim() }.filter { it.length > 3 }.toList()
            return if (qs.isNotEmpty()) qs.takeLast(2).joinToString(" ") else tail.substringAfterLast("\n\n").trim()
        }

        /**
         * v0.32.0: an open-ended duo conversation. CHAT runs in the main chat; ROOM in a meeting room (a new one, or
         * [resumeRoom] when you answered its question). No turn limit, only the "duo_max_turns" safety stop.
         */
        private suspend fun runConversation(kind: Duo.Kind, task: String, resumeRoom: Long, fromMsg: Long) {
            val t0 = System.currentTimeMillis()
            val name = Settings.greetName()
            val maxTurns = Settings.str("duo_max_turns", "24").toIntOrNull()?.coerceIn(4, 200) ?: 24
            var room = -1L
            try {
                mood = Mood.THINKING
                duoChatOpenAt = 0L
                if (kind == Duo.Kind.ROOM) {
                    room = if (resumeRoom >= 0) resumeRoom.also { setCard(it, Duo.cardMeta(Duo.LIVE)) } else {
                        val id = ids.getAndIncrement()
                        msgs = msgs + ChatMessage(id, Role.BLAZE, task.take(160), meta = Duo.cardMeta(Duo.LIVE))
                        persist(); id
                    }
                }
                val tools = if (kind == Duo.Kind.ROOM) roomTools() else null
                var seenMsg = fromMsg - 1
                fun pullUser() {
                    takeInbox(true)
                    if (room < 0) return
                    msgs.filter { it.role == Role.USER && it.id > seenMsg }.forEach { u ->
                        roomAdd(room, ChatMessage(ids.getAndIncrement(), Role.USER, u.text, at = u.at)); seenMsg = maxOf(seenMsg, u.id) }
                }
                var n = 0
                var next: Duo.Who? = null
                var prevWho: Duo.Who? = null; var prevSig: Duo.Signal? = null
                val facts = duoFacts(task)
                // v0.43.0: an @all chat message gets both answers at once (parallel, streamed, 45 s cap each) instead of a
                // race + a second, sequential answer + more back-and-forth, which took about two minutes on a phone.
                if (kind == Duo.Kind.CHAT) { duoBoth(name, facts); duoChatOpenAt = System.currentTimeMillis() }
                else while (true) {
                    pullUser()
                    val wrap = n + 1 >= maxTurns
                    fun req(w: Duo.Who): JSONArray {
                        val sys = (if (kind == Duo.Kind.ROOM) Duo.roomSystem(w, name, task, tools != null) else Duo.chatSystem(w, name, first = n == 0)) +
                            (if (wrap) Duo.wrapUpNote(kind) else "") + facts
                        val r = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
                        val ctx = duoContext(w, if (kind == Duo.Kind.ROOM) 8 else 24)
                        for (i in 0 until ctx.length()) r.put(ctx.get(i))
                        if (kind == Duo.Kind.ROOM) r.put(JSONObject().put("role", "user").put("content", Duo.roomTranscript(roomLines(room), w)))
                        return r
                    }
                    val who: Duo.Who; val raw: String; val live: LiveMsg
                    if (next == null) { val (w, r, l) = duoRace(::req, tools, room); who = w; raw = r; live = l }
                    else {
                        who = next; live = LiveMsg(who, room)
                        raw = try { duoCall(who, req(who), tools, room) { live.show(it) } } catch (e: CancellationException) { live.end(""); throw e }
                    }
                    n++
                    val text = duoVisible(raw, final = true)
                    live.end(text)
                    if (text.isEmpty()) {
                        // One of them came back empty: the other carries on once, then it stops.
                        if (next != null && prevWho != who) { next = who.other; prevWho = who; prevSig = null; continue }
                        break
                    }
                    if (room < 0) history.put(JSONObject().put("role", "assistant").put("content", Duo.tag(who) + text))
                    persist()
                    val sig = Duo.effective(Duo.parseSignal(raw, who, Duo.fallbackNext(kind, n)), kind, n)
                    val deliver = Duo.consensus(prevWho, prevSig, who, sig) ?: if (wrap && kind == Duo.Kind.ROOM) (sig.deliver ?: who) else null
                    if (deliver != null) { deliverFinal(deliver, kind, task, room); if (room >= 0) setCard(room, Duo.cardMeta(Duo.DONE, deliver)); break }
                    if (sig.next == Duo.Next.USER) {
                        if (room >= 0) {
                            // Their question comes to you in the main chat; the room waits for your answer.
                            val q = questionOf(text)
                            msgs = msgs + ChatMessage(ids.getAndIncrement(), Role.BLAZE, q, speaker = who.id)
                            history.put(JSONObject().put("role", "assistant").put("content", Duo.tag(who) + "(from the meeting room) " + q))
                            setCard(room, Duo.cardMeta(Duo.WAITING))
                        } else duoChatOpenAt = System.currentTimeMillis()
                        break
                    }
                    if (sig.next == Duo.Next.DONE && kind == Duo.Kind.CHAT) break
                    if (wrap) { if (kind == Duo.Kind.CHAT) duoChatOpenAt = System.currentTimeMillis(); break }
                    prevWho = who; prevSig = sig; next = who.other
                }
                if (Settings.showRoute) msgs.lastOrNull { it.role == Role.BLAZE && !it.meta.startsWith(Duo.ROOM_CARD) }
                    ?.let { addMeta(it.id, "\u2726 Halo x Blaze · %.1f s".format((System.currentTimeMillis() - t0) / 1000.0)) }
                com.blaze.assistant.device.Haptics.done(app)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                add(Role.ERROR, e.message ?: "Blaze and Halo lost the connection. Tap Retry.")
            } finally {
                if (room >= 0 && msgs.firstOrNull { it.id == room }?.meta?.let { Duo.cardState(it) } == Duo.LIVE) setCard(room, Duo.cardMeta(Duo.STOPPED))
                voice = Duo.Who.BLAZE; roomSink = -1L
                duoLive = emptySet(); streamId = -1L
                ackGate?.let { g -> g.finished = true; if (g.acked) TaskKeeper.finished(app, id, g.user) }; taskChip = ""
                mood = Mood.IDLE
                persist(); announce()
            }
        }

        /**
         * v0.43.0: what the app can check itself right now, for the duo lanes (they have no tools). Only when the talk is
         * about the Linux desktop: "is it really running?" gets the truth from the port, not from earlier messages.
         */
        private suspend fun duoFacts(task: String): String = try {
            val recent = buildString {
                append(task)
                for (i in maxOf(0, history.length() - 6) until history.length()) (history.optJSONObject(i)?.opt("content") as? String)?.let { append('\n').append(it.take(400)) }
            }
            if (!Regex("(?i)desktop|linux|dillo|xfce|vnc|browser|running").containsMatchIn(recent) || !com.blaze.assistant.desktop.LinuxDesktop.installed()) ""
            else {
                val up = com.blaze.assistant.desktop.LinuxDesktop.alive()
                "\n[Live check by the app just now: Blaze's Linux desktop is " + (if (up) "running (port 5901 answered)." else
                    "NOT running (nothing answers on port 5901), so no app in it is open either. Don't say it is running; @blaze can start or repair it.") + "]"
            }
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { "" }

        /**
         * v0.43.0 @all chat: Blaze and Halo answer at the same time, each streaming into its own bubble, each capped at
         * [Duo.PARALLEL_LIMIT_MS]. One that fails or runs late gets a plain line; the other's answer still stands.
         * Timed with TurnTimer (logcat BlazeSpeed + the speed log): first word and total per companion.
         */
        private suspend fun duoBoth(name: String?, facts: String): Unit = kotlinx.coroutines.coroutineScope {
            val timer = TurnTimer()
            val t0 = timer.start
            val first = java.util.concurrent.ConcurrentHashMap<Duo.Who, Long>()
            val jobs = Duo.Who.values().associateWith { w ->
                async {
                    val live = LiveMsg(w, -1L)
                    val req = JSONArray().put(JSONObject().put("role", "system").put("content", Duo.parallelSystem(w, name) + facts))
                    val ctx = duoContext(w, 24)
                    for (i in 0 until ctx.length()) req.put(ctx.get(i))
                    try {
                        val raw = duoCall(w, req, null, -1L, hardMs = Duo.PARALLEL_LIMIT_MS) { t -> first.putIfAbsent(w, System.currentTimeMillis() - t0); live.show(t) }
                        val text = duoVisible(raw, final = true)
                        live.end(text)
                        Triple(text, System.currentTimeMillis() - t0, false)
                    } catch (e: CancellationException) { live.end(""); throw e
                    } catch (e: Exception) {
                        live.end("")
                        if (e !is DuoLate) com.blaze.assistant.device.CrashLog.error("duo all ${w.id}", e)
                        Triple("", System.currentTimeMillis() - t0, e is DuoLate)
                    }
                }
            }
            val res = jobs.mapValues { it.value.await() }
            for (w in Duo.Who.values()) res.getValue(w).first.takeIf { it.isNotEmpty() }?.let { history.put(JSONObject().put("role", "assistant").put("content", Duo.tag(w) + it)) }
            val missing = Duo.Who.values().filter { res.getValue(it).first.isEmpty() }
            when (missing.size) {
                0 -> Unit
                1 -> add(Role.ERROR, Duo.lateLine(missing[0]))
                else -> add(Role.ERROR, Duo.BOTH_LATE)
            }
            for (w in Duo.Who.values()) { timer.add("${w.id} first word", first[w] ?: 0L); timer.add("${w.id} total", res.getValue(w).second) }
            try {
                val firstMs = first.values.minOrNull() ?: -1L
                SpeedLog.add(SpeedLog.Entry(t0, firstMs.coerceAtLeast(0), timer.total(), 1, 0, 0, "duo-all", timer.line()))
                android.util.Log.i("BlazeSpeed", "@all parallel: ${timer.line()}")
            } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: val firstMs first.values.minOrNull 1L", e) }
            persist()
            Unit
        }

        /** After both agreed: [who] writes the polished final answer in the main chat, under "Consensus" / "Verdict". */
        private suspend fun deliverFinal(who: Duo.Who, kind: Duo.Kind, task: String, room: Long) {
            val req = JSONArray().put(JSONObject().put("role", "system").put("content", Duo.deliverSystem(who, Settings.greetName(), kind)))
            val ctx = duoContext(who, if (room >= 0) 8 else 24)
            for (i in 0 until ctx.length()) req.put(ctx.get(i))
            req.put(JSONObject().put("role", "user").put("content",
                (if (room >= 0) Duo.roomTranscript(roomLines(room), who) + "\n\n" else "") +
                "You both agreed. Write the final answer to the user's request now: \"${task.take(1500)}\""))
            val live = LiveMsg(who, -1L)
            val raw = try { duoCall(who, req, null, -1L) { live.show(it) } } catch (e: CancellationException) { live.end(""); throw e }
            var text = duoVisible(raw, final = true)
            val head = "### " + Duo.heading(kind)
            if (text.isNotEmpty() && !text.trimStart().startsWith("#")) text = "$head\n\n$text"
            live.end(text)
            if (text.isNotEmpty()) history.put(JSONObject().put("role", "assistant").put("content", Duo.tag(who) + text))
        }

        /**
         * Retry from a message. On a user message: resend it. On a reply or error: regenerate
         * the answer to the prompt before it. Everything after that prompt is discarded.
         */
        fun retry(messageId: Long) {
            val list = msgs
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx < 0) return
            val userIdx = (idx downTo 0).firstOrNull { list[it].role == Role.USER } ?: return
            val user = list[userIdx]
            val mark = historyMark[user.id] ?: return
            stop()
            // Keep the original user turn (with any attachments) and drop everything after it.
            while (history.length() > mark + 1) history.remove(history.length() - 1)
            list.drop(userIdx + 1).forEach { historyMark.remove(it.id) }
            msgs = list.take(userIdx + 1)
            Voice.lastTurnByVoice = false
            turnFrom = user.id
            beginTurn()
            job = scope.launch(ctxTag + taint + CallState().element()) { runLoop() }
        }

        /**
         * A turn started by the platform, not the user (a background agent's result): it goes into
         * the history as a hidden message and the model answers in this chat. One at a time.
         */
        fun wake(text: String) {
            if (job?.isActive == true) { wakes.add(text); return }
            history.put(JSONObject().put("role", "user").put("content", text))
            taint.clear(); taint.mark(ConfirmGate.AGENT_REPORT)
            mood = Mood.THINKING
            Voice.lastTurnByVoice = false
            beginTurn()
            job = scope.launch(ctxTag + taint + CallState().element()) { runLoop() }
        }

        /** v0.41.0: what the chat lane reads (the newest turns after a Clear screen), without building the full prompt. */
        fun liteHistory(): JSONArray {
            val base = ctxFrom.coerceIn(0, history.length())
            val from = maxOf(base, history.length() - 24)
            val out = JSONArray()
            for (i in from until history.length()) out.put(history.getJSONObject(i))
            return out
        }

        /** Only the newest screenshot is sent as an image; older ones become a note. */
        fun requestMessages(): JSONArray {
            val base = ctxFrom.coerceIn(0, history.length())
            val st = LongChat.State(lcSummary, maxOf(lcUpto, base))
            // One mind's context ladder: recent turns word for word; then the rolling summary + tail; then lookup
            // (pinned core + index + the last few turns, and Blaze looks the rest up).
            var rung = Mind.Rung.RECENT
            var windowed = false
            val from: Int
            if (Settings.longChat) {
                val sFrom = maxOf(LongChat.sendFrom(history, st), base)
                val histTok = LongChat.tokens(history, base)
                rung = Mind.rung(histTok, budget(), lcSummary.isNotBlank(), lcSummary.length / 4 + 40,
                    LongChat.tokens(history, sFrom), ctxWindow(), AutoCompact.pressure(overheadTok, histTok, ctxWindow(), squeezed))
                if (rung == Mind.Rung.LOOKUP && !Mind.ready) rung = if (lcSummary.isNotBlank()) Mind.Rung.SUMMARY else Mind.Rung.RECENT.also { windowed = true }
                from = when {
                    windowed -> maxOf(windowStart(), base)
                    rung == Mind.Rung.RECENT -> base
                    rung == Mind.Rung.SUMMARY -> sFrom
                    else -> Mind.lookupStart(history, base)
                }
            } else { from = maxOf(windowStart(), base); windowed = from > base }
            lastRung = rung
            val sp = specialist
            val sys = if (sp != null) Playbooks.specialistSystem(sp, Playbooks.forSpec(sp), SpecialistMatch.brief(sp, SkillMatch.cached()),
                    SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date()), Memory.promptBlock(lastUserText()), agentId) +
                    Modes.promptLine(agentPlan) + (if (Settings.longChat && rung == Mind.Rung.SUMMARY) LongChat.summaryBlock(st) else "")
                else systemPrompt(lastUserText(), turnSkills.full.map { it.name }.toSet(), id, awareFull,
                    promptGroups, turnView != null || turnDesignNote.isNotEmpty(), promptTurn) + Modes.promptLine(agentPlan) + (if (isPrivate) PrivateChat.PROMPT_LINE else "") +
                (if (Settings.longChat) LongChat.PROMPT_LINE + (if (rung == Mind.Rung.SUMMARY) LongChat.summaryBlock(st) else "") else "") +
                (if (rung == Mind.Rung.LOOKUP) "\n" + Mind.LOOKUP_RULE + "\n" else "") +
                GoalPin.block(turnGoal) +
                (agentId?.let { "\nYou are background agent $it, not the main chat. Work alone and finish with the result only." } ?: BgAgents.leadLine()) +
                (turnDesignNote.ifEmpty { turnView?.let { ViewRouter.hint(it) } ?: "" }) + SkillMatch.block(turnSkills) + SpecialistMatch.hint(turnSpecs) +
                // v0.45.2: desktop requests → desktop_task with the live card; no run_shell installs, no VNC-client advice.
                (if (turnDesk && agentId == null) DesktopRoute.HINT else "") +
                // v0.27.x: "how do I install X" → lead with the action and end with one concrete offer to do it.
                AnswerStyle.turnHint(lastUserText()) +
                // v0.28.4: "take me to the blaze-agent repo" / "what version are you": look it up in Blaze's docs, never guess.
                (if (BlazeDocs.isAboutBlaze(lastUserText())) BlazeDocs.TURN_HINT else "") +
                // v0.26.1: an in-chat diagram / chart is drawn in the reply itself (no files, preview or use_skill).
                (if (turnView == null && turnDesignNote.isEmpty() && Settings.bool("rich_replies", true) && VisualRoute.inChat(lastUserText())) VisualRoute.HINT else "") +
                // v0.31.2 Halo x Blaze: both know the other exists; on "@halo" turns the agent speaks as Halo.
                (if (agentId == null) Duo.awareness(voice) else "") +
                // v0.45.9: the [context] block last (cached ~60 s; built from local data only, never a network call).
                "\n" + TurnContextSource.block(lastUserText())
            val msgs = JSONArray()
            // v0.45.5 (#10): the shared persona first (byte-identical every turn), the app's context second.
            if (sp == null && SharedPersona.applies(Settings.personaId)) SharedPersona.get()?.let { msgs.put(JSONObject().put("role", "system").put("content", it.system)) }
            msgs.put(JSONObject().put("role", "system").put("content", sys))
            var lastShot = -1
            for (i in 0 until history.length()) if (isShot(history.getJSONObject(i))) lastShot = i
            if (windowed && from > base) msgs.put(JSONObject().put("role", "user").put("content",
                "[Earlier in this chat (older messages not shown): ${summary.ifBlank { "use search_chat if you need them" }}]"))
                .also { msgs.put(JSONObject().put("role", "assistant").put("content", "Got it.")) }
            // Only the newest screen (plus the full readout a diff refers to) is sent; older ones are stale.
            val keepScreens = FastActions.screensToKeep((0 until history.length()).map { i -> history.getJSONObject(i).takeIf { it.optString("role") == "tool" }?.optString("content") })
            for (i in from until history.length()) {
                val m = history.getJSONObject(i)
                msgs.put(when {
                    isShot(m) && i != lastShot -> JSONObject().put("role", "user").put("content", "$SHOT_NOTE (older, removed)")
                    // Only the newest screen readout is sent in full; older ones are stale anyway.
                    i !in keepScreens && m.optString("role") == "tool" && FastActions.isScreen(m.optString("content")) ->
                        JSONObject(m.toString()).put("content", FastActions.stripScreen(m.optString("content")))
                    else -> m
                })
            }
            return msgs
        }

        /**
         * Speak whole sentences while the rest of the reply is still streaming in. The last sentence
         * is always held back for announce(), which speaks it as the final utterance.
         */
        fun speakAsItStreams(text: String) {
            if (!(Voice.lastTurnByVoice && Voice.shouldSpeak() && Settings.bool("speak_streaming", true))) return
            if (!text.startsWith(spokenPrefix)) return
            // v0.41.0: narration before a tool call ("Looking at your series…") becomes a step and is never spoken.
            if (spokenPrefix.isEmpty() && !com.blaze.assistant.voice.SpokenText.streamOk(text, streamNoTools)) return
            val ends = Regex("[.!?](\\s|$)|\n").findAll(text, spokenPrefix.length).map { it.range.first + 1 }
                .filter { e -> text.substring(e).isNotBlank() }.toList()
            val end = ends.lastOrNull() ?: return
            if (end - spokenPrefix.length < 2) return
            Voice.speakPart(text.substring(spokenPrefix.length, end))
            spokenPrefix = text.substring(0, end)
            streamedVoice = true
            roundSpoke = true
        }

        /**
         * Long chats (v0.15.0), automatic compaction (v0.26.0): when the un-summarised part is over the budget, or the
         * whole request nears the model's context window, everything before the recent tail folds into the rolling
         * summary with one background call, in this same chat and without asking. The chat on screen is untouched;
         * only what is sent changes. The summary is saved with the chat (lc_summary / lc_upto), so it is updated,
         * never redone each turn. [force] = the provider just refused the request as too long: keep fewer messages
         * word for word and, if the summariser fails, fold a clipped transcript instead. True when something folded.
         */
        suspend fun compact(force: Boolean = false): Boolean {
            if (!Settings.longChat) return false
            if (lcUpto > history.length()) { lcUpto = 0; lcSummary = "" }
            if (lcUpto < ctxFrom && ctxFrom <= history.length()) { lcUpto = ctxFrom; lcSummary = "" }
            val st = LongChat.State(lcSummary, lcUpto)
            if (!AutoCompact.shouldCompact(history, st, budget(), overheadTok, ctxWindow(), force)) return false
            val r = AutoCompact.range(history, st, force)
            val req = LongChat.summariseRequest(lcSummary, history, r.first, r.last + 1)
            var txt = try {
                LlmClient.complete(req, null, null, null, LlmClient.Lane.BACKGROUND).optString("content").substringAfterLast("</think>").trim()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
            if (txt.isBlank() && force) txt = AutoCompact.fallbackNote(lcSummary, history, r.first, r.last + 1)
            if (txt.isBlank()) return false
            val ns = LongChat.folded(history, txt, AutoCompact.keep(force))
            lcSummary = ns.summary; lcUpto = ns.upto
            persist()
            return true
        }

        private suspend fun longChatTool(name: String, args: JSONObject): String {
            val snap = JSONArray(history.toString())
            val me = id
            val load: (String) -> JSONArray? = { c -> if (c == me) snap else ChatStore.load(c)?.optJSONArray("history") }
            return when (name) {
                "search_chat" -> if (Mind.ready) { add(Role.STEP, "Looking back through your conversations for \u201c${args.optString("query").take(40)}\u201d")
                    kotlinx.coroutines.withContext(Dispatchers.IO) { Mind.journal(me, snap); Mind.searchChat(args.optString("query"), args.optInt("limit", 8)) } }
                else { add(Role.STEP, "Looking back through ${if (args.optBoolean("all_chats")) "your chats" else "this chat"} for “${args.optString("query").take(40)}”")
                    kotlinx.coroutines.withContext(Dispatchers.IO) { LongChat.searchText(args.optString("query"), me, args.optBoolean("all_chats"), args.optInt("limit", 8), load) { ChatStore.list().map { it.id } } } }
                "read_turn" -> { add(Role.STEP, "Reading an earlier message"); kotlinx.coroutines.withContext(Dispatchers.IO) { Mind.readTurn(args.optString("id")) ?: LongChat.readTurn(args.optString("id"), me, load) } }
                "read_artifact" -> { add(Role.STEP, "Reading the saved output"); kotlinx.coroutines.withContext(Dispatchers.IO) { LongChat.readArtifact(args.optString("id"), args.optInt("from", 1), args.optInt("lines", 200)) } }
                "update_brief" -> {
                    val p = Projects.of(me) ?: return "This chat isn't in a project, so there's no brief to update. Keep going without it."
                    add(Role.STEP, "Updating the project brief")
                    // v0.28.5: capped at 600 tokens (squeezed, then its top part; the whole text and the old brief are kept as notes).
                    Projects.updateBrief(p.id, args.optString("brief"))
                }
                else -> "Unknown tool"
            }
        }

        /** First history index to send: 0 for short chats, else a user message 24 to 48 messages back. */
        fun windowStart(): Int {
            val n = history.length()
            val keep = Settings.str("history_window", "24").toIntOrNull()?.coerceIn(8, 400) ?: 24
            if (n <= keep * 2) return 0
            // Jump in blocks of [keep] so the start only moves once every [keep] messages.
            var cut = ((n - keep) / keep) * keep
            while (cut < n) {
                val m = history.getJSONObject(cut)
                if (m.optString("role") == "user" && !isShot(m)) return cut
                cut++
            }
            return 0
        }

        fun isShot(m: JSONObject): Boolean {
            val c = m.opt("content") as? JSONArray ?: return false
            return c.optJSONObject(0)?.optString("text") == SHOT_NOTE
        }

        private var repairedThisStep: List<String> = emptyList()

        /** Rough closeness of two tool names, for "did you mean" hints. */
        private fun levGuess(a: String, b: String): Int {
            val x = a.lowercase(); val y = b.lowercase()
            val common = x.split('_').count { it.isNotBlank() && y.contains(it) }
            return kotlin.math.abs(x.length - y.length) - common * 6
        }

        suspend fun runLoop() {
            var turnEnd = TurnEnd.Reason.DONE   // v0.45.6: why the turn ended, for the silent-ending watchdog
            turnDesignRender = null; turnDesignRenderSet = false
            val bytes0 = DataMeter.used()
            // v0.41.0 speed: every phase of the turn is timed (TurnTimer → logcat BlazeSpeed, slow log, speed log).
            val timer = TurnTimer()
            if (agentId == null) StartTrace.stage("turn")   // v0.45.9: finer marks before the wire
            // v0.45.9 ChipGate: chips only offer what this phone can do (calendar, weather, music, SMS…); local checks only.
            // Off the send path: the reply's chips are read long after this lands.
            if (agentId == null) io.launch { try { ChipGate.caps = TurnContextSource.caps() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: ChipGate.caps", e) } }
            // 0.45.16: an explicit topic switch drops the earlier topic's names from this turn's chips (ChipGate.stale).
            if (agentId == null) try { val ask = lastUserText(); val prev = msgs.takeLast(13).filter { it.text != ask }; ChipGate.stale = ChipGate.staleTerms(ask, prev.map { it.text }, prev.filter { it.role == Role.USER }.map { it.text }) } catch (e: Throwable) { ChipGate.stale = emptySet() }
            Memory.turnKey = timer.start
            promptTurn = timer.start   // v0.45.1: the system prompt's live tail is kept for this turn's steps
            // v0.42.0 Cloud runs: this turn's run on the gateway (null = the local loop, as before).
            var cloudTurn: CloudRuns.Turn? = null
            var turnModel: TurnModel? = null
            try {
                val spNow = specialist
                val routine = lastUserText().startsWith("[Scheduled routine")
                // v0.26.2: a specialist run has all its own (already filtered) tools on, no views and no chat lane.
                val bulk = routine || spNow != null
                val userNow = lastUserText()
                // v0.41.0 speed: the independent lookups before the first model call (tool list, current design, skills,
                // specialists, plan memory) start together instead of one after another. Each is cheap to throw away.
                val stateQ0 = !routine && PhoneState.kind(userNow) != null
                val selfQ0 = !routine && BlazeDocs.isAboutBlaze(userNow)
                // v0.45.2: a request about the Linux desktop goes to the desktop agent (live view in chat), never the phone's fast path.
                val desk0 = !routine && !bulk && DesktopRoute.wants(userNow,
                    try { com.blaze.assistant.desktop.LinuxDesktop.state.value.phase == com.blaze.assistant.desktop.DesktopPlan.Phase.RUNNING } catch (_: Throwable) { false },
                    DesktopRoute.mentioned(recentText(6)))
                turnDesk = desk0
                val plainDev0 = !routine && !bulk && !desk0 && FastActions.plainDevice(userNow)
                val par = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext + Dispatchers.IO)
                // v0.31.1 jank: the tool list (hundreds of JSON objects) is built off the main thread.
                val dTools = par.async(Dispatchers.Default) { tools().apply { put(ToolPick.loadTool()) }.let { if (isPrivate) PrivateChat.filterTools(it) else it }
                    .let { if (Projects.of(id) == null) ProjectDocs.strip(it) else it } }
                val dDesign = par.async { if (bulk) null else try { Designs.current() } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
                val dSkills = par.async { SkillMatch.forTurn(userNow) }
                val dLearned = par.async { if (plainDev0) SkillMatch.learnedOnly(userNow) else null }
                val dSpecs = par.async {
                    if (routine || stateQ0 || selfQ0 || plainDev0 || agentId != null || !Settings.bool("specialists_on", true)) emptyList()
                    else try { SpecialistMatch.match(userNow, Specialists.all()) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                }
                val dPlan = par.async {
                    // 0.45.15 (device /phoneqa 22:40): an ask a direct phone tool does (set_alarm, set_timer, compose_sms,
                    // add_contact, device_setting, open_settings then tap) never gets the saved tap plan: the plan hint made
                    // codestral write run_plan JSON as text instead of calling the tool the request names.
                    if (routine || bulk || agentId != null || isPrivate || !Settings.phoneControl || ToolTruth.directPhone(userNow)) ""
                    else try { TapLoop.Plans.find(userNow)?.let { TapLoop.hint(it) } ?: "" } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
                }
                // Speed plan step 1: core tools + the groups this request (and this chat) needs.
                val groupsOn = HashSet<String>().apply {
                    if (bulk) addAll(ToolRouter.groups.map { it.id })
                    val lu = userNow
                    val g0 = kotlinx.coroutines.withContext(Dispatchers.Default) { ToolRouter.guess(lu) + ToolRouter.usedIn(history) }
                    addAll(g0)
                    // v0.33.1 learned tool router: groups this phone's own past turns say these words need (adds only).
                    if (!bulk && agentId == null && !isPrivate) addAll(kotlinx.coroutines.withContext(Dispatchers.Default) { LearnedRoutes.predict(lu, g0) })
                    // v0.45.0 (#20): a request in another language that no English cue matched still gets the core action groups.
                    if (!bulk) addAll(ToolRouter.fallback(lu, this))
                    if (desk0) add(DesktopRoute.GROUP)
                    // v0.45.9: a calendar / meetings / schedule question always gets the calendar and reminder tools.
                    if (!bulk && TurnContext.scheduleAsk(lu)) add("schedule")
                    // v0.45.9: "@halo Yes" to "I can check your Google Calendar, want me to?" gets them too.
                    if (!bulk && TurnContext.scheduleFollowUp(lu, msgs.lastOrNull { it.role == Role.BLAZE }?.text.orEmpty())) add("schedule")
                    // v0.45.0 (#13): Simple mode keeps the tools, but terminal / PC / connectors load only on a strong match
                    // (or when this chat already used them).
                    if (!bulk && SimpleMode.simple) { val usedG = ToolRouter.usedIn(history); removeAll { it !in usedG && !PromptProfile.simpleGroupOk(it, lu) } }
                }
                timer.mark("route"); if (agentId == null) StartTrace.stage("route")
                // v0.45.1 speed: the memory block (kept per turn by PromptMemo) and the orient screen read start now, while
                // the tool list, skills and specialists are still being looked up. The screen is read on the main thread as
                // before (the accessibility service's element numbers live there); it just no longer waits its turn.
                par.launch(Dispatchers.Default) { try { Memory.promptBlock(userNow) } catch (e: CancellationException) { throw e } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: memory warm", e) } }
                val dOrient = if (!routine && "screen" in groupsOn) par.async(Dispatchers.Main) {
                    try { orientRead() } catch (e: CancellationException) { throw e } catch (_: Throwable) { "" to "" } } else null
                val allTools0 = dTools.await()
                // Agents don't start or steer other agents; Plan keeps only read-only tools.
                val allTools = Modes.filterTools(if (agentId == null) allTools0 else JSONArray().apply {
                    // v0.26.2: a background agent running as a specialist gets only its groups' tools (like delegate's runs).
                    val src = if (spNow != null) Specialists.filter(allTools0, spNow) else allTools0
                    for (i in 0 until src.length()) src.getJSONObject(i).let { t -> if (t.optJSONObject("function")?.optString("name") !in BgAgents.leadOnly) put(t) } }, agentPlan)
                timer.mark("tools"); if (agentId == null) StartTrace.stage("tools")
                // 0.45.19: every bubble's final sanitizer knows this app's tool names (CallScan).
                try { CallScan.learn(ToolCalls.names(allTools0)) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("CallScan.learn", e) }
                // v0.23.0 views + skills: the view this request needs (its tools on) and the skills that match it,
                // looked up before the first model call with no extra model call. Shown on the work card.
                // v0.23.2 follow-on edits: right after a design (or from the Design view's own box), "show engine" edits that design.
                val curDesign = dDesign.await()
                val followOn = !routine && agentId == null && curDesign != null &&
                    (lastTurnDesign || com.blaze.assistant.ui.Views.showing.value == "design" || Designs.recent())
                turnView = if (bulk) null else ViewRouter.route(userNow, followOn)
                turnDesignNote = if (followOn && curDesign != null && turnView?.id == "design" && ViewRouter.route(userNow)?.id != "design")
                    ViewRouter.followOnHint(curDesign.title, curDesign.kind, curDesign.path) else ""
                turnOk.clear()
                turnDone.clear()
                turnSetResults.clear(); goalNudged = false
                turnBook = VerifyBook()
                oneShot.clear(); loopGuard.clear()
                // v0.45.0 (#18): "no, that's wrong" right after a saved plan → that plan is never replayed.
                lastPlanKey?.let { k -> if (PlanGate.correction(userNow)) try { TapLoop.Plans.drop(k) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: TapLoop.Plans.dropk", e) } }
                lastPlanKey = null
                // v0.45.0 (#38): a resumed task keeps the goal (and constraints) it was stopped with.
                val resuming = resumeGoal != null
                turnGoal = if (bulk) null else resumeGoal ?: GoalPin.of(userNow, GoalPin.userTexts(history).let { l -> if (l.lastOrNull()?.contains(userNow.trim().take(40)) == true) l.dropLast(1) else l })
                resumeGoal = null
                var nudgeCount = 0
                var rewroteClaim = false
                var claimNudged = false
                var proofNudged = false
                var emptyNudged = false
                var echoNudged = false
                var leakNudged = false
                var credGuarded = false   // 0.45.19 CredAsk: one swap per turn
                var vncNudged = false
                var refusalNudged = false
                var lastOkStep = ""
                var savedPath: String? = null   // v0.45.9: a save_file this turn; the final answer names its path
                var turnToolCalls = 0   // v0.45.9: tools run this turn (ScheduleTruth checks only answers made from the [context] block)
                turnView?.let { groupsOn += it.groups }
                turnSkills = dSkills.await()
                // v0.26.2: a phone-state question ("am I on mute?") is answered from the Phone now line: no skill packs, no tool loop.
                val stateQ = stateQ0
                // v0.28.4: a question about Blaze itself ("take me to blaze-agent repo in github") is a docs lookup: no coding skills
                // (≈ 6,000 chars of them were pulled in by "repo" / "github") and no Coder specialist.
                val selfQ = selfQ0
                if (stateQ || selfQ) turnSkills = SkillMatch.Result.NONE
                // v0.30.4 fast actions: a plain device / settings action ("stop GitHub opening links") gets no skill packs
                // (only a learned, replayable skill of the user's own on a strong match), no specialists, none of the heavy
                // tool groups its words happen to hit ("github" → terminal, apps), and the screen tools up front.
                val plainDev = plainDev0
                if (plainDev) {
                    turnSkills = dLearned.await() ?: SkillMatch.learnedOnly(userNow)
                    groupsOn.removeAll(FastActions.heavyGroups - ToolRouter.usedIn(history) - (turnView?.groups ?: emptyList()).toSet())
                    groupsOn += "screen"; groupsOn += "device"
                }
                // v0.24.3 specialists: the matching specialist(s) bring their skill packs and the delegate tool.
                val lateSteps = ArrayList<String>()   // v0.45.3: optional look-ups that missed their PreBudget
                turnSpecs = PreBudget.within(dSpecs, PreBudget.SPECS_MS, emptyList(), "specialists") { lateSteps += it }
                if (turnSpecs.isNotEmpty()) {
                    turnSkills = SpecialistMatch.attach(turnSkills, turnSpecs, SkillMatch.cached())
                    groupsOn += "extras"
                    add(Role.STEP, SpecialistMatch.cardLine(turnSpecs))
                }
                SkillMatch.cardLine(turnSkills).takeIf { it.isNotBlank() }?.let { add(Role.STEP, it) }
                var learnNudged = false
                // v0.44.0 fewer tools per turn: ToolRouter's groups, then ToolPick (core split into cues, capped).
                val usedNames = ToolPick.usedNames(history)
                if (turnView != null) groupsOn += "make"
                var sizeLine = ""
                // v0.45.0 context: once the model asks for a tool it wasn't sent (or one that doesn't exist), the rest of the
                // turn gets the full tool set instead of a guessed subset.
                var widened = false
                var sentOrder = emptyList<String>()   // v0.45.1: tools keep their place across the turn's steps (prompt caching)
                fun pickTools(): JSONArray {
                    if (widened) { sizeLine = "tools all ${allTools.length()} (widened)"; return allTools }
                    // v0.45.9 (ToolTruth): every agent request carries the full registered list (gateway 0.8.2 drops calls to tools not in it).
                    if (ToolTruth.full) { sizeLine = "tools all ${allTools.length()}"; return ToolTruth.forRequest(allTools) { allTools } }
                    val diet = ToolRouter.select(allTools, groupsOn)
                    val sent = ToolPick.keepOrder(sentOrder, ToolPick.pick(diet, userNow, usedNames + synchronized(turnTools) { turnTools.toSet() }, groupsOn, keepAll = bulk,
                        must = (if (DesktopRoute.GROUP in groupsOn) DesktopRoute.TOOLS else emptySet()) +
                            (if (TurnContext.scheduleAsk(userNow)) TurnContext.SCHEDULE_TOOLS else emptySet())))
                    sizeLine = ToolPick.sizeLine(sent, allTools, diet)
                    sentOrder = ToolPick.names(sent)
                    return sent
                }
                var tools = pickTools()
                orient.reset(); lastPlan = null
                planRec.reset(); screenActed = false
                // v0.30.7 plan memory: this request worked before → its steps go with the first call as one run_plan to send.
                val planHint = PreBudget.within(dPlan, PreBudget.PLAN_MS, "", "plan") { lateSteps += it }
                if (planHint.isNotEmpty()) { groupsOn += "screen"; groupsOn += "device"; add(Role.STEP, "Using the steps that worked last time") }
                timer.mark("prep")
                val orientNote = (if (!routine && "screen" in groupsOn) (dOrient?.let { d -> PreBudget.within(d, PreBudget.ORIENT_MS, "" to "", "orient") { lateSteps += it } }?.let { (pkg, note) -> if (pkg.isNotEmpty()) orient.saw(pkg); note } ?: orientContext())
                    else { dOrient?.cancel(); "" }).let { o ->
                    if (planHint.isEmpty()) o else if (o.isBlank()) planHint else "$o\n\n$planHint" }
                timer.mark("orient")
                var orientSent = false
                // Chat lane: small talk and plain questions = one light call with no tools.
                val lastBlaze = msgs.lastOrNull { it.role == Role.BLAZE }?.text.orEmpty()
                var lite = !bulk && !resuming && voice != Duo.Who.HALO && planHint.isEmpty() && history.optJSONObject(history.length() - 1)?.opt("content") is String &&
                    ChatLane.fits(lastUserText(), lastBlaze) && turnView == null && turnSkills.full.isEmpty()
                var firstMs = -1L
                // v0.45.3 speed_stats: when the first words appeared on screen, and the gateway's time to answer headers.
                var shownAt = -1L; var hdrMs = -1L
                streamedVoice = false
                while (trace.length() > 0) trace.remove(0)
                turnTools.clear()
                turnAppsCard = null
                val turnThink = StringBuilder()
                val turnStart = System.currentTimeMillis()
                thinking = ""
                var gateLeft = 2
                var actedThisTurn = false
                // v0.27.x: any tool ran (lookups too), corrective re-prompts left this turn (all kinds), designs shown this turn.
                var lookedThisTurn = false
                var nudgesLeft = RetryGuard.MAX
                Designs.touched.clear()
                val lastResults = ArrayList<String>()
                // Autopilot: a bigger step budget and a clock; normal turns keep the old cap.
                val autopilot = Modes.autopilot && !routine
                val maxSteps = if (autopilot) Modes.apSteps else MAX_STEPS
                val deadline = if (autopilot) turnStart + Modes.apMinutes * 60_000L else Long.MAX_VALUE
                // v0.45.9 (TurnDeadline): a hard end for every turn, checked during each model step too (the 56-minute reply).
                val longWork = bulk || routine || agentId != null || resuming || userNow.length > 280
                fun hardEnd(): Long = turnStart + TurnDeadline.turnMs(autopilot, Modes.apMinutes, longWork || synchronized(turnTools) { turnTools.size } >= 3)
                var autoReplies = 0
                // v0.42.0 Cloud runs: a normal chat turn goes to the gateway once, as a run it keeps until the phone has the
                // reply. Private chats, background agents, specialists, routines and Halo turns stay on the local loop.
                cloudTurn = if (!bulk && voice != Duo.Who.HALO && CloudRuns.eligible(agentId == null, isPrivate, FastLane.online(app)))
                    CloudRuns.Turn(id, "t-$id-$turnFrom-$turnStart") else null
                val ct = cloudTurn
                // v0.45.0 (#17): one turn id and one preferred route for every step of a main-chat turn.
                turnModel = if (agentId == null && !bulk) TurnModel("t-$id-$turnStart").also { TurnModel.current = it } else null
                // v0.45.0 (#6): a phone step's screenshot is put into words here, so the run continues (no new run, no re-upload).
                ct?.describe = { shot ->
                    kotlinx.coroutines.withTimeoutOrNull(VisionFast.TIMEOUT_MS) {
                        LlmClient.complete(CloudRuns.describeRequest(shot, lastUserText()), null, null, null, LlmClient.Lane.STEPS)
                            .optString("content").let { ReplyParts.split(it, final = true).text }.takeIf { it.isNotBlank() }
                    }
                }
                for (step in 0 until maxSteps) {
                    currentStep = step + 1
                    if (step > 0) timer.mark("tool work")
                    // v0.30.6 rapid messages: what you sent while Blaze worked (already in the chat) joins here, before the next model call.
                    takeInbox(midTurn = step > 0)
                    // Steering: what the user added while this chat worked joins before the next model call.
                    generateSequence { steer.poll() }.toList().takeIf { it.isNotEmpty() }?.let { notes ->
                        notes.forEach { n -> add(Role.USER, n) }
                        history.put(JSONObject().put("role", "user").put("content", "[The user adds, while you work: ${notes.joinToString("\n")}]"))
                    }
                    if (System.currentTimeMillis() > deadline) {
                        add(Role.BLAZE, "Autopilot stopped at its time budget (${Modes.apMinutes} min). Say \"continue\" to keep going.")
                        break
                    }
                    if (System.currentTimeMillis() > hardEnd()) { add(Role.BLAZE, TurnDeadline.line(hardEnd() - turnStart)); break }
                    mood = Mood.THINKING
                    status = Friendly.between(turnTools.lastOrNull())
                    var liveId = -1L
                    spokenPrefix = ""
                    roundSpoke = false
                    streamNoTools = lite
                    val before = turnThink.toString()
                    var pendingFollowups = emptyList<String>()
                    val liteNow = lite
                    // v0.31.0 token streaming: every chunk from the stream lands in [gate]; the chat is updated at most once
                    // per display frame on the main thread with the newest text (older chunks in between are dropped).
                    val gate = StreamGate(scope) { partial ->
                        val p = ReplyParts.split(partial)
                        if (p.thinking.isNotEmpty()) thinking = listOf(before, p.thinking).filter { it.isNotBlank() }.joinToString("\n\n")
                        // A tool call the model is writing as text never flashes up as a reply.
                        // v0.30.6: the [re:m7] marker is never shown or spoken (an unfinished one waits until it's whole).
                        val seen = RapidMessages.display(p.text, final = false)
                        if (seen.isNotEmpty() && !(liveId < 0 && FakeCall.callish(seen))) {
                            if (liveId < 0) { liveId = add(Role.BLAZE, seen); status = "Writing the reply"; if (shownAt < 0) shownAt = System.currentTimeMillis() } else replace(liveId, Role.BLAZE, seen)
                            streamId = liveId
                            // v0.42.0: a run's streamed text can be taken back (reset), so voice waits for the final reply.
                            if (ct?.streaming != true) speakAsItStreams(seen)
                        }
                    }
                    val onText: ((String) -> Unit)? = if (Settings.streamReplies) { partial ->
                        if (!(liteNow && partial.trimStart().let { it.startsWith("[") || it.startsWith("TOOLS", true) })) gate.offer(partial)
                    } else null
                    liveGate = gate   // v0.45.0 (#5): Stop closes it at once, so no queued frame lands after "Stopped."
                    val onThink: (String) -> Unit = { t -> if (!gate.isClosed) scope.launch { if (!gate.isClosed) thinking = listOf(before, t).filter { it.isNotBlank() }.joinToString("\n\n") } }

                    // v0.30.7: no blocking summarise call in the middle of a phone task (a context error below still folds).
                    if (!lite && TapLoop.compactNow(step, screenActed)) compact()
                    LlmClient.liteTurn = lite
                    awareFull = stateQ || Awareness.needsFull(userNow, groupsOn)
                    // v0.31.1 jank: the system prompt, memory lookup and the whole request JSON are built off the main thread.
                    // v0.41.0 speed: the chat lane never builds the full system prompt (memory, awareness, skills, ~40k chars)
                    // just to throw it away: ChatLane only reads the last few turns.
                    promptGroups = groupsOn.toSet()
                    var req = kotlinx.coroutines.withContext(Dispatchers.Default) {
                        if (lite) { if (SharedPersona.applies(Settings.personaId) && SharedPersona.get() != null)
                            SharedPersona.prepend(ChatLane.messages(liteHistory(), lastUserText(), SharedPersona.contextLine(null, ""), Settings.greetName(), TurnContextSource.block(lastUserText())))
                        else ChatLane.messages(liteHistory(), lastUserText(), personaText(), Settings.greetName(), TurnContextSource.block(lastUserText())) } else requestMessages() }
                    // Orient loop, step 1 (context → orientation): where the phone is, sent with the first call only, never saved.
                    val orientNow = !lite && !orientSent && orientNote.isNotEmpty()
                    if (orientNow) { req.put(JSONObject().put("role", "user").put("content", orientNote)); orientSent = true }
                    tools = if (lite) JSONArray() else pickTools()
                    // v0.45.9 (ToolTruth): the tool list each request carries (count + names), in logcat and the speed line.
                    run { val t0 = tools; try { android.util.Log.i("BlazeTools", "step ${step + 1}: " + ToolTruth.line(t0)) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("tools-line", e) } }
                    // v0.44.0 model routing: an action turn goes to the strong lineup (ModelRoute); plain chat stays fast.
                    // v0.45.3: Autopilot no longer makes every turn "heavy": a plain question on Autopilot (the default) took the
                    // strong lineup with the small fast routes pushed last. Routines / specialists (bulk) still always do.
                    LlmClient.heavyTurn = !lite && ModelRoute.heavy(userNow, groupsOn, synchronized(turnTools) { turnTools.size }, bulk)
                    // v0.26.0 auto-compaction: what the system prompt and tools cost, so the next fold sees the whole request.
                    if (!lite) { val r0 = req; val t0 = tools; overheadTok = kotlinx.coroutines.withContext(Dispatchers.Default) { ((r0.optJSONObject(0)?.optString("content")?.length ?: 0) + t0.toString().length) / 4 } }
                    timer.mark("prompt"); if (agentId == null) StartTrace.stage(if (cloudTurn != null) "prompt (run)" else if (lite) "prompt (chat lane)" else "prompt (agent)")
                    // Watchdog: only a model that goes SILENT (no text, thinking or tool-call chunks) for the
                    // limit is cut off and retried once on the same model. Long thinking is fine while it streams.
                    val lane = if (step == 0) LlmClient.Lane.MAIN else LlmClient.Lane.STEPS
                    val limit = Settings.str("model_timeout_s", "100").toLongOrNull()?.coerceIn(30, 600)?.times(1000) ?: 100_000L
                    // v0.42.0: the turn's Cloud run when there is one (null = this call goes the old way).
                    // v0.45.9 (TurnDeadline): a Cloud run step is cut at CLOUD_STEP_MS (cancelled on the gateway, then sent direct once);
                    // any step at STEP_MS or the turn's hard end, whichever is first.
                    suspend fun model(r: JSONArray): JSONObject {
                        val budget = TurnDeadline.stepBudget(hardEnd(), System.currentTimeMillis())
                        val t0 = System.currentTimeMillis()
                        if (ct != null && !ct.off) {
                            var ended = false
                            val got = kotlinx.coroutines.withTimeoutOrNull(minOf(budget, TurnDeadline.CLOUD_STEP_MS)) {
                                CloudRuns.step(ct, r, tools, onText, { line -> scope.launch { if (mood != Mood.IDLE) status = line } },
                                    { a -> val g = ackGate; val u = turnFrom; val said = lastUserText(); scope.launch { if (g != null) showAck(g, a, u, said) } }).also { ended = true }
                            }
                            if (got != null) return got
                            if (!ended) { ct.off = true; LlmClient.lastCloudError = "cloud run: step over ${TurnDeadline.CLOUD_STEP_MS / 1000}s"; add(Role.STEP, TurnDeadline.CLOUD_SLOW) }
                        }
                        val left = maxOf(5_000L, budget - (System.currentTimeMillis() - t0))
                        return kotlinx.coroutines.withTimeoutOrNull(left) { LlmClient.complete(r, tools, onText, onThink, lane) }
                            ?: throw LlmClient.LlmException(TurnDeadline.line(hardEnd() - turnStart))
                    }
                    suspend fun callPlain(r: JSONArray): JSONObject = (Agent.idleGuard(limit) { model(r) }
                        ?.also { gate.flush() })
                        ?: run {
                            gate.flush()
                            add(Role.STEP, "The model went silent, retrying")
                            if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                            Agent.idleGuard(limit) { model(r) }
                                ?: throw LlmClient.LlmException("${LlmClient.lastRoute.ifBlank { "The model" }} sent nothing for ${limit / 1000}s, twice. Tap Retry, or send a shorter message.")
                        }
                    suspend fun call(r0: JSONArray): JSONObject {
                        var r = r0
                        // v0.33.3: a call that must read a fresh screenshot gets 12 s; then the picture becomes the screen
                        // as text and the same request goes on the gateway's text route (much faster on a slow phone/network).
                        if (VisionFast.hasFreshShot(r, SHOT_NOTE)) {
                            // v0.45.0 (#5): the screenshot call is a child of this turn (Stop cancels it and closes its socket),
                            // with its own call state; [live] goes false in a finally, so nothing it streams lands after Stop or the timeout.
                            val live = java.util.concurrent.atomic.AtomicBoolean(true)
                            val st = CallState.current().fork()
                            val got = kotlinx.coroutines.supervisorScope {
                                val d = async(Dispatchers.IO + st.element()) {
                                    LlmClient.complete(r, tools, onText?.let { f -> { t: String -> if (live.get()) f(t) } }, { t: String -> if (live.get()) onThink(t) }, lane) }
                                try { kotlinx.coroutines.withTimeoutOrNull(VisionFast.TIMEOUT_MS) { d.await() } }
                                catch (e: LlmClient.LlmException) { if (LlmClient.tooLarge(e)) throw e; null }
                                finally { if (!d.isCompleted) { live.set(false); d.cancel() }; st.publish() }
                            }
                            if (got != null) { gate.flush(); return got }
                            live.set(false); gate.flush()
                            if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                            add(Role.STEP, "Reading the screen as text (the picture was slow)")
                            val text = com.blaze.assistant.phone.BlazeAccessibilityService.instance?.readScreen() ?: "Phone control isn't connected, so the screen can't be read as text."
                            r = VisionFast.withoutShots(r, SHOT_NOTE, text)
                            VisionFast.pruneShots(history, SHOT_NOTE)
                        }
                        return callPlain(r)
                    }
                    val reply = try { call(req).also { streamId = -1L } } catch (e: LlmClient.LlmException) {
                        gate.flush(); streamId = -1L
                        // v0.26.0: "too long for the model" → fold older turns into the summary (same chat, nothing
                        // asked) and retry once. Only if that still fails does the user see an error.
                        if (liteNow || !(LlmClient.tooLarge(e) || AutoCompact.isContextError(e.message, e.code))) throw e
                        if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                        squeezed = true
                        val folded = compact(force = true)
                        val retry = requestMessages()
                        if (!folded && LongChat.tokens(retry) >= LongChat.tokens(req)) throw e
                        if (orientNow) retry.put(JSONObject().put("role", "user").put("content", orientNote))
                        req = retry
                        call(req).also { streamId = -1L }
                    }
                    if (firstMs < 0 && LlmClient.lastFirstTokenAt > 0) { firstMs = (LlmClient.lastFirstTokenAt - turnStart).coerceAtLeast(0); timer.add("first word", LlmClient.lastFirstTokenMs)
                        if (LlmClient.lastHeadersMs > 0) { hdrMs = LlmClient.lastHeadersMs; timer.add("headers", hdrMs) }
                        if (LlmClient.lastFirstTextMs > 0 && LlmClient.lastFirstTextMs != LlmClient.lastFirstTokenMs) timer.add("first text", LlmClient.lastFirstTextMs) }
                    timer.mark(if (liteNow) "chat model" else "model")
                    reply.optString("_reasoning").takeIf { it.isNotBlank() }?.let { if (turnThink.isNotEmpty()) turnThink.append("\n\n"); turnThink.append(it) }
                    reply.remove("_reasoning")
                    try { Usage.record(req, reply, LlmClient.lastUsage) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: Usage.recordreq reply LlmClient.lastUsag", e) }
                    // v0.33.1 token ledger: where this call's input tokens went (system prompt, schemas per group, results per tool…).
                    run { val r0 = req; val t0 = tools; val pp = if (lite) emptyList() else lastPromptParts
                        kotlinx.coroutines.withContext(Dispatchers.Default) { TokenLedger.record(r0, t0); TokenLedger.recordSections(pp) } }
                    try {
                        val u = LlmClient.lastUsage
                        val inT = u?.optInt("prompt_tokens")?.takeIf { it > 0 } ?: (LongChat.tokens(req) + tools.toString().length / 4)
                        val outT = u?.optInt("completion_tokens")?.takeIf { it > 0 } ?: (LongChat.text(reply).length / 4)
                        meter = Meter(inT, outT, LongChat.meterLimit(ctxWindow()))
                    } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: val u LlmClient.lastUsage", e) }
                    if (lite) {
                        lite = false
                        LlmClient.liteTurn = false
                        val said = (reply.opt("content") as? String).orEmpty()
                        if (ChatLane.escaped(said) || (reply.optJSONArray("tool_calls")?.length() ?: 0) > 0) {
                            // It needs tools after all: rerun this turn on the full lane.
                            if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                            continue
                        }
                    }
                    // Gatekeeper: a call written as text becomes a real call; glued/typo'd names,
                    // broken JSON and wrong types are repaired before anything runs or is sent back.
                    var unknownCalls: Set<String> = emptySet()
                    try {
                        if ((reply.optJSONArray("tool_calls")?.length() ?: 0) == 0) {
                            val txt = reply.opt("content") as? String
                            val seen = if (txt != null) ReplyParts.split(txt, keepCalls = true).text else ""
                            // 0.45.19: CallScan first: it reads multi-line Python calls, list / object arguments, JSON calls mid-prose,
                            // fenced tool blocks and cut-off calls, which the older parsers missed or cut at the first comma.
                            if (txt != null) (CallScan.toCalls(seen, allTools) ?: ToolCalls.fromText(seen, allTools) ?: ToolLeak.toCalls(seen, allTools) ?: TextCalls.toCalls(seen, allTools) ?: TextCalls.blockCalls(seen, allTools))?.let { (c, rest) ->
                                reply.put("tool_calls", c); reply.put("content", if (rest.isBlank()) JSONObject.NULL else CallScan.clean(TextCalls.strip(ToolLeak.strip(rest))))
                                for (k in 0 until c.length()) ToolStats.add(c.getJSONObject(k).getJSONObject("function").getString("name"), "text_call")
                            }
                            // v0.28.1: tool syntax that maps to no real tool: strip it and ask the model once to answer properly
                            // (native tool call or plain words); after that, an honest plain line, never the raw syntax.
                            if (txt != null && (reply.optJSONArray("tool_calls")?.length() ?: 0) == 0 && (ToolLeak.has(seen) || TextCalls.has(seen, ToolCalls.names(allTools)) || TextCalls.blockHas(seen, ToolCalls.names(allTools)) || CallScan.has(seen, ToolCalls.names(allTools)))) {
                                val left = CallScan.clean(TextCalls.blockStrip(ReplyParts.split(txt, final = true).text, ToolCalls.names(allTools)), true, ToolCalls.names(allTools), loose = true)
                                if (!leakNudged && nudgesLeft > 0 && step < maxSteps - 2) {
                                    leakNudged = true; nudgesLeft--
                                    if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                    add(Role.STEP, "Getting a proper answer")
                                    if (left.isNotBlank()) history.put(JSONObject().put("role", "assistant").put("content", left))
                                    history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(if (ToolLeak.has(seen)) ToolLeak.NUDGE else TextCalls.NUDGE, lastUserText())))
                                    continue
                                }
                                reply.put("content", left.ifBlank { ToolLeak.HONEST })
                            }
                        }
                        val rep = ToolCalls.normalize(reply, allTools)
                        unknownCalls = rep.unknown
                        repairedThisStep = rep.repaired
                        // v0.45.13 round 3: a split switch tap (tap{text} + tap{want}) runs as one tap with want, so it's read back.
                        if (ToolTruth.mergeTaps(reply) > 0) add(Role.STEP, "Combined the switch tap")
                        // 0.45.15: the named flow tool alone on its step (no blind run_plan beside open_settings), and
                        // "tomorrow" / "today" events on the day the user said.
                        try {
                            val named = ToolTruth.choiceName(history, ToolTruth.names(tools))
                            ReplyFix.keepFlowTool(reply, named, lastUserText())
                            if (ReplyFix.fixEventDays(reply, lastUserText(), java.time.LocalDate.now()) > 0) CallState.current().errors.add("event day moved to the day asked")
                        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("ReplyFix", e) }
                        if (!widened && !lite && ToolPick.missing(reply, tools).isNotEmpty()) { widened = true; add(Role.STEP, "Loading all tools") }
                    } catch (e: Exception) {
                        com.blaze.assistant.device.CrashLog.error("tool gatekeeper", e)
                        // 0.45.15 (review 22 Oct #6): a reply the gatekeeper choked on never goes on with broken calls.
                        try { ToolCalls.dropMalformed(reply, allTools).takeIf { it > 0 }?.let { CallState.current().errors.add("dropped $it malformed tool call(s)") } } catch (_: Throwable) { reply.remove("tool_calls") }
                    }
                    // 0.45.19 (CredAsk): a page is open and the reply asks the user to type a login into the chat → the secure
                    // form instead, once per turn and inside the per-site limits (3 asks, none after a cancel). Never card numbers.
                    if (!routine && !credGuarded && (reply.optJSONArray("tool_calls")?.length() ?: 0) == 0) try {
                        val said = ReplyParts.split((reply.opt("content") as? String).orEmpty(), final = true).text
                        if (CredAsk.asks(said)) {
                            val host = kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.browser.BrowserEngine.host() }
                            CredAsk.decide(said, host, formAsks[host] ?: 0, credGuarded, ToolCalls.names(allTools), lastUserText())?.let { c ->
                                credGuarded = true
                                if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                reply.put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(c))
                                CallState.current().errors.add("chat credentials ask replaced by the secure form")
                            }
                        }
                    } catch (e: CancellationException) { throw e } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("CredAsk", e) }
                    kotlinx.coroutines.yield()
                    history.put(reply)

                    // v0.45.3: the raw text (with Autopilot's hidden <<done>> / <<continue>> marker) before tags are taken out.
                    val rawContent = (reply.opt("content") as? String).orEmpty()
                    // Keep reasoning and follow-up tags out of what's sent back next turn.
                    (reply.opt("content") as? String)?.let { raw ->
                        val p = ReplyParts.split(raw, final = true)
                        if (p.text != raw.trim()) {
                            reply.put("content", p.text)
                            val parts = ReplyParts.split(raw, final = true)
                            if (parts.thinking.isNotEmpty()) { if (turnThink.isNotEmpty()) turnThink.append("\n\n"); turnThink.append(parts.thinking) }
                            if (parts.followups.isNotEmpty()) pendingFollowups = parts.followups
                        }
                    }
                    val calls = reply.optJSONArray("tool_calls")
                    var textOut = when (val content = reply.opt("content")) {
                        is String -> content
                        is JSONArray -> (0 until content.length()).joinToString("") {
                            content.optJSONObject(it)?.optString("text") ?: ""
                        }
                        else -> ""
                    }.trim()

                    // v0.45.3: Autopilot sends another turn by itself only when the task is really unfinished (AutoContinue):
                    // the model's <<continue>> marker, a stated plan with steps left, or a failed check. A complete answer, a
                    // question or an offer to the user, chit-chat and <<done>> end the turn. Never after a question; at most 3.
                    if ((calls == null || calls.length() == 0) && autopilot && step < maxSteps - 2) {
                        val rawText = rawContent.ifBlank { textOut }
                        val ac = AutoContinue.decide(rawText, autoReplies, synchronized(turnTools) { turnTools.size }, actedThisTurn && Autonomy.lastFailed(lastResults))
                        if (ac.go) {
                            autoReplies++
                            val shownNow = RapidMessages.display(ReplyParts.split(textOut, final = true).text)
                            if (liveId < 0 && shownNow.isNotBlank()) add(Role.BLAZE, shownNow)
                            add(Role.STEP, "Autopilot: carrying on with the next step")
                            history.put(JSONObject().put("role", "user").put("content", AutoContinue.NUDGE))
                            continue
                        }
                    }
                    // Views first (v0.23.0): flashcards or a quiz written as text on a Learn turn become a Learn deck,
                    // or the model is asked once to make the deck itself.
                    if ((calls == null || calls.length() == 0) && !routine && turnView?.id == "learn") {
                        val called = synchronized(turnTools) { Flashcards.TOOL in turnTools }
                        when (ViewRouter.shouldConvert(lastUserText(), ReplyParts.split(textOut, final = true).text, called, learnNudged)) {
                            ViewRouter.Fix.CONVERT -> {
                                val cards = ViewRouter.cardsFrom(ReplyParts.split(textOut, final = true).text)
                                val deck = ViewRouter.deckName(lastUserText())
                                val n = kotlinx.coroutines.withContext(Dispatchers.IO) { Flashcards.add(deck, cards) }
                                add(Role.STEP, "Made the \u201c$deck\u201d deck in Learn")
                                turnTools.add(Flashcards.TOOL)
                                turnOk += ClaimGuard.Call(Flashcards.TOOL)
                                textOut = ViewRouter.converted(deck, n, cards.size)
                                reply.put("content", textOut)
                                if (agentId == null) kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Views.openOrOffer("learn", "", lastUserText(), Modes.autopilot) }
                            }
                            ViewRouter.Fix.REPROMPT -> if (nudgesLeft > 0 && step < maxSteps - 3) {
                                learnNudged = true; nudgesLeft--
                                if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                add(Role.STEP, "Putting the cards in Learn")
                                history.put(JSONObject().put("role", "user").put("content", ViewRouter.NUDGE))
                                continue
                            }
                            ViewRouter.Fix.NONE -> {}
                        }
                    }
                    // v0.45.9 (ToolTruth): "I don't have the capability…" / desktop advice while the list has the tool → one re-prompt.
                    if ((calls == null || calls.length() == 0) && !routine && !refusalNudged && nudgesLeft > 0 && tools.length() > 0 && step < maxSteps - 2 &&
                        ToolTruth.refusal(ReplyParts.split(textOut, final = true).text)) {
                        refusalNudged = true; nudgesLeft--
                        nudgeCount++
                        if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                        add(Role.STEP, "Using my tools instead of refusing")
                        history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(ToolTruth.NUDGE, lastUserText())))
                        continue
                    }
                    // v0.45.2: Blaze shows the Linux desktop itself: a reply telling the user to use a VNC client / localhost:5901 gets one
                    // re-prompt (desktop tools on), then the plain line pointing at the live view.
                    if ((calls == null || calls.length() == 0) && !routine && agentId == null && DesktopRoute.vncAdvice(ReplyParts.split(textOut, final = true).text)) {
                        if (!vncNudged && nudgesLeft > 0 && step < maxSteps - 2) {
                            vncNudged = true; nudgesLeft--
                            groupsOn += DesktopRoute.GROUP; turnDesk = true
                            if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                            add(Role.STEP, "Showing the desktop here instead")
                            history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(DesktopRoute.VNC_NUDGE, lastUserText())))
                            continue
                        }
                        textOut = DesktopRoute.NO_VNC_LINE; reply.put("content", textOut)
                        if (agentId == null) com.blaze.assistant.ui.Views.offer.value = com.blaze.assistant.ui.Views.Offer("linux")
                    }
                    // v0.23.2 no fake claims: "I've updated the design" / "view it in the design section" with no
                    // design or open_view call that succeeded this turn → one re-prompt, then an honest line instead.
                    if ((calls == null || calls.length() == 0) && !routine) {
                        val shown = ReplyParts.split(textOut, final = true).text
                        val bad = synchronized(turnOk) { ClaimGuard.unbacked(shown, turnOk.toList()) }
                        if (bad != null) {
                            // v0.27.x: only a claim about what the user asked for is worth doing for real; a stray claim about
                            // another view ("show me your memories" → "Opened the solar diagram in Design") is dropped, never acted on.
                            val related = ClaimGuard.relevant(bad, lastUserText(), turnView?.id)
                            val rest = if (related) "" else ClaimGuard.drop(shown, bad)
                            if (!related && rest.isNotEmpty()) {
                                textOut = rest
                                reply.put("content", textOut)
                                pendingFollowups = emptyList()
                            } else if (!claimNudged && nudgesLeft > 0 && step < maxSteps - 3) {
                                claimNudged = true
                                nudgeCount++
                                nudgesLeft--
                                if (related) groupsOn += bad.groups
                                if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                add(Role.STEP, if (related) RetryGuard.label(RetryGuard.Kind.CLAIM, bad.name) else RetryGuard.label(RetryGuard.Kind.OFF_TOPIC))
                                val n = if (related) ClaimGuard.nudge(bad, if (bad.id == "design" && (lastTurnDesign || Designs.recent())) Designs.current() else null) else RetryGuard.OFF_TOPIC
                                history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(n, lastUserText())))
                                continue
                            } else {
                                textOut = ClaimGuard.honest(shown, bad)
                                reply.put("content", textOut)
                                pendingFollowups = emptyList()
                            }
                        }
                    }
                    // v0.28.8 proof for every action (v0.28.7 did sends): "Message sent", "Alarm set for 7", "I turned on Wi-Fi",
                    // "Saved to memory" with no tool of that kind succeeding this turn → one re-prompt to really do it, then the truth.
                    if ((calls == null || calls.length() == 0) && !routine) {
                        val shown = ReplyParts.split(textOut, final = true).text
                        // v0.44.0 final self-check: a claim backed only by an unconfirmed / failed result is rewritten here
                        // ("I tried to … but couldn't confirm it"), no model call; a claim with nothing behind it gets the re-prompt.
                        // v0.45.0 (#4c, #20): intent = tools called this turn, plus the tools offered when the request isn't English.
                        val sentNames = ToolCalls.names(tools)
                        val calledNames = synchronized(turnTools) { turnTools.toSet() }
                        val intent = calledNames + (if (ToolRouter.looksEnglish(lastUserText())) emptySet() else sentNames)
                        val check = ClaimCheck.review(shown, synchronized(turnDone) { turnDone.toList() }, lastUserText(), intent)
                        if (check is ClaimCheck.Outcome.Rewrite) { textOut = check.text; reply.put("content", textOut); pendingFollowups = emptyList(); rewroteClaim = true }
                        val miss = (check as? ClaimCheck.Outcome.Unbacked)?.action
                        // v0.45.0 (#19): re-prompt only when it can help: the tool wasn't offered (load its group, ask once) or
                        // nothing at all ran this turn. Otherwise the honest line straight away, with "Try again" as a chip.
                        val proofWay = if (miss == null) null else NudgePolicy.proof(miss, sentNames, calledNames)
                        if (miss != null && proofWay == NudgePolicy.Way.LOAD_AND_ASK) ToolRouter.groupsFor(miss.strong).forEach { groupsOn += it }
                        if (miss != null) {
                            if (proofWay != NudgePolicy.Way.HONEST && !proofNudged && nudgesLeft > 0 && step < maxSteps - 3) {
                                proofNudged = true
                                nudgeCount++
                                nudgesLeft--
                                if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                add(Role.STEP, RetryGuard.label(RetryGuard.Kind.CLAIM))
                                history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(ActionProof.nudge(miss), lastUserText())))
                                continue
                            }
                            textOut = ActionProof.honest(shown, miss); reply.put("content", textOut); pendingFollowups = listOf(NudgePolicy.TRY_AGAIN); rewroteClaim = true
                        }
                    }
                    // v0.45.13 verify-after-act: opening an app is step 1, never the goal. A "done" for a multi-step ask whose only
                    // verified actions were opens gets one nudge to carry the flow on, then the honest line.
                    if ((calls == null || calls.length() == 0) && !routine && !rewroteClaim) {
                        val shown = ReplyParts.split(textOut, final = true).text
                        val doneNow = synchronized(turnDone) { turnDone.toList() }
                        if (GoalCheck.openedOnly(lastUserText(), shown, doneNow)) {
                            CallState.current().errors.add("reply said done after only opening an app")
                            if (!goalNudged && nudgesLeft > 0 && step < maxSteps - 3) {
                                goalNudged = true; nudgeCount++; nudgesLeft--
                                if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                add(Role.STEP, "Opening is only the first step: carrying on")
                                history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(GoalCheck.NUDGE, lastUserText())))
                                continue
                            }
                            val opened = doneNow.lastOrNull { it.name in GoalCheck.OPENERS && it.status == ToolVerify.Status.VERIFIED }?.why?.substringBefore(" is in front")?.ifBlank { null } ?: "the app"
                            textOut = GoalCheck.honest(opened); reply.put("content", textOut); pendingFollowups = listOf(NudgePolicy.TRY_AGAIN); rewroteClaim = true
                        }
                    }
                    // v0.45.13 (phone 17:12): a reply about the alarm / timer set this turn says the time really set.
                    if ((calls == null || calls.length() == 0)) try {
                        val times = TimeEcho.setTimes(synchronized(turnSetResults) { turnSetResults.toList() })
                        if (times.isNotEmpty()) { val g = TimeEcho.fix(textOut, times); if (g != textOut) { CallState.current().errors.add("reply named another time than the alarm set; fixed to ${times.first()}"); textOut = g; reply.put("content", g) } }
                    } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("TimeEcho", e) }
                    // v0.45.13 replay round 1: an unconfirmed phone action is never left unsaid in the reply.
                    if ((calls == null || calls.length() == 0) && !rewroteClaim) try {
                        val g = UnconfirmedEcho.fix(textOut, synchronized(turnDone) { turnDone.toList() })
                        if (g != textOut) { CallState.current().errors.add("reply left out that the action wasn't confirmed; said so"); textOut = g; reply.put("content", g) }
                    } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("UnconfirmedEcho", e) }
                    // 0.45.19: a switch read back on the phone ends with one line saying what was read ("Done ✓ Dark mode is on.").
                    if ((calls == null || calls.length() == 0) && !rewroteClaim) try {
                        val g = ToggleReply.fix(textOut, synchronized(turnDone) { turnDone.toList() })
                        if (g != textOut) { textOut = g; reply.put("content", g) }
                    } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("ToggleReply", e) }
                    if ((calls == null || calls.length() == 0) && step < maxSteps - 4 && gateLeft > 0 && nudgesLeft > 0 && !routine) {
                        val nudge = when {
                            FakeCall.looksFake(ReplyParts.split(textOut, final = true).text, ToolCalls.names(allTools), actedThisTurn, lastUserText(), lookedThisTurn) -> FakeCall.NUDGE
                            Autonomy.looksLikeRefusal(textOut) -> Autonomy.REFUSAL_NUDGE
                            actedThisTurn && Autonomy.lastFailed(lastResults) -> Autonomy.VERIFY_NUDGE
                            else -> null
                        }
                        if (nudge != null) {
                            gateLeft--
                            nudgeCount++
                            nudgesLeft--
                            if (liveId >= 0) msgs = msgs.filterNot { it.id == liveId }
                            add(Role.STEP, RetryGuard.label(if (nudge == FakeCall.NUDGE) RetryGuard.Kind.FAKE_CALL else RetryGuard.Kind.REFUSAL))
                            // v0.27.x: a retry stays on the user's latest request.
                            history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(nudge, lastUserText())))
                            lastResults.clear()
                            continue
                        }
                    }
                    // v0.45.5 (#1): a reply that only repeats Blaze's own context (Awareness, the system prompt) or a context tool's
                    // result is never the answer: ask once more, then say plainly that it went wrong.
                    if ((calls == null || calls.length() == 0)) {
                        val shown = RapidMessages.display(ReplyParts.split(textOut, final = true).text)
                        val ctxTexts = try { (0 until req.length()).mapNotNull { k -> req.optJSONObject(k)?.takeIf { it.optString("role") == "system" }?.optString("content") } +
                            listOf(Awareness.promptBlock(), Awareness.summaryBlock()) } catch (_: Throwable) { emptyList() }
                        if (shown.isNotBlank() && EchoGuard.echoes(shown, lastUserText(), ctxTexts, EchoGuard.contextToolTexts(history))) {
                            CallState.current().errors.add("reply repeated Blaze's context or a tool result" + if (echoNudged) "; replaced" else "; asked again")
                            if (!echoNudged && nudgesLeft > 0 && step < maxSteps - 2) {
                                echoNudged = true; nudgesLeft--
                                if (history.length() > 0 && history.optJSONObject(history.length() - 1) === reply) history.remove(history.length() - 1)
                                if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                add(Role.STEP, "Asking for a real answer")
                                history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(EchoGuard.NUDGE, lastUserText())))
                                continue
                            }
                            textOut = EchoGuard.HONEST; reply.put("content", EchoGuard.HONEST)
                        }
                    }
                    if (calls == null || calls.length() == 0) savedPath?.let { p -> val g = SaveFile.withPath(textOut, p); if (g != textOut) { textOut = g; reply.put("content", g) } }
                    // v0.45.9: a schedule answer made from the [context] block names exactly that date's items (ScheduleTruth).
                    if ((calls == null || calls.length() == 0) && turnToolCalls == 0) try {
                        val lu = lastUserText()
                        if (TurnContext.scheduleAsk(lu)) {
                            val g = ScheduleTruth.fix(textOut, TurnContextSource.block(lu), true, lu)
                            if (g != textOut) { CallState.current().errors.add("schedule answer didn't match the phone's schedule; rebuilt"); textOut = g; reply.put("content", g) }
                        }
                    } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("ScheduleTruth", e) }
                    // v0.26.2: an empty final answer (reasoning only, or only tags) is never shown as "Done.": a specific line
                    // when the user asked for an action and a tool succeeded; else ask the model once more, then fail plainly.
                    if ((calls == null || calls.length() == 0) && RapidMessages.display(ReplyParts.split(textOut, final = true).text).isBlank()) {
                        val done = EmptyReply.finalText(lastUserText(), lastOkStep)
                        if (done != null) { textOut = done; reply.put("content", done) }
                        else {
                            if (history.length() > 0 && history.optJSONObject(history.length() - 1) === reply) history.remove(history.length() - 1)
                            if (!emptyNudged && nudgesLeft > 0 && step < maxSteps - 2) {
                                emptyNudged = true; nudgesLeft--
                                if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                add(Role.STEP, "Asking for the answer again")
                                history.put(JSONObject().put("role", "user").put("content", EmptyReply.NUDGE))
                                continue
                            }
                            throw LlmClient.LlmException(EmptyReply.FAILED, EmptyReply.CODE)
                        }
                    }
                    if (calls == null || calls.length() == 0) {
                        // v0.27.x: the reply only describes what the design tool really produced (a flat diagram is not "3D").
                        // v0.33.3: "Here's your app list" always comes with the list (the tappable app card).
                        turnAppsCard?.let { card ->
                            val g = com.blaze.assistant.phone.AppFinder.ensureCard(textOut, card, lastUserText())
                            if (g != textOut) { textOut = g; reply.put("content", g) }
                        }
                        if (Designs.touched.isNotEmpty()) {
                            val g = Designs.groundReply(textOut, Designs.touched.toList())
                            if (g != textOut) { textOut = g; reply.put("content", g) }
                        }
                        // v0.45.6: no "Done" / "ready" unless the Design view proved it drew; Motion asks get the honest Motion status.
                        if (turnDesignRenderSet || DesignTruth.isMotionAsk(lastUserText())) {
                            val g = DesignTruth.ground(textOut, if (turnDesignRenderSet) turnDesignRender else com.blaze.assistant.ui.DesignStatus.Status(com.blaze.assistant.ui.DesignStatus.State.RENDERED, "", 0),
                                DesignTruth.isMotionAsk(lastUserText()), Designs.touched.any { Designs.is3d(it) })
                            if (g != textOut) { textOut = g; reply.put("content", g) }
                        }
                        kotlinx.coroutines.delay(60)   // let the last streamed chunk land first
                        // v0.30.7 plan memory: the phone steps of a task that ended well are kept for this request.
                        // v0.45.0 (#18): only when every action this turn was verified (and nothing was nudged or rewritten).
                        val logNow = synchronized(turnLog) { turnLog.toList() }
                        var fbPlan = false; var fbGroups: Set<String>? = null   // v0.45.0 reactions: what this turn taught (TurnFeedback)
                        if (!routine && !bulk && agentId == null && !isPrivate && PlanGate.keep(logNow, proofNudged || claimNudged, rewroteClaim)) try {
                            planRec.recipe()?.let { TapLoop.Plans.put(userNow, it); lastPlanKey = userNow; fbPlan = true }
                        } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: planRec.recipe.let TapLoop.Plans.putuser", e) }
                        val secs = (System.currentTimeMillis() - turnStart) / 1000.0
                    if (step > 0) { com.blaze.assistant.device.Haptics.done(app); com.blaze.assistant.device.Sounds.done() }
                        com.blaze.assistant.device.CrashLog.slow("turn ($step steps, ${LlmClient.lastRoute})", System.currentTimeMillis() - turnStart)
                        // v0.33.1 learned tool router: what this request's words needed, from the tools that really ran.
                        if (!routine && !bulk && agentId == null && !isPrivate && PlanGate.learn(logNow, proofNudged || claimNudged)) try {
                            val used = synchronized(turnTools) { turnTools.toList() }.mapNotNull { ToolRouter.groupOf(it) }.filter { it != "more" }.toSet()
                            kotlinx.coroutines.withContext(Dispatchers.IO) { LearnedRoutes.learn(userNow, used) }
                            fbGroups = used
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: val used synchronizedturnTools turnTools", e) }
                        // v0.45.0 (#19, #6, #17): nudges, Cloud-run continues / restarts and route changes go in the speed line.
                        val extra = listOf(sizeLine, "tools sent ${tools.length()}", if (nudgeCount > 0) "nudges $nudgeCount" else "", if (lateSteps.isNotEmpty()) "late: " + lateSteps.joinToString(",") else "",
                            if (LlmClient.lastMaxGapMs >= 1500) "longest pause in the stream ${TurnTimer.fmt(LlmClient.lastMaxGapMs)} (${LlmClient.lastServedBy.ifBlank { "model" }})" else "",
                            ct?.let { c -> if (c.continued + c.restarted > 0) "run kept ${c.continued}, new ${c.restarted}" else "" } ?: "",
                            turnModel?.line().orEmpty(),
                            // v0.45.1: system prompt size by tier, how often the live tail was kept, and the provider's cache hit.
                            if (!lite) PromptParts.line(lastPromptParts) + " · top " + PromptParts.rank(lastPromptParts, 4) else "",
                            PromptParts.cacheLine(LlmClient.lastUsage),
                            // v0.45.2: verification time this turn, local vs model checks (speed_stats shows it).
                            turnBook.let { b -> if (VerifyPolicy.plainTurn(synchronized(turnTools) { turnTools.toList() })) b.stats.skipped++; b.stats.line() }).filter { it.isNotBlank() }.joinToString(" · ")
                        SpeedLog.add(SpeedLog.Entry(turnStart, firstMs.coerceAtLeast(0), System.currentTimeMillis() - turnStart, step + 1,
                            tools.length(), req.toString().length + tools.toString().length, LlmClient.lastRoute, timer.line() + (if (extra.isNotBlank()) " · $extra" else ""),
                            shownMs = (if (shownAt > 0) shownAt else System.currentTimeMillis()) - (msgs.firstOrNull { it.id == turnFrom }?.at?.takeIf { it in 1..turnStart } ?: turnStart), headersMs = hdrMs,
                            visibleMs = (if (agentId == null) StartTrace.take()?.visibleMs else null) ?: -1, wireMs = (if (agentId == null) StartTrace.take()?.wireMs else null) ?: -1))
                        if (agentId == null) StartTrace.take()?.let { m -> try { android.util.Log.i("BlazeSpeed", StartTrace.line(m)) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: BlazeSpeed start line", e) } }
                        try { android.util.Log.i("BlazeSpeed", "turn ${step + 1} steps, ${LlmClient.lastRoute}: ${timer.line()}" + (if (extra.isNotBlank()) " · $extra" else "")) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: android.util.Log.iBlazeSpeed turn step 1", e) }
                        val idsBefore = ids.get()
                        addReply(liveId, textOut, turnThink.toString(), pendingFollowups,
                            if (Settings.showRoute) "☁ " + listOf(LlmClient.lastRoute.ifBlank { "Blaze Cloud" }.replace(" · ", " / "),
                                if (firstMs > 0) "first word %.1f s".format(firstMs / 1000.0) else "",
                                "%.1f s".format(secs),
                                if (step > 0) "${step + 1} steps" else "").filter { it.isNotBlank() }.joinToString(" · ") else "",
                            Receipt.toJson(logNow),
                            // v0.45.5 (#1): the "What happened" sheet for this reply.
                            try { val st = CallState.current()
                                TurnTrace.toJson(TurnTrace.build(st.gw, LlmClient.lastRoute.ifBlank { "Blaze Cloud" }, if (ct != null) "cloud run" else if (liteNow) "chat lane" else "agent",
                                    st.requestIds.toList(), if (shownAt > 0) shownAt - turnStart else LlmClient.lastFirstTextMs, hdrMs, System.currentTimeMillis() - turnStart, step + 1,
                                    logNow.map { e -> TurnTrace.Tool(e.tool, e.args, e.result, e.status != ToolVerify.Status.FAILED) }, st.errors.toList(), LlmClient.lastMaxGapMs,
                                    if (agentId == null) StartTrace.take() else null, ToolTruth.names(tools)))
                            } catch (_: Throwable) { "" })
                        // v0.45.0 (#36): the quiet receipt line under the reply ("✓ Sent to Mum · ⚠ Couldn't confirm: Wi-Fi on").
                        Receipt.line(logNow).takeIf { it.isNotBlank() }?.let { line -> msgs.lastOrNull { it.role == Role.BLAZE }?.let { addMeta(it.id, line) } }
                        // v0.45.2: a check still running flips this reply's receipt when it ends (VerifyPolicy (c)).
                        msgs.lastOrNull { it.role == Role.BLAZE }?.let { last -> val bk = turnBook
                            synchronized(bk.log) { bk.msgId = last.id; if (bk.log.toList() != logNow) updateReceipt(last.id, bk.log.toList()) } }
                        // v0.45.0 reactions (R4): a later 👍 / 👎 on any bubble of this reply maps back to this turn.
                        if ((fbPlan || fbGroups != null) && !isPrivate) try {
                            val gids = msgs.filter { it.role == Role.BLAZE && (it.id == liveId || it.id >= idsBefore) }.map { it.gid }
                            TurnFeedback.record(gids, id, userNow, fbPlan, fbGroups, turnStart / 1000, System.currentTimeMillis() / 1000)
                        } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: TurnFeedback.record", e) }
                        break
                    }
                    // Text that came with tool calls is narration, shown as a step; its reasoning joins the turn's.
                    val narr = ReplyParts.split(textOut).let { it.copy(text = Friendly.plain(RapidMessages.display(it.text))) }
                    if (narr.thinking.isNotEmpty()) { if (turnThink.isNotEmpty()) turnThink.append("\n\n"); turnThink.append(narr.thinking) }
                    thinking = turnThink.toString()
                    // v0.41.0: it turned out to be narration: stop any of it being read out, the reply comes later.
                    if (roundSpoke) { Voice.stopSpeaking(); roundSpoke = false; streamedVoice = false; spokenPrefix = "" }
                    if (liveId >= 0) {
                        if (narr.text.isNotEmpty()) replace(liveId, Role.STEP, narr.text)
                        else msgs = msgs.filterNot { it.id == liveId }
                    } else if (narr.text.isNotEmpty()) add(Role.STEP, narr.text)

                    mood = Mood.ACTING
                    val shots = mutableListOf<String>()
                    // Speed plan step 8: several read-only lookups in one step run at the same time.
                    fun callName(i: Int) = calls.getJSONObject(i).getJSONObject("function").getString("name")
                    fun callArgs(i: Int) = try { JSONObject(calls.getJSONObject(i).getJSONObject("function").optString("arguments", "{}").ifBlank { "{}" }) }
                        catch (_: Exception) { JSONObject() }
                    val pre: Map<Int, String> = if (calls.length() > 1 && (0 until calls.length()).all { callName(it) in parallelSafe })
                        (0 until calls.length()).map { i -> scope.async { i to runTool(callName(i), callArgs(i), shots) } }
                            .map { it.await() }.toMap()
                    else emptyMap()
                    val readAt = FastActions.screenReadAt((0 until calls.length()).map { callName(it) }, screenActions + "do_actions")
                    // v0.45.3: Normal mode asks once for all the commands in this reply (CommandPolicy batch card).
                    val refusedCmds = if (calls.length() > 1) batchApprove((0 until calls.length()).map { callName(it) to callArgs(it) }) else { cmdBatchOkClear(); emptySet() }
                    for (i in 0 until calls.length()) {
                        val call = calls.getJSONObject(i)
                        val fn = call.getJSONObject("function")
                        val name = fn.getString("name")
                        val args = try { JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" }) }
                            catch (_: Exception) { JSONObject() }
                        if (name !in Friendly.selfNarrating && name !in unknownCalls) addStep(Friendly.say(name, args), Friendly.exact(name, args))
                        if (pre.containsKey(i)) autoStepId = -1L
                        val toolT0 = System.currentTimeMillis()
                        var result = if (pre.containsKey(i)) pre.getValue(i) else if (i in refusedCmds) "The user did NOT approve this command (they refused the card for this step's commands). Don't run it; ask how they'd like to go on."
                        else if (name in unknownCalls) {
                            val close = ToolCalls.names(allTools).sortedBy { n -> levGuess(n, name) }.take(4)
                            "Error: there is no tool called \"$name\". Closest real tools: ${close.joinToString(", ")}. Call one of those (exact name), or find_tool to search. Every tool is available from your next step."
                        } else if (name == "find_tool") {
                            add(Role.STEP, "Looking for the right tool")
                            val (txt, g) = Autonomy.findTool(allTools, args.optString("need"))
                            groupsOn += g
                            groupsOn += ToolPick.cuesIn(txt)
                            txt
                        } else if (name == "load_tools") {
                            val g = args.optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                            groupsOn += g
                            add(Role.STEP, "Loading ${g.joinToString(", ")} tools")
                            "Loaded: ${g.joinToString(", ")}. Those tools are available now."
                        } else if (name == "ask_user") runTool(name, args, shots)
                        else loopGuard.check(name, args)?.also { add(Role.STEP, "Not trying ${Friendly.say(name, args).trimEnd('.', '\u2026')} again: it made no difference") }
                        ?: oneShot.earlier(name, args)?.also { add(Role.STEP, "Not repeating ${Friendly.say(name, args).trimEnd('.', '\u2026')}: it already ran") }
                            ?: (Recovery.run(name, OneShot.risky(name), Settings.str("tool_timeout_s", "180").toLongOrNull()?.coerceIn(30, 900)?.times(1000) ?: 180_000L) { runTool(name, args, shots) }
                                .also { r -> oneShot.record(name, args, r) })
                        if (!result.startsWith("Not run:")) loopGuard.record(name, args, result)
                        ToolStats.add(name, when {
                            name in unknownCalls -> "unknown"
                            repairedThisStep.any { it.startsWith(name) || it.contains("→ $name") || (it.startsWith("split") && it.contains(name)) } -> "repaired"
                            result.startsWith("Error") || result.startsWith("Couldn't") -> "error"
                            else -> "ok"
                        })
                        // A tool from a group pulls in the rest of that group (e.g. a stuck run_plan brings tap/scroll).
                        ToolRouter.groupOf(name)?.let { groupsOn += it }
                        if (name !in parallelSafe && name !in setOf("find_tool", "load_tools", "goal", "processes")) actedThisTurn = true
                        if (name !in setOf("find_tool", "load_tools")) lookedThisTurn = true
                        lastResults += result
                        // v0.44.0 verify before claiming: the result's own words plus a cheap post-condition check (app in front,
                        // desktop port, next alarm, file there); the model sees [verified=…] on every result.
                        // v0.45.2 (VerifyPolicy): risky actions and strict mode wait for the check as before; other probed actions
                        // get QUICK_MS, then the check finishes in the background and flips the receipt when it ends.
                        val book = turnBook
                        val strictV = VerifyPolicy.strict()
                        val vMode = VerifyPolicy.mode(name, strictV)
                        val vt0 = System.currentTimeMillis()
                        if (book.openUnverified > 0 && VerifyBook.isModelCheck(name, args)) { book.stats.model(System.currentTimeMillis() - toolT0); book.openUnverified = 0 }
                        var pendingCheck: kotlinx.coroutines.Deferred<ToolVerify.Verdict>? = null
                        val verdict = if (name in setOf("find_tool", "load_tools") || name in unknownCalls) null else try {
                            when (vMode) {
                                VerifyPolicy.Mode.QUICK_THEN_BACKGROUND -> {
                                    val full = VerifyBook.scope.async {
                                        try { kotlinx.coroutines.withTimeoutOrNull(VerifyPolicy.BACKGROUND_MS) { ToolVerify.check(name, args, result, VerifyProbes(app)) } ?: VerifyPolicy.timedOut() }
                                        catch (e: CancellationException) { throw e } catch (_: Exception) { VerifyPolicy.timedOut() }
                                    }
                                    kotlinx.coroutines.withTimeoutOrNull(VerifyPolicy.QUICK_MS) { full.await() } ?: run {
                                        pendingCheck = full
                                        ToolVerify.classify(name, args, result).let { w -> if (w.status == ToolVerify.Status.UNVERIFIED) w.copy(why = "still checking") else w }
                                    }
                                }
                                else -> kotlinx.coroutines.withTimeoutOrNull(VerifyPolicy.BLOCKING_MS) { ToolVerify.check(name, args, result, VerifyProbes(app)) }
                                    ?: ToolVerify.classify(name, args, result)
                            }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { ToolVerify.classify(name, args, result) }
                        if (verdict != null) {
                            book.stats.local(System.currentTimeMillis() - vt0)
                            val pend = pendingCheck
                            val di: Int; val li: Int
                            synchronized(turnDone) { turnDone += ClaimCheck.Done(name, verdict.status, verdict.why); di = turnDone.size - 1 }
                            synchronized(book.log) { book.log += Receipt.entry(name, args, result, verdict).copy(pending = pend != null); li = book.log.size - 1 }
                            if (!ToolVerify.tagged(result)) result = if (pend != null) result.trimEnd() + "\n" + VerifyPolicy.pendingTag(verdict.why)
                                else VerifyPolicy.tag(result, verdict, fromProbe = VerifyPolicy.probed(name), strict = strictV)
                            if (verdict.status == ToolVerify.Status.UNVERIFIED && pend == null && Receipt.isAction(name)) book.openUnverified++
                            if (pend != null) {
                                book.stats.pending++
                                book.stats.savedMs += VerifyPolicy.BLOCKING_MS.coerceAtMost(1_500L)   // the old path's usual poll wait
                                VerifyBook.scope.launch {
                                    val fin = try { pend.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { VerifyPolicy.timedOut() }
                                    flipReceipt(book, li, di, fin)
                                }
                            }
                        }
                        if (ClaimGuard.succeeded(result) && verdict?.status != ToolVerify.Status.FAILED) { turnOk += ClaimGuard.Call(name, args); lastOkStep = msgs.lastOrNull { it.role == Role.STEP }?.text.orEmpty() }
                        turnToolCalls++
                        if (name == SaveFile.TOOL) SaveFile.pathIn(result)?.let { savedPath = it }
                        if (name == "set_alarm" || name == "set_timer" || name == "create_routine") turnSetResults += result
                        if (name == "run_plan" || name == "read_screen" || name == com.blaze.assistant.phone.SettingsJump.TOOL) groupsOn += "screen"
                        if (name in screenActions && !result.startsWith("Error") && !result.startsWith("Couldn't")) com.blaze.assistant.device.Haptics.tick(app)
                        // v0.30.4 batching: several screen actions in one reply run back to back with a short settle; the
                        // screen is read once, after the last of them.
                        if (name == "do_actions" || (name in screenActions && !FastActions.isScreen(result))) {
                            if (i in readAt || name == "do_actions") result += screenAfter(result)
                            else com.blaze.assistant.phone.BlazeAccessibilityService.instance?.settle(700, 150)
                        }
                        history.put(
                            JSONObject().put("role", "tool")
                                .put("tool_call_id", call.optString("id", "call_$step$i"))
                                .put("name", name)
                                .put("content", if (!Settings.longChat || name in LongChat.keepWhole || FastActions.isScreen(result)) result else LongChat.aside(result))
                        )
                        // v0.30.4: only the newest screen stays in the history; older readouts are dropped in place.
                        if (FastActions.isScreen(result)) FastActions.pruneScreens(history)
                    }
                    if (Settings.lowData) shots.clear()
                    // v0.33.3: a screenshot is seen by one model call only; later steps no longer re-send it (nor go to vision).
                    VisionFast.pruneShots(history, SHOT_NOTE)
                    shots.lastOrNull()?.let { url ->
                        history.put(JSONObject().put("role", "user").put("content", JSONArray()
                            .put(JSONObject().put("type", "text").put("text", SHOT_NOTE))
                            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))))
                    }
                    if (step == maxSteps - 1) add(Role.BLAZE, "I've taken a lot of steps on this one, so I'm pausing here. Tell me if you want me to keep going.")
                }
            } catch (e: CancellationException) {
                // v0.45.0 (#5, #38): a clean Stop. The live stream is closed first (nothing lands after this line), the history
                // is tidied (an unfinished tool call gets an honest "interrupted" result, the unread screenshot is dropped) and
                // the task is kept so "Continue where you stopped" picks it up with its goal and receipts.
                turnEnd = TurnEnd.Reason.STOPPED
                liveGate?.close(); liveGate = null
                keepForResume()
                add(Role.STEP, "Stopped.")
                if (agentId == null && resume != null) {
                    val sid = add(Role.BLAZE, StopResume.STOPPED_LINE)
                    msgs = msgs.map { if (it.id == sid) it.copy(followups = listOf(StopResume.CHIP)) else it }
                }
                throw e
            } catch (e: LlmClient.UsedUp) {
                keepForResume()
                val id = add(Role.ERROR, e.message ?: "Credits used up.")
                msgs = msgs.map { if (it.id == id) it.copy(meta = "usedup:${e.pid}|${e.model}") else it }
            } catch (e: LlmClient.LlmException) {
                keepForResume()
                // v0.45.4: signed out of Blaze Cloud with nothing else to answer: a Sign in card, the message waits and is
                // sent by itself once the account lands (SignInGate).
                val offline = try { OfflineBrain.on && OfflineBrain.ready() && !OfflineBrain.online() } catch (_: Throwable) { false }
                if (SignInGate.applies(e.code, e.message, offline)) {
                    val cid = add(Role.ERROR, SignInGate.LINE)
                    msgs = msgs.map { if (it.id == cid) it.copy(meta = SignInGate.CARD) else it }
                    signInHolds.hold(id, cid)
                } else add(Role.ERROR, e.message ?: "Something went wrong.")
            } catch (e: Exception) {
                com.blaze.assistant.device.CrashLog.error("agent loop", e)
                keepForResume()
                add(Role.ERROR, e.message ?: "Something went wrong.")
            } finally {
                liveGate = null
                // v0.45.6: a turn never ends silently (stop, timeout, tool failure): one plain closing line.
                if (agentId == null) try {
                    if (TurnEnd.silent(msgs.filterNot { it.meta == Ack.META }.map { it.role })) {
                        val lastStep = msgs.lastOrNull { it.role == Role.STEP }?.text.orEmpty()
                        val why = if (turnEnd == TurnEnd.Reason.DONE && lastStep == "You stopped me") TurnEnd.Reason.STOPPED else turnEnd
                        add(Role.BLAZE, TurnEnd.line(why, lastStep))
                    }
                } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: TurnEnd", e) }
                // Low-data mode: how much this task used (Blaze's own traffic: models, web, browser).
                if (Settings.lowData) try { DataMeter.chip(bytes0)?.let { chip -> msgs.lastOrNull { it.role == Role.BLAZE }?.let { addMeta(it.id, chip) } } } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: DataMeter.chipbytes0.let chip msgs.lastO", e) }
                LlmClient.liteTurn = false
                LlmClient.heavyTurn = false
                turnModel?.let { tm -> if (TurnModel.current === tm) TurnModel.current = null }
                // v0.42.0: a run still waiting for the phone is stopped; one still answering stays saved and is picked up later.
                cloudTurn?.let { try { CloudRuns.endTurn(it) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: CloudRuns.endTurnit", e) } }
                lastTurnDesign = ViewRouter.byId("design")?.let { d -> synchronized(turnOk) { turnOk.any { ClaimGuard.backs(d, it) } } } == true
                if (agentId == null) com.blaze.assistant.phone.Pointer.done()
                try { finishAck() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: finishAck", e) }
                thinking = ""
                mood = Mood.IDLE
                currentStep = 0
                persist()
                announce()
                learnAfterTurn()
                trimSessions()   // v0.45.0 (#12)
                if (wakes.isNotEmpty()) scope.launch { job?.join(); wakes.poll()?.let { wake(it) } }
                // v0.30.6: messages that came in after this turn's last step start the next turn (after this one ends).
                if (inbox.isNotEmpty()) scope.launch { job?.join(); drain() }
            }
        }

        /**
         * v0.26.2 delegate: hands specialist tasks out WITHOUT blocking. Each brief becomes a background agent running as
         * that specialist (its card with Steer / Stop in the chat); the turn carries on and each result wakes this chat as it
         * lands. [wait] (or a private chat, whose work must never be written to disk) keeps the old blocking run.
         */
        suspend fun delegate(args: JSONObject): String {
            val tasks = args.optJSONArray("tasks") ?: JSONArray()
            val briefs = (0 until tasks.length()).mapNotNull { tasks.optJSONObject(it) }.map { o ->
                val spec = Specialists.byId(o.optString("specialist")) ?: Specialists.builtIn.first()
                Dispatch.Brief(spec.id, o.optString("task").trim(), o.optString("title").trim())
            }
            if (args.optBoolean("wait") || isPrivate || !Settings.bool("bg_agents", true)) return delegateAndWait(briefs)
            val live = BgAgents.all.value.filter { it.live && it.parent == id && it.specialist.isNotBlank() }.map { Dispatch.Brief(it.specialist, it.task) }
            val plan = Dispatch.plan(briefs, live)
            val specs = plan.accepted.map { Specialists.byId(it.specialist) ?: Specialists.builtIn.first() }
            val scope = Dispatch.scopeLine(args.optString("scope"), specs.map { it.name })
            if (scope.isNotBlank()) { val mid = add(Role.BLAZE, scope); addMeta(mid, "Specialists") }
            val started = plan.accepted.zip(specs).map { (b, sp) ->
                val bg = BgAgents.start(b.task, b.title.ifBlank { Dispatch.title(b.task) }, id, sp.id)
                val mid = add(Role.BLAZE, "${sp.name} ${bg.id}: ${bg.title}")
                msgs = msgs.map { if (it.id == mid) it.copy(meta = BgAgents.CARD + bg.id) else it }
                bg
            }
            persist()
            val queued = started.count { BgAgents.get(it.id)?.status == BgAgents.QUEUED }
            return Dispatch.startedLine(started.map { it.id to (BgAgents.spec(it)?.name ?: it.specialist) }, plan.rejected, queued, scope.isNotBlank())
        }

        /** The blocking run (delegate wait=true): non-phone specialists in parallel, phone ones one after another. */
        private suspend fun delegateAndWait(briefs: List<Dispatch.Brief>): String = kotlinx.coroutines.coroutineScope {
            val all = tools().let { if (isPrivate) PrivateChat.filterTools(it) else it }.let { if (Projects.of(id) == null) ProjectDocs.strip(it) else it }
            val jobs = briefs.take(Dispatch.MAX_TASKS).mapIndexed { i, b -> Triple(i, Specialists.byId(b.specialist) ?: Specialists.builtIn.first(), b.task) }
            val phoneLock = kotlinx.coroutines.sync.Mutex()
            val results = jobs.map { (i, spec, task) ->
                async {
                    add(Role.STEP, "${spec.name} started: ${task.take(60)}")
                    val r = try {
                        if ("phone" in spec.groups) phoneLock.withLock { runSpecialist(spec, task, all) } else runSpecialist(spec, task, all)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                    add(Role.STEP, "${spec.name} finished")
                    "## ${i + 1}. ${spec.name}\n$r"
                }
            }.map { it.await() }
            results.joinToString("\n\n")
        }

        private suspend fun runSpecialist(spec0: Specialists.Spec, task: String, all: JSONArray): String {
            // v0.26.2: its field playbook goes into its prompt (a custom one without a playbook gets one written first).
            val spec = Specialists.byId(spec0.id) ?: spec0
            val playbook = Playbooks.forSpec(spec).ifBlank { if (spec.custom && !isPrivate) Agent.ensurePlaybook(spec) else "" }
            val tools = Specialists.filter(all, spec)
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system").put("content", Playbooks.specialistSystem(spec, playbook, SpecialistMatch.brief(spec, SkillMatch.cached()),
                    SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date()), Memory.promptBlock(task))))
                .put(JSONObject().put("role", "user").put("content", task))
            val shots = mutableListOf<String>()
            var reviewed = playbook.isBlank()
            for (step in 0 until 16) {
                val reply = LlmClient.complete(msgs, if (tools.length() > 0) tools else null, null, null, LlmClient.Lane.STEPS)
                reply.remove("_reasoning")
                try { Usage.record(msgs, reply, LlmClient.lastUsage) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: Usage.recordmsgs reply LlmClient.lastUsa", e) }
                val unknown = try {
                    if ((reply.optJSONArray("tool_calls")?.length() ?: 0) == 0) (reply.opt("content") as? String)?.let { txt ->
                        (CallScan.toCalls(txt, all) ?: ToolCalls.fromText(txt, all) ?: ToolLeak.toCalls(txt, all))?.let { (c, rest) -> reply.put("tool_calls", c); reply.put("content", if (rest.isBlank()) JSONObject.NULL else CallScan.clean(ToolLeak.strip(rest))) } }
                    ToolCalls.normalize(reply, all).unknown
                } catch (_: Exception) { emptySet() }
                msgs.put(reply)
                val calls = reply.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) {
                    val draft = ReplyParts.split(reply.optString("content")).text.ifBlank { "(no result)" }
                    // Self-check: one review against the playbook; if it fails, one redo with the exact fixes.
                    if (!reviewed) {
                        reviewed = true
                        add(Role.STEP, "${spec.name}: checking its work")
                        val r = Agent.reviewDraft(spec, playbook, task, draft)
                        if (!r.checked) { add(Role.STEP, "${spec.name}: self-check didn't finish"); return draft + "\n\n" + VerifyPolicy.NOT_REVIEWED }
                        if (!r.pass) {
                            add(Role.STEP, "${spec.name}: fixing ${r.fixes.size} thing(s)")
                            msgs.put(JSONObject().put("role", "user").put("content", Playbooks.redoPrompt(r.fixes)))
                            continue
                        }
                    }
                    return draft
                }
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val fn = call.getJSONObject("function")
                    val name = fn.getString("name")
                    val args = try { JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                    var result = if (name in unknown) "Error: there is no tool called \"$name\". Use one of the tools you were given (exact name)." else runTool(name, args, shots)
                    if (name == "do_actions" || (name in screenActions && !FastActions.isScreen(result))) result += screenAfter(result)
                    msgs.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id", "s$step$i")).put("name", name).put("content", result.take(12000)))
                }
                VisionFast.pruneShots(msgs, SHOT_NOTE)
                shots.lastOrNull()?.let { url -> shots.clear()
                    msgs.put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", SHOT_NOTE))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))))) }
            }
            return "Stopped after 16 steps without finishing."
        }

        /** create_specialist: validated playbook or a precise list of what to fix (a few tries per id). */
        private fun createSpecialist(args: JSONObject): String {
            val key = Specialists.slug(args.optString("id").ifBlank { args.optString("name") })
            val n = (createTries[key] ?: 0) + 1
            if (n > Playbooks.MAX_TRIES) return "Not saved: the playbook failed validation ${Playbooks.MAX_TRIES} times. Do the task yourself without a new specialist."
            createTries[key] = n
            val groups = args.optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: args.optString("groups").split(',')
            val existing = Specialists.custom()
            val others = Specialists.builtIn.map { Playbooks.builtIn(it.id) } + existing.filter { it.id != key }.map { it.playbook }
            val m = Specialists.make(args.optString("id"), args.optString("name"), args.optString("prompt"), groups, args.optString("playbook"), existing, others, n)
            if (m.ok && m.spec != null) {
                Specialists.upsert(m.spec)
                createTries.remove(key)
                add(Role.STEP, "Made a new specialist: ${m.spec.name}")
            } else add(Role.STEP, "Specialist playbook needs work (try $n of ${Playbooks.MAX_TRIES})")
            return m.message
        }

        /** Fresh compact screen after an action, so the model doesn't spend a turn on read_screen (the orient loop's "look after"). */
        suspend fun screenAfter(result: String): String {
            val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance ?: return ""
            if (!Settings.phoneControl || result.startsWith("Error") || result.startsWith("No app") || result.startsWith("Phone control is off") || result.startsWith("Not done yet")) return ""
            // 0.45.19: a result the phone already read back (verified by state) needs only a short settle before the look
            if (result.contains(ActCheck.OK)) svc.settle(500, 120) else svc.settle(1200, 200)
            val s = svc.readScreen(diff = true)
            orient.saw(svc.currentPkg())
            return "\n\nScreen now:\n" + s + com.blaze.assistant.phone.Watchers.drain()
        }

        /** v0.30.7: screen tools whose steps go into plan memory (run_plan records its own steps). */
        private val planTools = setOf("open_app", "tap", "type_text", "scroll", "press_button", "search_in_app", "open_settings", "read_screen", "look_at_screen")

        /** Orient loop state for this turn (v0.20.0). */
        val orient = Orient.State()
        /** v0.30.4: the last run_plan this turn (for resuming a failed script instead of restarting it). */
        @Volatile var lastPlan: FastActions.PlanRun? = null
        /** Secure-form asks per site this session, so a refusing site can't loop on the form. */
        private val formAsks = HashMap<String, Int>()

        /** browser_secure_input: secure card in the chat → values straight into the page; the model gets placeholders. */
        private suspend fun secureInput(args: JSONObject): String {
            val B = com.blaze.assistant.browser.BrowserEngine
            val S = com.blaze.assistant.browser.SecureInput
            val V = com.blaze.assistant.browser.Vault
            val host = kotlinx.coroutines.withContext(Dispatchers.Main) { B.host() }
            if (host.isBlank()) return "No page is open in Blaze's browser. Open the sign-in page with browser_open first, then call this again."
            // v0.28.2: no secure form or saved login on a plain-http page (except this phone / the LAN).
            val pageUrl = kotlinx.coroutines.withContext(Dispatchers.Main) { B.url() }
            if (!com.blaze.assistant.connect.UrlSafety.autofillAllowed(pageUrl)) return com.blaze.assistant.connect.UrlSafety.AUTOFILL_REFUSED
            // 0.45.19: fields written as a JSON string or a word list ("email, password") still open the form.
            val fields = S.parseFields(args.optJSONArray("fields") ?: CallScan.secureArgs(args)?.optJSONArray("fields"))
            if (fields.isEmpty()) return "Error: give fields, e.g. [{\"name\":\"email\",\"kind\":\"email\"},{\"name\":\"password\",\"kind\":\"password\"}]."
            if (fields.any { S.isCardField(it) }) return "Card details are never collected in the chat. Show the live browser with browser_show so the user types them on the page, or stop before paying."
            if (fields.any { it.kind == com.blaze.assistant.browser.SecureInput.Kind.PASSWORD }) B.passwordRefused()?.let { return it }
            val asks = (formAsks[host] ?: 0) + 1; formAsks[host] = asks
            if (asks > 50) return "The user cancelled the form for $host in this task. Don't ask again: stop this sign-in and say what you needed."
            if (asks > 3) return "I've already asked for this on $host 3 times. Don't ask again: use ask_user to offer Continue with Google, a passkey, or the user doing it themselves in the live browser (browser_show)."
            val names = fields.map { it.name }
            val fromVault = !args.optBoolean("fresh") && V.has(host, names)
            val title = args.optString("title").ifBlank { "Details for $host" }
            val answer = if (fromVault) {
                add(Role.STEP, "Using your saved details for $host")
                com.blaze.assistant.browser.SecureInput.Answer(names.associateWith { V.get(host, it).orEmpty() }, false)
            } else {
                add(Role.STEP, "Waiting for you: ${title.take(60)}")
                mood = Mood.IDLE
                val a = askSecure(com.blaze.assistant.browser.SecureInput.Request(ids.getAndIncrement(), host, title, args.optString("reason"), fields,
                    args.optBoolean("submit", true), kotlinx.coroutines.CompletableDeferred()), this)
                mood = Mood.ACTING
                a ?: run {
                    // v0.30.5: a cancel ends this sign-in; the next step of the same signup doesn't pop a new card.
                    formAsks[host] = 99
                    return "The user cancelled the form (or didn't answer). STOP this sign-in / sign-up now: don't call browser_secure_input again for $host in this task, and don't keep filling its steps. Say in one line what you needed and offer the live browser (browser_show) if they want to finish it themselves."
                }
            }
            answer.values.forEach { (n, v) -> S.put(n, v) }
            if (answer.save) fields.filter { it.kind.saveable }.forEach { f -> answer.values[f.name]?.takeIf { it.isNotEmpty() }?.let { V.save(host, f.name, it) } }
            add(Role.STEP, "Filling the page on $host")
            val missed = fields.filter { f -> val v = answer.values[f.name].orEmpty(); v.isEmpty() || !B.fillSecret(f, v) }
            var out = S.summary(fields, answer.save, fromVault)
            if (missed.isNotEmpty()) out += " Couldn't find the page field for ${missed.joinToString { it.label }}: call browser_read, then browser_type number=N text=\"${S.placeholder(missed.first().name)}\"."
            else if (args.optBoolean("submit", true)) out += "\n" + B.submitSecret()
            return S.scrub(out)
        }

        /** Every tool call goes through here: a crash or an empty result becomes a plain error with a fix. */
        /** 0.45.18: the prompt-injection card for a screen action, or null to go ahead. */
        private suspend fun screenGate(name: String, args: JSONObject): String? {
            val taint = kotlin.coroutines.coroutineContext[ConfirmGate.Taint] ?: return null
            val label = args.optString("text").ifBlank { if (args.has("element")) com.blaze.assistant.phone.BlazeAccessibilityService.instance?.labelOf(args.optInt("element")).orEmpty() else "" }
                .ifBlank { args.optString("button") }.trim()
            val app0 = com.blaze.assistant.phone.BlazeAccessibilityService.instance?.currentApp() ?: "the app"
            val what = when (name) { "type_text" -> "type \u201c${label.take(40)}\u201d"; "press_button" -> "press ${label.ifBlank { "a button" }.take(30)}"
                "run_plan" -> "run a saved set of taps"; else -> "tap \u201c${label.take(40)}\u201d" }
            return when (ConfirmGate.screenAct(name, label, taint.source != null, taint.outside, taint.screenOk)) {
                ConfirmGate.ScreenDecision.PASS -> null
                ConfirmGate.ScreenDecision.ASK_ONCE ->
                    if (confirm("Use the screen in $app0?", "Blaze wants to $what, and keep working on the screen this turn.", "Allow", always = true)) { taint.screenOk = true; null }
                    else "The user did NOT approve acting on the screen after reading ${taint.outside}. Don't retry; tell them what you would have done so they can do it."
                ConfirmGate.ScreenDecision.ASK -> {
                    taint.askedLabel = label
                    if (confirm("${what.replaceFirstChar { it.uppercase() }}?", "Blaze wants to $what in $app0.", "Tap", always = true)) null
                    else "The user did NOT approve tapping \"$label\". Don't retry; tell them it's ready for them to finish."
                }
            }
        }

        suspend fun runTool(name: String, args: JSONObject, shots: MutableList<String>): String {
            // Plan mode is enforced here, at dispatch, not only in the prompt.
            Modes.block(name, args, agentPlan).takeIf { it.isNotEmpty() }?.let { add(Role.STEP, "Plan mode: skipped ${name.replace('_', ' ')}"); return it }
            if (agentId != null && name in BgAgents.leadOnly) return "Error: background agents can't start or steer other agents. Do the work yourself."
            if (isPrivate && PrivateChat.blocked(name)) return PrivateChat.REFUSAL
            // v0.43.0 Bank mode: Blaze has stepped back, so it doesn't look at or act on the screen until the user turns it off.
            if (com.blaze.assistant.bank.BankMode.active && (name in Orient.acting || name in Orient.looking || name == "open_app" || name == "search_in_app"))
                return "Bank mode is on: Blaze has stepped back so the user's banking app works, and doesn't read or use the screen. " +
                    "Tell the user, and offer to turn Bank mode off (bank_mode action=off) when they're done banking."
            // 0.45.18: screen actions after outside content (web, messages, email…) need the user's OK (ConfirmGate.screenAct).
            if (name in ConfirmGate.screenActs) screenGate(name, args)?.let { return it }
            // Visible pointer (v0.21.0): while the user has the pointer, screen actions wait for them.
            if (name in Orient.acting && com.blaze.assistant.phone.Pointer.enabled) pointerGate()?.let { return it }
            // Orient loop (v0.20.0): know where we are before acting on the screen.
            val (held, glance) = try { orientFirst(name, args) } catch (e: CancellationException) { throw e } catch (_: Throwable) { null to "" }
            if (held != null) return held
            // v0.30.7 plan memory: the element's words before it's acted on (numbers change, words replay).
            val recName = if (name == com.blaze.assistant.phone.SettingsJump.TOOL) "open_settings" else name
            val recSvc = com.blaze.assistant.phone.BlazeAccessibilityService.instance
            val recLabel = if ((name == "tap" || name == "type_text") && args.has("element") && args.optString("text").isBlank())
                try { recSvc?.labelOf(args.optInt("element")).orEmpty() } catch (_: Throwable) { "" } else ""
            recSvc?.lastToggle = null
            val undoBefore = UndoCenter.snapshot(name)
            val r0 = try { runToolRaw(name, args, shots) }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { com.blaze.assistant.device.CrashLog.error("tool $name", e); ToolGuide.crash(name, e) }
            // v0.39.0: a finished undoable action under Autopilot gets the 10-second Undo bar; a missing access gets a fix card.
            if (agentId == null && this === cur) try { UndoCenter.afterTool(name, args, r0, Modes.autopilot || ackGate?.acked == true, undoBefore) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Agent: UndoCenter.afterToolname args r0 Modes.a", e) }
            val fix = if (agentId == null) FixCards.kindOf(r0) else null
            if (fix != null) FixCards.afterTool(name, r0)
            val r = if (fix != null) r0 + FixCards.NOTE else r0
            if (recName in planTools) {
                screenActed = true
                if (TapLoop.failed(r)) planRec.fail()
                else if (recName !in TapLoop.lookOnly) planRec.ok(TapLoop.stepOf(recName, args, recLabel, if (name == "tap") recSvc?.lastToggle else null))
            }
            // v0.28.2: outside content is now in the context: later sends / deletes this turn always ask (ConfirmGate).
            ConfirmGate.sourceOf(name)?.let { taint.mark(it) }
            // v0.30.4: any result that carries the screen (open_settings, a stopped run_plan) counts as a look, so the next
            // numbered tap isn't bounced with "Looking at the screen first" (a whole extra model round trip).
            if ((name in Orient.looking || FastActions.isScreen(r)) && !r.startsWith("Phone control")) com.blaze.assistant.phone.BlazeAccessibilityService.instance?.let { orient.saw(it.currentPkg()) }
            return if (r.isBlank()) ToolGuide.empty(name) else glance + r
        }

        /**
         * Before a screen action: GO, a compact glance put in front of the result, or (for numbered taps
         * on a screen Blaze hasn't seen this turn) hold the action and return the fresh screen instead.
         */
        private suspend fun orientFirst(name: String, args: JSONObject): Pair<String?, String> {
            if (name !in Orient.acting || !Settings.phoneControl) return null to ""
            val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance ?: return null to ""
            val now = svc.currentPkg()
            return when (Orient.decide(name, args, orient, now)) {
                Orient.Decision.GO -> null to ""
                Orient.Decision.LOOK_FIRST -> {
                    orient.bounced++
                    add(Role.STEP, "Looking at the screen first")
                    val screen = svc.readScreen()
                    orient.saw(now)
                    Orient.lookFirst(name, screen) to ""
                }
                Orient.Decision.GLANCE -> {
                    val screen = svc.readScreen()
                    orient.saw(now)
                    null to Orient.glanceNote(Orient.compact(screen))
                }
            }
        }

        /** v0.45.14: the pointer never pauses a run; the only stop from the screen is its Stop chip, which ends the task. */
        private fun pointerGate(): String? {
            if (!com.blaze.assistant.phone.Pointer.takeStop()) return null
            add(Role.STEP, "You stopped me"); job?.cancel()
            return "Stopped by the user."
        }

        /** The "context → orientation" step at the start of a phone-control turn: a compact look, or "" when it wouldn't help. */
        private fun orientContext(): String = orientRead().let { (pkg, note) -> if (pkg.isNotEmpty()) orient.saw(pkg); note }

        /** v0.45.6: what the Design view proved about this turn's design (set by the design tool step). */
        @Volatile var turnDesignRender: com.blaze.assistant.ui.DesignStatus.Status? = null
        @Volatile var turnDesignRenderSet = false

        /** v0.45.3: commands the user approved on this step's batch card (exact command keys; cleared each step). */
        private val cmdBatchOk = java.util.Collections.synchronizedSet(HashSet<String>())
        private fun cmdBatchOkClear() = cmdBatchOk.clear()

        private fun cmdRoots(): List<String> = CommandPolicy.DEFAULT_ROOTS + listOfNotNull(
            try { com.blaze.assistant.terminal.Terminal.home().absolutePath } catch (_: Throwable) { null },
            try { app.filesDir.absolutePath } catch (_: Throwable) { null })

        private fun cmdExact(kind: CommandPolicy.Kind, text: String) = kind.name + ":" + text.trim().replace(Regex("\\s+"), " ")

        /**
         * v0.45.3: may this command run? Autopilot keeps its old rule exactly ([legacy]). Normal (Auto): safe commands run,
         * risky ones always ask, the rest ask with "Allow for this chat" / "Always allow this command" (CommandPolicy).
         */
        suspend fun cmdOk(kind: CommandPolicy.Kind, text: String, title: String, detail: String, action: String, risky: Boolean,
                          legacy: suspend () -> Boolean): Boolean {
            if (Modes.autopilot) return legacy()
            val level = CommandPolicy.level(kind, text, risky, cmdRoots())
            val key = CommandPolicy.key(kind, text)
            val tainted = kotlin.coroutines.coroutineContext[ConfirmGate.Taint]?.source != null
            return when (CommandPolicy.decide(level, key, cmdGrants.chat(id), cmdGrants.always(), tainted, cmdExact(kind, text) in cmdBatchOk)) {
                CommandPolicy.Decision.RUN -> true
                CommandPolicy.Decision.ASK_RISKY -> confirm(title, detail, action, always = true)
                CommandPolicy.Decision.ASK -> {
                    val g = confirmGrant(title, detail, action)
                    if (g == CommandPolicy.Grant.CHAT || g == CommandPolicy.Grant.ALWAYS) { cmdGrants.grant(id, key, g); note("Allowed \u201c$key\u201d " + if (g == CommandPolicy.Grant.ALWAYS) "from now on" else "for this chat", false) }
                    g != CommandPolicy.Grant.NO
                }
            }
        }

        /** The command a tool call would run, for the batch card (null = not a command). */
        private fun cmdOf(name: String, args: JSONObject): Pair<CommandPolicy.Kind, String>? = when (name) {
            "run_shell" -> if (InAppRoute.toolFor(args.optString("command")) != null) null else CommandPolicy.Kind.SHELL to args.optString("command")
            com.blaze.assistant.terminal.Git.NAME -> CommandPolicy.Kind.GIT to ("git " + args.optString("action").takeIf { it.isNotBlank() && it != "raw" }.orEmpty() + " " + args.optString("args")).trim()
            "desktop" -> when (args.optString("action")) {
                "type" -> CommandPolicy.Kind.DESKTOP_TYPE to args.optString("text")
                "key" -> CommandPolicy.Kind.DESKTOP_KEY to args.optString("keys")
                "run", "launch" -> CommandPolicy.Kind.DESKTOP_OPEN to args.optString("app").ifBlank { args.optString("text") }
                else -> null
            }
            else -> null
        }

        /**
         * v0.45.3: several commands in one reply (a multi-step plan) get ONE card in Normal mode. Approving it lets each of
         * them run; "Allow for this chat" / "Always" (offered when none is risky) also remember them. Refusing skips them all.
         * Returns the indices the user refused (their tool results say so).
         */
        suspend fun batchApprove(calls: List<Pair<String, JSONObject>>): Set<Int> {
            cmdBatchOk.clear()
            if (Modes.autopilot || calls.size < 2) return emptySet()
            val tainted = kotlin.coroutines.coroutineContext[ConfirmGate.Taint]?.source != null
            val roots = cmdRoots()
            val need = calls.mapIndexedNotNull { i, (n, a) ->
                val c = cmdOf(n, a) ?: return@mapIndexedNotNull null
                if (c.second.isBlank()) return@mapIndexedNotNull null
                val risky = (n == "run_shell" && com.blaze.assistant.terminal.Terminal.risky(c.second)) || (n == com.blaze.assistant.terminal.Git.NAME && com.blaze.assistant.terminal.Git.risky(a))
                val lvl = CommandPolicy.level(c.first, c.second, risky, roots)
                val key = CommandPolicy.key(c.first, c.second)
                if (CommandPolicy.decide(lvl, key, cmdGrants.chat(id), cmdGrants.always(), tainted) == CommandPolicy.Decision.RUN) null else Triple(i, c, lvl)
            }
            if (need.size < 2) return emptySet()
            val (title, detail) = CommandPolicy.batch(need.map { (_, c, l) -> c.second to l })
            val anyRisky = need.any { it.third == CommandPolicy.Level.RISKY }
            val g = if (anyRisky) (if (confirm(title, detail, "Run all", always = true)) CommandPolicy.Grant.ONCE else CommandPolicy.Grant.NO)
                else confirmGrant(title, detail, "Run all")
            if (g == CommandPolicy.Grant.NO) return need.map { it.first }.toSet()
            for ((_, c, _) in need) {
                cmdBatchOk += cmdExact(c.first, c.second)
                if (g == CommandPolicy.Grant.CHAT || g == CommandPolicy.Grant.ALWAYS) cmdGrants.grant(id, CommandPolicy.key(c.first, c.second), g)
            }
            return emptySet()
        }

        /** v0.45.1: the orient read without touching the turn's orient state ((package, note); note "" = nothing to say). */
        private fun orientRead(): Pair<String, String> {
            if (!Settings.phoneControl || agentId != null) return "" to ""
            val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance ?: return "" to ""
            val pkg = svc.currentPkg()
            if (pkg.isBlank() || pkg == app.packageName) return "" to ""   // Blaze's own chat is on screen: nothing to orient on yet
            return try { Orient.contextNote(Orient.compact(svc.readScreen())).let { pkg to it } } catch (_: Throwable) { "" to "" }
        }

        /** Runs terminal work, then puts any new pictures / audio / video it wrote into the Gallery and this reply. */
        private suspend fun withMediaScan(block: suspend () -> String): String {
            val started = System.currentTimeMillis() - 2000
            try { com.blaze.assistant.media.Media.installHelpers(com.blaze.assistant.terminal.Terminal.home()) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: Media.installH", e) }
            val out = block()
            val found = kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    val roots = listOf(com.blaze.assistant.terminal.Terminal.home(), java.io.File(com.blaze.assistant.terminal.Linux.rootfs(), "root"))
                    roots.filter { it.isDirectory }.flatMap { r ->
                        r.walkTopDown().maxDepth(4).onEnter { d -> d.name !in setOf("node_modules", "blaze_lib", "site-packages", ".cache", ".git", "phone") || d == r }
                            .filter { it.isFile && it.lastModified() >= started && it.length() > 1000 && com.blaze.assistant.media.Media.isMedia(it) }.toList()
                    }.distinctBy { it.canonicalPath }.sortedBy { it.lastModified() }.takeLast(6)
                } catch (_: Exception) { emptyList() }
            }
            if (found.isEmpty()) return out
            val names = found.mapNotNull { f -> try { com.blaze.assistant.media.Media.add(f).also { g -> synchronized(pendingMedia) { if (g.absolutePath !in pendingMedia) pendingMedia += g.absolutePath } }.name } catch (_: Exception) { null } }
            if (names.isNotEmpty()) add(Role.STEP, "Added to your Gallery: " + names.joinToString())
            return out + "\n[Shown to the user in the chat and saved in their Gallery: ${names.joinToString()}. No need to export or link them.]"
        }

        /**
         * An MCP tool (mcp__<server>__<tool>) with Desktop's approval rules: read-only tools run, tools the
         * user switched off are refused, others show the confirm card (Action tools on their default pass in
         * Autopilot; destructive ones always ask, see McpConfig.needsApproval).
         */
        private suspend fun mcpCall(name: String, args: JSONObject): String {
            val m = com.blaze.assistant.connect.Mcp
            val t = m.find(name) ?: return "That MCP tool isn't available any more (its server is off, removed or unreachable). Use find_tool or another way."
            val label = "${t.server.name} \u00b7 ${t.tool.name}"
            if (t.mode == com.blaze.assistant.connect.McpConfig.OFF) return "Refused: the user turned ${t.tool.name} off in Settings \u203a MCP servers."
            if (com.blaze.assistant.connect.McpConfig.needsApproval(t.override, t.tool.risk, Modes.autopilot)) {
                val shown = args.toString(2).let { if (it.length > 900) it.take(900) + "\n\u2026" else it }
                val warn = if (t.tool.risk == com.blaze.assistant.connect.McpConfig.Risk.WRITE) "This tool can delete or change data on ${t.server.name}.\n" else ""
                if (!confirm("Allow $label?", warn + shown, "Allow"))
                    return "The user did NOT approve ${t.tool.name}. Don't retry it; say what you were about to do and ask how they'd like to proceed."
            }
            add(Role.STEP, "Using $label")
            return m.call(t, args)
        }

        /**
         * v0.30.5 SkillGuard: use_skill / run_skill only for a skill about what the user is asking (their last few
         * messages, so "yes, do it" still carries the request). Null to go ahead, else the refusal the model reads.
         */
        private fun skillGate(name: String, args: JSONObject): String? {
            if (name != "use_skill" && name != "run_skill") return null
            if (lastUserText().startsWith("[Scheduled routine") || agentId != null) return null   // a routine / background agent names its own skills
            val request = msgs.filter { it.role == Role.USER }.takeLast(3).joinToString(" ") { it.text.take(600) }
            return try { SkillGuard.gate(args.optString("name"), request, args.optString("reason"), SkillMatch.cached()) } catch (_: Throwable) { null }
        }

        /** Wi-Fi or another unmetered network (speculative fetches only run there). */
        private fun unmetered(): Boolean = try { app.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == false } catch (_: Throwable) { false }

        suspend fun runToolRaw(name: String, args: JSONObject, shots: MutableList<String>): String {
            turnTools.add(name)
            // v0.45.1: a tool that may change the screen (anything but a read) makes the next screen read walk the tree again.
            if (name != "read_screen" && name != "look_at_screen") com.blaze.assistant.phone.BlazeAccessibilityService.touched()
            val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance
            // Record phone actions (with the element's label, since numbers change) for task replay.
            if (name in setOf("open_app", "tap", "type_text", "scroll", "press_button", "run_plan", "do_actions", "search_in_app", "read_screen")) com.blaze.assistant.phone.Watchers.taskTick()
            if (name in setOf("open_app", "tap", "type_text", "scroll", "press_button", "open_url", "search_in_app", com.blaze.assistant.phone.SettingsJump.TOOL) && trace.length() < 40) {
                val label = args.optString("text").takeIf { name == "tap" && it.isNotBlank() }
                    ?: if (name == "tap" || name == "type_text") svc?.labelOf(args.optInt("element")).orEmpty() else ""
                trace.put(JSONObject().put("tool", name).put("args", JSONObject(args.toString()).apply { remove("element") }).put("label", label))
            }
            // v0.45.9 MemoryGate: no memory writes on small talk or read-only questions (schedule, weather, time).
            MemoryGate.refusal(name, lastUserText())?.let { return it }
            return when (name) {
                "remember" -> { add(Role.STEP, "Remembering that"); Memory.add(args.optString("fact"), args.optString("replaces")) }
                "forget" -> { add(Role.STEP, "Forgetting that"); Memory.forget(args.optString("fact")) }
                "memory_delete" -> {
                    // v0.45.3: one card for the whole list (a delete keeps its card in Autopilot too), then one delete pass.
                    val want = MemoryDelete.idsOf(args.opt("ids") ?: args.opt("id"))
                    val lines = kotlinx.coroutines.withContext(Dispatchers.IO) { val byId = Mind.all().associateBy { it.id }; want.mapNotNull { byId[it]?.let(Mind::line) } }
                    if (want.isEmpty()) "No ids given. memory_search shows each line's #id."
                    else if (lines.isEmpty()) "Nothing deleted: none of " + want.joinToString(", ") { "#$it" } + " is saved (memory_search shows the current ids)."
                    else {
                        val (title, detail) = MemoryDelete.card(lines)
                        add(Role.STEP, "Asking before deleting ${lines.size} saved ${if (lines.size == 1) "memory" else "memories"}")
                        if (!confirm(title, detail, "Delete", always = true)) "The user did NOT approve deleting those memories. Nothing was deleted; don't ask again in this task."
                        else kotlinx.coroutines.withContext(Dispatchers.IO) { Mind.run(name, args) }.also { add(Role.STEP, it.take(80)) }
                    }
                }
                in Mind.toolNames -> { add(Role.STEP, if (name == "memory_search") (if (MemoryIntent.meansAll(args.optString("query"))) "Looking through what I remember" else "Checking memory for \u201c${args.optString("query").take(40)}\u201d") else "Saving to memory")
                    kotlinx.coroutines.withContext(Dispatchers.IO) { Mind.run(name, args) } }
                "generate_image" -> if (Regex("(?i)\\b(pie|bar|line)\\s*chart|\\bgraph\\b|\\bplot\\b|\\bdiagram\\b|\\btable\\b|\\bpdf\\b|\\binfographic|\\bspreadsheet").containsMatchIn(args.optString("prompt"))) {
                    "Refused: the AI picture tool can't draw exact charts, tables or documents (it makes a random look-alike). Make it with run_code (Python matplotlib / reportlab) and save under /root/phone/. " +
                        "If the terminal isn't installed yet, call linux_install first and wait for it, then run_code. Never send the user an AI picture instead of a real chart."
                } else {
                    add(Role.STEP, "Drawing \u201c${args.optString("prompt").take(60)}\u201d")
                    com.blaze.assistant.media.Media.generate(args.optString("prompt"), args.optInt("width", 1024), args.optInt("height", 1024),
                        if (args.has("seed")) args.optInt("seed") else null, args.optString("model")).fold(
                        { f -> synchronized(pendingMedia) { pendingMedia += f.absolutePath }
                            "Image made and shown to the user in the chat (saved in their Gallery as ${f.name}). Reply with one short line about it; don't describe it at length or paste a link." },
                        { e -> "Image service failed (${e.message}). Try once more with a simpler prompt, or draw it with run_code (Python + Pillow) and save it under /root/phone/." })
                }
                "gallery" -> {
                    val M = com.blaze.assistant.media.Media
                    when (args.optString("action")) {
                        "add" -> {
                            val f = com.blaze.assistant.terminal.DevTools.resolve(args.optString("path"))
                            if (!f.isFile) "No such file: ${args.optString("path")}"
                            else { val g = M.add(f, args.optString("title")); synchronized(pendingMedia) { if (g.absolutePath !in pendingMedia) pendingMedia += g.absolutePath }
                                add(Role.STEP, "Added ${g.name} to your Gallery"); "Added to the Gallery and shown in the chat as ${g.name}." }
                        }
                        "save" -> M.find(args.optString("path"))?.let { add(Role.STEP, "Saving ${it.name} to your phone"); M.saveToPhone(it) } ?: "Not in the gallery: ${args.optString("path")}. Call gallery list."
                        "share" -> M.find(args.optString("path"))?.let { app.startActivity(M.shareIntent(it)); "Opened the share sheet for ${it.name}." } ?: "Not in the gallery."
                        "delete" -> M.find(args.optString("path"))?.let { f -> if (confirm("Delete from Gallery?", f.name, "Delete", always = true)) { M.delete(f); "Deleted ${f.name}." } else "The user did NOT approve deleting it." } ?: "Not in the gallery."
                        else -> M.list()
                    }
                }
                "ask_pc" -> {
                    val L = com.blaze.assistant.link.DesktopLink
                    add(Role.STEP, "Asking ${L.pcName}: \u201c${args.optString("task").take(60)}\u201d")
                    L.askPc(args.optString("task"), args.optBoolean("new_conversation")) { line -> add(Role.STEP, "${L.pcName}: ${line.take(90)}") }
                }
                // v0.33.1 content-hash cache (identical lookups reused, private chats never) + speculative fetch of the top results on Wi-Fi.
                "web_search" -> { add(Role.STEP, "Searching the web for \u201c${args.optString("query").take(50)}\u201d")
                    val r = ToolCache.cached(name, args, lastUserText(), isPrivate) { Web.search(args.optString("query")) }
                    if (!r.startsWith("[Cached")) ToolCache.prefetch(r, unmetered(), Settings.lowData, isPrivate) { u -> Web.fetch(u) }
                    r }
                "fetch_page" -> { add(Role.STEP, "Reading ${args.optString("url").removePrefix("https://").take(50)}")
                    val a = JSONObject(args.toString()).put("url", ToolLeak.fixBlazeUrl(args.optString("url")))
                    ToolCache.cached(name, a, lastUserText(), isPrivate) { Web.fetch(a.optString("url")) } }
                "blazerouter" -> { add(Role.STEP, "BlazeRouter: " + args.optString("action")); BlazeRouter.run(app, args) }
                com.blaze.assistant.desktop.DesktopAgent.TOOL -> {
                    add(Role.STEP, "Using the computer: " + args.optString("title").ifBlank { args.optString("goal") }.take(50))
                    com.blaze.assistant.desktop.DesktopAgent.run(app, args,
                        confirm = { title, detail, always -> confirm(title, detail, if (always) "Allow" else "Start", always) },
                        open = { com.blaze.assistant.ui.Views.open("linux") }).also { offerDesktopView() }
                }
                CommandRegistry.TOOL -> {
                    val cn = args.optString("command").trim().removePrefix("/").substringBefore(' ').lowercase()
                    val input = args.optString("input").ifBlank { args.optString("command").trim().substringAfter(' ', "") }.trim()
                    add(Role.STEP, "Running /$cn")
                    when (CommandRegistry.kind(cn)) {
                        CommandRegistry.Kind.USER_ONLY -> "/$cn is for the user to run (it would end or wipe this turn). Tell them they can type /$cn."
                        CommandRegistry.Kind.UNKNOWN -> "No /$cn command. The list is in blaze_now."
                        CommandRegistry.Kind.PROMPT -> Commands.all().firstOrNull { it.name == cn && it.prompt != null }?.let { "Follow this now: " + Commands.expand(it, input) } ?: "No /$cn command."
                        CommandRegistry.Kind.VIEW -> {
                            val v = com.blaze.assistant.ui.Views.find(cn)!!
                            val opened = if (agentId == null) kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Views.openOrOffer(v.id, input, lastUserText(), true) } else false
                            if (opened) "Opened ${v.name}." else "Couldn't open ${v.name} from a background agent."
                        }
                        CommandRegistry.Kind.ACTION -> if (agentId != null) "App commands run only in the main chat." else kotlinx.coroutines.withContext(Dispatchers.Main) {
                            val r = com.blaze.assistant.ui.CommandRunner.run(app, "/$cn" + (if (input.isNotBlank()) " $input" else ""),
                                openSettings = { pg -> SelfSetup.openPage.value = pg }, listen = { com.blaze.assistant.voice.Voice.listenRequests.value++ })
                            when { r == null -> "No /$cn command."; r.isBlank() -> "Ran /$cn."; else -> "Ran /$cn. Follow this now: $r" }
                        }
                    }
                }
                "desktop" -> {
                    // v0.25.5: the step line comes from Friendly.say (DesktopNarration), the same words as the live desktop card.
                    com.blaze.assistant.desktop.LinuxDesktop.run(app, args, shots,
                        confirm = { title, detail -> confirm(title, detail, "Allow") },
                        cmdOk = { kind, text, title, detail, risky, legacy -> cmdOk(kind, text, title, detail, "Allow", risky, legacy) },
                        open = { com.blaze.assistant.ui.Views.open("linux") }).also { offerDesktopView() }
                }
                in com.blaze.assistant.home.HomeTools.NAMES -> { add(Role.STEP, com.blaze.assistant.home.HomeTools.step(name, args)); kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.home.HomeBoard.runTool(name, args) } }
                BlazeDocs.TOOL_SEARCH -> { add(Role.STEP, "Looking it up in Blaze's docs"); kotlinx.coroutines.withContext(Dispatchers.IO) { BlazeDocs.search(args) } }
                BlazeDocs.TOOL_READ -> { add(Role.STEP, "Reading Blaze's docs"); kotlinx.coroutines.withContext(Dispatchers.IO) { BlazeDocs.read(args) } }
                ProjectDocs.TOOL_SEARCH -> { add(Role.STEP, "Checking the project's notes"); kotlinx.coroutines.withContext(Dispatchers.IO) { ProjectDocs.search(id, args) } }
                ProjectDocs.TOOL_READ -> { add(Role.STEP, "Reading the project's notes"); kotlinx.coroutines.withContext(Dispatchers.IO) { ProjectDocs.read(id, args) } }
                com.blaze.assistant.proactive.FollowUps.TOOL -> { add(Role.STEP, "Checking what you need to get back to"); com.blaze.assistant.proactive.FollowUps.run(args) }
                com.blaze.assistant.bank.BankMode.TOOL -> { add(Role.STEP, if (args.optString("action") == "off") "Turning Bank mode off" else if (args.optString("action") == "on") "Turning Bank mode on" else "Checking Bank mode"); com.blaze.assistant.bank.BankMode.runTool(app, args) }
                com.blaze.assistant.proactive.Jarvis.TOOL -> { add(Role.STEP, "Changing how often I reach out"); com.blaze.assistant.proactive.Jarvis.run(args) }
                Awareness.TOOL -> { add(Role.STEP, "Checking what's going on"); kotlinx.coroutines.withContext(Dispatchers.IO) { Awareness.report(com.blaze.assistant.ui.Views.all.map { Triple(it.name, it.id, it.desc) }, commands = CommandRegistry.catalog()) } }
                SelfKnowledge.TOOL -> { add(Role.STEP, "Checking my own setup"); kotlinx.coroutines.withContext(Dispatchers.IO) { SelfKnowledge.report(app, args.optString("section")) } }
                com.blaze.assistant.ui.Views.TOOL -> {
                    val v = com.blaze.assistant.ui.Views.find(args.optString("view"))
                    if (v == null) "Unknown view. Pick one of: " + com.blaze.assistant.ui.Views.all.joinToString { it.id }
                    else {
                        add(Role.STEP, com.blaze.assistant.ui.Views.step(args))
                        // v0.24.6: Blaze asks first with an "Open in <view>?" chip in the chat, unless the user asked for the view.
                        val tgt = args.optString("target").ifBlank { if (v.id == "design") kotlinx.coroutines.withContext(Dispatchers.IO) { Designs.current()?.path }.orEmpty() else "" }
                        val opened = if (agentId == null) kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Views.openOrOffer(v.id, tgt, lastUserText(), Modes.autopilot) } else false
                        if (v.id == "design" && tgt.isBlank()) "There is no design to show yet: make one with the design tool (action=create)."
                        else if (!opened) "Offered to open ${v.name} with an \u201c${com.blaze.assistant.ui.Views.offerLabel(v.id)}\u201d chip in the chat; the user opens it with one tap. Don't say it's already open."
                        else "Opened ${v.name}" + (if (v.id == "design") " showing $tgt" else "") + ". The user can tap Exit to come back to the chat."
                    }
                }
                Designs.TOOL -> {
                    add(Role.STEP, Designs.step(args))
                    val out0 = kotlinx.coroutines.withContext(Dispatchers.IO) { Designs.run(args) }
                    // v0.26.2 Designer playbook, enforced: a page that renders as bare text / raw arrows / Mermaid-as-text is sent back.
                    val out = if (args.optString("action").lowercase() in setOf("create", "update", "edit") && out0.open != null) {
                        val note = kotlinx.coroutines.withContext(Dispatchers.IO) { try { DesignLint.note(java.io.File(out0.open).readText(), Designs.current()?.kind ?: "page") } catch (_: Exception) { "" } }
                        if (note.isNotEmpty()) out0.copy(text = out0.text + note) else out0
                    } else out0
                    val renderT0 = System.currentTimeMillis()
                    val opened = out.open?.let { path -> if (agentId == null) kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Views.openOrOffer("design", path, lastUserText(), Modes.autopilot) } else false }
                    // v0.45.6: never "Done" unless it rendered: wait briefly for the Design view's load / draw report on this version.
                    val render = if (opened == true && out.open != null) {
                        com.blaze.assistant.ui.DesignStatus.requestReload()
                        var st: com.blaze.assistant.ui.DesignStatus.Status? = null
                        val until = renderT0 + 15_000
                        while (System.currentTimeMillis() < until) {
                            st = com.blaze.assistant.ui.DesignStatus.since(out.open, renderT0)
                            if (st != null && st.state != com.blaze.assistant.ui.DesignStatus.State.PENDING) break
                            kotlinx.coroutines.delay(250)
                        }
                        st?.takeIf { it.state != com.blaze.assistant.ui.DesignStatus.State.PENDING }
                    } else null
                    turnDesignRender = render; turnDesignRenderSet = true
                    (if (opened == false) out.text + "\n(Design isn't open yet: the user has an \u201cOpen in Design?\u201d chip in the chat.)" else out.text) + DesignTruth.toolLine(render)
                }
                "ask_user" -> {
                    val opts = args.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                    add(Role.STEP, "Asked: ${args.optString("question").take(80)}")
                    mood = Mood.IDLE
                    val a = askUser(args.optString("question"), opts, title)
                    mood = Mood.ACTING
                    add(Role.STEP, "You answered: ${a.take(80)}")
                    "The user answered: $a"
                }
                in com.blaze.assistant.proactive.Triggers.names -> { add(Role.STEP, if (name == "create_trigger") "Setting up \u201c${args.optString("name")}\u201d" else "Checking triggers")
                    com.blaze.assistant.proactive.Triggers.run(name, args) }
                "run_shell" -> {
                    val cmd = args.optString("command")
                    // v0.45.6: "music_ops play" typed as a shell line runs as the in-app tool (no card, no terminal).
                    val inApp = InAppRoute.toolFor(cmd)
                    if (inApp != null) return runTool(inApp.first, JSONObject(inApp.second as Map<*, *>), shots)
                    add(Role.STEP, "$ " + cmd.take(70))
                    // v0.45.3: Normal mode runs safe commands without a card, keeps asking for risky ones (CommandPolicy); Autopilot as before.
                    if (!cmdOk(CommandPolicy.Kind.SHELL, cmd, "Run this command?", cmd.take(200), "Run", com.blaze.assistant.terminal.Terminal.risky(cmd)) {
                            !com.blaze.assistant.terminal.Terminal.risky(cmd) || confirm("Run this command?", cmd.take(200), "Run") })
                        "The user did NOT approve running that command."
                    else if (args.optBoolean("background")) kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.terminal.Terminal.startJob(cmd, args.optBoolean("linux"), args.optInt("max_minutes", 0)) }
                    else withMediaScan { com.blaze.assistant.terminal.Terminal.run(cmd, args.optInt("timeout_seconds", 60), args.optBoolean("linux")) }
                }
                "run_ussd" -> {
                    val code = args.optString("code")
                    add(Role.STEP, "Dialling $code")
                    if (com.blaze.assistant.phone.Ussd.risky(code) && !confirm("Dial $code?", "This looks like a mobile money or multi-step code.", "Dial", always = true))
                        "The user did NOT approve dialling $code."
                    else com.blaze.assistant.phone.Ussd.run(app, code)
                }
                in com.blaze.assistant.terminal.DevTools.names -> {
                    add(Role.STEP, when (name) {
                        "run_code" -> "Running ${args.optString("language", "code")}"
                        "file_write", "file_edit" -> "Writing ${args.optString("path").takeLast(50)}"
                        "file_read", "file_list" -> "Reading ${args.optString("path", "~").takeLast(50)}"
                        "http_request" -> "${args.optString("method", "GET").uppercase()} ${args.optString("url").removePrefix("https://").take(50)}"
                        "download_file" -> "Downloading ${args.optString("url").substringAfterLast('/').take(50)}"
                        else -> name.replace('_', ' ')
                    })
                    if (name == "file_delete" && !confirm("Delete this?", args.optString("path").take(200), "Delete", always = true)) "The user did NOT approve deleting that."
                    else if (name == "run_code") withMediaScan { com.blaze.assistant.terminal.DevTools.run(app, name, args) }
                    else com.blaze.assistant.terminal.DevTools.run(app, name, args)
                }
                in com.blaze.assistant.phone.PimEdit.names -> {
                    val (title, detail) = com.blaze.assistant.phone.PimEdit.describe(name, args)
                    add(Role.STEP, title.removeSuffix("?"))
                    if (!confirm(title, detail.take(200), if (name == "delete_event") "Delete" else "Save", always = name.startsWith("delete"))) "The user did NOT approve that change."
                    else kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.phone.PimEdit.run(app, name, args) }
                }
                com.blaze.assistant.terminal.Git.NAME -> {
                    add(Role.STEP, "git ${args.optString("action")} ${args.optString("repo").ifBlank { args.optString("branch") }}".trim())
                    val gitLine = cmdOf(name, args)?.second.orEmpty()
                    if (!cmdOk(CommandPolicy.Kind.GIT, gitLine, "Run this git command?", gitLine.take(200), "Run", com.blaze.assistant.terminal.Git.risky(args)) {
                            !com.blaze.assistant.terminal.Git.risky(args) || confirm("Run this git command?", "git " + args.optString("args").take(200), "Run") }) "The user did NOT approve that git command."
                    else com.blaze.assistant.terminal.Git.run(args)
                }
                com.blaze.assistant.phone.Location.NAME -> { add(Role.STEP, "Checking location"); kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.phone.Location.run(app) } }
                "linux_install" -> { add(Role.STEP, "Installing Alpine Linux"); com.blaze.assistant.terminal.Linux.install() }
                "terminal_jobs" -> kotlinx.coroutines.withContext(Dispatchers.IO) {
                    if (args.optInt("id", 0) > 0) com.blaze.assistant.terminal.Terminal.jobLog(args.optInt("id"), args.optInt("lines", 80)) else com.blaze.assistant.terminal.Terminal.listJobs() }
                "preview_server" -> {
                    val port = args.optInt("port")
                    val url = "http://127.0.0.1:$port" + args.optString("path").let { if (it.isBlank()) "" else if (it.startsWith("/")) it else "/$it" }
                    add(Role.STEP, "Opening localhost:$port")
                    val r = try { com.blaze.assistant.browser.BrowserEngine.open(app, url, true) } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                    kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.browser.BrowserTools.show(app) }
                    if (r.contains("ERR_CONNECTION_REFUSED", true) || r.contains("net::ERR", true)) "Nothing is answering on port $port yet. Check the job with terminal_jobs, or start the server with run_shell background=true.\n$r" else r
                }
                "self_test" -> { add(Role.STEP, "Running a self-check"); com.blaze.assistant.device.SelfCheck.report(app) }
                "terminal_stop" -> com.blaze.assistant.terminal.Terminal.stopJob(args.optInt("id"))
                "termux_run" -> { add(Role.STEP, "Termux: " + args.optString("command").take(60)); com.blaze.assistant.terminal.Terminal.runInTermux(app, args.optString("command"), args.optBoolean("background", true)) }
                "delegate" -> delegate(args)
                "create_specialist" -> createSpecialist(args)
                "recall_memory" -> { add(Role.STEP, if (MemoryIntent.meansAll(args.optString("query"))) "Looking through what I remember" else "Checking memory"); Memory.recall(args.optString("query")) }
                "blaze_self" -> {
                    val act = args.optString("action")
                    add(Role.STEP, when (act) { "setup_check", "status" -> "Checking my setup"; "install" -> "Installing ${args.optString("what")}"; "open" -> "Opening ${args.optString("target")}"; else -> "Adjusting myself: $act" })
                    if (act == "install") SelfSetup.install(app, args.optString("what")) else SelfControl.run(app, act, args)
                }
                "device_status" -> { add(Role.STEP, "Checking the phone"); com.blaze.assistant.device.DeviceStatus.describe(app) }
                // v0.45.9: "save this file" saves the last code block (or the given content) to Downloads/Blaze; Normal mode, no card.
                SaveFile.TOOL -> {
                    val texts = ArrayList<String>()
                    for (i in 0 until history.length()) history.optJSONObject(i)?.takeIf { it.optString("role") == "assistant" }?.let { (it.opt("content") as? String)?.let { c -> texts += c } }
                    if (SaveFile.lastCode(texts) == null) msgs.filter { it.role == Role.BLAZE }.forEach { texts += it.text }
                    val plan = SaveFile.plan(args, texts)
                    if (plan == null) SaveFile.NOTHING
                    else { add(Role.STEP, "Saving ${plan.name} to Downloads")
                        kotlinx.coroutines.withContext(Dispatchers.IO) { SaveFileAndroid.save(app, plan) } }
                }
                GatewayWeather.TOOL -> { add(Role.STEP, "Checking the weather")
                    kotlinx.coroutines.withContext(Dispatchers.IO) { GatewayWeather.run(args, Settings.gatewayToken()) { lastFix() } } }
                com.blaze.assistant.mediaapps.store.JamendoSource.TOOL -> { add(Role.STEP, "Setting up Jamendo"); com.blaze.assistant.mediaapps.store.JamendoSource.init(app); com.blaze.assistant.mediaapps.store.JamendoSource.runTool(args) }
                "look_at_screen" -> if (Settings.lowData) {
                    add(Role.STEP, "Reading the screen (low-data mode)")
                    "Low-data mode is on, so no screenshot was taken. Screen as text:\n" + (com.blaze.assistant.phone.BlazeAccessibilityService.instance?.readScreen() ?: "Phone control isn't connected.")
                } else {
                    // v0.33.3: the accessibility tree first (instant); a picture only when it isn't enough or pixels=true.
                    val text = if (args.optBoolean("pixels")) null else com.blaze.assistant.phone.BlazeAccessibilityService.instance?.readScreen()
                    if (text != null && VisionFast.textEnough(text)) {
                        add(Role.STEP, "Reading the screen")
                        text + "\n[Read as text, which is faster than a picture. If you must see images, a game, video or a map, call look_at_screen with pixels=true.]"
                    } else {
                        add(Role.STEP, "Looking at a screenshot")
                        val shot = PhoneTools.screenshot()
                        if (shot.startsWith("data:")) { shots += shot; "Screenshot taken. It is attached in the next message." } else shot
                    }
                }
                "start_agent" -> {
                    val bg = BgAgents.start(args.optString("task"), args.optString("title"), id)
                    val mid = add(Role.BLAZE, "Background agent ${bg.id}: ${bg.title}")
                    msgs = msgs.map { if (it.id == mid) it.copy(meta = BgAgents.CARD + bg.id) else it }
                    persist()
                    val queued = BgAgents.get(bg.id)?.status == BgAgents.QUEUED
                    "Started ${bg.id} \u00ab${bg.title}\u00bb${if (queued) " (queued: ${BgAgents.runningCount()} already running)" else ""}. Its card is in the chat; its result reaches you automatically. Tell the user in one short line and carry on."
                }
                in BgAgents.toolNames -> BgAgents.run(name, args, id)
                "call_control" -> {
                    add(Role.STEP, when (args.optString("action")) { "speaker" -> if (args.optBoolean("on", true)) "Loudspeaker on" else "Loudspeaker off"; "mute" -> if (args.optBoolean("on", true)) "Muting" else "Unmuting"
                        "end" -> "Hanging up"; "answer" -> "Answering"; "decline" -> "Declining"; "start_blaze_call" -> "Starting a call"; "keypad" -> "Pressing ${args.optString("digits")}"; else -> "Call controls" })
                    com.blaze.assistant.phone.CallControl.run(app, args)
                }
                "clipboard" -> {
                    val C = com.blaze.assistant.phone.ClipAccess
                    when (args.optString("action")) {
                        "set" -> { add(Role.STEP, "Copying to your clipboard"); kotlinx.coroutines.withContext(Dispatchers.Main) { C.setText(app, args.optString("text")) }; "Copied to the clipboard." }
                        "set_image" -> {
                            val p = args.optString("path")
                            val f = com.blaze.assistant.media.Media.find(p) ?: com.blaze.assistant.terminal.DevTools.resolve(p)
                            if (!f.isFile) "No such file: $p" else { add(Role.STEP, "Copying ${f.name} to your clipboard"); kotlinx.coroutines.withContext(Dispatchers.Main) { C.setFile(app, f) } }
                        }
                        "clear" -> { kotlinx.coroutines.withContext(Dispatchers.Main) { C.clear(app) }; "Clipboard cleared." }
                        "history" -> C.history().take(15).mapIndexed { i, t -> "${i + 1}. ${t.take(300)}" }.joinToString("\n").ifBlank { "No clipboard history yet." }
                        "get_image" -> {
                            add(Role.STEP, "Looking at your clipboard")
                            val clip = C.read(app)
                            val u = clip?.uris?.firstOrNull()
                            if (u == null) (if (clip == null) "Couldn't read the clipboard." else "There's no image on the clipboard" + (if (clip.text.isNotEmpty()) ", only text: ${clip.text.take(500)}" else "."))
                            else Attachments.imageUrl(app, u.first)?.let { shots += it; "The copied image is attached in the next message." } ?: "There's a file on the clipboard (${u.second.ifBlank { "unknown type" }}) but it isn't a picture I can open."
                        }
                        else -> {
                            add(Role.STEP, "Reading your clipboard")
                            val clip = C.read(app)
                            when {
                                clip == null -> "Couldn't read the clipboard. Turn on Blaze's accessibility service or 'draw over apps' so it can read it in the background."
                                clip.text.isEmpty() && clip.uris.isEmpty() -> "The clipboard is empty."
                                else -> clip.text.take(4000) + (if (clip.uris.isNotEmpty()) "\n[Also ${clip.uris.size} file(s) on the clipboard (${clip.uris.first().second.ifBlank { "file" }}). Use clipboard get_image to look at a picture.]" else "")
                            }
                        }
                    }
                }
                // v0.33.3: fuzzy (label + package + category), off the main thread, and the chat draws the result as a card.
                "list_apps" -> {
                    add(Role.STEP, "Checking your apps")
                    val (text, card) = kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.phone.InstalledApps.listTool(app, args.optString("filter")) }
                    if (card != null) turnAppsCard = card
                    text
                }
                in com.blaze.assistant.phone.AndroidControl.names -> {
                    add(Role.STEP, if (name == "blaze_camera") "Opening Blaze Camera" else name.replace('_', ' ').replaceFirstChar { it.uppercase() })
                    // v0.28.x: Blaze Camera brings its capture back into this chat, as an answer to the user's last message.
                    val a2 = if (name != "blaze_camera") args else JSONObject(args.toString()).put("_chat", id)
                        .put("_asked", msgs.lastOrNull { it.role == Role.USER }?.text.orEmpty())
                    try { kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.phone.AndroidControl.run(app, name, a2) } } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                }
                in SkillPacks.names -> {
                    skillGate(name, args)?.let { return it }
                    add(Role.STEP, when (name) { "use_skill" -> "Reading skill \u201c${args.optString("name")}\u201d"; "install_skill" -> "Installing skills"; "write_skill" -> "Saving a skill"; "save_workflow" -> "Saving a workflow"; else -> "Reading a skill file" })
                    try { SkillPacks.run(name, args) } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                }
                "watch" -> { add(Role.STEP, "Setting a watcher"); com.blaze.assistant.phone.Watchers.run(args) }
                "goal" -> { add(Role.STEP, if (args.optString("action") == "add") "Planning \u201c${args.optString("title").take(40)}\u201d" else "Updating the plan"); Autonomy.goal(args) }
                "standing_permission" -> { add(Role.STEP, "Updating permissions"); Autonomy.setPermission(args) }
                in Processes.names -> {
                    add(Role.STEP, if (name == "processes") "Checking what's running" else "${args.optString("action").replaceFirstChar { it.uppercase() }} ${args.optString("id")}")
                    try { Processes.run(app, name, args) } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                }
                "create_routine", "list_routines", "delete_routine", "schedule_command" -> {
                    add(Role.STEP, if (name == "create_routine" || name == "schedule_command") "Scheduling \"${args.optString("name")}\"" else "Checking routines")
                    try { com.blaze.assistant.routine.Routines.run(name, args) } catch (e: Exception) { "Error: ${e.message}" }
                }
                in com.blaze.assistant.phone.Skills.names -> {
                    add(Role.STEP, com.blaze.assistant.phone.Skills.describe(name, args))
                    com.blaze.assistant.phone.Skills.run(app, name, args)
                }
                com.blaze.assistant.update.Updater.TOOL -> {
                    if (this === cur) com.blaze.assistant.update.Updater.showCard()
                    com.blaze.assistant.update.Updater.fromTool(app, args.optBoolean("install", true))
                }
                in Learning.toolNames -> {
                    skillGate(name, args)?.let { return it }
                    add(Role.STEP, when (name) { "run_skill" -> "Replaying skill \"${args.optString("name")}\""; "skill_manage" -> "Saving a skill"; else -> "Searching past chats" })
                    try { Learning.run(app, name, args) } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                }
                "search_screen_memory" -> { add(Role.STEP, "Searching screen memory"); ScreenMemory.search(app, args.optString("query"), args.optString("app").ifBlank { null }) }
                "recent_notifications" -> { add(Role.STEP, "Checking notifications"); com.blaze.assistant.notify.NotificationBrain.list(args.optString("app").ifBlank { null }, args.optDouble("hours", 6.0)) }
                com.blaze.assistant.phone.Messaging.NAME -> { add(Role.STEP, "Messaging ${args.optString("to")}"); com.blaze.assistant.phone.Messaging.run(app, args) }
                com.blaze.assistant.browser.BrowserTools.SECURE -> secureInput(args)
                in com.blaze.assistant.browser.phone.PhoneBrowserTools.names -> {
                    // 0.45.19 Blaze Phone: one screen, so parallel specialists take turns (the first to use it holds it).
                    agentId?.let { me -> Dispatch.phone.take(me) { h -> BgAgents.get(h)?.live == true }?.let {
                        return "Blaze Phone is in use by another agent right now. Use web_search / fetch_page for this step, or finish without it." } }
                    add(Role.STEP, com.blaze.assistant.browser.phone.PhoneBrowserTools.describe(name, args))
                    // 0.45.19 fix 4: who drives Blaze Phone now, so the picture-in-picture Stop cancels exactly this turn.
                    com.blaze.assistant.browser.phone.PhoneBrowser.own(id, agentId)
                    val drv = com.blaze.assistant.browser.phone.PhoneBrowser.Driver(app) { reason, site ->
                        if (!inForeground) Notifier.actionRequired("Take control: ${reason.take(50)}", site,
                            "Blaze needs you on Blaze Phone ($site): ${reason.take(80)}. Tap, then Take control.") }
                    com.blaze.assistant.browser.phone.PhoneBrowserTools.run(drv, name, args, shots) { t, d, a -> confirm(t, d, a, always = true) }
                }
                in com.blaze.assistant.browser.BrowserTools.names -> {
                    // v0.26.2: parallel specialists take turns with the one background browser (the first to use it holds it).
                    agentId?.let { me -> Dispatch.browser.take(me) { h -> BgAgents.get(h)?.live == true }?.let { return it } }
                    add(Role.STEP, com.blaze.assistant.browser.BrowserTools.describe(name, args))
                    com.blaze.assistant.browser.BrowserTools.run(app, name, args)
                }
                "reply_to_notification" -> { add(Role.STEP, "Replying"); com.blaze.assistant.notify.NotificationBrain.reply(app, args.optInt("number"), args.optString("text")) }
                "run_plan" -> runPlan(args)
                com.blaze.assistant.phone.SettingsJump.TOOL -> com.blaze.assistant.phone.SettingsJump.run(app, args)
                "do_actions" -> {
                    val steps = args.optJSONArray("steps") ?: JSONArray()
                    val log = StringBuilder()
                    for (i in 0 until minOf(steps.length(), 8)) {
                        val st = steps.getJSONObject(i)
                        val act = st.optString("action").let { if (it == "type") "type_text" else it }
                        val sub = JSONObject(st.toString()).apply { remove("action") }
                        val r = runTool(act, sub, shots)
                        log.append("${i + 1}. $act: ${r.take(200)}\n")
                        if (r.startsWith("Error") || r.startsWith("No element") || r.contains("did NOT approve") || r.startsWith("Unknown")) { log.append("Stopped at step ${i + 1}."); break }
                        if (i < steps.length() - 1 && act != "wait") svc?.settle(900, 200)
                    }
                    log.toString()
                }
                "tap" -> {
                    add(Role.STEP, PhoneTools.describe(name, args))
                    val label = args.optString("text").ifBlank { svc?.labelOf(args.optInt("element")).orEmpty() }
                    // v0.30.7: want="on"/"off" on a switch: read it first and only tap when it isn't already that way.
                    val want = TapLoop.wantOf(args.optString("want"))
                    val wantId = if (want != null && svc != null) (if (args.optString("text").isNotBlank()) svc.reveal(args.optString("text")) else if (args.has("element")) args.optInt("element") else null) else null
                    val wantNow = wantId?.let { svc?.switchState(it) }
                    if (want != null && wantNow != null && !TapLoop.needsTap(want, wantNow))
                        "Already ${TapLoop.wantWord(want)}: \u201c${(wantId?.let { svc?.labelOf(it) } ?: label).ifBlank { label }.take(40)}\u201d. Not tapped, it's already how you wanted it."
                    else if (Settings.confirmRisky && risky.containsMatchIn(label) && kotlin.coroutines.coroutineContext[ConfirmGate.Taint]?.askedLabel != label.trim() &&
                        !confirm("Tap \u201c${label.take(40)}\u201d?", "Blaze wants to tap this in ${svc?.currentApp() ?: "the app"}.", "Tap", always = true))
                        "The user did NOT approve tapping \"$label\". Don't retry; tell them it's ready for them to finish."
                    else PhoneTools.run(app, name, args).let { r ->
                        // v0.45.13 verify-after-act: a switch tapped toward a wanted state is read back before anything says it changed.
                        if (want == null || wantId == null || svc == null || r.startsWith("Error") || r.startsWith("Couldn't")) r
                        else {
                            // 0.45.19: verify by STATE first (dark mode, Wi-Fi, Bluetooth… read from the system), then the switch
                            // re-found and re-read; polled with a timeout. A theme switch recreates the screen, so the old node
                            // read back "unknown" while Dark theme was on, and the reply said both (StateCheck).
                            val vr = com.blaze.assistant.phone.ToggleVerify.after(app, svc, label, wantId, want)
                            r.trimEnd() + " " + com.blaze.assistant.phone.ToggleVerify.marker(vr)
                        }
                    }
                }
                else -> when {
                    name in LongChat.names -> longChatTool(name, args)
                    name == "notif_reply" -> { add(Role.STEP, "Replying to ${args.optString("conversation").take(40)}")
                        com.blaze.assistant.notify.Conversations.reply(app, args.optString("conversation"), args.optString("text")) }
                    name in com.blaze.assistant.notify.Conversations.toolNames -> { add(Role.STEP, if (name == "notif_read") "Reading the chat with ${args.optString("conversation").take(40)}" else "Checking your chats")
                        kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.notify.Conversations.run(app, name, args) } }
                    name == com.blaze.assistant.phone.SafeWalk.TOOL -> { add(Role.STEP, "Safe Walk")
                        com.blaze.assistant.phone.SafeWalk.run(app, args) }
                    name == com.blaze.assistant.device.PhoneHealth.TOOL -> { add(Role.STEP, "Checking your phone's health")
                        kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.device.PhoneHealth.run(app, args) } }
                    name == com.blaze.assistant.mediaapps.CoverArt.TOOL -> { add(Role.STEP, "Forging cover art")
                        com.blaze.assistant.mediaapps.CoverArt.run(app, args) }
                    name == com.blaze.assistant.voice.ReadAloud.TOOL -> { add(Role.STEP, if (args.optString("action") == "stop") "Stopping" else "Reading aloud")
                        com.blaze.assistant.voice.ReadAloud.run(app, args) }
                    name == com.blaze.assistant.mediaapps.BeatLab.TOOL -> { add(Role.STEP, if (args.optString("action") == "record") "Listening to your hum" else "Making the beat")
                        com.blaze.assistant.mediaapps.BeatLab.run(app, args) }
                    name == com.blaze.assistant.mediaapps.ClipStudio.TOOL -> { add(Role.STEP, "Cutting the reel")
                        com.blaze.assistant.mediaapps.ClipStudio.run(app, args) }
                    name == Marketplace.TOOL -> { add(Role.STEP, when (args.optString("action")) { "browse" -> "Browsing the skill marketplace"; "share" -> "Packing your skill"; else -> "Reviewing the skill pack" })
                        Marketplace.run(app, args) }
                    name == DuoWorkspace.TOOL -> { add(Role.STEP, "Workspace: ${args.optString("action")}")
                        kotlinx.coroutines.withContext(Dispatchers.IO) { DuoWorkspace.run(app, args) } }
                    name == com.blaze.assistant.phone.CallCopilot.TOOL -> { add(Role.STEP, "Call Copilot"); com.blaze.assistant.phone.CallCopilot.run(app, args) }
                    name == HaloStudio.TOOL -> { add(Role.STEP, "Tuning Halo"); HaloStudio.run(args) }
                    name == com.blaze.assistant.notify.GroupBrain.TOOL -> { add(Role.STEP, "Reading ${args.optString("group").take(40)}")
                        kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.notify.GroupBrain.run(args) } }
                    name == TimeCapsule.TOOL -> { add(Role.STEP, if (args.optString("action") == "seal") "Sealing a time capsule" else "Time capsules")
                        kotlinx.coroutines.withContext(Dispatchers.IO) { TimeCapsule.run(app, args) } }
                    name == com.blaze.assistant.notify.MoneyRadar.TOOL -> { add(Role.STEP, "Checking Money Radar")
                        kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.notify.MoneyRadar.run(app, args) } }
                    name == com.blaze.assistant.device.CameraMathTool.TOOL -> { add(Role.STEP, "Working it out")
                        com.blaze.assistant.device.CameraMathTool.run(args) }
                    name == com.blaze.assistant.media.GalleryOps.TOOL -> { add(Role.STEP, com.blaze.assistant.media.GalleryOps.describe(args))
                        com.blaze.assistant.media.GalleryOps.run(app, args) }
                    name == com.blaze.assistant.mediaapps.MusicTools.TOOL -> { add(Role.STEP, com.blaze.assistant.mediaapps.MusicTools.describe(args))
                        com.blaze.assistant.mediaapps.MusicTools.run(app, args) }
                    name == com.blaze.assistant.mediaapps.VideoTools.TOOL -> { add(Role.STEP, com.blaze.assistant.mediaapps.VideoTools.describe(args))
                        com.blaze.assistant.mediaapps.VideoTools.run(app, args) }
                    name == com.blaze.assistant.mediaapps.MediaFetch.TOOL -> { add(Role.STEP, com.blaze.assistant.mediaapps.MediaFetch.describe(args))
                        kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.mediaapps.MediaFetch.run(app, args) } }
                    name == com.blaze.assistant.life.LifeLogs.TOOL -> { add(Role.STEP, com.blaze.assistant.life.LifeLogs.describe(args))
                        com.blaze.assistant.life.LifeLogs.run(app, args) }
                    name == Seller.TOOL -> { add(Role.STEP, if (args.optString("action") == "set") "Saving the brand kit" else "Reading the brand kit")
                        kotlinx.coroutines.withContext(Dispatchers.IO) { Seller.run(args) } }
                    name == Flashcards.TOOL -> { add(Role.STEP, if (args.optString("action") == "add") "Making flashcards" else "Checking your flashcards")
                        kotlinx.coroutines.withContext(Dispatchers.IO) { Flashcards.run(args) } }
                    com.blaze.assistant.connect.Mcp.handles(name) -> mcpCall(name, args)
                    com.blaze.assistant.connect.Connectors.handles(name) -> { add(Role.STEP, com.blaze.assistant.connect.Connectors.describe(name)); com.blaze.assistant.connect.Connectors.run(app, name, args) }
                    else -> { add(Role.STEP, PhoneTools.describe(name, args)); PhoneTools.run(app, name, args) }
                }
            }
        }


        /**
         * v1.9 fast path: run a whole script of steps the model planned, addressed by visible text
         * instead of element numbers. Each step is checked locally (target found, screen changed,
         * expected text appeared) — no model call until the script finishes or gets stuck.
         */
        suspend fun runPlan(args: JSONObject): String {
            val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance
                ?: return "Phone control isn't connected. Ask the user to turn on Blaze in Accessibility settings."
            val steps = args.optJSONArray("steps") ?: return "Error: no steps."
            val n = minOf(steps.length(), 15)
            fun desc(st: JSONObject): String = when (st.optString("do")) {
                "open_app" -> "Open ${st.optString("app").ifBlank { st.optString("target") }}"
                "tap" -> "Tap \u201c${st.optString("target").take(30)}\u201d"
                "long_press" -> "Hold \u201c${st.optString("target").take(30)}\u201d"
                "type" -> "Type \u201c${st.optString("text").take(30)}\u201d" + if (st.optBoolean("submit")) " and search" else ""
                "scroll" -> "Scroll ${st.optString("direction", "down")}"
                "back" -> "Go back"; "home" -> "Go home"; "enter" -> "Press Enter"
                "wait" -> "Wait"
                "search_in_app" -> "Search ${st.optString("app")} for \u201c${st.optString("query").take(30)}\u201d"
                "open_settings" -> "Open ${st.optString("app").let { if (it.isBlank()) "" else "$it\u2019s " }}${st.optString("page").replace('_', ' ')} settings"
                else -> st.optString("do")
            }
            val keys = (0 until n).map { FastActions.key(steps.getJSONObject(it)) }
            // v0.30.4: the same script sent again after it failed at step k skips the steps already done (the phone is
            // still on step k's screen), instead of reopening the app and starting over.
            val skip = FastActions.skipDone(keys, lastPlan, svc.currentPkg())
            val labels = (0 until n).map { desc(steps.getJSONObject(it)) + if (it < skip) " (already done)" else "" }.toMutableList()
            screenActed = true
            // v0.30.7: after a short settle the next step's own wait polls the new screen for its target (on-device check).
            var shortSettle = false
            val state = MutableList(n) { if (it < skip) 2 else 0 }   // 0 todo, 1 running, 2 done, 3 failed, 4 skipped
            fun board() = labels.mapIndexed { i, l -> (when (state[i]) { 2 -> "\u2713 "; 1 -> "\u2192 "; 3 -> "\u2715 "; 4 -> "\u2013 "; else -> "\u00b7 " }) + l }.joinToString("\n")
            val boardId = add(Role.STEP, board())
            val started = System.currentTimeMillis()
            val planChecks = mutableListOf<String>()
            for (i in skip until n) {
                val st = steps.getJSONObject(i)
                state[i] = 1; replace(boardId, Role.STEP, board())
                val t0 = System.currentTimeMillis()
                svc.lastToggle = null
                val pkgBefore = svc.currentPkg()
                val target = st.optString("target")
                var fail: String? = null
                try {
                    when (st.optString("do")) {
                        "open_app" -> {
                            val r = PhoneTools.run(app, "open_app", JSONObject().put("app_name", st.optString("app").ifBlank { target }))
                            if (r.startsWith("No app") || r.startsWith("Error") || r.startsWith("Couldn't")) fail = r
                            else {
                                val end = System.currentTimeMillis() + 3000
                                while (svc.currentPkg() == pkgBefore && System.currentTimeMillis() < end) kotlinx.coroutines.delay(100)
                                val (omx, oq) = TapLoop.settleAfter("open_app", if (i + 1 < n) steps.optJSONObject(i + 1) else null)
                                svc.settle(omx, oq)
                            }
                        }
                        // v0.30.7: settings pages by intent inside a plan (open_settings + tap in ONE call).
                        "open_settings" -> {
                            val r = com.blaze.assistant.phone.SettingsJump.run(app, JSONObject().put("page", st.optString("page"))
                                .apply { st.optString("app").takeIf { it.isNotBlank() }?.let { put("app", it) } })
                            if (TapLoop.failed(r)) fail = r.substringBefore("\n").take(200)
                        }
                        "tap", "long_press" -> {
                            // v0.30.4: a short wait for a lazy screen, then synonyms / fuzzy / off-screen rows scrolled into view
                            // (LabelMatch + reveal) instead of 3 s of polling and a single blind scroll.
                            val id = svc.waitFor(target, TapLoop.waitMs(shortSettle)) ?: svc.reveal(target)
                            val want = TapLoop.wantOf(st.optString("want"))
                            val nowOn = if (id != null && want != null) svc.switchState(id) else null
                            if (id == null) fail = "couldn't find \u201c$target\u201d on screen, even after scrolling through it"
                            else if (!TapLoop.needsTap(want, nowOn)) {
                                // v0.30.7: the switch is already the way the step wants it: done, nothing tapped.
                                labels[i] = labels[i] + " (already ${TapLoop.wantWord(want == true)})"
                            }
                            else {
                                val label = svc.labelOf(id)
                                if (Settings.confirmRisky && risky.containsMatchIn(label) &&
                                    !confirm("Tap \u201c${label.take(40)}\u201d?", "Blaze wants to tap this in ${svc.currentApp() ?: "the app"}.", "Tap", always = true))
                                    return "The user did NOT approve tapping \"$label\" (step ${i + 1}). Don't retry; tell them it's ready for them to finish."
                                svc.flash(id)
                                val r = svc.tap(id, st.optString("do") == "long_press")
                                if (r.startsWith("Couldn't") || r.startsWith("No element")) fail = r
                                else if (want != null) {
                                    // 0.45.19: a switch step toward a wanted state is verified by state / re-read, like the tap tool.
                                    val vr = com.blaze.assistant.phone.ToggleVerify.after(app, svc, label.ifBlank { target }, id, want)
                                    planChecks += com.blaze.assistant.phone.ToggleVerify.marker(vr)
                                    if (vr.status == com.blaze.assistant.phone.StateCheck.Status.CONTRADICTED) fail = com.blaze.assistant.phone.StateCheck.contradicted(vr)
                                }
                                trace.put(JSONObject().put("tool", "tap").put("args", JSONObject().put("text", label)).put("label", label))
                            }
                        }
                        "type" -> {
                            val id = if (target.isNotBlank()) svc.waitFor(target, TapLoop.waitMs(shortSettle, field = true), wantField = true) ?: svc.reveal(target, wantField = true, maxScrolls = 3) ?: svc.anyField() else svc.anyField()
                            if (id == null) fail = "no text field to type into"
                            else {
                                svc.flash(id)
                                val r = svc.type(id, st.optString("text"), st.optBoolean("submit"))
                                if (r.startsWith("Couldn't") || r.startsWith("No element")) fail = r
                                trace.put(JSONObject().put("tool", "type_text").put("args", JSONObject().put("text", st.optString("text")).put("submit", st.optBoolean("submit"))).put("label", svc.labelOf(id)))
                            }
                        }
                        "enter" -> if (!svc.pressEnter()) fail = "couldn't press Enter"
                        "scroll" -> { val r = svc.scroll(st.optString("direction", "down"), null); if (r.startsWith("Couldn't")) fail = r }
                        "back" -> svc.press("back")
                        "home" -> svc.press("home")
                        "wait" -> kotlinx.coroutines.delay((st.optDouble("seconds", 1.0).coerceIn(0.2, 10.0) * 1000).toLong())
                        "search_in_app" -> {
                            val r = com.blaze.assistant.phone.AppSearch.run(app, st.optString("app"), st.optString("query"), st.optBoolean("play"))
                            if (r.startsWith("Error") || r.startsWith("No app")) fail = r
                        }
                        else -> fail = "unknown step \u201c${st.optString("do")}\u201d"
                    }
                    val next = if (i + 1 < n) steps.optJSONObject(i + 1) else null
                    val (mx, q) = TapLoop.settleAfter(st.optString("do"), next)
                    shortSettle = mx < 1000L
                    if (fail == null && st.optString("do") !in setOf("wait", "open_app", "open_settings")) svc.settle(mx, q)
                    val expect = st.optString("expect")
                    if (fail == null && expect.isNotBlank() && svc.waitFor(expect, 2500) == null) fail = "expected \u201c$expect\u201d to appear but it didn't"
                } catch (e: CancellationException) { throw e } catch (e: Exception) { fail = "error: ${e.message}" }
                if (!isPrivate) Traces.log(svc.currentPkg(), st, fail == null, System.currentTimeMillis() - t0, fail ?: "")
                if (fail != null && st.optBoolean("optional")) { state[i] = 4; replace(boardId, Role.STEP, board()); continue }
                if (fail != null) {
                    planRec.fail()
                    state[i] = 3; replace(boardId, Role.STEP, board())
                    lastPlan = FastActions.PlanRun(keys, i, true, svc.currentPkg())
                    return "Stopped at step ${i + 1} (${labels[i]}): $fail. " + FastActions.resumeHint(i + 1, n) + "\n\n" + svc.readScreen()
                }
                state[i] = 2; replace(boardId, Role.STEP, board())
                // v0.30.7 plan memory: the step as it worked (a switch tap remembers which way it ended up).
                planRec.ok(JSONObject(st.toString()).apply {
                    if (st.optString("do") == "tap" && !st.has("want")) svc.lastToggle?.let { put("want", TapLoop.wantWord(it)) } })
                com.blaze.assistant.device.Haptics.tick(app)
            }
            lastPlan = FastActions.PlanRun(keys, n, false, svc.currentPkg())
            val words = svc.visibleWords().filter { it.length in 2..60 }.take(25)
            return "All $n steps done in ${"%.1f".format((System.currentTimeMillis() - started) / 1000.0)} s. Now in ${svc.currentApp() ?: "?"}. On screen: ${words.joinToString(" · ")}\n(Call read_screen only if you need to act further on this screen.)" +
                (if (planChecks.isNotEmpty()) " " + planChecks.last() else "")
        }

        /**
         * After a turn: the chat's title and summary (Titles), then memories and skills (Learning), both in
         * the background. Since v0.8.0 a cheap local check (Learn.decide, no model call) decides whether the
         * learning review is worth a call at all, like Blaze Desktop v0.7: small talk, turns that already
         * saved, and turns with nothing durable no longer cost a request.
         */
        fun learnAfterTurn() {
            if (isPrivate) return   // v0.24.4: nothing is learned from a private chat (no title, memories, skills)
            val reply = msgs.lastOrNull { it.role == Role.BLAZE } ?: return
            if (reply.meta.startsWith("\u26a1")) return
            val lastUser = msgs.indexOfLast { it.role == Role.USER }; if (lastUser < 0) return
            val failed = msgs.drop(lastUser).any { it.role == Role.ERROR }
            val cid = id
            // Titles
            val userTurns = msgs.count { it.role == Role.USER }
            val endsWithAnswer = !failed && msgs.lastOrNull()?.role == Role.BLAZE && reply.text.isNotBlank()
            if (agentId == null && Titles.needs(titleSource, titleTurns, userTurns, msgs[lastUser].text, endsWithAnswer)) {
                val lines = msgs.filter { it.role == Role.USER || it.role == Role.BLAZE }.map { (it.role == Role.USER) to it.text }
                val current = title.ifBlank { msgs[lastUser].text.lineSequence().first().take(48) }
                io.launch {
                    val r = Titles.ask(cid, current, lines) ?: return@launch
                    scope.launch {
                        if (titleSource == Titles.MANUAL) return@launch   // renamed by hand meanwhile
                        title = r.title; titleSource = Titles.AUTO; titleTurns = userTurns
                        if (r.summary.isNotBlank()) summary = r.summary
                        persist()
                    }
                }
            }
            if (agentId == null) TurnContextSource.countTurn()   // v0.45.9: the "new in" line shows on the first few turns only
            // Learning
            if (!Settings.autoLearn || failed) return
            val tools = synchronized(turnTools) { turnTools.toList() }
            val userText = msgs[lastUser].text
            val (n, distinct) = Learn.steps(tools)
            val sig = Learn.signature(userText, tools)
            val repeat = Learn.repeatsAndRecord(sig, n >= 5 && distinct >= 2)
            val savedSkill = tools.any { it == "write_skill" || it == "skill_manage" || it == "save_workflow" }
            // v0.30.5: count every real task (≥ 2 work tool calls); one done often with no skill covering it gets a skill first.
            val usedSkill = tools.any { it == "use_skill" || it == "run_skill" }
            val frequent = if (agentId == null && n >= 2 && !Learn.trivial(userText)) {
                val covered = savedSkill || usedSkill || try { !SkillMatch.match(userText, SkillMatch.cached().filter { it.source == "learned" || it.source == "user" },
                    minScore = SkillMatch.STRONG, fullMin = SkillMatch.STRONG, fullWords = SkillMatch.FULL_WORDS).isEmpty } catch (_: Throwable) { false }
                SkillGuard.Freq.note(sig, userText, covered)
            } else false
            val d00 = Learn.decide(Learn.Turn(userText, reply.text, tools,
                savedMemory = tools.any { it == "remember" || it == "forget" || it == "memory_save" || it == "memory_update" },
                savedSkill = savedSkill), repeat || frequent)
            val d0 = if (frequent && !savedSkill && !d00.skill) d00.copy(reflect = true, skill = true, why = "a task done often has no skill yet") else d00
            // One mind: save as you go. One cheap extraction call per real turn writes or merges memories; the
            // learning review below then only looks for skills.
            // v0.45.9 MemoryGate: never on greetings, small talk, thanks or read-only questions ("doing good thanks!", "anything on for today?").
            if (Mind.ready && agentId == null && userText.trim().split(Regex("\\s+")).size >= 3 && MemoryGate.allows(userText)) {
                val ex = msgs.drop(maxOf(0, lastUser - 4)).filter { it.role == Role.USER || it.role == Role.BLAZE }
                    .joinToString("\n") { (if (it.role == Role.USER) "[user] " else "[assistant] ") + it.text.take(2000) }
                io.launch {
                    val n = try { MindReview.afterTurn(ex) } catch (_: Exception) { 0 }
                    if (n > 0) scope.launch { addMeta(reply.id, "Memory updated"); persist() }
                }
            }
            val d = if (Mind.ready) d0.copy(memory = false) else d0
            if (!d.reflect || (!d.memory && !d.skill)) return
            val exchange = msgs.drop(maxOf(0, lastUser - 4)).joinToString("\n") { m ->
                when (m.role) { Role.USER -> "User: "; Role.BLAZE -> "Blaze: "; Role.STEP -> "  (step) "; Role.ERROR -> "  (error) " } + m.text.take(1500) }
            val t = JSONArray(trace.toString())
            io.launch {
                val chip = Learning.review(exchange, t, d, tools)
                if (chip != null) scope.launch { addMeta(reply.id, chip); persist() }
            }
        }

        /**
         * Ack-and-background: shows [a] as a plain Blaze message when [gate] lets it (first ack of the turn, nothing has
         * answered yet, it doesn't claim done). The model never sees acks; off screen it lands as a text-style notification.
         */
        fun showAck(gate: Ack.Gate, a: String, turnUser: Long, said: String) {
            if (gate !== ackGate || streamId >= 0 || duoLive.isNotEmpty()) return
            val after = msgs.dropWhile { it.id != turnUser }.drop(1)
            if (after.any { it.role == Role.BLAZE || it.role == Role.ERROR }) return
            if (!gate.take(a)) return
            gate.user = turnUser
            val aid = ids.getAndIncrement()
            msgs = msgs + ChatMessage(aid, Role.BLAZE, a.trim(), meta = Ack.META)
            taskChip = Ack.chip(said)
            persist()
            TaskKeeper.started(app, TaskKeeper.Task(id, turnUser, said, System.currentTimeMillis()))
            if (!(this === cur && (inForeground || overlayOpen))) {
                if (isPrivate) Notifier.reply("Blaze got your message in your private chat.", false, id)
                else Notifier.reply(a.trim(), false, id)
            }
        }

        /**
         * Ack-and-background: the end of an acknowledged turn. The last reply becomes "Done ✓ …" only when every action
         * the turn ran was verified (ClaimCheck has already made a failure honest); the task chip goes away.
         */
        private fun finishAck() {
            val gate = ackGate ?: return
            gate.finished = true
            taskChip = ""
            if (!gate.acked) return
            TaskKeeper.finished(app, id, gate.user)
            val done = synchronized(turnDone) { turnDone.toList() }
            val last = done.groupBy { it.name }.mapValues { it.value.last().status }
            val verified = last.count { it.value == ToolVerify.Status.VERIFIED }
            val unproven = last.count { it.value != ToolVerify.Status.VERIFIED }
            val r = msgs.lastOrNull() ?: return
            if (r.role != Role.BLAZE || r.meta == Ack.META) return
            val t = Ack.receipt(r.text, verified, unproven, rewrote = false)
            if (t != r.text) msgs = msgs.map { if (it.id == r.id) it.copy(text = t) else it }
        }

        /** v0.42.0: a Cloud run's reply that arrived after its turn ended (app closed, connection lost). */
        fun cloudReply(text: String) {
            val p = ReplyParts.split(text, final = true)
            val shown = RapidMessages.display(p.text, final = true).trim()
            if (shown.isBlank()) return
            add(Role.BLAZE, shown)
            history.put(JSONObject().put("role", "assistant").put("content", p.text))
            persist()
            announce()
        }

        /** When Blaze finishes while you're in another app, post its answer as a notification. */
        fun announce() {
            if (sessions.values.none { it !== this && it.job?.isActive == true }) Notifier.doneWorking()
            val last = msgs.lastOrNull() ?: return
            if (last.role != Role.BLAZE && last.role != Role.ERROR) return
            // v0.45.0 (#38): the user stopped it: the "Stopped" line is never spoken, notified or put on the widget.
            if (last.text == StopResume.STOPPED_LINE) return
            if (Voice.lastTurnByVoice && Voice.shouldSpeak()) {
                if (streamedVoice) {
                    val rest = if (spokenPrefix.isNotEmpty() && last.text.startsWith(spokenPrefix)) last.text.substring(spokenPrefix.length) else if (spokenPrefix.isEmpty()) last.text else ""
                    Voice.speak(rest.ifBlank { " " }, queue = true)
                } else Voice.speak(last.text)
            }
            if (!isPrivate) try { com.blaze.assistant.widget.BlazeWidget.update(app, last.text.take(240)) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("Agent: BlazeWidget.u", e) }
            if (this === cur && (inForeground || overlayOpen)) return
            // v0.24.4: a private chat's words never land in the notification shade.
            if (isPrivate) { Notifier.reply("Blaze answered in your private chat.", last.role == Role.ERROR, id); return }
            // A chat you left kept working: say which one finished.
            Notifier.reply(if (this === cur) last.text else "${title.ifBlank { "A chat" }}: ${last.text}", last.role == Role.ERROR, id)
        }
    }
}
