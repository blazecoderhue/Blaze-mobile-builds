package com.blaze.assistant.mediaapps

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer

/**
 * v0.30.1: where Blaze Music and Blaze Video keep what they download. Shared, app-visible folders registered with
 * MediaStore so the phone's own apps and Blaze's Gallery see them:
 *
 *   songs  → Music/Blaze/Music   (MediaStore.Audio)
 *   videos → Movies/Blaze/Videos (MediaStore.Video)
 *
 * Android only allows audio under Music/ (and a few other audio roots) and video under Movies/ / DCIM/ / Pictures/, so
 * "Blaze/Music" and "Blaze/Videos" sit inside those. The Gallery reads the same two folders (keep [MUSIC_DIR] /
 * [VIDEO_DIR] in sync with it). Android 8–9: the same paths on shared storage (needs the storage permission; without it
 * the files go to Blaze's own folder on shared storage and are still scanned into MediaStore).
 */
object MediaFiles {
    val MUSIC_DIR = Environment.DIRECTORY_MUSIC + "/Blaze/Music"
    val VIDEO_DIR = Environment.DIRECTORY_MOVIES + "/Blaze/Videos"

    fun relDir(kind: String) = if (kind == "video") VIDEO_DIR else MUSIC_DIR

    fun mime(ext: String): String = when (ext.lowercase()) {
        "m4a" -> "audio/mp4"; "opus", "ogg" -> "audio/ogg"; "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"
        "webm" -> "video/webm"; "mp4" -> "video/mp4"; else -> "application/octet-stream"
    }

    /** Working files (partial downloads, merges): Blaze's own storage, removed when a download finishes or is cancelled. */
    fun workDir(ctx: Context): File = File(ctx.filesDir, "mediaapps/dl").apply { mkdirs() }
    fun artDir(ctx: Context): File = File(ctx.filesDir, "mediaapps/art").apply { mkdirs() }

    /** Free bytes where downloads are written (internal storage; MediaStore lives on the same volume on most phones). */
    fun freeBytes(ctx: Context): Long = try { StatFs(ctx.filesDir.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }

    /**
     * Copies [src] into the shared folder for [kind] and registers it. Returns the content:// (Android 10+) or file://
     * uri the library and the Gallery use.
     */
    fun publish(ctx: Context, src: File, kind: String, name: String, title: String, artist: String, durationMs: Long): Uri {
        val ext = src.extension.ifBlank { name.substringAfterLast('.', "") }
        val mime = mime(ext)
        if (Build.VERSION.SDK_INT >= 29) {
            val coll = if (kind == "video") MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relDir(kind))
                put(MediaStore.MediaColumns.TITLE, title)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (kind != "video") { put(MediaStore.Audio.Media.ARTIST, artist); put(MediaStore.Audio.Media.IS_MUSIC, 1) }
                if (durationMs > 0) put(MediaStore.MediaColumns.DURATION, durationMs)
            }
            val uri = ctx.contentResolver.insert(coll, v) ?: throw IllegalStateException("The phone wouldn't save the file")
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
                    ?: throw IllegalStateException("The phone wouldn't save the file")
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                throw e
            }
            return uri
        }
        // Android 8–9: plain files on shared storage, then a media scan.
        @Suppress("DEPRECATION")
        val pub = File(Environment.getExternalStorageDirectory(), relDir(kind))
        val dir = if (runCatching { pub.mkdirs(); pub.isDirectory && pub.canWrite() }.getOrDefault(false)) pub
            else File(ctx.getExternalFilesDir(if (kind == "video") Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC), "Blaze").apply { mkdirs() }
        var out = File(dir, name)
        var n = 2
        while (out.exists()) out = File(dir, name.substringBeforeLast('.') + " ($n)." + ext).also { n++ }
        src.copyTo(out)
        MediaScannerConnection.scanFile(ctx, arrayOf(out.path), arrayOf(mime), null)
        return Uri.fromFile(out)
    }

    /** True when a library uri still points at something (the user may have deleted it in another app). */
    fun exists(ctx: Context, uri: String): Boolean = try {
        val u = Uri.parse(uri)
        if (u.scheme == "file") File(u.path ?: "").isFile
        else ctx.contentResolver.query(u, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    } catch (_: SecurityException) { true } catch (_: Exception) { false }

    fun delete(ctx: Context, uri: String): Boolean = try {
        val u = Uri.parse(uri)
        if (u.scheme == "file") File(u.path ?: "").delete() else ctx.contentResolver.delete(u, null, null) > 0
    } catch (_: Exception) { false }
}

/**
 * v0.30.1: on-phone remuxing with Android's own MediaExtractor + MediaMuxer (no ffmpeg in the APK).
 *  - [merge]: YouTube's separate video-only and audio-only streams → one .mp4 (H.264 + AAC) or .webm (VP9 + Opus).
 *  - [remux]: a downloaded DASH audio stream → a plain .m4a, or Opus → .opus (Ogg, Android 10+).
 * Nothing is re-encoded, so it's quick and lossless even on a low-end phone.
 */
object Mux {
    private fun outFormat(ext: String): Int = when (ext) {
        "webm" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
        "opus", "ogg" -> if (Build.VERSION.SDK_INT >= 29) MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG else throw IllegalStateException("Ogg needs Android 10")
        else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    }

    private class Src(val ex: MediaExtractor, val track: Int, var muxTrack: Int = -1, var done: Boolean = false)

    private fun open(f: File, want: String): Src? {
        val ex = MediaExtractor()
        ex.setDataSource(f.path)
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (m.startsWith(want)) { ex.selectTrack(i); return Src(ex, i) }
        }
        ex.release(); return null
    }

    fun merge(video: File, audio: File, out: File, ext: String) = run(listOfNotNull(open(video, "video/"), open(audio, "audio/")), out, ext, 2)

    fun remux(audio: File, out: File, ext: String) = run(listOfNotNull(open(audio, "audio/")), out, ext, 1)

    private fun run(srcs: List<Src>, out: File, ext: String, want: Int) {
        try {
            if (srcs.size != want) throw IllegalStateException("A stream was missing its audio or video track")
            out.delete()
            val mux = MediaMuxer(out.path, outFormat(ext))
            try {
                srcs.forEach { it.muxTrack = mux.addTrack(it.ex.getTrackFormat(it.track)) }
                mux.start()
                val buf = ByteBuffer.allocate(2 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                // Interleave by timestamp so the file plays smoothly from any point.
                while (true) {
                    val s = srcs.filter { !it.done }.minByOrNull { it.ex.sampleTime.let { t -> if (t < 0) Long.MAX_VALUE else t } } ?: break
                    val size = s.ex.readSampleData(buf, 0)
                    if (size < 0) { s.done = true; continue }
                    info.offset = 0; info.size = size; info.presentationTimeUs = s.ex.sampleTime
                    info.flags = if ((s.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    mux.writeSampleData(s.muxTrack, buf, info)
                    s.ex.advance()
                }
                mux.stop()
            } finally { try { mux.release() } catch (_: Exception) { } }
        } catch (e: Exception) {
            out.delete(); throw e
        } finally { srcs.forEach { try { it.ex.release() } catch (_: Exception) { } } }
    }
}
