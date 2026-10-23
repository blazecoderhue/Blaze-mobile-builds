package com.blaze.assistant.browser.phone

import com.blaze.assistant.browser.SecureInput
import com.blaze.assistant.browser.phone.PhoneDriver.ActResult
import com.blaze.assistant.browser.phone.PhoneDriver.FocusInfo
import com.blaze.assistant.browser.phone.PhoneDriver.Target
import org.json.JSONObject

/**
 * 0.45.19 Blaze Phone browser: how a page is read and acted on (reads, locating elements, taps, typing, waits), as pure
 * Kotlin over a [PageHost]. [PhoneBrowser] runs it on the real WebView; the perf harness (PhonePerfTest) runs the same
 * code on a simulated page with a virtual clock.
 */
class PhoneEngine(private val h: PageHost) {

    internal suspend fun settle(ms: Long = 12_000) {
        h.sleep(250)
        h.awaitLoad(ms)
        h.sleep(350)   // let scripts render
    }

    suspend fun read(): PhonePolicy.Snapshot? {
        if (!h.hasPage()) return null
        val o = h.eval(PhoneScripts.SNAPSHOT) as? JSONObject ?: return null
        h.touch()
        return PhonePolicy.parseSnapshot(o) { SecureInput.scrub(it) }
    }

    /** Finds a target: an element id ("e12"), or words on the page. Scrolled into view. */
    suspend fun locate(target: String, field: Boolean = false): Target? {
        val t = target.trim().removePrefix("[").removeSuffix("]")
        val id = if (Regex("^e\\d+$").matches(t)) t else if (t == ":focus") (h.eval(PhoneScripts.FOCUSED_ID) as? String) ?: return null
            else (h.eval(PhoneScripts.find(t, field)) as? String) ?: return null
        val o = h.eval(PhoneScripts.rect(id)) as? JSONObject ?: return null
        h.sleep(60)
        return target(id, o)
    }

    internal fun target(id: String, o: JSONObject) = Target(id, o.optInt("x"), o.optInt("y"), o.optInt("w"), o.optInt("h"), SecureInput.scrub(o.optString("label")),
        o.optString("role"), o.optString("tag"), o.optString("type"), o.optBoolean("card"), o.optBoolean("sens"), o.optBoolean("editable"),
        o.optBoolean("disabled"), o.optBoolean("covered"), o.optString("coveredBy"))

    suspend fun focusInfo(): FocusInfo {
        val o = h.eval(PhoneScripts.FOCUS) as? JSONObject ?: return FocusInfo("", false, false, false)
        return FocusInfo(o.optString("submit"), o.optBoolean("card"), o.optBoolean("inForm"), o.optBoolean("editable"))
    }

    suspend fun click(t: Target): ActResult {
        if (t.disabled) return ActResult(false, "\u201c${t.label.take(40)}\u201d is disabled right now (a required field may be empty). phone_read to see what's missing.")
        val real = t.w > 0 && t.h > 0 && !t.covered
        if (real) h.tap(t.x.toFloat(), t.y.toFloat()) else h.eval(PhoneScripts.click(t.id))
        settle(10_000)
        return ActResult(true, "Clicked \u201c${t.label.take(60)}\u201d" + (if (!real) " (by script: ${if (t.covered) "covered by \u201c${t.coveredBy}\u201d" else "no size"})" else "") + ".")
    }

    suspend fun type(t: Target, text0: String, clear: Boolean, submit: Boolean): ActResult {
        val url = h.url()
        val secret = SecureInput.hasPlaceholder(text0)
        if (secret && !com.blaze.assistant.connect.UrlSafety.autofillAllowed(url)) return ActResult(false, com.blaze.assistant.connect.UrlSafety.AUTOFILL_REFUSED)
        val text = if (!secret) text0 else SecureInput.resolve(text0, SecureInput.hostOf(url)).first?.also { SecureInput.hide(it) }
            ?: return ActResult(false, "No saved value for that placeholder on this site. Ask the user to take control (phone_handoff) so they type it themselves.")
        if (t.w > 0 && t.h > 0 && !t.covered) h.tap(t.x.toFloat(), t.y.toFloat()) else h.eval(PhoneScripts.focus(t.id))
        h.sleep(120)
        val typedByKeys = h.keys(text, clear)
        h.sleep(80)
        // Check what landed; frameworks that swallow key events get the value set by script instead.
        val len = (h.eval(PhoneScripts.valueLen(t.id)) as? Number)?.toInt() ?: -1
        val good = typedByKeys && (len < 0 || (if (clear) len == text.length else len >= text.length))
        if (!good) h.eval(PhoneScripts.type(t.id, text, secret || t.sens, clear))
        else if (secret || t.sens) h.eval(PhoneScripts.markSecret(t.id))
        h.touch()
        var msg = if (secret || t.sens) "Typed into \u201c${t.label.take(40)}\u201d (value hidden)" else "Typed \u201c${text0.take(60)}\u201d into \u201c${t.label.take(40)}\u201d"
        if (!good) msg += " (by script)"
        if (submit) { h.key("enter"); settle(12_000); msg += " and pressed Enter" }
        return ActResult(true, SecureInput.scrub("$msg."))
    }

    suspend fun select(t: Target, option: String): ActResult {
        val r = h.eval(PhoneScripts.select(t.id, option))?.toString().orEmpty()
        h.touch()
        return when {
            r.startsWith("ok:") -> { settle(4000); ActResult(true, "Selected \u201c${r.removePrefix("ok:").take(60)}\u201d in \u201c${t.label.take(40)}\u201d.") }
            r.startsWith("nooption:") -> ActResult(false, "No option \u201c${option.take(40)}\u201d. Options: ${r.removePrefix("nooption:").take(300)}")
            r == "notselect" -> ActResult(false, "\u201c${t.label.take(40)}\u201d isn't a dropdown; click it, then click the option.")
            else -> ActResult(false, "That element is gone. phone_read for fresh ids.")
        }
    }

    suspend fun scroll(down: Boolean): ActResult {
        val before = (h.eval(PhoneScripts.SCROLL_Y) as? String)?.toIntOrNull()
        val vh = h.viewHeight()
        h.drag((if (down) 1 else -1) * vh * 0.6f)
        h.sleep(350)
        val after = (h.eval(PhoneScripts.SCROLL_Y) as? String)?.toIntOrNull()
        if (before != null && before == after) h.eval(PhoneScripts.scrollBy(down))
        return ActResult(true, "Scrolled ${if (down) "down" else "up"}.")
    }

    suspend fun navigate(what: String): ActResult {
        val r = h.history(what)
        h.touch(); settle(10_000)
        return ActResult(true, r)
    }

    /** A key from phone_act: back navigates; Enter waits for what it started. */
    suspend fun pressKey(k: String) {
        if (k.equals("back", true)) navigate("back") else { h.key(k); if (k.equals("enter", true)) settle(10_000) }
    }

    /** After phone_open's loadUrl: wait for the page. */
    suspend fun afterOpen() = settle(15_000)

    /** Waits until [selector] is visible / [text] is on the page / the page loaded (or the opposite with [gone]). */
    suspend fun waitFor(selector: String?, text: String?, gone: Boolean, ms: Long): String {
        if (!h.hasPage()) return "No page open."
        val until = h.now() + ms
        if (selector.isNullOrBlank() && text.isNullOrBlank()) return if (h.awaitLoad(ms)) "Page loaded." else "Still loading after ${ms / 1000}s."
        while (h.now() < until) {
            when (h.eval(PhoneScripts.waitCheck(selector, text, gone))) {
                "yes" -> { h.touch(); return "Condition met: ${describe(selector, text, gone)}." }
                "badselector" -> return "That CSS selector isn't valid."
            }
            h.sleep(300)
        }
        return "Timed out after ${ms / 1000}s waiting for ${describe(selector, text, gone)}."
    }

    private fun describe(sel: String?, text: String?, gone: Boolean) =
        (if (!sel.isNullOrBlank()) "selector $sel" else "text \u201c${text.orEmpty().take(40)}\u201d") + if (gone) " gone" else ""
}
