package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.LinkParse
import com.blaze.assistant.mediaapps.store.StoreSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.34.0: YouTube caption tracks reach the player; caption parsing and the subtitle menu's choices. */
class SubsTest {
    private val player = """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"title":"T","author":"A","lengthSeconds":"60"},
         "streamingData":{"formats":[{"itag":18,"url":"https://x/v","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","height":360,"bitrate":500000}]},
         "captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
           {"baseUrl":"https://www.youtube.com/api/timedtext?v=abc&lang=en","name":{"simpleText":"English"},"vssId":".en","languageCode":"en"},
           {"baseUrl":"https://www.youtube.com/api/timedtext?v=abc&lang=en&kind=asr&fmt=srv3","name":{"runs":[{"text":"English (auto-generated)"}]},"vssId":"a.en","languageCode":"en","kind":"asr"},
           {"baseUrl":"https://www.youtube.com/api/timedtext?v=abc&lang=fr","name":{"simpleText":"French"},"vssId":".fr","languageCode":"fr"},
           {"name":{"simpleText":"Broken"},"vssId":".de","languageCode":"de"}
         ]}}}
    """.trimIndent()

    @Test fun ytTracksReadsManualAndAuto() {
        val t = Subs.ytTracks(player)
        assertEquals(3, t.size)
        assertEquals("yt-.en", t[0].id); assertFalse(t[0].auto); assertEquals("en", t[0].lang)
        assertTrue(t[1].auto); assertEquals("English (auto-generated)", Subs.label(t[1]))
        assertEquals("French", Subs.label(t[2]))
        assertTrue(Subs.ytTracks("{}").isEmpty())
        assertTrue(Subs.ytTracks("not json").isEmpty())
    }

    @Test fun ytDetailCarriesSubtitlesToThePlayer() {
        val d = LinkParse.ytDetail(player, "abc")
        assertEquals(3, d.subtitles.size)
        assertEquals("fr", d.subtitles[2].lang)
    }

    @Test fun formatParamReplaced() {
        assertEquals("https://y/t?v=1&lang=en&fmt=json3", Subs.withFormat("https://y/t?v=1&lang=en", "json3"))
        assertEquals("https://y/t?v=1&kind=asr&fmt=vtt", Subs.withFormat("https://y/t?v=1&fmt=srv3&kind=asr", "vtt"))
        assertEquals("https://y/t?fmt=vtt", Subs.withFormat("https://y/t", "vtt"))
    }

    @Test fun clockParses() {
        assertEquals(62_345, Subs.clockMs("00:01:02,345"))
        assertEquals(62_345, Subs.clockMs("01:02.345"))
        assertEquals(3_723_400, Subs.clockMs("1:02:03.4"))
        assertEquals(62_500, Subs.clockMs("62.5"))
        assertEquals(-1, Subs.clockMs("abc"))
        assertEquals("01:02:03.004", Subs.vttClock(3_723_004))
    }

    @Test fun srtParses() {
        val srt = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\n{\\an8}Top\r\n\r\n3\r\n00:00:05,000 --> 00:00:04,000\r\nbad\r\n"
        val c = Subs.parse(srt)
        assertEquals(2, c.size)
        assertEquals(Cue(1000, 2500, "Hello\nthere"), c[0])
        assertEquals("Top", c[1].text)
    }

    @Test fun vttParsesAndSkipsNotes() {
        val vtt = "WEBVTT Kind: captions\n\nNOTE a comment\n--> not a cue\n\nSTYLE\n::cue { color: red }\n\nc1\n00:00.500 --> 00:02.000 align:start position:0%\nOne <c.yellow>two</c>\n\n00:00:03.000 --> 00:00:04.000\nThree\n"
        val c = Subs.parse(vtt)
        assertEquals(listOf(Cue(500, 2000, "One two"), Cue(3000, 4000, "Three")), c)
    }

    @Test fun autoCaptionVttRollingLinesDeduped() {
        val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nhello world\n\n00:00:02.000 --> 00:00:02.010\nhello world\n\n" +
            "00:00:02.010 --> 00:00:04.000\nhello world\nhow are<00:00:03.000><c> you</c>\n"
        val c = Subs.parseVtt(vtt)
        assertEquals(2, c.size)
        assertEquals("hello world", c[0].text)
        assertEquals("how are you", c[1].text)
    }

    @Test fun json3ParsesAndClipsOverlaps() {
        val j = """{"events":[{"tStartMs":0,"dDurationMs":1000},
            {"tStartMs":100,"dDurationMs":3000,"segs":[{"utf8":"Hi"},{"utf8":" there","tOffsetMs":400}]},
            {"tStartMs":2000,"dDurationMs":1500,"aAppend":1,"segs":[{"utf8":"\n"}]},
            {"tStartMs":2500,"dDurationMs":1500,"segs":[{"utf8":"Next &amp; last"}]}]}"""
        val c = Subs.parse(j)
        assertEquals(listOf(Cue(100, 2500, "Hi there"), Cue(2500, 4000, "Next & last")), c)
    }

    @Test fun xmlFormatsParse() {
        val srv1 = """<?xml version="1.0"?><transcript><text start="1.5" dur="2">It&amp;#39;s &lt;fine&gt;</text><text start="4" dur="1">ok</text></transcript>"""
        assertEquals(listOf(Cue(1500, 3500, "It's <fine>"), Cue(4000, 5000, "ok")), Subs.parse(srv1))
        val srv3 = """<timedtext format="3"><body><p t="0" d="900">a<s>b</s></p><p t="1000" d="500">c</p></body></timedtext>"""
        assertEquals(listOf(Cue(0, 900, "ab"), Cue(1000, 1500, "c")), Subs.parse(srv3))
    }

    @Test fun vttRoundTrip() {
        val cues = listOf(Cue(0, 1500, "One"), Cue(61_000, 62_250, "Two\nlines"))
        val vtt = Subs.toVtt(cues)
        assertTrue(vtt.startsWith("WEBVTT\n\n1\n00:00:00.000 --> 00:00:01.500\nOne"))
        assertEquals(cues, Subs.parse(vtt))
        assertEquals(cues, Subs.parse(Subs.toSrt(cues)))
        assertTrue(Subs.parse("hello").isEmpty())
    }

    private val en = StoreSubtitle("yt-.en", "u", "en", "English")
    private val enAuto = StoreSubtitle("yt-a.en", "u", "en", "English", auto = true)
    private val fr = StoreSubtitle("yt-.fr", "u", "fr", "French")
    private val ptBr = StoreSubtitle("yt-.pt-BR", "u", "pt-BR", "Portuguese (Brazil)")

    @Test fun pickFollowsSavedChoice() {
        val all = listOf(enAuto, fr, en)
        assertNull(Subs.pick(all, ""))       // never chosen: stays off
        assertNull(Subs.pick(all, "off"))
        assertEquals(en, Subs.pick(all, "en"))      // manual beats auto
        assertEquals(enAuto, Subs.pick(listOf(enAuto, fr), "en-GB"))
        assertEquals(ptBr, Subs.pick(listOf(en, ptBr), "pt"))
        assertNull(Subs.pick(all, "de"))
    }

    @Test fun orderPutsViewerLanguageAndManualFirst() {
        assertEquals(listOf(fr, en, enAuto), Subs.ordered(listOf(enAuto, en, fr), "fr"))
        assertEquals(listOf(en, enAuto, fr), Subs.ordered(listOf(fr, enAuto, en), "en"))
        assertEquals(listOf(en, enAuto), Subs.prefetch(listOf(fr, enAuto, en), "en", "en"))
    }

    @Test fun menuMergesLiveTracksWithUnloaded() {
        val live = listOf(Subs.MenuItem("yt-.en", "English", "en", true, true))
        val m = Subs.menu(live, listOf(en, fr, enAuto), "en")
        assertEquals(listOf("yt-.en", "yt-a.en", "yt-.fr"), m.map { it.id })
        assertTrue(m[0].loaded && m[0].selected)
        assertFalse(m[2].loaded)
        // Live updates: the menu is recomputed from whatever the player reports now.
        assertEquals(3, Subs.menu(emptyList(), listOf(en, fr, enAuto), "en").size)
    }

    @Test fun savedIndexRoundTrips() {
        val l = listOf(en, enAuto.copy(url = "file:///x/a.vtt"))
        assertEquals(l, Subs.indexParse(Subs.indexJson(l)))
        assertTrue(Subs.indexParse("garbage").isEmpty())
        assertEquals(20, Subs.folderFor("content://media/1").length)
        assertTrue(Subs.isSubtitleFile("Movie.SRT")); assertFalse(Subs.isSubtitleFile("a.mp4"))
    }
}
