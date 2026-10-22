package com.blaze.assistant.voice.myvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class VoicePackTest {
    private fun tmp(): File = Files.createTempDirectory("vp").toFile()

    /** A fake server that honours Range, optionally dropping the connection after [cutAt] bytes. */
    private class FakeServer(val data: ByteArray, var cutAt: Int = Int.MAX_VALUE, var ignoreRange: Boolean = false) : VoicePack.Opener {
        val froms = ArrayList<Long>()
        override fun open(url: String, from: Long): VoicePack.Response {
            froms += from
            if (from >= data.size) return VoicePack.Response(416, 0, null, ByteArrayInputStream(ByteArray(0)))
            val start = if (ignoreRange) 0 else from.toInt()
            val end = minOf(data.size.toLong(), start.toLong() + cutAt).toInt()
            val body = data.copyOfRange(start, end)
            return if (from > 0 && !ignoreRange) VoicePack.Response(206, body.size.toLong(), "bytes $start-${data.size - 1}/${data.size}", ByteArrayInputStream(body))
            else VoicePack.Response(200, data.size.toLong(), null, ByteArrayInputStream(body))
        }
    }

    private fun sha(b: ByteArray) = VoicePack.sha256(ByteArrayInputStream(b))

    @Test fun responses() {
        assertEquals(VoicePack.Resume.APPEND, VoicePack.onResponse(206, 100, "bytes 100-999/1000"))
        assertEquals(VoicePack.Resume.FAIL, VoicePack.onResponse(206, 100, "bytes 0-999/1000"))
        assertEquals(VoicePack.Resume.RESTART, VoicePack.onResponse(200, 100, null))
        assertEquals(VoicePack.Resume.ALREADY_COMPLETE, VoicePack.onResponse(416, 100, null))
        assertEquals(VoicePack.Resume.FAIL, VoicePack.onResponse(404, 0, null))
        assertEquals(1000L, VoicePack.contentRangeTotal("bytes 100-999/1000"))
    }

    @Test fun resumesAfterADroppedConnection() {
        val data = ByteArray(1_000_000) { (it * 31 % 251).toByte() }
        val dir = tmp(); val part = File(dir, "x.part"); val dest = File(dir, "x")
        val server = FakeServer(data, cutAt = 400_000)
        try { VoicePack.download(server, "u", part, dest, data.size.toLong(), sha(data)); fail("should stop early") } catch (e: IOException) { assertTrue(e.message!!.contains("early")) }
        assertEquals(400_000L, part.length())
        server.cutAt = Int.MAX_VALUE
        var last = 0L
        VoicePack.download(server, "u", part, dest, data.size.toLong(), sha(data)) { d, _ -> last = d }
        assertEquals(listOf(0L, 400_000L), server.froms)
        assertTrue(dest.readBytes().contentEquals(data))
        assertFalse(part.exists())
        assertEquals(data.size.toLong(), last)
        // already there: no request at all
        VoicePack.download(server, "u", part, dest, data.size.toLong(), sha(data))
        assertEquals(2, server.froms.size)
    }

    @Test fun restartsWhenServerIgnoresRange() {
        val data = ByteArray(300_000) { it.toByte() }
        val dir = tmp(); val part = File(dir, "x.part"); val dest = File(dir, "x")
        part.writeBytes(data.copyOf(1000))
        VoicePack.download(FakeServer(data, ignoreRange = true), "u", part, dest, data.size.toLong(), sha(data))
        assertTrue(dest.readBytes().contentEquals(data))
    }

    @Test fun badChecksumDeletesThePart() {
        val data = ByteArray(10_000) { 7 }
        val dir = tmp(); val part = File(dir, "x.part"); val dest = File(dir, "x")
        try { VoicePack.download(FakeServer(data), "u", part, dest, data.size.toLong(), "00"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("damaged")) }
        assertFalse(part.exists()); assertFalse(dest.exists())
    }

    @Test fun cancel() {
        val data = ByteArray(2_000_000)
        val dir = tmp()
        try { VoicePack.download(FakeServer(data), "u", File(dir, "p"), File(dir, "d"), data.size.toLong(), null, cancelled = { true }); fail() }
        catch (_: VoicePack.Cancelled) { }
    }

    @Test fun percentAndLabels() {
        assertEquals(0, VoicePack.percent(VoicePack.State.Missing))
        assertEquals(8, VoicePack.percent(VoicePack.State.Working(VoicePack.Step.RUNTIME, 10, 10)))
        assertEquals(46, VoicePack.percent(VoicePack.State.Working(VoicePack.Step.MODEL, 50, 100)))
        assertEquals(100, VoicePack.percent(VoicePack.State.Ready))
        assertTrue(VoicePack.label(VoicePack.State.Working(VoicePack.Step.UNPACK, 1, 2)).startsWith("Unpacking"))
        assertEquals(107, VoicePack.downloadMb("arm64-v8a"))
        assertEquals("arm64-v8a", VoicePack.runtimeFor(true).abi)
    }

    // ---- tar ----

    private fun tarEntry(out: ByteArrayOutputStream, name: String, body: ByteArray, type: Char = '0') {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "0000644\u0000".toByteArray().copyInto(h, 100)
        String.format("%011o\u0000", body.size.toLong()).toByteArray().copyInto(h, 124)
        h[156] = type.code.toByte()
        "ustar\u000000".toByteArray().copyInto(h, 257)
        "        ".toByteArray().copyInto(h, 148)
        val sum = h.sumOf { it.toInt() and 0xff }
        String.format("%06o\u0000 ", sum).toByteArray().copyInto(h, 148)
        out.write(h); out.write(body)
        out.write(ByteArray(((512 - body.size % 512) % 512)))
    }

    private fun modelTar(skip: String? = null, longNameFor: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        tarEntry(out, VoicePack.MODEL_DIR_IN_TAR, ByteArray(0), '5')
        tarEntry(out, VoicePack.MODEL_DIR_IN_TAR + "README.md", "hi".toByteArray())
        for ((n, _) in VoicePack.modelFiles) {
            if (n == skip) continue
            val body = ByteArray(n.length * 3) { n[it % n.length].code.toByte() }
            if (n == longNameFor) {
                val ln = (VoicePack.MODEL_DIR_IN_TAR + n).toByteArray()
                tarEntry(out, "././@LongLink", ln + 0, 'L')
                tarEntry(out, "short", body)
            } else tarEntry(out, VoicePack.MODEL_DIR_IN_TAR + n, body)
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    @Test fun tarReaderReadsFilesAndLongNames() {
        val seen = LinkedHashMap<String, Int>()
        TarReader(ByteArrayInputStream(modelTar(longNameFor = "vocab.json"))).forEach { name, size, body ->
            val b = body.readBytes(); assertEquals(size, b.size.toLong()); seen[name] = b.size
        }
        assertTrue(seen.containsKey(VoicePack.MODEL_DIR_IN_TAR + "vocab.json"))
        assertTrue(seen.containsKey(VoicePack.MODEL_DIR_IN_TAR + "README.md"))
        assertEquals(VoicePack.modelFiles.size + 1, seen.size)
    }

    @Test fun unpackChecksSizesAndMissingFiles() {
        val dir = File(tmp(), "model")
        try { VoicePack.unpackModel(ByteArrayInputStream(modelTar()), dir); fail("sizes differ from the real model") }
        catch (e: IOException) { assertTrue(e.message!!.contains("size")) }
        assertFalse(VoicePack.modelReady(dir))
        assertFalse(File(dir.parentFile, "model").exists())
    }

    @Test fun unpackKeepsOnlyModelFiles() {
        val fake = VoicePack.modelFiles.mapValues { (n, _) -> n.length * 3L }
        val dir = File(tmp(), "model")
        VoicePack.unpackModel(ByteArrayInputStream(modelTar(longNameFor = "LICENSE")), dir, files = fake)
        assertTrue(VoicePack.modelReady(dir, fake))
        assertFalse(File(dir, "README.md").exists())
        assertFalse(File(dir.parentFile, "model.tmp").exists())
        val dir2 = File(tmp(), "model")
        try { VoicePack.unpackModel(ByteArrayInputStream(modelTar(skip = "encoder.onnx")), dir2, files = fake); fail() }
        catch (e: IOException) { assertTrue(e.message!!.contains("missing")) }
        assertFalse(VoicePack.modelReady(dir2, fake))
    }

    @Test fun spaceNeeded() {
        assertTrue(VoicePack.spaceNeeded() > 300_000_000L)
        assertTrue(VoicePack.spaceNeeded(VoicePack.MODEL_BYTES, true) < VoicePack.spaceNeeded())
    }

    @Suppress("unused") private fun read(i: InputStream) = i.readBytes()
}
