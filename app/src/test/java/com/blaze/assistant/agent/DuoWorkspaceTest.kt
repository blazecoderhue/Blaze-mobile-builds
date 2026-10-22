package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoWorkspaceTest {
    @Test fun board() {
        val B = DuoWorkspace.Board
        var w = DuoWorkspace.Ws("biz", "Biz plan", "Chicken business", 0, emptyList(), emptyList(), "")
        w = B.add(w, "Market research", "halo"); w = B.add(w, "Costing", "nobody")
        assertEquals(listOf(1, 2), w.cards.map { it.id }); assertEquals("blaze", w.cards[1].owner)
        w = B.move(w, 1, "Done")!!
        assertEquals(50, B.progress(w)); assertNull(B.move(w, 9, "done")); assertNull(B.move(w, 1, "later"))
        assertTrue(B.render(w).contains("DONE: #1 Market research [halo]"))
        assertEquals("my-thesis-2026", B.slug("My Thesis 2026!"))
    }
}
