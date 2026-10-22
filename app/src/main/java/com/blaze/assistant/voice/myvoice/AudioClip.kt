package com.blaze.assistant.voice.myvoice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mono audio helpers for the My voice recording: level meter, trimming silence, shortening long pauses,
 * normalising, and 16-bit WAV in/out. Pure Kotlin (AudioClipTest).
 */
object AudioClip {
    /** How much clean speech the voice model actually uses (sherpa-onnx PocketTTS max_reference_audio_len default). */
    const val MAX_REFERENCE_SEC = 10f
    /**
     * Less speech than this (measured on the whole recording, see [activeSpeechSeconds]) and the clone sounds generic;
     * ask for a re-record. A normal read of the script is 10-15 s.
     */
    const val MIN_SPEECH_SEC = 5f

    fun pcm16ToFloat(pcm: ShortArray, n: Int = pcm.size): FloatArray = FloatArray(n) { pcm[it] / 32768f }

    fun floatToPcm16(x: FloatArray): ShortArray = ShortArray(x.size) { (x[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        if (to <= from) return 0f
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from)).toFloat()
    }

    /** 0..1 for a level meter: -60 dBFS (silence) .. -6 dBFS (loud speech). */
    fun meterLevel(x: ShortArray, n: Int = x.size): Float {
        if (n <= 0) return 0f
        var s = 0.0
        for (i in 0 until n) { val v = x[i] / 32768.0; s += v * v }
        val r = sqrt(s / n)
        if (r <= 1e-6) return 0f
        val db = 20 * log10(r)
        return ((db + 60) / 54).toFloat().coerceIn(0f, 1f)
    }

    fun removeDc(x: FloatArray): FloatArray {
        if (x.isEmpty()) return x
        var m = 0.0
        for (v in x) m += v
        val mean = (m / x.size).toFloat()
        return FloatArray(x.size) { x[it] - mean }
    }

    private fun frameRms(x: FloatArray, frame: Int): FloatArray {
        val n = (x.size + frame - 1) / frame
        return FloatArray(n) { rms(x, it * frame, min(x.size, (it + 1) * frame)) }
    }

    /** Frame energy above which a 20 ms frame counts as speech: well above the room's noise floor. */
    internal fun speechThreshold(frames: FloatArray): Float {
        if (frames.isEmpty()) return 1f
        val sorted = frames.sorted()
        val floor = sorted[(sorted.size * 0.1).toInt().coerceAtMost(sorted.size - 1)]
        val loud = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        // At least 3x the floor, at least ~-46 dBFS, and never above a fifth of the loud speech.
        return max(max(floor * 3f, 0.005f), min(loud * 0.2f, 0.05f)).coerceAtMost(max(loud * 0.5f, 0.005f))
    }

    /** Cut leading/trailing silence, keeping [padMs] around the speech. Returns an empty array when nothing was said. */
    fun trimSilence(x: FloatArray, sampleRate: Int, padMs: Int = 150): FloatArray {
        val frame = max(1, sampleRate / 50)
        val f = frameRms(x, frame)
        val thr = speechThreshold(f)
        val first = f.indexOfFirst { it >= thr }
        if (first < 0) return FloatArray(0)
        val last = f.indexOfLast { it >= thr }
        val pad = padMs * sampleRate / 1000
        val start = max(0, first * frame - pad)
        val end = min(x.size, (last + 1) * frame + pad)
        return x.copyOfRange(start, end)
    }

    /** Shorten every pause longer than [maxPauseMs] to [maxPauseMs], so the model hears more speech. */
    fun shortenPauses(x: FloatArray, sampleRate: Int, maxPauseMs: Int = 350): FloatArray {
        val frame = max(1, sampleRate / 50)
        val f = frameRms(x, frame)
        val thr = speechThreshold(f)
        val maxFrames = max(1, maxPauseMs / 20)
        val out = ArrayList<FloatArray>()
        var i = 0
        while (i < f.size) {
            if (f[i] >= thr) {
                out += x.copyOfRange(i * frame, min(x.size, (i + 1) * frame)); i++; continue
            }
            var j = i
            while (j < f.size && f[j] < thr) j++
            val keep = min(j - i, maxFrames)
            // keep the middle of the pause centred: half from each side
            val head = keep / 2
            for (k in i until i + head) out += x.copyOfRange(k * frame, min(x.size, (k + 1) * frame))
            for (k in j - (keep - head) until j) out += x.copyOfRange(k * frame, min(x.size, (k + 1) * frame))
            i = j
        }
        val total = out.sumOf { it.size }
        val y = FloatArray(total); var p = 0
        for (a in out) { a.copyInto(y, p); p += a.size }
        return y
    }

    /** Peak-normalise to [targetPeak] (about -1 dBFS) without boosting more than [maxGain] (keeps hiss quiet). */
    fun normalize(x: FloatArray, targetPeak: Float = 0.89f, maxGain: Float = 12f): FloatArray {
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        if (peak < 1e-6f) return x.copyOf()
        val g = min(targetPeak / peak, maxGain)
        return FloatArray(x.size) { (x[it] * g).coerceIn(-1f, 1f) }
    }

    /** Seconds of frames that count as speech. */
    fun speechSeconds(x: FloatArray, sampleRate: Int): Float {
        val frame = max(1, sampleRate / 50)
        val f = frameRms(x, frame)
        val thr = speechThreshold(f)
        return f.count { it >= thr } * 0.02f
    }

    /**
     * Seconds of talking in a whole raw recording, the way a person would time it: frames clearly above the room's
     * noise floor, with the short gaps between syllables and words (up to 200 ms each side) counted as talking.
     *
     * v0.41.0: this replaced [speechSeconds] for the "too short" check. That one ran on the clip after it was cut to
     * 10 s with its pauses removed, so the room's real noise floor was gone and the threshold landed on ordinary
     * speech (up to -26 dBFS): only the loudest ~40 % of frames counted and a full 15 s read scored ~3.5-4 s, under
     * the 5 s minimum, every time.
     */
    fun activeSpeechSeconds(x: FloatArray, sampleRate: Int, hangoverMs: Int = 200): Float {
        if (x.isEmpty() || sampleRate <= 0) return 0f
        val frame = max(1, sampleRate / 50)
        val f = frameRms(x, frame)
        if (f.isEmpty()) return 0f
        val sorted = f.sorted()
        val floor = sorted[(sorted.size * 0.1).toInt().coerceAtMost(sorted.size - 1)]
        val loud = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        // No real contrast between the loud part and the quiet part: steady noise, not someone talking.
        if (loud < 0.003f || loud < floor * 4f) return 0f
        val thr = max(max(floor * 2.5f, 0.002f), loud * 0.06f).coerceAtMost(max(loud * 0.5f, 0.002f))
        val hang = max(0, hangoverMs / 20)
        val active = BooleanArray(f.size)
        for (i in f.indices) if (f[i] >= thr) {
            for (k in max(0, i - hang)..min(f.size - 1, i + hang)) active[k] = true
        }
        return active.count { it } * 0.02f
    }

    /** Share of samples at full scale: above ~1% the recording clipped (too close / too loud). */
    fun clippedShare(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        return x.count { abs(it) >= 0.985f }.toFloat() / x.size
    }

    enum class Verdict { OK, NOTHING_HEARD, TOO_SHORT, TOO_QUIET, TOO_LOUD, TOO_NOISY }

    data class Prepared(val samples: FloatArray, val sampleRate: Int, val speechSec: Float, val verdict: Verdict) {
        val seconds get() = samples.size / sampleRate.toFloat()
    }

    /** Raw recording -> the clip the model clones from: DC removed, trimmed, pauses shortened, at most 10 s, normalised. */
    fun prepareReference(raw: FloatArray, sampleRate: Int, maxSec: Float = MAX_REFERENCE_SEC): Prepared {
        var peak = 0f
        for (v in raw) peak = max(peak, abs(v))
        val clipped = clippedShare(raw)
        val x0 = removeDc(raw)
        val trimmed = trimSilence(x0, sampleRate)
        if (trimmed.isEmpty() || peak < 0.003f) return Prepared(FloatArray(0), sampleRate, 0f, Verdict.NOTHING_HEARD)
        val tight = shortenPauses(trimmed, sampleRate)
        val limited = if (tight.size > maxSec * sampleRate) tight.copyOf((maxSec * sampleRate).toInt()) else tight
        // Measured on the whole recording (DC removed), before trimming and the 10 s cut: see activeSpeechSeconds.
        val speech = activeSpeechSeconds(x0, sampleRate)
        val verdict = when {
            clipped > 0.01f -> Verdict.TOO_LOUD
            peak < 0.05f -> Verdict.TOO_QUIET
            speech <= 0f -> Verdict.TOO_NOISY
            speech < MIN_SPEECH_SEC -> Verdict.TOO_SHORT
            else -> Verdict.OK
        }
        return Prepared(normalize(limited), sampleRate, speech, verdict)
    }

    fun verdictText(v: Verdict): String = when (v) {
        Verdict.OK -> "Sounds good."
        Verdict.NOTHING_HEARD -> "I couldn't hear anything. Check the mic isn't covered and try again."
        Verdict.TOO_SHORT -> "That was a bit short. Read the whole script out loud, about 10 to 15 seconds."
        Verdict.TOO_QUIET -> "That was very quiet. Hold the phone a little closer and try again."
        Verdict.TOO_LOUD -> "That was too loud and distorted. Hold the phone a bit further away."
        Verdict.TOO_NOISY -> "There's a lot of background noise. Find a quieter spot and try again."
    }

    // ---- WAV (16-bit PCM mono) ----

    fun wavBytes(x: FloatArray, sampleRate: Int): ByteArray {
        val pcm = floatToPcm16(x)
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(sampleRate); b.putInt(sampleRate * 2)
        b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        for (s in pcm) b.putShort(s)
        return b.array()
    }

    /** Reads a 16-bit PCM WAV (mono, or the first channel). Returns samples and sample rate, or null when it isn't one. */
    fun readWav(bytes: ByteArray): Pair<FloatArray, Int>? {
        if (bytes.size < 44) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var pos = 12
        var rate = 0; var channels = 1; var bits = 16; var fmtOk = false
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > bytes.size + 1) return null
            if (id == "fmt ") {
                val format = b.getShort(body).toInt()
                channels = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
                fmtOk = format == 1 && bits == 16 && channels >= 1
            } else if (id == "data") {
                if (!fmtOk || rate <= 0) return null
                val end = min(bytes.size, body + size)
                val frames = (end - body) / (2 * channels)
                val out = FloatArray(frames) { b.getShort(body + it * 2 * channels) / 32768f }
                return out to rate
            }
            pos = body + size + (size and 1)
        }
        return null
    }

    fun concat(vararg parts: FloatArray): FloatArray {
        val y = FloatArray(parts.sumOf { it.size }); var p = 0
        for (a in parts) { a.copyInto(y, p); p += a.size }
        return y
    }
}
