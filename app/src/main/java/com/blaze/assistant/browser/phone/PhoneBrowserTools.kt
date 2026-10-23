package com.blaze.assistant.browser.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.45.19 Blaze Phone browser: the agent's tools. Blaze uses its own phone the way an agent uses a computer for the
 * user: browsing, logging in, booking, with a live view in the chat and Take control / Hand back.
 *
 *  - Anything that pays, books, sends, submits or deletes asks first (the confirm card, ConfirmGate always = true).
 *  - Card numbers are never typed by Blaze: the user takes control for them.
 *  - Done means verified: every page read carries a VERIFIED / NOT VERIFIED line ([PhonePolicy.verify]); the tool text
 *    tells the agent never to say booked / confirmed without it.
 *
 * Pure of Android (it drives a [PhoneDriver]), so the JVM sims run the exact tool code.
 */
object PhoneBrowserTools {
    val names = PhonePolicy.TOOLS

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)
    private fun e(d: String, vararg v: String) = JSONObject().put("type", "string").put("description", d).put("enum", JSONArray(v.toList()))

    const val RULE = "Blaze Phone is Blaze's own phone screen: a real browser that keeps the user's sign-ins per site and runs in the background " +
        "while they use other apps (they can watch it live in the chat and take control). Use it to log in, book, order, fill forms and check accounts on websites. " +
        "Never say something is booked, ordered, paid or confirmed unless the page read says VERIFIED; otherwise say exactly what the page shows. " +
        "Users never give web addresses: pass what they called the site to phone_open as they said it ('the booking site', 'my bank', 'herokuapp login page', " +
        "'the-internet login'); Blaze picks the site (ones they used before first, then a search that skips ads and look-alike sites, official domain) and the " +
        "sub-page (e.g. the login form). If it says it can't tell which site, ask the user; never guess or invent a URL. " +
        "No games; on banking sites open and read only, never move money."

    fun definitions(): List<JSONObject> = listOf(
        fn("phone_open", "$RULE Opens a site on Blaze Phone: a URL, or what the user called it (resolved for you), and returns the page: a VERIFIED/NOT VERIFIED line, " +
            "elements with ids like [e12], and the text.",
            JSONObject().put("url", p("string", "A URL, or the site as the user named it: 'the booking site', 'my bank', 'github login'")), listOf("url")),
        fn("phone_read", "Reads the Blaze Phone page again: verification line, interactive elements with stable ids (password and card values hidden), headings and text. " +
            "Call it before saying anything is done."),
        fn("phone_act", "Acts on the Blaze Phone page with real taps and keys. action: click (target), type (target + text; submit=true presses Enter), " +
            "key (key: enter/tab/back/escape/backspace/up/down…), scroll (direction), select (target + option), navigate (text = URL), back, forward, reload, " +
            "fill_login (fills this site's saved login, not submitted; text = which username if several). target = an element id from phone_read (e.g. e12) or its visible words. " +
            "Typing {{secret:name}} uses a value the user saved, never shown to you. Buttons that pay, book, send, submit or delete ask the user first. " +
            "Card numbers: never type them; use phone_handoff. 2FA codes: ask the user with ask_user, then type them.",
            JSONObject().put("action", e("What to do", *PhonePolicy.ACTIONS.toTypedArray()))
                .put("target", p("string", "Element id from phone_read (e12) or its visible words"))
                .put("text", p("string", "Text to type, URL for navigate, username for fill_login"))
                .put("option", p("string", "Option text for select"))
                .put("key", e("Key for action=key", *PhonePolicy.KEYS.toTypedArray()))
                .put("direction", e("Scroll direction", "down", "up"))
                .put("submit", p("boolean", "After typing, press Enter"))
                .put("clear", p("boolean", "Replace what's in the field (default true)")), listOf("action")),
        fn("phone_screenshot", "A picture of Blaze Phone's screen, attached for you to look at (use it to check a confirmation page, a captcha, or a layout the read doesn't explain)."),
        fn("phone_wait", "Waits on Blaze Phone until a CSS selector is visible, some text appears, or (gone=true) disappears; with neither, until the page loads. Timeout 0.5-30 s (default 10).",
            JSONObject().put("selector", p("string", "CSS selector")).put("text", p("string", "Text to wait for"))
                .put("gone", p("boolean", "Wait for it to go away")).put("timeout", p("number", "Seconds"))),
        fn("phone_handoff", "Asks the user to take control of Blaze Phone (a captcha, their own password, a card form, an approval only they can give) and waits until they hand back " +
            "(up to 10 minutes), then returns a fresh read. They see the request in the chat's live view (and a notification if they're away).",
            JSONObject().put("reason", p("string", "One short line the user sees, e.g. 'Solve the captcha', 'Enter your card details'")), listOf("reason")),
        fn("phone_status", "What Blaze Phone is doing: who is in control, the page, the site session, live view and memory."),
    )

    fun describe(name: String, a: JSONObject): String = when (name) {
        "phone_open" -> "Opening ${hostish(a.optString("url"))} on Blaze Phone"
        "phone_read" -> "Reading Blaze Phone's page"
        "phone_act" -> when (a.optString("action")) {
            // Never a raw element id ("e12") or call text in the chat's step line; the live tile gets the element's label.
            "click" -> a.optString("target").trim().let { t -> if (t.isEmpty() || Regex("^\\[?e\\d+\\]?$").matches(t)) "Tapping on Blaze Phone" else "Tapping \u201c${t.take(40)}\u201d" }
            "type" -> "Typing on Blaze Phone"
            "key" -> "Pressing ${a.optString("key").ifBlank { "a key" }}"
            "scroll" -> "Scrolling Blaze Phone"
            "select" -> "Choosing ${a.optString("option").take(40)}"
            "navigate" -> "Going to ${hostish(a.optString("text"))}"
            "fill_login" -> "Filling your saved login"
            else -> "Working on Blaze Phone"
        }
        "phone_screenshot" -> "Looking at Blaze Phone"
        "phone_wait" -> "Waiting for the page"
        "phone_handoff" -> "Asking you to take control"
        else -> "Checking Blaze Phone"
    }

    private fun hostish(u: String) = u.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/').take(40)

    /** The user's answer to a confirm card: true = go ahead. */
    // (a plain suspend function type: confirm(title, detail, action))

    private const val NO_PAGE = "Blaze Phone has no page open. Use phone_open first."

    suspend fun run(d: PhoneDriver, name: String, a: JSONObject, shots: MutableList<String>, confirm: suspend (String, String, String) -> Boolean): String {
        // 0.45.19-speed: timings for phone_status (the user's approval time on a confirm card isn't Blaze's).
        val t0 = d.now()
        var waited = 0L
        val timedConfirm: suspend (String, String, String) -> Boolean = { t, de, ac -> val c0 = d.now(); try { confirm(t, de, ac) } finally { waited += d.now() - c0 } }
        val out = runInner(d, name, a, shots, timedConfirm)
        when (name) {
            "phone_read" -> PhonePerf.add(PhonePerf.READ, d.now() - t0)
            "phone_act" -> PhonePerf.add(PhonePerf.ACT, d.now() - t0 - waited)
        }
        return out
    }

    private suspend fun runInner(d: PhoneDriver, name: String, a: JSONObject, shots: MutableList<String>, confirm: suspend (String, String, String) -> Boolean): String = try {
        d.step(describe(name, a))
        when (name) {
            "phone_status" -> d.status()
            "phone_open" -> {
                val url = a.optString("url").trim()
                if (url.isEmpty()) "Give a URL or the site's name." else if (SiteResolver.isUrlish(url)) d.open(url) else openByName(d, url)
            }
            "phone_read" -> if (!d.hasPage()) NO_PAGE else if (!d.awaitAgentTurn()) USER_HAS_IT else d.snapshot()?.let { PhonePolicy.render(it) } ?: readFallback(d, shots)
            "phone_screenshot" -> if (!d.hasPage()) NO_PAGE else {
                val shot = d.screenshot()
                val v = d.snapshot()?.let { PhonePolicy.verify(it).line() }.orEmpty()
                if (shot == null) "No picture on this phone (Blaze Phone runs offscreen here). Use phone_read. $v".trim()
                else { shots += shot; "Screenshot of Blaze Phone taken; it is attached in the next message. $v".trim() }
            }
            "phone_wait" -> if (!d.hasPage()) NO_PAGE else d.waitFor(a.optString("selector").ifBlank { null }, a.optString("text").ifBlank { null },
                a.optBoolean("gone", false), PhonePolicy.waitMs(if (a.has("timeout")) a.optDouble("timeout") else null))
            "phone_handoff" -> d.handoff(a.optString("reason").ifBlank { "Blaze needs you for a step" })
            "phone_act" -> act(d, a, confirm)
            else -> "Unknown Blaze Phone tool."
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Blaze Phone error: ${com.blaze.assistant.browser.SecureInput.scrub(e.message.orEmpty().take(200))}" }

    /** The DOM couldn't be read: only then a screenshot (never as a substitute for verification). */
    private suspend fun readFallback(d: PhoneDriver, shots: MutableList<String>): String {
        val shot = d.screenshot()
        return if (shot != null) { shots += shot
            "The page's elements couldn't be read right now (still loading, or the page blocks it); a screenshot of Blaze Phone is attached in the next message. " +
                "NOT VERIFIED: nothing counts as done from a picture; phone_wait, then phone_read." }
        else "Couldn't read this page (still loading?). phone_wait, then phone_read."
    }

    /**
     * 0.45.19-speed: phone_open with what the user called a site ([SiteResolver]): the resolved page, then the sub-page
     * the words asked for (tapping the site's own "Log in" / "Sign up" / "Orders" link when the search had no deep link).
     */
    private suspend fun openByName(d: PhoneDriver, q: String): String {
        val r = d.resolve(q)
        val url = r.url ?: return "Couldn't tell which site \u201c${q.take(60)}\u201d is. ${r.ask ?: "Ask the user which site they mean."}"
        d.step("Opening ${hostish(url)}")
        val opened = d.open(url)
        val head = "\u201c${q.take(60)}\u201d \u2192 $url (${r.why})."
        val sub = r.subpage ?: return "$head\n$opened"
        val s = d.snapshot() ?: return "$head\n$opened"
        if (SiteResolver.onSubpage(sub, s.url, s.passwordVisible)) return "$head\n$opened"
        val re = SiteResolver.linkRe(sub) ?: return "$head\n$opened"
        val node = s.nodes.filter { (it.role == "link" || it.role == "button") && !it.disabled && re.containsMatchIn(it.name) }.minByOrNull { it.name.length }
            ?: return "$head There's no $sub link on this page; look for it with phone_read.\n$opened"
        val t = d.locate(node.id, false) ?: return "$head\n$opened"
        if (PhonePolicy.gate("click", t.label, t.node(), false) != PhonePolicy.Gate.PASS)
            return "$head \u201c${t.label.take(40)}\u201d may submit something; tap it with phone_act only if that's what the user wants.\n$opened"
        d.step("Tapping \u201c${t.label.take(40)}\u201d")
        val c = d.click(t)
        val now = d.snapshot()?.let { PhonePolicy.render(it) } ?: "Couldn't read the page yet; phone_read."
        return "$head Went to the $sub page (tapped \u201c${t.label.take(40)}\u201d). ${c.text}\n\nNow:\n$now"
    }

    private const val USER_HAS_IT = "The user is in control of Blaze Phone and hasn't handed back. Don't act; tell them you'll continue when they tap Hand back."

    private fun refused(label: String) = "The user did NOT approve \u201c${label.take(50)}\u201d. Don't retry; tell them it's ready for them to finish (phone_handoff)."

    private suspend fun after(d: PhoneDriver, r: PhoneDriver.ActResult, pageChange: Boolean): String {
        if (!r.ok || !pageChange) return r.text
        val s = d.snapshot() ?: return r.text
        return r.text + "\n\nNow:\n" + PhonePolicy.render(s, maxText = 1500, maxNodes = 60)
    }

    private suspend fun act(d: PhoneDriver, a: JSONObject, confirm: suspend (String, String, String) -> Boolean): String {
        val action = a.optString("action").lowercase()
        if (action !in PhonePolicy.ACTIONS) return "Unknown action \u201c$action\u201d. Use one of: ${PhonePolicy.ACTIONS.joinToString()}."
        if (action == "navigate") return a.optString("text").ifBlank { a.optString("target") }.let { if (it.isBlank()) "navigate needs text = the URL." else d.open(it) }
        if (!d.hasPage()) return NO_PAGE
        if (!d.awaitAgentTurn()) return USER_HAS_IT
        val site = d.site().ifBlank { "this site" }
        val target = a.optString("target").trim()
        return when (action) {
            "click" -> {
                if (target.isEmpty()) return "click needs a target (an id like e12, or its words)."
                val t = d.locate(target, false) ?: return "No element \u201c${target.take(40)}\u201d on the page now. phone_read for fresh ids."
                d.step("Tapping \u201c${t.label.take(40).ifBlank { "an element" }}\u201d")
                val card = d.focusInfo().card
                when (PhonePolicy.gate("click", t.label, t.node(), card)) {
                    PhonePolicy.Gate.CONFIRM -> if (!confirm("Tap \u201c${t.label.take(40)}\u201d on $site?", "On Blaze Phone. This looks like it books, pays, sends or submits something.", "Tap")) return refused(t.label)
                    PhonePolicy.Gate.REFUSE -> return "Blaze doesn't press that. Use phone_handoff."
                    PhonePolicy.Gate.PASS -> {}
                }
                after(d, d.click(t), true)
            }
            "type" -> {
                val text = a.optString("text")
                if (text.isEmpty()) return "type needs text."
                val t = (if (target.isNotEmpty()) d.locate(target, true) else d.locate(":focus", true))
                    ?: return "No field \u201c${target.take(40)}\u201d. phone_read for fresh ids."
                d.step("Typing into \u201c${t.label.take(40).ifBlank { "a field" }}\u201d")
                val submit = a.optBoolean("submit", false)
                val f = d.focusInfo()
                when (PhonePolicy.gate("type", if (submit) f.submit.ifBlank { t.label } else t.label, t.node(), f.card, submit)) {
                    PhonePolicy.Gate.REFUSE -> return "\u201c${t.label.take(40)}\u201d is a card field. Blaze never types card details: call phone_handoff so the user enters them."
                    PhonePolicy.Gate.CONFIRM -> if (!confirm("Submit on $site?", "Blaze Phone will type into \u201c${t.label.take(40)}\u201d and press Enter, which submits \u201c${f.submit.ifBlank { "the form" }.take(40)}\u201d.", "Submit"))
                        return refused(f.submit.ifBlank { "submit" })
                    PhonePolicy.Gate.PASS -> {}
                }
                after(d, d.type(t, text, a.optBoolean("clear", true), submit), submit)
            }
            "key" -> {
                val k = a.optString("key").ifBlank { a.optString("text") }.lowercase()
                if (k !in PhonePolicy.KEYS) return "Unknown key \u201c$k\u201d. Use one of: ${PhonePolicy.KEYS.joinToString()}."
                val f = d.focusInfo()
                val isSubmit = k == "enter" && f.inForm && PhonePolicy.irreversible(f.submit)
                if (PhonePolicy.gate("key", k, null, f.card && k == "enter", isSubmit) == PhonePolicy.Gate.CONFIRM &&
                    !confirm("Press Enter on $site?", "On Blaze Phone this submits \u201c${f.submit.ifBlank { "the form" }.take(40)}\u201d.", "Submit")) return refused(f.submit.ifBlank { "Enter" })
                d.key(k)
                after(d, PhoneDriver.ActResult(true, "Pressed ${k.replaceFirstChar { it.uppercase() }}."), k == "enter" || k == "back")
            }
            "select" -> {
                val opt = a.optString("option").ifBlank { a.optString("text") }
                if (target.isEmpty() || opt.isEmpty()) return "select needs target and option."
                val t = d.locate(target, true) ?: return "No dropdown \u201c${target.take(40)}\u201d. phone_read for fresh ids."
                if (PhonePolicy.gate("select", opt, t.node()) == PhonePolicy.Gate.CONFIRM &&
                    !confirm("Choose \u201c${opt.take(40)}\u201d on $site?", "On Blaze Phone. This option looks like it confirms or pays.", "Choose")) return refused(opt)
                d.select(t, opt).text
            }
            "scroll" -> d.scroll(a.optString("direction", "down") != "up").text
            "back", "forward", "reload" -> after(d, d.navigate(action), true)
            "fill_login" -> d.fillLogin(a.optString("text").ifBlank { null }).text
            else -> "Unknown action."
        }
    }
}
