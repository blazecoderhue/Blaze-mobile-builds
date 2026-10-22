package com.blaze.assistant.voice.gemini

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.blaze.assistant.agent.KeyRing
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.voice.myvoice.SpeechChunker
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * v0.41.0 "Gemini voice": Blaze's replies spoken by Gemini text-to-speech with the user's own Gemini API key (the
 * Gemini key in Blaze's keys, which also arrives through the Blaze account). The reply is cut into sentences
 * (SpeechChunker); each is made on the "gemini-tts" thread while the one before plays on "gemini-play", so speech
 * starts after the first short sentence. Any failure (no key, no connection, used up) hands the rest of the reply to
 * the phone's voice under the same utterance id, and the user is told once, in plain words.
 */
object GeminiVoice {
    interface Events {
        fun started(id: String)
        fun done(id: String)
        fun handoff(text: String, id: String)
        fun tell(text: String)
    }

    @Volatile var events: Events? = null
    private val main = Handler(Looper.getMainLooper())

    fun chosen() = Settings.str("blaze_voice", "system") == "gemini"
    fun choose() = Settings.setStr("blaze_voice", "gemini")
    fun voice() = GeminiTts.voiceOrDefault(Settings.str("gemini_voice", GeminiTts.DEFAULT_VOICE))
    fun setVoice(v: String) = Settings.setStr("gemini_voice", GeminiTts.voiceOrDefault(v))

    fun key(): String = try { KeyRing.all("gemini").firstOrNull().orEmpty() } catch (_: Throwable) { "" }
    fun hasKey() = key().isNotBlank()

    /** Saves a pasted key as the Gemini key (the same one Blaze's other Gemini features use). */
    fun saveKey(k: String): Boolean = try { KeyRing.add("gemini", k.trim()) || KeyRing.all("gemini").contains(k.trim()) } catch (_: Throwable) { false }

    @Volatile var lastError = ""
        private set
    @Volatile private var failedThisRun = false
    /** Gemini speaks right now: chosen, a key, and no failure this run. */
    fun ready() = chosen() && hasKey() && !failedThisRun

    /** Settings "Hear it" and a manual retry clear the failure. */
    fun resetFailure() { failedThisRun = false; lastError = "" }

    data class Made(val samples: FloatArray, val rate: Int)

    /** One request; returns the audio or a plain error. Blocking: never on the main thread. */
    fun make(text: String, voice: String = voice()): Result<Made> {
        val k = key()
        if (k.isBlank()) return Result.failure(IllegalStateException(GeminiTts.NO_KEY))
        var last = "Gemini voice didn't work this time."
        for (model in GeminiTts.MODELS) {
            val (code, body) = try { post(k, GeminiTts.requestBody(text, voice, model).toString()) } catch (e: Exception) { 0 to (e.message ?: "") }
            if (code in 200..299) {
                val a = GeminiTts.audio(body) ?: return Result.failure(IllegalStateException("Gemini sent no sound."))
                return Result.success(Made(a.first, a.second))
            }
            last = GeminiTts.friendlyError(code, body)
            if (!GeminiTts.tryNextModel(code, body)) break
        }
        return Result.failure(IllegalStateException(last))
    }

    private fun post(key: String, json: String): Pair<Int, String> {
        val c = URL(GeminiTts.URL).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to body
    }

    // ---------------- speaking ----------------

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "gemini-tts").apply { isDaemon = true } }
    private val token = AtomicInteger(0)
    private val pending = AtomicInteger(0)

    private sealed class Item(val id: String, val tok: Int) {
        class Pcm(val samples: FloatArray, val rate: Int, id: String, tok: Int, val last: Boolean) : Item(id, tok)
        class Handoff(val text: String, id: String, tok: Int) : Item(id, tok)
    }
    private val queue = LinkedBlockingQueue<Item>()
    @Volatile private var playing = false
    @Volatile private var usage = AudioAttributes.USAGE_ASSISTANT
    private val trackLock = Object()
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var framesWritten = 0L
    private var startedId: String? = null
    private val player = Thread({ playLoop() }, "gemini-play").apply { isDaemon = true; start() }

    fun busy() = pending.get() > 0 || queue.isNotEmpty() || playing

    fun speak(text: String, id: String, flush: Boolean) {
        if (flush) stop()
        val tok = token.get()
        pending.incrementAndGet()
        exec.execute {
            try {
                val pieces = SpeechChunker.chunks(text)
                if (pieces.isEmpty()) { queue.put(Item.Pcm(FloatArray(0), GeminiTts.SAMPLE_RATE, id, tok, last = true)); return@execute }
                for ((i, p) in pieces.withIndex()) {
                    if (tok != token.get()) return@execute
                    val r = make(p)
                    if (tok != token.get()) return@execute
                    val m = r.getOrNull()
                    if (m == null) {
                        failedThisRun = true
                        lastError = r.exceptionOrNull()?.message.orEmpty()
                        events?.let { e -> main.post { e.tell("$lastError Using the phone voice for now.") } }
                        queue.put(Item.Handoff(pieces.drop(i).joinToString(" "), id, tok)); return@execute
                    }
                    queue.put(Item.Pcm(m.samples, m.rate, id, tok, last = i == pieces.lastIndex))
                }
            } finally { pending.decrementAndGet() }
        }
    }

    /** Settings "Hear it": makes and plays one sentence; returns a plain error, or null when it played. */
    fun preview(text: String, voice: String, onDone: (String?) -> Unit) {
        stop()
        val tok = token.get()
        exec.execute {
            val r = make(text, voice)
            val m = r.getOrNull()
            if (m != null && tok == token.get()) { failedThisRun = false; queue.put(Item.Pcm(m.samples, m.rate, "preview-gemini", tok, last = true)) }
            val err = r.exceptionOrNull()?.message
            main.post { onDone(err) }
        }
    }

    fun setUsage(u: Int) {
        if (u == usage) return
        usage = u
        synchronized(trackLock) { try { track?.release() } catch (_: Exception) { }; track = null }
    }

    fun stop() {
        token.incrementAndGet()
        queue.clear()
        synchronized(trackLock) { try { track?.pause(); track?.flush() } catch (_: Exception) { }; framesWritten = 0 }
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

    private fun drain(tok: Int) {
        val deadline = System.currentTimeMillis() + 30_000
        while (tok == token.get() && System.currentTimeMillis() < deadline) {
            val (head, written) = synchronized(trackLock) { ((track?.playbackHeadPosition ?: 0).toLong() and 0xffffffffL) to framesWritten }
            if (track == null || head >= written) break
            Thread.sleep(20)
        }
        synchronized(trackLock) { if (tok == token.get()) { try { track?.pause() } catch (_: Exception) { } } }
    }

    private fun playLoop() {
        while (true) {
            val item = try { queue.take() } catch (_: InterruptedException) { continue }
            if (item.tok != token.get()) continue
            playing = true
            try {
                when (item) {
                    is Item.Pcm -> {
                        if (startedId != item.id && item.samples.isNotEmpty()) { startedId = item.id; val id = item.id; main.post { events?.started(id) } }
                        var off = 0
                        var written = 0L
                        while (off < item.samples.size && item.tok == token.get()) {
                            synchronized(trackLock) {
                                if (item.tok != token.get()) return@synchronized
                                val t = ensureTrack(item.rate)
                                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                                val n = t.write(item.samples, off, minOf(item.rate / 10, item.samples.size - off), AudioTrack.WRITE_BLOCKING)
                                if (n <= 0) off = item.samples.size else { off += n; written += n; framesWritten += n }
                            }
                        }
                        if (item.last && item.tok == token.get()) {
                            // Let the tail play out before saying "done" (the mic may reopen right after).
                            if (written > 0 && queue.isEmpty()) drain(item.tok)
                            startedId = null; val id = item.id; main.post { events?.done(id) }
                        }
                    }
                    is Item.Handoff -> { startedId = null; val (t, id) = item.text to item.id; main.post { events?.handoff(t, id) } }
                }
            } catch (t: Throwable) {
                android.util.Log.w("GeminiVoice", "playback", t)
                val id = item.id; main.post { events?.done(id) }
            } finally { playing = false }
        }
    }
}
