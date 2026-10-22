package com.blaze.assistant.mediaapps

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v0.33.0 Beat Lab: hum a melody and Blaze turns it into a beat. On the phone, no internet: the hum is recorded (or a
 * WAV you give), its pitch is tracked (YIN), split into notes, the key is found and the notes snapped to it, chords are
 * picked bar by bar to fit the melody, and a beat is rendered (drums, bass, chord pad and your melody as a lead) at the
 * tempo you want. The WAV lands in the Music library; Halo can then write lyrics on the chords. Pure DSP in [Dsp]
 * (BeatLabTest).
 */
object BeatLab {
    const val TOOL = "beat_lab"
    const val IN_RATE = 16000
    const val OUT_RATE = 22050

    object Dsp {
        data class Note(val midi: Int, val startS: Double, val durS: Double)

        /** YIN pitch of one frame, Hz, or 0 when unvoiced. */
        fun yin(x: FloatArray, rate: Int, minHz: Double = 70.0, maxHz: Double = 1000.0, thr: Double = 0.15): Double {
            val maxTau = (rate / minHz).toInt().coerceAtMost(x.size / 2); val minTau = (rate / maxHz).toInt().coerceAtLeast(2)
            if (maxTau <= minTau) return 0.0
            var energy = 0.0; for (v in x) energy += v * v
            if (energy / x.size < 1e-5) return 0.0
            val d = DoubleArray(maxTau + 1)
            for (tau in 1..maxTau) { var s = 0.0; for (i in 0 until x.size - maxTau) { val df = x[i] - x[i + tau]; s += df * df }; d[tau] = s }
            var run = 0.0; val cm = DoubleArray(maxTau + 1); cm[0] = 1.0
            for (tau in 1..maxTau) { run += d[tau]; cm[tau] = if (run == 0.0) 1.0 else d[tau] * tau / run }
            var tau = minTau
            while (tau <= maxTau) { if (cm[tau] < thr) { while (tau + 1 <= maxTau && cm[tau + 1] < cm[tau]) tau++; break }; tau++ }
            if (tau > maxTau) return 0.0
            val t = if (tau in 1 until maxTau) { val a = cm[tau - 1]; val b = cm[tau]; val c = cm[tau + 1]; val den = a + c - 2 * b; if (den != 0.0) tau + (a - c) / (2 * den) else tau.toDouble() } else tau.toDouble()
            return rate / t
        }

        fun midi(hz: Double) = 69 + 12 * ln(hz / 440.0) / ln(2.0)
        fun hz(m: Double) = 440.0 * 2.0.pow((m - 69) / 12)

        /** Pitch track (one value per 40 ms hop, MIDI float or NaN) → notes of at least [minS]. */
        fun notes(samples: FloatArray, rate: Int, hopS: Double = 0.04, minS: Double = 0.12): List<Note> {
            val hop = (rate * hopS).toInt(); val win = (rate * 0.05).toInt()
            val track = ArrayList<Double>()
            var i = 0
            while (i + win < samples.size) { val f = yin(samples.copyOfRange(i, i + win), rate); track += if (f > 0) midi(f) else Double.NaN; i += hop }
            // median-of-3 smoothing
            val sm = track.indices.map { k -> val w = listOf(track.getOrNull(k - 1), track[k], track.getOrNull(k + 1)).filterNotNull().filter { !it.isNaN() }
                if (track[k].isNaN() || w.isEmpty()) Double.NaN else w.sorted()[w.size / 2] }
            val out = ArrayList<Note>(); var start = -1; var acc = ArrayList<Double>()
            fun close(end: Int) { if (start >= 0 && (end - start) * hopS >= minS) out += Note(acc.sorted()[acc.size / 2].roundToInt(), start * hopS, (end - start) * hopS); start = -1; acc = ArrayList() }
            for (k in sm.indices) {
                val v = sm[k]
                if (v.isNaN()) { close(k); continue }
                if (start >= 0 && abs(v - acc.sorted()[acc.size / 2]) > 0.8) close(k)
                if (start < 0) start = k
                acc += v
            }
            close(sm.size)
            return out
        }

        private val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val NAMES = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

        /** Major key (tonic pitch class) that fits most note time. */
        fun key(notes: List<Note>): Int = (0..11).maxByOrNull { k -> notes.sumOf { n -> if (((n.midi - k) % 12 + 12) % 12 in MAJOR) n.durS else 0.0 } + (if (notes.firstOrNull()?.let { (it.midi % 12) == k } == true) 0.01 else 0.0) } ?: 0

        fun snap(m: Int, key: Int): Int { if (((m - key) % 12 + 12) % 12 in MAJOR) return m
            return if (((m + 1 - key) % 12 + 12) % 12 in MAJOR) m + 1 else m - 1 }

        /** Degrees I, IV, V, vi as (root offset, minor). */
        val CHORDS = listOf(0 to false, 5 to false, 7 to false, 9 to true)
        fun chordTones(key: Int, c: Pair<Int, Boolean>) = listOf(0, if (c.second) 3 else 4, 7).map { (key + c.first + it) % 12 }

        /** One chord per bar: the one whose tones cover most melody time in that bar (ties: I, then the next in the cycle). */
        fun chords(notes: List<Note>, key: Int, barS: Double, bars: Int): List<Int> = (0 until bars).map { b ->
            val inBar = notes.filter { it.startS >= b * barS && it.startS < (b + 1) * barS }
            if (inBar.isEmpty()) listOf(0, 3, 1, 2)[b % 4]
            else CHORDS.indices.maxByOrNull { ci -> inBar.sumOf { n -> if ((n.midi % 12) in chordTones(key, CHORDS[ci])) n.durS else 0.0 } - ci * 0.001 }!!
        }

        fun chordName(key: Int, ci: Int) = NAMES[(key + CHORDS[ci].first) % 12] + if (CHORDS[ci].second) "m" else ""

        /** Snap note starts to the 8th-note grid and lengths to at least one 8th. */
        fun quantize(notes: List<Note>, bpm: Int): List<Note> { val e = 30.0 / bpm
            return notes.map { Note(it.midi, (it.startS / e).roundToInt() * e, maxOf(e, (it.durS / e).roundToInt() * e)) }.distinctBy { it.startS } }

        fun wav(pcm: ShortArray, rate: Int): ByteArray {
            val data = pcm.size * 2; val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(data)
            pcm.forEach { b.putShort(it) }; return b.array()
        }

        /** Renders [loops] passes of the bars: drums, bass, pad, lead. */
        fun render(notes: List<Note>, key: Int, chords: List<Int>, bpm: Int, style: String, loops: Int = 2, rate: Int = OUT_RATE): ShortArray {
            val beat = 60.0 / bpm; val bar = beat * 4; val total = bar * chords.size * loops
            val n = (total * rate).toInt() + rate; val mix = FloatArray(n)
            val rnd = java.util.Random(7)
            fun addTone(f: Double, t0: Double, dur: Double, amp: Double, wave: Int, rel: Double = 0.08) {
                val s0 = (t0 * rate).toInt(); val len = ((dur + rel) * rate).toInt()
                for (i in 0 until len) { val idx = s0 + i; if (idx !in mix.indices) break
                    val t = i.toDouble() / rate; val ph = 2 * PI * f * t
                    val env = minOf(1.0, t / 0.01) * (if (t > dur) exp(-(t - dur) / rel * 3) else 1.0)
                    val v = when (wave) { 0 -> sin(ph); 1 -> sin(ph) + 0.3 * sin(2 * ph) + 0.15 * sin(3 * ph); else -> sin(ph) + 0.5 * sin(3 * ph) / 3 + 0.2 * sin(5 * ph) / 5 }
                    mix[idx] += (v * env * amp).toFloat() }
            }
            fun kick(t0: Double) { val s0 = (t0 * rate).toInt(); for (i in 0 until (0.25 * rate).toInt()) { val idx = s0 + i; if (idx !in mix.indices) break
                val t = i.toDouble() / rate; mix[idx] += (sin(2 * PI * (50 + 90 * exp(-t * 30)) * t) * exp(-t * 12) * 0.9).toFloat() } }
            fun noise(t0: Double, dur: Double, amp: Double, bright: Boolean) { val s0 = (t0 * rate).toInt(); var prev = 0.0
                for (i in 0 until (dur * rate).toInt()) { val idx = s0 + i; if (idx !in mix.indices) break
                    val t = i.toDouble() / rate; val w = rnd.nextGaussian(); val v = if (bright) w - prev else w; prev = w
                    mix[idx] += (v * exp(-t / dur * 5) * amp).toFloat() } }
            val trap = style.contains("trap", true) || style.contains("drill", true)
            val amapiano = style.contains("amapiano", true)
            for (loop in 0 until loops) for ((b, ci) in chords.withIndex()) {
                val t0 = (loop * chords.size + b) * bar
                val tones = chordTones(key, CHORDS[ci])
                tones.forEach { pc -> addTone(hz((60 + pc).toDouble()), t0, bar * 0.95, 0.07, 1, 0.3) }
                val root = 36 + (key + CHORDS[ci].first) % 12
                for (q in 0 until 4) addTone(hz(root.toDouble()), t0 + q * beat, beat * (if (amapiano) 0.9 else 0.45), if (amapiano) 0.32 else 0.25, 0)
                val kicks = if (trap) listOf(0.0, 1.5, 2.25) else if (amapiano) listOf(0.0, 1.0, 2.0, 3.0) else listOf(0.0, 2.0, 2.5)
                kicks.forEach { kick(t0 + it * beat) }
                listOf(1.0, 3.0).forEach { noise(t0 + it * beat, 0.18, 0.35, false) }
                val hatStep = if (trap) 0.25 else 0.5
                var h = 0.0; while (h < 4) { noise(t0 + h * beat, 0.04, 0.12, true); h += hatStep }
            }
            val loopLen = bar * chords.size
            for (loop in 0 until loops) notes.forEach { nt -> if (nt.startS < loopLen) addTone(hz(nt.midi.toDouble()), loop * loopLen + nt.startS, minOf(nt.durS, loopLen - nt.startS), 0.22, 2) }
            var peak = 1e-6f; for (v in mix) peak = maxOf(peak, abs(v))
            val g = 0.89f / peak
            return ShortArray(n) { (mix[it] * g * 32767).toInt().coerceIn(-32768, 32767).toShort() }
        }
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Beat Lab: the user hums a melody (record for N seconds, or give a WAV path) and Blaze makes a beat from it on the phone: key, " +
            "chords per bar, drums, bass, pad and the hummed melody as lead, saved to the Music library. Then offer lyrics from Halo on the chords. " +
            "Tell the user to start humming right when you call record.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("record", "file"))))
            .put("seconds", JSONObject().put("type", "integer").put("description", "record length, 5-20, default 10"))
            .put("path", JSONObject().put("type", "string").put("description", "file: a 16-bit WAV of the hum"))
            .put("bpm", JSONObject().put("type", "integer").put("description", "tempo, default 92"))
            .put("style", JSONObject().put("type", "string").put("description", "pop (default), trap, amapiano"))
            .put("title", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action")))))

    @Suppress("MissingPermission")
    private fun record(seconds: Int): FloatArray {
        val min = android.media.AudioRecord.getMinBufferSize(IN_RATE, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val ar = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, IN_RATE, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        val out = ShortArray(IN_RATE * seconds); var got = 0
        try { ar.startRecording(); while (got < out.size) { val n = ar.read(out, got, minOf(1600, out.size - got)); if (n <= 0) break; got += n } } finally { try { ar.stop() } catch (_: Throwable) { }; ar.release() }
        return FloatArray(got) { out[it] / 32768f }
    }

    private fun readWav(f: File): Pair<FloatArray, Int>? = try {
        RandomAccessFile(f, "r").use { r -> val b = ByteArray(r.length().toInt().coerceAtMost(40_000_000)); r.readFully(b)
            val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val ch = bb.getShort(22).toInt(); val rate = bb.getInt(24); var p = 12; var dataAt = -1; var dataLen = 0
            while (p + 8 <= b.size) { val id = String(b, p, 4); val len = bb.getInt(p + 4); if (id == "data") { dataAt = p + 8; dataLen = minOf(len, b.size - p - 8); break }; p += 8 + len }
            if (dataAt < 0 || bb.getShort(34).toInt() != 16) null else {
                val frames = dataLen / 2 / ch; FloatArray(frames) { i -> bb.getShort(dataAt + i * 2 * ch).toFloat() / 32768f } to rate } }
    } catch (_: Exception) { null }

    suspend fun run(ctx: Context, a: JSONObject): String = withContext(Dispatchers.IO) {
        val (samples, rate) = when (a.optString("action")) {
            "file" -> readWav(File(a.optString("path").replace("/root/phone/", "/storage/emulated/0/"))) ?: return@withContext "Give me a 16-bit WAV file."
            else -> { if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@withContext "Beat Lab needs the microphone permission."
                record(a.optInt("seconds", 10).coerceIn(5, 20)) to IN_RATE }
        }
        val raw = Dsp.notes(samples, rate)
        if (raw.size < 3) return@withContext "I couldn't hear a clear melody. Hum louder and closer (\u201cdoo doo\u201d works best), away from music, and try again."
        val bpm = a.optInt("bpm", 92).coerceIn(60, 170)
        val key = Dsp.key(raw)
        var notes = Dsp.quantize(raw.map { it.copy(midi = Dsp.snap(it.midi, key)) }, bpm)
        // Lead in a comfortable octave around C5.
        val med = notes.map { it.midi }.sorted()[notes.size / 2]; val shift = ((72 - med) / 12.0).roundToInt() * 12
        notes = notes.map { it.copy(midi = it.midi + shift) }
        val barS = 240.0 / bpm
        val bars = ((notes.maxOf { it.startS + it.durS } / barS).toInt() + 1).coerceIn(2, 8).let { if (it % 2 == 1) it + 1 else it }
        val chords = Dsp.chords(notes, key, barS, bars)
        val style = a.optString("style", "pop")
        val pcm = Dsp.render(notes, key, chords, bpm, style)
        val dir = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: ctx.filesDir, "BeatLab").apply { mkdirs() }
        val title = a.optString("title").ifBlank { "Beat Lab " + java.text.SimpleDateFormat("d MMM HH.mm", java.util.Locale.UK).format(java.util.Date()) }
        val f = File(dir, title.replace(Regex("[^A-Za-z0-9 ._-]"), "_") + ".wav"); f.writeBytes(Dsp.wav(pcm, OUT_RATE))
        try { MediaLib.ensure(ctx); MediaLib.addDownload(Track(Uri.fromFile(f).toString(), Uri.fromFile(f).toString(), title, "Beat Lab", "Beat Lab", (pcm.size * 1000L / OUT_RATE),
            "", "download", System.currentTimeMillis(), "", "audio", "Yours", "beatlab")) } catch (_: Throwable) { }
        val names = chords.map { Dsp.chordName(key, it) }
        "Beat made: \u201c$title\u201d, ${Dsp.NAMES[key]} major, $bpm BPM, ${style.ifBlank { "pop" }}, $bars bars x2 (${pcm.size / OUT_RATE}s), " +
            "chords ${names.joinToString(" | ")}, ${notes.size} melody notes. It's in Music (Downloads). Offer: Halo writes lyrics on these chords in the user's style, " +
            "another tempo/style, or cover art (cover_art)."
    }
}
