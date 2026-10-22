package com.blaze.assistant.ui

import com.blaze.assistant.agent.ChatNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.45.8: Design as its own full-screen view (like Music and Video): opens from its entry onto the main chat tab, covers
 * the chat's top bar and composer, and Back returns to the chat. The 0.45.6 render check and Beta label stay.
 */
class DesignNavTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    @Test fun opensFullScreenFromAnywhere() {
        for (from in listOf(ChatNav(), ChatNav(ChatNav.PROJECTS, "p1", ""), ChatNav(ChatNav.PROJECTS, "p1", "c1"), ChatNav(ChatNav.HOME))) {
            val (nav, showing) = DesignNav.open(from)
            assertEquals(ChatNav.CHAT, nav.tab)
            assertEquals("design", showing)
            assertTrue("$from", DesignNav.fullScreen(showing, nav))
            assertFalse("chat chrome hidden", DesignNav.chatChrome(showing, nav))
        }
    }

    @Test fun onlyDesignIsFullScreen() {
        assertFalse(DesignNav.fullScreen("", ChatNav()))
        assertFalse(DesignNav.fullScreen("music", ChatNav()))
        assertFalse("not over Projects", DesignNav.fullScreen("design", ChatNav(ChatNav.PROJECTS)))
        assertFalse("not over a project chat", DesignNav.fullScreen("design", ChatNav(ChatNav.PROJECTS, "p", "c")))
        assertTrue(DesignNav.chatChrome("", ChatNav()))
    }

    @Test fun backReturnsToChat() {
        val (nav, showing) = DesignNav.open(ChatNav())
        val after = DesignNav.back(showing)
        assertEquals("", after)
        assertFalse(DesignNav.fullScreen(after, nav))
        assertTrue(DesignNav.chatChrome(after, nav))
        assertEquals(ChatNav.CHAT, nav.tab)
        // Back with something else showing leaves it alone.
        assertEquals("music", DesignNav.back("music"))
    }

    @Test fun wiredIntoTheChatScreen() {
        val chat = src("ui/ChatScreen.kt")
        assertTrue("DesignNav.fullScreen(showing, nav)" in chat)
        assertTrue("top bar hidden", "if (!designFull) Box(" in chat)
        assertTrue("back button", "onBack = { Views.showing.value = DesignNav.back(Views.showing.value) }" in chat)
        assertTrue("system back", "BackHandler { Views.showing.value = DesignNav.back(Views.showing.value) }" in chat)
        val view = src("ui/DesignView.kt")
        assertTrue("BackButton(onBack, desc = DesignNav.BACK_LABEL)" in view)
        // 0.45.6 behaviour kept: Beta label, render truth.
        assertTrue("Text(DESIGN_BETA" in view)
        assertTrue("DesignTruth" in view)
        // Its own entry stays in Views (open_view design, /design, the views sheet).
        assertTrue("BlazeView(\"design\", \"Design\"" in src("ui/Views.kt"))
    }
}
