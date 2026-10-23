package com.blaze.assistant.browser.phone

/**
 * 0.45.19-speed: what [PhoneEngine] needs from a page. On the phone it is [PhoneBrowser]'s WebView (real taps, keys,
 * evaluateJavascript, page-load events); in the JVM perf harness and sims it is a simulated page on a virtual clock, so
 * the exact engine code is measured and tested without a device.
 */
interface PageHost {
    /** Milliseconds on a monotonic clock. */
    fun now(): Long
    suspend fun sleep(ms: Long)
    /** Runs a page script; the JSON result parsed (JSONObject, String, Number, Boolean) or null. */
    suspend fun eval(code: String): Any?
    fun hasPage(): Boolean
    fun url(): String
    /** A real tap at (x, y) in the WebView's pixels. */
    suspend fun tap(x: Float, y: Float)
    /** A real finger drag of [dy] pixels (positive = page moves up = scroll down). */
    suspend fun drag(dy: Float)
    /** Types as real key events into the focused field; false when a character has no key. */
    suspend fun keys(text: String, clear: Boolean): Boolean
    suspend fun key(k: String)
    suspend fun viewHeight(): Int
    /** back / forward / reload; returns the line for the agent. */
    suspend fun history(what: String): String
    /** Waits until the current navigation finished (true) or [ms] passed (false). */
    suspend fun awaitLoad(ms: Long): Boolean
    /** Marks the page as used (idle release clock). */
    fun touch()

    // 0.45.19-speed: the DOM bridge and page events (defaults: a page without a bridge).
    /** The bridge's live model of the page; null when this page has none (the engine then reads the DOM directly). */
    fun dom(): DomCache? = null
    /** Bumped on every page signal: a bridge message, a navigation starting or finishing. */
    fun signal(): Long = 0L
    /** Waits until [signal] moves past [after] or [ms] pass; true when it moved. */
    suspend fun awaitSignal(after: Long, ms: Long): Boolean { sleep(ms); return false }
    /** A navigation is in flight (started, not finished). */
    fun loading(): Boolean = false
    /** Someone watches the live view (typing is paced so letters show). */
    fun watching(): Boolean = false
}
