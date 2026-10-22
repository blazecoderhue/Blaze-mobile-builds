package com.blaze.assistant.voice.myvoice

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * What My voice downloads (nothing is in the APK) and the pure logic around it: resumable downloads with a
 * size + SHA-256 check, a tiny tar reader, and the state the setup screen shows. Tested on the JVM (VoicePackTest).
 *
 *  - Runtime: libsherpa-onnx-jni.so (sherpa-onnx 1.13.8, onnxruntime linked in) for this phone's ABI, gzipped,
 *    from the blaze-releases package registry (generic package voice-runtime/1.13.8, copied unchanged from the
 *    official sherpa-onnx AAR, see tools/voice-runtime.sh). ~9 MB download. Both the .gz and the unpacked .so are
 *    SHA-256 checked.
 *  - Model: sherpa-onnx PocketTTS int8 (Kyutai Pocket TTS, zero-shot voice cloning), the official k2-fsa
 *    tar.bz2. ~98 MB download, ~198 MB unpacked.
 */
object VoicePack {
    const val RUNTIME_VERSION = "1.13.8"

    data class Runtime(val abi: String, val url: String, val sha256: String, val bytes: Long)

    val runtimes = listOf(
        Runtime("arm64-v8a",
            "https://gitlab.com/api/v4/projects/87867230/packages/generic/voice-runtime/1.13.8/libsherpa-onnx-jni-1.13.8-arm64-v8a.so.gz",
            "b4a53d915c19cb6d09b1cdc9a8084be54419b647aab9097ffdfb46f30c3c4dd8", 24_169_352L),
        Runtime("armeabi-v7a",
            "https://gitlab.com/api/v4/projects/87867230/packages/generic/voice-runtime/1.13.8/libsherpa-onnx-jni-1.13.8-armeabi-v7a.so.gz",
            "2c0350f9c23dda60318c0d195fcc02d505f05cdbfba89492a4468d2c4276be28", 16_437_284L),
    )
    /** Download sizes of the gz files (for progress before the server answers). */
    val runtimeDownloadBytes = mapOf("arm64-v8a" to 8_969_255L, "armeabi-v7a" to 8_078_888L)
    /** SHA-256 of the gz files as uploaded (checked before unpacking). */
    val runtimeDownloadSha256 = mapOf(
        "arm64-v8a" to "ff3d87223bbe0709247194645aa689071a120675102c8c0401aec8d057004882",
        "armeabi-v7a" to "fdef93cbade76bca0aeaa75f4cd72179fb323c96b54d101a38e8f3af43895ea1")

    const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2"
    const val MODEL_SHA256 = "2f3b88823cbbb9bf0b2477ec8ae7b3fec417b3a87b6bb5f256dba66f2ad967cb"
    const val MODEL_BYTES = 98_336_520L
    const val MODEL_DIR_IN_TAR = "sherpa-onnx-pocket-tts-int8-2026-01-26/"

    /** Files kept from the archive, with their exact sizes (checked after unpacking). */
    val modelFiles = linkedMapOf(
        "lm_flow.int8.onnx" to 9_962_530L,
        "lm_main.int8.onnx" to 76_341_079L,
        "encoder.onnx" to 72_713_165L,
        "decoder.int8.onnx" to 22_693_618L,
        "text_conditioner.onnx" to 16_388_343L,
        "vocab.json" to 69_478L,
        "token_scores.json" to 123_616L,
        "LICENSE" to 18_655L,
    )
    val unpackedBytes: Long get() = modelFiles.values.sum()

    /** Free space needed to finish from scratch: archive + unpacked model + runtime + headroom. */
    fun spaceNeeded(haveArchiveBytes: Long = 0, runtimeReady: Boolean = false): Long =
        (MODEL_BYTES - haveArchiveBytes).coerceAtLeast(0) + unpackedBytes + (if (runtimeReady) 0 else 26_000_000L) + 20_000_000L

    /** Total download in MB, as the setup screen states it. */
    fun downloadMb(abi: String): Int = ((MODEL_BYTES + (runtimeDownloadBytes[abi] ?: 9_000_000L)) / 1_000_000L).toInt()

    fun runtimeFor(is64Bit: Boolean): Runtime = runtimes.first { it.abi == if (is64Bit) "arm64-v8a" else "armeabi-v7a" }

    // ---------------- setup state ----------------

    enum class Step { RUNTIME, MODEL, UNPACK }

    sealed class State {
        object Missing : State()
        data class Working(val step: Step, val done: Long, val total: Long) : State()
        object Ready : State()
        data class Failed(val message: String) : State()
    }

    /** One progress bar for the whole pack: runtime 8%, model download 77%, unpacking 15%. */
    fun percent(s: State): Int = when (s) {
        is State.Ready -> 100
        is State.Working -> {
            val f = if (s.total > 0) (s.done.toDouble() / s.total).coerceIn(0.0, 1.0) else 0.0
            when (s.step) {
                Step.RUNTIME -> (f * 8).toInt()
                Step.MODEL -> 8 + (f * 77).toInt()
                Step.UNPACK -> 85 + (f * 15).toInt()
            }
        }
        else -> 0
    }

    fun label(s: State): String = when (s) {
        is State.Missing -> "Not downloaded"
        is State.Ready -> "Downloaded"
        is State.Failed -> s.message
        is State.Working -> when (s.step) {
            Step.RUNTIME -> "Downloading voice engine… ${percent(s)}%"
            Step.MODEL -> "Downloading voice model… ${percent(s)}% (${s.done / 1_000_000} of ${s.total / 1_000_000} MB)"
            Step.UNPACK -> "Unpacking… ${percent(s)}%"
        }
    }

    // ---------------- resumable download ----------------

    /** What an HTTP response means for a download that asked for bytes from [from]. */
    enum class Resume { APPEND, RESTART, ALREADY_COMPLETE, FAIL }

    fun onResponse(code: Int, from: Long, contentRange: String?): Resume = when {
        code == 206 && from > 0 && contentRangeStart(contentRange) == from -> Resume.APPEND
        code == 206 && from == 0L -> Resume.RESTART
        code == 200 -> Resume.RESTART          // server ignored Range: start over
        code == 416 && from > 0 -> Resume.ALREADY_COMPLETE
        else -> Resume.FAIL
    }

    /** "bytes 100-999/1000" -> 100 */
    fun contentRangeStart(v: String?): Long? = v?.let { Regex("""bytes\s+(\d+)-\d+/(\d+|\*)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** "bytes 100-999/1000" -> 1000 */
    fun contentRangeTotal(v: String?): Long? = v?.let { Regex("""bytes\s+\d+-\d+/(\d+)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    class Response(val code: Int, val contentLength: Long, val contentRange: String?, val body: InputStream) : AutoCloseable {
        override fun close() { try { body.close() } catch (_: Exception) { } }
    }

    /** Opens [url] asking for bytes from [from] (no Range header when 0). Android uses HttpURLConnection; tests fake it. */
    fun interface Opener { fun open(url: String, from: Long): Response }

    class Cancelled : IOException("Cancelled")

    /**
     * Downloads [url] into [part], resuming what's already there, then checks [expectedBytes] and [sha256].
     * The finished, verified file is renamed to [dest]. A bad file is deleted so the next try starts clean.
     */
    fun download(
        opener: Opener, url: String, part: File, dest: File, expectedBytes: Long, sha256: String?,
        cancelled: () -> Boolean = { false }, progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        if (dest.exists() && dest.length() == expectedBytes && (sha256 == null || sha256(dest) == sha256)) return
        part.parentFile?.mkdirs()
        var from = if (part.exists()) part.length() else 0L
        if (expectedBytes > 0 && from > expectedBytes) { part.delete(); from = 0 }
        if (!(expectedBytes > 0 && from == expectedBytes)) {
            opener.open(url, from).use { r ->
                var append = when (onResponse(r.code, from, r.contentRange)) {
                    Resume.APPEND -> true
                    Resume.RESTART -> false
                    Resume.ALREADY_COMPLETE -> null
                    Resume.FAIL -> throw IOException("Server said ${r.code}")
                }
                if (append != null) {
                    if (!append) from = 0
                    val total = if (expectedBytes > 0) expectedBytes else from + r.contentLength
                    java.io.FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = from
                        var lastTick = 0L
                        while (true) {
                            if (cancelled()) throw Cancelled()
                            val n = r.body.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (done - lastTick >= 256 * 1024) { lastTick = done; progress(done, total) }
                        }
                        progress(done, total)
                    }
                }
            }
        }
        if (expectedBytes > 0 && part.length() != expectedBytes) {
            if (part.length() > expectedBytes) part.delete()
            throw IOException("Download stopped early (${part.length()} of $expectedBytes bytes). Tap Download to continue.")
        }
        if (sha256 != null && sha256(part) != sha256) { part.delete(); throw IOException("The download was damaged. Tap Download to try again.") }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Couldn't save the download")
    }

    fun sha256(f: File): String = f.inputStream().use { sha256(it) }

    fun sha256(inp: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------- unpack ----------------

    /** Counts bytes read, for unpack progress over the compressed archive. */
    class Counting(inp: InputStream) : FilterInputStream(inp) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    /**
     * Unpacks the wanted model files from a tar stream into [dir] (via a temp dir, so a half-unpacked model is
     * never mistaken for a ready one), checking each file's size. Writes [READY_MARK] last.
     */
    fun unpackModel(tar: InputStream, dir: File, cancelled: () -> Boolean = { false }, files: Map<String, Long> = modelFiles) {
        val tmp = File(dir.parentFile, dir.name + ".tmp")
        tmp.deleteRecursively(); tmp.mkdirs()
        val seen = HashSet<String>()
        TarReader(tar).forEach { name, size, body ->
            if (cancelled()) throw Cancelled()
            val rel = name.removePrefix("./").removePrefix(MODEL_DIR_IN_TAR)
            val want = files[rel]
            if (want != null) {
                if (size != want) throw IOException("Unexpected model file size for $rel")
                File(tmp, rel).outputStream().use { body.copyTo(it, 64 * 1024) }
                if (File(tmp, rel).length() != want) throw IOException("Model file $rel is incomplete")
                seen += rel
            }
        }
        val missing = files.keys - seen
        if (missing.isNotEmpty()) throw IOException("Model archive is missing ${missing.joinToString()}")
        File(tmp, READY_MARK).writeText("pocket-tts-int8-2026-01-26\n")
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IOException("Couldn't save the model")
    }

    const val READY_MARK = ".ready"

    fun modelReady(dir: File, files: Map<String, Long> = modelFiles): Boolean =
        File(dir, READY_MARK).exists() && files.all { (n, s) -> File(dir, n).length() == s }
}

/** Minimal ustar/GNU tar reader: regular files only, with GNU long names. Enough for the model archive. */
class TarReader(private val inp: InputStream) {
    fun forEach(onFile: (name: String, size: Long, body: InputStream) -> Unit) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (!readFully(header)) return
            if (header.all { it == 0.toByte() }) return   // end of archive
            val name0 = cstr(header, 0, 100)
            val prefix = cstr(header, 345, 155)
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val name = longName ?: if (prefix.isNotEmpty() && String(header, 257, 5, Charsets.US_ASCII) == "ustar") "$prefix/$name0" else name0
            longName = null
            val body = Bounded(inp, size)
            when (type) {
                'L' -> longName = String(body.readBytes(), Charsets.UTF_8).trimEnd('\u0000')
                '0', '\u0000', '7' -> onFile(name, size, body)
                else -> {}
            }
            body.skipRest()
            val pad = (512 - size % 512) % 512
            skipFully(pad)
        }
    }

    private fun readFully(b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val n = inp.read(b, off, b.size - off)
            if (n < 0) { if (off == 0) return false else throw IOException("Truncated tar header") }
            off += n
        }
        return true
    }

    private fun skipFully(n: Long) {
        var left = n
        val buf = ByteArray(512)
        while (left > 0) {
            val r = inp.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw IOException("Truncated tar")
            left -= r
        }
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        // GNU base-256 for huge files
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
        val s = cstr(b, off, len).trim().trim('\u0000')
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    /** A view of the next [size] bytes. */
    private class Bounded(private val src: InputStream, size: Long) : InputStream() {
        private var left = size
        override fun read(): Int {
            if (left <= 0) return -1
            val v = src.read()
            if (v < 0) throw IOException("Truncated tar entry")
            left--; return v
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw IOException("Truncated tar entry")
            left -= n; return n
        }
        fun skipRest() {
            val buf = ByteArray(8192)
            while (left > 0) { if (read(buf, 0, buf.size) < 0) break }
        }
        override fun close() {}
    }
}
