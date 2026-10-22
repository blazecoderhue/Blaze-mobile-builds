package com.blaze.assistant.connect

import com.blaze.assistant.connect.Billing.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.3: yearly checkout against gateway 0.6.2's billing API (shape from feat/gateway-0.6.2-yearly 0616c554 billing.ts). */
class BillingTest {
    private val v062 = """{"term_days":30,"periods":{"month":{"days":30},"year":{"days":365}},
        "plans":[{"id":"standard","price_usd":10,"daily_requests":1000,"daily_tokens":2000000,"price_usd_year":100},
                 {"id":"pro","price_usd":20,"daily_requests":5000,"daily_tokens":0,"price_usd_year":200}],
        "methods":[{"id":"airtel","name":"Airtel Money","currency":"ZMW","details":[{"label":"Number","value":"0970000000"}],
                    "ref_label":"Transaction ID","hint":"","amounts":{"standard":"ZMW 200","pro":"ZMW 400"},"amounts_year":{"standard":"ZMW 2000","pro":"ZMW 4000"}}]}"""
    private val v061 = """{"term_days":30,"plans":[{"id":"standard","price_usd":10},{"id":"pro","price_usd":20}],
        "methods":[{"id":"airtel","name":"Airtel Money","currency":"ZMW","details":[],"amounts":{"standard":"ZMW 200","pro":"ZMW 400"}}]}"""

    @Test fun parsesYearlyPricesAndAmounts() {
        val m = Billing.parseMethods(v062)
        assertTrue(Billing.yearlyOffered(m)); assertEquals(365, m.yearDays); assertEquals(30, m.monthDays)
        val pro = m.plans.first { it.id == "pro" }
        assertEquals("$20 / month", Billing.priceLine(pro, Period.MONTH)); assertEquals("$200 / year", Billing.priceLine(pro, Period.YEAR))
        assertEquals("$100 / year", Billing.priceLine(m.plans.first { it.id == "standard" }, Period.YEAR))
        assertEquals("2 months free", Billing.saving(pro))
        val air = m.methods[0]
        assertEquals("ZMW 400", Billing.amount(air, "pro", Period.MONTH)); assertEquals("ZMW 4000", Billing.amount(air, "pro", Period.YEAR))
        assertEquals("ZMW 2000", Billing.amount(air, "standard", Period.YEAR)); assertEquals("Transaction ID", air.refLabel)
        assertEquals("365 days, not auto-renewing", Billing.termLine(Period.YEAR)); assertEquals("30 days, not auto-renewing", Billing.termLine(Period.MONTH))
    }

    @Test fun olderGatewayHidesTheToggle() {
        val m = Billing.parseMethods(v061)
        assertFalse(Billing.yearlyOffered(m)); assertNull(m.yearDays)
        assertEquals(Period.MONTH, Billing.effective(m, Period.YEAR))
        assertEquals("$10 / month", Billing.priceLine(m.plans[0], Period.YEAR))
        assertEquals("Payment reference", m.methods[0].refLabel); assertNull(Billing.saving(m.plans[0]))
    }

    @Test fun payBodyUsesPeriodNeverTerm() {
        val b = Billing.payBody("pro", "airtel", " ABC123 ", Period.YEAR)
        assertEquals("year", b.getString("period")); assertFalse(b.has("term")); assertEquals("ABC123", b.getString("reference"))
        assertEquals("month", Billing.payBody("standard", "airtel", "x", Period.MONTH).getString("period"))
    }

    @Test fun historyAndErrors() {
        val me = Billing.parseMe("""{"email":"a@b","plan":"pro","term":{"plan":"pro","expires_at":2000000000000,"days_left":364,"renew_soon":false},
            "payments":[{"id":3,"plan":"pro","period":"year","amount_local":"ZMW 4000","status":"pending","created_at":1},
                        {"id":2,"plan":"standard","amount_local":"ZMW 200","status":"approved","created_at":0}]}""")
        assertEquals(364, me.daysLeft); assertEquals("Pro, yearly", Billing.historyLabel(me.payments[0])); assertEquals("Standard", Billing.historyLabel(me.payments[1]))
        assertEquals(Period.MONTH, me.payments[1].period)
        assertEquals("Choose monthly or yearly", Billing.errorLine(400, """{"error":{"message":"Choose monthly or yearly","code":"bad_period"}}"""))
        assertEquals("Sign in to Blaze first.", Billing.errorLine(401, ""))
    }
}
