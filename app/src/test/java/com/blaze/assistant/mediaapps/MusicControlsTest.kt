package com.blaze.assistant.mediaapps

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.33.0 Loop button in the notification cycles off -> all -> one -> off. */
class MusicControlsTest {
    @Test fun repeatCycles() {
        assertEquals(Player.REPEAT_MODE_ALL, MusicService.nextRepeat(Player.REPEAT_MODE_OFF))
        assertEquals(Player.REPEAT_MODE_ONE, MusicService.nextRepeat(Player.REPEAT_MODE_ALL))
        assertEquals(Player.REPEAT_MODE_OFF, MusicService.nextRepeat(Player.REPEAT_MODE_ONE))
    }
}
