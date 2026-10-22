package com.blaze.assistant.mediaapps

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File

/**
 * Wave B item 20: Share and Export for library items picked with multi-select. Kept thin on purpose (another branch adds
 * private storage + its own export; merge by pointing [exportAll] at that helper).
 */
object MediaExport {
    /** A content:// uri other apps can read: MediaStore uris as they are, Blaze's own files through its FileProvider. */
    fun readable(ctx: Context, uri: String): Uri? = try {
        val u = Uri.parse(uri)
        val path = u.path
        if (u.scheme != "file") u else if (path == null) null else FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))
    } catch (_: Exception) { null }   // a file outside the FileProvider paths (old shared-storage downloads) can't be shared

    private fun mimeOf(ctx: Context, t: Track, u: Uri): String =
        runCatching { ctx.contentResolver.getType(u) }.getOrNull()
            ?: MediaFiles.mime(t.uri.substringAfterLast('.', "")).takeIf { it != "application/octet-stream" }
            ?: if (t.kind == "video") "video/*" else "audio/*"

    /** The share sheet for [tracks] (one or many), or null when none of them can be shared. */
    fun shareIntent(ctx: Context, tracks: List<Track>): Intent? {
        val pairs = tracks.mapNotNull { t -> readable(ctx, t.uri)?.let { t to it } }
        if (pairs.isEmpty()) return null
        val uris = ArrayList(pairs.map { it.second })
        val type = LibrarySelection.shareType(pairs.map { (t, u) -> mimeOf(ctx, t, u) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Copies each of [tracks] into the folder the user picked ([treeUri] from ACTION_OPEN_DOCUMENT_TREE). Streams with a
     * 64 KB buffer so a big film never sits in memory. Returns how many were copied. Call off the main thread.
     */
    fun exportAll(ctx: Context, tracks: List<Track>, treeUri: Uri): Int {
        val cr = ctx.contentResolver
        val dir = try { DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)) } catch (_: Exception) { return 0 }
        var ok = 0
        for (t in tracks) {
            var made: Uri? = null
            try {
                val src = Uri.parse(t.uri)
                val name = LibrarySelection.exportName(t.title, t.uri, t.kind)
                val mime = runCatching { cr.getType(src) }.getOrNull() ?: MediaFiles.mime(name.substringAfterLast('.'))
                val out = DocumentsContract.createDocument(cr, dir, mime, name) ?: continue
                made = out
                val input = if (src.scheme == "file") File(src.path ?: continue).inputStream() else cr.openInputStream(src) ?: continue
                input.use { i -> cr.openOutputStream(out)?.use { o -> i.copyTo(o, 64 * 1024) } ?: throw IllegalStateException("no output") }
                ok++; made = null
            } catch (_: Exception) {
            } finally {
                made?.let { runCatching { DocumentsContract.deleteDocument(cr, it) } }   // drop a half-written copy
            }
        }
        return ok
    }
}
