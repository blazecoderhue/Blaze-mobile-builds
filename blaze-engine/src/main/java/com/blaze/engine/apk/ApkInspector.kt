package com.blaze.engine.apk

import com.blaze.engine.manifest.GuestManifest
import com.blaze.engine.manifest.ManifestReader
import java.io.File
import java.util.zip.ZipFile

// Opens a guest APK (a zip, per the APK format on developer.android.com), reads its manifest and native-lib layout,
// and decides what milestone of the engine can run it (ENGINE_DESIGN.md section 3). Pure JVM.

enum class Blocker(val why: String) {
    NO_MANIFEST("APK has no AndroidManifest.xml"),
    NO_LAUNCHER("no launcher activity"),
    NO_CODE("hasCode=false"),
    SPLIT_REQUIRED("app needs split APKs (install the full bundle)"),
    SHARED_USER_ID("uses sharedUserId"),
    MIN_SDK_TOO_HIGH("minSdk is higher than this device"),
    NO_MATCHING_ABI("no native libraries for this device's ABIs"),
    UNALIGNED_16K("native libraries are not 16 KB aligned on a 16 KB page device"),
}

enum class Concern(val why: String) {
    PLAY_SERVICES("uses Google Play Services (sign-in, push, maps may fail)"),
    NATIVE_CODE("has native code"),
    MULTI_ACTIVITY("more than one activity"),
    SERVICES("declares services"),
    PROVIDERS("declares content providers"),
    RECEIVERS("declares broadcast receivers"),
    MULTI_PROCESS("uses more than one process"),
    CUSTOM_COMPONENT_FACTORY("custom AppComponentFactory"),
    PRIVILEGED_PERMISSIONS("asks for permissions Blaze does not hold for guests"),
}

/** Highest engine milestone needed to run the app; null when a [Blocker] applies. */
enum class Milestone { M1, M2, M3 }

data class ApkReport(
    val manifest: GuestManifest?,
    val dexCount: Int,
    val hasResourcesTable: Boolean,
    /** ABI → library file names (no path). */
    val nativeLibs: Map<String, List<String>>,
    val blockers: List<Blocker>,
    val concerns: List<Concern>,
    val chosenAbi: String?,
) {
    val milestone: Milestone?
        get() = when {
            blockers.isNotEmpty() -> null
            concerns.any { it == Concern.PLAY_SERVICES || it == Concern.NATIVE_CODE || it == Concern.CUSTOM_COMPONENT_FACTORY || it == Concern.PRIVILEGED_PERMISSIONS } -> Milestone.M3
            concerns.isNotEmpty() -> Milestone.M2
            else -> Milestone.M1
        }
}

data class DeviceProfile(
    val sdkInt: Int,
    /** Build.SUPPORTED_ABIS order (most preferred first). */
    val supportedAbis: List<String>,
    val pageSize: Int = 4096,
)

object ApkInspector {
    /** Permissions a guest may not get through Blaze (ENGINE_DESIGN.md 1.7). */
    val PRIVILEGED = setOf(
        "android.permission.BIND_ACCESSIBILITY_SERVICE", "android.permission.BIND_DEVICE_ADMIN",
        "android.permission.BIND_VPN_SERVICE", "android.permission.BIND_INPUT_METHOD",
        "android.permission.QUERY_ALL_PACKAGES", "android.permission.READ_SMS", "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS", "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.REQUEST_INSTALL_PACKAGES",
    )

    private val LIB = Regex("^lib/([^/]+)/([^/]+\\.so)$")
    private val DEX = Regex("^classes\\d*\\.dex$")

    fun inspect(apk: File, device: DeviceProfile): ApkReport = ZipFile(apk).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toList()
        val manifestEntry = zip.getEntry("AndroidManifest.xml")
        val manifest = manifestEntry?.let { e -> zip.getInputStream(e).use { ManifestReader.read(it.readBytes()) } }
        val libs = LinkedHashMap<String, MutableList<String>>()
        for (n in names) LIB.matchEntire(n)?.let { m -> libs.getOrPut(m.groupValues[1]) { mutableListOf() }.add(m.groupValues[2]) }
        val abi = AbiSelector.choose(libs.keys, device.supportedAbis)
        val unaligned = if (abi != null && device.pageSize > 4096) {
            libs[abi].orEmpty().any { lib ->
                val e = zip.getEntry("lib/$abi/$lib") ?: return@any false
                val head = zip.getInputStream(e).use { readHead(it, 64 * 1024) }
                ElfAlign.minLoadAlign(head)?.let { it < device.pageSize } ?: false
            }
        } else false
        evaluate(manifest, names.count { DEX.matches(it) }, "resources.arsc" in names, libs, abi, unaligned, device)
    }

    /** Reads up to [max] bytes (InputStream.readNBytes is API 33+, the engine runs from API 26). */
    private fun readHead(input: java.io.InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = input.read(buf, n, max - n)
            if (r < 0) break
            n += r
        }
        return buf.copyOf(n)
    }

    fun evaluate(
        manifest: GuestManifest?,
        dexCount: Int,
        hasArsc: Boolean,
        libs: Map<String, List<String>>,
        abi: String?,
        unaligned16k: Boolean,
        device: DeviceProfile,
    ): ApkReport {
        val blockers = ArrayList<Blocker>()
        val concerns = ArrayList<Concern>()
        if (manifest == null) {
            blockers += Blocker.NO_MANIFEST
            return ApkReport(null, dexCount, hasArsc, libs, blockers, concerns, abi)
        }
        if (manifest.launcherActivity == null) blockers += Blocker.NO_LAUNCHER
        if (!manifest.hasCode || dexCount == 0) blockers += Blocker.NO_CODE
        if (manifest.flags["isSplitRequired"].let { it == "true" || it == "-1" } || manifest.flags.containsKey("requiredSplitTypes")) blockers += Blocker.SPLIT_REQUIRED
        if (manifest.sharedUserId != null) blockers += Blocker.SHARED_USER_ID
        if (manifest.minSdk > device.sdkInt) blockers += Blocker.MIN_SDK_TOO_HIGH
        if (libs.isNotEmpty() && abi == null) blockers += Blocker.NO_MATCHING_ABI
        if (unaligned16k) blockers += Blocker.UNALIGNED_16K

        val gms = manifest.metaData.keys.any { it.startsWith("com.google.android.gms") } ||
            manifest.components.any { it.className.startsWith("com.google.android.gms.") || it.className.startsWith("com.google.firebase.") }
        if (gms) concerns += Concern.PLAY_SERVICES
        if (libs.isNotEmpty()) concerns += Concern.NATIVE_CODE
        if (manifest.activities.size > 1) concerns += Concern.MULTI_ACTIVITY
        if (manifest.services.isNotEmpty()) concerns += Concern.SERVICES
        if (manifest.providers.isNotEmpty()) concerns += Concern.PROVIDERS
        if (manifest.receivers.isNotEmpty()) concerns += Concern.RECEIVERS
        if (manifest.extraProcesses.isNotEmpty()) concerns += Concern.MULTI_PROCESS
        val factory = manifest.appComponentFactory
        if (factory != null && factory != "androidx.core.app.CoreComponentFactory") concerns += Concern.CUSTOM_COMPONENT_FACTORY
        if (manifest.usesPermissions.any { it in PRIVILEGED }) concerns += Concern.PRIVILEGED_PERMISSIONS
        return ApkReport(manifest, dexCount, hasArsc, libs, blockers, concerns, abi)
    }
}

object AbiSelector {
    /** First device ABI the APK ships libraries for; all libraries must then come from that one ABI. */
    fun choose(apkAbis: Collection<String>, deviceAbis: List<String>): String? =
        if (apkAbis.isEmpty()) null else deviceAbis.firstOrNull { it in apkAbis }
}

/** Minimal ELF reader: smallest PT_LOAD p_align (ELF spec / bionic linker rules for 16 KB pages). */
object ElfAlign {
    private const val PT_LOAD = 1

    fun minLoadAlign(head: ByteArray): Long? {
        if (head.size < 52 || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) return null
        val is64 = head[4].toInt() == 2
        if (head[5].toInt() != 1) return null // little-endian only (all Android ABIs)
        fun u16(o: Int) = (head[o].toInt() and 0xff) or ((head[o + 1].toInt() and 0xff) shl 8)
        fun u32(o: Int) = (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)
        fun u64(o: Int) = u32(o) or (u32(o + 4) shl 32)
        val phoff = if (is64) u64(0x20) else u32(0x1c)
        val phentsize = if (is64) u16(0x36) else u16(0x2a)
        val phnum = if (is64) u16(0x38) else u16(0x2c)
        var min: Long? = null
        for (i in 0 until phnum) {
            val o = phoff + i.toLong() * phentsize
            if (o < 0 || o + phentsize > head.size) return min
            val p = o.toInt()
            if (u32(p) != PT_LOAD.toLong()) continue
            val align = if (is64) u64(p + 0x30) else u32(p + 0x1c)
            min = if (min == null) align else minOf(min, align)
        }
        return min
    }
}
