package com.blaze.assistant.blazeos

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * v0.33.2 BlazeOS's own disk, sandboxed at filesDir/blazeos/ (nothing outside it is read or written by BlazeOS apps):
 *
 *   apps/<manifest id>/     an installed app's package (web apps: the unzipped HTML / JS)
 *   data/<app>/             each app's own data folder (web storage, notes, the Calendar's events …)
 *   system/                 registry.json (installed apps), permissions.json, wallpaper.jpg
 *   cache/                  thumbnails and other things that can go at any time (Pause drops part of it)
 *
 * Factory reset deletes the whole folder; nothing else of Blaze's is touched.
 */
object OsDisk {
    fun root(ctx: Context) = File(ctx.filesDir, "blazeos")
    fun apps(ctx: Context) = File(root(ctx), "apps")
    fun data(ctx: Context) = File(root(ctx), "data")
    fun system(ctx: Context) = File(root(ctx), "system")
    fun cache(ctx: Context) = File(root(ctx), "cache")

    fun ensure(ctx: Context) { for (d in listOf(apps(ctx), data(ctx), system(ctx), cache(ctx))) d.mkdirs() }

    /** "pkg:blazeos.snake" → "pkg_blazeos.snake"; anything odd becomes "_". */
    fun safeName(appId: String): String = appId.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(80).ifEmpty { "_" }

    fun appDir(ctx: Context, manifestId: String) = File(apps(ctx), safeName(manifestId))
    fun dataDir(ctx: Context, appId: String) = File(data(ctx), safeName(appId)).apply { mkdirs() }
    fun wallpaper(ctx: Context) = File(system(ctx), "wallpaper.jpg")

    fun size(f: File): Long = try {
        if (!f.exists()) 0L else if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) { 0L }

    /** Bytes the BlazeOS disk holds (apps + data + system; the cache isn't counted against the quota). */
    fun used(ctx: Context): Long = size(apps(ctx)) + size(data(ctx)) + size(system(ctx))

    fun readText(f: File): String? = try { if (f.exists()) f.readText() else null } catch (_: Exception) { null }

    /** Written to a temp file then renamed, so a kill mid-write never leaves half a file. */
    fun writeText(f: File, text: String) {
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
        } catch (_: Exception) { }
    }

    fun registry(ctx: Context): OsRegistry = OsRegistry.fromJson(readText(File(system(ctx), "registry.json")))
    fun saveRegistry(ctx: Context, r: OsRegistry) = writeText(File(system(ctx), "registry.json"), r.toJson())
    fun grants(ctx: Context): OsGrants = OsGrants.fromJson(readText(File(system(ctx), "permissions.json")))
    fun saveGrants(ctx: Context, g: OsGrants) = writeText(File(system(ctx), "permissions.json"), g.toJson())

    /** Factory reset: the whole BlazeOS disk goes, then an empty one is made. */
    fun wipe(ctx: Context) { try { root(ctx).deleteRecursively() } catch (_: Exception) { }; ensure(ctx) }

    /** Keeps the cache under [max] bytes, oldest files first. */
    fun trimCache(ctx: Context, max: Long) {
        try {
            val files = cache(ctx).walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toMutableList()
            var total = files.sumOf { it.length() }
            while (total > max && files.isNotEmpty()) { val f = files.removeAt(0); total -= f.length(); f.delete() }
        } catch (_: Exception) { }
    }

    /**
     * Unzips an app package into [dest] (replacing what was there). Entries that would land outside [dest] (zip slip),
     * absolute paths and anything past [maxBytes] in total are refused. Returns the bytes written, or -1.
     */
    fun unzip(input: InputStream, dest: File, maxBytes: Long): Long {
        val tmp = File(dest.parentFile, dest.name + ".installing")
        tmp.deleteRecursively(); tmp.mkdirs()
        val base = tmp.canonicalPath + File.separator
        var total = 0L
        try {
            ZipInputStream(input).use { z ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name.replace('\\', '/')
                    if (name.startsWith("/") || name.split('/').any { it == ".." }) { tmp.deleteRecursively(); return -1 }
                    val out = File(tmp, name)
                    if (!out.canonicalPath.startsWith(base)) { tmp.deleteRecursively(); return -1 }
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        while (true) {
                            val n = z.read(buf); if (n < 0) break
                            total += n
                            if (total > maxBytes) { tmp.deleteRecursively(); return -1 }
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
        } catch (_: Exception) { tmp.deleteRecursively(); return -1 }
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) { tmp.deleteRecursively(); return -1 }
        return total
    }
}
