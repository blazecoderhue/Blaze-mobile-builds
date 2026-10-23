package com.blaze.assistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.random.Random

/** 0.45.19: the event-driven cached tree gives exactly what a full walk gives, while touching only what changed. */
class ScreenTreeTest {
    private fun legacy(a: FakeAccess, root: FakeNode): List<String> =
        ArrayList<Pair<FakeNode, String>>().also { ScreenTree(a).walkFull(root, it) }.map { it.second }

    @Test fun coldReadEqualsFullWalk() {
        for (root in listOf(FakeTrees.settings(), FakeTrees.heavy())) {
            val a = FakeAccess()
            val t = ScreenTree(a)
            assertEquals(legacy(a, root), t.read(root, 0).map { it.second })
            val p = ScreenTree(a); val pool = Executors.newFixedThreadPool(4)
            try { assertEquals("parallel cold walk keeps the order", legacy(a, root), p.read(root, 0, pool).map { it.second }) } finally { pool.shutdown() }
        }
    }

    @Test fun cleanReadTouchesNothing() {
        val root = FakeTrees.settings(); val a = FakeAccess(); val t = ScreenTree(a)
        t.read(root, 0)
        val before = a.fetches.get()
        t.read(root, 10)
        assertEquals(before, a.fetches.get())
        assertEquals(1, t.partialWalks)
    }

    @Test fun contentChangeReWalksOnlyThatSubtree() {
        val root = FakeTrees.settings(); val a = FakeAccess(); val t = ScreenTree(a)
        t.read(root, 0)
        val sw = root.all().first { it.checkable }
        sw.checked = !sw.checked
        t.onContentChanged(sw.key)
        val before = a.fetches.get()
        val got = t.read(root, 10).map { it.second }
        assertEquals(legacy(a, root), got)
        val used = a.fetches.get() - before
        assertTrue("touched $used fetches for one switch", used in 1..20)
    }

    @Test fun randomEditsStayIdenticalToFullWalk() {
        // 5 seeds x 60 edits: text changes, switch flips, rows added / removed / hidden, window changes, unknown sources
        for (seed in 1..5) {
            val rnd = Random(seed)
            val root = if (seed % 2 == 0) FakeTrees.heavy() else FakeTrees.settings()
            val a = FakeAccess(); val t = ScreenTree(a)
            var nextKey = 9_000_000L + seed * 100_000L
            t.read(root, 0)
            for (step in 1..60) {
                val nodes = root.all()
                val n = nodes[rnd.nextInt(nodes.size)]
                when (rnd.nextInt(7)) {
                    0 -> { n.text = "edit $step"; t.onContentChanged(n.key) }
                    1 -> { n.checkable = true; n.checked = !n.checked; t.onContentChanged(n.key) }
                    2 -> { n.add(FakeNode(nextKey++, "android.widget.TextView", text = "new $step")); t.onContentChanged(n.key) }
                    3 -> { val p = n.parent; if (p != null) { p.children.remove(n); t.onContentChanged(p.key) } }
                    4 -> { n.visible = !n.visible; t.onContentChanged((n.parent ?: n).key) }
                    5 -> { n.desc = "d$step"; if (rnd.nextBoolean()) t.onWindowChanged() else t.onContentChanged(null) }
                    else -> { }
                }
                assertEquals("seed $seed step $step", legacy(a, root), t.read(root, step * 1000L).map { it.second })
            }
        }
    }

    @Test fun safetyFullWalkByAgeAndCount() {
        val root = FakeTrees.settings(); val a = FakeAccess(); val t = ScreenTree(a)
        t.read(root, 0)
        t.read(root, ScreenTree.MAX_AGE_MS + 1)
        assertEquals(2, t.fullWalks)
        repeat(ScreenTree.FULL_EVERY + 1) { t.read(root, ScreenTree.MAX_AGE_MS + 2) }
        assertEquals(3, t.fullWalks)
    }

    @Test fun tooManyEventsMeansFullWalk() {
        val root = FakeTrees.settings(); val a = FakeAccess(); val t = ScreenTree(a)
        t.read(root, 0)
        val keys = root.all().map { it.key }
        repeat(ScreenTree.MAX_TRACKED + 2) { t.onContentChanged(keys[it % keys.size]) }
        t.read(root, 1)
        assertEquals(2, t.fullWalks)
    }

    @Test fun itemCapLikeBefore() {
        val root = FakeTrees.heavy(); val a = FakeAccess()
        val got = ScreenTree(a).read(root, 0)
        assertEquals(ScreenTree.MAX_ITEMS, got.size)
        assertEquals(legacy(a, root).size, got.size)
    }
}
