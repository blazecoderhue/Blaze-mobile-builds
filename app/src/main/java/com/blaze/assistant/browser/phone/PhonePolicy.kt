package com.blaze.assistant.browser.phone

import com.blaze.assistant.browser.SiteDomain
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.45.19 Blaze Phone browser (features item 13; user 23 Oct 08:34: "Blaze uses its own phone the way an agent uses a
 * computer for the user, mainly browsing, logging in and booking, with a live view in chat and Take control / hand back;
 * no gaming, no banking"). Every decision the Blaze Phone browser makes, as pure Kotlin (PhoneBrowserTest,
 * PhoneToolsRoutingTest): sessions per site, the low-RAM config, frame throttling, idle release, the DOM snapshot with
 * masked secrets, the no-false-done verifier, which actions need the confirm card, and which asks route to phone tools.
 */
object PhonePolicy {

    // ------------------------------------------------------------------ modes
    /** OFF: nothing alive. AGENT: Blaze drives. WAITING: Blaze asked the user to take control. USER: the user drives. */
    enum class Mode { OFF, AGENT, WAITING, USER }

    // ------------------------------------------------------------------ sessions
    /** Blaze Phone's own WebView profiles (androidx.webkit MULTI_PROFILE): one per site, apart from the user's browser. */
    const val PROFILE_PREFIX = "blaze-phone-"
    /** Pages with no site (about:blank, data:) share this one. */
    const val SHARED_PROFILE = "blaze-phone"

    /**
     * Where a page's cookies and storage live. [profile] null = the WebView's default profile (phones whose WebView has no
     * multi-profile support: shared with the user's normal browser tabs, kept on disk across runs, never with private
     * tabs' wiped data). [isolated] says whether it is Blaze Phone's own.
     */
    data class Session(val site: String, val profile: String?, val isolated: Boolean)

    fun siteOf(url: String): String = SiteDomain.siteOfUrl(url.trim())

    /** A WebView profile name for a site: lowercase letters, digits, '-' and '_' only, bounded. */
    fun profileName(site: String): String {
        val s = site.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it == '-') it else '_' }.joinToString("").trim('_').take(60)
        return if (s.isEmpty()) SHARED_PROFILE else PROFILE_PREFIX + s
    }

    fun sessionFor(url: String, multiProfile: Boolean): Session {
        val site = siteOf(url)
        return if (!multiProfile) Session(site, null, false) else Session(site, profileName(site), true)
    }

    /**
     * Does opening [url] need a fresh WebView? A WebView's profile is fixed when it is made, so a different site (with
     * profiles) gets its own; the same site, or any site without profile support, reuses the live one.
     */
    fun needsNewWebView(current: Session?, url: String, multiProfile: Boolean): Boolean {
        if (current == null) return true
        if (!multiProfile) return false
        val next = sessionFor(url, true)
        if (next.site.isEmpty()) return false   // about:blank / data: stay where they are
        return next.profile != current.profile
    }

    /** The sites Blaze Phone keeps sessions for ("Sign out of site" lists these). Stored as a JSON array. */
    object Sites {
        fun decode(s: String?): List<String> = try {
            val a = JSONArray(s ?: "[]"); (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.distinct()
        } catch (_: Exception) { emptyList() }
        fun encode(l: List<String>): String = JSONArray().apply { l.distinct().forEach { put(it) } }.toString()
        fun add(s: String?, site: String): String = if (site.isBlank()) s ?: "[]" else encode(listOf(site) + decode(s).filter { it != site })
        fun remove(s: String?, site: String): String = encode(decode(s).filter { it != site })
    }

    // ------------------------------------------------------------------ memory / frames
    /**
     * Phones under ~4 GB. A "4 GB" phone reports about 3.6-3.8 GiB of total RAM, a "3 GB" one about 2.7-2.9 GiB, so the
     * line sits between them: 4 GB phones get the normal mode, 3 GB and less (or Android's low-RAM flag) the light one.
     */
    const val LOW_RAM_BYTES = 3_400_000_000L

    /**
     * [scale]: the off-screen display's pixels per screen pixel (the page's CSS width stays the phone's, only the buffer
     * shrinks). [idleFps] / [activeFps]: frames taken for the live view at most this often. [maxSessions]: live WebViews.
     */
    data class Config(val lowRam: Boolean, val scale: Float, val idleFps: Int, val activeFps: Int, val jpegQuality: Int,
                      val shotMaxWidth: Int, val maxImages: Int)

    fun config(totalMemBytes: Long, lowRamDevice: Boolean): Config =
        if (lowRamDevice || (totalMemBytes in 1 until LOW_RAM_BYTES)) Config(true, 0.5f, 1, 3, 55, 540, 2)
        else Config(false, 0.75f, 2, 5, 70, 720, 2)

    /** Milliseconds between live frames: at most [Config.activeFps] while something changes, [Config.idleFps] otherwise. */
    fun frameInterval(c: Config, changing: Boolean): Long = 1000L / (if (changing) c.activeFps else c.idleFps).coerceIn(1, 5)

    /** Lets a frame through at most every [intervalMs] (and only when the page drew something new). */
    class FrameGate {
        private var last = Long.MIN_VALUE / 2
        @Synchronized fun allow(now: Long, intervalMs: Long): Boolean {
            if (now - last < intervalMs) return false
            last = now; return true
        }
        @Synchronized fun reset() { last = Long.MIN_VALUE / 2 }
    }

    /** Off-screen display size for a phone screen [screenW]×[screenH] px at [densityDpi], times [scale]. */
    data class DisplaySize(val width: Int, val height: Int, val dpi: Int)

    fun displaySize(screenW: Int, screenH: Int, densityDpi: Int, scale: Float): DisplaySize {
        val w = screenW.coerceAtLeast(360); val h = screenH.coerceAtLeast(640)
        val s = scale.coerceIn(0.25f, 1f)
        // dpi scaled with the pixels, so CSS px (px / density) stay the same: the page lays out exactly like on the phone.
        return DisplaySize((w * s).toInt().coerceAtLeast(180), (h * s).toInt().coerceAtLeast(320), (densityDpi * s).toInt().coerceAtLeast(80))
    }

    // ------------------------------------------------------------------ idle release
    const val IDLE_MS = 5 * 60_000L

    /** Free the WebView, the display and its buffers? Never while the user drives or Blaze waits on them. */
    fun shouldRelease(mode: Mode, lastActiveAt: Long, now: Long, idleMs: Long = IDLE_MS): Boolean = when (mode) {
        Mode.OFF -> false
        Mode.USER, Mode.WAITING -> false
        Mode.AGENT -> now - lastActiveAt >= idleMs
    }

    // ------------------------------------------------------------------ DOM snapshot
    data class Node(val id: String, val role: String, val name: String, val value: String, val type: String = "",
                    val checked: Boolean? = null, val disabled: Boolean = false, val href: String = "",
                    val x: Int = 0, val y: Int = 0, val w: Int = 0, val h: Int = 0,
                    val sensitive: Boolean = false, val card: Boolean = false, val inView: Boolean = true)

    data class Snapshot(val title: String, val url: String, val text: String, val nodes: List<Node>,
                        val passwordVisible: Boolean, val headings: List<String> = emptyList(), val dialog: String? = null)

    private val sensitiveName = Regex("""(?i)(pass(word|wd|code)?|pwd|pin\b|secret|otp|one.?time|2fa|mfa|verification.?code|security.?code|cvv|cvc|csc|card.?(number|no)|cc-?(number|csc|exp)|iban|ssn|social.?security|account.?number|routing)""")
    private val cardName = Regex("""(?i)(card.?(number|no)|cc-?(number|csc)|cvv|cvc|csc|security.?code|expir|cc-exp)""")
    const val HIDDEN = "(filled, hidden)"

    /** Is this field secret (passwords, codes, card data)? Belt and braces with the page script's own check. */
    fun isSensitive(type: String, name: String, autocomplete: String = "", marked: Boolean = false): Boolean =
        marked || type.equals("password", true) || autocomplete.startsWith("cc-", true) || autocomplete.contains("one-time-code", true) ||
            autocomplete.contains("password", true) || sensitiveName.containsMatchIn(name)

    fun isCard(name: String, autocomplete: String = ""): Boolean = autocomplete.startsWith("cc-", true) || cardName.containsMatchIn(name)

    fun mask(n: Node): Node = if (!n.sensitive) n else n.copy(value = if (n.value.isEmpty() || n.value == HIDDEN) n.value else HIDDEN)

    /** Parses the page script's JSON (see PhoneScripts.SNAPSHOT) and masks every secret value. */
    fun parseSnapshot(o: JSONObject, scrub: (String) -> String = { it }): Snapshot {
        val a = o.optJSONArray("nodes") ?: JSONArray()
        val nodes = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { j ->
            val type = j.optString("type"); val name = j.optString("name"); val ac = j.optString("ac")
            val key = name + " " + j.optString("key")
            val sens = j.optBoolean("sens") || isSensitive(type, key, ac, j.optBoolean("marked"))
            mask(Node(j.optString("id"), j.optString("role"), scrub(name.take(100)), scrub(j.optString("value").take(100)), type,
                if (j.has("checked")) j.optBoolean("checked") else null, j.optBoolean("disabled"), j.optString("href").take(120),
                j.optInt("x"), j.optInt("y"), j.optInt("w"), j.optInt("h"), sens, isCard(key, ac), j.optBoolean("inView", true)))
        }
        val hs = o.optJSONArray("headings")?.let { h -> (0 until h.length()).map { scrub(h.optString(it).take(120)) } } ?: emptyList()
        return Snapshot(o.optString("title"), o.optString("url"), scrub(o.optString("text")), nodes, o.optBoolean("pw"), hs,
            o.optString("dialog").ifBlank { null })
    }

    /** The snapshot as the agent reads it: page, verification, headings, elements with ids, then the text. */
    fun render(s: Snapshot, maxText: Int = 4000, maxNodes: Int = 120): String {
        val sb = StringBuilder()
        sb.append("Page: ").append(s.title.take(100)).append(" \u2014 ").append(s.url.take(200)).append('\n')
        sb.append(verify(s).line()).append('\n')
        if (s.dialog != null) sb.append("Dialog: ").append(s.dialog.take(200)).append('\n')
        if (s.headings.isNotEmpty()) sb.append("Headings: ").append(s.headings.take(8).joinToString(" | ")).append('\n')
        sb.append("\nElements (use the id with phone_act):\n")
        s.nodes.take(maxNodes).forEach { sb.append(line(it)).append('\n') }
        if (s.nodes.size > maxNodes) sb.append("(+${s.nodes.size - maxNodes} more; scroll or phone_wait for a selector)\n")
        sb.append("\nText:\n").append(s.text.take(maxText))
        return sb.toString()
    }

    fun line(n: Node): String {
        val sb = StringBuilder("[").append(n.id).append("] ").append(n.role)
        if (n.type.isNotBlank() && n.role in setOf("textbox", "input")) sb.append(':').append(n.type)
        sb.append(" \"").append(n.name.replace('\n', ' ').take(80)).append('"')
        if (n.value.isNotEmpty()) sb.append(" = \"").append(n.value.take(60)).append('"')
        n.checked?.let { sb.append(if (it) " (checked)" else " (unchecked)") }
        if (n.disabled) sb.append(" (disabled)")
        if (n.card) sb.append(" (card field: the user fills it)")
        if (!n.inView) sb.append(" (off screen)")
        if (n.href.isNotBlank() && n.role == "link") sb.append(" \u2192 ").append(n.href.take(80))
        return sb.toString()
    }

    // ------------------------------------------------------------------ no false done
    /**
     * What the page proves. [confirmed]: the page shows a booking / order / reservation confirmation (a reference number,
     * or explicit confirmation text) and no error. [loggedIn]: a sign-out / account control is there and no password
     * field. [ref]: the reference found. [evidence]: the words it was read from. [blocker]: an error or a hold on the page.
     */
    data class Verdict(val confirmed: Boolean, val ref: String?, val evidence: String, val loggedIn: Boolean, val blocker: String?) {
        fun line(): String = when {
            confirmed -> "VERIFIED: the page shows \u201c${evidence.take(120)}\u201d" + (ref?.let { " (reference $it)" } ?: "") + "."
            blocker != null -> "NOT VERIFIED: the page shows a problem: \u201c${blocker.take(120)}\u201d. Nothing is confirmed; tell the user what it says."
            loggedIn -> "SIGNED IN: the page has an account / sign-out control and no password field. No booking or order is confirmed on this page."
            else -> "NOT VERIFIED: no confirmation number or confirmation text on this page. Don't say it's done, booked or confirmed; say what the page shows."
        }
    }

    private val refRe = Regex("""(?i)\b(?:booking|reservation|order|confirmation|reference|ticket|transaction|itinerary|record locator|pnr)\s*(?:number|no\.?|#|code|id|ref(?:erence)?)?\s*(?:is|:|#)?\s*#?\s*([A-Z0-9][A-Z0-9-]{3,19})\b""")
    private val confirmRe = Regex("""(?i)\b(?:(?:booking|reservation|order|appointment|purchase|payment|table|ticket)s?\s+(?:is\s+|has\s+been\s+|was\s+)?(?:confirmed|complete[d]?|placed|successful|received|booked)|(?:you(?:'re| are)|you have been|it's|we've got you)\s+(?:all\s+)?(?:booked|confirmed|set|reserved)|thank(?:s| you)\s+for\s+(?:your\s+)?(?:order|booking|reservation|purchase|payment)|(?:successfully|been)\s+(?:booked|reserved|placed|confirmed|purchased|paid)|confirmation\s+(?:number|code|#|email has been sent|sent))\b""")
    private val errorRe = Regex("""(?i)\b(?:error|failed|failure|declined|could not (?:be )?(?:complete|process|book|confirm)[a-z]*|couldn'?t (?:complete|process|book|confirm)|unable to (?:complete|process|book|confirm)|try again|sold out|no longer available|not available|invalid|incorrect|expired|is required|please (?:review|correct|fix)|something went wrong|not (?:yet |been )?confirmed|awaiting (?:payment|confirmation)|pending (?:payment|confirmation))\b""")
    private val logoutRe = Regex("""(?i)\b(sign ?out|log ?out|my account|your account|account settings|my profile|my bookings|my orders|my trips)\b""")
    private val badRef = setOf("NUMBER", "CODE", "DETAILS", "EMAIL", "SUMMARY", "STATUS", "HISTORY", "CONFIRMED", "PENDING", "TOTAL", "FORM", "PAGE")

    fun verify(s: Snapshot): Verdict = verify(s.text + "\n" + s.headings.joinToString("\n"), s.title, s.nodes, s.passwordVisible)

    fun verify(text0: String, title: String = "", nodes: List<Node> = emptyList(), passwordVisible: Boolean = false): Verdict {
        val text = (title + "\n" + text0).replace(Regex("[ \\t]+"), " ")
        val err = errorRe.find(text)?.let { sentence(text, it.range.first) }
        val conf = confirmRe.find(text)
        val ref = refRe.findAll(text).map { it.groupValues[1] }.firstOrNull { r -> r.any { it.isDigit() } && r.uppercase() !in badRef }
        val loggedIn = !passwordVisible && (nodes.any { logoutRe.containsMatchIn(it.name) } || nodes.isEmpty() && logoutRe.containsMatchIn(text0) && !Regex("(?i)\\b(sign|log) ?in\\b").containsMatchIn(text0))
        // An error on the page wins over any confirmation words ("your booking could not be confirmed").
        if (err != null) return Verdict(false, null, "", loggedIn, err)
        if (conf != null) return Verdict(true, ref, sentence(text, conf.range.first), loggedIn, null)
        // A bare reference with no confirmation words isn't proof (an order *form* has "order number" fields too).
        return Verdict(false, null, "", loggedIn, null)
    }

    private fun sentence(text: String, at: Int): String {
        val start = text.lastIndexOfAny(charArrayOf('\n', '.', '!', '?'), (at - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOfAny(charArrayOf('\n', '.', '!', '?'), at).let { if (it < 0) text.length else it }
        return text.substring(start, end.coerceAtLeast(start)).trim().take(160)
    }

    // ------------------------------------------------------------------ the confirm gate
    enum class Gate { PASS, CONFIRM, REFUSE }

    private val irreversible = Regex("""(?i)\b(pay|payment|buy|purchase|order|place order|checkout|check out|book|book now|reserve|confirm|complete (?:booking|purchase|order|reservation)|submit|send|post|publish|transfer|delete|remove|cancel (?:booking|reservation|order|subscription)|subscribe|donate|sign up|register|create account|agree|accept|apply)\b""")

    /** On a link (which only navigates) only these still ask: money, final confirmation, sending, deleting. */
    private val strongIrreversible = Regex("""(?i)\b(pay|payment|purchase|buy now|place order|checkout|check out|confirm|complete (?:booking|purchase|order|reservation)|submit|send|transfer|delete|donate)\b""")

    /** A label that pays, books, sends, submits or deletes something. */
    fun irreversible(label: String): Boolean = irreversible.containsMatchIn(label)

    /**
     * Does this action need the user's OK? Clicks / Enter / select on something irreversible ([irreversible] label, or a
     * submit on a page with card fields) show the confirm card every time (ConfirmGate always = true). Typing into a card
     * field is refused: card numbers are the user's to type (Take control). Reading, scrolling, navigating pass.
     */
    fun gate(action: String, label: String, target: Node? = null, pageHasCard: Boolean = false, isSubmit: Boolean = false): Gate {
        val a = action.lowercase()
        return when (a) {
            "type", "fill" -> if (target?.card == true) Gate.REFUSE else if (isSubmit && (pageHasCard || irreversible(label))) Gate.CONFIRM else Gate.PASS
            // A link ("Book a table", "Order online") opens a page; the real submit is a button, which asks.
            "click", "tap", "select", "check" -> if (target != null && target.role == "link") { if (strongIrreversible.containsMatchIn(label)) Gate.CONFIRM else Gate.PASS }
                else if (irreversible(label) || (target != null && target.role == "button" && target.type.equals("submit", true) && pageHasCard)) Gate.CONFIRM else Gate.PASS
            "key" -> if (label.equals("enter", true) && (pageHasCard || isSubmit)) Gate.CONFIRM else Gate.PASS
            else -> Gate.PASS
        }
    }

    // ------------------------------------------------------------------ routing
    private val siteCue = """(?:\s+(?:at|on|with|from|via|through|in|into|to)\s+\S|\s+\S+\.(?:com|org|net|io|co|zm|app|dev|me|uk|za)\b)"""
    private val ask = Regex("""(?i)\b(?:(?:book|reserve)\s+(?:me\s+)?(?:a|an|the|my|two|\d+)?\s*(?:table|room|hotel|flight|ticket|tickets|seat|seats|slot|stay|ride|car|class|session|spot|reservation|appointment)\b.{0,80}?$siteCue|(?:log|sign)\s*(?:me\s+)?(?:in|on)(?:to)?\s+(?:to\s+)?(?:my\s+)?\S+|sign\s+(?:me\s+)?up\s+(?:for|on|at)\s+\S+|(?:order|buy|purchase)\s+.{1,60}?\s+(?:on|from)\s+\S+\.(?:com|org|net|io|co|zm|app|shop|store)\b|fill\s+(?:out|in)\s+(?:the|a|this|that)\s+form\s+(?:on|at)\s+\S+|(?:check\s*out|checkout)\s+(?:on|at)\s+\S+|(?:use|on)\s+(?:your|blaze'?s?)\s+(?:own\s+)?phone\b|blaze phone\b)""")
    private val notPhone = Regex("""(?i)\b(bank|banking|mobile money|momo|wire|transfer money|send money|crypto|wallet|game|gaming|play (?:a )?game|calendar|blaze account|wi-?fi|router|hotspot|the linux|linux desktop|my computer|my pc)\b""")

    /** "book a table at X", "log into Y", "sign up for Z", "order … from shop.com": a job for the Blaze Phone browser. */
    fun phoneAsk(text: String): Boolean {
        val u = text.trim().replace('\u2019', '\'')
        return ask.containsMatchIn(u) && !notPhone.containsMatchIn(u)
    }

    /** Tool names (BrowserTools-style registry). */
    val TOOLS = setOf("phone_open", "phone_read", "phone_act", "phone_screenshot", "phone_wait", "phone_handoff", "phone_status")
    val ACTIONS = setOf("click", "type", "key", "scroll", "select", "navigate", "back", "forward", "reload", "fill_login")
    val KEYS = setOf("enter", "back", "tab", "escape", "backspace", "space", "up", "down", "left", "right", "pageup", "pagedown")

    /** Bounds a wait: 0.5–30 s, default 10. */
    fun waitMs(timeoutSec: Double?): Long = ((timeoutSec ?: 10.0) * 1000).toLong().coerceIn(500, 30_000)
}
