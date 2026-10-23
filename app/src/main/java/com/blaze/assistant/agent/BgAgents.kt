package com.blaze.assistant.agent

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Background agents (v1.14, ported from desktop internal/agents + bgagents.go).
 *
 * The main chat hands long work to an agent and keeps chatting. Each agent runs in its own
 * hidden chat (a normal Session, so it gets the same tools, modes and repairs), takes a slot
 * (1 at a time, 2 on phones with 6 GB+ RAM), and waits in a queue otherwise. Its card in the
 * chat shows live progress with Steer and Stop. When it finishes, its result wakes the chat that
 * started it: the main Blaze reads it and tells the user. Agents are saved in filesDir and pick up
 * where they were after Blaze restarts.
 */
object BgAgents {
    data class Bg(
        val id: String, val title: String, val task: String, val parent: String, val chat: String,
        val plan: Boolean = false, val status: String = QUEUED,
        val created: Long = System.currentTimeMillis(), val started: Long = 0, val ended: Long = 0,
        val steps: Int = 0, val last: String = "", val result: String = "", val waiting: Boolean = false,
        val resume: Boolean = false, val followUp: String = "",
        /** v0.26.2: the specialist this agent runs as ("" = a general background agent). */
        val specialist: String = "",
    ) {
        val live get() = status == QUEUED || status == RUNNING
        val seconds get() = (((if (ended > 0) ended else System.currentTimeMillis()) - (if (started > 0) started else created)) / 1000).coerceAtLeast(0)
    }

    const val QUEUED = "queued"; const val RUNNING = "running"; const val DONE = "done"
    const val FAILED = "failed"; const val CANCELLED = "cancelled"
    const val CARD = "agent:"
    const val RESULT_PREFIX = "[Background agent result"

    /** Marks a coroutine as belonging to an agent, so its confirm and question cards say which. */
    class Tag(val id: String) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Tag> }

    val toolNames = setOf("start_agent", "list_agents", "check_agent", "message_agent", "cancel_agent")
    /** Agents don't start or steer other agents. */
    val leadOnly = setOf("start_agent", "message_agent", "cancel_agent", "delegate", "create_specialist")

    private lateinit var app: Context
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.blaze.assistant.device.CrashLog.guard)
    private val jobs = HashMap<String, Job>()
    private val _all = MutableStateFlow<List<Bg>>(emptyList())
    val all: StateFlow<List<Bg>> = _all

    fun init(ctx: Context) {
        app = ctx.applicationContext
        file = File(app.filesDir, "bg_agents.json")
        val list = try {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Bg(it.getString("id"), it.optString("title"), it.optString("task"), it.optString("parent"), it.optString("chat"),
                    it.optBoolean("plan"), it.optString("status", DONE), it.optLong("created"), it.optLong("started"), it.optLong("ended"),
                    it.optInt("steps"), it.optString("last"), it.optString("result"), false, it.optBoolean("resume"), it.optString("follow_up"),
                    it.optString("specialist"))
            }
        } catch (_: Exception) { emptyList() }
        // Blaze was closed mid-run: those go back in the queue and continue where they were.
        _all.value = list.map { if (it.status == RUNNING) it.copy(status = QUEUED, resume = true) else it }
            .sortedBy { it.created }.takeLast(40)
        save()
        scope.launch { delay(4000); pump() }
    }

    private fun ok() = ::file.isInitialized

    @Synchronized private fun save() {
        if (!ok()) return
        try {
            file.writeText(JSONArray(_all.value.map {
                JSONObject().put("id", it.id).put("title", it.title).put("task", it.task).put("parent", it.parent).put("chat", it.chat)
                    .put("plan", it.plan).put("status", it.status).put("created", it.created).put("started", it.started).put("ended", it.ended)
                    .put("steps", it.steps).put("last", it.last).put("result", it.result.take(20000)).put("resume", it.resume).put("follow_up", it.followUp)
                    .apply { if (it.specialist.isNotBlank()) put("specialist", it.specialist) }
            }).toString())
        } catch (_: Exception) { }
    }

    @Synchronized private fun update(id: String, persist: Boolean = true, f: (Bg) -> Bg) {
        _all.value = _all.value.map { if (it.id == id) f(it) else it }
        if (persist) save()
    }

    /** Screenshot tests (ShotsTest) only: show these agents without saving anything. */
    fun preview(list: List<Bg>) { _all.value = list }

    fun get(id: String): Bg? = _all.value.firstOrNull { it.id.equals(id.trim(), true) }

    /** How many agents may run at once: 1, or 2 on phones with 6 GB of RAM or more. */
    fun cap(): Int {
        val mi = ActivityManager.MemoryInfo()
        return try {
            app.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
            // totalMem reports a little under the marketing size (a "6 GB" phone shows ~5.5 GB).
            if (mi.totalMem >= 5_200L * 1024 * 1024) 2 else 1
        } catch (_: Exception) { 1 }
    }

    /** The specialist an agent runs as (null = a general agent). */
    fun spec(bg: Bg): Specialists.Spec? = bg.specialist.takeIf { it.isNotBlank() }?.let { try { Specialists.byId(it) } catch (_: Throwable) { null } }

    /** v0.26.2: what kind of slot it needs (Dispatch): network specialists run up to 3 at once, phone ones take turns. */
    fun kind(bg: Bg): Dispatch.Kind = if (bg.specialist.isBlank()) Dispatch.Kind.GENERAL else Dispatch.kind(spec(bg)?.groups ?: setOf("web"))

    /** The card's role label: "AGENT", or the specialist's name ("SHOPPER"). */
    fun cardRole(bg: Bg): String = if (bg.specialist.isBlank()) "AGENT" else (spec(bg)?.name ?: bg.specialist).uppercase()

    /** Queue a new agent. Returns it; it starts as soon as a slot is free. [specialist] = run it as that specialist. */
    fun start(task: String, title: String, parent: String, specialist: String = ""): Bg {
        val id = Processes.nextId("agent")
        val bg = Bg(id, title.ifBlank { task.lineSequence().first().take(40) }.take(60), task, parent,
            "bg-" + java.util.UUID.randomUUID().toString().take(10), plan = Modes.plan, specialist = specialist)
        _all.value = (_all.value + bg).takeLast(40)
        save()
        scope.launch { pump() }
        return bg
    }

    @Synchronized private fun claimNext(): Bg? {
        val running = _all.value.filter { it.status == RUNNING }.map { kind(it) }
        val queued = _all.value.filter { it.status == QUEUED }
        val i = Dispatch.pick(queued.map { kind(it) }, running, cap())
        if (i < 0) return null
        val next = queued[i]
        _all.value = _all.value.map { if (it.id == next.id) it.copy(status = RUNNING, started = if (it.started > 0) it.started else System.currentTimeMillis()) else it }
        save()
        return get(next.id)
    }

    private fun pump() {
        while (true) {
            val bg = claimNext() ?: return
            jobs[bg.id] = scope.launch { runOne(bg) }
        }
    }

    private fun prompt(bg: Bg) = if (bg.specialist.isNotBlank()) "[Brief from the main Blaze for you, ${spec(bg)?.name ?: bg.specialist} ${bg.id}]\n${bg.task}"
        else "[Background agent ${bg.id} \u00ab${bg.title}\u00bb. You work on your own in the background while the user keeps chatting with the main Blaze; " +
        "nobody watches this chat. Don't ask questions unless you're truly blocked (ask_user shows the user a card). Use background tools; touch the phone screen only if the task can't be done any other way. " +
        "When finished, reply with the result only: what you did or found, short but complete, with any links or file names.]\n${bg.task}"

    private suspend fun runOne(start: Bg) {
        var bg = start
        val s = Agent.agentSession(bg)
        val text = when {
            bg.followUp.isNotBlank() -> "[Follow-up from the user]\n${bg.followUp}"
            bg.resume -> "[Blaze restarted while you were working. Continue the task from where you left off; check what's already done first, don't redo it.]"
            else -> prompt(bg)
        }
        update(bg.id) { it.copy(resume = false, waiting = false, followUp = "") }
        // v0.26.2: a custom specialist without a playbook gets one written (validated) before its first run.
        spec(bg)?.let { sp -> if (sp.custom && sp.playbook.isBlank()) { update(bg.id, persist = false) { it.copy(last = "Writing its field playbook") }; Agent.ensurePlaybook(sp) } }
        suspend fun runAndWait(t: String) {
            withContext(Dispatchers.Main) { s.send(t) }
            // Live progress for the card, without writing the file every second.
            while (s.job?.isActive == true) {
                val steps = s.msgs.count { it.role == Role.STEP }
                val last = s.msgs.lastOrNull { it.role == Role.STEP }?.text.orEmpty()
                update(bg.id, persist = false) { it.copy(steps = steps, last = last.take(120)) }
                delay(1000)
            }
        }
        runAndWait(text)
        // v0.26.2 self-check: one review of the draft against the specialist's playbook; redo once if it fails.
        spec(bg)?.let { sp ->
            val draft = s.msgs.lastOrNull { it.role == Role.BLAZE || it.role == Role.ERROR }
            if (get(bg.id)?.status == RUNNING && draft != null && draft.role == Role.BLAZE) {
                val pb = Playbooks.forSpec(Specialists.byId(sp.id) ?: sp)
                if (pb.isNotBlank()) {
                    update(bg.id, persist = false) { it.copy(last = "Checking its work against the ${sp.name} playbook") }
                    val r = Agent.reviewDraft(sp, pb, bg.task, draft.text)
                    // v0.45.2: a review that didn't finish is said plainly on the card, not counted as a pass.
                    if (!r.checked) update(bg.id, persist = false) { it.copy(last = VerifyPolicy.NOT_REVIEWED) }
                    if (r.checked && !r.pass && get(bg.id)?.status == RUNNING) {
                        update(bg.id, persist = false) { it.copy(last = "Fixing: ${r.fixes.firstOrNull().orEmpty().take(80)}") }
                        runAndWait(Playbooks.redoPrompt(r.fixes))
                    }
                }
            }
        }
        Dispatch.browser.release(bg.id); Dispatch.phone.release(bg.id)
        bg = get(bg.id) ?: return
        jobs.remove(bg.id)
        if (bg.status == CANCELLED) { save(); pump(); return }
        val lastMsg = s.msgs.lastOrNull { it.role == Role.BLAZE || it.role == Role.ERROR }
        val failed = lastMsg == null || lastMsg.role == Role.ERROR
        val result = lastMsg?.text.orEmpty().ifBlank { "(no result)" }
        update(bg.id) { it.copy(status = if (failed) FAILED else DONE, ended = System.currentTimeMillis(), result = result,
            steps = s.msgs.count { m -> m.role == Role.STEP }, waiting = false) }
        get(bg.id)?.let { Agent.agentFinished(it) }
        pump()
    }

    /** Add an instruction while it works, or a follow-up once it's done (it runs again). */
    fun steer(id: String, text: String): String {
        val bg = get(id) ?: return "No agent $id. Call list_agents."
        if (text.isBlank()) return "Say what to tell it."
        return when (bg.status) {
            RUNNING -> { Agent.agentSession(bg).steer.add(text.trim()); "Told ${bg.id}: it picks this up at its next step." }
            QUEUED -> { update(id) { it.copy(task = it.task + "\n\nAlso: " + text.trim()) }; "${bg.id} hasn't started yet; added that to its task." }
            else -> {
                // Follow-up: the agent's chat continues with the new instruction when a slot is free.
                update(id) { it.copy(status = QUEUED, ended = 0, result = "", resume = false, followUp = text.trim()) }
                scope.launch { pump() }
                "${bg.id} is on that follow-up."
            }
        }
    }

    fun cancel(id: String): String {
        val bg = get(id) ?: return "No agent $id."
        if (!bg.live) return "${bg.id} isn't running."
        update(id) { it.copy(status = CANCELLED, ended = System.currentTimeMillis(), waiting = false) }
        Dispatch.browser.release(bg.id); Dispatch.phone.release(bg.id)
        if (bg.status == RUNNING) Agent.stopAgent(bg)
        jobs.remove(id)?.cancel()
        scope.launch { pump() }
        return "Stopped ${bg.id}."
    }

    /** Forget a finished agent (its chat stays in the chat list). */
    fun discard(id: String) { _all.value = _all.value.filterNot { it.id == id && !it.live }; save() }

    fun setWaiting(id: String, w: Boolean) = update(id, persist = false) { it.copy(waiting = w) }

    /** For the main chat's prompt: when to hand work off, and what's running. */
    fun leadLine(): String {
        if (!Settings.bool("bg_agents", true)) return ""
        val live = _all.value.filter { it.live }
        return "\nBACKGROUND AGENTS: for long or slow work (deep research, multi-step jobs, anything over about a minute) call start_agent and keep chatting; the result comes back to you on its own. Quick things you do yourself. " +
            "Specialist work goes out with delegate (it doesn't block either; results land one by one)." +
            (if (live.isNotEmpty()) " Working now: " + live.joinToString("; ") { "${it.id} ${it.title} (${it.status})" } + "." else "")
    }

    fun runningCount() = _all.value.count { it.status == RUNNING }

    /** What the main Blaze reads when an agent finishes (desktop ResultMessage). */
    fun resultMessage(bg: Bg): String = if (bg.specialist.isNotBlank())
        Dispatch.resultMessage(bg.id, spec(bg)?.name ?: bg.specialist, bg.title, bg.status, bg.steps, bg.seconds, bg.result,
            _all.value.count { it.live && it.parent == bg.parent && it.id != bg.id })
    else buildString {
        append("$RESULT_PREFIX · ${bg.id} · ${bg.status} · ${bg.steps} step(s) · ${bg.seconds}s]\n")
        append("This is a platform message, not the user. Your background agent finished. Tell the user what it found or did, in your own words and briefly; don't paste it whole.\n")
        append("<agent_result id=\"${bg.id}\" title=\"${bg.title}\" status=\"${bg.status}\">\n")
        append(if (bg.status == DONE) bg.result.take(8000) else "The agent could not finish: " + bg.result.take(3000))
        append("\n</agent_result>")
        val others = _all.value.count { it.live && it.parent == bg.parent }
        if (others > 0) append("\n$others other agent(s) from this chat are still working. End your turn with at most one short progress line; summarise when the last one is in.")
    }

    fun describe(bg: Bg): String = "${bg.id} \u00ab${bg.title}\u00bb: ${bg.status}${if (bg.waiting) " (waiting for the user's tap)" else ""}, ${bg.steps} steps, ${bg.seconds}s" +
        (if (bg.last.isNotBlank() && bg.live) ". Now: ${bg.last}" else "")

    // ---------- tools ----------

    fun tools(): List<JSONObject> {
        fun fn(name: String, d: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", name).put("description", d)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
        fun s(d: String) = JSONObject().put("type", "string").put("description", d)
        return listOf(
            fn("start_agent", "Hand long or slow work (research, a multi-step job, anything over ~1 minute) to a background agent and keep chatting with the user. " +
                "Give a complete, self-contained task: it doesn't see this chat. Its result comes back to you automatically when it's done; don't wait for it or poll. " +
                "One or two run at a time; more wait in a queue.",
                JSONObject().put("task", s("Everything the agent needs to know and do")).put("title", s("2-5 word name for its card")), listOf("task")),
            fn("list_agents", "List background agents and what each is doing.", JSONObject(), emptyList()),
            fn("check_agent", "Status, current step and (when done) the full result of one background agent.", JSONObject().put("id", s("e.g. B2")), listOf("id")),
            fn("message_agent", "Steer a background agent: add an instruction while it works, or give a follow-up after it finished.", JSONObject().put("id", s("e.g. B2")).put("text", s("What to tell it")), listOf("id", "text")),
            fn("cancel_agent", "Stop a background agent.", JSONObject().put("id", s("e.g. B2")), listOf("id")),
        )
    }

    fun run(name: String, a: JSONObject, parent: String): String = when (name) {
        "list_agents" -> _all.value.filter { it.live || it.ended > System.currentTimeMillis() - 6 * 3600_000 }.takeLast(12)
            .joinToString("\n") { describe(it) }.ifBlank { "No background agents." }
        "check_agent" -> get(a.optString("id"))?.let { describe(it) + (if (it.result.isNotBlank()) "\nResult:\n" + it.result.take(8000) else "") } ?: "No agent ${a.optString("id")}."
        "message_agent" -> steer(a.optString("id"), a.optString("text"))
        "cancel_agent" -> cancel(a.optString("id"))
        else -> "Unknown agent tool."
    }
}
