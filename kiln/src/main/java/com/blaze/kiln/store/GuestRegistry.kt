package com.blaze.kiln.store

import com.blaze.kiln.manifest.GuestManifest
import java.io.File

// Kiln's own list of installed guest apps (ENGINE_DESIGN.md 1.2). One small properties file per package next to
// the APK; the full manifest is re-parsed from base.apk on demand, so there is no second source of truth to drift.

data class GuestPackage(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val abi: String?,
    val installedAtMillis: Long,
    val launcherActivity: String?,
    val profiles: Set<Int>,
) {
    fun toProperties(): java.util.Properties = java.util.Properties().also { p ->
        p["package"] = packageName
        p["versionCode"] = versionCode.toString()
        versionName?.let { p["versionName"] = it }
        abi?.let { p["abi"] = it }
        p["installedAt"] = installedAtMillis.toString()
        launcherActivity?.let { p["launcher"] = it }
        p["profiles"] = profiles.sorted().joinToString(",")
        p["format"] = "1"
    }

    companion object {
        fun from(m: GuestManifest, abi: String?, now: Long, profiles: Set<Int> = setOf(0)) =
            GuestPackage(m.packageName, m.versionCode, m.versionName, abi, now, m.launcherActivity, profiles)

        fun fromProperties(p: java.util.Properties): GuestPackage? {
            if (p.getProperty("format") != "1") return null
            val pkg = p.getProperty("package")?.takeIf { PackageNames.isValid(it) } ?: return null
            return GuestPackage(
                packageName = pkg,
                versionCode = p.getProperty("versionCode")?.toLongOrNull() ?: return null,
                versionName = p.getProperty("versionName"),
                abi = p.getProperty("abi"),
                installedAtMillis = p.getProperty("installedAt")?.toLongOrNull() ?: 0L,
                launcherActivity = p.getProperty("launcher"),
                profiles = p.getProperty("profiles").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet(),
            )
        }
    }
}

class GuestRegistry(private val layout: StorageLayout) {
    private val cache = LinkedHashMap<String, GuestPackage>()

    @Synchronized
    fun load(): List<GuestPackage> {
        cache.clear()
        val apps = File(layout.root, "apps").listFiles()?.sortedBy { it.name } ?: emptyList()
        for (dir in apps) {
            if (!PackageNames.isValid(dir.name)) continue
            val f = layout.packageFile(dir.name)
            if (!f.isFile) continue
            val p = java.util.Properties()
            runCatching { f.inputStream().use { p.load(it) } }.getOrNull() ?: continue
            GuestPackage.fromProperties(p)?.takeIf { it.packageName == dir.name }?.let { cache[it.packageName] = it }
        }
        return cache.values.toList()
    }

    @Synchronized fun get(pkg: String): GuestPackage? = cache[pkg]
    @Synchronized fun packages(): Set<String> = cache.keys.toSet()
    @Synchronized fun all(): List<GuestPackage> = cache.values.toList()

    /**
     * Copies [apk] in as the guest's base.apk (read-only), writes its record, and returns it. Native library extraction
     * happens in the runtime (it needs the chosen ABI from ApkInspector); [abi] is recorded here.
     */
    @Synchronized
    fun install(apk: File, manifest: GuestManifest, abi: String?, now: Long = System.currentTimeMillis()): GuestPackage {
        val pkg = PackageNames.requireValid(manifest.packageName)
        val dir = layout.appDir(pkg)
        dir.mkdirs()
        val target = layout.apk(pkg)
        val tmp = File(dir, "base.apk.tmp")
        tmp.delete()
        apk.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (target.exists()) { target.setWritable(true); target.delete() }
        check(tmp.renameTo(target)) { "could not move APK into place" }
        // Android 14+ refuses to load dex from a writable file (developer.android.com, safer dynamic code loading).
        target.setWritable(false, false)
        target.setReadOnly()
        val record = GuestPackage.from(manifest, abi, now, cache[pkg]?.profiles ?: setOf(0))
        val pf = layout.packageFile(pkg)
        val ptmp = File(dir, "package.json.tmp")
        ptmp.outputStream().use { record.toProperties().store(it, "Blaze Kiln guest package") }
        check(ptmp.renameTo(pf) || (pf.delete() && ptmp.renameTo(pf))) { "could not write package record" }
        cache[pkg] = record
        return record
    }

    /** Deletes everything the guest owns. Returns false if it was not installed. */
    @Synchronized
    fun uninstall(pkg: String): Boolean {
        val rec = cache.remove(pkg) ?: return false
        for (d in layout.ownedBy(pkg, rec.profiles)) deleteTree(d)
        return true
    }

    private fun deleteTree(f: File) {
        if (!f.exists()) return
        // Never follow a symlink out of our tree.
        if (f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath())) f.listFiles()?.forEach { deleteTree(it) }
        f.setWritable(true)
        f.delete()
    }
}
