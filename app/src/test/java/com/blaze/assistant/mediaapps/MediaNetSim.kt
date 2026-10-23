package com.blaze.assistant.mediaapps

import java.io.IOException
import java.security.MessageDigest

/**
 * 0.45.19 test harness: a deterministic simulated network on a virtual clock (no real sleeps, no threads) and a
 * simple player model on top of [MediaNet.NetReader]. Used by MediaNetTest (drops, time-to-first-play).
 */
class VClock(var t: Double = 0.0) : MediaNet.Clock {
    override fun now(): Long = t.toLong()
    override fun sleep(ms: Long) { t += ms }
    fun advance(ms: Double) { t += ms }
}

/**
 * One server, one connection at a time. [bytesPerMs] throughput, [latencyMs] per request (connect + first byte),
 * [drops] virtual-time windows with no network (opens fail after 200 ms, reads reset). [throttleOpenEnded]: an
 * open-ended / large range (> [throttleOver] bytes) is served at [throttledBytesPerMs] after its first [burst] bytes,
 * the way some video CDNs pace whole-file requests (only used where a test says so).
 */
class FakeNet(
    val data: ByteArray,
    val clock: VClock,
    var bytesPerMs: Double,
    val latencyMs: Long = 300,
    val drops: List<LongRange> = emptyList(),
    val ignoreRange: Boolean = false,
    val throttleOpenEnded: Boolean = false,
    val throttleOver: Long = 1_000_000,
    val throttledBytesPerMs: Double = 0.0,
    val burst: Long = 256 * 1024,
) : MediaNet.RangeSource {
    var opens = 0; var failedOpens = 0; var resets = 0; var served = 0L
    private var pos = 0L; private var end = 0L; private var sent = 0L; private var throttled = false
    private var lastIo = 0L
    fun down() = drops.any { clock.now() in it }
    /** A drop that began since the last byte moved also killed the (idle) connection. */
    private fun droppedSinceLastIo() = drops.any { it.first > lastIo && it.first <= clock.now() }

    override fun open(pos: Long, len: Long): MediaNet.Opened {
        if (down()) { clock.advance(200.0); failedOpens++; throw IOException("no route to host") }
        clock.advance(latencyMs.toDouble())
        if (down()) { failedOpens++; throw IOException("connect timed out") }
        opens++; lastIo = clock.now()
        val size = data.size.toLong()
        if (ignoreRange) { this.pos = pos; end = size; sent = 0; throttled = false; return MediaNet.Opened(size - pos, -1, ranged = len < 0 && pos == 0L) }
        if (pos >= size) throw IOException("416")
        this.pos = pos; end = if (len < 0) size else minOf(size, pos + len); sent = 0
        throttled = throttleOpenEnded && (len < 0 || len > throttleOver)
        return MediaNet.Opened(end - pos, size, ranged = true)
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (down() || droppedSinceLastIo()) { resets++; lastIo = clock.now(); throw IOException("connection reset") }
        if (pos >= end) return -1
        val k = minOf(len.toLong(), 4_096L, end - pos).toInt()
        val rate = if (throttled && sent >= burst) throttledBytesPerMs else bytesPerMs
        clock.advance(k / rate)
        if (down()) { resets++; lastIo = clock.now(); throw IOException("connection reset") }
        lastIo = clock.now()
        System.arraycopy(data, pos.toInt(), buf, off, k)
        pos += k; sent += k; served += k
        return k
    }

    override fun close() {}
}

/** What one simulated playback did. */
data class PlayRun(val firstPlayMs: Long, val stallMs: Long, val stalls: Int, val bytes: Long, val sha: String,
                   val readerErrors: Int, val requests: Int, val reopens: Int, val finished: Boolean)

/**
 * A player model: loads through [open]ed readers into a buffer, starts after [startBufMs] of media, plays at
 * [rateBytesPerMs], stops loading at [readAheadMs] ahead, and (like ExoPlayer with [MediaNet.retryDelayMs]) re-opens
 * at the loaded position when a read error gets past the reader, up to [MediaNet.LOAD_RETRIES] times.
 * [startAtMs] = when the stream request starts (metadata fetched first counts against time-to-first-play).
 */
class PlayerSim(
    val clock: VClock,
    val rateBytesPerMs: Double,
    val startBufMs: Long,
    val readAheadMs: Long,
    val total: Long,
    val reader: () -> MediaNet.NetReader,
) {
    private var loaded = 0L
    private var played = 0.0
    private var playing = false
    private var first = -1L
    private var stallMs = 0.0
    private var stalls = 0
    private var stalled = false
    private var last = 0.0

    private fun tick() {
        val now = clock.t; val dt = now - last; last = now
        if (!playing || dt <= 0) return
        val can = loaded - played
        val need = dt * rateBytesPerMs
        if (need <= can) { played += need; stalled = false }
        else {
            played = loaded.toDouble()
            stallMs += dt - can / rateBytesPerMs
            if (!stalled) { stalls++; stalled = true }
        }
    }

    fun run(): PlayRun {
        last = clock.t
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var errors = 0; var requests = 0; var reopens = 0
        var r = reader()
        var retry = 0
        var openNeeded = true
        while (loaded < total) {
            try {
                if (openNeeded) { r.open(loaded, -1); openNeeded = false }
                // Stop loading at the read-ahead; carry on when it's 5 s under.
                if (playing && (loaded - played) / rateBytesPerMs >= readAheadMs) {
                    clock.advance((loaded - played) / rateBytesPerMs - (readAheadMs - 5_000)); tick(); continue
                }
                val n = r.read(buf, 0, buf.size)
                tick()
                if (n < 0) break
                md.update(buf, 0, n); loaded += n; retry = 0
                if (!playing && loaded >= startBufMs * rateBytesPerMs) { playing = true; first = clock.now(); last = clock.t }
            } catch (e: IOException) {
                tick()
                errors++; requests += r.requests; reopens += r.reopens
                if (++retry > MediaNet.LOAD_RETRIES) break
                clock.advance(MediaNet.retryDelayMs(false, false, retry).toDouble()); tick()
                r = reader(); openNeeded = true
            }
        }
        requests += r.requests; reopens += r.reopens
        if (!playing && loaded > 0) { playing = true; first = clock.now() }
        return PlayRun(first, stallMs.toLong(), stalls, loaded, md.digest().joinToString("") { "%02x".format(it) },
            errors, requests, reopens, loaded == total)
    }
}

fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
fun bytes(n: Int, seed: Int = 7): ByteArray { val r = java.util.Random(seed.toLong()); return ByteArray(n).also { r.nextBytes(it) } }
