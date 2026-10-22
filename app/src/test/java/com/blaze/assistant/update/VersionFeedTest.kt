package com.blaze.assistant.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** v0.45.4: updates come only from the Blaze site's versions.json; never from GitHub. */
class VersionFeedTest {
    private val sha = "a".repeat(64)
    private fun json(version: String = "0.45.4", url: String = "https://blazeinchue.pages.dev/dl/android/v0.45.4", s: String = sha, schema: Int = 1) =
        """{"schema":$schema,"android":{"version":"$version","url":"$url","sha256":"$s","notes":"Fixes sign-in."},"desktop":{"version":"0.9.0"},"cli":{"version":"v0.14.12"}}"""

    @Test fun semverCompareIgnoresLeadingV() {
        assertTrue(VersionFeed.newer("0.45.4", "0.45.3"))
        assertTrue(VersionFeed.newer("v0.45.10", "0.45.9"))
        assertTrue(VersionFeed.newer("0.46.0", "V0.45.99"))
        assertTrue(VersionFeed.newer("1.0", "0.99.99"))
        assertFalse(VersionFeed.newer("0.45.4", "0.45.4"))
        assertFalse(VersionFeed.newer("v0.45.4", "0.45.4"))
        assertFalse(VersionFeed.newer("0.45.3", "0.45.4"))
        assertFalse(VersionFeed.newer("0.45", "0.45.0"))
        assertFalse(VersionFeed.newer("garbage", "0.1.0"))
        assertEquals(0, VersionFeed.compare("0.45.4-debug", "0.45.4"))
        assertEquals("0.45.4", VersionFeed.clean("v0.45.4"))
    }

    @Test fun parsesTheAndroidEntry() {
        val a = VersionFeed.parse(json(version = "v0.45.4"))!!
        assertEquals("0.45.4", a.version)
        assertEquals("https://blazeinchue.pages.dev/dl/android/v0.45.4", a.url)
        assertEquals(sha, a.sha256)
        assertEquals("Fixes sign-in.", a.notes)
        assertNotNull(VersionFeed.parse(json(s = "A".repeat(64))))   // hash case doesn't matter
    }

    @Test fun rejectsWhatItDoesNotUnderstand() {
        assertNull(VersionFeed.parse(json(schema = 2)))
        assertNull(VersionFeed.parse(json(s = "abc")))
        assertNull(VersionFeed.parse(json(url = "http://blazeinchue.pages.dev/x")))
        assertNull(VersionFeed.parse(json(version = "latest")))
        assertNull(VersionFeed.parse("""{"schema":1}"""))
        assertNull(VersionFeed.parse("<html>offline</html>"))
        assertNull(VersionFeed.parse(""))
    }

    @Test fun shaMismatchDeletesTheFileAndFailsPlainly() {
        val d = Files.createTempDirectory("vf").toFile()
        val bytes = ByteArray(10_000) { it.toByte() }
        val io = object : ApkDownload.Io {
            override fun open(url: String, from: Long) = ApkDownload.Response(200, bytes.size.toLong(), null, ByteArrayInputStream(bytes))
        }
        val part = File(d, "blaze-0.45.4.apk.part"); val dest = File(d, "blaze-0.45.4.apk")
        try { ApkDownload.fetch(listOf("https://blazeinchue.pages.dev/dl/android/v0.45.4"), io, part, dest, 0L, "b".repeat(64)); fail() }
        catch (e: ApkDownload.Damaged) {
            assertEquals(VersionFeed.SHA_MISMATCH, e.message)
            assertFalse(e.message!!.contains("sha", ignoreCase = true))
        }
        assertFalse(part.exists()); assertFalse(dest.exists())
        // The right hash installs.
        val good = File(d, "ref").apply { writeBytes(bytes) }
        ApkDownload.fetch(listOf("u"), io, part, dest, 0L, VersionFeed.sha256(good))
        assertTrue(dest.exists())
    }

    @Test fun checksAtMostOnceADay() {
        val t0 = 1_760_000_000_000L
        assertTrue(VersionFeed.due(t0, 0L))
        assertFalse(VersionFeed.due(t0 + 1_000, t0))
        assertFalse(VersionFeed.due(t0 + VersionFeed.DAY_MS - 1, t0))
        assertTrue(VersionFeed.due(t0 + VersionFeed.DAY_MS, t0))
        assertTrue(VersionFeed.due(t0 - 5_000, t0))   // clock went backwards
    }

    @Test fun bannerShowsOnlyANewerUndismissedVersion() {
        val a = VersionFeed.parse(json())!!
        assertEquals("Blaze 0.45.4 is available", VersionFeed.banner(a, "0.45.3", ""))
        assertNull(VersionFeed.banner(a, "0.45.4", ""))
        assertNull(VersionFeed.banner(a, "0.45.3", "0.45.4"))
        assertNull(VersionFeed.banner(a, "0.45.3", "v0.45.4"))
        assertEquals("Blaze 0.45.4 is available", VersionFeed.banner(a, "0.45.3", "0.45.2"))
        assertNull(VersionFeed.banner(null, "0.45.3", ""))   // offline / unreadable: silent
    }

    @Test fun updateSourcesContainNoGithubHost() {
        val a = VersionFeed.parse(json())!!
        val hosts = VersionFeed.hosts(a)
        assertEquals(listOf("blazeinchue.pages.dev", "blazeinchue.pages.dev"), hosts)
        assertTrue(VersionFeed.sources(a) == listOf(a.url))
        for (h in hosts) assertFalse(h, h.contains("github"))
        // And the update code itself names no GitHub host or token.
        val root = listOf(File("src/main/java/com/blaze/assistant/update"), File("app/src/main/java/com/blaze/assistant/update")).first { it.isDirectory }
        for (f in root.listFiles()!!.filter { it.name.endsWith(".kt") }) {
            val t = f.readText().lowercase()
            for (bad in listOf("github.com", "api.github", "githubusercontent", "connectors.access(\"github\")"))
                assertFalse("${f.name} mentions $bad", t.contains(bad))
        }
    }
}
