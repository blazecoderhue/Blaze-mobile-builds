package com.blaze.assistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.24.4: the one combined status notification. */
class StatusNoteTest {
    @Test fun nothingRunningMeansNoNotification() {
        assertNull(StatusNote.line(StatusNote.Input()))
    }

    @Test fun agentWorkSaysWhatItIsDoing() {
        val l = StatusNote.line(StatusNote.Input(busy = true, step = "Reading the page"))!!
        assertEquals("Browsing…", l.title); assertEquals("Reading the page", l.text); assertEquals(-1, l.progress); assertTrue(l.stop)
        assertEquals("Thinking…", StatusNote.line(StatusNote.Input(busy = true))!!.title)
        assertEquals("Thinking…", StatusNote.verb("Writing the reply"))
        assertEquals("Browsing…", StatusNote.verb("Opening github.com"))
        assertEquals("Downloading…", StatusNote.verb("Downloading the file"))
    }

    @Test fun updateDownloadShowsPercentWithStop() {
        val l = StatusNote.line(StatusNote.Input(updatePct = 42, updateLabel = "Blaze v0.24.4"))!!
        assertEquals("Downloading update 42%", l.title); assertEquals(42, l.progress); assertTrue(l.stop)
        assertEquals("Checking for an update…", StatusNote.line(StatusNote.Input(updatePct = -1, updateLabel = "Checking for an update…"))!!.title)
    }

    @Test fun workAndUpdateShareOneLine() {
        val l = StatusNote.line(StatusNote.Input(busy = true, step = "Writing the reply", updatePct = 10))!!
        assertEquals("Thinking…", l.title)
        assertEquals("Writing the reply · Downloading update 10%", l.text)
    }

    @Test fun needsYouWins() {
        val l = StatusNote.line(StatusNote.Input(busy = true, step = "Searching", needs = "Which Gloria?"))!!
        assertEquals("Needs you: Which Gloria?", l.title); assertNull(l.progress); assertTrue(l.stop)
        assertEquals("Tap to answer", StatusNote.line(StatusNote.Input(needs = "Sign in to github.com"))!!.text)
    }

    @Test fun notifierStateFeedsTheLine() {
        Notifier.resetStatus()
        // v0.45.18 ack/background: "Blaze is working" is gone (WORKING_NOTE=false), so working() feeds no status line.
        Notifier.working("Opening youtube.com")
        assertNull(StatusNote.line(Notifier.statusInput()))
        Notifier.update(55, "Blaze v0.24.4")
        assertEquals("Downloading update 55%", StatusNote.line(Notifier.statusInput())!!.title)
        Notifier.doneWorking()
        assertEquals("Downloading update 55%", StatusNote.line(Notifier.statusInput())!!.title)
        Notifier.update(null)
        assertNull(StatusNote.line(Notifier.statusInput()))
        // On screen: agent work isn't duplicated in the shade, a download still is.
        Notifier.visible(true); Notifier.working("Thinking"); assertNull(StatusNote.line(Notifier.statusInput()))
        Notifier.resetStatus()
    }
}
