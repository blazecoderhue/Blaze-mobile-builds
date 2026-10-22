package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.ArchiveFilmsSource
import com.blaze.assistant.mediaapps.store.ArchiveAudioSource
import com.blaze.assistant.mediaapps.store.ArchiveParse
import com.blaze.assistant.mediaapps.store.BlenderMoviesSource
import com.blaze.assistant.mediaapps.store.JamendoParse
import com.blaze.assistant.mediaapps.store.Licence
import com.blaze.assistant.mediaapps.store.StoreError
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.StoreSources
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.1: the free Store sources (Internet Archive, Blender open movies, Jamendo) and licence labels. */
class StoreSourcesTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource("store/$n")!!.readText()

    @Test fun licenceLabels() {
        assertEquals("CC BY 3.0", Licence.label("http://creativecommons.org/licenses/by/3.0/"))
        assertEquals("CC BY-NC-SA 3.0", Licence.label("http://creativecommons.org/licenses/by-nc-sa/3.0/us/"))
        assertEquals("Public domain", Licence.label("http://creativecommons.org/licenses/publicdomain/"))
        assertEquals("Public domain", Licence.label("http://creativecommons.org/publicdomain/mark/1.0/"))
        assertEquals("CC0 (public domain)", Licence.label("https://creativecommons.org/publicdomain/zero/1.0/"))
        assertEquals("", Licence.label(""))
        assertTrue(Licence.isFree("https://creativecommons.org/licenses/by/4.0/"))
        assertFalse(Licence.isFree("https://example.com/all-rights-reserved"))
        assertFalse(Licence.isFree(""))
    }

    @Test fun archiveSearch() {
        val p = ArchiveParse.search(res("ia-search.json"), "archive-audio", 1, 3, true)
        assertEquals(3, p.items.size)
        val a = p.items[0]
        assertEquals("OTRR_Dragnet_Singles", a.id)
        assertEquals("archive-audio", a.source)
        assertEquals("CC BY-NC-SA 3.0", a.licence)
        assertEquals("https://archive.org/services/img/OTRR_Dragnet_Singles", a.thumb)
        assertTrue(a.square)
        assertEquals("2", p.next)
    }

    @Test fun archiveSearchDropsUnlicensedUnlessDefault() {
        val json = """{"response":{"numFound":2,"docs":[{"identifier":"a","title":"A"},{"identifier":"b","title":["B"],"licenseurl":"http://creativecommons.org/licenses/by/3.0/","runtime":"1:30:00"}]}}"""
        val strict = ArchiveParse.search(json, "x", 1, 30, false)
        assertEquals(listOf("b"), strict.items.map { it.id })
        assertEquals(5400, strict.items[0].durationSec)
        assertEquals("", strict.next)
        val films = ArchiveParse.search(json, "x", 1, 30, false, "Public domain", requireFree = false)
        assertEquals(listOf("Public domain", "CC BY 3.0"), films.items.map { it.licence })
    }

    @Test fun archiveFilmFiles() {
        val d = ArchiveParse.detail(res("ia-sintel.json"), "video")
        assertEquals("Sintel", d.title); assertEquals("CC BY 3.0", d.licence); assertEquals(888, d.lengthSec)
        val c = ArchiveParse.videoChoices(d)
        assertTrue(c.isNotEmpty())
        assertTrue(c.all { it.ext == "mp4" })
        assertFalse(d.formats.any { it.key.endsWith(".ogv") || it.key.endsWith(".avi") })
        val def = ArchiveParse.defaultVideo(c)!!
        assertEquals(240, def.height)   // the 2048-wide originals are 872p: default stays ≤ 720p
        assertTrue(d.formats.first { it.key == def.keys[0] }.url.startsWith("https://archive.org/download/Sintel/"))
    }

    @Test fun archiveAlbumFiles() {
        val d = ArchiveParse.detail(res("ia-album.json"), "audio")
        assertEquals("CC BY-SA 4.0", d.licence)
        val c = ArchiveParse.audioChoices(d)
        assertEquals(listOf("01 First.mp3", "01 First.ogg", "01 First.flac", "02 Second.mp3"), c.map { it.keys[0] })
        assertEquals("First", c[0].title); assertEquals("mp3", c[0].ext); assertEquals("ogg", c[1].ext); assertEquals("flac", c[2].ext)
        assertEquals(200, ArchiveParse.seconds("200")); assertEquals(200, ArchiveParse.seconds("03:20")); assertEquals(180, ArchiveParse.seconds("3 minutes"))
        assertEquals("https://archive.org/download/id/a%20b/c%26d.mp3", ArchiveParse.fileUrl("id", "a b/c&d.mp3"))
    }

    @Test fun archiveQueries() {
        assertEquals("(jazz piano) AND mediatype:audio AND licenseurl:(*creativecommons.org* OR *publicdomain*)", ArchiveAudioSource.query("jazz: piano"))
        assertEquals("collection:feature_films AND mediatype:movies", ArchiveFilmsSource.query("  "))
        assertTrue(BlenderMoviesSource.query("sintel").startsWith("identifier:(BigBuckBunny_124 OR"))
        assertTrue(BlenderMoviesSource.query("sintel").endsWith(" AND (sintel)"))
    }

    @Test fun jamendo() {
        val json = """{"headers":{"status":"success","code":0,"results_count":2},"results":[
            {"id":"1","name":"Song","duration":185,"artist_name":"Ann","album_name":"LP","image":"https://img/1.jpg",
             "audio":"https://prod-1.storage.jamendo.com/?trackid=1&format=mp31&from=x","audiodownload":"https://prod-1.storage.jamendo.com/download/track/1/mp32/",
             "audiodownload_allowed":true,"license_ccurl":"http://creativecommons.org/licenses/by-nc-sa/3.0/"},
            {"id":"2","name":"Stream only","duration":60,"artist_name":"Bo","audio":"https://s/?trackid=2&format=mp31","audiodownload_allowed":false,"license_ccurl":""}]}"""
        val p = JamendoParse.search(json, 0, 2)
        assertEquals(listOf("1", "2"), p.items.map { it.id })
        assertEquals("CC BY-NC-SA 3.0", p.items[0].licence)
        assertEquals("Creative Commons", p.items[1].licence)
        assertEquals("2", p.next)
        val d = JamendoParse.detail(p.items[0])
        assertEquals(listOf("mp32", "mp31"), d.formats.map { it.key })
        assertEquals(listOf("High \u00b7 MP3 VBR", "Low \u00b7 MP3 96 kbps"), JamendoParse.choices(d).map { it.label })
        assertEquals(listOf("mp31"), JamendoParse.detail(p.items[1]).formats.map { it.key })
        assertEquals("https://s/?trackid=2&format=mp31", JamendoParse.withFormat("https://s/?trackid=2&format=mp32", "mp31"))
        assertEquals("https://s/x?format=mp31", JamendoParse.withFormat("https://s/x", "mp31"))
    }

    @Test fun youtubeIsAPluginFoundByName() {
        assertNull(StoreSources.plugin("com.example.NotThere"))
        // v0.33.0: YouTube is built in and first in both stores
        assertEquals(listOf("youtube", "jamendo", "archive-audio"), StoreSources.forKind("audio").map { it.id }.take(3))
        assertEquals(listOf("youtube", "archive-films", "blender"), StoreSources.forKind("video").map { it.id }.take(3))
    }

    @Test(expected = StoreError::class) fun jamendoBadKey() {
        JamendoParse.search("""{"headers":{"status":"failed","code":5,"error_message":"Your credential is not authorized."}}""", 0, 30)
    }
}
