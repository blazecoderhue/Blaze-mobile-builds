package com.blaze.engine

import com.blaze.engine.manifest.LaunchMode
import com.blaze.engine.route.GuestProcesses
import com.blaze.engine.route.IdentityRewriter
import com.blaze.engine.route.LaunchEnvelope
import com.blaze.engine.route.ProcessKey
import com.blaze.engine.route.ProcessTable
import com.blaze.engine.route.SlotAllocator
import com.blaze.engine.route.SlotExhaustedException
import com.blaze.engine.route.SlotId
import com.blaze.engine.route.SlotKind
import com.blaze.engine.route.SlotPlan
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertNull
import org.testng.Assert.assertSame
import org.testng.Assert.assertTrue
import org.testng.Assert.fail
import org.testng.annotations.Test

/** A3 (routing): slot names, leasing per launch mode, process table, envelope, identity rewrite. */
class RouteTest {
    private var now = 0L
    private val clock = { ++now }

    @Test
    fun slotNamesRoundTrip() {
        val ids = listOf(
            SlotId(0, SlotKind.ACTIVITY, LaunchMode.STANDARD, 11),
            SlotId(7, SlotKind.ACTIVITY_TRANSLUCENT, LaunchMode.SINGLE_TASK, 0),
            SlotId(3, SlotKind.SERVICE, LaunchMode.STANDARD, 5),
            SlotId(3, SlotKind.SERVICE_FG, LaunchMode.STANDARD, 2),
            SlotId(2, SlotKind.PROVIDER, LaunchMode.STANDARD, 0),
            SlotId(2, SlotKind.RECEIVER, LaunchMode.STANDARD, 0),
        )
        for (id in ids) assertEquals(SlotId.parse(id.className), id, id.className)
        assertEquals(ids[0].className, "com.blaze.engine.slot.A0_0_11")
        assertEquals(ids[0].processName, "com.blaze.assistant:g0")
        assertNull(SlotId.parse("com.blaze.assistant.MainActivity"))
        assertNull(SlotId.parse("com.blaze.engine.slot.A0_0"))
        try { GuestProcesses.name(GuestProcesses.COUNT); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun standardTakesNewSlotSingleTaskReuses() {
        val a = SlotAllocator(0, clock)
        val s1 = a.leaseActivity("org.example.calc", "org.example.calc.Main", LaunchMode.STANDARD, "t1")
        val s2 = a.leaseActivity("org.example.calc", "org.example.calc.Main", LaunchMode.STANDARD, "t1")
        assertNotEquals(s1.slot, s2.slot)
        val t1 = a.leaseActivity("org.example.calc", "org.example.calc.Home", LaunchMode.SINGLE_TASK, "t1")
        val t2 = a.leaseActivity("org.example.calc", "org.example.calc.Home", LaunchMode.SINGLE_TASK, "t2")
        assertSame(t1, t2)
        assertEquals(t1.slot.launchMode, LaunchMode.SINGLE_TASK)
        assertEquals(a.active.size, 3)
        a.release(s1.slot)
        assertEquals(a.active.size, 2)
        a.releaseAll("org.example.calc")
        assertTrue(a.active.isEmpty())
    }

    @Test
    fun singleTopReusesOnlyInSameTask() {
        val a = SlotAllocator(1, clock)
        val x = a.leaseActivity("p.q", "p.q.Top", LaunchMode.SINGLE_TOP, "t1")
        assertSame(a.leaseActivity("p.q", "p.q.Top", LaunchMode.SINGLE_TOP, "t1"), x)
        assertNotEquals(a.leaseActivity("p.q", "p.q.Top", LaunchMode.SINGLE_TOP, "t2").slot, x.slot)
    }

    @Test
    fun standardPoolRecyclesOldestWhenFull() {
        val a = SlotAllocator(0, clock)
        val n = SlotPlan.activityCount(LaunchMode.STANDARD)
        val first = a.leaseActivity("p.q", "p.q.A0", LaunchMode.STANDARD, null)
        repeat(n - 1) { a.leaseActivity("p.q", "p.q.A${it + 1}", LaunchMode.STANDARD, null) }
        val extra = a.leaseActivity("p.q", "p.q.Extra", LaunchMode.STANDARD, null)
        assertEquals(extra.slot, first.slot)
        assertEquals(a.leaseOf(first.slot)?.guestClass, "p.q.Extra")
        assertEquals(a.active.size, n)
    }

    @Test
    fun singleInstancePoolExhausts() {
        val a = SlotAllocator(0, clock)
        repeat(SlotPlan.activityCount(LaunchMode.SINGLE_INSTANCE)) { a.leaseActivity("p.q", "p.q.I$it", LaunchMode.SINGLE_INSTANCE, null) }
        try { a.leaseActivity("p.q", "p.q.More", LaunchMode.SINGLE_INSTANCE, null); fail("should be exhausted") } catch (e: SlotExhaustedException) { }
    }

    @Test
    fun servicesReuseTheirSlot() {
        val a = SlotAllocator(0, clock)
        val s = a.leaseService("p.q", "p.q.Sync")
        assertSame(a.leaseService("p.q", "p.q.Sync"), s)
        assertEquals(a.leaseService("p.q", "p.q.Player", foreground = true).slot.kind, SlotKind.SERVICE_FG)
    }

    @Test
    fun processTableAssignsAndEvictsLru() {
        val t = ProcessTable(size = 2, clock = clock)
        val a = t.assign(ProcessKey("a.a", "a.a"))!!
        val b = t.assign(ProcessKey("b.b", "b.b"))!!
        assertNotEquals(a.index, b.index)
        assertEquals(t.assign(ProcessKey("a.a", "a.a"))!!.index, a.index) // same package, same process
        t.markRunning(a.index, true)
        val c = t.assign(ProcessKey("c.c", "c.c"))!!
        assertEquals(c.index, b.index) // b was not running
        assertEquals(c.evicted, ProcessKey("b.b", "b.b"))
        t.markRunning(c.index, true)
        assertNull(t.assign(ProcessKey("d.d", "d.d"))) // all busy
        t.onProcessDied(a.index)
        assertEquals(t.assign(ProcessKey("d.d", "d.d"))!!.evicted, ProcessKey("a.a", "a.a"))
        t.releasePackage("c.c")
        assertNull(t.ownerOf(c.index))
    }

    @Test
    fun envelopeRoundTripAndValidation() {
        val e = LaunchEnvelope("org.example.calc", "org.example.calc.Main", 0, "com.blaze.engine.slot.A0_0_3", "0123456789abcdef0123", "t1", 42)
        assertEquals(LaunchEnvelope.fromMap(e.toMap()), e)
        val m = e.toMap()
        assertNull(LaunchEnvelope.fromMap(m + (LaunchEnvelope.K_VERSION to "0")))
        assertNull(LaunchEnvelope.fromMap(m + (LaunchEnvelope.K_PKG to "../evil")))
        assertNull(LaunchEnvelope.fromMap(m + (LaunchEnvelope.K_SLOT to "com.blaze.assistant.MainActivity")))
        assertNull(LaunchEnvelope.fromMap(m + (LaunchEnvelope.K_TOKEN to "short")))
        assertNull(LaunchEnvelope.fromMap(m - LaunchEnvelope.K_CLASS))
    }

    @Test
    fun identityRewrite() {
        val rw = IdentityRewriter("com.blaze.assistant", { setOf("org.example.calc") }) { pkg, cls ->
            if (pkg == "org.example.calc" && cls == "org.example.calc.Main") "com.blaze.engine.slot.A0_0_0" else null
        }
        val args = arrayOf<Any?>("org.example.calc", 5, null, "com.android.chrome", Holder("org.example.calc"))
        val adapter = object : IdentityRewriter.ArgAdapter {
            override fun accepts(value: Any) = value is Holder
            override fun rewrite(value: Any, rw: IdentityRewriter): Any {
                val h = value as Holder
                val out = rw.outboundPackage(h.pkg)
                return if (out == h.pkg) h else Holder(out!!)
            }
        }
        assertEquals(rw.rewriteArgs(args, listOf(adapter)), 2)
        assertEquals(args[0], "com.blaze.assistant")
        assertEquals(args[3], "com.android.chrome")
        assertEquals(args[4], Holder("com.blaze.assistant"))
        assertEquals(rw.outboundComponent("org.example.calc", "org.example.calc.Main"), "com.blaze.assistant" to "com.blaze.engine.slot.A0_0_0")
        assertEquals(rw.outboundComponent("com.android.chrome", "X"), "com.android.chrome" to "X")
        assertEquals(rw.rewriteArgs(null), 0)
    }

    data class Holder(val pkg: String)
}
