package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.45.19: a final reply that asks the user to type a login into the chat while a page is open in Blaze's browser.
 * Seen in the live gateway replay: glm-4.5-flash "Could you please provide your username and password?" and
 * kilo-auto/free after the form "drop your username and password here". Credentials never go through the chat: the
 * agent shows the secure form (browser_secure_input) instead, once per turn, inside the existing per-site limits
 * (3 asks, none after a cancel). Card numbers are never asked for (SecureInput keeps that rule). Pure Kotlin;
 * SecureFormRenderTest.
 */
object CredAsk {
    private const val NOUN = """(?:e-?mail(?:\s+address)?(?!\s+(?:draft|message|inbox|thread|reply|replies|to\b|from\b|list|newsletter))|user\s?name|login(?:\s+details)?|password|passcode|pass\s?word|pin\b|credentials|sign[- ]in details)"""
    private const val VERB = """(?:provide|send|share|give|type|enter|drop|paste|post|reply with|tell me|let me know|what(?:'s| is| are)|need|could i (?:get|have)|can i (?:get|have))"""
    // "provide your username and password", "I need your email and password", "drop your username … here".
    private val ask = Regex("""(?i)\b$VERB\b[^.!?\n]{0,40}?\b(?:your|the|those|these|ur)\b[^.!?\n]{0,30}?\b$NOUN""")
    // "What's your email?" / "Your password?" as a question.
    private val question = Regex("""(?i)\b(?:your|ur)\s+$NOUN[^.!?\n]{0,30}\?""")
    // Talk that is about the secure form or a refusal, not an ask to type it here.
    private val notAsk = Regex("""(?i)(secure form|form (?:above|below)|in the form|on the form|never (?:share|send|type)|don'?t (?:share|send|type)|won'?t ask|saved (?:login|password)|reset (?:your|the) password|forgot)""")
    private val card = Regex("""(?i)\b(card number|credit card|debit card|cvv|cvc|security code|expiry|expiration)\b""")

    /** True when [reply] asks the user to type or send a login in the chat. */
    fun asks(reply: String): Boolean {
        val t = reply.trim()
        if (t.isEmpty() || t.length > 600) return false
        if (notAsk.containsMatchIn(t) || card.containsMatchIn(t)) return false
        return ask.containsMatchIn(t) || question.containsMatchIn(t)
    }

    /** The fields the reply asked for: email or username, plus password (or PIN / passcode). [userText] breaks a tie. */
    fun fields(reply: String, userText: String = ""): JSONArray {
        val r = reply.lowercase(); val u = userText.lowercase()
        val f = JSONArray()
        val email = Regex("""e-?mail""").containsMatchIn(r)
        val user = Regex("""user\s?name|\blogin\b""").containsMatchIn(r)
        val pass = Regex("""pass\s?word|credentials|login details|sign[- ]in details|\blogin\b""").containsMatchIn(r)
        val pin = Regex("""\bpin\b|passcode""").containsMatchIn(r) && !Regex("""pass\s?word""").containsMatchIn(r)
        when {
            email -> f.put(JSONObject().put("name", "email").put("kind", "email"))
            user -> f.put(JSONObject().put("name", "username").put("kind", "username"))
            pin -> {}   // a PIN / passcode alone: just that
            Regex("""e-?mail""").containsMatchIn(u) -> f.put(JSONObject().put("name", "email").put("kind", "email"))
            else -> f.put(JSONObject().put("name", "username").put("kind", "username"))
        }
        if (pin) f.put(JSONObject().put("name", "pin").put("label", if (r.contains("passcode")) "Passcode" else "PIN").put("kind", "password"))
        else if (pass || !(email || user)) f.put(JSONObject().put("name", "password").put("kind", "password"))
        return f
    }

    /**
     * The browser_secure_input call to run instead of showing [reply], or null to show it. [host] = the page open in
     * Blaze's browser ("" = none); [asks] = secure forms already asked on that host this session (99 after a cancel);
     * [guarded] = this turn already swapped one reply; [known] = the tools this request may call.
     */
    fun decide(reply: String, host: String, asks: Int, guarded: Boolean, known: Collection<String>, userText: String = ""): JSONObject? {
        if (guarded || host.isBlank() || asks >= 3 || CallScan.SECURE !in known) return null
        if (!asks(reply)) return null
        val args = JSONObject().put("fields", fields(reply, userText)).put("title", "Sign in to $host").put("reason", "So I can sign you in without the details going through the chat.")
        return JSONObject().put("id", "credask_0").put("type", "function")
            .put("function", JSONObject().put("name", CallScan.SECURE).put("arguments", args.toString()))
    }
}
