package com.blaze.assistant.connect

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.45.3: Blaze Cloud paid plans and checkout (gateway billing API; yearly terms since gateway 0.6.2).
 *
 *  - GET /v1/billing/methods: plans (id, price_usd, price_usd_year), payment methods (details, ref_label, hint,
 *    amounts {plan: "ZMW 200"}, amounts_year {plan: "ZMW 2000"}), periods {month:{days:30}, year:{days:365}}.
 *  - POST /v1/billing/pay {plan, method, reference, period: "month" | "year"} → a pending payment an admin confirms.
 *  - GET /v1/billing/me: the plan, the paid term and recent payments (each with its period).
 *
 * The field is `period` (never "term"); month is the default and what an older gateway assumes. A gateway without
 * price_usd_year (before 0.6.2) gets no Monthly / Yearly toggle. Terms don't renew by themselves. Pure parsing and
 * wording here (BillingTest); the HTTP calls are [Client].
 */
object Billing {
    enum class Period(val id: String, val days: Int) { MONTH("month", 30), YEAR("year", 365);
        companion object { fun of(s: String?): Period = if (s?.trim()?.lowercase() == "year") YEAR else MONTH } }

    data class Plan(val id: String, val priceUsd: Double, val priceUsdYear: Double?)
    data class Detail(val label: String, val value: String)
    data class Method(val id: String, val name: String, val currency: String, val details: List<Detail>, val refLabel: String, val hint: String,
                      val amounts: Map<String, String>, val amountsYear: Map<String, String>)
    data class Methods(val plans: List<Plan>, val methods: List<Method>, val monthDays: Int, val yearDays: Int?)
    data class Payment(val id: Long, val plan: String, val period: Period, val status: String, val amountLocal: String, val createdAt: Long)
    data class Me(val plan: String, val expiresAt: Long, val daysLeft: Int?, val payments: List<Payment>)

    private fun map(o: JSONObject?): Map<String, String> = o?.keys()?.asSequence()?.associateWith { o.optString(it) }.orEmpty()

    fun parseMethods(json: String): Methods {
        val o = JSONObject(json)
        val plans = o.optJSONArray("plans") ?: JSONArray()
        val ms = o.optJSONArray("methods") ?: JSONArray()
        val periods = o.optJSONObject("periods")
        return Methods(
            (0 until plans.length()).mapNotNull { plans.optJSONObject(it) }.map { p ->
                Plan(p.optString("id"), p.optDouble("price_usd", 0.0), if (p.has("price_usd_year") && !p.isNull("price_usd_year")) p.optDouble("price_usd_year").takeIf { !it.isNaN() && it > 0 } else null)
            }.filter { it.id.isNotBlank() },
            (0 until ms.length()).mapNotNull { ms.optJSONObject(it) }.map { m ->
                val d = m.optJSONArray("details") ?: JSONArray()
                Method(m.optString("id"), m.optString("name"), m.optString("currency"),
                    (0 until d.length()).mapNotNull { d.optJSONObject(it) }.map { Detail(it.optString("label"), it.optString("value")) },
                    m.optString("ref_label").ifBlank { "Payment reference" }, m.optString("hint"), map(m.optJSONObject("amounts")), map(m.optJSONObject("amounts_year")))
            }.filter { it.id.isNotBlank() },
            periods?.optJSONObject("month")?.optInt("days", 30) ?: o.optInt("term_days", 30),
            periods?.optJSONObject("year")?.optInt("days")?.takeIf { it > 0 },
        )
    }

    /** Show the Monthly / Yearly toggle: every plan has a yearly price (gateway 0.6.2+). */
    fun yearlyOffered(m: Methods): Boolean = m.plans.isNotEmpty() && m.plans.all { it.priceUsdYear != null }

    /** The period actually used: yearly only when offered. */
    fun effective(m: Methods, p: Period): Period = if (p == Period.YEAR && yearlyOffered(m)) Period.YEAR else Period.MONTH

    private fun usd(v: Double): String = if (v % 1.0 == 0.0) "$" + v.toLong() else "$" + "%.2f".format(v)

    /** "$10 / month" or "$100 / year". */
    fun priceLine(p: Plan, period: Period): String =
        if (period == Period.YEAR && p.priceUsdYear != null) usd(p.priceUsdYear) + " / year" else usd(p.priceUsd) + " / month"

    /** "2 months free" when the yearly price is ten months of the monthly one (null otherwise). */
    fun saving(p: Plan): String? {
        val y = p.priceUsdYear ?: return null
        if (p.priceUsd <= 0) return null
        val months = Math.round(12 - y / p.priceUsd).toInt()
        return if (months >= 1 && kotlin.math.abs((12 - months) * p.priceUsd - y) < 0.01) "$months month${if (months == 1) "" else "s"} free" else null
    }

    /** The local amount to pay for [plan] with method [m] ("ZMW 2000"), "" when the gateway gave none. */
    fun amount(m: Method, plan: String, period: Period): String =
        (if (period == Period.YEAR) m.amountsYear[plan] else m.amounts[plan]).orEmpty()

    /** The term copy under the price. */
    fun termLine(period: Period, days: Int = period.days): String = "$days days, not auto-renewing"

    fun planName(id: String): String = when (id.lowercase()) { "standard" -> "Standard"; "pro" -> "Pro"; "basic" -> "Basic"; else -> id.replaceFirstChar { it.uppercase() } }

    /** A payment in the history: "Pro, yearly" / "Standard". */
    fun historyLabel(p: Payment): String = planName(p.plan) + if (p.period == Period.YEAR) ", yearly" else ""

    /** The POST /v1/billing/pay body. */
    fun payBody(plan: String, method: String, reference: String, period: Period): JSONObject =
        JSONObject().put("plan", plan).put("method", method).put("reference", reference.trim()).put("period", period.id)

    fun parseMe(json: String): Me {
        val o = JSONObject(json)
        val term = o.optJSONObject("term")
        val pays = o.optJSONArray("payments") ?: JSONArray()
        return Me(o.optString("plan").ifBlank { term?.optString("plan").orEmpty() }, term?.optLong("expires_at") ?: 0L,
            term?.let { t -> if (t.has("days_left")) t.optInt("days_left") else null },
            (0 until pays.length()).mapNotNull { pays.optJSONObject(it) }.map { p ->
                Payment(p.optLong("id"), p.optString("plan"), Period.of(p.optString("period")), p.optString("status"), p.optString("amount_local"), p.optLong("created_at"))
            })
    }

    /** The gateway's own words for a refused payment ("Choose monthly or yearly" for bad_period), else a plain line. */
    fun errorLine(code: Int, body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null } ?: if (code == 401) "Sign in to Blaze first." else "The payment couldn't be sent ($code). Try again in a moment."

    /** The gateway calls (signed in with the Blaze account token). */
    object Client {
        private fun call(method: String, path: String, body: String?): Pair<Int, String> {
            val c = UrlSafety.open(com.blaze.assistant.agent.Providers.gatewayBase.trimEnd('/') + path)
            c.requestMethod = method; c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer " + com.blaze.assistant.agent.Settings.gatewayToken())
            try {
                if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
                val code = c.responseCode
                val text = ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }).orEmpty()
                return code to text
            } finally { c.disconnect() }
        }
        fun methods(): Result<Methods> = runCatching { call("GET", "/billing/methods", null).let { (c, t) -> if (c in 200..299) parseMethods(t) else error(errorLine(c, t)) } }
        fun me(): Result<Me> = runCatching { call("GET", "/billing/me", null).let { (c, t) -> if (c in 200..299) parseMe(t) else error(errorLine(c, t)) } }
        fun pay(plan: String, method: String, reference: String, period: Period): Result<String> = runCatching {
            call("POST", "/billing/pay", payBody(plan, method, reference, period).toString()).let { (c, t) ->
                if (c in 200..299) JSONObject(t).optString("message").ifBlank { "Thanks. We'll confirm your payment soon." } else error(errorLine(c, t)) }
        }
    }
}
