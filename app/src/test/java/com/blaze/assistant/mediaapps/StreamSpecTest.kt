package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.MediaNet.Link
import com.blaze.assistant.mediaapps.store.StoreFormat
import com.blaze.assistant.mediaapps.store.StoreItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

/**
 * 0.45.20: Compass's STREAM_PREFETCH_SPEC.md §5 strict tests, each 5 runs that must all pass, on a deterministic
 * simulated throttled network (virtual clock, seeded loss; no real waiting) over the production rules: MediaNet's
 * NetReader / chunks / read-ahead / prefetch gating, UrlRenew, NetHonesty, LiveSearch, SavedSearches. Test 4 (cache cap)
 * runs on the real media3 SimpleCache in [StreamSpecCacheTest]. These are model runs, not phone measurements.
 */
class StreamSpecTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    // ------------------------------------------------------------------ the throttled network

    class HttpErr(val code: Int) : IOException("HTTP $code")

    /**
     * One link, one connection at a time: [bytesPerMs] throughput, [latencyMs] per request, [lossPct] of 4 KB reads lose
     * a segment (one retransmit = one more [latencyMs]), [resetPct] of reads reset the connection, no network from
     * [offlineAt]. A stream's original url expires at [Stream.expireAt]: the connection open then is reset and every
     * new request with it is refused (403), the way an expired signed CDN url behaves.
     */
    class SpecNet(val clock: VClock, var bytesPerMs: Double, val latencyMs: Long = 300, val lossPct: Double = 0.0,
                  val resetPct: Double = 0.0, seed: Long = 1, var offlineAt: Long = Long.MAX_VALUE) {
        private val rnd = java.util.Random(seed)
        var opens = 0; var resets = 0; var losses = 0; var refused = 0; var served = 0L
        fun down() = clock.now() >= offlineAt

        inner class Stream(val data: ByteArray, var url: String) : MediaNet.RangeSource {
            val original = url
            var expireAt = Long.MAX_VALUE
            private var killed = false
            private var pos = 0L; private var end = 0L
            private fun expiredNow() = url == original && clock.now() >= expireAt
            override fun open(pos: Long, len: Long): MediaNet.Opened {
                if (down()) { clock.advance(200.0); throw IOException("no route to host") }
                clock.advance(latencyMs.toDouble())
                if (expiredNow()) { refused++; throw HttpErr(403) }
                val size = data.size.toLong()
                if (pos >= size) throw HttpErr(416)
                opens++
                this.pos = pos; end = if (len < 0) size else minOf(size, pos + len)
                return MediaNet.Opened(end - pos, size, ranged = true)
            }
            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                if (down()) { resets++; throw IOException("connection reset") }
                if (pos >= end) return -1
                if (expiredNow() && !killed) { killed = true; resets++; throw IOException("connection reset by peer") }
                if (rnd.nextDouble() < resetPct) { resets++; throw IOException("connection reset") }
                if (rnd.nextDouble() < lossPct) { losses++; clock.advance(latencyMs.toDouble()) }
                val k = minOf(len.toLong(), 4_096L, end - pos).toInt()
                clock.advance(k / bytesPerMs)
                if (down()) { resets++; throw IOException("connection reset") }
                System.arraycopy(data, pos.toInt(), buf, off, k)
                pos += k; served += k
                return k
            }
            override fun close() {}
        }
    }

    private fun reader(clock: VClock, s: MediaNet.RangeSource, renew: () -> Boolean = { false }) = MediaNet.NetReader(s, clock, MediaNet.SHORT_DROP_MS,
        MediaNet.STREAM_CHUNKS, fatal = { it is HttpErr && it.code != 416 }, eof = { it is HttpErr && it.code == 416 },
        expired = { it is HttpErr && UrlRenew.expired(it.code) }, renew = renew)

    // ------------------------------------------------------------------ a queue player model

    data class QueueRun(val starts: List<Long>, val stalls: List<Long>, val playedMs: List<Long>, val finished: Int, val skipped: Int,
                        val pausedAt: Long, val offlineBannerMs: Long, val maxOfflineStallMs: Long, val songShas: List<Boolean>)

    /**
     * Plays [songs] in order: the loader keeps [readAheadMs] of the current song loaded (stops 5 s over, like
     * ReadAheadControl), then fetches the next song's first [prefetchBytes] (0 = off) at low priority. Starts at
     * MediaNet.START_BUFFER_MS buffered, resumes after a stall at REBUFFER_MS. A load error past the reader's grace is
     * retried like DropPolicy. Offline, a stall of NetHonesty.OFFLINE_SKIP_MS skips to the next item that plays offline
     * (MusicService.offlineSkip) or pauses.
     */
    class QueueSim(val clock: VClock, val net: SpecNet, val songs: List<ByteArray>, val rate: Double, val readAheadMs: Long,
                   val prefetchBytes: Long, val untilMs: Long = Long.MAX_VALUE) {
        private val n = songs.size
        private val streams = songs.mapIndexed { i, b -> net.Stream(b, "https://cdn/s$i?itag=140") }
        private val loaded = LongArray(n)
        private val md = songs.map { MessageDigest.getInstance("SHA-256") }
        private var cur = 0; private var played = 0.0; private var playing = false; private var startedThis = false
        private var switchAt = 0L; private var stallFrom = -1L; private var paused = false
        private var last = 0.0
        private val starts = ArrayList<Long>(); private val stalls = ArrayList<Long>(); private val playedMs = LongArray(n)
        private var skipped = 0; private var finished = 0; private var bannerMs = 0.0; private var maxOfflineStall = 0L
        private var rTarget = -1; private var r: MediaNet.NetReader? = null; private var errors = 0
        private val buf = ByteArray(64 * 1024)

        private fun size(i: Int) = songs[i].size.toLong()
        private fun ahead() = (loaded[cur] - played) / rate

        private fun tick() {
            val now = clock.t; val dt = now - last; last = now
            if (dt <= 0 || paused) return
            if (playing) {
                val can = loaded[cur] - played; val need = dt * rate
                if (net.down()) bannerMs += minOf(dt, can / rate)   // "Offline: playing from saved" while it plays
                if (need <= can) { played += need; playedMs[cur] += dt.toLong() }
                else { played = loaded[cur].toDouble(); playedMs[cur] += (can / rate).toLong(); playing = false; if (played < size(cur)) stallFrom = (now - (need - can) / rate).toLong() }
                if (played >= size(cur)) { finished++; next(cur + 1) }
            }
            if (!playing && !paused && cur < n) {
                val need = if (startedThis) MediaNet.REBUFFER_MS else MediaNet.START_BUFFER_MS
                if (ahead() >= need || loaded[cur] >= size(cur)) {
                    playing = true
                    if (!startedThis) { starts += clock.now() - switchAt; startedThis = true }
                    else if (stallFrom >= 0) stalls += clock.now() - stallFrom
                    stallFrom = -1
                } else if (startedThis && stallFrom >= 0 && net.down()) {
                    val st = clock.now() - stallFrom
                    maxOfflineStall = maxOf(maxOfflineStall, st)
                    if (NetHonesty.offlineSkipNow(st, false, true)) {
                        val nx = NetHonesty.offlineNext((0 until n).toList(), cur) { loaded[it] >= MediaNet.START_BUFFER_MS * rate }
                        skipped++; stallFrom = -1
                        if (nx < 0) paused = true else next(nx)
                    }
                }
            }
        }

        private fun next(i: Int) {
            cur = i; played = 0.0; playing = false; startedThis = false; switchAt = clock.now(); stallFrom = -1
            if (cur >= n) paused = true
        }

        private fun target(): Int {
            if (cur >= n) return -1
            if (loaded[cur] < size(cur) && ahead() < readAheadMs) return cur
            val nx = cur + 1
            if (prefetchBytes > 0 && nx < n && loaded[nx] < minOf(prefetchBytes, size(nx))) return nx
            return -1
        }

        fun run(): QueueRun {
            last = clock.t; switchAt = clock.now()
            var idleFor = 0L
            while (!paused && cur < n && clock.now() < untilMs) {
                val t = target()
                if (t < 0) {
                    // Nothing to load: play on for a moment (the loader wakes when the buffer is 5 s under its target).
                    idleFor = 100; clock.advance(idleFor.toDouble()); tick(); continue
                }
                try {
                    if (t != rTarget || r == null) {
                        r?.close(); rTarget = t
                        r = MediaNet.NetReader(streams[t], clock, MediaNet.SHORT_DROP_MS, MediaNet.STREAM_CHUNKS,
                            fatal = { it is HttpErr && it.code != 416 }, eof = { it is HttpErr && it.code == 416 })
                        val len = if (t == cur) -1L else minOf(prefetchBytes, size(t)) - loaded[t]
                        r!!.open(loaded[t], len)
                    }
                    val k = r!!.read(buf, 0, buf.size)
                    if (k < 0) { r?.close(); r = null; rTarget = -1; tick(); continue }
                    md[t].update(buf, 0, k); loaded[t] += k
                    tick()
                } catch (e: IOException) {
                    r?.close(); r = null; rTarget = -1; errors++
                    // The player's retry wait, in 100 ms steps so playback (and the offline skip) keeps its own time.
                    var w = MediaNet.retryDelayMs(false, false, minOf(errors, 6))
                    while (w > 0 && !paused) { clock.advance(100.0); tick(); w -= 100 }
                }
            }
            val shas = songs.indices.map { i -> loaded[i] == size(i) && md[i].digest().joinToString("") { "%02x".format(it) } == sha(songs[i]) }
            return QueueRun(starts, stalls, playedMs.toList(), finished, skipped, if (paused) clock.now() else -1, bannerMs.toLong(), maxOfflineStall, shas)
        }
    }

    // ------------------------------------------------------------------ 1. flaky 3G

    @Test fun t1_flaky3gQueueNoStallOver3sAndStartsUnder2s_5of5() {
        val lines = ArrayList<String>(); var pass = 0
        for (run in 0 until 5) {
            val clock = VClock()
            // 400 kbps = 50 bytes/ms, 300 ms per request, 2 % segment loss, a few resets; 10 songs x 90 s = 15 min.
            val net = SpecNet(clock, 50.0, 300, lossPct = 0.02, resetPct = 0.002, seed = 100L + run)
            val rate = 16.0                                    // 128 kbps AAC (YouTube itag 140)
            val songs = (0 until 10).map { bytes((90_000 * rate).toInt(), seed = 31 * run + it) }
            val link = Link.WEAK                                 // 400 kbps on mobile data: MediaNet.classify -> WEAK
            val ra = MediaNet.readAheadMs(link, false, 120_000).toLong()
            val pf = if (MediaNet.noPrefetch(link, false, null, 0) == null) MediaNet.prefetchBytes(link, "song") else 0L
            val q = QueueSim(clock, net, songs, rate, ra, pf).run()
            val worst = q.stalls.maxOrNull() ?: 0L
            val laterStarts = q.starts.drop(1)
            val ok = q.finished == 10 && worst <= 3_000 && laterStarts.size == 9 && laterStarts.all { it < 2_000 } && q.songShas.all { it }
            lines += "run ${run + 1}: finished=${q.finished} stalls=${q.stalls.size} worst=${worst}ms starts=${q.starts} losses=${net.losses} resets=${net.resets} prefetch=$pf ok=$ok"
            if (ok) pass++
        }
        println("Spec 1 flaky 3G (strict):\n" + lines.joinToString("\n"))
        assertEquals("strict 5/5:\n" + lines.joinToString("\n"), 5, pass)
    }

    // ------------------------------------------------------------------ 2. airplane mode mid-song

    @Test fun t2_airplaneMidSongCurrentFinishesNextPrefetchedPlays30sNothingHangs_5of5() {
        val lines = ArrayList<String>(); var pass = 0
        for ((run, pct) in listOf(55, 58, 60, 62, 65).withIndex()) {
            val clock = VClock()
            val rate = 40.0                                      // 320 kbps
            val songMs = 240_000L
            val songs = (0 until 3).map { bytes((songMs * rate).toInt(), seed = 7 * run + it) }
            val net = SpecNet(clock, 625.0, 60, lossPct = 0.005, seed = 200L + run)   // home Wi-Fi, 5 Mbps
            val link = Link.WIFI
            val ra = MediaNet.readAheadMs(link, false, 120_000).toLong()   // StreamCache.bufferMs on a 6 GB phone: 30-120 s
            val pf = MediaNet.prefetchBytes(link, "song")
            // Airplane mode at pct % of song 1 (the first song starts after its first chunk, well under a second).
            net.offlineAt = 800 + songMs * pct / 100
            val q = QueueSim(clock, net, songs, rate, ra, pf).run()
            val song2 = q.playedMs[1]
            val banner = NetHonesty.play(0, online = false, stream = true) == NetHonesty.Play.OFFLINE
            val ok = q.playedMs[0] >= songMs - 50 && song2 >= 30_000 && q.pausedAt > 0 && q.maxOfflineStallMs <= NetHonesty.OFFLINE_SKIP_MS + 400 &&
                q.offlineBannerMs >= 30_000 && banner && q.skipped >= 1
            lines += "run ${run + 1}: offline at $pct% song1=${q.playedMs[0] / 1000}s song2=${song2 / 1000}s skipped=${q.skipped} paused=${q.pausedAt / 1000}s " +
                "offlineStallMax=${q.maxOfflineStallMs}ms banner=${q.offlineBannerMs / 1000}s ok=$ok"
            if (ok) pass++
        }
        println("Spec 2 airplane mode (strict):\n" + lines.joinToString("\n"))
        assertEquals("strict 5/5:\n" + lines.joinToString("\n"), 5, pass)
    }

    // ------------------------------------------------------------------ 3. expired url at ~60 %

    private fun fresh(itag: String, n: Int) = listOf(
        StoreFormat("251", "https://rr1.googlevideo.com/videoplayback?itag=251&expire=$n&sig=b$n", "audio/webm"),
        StoreFormat(itag, "https://rr1.googlevideo.com/videoplayback?itag=$itag&expire=$n&sig=a$n", "audio/mp4"))

    @Test fun t3_expiredUrlAt60PercentContinuesSameByteNoRestart_5of5() {
        val lines = ArrayList<String>(); var pass = 0
        for (run in 0 until 5) {
            val clock = VClock()
            val rate = 16.0
            val song = bytes((200_000 * rate).toInt(), seed = 50 + run)
            val net = SpecNet(clock, 120.0, 250, lossPct = 0.01, seed = 300L + run)
            val s = net.Stream(song, "https://rr1.googlevideo.com/videoplayback?itag=140&expire=1&sig=old")
            // The url expires when playback reaches 56-64 % (playback starts within the first second).
            val pct = 56 + 2 * run
            s.expireAt = 600 + 200_000L * pct / 100
            var asked = 0
            val renewer = UrlRenew.Renewer({ _, _, _ -> asked++; clock.advance(600.0); fresh("140", run + 2) }, now = { clock.now() })
            lateinit var rd: MediaNet.NetReader
            rd = reader(clock, s) { renewer.renew("stream:youtube:abc$run", s.url, rd.totalSize)?.let { s.url = it; true } ?: false }
            val run1 = PlayerSim(clock, rate, MediaNet.START_BUFFER_MS.toLong(), 45_000, song.size.toLong()) { rd }.run()
            val ok = run1.finished && run1.sha == sha(song) && run1.readerErrors == 0 && run1.stallMs <= 3_000 && rd.renewals == 1 &&
                asked == 1 && net.refused == 1 && "itag=140" in s.url && s.url != s.original
            lines += "run ${run + 1}: 403 at $pct% renewals=${rd.renewals} asked=$asked refused=${net.refused} playerErrors=${run1.readerErrors} stall=${run1.stallMs}ms same bytes=${run1.sha == sha(song)} ok=$ok"
            if (ok) pass++
        }
        println("Spec 3 expired url (strict):\n" + lines.joinToString("\n"))
        assertEquals("strict 5/5:\n" + lines.joinToString("\n"), 5, pass)
    }

    @Test fun t3b_withoutRenewTheSame403ReachesThePlayer() {
        val clock = VClock()
        val song = bytes(16 * 120_000, seed = 9)
        val net = SpecNet(clock, 120.0, 250)
        // 1.92 MB read straight at 120 B/ms (~16 s): the url expires 60 % of the way.
        val s = net.Stream(song, "https://rr1.googlevideo.com/videoplayback?itag=140&sig=old").apply { expireAt = 9_600 }
        val r = reader(clock, s)          // renew off = 0.45.19
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        r.open(0, -1)
        val err = try { while (true) { val k = r.read(buf, 0, buf.size); if (k < 0) break; out.write(buf, 0, k) }; null } catch (e: IOException) { e }
        assertTrue("a 403 reaches the player without renew", err is HttpErr && err.code == 403)
    }

    // ------------------------------------------------------------------ 5. live search on 3G

    @Test fun t5_liveSearchSkeletonsUnder100msOneInFlightResultsUnder1500ms_5of5() {
        val lines = ArrayList<String>(); var pass = 0
        val word = "blinding lights"
        for (run in 0 until 5) {
            val rnd = java.util.Random(500L + run)
            // Normal typing: 120-260 ms between keys; runs 2 and 4 pause 300-450 ms after "blinding " (a search fires, then is cancelled).
            val keys = ArrayList<Pair<Long, String>>(); var t = 0L
            for (i in 1..word.length) {
                t += 120 + rnd.nextInt(140)
                if ((run == 1 || run == 3) && i == 10) t += 300 + rnd.nextInt(150)
                keys += t to word.take(i)
            }
            // StoreView rules: a key that will search shows skeletons on the next frame (16 ms); the search fires DEBOUNCE_MS
            // after the last key; a new search cancels the one in flight. 3G answer: 300 ms + 12-20 KB at 50 B/ms, 2 % loss.
            var fired = "\u0000"; var skeletonAt = -1L; var triggerAt = -1L
            val inflight = ArrayList<LongRange>(); var cancels = 0; var maxInFlight = 0; var resultAt = -1L
            var pendingFire = -1L; var pendingQ = ""
            var cur: Pair<Long, Long>? = null          // (start, end) of the request in flight
            fun fire(at: Long, q: String) {
                cur?.let { (s0, e0) -> if (e0 > at) { cancels++; inflight += s0 until at } else inflight += s0 until e0 }
                val bytes = 12_000 + rnd.nextInt(8_000)
                val dur = 300 + (bytes / 50.0).toLong() + (if (rnd.nextDouble() < 0.02 * (bytes / 4096)) 300 else 0)
                cur = at to at + dur; fired = LiveSearch.key(q, "")
            }
            for ((at, q) in keys) {
                if (pendingFire in 0..at) { fire(pendingFire, pendingQ); pendingFire = -1 }
                if (LiveSearch.shouldFire(fired, q, "")) {
                    if (skeletonAt < 0) { skeletonAt = at + 16; triggerAt = at }
                    pendingFire = at + LiveSearch.DEBOUNCE_MS; pendingQ = q
                }
            }
            if (pendingFire >= 0) fire(pendingFire, pendingQ)
            cur?.let { (s0, e0) -> inflight += s0 until e0; resultAt = e0 }
            // At most one request in flight at any moment.
            val edges = inflight.flatMap { listOf(it.first to 1, it.last + 1 to -1) }.sortedWith(compareBy({ it.first }, { it.second }))
            var live = 0; edges.forEach { live += it.second; maxInFlight = maxOf(maxInFlight, live) }
            val lastKey = keys.last().first
            val skeleton = skeletonAt - triggerAt
            val after = resultAt - lastKey
            val ok = skeleton < 100 && maxInFlight <= 1 && after < 1_500 && (run != 1 && run != 3 || cancels >= 1)
            lines += "run ${run + 1}: skeleton=${skeleton}ms maxInFlight=$maxInFlight cancels=$cancels results ${after}ms after the last key ok=$ok"
            if (ok) pass++
        }
        // The app side of the model: skeletons on the key, not after the debounce; cancel on a new search.
        val ui = src("mediaapps/MediaUi.kt")
        assertTrue(ui.contains("state.typing = true")); assertTrue(ui.contains("state.job?.cancel()"))
        println("Spec 5 live search (strict, model):\n" + lines.joinToString("\n"))
        assertEquals("strict 5/5:\n" + lines.joinToString("\n"), 5, pass)
    }

    // ------------------------------------------------------------------ 6. search offline

    private fun items(q: String, n: Int) = (1..n).map { StoreItem("youtube", "$q-$it", "$q $it", "A", 200, "3:20", "https://i.ytimg.com/vi/$q$it/mqdefault.jpg") }

    @Test fun t6_searchOfflineSavedResultsThenErrorCardThenRetry_5of5() {
        val lines = ArrayList<String>(); var pass = 0
        val qs = listOf("blinding lights", "lo-fi beats", "amapiano", "zambian gospel", "jazz piano")
        for ((run, q) in qs.withIndex()) {
            // Online: the search lands and is saved (MediaUi SavedSearchStore -> SavedSearches, on disk as JSON).
            var disk = SavedSearches.encode(emptyList())
            val key = RecentSearches.key(q, "", "")
            disk = SavedSearches.encode(SavedSearches.put(SavedSearches.decode(disk), key, items(q, 12), 1_000L * run))
            // ...the app is restarted later with no network (memory cache gone, 10-min TTL long past).
            val pages = SavedSearches.decode(disk)
            val again = SavedSearches.find(pages, key)
            val repeated = again != null && again.items == items(q, 12)
            // A new query offline: nothing saved -> the "No internet" card with Retry, never a blank list.
            val newKey = RecentSearches.key("$q remix", "", "")
            val none = SavedSearches.find(pages, newKey) == null
            val card = NetHonesty.search(loading = false, elapsedMs = 0, hasResults = false, failed = true, online = false)
            val title = NetHonesty.errorTitle(card, "YouTube")
            // Network back, Retry: the search runs and lands (online, results) -> no card.
            val after = NetHonesty.search(loading = false, elapsedMs = 300, hasResults = true, failed = false, online = true)
            val ok = repeated && none && card == NetHonesty.Search.OFFLINE_ERROR && title == "No internet" && after == NetHonesty.Search.NONE
            lines += "run ${run + 1}: \"$q\" saved=${again?.items?.size} newOffline=$card \"$title\" retry->$after ok=$ok"
            if (ok) pass++
        }
        val ui = src("mediaapps/MediaUi.kt")
        assertTrue("offline first: saved results or the card, no 15 s wait", ui.contains("if (!NetWatch.now(ctx))") && ui.contains("SavedSearchStore.find(ctx, kind, cacheKey)"))
        assertTrue("Retry runs the search again", ui.contains("onRetry = { search() }"))
        assertTrue(ui.contains("Offline \\u00b7 saved results"))
        println("Spec 6 search offline (strict):\n" + lines.joinToString("\n"))
        assertEquals("strict 5/5:\n" + lines.joinToString("\n"), 5, pass)
    }

    // ------------------------------------------------------------------ 7. rendition step-down

    @Test fun t7_videoStepsDownAfter2StallsAndPlays5MinWithoutAThird_5of5() {
        val lines = ArrayList<String>(); var pass = 0
        // video+audio bitrates per height (bits/s), progressive picks like VideoSmart.streamFor
        val kbps = mapOf(144 to 180, 240 to 400, 360 to 700, 480 to 1_200, 720 to 2_500)
        for (run in 0 until 5) {
            val clock = VClock()
            val link = 560.0 + 20 * run                      // ~600 kbps throttle
            val net = SpecNet(clock, link / 8.0, 300, lossPct = 0.02, resetPct = 0.001, seed = 700L + run)
            var height = 360
            val window = NetHonesty.StallWindow()
            val stallTimes = ArrayList<Long>(); var stepAt = -1L; var steps = 0
            val totalMs = 8 * 60_000L
            var mediaPos = 0.0                               // ms of video played
            var loadedMs = 0.0
            var playing = false; var started = false; var stallFrom = 0L
            var rd: MediaNet.NetReader? = null
            var data = ByteArray(0)
            fun open(atMs: Double) {
                val rate = kbps[height]!! / 8.0              // bytes per ms
                data = bytes(((totalMs - atMs) * rate).toInt().coerceAtLeast(1), seed = height + run)
                rd = MediaNet.NetReader(net.Stream(data, "https://v/$height"), clock, MediaNet.SHORT_DROP_MS, MediaNet.STREAM_CHUNKS)
                rd!!.open(0, -1)
                loadedMs = atMs
            }
            open(0.0)
            val buf = ByteArray(32 * 1024)
            var last = clock.t
            val ra = MediaNet.readAheadMs(Link.WEAK, false, 50_000).toDouble()
            while (mediaPos < totalMs && clock.now() < 20 * 60_000L) {
                val rate = kbps[height]!! / 8.0
                try {
                    if (loadedMs - mediaPos < ra && loadedMs < totalMs) {
                        val k = rd!!.read(buf, 0, buf.size); if (k > 0) loadedMs += k / rate
                    } else clock.advance(100.0)
                } catch (e: IOException) { clock.advance(1_000.0); open(loadedMs) }
                val dt = clock.t - last; last = clock.t
                if (playing) {
                    val can = loadedMs - mediaPos
                    if (dt <= can) mediaPos += dt else {
                        mediaPos = loadedMs; playing = false; stallFrom = clock.now()
                        stallTimes += clock.now()
                        if (window.stall(clock.now())) {
                            NetHonesty.stepDown(height, kbps.keys.toList())?.let { h -> height = h; steps++; stepAt = clock.now(); open(mediaPos) }
                        }
                    }
                } else {
                    val need = if (started) MediaNet.REBUFFER_MS else 2_000     // spec: video starts at 2 s buffered
                    if (loadedMs - mediaPos >= need || loadedMs >= totalMs) { playing = true; started = true }
                }
            }
            val afterStep = if (stepAt < 0) Int.MAX_VALUE else stallTimes.count { it > stepAt }
            val beforeStep = stallTimes.count { stepAt in 0 until Long.MAX_VALUE && it <= stepAt }
            val minutesAfter = if (stepAt < 0) 0L else (clock.now() - stepAt) / 60_000
            val ok = steps == 1 && height == 240 && beforeStep == 2 && afterStep == 0 && minutesAfter >= 5 && mediaPos >= totalMs
            lines += "run ${run + 1}: link=${link.toInt()}kbps stalls before step=$beforeStep step->${height}p at ${stepAt / 1000}s, stalls after=$afterStep for ${minutesAfter}min ok=$ok"
            if (ok) pass++
        }
        val v = src("mediaapps/VideoActivity.kt")
        assertTrue("the player counts stalls and steps down", v.contains("stalls.stall(") && v.contains("stepDown()") && v.contains("VideoSmart.capped("))
        println("Spec 7 rendition step-down (strict):\n" + lines.joinToString("\n"))
        assertEquals("strict 5/5:\n" + lines.joinToString("\n"), 5, pass)
    }

    // ------------------------------------------------------------------ pure rules

    @Test fun playNotes() {
        assertEquals(NetHonesty.Play.NONE, NetHonesty.play(1_000, true, true))
        assertEquals(NetHonesty.Play.WEAK, NetHonesty.play(1_500, true, true))
        assertEquals(NetHonesty.Play.RETRYING, NetHonesty.play(10_000, true, true))
        assertEquals(NetHonesty.Play.OFFLINE, NetHonesty.play(0, false, true))
        assertEquals(NetHonesty.Play.NONE, NetHonesty.play(20_000, false, stream = false))   // a download never needs a network
        assertEquals("Buffering\u2026 (weak network)", NetHonesty.playText(NetHonesty.Play.WEAK))
        assertEquals("Can't reach the source, retrying", NetHonesty.playText(NetHonesty.Play.RETRYING))
        assertEquals("Offline: playing from saved", NetHonesty.playText(NetHonesty.Play.OFFLINE))
        assertTrue(NetHonesty.retryButton(NetHonesty.Play.RETRYING)); assertFalse(NetHonesty.retryButton(NetHonesty.Play.WEAK))
        assertEquals(NetHonesty.Search.SLOW, NetHonesty.search(true, 8_000, false, false, true))
        assertEquals(NetHonesty.Search.NONE, NetHonesty.search(true, 8_000, true, false, true))
        assertEquals("Couldn't reach YouTube", NetHonesty.errorTitle(NetHonesty.Search.SOURCE_ERROR, "YouTube"))
        assertEquals("Couldn't reach the Store", NetHonesty.errorTitle(NetHonesty.Search.SOURCE_ERROR, null))
    }

    @Test fun offlineQueueOrder() {
        val cached = setOf(0, 3)
        assertEquals(3, NetHonesty.offlineNext(listOf(0, 1, 2, 3, 4), 0) { it in cached })
        assertEquals(-1, NetHonesty.offlineNext(listOf(0, 1, 2, 3, 4), 3) { it in cached })
        assertEquals(0, NetHonesty.offlineNext(listOf(2, 4, 0, 1), 2) { it in cached })   // shuffle order
    }

    @Test fun stallWindowAndStepDown() {
        val w = NetHonesty.StallWindow()
        assertFalse(w.stall(0)); assertFalse(w.stall(61_000)); assertTrue(w.stall(100_000))
        assertEquals(240, NetHonesty.stepDown(360, listOf(144, 240, 360, 720)))
        assertEquals(144, NetHonesty.stepDown(240, listOf(144, 240, 360)))
        assertNull(NetHonesty.stepDown(144, listOf(144, 240)))
    }

    @Test fun sameRenditionOnly() {
        val f = fresh("140", 9)
        assertEquals(f[1].url, UrlRenew.sameRendition("https://x/videoplayback?itag=140&sig=1", -1, f))
        assertNull("a different itag is never swapped in", UrlRenew.sameRendition("https://x/videoplayback?itag=18&sig=1", -1, f))
        val sized = listOf(StoreFormat("a", "https://a/1", "audio/mp4", contentLength = 100), StoreFormat("b", "https://a/2", "audio/mp4", contentLength = 200))
        assertEquals("https://a/2", UrlRenew.sameRendition("https://old/x", 200, sized))
        val named = listOf(StoreFormat("x.mp3", "https://ia.example/dl/x.mp3?t=2", "audio/mpeg"), StoreFormat("y.ogg", "https://ia.example/dl/y.ogg", "audio/ogg"))
        assertEquals("https://ia.example/dl/x.mp3?t=2", UrlRenew.sameRendition("https://ia.example/dl/x.mp3?t=1", -1, named))
        assertEquals(UrlRenew.Key("youtube", "abc", 0), UrlRenew.parseKey("stream:youtube:abc"))
        assertEquals(UrlRenew.Key("youtube", "abc", 1), UrlRenew.parseKey("stream:youtube:abc#1"))
        assertNull(UrlRenew.parseKey("/sdcard/Music/x.mp3"))
        assertEquals(listOf("video", "audio"), UrlRenew.kinds("https://x/v?itag=136&mime=video%2Fmp4", 0))
        assertEquals(listOf("video", "audio"), UrlRenew.kinds("https://x/v?itag=140", 1))
    }

    @Test fun renewerMemoAndBackoff() {
        var t = 0L; var calls = 0
        var answer: List<StoreFormat>? = fresh("140", 1)
        val r = UrlRenew.Renewer({ _, _, _ -> calls++; answer }, now = { t })
        val old = "https://x/videoplayback?itag=140&sig=old"
        val u = r.renew("stream:youtube:a", old, -1)
        assertNotNull(u); assertEquals(1, calls)
        assertEquals("a second open with the old url reuses it", u, r.renew("stream:youtube:a", old, -1)); assertEquals(1, calls)
        answer = null
        assertNull(r.renew("stream:youtube:b", "https://x/videoplayback?itag=140&sig=b", -1)); val after = calls
        assertNull(r.renew("stream:youtube:b", "https://x/videoplayback?itag=140&sig=b", -1)); assertEquals("backs off after a failure", after, calls)
        t += UrlRenew.FAIL_BACKOFF_MS
        answer = fresh("140", 2)
        assertNotNull(r.renew("stream:youtube:b", "https://x/videoplayback?itag=140&sig=b", -1))
    }

    @Test fun renewAtMostTwicePerOpen() {
        val clock = VClock()
        val song = bytes(400_000)
        val net = SpecNet(clock, 100.0, 50)
        val s = net.Stream(song, "https://x/videoplayback?itag=140&sig=old").apply { expireAt = 0 }
        var asked = 0
        // A source that keeps handing out urls that are refused too (forbidFrom checks the original only, so fake it).
        val r = reader(clock, object : MediaNet.RangeSource by s {
            override fun open(pos: Long, len: Long): MediaNet.Opened { clock.advance(50.0); throw HttpErr(403) }
        }) { asked++; true }
        val e = try { r.open(0, -1); null } catch (x: IOException) { x }
        assertTrue(e is HttpErr); assertTrue("bounded: $asked", asked <= UrlRenew.MAX_PER_OPEN)
    }

    @Test fun savedSearchesRoundTripAndCap() {
        var pages = emptyList<SavedSearches.Page>()
        for (i in 0 until 25) pages = SavedSearches.put(pages, "k$i", items("q$i", 40), i.toLong())
        assertEquals(SavedSearches.MAX, pages.size); assertEquals("k24", pages.first().key)
        assertEquals(SavedSearches.ITEMS, pages.first().items.size)
        val back = SavedSearches.decode(SavedSearches.encode(pages))
        assertEquals(pages, back)
        val x = StoreItem("jamendo", "1", "T", "A", extra = mapOf("album" to "Al"), square = true)
        assertEquals(x, SavedSearches.itemOf(SavedSearches.itemJson(x)))
        assertEquals(emptyList<SavedSearches.Page>(), SavedSearches.decode("not json"))
    }

    @Test fun wiring() {
        val sc = src("mediaapps/StreamCache.kt")
        assertTrue(sc.contains("expired = { it is HttpDataSource.InvalidResponseCodeException && UrlRenew.expired(it.responseCode) }"))
        assertTrue(sc.contains("spec = s.withUri(Uri.parse(fresh))"))
        val ms = src("mediaapps/MusicService.kt")
        assertTrue(ms.contains("offlineSkip(true)") && ms.contains("NetHonesty.offlineNext("))
        val ma = src("mediaapps/MusicActivity.kt")
        assertTrue(ma.contains("NetNote(net, online"))
        val v = src("mediaapps/VideoActivity.kt")
        assertTrue(v.contains("NetNote(net, online"))
        assertTrue("stepped-down renditions cache under their own key", v.contains("\"\${t.key}~\${heightCap}p\""))
    }
}
