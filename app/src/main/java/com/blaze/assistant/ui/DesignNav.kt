package com.blaze.assistant.ui

import com.blaze.assistant.agent.ChatNav

/**
 * v0.45.8: Design is its own full-screen app view, like Music and Video: it opens from its own entry (Views › Design,
 * open_view design, /design, "Open in Design?"), takes the whole screen (no chat top bar, no composer, no particles over
 * the canvas) and has its own "Back to chat". The 0.45.6 render check and Beta label are unchanged; the 0.46.0 rebuild
 * goes on top of this shell. Pure (DesignNavTest).
 */
object DesignNav {
    const val ID = "design"
    const val BACK_LABEL = "Back to chat"

    /** Design covers the screen when it is the view showing on the main chat tab. */
    fun fullScreen(showing: String, nav: ChatNav): Boolean = showing == ID && nav.tab == ChatNav.CHAT && !nav.inProjectChat

    /** Opening Design from anywhere: the main chat tab with Design showing. */
    fun open(nav: ChatNav): Pair<ChatNav, String> = nav.switchTab(ChatNav.CHAT) to ID

    /** Back (the button or the system gesture) from Design: the view closes and the chat is back. */
    fun back(showing: String): String = if (showing == ID) "" else showing

    /** The chat's own top bar and composer are hidden while Design is full screen. */
    fun chatChrome(showing: String, nav: ChatNav): Boolean = !fullScreen(showing, nav)
}
