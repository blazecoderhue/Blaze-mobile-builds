package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v0.28.2: the Design WebView needs no file access and stays on its own origin. */
class DesignSafetyTest {
    private val O = DesignServe.ORIGIN

    @Test fun filesOutsideHomeAreServedFromTheVirtualOrigin() {
        val home = Files.createTempDirectory("home").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        val page = File(outside, "my page.html").apply { writeText("<p>hi</p>") }
        File(outside, "pic.png").writeBytes(byteArrayOf(1, 2, 3))
        val fileUrl = "file://" + page.absolutePath
        assertEquals("$O/ext/my%20page.html", DesignServe.servedUrl(fileUrl, home))
        val root = DesignServe.extRoot(fileUrl, home)!!
        assertEquals(outside.canonicalPath, root.canonicalPath)
        assertEquals(page.canonicalPath, (DesignServe.resolve("$O/ext/my%20page.html", home, root) as DesignServe.Target.Disk).file.canonicalPath)
        assertEquals("image/png", (DesignServe.resolve("$O/ext/pic.png", home, root) as DesignServe.Target.Disk).mime)
        // No climbing out of the page's folder, and nothing without a root.
        assertNull(DesignServe.resolve("$O/ext/../x", home, root))
        assertNull(DesignServe.resolve("$O/ext/%2e%2e/x", home, root))
        assertNull(DesignServe.resolve("$O/ext/pic.png", home, null))
        // Files in Blaze's home keep their paths; URLs pass through.
        val d = File(home, "designs/a").apply { mkdirs() }; File(d, "index.html").writeText("x")
        assertEquals("$O/designs/a/index.html", DesignServe.servedUrl("file://" + File(d, "index.html").absolutePath, home))
        assertNull(DesignServe.extRoot("file://" + File(d, "index.html").absolutePath, home))
        assertEquals("http://localhost:3000/", DesignServe.servedUrl("http://localhost:3000/", home))
        assertNull(DesignServe.extRoot("https://example.com/", home))
    }

    @Test fun appPrivateFilesAreNotServed() {
        val appData = Files.createTempDirectory("data").toFile()
        val home = File(appData, "files/home").apply { mkdirs() }
        val prefs = File(appData, "shared_prefs/blaze.xml").apply { parentFile.mkdirs(); writeText("<map/>") }
        assertNull(DesignServe.extRoot("file://" + prefs.absolutePath, home, appData))
        assertEquals("$O/ext/blaze.xml", DesignServe.servedUrl("file://" + prefs.absolutePath, home))   // → 404
    }

    @Test fun topFrameStaysOnTheDesignOrigin() {
        val loaded = "$O/designs/a/index.html?bzgl=1"
        assertTrue(DesignServe.allowNavigation(loaded, "$O/designs/a/page2.html"))
        assertTrue(DesignServe.allowNavigation(loaded, "$O/designs/a/index.html#top"))
        assertFalse(DesignServe.allowNavigation(loaded, "https://evil.example/phish"))
        assertFalse(DesignServe.allowNavigation(loaded, "http://appassets.androidplatform.net/designs/a/"))   // other scheme
        assertFalse(DesignServe.allowNavigation(loaded, "https://appassets.androidplatform.net.evil.com/"))
        assertFalse(DesignServe.allowNavigation(loaded, "file:///data/data/com.blaze.assistant/shared_prefs/blaze.xml"))
        assertFalse(DesignServe.allowNavigation(loaded, "javascript:alert(1)"))
        assertFalse(DesignServe.allowNavigation(loaded, "intent://x#Intent;end"))
        // A local dev server keeps its own origin (default ports are the same origin).
        assertTrue(DesignServe.allowNavigation("http://localhost:3000/", "http://localhost:3000/about"))
        assertFalse(DesignServe.allowNavigation("http://localhost:3000/", "http://localhost:4000/"))
        assertTrue(DesignServe.allowNavigation("https://example.com/", "https://EXAMPLE.com:443/x"))
        assertFalse(DesignServe.allowNavigation("data:text/html,hi", "https://example.com/"))
        assertEquals("https://appassets.androidplatform.net", DesignServe.originOf("$O/designs/x"))
        assertEquals("", DesignServe.originOf("about:blank"))
    }

    @Test fun bridgeStringsAreCapped() {
        assertEquals(500, DesignServe.bridgeText("x".repeat(10_000)).length)
        assertEquals("r170", DesignServe.bridgeText("r170", 16))
        assertEquals("", DesignServe.bridgeText(null))
    }
}
