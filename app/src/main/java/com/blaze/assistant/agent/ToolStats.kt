package com.blaze.assistant.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Tool-call scoreboard: per tool and per model, how many calls ran clean, needed repair,
 * named no real tool, or failed. Kept in filesDir/tool_stats.json so we can see which tools
 * models trip on ("tool stats" in chat, or blaze_self tool_stats) and fix those schemas.
 */
object ToolStats {
    private var file: File? = null
    private val data = JSONObject()
    private var dirty = 0

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "tool_stats.json")
        try { file?.takeIf { it.exists() }?.readText()?.let { val o = JSONObject(it); o.keys().forEach { k -> data.put(k, o.get(k)) } } } catch (_: Exception) { }
    }

    @Synchronized fun add(tool: String, kind: String, model: String = LlmClient.lastRoute) {
        for (k in listOf("tool:$tool", "model:${model.substringBefore(" · trimmed").take(60).ifBlank { "?" }}")) {
            val o = data.optJSONObject(k) ?: JSONObject().also { data.put(k, it) }
            o.put(kind, o.optInt(kind) + 1)
        }
        if (++dirty >= 5) save()
    }

    @Synchronized fun save() {
        dirty = 0
        try { file?.writeText(data.toString()) } catch (_: Exception) { }
    }

    @Synchronized fun reset() { data.keys().asSequence().toList().forEach { data.remove(it) }; save() }

    /** One line per tool or model with problems, worst first. */
    @Synchronized fun report(limit: Int = 12): String {
        fun line(k: String): Pair<String, Double>? {
            val o = data.optJSONObject(k) ?: return null
            val ok = o.optInt("ok"); val rep = o.optInt("repaired"); val unk = o.optInt("unknown"); val err = o.optInt("error"); val txt = o.optInt("text_call")
            val n = ok + rep + unk + err
            if (n == 0) return null
            val bad = (rep + unk + err).toDouble() / n
            return "${k.substringAfter(':')}: $n calls, ${(bad * 100).toInt()}% trouble ($rep repaired, $unk unknown, $err failed${if (txt > 0) ", $txt written as text" else ""})" to bad
        }
        val tools = data.keys().asSequence().filter { it.startsWith("tool:") }.mapNotNull { line(it) }.sortedByDescending { it.second }.take(limit).toList()
        val models = data.keys().asSequence().filter { it.startsWith("model:") }.mapNotNull { line(it) }.sortedByDescending { it.second }.take(6).toList()
        if (tools.isEmpty()) return "No tool calls recorded yet."
        return "Tools (worst first):\n" + tools.joinToString("\n") { "• " + it.first } +
            "\n\nModels:\n" + models.joinToString("\n") { "• " + it.first }
    }
}
