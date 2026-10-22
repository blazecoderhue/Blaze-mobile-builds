package com.blaze.assistant.agent

/**
 * v0.45.4: a chat message sent while signed out of Blaze Cloud, with no other model that could answer (the Offline
 * Brain only answers with no internet), no longer ends in an error. The turn stops at its first model call, the chat
 * shows a "Sign in" card instead, the message stays where it is, and it is sent by itself once sign-in completes.
 * Direct commands that need no model ("open WhatsApp", the time, a timer) still run signed out, because the card only
 * replaces the failure of a real model call.
 *
 * Pure: which failure is "signed out", whether the card applies, which held chats to send when the account lands.
 */
object SignInGate {
    /** The card's meta tag on its chat message (the chat draws a Sign in button for it). */
    const val CARD = "signin"
    const val LINE = "Sign in to your Blaze account and I'll send this message as soon as you're in."
    const val BUTTON = "Sign in"

    /** The model call failed only because there is no Blaze session (LlmClient: Cloud.SIGNED_OUT, 401). */
    fun isSignedOut(code: Int, message: String?): Boolean = code == 401 && message == Cloud.SIGNED_OUT

    /** The card replaces the error when the failure was "signed out" and no other model could have answered. */
    fun applies(code: Int, message: String?, offlineBrainAnswers: Boolean): Boolean =
        isSignedOut(code, message) && !offlineBrainAnswers

    /** A chat waiting on sign-in: its id and the card's message id (the user's message sits just above the card). */
    data class Held(val chatId: String, val cardId: Long)

    /** The chats waiting on sign-in, in order. One card per chat: a newer one replaces the older. */
    class Holds {
        private val list = ArrayList<Held>()
        @Synchronized fun hold(chatId: String, cardId: Long) { list.removeAll { it.chatId == chatId }; list.add(Held(chatId, cardId)) }
        @Synchronized fun drop(chatId: String) { list.removeAll { it.chatId == chatId } }
        @Synchronized fun size() = list.size
        /** On sign-in: everything held, emptied (each is sent once). */
        @Synchronized fun takeAll(): List<Held> = ArrayList(list).also { list.clear() }
    }

    /** Send the held messages when the account goes from signed out to signed in (not at app start with a saved session). */
    fun release(wasSignedIn: Boolean, signedIn: Boolean, held: Int): Boolean = !wasSignedIn && signedIn && held > 0

    /**
     * Whether a held chat's card is still the thing to answer: it's the newest message, and the chat isn't busy (the
     * user may have tapped Retry, sent something else, or cleared the chat meanwhile).
     */
    fun stillWaiting(lastMessageId: Long?, lastMeta: String?, cardId: Long, busy: Boolean): Boolean =
        !busy && lastMessageId == cardId && lastMeta == CARD
}
