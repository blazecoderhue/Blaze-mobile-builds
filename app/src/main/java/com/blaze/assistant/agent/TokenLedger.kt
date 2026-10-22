package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.33.1 token ledger per feature (idea #33). [Usage] knows how many tokens a day cost; this knows WHERE they went.
 * Every model call's request is split into the parts that make it up (system prompt, each tool group's schemas, your
 * messages, Blaze's replies and tool calls, each tool's results, screen readouts, images) and added to a rolling
 * 7-day ledger. "Where do my tokens go?" (blaze_self token_ledger) lists the top offenders with their share and a
 * concrete fix for each, so the big costs are found by measurement, not guesswork. Nothing is changed automatically.
 *
 * Sizes are characters ÷ 4 (BlazeDb.tokens' rule), images a flat 800. Pure parts tested in TokenLedgerTest.
 */
object TokenLedger {
    const val IMAGE_TOKENS = 800
    const val DAYS = 7

    private fun tok(chars: Int) = (chars + 3) / 4

    private fun toolName(t: Any?) = (t as? JSONObject)?.optJSONObject("function")?.optString("name").orEmpty()

    private val screenCue = Regex("\nScreen now:\n|Screen now:\n|\\). Elements, use the \\[number\\]:|Same screen in ")

    /** One request's parts in tokens. [groupOf] maps a tool name to its group (null = core). */
    fun breakdown(req: JSONArray, tools: JSONArray?, groupOf: (String) -> String? = { null }): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        fun add(k: String, n: Int) { if (n > 0) out[k] = (out[k] ?: 0) + n }
        for (i in 0 until req.length()) {
            val m = req.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val c = m.opt("content")
            var chars = 0; var images = 0
            when (c) {
                is String -> chars = c.length
                is JSONArray -> for (j in 0 until c.length()) {
                    val p = c.optJSONObject(j) ?: continue
                    if (p.optString("type") == "image_url") images++ else chars += p.optString("text").length
                }
            }
            add("images", images * IMAGE_TOKENS)
            when (role) {
                "system" -> add("system prompt", tok(chars))
                "user" -> add(if (c is String && c.startsWith("[")) "context notes" else "your messages", tok(chars))
                "assistant" -> {
                    add("Blaze replies", tok(chars))
                    m.optJSONArray("tool_calls")?.let { add("tool calls", tok(it.toString().length)) }
                }
                "tool" -> {
                    val s = c as? String ?: ""
                    add(if (screenCue.containsMatchIn(s)) "screen readouts" else "results: " + m.optString("name").ifBlank { "tool" }, tok(chars))
                }
                else -> add("other", tok(chars))
            }
        }
        if (tools != null) for (i in 0 until tools.length()) {
            val t = tools.opt(i)
            add("schemas: " + (groupOf(toolName(t)) ?: "core"), tok(t.toString().length))
        }
        return out
    }

    /** Adds one call's parts into a ledger JSON {day: {part: tokens, "_calls": n}}, keeping [DAYS] days. Pure. */
    fun fold(ledger: JSONObject, day: String, parts: Map<String, Int>): JSONObject {
        val d = ledger.optJSONObject(day) ?: JSONObject().also { ledger.put(day, it) }
        parts.forEach { (k, v) -> d.put(k, d.optLong(k) + v) }
        d.put("_calls", d.optLong("_calls") + 1)
        ledger.keys().asSequence().toList().sortedDescending().drop(DAYS).forEach { ledger.remove(it) }
        return ledger
    }

    data class Line(val part: String, val tokens: Long, val share: Double, val perCall: Long)

    /** The ledger's totals over its days, biggest first. */
    fun top(ledger: JSONObject): Pair<Long, List<Line>> {
        val sum = HashMap<String, Long>(); var calls = 0L
        for (day in ledger.keys()) {
            val d = ledger.optJSONObject(day) ?: continue
            for (k in d.keys()) if (k == "_calls") calls += d.optLong(k) else sum[k] = (sum[k] ?: 0L) + d.optLong(k)
        }
        val total = sum.values.sum().coerceAtLeast(1)
        return calls to sum.entries.sortedByDescending { it.value }.map { Line(it.key, it.value, it.value.toDouble() / total, if (calls > 0) it.value / calls else 0) }
    }

    /** The one concrete fix for a big part. */
    fun tip(part: String): String = when {
        part == "system prompt" -> "trim always-on rules; turn off Visual answers or unused specialists if you don't use them"
        part.startsWith("schemas: ") -> "this tool group rides along often; the learned router and the tool diet keep it off when not needed"
        part == "screen readouts" -> "phone tasks: run_plan batches taps so the screen is read once"
        part == "results: fetch_page" -> "long pages; ask for a summary or one section instead of whole pages"
        part.startsWith("results: ") -> "large tool results; Long chats saves big ones aside (keep it on)"
        part == "your messages" || part == "Blaze replies" -> "long chat history; start a new chat for a new topic, or let Long chats fold old turns"
        part == "images" -> "screenshots and photos cost ~800 tokens each; Low-data mode drops screenshots"
        part == "context notes" -> "orientation / heads-up notes; normal, shrinks with fewer pending notices"
        else -> ""
    }

    /** "Where do my tokens go" text. */
    fun report(ledger: JSONObject, cacheHits: Int, n: Int = 8): String {
        val (calls, lines) = top(ledger)
        if (calls == 0L) return "No model calls measured yet. Ask me something and check again."
        val total = lines.sumOf { it.tokens }
        val sb = StringBuilder("Last ${ledger.length()} day(s): $calls model calls, about ${total / 1000}k input tokens (≈ ${total / calls} per call).\nBiggest parts:\n")
        lines.take(n).forEachIndexed { i, l ->
            sb.append("${i + 1}. ${l.part}: ${"%.0f".format(l.share * 100)}% (≈ ${l.perCall} per call)")
            tip(l.part).takeIf { it.isNotEmpty() && l.share >= 0.08 }?.let { sb.append(" → ").append(it) }
            sb.append('\n')
        }
        if (cacheHits > 0) sb.append("Tool cache answered $cacheHits lookups without the network since start.\n")
        return sb.toString().trimEnd()
    }

    // ---------------- Android side ----------------

    @Volatile private var hits = 0
    fun cacheHit() { hits++ }

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    @Synchronized fun record(req: JSONArray, tools: JSONArray?) {
        if (!(try { Settings.bool("token_ledger", true) } catch (_: Throwable) { true })) return
        try {
            val parts = breakdown(req, tools) { ToolRouter.groupOf(it) }
            val l = try { JSONObject(Settings.str("token_ledger_v1", "{}")) } catch (_: Exception) { JSONObject() }
            Settings.setStr("token_ledger_v1", fold(l, today(), parts).toString())
        } catch (_: Exception) { }
    }

    fun report(): String = try { report(JSONObject(Settings.str("token_ledger_v1", "{}")), hits) +
        sectionReport(try { JSONObject(Settings.str("token_ledger_sys_v1", "{}")) } catch (_: Exception) { JSONObject() }) } catch (_: Exception) { "No token ledger yet." }

    /** v0.45.1 (#16): the system prompt by part (PromptParts names), so its biggest blocks are found by measurement. */
    @Synchronized fun recordSections(parts: List<PromptParts.Part>) {
        if (parts.isEmpty()) return
        if (!(try { Settings.bool("token_ledger", true) } catch (_: Throwable) { true })) return
        try {
            val l = try { JSONObject(Settings.str("token_ledger_sys_v1", "{}")) } catch (_: Exception) { JSONObject() }
            Settings.setStr("token_ledger_sys_v1", fold(l, today(), sectionTokens(parts)).toString())
        } catch (_: Exception) { }
    }

    /** Tokens per prompt part, "live: " prefixed for the per-call tail. Pure. */
    fun sectionTokens(parts: List<PromptParts.Part>): Map<String, Int> = LinkedHashMap<String, Int>().apply {
        parts.forEach { p -> val k = (if (p.tier == PromptParts.Tier.LIVE) "live: " else "") + p.name; put(k, (get(k) ?: 0) + tok(p.text.length)) }
    }

    /** "System prompt by part (per call): rich 2.3k, core_skills 2.0k, …" or "". Pure. */
    fun sectionReport(ledger: JSONObject, n: Int = 8): String {
        val (calls, lines) = top(ledger)
        if (calls == 0L) return ""
        return "\nSystem prompt by part (tokens per call): " + lines.take(n).joinToString(", ") { "${it.part} ${it.perCall}" }
    }

    fun clear() = try { Settings.setStr("token_ledger_v1", "{}") } catch (_: Exception) { }
}
