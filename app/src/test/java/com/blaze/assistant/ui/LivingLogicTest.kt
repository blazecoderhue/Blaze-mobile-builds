package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivingLogicTest {
    @Test fun defaultIsTheCurrentStillBackground() {
        assertEquals(LivingLogic.Bg.PAPER, LivingLogic.Bg.of(LivingLogic.DEFAULT))
        assertEquals(LivingLogic.Bg.PAPER, LivingLogic.Bg.of(null))
        assertEquals(LivingLogic.Bg.AURORA, LivingLogic.Bg.of("Aurora"))
        assertFalse(LivingLogic.Bg.PAPER.moves)
    }

    @Test fun phasesByLocalTime() {
        assertEquals(LivingLogic.Phase.NIGHT, LivingLogic.phase(2))
        assertEquals(LivingLogic.Phase.DAWN, LivingLogic.phase(6))
        assertEquals(LivingLogic.Phase.DAY, LivingLogic.phase(13))
        assertEquals(LivingLogic.Phase.SUNSET, LivingLogic.phase(18, 42))
        assertEquals(LivingLogic.Phase.NIGHT, LivingLogic.phase(22, 14))
        assertTrue(LivingLogic.skyIsDark(22)); assertFalse(LivingLogic.skyIsDark(12))
    }

    @Test fun weatherGreysTheSky() {
        assertEquals(LivingLogic.Weather.RAIN, LivingLogic.weatherOf("Heavy rain \u00b7 feels 18\u00b0"))
        assertEquals(LivingLogic.Weather.CLOUDY, LivingLogic.weatherOf("Partly cloudy"))
        assertEquals(LivingLogic.Weather.UNKNOWN, LivingLogic.weatherOf(null))
        assertTrue(LivingLogic.sky(13, 0).contentEquals(LivingLogic.sky(13, 0, LivingLogic.Weather.UNKNOWN)))
        assertNotEquals(LivingLogic.sky(13, 0)[0], LivingLogic.sky(13, 0, LivingLogic.Weather.STORM)[0])
        assertEquals(0xFF7F7F7F, LivingLogic.mix(0xFF000000, 0xFFFFFFFF, 0.5f))
    }

    @Test fun keepsStillWhenItShould() {
        assertTrue(LivingLogic.moving(LivingLogic.Bg.SKY, false, false, 80, false))
        assertFalse(LivingLogic.moving(LivingLogic.Bg.SKY, true, false, 80, false))      // Save battery
        assertFalse(LivingLogic.moving(LivingLogic.Bg.SKY, false, true, 80, false))      // power saver
        assertFalse(LivingLogic.moving(LivingLogic.Bg.SKY, false, false, 15, false))     // low battery
        assertTrue(LivingLogic.moving(LivingLogic.Bg.SKY, false, false, 15, true))       // low but charging
        assertFalse(LivingLogic.moving(LivingLogic.Bg.PAPER, false, false, 80, false))
    }

    @Test fun themeAndMusic() {
        assertTrue(LivingLogic.darkFor("auto", 23)); assertFalse(LivingLogic.darkFor("auto", 11)); assertTrue(LivingLogic.darkFor("dark", 11))
        assertEquals(0f, LivingLogic.musicPulse(false, true, 1.0))
        assertEquals(0f, LivingLogic.musicPulse(true, false, 1.0))
        assertTrue(LivingLogic.musicPulse(true, true, 0.09) > 0.2f)
        assertEquals(0xFF2F6BFF, LivingLogic.parseAccent("#FF2F6BFF")); assertEquals(null, LivingLogic.parseAccent(""))
    }
}
