package com.blaze.assistant.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan & quota as far as each provider exposes it to an API key:
 * balance/credit endpoints where they exist, rate-limit headers from a tiny test call,
 * and the plan found by model detection. Exact dashboards are linked from Settings.
 */
object Quota {
    data class Row(val label: String, val value: String)
    data class Report(val rows: List<Row>, val note: String?)

    fun openRouterKey(key: String): JSONObject? = try {
        val (c, t) = ModelCatalog.http("GET", "https://openrouter.ai/api/v1/key", null, LlmClient.authHeaders(key, "openrouter"))
        if (c in 200..299) JSONObject(t).optJSONObject("data") else null
    } catch (_: Exception) { null }

    suspend fun check(): Report = withContext(Dispatchers.IO) {
        val key = Settings.apiKey
        if (key.isBlank()) return@withContext Report(emptyList(), "Add your API key first.")
        val pid = Settings.providerId
        val rows = mutableListOf<Row>()
        ModelCatalog.cached()?.plan?.takeIf { it.isNotBlank() }?.let { rows += Row("Plan", it) }

        try {
            when (pid) {
                "openrouter" -> openRouterKey(key)?.let { d ->
                    rows += Row("Tier", if (d.optBoolean("is_free_tier")) "Free" else "Paid")
                    rows += Row("Used", "$" + "%.4f".format(d.optDouble("usage", 0.0)))
                    rows += Row("Limit", if (d.isNull("limit")) "No limit" else "$" + d.optDouble("limit"))
                    if (!d.isNull("limit_remaining")) rows += Row("Remaining", "$" + "%.4f".format(d.optDouble("limit_remaining")))
                }
                "moonshot" -> {
                    val (c, t) = ModelCatalog.http("GET", "https://api.moonshot.ai/v1/users/me/balance", null, LlmClient.authHeaders(key))
                    if (c in 200..299) JSONObject(t).optJSONObject("data")?.let { d ->
                        rows += Row("Balance", d.optString("available_balance", "?"))
                        rows += Row("Vouchers", d.optString("voucher_balance", "0"))
                    }
                }
                "deepseek" -> {
                    val (c, t) = ModelCatalog.http("GET", "https://api.deepseek.com/user/balance", null, LlmClient.authHeaders(key))
                    if (c in 200..299) {
                        val o = JSONObject(t)
                        rows += Row("Available", if (o.optBoolean("is_available")) "Yes" else "No — top up")
                        val arr = o.optJSONArray("balance_infos") ?: JSONArray()
                        for (i in 0 until arr.length()) arr.getJSONObject(i).let {
                            rows += Row("Balance", it.optString("total_balance") + " " + it.optString("currency"))
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        // Rate limits: a 1-token call with the chosen model; the reply's headers carry the limits.
        var gemini429: String? = null
        try {
            val base = Settings.baseUrl.trimEnd('/')
            val body = JSONObject().put("model", Settings.model).put("max_tokens", 1)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            val conn = com.blaze.assistant.connect.UrlSafety.open("$base/chat/completions").apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 45_000
                setRequestProperty("Content-Type", "application/json")
                LlmClient.authHeaders(key).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val h = LlmClient.rateHeaders(conn).ifEmpty { LlmClient.lastRateHeaders }
            if (code == 429) gemini429 = conn.errorStream?.bufferedReader()?.use { it.readText() }
            conn.disconnect()
            fun pick(vararg names: String) = names.firstNotNullOfOrNull { n -> h.entries.firstOrNull { it.key.endsWith(n) }?.value }
            pick("limit-requests", "requests-limit")?.let { lim ->
                rows += Row("Requests", "${pick("remaining-requests", "requests-remaining") ?: "?"} left of $lim")
            }
            pick("limit-tokens", "tokens-limit")?.let { lim ->
                rows += Row("Tokens", "${pick("remaining-tokens", "tokens-remaining") ?: "?"} left of $lim")
            }
            pick("reset-requests", "requests-reset")?.let { rows += Row("Resets", it) }
            if (code == 429 && gemini429 != null) {
                val m = Regex("""quotaId["\s:]+([A-Za-z0-9_-]+)""").find(gemini429!!)?.groupValues?.get(1)
                val v = Regex("""quotaValue["\s:]+"?(\d+)""").find(gemini429!!)?.groupValues?.get(1)
                rows += Row("Status", "Limit reached" + if (m != null) " ($m${if (v != null) ": $v" else ""})" else "")
            } else if (code in 200..299 && rows.none { it.label == "Requests" }) {
                rows += Row("Status", "Working · ${Settings.model}")
            }
        } catch (_: Exception) { }

        val note = when {
            rows.isEmpty() -> "This provider doesn't report quota to API keys."
            pid == "gemini" -> "Google shows exact per-model limits only in AI Studio."
            else -> null
        }
        Report(rows, note)
    }
}
