package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.MusicCtl.Decision
import com.blaze.assistant.mediaapps.MusicCtl.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.6 P1 music: play / pause / next run locally; "play music" with nothing queued asks. */
class MusicCtlTest {
    private val idle = State(service = false, playing = false, queue = 0, savedQueue = 0, songs = 0)

    @Test fun playResumesTheQueue() {
        assertEquals("play", (MusicCtl.decide("play", State(true, false, 5, 0, 20, title = "Song A")) as Decision.Player).op)
        assertTrue(MusicCtl.decide("play", State(false, false, 0, 7, 20)) is Decision.ResumeSaved)
        assertTrue((MusicCtl.decide("play", State(true, true, 5, 0, 20, title = "A")) as Decision.Say).say.startsWith("Already playing"))
        assertEquals("play", (MusicCtl.decide("resume", State(true, false, 2, 0, 3)) as Decision.Player).op)
    }

    @Test fun pauseNextPrevious() {
        val on = State(true, true, 4, 0, 10, title = "B")
        assertEquals("pause", (MusicCtl.decide("pause", on) as Decision.Player).op)
        assertEquals("pause", (MusicCtl.decide("stop", on) as Decision.Player).op)
        assertEquals("next", (MusicCtl.decide("next", on) as Decision.Player).op)
        assertEquals("next", (MusicCtl.decide("skip", on) as Decision.Player).op)
        assertEquals("previous", (MusicCtl.decide("previous", on) as Decision.Player).op)
        assertEquals("Nothing is playing in Blaze Music.", (MusicCtl.decide("pause", idle) as Decision.Say).say)
    }

    @Test fun nothingQueuedAsksWhatToPlay() {
        val d = MusicCtl.decide("play", idle) as Decision.Say
        assertTrue(d.asks)
        assertTrue(d.say, d.say.contains("What should I play?"))
        val withSongs = MusicCtl.decide("play", idle.copy(songs = 42, lastList = "Daily Mix 1")) as Decision.Say
        assertTrue(withSongs.asks)
        assertTrue(withSongs.say, withSongs.say.contains("your last playlist, Daily Mix 1") && withSongs.say.contains("local music (42 songs)"))
        val onlyLocal = MusicCtl.decide("play", idle.copy(songs = 1)) as Decision.Say
        assertTrue(onlyLocal.say, onlyLocal.say.contains("(1 song)") && !onlyLocal.say.contains("last playlist"))
        assertTrue((MusicCtl.decide("next", idle.copy(songs = 3)) as Decision.Say).asks)
    }

    @Test fun nowPlaying() {
        assertEquals("Playing \u201cSong A\u201d in Blaze Music.", (MusicCtl.decide("now", State(true, true, 1, 0, 1, title = "Song A")) as Decision.Say).say)
        assertEquals("Nothing is playing in Blaze Music.", (MusicCtl.decide("now", idle) as Decision.Say).say)
    }
}
