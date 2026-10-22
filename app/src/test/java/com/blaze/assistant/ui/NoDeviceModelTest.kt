package com.blaze.assistant.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.45.2: no phone-model mentions in the app's code, strings, prompts, skills or tests (generic device logic stays). */
class NoDeviceModelTest {
    private val model = Regex("""(?i)(galaxy\s*a12|samsung\s*a12|\bthe a12\b|\ba12\b(?!b)|sm-a125)""")
    private val allowed = Regex("""(?i)(R4 or A12|R4, A12|a12b)""")

    @Test fun noModelNamesInSourcesOrAssets() {
        val roots = listOf("src/main/java", "src/main/assets", "src/main/res", "src/test/java")
        val hits = roots.map(::File).filter { it.exists() }.flatMap { r -> r.walkTopDown().filter { it.isFile } }
            .filter { f -> f.extension in setOf("kt", "java", "md", "txt", "xml", "json", "html", "js") && !f.name.endsWith(".min.js") && f.name != "NoDeviceModelTest.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (model.containsMatchIn(l) && !allowed.containsMatchIn(l)) "${f.path}:${i + 1}" else null } }
        assertTrue("device model mentions: ${hits.take(10)}", hits.isEmpty())
    }
}
