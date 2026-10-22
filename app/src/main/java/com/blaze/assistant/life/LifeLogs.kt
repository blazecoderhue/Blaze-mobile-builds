package com.blaze.assistant.life

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Mind
import com.blaze.assistant.agent.MindLogs
import com.blaze.assistant.agent.MindReview
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Life logs (v0.19.0): "start logs" / "live logs" / /logs / Settings › Life logs turn on a foreground service that
 * transcribes what the mic hears, on the phone and for free (Vosk's small English model, the same one the wake word
 * uses, downloaded once; Android's SpeechRecognizer when it isn't there). Only text is kept, never audio: chunks in the
 * journal (kind "life_log"), which the idle review turns into memories marked "(inferred)".
 *
 * Always visible: a persistent notification with Pause and Stop, plus Android's mic indicator. Pauses itself during
 * phone calls, while another app records, while Blaze's own voice is listening, and below 15% battery unless charging.
 * "Brainstorm with me" is the same with a summary (ideas, decisions, next steps) in the chat and memory on stop.
 */
object LifeLogs {
    const val TOOL = "life_logs"
    const val LOW_BATTERY = 15
    private const val PREF_ACK = "life_logs_ack"
    private const val PREF_DAY = "life_logs_day"
    private const val PREF_MS = "life_logs_ms"

    data class State(val on: Boolean = false, val paused: String = "", val brainstorm: Boolean = false, val engine: String = "",
                     val since: Long = 0, val last: String = "", val downloading: Int = -1, val session: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    val active: Boolean get() = _state.value.on

    internal fun set(s: State) { _state.value = s }
    internal fun update(f: (State) -> State) { _state.value = f(_state.value) }

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    /** v0.33.0 Life Log 2: record the voiced moments and transcribe + review after Stop (default), or the old live text-only mode. */
    var recordMode: Boolean
        get() = try { Settings.bool("life_logs_record", true) } catch (_: Throwable) { true }
        set(v) = Settings.setBool("life_logs_record", v)

    /** Reviews the newest recorded session again (one that failed or was kept). */
    fun reviewLast(ctx: Context): String {
        val d = LifeAudio.sessions(ctx).firstOrNull { LifeAudio.state(it) in setOf("failed", "done", "queued") } ?: return "There's no recorded life log waiting to be reviewed."
        LifeAudio.setState(d, "queued"); LifeLogWorker.enqueue(ctx, d.name)
        return "Reviewing your last life log again. Blaze and Halo will post what they find."
    }

    var acknowledged: Boolean
        get() = try { Settings.bool(PREF_ACK, false) } catch (_: Throwable) { false }
        set(v) = Settings.setBool(PREF_ACK, v)

    const val EXPLAINER = "Life logs record what your phone's mic hears, including people near you, then transcribe it on the phone; Blaze and Halo turn it into memories and the audio is deleted after (unless you keep recordings). " +
        "Turn it off around anyone who hasn't agreed to be recorded. It stays visible in your notifications " +
        "with Pause and Stop, pauses during calls and on low battery, and you can delete the logs any time in Settings \u203a Life logs."

    // ---------------- time logged ----------------

    fun day(now: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    /** Listening time logged today (ms), from prefs. */
    fun todayMs(): Long = try { if (Settings.str(PREF_DAY) == day()) Settings.str(PREF_MS).toLongOrNull() ?: 0L else 0L } catch (_: Throwable) { 0L }

    internal fun addMs(ms: Long) {
        if (ms <= 0) return
        val today = day()
        val base = if (Settings.str(PREF_DAY) == today) Settings.str(PREF_MS).toLongOrNull() ?: 0L else 0L
        Settings.setStr(PREF_DAY, today); Settings.setStr(PREF_MS, (base + ms).toString())
    }

    fun hours(ms: Long): String = if (ms < 60_000) "${ms / 1000}s" else if (ms < 3_600_000) "${ms / 60_000} min" else "%.1f h".format(ms / 3_600_000.0)

    // ---------------- control ----------------

    fun micAllowed(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts (after the one-time explainer card when [ask] and it was never accepted). Returns what to tell the user.
     * The Settings page shows its own explainer dialog and calls with ask=false.
     */
    suspend fun requestStart(ctx: Context, brainstorm: Boolean, ask: Boolean = true): String {
        if (!micAllowed(ctx)) return "Life logs need the microphone permission. Open Blaze \u203a Settings \u203a Voice to allow the mic, then try again."
        if (!acknowledged) {
            if (!ask) return "Not started: the explainer wasn't accepted."
            if (!Agent.confirm(if (brainstorm) "Start a brainstorm log?" else "Start life logs?", EXPLAINER, "Start", always = true))
                return "Not started. Life logs stay off."
            acknowledged = true
        }
        return start(ctx, brainstorm)
    }

    fun start(ctx: Context, brainstorm: Boolean): String {
        if (!micAllowed(ctx)) return "Life logs need the microphone permission first."
        val s = _state.value
        if (s.on && s.brainstorm == brainstorm) return if (s.paused.isNotBlank()) { resume(ctx); "Logs were paused; resumed." } else "Logs are already on."
        return try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LifeLogService::class.java)
                .setAction(LifeLogService.ACT_START).putExtra(LifeLogService.EXTRA_BRAINSTORM, brainstorm))
            if (brainstorm) "Brainstorm log on. Think out loud; say \u201cstop brainstorm\u201d (or tap Stop) and I'll write up the ideas, decisions and next steps."
            else if (recordMode) "Life logs on. I'm recording the moments people speak; when you stop, I transcribe it, tell the voices apart, and Blaze and Halo turn it into memories."
            else "Life logs on. I'm transcribing what the mic hears (text only). Say \u201cstop logs\u201d or tap Stop in the notification."
        } catch (e: Exception) { "Couldn't start life logs: ${e.message}. Open Blaze and try again from the app." }
    }

    fun stop(ctx: Context): String {
        if (!_state.value.on) return "Life logs are off."
        send(ctx, LifeLogService.ACT_STOP)
        return if (_state.value.brainstorm) "Stopping the brainstorm; writing up the summary." else "Life logs off."
    }

    fun pause(ctx: Context): String { if (!_state.value.on) return "Life logs are off."; send(ctx, LifeLogService.ACT_PAUSE); return "Life logs paused." }
    fun resume(ctx: Context): String { if (!_state.value.on) return "Life logs are off."; send(ctx, LifeLogService.ACT_RESUME); return "Life logs resumed." }

    private fun send(ctx: Context, action: String) {
        try { ctx.startService(Intent(ctx, LifeLogService::class.java).setAction(action)) } catch (_: Exception) { }
    }

    /** From the UI and /logs (not suspend): toggles, with the explainer card when needed. */
    fun toggleFromCommand(ctx: Context, arg: String) {
        val a = arg.trim().lowercase()
        scope.launch {
            val msg = when {
                a in setOf("stop", "off", "end") -> stop(ctx)
                a == "pause" -> pause(ctx)
                a == "resume" -> resume(ctx)
                a == "status" -> status()
                a.startsWith("brain") -> requestStart(ctx, true)
                a in setOf("start", "on", "live") -> requestStart(ctx, false)
                active -> stop(ctx)
                else -> requestStart(ctx, false)
            }
            Agent.note(msg, asReply = true)
        }
    }

    fun status(): String {
        val s = _state.value
        val n = try { Mind.db()?.let { MindLogs.count(it, MindLogs.LIFE) } ?: 0 } catch (_: Exception) { 0 }
        val head = when {
            !s.on -> "Life logs are off."
            s.paused.isNotBlank() -> "Life logs are paused (${s.paused})."
            else -> "Life logs are on${if (s.brainstorm) " (brainstorm)" else ""}, using ${s.engine.ifBlank { "the on-device recogniser" }}."
        }
        return "$head ${hours(todayMs())} logged today; $n log chunks saved."
    }

    // ---------------- brainstorm summary ----------------

    const val SUMMARY_PROMPT = "Below is a brainstorm the user spoke out loud, transcribed by their phone (words may be wrong; other voices may be in it). " +
        "Write it up. Answer ONLY JSON: {\"title\":\"3-6 words\",\"ideas\":[\"...\"],\"decisions\":[\"...\"],\"next_steps\":[\"...\"]}. " +
        "Keep the user's own words where you can; only list decisions they actually made; [] when there are none."

    data class Summary(val title: String, val ideas: List<String>, val decisions: List<String>, val next: List<String>)

    fun parseSummary(raw: String): Summary? {
        val s = raw.substringAfterLast("</think>")
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val o = try { JSONObject(s.substring(a, b + 1)) } catch (_: Exception) { return null }
        fun list(k: String): List<String> = o.optJSONArray(k)?.let { arr -> (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }.take(12) } ?: emptyList()
        val sm = Summary(o.optString("title").trim().ifBlank { "Brainstorm" }.take(60), list("ideas"), list("decisions"), list("next_steps"))
        return if (sm.ideas.isEmpty() && sm.decisions.isEmpty() && sm.next.isEmpty()) null else sm
    }

    fun format(sm: Summary, minutes: Long): String = buildString {
        append("**Brainstorm: ${sm.title}** (${minutes.coerceAtLeast(1)} min)\n")
        if (sm.ideas.isNotEmpty()) { append("\n**Ideas**\n"); sm.ideas.forEach { append("- $it\n") } }
        if (sm.decisions.isNotEmpty()) { append("\n**Decisions**\n"); sm.decisions.forEach { append("- $it\n") } }
        if (sm.next.isNotEmpty()) { append("\n**Next steps**\n"); sm.next.forEach { append("- $it\n") } }
    }.trimEnd()

    /** After a brainstorm stops: summary to the chat (and a notification when Blaze isn't open) and to memory. */
    internal fun summarize(conv: String, startedAt: Long) {
        scope.launch(Dispatchers.IO) {
            val d = Mind.db() ?: return@launch
            val turns = try { MindLogs.since(d, conv, 0) } catch (_: Exception) { emptyList() }
            val text = turns.joinToString("\n") { it.content.substringAfter("): ", it.content) }
            val minutes = (System.currentTimeMillis() - startedAt) / 60_000
            val out = if (text.isBlank()) "The brainstorm ended, but I didn't catch any words. Nothing to summarise." else {
                val req = JSONArray().put(JSONObject().put("role", "system").put("content", SUMMARY_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", text.takeLast(Mind.CHUNK_CHARS)))
                val sm = try { MindReview.cheap(req)?.let { parseSummary(it) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                if (sm == null) "The brainstorm is saved (${turns.size} parts), but I couldn't write the summary right now. Ask me \u201csummarise my last brainstorm\u201d later."
                else {
                    val line = (listOf("Ideas: " + sm.ideas.take(5).joinToString("; ")).filter { sm.ideas.isNotEmpty() } +
                        listOf("next: " + sm.next.take(4).joinToString("; ")).filter { sm.next.isNotEmpty() }).joinToString(". ")
                    if (line.isNotBlank()) Mind.save("project", "Brainstorm: ${sm.title}", line)
                    sm.decisions.take(5).forEach { Mind.save("decision", "Brainstorm: ${sm.title}", it) }
                    format(sm, minutes)
                }
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                Agent.note(out, asReply = true)
                if (!Agent.inForeground) try { com.blaze.assistant.notify.Notifier.reply(out, false) } catch (_: Throwable) { }
            }
        }
    }

    // ---------------- tool ----------------

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Life logs: transcribe what the phone's mic hears (text only, on-device, free) into the journal so you learn from it later. " +
            "start for \"start logs\" / \"live logs\"; brainstorm for \"brainstorm with me\" (a summary of ideas, decisions and next steps comes when it stops); " +
            "pause, resume, stop, status; review re-runs the transcription + Blaze/Halo review of the last recording; delete erases every saved log (asks first). The first start shows the user a consent card.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("start", "brainstorm", "pause", "resume", "stop", "status", "delete", "review")))))
            .put("required", JSONArray(listOf("action")))))

    fun describe(args: JSONObject): String = when (args.optString("action")) {
        "start" -> "Starting life logs"; "brainstorm" -> "Starting a brainstorm log"; "stop" -> "Stopping life logs"
        "pause" -> "Pausing life logs"; "resume" -> "Resuming life logs"; "delete" -> "Deleting life logs"; else -> "Checking life logs"
    }

    suspend fun run(ctx: Context, args: JSONObject): String = when (args.optString("action")) {
        "start" -> requestStart(ctx, false)
        "brainstorm" -> requestStart(ctx, true)
        "pause" -> pause(ctx)
        "resume" -> resume(ctx)
        "stop" -> stop(ctx)
        "review" -> reviewLast(ctx)
        "delete" -> if (Agent.confirm("Delete all life logs?", "Every saved transcript is erased. Memories already learned from them stay (delete those in Memory).", "Delete", always = true))
            "Deleted ${deleteAll()} log chunks." else "Kept the logs."
        else -> status()
    }

    fun deleteAll(): Int = try { Mind.db()?.let { MindLogs.deleteKind(it, MindLogs.LIFE) } ?: 0 } catch (_: Exception) { 0 }
}
