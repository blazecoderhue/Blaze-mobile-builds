package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.45.0 (#36 + "What I did") action receipts. Non-technical users can't tell "said it did" from "did it", and Simple
 * mode hides the step card, so every turn keeps a log of what Blaze actually did: each tool call with a short, safe
 * summary of its arguments, its result's first line, its verified status (ToolVerify) and the evidence (what was read
 * back). The log is stored with the reply ([ChatMessage.receipt], JSON) and opens from the reply as "What I did".
 *
 * Under the reply a quiet line sums up the actions people care about ([line]): "✓ Sent to Mum · ✓ Alarm 07:00",
 * "⚠ Couldn't confirm: Wi-Fi on", "✗ Open WhatsApp didn't work". Lookups (search, reading) are in the log but not in
 * the line. Pure Kotlin; ReceiptTest.
 */
object Receipt {
    const val OK = "\u2713"
    const val WARN = "\u26a0"
    const val BAD = "\u2717"

    data class Entry(val tool: String, val args: String, val status: ToolVerify.Status, val result: String, val evidence: String,
                     val at: Long = 0L, val label: String = "",
                     /** v0.45.2: the phone is still checking this action (shown as "Checking…", never as done). */
                     val pending: Boolean = false)

    /** Most entries kept per reply (a 40-step task keeps its last 40). */
    const val MAX_ENTRIES = 40

    /** Tools that only look things up / organise the turn: in the log, never in the line. */
    val LOOKUPS = setOf("find_tool", "load_tools", "web_search", "fetch_page", "browser_read", "read_screen", "look_at_screen", "memory_search",
        "recall_memory", "search_chat", "read_turn", "read_artifact", "search_past_chats", "device_status", "blaze_now", "processes", "list_apps",
        "file_read", "file_list", "file_search", "terminal_status", "gmail_search", "gmail_read", "gcal_events", "calendar_events", "recent_notifications",
        "read_messages", "recent_calls", "find_contact", "get_location", "get_weather", "battery_level", "spotify_now_playing", "blaze_docs_search", "blaze_docs_read",
        "project_docs_search", "project_docs_read", "ask_user", "wait", "viewport", "use_skill", "about_blaze", "check_agent", "list_agents", "goal")

    private val secretKey = Regex("(?i)pass|secret|token|pin|otp|code|card|cvv|cvc|key|auth|cookie|session")

    /** A short, safe summary of the arguments: no secrets, long texts cut, at most 5 keys. */
    fun argsSummary(args: JSONObject): String {
        val parts = ArrayList<String>()
        val it = args.keys()
        while (it.hasNext() && parts.size < 5) {
            val k = it.next()
            val v = args.opt(k)
            val shown = when {
                secretKey.containsMatchIn(k) -> "\u2022\u2022\u2022"
                v is JSONObject || v is JSONArray -> v.toString().let { s -> if (s.length > 40) s.take(37) + "\u2026" else s }
                else -> v.toString().replace('\n', ' ').let { s -> if (s.length > 60) s.take(57) + "\u2026" else s }
            }
            parts += "$k=$shown"
        }
        return parts.joinToString(", ")
    }

    private fun firstLine(r: String) = r.trim().lineSequence().firstOrNull().orEmpty().let { if (it.length > 160) it.take(157) + "\u2026" else it }

    /** What a person calls this action ("Alarm 07:00", "Wi-Fi on", "Opened WhatsApp", "Sent to Mum"). */
    fun label(name: String, args: JSONObject, result: String): String {
        val r = result.trim()
        return when (name) {
            "set_alarm" -> if (args.has("hour")) "Alarm %02d:%02d".format(args.optInt("hour"), args.optInt("minute")) else "Alarm"
            "set_timer" -> "Timer" + (args.optString("seconds").toIntOrNull()?.let { s -> " " + (if (s >= 60) "${s / 60} min" else "$s sec") } ?: "")
            "device_setting", "toggle_setting" -> args.optString("setting").replace('_', ' ').replace("wifi", "Wi-Fi").replaceFirstChar { it.uppercase() }
                .let { n -> args.optString("value").takeIf { it.isNotBlank() }?.let { "$n $it" } ?: n }
            "open_app" -> "Open " + args.optString("app", args.optString("name")).ifBlank { "the app" }
            in ToolVerify.SEND_TOOLS -> if (r.startsWith("Sent", true)) r.substringBefore(':').substringBefore(" (").take(40)
                else "Message to " + args.optString("contact", args.optString("to", args.optString("name"))).ifBlank { "them" }.take(30)
            "call_contact", "call_number", "dial" -> "Call " + args.optString("contact", args.optString("number", args.optString("name"))).take(30)
            "memory_save", "remember", "memory_update" -> "Saved to memory"
            "file_write", "file_edit", "export_file" -> "File " + args.optString("path").substringAfterLast('/').ifBlank { "saved" }.take(30)
            "create_routine" -> "Routine"
            "add_calendar_event", "gcal_create" -> "Calendar event"
            else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }.replace('/', ' ').trim()
    }

    fun entry(name: String, args: JSONObject, result: String, v: ToolVerify.Verdict, at: Long = System.currentTimeMillis()): Entry =
        Entry(name, argsSummary(args), v.status, firstLine(result), v.why.trim(), at, label(name, args, result))

    fun isAction(name: String): Boolean = name !in LOOKUPS

    /** One chip for [e]. */
    fun chip(e: Entry): String {
        val l = e.label.ifBlank { e.tool.replace('_', ' ') }.take(36)
        if (e.pending) return "$WARN Checking: $l"
        return when (e.status) {
            ToolVerify.Status.VERIFIED -> "$OK $l"
            ToolVerify.Status.UNVERIFIED -> "$WARN Couldn't confirm: $l"
            ToolVerify.Status.FAILED -> "$BAD $l didn't work"
        }
    }

    /** The line under the reply: the newest verdict per action, problems first, at most [max] chips ("" = nothing to show). */
    fun line(log: List<Entry>, max: Int = 3): String {
        val newest = LinkedHashMap<String, Entry>()
        for (e in log) if (isAction(e.tool)) { val k = e.tool + "|" + e.label; newest.remove(k); newest[k] = e }
        if (newest.isEmpty()) return ""
        val sorted = newest.values.sortedBy { when (it.status) { ToolVerify.Status.FAILED -> 0; ToolVerify.Status.UNVERIFIED -> 1; else -> 2 } }
        val shown = sorted.take(max).map(::chip)
        val more = newest.size - shown.size
        return (shown + if (more > 0) listOf("+$more more") else emptyList()).joinToString(" \u00b7 ")
    }

    /** True for a meta part that is a receipt chip (always shown, even with technical details hidden). */
    fun isChip(part: String): Boolean = part.trimStart().let { it.startsWith(OK) || it.startsWith(WARN) || it.startsWith(BAD) || Regex("^\\+\\d+ more$").matches(it) }

    fun toJson(log: List<Entry>): String = if (log.isEmpty()) "" else JSONArray().apply {
        log.takeLast(MAX_ENTRIES).forEach { e ->
            put(JSONObject().put("tool", e.tool).put("args", e.args).put("status", e.status.name).put("result", e.result)
                .put("evidence", e.evidence).put("at", e.at).put("label", e.label).apply { if (e.pending) put("pending", true) })
        }
    }.toString()

    fun fromJson(s: String): List<Entry> = if (s.isBlank()) emptyList() else try {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            Entry(o.optString("tool"), o.optString("args"), runCatching { ToolVerify.Status.valueOf(o.optString("status")) }.getOrDefault(ToolVerify.Status.UNVERIFIED),
                o.optString("result"), o.optString("evidence"), o.optLong("at"), o.optString("label"), o.optBoolean("pending", false))
        }
    } catch (_: Exception) { emptyList() }

    /** The "What I did" sheet as plain lines (also what the dashboard and a copy show). */
    fun describe(log: List<Entry>): String = log.joinToString("\n") { e ->
        val mark = if (e.pending) WARN else when (e.status) { ToolVerify.Status.VERIFIED -> OK; ToolVerify.Status.UNVERIFIED -> WARN; ToolVerify.Status.FAILED -> BAD }
        "$mark ${e.label.ifBlank { e.tool }}" + (if (e.args.isNotBlank()) " (${e.tool}: ${e.args})" else " (${e.tool})") +
            "\n   ${e.result}" + (if (e.pending) "\n   Still checking on the phone\u2026" else if (e.evidence.isNotBlank()) "\n   Checked: ${e.evidence}" else "")
    }
}
