package com.blaze.assistant.agent

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Counts tokens per day and estimates cost where the price is known. */
object Usage {
    data class Totals(val input: Long, val output: Long, val cost: Double, val priced: Boolean, val calls: Int)

    private lateinit var prefs: SharedPreferences
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private val day get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun init(ctx: Context) { prefs = ctx.getSharedPreferences("blaze", Context.MODE_PRIVATE) }

    /** USD per million tokens (input, output), matched by model-name substring. Free tiers count as 0. */
    private fun price(provider: String, model: String): Pair<Double, Double>? {
        val m = model.lowercase()
        if (m.endsWith(":free")) return 0.0 to 0.0
        val table = listOf(
            "gpt-4o-mini" to (0.15 to 0.6), "gpt-4.1-nano" to (0.1 to 0.4), "gpt-4.1-mini" to (0.4 to 1.6),
            "gpt-4.1" to (2.0 to 8.0), "gpt-4o" to (2.5 to 10.0), "gpt-5-nano" to (0.05 to 0.4), "gpt-5-mini" to (0.25 to 2.0),
            "gpt-5" to (1.25 to 10.0), "o4-mini" to (1.1 to 4.4), "claude-3-5-haiku" to (0.8 to 4.0), "haiku" to (1.0 to 5.0),
            "sonnet" to (3.0 to 15.0), "opus" to (15.0 to 75.0), "deepseek-chat" to (0.27 to 1.1),
            "deepseek-reasoner" to (0.55 to 2.19), "mistral-small" to (0.1 to 0.3), "mistral-large" to (2.0 to 6.0)
        )
        return table.firstOrNull { m.contains(it.first) }?.second
    }

    /**
     * Record one model call. Uses the provider's own counts when it sent them, otherwise an
     * estimate (about 4 characters per token, ~1,000 per image).
     */
    fun record(request: JSONArray, reply: JSONObject, usage: JSONObject?) {
        val model = Settings.model; val provider = Settings.providerId
        var inTok = usage?.optLong("prompt_tokens", -1) ?: -1
        var outTok = usage?.optLong("completion_tokens", -1) ?: -1
        if (inTok < 0) inTok = estimate(request)
        if (outTok < 0) outTok = reply.toString().length / 4L
        val reported = usage?.optDouble("cost", Double.NaN) ?: Double.NaN
        val p = price(provider, model)
        val cost = if (!reported.isNaN()) reported else p?.let { (inTok * it.first + outTok * it.second) / 1_000_000.0 } ?: 0.0
        val priced = !reported.isNaN() || p != null

        val all = try { JSONObject(prefs.getString("usage", "{}")) } catch (_: Exception) { JSONObject() }
        val today = all.optJSONObject(day) ?: JSONObject()
        val row = today.optJSONArray(model) ?: JSONArray(listOf(0, 0, 0.0, 0, 0))
        today.put(model, JSONArray(listOf(row.optLong(0) + inTok, row.optLong(1) + outTok, row.optDouble(2) + cost,
            row.optInt(3) + 1, if (priced || row.optInt(4) == 1) 1 else 0)))
        all.put(day, today)
        // Keep about two months.
        val keys = all.keys().asSequence().sorted().toList()
        keys.dropLast(62).forEach { all.remove(it) }
        prefs.edit().putString("usage", all.toString()).apply()
        _version.value++
    }

    private fun estimate(msgs: JSONArray): Long {
        var chars = 0L; var images = 0
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            when (val c = m.opt("content")) {
                is String -> chars += c.length
                is JSONArray -> for (j in 0 until c.length()) {
                    val part = c.optJSONObject(j) ?: continue
                    if (part.optString("type") == "image_url") images++ else chars += part.optString("text").length
                }
            }
            m.optJSONArray("tool_calls")?.let { chars += it.toString().length }
        }
        return chars / 4 + images * 1000L
    }

    /** Totals for days whose key starts with [prefix] ("2026-10-07" for a day, "2026-10" for a month). */
    fun totals(prefix: String): Totals {
        val all = try { JSONObject(prefs.getString("usage", "{}")) } catch (_: Exception) { JSONObject() }
        var i = 0L; var o = 0L; var c = 0.0; var priced = true; var calls = 0; var any = false
        for (k in all.keys()) if (k.startsWith(prefix)) {
            val d = all.getJSONObject(k)
            for (m in d.keys()) {
                val r = d.getJSONArray(m); any = true
                i += r.optLong(0); o += r.optLong(1); c += r.optDouble(2); calls += r.optInt(3)
                if (r.optInt(4) == 0) priced = false
            }
        }
        return Totals(i, o, c, priced && any, calls)
    }

    /** Last [n] days, oldest first: day, tokens, cost, calls (for the dashboard chart). */
    fun daily(n: Int = 14): List<org.json.JSONObject> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = java.util.Calendar.getInstance()
        val out = mutableListOf<org.json.JSONObject>()
        for (i in n - 1 downTo 0) {
            val c = cal.clone() as java.util.Calendar; c.add(java.util.Calendar.DAY_OF_YEAR, -i)
            val d = fmt.format(c.time); val t = totals(d)
            out += org.json.JSONObject().put("day", d).put("tokens", t.input + t.output).put("cost", t.cost).put("calls", t.calls)
        }
        return out
    }

    fun today() = totals(day)
    fun month() = totals(day.take(7))
    fun reset() { prefs.edit().remove("usage").apply(); _version.value++ }
}
