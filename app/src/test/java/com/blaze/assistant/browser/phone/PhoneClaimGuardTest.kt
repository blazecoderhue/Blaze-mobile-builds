package com.blaze.assistant.browser.phone

import com.blaze.assistant.agent.ToolVerify
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.19-speed, from the device test: mid-task, "what is Blaze Phone doing?" was answered "Booking confirmed!" with
 * nothing booked. Strict 5/5: phone results say plainly what was / wasn't completed, and a reply can't say booked /
 * paid / confirmed unless a phone_read in the same turn saw a confirmation.
 */
class PhoneClaimGuardTest {
    private fun fiveTimes(what: String, f: (Int) -> Unit) { var ok = 0; repeat(5) { f(it); ok++ }; assertEquals("$what: 5/5", 5, ok) }

    private fun call(d: PhoneDriver, name: String, a: JSONObject = JSONObject()) =
        runBlocking { PhoneBrowserTools.run(d, name, a, mutableListOf()) { _, _, _ -> true } }

    /** The demo site part-way through a booking (the form), or on its confirmation page. */
    private fun site(page: String): PhoneToolSimTest.DemoSite = PhoneToolSimTest.DemoSite().apply {
        signedIn = true; mode = PhonePolicy.Mode.AGENT; party = "2"; time = "19:30"; this.page = page
    }

    private val CLAIMS = listOf("Booking confirmed!", "Done, booked! Table for 2 at 19:30.", "I've booked your table for 2 at 19:30.",
        "Your reservation is confirmed for 19:30.", "Payment successful, you're all set.")

    @Test fun midTaskStatusQuestion_cantSayConfirmed_5of5() = fiveTimes("status mid-task") { i ->
        val d = site(listOf("form", "review", "home", "form", "review")[i])
        val status = call(d, "phone_status")
        assertTrue(status, status.contains("Not booked, ordered or paid yet: no confirmation page or number seen on Blaze Phone."))
        val done = listOf("phone_status" to ToolVerify.phoneVerdict(status).why)
        val out = PhoneClaimGuard.review(CLAIMS[i], done, inPlay = true)
        assertTrue(CLAIMS[i], out != null && out.startsWith("Not booked yet") && PhoneClaimGuard.claims(out).isEmpty())
    }

    @Test fun aPhoneReadThatSawTheConfirmationBacksIt_5of5() = fiveTimes("verified read") { i ->
        val d = site("done")
        val read = call(d, "phone_read")
        assertTrue(read, read.contains("VERIFIED: the page shows"))
        val status = call(d, "phone_status")
        assertTrue(status, status.contains("Completed: the page shows a confirmation"))
        val done = listOf("phone_act" to "page not verified", "phone_read" to ToolVerify.phoneVerdict(read).why)
        assertNull(CLAIMS[i], PhoneClaimGuard.review(CLAIMS[i], done, inPlay = true))
    }

    @Test fun onlyAPhoneReadThisTurnCounts_5of5() = fiveTimes("only phone_read") { i ->
        val claim = CLAIMS[i]
        // A tap whose own result looked verified, or the status line: not a phone_read.
        assertTrue(PhoneClaimGuard.review(claim, listOf("phone_act" to "page verified: Your reservation is confirmed"), true)!!.startsWith("Not booked yet"))
        assertTrue(PhoneClaimGuard.review(claim, listOf("phone_status" to "page not verified"), true)!!.startsWith("Not booked yet"))
        // Nothing ran this turn but Blaze Phone has a task open (an earlier turn's read doesn't count).
        assertTrue(PhoneClaimGuard.review(claim, emptyList(), true)!!.startsWith("Not booked yet"))
        // The newest read decides: confirmation lost / an error after it.
        assertTrue(PhoneClaimGuard.review(claim, listOf("phone_read" to "page verified: x", "phone_act" to "page not verified", "phone_read" to "page not verified"), true)!!.startsWith("Not booked yet"))
        assertFalse(PhoneClaimGuard.backed(listOf("phone_read" to "page not verified (signed in)")))
    }

    @Test fun honestSentencesAndOtherTurnsAreLeftAlone_5of5() = fiveTimes("left alone") { i ->
        val fine = listOf("It's not booked yet: the page is still on the review step.", "I haven't booked anything; want me to tap Book?",
            "Shall I confirm the booking?", "The booking form is open and filled in.", "Once you approve, I'll book it.")[i]
        assertNull(fine, PhoneClaimGuard.review(fine, emptyList(), inPlay = true))
        // Blaze Phone not in play this turn: not this guard's business (ClaimCheck covers other tools).
        assertNull(PhoneClaimGuard.review(CLAIMS[i], emptyList(), inPlay = false))
        // The rest of the reply keeps its layout; the honest line takes the claim's place.
        val out = PhoneClaimGuard.review("Here's the update.\n${CLAIMS[i]}\nAnything else?", emptyList(), true)!!
        assertTrue(out, out.startsWith("Here's the update.\nNot booked yet") && out.endsWith("Anything else?"))
    }

    @Test fun everyPageReadSaysWhatWasntCompleted_5of5() = fiveTimes("plain results") { i ->
        val page = listOf("login", "home", "form", "review", "form")[i]
        val d = site(page)
        val r = call(d, "phone_read")
        assertTrue(r, r.contains("Not booked, ordered or paid yet") || r.contains("Not booked, ordered or paid."))
        assertEquals("page not verified", ToolVerify.phoneVerdict(r).why.substringBefore(" ("))
        // Nothing open at all.
        val none = PhoneToolSimTest.DemoSite()
        assertTrue(call(none, "phone_status").contains("Nothing open on Blaze Phone: nothing booked, ordered or paid."))
    }
}
