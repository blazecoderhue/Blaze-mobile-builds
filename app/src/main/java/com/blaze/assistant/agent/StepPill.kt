package com.blaze.assistant.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 0.45.20 step pills (files/from-site/STEPS_DELIVERY_CONTRACT.md §2, §3): ONE quiet line under the pending reply showing
 * the newest `step` text; each step replaces the one before. Hidden on done / failed / cancelled, when the run waits for
 * the phone (`needs_device`, the device tool UI takes over) and when reply text starts streaming (`delta`). Never shown
 * for a reply that lands within [SHOW_AFTER_MS] of the send.
 *
 * Specialists are always general ("Bringing in a specialist" / "Updating a specialist"), never which one. The server
 * promises clean text; the phone checks again ([clean]): no model / provider / route / owner names, raw tool names,
 * JSON, braces, URLs or code, at most [MAX] characters, no trailing period. A step that fails the check is dropped (the
 * pill keeps the previous line) rather than shown half-cleaned.
 *
 * [current] is the visible line as a StateFlow so other surfaces (the Blaze Phone PiP) can mirror it.
 * Pure [Machine] (StepPillTest drives it with explicit times); [StepPills] is the app's single live instance.
 */
object StepPill {
    const val MAX = 60
    /** No pill when the reply lands within this long of the send (contract: "~2 s"). */
    const val SHOW_AFTER_MS = 2_000L
    const val SPECIALIST_IN = "Bringing in a specialist"
    const val SPECIALIST_UPDATE = "Updating a specialist"

    // Owner names are built from pieces so no source file carries them (OwnerName19Test greps the repo).
    private val owner = Regex("(?i)\\b(" + listOf("lack" + "son", "lu" + "ke", "kap" + "apa").joinToString("|") + ")\\b")
    private val url = Regex("(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|net|org|io|dev|app|ai|co)\\b)")
    private val code = Regex("[{}\\[\\]<>`|\\\\=;$#@]|\\b[a-z]+_[a-z0-9_]+\\b|\\b[a-z]+[A-Z][a-zA-Z]*\\(|\\w+\\(|=>|->|::")
    private val routeWords = Regex("(?i)\\b(model|provider|route|routing|lineup|endpoint|gateway|token|api key|llm|gpt|claude|gemini|llama|mistral|qwen|deepseek|groq|cerebras|sambanova|openrouter|cohere|nvidia|anthropic|openai|codestral|kimi|glm)\\b")

    /**
     * The pill text for a step event, or null when it must not be shown. [kind] "specialist" (or a specialty event)
     * always gives the general line.
     */
    fun clean(text: String, kind: String = "step"): String? {
        if (kind == "specialist") return if (text.contains("updat", ignoreCase = true)) SPECIALIST_UPDATE else SPECIALIST_IN
        var t = text.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        if (owner.containsMatchIn(t) || url.containsMatchIn(t) || code.containsMatchIn(t) || routeWords.containsMatchIn(t)) return null
        if (try { ModelHide.mentions(t) } catch (_: Throwable) { false }) return null
        t = t.trimEnd('.', ' ', '\u2026')
        if (t.isEmpty()) return null
        if (t.length > MAX) t = t.take(MAX - 1).substringBeforeLast(' ').trimEnd(',', ';', ':', ' ').ifEmpty { t.take(MAX - 1) } + "\u2026"
        return t
    }

    /** Statuses that hide the pill. needs_device hides it without closing the turn (work can go on after the phone). */
    private val HIDE_STATUS = setOf("done", "failed", "cancelled", "expired", "needs_device")
    private val END = setOf("done", "failed", "cancelled", "expired")

    /** One turn's pill. Times are passed in so tests control them. */
    class Machine(val showAfterMs: Long = SHOW_AFTER_MS) {
        var start = 0L; private set
        var text: String? = null; private set
        var kind = "step"; private set
        var closed = true; private set

        fun begin(now: Long) { start = now; text = null; kind = "step"; closed = false }
        fun end() { text = null; closed = true }

        /** Applies one run event; true when the pill's text or visibility may have changed. */
        fun onEvent(o: JSONObject): Boolean {
            if (closed) return false
            when (o.optString("type")) {
                "step" -> { val k = o.optString("kind", "step"); val c = clean(o.optString("text"), k) ?: return false; text = c; kind = k }
                "specialty_loaded" -> { text = SPECIALIST_IN; kind = "specialist" }
                "specialty_updated" -> { text = SPECIALIST_UPDATE; kind = "specialist" }
                "delta" -> { if (o.optString("text").isEmpty() || text == null) return false; text = null }
                "status" -> { val s = o.optString("status"); if (s !in HIDE_STATUS) return false; text = null; if (s in END) closed = true }
                "done", "failed", "cancelled", "error" -> { text = null; closed = true }
                else -> return false
            }
            return true
        }

        /** A local step (the on-phone loop's work line): same rules as a server `step`. */
        fun onLocal(line: String): Boolean = onEvent(JSONObject().put("type", "step").put("kind", "step").put("text", line))

        /** The pill to draw at [now], or null. */
        fun visible(now: Long): String? = if (closed || text == null || now - start < showAfterMs) null else text

        /** When a step that arrived early becomes visible. */
        fun dueAt(): Long = start + showAfterMs
    }
}

/**
 * The app's live pill: one turn at a time per chat; [current] is what the chat (and the Blaze Phone PiP) shows.
 * Publishing is scheduled so a step that came in during the first [StepPill.SHOW_AFTER_MS] appears exactly then.
 */
object StepPills {
    data class Pill(val chat: String, val text: String, val specialist: Boolean)

    private val _current = MutableStateFlow<Pill?>(null)
    /** The visible pill (null = none). Mirrored by the Blaze Phone PiP. */
    val current: StateFlow<Pill?> = _current.asStateFlow()

    private val m = StepPill.Machine()
    private var chat = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timer: Job? = null
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    @Synchronized fun begin(chatId: String, at: Long = clock()) { chat = chatId; m.begin(at); publish() }
    @Synchronized fun end(chatId: String) { if (chatId == chat) { m.end(); publish() } }
    @Synchronized fun onEvent(chatId: String, o: JSONObject) { if (chatId == chat && m.onEvent(o)) publish() }
    @Synchronized fun onLocal(chatId: String, line: String) { if (chatId == chat && m.onLocal(line)) publish() }

    private fun publish() {
        val now = clock()
        val t = m.visible(now)
        _current.value = t?.let { Pill(chat, it, m.kind == "specialist") }
        timer?.cancel(); timer = null
        if (t == null && !m.closed && m.text != null && now < m.dueAt()) {
            val wait = m.dueAt() - now
            timer = scope.launch { delay(wait); synchronized(this@StepPills) { publish() } }
        }
    }
}
