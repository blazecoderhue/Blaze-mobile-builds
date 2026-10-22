package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.LinkParse
import com.blaze.assistant.mediaapps.store.StoreError
import com.blaze.assistant.mediaapps.store.StoreSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** v0.32.1 "Download by link". */
class LinkSourceTest {
    @Test fun youtubeIds() {
        val id = "dQw4w9WgXcQ"
        listOf("https://www.youtube.com/watch?v=$id", "https://youtu.be/$id?si=abc", "https://m.youtube.com/watch?feature=share&v=$id",
            "https://music.youtube.com/watch?v=$id&list=RD", "https://www.youtube.com/shorts/$id", "https://www.youtube.com/embed/$id",
            "https://youtube.com/live/$id?feature=x").forEach { assertEquals(it, id, LinkParse.youtubeId(it)) }
        assertNull(LinkParse.youtubeId("https://www.youtube.com/@channel"))
        assertNull(LinkParse.youtubeId("https://example.com/watch?v=$id"))
    }

    @Test fun pastedText() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ", LinkParse.firstUrl("check this https://youtu.be/dQw4w9WgXcQ!"))
        assertEquals("https://youtube.com/watch?v=dQw4w9WgXcQ", LinkParse.firstUrl("youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(LinkParse.firstUrl("no link here"))
    }

    @Test fun files() {
        assertEquals("audio/mpeg", LinkParse.extMime("https://x.org/a/My_Song-01.mp3?dl=1"))
        assertEquals("My Song 01", LinkParse.fileTitle("https://x.org/a/My_Song-01.mp3?dl=1"))
        assertEquals("https://x.org/media/clip.mp4", LinkParse.mediaInPage("<meta property=\"og:video\" content=\"/media/clip.mp4\">", "https://x.org/page"))
        assertNull(LinkParse.mediaInPage("<p>nothing</p>", "https://x.org/"))
    }

    @Test fun linkIsRegisteredButNotAChip() {
        assertTrue(StoreSources.get("link") != null)
        assertTrue(StoreSources.forKind("audio").none { it.id == "link" } && StoreSources.forKind("video").none { it.id == "link" })
    }

    private val player = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"title":"Song","author":"Artist","lengthSeconds":"200",
      "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/a.jpg"}]}},
      "streamingData":{"formats":[{"itag":18,"url":"https://r/18","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","bitrate":500000,"height":360,"qualityLabel":"360p"}],
      "adaptiveFormats":[
        {"itag":137,"url":"https://r/137","mimeType":"video/mp4; codecs=\"avc1.640028\"","bitrate":4000000,"height":1080,"qualityLabel":"1080p","contentLength":"90000000"},
        {"itag":136,"url":"https://r/136","mimeType":"video/mp4; codecs=\"avc1.4d401f\"","bitrate":2000000,"height":720,"qualityLabel":"720p"},
        {"itag":248,"url":"https://r/248","mimeType":"video/webm; codecs=\"vp9\"","bitrate":3000000,"height":1080},
        {"itag":313,"signatureCipher":"s=x","mimeType":"video/mp4; codecs=\"avc1\"","height":2160},
        {"itag":140,"url":"https://r/140","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000,"contentLength":"3200000"},
        {"itag":251,"url":"https://r/251","mimeType":"audio/webm; codecs=\"opus\"","bitrate":140000}]}}"""

    @Test fun youtubePlayer() {
        val d = LinkParse.ytDetail(player, "dQw4w9WgXcQ")
        assertEquals("Song", d.title); assertEquals(200, d.lengthSec); assertEquals("yt:dQw4w9WgXcQ", d.id)
        assertTrue(d.formats.none { it.key == "313" })
        val v = LinkParse.ytChoices("video", d)
        assertEquals(listOf(1080, 720, 360), v.map { it.height })
        assertEquals(listOf("137", "140"), v[0].keys); assertEquals("mp4", v[0].ext)
        assertEquals(listOf("18"), v[2].keys)
        val a = LinkParse.ytChoices("audio", d)
        assertEquals(listOf("140"), a[0].keys); assertEquals("m4a", a[0].ext)
        assertTrue(a.any { it.ext == "opus" })
    }

    @Test fun probeReadsPastTheFirstBytes() {
        assertEquals(0L, LinkParse.probeAt(500_000)); assertEquals(1_500_000L, LinkParse.probeAt(3_200_000)); assertEquals(1_500_000L, LinkParse.probeAt(0))
        assertEquals("VISIONOS", com.blaze.assistant.mediaapps.store.LinkYt.clients.first().name)
    }

    @Test fun youtubeRefusal() {
        try { LinkParse.ytDetail("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}""", "x"); fail() }
        catch (e: StoreError) { assertTrue(e.message!!.contains("bot")) }
    }

    @Test fun youtubeSearchParse() {
        val json = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
          {"itemSectionRenderer":{"contents":[
            {"videoRenderer":{"videoId":"abc123def45","title":{"runs":[{"text":"I'm The Sinner"}]},"ownerText":{"runs":[{"text":"Artist"}]},
              "lengthText":{"simpleText":"3:41"},"shortViewCountText":{"simpleText":"1.2M views"}}},
            {"videoRenderer":{"videoId":"live0000000","title":{"runs":[{"text":"Live now"}]},"badges":[{"metadataBadgeRenderer":{"label":"LIVE"}}]}}
          ]}},
          {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT"}}}}]}}}}}"""
        val p = LinkParse.ytSearch(json, "youtube")
        assertEquals(1, p.items.size)
        assertEquals("abc123def45", p.items[0].id); assertEquals(221, p.items[0].durationSec); assertEquals("Artist", p.items[0].author)
        assertEquals("1.2M views", p.items[0].views); assertEquals("NEXT", p.next)
    }
}
