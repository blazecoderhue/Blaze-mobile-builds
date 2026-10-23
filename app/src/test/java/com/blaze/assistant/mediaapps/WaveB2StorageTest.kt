package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WaveB2StorageTest {
    @Test fun privateFoldersPerKind() {
        assertEquals("media/videos", MediaStorePolicy.privateRel("video"))
        assertEquals("media/music", MediaStorePolicy.privateRel("audio"))
    }

    @Test fun uniqueNames() {
        assertEquals("Song.m4a", MediaStorePolicy.uniqueName("Song.m4a", "m4a", emptySet()))
        assertEquals("Song (2).m4a", MediaStorePolicy.uniqueName("Song.m4a", "m4a", setOf("Song.m4a")))
        assertEquals("Song (3).m4a", MediaStorePolicy.uniqueName("Song.m4a", "m4a", setOf("Song.m4a", "Song (2).m4a")))
    }

    @Test fun exportTargets() {
        assertEquals("Download/Blaze", MediaStorePolicy.exportRel("video", MediaStorePolicy.Target.DOWNLOADS))
        assertEquals("Movies/Blaze/Videos", MediaStorePolicy.exportRel("video", MediaStorePolicy.Target.DEFAULT))
        assertEquals("Music/Blaze/Music", MediaStorePolicy.exportRel("audio", MediaStorePolicy.Target.DEFAULT))
    }

    @Test fun shareTypes() {
        assertEquals("audio/mp4", MediaStorePolicy.shareType(listOf("audio/mp4", "audio/mp4")))
        assertEquals("audio/*", MediaStorePolicy.shareType(listOf("audio/mp4", "audio/ogg")))
        assertEquals("*/*", MediaStorePolicy.shareType(listOf("audio/mp4", "video/mp4")))
    }

    @Test fun summaries() {
        assertEquals("Exported to Music", MediaStorePolicy.exportSummary(1, 0, "Music"))
        assertEquals("Exported 3 files to Downloads", MediaStorePolicy.exportSummary(3, 0, "Downloads"))
        assertEquals("Exported 2 to Music, 1 couldn't be copied", MediaStorePolicy.exportSummary(2, 1, "Music"))
        assertEquals("Couldn't export 2 files", MediaStorePolicy.exportSummary(0, 2, "Music"))
    }

    @Test fun atomicMoveRenamesAndNeverLeavesTemp() {
        val d = Files.createTempDirectory("wb2").toFile()
        val src = File(d, "a.part").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        val dst = File(File(d, "media/music"), "Song.m4a")
        AtomicMove.move(src, dst)
        assertTrue(dst.isFile); assertFalse(src.exists()); assertEquals(300_000L, dst.length())
        assertFalse(File(dst.path + ".tmp").exists())
        d.deleteRecursively()
    }

    @Test(expected = java.io.IOException::class) fun atomicMoveRefusesToOverwrite() {
        val d = Files.createTempDirectory("wb2").toFile()
        val src = File(d, "a").apply { writeText("x") }
        val dst = File(d, "b").apply { writeText("y") }
        try { AtomicMove.move(src, dst) } finally { d.deleteRecursively() }
    }

    @Test fun noMediaStoreInsertForNewDownloads() {
        // Source guard: publish() must not touch MediaStore any more (wave B 1); exportShared() is the only insert.
        val f = listOf("src/main/java/com/blaze/assistant/mediaapps/MediaFiles.kt", "app/src/main/java/com/blaze/assistant/mediaapps/MediaFiles.kt").map { File(it) }.first { it.isFile }
        val s = f.readText()
        val pub = s.substringAfter("fun publish(").substringBefore("fun exportShared(")
        assertFalse(Regex("\\bMediaStore\\b").containsMatchIn(pub)); assertTrue(pub.contains("privateDir(") && pub.contains("AtomicMove.move"))
        assertTrue(s.contains("\".nomedia\""))
    }
}
