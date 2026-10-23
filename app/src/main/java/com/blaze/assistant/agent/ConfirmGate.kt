package com.blaze.assistant.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * v0.28.2: when Blaze's confirm card may be skipped, as one pure decision (unit-tested in ConfirmGateTest).
 *
 *  - Outbound actions in the user's name (texts, calls, messages in other apps, email, posts, payments,
 *    deleting files / data, installing apps, changing phone settings) pass `always = true`: the card shows
 *    whatever the standing OKs or Autopilot say.
 *  - Prompt injection: a turn that has read untrusted content (a web page, a fetched link, search results,
 *    notifications, messages, email, the screen, a shared file, an MCP tool's answer…) is "tainted". Then
 *    any destructive or pre-approved action shows the card too, and the card says "Requested after reading …".
 *  - Otherwise a standing OK ([Autonomy.preApproved]) or Autopilot (non-destructive only) pass as before.
 */
object ConfirmGate {
    enum class Decision { SHOW, STANDING_OK, AUTOPILOT_OK }

    /**
     * [always]: outbound / must-ask action. [destructive]: [Modes.destructive] matched. [preApproved]: a standing OK
     * covers it. [autopilot]: Autopilot is on. [tainted]: this turn read untrusted content.
     */
    fun decide(always: Boolean, destructive: Boolean, preApproved: Boolean, autopilot: Boolean, tainted: Boolean): Decision = when {
        always -> Decision.SHOW
        tainted && (destructive || preApproved) -> Decision.SHOW
        preApproved -> Decision.STANDING_OK
        autopilot && !destructive -> Decision.AUTOPILOT_OK
        else -> Decision.SHOW
    }

    /** The card's detail, with "Requested after reading <source>." on top when the turn is tainted. */
    fun detail(detail: String, source: String?): String =
        if (source.isNullOrBlank()) detail else "Requested after reading $source." + if (detail.isBlank()) "" else "\n$detail"

    /** What reading [tool]'s result counts as (for the card), or null when the tool doesn't bring in outside content. */
    fun sourceOf(tool: String): String? = when {
        tool in web -> "a web page"
        tool == "web_search" -> "web search results"
        tool in fetched -> "a fetched link"
        tool in notifications -> "notifications"
        tool in messages -> "your messages"
        tool in mail -> "your email"
        tool in screen -> SCREEN
        tool in otherApps -> "content from ${tool.substringBefore('_').replaceFirstChar { it.uppercase() }}"
        tool.startsWith("mcp__") -> "an MCP tool's answer"
        tool == "blaze_phone" -> PHONE_APP
        else -> null
    }

    private val web = setOf("browser_open", "browser_read", "browser_click", "browser_scroll", "browser_back", "browser_tabs")
    private val fetched = setOf("fetch_page", "http_request", "download_file")
    private val notifications = setOf("recent_notifications", "notif_read", "notif_conversations")
    private val messages = setOf("read_messages", "slack_read")
    private val mail = setOf("gmail_read", "gmail_search")
    private val screen = setOf("read_screen", "look_at_screen", "search_screen_memory", "search_in_app")
    private val otherApps = setOf("github_issues", "github_notifications", "notion_search", "drive_search", "classroom_coursework")

    /** Labels for content that arrives with the user's own message. */
    const val SHARED_FILE = "a shared file"
    const val MAGIC_HAND = "the screen (Magic Hand)"
    /** v0.45.0: a message typed into the dashboard from another device on the Wi-Fi. */
    const val DASHBOARD = "a message from the dashboard on another device"
    const val AGENT_REPORT = "a background agent's report"
    /** Blaze Phone (spike): a guest app's screen (view_tree) is outside content like any other app's. */
    const val PHONE_APP = "an app in Blaze Phone"

    /**
     * The current turn's taint, carried in the coroutine context of each chat's turn (so [Agent.confirm], called
     * deep inside a tool, can read it). Cleared when the user sends a new message.
     */
    class Taint : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Taint>
        @Volatile var source: String? = null
            private set
        /** 0.45.18: the first source this turn that isn't the screen itself (web, messages, email, files…), see [screenAct]. */
        @Volatile var outside: String? = null
            private set
        /** 0.45.18: the user OK'd acting on the screen after reading [outside] this turn. */
        @Volatile var screenOk: Boolean = false
        /** 0.45.18: the label the screen gate last asked about, so the tap's own risky card doesn't ask twice. */
        @Volatile var askedLabel: String? = null
        /** Keeps the first source this turn (the card names one). */
        fun mark(s: String?) {
            if (s.isNullOrBlank()) return
            if (source == null) source = s
            if (outside == null && s != SCREEN) outside = s
        }
        fun clear() { source = null; outside = null; screenOk = false; askedLabel = null }
    }

    /** What reading the screen counts as ([sourceOf]); the only source that doesn't count as outside content. */
    const val SCREEN = "the screen"

    /** Tools that act on another app's screen. */
    val screenActs = setOf("tap", "type_text", "press_button", "run_plan")

    /** Labels that send, pay, post, delete or confirm something. */
    private val outbound = Regex("\\b(send|reply|post|publish|share|pay|buy|purchase|order|checkout|check out|transfer|delete|remove|confirm|subscribe|book|call|submit|accept|allow|install)\\b",
        RegexOption.IGNORE_CASE)
    fun outbound(label: String): Boolean = outbound.containsMatchIn(label)
    /** The narrower set that still asks in a turn that only read the screen (sends, money, posts, deletes). */
    private val sendsOrPays = Regex("\\b(send|reply|post|publish|pay|buy|purchase|order|checkout|check out|transfer|delete)\\b", RegexOption.IGNORE_CASE)
    fun sendsOrPays(label: String): Boolean = sendsOrPays.containsMatchIn(label)

    enum class ScreenDecision { PASS, ASK_ONCE, ASK }

    /**
     * 0.45.18 prompt-injection rule for screen actions (unit-tested in ScreenActGateTest), applied in every mode
     * (Autopilot and standing OKs included):
     *  - A turn that read outside content ([Taint.outside]: a web page, messages, notifications, email, a file, an MCP
     *    answer…): a tap on something that sends, pays, posts, deletes or confirms asks every time ([ASK]); any other
     *    screen action asks once per turn ([ASK_ONCE]) until the user OKs it.
     *  - A turn that only read the screen: a tap that sends, pays, posts or deletes asks ([ASK]) even with "confirm risky taps" off.
     *  - Otherwise [PASS].
     */
    fun screenAct(tool: String, label: String, tainted: Boolean, outside: String?, screenOk: Boolean): ScreenDecision = when {
        tool !in screenActs -> ScreenDecision.PASS
        outside != null && tool == "tap" && outbound(label) -> ScreenDecision.ASK
        outside != null && !screenOk -> ScreenDecision.ASK_ONCE
        tainted && tool == "tap" && sendsOrPays(label) -> ScreenDecision.ASK
        else -> ScreenDecision.PASS
    }

    /**
     * A command the user typed or said, run by the on-device fast lane (no outside content involved):
     * phone-setting changes it makes don't need the card. Texts and calls still always ask.
     */
    object Direct : AbstractCoroutineContextElement(DirectKey)
    object DirectKey : CoroutineContext.Key<Direct>
}
