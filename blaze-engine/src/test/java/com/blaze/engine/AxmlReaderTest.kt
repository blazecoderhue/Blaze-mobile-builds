package com.blaze.engine

import com.blaze.engine.manifest.AndroidAttr
import com.blaze.engine.manifest.AxmlFormatException
import com.blaze.engine.manifest.AxmlReader
import com.blaze.engine.manifest.ComponentKind
import com.blaze.engine.manifest.LaunchMode
import com.blaze.engine.manifest.ManifestReader
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/** A1: the binary-XML reader on a real AAPT2-built manifest (Blaze's own 0.45.18 APK, our app). */
class AxmlReaderTest {

    private fun fixture(): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/host-manifest.axml")) { "fixture missing" }
            .use { it.readBytes() }

    @Test
    fun parsesRootAndPackage() {
        val root = AxmlReader.parse(fixture())
        assertEquals(root.name, "manifest")
        assertEquals(root.plain("package")?.asString(), "com.blaze.assistant")
        assertEquals(root.attr(AndroidAttr.VERSION_CODE, "versionCode")?.asInt(), 163)
        assertEquals(root.attr(AndroidAttr.VERSION_NAME, "versionName")?.asString(), "0.45.18")
    }

    @Test
    fun readsManifestModel() {
        val m = ManifestReader.read(fixture())
        assertEquals(m.packageName, "com.blaze.assistant")
        assertEquals(m.versionCode, 163L)
        assertEquals(m.minSdk, 26)
        assertEquals(m.targetSdk, 34)
        assertEquals(m.applicationClass, "com.blaze.assistant.BlazeApp")
        assertEquals(m.appComponentFactory, "androidx.core.app.CoreComponentFactory")
        assertTrue(m.hasCode)
        assertNull(m.sharedUserId)
        assertTrue(m.appThemeRes != 0, "application theme is a resource reference")
        assertEquals(m.labelText, "Blaze")
    }

    @Test
    fun countsComponents() {
        val m = ManifestReader.read(fixture())
        assertEquals(m.activities.size, 12)
        assertEquals(m.services.size, 23)
        assertEquals(m.providers.size, 3)
        assertEquals(m.receivers.size, 17)
        assertEquals(m.usesPermissions.size, 48)
        assertTrue("android.permission.INTERNET" in m.usesPermissions)
    }

    @Test
    fun findsLauncherActivityAndLaunchModes() {
        val m = ManifestReader.read(fixture())
        assertEquals(m.launcherActivity, "com.blaze.assistant.MainActivity")
        assertEquals(m.activity("com.blaze.assistant.MainActivity")?.launchMode, LaunchMode.SINGLE_TASK)
        val camera = m.activity("com.blaze.assistant.ui.CameraActivity")
        assertNotNull(camera)
        assertEquals(camera!!.launchMode, LaunchMode.STANDARD)
        assertEquals(camera.exported, false)
        val terminal = m.activity("com.blaze.assistant.terminal.BlazeTerminalActivity")
        assertEquals(terminal?.taskAffinity, "com.blaze.assistant.terminal")
        assertTrue((terminal?.configChanges ?: 0) != 0)
    }

    @Test
    fun readsIntentFiltersAndProviders() {
        val m = ManifestReader.read(fixture())
        val main = m.activity("com.blaze.assistant.MainActivity")!!
        assertTrue(main.intentFilters.any { it.isLauncher })
        assertTrue(main.intentFilters.any { "android.intent.action.VIEW" in it.actions })
        val fileProvider = m.providers.first { it.className == "androidx.core.content.FileProvider" }
        assertEquals(fileProvider.authorities, listOf("com.blaze.assistant.files"))
        assertEquals(fileProvider.exported, false)
    }

    @Test
    fun readsMetaData() {
        val m = ManifestReader.read(fixture())
        assertTrue(m.metaData.keys.any { it.startsWith("com.google.android.gms") }, "host declares a gms meta-data key")
    }

    @Test
    fun relativeClassAndProcessNames() {
        assertEquals(ManifestReader.className("a.b", ".C"), "a.b.C")
        assertEquals(ManifestReader.className("a.b", "C"), "a.b.C")
        assertEquals(ManifestReader.className("a.b", "x.y.C"), "x.y.C")
        assertEquals(ManifestReader.processName("a.b", ":work"), "a.b:work")
        assertEquals(ManifestReader.processName("a.b", "other.proc"), "other.proc")
    }

    @Test
    fun rejectsGarbage() {
        for (bad in listOf(ByteArray(0), ByteArray(8), "<manifest/>".toByteArray())) {
            var threw = false
            try { AxmlReader.parse(bad) } catch (e: AxmlFormatException) { threw = true }
            assertTrue(threw, "should refuse ${bad.size} bytes of non-AXML")
        }
    }

    @Test
    fun rejectsTruncatedFixture() {
        val full = fixture()
        var threw = false
        try { AxmlReader.parse(full.copyOf(full.size / 2)) } catch (e: AxmlFormatException) { threw = true }
        assertTrue(threw, "truncated manifest must be refused, not half-parsed")
    }

    @Test
    fun componentKindsAreSeparated() {
        val m = ManifestReader.read(fixture())
        assertTrue(m.components.all { it.kind in ComponentKind.entries })
        assertEquals(m.components.size, m.activities.size + m.services.size + m.providers.size + m.receivers.size)
    }
}
