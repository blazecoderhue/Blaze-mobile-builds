package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipStudioTest {
    @Test fun plans() {
        val short = ClipStudio.plan(listOf(5000L, 8000L), 30_000)
        assertEquals(listOf(0L to 5000L, 0L to 8000L), short.map { it.startMs to it.endMs })
        val p = ClipStudio.plan(listOf(60_000L, 30_000L, 10_000L, 500L), 30_000)
        assertEquals(3, p.size)
        val total = p.sumOf { it.endMs - it.startMs }; assertTrue(total in 27_000..31_000)
        assertTrue(p.all { it.endMs <= listOf(60_000L, 30_000L, 10_000L)[it.index] && it.endMs - it.startMs >= 1500 })
        assertTrue(ClipStudio.plan(listOf(200L), 30_000).isEmpty())
    }
}
