package com.blaze.assistant.mediaapps

import java.io.File
import java.io.IOException

/**
 * Wave B 1+2: pure rules for Blaze's private media storage and Export (unit-tested in WaveB2StorageTest).
 */
object MediaStorePolicy {
    const val ROOT = "media"

    /** Private folder (relative to filesDir) per kind. */
    fun privateRel(kind: String): String = ROOT + "/" + (if (kind == "video") "videos" else "music")

    /** "Song.m4a" → "Song (2).m4a" … when the name is taken. */
    fun uniqueName(name: String, ext: String, taken: Set<String>): String {
        if (name !in taken) return name
        val base = name.substringBeforeLast('.', name)
        val e = ext.ifBlank { name.substringAfterLast('.', "") }
        var n = 2
        while (true) {
            val c = if (e.isBlank()) "$base ($n)" else "$base ($n).$e"
            if (c !in taken) return c
            n++
        }
    }

    /** Export targets offered in the sheet. */
    enum class Target(val label: String) { DEFAULT("Music / Movies"), DOWNLOADS("Downloads"), FOLDER("Choose folder…"), SHARE("Share…") }

    /** Relative shared path for an export: Downloads/Blaze, or the kind's own Music/Blaze/Music, Movies/Blaze/Videos. */
    fun exportRel(kind: String, target: Target, downloadsDir: String = "Download", musicDir: String = "Music", moviesDir: String = "Movies"): String = when (target) {
        Target.DOWNLOADS -> "$downloadsDir/Blaze"
        else -> if (kind == "video") "$moviesDir/Blaze/Videos" else "$musicDir/Blaze/Music"
    }

    /** Share intent type for a set of files: one mime, "audio/ *"-style family, or anything. */
    fun shareType(mimes: List<String>): String {
        val d = mimes.distinct()
        if (d.size == 1) return d[0]
        val fam = d.map { it.substringBefore('/') }.distinct()
        return if (fam.size == 1) fam[0] + "/*" else "*/*"
    }

    /** A user-facing summary after a bulk export. */
    fun exportSummary(ok: Int, failed: Int, where: String): String = when {
        ok == 0 && failed == 0 -> "Nothing to export"
        failed == 0 -> if (ok == 1) "Exported to $where" else "Exported $ok files to $where"
        ok == 0 -> "Couldn't export" + if (failed > 1) " $failed files" else ""
        else -> "Exported $ok to $where, $failed couldn't be copied"
    }
}

/**
 * Wave B 1/19: a temp→final move that never leaves a half-written final file: rename when both are on one volume,
 * otherwise copy to "<final>.tmp" next to the target, fsync, rename, then remove the source.
 */
object AtomicMove {
    fun move(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (dst.exists()) throw IOException("${dst.name} already exists")
        if (src.renameTo(dst)) return
        val tmp = File(dst.path + ".tmp")
        try {
            src.inputStream().use { inp -> java.io.FileOutputStream(tmp).use { out -> inp.copyTo(out, 256 * 1024); out.fd.sync() } }
            if (tmp.length() != src.length()) throw IOException("Copy was short")
            if (!tmp.renameTo(dst)) throw IOException("Couldn't move into place")
            src.delete()
        } catch (e: Exception) { tmp.delete(); throw e }
    }
}
