package com.blaze.assistant.notify

/**
 * OTP and bank-code filter (v0.19.0). A message that looks like a one-time code, a login or verification code,
 * a PIN, or a bank / mobile-money alert is never stored: not in the conversation log, not in the journal and
 * so never in memory. Blaze only sees "(a code or bank message, hidden)". Pure, unit-tested (ConversationsTest).
 */
object Sensitive {
    /** On their own these always mean a code. */
    private val strong = Regex(
        "\\b(otp|one[- ]?time (?:password|pin|code|passcode)|verification code|verify code|security code|confirmation code|" +
            "auth(?:entication)? code|login code|log-?in code|sign-?in code|passcode|2fa|two[- ]factor|do not share|don'?t share|never share|" +
            "pin code|your pin|reset code|access code)\\b", RegexOption.IGNORE_CASE)

    /** Bank and mobile-money alerts: balances, debits, credits, transfers. Hidden even without a code. */
    private val bank = Regex(
        "\\b(debited|credited|available balance|avail\\.? bal|acc(?:ount)? bal(?:ance)?|your balance|new balance|transaction (?:id|ref|reference)|" +
            "txn|card ending|ending in \\d{3,4}|withdrawal|withdrawn|cash ?out|mobile money|airtel money|mtn ?momo|momo|zamtel kwacha|" +
            "deposit(?:ed)? (?:of|into)|transfer(?:red)? (?:of|to)|you have (?:received|sent) (?:k|zmw|usd|\\$)|amount:? ?(?:k|zmw|usd|\\$) ?\\d)\\b",
        RegexOption.IGNORE_CASE)

    /** A code-like token: 4-8 digits, maybe split "123-456" / "123 456", or "G-123456". */
    private val codeToken = Regex("(?<![\\d.,:])(?:[A-Z]{1,3}-)?\\d{3,4}[- ]?\\d{2,4}(?![\\d.,:])|(?<![\\d.,:])\\d{4,8}(?![\\d.,:])")

    /** Words that make a code-like token a code. */
    private val codeWords = Regex("\\b(code|pin|verify|verification|confirm|login|log in|sign in|signin|password|bank|card|account|acct|token|key)\\b",
        RegexOption.IGNORE_CASE)

    fun isSensitive(text: String?, title: String? = null): Boolean {
        val t = listOfNotNull(title, text).joinToString(" ")
        if (t.isBlank()) return false
        if (strong.containsMatchIn(t) || bank.containsMatchIn(t)) return true
        return codeToken.containsMatchIn(t) && codeWords.containsMatchIn(t)
    }

    const val HIDDEN = "(a code or bank message, hidden)"
}
