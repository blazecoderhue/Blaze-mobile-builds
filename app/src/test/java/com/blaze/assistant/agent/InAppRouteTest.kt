package com.blaze.assistant.agent

import com.blaze.assistant.agent.InAppRoute.Route
import com.blaze.assistant.phone.AppMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.6 P1 music: in-app tools before screen control; exact app launches only; music_ops never needs a card. */
class InAppRouteTest {
    @Test fun musicRequestsRouteToBlazeMusic() {
        for (t in listOf("play music", "Play music.", "please play some music", "play my music", "resume the music", "continue music", "Hey Blaze play music", "play blaze music"))
            assertEquals(t, Route.Music("play"), InAppRoute.match(t))
        for (t in listOf("pause music", "pause the music", "stop the music", "stop music")) assertEquals(t, Route.Music("pause"), InAppRoute.match(t))
        for (t in listOf("next song", "skip this song", "skip song", "play the next track", "skip")) assertEquals(t, Route.Music("next"), InAppRoute.match(t))
        for (t in listOf("previous song", "last track", "play the previous song")) assertEquals(t, Route.Music("previous"), InAppRoute.match(t))
        assertEquals(Route.Music("now"), InAppRoute.match("what's playing?"))
    }

    @Test fun otherAppsAndRealQuestionsStayWithTheModel() {
        for (t in listOf("play music on spotify", "play some music on YouTube", "play Despacito", "make me a song", "write music for my video",
                "pause", "stop", "what is music theory", "play music on my pc"))
            assertNull(t, InAppRoute.match(t))
    }

    @Test fun blazeScreensOpenAsViews() {
        assertEquals(Route.View("music"), InAppRoute.match("open Blaze Music"))
        assertEquals(Route.View("music"), InAppRoute.match("open music"))
        assertEquals(Route.View("projects"), InAppRoute.match("show my projects"))
        assertEquals(Route.View("settings"), InAppRoute.match("open blaze settings"))
        assertEquals(Route.View("design"), InAppRoute.match("open the design studio"))
        assertNull("phone settings stay a device action", InAppRoute.match("open settings"))
        assertNull(InAppRoute.match("open whatsapp"))
    }

    @Test fun shellLinesThatAreInAppToolsRunAsTools() {
        assertEquals("music_ops" to mapOf("action" to "play"), InAppRoute.toolFor("music_ops play"))
        assertEquals("music_ops" to mapOf("action" to "next"), InAppRoute.toolFor("music_ops --action=next"))
        assertEquals("open_view" to mapOf("view" to "music"), InAppRoute.toolFor("open_view music"))
        assertNull(InAppRoute.toolFor("ls -la"))
        assertNull(InAppRoute.toolFor("music_ops play; rm -rf ~"))
        assertNull(InAppRoute.toolFor("music_ops"))
    }

    @Test fun musicOpsTransportIsSafeInNormalMode() {
        for (c in listOf("music_ops play", "music_ops pause", "music_ops next", "music_ops previous", "music_ops resume"))
            assertEquals(c, CommandPolicy.Level.SAFE, CommandPolicy.shell(c))
        assertEquals(CommandPolicy.Decision.RUN, CommandPolicy.decide(CommandPolicy.shell("music_ops play"), CommandPolicy.key(CommandPolicy.Kind.SHELL, "music_ops play"), emptySet(), emptySet()))
        assertEquals(CommandPolicy.Level.ASK, CommandPolicy.shell("music_ops import https://x"))
    }

    private val apps = listOf("BlazeFX" to "com.blazefx.app", "WhatsApp" to "com.whatsapp", "Maps" to "com.google.android.apps.maps",
        "Blaze" to "com.blaze.assistant", "Spotify" to "com.spotify.music", "Settings" to "com.android.settings")

    @Test fun appsLaunchOnlyOnAnExactMatch() {
        assertNull("never a fuzzy Blaze* match", AppMatch.exact("Blaze", apps, self = "com.blaze.assistant"))
        assertNull(AppMatch.exact("Blaze Music", apps, self = "com.blaze.assistant"))
        assertNull(AppMatch.exact("blaze f", apps))
        assertEquals("com.whatsapp" to "WhatsApp", AppMatch.exact("whatsapp", apps))
        assertEquals("com.whatsapp" to "WhatsApp", AppMatch.exact("WhatsApp app", apps))
        assertEquals("com.blazefx.app" to "BlazeFX", AppMatch.exact("BlazeFX", apps))
        assertEquals("com.spotify.music" to "Spotify", AppMatch.exact("com.spotify.music", apps))
        assertNull("no partial label", AppMatch.exact("google maps", apps))
        assertTrue(AppMatch.near("google maps", apps).contains("Maps"))
        assertEquals("music", AppMatch.blazeView("Blaze Music"))
        assertEquals("settings", AppMatch.blazeView("blaze settings"))
        assertNull(AppMatch.blazeView("BlazeFX"))
    }
}
