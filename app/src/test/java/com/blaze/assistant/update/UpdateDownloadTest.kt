package com.blaze.assistant.update

import com.blaze.assistant.ui.UpdateCardText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/** v0.28.1: the update download survives a flaky connection; the card is plain and goes away by itself. */
class UpdateDownloadTest {
    private val data = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private fun tmp(): File = Files.createTempDirectory("upd").toFile()

    /** A stream that throws "unexpected end of stream" after [cut] bytes (Luke's error). */
    private class Cut(private val src: InputStream, private var cut: Int) : InputStream() {
        override fun read(): Int { if (cut-- <= 0) throw IOException("unexpected end of stream"); return src.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (cut <= 0) throw IOException("unexpected end of stream")
            val n = src.read(b, off, minOf(len, cut)); if (n > 0) cut -= n; return n
        }
    }

    /** Fake server: [plan] per request = bytes it sends before the connection drops (-1 = all); [ranges] = honours Range. */
    private inner class Fake(val plan: MutableList<Int>, val ranges: Boolean = true, val failUrls: Set<String> = emptySet()) : ApkDownload.Io {
        val calls = ArrayList<Pair<String, Long>>()
        var sleeps = 0L
        var onlineAnswers = ArrayDeque<Boolean>()
        override fun open(url: String, from: Long): ApkDownload.Response {
            calls += url to from
            if (url in failUrls) throw IOException("connection reset")
            val start = if (ranges) from else 0L
            val body = ByteArrayInputStream(data, start.toInt(), data.size - start.toInt())
            val cut = plan.removeFirstOrNull() ?: -1
            val stream: InputStream = if (cut < 0) body else Cut(body, cut)
            return if (ranges && from > 0) ApkDownload.Response(206, data.size - from, "bytes $from-${data.size - 1}/${data.size}", stream)
                else ApkDownload.Response(200, data.size.toLong(), null, stream)
        }
        override fun online(): Boolean = onlineAnswers.removeFirstOrNull() ?: true
        override fun sleep(ms: Long) { sleeps += ms }
    }

    private fun sha(): String { val d = tmp(); val f = File(d, "x"); f.writeBytes(data); return VersionFeed.sha256(f) }

    @Test fun resumesAfterUnexpectedEndOfStream() {
        val d = tmp(); val io = Fake(mutableListOf(100_000, 50_000, -1))
        ApkDownload.fetch(listOf("a"), io, File(d, "b.apk.part"), File(d, "b.apk"), data.size.toLong(), sha())
        assertArrayEquals(data, File(d, "b.apk").readBytes())
        assertEquals(listOf(0L, 100_000L, 150_000L), io.calls.map { it.second })   // each try asks from where it stopped
        assertFalse(File(d, "b.apk.part").exists())
    }

    @Test fun serverIgnoringRangeStartsOver() {
        val d = tmp(); val io = Fake(mutableListOf(120_000, -1), ranges = false)
        ApkDownload.fetch(listOf("a"), io, File(d, "p"), File(d, "f"), data.size.toLong(), sha())
        assertArrayEquals(data, File(d, "f").readBytes())
    }

    @Test fun switchesToTheNextMirrorAndKeepsThePartialFile() {
        val d = tmp(); val io = Fake(mutableListOf(80_000, -1), failUrls = setOf("release"))
        // First source fails outright twice → next mirror.
        ApkDownload.fetch(listOf("release", "second", "third"), io, File(d, "p"), File(d, "f"), data.size.toLong(), null)
        assertEquals(listOf("release", "release", "second", "second"), io.calls.map { it.first })
        assertEquals(80_000L, io.calls.last().second)
        assertArrayEquals(data, File(d, "f").readBytes())
    }

    @Test fun waitsForTheNetworkInsteadOfFailing() {
        val d = tmp(); val io = Fake(mutableListOf(-1)); io.onlineAnswers.addAll(listOf(false, false, false, true))
        val events = ArrayList<ApkDownload.Event>()
        ApkDownload.fetch(listOf("a"), io, File(d, "p"), File(d, "f"), data.size.toLong(), null) { events += it }
        assertTrue(events.any { it is ApkDownload.Event.Offline })
        assertTrue(io.sleeps >= 4_000)
        assertArrayEquals(data, File(d, "f").readBytes())
    }

    @Test fun givesUpAfterManyTriesWithNoProgressButKeepsWhatItHas() {
        val d = tmp(); val io = Fake(MutableList(40) { 0 })
        File(d, "p").writeBytes(data.copyOf(1000))
        try { ApkDownload.fetch(listOf("a", "b"), io, File(d, "p"), File(d, "f"), data.size.toLong(), null); fail() }
        catch (e: ApkDownload.GaveUp) { }
        assertEquals(ApkDownload.MAX_FAILS, io.calls.size)
        assertEquals(1000L, File(d, "p").length())
    }

    @Test fun damagedDownloadIsRejected() {
        val d = tmp(); val io = Fake(mutableListOf(-1))
        try { ApkDownload.fetch(listOf("a"), io, File(d, "p"), File(d, "f"), data.size.toLong(), "0".repeat(64)); fail() }
        catch (e: ApkDownload.Damaged) { assertEquals(VersionFeed.SHA_MISMATCH, e.message) }
        assertFalse(File(d, "f").exists()); assertFalse(File(d, "p").exists())
    }

    /** v0.45.4: versions.json gives no size; the download still resumes and is checked by SHA-256 alone. */
    @Test fun unknownSizeResumesAndChecksSha256() {
        val d = tmp(); val io = Fake(mutableListOf(70_000, -1))
        ApkDownload.fetch(listOf("https://blazeinchue.pages.dev/dl/android/v0.45.4"), io, File(d, "p"), File(d, "f"), 0L, sha().uppercase())
        assertArrayEquals(data, File(d, "f").readBytes())
        assertEquals(listOf(0L, 70_000L), io.calls.map { it.second })
    }

    @Test fun cancelStops() {
        val d = tmp(); val io = Fake(mutableListOf(-1))
        try { ApkDownload.fetch(listOf("a"), io, File(d, "p"), File(d, "f"), data.size.toLong(), null, cancelled = { true }); fail() }
        catch (e: ApkDownload.Cancelled) { }
    }

    @Test fun backoff() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..6).map { ApkDownload.backoffMs(it) })
    }

    @Test fun cardIsSentenceCaseAndPlain() {
        val (t, line, _) = UpdateCardText.of(Updater.State.Failed("The connection kept dropping"), "0.28.0")
        assertEquals("The update didn't finish", t)
        assertFalse(t == t.uppercase())
        assertEquals("The connection kept dropping", line)
        val (_, retry, pct) = UpdateCardText.of(Updater.State.Downloading("0.28.1", 42, "Connection dropped, I'll keep trying."), "0.28.0")
        assertEquals("Connection dropped, I'll keep trying.", retry); assertEquals(42, pct)
        assertTrue(ApkDownload.retryLine(ApkDownload.Event.Retrying(1, 2_000, 0)).startsWith("Connection dropped, I'll keep trying"))
    }

    @Test fun cardsGoAwayByThemselves() {
        assertEquals(60_000L, ApkDownload.dismissAfterMs(Updater.State.Failed("x")))
        assertEquals(60_000L, ApkDownload.dismissAfterMs(Updater.State.Done("0.28.1")))
        assertEquals(60_000L, ApkDownload.dismissAfterMs(Updater.State.Idle))
        assertNull(ApkDownload.dismissAfterMs(Updater.State.Downloading("0.28.1", 3)))
        assertNull(ApkDownload.dismissAfterMs(Updater.State.Ready("0.28.1")))
    }
}
