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
        tool in screen -> "the screen"
        tool in otherApps -> "content from ${tool.substringBefore('_').replaceFirstChar { it.uppercase() }}"
        tool.startsWith("mcp__") -> "an MCP tool's answer"
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

    /**
     * The current turn's taint, carried in the coroutine context of each chat's turn (so [Agent.confirm], called
     * deep inside a tool, can read it). Cleared when the user sends a new message.
     */
    class Taint : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Taint>
        @Volatile var source: String? = null
            private set
        /** Keeps the first source this turn (the card names one). */
        fun mark(s: String?) { if (!s.isNullOrBlank() && source == null) source = s }
        fun clear() { source = null }
    }

    /**
     * A command the user typed or said, run by the on-device fast lane (no outside content involved):
     * phone-setting changes it makes don't need the card. Texts and calls still always ask.
     */
    object Direct : AbstractCoroutineContextElement(DirectKey)
    object DirectKey : CoroutineContext.Key<Direct>
}
