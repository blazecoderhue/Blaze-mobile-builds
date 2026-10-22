package com.blaze.assistant.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.5: Home's live browser preview shows only while Blaze is working and has just used its browser. */
class BrowserLiveTest {
    private val now = 1_000_000L

    @Test fun showsWhileWorkingInTheBrowser() {
        assertTrue(BrowserLive.showing(BrowserLive.Step("Browsing news.google.com", now - 2_000), busy = true, now = now))
        assertTrue(BrowserLive.showing(BrowserLive.Step("Reading the page", now - BrowserLive.WINDOW_MS), busy = true, now = now))
    }

    @Test fun hidesOtherwise() {
        assertFalse("idle", BrowserLive.showing(BrowserLive.Step("Reading the page", now - 1_000), busy = false, now = now))
        assertFalse("never browsed", BrowserLive.showing(null, busy = true, now = now))
        assertFalse("browsed long ago", BrowserLive.showing(BrowserLive.Step("Reading the page", now - BrowserLive.WINDOW_MS - 1), busy = true, now = now))
    }

    @Test fun everyBrowserStepMarksIt() {
        val src = listOf(java.io.File("src/main/java/com/blaze/assistant/browser/BrowserTools.kt"), java.io.File("app/src/main/java/com/blaze/assistant/browser/BrowserTools.kt"))
            .first { it.exists() }.readText()
        assertTrue(src.contains("BrowserLive.mark(describe(name, a))"))
    }
}
