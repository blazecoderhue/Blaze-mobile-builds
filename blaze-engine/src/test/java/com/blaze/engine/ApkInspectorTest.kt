package com.blaze.engine

import com.blaze.engine.apk.AbiSelector
import com.blaze.engine.apk.ApkInspector
import com.blaze.engine.apk.Blocker
import com.blaze.engine.apk.Concern
import com.blaze.engine.apk.DeviceProfile
import com.blaze.engine.apk.ElfAlign
import com.blaze.engine.apk.Milestone
import com.blaze.engine.manifest.AndroidAttr
import com.blaze.engine.manifest.LaunchMode
import com.blaze.engine.manifest.ManifestReader
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.nio.file.Files

/** A2: eligibility verdicts per milestone on synthetic APKs; ELF alignment; ABI choice. */
class ApkInspectorTest {
    private val phone = DeviceProfile(sdkInt = 34, supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
    private fun tmp() = Files.createTempDirectory("blaze-engine-apk").toFile()

    @Test
    fun utf16SyntheticManifestRoundTrips() {
        val m = ManifestReader.read(TestAxml.simpleGuest(launchMode = 1))
        assertEquals(m.packageName, "org.example.calc")
        assertEquals(m.versionCode, 7L)
        assertEquals(m.versionName, "1.2")
        assertEquals(m.minSdk, 21)
        assertEquals(m.targetSdk, 33)
        assertEquals(m.launcherActivity, "org.example.calc.MainActivity")
        assertEquals(m.activities.single().launchMode, LaunchMode.SINGLE_TOP)
        assertEquals(m.appThemeRes, 0x7f0f0001)
        assertEquals(m.labelText, "Calc")
    }

    @Test
    fun simpleAppIsM1() {
        val r = ApkInspector.inspect(TestAxml.apk(tmp(), "calc.apk", TestAxml.simpleGuest()), phone)
        assertEquals(r.blockers, emptyList<Blocker>())
        assertEquals(r.concerns, emptyList<Concern>())
        assertEquals(r.milestone, Milestone.M1)
        assertEquals(r.dexCount, 1)
        assertTrue(r.hasResourcesTable)
        assertNull(r.chosenAbi)
    }

    @Test
    fun secondActivityAndServiceAreM2() {
        val extra = listOf(
            TestAxml.e("activity", TestAxml.str("name", AndroidAttr.NAME, ".Settings")),
            TestAxml.e("service", TestAxml.str("name", AndroidAttr.NAME, "org.example.calc.Sync"), TestAxml.str("process", AndroidAttr.PROCESS, ":sync")),
        )
        val r = ApkInspector.inspect(TestAxml.apk(tmp(), "m2.apk", TestAxml.simpleGuest(extraApp = extra)), phone)
        assertEquals(r.milestone, Milestone.M2)
        assertTrue(Concern.MULTI_ACTIVITY in r.concerns)
        assertTrue(Concern.SERVICES in r.concerns)
        assertTrue(Concern.MULTI_PROCESS in r.concerns)
        assertEquals(r.manifest!!.services.single().process, "org.example.calc:sync")
    }

    @Test
    fun playServicesAndNativeAreM3() {
        val extra = listOf(TestAxml.e("meta-data", TestAxml.str("name", AndroidAttr.NAME, "com.google.android.gms.version"), TestAxml.int("value", AndroidAttr.VALUE, 12451000)))
        val apk = TestAxml.apk(tmp(), "m3.apk", TestAxml.simpleGuest(extraApp = extra), mapOf(
            "classes.dex" to ByteArray(112),
            "lib/arm64-v8a/libcore.so" to TestAxml.elf(true, 16384, 16384),
            "lib/armeabi-v7a/libcore.so" to TestAxml.elf(false, 4096),
        ))
        val r = ApkInspector.inspect(apk, phone)
        assertEquals(r.milestone, Milestone.M3)
        assertTrue(Concern.PLAY_SERVICES in r.concerns)
        assertTrue(Concern.NATIVE_CODE in r.concerns)
        assertEquals(r.chosenAbi, "arm64-v8a")
        assertEquals(r.nativeLibs["arm64-v8a"], listOf("libcore.so"))
        assertEquals(r.manifest!!.metaData["com.google.android.gms.version"], "12451000")
    }

    @Test
    fun blockers() {
        val noAbi = ApkInspector.inspect(TestAxml.apk(tmp(), "x86.apk", TestAxml.simpleGuest(), mapOf(
            "classes.dex" to ByteArray(8), "lib/x86_64/liba.so" to TestAxml.elf(true, 4096))), phone)
        assertTrue(Blocker.NO_MATCHING_ABI in noAbi.blockers)
        assertNull(noAbi.milestone)

        val tooNew = ApkInspector.inspect(TestAxml.apk(tmp(), "new.apk", TestAxml.simpleGuest(minSdk = 35)), phone)
        assertTrue(Blocker.MIN_SDK_TOO_HIGH in tooNew.blockers)

        val noDex = ApkInspector.inspect(TestAxml.apk(tmp(), "nodex.apk", TestAxml.simpleGuest(), emptyMap()), phone)
        assertTrue(Blocker.NO_CODE in noDex.blockers)

        val launcherFilter = TestAxml.e("intent-filter", children = listOf(
            TestAxml.e("action", TestAxml.str("name", AndroidAttr.NAME, "android.intent.action.MAIN")),
            TestAxml.e("category", TestAxml.str("name", AndroidAttr.NAME, "android.intent.category.LAUNCHER")),
        ))
        val activity = TestAxml.e("activity", TestAxml.str("name", AndroidAttr.NAME, ".Main"), children = listOf(launcherFilter))
        val application = TestAxml.e("application", children = listOf(activity))
        // isSplitRequired lives on <manifest>
        val splitManifest = TestAxml.write(TestAxml.e("manifest",
            TestAxml.plain("package", "org.example.split"),
            TestAxml.bool("isSplitRequired", 0x01010591, true),
            children = listOf(application)))
        val split = ApkInspector.inspect(TestAxml.apk(tmp(), "split.apk", splitManifest), phone)
        assertTrue(Blocker.SPLIT_REQUIRED in split.blockers, "blockers=${split.blockers} flags=${split.manifest?.flags}")
    }

    @Test
    fun unalignedLibsBlockOn16kDevices() {
        val apk = TestAxml.apk(tmp(), "old.apk", TestAxml.simpleGuest(), mapOf(
            "classes.dex" to ByteArray(8), "lib/arm64-v8a/libold.so" to TestAxml.elf(true, 65536, 4096)))
        val r16 = ApkInspector.inspect(apk, phone.copy(pageSize = 16384))
        assertTrue(Blocker.UNALIGNED_16K in r16.blockers)
        val r4 = ApkInspector.inspect(apk, phone)
        assertTrue(Blocker.UNALIGNED_16K !in r4.blockers)
    }

    @Test
    fun elfAlignment() {
        assertEquals(ElfAlign.minLoadAlign(TestAxml.elf(true, 16384, 4096)), 4096L)
        assertEquals(ElfAlign.minLoadAlign(TestAxml.elf(false, 16384)), 16384L)
        assertNull(ElfAlign.minLoadAlign(ByteArray(64)))
    }

    @Test
    fun abiChoiceFollowsDeviceOrder() {
        assertEquals(AbiSelector.choose(setOf("armeabi-v7a", "arm64-v8a"), phone.supportedAbis), "arm64-v8a")
        assertEquals(AbiSelector.choose(setOf("armeabi-v7a"), phone.supportedAbis), "armeabi-v7a")
        assertNull(AbiSelector.choose(setOf("x86"), phone.supportedAbis))
        assertNull(AbiSelector.choose(emptySet(), phone.supportedAbis))
    }

    @Test
    fun hostManifestNeedsM3() {
        // Blaze itself uses Play Services pieces and many components: a good "real app" check of the rules.
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/host-manifest.axml")).use { it.readBytes() }
        val r = ApkInspector.evaluate(ManifestReader.read(bytes), 3, true, emptyMap(), null, false, phone)
        assertTrue(Concern.PLAY_SERVICES in r.concerns)
        assertTrue(Concern.PRIVILEGED_PERMISSIONS in r.concerns)
        assertEquals(r.milestone, Milestone.M3)
    }
}
