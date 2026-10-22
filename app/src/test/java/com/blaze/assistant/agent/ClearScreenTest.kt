package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.24.0 /clear: parsing, the context window it opens, and when undo is allowed. */
class ClearScreenTest {
    private fun m(id: Long, role: Role, text: String = "x") = ChatMessage(id, role, text, at = 1_000L * id)

    @Test fun parsesClear() {
        listOf("/clear", " /clear ", "/CLEAR", "/Clear now", "/clear\n").forEach { assertTrue(it, ClearScreen.isCommand(it)) }
        listOf("clear", "//clear", "/clearance", "/clea", "/ clear", "", "/", "please /clear", "/new").forEach { assertFalse(it, ClearScreen.isCommand(it)) }
    }

    @Test fun clearIsListedAndChatsIsGone() {
        val names = Commands.actions.map { it.name }
        assertTrue("clear" in names)
        assertFalse("chats" in names)
        assertTrue(Commands.matching("/cle").any { it.name == "clear" })
        assertTrue(Commands.actions.first { it.name == "clear" }.desc.contains("nothing is forgotten"))
    }

    @Test fun windowStartsAtTheEndAndKeepsHistory() {
        val w = ClearScreen.window(42)
        assertEquals(42, w.ctxFrom); assertEquals(42, w.lcUpto); assertEquals("", w.lcSummary)
        assertEquals(0, ClearScreen.window(-3).ctxFrom)
    }

    @Test fun undoRules() {
        val old = listOf(m(1, Role.USER), m(2, Role.STEP), m(3, Role.BLAZE))
        val snap = ClearScreen.Snapshot("main", old, 0, "sum", 0, historyLen = 6, at = 10_000)
        assertTrue(ClearScreen.canUndo(snap, "main", emptyList(), 6, 12_000))
        // another chat, too late, a new turn sent, history grew, nothing to undo
        assertFalse(ClearScreen.canUndo(snap, "p1", emptyList(), 6, 12_000))
        assertFalse(ClearScreen.canUndo(snap, "main", emptyList(), 6, 10_000 + ClearScreen.UNDO_MS + 1))
        assertFalse(ClearScreen.canUndo(snap, "main", listOf(m(9, Role.USER)), 6, 12_000))
        assertFalse(ClearScreen.canUndo(snap, "main", emptyList(), 8, 12_000))
        assertFalse(ClearScreen.canUndo(null, "main", emptyList(), 6, 12_000))
        // a clock that went backwards doesn't allow it either
        assertFalse(ClearScreen.canUndo(snap, "main", emptyList(), 6, 9_000))
    }

    @Test fun restoredKeepsOldThenNewNotes() {
        val old = listOf(m(1, Role.USER), m(3, Role.BLAZE))
        val snap = ClearScreen.Snapshot("main", old, 0, "", 0, 4, 0)
        val note = m(7, Role.BLAZE, "Model: x")
        assertEquals(listOf(1L, 3L, 7L), ClearScreen.restored(snap, listOf(note)).map { it.id })
        assertEquals(listOf(1L, 3L), ClearScreen.restored(snap, emptyList()).map { it.id })
        assertTrue(ClearScreen.isEmpty(emptyList()))
        assertFalse(ClearScreen.isEmpty(old))
    }
}
