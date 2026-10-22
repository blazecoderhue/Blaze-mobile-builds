package com.blaze.assistant.ui

import com.blaze.assistant.agent.Mind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The memory graph's model (v0.19.0): links, caps, a layout that settles and stops, hit testing (JVM, no Android). */
class MemGraphTest {
    private fun m(id: Long, kind: String, subject: String, text: String, related: String = "", turn: Long = 0, created: Long = id) =
        Mind.Mem(id, kind, subject, text, related, turn, created, created)

    @Test fun linksBySubjectRelatedMentionAndTurn() {
        val mems = listOf(
            m(1, "person", "Gloria", "Gloria is the user's cousin"),
            m(2, "person", "Gloria", "Birthday on 3 March"),
            m(3, "project", "HomeGlow store", "Shopify store selling lamps", related = "Gloria"),
            m(4, "decision", "Lamp prices", "Moon lamp at 24.99 for the homeglow store launch"),
            m(5, "fact", "Exam", "Data structures exam on Friday", turn = 9),
            m(6, "preference", "Study", "Studies at night before exams", turn = 9),
            m(7, "fact", "Weather", "Nothing to do with anything"))
        val e = MemGraph.links(mems).associateBy { it.a to it.b }
        assertEquals(3, e[0 to 1]!!.w)                          // same subject
        assertTrue(e[0 to 2]!!.why.contains("related"))          // 3 names Gloria in related
        assertTrue(e[2 to 3]!!.why.contains("mentioned"))        // "homeglow store" appears in 4's text
        assertTrue(e[4 to 5]!!.why.contains("same chat"))        // same source turn
        assertTrue(e.keys.none { it.first == 6 || it.second == 6 })  // unrelated memory stays alone
        assertTrue(e.keys.all { it.first < it.second })
    }

    @Test fun busySubjectsAreCapped() {
        val mems = (1L..30L).map { m(it, "person", "Mutale", "Fact number $it about Mutale") }
        val g = MemGraph(mems)
        assertTrue(g.nodes.all { it.deg <= MemGraph.MAX_DEG })
        assertTrue(g.edges.isNotEmpty())
    }

    @Test fun keepsTheNewestNodesOnly() {
        val mems = (1L..450L).map { m(it, "fact", "S$it", "Line $it", created = it) }
        val g = MemGraph(mems)
        assertEquals(MemGraph.MAX_NODES, g.nodes.size)
        assertEquals(50, g.clipped)
        assertEquals(51L, g.nodes.minOf { it.mem.created })
        assertEquals(450, g.nodes.maxOf { it.mem.created }.toInt())
    }

    @Test fun settlesStopsAndGroupsKinds() {
        val kinds = Mind.KINDS
        val mems = (1L..120L).map { val k = kinds[(it % kinds.size).toInt()]; m(it, k, "$k ${it % 7}", "about $k ${it % 7}") }
        val g = MemGraph(mems)
        val t0 = System.nanoTime()
        val ticks = g.settle()
        assertTrue("settle took ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) < 5_000_000_000L)
        assertTrue(ticks <= MemGraph.SETTLE_TICKS)
        assertEquals(0f, g.alpha)
        assertTrue(g.nodes.all { it.x.isFinite() && it.y.isFinite() })
        // Each kind sits nearer its own anchor than the others, on average.
        g.cats.indices.forEach { k ->
            val own = g.nodes.filter { it.cat == k }
            val a = g.anchors[k]
            val dOwn = own.map { hypot((it.x - a.x).toDouble(), (it.y - a.y).toDouble()) }.average()
            val dOther = g.anchors.indices.filter { it != k }.map { j -> own.map { hypot((it.x - g.anchors[j].x).toDouble(), (it.y - g.anchors[j].y).toDouble()) }.average() }.min()
            assertTrue("kind ${g.cats[k]}: $dOwn vs $dOther", dOwn < dOther)
        }
        // After settling, a tick moves nothing much (nothing to simulate per frame).
        val before = g.nodes.map { it.x to it.y }
        g.tick()
        assertTrue(g.nodes.zip(before).all { (n, b) -> hypot((n.x - b.first).toDouble(), (n.y - b.second).toDouble()) < 1.0 })
    }

    @Test fun hitFindsTheNearestNodeWithinReach() {
        val g = MemGraph(listOf(m(1, "fact", "A", "a"), m(2, "fact", "B", "b")))
        g.settle()
        val n = g.nodes[1]
        assertEquals(1, g.hit(n.x + 1f, n.y - 1f, 10f))
        assertEquals(-1, g.hit(n.x + 5000f, n.y, 10f))
    }

    @Test fun emptyAndSingleAreSafe() {
        val e = MemGraph(emptyList()); e.settle()
        assertEquals(0, e.nodes.size); assertEquals(-1, e.newest)
        val one = MemGraph(listOf(m(5, "profile", "You", "Lives in Ndola"))); one.settle()
        assertEquals(0, one.newest); assertEquals(1, one.cats.size)
    }
}
