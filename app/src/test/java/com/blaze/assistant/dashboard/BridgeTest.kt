package com.blaze.assistant.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BridgeTest {
    @Test fun names() {
        assertEquals("passwd", Bridge.safeName("../../etc/passwd"))
        assertEquals("my_file_.pdf", Bridge.safeName("C:\\x\\my*file?.pdf").replace("my_file_.pdf", "my_file_.pdf").let { if (it == "my_file_.pdf") it else it })
        assertEquals("upload.bin", Bridge.safeName("   "))
    }
    @Test fun paths() {
        val root = Files.createTempDirectory("br").toFile(); val inside = File(root, "a.txt").apply { writeText("x") }
        val outside = Files.createTempFile("o", ".txt").toFile()
        assertTrue(Bridge.allowed(inside, listOf(root)))
        assertFalse(Bridge.allowed(File(root, "../" + outside.name), listOf(root)))
        assertFalse(Bridge.allowed(outside, listOf(root)))
        assertFalse(Bridge.allowed(root, listOf(root)))
    }
}
