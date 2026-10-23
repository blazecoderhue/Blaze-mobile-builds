package com.blaze.assistant.mediaapps

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wave B 2: Export out of Blaze's private storage, per item or in bulk: to the shared Music/Movies or Downloads
 * folders, to a folder the user picks (SAF), or through the system share sheet. Rules in [MediaStorePolicy].
 * Wave B3's multi-select bar uses [shareIntent] / [exportAll]; per-item menus and the bulk Export menu use [ExportActions].
 */
object MediaExport {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun fileOf(t: Track): File? = Uri.parse(t.uri).takeIf { it.scheme == "file" }?.path?.let { File(it) }?.takeIf { it.isFile }
    private fun nameOf(t: Track): String = fileOf(t)?.name ?: DlLogic.fileName(t.title, if (t.kind == "video") "mp4" else "m4a")

    /** A uri another app may read: private files go through Blaze's FileProvider; MediaStore uris are already shareable. */
    fun shareUri(ctx: Context, t: Track): Uri = fileOf(t)?.let { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) } ?: Uri.parse(t.uri)

    fun share(ctx: Context, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val uris = ArrayList(tracks.map { shareUri(ctx, it) })
        val type = MediaStorePolicy.shareType(tracks.map { MediaFiles.mime(nameOf(it).substringAfterLast('.', "")) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = type
        send.clipData = ClipData.newRawUri("", uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, if (uris.size == 1) "Share" else "Share ${uris.size} files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- wave B3 multi-select helpers (selection bar Share / Export to folder) ----
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

    /** Copies to the shared Music/Movies (DEFAULT) or Downloads folder. */
    fun toShared(ctx: Context, tracks: List<Track>, target: MediaStorePolicy.Target, done: (String) -> Unit) {
        io.launch {
            var ok = 0; var bad = 0
            for (t in tracks) {
                val f = fileOf(t)
                if (f == null) { bad++; continue }
                val rel = MediaStorePolicy.exportRel(t.kind, target, Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_MOVIES)
                if (runCatching { MediaFiles.exportShared(ctx, f, t.kind, f.name, t.title, t.artist, t.durationMs, rel) }.isSuccess) ok++ else bad++
            }
            val where = if (target == MediaStorePolicy.Target.DOWNLOADS) "Downloads" else if (tracks.all { it.kind == "video" }) "Movies" else "Music"
            withContext(Dispatchers.Main) { done(MediaStorePolicy.exportSummary(ok, bad, where)) }
        }
    }

    /** Copies into a folder the user picked with ACTION_OPEN_DOCUMENT_TREE. */
    fun toTree(ctx: Context, tree: Uri, tracks: List<Track>, done: (String) -> Unit) {
        io.launch {
            var ok = 0; var bad = 0
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            for (t in tracks) {
                val r = runCatching {
                    val name = nameOf(t)
                    val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, MediaFiles.mime(name.substringAfterLast('.', "")), name)
                        ?: error("no document")
                    try {
                        ctx.contentResolver.openOutputStream(doc)?.use { out -> ctx.contentResolver.openInputStream(Uri.parse(t.uri).let { u -> if (u.scheme == "file") Uri.fromFile(File(u.path!!)) else u })!!.use { it.copyTo(out, 256 * 1024) } }
                            ?: error("no stream")
                    } catch (e: Exception) { runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, doc) }; throw e }
                }
                if (r.isSuccess) ok++ else bad++
            }
            withContext(Dispatchers.Main) { done(MediaStorePolicy.exportSummary(ok, bad, "the folder")) }
        }
    }
}

/** Compose helper: one object a Library screen holds to export a selection (per item or bulk, used by wave B 3). */
class ExportActions(private val ctx: Context, private val pickFolder: (List<Track>) -> Unit) {
    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    fun run(tracks: List<Track>, target: MediaStorePolicy.Target) {
        when (target) {
            MediaStorePolicy.Target.SHARE -> runCatching { MediaExport.share(ctx, tracks) }.onFailure { toast("Couldn't share") }
            MediaStorePolicy.Target.FOLDER -> pickFolder(tracks)
            else -> if (StoragePermission.needsForExport(ctx)) {
                // 0.45.20 (item 15): asked when exporting to a shared folder on Android 9 and older, with the reason.
                StoragePermission.ask(ctx); toast("Allow storage so Blaze can copy to your shared folders, then export again")
            } else MediaExport.toShared(ctx, tracks, target) { toast(it) }
        }
    }
    /** Menu entries for a track menu: "Export to Music / Movies", "Export to Downloads", "Export to folder…", "Share…". */
    fun menu(tracks: () -> List<Track>, video: Boolean): List<Pair<String, () -> Unit>> = listOf(
        (if (video) "Export to Movies" else "Export to Music") to { run(tracks(), MediaStorePolicy.Target.DEFAULT) },
        "Export to Downloads" to { run(tracks(), MediaStorePolicy.Target.DOWNLOADS) },
        "Export to folder\u2026" to { run(tracks(), MediaStorePolicy.Target.FOLDER) },
        "Share\u2026" to { run(tracks(), MediaStorePolicy.Target.SHARE) },
    )
}

@Composable
fun rememberExportActions(): ExportActions {
    val ctx = LocalContext.current
    val pending = remember { arrayOfNulls<List<Track>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val list = pending[0]; pending[0] = null
        if (tree != null && list != null) MediaExport.toTree(ctx, tree, list) { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }
    return remember(ctx) { ExportActions(ctx) { list -> pending[0] = list; runCatching { launcher.launch(null) } } }
}
