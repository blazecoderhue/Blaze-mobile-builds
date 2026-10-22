package com.blaze.assistant.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blaze.assistant.R
import com.blaze.assistant.agent.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * v0.33.0 Money Radar: three of the 22 ideas in one place, all on the phone, opt-in (pref money_radar, off by default
 * because the chat-log promise says bank messages are never stored unless you ask).
 *
 * - **Receipt Vault**: mobile-money / bank SMS (Airtel Money, MTN MoMo, Zamtel Kwacha, banks) are parsed into a ledger:
 *   amount, sent / received / paid / withdrawn, who, balance after, reference. Never the PIN, code or full message.
 * - **Bill Radar**: payees you pay roughly monthly (or bills you add) get an expected next date; a nudge 2 days before,
 *   and a summary of what's due this week.
 * - **Bundle Guardian**: data / airtime bundle SMS ("balance 85MB, expires 20/10/2026") are tracked; a warning when data
 *   runs low (under 100 MB or 10 %) or a bundle expires within 24 h, so a bundle never dies silently.
 *
 * Tool `money` (summary / receipts / bills / bundles / add_bill / remove_bill). Pure parsing in [Parse] (MoneyRadarTest).
 */
object MoneyRadar {
    const val PREF = "money_radar"
    const val TOOL = "money"
    private const val CH = "blaze_money"

    var on: Boolean
        get() = try { Settings.bool(PREF, false) } catch (_: Throwable) { false }
        set(v) = Settings.setBool(PREF, v)

    private val smsApps = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.android.messaging", "com.truecaller", "com.airtel.africa.selfcare", "com.mtn.mtnapp", "com.mtn.momo", "zm.co.zamtel")

    data class Tx(val at: Long, val kind: String, val amount: Double, val who: String, val balance: Double?, val ref: String, val via: String)
    data class Bundle(val at: Long, val via: String, val mb: Double?, val airtime: Double?, val expires: Long?)
    data class Bill(val name: String, val amount: Double?, val day: Int, val auto: Boolean)

    // ---------------------------------------------------------------- pure parsing (unit tested)
    object Parse {
        private val amt = """(?:k|zmw|kr|kwacha)\s?([\d,]+(?:\.\d{1,2})?)|([\d,]+(?:\.\d{1,2})?)\s?(?:zmw|kwacha)"""
        private val amountRe = Regex(amt, RegexOption.IGNORE_CASE)
        private val balRe = Regex("""(?:new |available |wallet |your )?bal(?:ance)?(?: is| of)?\s*:?\s*(?:k|zmw)?\s?([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
        private val refRe = Regex("""(?:txn|trans(?:action)?|ref(?:erence)?|id)\.?\s*(?:id|no\.?)?\s*:?\s*([A-Z0-9.]{6,})""", RegexOption.IGNORE_CASE)
        private val kinds = listOf(
            "received" to Regex("""\b(received|you have received|credited|deposit(?:ed)?|cash in)\b""", RegexOption.IGNORE_CASE),
            "withdrawn" to Regex("""\b(withdr[ae]w\w*|cash out|cashout)\b""", RegexOption.IGNORE_CASE),
            "paid" to Regex("""\b(paid|payment of|bill pay\w*|purchased|bought|merchant)\b""", RegexOption.IGNORE_CASE),
            "sent" to Regex("""\b(sent|transferred|you have sent|debited|transfer of)\b""", RegexOption.IGNORE_CASE))
        private val whoRe = Regex("""\b(?:to|from|at)\s+([A-Z][A-Za-z.'&-]+(?:\s+[A-Z][A-Za-z.'&-]+){0,3})""")
        private val moneyHint = Regex("""\b(money|momo|kwacha|wallet|bank|account|txn|transaction)\b|\b(?:k|zmw)\s?\d""", RegexOption.IGNORE_CASE)

        fun num(s: String): Double? = s.replace(",", "").toDoubleOrNull()

        /** A money SMS as a transaction, or null (bundles, codes, promos and chat are not transactions). */
        fun tx(text: String, at: Long, via: String): Tx? {
            if (!moneyHint.containsMatchIn(text) || bundle(text, at, via) != null && !Regex("(?i)sent|received|paid").containsMatchIn(text)) return null
            if (Regex("""(?i)\b(otp|verification code|one.time|pin is)\b""").containsMatchIn(text)) return null
            val kind = kinds.firstOrNull { it.second.containsMatchIn(text) }?.first ?: return null
            val balM = balRe.find(text)
            // The amount is the first figure that isn't the balance.
            val am = amountRe.findAll(text).firstOrNull { m -> balM == null || m.range.first !in balM.range }
                ?.let { num(it.groupValues[1].ifEmpty { it.groupValues[2] }) } ?: return null
            if (am <= 0) return null
            val who = whoRe.find(text)?.groupValues?.get(1)?.trim()?.take(40).orEmpty()
                .replace(Regex("""\s+(?:on|at|ref|txn|new|your|bal\w*)$""", RegexOption.IGNORE_CASE), "")
            return Tx(at, kind, am, who, balM?.let { num(it.groupValues[1]) }, refRe.find(text)?.groupValues?.get(1).orEmpty(), via)
        }

        private val mbRe = Regex("""([\d,]+(?:\.\d+)?)\s?(gb|mb)\b""", RegexOption.IGNORE_CASE)
        private val bundleHint = Regex("""\b(bundle|data|internet|mb|gb|airtime)\b""", RegexOption.IGNORE_CASE)
        private val expRe = Regex("""(?:expir\w*|valid (?:till|until|to)|ends?)\s*(?:on|at)?\s*:?\s*(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})(?:\s+(\d{1,2}):(\d{2}))?""", RegexOption.IGNORE_CASE)
        private val airRe = Regex("""airtime (?:balance )?(?:is |of )?:?\s*(?:k|zmw)\s?([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)

        fun bundle(text: String, at: Long, via: String): Bundle? {
            if (!bundleHint.containsMatchIn(text)) return null
            val mb = mbRe.findAll(text).map { m -> (num(m.groupValues[1]) ?: 0.0) * if (m.groupValues[2].equals("gb", true)) 1024 else 1 }.firstOrNull()
            val air = airRe.find(text)?.let { num(it.groupValues[1]) }
            val exp = expRe.find(text)?.let { m ->
                val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
                Calendar.getInstance().apply {
                    set(y, m.groupValues[2].toInt() - 1, m.groupValues[1].toInt(), m.groupValues[4].toIntOrNull() ?: 23, m.groupValues[5].toIntOrNull() ?: 59, 0)
                    set(Calendar.MILLISECOND, 0) }.timeInMillis }
            if (mb == null && air == null) return null
            if (!Regex("""(?i)balance|remaining|left|expir|valid|bought|purchased|activated|subscribed""").containsMatchIn(text)) return null
            return Bundle(at, via, mb, air, exp)
        }

        /** Bundle Guardian: a warning line, or null. [startMb] = the size when bought (for the 10 % rule). */
        fun bundleWarning(b: Bundle, now: Long, startMb: Double?): String? {
            val mb = b.mb
            if (b.expires != null && b.expires > now && b.expires - now < 24 * 3600_000L)
                return "Your ${b.via} bundle expires in ${((b.expires - now) / 3600_000L).coerceAtLeast(1)} h" + (mb?.let { " with ${fmtMb(it)} left" } ?: "") + "."
            if (mb != null && (mb < 100 || (startMb != null && startMb > 0 && mb / startMb < 0.1)))
                return "Data is low on ${b.via}: ${fmtMb(mb)} left."
            return null
        }

        /** "2026-10-18" → epoch ms (midday), else null. */
        fun ymd(s: String?): Long? {
            val m = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""").find(s?.trim().orEmpty()) ?: return null
            return Calendar.getInstance().apply { set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(), 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        }

        /** Average money out (sent + paid + withdrawn) per month over the last 3 full months, or null with no data. */
        fun monthlySpend(txs: List<Tx>, now: Long): Double? {
            val since = now - 90L * 86_400_000L
            val out = txs.filter { it.at >= since && it.kind != "received" }
            if (out.isEmpty()) return null
            val spanDays = ((now - out.minOf { it.at }) / 86_400_000.0).coerceIn(30.0, 90.0)
            return out.sumOf { it.amount } / (spanDays / 30.0)
        }

        fun fmtMb(mb: Double): String = if (mb >= 1024) "%.1f GB".format(mb / 1024) else "${mb.toInt()} MB"

        /**
         * Bill Radar: payees paid in at least 2 different months, about a month apart, become bills due on their usual day.
         */
        fun bills(txs: List<Tx>): List<Bill> {
            val cal = Calendar.getInstance()
            return txs.filter { (it.kind == "paid" || it.kind == "sent") && it.who.isNotBlank() }
                .groupBy { it.who.lowercase() }
                .mapNotNull { (_, list) ->
                    val months = list.map { cal.timeInMillis = it.at; cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH) }.distinct().sorted()
                    if (months.size < 2) return@mapNotNull null
                    val gaps = months.zipWithNext { a, b -> b - a }
                    if (gaps.count { it == 1 } * 2 < gaps.size) return@mapNotNull null
                    val days = list.map { cal.timeInMillis = it.at; cal.get(Calendar.DAY_OF_MONTH) }.sorted()
                    Bill(list.last().who, list.map { it.amount }.sorted()[list.size / 2], days[days.size / 2], true)
                }
        }

        /** The next date (epoch ms, midday) a bill on [day] of the month falls on, from [now]. */
        fun nextDue(day: Int, now: Long): Long {
            val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val today = c.get(Calendar.DAY_OF_MONTH)
            if (day < today) c.add(Calendar.MONTH, 1)
            c.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(c.getActualMaximum(Calendar.DAY_OF_MONTH)))
            return c.timeInMillis
        }

        fun summary(txs: List<Tx>, since: Long): String {
            val w = txs.filter { it.at >= since }
            if (w.isEmpty()) return "No money messages in that time."
            val by = w.groupBy { it.kind }.mapValues { e -> e.value.sumOf { it.amount } }
            val top = w.filter { it.kind != "received" && it.who.isNotBlank() }.groupBy { it.who }.mapValues { e -> e.value.sumOf { it.amount } }
                .entries.sortedByDescending { it.value }.take(3)
            return buildString {
                append("${w.size} transactions. ")
                listOf("received", "sent", "paid", "withdrawn").forEach { k -> by[k]?.let { append("${k.replaceFirstChar { c -> c.uppercase() }} K${"%,.2f".format(it)}. ") } }
                if (top.isNotEmpty()) append("Most to: " + top.joinToString(", ") { "${it.key} K${"%,.2f".format(it.value)}" } + ". ")
                w.lastOrNull { it.balance != null }?.let { append("Last balance K${"%,.2f".format(it.balance)} (${it.via}).") }
            }.trim()
        }
    }

    // ---------------------------------------------------------------- storage (filesDir/money)
    private fun dir(ctx: Context) = File(ctx.filesDir, "money").apply { mkdirs() }
    private fun read(f: File): JSONArray = try { if (f.exists()) JSONArray(f.readText()) else JSONArray() } catch (_: Exception) { JSONArray() }

    @Synchronized fun txs(ctx: Context): List<Tx> {
        val a = read(File(dir(ctx), "ledger.json"))
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            Tx(o.optLong("at"), o.optString("kind"), o.optDouble("amount"), o.optString("who"), if (o.has("bal")) o.optDouble("bal") else null, o.optString("ref"), o.optString("via")) }
    }

    @Synchronized private fun addTx(ctx: Context, t: Tx) {
        val f = File(dir(ctx), "ledger.json"); val a = read(f)
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue
            if (t.ref.isNotBlank() && o.optString("ref") == t.ref) return
            if (o.optLong("at") == t.at && o.optDouble("amount") == t.amount) return }
        a.put(JSONObject().put("at", t.at).put("kind", t.kind).put("amount", t.amount).put("who", t.who).put("ref", t.ref).put("via", t.via)
            .apply { t.balance?.let { put("bal", it) } })
        val keep = JSONArray(); val from = (a.length() - 3000).coerceAtLeast(0)
        for (i in from until a.length()) keep.put(a.get(i))
        f.writeText(keep.toString())
    }

    @Synchronized fun bundles(ctx: Context): List<Bundle> {
        val a = read(File(dir(ctx), "bundles.json"))
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            Bundle(o.optLong("at"), o.optString("via"), if (o.has("mb")) o.optDouble("mb") else null, if (o.has("air")) o.optDouble("air") else null, if (o.has("exp")) o.optLong("exp") else null) }
    }

    @Synchronized private fun addBundle(ctx: Context, b: Bundle) {
        val f = File(dir(ctx), "bundles.json"); val a = read(f)
        a.put(JSONObject().put("at", b.at).put("via", b.via).apply { b.mb?.let { put("mb", it) }; b.airtime?.let { put("air", it) }; b.expires?.let { put("exp", it) } })
        val keep = JSONArray(); val from = (a.length() - 200).coerceAtLeast(0)
        for (i in from until a.length()) keep.put(a.get(i))
        f.writeText(keep.toString())
    }

    @Synchronized fun manualBills(ctx: Context): List<Bill> {
        val a = read(File(dir(ctx), "bills.json"))
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Bill(it.optString("name"), if (it.has("amount")) it.optDouble("amount") else null, it.optInt("day", 1), false) }
    }

    @Synchronized private fun saveBills(ctx: Context, list: List<Bill>) {
        File(dir(ctx), "bills.json").writeText(JSONArray(list.map { b -> JSONObject().put("name", b.name).put("day", b.day).apply { b.amount?.let { put("amount", it) } } }).toString())
    }

    fun allBills(ctx: Context): List<Bill> {
        val manual = manualBills(ctx)
        return manual + Parse.bills(txs(ctx)).filter { a -> manual.none { it.name.equals(a.name, true) } }
    }

    // ---------------------------------------------------------------- receipt photos + warranties
    private fun addReceipt(ctx: Context, a: JSONObject): String {
        val amount = a.optDouble("amount").takeIf { !it.isNaN() && it > 0 } ?: return "What's the total on the receipt?"
        val merchant = a.optString("merchant").trim().ifBlank { "Receipt" }.take(40)
        val at = Parse.ymd(a.optString("date")) ?: System.currentTimeMillis()
        addTx(ctx, Tx(at, "paid", amount, merchant, null, "rcpt-" + (merchant + at + amount).hashCode().toString(36), "Receipt photo"))
        val w = Parse.ymd(a.optString("warranty_until"))
        if (w != null) {
            val f = File(dir(ctx), "warranties.json"); val arr = read(f)
            arr.put(JSONObject().put("merchant", merchant).put("items", a.optString("items").take(120)).put("until", w).put("amount", amount)); f.writeText(arr.toString())
            schedule(ctx)
        }
        return "Filed: $merchant K${"%,.2f".format(amount)}" + (a.optString("items").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            (w?.let { ". Warranty until ${java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(it)}; I'll remind you 2 weeks before" } ?: "") + "."
    }

    private fun warranties(ctx: Context): String {
        val a = read(File(dir(ctx), "warranties.json")); val df = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK)
        val l = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.sortedBy { it.optLong("until") }
        return if (l.isEmpty()) "No warranties filed. Send me a receipt photo and say it has a warranty." else l.joinToString("\n") {
            "${it.optString("merchant")} ${it.optString("items")} · until ${df.format(it.optLong("until"))}" + if (it.optLong("until") < System.currentTimeMillis()) " (ended)" else "" }
    }

    private fun warrantyCheck(ctx: Context, now: Long) {
        val a = read(File(dir(ctx), "warranties.json"))
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue
            val left = o.optLong("until") - now
            if (left in 0..14 * 86_400_000L && !o.optBoolean("warned")) {
                o.put("warned", true)
                notify(ctx, 4310 + i % 20, "Warranty ending", "${o.optString("merchant")} ${o.optString("items")}: the warranty ends in ${left / 86_400_000L} days. Anything to claim?")
            } }
        File(dir(ctx), "warranties.json").writeText(a.toString())
    }

    // ---------------------------------------------------------------- live hooks
    /** From NotificationBrain, raw text (before codes are hidden). Codes/PINs are never kept: only the parsed fields. */
    fun onSms(ctx: Context, pkg: String, title: String, text: String, at: Long) {
        if (!on || pkg !in smsApps || text.length < 15) return
        val via = title.ifBlank { "SMS" }.take(30)
        Parse.tx(text, at, via)?.let { addTx(ctx, it) }
        Parse.bundle(text, at, via)?.let { b ->
            val start = bundles(ctx).filter { it.via == via }.takeLast(10).mapNotNull { it.mb }.maxOrNull()
            addBundle(ctx, b)
            Parse.bundleWarning(b, System.currentTimeMillis(), start)?.let { notify(ctx, 4300, "Bundle Guardian", it) }
        }
        schedule(ctx)
    }

    fun notify(ctx: Context, id: Int, title: String, body: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Money Radar", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(id, NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat_blaze).setContentTitle(title).setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setAutoCancel(true).build())
        } catch (_: Throwable) { }
    }

    /** Daily check for bills due in 2 days and bundles expiring. */
    fun schedule(ctx: Context) {
        if (!on && !File(dir(ctx), "warranties.json").exists()) return
        try { WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("money_radar", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MoneyRadarWorker>(12, TimeUnit.HOURS).build()) } catch (_: Throwable) { }
    }

    fun daily(ctx: Context) {
        val now = System.currentTimeMillis()
        try { warrantyCheck(ctx, now) } catch (_: Exception) { }
        if (!on) return
        val soon = allBills(ctx).filter { val d = Parse.nextDue(it.day, now); d - now in 0..(2 * 86_400_000L + 12 * 3600_000L) }
        val key = "money_bill_nudged"; val today = now / 86_400_000L
        if (soon.isNotEmpty() && Settings.str(key) != today.toString()) {
            Settings.setStr(key, today.toString())
            notify(ctx, 4301, "Bills due soon", soon.joinToString("; ") { "${it.name}" + (it.amount?.let { a -> " ~K${"%,.0f".format(a)}" } ?: "") + " on the ${it.day}" })
        }
        bundles(ctx).groupBy { it.via }.values.mapNotNull { it.lastOrNull() }.forEach { b ->
            Parse.bundleWarning(b, now, null)?.takeIf { b.expires != null }?.let { notify(ctx, 4302 + (b.via.hashCode() and 0x3f), "Bundle Guardian", it) }
        }
    }

    // ---------------------------------------------------------------- agent tool
    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Money Radar (on the phone, from mobile-money / bank / bundle SMS): spending summary, the receipt vault, bills due " +
            "(learned from repeat payments or added by the user), a monthly forecast and data/airtime bundles. For a receipt PHOTO, read it and call add_receipt " +
            "(works even with Money Radar off); warranty end dates get a reminder 2 weeks before. Needs Money Radar on in Settings › Notification replies; " +
            "action enable turns it on when the user asks.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("summary", "receipts", "bills", "forecast", "bundles", "add_bill", "remove_bill", "add_receipt", "warranties", "enable", "disable"))))
            .put("days", JSONObject().put("type", "integer").put("description", "summary/receipts window, default 30"))
            .put("query", JSONObject().put("type", "string").put("description", "receipts: who / reference / amount to look for"))
            .put("name", JSONObject().put("type", "string")).put("amount", JSONObject().put("type", "number"))
            .put("day", JSONObject().put("type", "integer").put("description", "day of the month the bill is due"))
            .put("merchant", JSONObject().put("type", "string").put("description", "add_receipt: shop (read it off the receipt photo)"))
            .put("date", JSONObject().put("type", "string").put("description", "add_receipt: YYYY-MM-DD"))
            .put("items", JSONObject().put("type", "string").put("description", "add_receipt: short item list"))
            .put("warranty_until", JSONObject().put("type", "string").put("description", "add_receipt: YYYY-MM-DD when a warranty/guarantee ends")))
            .put("required", JSONArray(listOf("action")))))

    fun run(ctx: Context, a: JSONObject): String {
        val act = a.optString("action")
        if (act == "add_receipt") return addReceipt(ctx, a)
        if (act == "warranties") return warranties(ctx)
        if (act == "enable") { on = true; schedule(ctx); return "Money Radar is on. New mobile-money, bank and bundle SMS will be tracked from now (amounts, payees and balances only)." }
        if (act == "disable") { on = false; return "Money Radar is off. Saved receipts are kept until you ask me to delete them." }
        if (!on) return "Money Radar is off. Ask me to turn it on (or Settings › Notification replies › Money Radar); it only sees SMS that arrive after that."
        val days = a.optInt("days", 30).coerceIn(1, 3650)
        val since = System.currentTimeMillis() - days * 86_400_000L
        val df = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK)
        return when (act) {
            "summary" -> "Last $days days: " + Parse.summary(txs(ctx), since)
            "receipts" -> {
                val q = a.optString("query").trim().lowercase()
                val list = txs(ctx).filter { it.at >= since && (q.isEmpty() || it.who.lowercase().contains(q) || it.ref.lowercase().contains(q) || "%.2f".format(it.amount).contains(q)) }.takeLast(25)
                if (list.isEmpty()) "No receipts match." else list.joinToString("\n") { "${df.format(it.at)} · ${it.kind} K${"%,.2f".format(it.amount)}" +
                    (if (it.who.isNotBlank()) " · ${it.who}" else "") + (if (it.ref.isNotBlank()) " · ref ${it.ref}" else "") + " · ${it.via}" }
            }
            "bills" -> { val now = System.currentTimeMillis(); val b = allBills(ctx).sortedBy { Parse.nextDue(it.day, now) }
                if (b.isEmpty()) "No bills yet. They appear after a payee is paid two months running, or add one (add_bill)." else b.joinToString("\n") {
                    "${it.name}" + (it.amount?.let { x -> " ~K${"%,.2f".format(x)}" } ?: "") + " · next ${java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.UK).format(Parse.nextDue(it.day, now))}" + if (it.auto) " (learned)" else "" } }
            "forecast" -> { val now = System.currentTimeMillis(); val b = allBills(ctx)
                val cal = Calendar.getInstance().apply { timeInMillis = now }; val end = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_MONTH, 30) }.timeInMillis
                val due = b.filter { Parse.nextDue(it.day, now) <= end }
                val spend = Parse.monthlySpend(txs(ctx), now)
                "Next 30 days: bills ${if (due.isEmpty()) "none known" else due.joinToString(", ") { "${it.name}" + (it.amount?.let { x -> " K${"%,.0f".format(x)}" } ?: "") }}" +
                    " = K${"%,.0f".format(due.sumOf { it.amount ?: 0.0 })}." + (spend?.let { " Your usual spending is about K${"%,.0f".format(it)} a month (last 3 months)." } ?: "") +
                    (if (cal.get(Calendar.DAY_OF_MONTH) > 0) "" else "") }
            "bundles" -> { val now = System.currentTimeMillis(); val b = bundles(ctx).groupBy { it.via }.values.mapNotNull { it.lastOrNull() }
                if (b.isEmpty()) "No bundle messages yet." else b.joinToString("\n") { x -> "${x.via}: " + listOfNotNull(x.mb?.let { Parse.fmtMb(it) + " data" }, x.airtime?.let { "K%.2f airtime".format(it) },
                    x.expires?.let { "expires " + df.format(it) + if (it < now) " (expired)" else "" }).joinToString(", ") + " (as of ${df.format(x.at)})" } }
            "add_bill" -> { val n = a.optString("name").trim(); if (n.isEmpty()) return "Name the bill."
                val day = a.optInt("day", 0); if (day !in 1..31) return "Which day of the month is it due?"
                saveBills(ctx, manualBills(ctx).filterNot { it.name.equals(n, true) } + Bill(n, a.optDouble("amount").takeIf { !it.isNaN() }, day, false)); schedule(ctx)
                "Added $n, due on the $day. I'll nudge you two days before." }
            "remove_bill" -> { val n = a.optString("name").trim(); val m = manualBills(ctx)
                if (m.none { it.name.equals(n, true) }) "No added bill called $n." else { saveBills(ctx, m.filterNot { it.name.equals(n, true) }); "Removed $n." } }
            else -> "Unknown action."
        }
    }
}

class MoneyRadarWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result { try { MoneyRadar.daily(applicationContext) } catch (_: Throwable) { }; return Result.success() }
}
