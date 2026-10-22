package com.blaze.assistant.notify

import com.blaze.assistant.notify.MoneyRadar.Parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** v0.33.0 Receipt Vault / Bill Radar / Bundle Guardian parsing. */
class MoneyRadarTest {
    private fun at(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { set(y, m - 1, d, 10, 0, 0) }.timeInMillis

    @Test fun parsesMobileMoney() {
        val t = Parse.tx("You have sent K250.00 to John Banda 0971234567. Your new balance is K1,034.50. Txn ID: MP231018.1234.A1", 1L, "Airtel Money")!!
        assertEquals("sent", t.kind); assertEquals(250.0, t.amount, 0.001); assertEquals(1034.5, t.balance!!, 0.001)
        assertTrue(t.who.startsWith("John Banda")); assertEquals("MP231018.1234.A1", t.ref)
        val r = Parse.tx("You have received ZMW 1,200.00 from MARY PHIRI. Bal: ZMW 2,234.50", 1L, "MoMo")!!
        assertEquals("received", r.kind); assertEquals(1200.0, r.amount, 0.001)
        assertNull(Parse.tx("Your verification code is 123456", 1L, "x"))
        assertNull(Parse.tx("Hey are we still meeting at 5?", 1L, "x"))
    }

    @Test fun parsesBundles() {
        val b = Parse.bundle("Your data balance is 85.5MB, expires 20/10/2026 23:59.", at(2026, 10, 20), "MTN")!!
        assertEquals(85.5, b.mb!!, 0.01); assertNotNull(b.expires)
        assertTrue(Parse.bundleWarning(b, at(2026, 10, 20), 1024.0)!!.contains("expires"))
        val big = Parse.bundle("Data balance remaining 2GB valid until 30/11/2026", at(2026, 10, 19), "Airtel")!!
        assertEquals(2048.0, big.mb!!, 0.01); assertNull(Parse.bundleWarning(big, at(2026, 10, 19), 4096.0))
        assertTrue(Parse.bundleWarning(big.copy(mb = 60.0, expires = null), 0, null)!!.contains("low"))
    }

    @Test fun learnsBills() {
        val txs = listOf(8, 9, 10).map { MoneyRadar.Tx(at(2026, it, 5), "paid", 300.0, "ZESCO", null, "", "Airtel") } +
            MoneyRadar.Tx(at(2026, 9, 12), "sent", 50.0, "Once Only", null, "", "Airtel")
        val b = Parse.bills(txs)
        assertEquals(1, b.size); assertEquals("ZESCO", b[0].name); assertEquals(5, b[0].day)
        val next = Calendar.getInstance().apply { timeInMillis = Parse.nextDue(5, at(2026, 10, 18)) }
        assertEquals(10, next.get(Calendar.MONTH)); assertEquals(5, next.get(Calendar.DAY_OF_MONTH))
        assertTrue(Parse.summary(txs, 0).contains("Paid K900.00"))
    }
}

class MoneyRadarForecastTest {
    @Test fun spendAndDates() {
        val now = Calendar.getInstance().apply { set(2026, 9, 18, 12, 0, 0) }.timeInMillis
        val tx = (1..3).map { MoneyRadar.Tx(now - it * 30L * 86_400_000L + 1000, "paid", 600.0, "Shop", null, "", "x") } +
            MoneyRadar.Tx(now - 5 * 86_400_000L, "received", 5000.0, "Boss", null, "", "x")
        assertEquals(600.0, MoneyRadar.Parse.monthlySpend(tx, now)!!, 1.0)
        assertNull(MoneyRadar.Parse.monthlySpend(emptyList(), now))
        val d = Calendar.getInstance().apply { timeInMillis = MoneyRadar.Parse.ymd("2027-03-09")!! }
        assertEquals(2, d.get(Calendar.MONTH)); assertNull(MoneyRadar.Parse.ymd("next week"))
    }
}
