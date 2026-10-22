package com.blaze.assistant.life

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * v0.33.0 Life Log 2: "record, then transcribe". While logs run, only the stretches with voice-level sound are written
 * (16 kHz mono 16-bit PCM, with a short pre-roll and hangover) to one file per session, plus an index of segments
 * (when each started on the clock, where it sits in the file, how loud the room was). Silence costs nothing.
 *
 * After Stop, [LifeLogWorker] transcribes the file with Vosk, tells voices apart with Vosk's speaker model
 * ([Diarize]), and Blaze and Halo review it into memories ([LifeReview]). The audio is deleted after the review
 * unless "Keep recordings" is on.
 *
 * Layout: filesDir/lifelog/<id>/audio.pcm, index.json, state.json ("recording" | "queued" | "done" | "failed").
 */
object LifeAudio {
    const val RATE = 16000
    const val BYTES_PER_SEC = RATE * 2

    data class Segment(val startMs: Long, val offset: Long, val bytes: Long, val rms: Int, val floor: Int)

    fun root(ctx: Context) = File(ctx.filesDir, "lifelog").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id)

    fun sessions(ctx: Context): List<File> = root(ctx).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    fun state(d: File): String = try { JSONObject(File(d, "state.json").readText()).optString("state") } catch (_: Exception) { "" }
    fun setState(d: File, s: String, extra: JSONObject = JSONObject()) {
        try { File(d, "state.json").writeText(extra.put("state", s).put("at", System.currentTimeMillis()).toString()) } catch (_: Exception) { }
    }

    fun readIndex(d: File): JSONObject = try { JSONObject(File(d, "index.json").readText()) } catch (_: Exception) { JSONObject() }

    fun segments(idx: JSONObject): List<Segment> {
        val a = idx.optJSONArray("segments") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let {
            Segment(it.optLong("t"), it.optLong("o"), it.optLong("n"), it.optInt("rms"), it.optInt("floor")) } }
    }

    /** Space the recordings take (all sessions). */
    fun usedBytes(ctx: Context): Long = sessions(ctx).sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }

    /** Writes one session from the recorder thread: [open] when the gate opens, [write] audio, [close] when it shuts. */
    class Writer(val dirFile: File, val startedAt: Long, val brainstorm: Boolean) {
        private val out = FileOutputStream(File(dirFile.apply { mkdirs() }, "audio.pcm"), true)
        private val segs = JSONArray()
        private var cur: JSONObject? = null
        private var offset = File(dirFile, "audio.pcm").length()
        private var sumSq = 0.0
        private var samples = 0L
        private var floor = 0

        init { setState(dirFile, "recording"); saveIndex() }

        @Synchronized fun open(startMs: Long, roomFloor: Int) {
            if (cur != null) return
            cur = JSONObject().put("t", startMs).put("o", offset).put("n", 0L)
            sumSq = 0.0; samples = 0; floor = roomFloor
        }

        @Synchronized fun write(buf: ShortArray, n: Int) {
            val c = cur ?: return
            val b = ByteArray(n * 2)
            for (i in 0 until n) { val v = buf[i].toInt(); b[2 * i] = (v and 0xff).toByte(); b[2 * i + 1] = ((v shr 8) and 0xff).toByte(); sumSq += v.toDouble() * v }
            out.write(b); offset += b.size; samples += n
            c.put("n", c.optLong("n") + b.size)
        }

        @Synchronized fun close() {
            val c = cur ?: return
            cur = null
            if (c.optLong("n") < BYTES_PER_SEC / 2) return        // under half a second: noise, not indexed
            c.put("rms", if (samples > 0) kotlin.math.sqrt(sumSq / samples).toInt() else 0).put("floor", floor)
            segs.put(c)
            saveIndex()
        }

        private fun saveIndex() {
            try {
                File(dirFile, "index.json").writeText(JSONObject().put("started", startedAt).put("brainstorm", brainstorm)
                    .put("segments", segs).toString())
            } catch (_: Exception) { }
        }

        @Synchronized fun finish(): Int {
            close()
            try { out.flush(); out.close() } catch (_: Exception) { }
            saveIndex()
            return segs.length()
        }
    }

    /** Reads [bytes] of PCM at [offset] as shorts. */
    fun read(d: File, offset: Long, bytes: Long): ShortArray {
        RandomAccessFile(File(d, "audio.pcm"), "r").use { f ->
            val len = minOf(bytes, (f.length() - offset).coerceAtLeast(0)).toInt()
            val b = ByteArray(len); f.seek(offset); f.readFully(b)
            return ShortArray(len / 2) { i -> ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort() }
        }
    }

    /** "quiet" / "some background noise" / "noisy" from a segment's room noise floor. Pure. */
    fun roomLabel(floor: Int): String = when { floor < 300 -> "quiet"; floor < 900 -> "some background noise"; else -> "noisy" }
}
