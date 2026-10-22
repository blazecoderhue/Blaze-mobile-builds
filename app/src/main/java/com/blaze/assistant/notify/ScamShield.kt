package com.blaze.assistant.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.blaze.assistant.R
import com.blaze.assistant.agent.Settings

/**
 * v0.33.0 Scam Shield: every SMS / chat notification is checked on the phone for the usual mobile-money and prize
 * scams and phishing links ("sent to you by mistake, send it back", "you've won", "your account is blocked, click",
 * hidden or look-alike links). A likely scam gets a warning notification with the reasons. Nothing leaves the phone;
 * on by default, off in Settings › Notification replies (pref scam_shield). Pure scoring in [score] (ScamShieldTest).
 */
object ScamShield {
    const val PREF = "scam_shield"
    private const val CH = "blaze_scam"
    private val recent = LinkedHashMap<String, Long>()

    var on: Boolean
        get() = try { Settings.bool(PREF, true) } catch (_: Throwable) { true }
        set(v) = Settings.setBool(PREF, v)

    data class Verdict(val score: Int, val reasons: List<String>) { val scam get() = score >= 5 }

    private val chatApps = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca", "com.google.android.apps.messaging",
        "com.samsung.android.messaging", "com.android.mms", "com.android.messaging", "com.truecaller", "com.imo.android.imoim")

    private val rules: List<Triple<Regex, Int, String>> = listOf(
        Triple(Regex("""(sent|received|deposited|transferred).{0,40}(by mistake|wrongly|in error|mistakenly)""", RegexOption.IGNORE_CASE), 5, "\u201cmoney sent by mistake\u201d is the most common mobile-money scam"),
        Triple(Regex("""(send|refund|return|reverse) (it|the money|back)""", RegexOption.IGNORE_CASE), 2, "asks you to send money back"),
        Triple(Regex("""(you('ve| have)? won|winner|congratulations).{0,60}(prize|promotion|draw|lottery|k\s?\d|zmw|cash|phone)""", RegexOption.IGNORE_CASE), 5, "a prize you didn't enter"),
        Triple(Regex("""(account|sim|line|wallet|mtn|airtel|zamtel|momo).{0,40}(blocked|suspended|deactivated|closed|locked)""", RegexOption.IGNORE_CASE), 4, "threatens to block your account"),
        Triple(Regex("""(share|send|tell|give).{0,25}\b(pin|otp|code)\b""", RegexOption.IGNORE_CASE), 4, "asks for your PIN or code; real providers never do"),
        Triple(Regex("""(dial|call)\s*\*\d{2,4}[*#\d]*""", RegexOption.IGNORE_CASE), 2, "tells you to dial a USSD code"),
        Triple(Regex("""(urgent|immediately|within \d+ (min|hour)|act now|last chance|today only)""", RegexOption.IGNORE_CASE), 1, "pushes you to hurry"),
        Triple(Regex("""(bit\.ly|tinyurl|cutt\.ly|rb\.gy|is\.gd|shorturl|tiny\.cc)""", RegexOption.IGNORE_CASE), 2, "a hidden short link"),
        Triple(Regex("""https?://\S*(-mtn|mtn-|airtel-|-airtel|zamtel-|momo-|bank-|-bank|verify|secure-|login-)\S*""", RegexOption.IGNORE_CASE), 4, "a look-alike website link"),
        Triple(Regex("""(job offer|work from home|earn k?\s?\d{3,}.{0,20}(daily|per day|a day)|investment.{0,30}(double|guaranteed|profit))""", RegexOption.IGNORE_CASE), 4, "a too-good-to-be-true money offer"),
        Triple(Regex("""(registration|processing|activation) fee""", RegexOption.IGNORE_CASE), 3, "asks for a fee up front"),
    )

    fun score(text: String): Verdict {
        var s = 0; val why = ArrayList<String>()
        for ((re, w, reason) in rules) if (re.containsMatchIn(text)) { s += w; why += reason }
        return Verdict(s, why.distinct())
    }

    /** From NotificationBrain (chat / SMS apps). Warns once per message. */
    fun check(ctx: Context, pkg: String, app: String, title: String, text: String) {
        if (!on || pkg !in chatApps || text.length < 20) return
        val v = score(text)
        if (!v.scam) return
        val key = (title + text).hashCode().toString()
        synchronized(recent) {
            if (recent.containsKey(key)) return
            recent[key] = System.currentTimeMillis(); while (recent.size > 50) recent.remove(recent.keys.first())
        }
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Scam Shield", NotificationManager.IMPORTANCE_HIGH))
            val body = "This $app message from ${title.ifBlank { "an unknown sender" }} looks like a scam: ${v.reasons.take(3).joinToString("; ")}. Don't send money, codes or PINs."
            nm.notify(4000 + (key.hashCode() and 0xff), NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat_blaze)
                .setContentTitle("Careful: likely scam").setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
        } catch (_: Throwable) { }
    }
}
