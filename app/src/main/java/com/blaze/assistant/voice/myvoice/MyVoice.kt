package com.blaze.assistant.voice.myvoice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.blaze.assistant.agent.Settings
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume

/**
 * "My voice": Blaze speaks in the user's own voice, cloned on the phone by sherpa-onnx PocketTTS from a short
 * recording. Nothing leaves the phone: the model runs offline and the recording stays in app-private storage.
 *
 * Threads: one "myvoice" thread loads the model and makes speech piece by piece (never the main thread); one
 * "myvoice-play" thread plays the pieces through an AudioTrack while the next one is being made. Events go back to
 * [com.blaze.assistant.voice.Voice] on the main thread, so the voice loop / call screen behave exactly as with the
 * phone's TextToSpeech. When the phone can't keep up, or the model fails, the rest of the reply is handed to the
 * system voice and the user is told once, in plain words.
 */
object MyVoice {
    interface Events {
        fun started(id: String)
        fun done(id: String)
        /** Speak [text] with the system voice under the same utterance [id] (slow phone / model failure). */
        fun handoff(text: String, id: String)
    }

    @Volatile var events: Events? = null
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        app = ctx.applicationContext
        refreshPack()
    }

    // ---------------- files ----------------

    fun root() = File(app.filesDir, "myvoice")
    private fun runtimeDir() = File(root(), "runtime")
    fun runtimeFile() = File(runtimeDir(), "libsherpa-onnx-jni.so")
    private fun runtimeMark() = File(runtimeDir(), ".ok")
    fun modelDir() = File(root(), "model")
    private fun dlDir() = File(root(), "dl")
    fun voiceDir() = File(root(), "voice")
    /** The voice in use. */
    fun referenceFile() = File(voiceDir(), "reference.wav")
    /** A fresh recording that hasn't been confirmed with "Use this voice" yet. */
    fun candidateFile() = File(voiceDir(), "candidate.wav")

    fun runtimeSpec() = VoicePack.runtimeFor(android.os.Process.is64Bit())
    fun runtimeReady(): Boolean = runtimeFile().length() == runtimeSpec().bytes &&
        runtimeMark().let { it.exists() && it.readText().trim() == runtimeSpec().sha256 }

    @Volatile private var packOk = false
    fun packReady() = packOk
    fun voiceReady() = referenceFile().length() > 44
    /** A good recording from setup, saved but not confirmed with "Use this voice" yet. */
    fun candidateReady() = try { candidateFile().length() > 44 } catch (_: Exception) { false }

    private val _pack = MutableStateFlow<VoicePack.State>(VoicePack.State.Missing)
    val pack: StateFlow<VoicePack.State> = _pack

    fun refreshPack() {
        packOk = try { runtimeReady() && VoicePack.modelReady(modelDir()) } catch (_: Exception) { false }
        if (_pack.value !is VoicePack.State.Working) _pack.value = if (packOk) VoicePack.State.Ready else VoicePack.State.Missing
    }

    // ---------------- settings ----------------

    fun chosen() = Settings.str("blaze_voice", "system") == "mine"
    fun choose(mine: Boolean) {
        Settings.setStr("blaze_voice", if (mine) "mine" else "system")
        if (mine) { failedThisRun = false; Settings.setStr("myvoice_slow_at", "0") }
        else stop()
    }
    fun quality() = Settings.str("myvoice_quality", "auto")
    fun keepWhenSlow() = Settings.bool("myvoice_keep_slow", false)
    private fun slowAt() = Settings.str("myvoice_slow_at", "0").toLongOrNull() ?: 0L

    private val lowEnd: Boolean by lazy {
        val mi = android.app.ActivityManager.MemoryInfo()
        try { app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi) } catch (_: Exception) { }
        VoiceRoute.lowEnd(mi.totalMem, Runtime.getRuntime().availableProcessors())
    }
    fun numSteps() = VoiceRoute.numSteps(quality(), lowEnd)

    @Volatile var failedThisRun = false
        private set
    @Volatile var lastError: String = ""
        private set

    /** Which voice speaks right now, and why not mine. */
    fun route(): Pair<VoiceRoute.Route, VoiceRoute.Reason> =
        VoiceRoute.pick(chosen(), packOk, voiceReady(), failedThisRun, slowAt(), keepWhenSlow(), System.currentTimeMillis())

    /** Tell the user (once per reason, ever) why the phone voice is speaking instead. */
    fun tellOnce(reason: VoiceRoute.Reason) {
        val (text, stored) = VoiceRoute.tellOnce(reason, Settings.str("myvoice_told", ""))
        if (text == null) return
        Settings.setStr("myvoice_told", stored)
        main.post { try { android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) { } }
    }

    // ---------------- download ----------------

    @Volatile private var cancelDl = false
    fun cancelDownload() { cancelDl = true }

    private val opener = VoicePack.Opener { url, from ->
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Blaze-Android")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        val code = c.responseCode
        val body = if (code >= 400) (c.errorStream ?: ByteArrayInputStream(ByteArray(0))) else c.inputStream
        VoicePack.Response(code, c.contentLengthLong, c.getHeaderField("Content-Range"), body)
    }

    /** True when the phone is on mobile data (or another metered network): the screen recommends Wi-Fi. */
    fun onMeteredNetwork(): Boolean = try {
        app.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
    } catch (_: Exception) { false }

    /** Downloads (resuming) and unpacks the runtime + model. Safe to call again after a failure or cancel. */
    suspend fun download(): Result<Unit> = withContext(Dispatchers.IO) {
        cancelDl = false
        runCatching {
            val dl = dlDir().apply { mkdirs() }
            val rt = runtimeSpec()
            if (!runtimeReady()) {
                val gzBytes = VoicePack.runtimeDownloadBytes[rt.abi] ?: 0L
                val gz = File(dl, "runtime-${rt.abi}.so.gz")
                VoicePack.download(opener, rt.url, File(dl, gz.name + ".part"), gz, gzBytes, VoicePack.runtimeDownloadSha256[rt.abi], { cancelDl }) { d, t ->
                    _pack.value = VoicePack.State.Working(VoicePack.Step.RUNTIME, d, t)
                }
                runtimeDir().mkdirs()
                val tmp = File(runtimeDir(), "lib.tmp")
                GZIPInputStream(gz.inputStream().buffered()).use { inp -> tmp.outputStream().use { inp.copyTo(it, 64 * 1024) } }
                if (tmp.length() != rt.bytes || VoicePack.sha256(tmp) != rt.sha256) { tmp.delete(); gz.delete(); throw IOException("The voice engine download was damaged. Tap Download to try again.") }
                runtimeFile().delete()
                if (!tmp.renameTo(runtimeFile())) throw IOException("Couldn't save the voice engine")
                runtimeMark().writeText(rt.sha256)
                gz.delete()
            }
            if (!VoicePack.modelReady(modelDir())) {
                val archive = File(dl, "pocket-tts-int8.tar.bz2")
                val part = File(dl, archive.name + ".part")
                val have = if (archive.exists()) archive.length() else part.length()
                val free = try { android.os.StatFs(app.filesDir.absolutePath).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
                val need = VoicePack.spaceNeeded(have, true)
                if (free < need) throw IOException("Not enough space: free up about ${(need - free) / 1_000_000 + 1} MB and try again.")
                VoicePack.download(opener, VoicePack.MODEL_URL, part, archive, VoicePack.MODEL_BYTES, VoicePack.MODEL_SHA256, { cancelDl }) { d, t ->
                    _pack.value = VoicePack.State.Working(VoicePack.Step.MODEL, d, t)
                }
                _pack.value = VoicePack.State.Working(VoicePack.Step.UNPACK, 0, archive.length())
                val counting = VoicePack.Counting(archive.inputStream())
                val ticker = Thread {
                    try { while (true) { Thread.sleep(400); _pack.value = VoicePack.State.Working(VoicePack.Step.UNPACK, counting.count, archive.length()) } }
                    catch (_: InterruptedException) { }
                }.apply { isDaemon = true; start() }
                try {
                    org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(counting.buffered(256 * 1024), true).use { bz ->
                        VoicePack.unpackModel(bz.buffered(256 * 1024), modelDir(), { cancelDl })
                    }
                } finally { ticker.interrupt() }
                archive.delete()
            }
            packOk = true
            _pack.value = VoicePack.State.Ready
            Unit
        }.onFailure { e ->
            refreshPack()
            _pack.value = if (e is VoicePack.Cancelled) (if (packOk) VoicePack.State.Ready else VoicePack.State.Missing)
                else VoicePack.State.Failed(friendly(e))
        }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "No connection. Connect to Wi-Fi and tap Download to continue."
        is java.net.SocketTimeoutException -> "The connection dropped. Tap Download to continue where it stopped."
        is IOException -> e.message ?: "Download failed. Tap Download to continue."
        else -> "Download failed: ${e.message ?: e.javaClass.simpleName}"
    }

    /** Frees ~220 MB. My voice falls back to the system voice until it's downloaded again. */
    fun deletePack() {
        stop()
        exec.execute { releaseEngine() }
        modelDir().deleteRecursively(); File(root(), "model.tmp").deleteRecursively()
        dlDir().deleteRecursively()
        // The runtime .so may be mapped into this process already; deleting the file is still fine on Linux.
        runtimeDir().deleteRecursively()
        refreshPack()
        _pack.value = VoicePack.State.Missing
    }

    /** Deletes the recording(s). Blaze goes back to the system voice. */
    fun deleteVoice() {
        if (chosen()) choose(false) else stop()
        voiceDir().deleteRecursively()
        refCache = null
    }

    fun saveCandidate(p: AudioClip.Prepared) {
        voiceDir().mkdirs()
        candidateFile().writeBytes(AudioClip.wavBytes(p.samples, p.sampleRate))
    }

    /** "Use this voice": the tested recording becomes the voice, and Blaze starts speaking with it. */
    fun useCandidate(): Boolean {
        val c = candidateFile()
        if (!c.exists()) return voiceReady().also { if (it) choose(true) }
        referenceFile().delete()
        if (!c.renameTo(referenceFile())) return false
        refCache = null
        Settings.setStr("myvoice_told", "")
        choose(true)
        prewarm()
        return true
    }

    // ---------------- engine (on the "myvoice" thread) ----------------

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "myvoice").apply { isDaemon = true } }
    @Volatile private var tts: OfflineTts? = null
    private var libLoaded = false
    @Volatile var loading = false
        private set
    private var refCache: Triple<String, FloatArray, Int>? = null
    private var idle: ScheduledFuture<*>? = null
    private val pending = AtomicInteger(0)
    private val token = AtomicInteger(0)

    /** Load time of the model on this phone, ms (0 = not loaded yet). */
    @Volatile var loadMs = 0L
        private set

    private fun loadBlocking(): Boolean {
        tts?.let { return true }
        if (!packOk) { lastError = "Voice model isn't downloaded"; return false }
        loading = true
        val t0 = SystemClock.elapsedRealtime()
        return try {
            if (!libLoaded) { System.load(runtimeFile().absolutePath); libLoaded = true }
            val d = modelDir().absolutePath
            val cores = Runtime.getRuntime().availableProcessors()
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    pocket = OfflineTtsPocketModelConfig(
                        lmFlow = "$d/lm_flow.int8.onnx",
                        lmMain = "$d/lm_main.int8.onnx",
                        encoder = "$d/encoder.onnx",
                        decoder = "$d/decoder.int8.onnx",
                        textConditioner = "$d/text_conditioner.onnx",
                        vocabJson = "$d/vocab.json",
                        tokenScoresJson = "$d/token_scores.json",
                        voiceEmbeddingCacheCapacity = 4,
                    ),
                    numThreads = VoiceRoute.threads(cores),
                    debug = false,
                    provider = "cpu",
                ),
            )
            tts = OfflineTts(null, cfg)
            loadMs = SystemClock.elapsedRealtime() - t0
            true
        } catch (t: Throwable) {   // UnsatisfiedLinkError, OutOfMemoryError, bad model files
            failedThisRun = true
            lastError = t.message ?: t.javaClass.simpleName
            android.util.Log.w("MyVoice", "load failed", t)
            false
        } finally { loading = false }
    }

    private fun releaseEngine() {
        try { tts?.release() } catch (_: Throwable) { }
        tts = null
    }

    private fun reference(file: File): Pair<FloatArray, Int>? {
        val key = file.absolutePath + ":" + file.lastModified() + ":" + file.length()
        refCache?.let { if (it.first == key) return it.second to it.third }
        val r = try { AudioClip.readWav(file.readBytes()) } catch (_: Exception) { null } ?: return null
        refCache = Triple(key, r.first, r.second)
        return r
    }

    private fun genConfig(ref: Pair<FloatArray, Int>) = GenerationConfig(
        referenceAudio = ref.first,
        referenceSampleRate = ref.second,
        numSteps = numSteps(),
        // Same seed every time: the voice stays the same from one sentence to the next.
        extra = mapOf("seed" to "42", "max_reference_audio_len" to AudioClip.MAX_REFERENCE_SEC.toString()),
    )

    private fun scheduleIdle() {
        idle?.cancel(false)
        // Free the ~300 MB model when Blaze hasn't spoken for a while (low-RAM phones).
        idle = exec.schedule({ if (pending.get() == 0 && !busy()) releaseEngine() }, 4, TimeUnit.MINUTES)
    }

    /** Load the model (and the voice) ahead of a reply, e.g. when the mic opens. No-op when My voice isn't in use. */
    fun prewarm() {
        if (route().first != VoiceRoute.Route.MINE || tts != null || loading) return
        exec.execute {
            if (!loadBlocking()) return@execute
            val ref = reference(referenceFile()) ?: return@execute
            // The first call computes the voice embedding from the recording (cached afterwards).
            try { tts?.generateWithConfig("Okay.", genConfig(ref)) } catch (_: Throwable) { }
            scheduleIdle()
        }
    }

    /** Speak [text] in My voice as utterance [id]. [flush] stops whatever is playing first. */
    fun speak(text: String, id: String, flush: Boolean) {
        if (flush) stop()
        val tok = token.get()
        pending.incrementAndGet()
        exec.execute {
            try { runUtterance(text, id, tok) } finally { pending.decrementAndGet(); scheduleIdle() }
        }
    }

    private fun runUtterance(text: String, id: String, tok: Int) {
        if (tok != token.get()) return
        idle?.cancel(false)
        if (!loadBlocking()) { handoff(text, id, tok, VoiceRoute.Reason.FAILED); return }
        val ref = reference(referenceFile())
        if (ref == null) { failedThisRun = true; handoff(text, id, tok, VoiceRoute.Reason.FAILED); return }
        val pieces = SpeechChunker.chunks(text)
        if (pieces.isEmpty()) { queue.put(Item.Done(id, tok)); return }
        val watch = SpeedWatch(keepWhenSlow())
        for ((i, piece) in pieces.withIndex()) {
            if (tok != token.get()) return
            val t0 = SystemClock.elapsedRealtime()
            val audio = try { tts!!.generateWithConfig(piece, genConfig(ref)) } catch (t: Throwable) {
                failedThisRun = true; lastError = t.message ?: t.javaClass.simpleName
                handoff(pieces.drop(i).joinToString(" "), id, tok, VoiceRoute.Reason.FAILED); return
            }
            val makeMs = SystemClock.elapsedRealtime() - t0
            if (tok != token.get()) return
            if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
                handoff(pieces.drop(i).joinToString(" "), id, tok, VoiceRoute.Reason.FAILED); return
            }
            val audioMs = audio.samples.size * 1000L / audio.sampleRate
            val verdict = watch.onPiece(makeMs, audioMs)
            lastRtf = watch.lastRtf
            queue.put(Item.Pcm(audio.samples, audio.sampleRate, id, tok, last = i == pieces.lastIndex))
            if (verdict == SpeedWatch.Verdict.SLOW) {
                Settings.setStr("myvoice_slow_at", System.currentTimeMillis().toString())
                if (i < pieces.lastIndex) { handoff(pieces.drop(i + 1).joinToString(" "), id, tok, VoiceRoute.Reason.SLOW); return }
                tellOnce(VoiceRoute.Reason.SLOW)
            }
        }
    }

    /** Last measured speed: time to make speech / time it plays. */
    @Volatile var lastRtf = 0f
        private set

    private fun handoff(text: String, id: String, tok: Int, reason: VoiceRoute.Reason) {
        tellOnce(reason)
        queue.put(Item.Handoff(text, id, tok))
    }

    data class TestResult(val ok: Boolean, val rtf: Float, val makeMs: Long, val audioSec: Float, val loadMs: Long, val error: String = "")

    /** Setup: make [text] with the candidate (or current) recording, play it, and report how fast this phone is. */
    suspend fun test(text: String): TestResult = suspendCancellableCoroutine { cont ->
        exec.execute {
            val r = try {
                val wasLoaded = tts != null
                val t0 = SystemClock.elapsedRealtime()
                if (!loadBlocking()) TestResult(false, 0f, 0, 0f, 0, "The voice engine couldn't start on this phone ($lastError).")
                else {
                    val load = if (wasLoaded) 0L else SystemClock.elapsedRealtime() - t0
                    val file = if (candidateFile().exists()) candidateFile() else referenceFile()
                    val ref = reference(file)
                    if (ref == null) TestResult(false, 0f, 0, 0f, load, "Record your voice first.")
                    else {
                        // Warm-up (voice embedding) isn't counted in the speed: it happens once per recording.
                        try { tts!!.generateWithConfig("Okay.", genConfig(ref)) } catch (_: Throwable) { }
                        val g0 = SystemClock.elapsedRealtime()
                        val audio = tts!!.generateWithConfig(text, genConfig(ref))
                        val make = SystemClock.elapsedRealtime() - g0
                        val sec = audio.samples.size / audio.sampleRate.toFloat().coerceAtLeast(1f)
                        if (audio.samples.isEmpty()) TestResult(false, 0f, make, 0f, load, "The voice model made no sound.")
                        else {
                            stop()
                            queue.put(Item.Pcm(audio.samples, audio.sampleRate, "preview-test", token.get(), last = true))
                            TestResult(true, make / 1000f / sec, make, sec, load)
                        }
                    }
                }
            } catch (t: Throwable) {
                TestResult(false, 0f, 0, 0f, 0, "The voice model failed: ${t.message ?: t.javaClass.simpleName}")
            }
            scheduleIdle()
            if (cont.isActive) cont.resume(r)
        }
    }

    /** Play a clip (the raw recording playback in setup). */
    fun playClip(samples: FloatArray, sampleRate: Int) {
        stop()
        queue.put(Item.Pcm(samples, sampleRate, "preview-clip", token.get(), last = true))
    }

    // ---------------- playback (on the "myvoice-play" thread) ----------------

    private sealed class Item(val id: String, val tok: Int) {
        class Pcm(val samples: FloatArray, val rate: Int, id: String, tok: Int, val last: Boolean) : Item(id, tok)
        class Handoff(val text: String, id: String, tok: Int) : Item(id, tok)
        class Done(id: String, tok: Int) : Item(id, tok)
    }

    private val queue = LinkedBlockingQueue<Item>()
    private val trackLock = Object()
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var framesWritten = 0L
    @Volatile private var playing = false
    @Volatile private var usage = AudioAttributes.USAGE_ASSISTANT
    private var startedId: String? = null

    private val player = Thread({ playLoop() }, "myvoice-play").apply { isDaemon = true; start() }

    /** True while My voice is making or playing speech. */
    fun busy(): Boolean = pending.get() > 0 || queue.isNotEmpty() || playing

    /** Phone-call earpiece vs loudspeaker, like Voice.setEarpiece for the system voice. */
    fun setUsage(u: Int) {
        if (u == usage) return
        usage = u
        synchronized(trackLock) { try { track?.release() } catch (_: Exception) { }; track = null; framesWritten = 0 }
    }

    /** Stop now: drops queued speech, silences the speaker. Speech being made is thrown away when it finishes. */
    fun stop() {
        token.incrementAndGet()
        queue.clear()
        synchronized(trackLock) {
            try { track?.pause(); track?.flush() } catch (_: Exception) { }
            framesWritten = 0
        }
        startedId = null
    }

    private fun ensureTrack(rate: Int): AudioTrack {
        track?.let { if (trackRate == rate) return it; try { it.release() } catch (_: Exception) { } }
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, rate / 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t; trackRate = rate; framesWritten = 0
        return t
    }

    private fun playLoop() {
        while (true) {
            val item = try { queue.take() } catch (_: InterruptedException) { continue }
            if (item.tok != token.get()) continue
            playing = true
            try {
                when (item) {
                    is Item.Pcm -> {
                        if (startedId != item.id) { startedId = item.id; val id = item.id; main.post { events?.started(id) } }
                        val block = item.rate / 10
                        var off = 0
                        while (off < item.samples.size && item.tok == token.get()) {
                            synchronized(trackLock) {
                                if (item.tok != token.get()) return@synchronized
                                val t = ensureTrack(item.rate)
                                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                                val n = t.write(item.samples, off, minOf(block, item.samples.size - off), AudioTrack.WRITE_BLOCKING)
                                if (n <= 0) { off = item.samples.size } else { off += n; framesWritten += n }
                            }
                        }
                        if (item.last && item.tok == token.get()) {
                            if (queue.isEmpty()) drain(item.tok)
                            if (item.tok == token.get()) { startedId = null; val id = item.id; main.post { events?.done(id) } }
                        }
                    }
                    is Item.Handoff -> {
                        drain(item.tok)
                        if (item.tok == token.get()) { startedId = null; val (t, id) = item.text to item.id; main.post { events?.handoff(t, id) } }
                    }
                    is Item.Done -> { startedId = null; val id = item.id; main.post { events?.done(id) } }
                }
            } catch (t: Throwable) {
                android.util.Log.w("MyVoice", "playback", t)
                val id = item.id; main.post { events?.done(id) }
            } finally { playing = false }
        }
    }

    /** Wait until what's been written has actually played (so "done" means the speaker went quiet). */
    private fun drain(tok: Int) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (tok == token.get() && SystemClock.elapsedRealtime() < deadline) {
            val (head, written) = synchronized(trackLock) { ((track?.playbackHeadPosition ?: 0).toLong() and 0xffffffffL) to framesWritten }
            if (track == null || head >= written) break
            Thread.sleep(20)
        }
        synchronized(trackLock) { if (tok == token.get()) { try { track?.pause() } catch (_: Exception) { } } }
    }
}
