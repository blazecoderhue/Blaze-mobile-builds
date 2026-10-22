package com.blaze.assistant.ui

import com.blaze.assistant.agent.Project
import com.blaze.assistant.agent.Projects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ViewsTest {
    @Test fun everyOldPageStillHasAView() {
        // The sidebar's pages before 0.12, and Desktop's /views list: none may disappear.
        // v0.24.0: "chats" (the Archive chat list) left with the conversation switcher.
        listOf("gallery", "browser", "routines", "memory", "commands", "usage", "settings",
            "jobs", "schedule", "accounts", "doctor", "design", "router", "agents", "skills", "learn").forEach {
            assertNotNull("missing view $it", Views.find(it))
        }
    }

    @Test fun noConversationListView() {
        assertNull(Views.find("chats")); assertNull(Views.find("archive")); assertNull(Views.find("history"))
        assertTrue(Views.all.none { it.special == "chats" })
    }

    @Test fun aliasesAndSlash() {
        assertEquals("schedule", Views.find("/routines")?.id)
        assertEquals("brain", Views.find("models")?.id)
        assertEquals("router", Views.find("blazerouter")?.id)
        assertEquals("terminal", Views.find("Terminal")?.id); assertEquals("terminal", Views.find("termux")?.id); assertEquals("jobs", Views.find("jobs")?.id)
        assertEquals("music", Views.find("music")?.id); assertEquals("music", Views.find("/songs")?.id)
        assertEquals("video", Views.find("movies")?.id); assertEquals("video", Views.find("Video")?.id)
        assertNull(Views.find("nope"))
    }

    @Test fun filterMatchesNameAndDescription() {
        assertTrue(Views.filter("remember").any { it.id == "memory" })
        assertEquals(Views.all.size, Views.filter("").size)
    }

    @Test fun idsAreUnique() = assertEquals(Views.all.size, Views.all.map { it.id }.toSet().size)

    @Test fun designUrls() {
        val home = File("/data/home")
        assertEquals("https://x.dev", designUrl("https://x.dev", home))
        assertEquals("http://localhost:5173/", designUrl("localhost:5173/", home))
        assertEquals("file:///data/home/site/index.html", designUrl("~/site/index.html", home))
        assertEquals("file:///data/home/a.html", designUrl("a.html", home))
        assertEquals("", designUrl("  ", home))
    }

    @Test fun modeChipOnlyWhenNotDefault() {
        assertEquals("", modeChip("build", "auto"))
        assertEquals("PLAN · AUTOPILOT", modeChip("plan", "autopilot"))
    }

    @Test fun durations() {
        assertEquals("12s", formatDuration(12_400))
        assertEquals("3m 11s", formatDuration(191_000))
    }

    @Test fun projectsRoundTrip() {
        val list = listOf(Project("p1", "School", "Be brief", listOf("c1", "c2"), 5L), Project("p2", "Store"))
        assertEquals(list, Projects.parse(Projects.toJson(list)))
        assertEquals(emptyList<Project>(), Projects.parse(""))
    }
}
