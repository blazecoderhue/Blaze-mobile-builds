package com.blaze.kiln.store

import java.io.File

// Where each guest's code and data live inside Blaze's own private storage (ENGINE_DESIGN.md 1.2 and 1.6).
// Layout is ours:
//   <root>/apps/<pkg>/base.apk          read-only copy of the guest APK (Android 14 dynamic-code rule)
//   <root>/apps/<pkg>/lib/<abi>/        extracted native libraries
//   <root>/apps/<pkg>/package.json      parsed GuestPackage
//   <root>/data/u<profile>/<pkg>/       the guest's dataDir (files/, cache/, databases/, shared_prefs/ ...)
//   <root>/ext/u<profile>/<pkg>/        app-specific external storage redirect target (M2)
// Pure JVM.

class StorageLayout(val root: File) {
    fun appDir(pkg: String): File = File(File(root, "apps"), PackageNames.requireValid(pkg))
    fun apk(pkg: String): File = File(appDir(pkg), "base.apk")
    fun splitApk(pkg: String, split: String): File = File(appDir(pkg), "split_" + PackageNames.requireSafeSegment(split) + ".apk")
    fun libDir(pkg: String, abi: String): File = File(File(appDir(pkg), "lib"), PackageNames.requireSafeSegment(abi))
    fun packageFile(pkg: String): File = File(appDir(pkg), "package.json")
    fun dataDir(profile: Int, pkg: String): File = File(File(File(root, "data"), "u${requireProfile(profile)}"), PackageNames.requireValid(pkg))
    fun externalDir(profile: Int, pkg: String): File = File(File(File(root, "ext"), "u${requireProfile(profile)}"), PackageNames.requireValid(pkg))

    /** Every directory that belongs to one guest (what uninstall deletes). */
    fun ownedBy(pkg: String, profiles: Collection<Int>): List<File> =
        listOf(appDir(pkg)) + profiles.flatMap { listOf(dataDir(it, pkg), externalDir(it, pkg)) }

    private fun requireProfile(p: Int): Int { require(p in 0..999) { "bad profile $p" }; return p }
}

object PackageNames {
    // Android package name rules (developer.android.com, <manifest package>): at least two segments,
    // each starting with a letter, then letters, digits or underscores.
    private val SEGMENT = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val SAFE = Regex("[A-Za-z0-9_.\\-]+")

    fun isValid(pkg: String): Boolean {
        if (pkg.length > 255) return false
        val parts = pkg.split('.')
        return parts.size >= 2 && parts.all { SEGMENT.matches(it) }
    }

    fun requireValid(pkg: String): String { require(isValid(pkg)) { "invalid package name: $pkg" }; return pkg }

    fun requireSafeSegment(s: String): String {
        require(s.isNotEmpty() && SAFE.matches(s) && s != "." && s != "..") { "unsafe path segment: $s" }
        return s
    }
}

/**
 * Maps paths a guest believes it owns onto its real place under [StorageLayout]. One table, used by the Java-level
 * storage shim in M2 and the native path hooks in M3. Anything that would escape the guest's own tree is refused.
 */
class PathRedirector(
    private val layout: StorageLayout,
    private val hostPackage: String,
    private val profile: Int,
    private val guestPackage: String,
    /** The device's external storage root, e.g. /storage/emulated/0 */
    private val externalRoot: String = "/storage/emulated/$profile",
) {
    private val dataDir = layout.dataDir(profile, guestPackage).path
    private val extDir = layout.externalDir(profile, guestPackage).path

    private val rules: List<Pair<String, String>> = listOf(
        "/data/data/$guestPackage" to dataDir,
        "/data/user/$profile/$guestPackage" to dataDir,
        "/data/user_de/$profile/$guestPackage" to dataDir,
        "$externalRoot/Android/data/$guestPackage" to extDir,
        "/sdcard/Android/data/$guestPackage" to extDir,
    )

    /**
     * Returns the redirected absolute path, the normalised path unchanged when no rule applies, or null when the
     * path is not allowed for this guest (another guest's tree or Blaze's own private data).
     */
    fun map(path: String): String? {
        val n = normalize(path) ?: return null
        for ((from, to) in rules) {
            if (n == from) return to
            if (n.startsWith("$from/")) return to + n.substring(from.length)
        }
        if (isHostPrivate(n)) {
            // The guest may reach its own real directories (already mapped), nothing else of Blaze's.
            return if (n == dataDir || n.startsWith("$dataDir/") || n == extDir || n.startsWith("$extDir/")) n else null
        }
        return n
    }

    private fun isHostPrivate(n: String): Boolean {
        val roots = listOf("/data/data/$hostPackage", "/data/user/$profile/$hostPackage", "/data/user_de/$profile/$hostPackage",
            "$externalRoot/Android/data/$hostPackage", layout.root.path)
        return roots.any { n == it || n.startsWith("$it/") }
    }

    companion object {
        /** Absolute-path normalisation without touching the filesystem; null if '..' climbs above '/' or path is relative. */
        fun normalize(path: String): String? {
            if (!path.startsWith("/")) return null
            if (path.indexOf('\u0000') >= 0) return null
            val out = ArrayList<String>()
            for (seg in path.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> { if (out.isEmpty()) return null; out.removeAt(out.size - 1) }
                    else -> out.add(seg)
                }
            }
            return "/" + out.joinToString("/")
        }
    }
}
