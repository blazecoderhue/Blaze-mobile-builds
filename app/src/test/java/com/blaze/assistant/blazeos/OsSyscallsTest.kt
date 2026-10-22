package com.blaze.assistant.blazeos

import com.blaze.assistant.blazeos.OsSyscalls.Gate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.36.0 BlazeOS native apps: the syscall table and the permission gate in front of every call. */
class OsSyscallsTest {
    private val A = OsGrants.Decision.ALLOWED; private val D = OsGrants.Decision.DENIED; private val Q = OsGrants.Decision.ASK
    private fun call(n: String) = OsSyscalls.get(n)!!

    @Test fun callsWithoutAPermissionAlwaysRun() {
        for (n in listOf("clear", "rect", "circle", "line", "text", "image", "width", "height", "theme_dark", "theme_accent", "font_scale", "now_ms", "uptime_ms", "random", "log"))
            for (d in listOf(A, D, Q)) assertEquals(n, Gate.ALLOW, OsSyscalls.gate(call(n), emptyList(), d))
    }

    @Test fun storageNeedsTheManifestAndIsRefusedWhenTurnedOff() {
        for (n in listOf("store_get", "store_set", "store_remove")) {
            assertEquals(Gate.NOT_DECLARED, OsSyscalls.gate(call(n), listOf("notifications"), A))
            assertEquals(Gate.ALLOW, OsSyscalls.gate(call(n), listOf("storage"), Q))     // its own store: no prompt
            assertEquals(Gate.ALLOW, OsSyscalls.gate(call(n), listOf("storage"), A))
            assertEquals(Gate.DENIED, OsSyscalls.gate(call(n), listOf("storage"), D))
        }
    }

    @Test fun notificationsAskTheFirstTime() {
        val n = call("notify")
        assertEquals(Gate.NOT_DECLARED, OsSyscalls.gate(n, listOf("storage"), A))
        assertEquals(Gate.ASK, OsSyscalls.gate(n, listOf("notifications"), Q))
        assertEquals(Gate.ALLOW, OsSyscalls.gate(n, listOf("notifications"), A))
        assertEquals(Gate.DENIED, OsSyscalls.gate(n, listOf("notifications"), D))
    }

    @Test fun refusalCodes() {
        assertEquals(OsSys.OK, OsSyscalls.code(Gate.ALLOW))
        assertEquals(OsSys.ASKING, OsSyscalls.code(Gate.ASK))
        assertEquals(OsSys.NOT_DECLARED, OsSyscalls.code(Gate.NOT_DECLARED))
        assertEquals(OsSys.DENIED, OsSyscalls.code(Gate.DENIED))
    }

    @Test fun everyGatedCallNeedsARealBlazeOsPermission() {
        val gated = OsSyscalls.all.filter { it.perm != null }
        assertTrue(gated.isNotEmpty())
        gated.forEach { assertTrue(it.name, it.perm in OsPerms.all) }
        assertEquals(OsSyscalls.all.size, OsSyscalls.all.map { it.name }.toSet().size)
    }

    @Test fun importsAreCheckedByNameAndSignature() {
        val I = WType.I32; val F = WType.F32
        assertTrue(OsSyscalls.unknownImports(listOf(Triple("clear", listOf(I), emptyList()))).isEmpty())
        assertEquals(listOf("open_socket (no such call)"), OsSyscalls.unknownImports(listOf(Triple("open_socket", listOf(I), listOf(I)))))
        assertEquals(listOf("clear (wrong signature)"), OsSyscalls.unknownImports(listOf(Triple("clear", listOf(F), emptyList()))))
    }

    @Test fun keysAndSwipes() {
        assertEquals(37, OsSyscalls.keyCode(21)); assertEquals(38, OsSyscalls.keyCode(19))
        assertEquals(39, OsSyscalls.keyCode(22)); assertEquals(40, OsSyscalls.keyCode(20))
        assertEquals(13, OsSyscalls.keyCode(66)); assertEquals(65, OsSyscalls.keyCode(29)); assertEquals(48, OsSyscalls.keyCode(7))
        assertEquals(0, OsSyscalls.keyCode(9999))
        assertNull(OsSyscalls.swipe(5f, 10f))
        assertNotNull(OsSyscalls.swipe(-40f, 3f))
    }
}
