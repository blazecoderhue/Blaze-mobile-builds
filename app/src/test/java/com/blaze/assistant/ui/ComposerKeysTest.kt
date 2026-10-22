package com.blaze.assistant.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.28.1 (Luke): Enter never sends a message; only the send button does. */
class ComposerKeysTest {
    @Test fun messageBoxesUseTheNormalEnterKey() {
        assertEquals(ImeAction.Default, ComposerKeys.Message.imeAction)
        assertTrue(ComposerKeys.enterIsNewline(ComposerKeys.Message, singleLine = false))
        assertFalse(ComposerKeys.enterIsNewline(KeyboardOptions(imeAction = ImeAction.Send), singleLine = false))
        assertFalse(ComposerKeys.enterIsNewline(ComposerKeys.Message, singleLine = true))
    }

    private fun src(path: String): String {
        val roots = listOf("src/main/java/com/blaze/assistant", "app/src/main/java/com/blaze/assistant")
        return roots.map { File(it, path) }.first { it.exists() }.readText()
    }

    /** The composer, the assist panel, page chat, specialist Steer and Design's ask box: no Send action, no Enter key handler. */
    @Test fun noMessageBoxSendsOnEnter() {
        for (p in listOf("ui/Shell.kt", "ui/AssistPanel.kt", "browser/PageAskSheet.kt", "ui/AgentCard.kt", "ui/DesignView.kt", "phone/MagicHandUi.kt")) {
            val s = src(p)
            assertFalse("$p uses ImeAction.Send", s.contains("ImeAction.Send"))
            assertFalse("$p sends from KeyboardActions", Regex("""KeyboardActions\([^)]*onSend""").containsMatchIn(s))
            assertFalse("$p handles key events", s.contains("onKeyEvent") || s.contains("onPreviewKeyEvent") || s.contains("Key.Enter"))
        }
        // The main composer is multi-line (Enter adds a line on the soft and the hardware keyboard).
        val shell = src("ui/Shell.kt")
        val composer = shell.substring(shell.indexOf("keyboardOptions = ComposerKeys.Message") - 600, shell.indexOf("keyboardOptions = ComposerKeys.Message"))
        assertFalse(composer.contains("singleLine = true"))
        assertTrue(composer.contains("maxLines = 6"))
    }
}
