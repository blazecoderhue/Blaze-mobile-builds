package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave A item 17: live debounced search, stale requests cancelled, skeleton cards while loading. */
class WaveA4LiveSearchTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    @Test fun firesOnlyOnRealChanges() {
        val last = LiveSearch.key("lo-fi", "")
        assertFalse("same words", LiveSearch.shouldFire(last, "lo-fi ", ""))
        assertFalse("same words, other case", LiveSearch.shouldFire(last, "LO-FI", ""))
        assertTrue("new words", LiveSearch.shouldFire(last, "lo-fi beats", ""))
        assertFalse("one letter is too short", LiveSearch.shouldFire(last, "l", ""))
        assertTrue("cleared box goes back to For you", LiveSearch.shouldFire(last, "", ""))
        assertFalse("links wait for Enter", LiveSearch.shouldFire(last, "https://youtu.be/abc", ""))
        assertTrue("same words under another chip", LiveSearch.shouldFire(last, "lo-fi", "Amapiano"))
        assertTrue(LiveSearch.DEBOUNCE_MS in 250..500)
    }

    @Test fun skeletonsFillTwoOrThreeRows() {
        assertEquals(6, LiveSearch.skeletons(false).size)
        assertEquals(9, LiveSearch.skeletons(true).size)
    }

    @Test fun wiring() {
        val s = src("mediaapps/MediaUi.kt")
        assertTrue("debounced effect keyed on the query", "LaunchedEffect(state.query)" in s && "delay(LiveSearch.DEBOUNCE_MS)" in s)
        assertTrue("stale search cancelled", "state.job?.cancel(); state.gen++" in s && "state.job = scope.launch" in s)
        assertTrue("stale results dropped", "if (gen != state.gen || !isActive) return@launch" in s)
        assertTrue("skeletons while loading", "SkeletonTile(" in s && "state.loading && state.results.isEmpty()" in s)
        assertFalse("no blank 'Searching…' line instead of cards", "MText(\"Searching\\u2026\"" in s)
        assertTrue("sources searched in parallel", "async {" in s)
        assertTrue("skeleton holds still on battery saver", "isPowerSaveMode" in s)
    }
}
