package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.StoreSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave A item 25: every track listed, Translate to any language, remembered language, offline with downloads, no "und". */
class WaveA3CaptionsTest {
    private val asr = StoreSubtitle("yt-a.en", "https://www.youtube.com/api/timedtext?v=abc&lang=en&kind=asr", "en", "English (auto-generated)", auto = true)
    private val manFr = StoreSubtitle("yt-.fr", "https://www.youtube.com/api/timedtext?v=abc&lang=fr", "fr", "French")
    private val file = StoreSubtitle("file-x", "file:///data/x.vtt", "", "my.srt")
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    @Test fun audioNamesAreReadable() {
        assertEquals("Original audio", Subs.audioName(null, "und", 0, 1))
        assertEquals("Audio 2", Subs.audioName("", "und", 1, 2))
        assertEquals("English", Subs.audioName(null, "en", 0, 1))
        assertEquals("French \u00b7 Commentary", Subs.audioName("Commentary", "fr", 0, 2))
        assertEquals("English", Subs.audioName("English", "en", 0, 1))
        for (c in listOf("und", "zxx", "", null)) assertFalse(Subs.audioName(c, c, 0, 1).contains("und"))
    }

    @Test fun translateMakesAYouTubeTrack() {
        assertTrue(Subs.canTranslate(asr)); assertFalse(Subs.canTranslate(file))
        val sw = Subs.translated(asr, "sw")
        assertTrue(sw.url.endsWith("&tlang=sw")); assertEquals("sw", sw.lang)
        assertEquals("Swahili (translated)", sw.name); assertTrue(sw.auto)
        assertEquals(1, Regex("tlang=").findAll(Subs.translated(sw, "ny").url).count())
        assertFalse("a translated track is not a base again", Subs.canTranslate(sw))
        assertEquals(manFr, Subs.translationBase(listOf(asr, manFr)))
        assertEquals(asr, Subs.translationBase(listOf(asr, file)))
        assertNull(Subs.translationBase(listOf(file)))
        assertTrue(Subs.translateTo.size >= 40)
        assertEquals("Chichewa (Nyanja)", Subs.translateName("ny"))
        assertEquals("Chinese (Simplified)", Subs.translateName("zh-Hans"))
    }

    @Test fun rememberedLanguageComesBackTranslatedWhenMissing() {
        assertEquals(manFr, Subs.pickOrTranslate(listOf(asr, manFr), "fr"))
        val es = Subs.pickOrTranslate(listOf(asr), "es")!!
        assertEquals("es", es.lang); assertTrue("tlang=es" in es.url)
        assertNull(Subs.pickOrTranslate(listOf(asr), "off"))
        assertNull(Subs.pickOrTranslate(listOf(asr), ""))
        assertNull(Subs.pickOrTranslate(listOf(file), "es"))
    }

    @Test fun downloadsKeepCaptionsForOffline() {
        val d = Subs.forDownload(listOf(asr, manFr), "sw", "en")
        assertEquals(listOf("sw", "en"), d.map { it.lang })
        assertEquals(listOf("en"), Subs.forDownload(listOf(asr), "", "en").map { it.lang })
        assertTrue(Subs.forDownload(listOf(file), "en", "en").isEmpty())
        val j = DlJob("dl1", "abc", "video", "t", "ch", 10, "", "720p", "c", "mp4", emptyList(), subs = Subs.indexJson(d))
        val back = DlLogic.fromJson(DlLogic.toJson(j))
        assertEquals(d.map { it.id }, Subs.indexParse(back.subs).map { it.id })
        assertEquals("", DlLogic.fromJson(DlLogic.toJson(j.copy(subs = ""))).subs)
    }

    @Test fun wiring() {
        val v = src("mediaapps/VideoActivity.kt")
        assertTrue("Translate to entry", "Option(\"Translate to\\u2026\"" in v)
        assertTrue("audio names", "Subs.audioName(f.label, f.language, n, audioTotal)" in v)
        assertTrue("caption safe area", "setBottomPaddingFraction(" in v)
        assertTrue("remembered language", "Subs.pickOrTranslate(subsAvailable.value, SubsLoader.pref(" in v)
        val dl = src("mediaapps/Downloads.kt")
        assertTrue("captions saved with downloads", "SubsLoader.save(c, uri.toString(), t, cues)" in dl && "Subs.forDownload(" in dl)
    }
}
