package com.blaze.assistant.media

import com.blaze.assistant.media.GalleryModel.Entry
import com.blaze.assistant.media.GalleryModel.Sort
import com.blaze.assistant.media.GalleryModel.Src
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GalleryModelTest {
    private fun e(id: String, title: String = id, kind: String = "image", src: String = Src.BLAZE, time: Long = 0, size: Long = 0, name: String = "$title.jpg") =
        Entry(id = id, name = name, title = title, kind = kind, src = src, time = time, size = size)

    // ---------------------------------------------------------------- sorting

    @Test fun sortsByDateNewestFirstByDefault() {
        val l = listOf(e("a", time = 1), e("b", time = 3), e("c", time = 2))
        assertEquals(listOf("b", "c", "a"), GalleryModel.sort(l, Sort.DATE).map { it.id })
        assertEquals(listOf("a", "c", "b"), GalleryModel.sort(l, Sort.DATE, asc = true).map { it.id })
    }

    @Test fun sortsByNameNaturallyAndCaseInsensitively() {
        val l = listOf(e("1", "track 10"), e("2", "Track 2"), e("3", "apple"), e("4", "Banana"))
        assertEquals(listOf("apple", "Banana", "Track 2", "track 10"), GalleryModel.sort(l, Sort.NAME).map { it.title })
        assertEquals(listOf("track 10", "Track 2", "Banana", "apple"), GalleryModel.sort(l, Sort.NAME, asc = false).map { it.title })
    }

    @Test fun sortsBySizeBiggestFirstAndTiesGoNewestFirst() {
        val l = listOf(e("a", size = 10, time = 1), e("b", size = 30, time = 1), e("c", size = 10, time = 5))
        assertEquals(listOf("b", "c", "a"), GalleryModel.sort(l, Sort.SIZE).map { it.id })
        assertEquals(listOf("c", "a", "b"), GalleryModel.sort(l, Sort.SIZE, asc = true).map { it.id })
    }

    @Test fun naturalCompareHandlesLeadingZerosAndPrefixes() {
        assertTrue(GalleryModel.naturalCompare("img2", "img10") < 0)
        assertTrue(GalleryModel.naturalCompare("img002", "img10") < 0)
        assertEquals(0, GalleryModel.naturalCompare("abc", "abc"))
        assertTrue(GalleryModel.naturalCompare("abc", "abcd") < 0)
    }

    // ---------------------------------------------------------------- tabs and search

    @Test fun tabsFilterByKind() {
        val l = listOf(e("i"), e("a", kind = "audio"), e("v", kind = "video"))
        assertEquals(listOf("i"), GalleryModel.forTab(l, "images").map { it.id })
        assertEquals(listOf("a"), GalleryModel.forTab(l, "music").map { it.id })
        assertEquals(listOf("v"), GalleryModel.forTab(l, "videos").map { it.id })
        assertEquals(3, GalleryModel.forTab(l, "all").size)
        assertEquals(listOf("all", "albums", "images", "music", "videos"), GalleryModel.tabs.map { it.first })
    }

    @Test fun searchMatchesEveryWordInTitleOrName() {
        val l = listOf(e("1", "Lion in sunglasses", name = "lion_42.png"), e("2", "Afro beat", kind = "audio", name = "afro.wav"), e("3", "Sunset", name = "sun.jpg"))
        assertEquals(listOf("1"), GalleryModel.search(l, "lion SUN").map { it.id })
        assertEquals(listOf("1", "3"), GalleryModel.search(l, "sun").map { it.id })
        assertEquals(listOf("2"), GalleryModel.search(l, ".wav").map { it.id })
        assertEquals(3, GalleryModel.search(l, "   ").size)
        assertTrue(GalleryModel.search(l, "zebra").isEmpty())
    }

    // ---------------------------------------------------------------- sources and grouping

    @Test fun sourceComesFromTheFolder() {
        assertEquals(Src.MUSIC, GalleryModel.sourceOf("Music/Blaze/", "audio"))
        assertEquals(Src.MUSIC, GalleryModel.sourceOf("Blaze/Music/", "audio"))
        assertEquals(Src.MUSIC, GalleryModel.sourceOf("Download/Blaze/Music/", "audio"))
        assertEquals(Src.VIDEOS, GalleryModel.sourceOf("Movies/Blaze/", "video"))
        assertEquals(Src.VIDEOS, GalleryModel.sourceOf("Blaze/Videos", "video"))
        assertEquals(Src.DOWNLOADS, GalleryModel.sourceOf("Download/Blaze/", "image"))
        assertEquals(Src.PICTURES, GalleryModel.sourceOf("Pictures/Blaze/", "image"))
        assertEquals(Src.SCREENSHOTS, GalleryModel.sourceOf("Pictures/Screenshots/", "image"))
        assertEquals(Src.SCREENSHOTS, GalleryModel.sourceOf("DCIM/Screenshots/", "image"))
        assertEquals(Src.CAMERA, GalleryModel.sourceOf("DCIM/Camera/", "image"))
        assertEquals(Src.OTHER, GalleryModel.sourceOf("Pictures/WhatsApp/", "image"))
    }

    @Test fun autoAlbumsGroupAndSkipEmptyOnes() {
        val l = listOf(
            e("gen", src = Src.BLAZE, time = 5), e("saved", src = Src.PICTURES, time = 1),
            e("beat", kind = "audio", src = Src.BLAZE, time = 2), e("song", kind = "audio", src = Src.MUSIC, time = 9),
            e("clip", kind = "video", src = Src.VIDEOS), e("dl", src = Src.DOWNLOADS), e("shot", src = Src.SCREENSHOTS),
        )
        val a = GalleryModel.autoAlbums(l).associate { it.name to it.items.map { x -> x.id } }
        assertEquals(listOf("Generated images", "Music", "Videos", "Downloads", "Screenshots"), GalleryModel.autoAlbums(l).map { it.name })
        assertEquals(listOf("gen", "saved"), a["Generated images"])
        assertEquals(listOf("song", "beat"), a["Music"])   // newest first
        assertEquals(listOf("clip"), a["Videos"])
        assertEquals(listOf("dl"), a["Downloads"])
        assertNull(a["Camera"])
    }

    @Test fun mergeDropsThePhoneCopyOfABlazePicture() {
        val blaze = listOf(e("file:/g/lion.jpg", name = "lion.jpg", size = 100))
        val phone = listOf(e("content://1", name = "lion.jpg", size = 100, src = Src.PICTURES), e("content://2", name = "lion.jpg", size = 99, src = Src.PICTURES),
            e("content://3", name = "song.mp3", kind = "audio", src = Src.MUSIC), e("content://3", name = "song.mp3", kind = "audio", src = Src.MUSIC))
        assertEquals(listOf("file:/g/lion.jpg", "content://2", "content://3"), GalleryModel.merge(blaze, phone).map { it.id })
    }

    // ---------------------------------------------------------------- labels

    @Test fun durationAndSizeLabels() {
        assertEquals("", GalleryModel.duration(0))
        assertEquals("0:05", GalleryModel.duration(4_600))
        assertEquals("5:00", GalleryModel.duration(300_000))
        assertEquals("1:02:03", GalleryModel.duration(3_723_000))
        assertEquals("512 B", GalleryModel.size(512))
        assertEquals("2 KB", GalleryModel.size(2048))
        assertEquals("1.5 MB", GalleryModel.size(1_572_864))
    }

    @Test fun cleanNameKeepsTheExtension() {
        assertEquals("My lion.jpg", GalleryModel.cleanName("  My lion ", "lion_1.jpg"))
        assertEquals("My lion.jpg", GalleryModel.cleanName("My lion.jpg", "lion_1.jpg"))
        assertEquals("a_b.mp3", GalleryModel.cleanName("a/b", "x.mp3"))
        assertNull(GalleryModel.cleanName("   ", "x.mp3"))
        assertNull(GalleryModel.cleanName("..", "x.mp3"))
    }

    // ---------------------------------------------------------------- album store

    private fun tmp(): File = Files.createTempDirectory("albums").toFile().resolve("gallery_albums.json")

    @Test fun createRenameDeleteAndPersist() {
        val f = tmp()
        val s = AlbumStore(f)
        val trip = s.create("  Trip  ", now = 1)!!
        assertEquals("Trip", trip.name)
        assertEquals("trip 2", s.create("trip", now = 2)!!.name)       // unique, case-insensitive
        assertNull(s.create("   "))
        assertTrue(s.rename(trip.id, "Lusaka trip"))
        assertFalse(s.rename(trip.id, " "))
        assertFalse(s.rename("nope", "x"))
        val again = AlbumStore(f)
        assertEquals(listOf("Lusaka trip", "trip 2"), again.albums.map { it.name })
        assertTrue(again.delete(trip.id))
        assertFalse(again.delete(trip.id))
        assertEquals(listOf("trip 2"), AlbumStore(f).albums.map { it.name })
    }

    @Test fun renameToItsOwnNameIsAllowed() {
        val s = AlbumStore(null)
        val a = s.create("Beats", now = 1)!!
        assertTrue(s.rename(a.id, "beats"))
        assertEquals("beats", s.get(a.id)!!.name)
    }

    @Test fun addRemoveAndMoveItems() {
        val s = AlbumStore(tmp())
        val a = s.create("A", now = 1)!!; val b = s.create("B", now = 2)!!
        assertTrue(s.add(a.id, listOf("x", "y", "x")))
        s.add(a.id, listOf("y", "z"))
        assertEquals(listOf("x", "y", "z"), s.get(a.id)!!.items)
        assertTrue(s.move(a.id, b.id, listOf("y", "z")))
        assertEquals(listOf("x"), s.get(a.id)!!.items)
        assertEquals(listOf("y", "z"), s.get(b.id)!!.items)
        assertFalse(s.move(a.id, a.id, listOf("x")))
        assertFalse(s.move(a.id, "missing", listOf("x")))
        s.remove(b.id, listOf("y"))
        assertEquals(listOf("z"), s.get(b.id)!!.items)
    }

    @Test fun sameTimestampStillGivesDistinctIds() {
        val s = AlbumStore(null)
        val a = s.create("A", now = 7)!!; val b = s.create("B", now = 7)!!
        assertTrue(a.id != b.id)
    }

    @Test fun renamedAndDeletedItemsFollowThrough() {
        val s = AlbumStore(tmp())
        val a = s.create("A", listOf("file:/g/a.jpg", "content://9"), now = 1)!!
        s.renameItem("file:/g/a.jpg", "file:/g/b.jpg")
        assertEquals(listOf("file:/g/b.jpg", "content://9"), s.get(a.id)!!.items)
        s.prune(setOf("file:/g/b.jpg"))
        assertEquals(listOf("file:/g/b.jpg"), s.get(a.id)!!.items)
        s.forget(listOf("file:/g/b.jpg"))
        assertTrue(s.get(a.id)!!.items.isEmpty())
    }

    @Test fun jsonRoundTripAndBadFilesAreSurvived() {
        val albums = listOf(AlbumStore.Album("album:1", "Mix", listOf("a", "b"), 1))
        assertEquals(albums, AlbumStore.fromJson(AlbumStore.toJson(albums)))
        val f = tmp(); f.parentFile!!.mkdirs(); f.writeText("{not json")
        assertTrue(AlbumStore(f).albums.isEmpty())
        assertNotNull(AlbumStore(f).create("Fresh"))
        assertEquals(listOf("Fresh"), AlbumStore(f).albums.map { it.name })
        assertTrue(AlbumStore.fromJson("""{"albums":[{"id":"","name":"x"},{"id":"k","name":"ok","items":["a","a",""]}]}""").let { it.size == 1 && it[0].items == listOf("a") })
    }

    // ---------------------------------------------------------------- timeline

    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val now = 1_792_108_800_000L + 15 * 3_600_000L   // Fri 16 Oct 2026, 15:00 UTC

    @Test fun dayLabelsReadLikePhotos() {
        assertEquals("Today", GalleryModel.dayLabel(now - 3_600_000L, now, utc))
        assertEquals("Yesterday", GalleryModel.dayLabel(now - day, now, utc))
        assertEquals("Mon, 12 Oct", GalleryModel.dayLabel(now - 4 * day, now, utc))
        assertEquals("16 Oct 2025", GalleryModel.dayLabel(now - 365 * day, now, utc))
    }

    @Test fun timelinePutsAHeaderBeforeEachDay() {
        val l = GalleryModel.sort(listOf(e("a", time = now - 60_000), e("b", time = now - 120_000), e("c", time = now - day), e("d", time = now - 30 * day)), Sort.DATE)
        val cells = GalleryModel.timeline(l, Sort.DATE, now, utc)
        val shape = cells.map { when (it) { is GalleryModel.Cell.Header -> "[${it.label}:${it.count}]"; is GalleryModel.Cell.Item -> it.entry.id } }
        assertEquals(listOf("[Today:2]", "a", "b", "[Yesterday:1]", "c", "[Wed, 16 Sep:1]", "d"), shape)
        assertEquals(cells.size, cells.map { it.key }.toSet().size)   // keys unique for the lazy grid
    }

    @Test fun timelineIsFlatForNameAndSizeSorts() {
        val l = listOf(e("a", time = now), e("b", time = now - 9 * day))
        assertTrue(GalleryModel.timeline(l, Sort.NAME, now, utc).all { it is GalleryModel.Cell.Item })
        assertTrue(GalleryModel.timeline(emptyList(), Sort.DATE, now, utc).isEmpty())
    }

    @Test fun timelineHandlesThousandsQuickly() {
        val l = GalleryModel.sort((0 until 5000).map { e("i$it", time = now - it * 3_600_000L) }, Sort.DATE)
        val t0 = System.nanoTime()
        val cells = GalleryModel.timeline(l, Sort.DATE, now, utc)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1500)
        assertEquals(5000, cells.count { it is GalleryModel.Cell.Item })
        assertEquals(209, cells.count { it is GalleryModel.Cell.Header })
    }
}
