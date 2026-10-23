package com.blaze.kiln

import com.blaze.kiln.apk.ApkInspector
import com.blaze.kiln.apk.DeviceProfile
import com.blaze.kiln.apk.Milestone
import com.blaze.kiln.manifest.ComponentKind
import com.blaze.kiln.manifest.LaunchMode
import com.blaze.kiln.store.GuestRegistry
import com.blaze.kiln.store.StorageLayout
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * The real Kiln test guest APK, as AAPT2 + D8 built it in this same Gradle run (kiln/build.gradle.kts passes its path).
 * Fails, never skips, when the APK is missing: the phone test build bundles exactly this file.
 */
class TestGuestApkTest {
    private val apk: File by lazy {
        val p = System.getProperty("kiln.testGuestApk") ?: error("kiln.testGuestApk not set (kiln/build.gradle.kts)")
        File(p).also { check(it.isFile && it.length() > 1000) { "test guest APK not built: $p" } }
    }
    private val phone34 = DeviceProfile(34, listOf("arm64-v8a", "armeabi-v7a"))

    @Test
    fun guestIsM1OnEverySupportedAndroid() {
        for (dev in listOf(DeviceProfile(26, listOf("armeabi-v7a")), DeviceProfile(29, listOf("arm64-v8a")), phone34,
            DeviceProfile(35, listOf("arm64-v8a"), pageSize = 16384), DeviceProfile(35, listOf("x86_64")))) {
            val r = ApkInspector.inspect(apk, dev)
            assertEquals(r.blockers, emptyList<Any>(), "API ${dev.sdkInt}")
            assertEquals(r.concerns, emptyList<Any>(), "API ${dev.sdkInt}")
            assertEquals(r.milestone, Milestone.M1, "API ${dev.sdkInt}")
            assertNull(r.chosenAbi)
        }
        assertNotNull(ApkInspector.inspect(apk, DeviceProfile(25, listOf("arm64-v8a"))).blockers.firstOrNull(), "minSdk 26 enforced")
    }

    @Test
    fun manifestIsWhatTheHarnessExpects() {
        val r = ApkInspector.inspect(apk, phone34)
        val m = r.manifest!!
        assertEquals(m.packageName, "com.blaze.kiln.testguest")
        assertEquals(m.launcherActivity, "com.blaze.kiln.testguest.MainActivity")
        assertEquals(m.minSdk, 26)
        assertEquals(m.targetSdk, 34)
        assertEquals(m.versionCode, 1L)
        assertEquals(m.versionName, "1.0-m1")
        assertTrue(m.hasCode)
        assertNull(m.sharedUserId)
        assertNull(m.applicationClass, "no custom Application: M1 uses the default")
        assertEquals(m.usesPermissions, emptyList<String>())
        assertEquals(m.components.size, 1)
        val a = m.components.single()
        assertEquals(a.kind, ComponentKind.ACTIVITY)
        assertEquals(a.launchMode, LaunchMode.STANDARD)
        assertNotEquals(m.appThemeRes, 0, "own theme resource")
        assertNotEquals(m.labelRes, 0, "label is a string resource")
        assertTrue(m.extraProcesses.isEmpty())
        assertTrue(r.dexCount >= 1)
        assertTrue(r.hasResourcesTable)
    }

    @Test
    fun apkCarriesItsResourcesAndNoNativeCode() {
        ZipFile(apk).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            assertTrue(names.contains("resources.arsc"))
            assertTrue(names.any { it.startsWith("res/") && it.contains("layout") }, "compiled layouts present")
            assertTrue(names.count { it.startsWith("res/") && it.contains("layout") } >= 2, "portrait + landscape layout")
            assertFalse(names.any { it.startsWith("lib/") }, "no native libraries")
            assertFalse(names.any { it.startsWith("kotlin/") }, "no Kotlin stdlib: Java only")
        }
        assertTrue(apk.length() < 200_000, "tiny guest, got ${apk.length()} bytes")
        // APK Signature Scheme v2/v3 block magic (source.android.com, APK signing): the debug APK is signed.
        val bytes = apk.readBytes()
        val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        assertTrue((0..bytes.size - magic.size).any { i -> magic.indices.all { bytes[i + it] == magic[it] } }, "v2/v3 signing block present")
    }

    @Test
    fun installsIntoRegistryReadOnlyAndSurvivesReload() {
        val root = Files.createTempDirectory("blaze-kiln-guest").toFile()
        try {
            val layout = StorageLayout(File(root, "kiln"))
            val reg = GuestRegistry(layout)
            val m = ApkInspector.inspect(apk, phone34).manifest!!
            val rec = reg.install(apk, m, null, now = 42)
            assertEquals(rec.packageName, "com.blaze.kiln.testguest")
            assertEquals(rec.launcherActivity, "com.blaze.kiln.testguest.MainActivity")
            val copy = layout.apk(rec.packageName)
            assertTrue(copy.isFile)
            assertFalse(copy.canWrite(), "Android 14 refuses writable dex files")
            assertTrue(copy.readBytes().contentEquals(apk.readBytes()), "byte-identical copy")
            assertEquals(copy.length(), apk.length())
            // Reinstall over a read-only copy works (tester taps step 1 twice).
            reg.install(apk, m, null, now = 43)
            val again = GuestRegistry(layout)
            again.load()
            assertEquals(again.get(rec.packageName)!!.installedAtMillis, 43L)
            assertTrue(again.uninstall(rec.packageName))
            assertFalse(layout.appDir(rec.packageName).exists())
        } finally {
            root.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
