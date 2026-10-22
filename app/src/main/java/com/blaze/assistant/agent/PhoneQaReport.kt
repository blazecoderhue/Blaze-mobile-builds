package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.45.13 phone self-test report (PhoneQa on the phone; pure here so PhoneQaReportTest covers it). One [Run] per case
 * per repeat, with everything the user asked for as evidence: request id, route / model, tool calls with their
 * verdicts, first-token and total time, the screen before and after (accessibility tree text), the verifier's reading
 * and the strict judgement. Writes JSON (to upload) and Markdown (to read / share).
 */
object PhoneQaReport {
    data class Tool(val name: String, val args: String, val status: String, val evidence: String)

    data class Run(
        val case: String, val run: Int, val prompt: String,
        val requestIds: List<String>, val route: String, val model: String, val provider: String,
        val tools: List<Tool>, val firstTokenMs: Long, val totalMs: Long,
        val screenBefore: String, val screenAfter: String,
        /** The verifier's own reading of the phone after the turn. */
        val verified: Boolean?, val verifierEvidence: String,
        /** PhoneCases.judge on the turn's verdicts and reply. */
        val judgePass: Boolean, val judgeWhy: String,
        val reply: String, val chatId: String, val error: String = "",
    ) {
        /** Strict: the phone read the result back AND the turn's own verdicts / reply were right. */
        val pass: Boolean get() = verified == true && judgePass && error.isBlank()
    }

    fun toolsFrom(receiptJson: String): List<Tool> = Receipt.fromJson(receiptJson).map { Tool(it.tool, it.args.take(300), it.status.name, it.evidence.take(200)) }

    fun runJson(r: Run): JSONObject = JSONObject().put("case", r.case).put("run", r.run).put("prompt", r.prompt)
        .put("request_ids", JSONArray(r.requestIds)).put("route", r.route).put("model", r.model).put("provider", r.provider)
        .put("tools", JSONArray().apply { r.tools.forEach { t -> put(JSONObject().put("name", t.name).put("args", t.args).put("status", t.status).put("evidence", t.evidence)) } })
        .put("first_token_ms", r.firstTokenMs).put("total_ms", r.totalMs)
        .put("screen_before", r.screenBefore.take(4000)).put("screen_after", r.screenAfter.take(4000))
        .put("verifier", JSONObject().put("result", r.verified?.let { if (it) "pass" else "fail" } ?: "unknown").put("evidence", r.verifierEvidence))
        .put("judge", JSONObject().put("pass", r.judgePass).put("why", r.judgeWhy))
        .put("reply", r.reply.take(1200)).put("chat_id", r.chatId).put("error", r.error).put("pass", r.pass)

    data class Summary(val case: String, val passed: Int, val runs: Int) { val strict get() = runs > 0 && passed == runs }

    fun summary(runs: List<Run>): List<Summary> = runs.groupBy { it.case }.map { (c, l) -> Summary(c, l.count { it.pass }, l.size) }

    fun json(runs: List<Run>, meta: Map<String, String>): String = JSONObject().apply {
        meta.forEach { (k, v) -> put(k, v) }
        put("kind", "device")
        put("summary", JSONArray().apply { summary(runs).forEach { s -> put(JSONObject().put("case", s.case).put("passed", s.passed).put("runs", s.runs).put("strict", s.strict)) } })
        put("runs", JSONArray().apply { runs.forEach { put(runJson(it)) } })
    }.toString(1)

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ").trim()

    fun markdown(runs: List<Run>, meta: Map<String, String>): String = buildString {
        append("# Blaze phone-control self-test (DEVICE evidence)\n\n")
        meta.forEach { (k, v) -> append("- ").append(k).append(": ").append(v).append('\n') }
        append("\nStrict pass = the phone read the result back (verifier) AND the turn's verdicts and reply were right (no premature or false \"done\").\n\n")
        append("| case | pass | runs | strict |\n|---|---|---|---|\n")
        summary(runs).forEach { s -> append("| ${s.case} | ${s.passed} | ${s.runs} | ${if (s.strict) "PASS" else "FAIL"} |\n") }
        append("\n## Runs\n\n| case | # | pass | verifier | judge | tools (status) | first token | total | request id | model |\n|---|---|---|---|---|---|---|---|---|---|\n")
        runs.forEach { r ->
            append("| ${r.case} | ${r.run} | ${if (r.pass) "PASS" else "FAIL"} | ${cell((r.verified?.let { if (it) "pass" else "fail" } ?: "unknown") + ": " + r.verifierEvidence).take(140)} | ")
            append("${cell(r.judgeWhy).take(80)} | ${cell(r.tools.joinToString(", ") { it.name + "=" + it.status.lowercase() }).take(160)} | ${r.firstTokenMs} ms | ${r.totalMs} ms | ")
            append("${cell(r.requestIds.firstOrNull() ?: "-")} | ${cell(r.model.ifBlank { r.route })} |\n")
        }
        append("\n## Screen traces (accessibility tree, before → after)\n")
        runs.forEach { r ->
            append("\n### ${r.case} #${r.run}\n\nPrompt: ${r.prompt}\n\nReply: ${r.reply.take(400)}\n")
            if (r.error.isNotBlank()) append("\nError: ${r.error}\n")
            append("\nBefore:\n```\n${r.screenBefore.take(1200)}\n```\nAfter:\n```\n${r.screenAfter.take(1200)}\n```\n")
        }
    }
}
