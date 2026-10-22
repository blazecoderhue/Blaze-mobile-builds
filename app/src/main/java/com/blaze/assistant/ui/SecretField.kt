package com.blaze.assistant.ui

/** v0.45.7: show/hide state of a password or passphrase field (pure, unit-tested). */
object SecretField {
    /** Dots only for a secret field the user hasn't revealed. */
    fun masked(secret: Boolean, reveal: Boolean): Boolean = secret && !reveal
    /** What the eye says (TalkBack and long-press): the action it will do. */
    fun label(reveal: Boolean): String = if (reveal) "Hide password" else "Show password"
}
