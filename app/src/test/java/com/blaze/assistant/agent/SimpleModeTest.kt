package com.blaze.assistant.agent

import com.blaze.assistant.ui.Views
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleModeTest {
    @Test fun simpleIsTheDefault() {
        assertEquals(SimpleMode.SIMPLE, SimpleMode.of(null))
        assertEquals(SimpleMode.SIMPLE, SimpleMode.of(""))
        assertEquals(SimpleMode.SIMPLE, SimpleMode.of("whatever"))
        assertEquals(SimpleMode.ADVANCED, SimpleMode.of(" Advanced "))
    }

    @Test fun simpleHidesOnlyTechnicalViews() {
        listOf("terminal", "jobs", "router", "brain", "dashboard", "usage", "commands", "skills", "agents").forEach {
            assertFalse(it, SimpleMode.viewVisible(it, simple = true)); assertTrue(it, SimpleMode.viewVisible(it, simple = false))
        }
        // No feature is removed: the everyday views all stay in Simple.
        listOf("music", "video", "gallery", "phone", "linux", "design", "browser", "projects", "doing", "look", "home", "settings").forEach {
            assertTrue(it, SimpleMode.viewVisible(it, simple = true))
        }
    }

    @Test fun everyHiddenViewStillExists() {
        SimpleMode.TECH_VIEWS.forEach { assertTrue("$it must still be a view", Views.find(it) != null) }
    }

    @Test fun pagesPlusAndMcp() {
        assertFalse(SimpleMode.pageVisible("ai", true)); assertTrue(SimpleMode.pageVisible("ai", false))
        assertTrue(SimpleMode.pageVisible("linux", true)); assertTrue(SimpleMode.pageVisible("voice", true))
        assertFalse(SimpleMode.plusVisible("plan", true)); assertTrue(SimpleMode.plusVisible("approval", true)); assertTrue(SimpleMode.plusVisible("linux", true))
        assertFalse(SimpleMode.showMcp(true)); assertTrue(SimpleMode.showMcp(false))
        assertTrue(SimpleMode.commandRuns("terminal", true))
    }
}
