package com.blaze.assistant.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

/**
 * v0.28.1 (Luke): Enter never sends. In every message box (the chat composer, the assist panel, "Ask Blaze about this
 * page", a specialist's Steer / Follow-up box, Design's "ask for a change") the keyboard's Enter key, and a hardware
 * keyboard's Enter, insert a new line; only the send button sends. The fields are multi-line (never singleLine) and
 * have no KeyboardActions / key handlers tied to Enter or ImeAction.Send. Tested in ComposerKeysTest.
 */
object ComposerKeys {
    /** Keyboard options for a message box: the IME shows its normal Enter (a new line in a multi-line field). */
    val Message = KeyboardOptions(imeAction = ImeAction.Default)

    /** True when Enter in a field with these options inserts a newline instead of running an action. */
    fun enterIsNewline(o: KeyboardOptions, singleLine: Boolean): Boolean =
        !singleLine && (o.imeAction == ImeAction.Default || o.imeAction == ImeAction.None)
}
