package com.blaze.assistant.browser.phone

/**
 * 0.45.19 Blaze Phone browser: what the phone_* tools drive. [PhoneBrowser] is the real one (WebView on Blaze's own
 * display); the JVM sims (PhoneToolSimTest) drive a scripted demo site through the same tool code.
 */
interface PhoneDriver {
    data class Target(val id: String, val x: Int, val y: Int, val w: Int, val h: Int, val label: String, val role: String, val tag: String,
                      val type: String, val card: Boolean, val sens: Boolean, val editable: Boolean, val disabled: Boolean,
                      val covered: Boolean = false, val coveredBy: String = "") {
        /** As a snapshot node, for [PhonePolicy.gate]. */
        fun node() = PhonePolicy.Node(id, role, label, "", type, sensitive = sens, card = card, disabled = disabled)
    }
    data class FocusInfo(val submit: String, val card: Boolean, val inForm: Boolean, val editable: Boolean)
    data class ActResult(val ok: Boolean, val text: String)

    fun hasPage(): Boolean
    fun mode(): PhonePolicy.Mode
    fun site(): String
    suspend fun open(url: String): String
    suspend fun snapshot(): PhonePolicy.Snapshot?
    suspend fun locate(target: String, field: Boolean): Target?
    suspend fun focusInfo(): FocusInfo
    suspend fun click(t: Target): ActResult
    suspend fun type(t: Target, text: String, clear: Boolean, submit: Boolean): ActResult
    suspend fun select(t: Target, option: String): ActResult
    suspend fun key(k: String)
    suspend fun scroll(down: Boolean): ActResult
    suspend fun navigate(what: String): ActResult
    suspend fun fillLogin(user: String?): ActResult
    suspend fun screenshot(): String?
    suspend fun waitFor(selector: String?, text: String?, gone: Boolean, ms: Long): String
    suspend fun handoff(reason: String): String
    /** Waits while the user is in control; true when Blaze may act. */
    suspend fun awaitAgentTurn(): Boolean
    fun status(): String
    fun step(what: String)
    /** Milliseconds on a monotonic clock (PhonePerf timings). */
    fun now(): Long = System.nanoTime() / 1_000_000L
    /** Turns what the user called a site into a page ([SiteResolver]); the real driver adds history and web search. */
    suspend fun resolve(query: String): SiteResolver.Resolution = SiteResolver.resolve(query, emptyList()) { emptyList() }
}
