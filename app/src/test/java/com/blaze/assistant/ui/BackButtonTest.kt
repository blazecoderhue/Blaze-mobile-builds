package com.blaze.assistant.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.28.3: every page uses the shared round [BackButton]; no thin "←" text glyph as a back button anywhere. */
class BackButtonTest {
    private fun root(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private val glyph = Regex("""Text\(\s*"(?:\u2190|\\u2190)""")
    private val oldIcon = Regex("""Icons\.(?:Rounded|Default|Filled|Outlined)\.ArrowBack\b""")

    @Test fun noHandRolledBackArrows() {
        val bad = root().walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { n, l -> if (glyph.containsMatchIn(l) || oldIcon.containsMatchIn(l)) "${f.name}:${n + 1}: ${l.trim()}" else null }
        }.toList()
        assertTrue("Use BackButton / BackTitle from ui/Kit.kt:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun kitHasTheSharedBackButton() {
        val kit = File(root(), "com/blaze/assistant/ui/Kit.kt").readText()
        assertTrue(kit.contains("fun BackButton("))
        assertTrue(kit.contains("Icons.AutoMirrored.Rounded.ArrowBack"))
        assertTrue(kit.contains("size(48.dp)") && kit.contains("size(40.dp)"))
        assertTrue(kit.contains("contentDescription = desc") && kit.contains("desc: String = \"Back\""))
    }
}
