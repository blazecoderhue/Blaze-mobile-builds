package com.blaze.engine

import com.blaze.engine.store.GuestRegistry
import com.blaze.engine.store.PackageNames
import com.blaze.engine.store.PathRedirector
import com.blaze.engine.store.StorageLayout
import com.blaze.engine.manifest.ManifestReader
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.Assert.fail
import org.testng.annotations.Test
import java.io.File
import java.nio.file.Files

/** A3 (storage): layout, path redirection incl. escapes, registry install/uninstall. */
class StorageTest {
    private val root = File("/data/user/0/com.blaze.assistant/files/engine")
    private val layout = StorageLayout(root)

    @Test
    fun layoutPaths() {
        assertEquals(layout.apk("org.example.calc").path, "$root/apps/org.example.calc/base.apk")
        assertEquals(layout.dataDir(0, "org.example.calc").path, "$root/data/u0/org.example.calc")
        assertEquals(layout.libDir("org.example.calc", "arm64-v8a").path, "$root/apps/org.example.calc/lib/arm64-v8a")
    }

    @Test
    fun packageNameRules() {
        assertTrue(PackageNames.isValid("org.example.calc"))
        assertTrue(PackageNames.isValid("a.b_2"))
        for (bad in listOf("calc", "../x.y", "org..x", "1org.x", "org.x/../y", "org.x y", "")) assertFalse(PackageNames.isValid(bad), bad)
        try { layout.appDir("../../etc"); fail("must refuse") } catch (e: IllegalArgumentException) { }
        try { layout.libDir("org.example.calc", ".."); fail("must refuse") } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun normalize() {
        assertEquals(PathRedirector.normalize("/a/./b//c/"), "/a/b/c")
        assertEquals(PathRedirector.normalize("/a/b/../c"), "/a/c")
        assertNull(PathRedirector.normalize("/../etc"))
        assertNull(PathRedirector.normalize("relative/path"))
        assertNull(PathRedirector.normalize("/a\u0000b"))
    }

    @Test
    fun redirectsGuestOwnPaths() {
        val r = PathRedirector(layout, "com.blaze.assistant", 0, "org.example.calc")
        val data = layout.dataDir(0, "org.example.calc").path
        assertEquals(r.map("/data/data/org.example.calc/files/x.txt"), "$data/files/x.txt")
        assertEquals(r.map("/data/user/0/org.example.calc"), data)
        assertEquals(r.map("/data/user_de/0/org.example.calc/no_backup"), "$data/no_backup")
        assertEquals(r.map("/sdcard/Android/data/org.example.calc/files/a"), layout.externalDir(0, "org.example.calc").path + "/files/a")
        assertEquals(r.map("/storage/emulated/0/Android/data/org.example.calc"), layout.externalDir(0, "org.example.calc").path)
        // prefix must end on a segment boundary
        assertEquals(r.map("/data/data/org.example.calculator/x"), "/data/data/org.example.calculator/x")
        // unrelated system paths pass through
        assertEquals(r.map("/system/lib64/libc.so"), "/system/lib64/libc.so")
    }

    @Test
    fun refusesHostAndEscapes() {
        val r = PathRedirector(layout, "com.blaze.assistant", 0, "org.example.calc")
        assertNull(r.map("/data/data/com.blaze.assistant/shared_prefs/secrets.xml"))
        assertNull(r.map("/data/user/0/com.blaze.assistant/files/engine/data/u0/org.other.app/files/a"))
        assertNull(r.map("/data/data/org.example.calc/../com.blaze.assistant/databases/chat.db"))
        assertNull(r.map("/data/data/org.example.calc/../../../../.."))
        // its own real directory is fine
        val own = layout.dataDir(0, "org.example.calc").path + "/files/a"
        assertEquals(r.map(own), own)
    }

    @Test
    fun registryInstallAndUninstall() {
        val dir = Files.createTempDirectory("blaze-engine-reg").toFile()
        val l = StorageLayout(File(dir, "engine"))
        val reg = GuestRegistry(l)
        val manifestBytes = TestAxml.simpleGuest()
        val apk = TestAxml.apk(dir, "calc.apk", manifestBytes)
        val rec = reg.install(apk, ManifestReader.read(manifestBytes), null, now = 1234L)
        assertEquals(rec.packageName, "org.example.calc")
        assertEquals(rec.launcherActivity, "org.example.calc.MainActivity")
        assertTrue(l.apk("org.example.calc").isFile)
        assertFalse(l.apk("org.example.calc").canWrite(), "base.apk must be read-only")
        l.dataDir(0, "org.example.calc").mkdirs()
        File(l.dataDir(0, "org.example.calc"), "f.txt").writeText("x")

        val again = GuestRegistry(l)
        assertEquals(again.load().map { it.packageName }, listOf("org.example.calc"))
        assertEquals(again.get("org.example.calc")?.installedAtMillis, 1234L)

        // reinstall over a read-only APK works
        reg.install(apk, ManifestReader.read(manifestBytes), null, now = 5678L)
        assertEquals(GuestRegistry(l).also { it.load() }.get("org.example.calc")?.installedAtMillis, 5678L)

        assertTrue(reg.uninstall("org.example.calc"))
        assertFalse(l.appDir("org.example.calc").exists())
        assertFalse(l.dataDir(0, "org.example.calc").exists())
        assertFalse(reg.uninstall("org.example.calc"))
    }
}
