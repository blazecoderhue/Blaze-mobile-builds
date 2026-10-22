package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave A item 4 + 26: no swipe-seek or seek hint, no stuck M3 slider bar, Back from Video/Music goes to the chat. */
class WaveA1PlayerBackTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    @Test fun backStepsFromInnermostOut() {
        assertEquals(BackToChat.Step.EXIT_FULLSCREEN, BackToChat.step(true, "player"))
        assertEquals(BackToChat.Step.EXIT_FULLSCREEN, BackToChat.step(true, "library"))
        assertEquals(BackToChat.Step.COLLAPSE_PLAYER, BackToChat.step(false, "player"))
        for (t in listOf("library", "store", "", "search", "playlists")) assertEquals(t, BackToChat.Step.TO_CHAT, BackToChat.step(false, t))
    }

    @Test fun playingVideoStaysAsMiniPlayer() {
        assertTrue(BackToChat.keepVideo(playing = true, pipCapable = true))
        assertFalse(BackToChat.keepVideo(playing = false, pipCapable = true))
        assertFalse(BackToChat.keepVideo(playing = true, pipCapable = false))
    }

    @Test fun scrubMapsFingerToPosition() {
        assertEquals(0f, Scrub.frac(-20f, 300f), 0f)
        assertEquals(0.5f, Scrub.frac(150f, 300f), 1e-6f)
        assertEquals(1f, Scrub.frac(999f, 300f), 0f)
        assertEquals(0f, Scrub.frac(10f, 0f), 0f)
    }

    @Test fun videoHasNoSwipeSeekOrHint() {
        val v = src("mediaapps/VideoActivity.kt")
        assertFalse("seek hint text still shown", "Swipe sideways to seek" in v)
        assertFalse("free 2D drag (swipe-seek) still wired", "detectDragGestures(" in v)
        assertFalse("horizontal drag still seeks", Regex("""acc / size\.width\) \* 90_000""").containsMatchIn(v))
        assertTrue("vertical-only brightness/volume", "detectVerticalDragGestures(" in v)
    }

    @Test fun seekBarsAreTheThinScrubber() {
        for (f in listOf("mediaapps/VideoActivity.kt", "mediaapps/MusicActivity.kt")) {
            val s = src(f)
            assertFalse("$f still uses the M3 Slider (bar thumb read as a stuck white line)", Regex("""\bSlider\(frac""").containsMatchIn(s))
            assertTrue(f, "ScrubBar(frac" in s)
        }
    }

    @Test fun backGoesToChatFromVideoAndMusic() {
        val v = src("mediaapps/VideoActivity.kt")
        assertTrue("Video back arrow", "BackButton({ a.leaveToChat() }" in v)
        assertTrue("Video system back at library/store", "BackToChat.Step.TO_CHAT) { a.leaveToChat() }" in v)
        assertFalse("Video back arrow still just finishes", "BackButton({ a.finish() }" in v)
        val m = src("mediaapps/MusicActivity.kt")
        assertTrue("Music close", "onClose = { BackToChat.go(this) }" in m)
        assertTrue("Music system back at library/store", "BackToChat.Step.TO_CHAT) { onClose() }" in m)
        val b = src("mediaapps/BackToChat.kt")
        assertTrue("chat is brought forward, not re-created on top", "FLAG_ACTIVITY_CLEAR_TOP" in b && "FLAG_ACTIVITY_SINGLE_TOP" in b && "MainActivity::class.java" in b)
    }
}
