package com.blaze.assistant.phone

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * 0.45.19 phone tasks: how long a screen read takes, before (0.45.18: a full re-walk whenever any event arrived since
 * the last read) and after (the event-driven cached tree), on synthetic trees shaped like a Samsung Settings page
 * (~300 nodes) and a heavy app (~1500 nodes), with a simulated binder cost per child fetch. Plus act-to-verified for
 * one toggle as a timing model of the code path's waits (glide, ripple, settle, read-back).
 *
 * These are JVM numbers with a MODELLED binder cost, not device numbers: the phone's own accessibility cache and
 * prefetching make many child fetches local, low-end phones are slower. Printed as "BENCH …" lines (they land in the
 * test report's system-out) and recorded in PHONE_TASKS_STATUS.md. The on-device check is listed there.
 */
class ScreenReadBench {
    private fun pct(xs: List<Double>, p: Double): Double { val s = xs.sorted(); return s[((s.size - 1) * p).toInt()] }
    private fun ms(ns: Long) = ns / 1e6

    private data class Row(val tree: String, val ipcUs: Long, val before50: Double, val before95: Double, val after50: Double, val after95: Double,
                           val fetchBefore: Long, val fetchAfter: Long)

    /** One read of the old path: full walk + the text it becomes. */
    private fun oldRead(a: FakeAccess, root: FakeNode): Int {
        val out = ArrayList<Pair<FakeNode, String>>()
        ScreenTree(a).walkFull(root, out)
        return out.joinToString("\n") { it.second }.length
    }

    private fun run(name: String, make: () -> FakeNode, ipcUs: Long, reads: Int = 36): Row {
        val rnd = Random(7)
        // before
        val a0 = FakeAccess(ipcUs * 1000); val r0 = make()
        repeat(3) { oldRead(a0, r0) }   // warm-up
        a0.fetches.set(0)
        val before = (1..reads).map { val t = System.nanoTime(); oldRead(a0, r0); ms(System.nanoTime() - t) }
        val fb = a0.fetches.get() / reads
        // after: between reads, what a phone sends while Blaze works: 1-3 small content changes (a switch row, a clock,
        // a counter), now and then a new window; every FULL_EVERY reads the safety full walk.
        val a1 = FakeAccess(ipcUs * 1000); val r1 = make(); val tree = ScreenTree(a1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            var shown = tree.read(r1, 0, pool).map { it.first }
            a1.fetches.set(0)
            val after = (1..reads).map { i ->
                // events come from views on screen: the changed nodes are ones the read shows
                repeat(1 + rnd.nextInt(3)) {
                    val n = shown[rnd.nextInt(shown.size)]
                    if (n.checkable) n.checked = !n.checked else n.text = if (n.text.isEmpty()) n.text else n.text + "."
                    tree.onContentChanged(n.key)
                }
                val t = System.nanoTime()
                val items = tree.read(r1, i * 2_000L, pool)
                items.joinToString("\n") { it.second }.length
                shown = items.map { it.first }
                ms(System.nanoTime() - t)
            }
            return Row(name, ipcUs, pct(before, .5), pct(before, .95), pct(after, .5), pct(after, .95), fb, a1.fetches.get() / reads)
        } finally { pool.shutdown() }
    }

    @Test fun readTimes() {
        val rows = mutableListOf<Row>()
        for (ipc in listOf(300L, 50L)) {
            rows += run("settings~${FakeTrees.settings().count()}", { FakeTrees.settings() }, ipc)
            rows += run("heavy~${FakeTrees.heavy().count()}", { FakeTrees.heavy() }, ipc)
        }
        for (r in rows) println("BENCH read tree=${r.tree} ipc=${r.ipcUs}us before p50=%.1fms p95=%.1fms fetches=%d | after p50=%.1fms p95=%.1fms fetches=%d"
            .format(r.before50, r.before95, r.fetchBefore, r.after50, r.after95, r.fetchAfter))
        // target: a read under 300 ms p95 even at the pessimistic 0.3 ms per fetch
        assertTrue(rows.all { it.after95 < 300.0 })
        assertTrue("cached reads must be faster at p50", rows.filter { it.ipcUs == 300L }.all { it.after50 < it.before50 })
    }

    /**
     * Act-to-verified for one toggle ("turn on dark mode"), a timing model of the waits on the code path (virtual clock,
     * 2000 seeded trials). Same physical latencies for both; what differs is what the code waits on:
     *  before: find the switch (full read) + glide + ripple + click + first event + settle(1200 ms cap, 250 ms quiet)
     *          + read the old switch node back (stale after a theme change: "couldn't read the switch back");
     *  after:  find the switch (cached read) + glide + ripple + click + first event + poll the system state on events /
     *          every 100 ms until it reads the wanted value (no settle).
     * Latency assumptions (not measured on the phone): glide 120-420 ms (Pointer.glideMs), ripple 240 ms, first event
     * 16-120 ms, screen animation after the tap 150-1000 ms (a theme change recreates Settings), system state flips
     * 30-400 ms after the click.
     */
    @Test fun actToVerified() {
        val rnd = Random(11)
        val readBefore = 25.0; val readAfter = 2.0   // the Settings read p50 at 0.3 ms/fetch, rounded (readTimes prints the real ones)
        val before = ArrayList<Double>(); val after = ArrayList<Double>()
        repeat(2000) {
            val glide = 120 + rnd.nextDouble() * 300; val ripple = 240.0; val ev = 16 + rnd.nextDouble() * 104
            val anim = 150 + rnd.nextDouble() * 850; val stateAt = 30 + rnd.nextDouble() * 370
            val settle = minOf(1200.0, 60 + anim + 250)
            before += readBefore + glide + ripple + ev + settle + 1
            val poll = kotlin.math.ceil((stateAt - ev).coerceAtLeast(0.0) / 100.0) * 100
            after += readAfter + glide + ripple + ev + poll + 1
        }
        println("BENCH act-to-verified model before p50=%.0fms p95=%.0fms | after p50=%.0fms p95=%.0fms"
            .format(pct(before, .5), pct(before, .95), pct(after, .5), pct(after, .95)))
        assertTrue(pct(after, .95) < 1500.0)
        assertTrue(pct(after, .5) < pct(before, .5))
    }
}
