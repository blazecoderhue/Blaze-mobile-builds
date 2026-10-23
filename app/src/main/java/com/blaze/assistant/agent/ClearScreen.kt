package com.blaze.assistant.agent

/**
 * /clear (v0.24.0). Blazeinchue: "it clears the screen of all the messages".
 *
 * Only the visible thread is emptied. The conversation's model history stays on disk (chat file `history`),
 * in the journal (`mind.db`, search_chat) and in the long-chat index (`longchat.db`), so One Mind can still
 * look anything up. Blaze also starts a new context window at that point (like Fresh start, but with no
 * "Fresh start" bubble), so it doesn't answer from words the user can no longer see.
 *
 * A short undo (snackbar) puts the messages back while nothing new was sent. Pure Kotlin, unit-tested in ClearScreenTest.
 */
object ClearScreen {
    const val NAME = "clear"
    /** How long the undo stays possible after a clear. */
    const val UNDO_MS = 15_000L

    /** What /clear did. */
    enum class Result { CLEARED, EMPTY, BUSY }

    /** True for "/clear" (any case, with or without trailing words), never for "//clear" or "/clearance". */
    fun isCommand(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("/") || t.startsWith("//")) return false
        return t.removePrefix("/").substringBefore(' ').substringBefore('\n').lowercase() == NAME
    }

    /** What the thread held before a clear (kept in memory only, for undo). */
    data class Snapshot(val chatId: String, val msgs: List<ChatMessage>, val ctxFrom: Int, val lcSummary: String, val lcUpto: Int,
                        val historyLen: Int, val at: Long)

    /** The context window after a clear: it starts at the end of the history; nothing in the history is removed. */
    data class Window(val ctxFrom: Int, val lcSummary: String, val lcUpto: Int)

    fun window(historyLen: Int): Window = Window(historyLen.coerceAtLeast(0), "", historyLen.coerceAtLeast(0))

    /** Nothing to clear: no message is on screen. */
    fun isEmpty(msgs: List<ChatMessage>): Boolean = msgs.isEmpty()

    /**
     * Undo is possible in the same chat, within [UNDO_MS], when no new turn was sent since the clear
     * (no user message on screen and the history didn't grow).
     */
    fun canUndo(s: Snapshot?, chatId: String, msgsNow: List<ChatMessage>, historyLenNow: Int, now: Long): Boolean =
        s != null && s.chatId == chatId && now - s.at in 0..UNDO_MS && msgsNow.none { it.role == Role.USER } && historyLenNow == s.historyLen

    /** The thread after undo: the old messages, then anything the app added since (notes), oldest first. */
    fun restored(s: Snapshot, msgsNow: List<ChatMessage>): List<ChatMessage> {
        val old = s.msgs.map { it.id }.toSet()
        return s.msgs + msgsNow.filter { it.id !in old }
    }
}
