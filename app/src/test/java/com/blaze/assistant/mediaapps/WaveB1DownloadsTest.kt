package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class WaveB1DownloadsTest {
    private val big = 8_000L; private val mid = 4_000L; private val small = 2_900L

    @Test fun tierLimits() {
        assertEquals(1, DlConcurrency.limit(Entitled.Capped(1, "basic"), big, false, false, 0))
        assertEquals(2, DlConcurrency.limit(Entitled.Capped(2, "standard"), big, false, false, 0))
        assertEquals(4, DlConcurrency.limit(Entitled.Capped(4, "pro"), big, false, false, 0))
        assertEquals(6, DlConcurrency.limit(Entitled.Uncapped, big, false, false, 0))
        assertEquals(4, DlConcurrency.limit(Entitled.Uncapped, mid, false, false, 0))
        assertEquals(3, DlConcurrency.limit(Entitled.Unknown, big, false, false, 0))
    }

    @Test fun smallPhonesCapAtTwo() {
        assertEquals(2, DlConcurrency.limit(Entitled.Unknown, small, false, false, 0))
        assertEquals(2, DlConcurrency.limit(Entitled.Capped(4), small, false, false, 0))
        assertEquals(2, DlConcurrency.limit(Entitled.Uncapped, small, false, false, 0))
        assertEquals(2, DlConcurrency.limit(Entitled.Uncapped, big, true, false, 0))
        assertEquals(1, DlConcurrency.limit(Entitled.Capped(1), small, false, false, 0))
    }

    @Test fun backsOffUnderPressure() {
        assertEquals(3, DlConcurrency.limit(Entitled.Uncapped, big, false, true, 0))
        assertEquals(2, DlConcurrency.limit(Entitled.Uncapped, big, false, false, 2))
        assertEquals(1, DlConcurrency.limit(Entitled.Uncapped, big, false, false, 3))
        assertEquals(1, DlConcurrency.limit(Entitled.Capped(1), big, false, true, 3))
    }

    @Test fun parsesEntitlements() {
        assertEquals(Entitled.Capped(1, "basic"), DlConcurrency.parse("""{"tier":"basic","max_parallel_downloads":1}"""))
        assertEquals(Entitled.Capped(4, "pro"), DlConcurrency.parse("""{"entitlements":{"tier":"pro","max_parallel_downloads":4}}"""))
        assertEquals(Entitled.Uncapped, DlConcurrency.parse("""{"tier":"lifetime","max_parallel_downloads":null}"""))
        assertEquals(Entitled.Unknown, DlConcurrency.parse("""{"tier":"pro"}"""))
        assertEquals(Entitled.Unknown, DlConcurrency.parse("<html>"))
        for (e in listOf(Entitled.Capped(2, "standard"), Entitled.Uncapped, Entitled.Unknown)) assertEquals(e, DlConcurrency.fromCache(DlConcurrency.toCache(e)))
    }

    private fun job(id: String, at: Long, st: DlStatus = DlStatus.QUEUED, next: Long = 0) =
        DlJob(id, "v$id", "video", "T$id", "", 0, "", "720p", "c", "mp4", listOf(DlPart("22", "u", 100)), st, nextTryAt = next, createdAt = at)

    @Test fun startsOldestUpToLimit() {
        val jobs = listOf(job("a", 1), job("b", 2), job("c", 3), job("d", 4, next = 999), job("e", 0, DlStatus.PAUSED), job("f", 5))
        assertEquals(listOf("a", "b", "c"), DlConcurrency.toStart(jobs, 10, emptySet(), 3).map { it.id })
        assertEquals(listOf("b"), DlConcurrency.toStart(jobs, 10, setOf("a", "x"), 3).map { it.id })
        assertTrue(DlConcurrency.toStart(jobs, 10, setOf("a", "b", "c"), 3).isEmpty())
    }

    @Test fun softNudgeOnlyWhenPlanHoldsBack() {
        assertNull(DlConcurrency.nudge(Entitled.Capped(1), 1, 1))
        assertTrue(DlConcurrency.nudge(Entitled.Capped(1), 1, 3)!!.contains("one at a time"))
        assertNull(DlConcurrency.nudge(Entitled.Capped(4), 4, 5))
        assertNull(DlConcurrency.nudge(Entitled.Uncapped, 6, 5))
        assertNull(DlConcurrency.nudge(Entitled.Capped(2), 1, 5)) // the phone, not the plan, is holding back
    }

    @Test fun statusLinesNeverStickAt100() {
        val j = job("a", 1, DlStatus.RUNNING).copy(parts = listOf(DlPart("22", "u", 100, 100)))
        assertEquals("Verifying\u2026", DlVerify.statusLine(j.copy(phase = DlVerify.PHASE_VERIFY)))
        assertEquals("Saving\u2026", DlVerify.statusLine(j.copy(phase = DlVerify.PHASE_SAVE)))
        assertEquals("Saved \u2713 \u00b7 verified", DlVerify.statusLine(j.copy(status = DlStatus.DONE, verified = true)))
        assertEquals("Saved", DlVerify.statusLine(j.copy(status = DlStatus.DONE)))
    }

    @Test fun badPartsByLength() {
        val parts = listOf(DlPart("a", "u", 100), DlPart("b", "u", 0))
        assertEquals(emptyList<Int>(), DlVerify.badParts(parts, listOf(100, 5)))
        assertEquals(listOf(0, 1), DlVerify.badParts(parts, listOf(99, 0)))
    }

    @Test fun jsonKeepsVerification() {
        val j = job("a", 1).copy(phase = "saving", sha256 = "ab", verified = true)
        assertEquals(j, DlLogic.fromJson(DlLogic.toJson(j)))
        assertEquals("", DlLogic.onProgress(j, 0, 10).phase)
    }

    @Test fun resumedHasherMatchesFullHash() {
        val f = Files.createTempFile("wb1", ".part").toFile()
        val data = Random(7).nextBytes(500_000); f.writeBytes(data)
        val h = PartHasher.resume(f, 200_000); val rest = data.copyOfRange(200_000, data.size); h.update(rest, rest.size)
        assertEquals(sha(data), h.hex()); assertEquals(sha(data), PartHasher.ofFile(f))
        f.delete()
    }

    /**
     * Five parallel ranged downloads against a flaky local server (drops ~1 in 3 responses mid-body, sometimes 503),
     * each "killed" once half-way (hasher thrown away, resumed from the .part file): every file ends byte-identical.
     */
    @Test fun fiveParallelOnFlakyNetworkWithKillAndResume() {
        val size = 3_000_000
        val blobs = (0 until 5).map { Random(it).nextBytes(size) }
        val rnd = Random(42); val hits = AtomicInteger()
        // Tiny raw-socket HTTP server (com.sun.net.httpserver is not on the Android unit-test classpath).
        val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val srvPool = Executors.newFixedThreadPool(8)
        val acceptor = Thread {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (_: Exception) { break }
                srvPool.submit {
                    sock.use { so ->
                        try {
                            val rd = so.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val req = rd.readLine() ?: return@use
                            var range = ""
                            while (true) { val l = rd.readLine() ?: break; if (l.isEmpty()) break
                                if (l.startsWith("Range:", true)) range = l.substringAfter(':').trim() }
                            val b = blobs[req.split(' ')[1].trim('/').toInt()]
                            val r = range.removePrefix("bytes=").split('-')
                            val from = r[0].toInt(); val to = minOf(r[1].toInt(), size - 1)
                            val roll = synchronized(rnd) { rnd.nextInt(10) }; hits.incrementAndGet()
                            val out = so.getOutputStream()
                            if (roll == 0) { out.write("HTTP/1.1 503 Busy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return@use }
                            val len = to - from + 1
                            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$to/$size\r\n" +
                                "Content-Length: $len\r\nConnection: close\r\n\r\n").toByteArray())
                            val cut = if (roll <= 3) len / 3 else len
                            out.write(b, from, cut); out.flush()
                        } catch (_: Exception) { }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val dir = Files.createTempDirectory("wb1par").toFile()
        val pool = Executors.newFixedThreadPool(5)
        val futures = (0 until 5).map { n -> pool.submit<String> {
            val f = File(dir, "$n.part"); var killed = false
            var h = PartHasher()
            var tries = 0
            while (f.length() < size && tries++ < 500) {
                val have = f.length()
                if (!killed && have > size / 2) { killed = true; h = PartHasher.resume(f, have) }  // process death + restart
                if (h.bytes != have) h = PartHasher.resume(f, have)
                val c = URL("http://127.0.0.1:${ss.localPort}/$n").openConnection() as HttpURLConnection
                try {
                    c.setRequestProperty("Range", DlLogic.range(have, size.toLong(), 512 * 1024))
                    if (DlLogic.onStatus(c.responseCode) != DlLogic.Resume.APPEND) continue
                    FileOutputStream(f, true).use { out -> c.inputStream.use { inp ->
                        val buf = ByteArray(32 * 1024); var k: Int
                        try { while (inp.read(buf).also { k = it } >= 0) { out.write(buf, 0, k); h.update(buf, k) } } catch (_: Exception) { }
                    } }
                } catch (_: Exception) { } finally { c.disconnect() }
            }
            assertTrue(killed)
            assertEquals(emptyList<Int>(), DlVerify.badParts(listOf(DlPart("p", "u", size.toLong())), listOf(f.length())))
            h.hex()
        } }
        futures.forEachIndexed { i, fu -> assertEquals(sha(blobs[i]), fu.get()) }
        pool.shutdown(); ss.close(); srvPool.shutdownNow(); acceptor.join(1000); dir.deleteRecursively()
        assertTrue(hits.get() > 30)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
