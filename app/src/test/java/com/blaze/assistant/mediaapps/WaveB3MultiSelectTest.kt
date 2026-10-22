package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave B item 20: press-and-hold multi-select in the Music and Video libraries. */
class WaveB3MultiSelectTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    private fun t(k: String, source: String = "download", kind: String = "audio") = Track(k, "content://media/$k", "Song $k", source = source, kind = kind)

    @Test fun longPressStartsAndTapToggles() {
        var sel = LibrarySelection.NONE
        assertFalse(LibrarySelection.active(sel))
        sel = LibrarySelection.start("a")
        assertTrue(LibrarySelection.active(sel))
        assertEquals(setOf("a"), sel)
        sel = LibrarySelection.toggle(sel, "b")
        assertEquals(2, LibrarySelection.count(sel))
        sel = LibrarySelection.toggle(sel, "a")
        assertEquals(setOf("b"), sel)
        sel = LibrarySelection.toggle(sel, "b")
        assertFalse("un-picking the last one leaves selection mode", LibrarySelection.active(sel))
    }

    @Test fun selectAllPicksShownAndTogglesOff() {
        val shown = listOf("a", "b", "c")
        var sel = LibrarySelection.start("b")
        assertFalse(LibrarySelection.allSelected(sel, shown))
        sel = LibrarySelection.selectAll(sel, shown)
        assertEquals(shown.toSet(), sel)
        assertTrue(LibrarySelection.allSelected(sel, shown))
        assertEquals("3 selected", LibrarySelection.label(sel))
        sel = LibrarySelection.selectAll(sel, shown)
        assertTrue("second press clears", sel.isEmpty())
        assertFalse("nothing shown is never all selected", LibrarySelection.allSelected(setOf("a"), emptyList()))
    }

    @Test fun labelAndClear() {
        assertEquals("1 selected", LibrarySelection.label(setOf("x")))
        assertEquals("120 selected", LibrarySelection.label((1..120).map { "$it" }.toSet()))
        assertTrue(LibrarySelection.clear().isEmpty())
    }

    @Test fun backLeavesSelectionFirst() {
        assertTrue(LibrarySelection.backClears(setOf("a")))
        assertFalse(LibrarySelection.backClears(LibrarySelection.NONE))
    }

    @Test fun pruneDropsGoneRowsAndKeepsIdentity() {
        val sel = setOf("a", "b", "c")
        assertEquals(setOf("a", "c"), LibrarySelection.prune(sel, listOf("a", "c", "d")))
        assertSame("unchanged set is reused (no extra recomposition)", sel, LibrarySelection.prune(sel, listOf("a", "b", "c", "d")))
        assertTrue(LibrarySelection.prune(sel, emptyList()).isEmpty())
    }

    @Test fun pickedInShownOrderAndOnlyDownloadsDelete() {
        val list = listOf(t("a"), t("g", source = "gallery"), t("b"), t("c"))
        val picked = LibrarySelection.picked(setOf("c", "g", "a"), list)
        assertEquals(listOf("a", "g", "c"), picked.map { it.key })
        assertEquals(listOf("a", "c"), LibrarySelection.deletable(picked).map { it.key })
    }

    @Test fun deleteConfirmText() {
        val (t1, b1) = LibrarySelection.deleteText(listOf(t("a")), "audio")
        assertEquals("Delete 1 song?", t1)
        assertTrue(b1.contains("every playlist"))
        val (t2, b2) = LibrarySelection.deleteText(listOf(t("a", kind = "video"), t("b", kind = "video"), t("g", "gallery", "video")), "video")
        assertEquals("Delete 2 videos?", t2)
        assertTrue(b2.contains("1 from Blaze's Gallery stays"))
    }

    @Test fun shareTypeAndExportName() {
        assertEquals("audio/mp4", LibrarySelection.shareType(listOf("audio/mp4", "audio/mp4")))
        assertEquals("audio/*", LibrarySelection.shareType(listOf("audio/mp4", "audio/ogg")))
        assertEquals("*/*", LibrarySelection.shareType(listOf("audio/mp4", "video/mp4")))
        assertEquals("AC DC Live.m4a", LibrarySelection.exportName("AC/DC: Live", "content://media/external/audio/media/12", "audio"))
        assertEquals("Film.webm", LibrarySelection.exportName("Film", "file:///data/gallery/x.webm", "video"))
        assertEquals("Blaze video.mp4", LibrarySelection.exportName("  ", "content://m/1", "video"))
        assertEquals("Track.mp3", LibrarySelection.exportName("Track.mp3", "file:///g/Track.mp3", "audio"))
    }

    @Test fun wiring() {
        val ui = src("mediaapps/MediaUi.kt")
        assertTrue("rows take a long-press", ui.contains("combinedClickable(onClick = onClick, onLongClick = onLongClick)"))
        val music = src("mediaapps/MusicActivity.kt")
        val video = src("mediaapps/VideoActivity.kt")
        for (s in listOf(music, video)) {
            assertTrue(s.contains("SelectionBar("))
            assertTrue(s.contains("BackHandler(LibrarySelection.backClears(sel))"))
            assertTrue(s.contains("MediaLib.deleteDownloads("))
            assertTrue(s.contains("MediaExport.exportAll("))
            assertTrue(s.contains("MediaExport.shareIntent("))
            assertTrue(s.contains("OpenDocumentTree()"))
        }
        assertTrue("Music has Add to playlist", music.contains("onAddToPlaylist = {"))
        // Back order in Music: the selection handler is registered after the group/playlist handler, so it wins.
        assertTrue(music.indexOf("BackHandler(LibrarySelection.backClears(sel))") > music.indexOf("BackHandler(group != null || openList != null)"))
        val lib = src("mediaapps/MediaLib.kt")
        assertTrue("batch delete refreshes once", lib.contains("fun deleteDownloads("))
    }
}
