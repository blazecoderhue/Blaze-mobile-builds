package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.5 hotfix: "java.lang.String cannot be cast to java.lang.Boolean" on every turn (the shared "followups" key). */
class PrefCoerceTest {
    private val list = """[{"id":"Fabc","pkg":"com.whatsapp","app":"WhatsApp","conv":"Cyp home","person":"Stefano","topic":"Thursday 9:00","status":"ASKED"}]"""

    @Test fun boolReadsEveryStoredShape() {
        assertTrue(PrefCoerce.bool(true, false)); assertFalse(PrefCoerce.bool(false, true))
        for (s in listOf("true", "TRUE", " 1 ", "yes", "on")) assertTrue(s, PrefCoerce.bool(s, false))
        for (s in listOf("false", "0", "no", "off")) assertFalse(s, PrefCoerce.bool(s, true))
        assertTrue("junk keeps the default", PrefCoerce.bool(list, true))
        assertFalse(PrefCoerce.bool(null, false)); assertTrue(PrefCoerce.bool(1, false)); assertFalse(PrefCoerce.bool(0L, true))
        assertNull(PrefCoerce.parseBool("[]")); assertEquals(true, PrefCoerce.parseBool("1"))
    }

    @Test fun strReadsBooleansAndNumbersToo() {
        assertEquals("true", PrefCoerce.str(true, "")); assertEquals("3", PrefCoerce.str(3, "")); assertEquals("x", PrefCoerce.str(null, "x"))
        assertEquals("[]", PrefCoerce.str("[]", "x"))
    }

    @Test fun theTrackersListMovesOffTheSwitchKey() {
        val ops = PrefCoerce.migrate(mapOf("followups" to list, "user_name" to "Luckson", "fast_lane" to true))
        assertTrue(ops.contains(PrefCoerce.Op.PutStr(PrefCoerce.FOLLOWUP_ITEMS, list)))
        assertTrue(ops.contains(PrefCoerce.Op.Remove("followups")))
        assertEquals("nothing else is touched", 2, ops.size)
    }

    @Test fun anExistingTrackerListIsKept() {
        val ops = PrefCoerce.migrate(mapOf("followups" to "[]", PrefCoerce.FOLLOWUP_ITEMS to list))
        assertEquals(listOf(PrefCoerce.Op.Remove("followups")), ops)
    }

    @Test fun booleanKeysStoredAsStringsBecomeBooleans() {
        val ops = PrefCoerce.migrate(mapOf("stream" to "true", "show_route" to "0", "low_data" to "garbage", "followups" to "false",
            "persona" to "blaze", "fast_lane" to false))
        assertTrue(ops.contains(PrefCoerce.Op.PutBool("stream", true)))
        assertTrue(ops.contains(PrefCoerce.Op.PutBool("show_route", false)))
        assertTrue(ops.contains(PrefCoerce.Op.PutBool("followups", false)))
        assertTrue(ops.contains(PrefCoerce.Op.Remove("low_data")))
        assertFalse("string prefs stay strings", ops.any { (it as? PrefCoerce.Op.PutBool)?.key == "persona" || (it as? PrefCoerce.Op.Remove)?.key == "persona" })
        assertEquals(4, ops.size)
        assertTrue("already right: nothing to do", PrefCoerce.migrate(mapOf("stream" to true, "persona" to "calm")).isEmpty())
    }

    @Test fun syncedSettingsAreWrittenWithThisPhonesType() {
        assertEquals(true, PrefCoerce.forSync("show_route", true, "true"))
        assertEquals(false, PrefCoerce.forSync("show_route", null, "0"))
        assertSame("a list for a switch is refused", PrefCoerce.SKIP, PrefCoerce.forSync("followups", true, list))
        assertEquals(true, PrefCoerce.forSync("some_new_switch", false, true))
        assertEquals("true", PrefCoerce.forSync("persona", "blaze", true))
        assertEquals("calm", PrefCoerce.forSync("persona", "blaze", "calm"))
        assertNull("delete stays a delete", PrefCoerce.forSync("stream", true, null))
    }
}
