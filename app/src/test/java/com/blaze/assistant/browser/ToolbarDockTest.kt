package com.blaze.assistant.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.34.1: the browser's floating toolbar docks to the nearest edge of the page and remembers it by id. */
class ToolbarDockTest {
    private val w = 1080f
    private val h = 1800f

    @Test fun nearestEdge() {
        assertEquals(ToolbarDock.BOTTOM, ToolbarDock.nearest(540f, 1700f, w, h))
        assertEquals(ToolbarDock.TOP, ToolbarDock.nearest(540f, 60f, w, h))
        assertEquals(ToolbarDock.LEFT, ToolbarDock.nearest(40f, 900f, w, h))
        assertEquals(ToolbarDock.RIGHT, ToolbarDock.nearest(1050f, 900f, w, h))
        // In the middle of a tall page the sides are closer than the top or bottom.
        assertEquals(ToolbarDock.LEFT, ToolbarDock.nearest(500f, 900f, w, h))
        // A tie keeps the bottom.
        assertEquals(ToolbarDock.BOTTOM, ToolbarDock.nearest(500f, 1300f, 1000f, 1800f))
    }

    @Test fun ids() {
        ToolbarDock.entries.forEach { assertEquals(it, ToolbarDock.of(it.id)) }
        assertEquals(ToolbarDock.BOTTOM, ToolbarDock.of(null))
        assertEquals(ToolbarDock.BOTTOM, ToolbarDock.of("middle"))
        assertEquals(false, ToolbarDock.BOTTOM.vertical); assertEquals(true, ToolbarDock.LEFT.vertical)
    }

    @Test fun centers() {
        assertEquals(540f to 1800f - 48f - 10f - 30f, ToolbarDock.center(ToolbarDock.BOTTOM, w, h, 60f, 10f, 48f))
        assertEquals(540f to 40f, ToolbarDock.center(ToolbarDock.TOP, w, h, 60f, 10f, 48f))
        assertEquals(40f to (1800f - 48f) / 2, ToolbarDock.center(ToolbarDock.LEFT, w, h, 60f, 10f, 48f))
        assertEquals(1040f to (1800f - 48f) / 2, ToolbarDock.center(ToolbarDock.RIGHT, w, h, 60f, 10f, 48f))
    }
}
