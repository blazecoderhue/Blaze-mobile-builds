package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.StoreItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.45.19 step 2: live search (250 ms debounce, results land per source, per-card skeletons, recent searches). */
class MediaNetSearchTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    private fun item(src: String, id: String, title: String = "t$id", thumb: String = "https://img/$id.jpg") =
        StoreItem(source = src, id = id, title = title, author = "", thumb = thumb)

    @Test fun debounceIs250msAndFiresOnceForAWord() { runBlocking { debounceRun() } }

    private suspend fun kotlinx.coroutines.CoroutineScope.debounceRun() {
        assertEquals(250L, LiveSearch.DEBOUNCE_MS)
        // The StoreView effect: keyed on the query, so each key restarts the wait (LaunchedEffect semantics).
        val fired = ArrayList<Pair<String, Long>>()
        var job: Job? = null
        var last = ""
        val t0 = System.nanoTime()
        fun key(q: String) {
            job?.cancel()
            job = launch {
                if (!LiveSearch.shouldFire(last, q, "")) return@launch
                delay(LiveSearch.DEBOUNCE_MS)
                last = LiveSearch.key(q, ""); fired += q to (System.nanoTime() - t0) / 1_000_000
            }
        }
        var lastKeyAt = 0L
        for (q in listOf("l", "lo", "lo-", "lo-f", "lo-fi")) { key(q); lastKeyAt = (System.nanoTime() - t0) / 1_000_000; delay(20) }
        delay(600)
        assertEquals(listOf("lo-fi"), fired.map { it.first })
        assertTrue("waited the debounce after the last key", fired[0].second - lastKeyAt >= LiveSearch.DEBOUNCE_MS)
        key("lo-fi "); delay(400)
        assertEquals("same words don't search again", 1, fired.size)
        job?.cancel()
    }

    @Test fun firstResultsLandWithoutWaitingForTheSlowestSource() {
        // Answers arrive at 600 ms (archive), 1200 ms (jamendo), 4000 ms (youtube).
        val a = (1..4).map { item("archive", "a$it") }; val j = (1..3).map { item("jamendo", "j$it") }; val y = (1..5).map { item("youtube", "y$it") }
        val slots = arrayOfNulls<List<StoreItem>>(3)
        var shown = emptyList<StoreItem>()
        val timeline = ArrayList<Pair<Long, Int>>()
        for ((at, i, l) in listOf(Triple(600L, 0, a), Triple(1200L, 1, j), Triple(4000L, 2, y))) {
            val before = shown
            slots[i] = l
            shown = LiveSearch.land(shown, ForYou.mix(slots.map { it.orEmpty() }))
            assertEquals("cards on screen keep their place", before, shown.take(before.size))
            timeline += at to shown.size
        }
        assertEquals(600L to 4, timeline[0])          // 0.45.18 showed nothing until 4000 ms
        assertEquals(12, shown.size); assertEquals(12, shown.map { it.source + it.id }.toSet().size)
        assertEquals(shown.toSet(), ForYou.mix(listOf(a, j, y)).toSet())
    }

    @Test fun perCardSkeletonStates() {
        assertEquals(LiveSearch.Card.SKELETON, LiveSearch.card("", "https://img/1.jpg", true))
        assertEquals(LiveSearch.Card.SKELETON, LiveSearch.card("  ", "", false))
        assertEquals(LiveSearch.Card.PARTIAL, LiveSearch.card("Song", "https://img/1.jpg", true))
        assertEquals(LiveSearch.Card.READY, LiveSearch.card("Song", "https://img/1.jpg", false))   // loaded or failed: stop pulsing
        assertEquals(LiveSearch.Card.READY, LiveSearch.card("Song", "", true))                     // no picture to wait for
        assertEquals(2, LiveSearch.trailing(1, false).size); assertEquals(3, LiveSearch.trailing(2, true).size)
        assertTrue(LiveSearch.trailing(0, true).isEmpty())
        assertEquals(6, LiveSearch.skeletons(false).size); assertEquals(9, LiveSearch.skeletons(true).size)
    }

    @Test fun recentSearchesList() {
        var l = emptyList<String>()
        for (q in listOf("lo-fi", "Amapiano ", "jazz", "LO-FI", "x", "https://youtu.be/abc", "  burna   boy ")) l = RecentSearches.add(l, q)
        assertEquals(listOf("burna boy", "LO-FI", "jazz", "Amapiano"), l)
        for (i in 1..20) l = RecentSearches.add(l, "q$i")
        assertEquals(RecentSearches.MAX, l.size); assertEquals("q20", l.first())
        assertEquals(l, RecentSearches.decode(RecentSearches.encode(l)))
        assertTrue(RecentSearches.decode("").isEmpty())
    }

    @Test fun recentResultsCache() {
        var now = 0L
        val c = RecentSearches.Results(max = 3, ttlMs = 10_000, now = { now })
        val k = RecentSearches.key("lo-fi", "", "")
        c.put(k, listOf(item("jamendo", "1")), next = "p2", pageSource = "jamendo", pageQuery = "lo-fi")
        assertEquals("p2", c.get(RecentSearches.key("LO-FI ", "", ""))?.next)          // same words, any case
        assertNull(c.get(RecentSearches.key("lo-fi", "Jazz", "")))                      // another chip
        assertNull(c.get(RecentSearches.key("lo-fi", "", "archive")))                   // another source
        c.put(RecentSearches.key("empty", "", ""), emptyList())
        assertNull("empty answers aren't cached", c.get(RecentSearches.key("empty", "", "")))
        now = 10_001
        assertNull("expired after the TTL", c.get(k))
        now = 0
        for (i in 1..4) c.put("k$i", listOf(item("a", "$i")))
        assertEquals(3, c.size()); assertNull("LRU dropped the oldest", c.get("k1"))
    }

    @Test fun wiring() {
        val s = src("mediaapps/MediaUi.kt")
        assertTrue("cache hit shows results at once", "RecentSearches.results(kind).get(cacheKey)" in s && "RecentSearches.results(kind).put(cacheKey" in s)
        assertTrue("answers land per source", "state.results = LiveSearch.land(state.results, ForYou.mix(slots.map { it.orEmpty() }))" in s)
        assertTrue("per-card loading state", "ResultCard(r)" in s && "LiveSearch.card(r.title, r.thumb, thumb.loading)" in s)
        assertTrue("skeleton row for pending sources", "LiveSearch.trailing(state.pending, square)" in s)
        assertTrue("recent searches remembered on Enter and on a tap", s.split("RecentSearches.remember(ctx, kind").size - 1 >= 3)
        assertTrue("debounced effect keyed on the query", "LaunchedEffect(state.query)" in s && "delay(LiveSearch.DEBOUNCE_MS)" in s)
    }
}
