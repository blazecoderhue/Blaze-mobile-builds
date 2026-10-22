package com.blaze.assistant.agent

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * v0.45.9: the phone side of [SaveFile]. API 29+: MediaStore.Downloads into Download/Blaze (no permission needed; Android
 * renames on a clash, so the name read back is the one reported). Below 29, or if MediaStore refuses: the file goes to
 * Blaze's cache and the share / save sheet opens (the user picks Files / Drive), and the result says so.
 */
object SaveFileAndroid {
    const val FOLDER = "Blaze"

    fun save(ctx: Context, p: SaveFile.Plan): String {
        if (Build.VERSION.SDK_INT >= 29) try {
            val rel = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, p.name)
                put(MediaStore.MediaColumns.MIME_TYPE, p.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("MediaStore refused the file")
            try {
                cr.openOutputStream(uri)?.use { it.write(p.content.toByteArray(Charsets.UTF_8)) } ?: throw IllegalStateException("no output stream")
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { runCatching { cr.delete(uri, null, null) }; throw e }
            val shown = cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: p.name
            return SaveFile.savedLine("Downloads/$FOLDER/$shown")
        } catch (e: Exception) {
            com.blaze.assistant.device.CrashLog.quiet("SaveFile: MediaStore", e)
        }
        return try {
            val dir = File(ctx.cacheDir, "save").apply { mkdirs() }
            val f = File(dir, p.name).apply { writeText(p.content) }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(p.mime).putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, p.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Save ${p.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened the save / share sheet for ${p.name} (this Android version can't write to Downloads directly). Tell the user to pick Files or Drive there to keep it."
        } catch (e: Exception) { "Couldn't save ${p.name}: ${e.message}. Tell the user plainly; don't suggest a computer." }
    }
}
