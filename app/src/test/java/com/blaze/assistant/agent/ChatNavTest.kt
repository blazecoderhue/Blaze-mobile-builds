package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.28.3: project chats are their own place; the main Chat thread is never replaced (Blazeinchue's phone report). */
class ChatNavTest {
    private val projectOf = mapOf("pc1" to "p1", "pc2" to "p1", "pc9" to "p2")
    private fun of(id: String): String? = projectOf[id]

    @Test fun startsOnMainChat() {
        val n = ChatNav()
        assertEquals(ChatNav.CHAT, n.tab)
        assertEquals(Mind.MAIN, n.visibleChat)
        assertEquals(Mind.MAIN, n.mainChat)
        assertFalse(n.inProjectChat)
    }

    @Test fun openingAProjectChatNeverChangesTheMainChat() {
        val n = ChatNav().switchTab(ChatNav.PROJECTS).openProject("p1").openChat("p1", "pc1")
        assertTrue(n.inProjectChat)
        assertEquals("pc1", n.visibleChat)
        assertEquals(Mind.MAIN, n.mainChat)
        assertEquals(ChatNav.PROJECTS, n.tab)
        // The Chat pill shows main, whatever is open in Projects.
        val onChat = n.switchTab(ChatNav.CHAT)
        assertEquals(Mind.MAIN, onChat.visibleChat)
        assertFalse(onChat.inProjectChat)
    }

    @Test fun newChatInThisProjectOpensInsideProjects() {
        val page = ChatNav().projectsList().openProject("p1")
        val n = page.openChat("p1", "fresh123")
        assertEquals(ChatNav.PROJECTS, n.tab)
        assertEquals("p1", n.project)
        assertEquals("fresh123", n.visibleChat)
        assertEquals(Mind.MAIN, n.mainChat)
    }

    @Test fun backWalksChatThenProjectThenList() {
        val chat = ChatNav().openChat("p1", "pc1")
        val page = chat.back()!!
        assertEquals(ChatNav("projects", "p1", ""), page)
        assertEquals(Mind.MAIN, page.visibleChat)
        val list = page.back()!!
        assertEquals(ChatNav("projects", "", ""), list)
        assertNull(list.back())
        assertNull(ChatNav().back())
    }

    @Test fun switchingTabsRestoresYourPlaceInProjects() {
        val chat = ChatNav().openChat("p1", "pc2")
        val away = chat.switchTab(ChatNav.CHAT)
        assertEquals(Mind.MAIN, away.visibleChat)
        val back = away.switchTab(ChatNav.PROJECTS)
        assertEquals(chat, back)
        assertEquals("pc2", back.visibleChat)
        val page = ChatNav().openProject("p2")
        assertEquals(page, page.switchTab(ChatNav.CHAT).switchTab(ChatNav.PROJECTS))
    }

    @Test fun aNotificationForAProjectChatOpensItInProjects() {
        val n = ChatNav().follow("pc9", ::of)
        assertEquals(ChatNav("projects", "p2", "pc9"), n)
        assertEquals(Mind.MAIN, n.mainChat)
        // Already there: nothing changes.
        assertEquals(n, n.follow("pc9", ::of))
        // Main showing on the Chat tab is a no-op.
        assertEquals(ChatNav(), ChatNav().follow(Mind.MAIN, ::of))
    }

    @Test fun mainShowingWhileInAProjectChatGoesBackToThePage() {
        val n = ChatNav().openChat("p1", "pc1").follow(Mind.MAIN, ::of)
        assertEquals(ChatNav("projects", "p1", ""), n)
    }

    @Test fun privateAndOtherChatsStayOnTheChatTab() {
        val inProject = ChatNav().openChat("p1", "pc1")
        assertEquals(ChatNav.CHAT, inProject.follow(PrivateChat.PREFIX + "abc", ::of).tab)
        assertEquals(ChatNav.CHAT, inProject.follow("agent-chat", ::of).tab)
    }

    @Test fun deletingTheOpenChatOrProjectLeavesIt() {
        val n = ChatNav().openChat("p1", "pc1")
        assertEquals(ChatNav("projects", "p1", ""), n.chatDeleted("pc1"))
        assertEquals(n, n.chatDeleted("other"))
        assertEquals(ChatNav("projects", "", ""), n.projectDeleted("p1"))
        assertEquals(n, n.projectDeleted("p2"))
    }

    @Test fun survivesSaveAndRestore() {
        for (n in listOf(ChatNav(), ChatNav().openProject("p1"), ChatNav().openChat("p1", "pc1"), ChatNav().openChat("p1", "pc1").switchTab(ChatNav.CHAT)))
            assertEquals(n, ChatNav.decode(n.encode()))
        assertEquals(ChatNav(), ChatNav.decode(null))
        assertEquals(ChatNav(), ChatNav.decode("garbage"))
        // A chat without a project is never restored as a project chat.
        assertFalse(ChatNav.decode(ChatNav("projects", "", "pc1").encode()).inProjectChat)
    }
}
