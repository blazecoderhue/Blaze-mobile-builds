package com.blaze.assistant.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.45.23: no code path brings the old first-run steps back, and the test gateway can't reach a release build. */
class FirstRunSourceTest {
    private val main = listOf(File("src/main/java/com/blaze/assistant"), File("app/src/main/java/com/blaze/assistant")).first { it.isDirectory }
    private fun src(rel: String) = File(main, rel).readText()
    private fun all() = main.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.map { it to it.readText() }.toList()
    private fun gradle() = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile && it.readText().contains("applicationId") }.readText()

    private fun fiveTimes(name: String, body: (Int) -> Unit) {
        var pass = 0
        for (i in 1..5) { body(i); pass++ }
        assertEquals("$name strict 5/5", 5, pass)
    }

    @Test fun theOldStepsAreGone() = fiveTimes("old steps gone") {
        val code = all()
        for (gone in listOf("fun SetupFlow(", "fun SetupGateScreen(", "fun Onboarding(", "fun shouldGate(", "SetupGateScreen(", "SetupFlow("))
            assertTrue("$gone still in " + code.filter { it.second.contains(gone) }.map { it.first.name }, code.none { it.second.contains(gone) })
        // No permission prompt at launch.
        assertFalse(src("MainActivity.kt").contains("requestPermissions("))
        // The chat shows only the sign-in step; the Home interest picker opens only from its card.
        val chat = src("ui/ChatScreen.kt")
        assertTrue(chat.contains("FirstRunSignIn {"))
        assertFalse(chat.contains("SetupGate.shouldGate"))
        val home = src("ui/HomeBoardUi.kt")
        assertTrue(home.contains("if (picking) {"))
        assertTrue(home.contains("a == \"interests\" -> { picking = true }"))
    }

    @Test fun theTestGatewayExistsOnlyInDebugBuilds() = fiveTimes("test base debug only") {
        val g = gradle()
        // defaultConfig (so release too) has an empty TEST_BASE; only the debug block reads -Pblaze.testBase.
        assertTrue(g.contains("buildConfigField(\"String\", \"TEST_BASE\", \"\\\"\\\"\")"))
        val release = g.substringAfter("release {").substringBefore("}")
        assertFalse(release.contains("TEST_BASE"))
        assertTrue(g.substringAfter("debug {").substringBefore("}").contains("blaze.testBase"))
        val p = src("agent/Providers.kt")
        assertTrue(p.contains("BuildConfig.DEBUG && it.isNotEmpty()"))
    }
}
