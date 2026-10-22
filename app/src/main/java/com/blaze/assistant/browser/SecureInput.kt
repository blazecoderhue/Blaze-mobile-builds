package com.blaze.assistant.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-chat secure forms for the background browser (v0.20.0).
 *
 * When a page needs something only the user has (email, phone, username + password, an API key,
 * a one-time code), Blaze shows a form card in the chat. The values go straight from the card into
 * the page. The model never sees them: it only ever handles placeholders like `{{secret:password}}`,
 * which the browser swaps for the real value at the moment it types. Values can be saved to the
 * vault (Android Keystore encrypted, per site) so the next sign-in needs no card.
 */
object SecureInput {

    enum class Kind(val id: String, val label: String, val masked: Boolean) {
        EMAIL("email", "Email", false),
        PHONE("phone", "Phone number", false),
        USERNAME("username", "Username", false),
        PASSWORD("password", "Password", true),
        API_KEY("api_key", "API key", true),
        OTP("otp", "Code", false),
        NUMBER("number", "Number", false),
        TEXT("text", "Text", false);

        /** Saved to the vault only for things that are worth reusing (not one-time codes). */
        val saveable get() = this != OTP
    }

    data class Field(val name: String, val label: String, val kind: Kind, val target: String = "")

    data class Answer(val values: Map<String, String>, val save: Boolean)

    /** A form waiting in the chat. [answer] completes with null when the user cancels. */
    data class Request(val id: Long, val site: String, val title: String, val reason: String, val fields: List<Field>,
                       val submit: Boolean, val answer: kotlinx.coroutines.CompletableDeferred<Answer?>)

    fun kindOf(raw: String, label: String = ""): Kind {
        val s = (raw + " " + label).lowercase()
        return when {
            Regex("pass ?word|passcode|\\bpwd\\b|\\bpass\\b|\\bpin\\b").containsMatchIn(s) -> Kind.PASSWORD
            Regex("otp|one.?time|2fa|two.?factor|verification|\\bcode\\b|totp|sms code").containsMatchIn(s) -> Kind.OTP
            Regex("api.?key|token|secret|\\bkey\\b").containsMatchIn(s) -> Kind.API_KEY
            Regex("e-?mail").containsMatchIn(s) -> Kind.EMAIL
            Regex("phone|mobile|\\btel\\b|msisdn").containsMatchIn(s) -> Kind.PHONE
            Regex("user ?name|login|account|handle|\\buser\\b").containsMatchIn(s) -> Kind.USERNAME
            Regex("number|amount|\\bage\\b").containsMatchIn(s) -> Kind.NUMBER
            else -> Kind.entries.firstOrNull { it.id == raw.lowercase() } ?: Kind.TEXT
        }
    }

    /** Card details are never collected this way (the user pays in the live browser view). */
    private val cardish = Regex("(?i)card ?number|credit ?card|debit ?card|\\bcvv\\b|\\bcvc\\b|security code|expir|\\biban\\b|\\bpan\\b")
    fun isCardField(f: Field) = cardish.containsMatchIn(f.name + " " + f.label)

    private fun safeName(s: String) = s.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').take(32)

    /** Fields from the tool call: [{name,label,kind,target}], or plain strings like "email". Names are made unique. */
    fun parseFields(a: JSONArray?): List<Field> {
        if (a == null) return emptyList()
        val used = HashSet<String>()
        return (0 until minOf(a.length(), 6)).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: a.optString(i).takeIf { it.isNotBlank() }?.let { JSONObject().put("name", it) } ?: return@mapNotNull null
            val label0 = o.optString("label").trim()
            val kind = kindOf(o.optString("kind").ifBlank { o.optString("type") }.ifBlank { o.optString("name") }, label0)
            var name = safeName(o.optString("name")).ifBlank { kind.id }
            var n = 2; val base = name
            while (!used.add(name)) name = base + "_" + n++
            val target = when (val t = o.opt("target") ?: o.opt("number")) { null, JSONObject.NULL -> ""; else -> t.toString() }
            Field(name, label0.ifBlank { kind.label }, kind, target)
        }
    }

    // ---------- placeholders ----------
    private val token = Regex("\\{\\{secret:([a-zA-Z0-9_]+)\\}\\}")
    fun placeholder(name: String) = "{{secret:$name}}"
    fun hasPlaceholder(s: String) = token.containsMatchIn(s)
    fun names(s: String): List<String> = token.findAll(s).map { it.groupValues[1] }.toList()

    /** Values given in this run, by name. Memory only; gone when Blaze restarts. */
    private val slots = ConcurrentHashMap<String, String>()
    /** Every value given this run (any site), so tool output can be scrubbed. */
    private val seen = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun put(name: String, value: String) { if (value.isEmpty()) return; slots[name] = value; if (value.length >= 4) seen.add(value) }
    fun clear() { slots.clear(); seen.clear() }
    /** v0.29.7: a value the browser typed or saw (a saved password) that must never reach the model in page text. */
    fun hide(value: String) { if (value.length >= 4) seen.add(value) }

    /**
     * Swaps placeholders for real values: this run's values first, then the vault for [host].
     * Returns null (and the missing names) when one isn't known, so nothing half-filled is typed.
     */
    fun resolve(text: String, host: String, vault: (String, String) -> String? = { h, n -> Vault.get(h, n) }): Pair<String?, List<String>> {
        val missing = ArrayList<String>()
        val out = token.replace(text) { m ->
            val n = m.groupValues[1]
            slots[n] ?: vault(host, n)?.also { put(n, it) } ?: run { missing += n; m.value }
        }
        return if (missing.isEmpty()) out to emptyList() else null to missing
    }

    /** Replaces any secret value that slipped into text (page text, an echo) with its placeholder or bullets. */
    fun scrub(text: String): String {
        if (seen.isEmpty() || text.isEmpty()) return text
        var t = text
        val byValue = slots.entries.associate { it.value to it.key }
        for (v in seen.sortedByDescending { it.length }) if (t.contains(v)) t = t.replace(v, byValue[v]?.let { placeholder(it) } ?: "••••")
        return t
    }

    fun hostOf(url: String): String = try {
        android.net.Uri.parse(url).host?.lowercase()?.removePrefix("www.").orEmpty()
    } catch (_: Throwable) { Regex("^[a-z]+://(?:www\\.)?([^/:?#]+)").find(url.lowercase())?.groupValues?.get(1).orEmpty() }

    /** The plain-text result the model gets after a form: names and placeholders only, never values. */
    fun summary(fields: List<Field>, saved: Boolean, fromVault: Boolean): String {
        val list = fields.joinToString(", ") { "${it.label} as ${placeholder(it.name)}" }
        return (if (fromVault) "Used the saved values for this site: " else "The user filled in: ") + list + "." +
            (if (saved) " Saved to Blaze's vault for this site." else "") +
            " You never see these values; reuse them with browser_type text=\"${placeholder(fields.first().name)}\" if a page asks again."
    }

    /** JS selector that finds the right page field for a kind when the model gave no target. */
    fun autoSelector(k: Kind): String = when (k) {
        Kind.PASSWORD -> "input[type=password]"
        Kind.EMAIL -> "input[type=email],input[autocomplete~=email],input[name*=email i],input[id*=email i],input[autocomplete~=username],input[name=login],input[name*=user i]"
        Kind.USERNAME -> "input[autocomplete~=username],input[name=login],input[name*=user i],input[id*=user i],input[id*=login i],input[type=email]"
        Kind.PHONE -> "input[type=tel],input[autocomplete~=tel],input[name*=phone i],input[id*=phone i]"
        Kind.OTP -> "input[autocomplete=one-time-code],input[name*=otp i],input[name*=code i],input[id*=otp i],input[id*=code i],input[inputmode=numeric]"
        Kind.API_KEY -> "input[name*=key i],input[name*=token i],input[id*=key i],input[id*=token i],textarea"
        Kind.NUMBER -> "input[type=number],input[inputmode=numeric],input[inputmode=decimal]"
        Kind.TEXT -> "input[type=text],input:not([type]),textarea"
    }
}

/**
 * Sites that won't take a password (GitHub's "This account does not support password sign-in",
 * passkey-only, Google-only accounts). Spotted in page text so Blaze offers the ways that work
 * instead of looping on the password form.
 */
object SignInHints {
    private val noPassword = Regex(
        "(?i)(does ?n[o']t support password|not support password sign.?in|password sign.?in (is )?(disabled|not (available|supported|allowed))|" +
            "can'?t sign in with (a|your) password|password login is disabled|passwords? (are|is) not (supported|enabled) for this account|" +
            "this account (uses|requires) (a )?(passkey|single sign.?on|sso|google)|sign in with your passkey instead|use a passkey to sign in|" +
            "passwordless (account|sign.?in)|no password (is )?set)")
    private val providers = Regex("(?i)(continue with|sign in with|log ?in with|use) (google|apple|microsoft|github|facebook|a passkey|passkey|email link|magic link|sso|single sign.?on)|passkey|security key")

    /** Element lines from browser_read that are other ways in. */
    fun options(items: List<String>): List<String> = items.filter { providers.containsMatchIn(it) && !it.contains("] field") }.take(4)

    fun blocked(text: String) = noPassword.containsMatchIn(text)

    /** A note for the model, or null when the page doesn't say passwords are refused. */
    fun check(text: String, items: List<String>): String? {
        if (!blocked(text)) return null
        val ways = options(items)
        return "SIGN-IN NOTE: this site says this account can't sign in with a password. Don't type the password again and don't ask for it. " +
            (if (ways.isNotEmpty()) "Ways in on this page: ${ways.joinToString("; ")}. " else "Look for \"Continue with Google\", a passkey or an email link. ") +
            "Ask the user with ask_user which one (for example: Continue with Google, Use a passkey, I'll do it myself in the browser), then click it. " +
            "Google's account picker or a passkey prompt may need the user's own tap: offer browser_show for that step."
    }
}

/**
 * Blaze's vault for website sign-ins: `filesDir/vault.json`, one entry per site and field, each value
 * encrypted with the Android Keystore key (Secrets). Excluded from cloud backup (the key can't leave
 * the phone anyway). Values are only ever handed to the browser, never to the model.
 */
object Vault {
    @Volatile var fileOverride: File? = null
    @Volatile var dir: File? = null
    /** Encrypt / decrypt; tests swap these for plain functions (no Keystore on the JVM). */
    var seal: (String) -> String = { com.blaze.assistant.agent.Secrets.encrypt(it) }
    var open: (String) -> String = { com.blaze.assistant.agent.Secrets.decrypt(it) }

    private fun file(): File? = fileOverride ?: dir?.let { File(it, "vault.json") }
    private val lock = Any()

    private fun data(): JSONObject = synchronized(lock) {
        try { file()?.takeIf { it.exists() }?.readText()?.let { JSONObject(it) } } catch (_: Exception) { null } ?: JSONObject()
    }
    private fun write(o: JSONObject) = synchronized(lock) { try { file()?.let { it.parentFile?.mkdirs(); it.writeText(o.toString()) } } catch (_: Exception) { } }

    fun get(host: String, name: String): String? {
        if (host.isBlank()) return null
        val v = data().optJSONObject(host)?.optString(name).orEmpty()
        return if (v.isEmpty()) null else open(v).ifEmpty { null }
    }

    fun has(host: String, names: List<String>) = names.isNotEmpty() && names.all { get(host, it) != null }

    fun save(host: String, name: String, value: String) {
        if (host.isBlank() || value.isEmpty()) return
        val d = data()
        val site = d.optJSONObject(host) ?: JSONObject()
        site.put(name, seal(value)); site.put("_saved", System.currentTimeMillis())
        d.put(host, site); write(d)
    }

    fun remove(host: String, name: String) {
        val d = data(); val site = d.optJSONObject(host) ?: return
        site.remove(name)
        if (site.keys().asSequence().none { !it.startsWith("_") }) d.remove(host) else d.put(host, site)
        write(d)
    }

    /** For the Saved logins page: "lu•••@gmail.com" for names and emails, dots for passwords and keys. Never logged. */
    fun masked(name: String, value: String): String {
        val k = SecureInput.kindOf(name)
        if (k.masked || value.length <= 3) return "•".repeat(value.length.coerceIn(6, 10))
        val at = value.indexOf('@')
        return if (at > 0) value.take(minOf(2, at)) + "•••" + value.substring(at) else value.take(2) + "•••" + value.takeLast(2)
    }

    fun forget(host: String): Boolean { val d = data(); val had = d.has(host); d.remove(host); write(d); return had }

    /** Sites with saved values and the field names (never the values). */
    fun sites(): List<Pair<String, List<String>>> {
        val d = data()
        return d.keys().asSequence().map { h -> h to (d.optJSONObject(h)?.keys()?.asSequence()?.filter { !it.startsWith("_") }?.toList() ?: emptyList()) }.toList()
    }
}
