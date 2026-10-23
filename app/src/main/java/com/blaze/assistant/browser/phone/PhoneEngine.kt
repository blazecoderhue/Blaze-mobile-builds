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
 *
 * 0.45.19-speed: reads come from the bridge's live model ([DomCache]) with no page round trip; waits are on events
 * (navigation finished, the DOM quiet for [QUIET_MS] with no request in flight), never fixed sleeps. Pages without the
 * bridge (it failed to inject) fall back to the direct DOM read and the old timed waits.
 */
class PhoneEngine(private val h: PageHost) {
    companion object {
        /** How long an action gets to start changing the page (DOM change, navigation, request) before it counts as done. */
        const val REACT_MS = 400L
        /** The page counts as settled after this long without a DOM change and with no request in flight. */
        const val QUIET_MS = 120L
        /** After the load, wait at most this long for quiet (pages that animate forever). */
        const val QUIET_CAP_MS = 1_500L
        /** Typing: how long the tap gets to focus the field, and the keys to land. */
        const val FOCUS_MS = 300L
        const val LAND_MS = 400L
        private val ID = Regex("^e\\d+$")
    }

    /** The old fixed wait, for pages without the bridge. */
    private suspend fun legacySettle(ms: Long) {
        h.sleep(250)
        h.awaitLoad(ms)
        h.sleep(350)
    }

    /**
     * Waits for what an action started: up to [REACT_MS] for any sign of it (DOM change, navigation, request), then the
     * navigation's load, then [QUIET_MS] without a DOM change and no request in flight. Bounded by [maxMs].
     * [sig0]: the page signal before the action; -1 when a load is known to be under way (phone_open).
     */
    internal suspend fun settle(sig0: Long, maxMs: Long) {
        val dom = h.dom() ?: return legacySettle(maxMs)
        val t0 = h.now()
        fun left() = maxMs - (h.now() - t0)
        if (sig0 >= 0 && h.signal() == sig0 && !h.loading()) {
            h.awaitSignal(sig0, minOf(REACT_MS, maxMs))
            if (h.signal() == sig0 && !h.loading()) return   // nothing happened: nothing to wait for
        }
        var calmSince = -1L
        while (left() > 0) {
            if (h.loading()) {
                calmSince = -1L
                val s = h.signal()
                if (!h.awaitLoad(left())) return
                if (h.loading()) h.awaitSignal(s, minOf(left(), 50L).coerceAtLeast(1L))
                continue
            }
            if (calmSince < 0) calmSince = h.now()
            if (h.now() - calmSince >= QUIET_CAP_MS) return
            val s = h.signal()
            if (!dom.usable()) { h.awaitSignal(s, minOf(left(), 250L).coerceAtLeast(1L)); continue }
            val quiet = h.now() - dom.activeAt()
            val busy = dom.netBusy(h.now())
            if (quiet >= QUIET_MS && !busy) return
            h.awaitSignal(s, minOf(left(), if (busy) 250L else QUIET_MS - quiet).coerceAtLeast(1L))
        }
    }

    /** Waits (on page signals, no polling) until [cond] holds; false after [ms]. */
    private suspend fun awaitUntil(ms: Long, cond: () -> Boolean): Boolean {
        val end = h.now() + ms
        while (true) {
            val s = h.signal()
            if (cond()) return true
            val left = end - h.now()
            if (left <= 0) return false
            h.awaitSignal(s, left)
        }
    }

    suspend fun read(): PhonePolicy.Snapshot? {
        if (!h.hasPage()) return null
        val o = model() ?: return null
        h.touch()
        return PhonePolicy.parseSnapshot(o) { SecureInput.scrub(it) }
    }

    /** The page model: the bridge's cache when current, a full model from the bridge after a missed message, else the direct DOM read. */
    private suspend fun model(): JSONObject? {
        val dom = h.dom()
        if (dom != null) {
            dom.snapshotJson()?.let { return it }
            if (!h.loading()) (h.eval(PhoneScripts.BRIDGE_FULL) as? JSONObject)?.let { f -> if (dom.acceptFull(f)) dom.snapshotJson()?.let { return it } }
        }
        return h.eval(PhoneScripts.SNAPSHOT) as? JSONObject
    }

    /** Finds a target: an element id ("e12"), or words on the page. Scrolled into view. One page call. */
    suspend fun locate(target: String, field: Boolean = false): Target? {
        val t = target.trim().removePrefix("[").removeSuffix("]")
        val o = when {
            ID.matches(t) -> h.eval(PhoneScripts.rect(t)) as? JSONObject
            t == ":focus" -> {
                val id = h.dom()?.focusId() ?: (h.eval(PhoneScripts.FOCUSED_ID) as? String)
                if (id == null || !ID.matches(id)) null else h.eval(PhoneScripts.rect(id)) as? JSONObject
            }
            t.isEmpty() -> null
            else -> h.eval(PhoneScripts.locate(t, field)) as? JSONObject
        } ?: return null
        val id = o.optString("id").ifEmpty { if (ID.matches(t)) t else "" }
        if (!ID.matches(id)) return null   // ids only ever come from our own scripts; anything else is the page's
        return target(id, o)
    }

    internal fun target(id: String, o: JSONObject) = Target(id, o.optInt("x"), o.optInt("y"), o.optInt("w"), o.optInt("h"), SecureInput.scrub(o.optString("label")),
        o.optString("role"), o.optString("tag"), o.optString("type"), o.optBoolean("card"), o.optBoolean("sens"), o.optBoolean("editable"),
        o.optBoolean("disabled"), o.optBoolean("covered"), o.optString("coveredBy"))

    suspend fun focusInfo(): FocusInfo {
        h.dom()?.focusInfo()?.let { return it }
        val o = h.eval(PhoneScripts.FOCUS) as? JSONObject ?: return FocusInfo("", false, false, false)
        return FocusInfo(o.optString("submit"), o.optBoolean("card"), o.optBoolean("inForm"), o.optBoolean("editable"))
    }

    suspend fun click(t: Target): ActResult {
        if (t.disabled) return ActResult(false, "\u201c${t.label.take(40)}\u201d is disabled right now (a required field may be empty). phone_read to see what's missing.")
        val real = t.w > 0 && t.h > 0 && !t.covered
        val sig0 = h.signal()
        if (real) h.tap(t.x.toFloat(), t.y.toFloat()) else h.eval(PhoneScripts.click(t.id))
        settle(sig0, 10_000)
        return ActResult(true, "Clicked \u201c${t.label.take(60)}\u201d" + (if (!real) " (by script: ${if (t.covered) "covered by \u201c${t.coveredBy}\u201d" else "no size"})" else "") + ".")
    }

    suspend fun type(t: Target, text0: String, clear: Boolean, submit: Boolean): ActResult {
        val url = h.url()
        val secret = SecureInput.hasPlaceholder(text0)
        if (secret && !com.blaze.assistant.connect.UrlSafety.autofillAllowed(url)) return ActResult(false, com.blaze.assistant.connect.UrlSafety.AUTOFILL_REFUSED)
        val text = if (!secret) text0 else SecureInput.resolve(text0, SecureInput.hostOf(url)).first?.also { SecureInput.hide(it) }
            ?: return ActResult(false, "No saved value for that placeholder on this site. Ask the user to take control (phone_handoff) so they type it themselves.")
        val dom = h.dom()
        if (t.w > 0 && t.h > 0 && !t.covered) h.tap(t.x.toFloat(), t.y.toFloat()) else h.eval(PhoneScripts.focus(t.id))
        if (dom != null) awaitUntil(FOCUS_MS) { dom.focusId() == t.id } else h.sleep(120)
        val typedByKeys = h.keys(text, clear)
        val want = { l: Int -> if (clear) l == text.length else l >= text.length }
        // Check what landed (the bridge reports it; else ask the page); frameworks that swallow key events get the value set by script.
        val good = if (dom != null && typedByKeys && awaitUntil(LAND_MS) { dom.valueLen(t.id)?.let(want) == true }) true else {
            if (dom == null) h.sleep(80)
            val len = (h.eval(PhoneScripts.valueLen(t.id)) as? Number)?.toInt() ?: -1
            typedByKeys && (len < 0 || want(len))
        }
        if (!good) h.eval(PhoneScripts.type(t.id, text, secret || t.sens, clear))
        else if (secret || t.sens) h.eval(PhoneScripts.markSecret(t.id))
        h.touch()
        var msg = if (secret || t.sens) "Typed into \u201c${t.label.take(40)}\u201d (value hidden)" else "Typed \u201c${text0.take(60)}\u201d into \u201c${t.label.take(40)}\u201d"
        if (!good) msg += " (by script)"
        if (submit) { val s = h.signal(); h.key("enter"); settle(s, 12_000); msg += " and pressed Enter" }
        return ActResult(true, SecureInput.scrub("$msg."))
    }

    suspend fun select(t: Target, option: String): ActResult {
        val s = h.signal()
        val r = h.eval(PhoneScripts.select(t.id, option))?.toString().orEmpty()
        h.touch()
        return when {
            r.startsWith("ok:") -> { settle(s, 4000); ActResult(true, "Selected \u201c${r.removePrefix("ok:").take(60)}\u201d in \u201c${t.label.take(40)}\u201d.") }
            r.startsWith("nooption:") -> ActResult(false, "No option \u201c${option.take(40)}\u201d. Options: ${r.removePrefix("nooption:").take(300)}")
            r == "notselect" -> ActResult(false, "\u201c${t.label.take(40)}\u201d isn't a dropdown; click it, then click the option.")
            else -> ActResult(false, "That element is gone. phone_read for fresh ids.")
        }
    }

    suspend fun scroll(down: Boolean): ActResult {
        val dom = h.dom()
        val before = dom?.scrollY() ?: (h.eval(PhoneScripts.SCROLL_Y) as? String)?.toIntOrNull()
        val vh = h.viewHeight()
        h.drag((if (down) 1 else -1) * vh * 0.6f)
        val moved = if (dom != null && before != null) awaitUntil(400) { dom.scrollY() != before } else {
            h.sleep(350)
            val after = (h.eval(PhoneScripts.SCROLL_Y) as? String)?.toIntOrNull()
            before == null || before != after
        }
        if (!moved) h.eval(PhoneScripts.scrollBy(down))
        return ActResult(true, "Scrolled ${if (down) "down" else "up"}.")
    }

    suspend fun navigate(what: String): ActResult {
        val s = h.signal()
        val r = h.history(what)
        h.touch()
        if (!r.startsWith("Nothing") && !r.startsWith("No page")) settle(s, 10_000)
        return ActResult(true, r)
    }

    /** A key from phone_act: back navigates; Enter waits for what it started. */
    suspend fun pressKey(k: String) {
        if (k.equals("back", true)) { navigate("back"); return }
        val s = h.signal()
        h.key(k)
        if (k.equals("enter", true)) settle(s, 10_000)
    }

    /** After phone_open's loadUrl: wait for the page. */
    suspend fun afterOpen() = settle(-1, 15_000)

    /** Waits until [selector] is visible / [text] is on the page / the page loaded (or the opposite with [gone]). */
    suspend fun waitFor(selector: String?, text: String?, gone: Boolean, ms: Long): String {
        if (!h.hasPage()) return "No page open."
        val until = h.now() + ms
        if (selector.isNullOrBlank() && text.isNullOrBlank()) return if (h.awaitLoad(ms)) "Page loaded." else "Still loading after ${ms / 1000}s."
        val dom = h.dom()
        while (true) {
            val s = h.signal()
            val fromCache = if (selector.isNullOrBlank() && dom != null && !h.loading()) dom.hasText(text.orEmpty())?.let { has -> if (has != gone) "yes" else "no" } else null
            when (fromCache ?: h.eval(PhoneScripts.waitCheck(selector, text, gone))) {
                "yes" -> { h.touch(); return "Condition met: ${describe(selector, text, gone)}." }
                "badselector" -> return "That CSS selector isn't valid."
            }
            val left = until - h.now()
            if (left <= 0) break
            // Re-check when the page changes (and at least every second, for CSS-only changes).
            if (dom != null) h.awaitSignal(s, minOf(left, 1_000L)) else h.sleep(minOf(left, 300L))
        }
        return "Timed out after ${ms / 1000}s waiting for ${describe(selector, text, gone)}."
    }

    private fun describe(sel: String?, text: String?, gone: Boolean) =
        (if (!sel.isNullOrBlank()) "selector $sel" else "text \u201c${text.orEmpty().take(40)}\u201d") + if (gone) " gone" else ""
}
