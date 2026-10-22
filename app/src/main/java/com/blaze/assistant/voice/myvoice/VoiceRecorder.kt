package com.blaze.assistant.voice.myvoice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Records the short My voice sample: mono 16-bit at 24 kHz (the model's rate; 16 kHz when the phone can't),
 * with a live level and timer for the setup screen. The audio only ever goes to app-private storage.
 */
class VoiceRecorder(private val maxSec: Int = 20) {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _seconds = MutableStateFlow(0f)
    val seconds: StateFlow<Float> = _seconds

    @Volatile private var running = false
    private var thread: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    var sampleRate = 24_000
        private set

    /** Starts recording. Needs RECORD_AUDIO (checked by the caller). Returns an error message, or null when started. */
    @SuppressLint("MissingPermission")
    fun start(onAutoStop: () -> Unit): String? {
        if (running) return null
        val rates = intArrayOf(24_000, 16_000, 48_000, 44_100)
        var rec: AudioRecord? = null
        for (r in rates) {
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                val a = try { AudioRecord(src, r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, r / 5 * 2)) } catch (_: Exception) { null }
                if (a != null && a.state == AudioRecord.STATE_INITIALIZED) { rec = a; sampleRate = r; break }
                a?.release()
            }
            if (rec != null) break
        }
        val ar = rec ?: return "Couldn't open the microphone. Another app may be using it."
        synchronized(chunks) { chunks.clear() }
        _seconds.value = 0f; _level.value = 0f
        try { ar.startRecording() } catch (e: Exception) { ar.release(); return "Couldn't start recording." }
        running = true
        thread = Thread({
            val buf = ShortArray(sampleRate / 20)
            var total = 0L
            try {
                while (running) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    synchronized(chunks) { chunks += buf.copyOf(n) }
                    total += n
                    _level.value = AudioClip.meterLevel(buf, n)
                    _seconds.value = total / sampleRate.toFloat()
                    if (total >= maxSec.toLong() * sampleRate) { running = false; android.os.Handler(android.os.Looper.getMainLooper()).post(onAutoStop) }
                }
            } finally {
                try { ar.stop() } catch (_: Exception) { }
                ar.release()
                _level.value = 0f
            }
        }, "myvoice-rec").apply { start() }
        return null
    }

    /** Stops and returns what was recorded, as floats. */
    fun stop(): FloatArray {
        running = false
        try { thread?.join(1500) } catch (_: InterruptedException) { }
        thread = null
        val all = synchronized(chunks) { chunks.toList() }
        val n = all.sumOf { it.size }
        val out = FloatArray(n); var p = 0
        for (c in all) { for (s in c) out[p++] = s / 32768f }
        return out
    }

    fun cancel() { running = false; synchronized(chunks) { chunks.clear() } }
}
