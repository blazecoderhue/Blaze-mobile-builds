package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.34.0 Blaze Music: smart playlists, mixes, Wrapped, DJ, EQ, fades, tags, covers and playlist import parsing. */
class MusicSmartTest {
    private val day = 86_400_000L
    private val now = 1000 * day
    private fun s(k: String, artist: String = "A", added: Long = 0) = Track(k, "u$k", "Song $k", artist, addedAt = added)
    private fun e(k: String, daysAgo: Int, ms: Long = 200_000) = PlayEvent(k, now - daysAgo * day, ms)

    @Test fun statsCountRealPlaysOnly() {
        val st = MusicSmart.stats(listOf(e("a", 1), e("a", 2, 10_000), e("b", 3)))
        assertEquals(1, st["a"]!!.plays); assertEquals(210_000, st["a"]!!.totalMs); assertEquals(now - day, st["a"]!!.lastAt)
    }

    @Test fun smartLists() {
        val songs = listOf(s("a", added = now - 2 * day), s("b"), s("c"), s("d"))
        val ev = listOf(e("a", 1), e("a", 2), e("a", 3), e("b", 50), e("b", 60), e("b", 70))
        val l = MusicSmart.smartLists(songs, ev, listOf("c", "zz"), now).associateBy { it.id }
        assertEquals(listOf("a", "b"), l["smart:top"]!!.keys)
        assertEquals(listOf("a"), l["smart:repeat"]!!.keys)
        assertEquals(listOf("a"), l["smart:new"]!!.keys)
        assertEquals(listOf("b"), l["smart:forgotten"]!!.keys)
        assertEquals(setOf("c", "d"), l["smart:never"]!!.keys.toSet())
        assertEquals(listOf("c"), l["smart:liked"]!!.keys)
    }

    @Test fun dailyMixesStartFromTopArtistsAndChangeDaily() {
        val songs = (1..12).map { s("x$it", "X") } + (1..12).map { s("y$it", "Y") } + (1..6).map { s("z$it", "Z") }
        val ev = (1..12).map { e("x$it", 1, 400_000) } + (1..12).map { e("y$it", 1, 100_000) }
        val m = MusicSmart.dailyMixes(songs, ev, 10)
        assertEquals(2, m.size)
        assertEquals("X and more", m[0].about)
        assertTrue(m[0].keys.count { it.startsWith("x") } >= 6)
        assertEquals(m, MusicSmart.dailyMixes(songs, ev, 10))
        assertTrue(m[0].keys != MusicSmart.dailyMixes(songs, ev, 11)[0].keys)
        assertTrue(MusicSmart.dailyMixes(songs, emptyList(), 1).isEmpty())
    }

    @Test fun wrapped() {
        val songs = listOf(s("a", "Ann"), s("b", "Bob"))
        val ev = listOf(e("a", 1, 180_000), e("a", 2, 180_000), e("b", 3, 240_000), e("b", 400, 240_000))
        val w = MusicSmart.wrapped(ev, songs, now - 30 * day, now + 1, "This month") { 21 }!!
        assertEquals(10, w.minutes); assertEquals(3, w.plays); assertEquals("a", w.topSongs[0].first.key)
        assertEquals("Ann" to 6L, w.topArtists[0]); assertEquals(21, w.busiestHour); assertEquals(2, w.artists)
        assertNull(MusicSmart.wrapped(ev, songs, 0, 1, "x") { 0 })
    }

    @Test fun djAvoidsRecentAndPrefersLoved() {
        val songs = (1..30).map { s("k$it") }
        val ev = (1..5).map { e("k30", it) }
        val picks = MusicSmart.djPicks(songs, ev, setOf("k29"), listOf("k1", "k2"), 5, 7)
        assertEquals(5, picks.size); assertTrue(picks.none { it.key == "k1" || it.key == "k2" })
        assertEquals(setOf("k30", "k29"), picks.take(2).map { it.key }.toSet())
    }

    @Test fun eqLevelsInterpolateAndClamp() {
        assertEquals(listOf(0, 0, 0, 0, 0), MusicSmart.bandLevels("Flat", listOf(60, 230, 910, 3600, 14000), -1500, 1500))
        assertEquals(listOf(600, 350, 0, 0, 0), MusicSmart.bandLevels("Bass boost", listOf(60, 230, 910, 3600, 14000), -1500, 1500))
        assertEquals(listOf(500), MusicSmart.bandLevels("Bass boost", listOf(30), -500, 500))
        val mid = MusicSmart.bandLevels("Bass boost", listOf(118), -1500, 1500)[0]
        assertTrue(mid in 351..599)
        assertEquals(listOf(0), MusicSmart.bandLevels("nope", listOf(1000), -100, 100))
    }

    @Test fun fades() {
        assertEquals(1f, MusicSmart.fadeVolume(50_000, 200_000, 0))
        assertEquals(0.5f, MusicSmart.fadeVolume(2_500, 200_000, 5_000), 1e-4f)
        assertEquals(0.2f, MusicSmart.fadeVolume(199_000, 200_000, 5_000), 1e-4f)
        assertEquals(1f, MusicSmart.fadeVolume(100_000, 200_000, 5_000))
        assertEquals(1f, MusicSmart.fadeVolume(1_000, 10_000, 5_000))   // too short to fade
        assertEquals(1f, MusicSmart.sleepVolume(60_000)); assertEquals(0.5f, MusicSmart.sleepVolume(15_000), 1e-4f); assertEquals(0f, MusicSmart.sleepVolume(0))
    }

    @Test fun tagFixer() {
        assertEquals("Coldplay" to "Yellow", MusicSmart.fixTags("Coldplay - Yellow (Official Video)", "ColdplayVEVO"))
        assertEquals("Coldplay" to "Yellow", MusicSmart.fixTags("Yellow", "Coldplay - Topic"))
        assertEquals("Burna Boy" to "Last Last", MusicSmart.fixTags("Burna Boy - Last Last [Official Music Video]", "Burna Boy"))
        assertEquals("Some Channel" to "Song Title", MusicSmart.fixTags("Song Title | Lyrics", "Some Channel"))
        assertEquals("Coldplay" to "Yellow", MusicSmart.fixTags("Yellow - Coldplay", "Coldplay"))
        assertEquals("Sia" to "Chandelier", MusicSmart.fixTags("Sia \u2013 Chandelier (Lyrics) [4K]", ""))
    }

    @Test fun itunesCover() {
        assertEquals("https://is1.mzstatic.com/x/600x600bb.jpg", MusicSmart.itunesArt("""{"resultCount":1,"results":[{"artworkUrl100":"https://is1.mzstatic.com/x/100x100bb.jpg"}]}"""))
        assertNull(MusicSmart.itunesArt("""{"results":[]}""")); assertNull(MusicSmart.itunesArt("x"))
    }

    @Test fun playlistImportParsing() {
        assertEquals("PLabcdefghij123", MusicSmart.youtubePlaylistId("https://www.youtube.com/watch?v=x&list=PLabcdefghij123&index=2"))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", MusicSmart.spotifyPlaylistId("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=1"))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", MusicSmart.spotifyPlaylistId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(MusicSmart.spotifyPlaylistId("https://open.spotify.com/track/abc"))
        val html = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Hits",
            "trackList":[{"title":"Yellow","subtitle":"Coldplay"},{"title":"Halo","subtitle":"Beyonc\u00e9"},{"title":""}]}}}}}}</script></html>"""
        assertEquals(listOf("Coldplay - Yellow", "Beyonc\u00e9 - Halo") to "Hits", MusicSmart.spotifyEmbed(html))
        assertEquals(emptyList<String>() to "", MusicSmart.spotifyEmbed("<html></html>"))
        val yt = """{"metadata":{"playlistMetadataRenderer":{"title":"Mix"}},"contents":[{"playlistVideoRenderer":{"videoId":"aaaaaaaaaaa","title":{"runs":[{"text":"Yellow"}]},
            "shortBylineText":{"runs":[{"text":"Coldplay"}]}}},{"playlistVideoRenderer":{"videoId":"bbbbbbbbbbb","title":{"simpleText":"Sia - Chandelier"},"shortBylineText":{"runs":[{"text":"Sia"}]}}},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT"}}}}]}"""
        val (items, next, name) = MusicSmart.ytPlaylist(yt)
        assertEquals(listOf("aaaaaaaaaaa" to "Coldplay - Yellow", "bbbbbbbbbbb" to "Sia - Chandelier"), items)
        assertEquals("NEXT", next); assertEquals("Mix", name)
    }

    @Test fun eventsRoundTrip() {
        val ev = listOf(PlayEvent("a", 1, 2), PlayEvent("b", 3, 4))
        assertEquals(ev, MusicSmart.eventsParse(MusicSmart.eventsJson(ev)))
        assertTrue(MusicSmart.eventsParse("nope").isEmpty())
    }
}
