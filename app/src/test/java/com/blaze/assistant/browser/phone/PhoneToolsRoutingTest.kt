package com.blaze.assistant.browser.phone

import com.blaze.assistant.agent.ClaimCheck
import com.blaze.assistant.agent.ClaimCheck.Done
import com.blaze.assistant.agent.ClaimCheck.Outcome
import com.blaze.assistant.agent.ConfirmGate
import com.blaze.assistant.agent.ToolTruth
import com.blaze.assistant.agent.ToolVerify
import com.blaze.assistant.agent.ToolVerify.Status
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.45.19 Blaze Phone browser: which asks route to the phone tools, and how the agent's plumbing treats them. */
class PhoneToolsRoutingTest {
    private val names = listOf("phone_open", "phone_read", "phone_act", "phone_screenshot", "phone_wait", "phone_handoff", "phone_status",
        "browser_open", "browser_read", "web_search", "add_calendar_event", "create_routine", "set_alarm", "open_app", "memory_search", "run_shell",
        "open_view", "calendar_events", "search_in_app", "device_setting")

    private val phoneAsks = listOf(
        "book a table at Nando's Lusaka for 2 tonight",
        "Book me a table for four at The Royal Dinner on Friday",
        "reserve a room at Protea Hotel Livingstone for the 3rd",
        "book 2 tickets on ticketmaster.com for the concert",
        "log into my Netflix account",
        "log in to amazon.com and check my orders",
        "sign in to GitHub",
        "Sign into booking.com",
        "sign me up for the newsletter on example.com",
        "order a phone case from jumia.co.zm",
        "fill out the form on forms.example.org with my details",
        "use your own phone to find me a hotel and book it",
        "book an appointment at the dentist on smiledental.co.zm",
    )

    private val notPhone = listOf(
        "remind me at 5 to call mum", "set an alarm for 6am", "what's on my calendar today", "add a meeting to my calendar tomorrow at 3",
        "open youtube", "search the web for zesco load shedding", "log into my bank and transfer money", "sign in to my Blaze account",
        "play a game with me", "run ls in the terminal", "what do you remember about me", "turn on wifi", "book",
    )

    @Test fun bookingAndLoginAsksRouteToPhoneOpen() {
        phoneAsks.forEach { assertEquals(it, "phone_open", ToolTruth.choiceFor(it, emptyList(), names)) }
    }

    @Test fun otherAsksKeepTheirRoutes() {
        notPhone.forEach { assertNotEquals(it, "phone_open", ToolTruth.choiceFor(it, emptyList(), names)) }
        assertEquals("create_routine", ToolTruth.choiceFor("remind me at 5 to call mum", emptyList(), names))
        assertEquals("set_alarm", ToolTruth.choiceFor("set an alarm for 6am", emptyList(), names))
    }

    @Test fun noPhoneToolsNoPhoneRoute() {
        val without = names.filter { !it.startsWith("phone_") }
        phoneAsks.forEach { assertNotEquals(it, "phone_open", ToolTruth.choiceFor(it, emptyList(), without)) }
    }

    @Test fun toolLessLanesHandPhoneAsksToTheFullLane() {
        phoneAsks.forEach { assertTrue(it, ToolTruth.toolShaped(it)) }
    }

    @Test fun theCapKeepsTheWholePhoneFlow() {
        val tools = JSONArray()
        names.forEach { tools.put(fn(it)) }
        for (i in 0 until 140) tools.put(fn("filler_tool_$i"))
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "book a table at Nando's for 2"))
        val kept = ToolTruth.names(ToolTruth.cap(msgs, tools))
        assertTrue(kept.size <= ToolTruth.MAX_TOOLS)
        PhonePolicy.TOOLS.forEach { assertTrue(it, it in kept) }
    }

    @Test fun registeredDefinitionsMatchTheToolNames() {
        val defs = PhoneBrowserTools.definitions().map { it.getJSONObject("function").getString("name") }.toSet()
        assertEquals(PhonePolicy.TOOLS, defs)
        assertEquals(PhonePolicy.TOOLS, PhoneBrowserTools.names)
        // Every definition is a valid tool for the gateway (ToolTruth.clean keeps it).
        val arr = JSONArray(); PhoneBrowserTools.definitions().forEach { arr.put(it) }
        assertEquals(PhonePolicy.TOOLS.size, ToolTruth.clean(arr).length())
    }

    @Test fun phonePagesTaintTheTurnLikeWebPages() {
        listOf("phone_open", "phone_read", "phone_act", "phone_screenshot").forEach { assertEquals("a web page", ConfirmGate.sourceOf(it)) }
        assertEquals(null, ConfirmGate.sourceOf("phone_status"))
    }

    @Test fun bookedClaimsNeedAVerifiedPage() {
        val ask = "book a table at Nando's for 2 tonight"
        val unverified = ToolVerify.classify("phone_act", JSONObject(), "Clicked \u201cBook now\u201d.\n\nNow:\nPage: Review \u2014 https://x\nNOT VERIFIED: no confirmation number or confirmation text on this page.")
        assertEquals("page not verified", unverified.why)
        val o = ClaimCheck.review("Done! Your table is booked for 2 at 19:30.", listOf(Done("phone_open", Status.VERIFIED, "page not verified"), Done("phone_act", unverified.status, unverified.why)), ask)
        assertTrue(o.toString(), o is Outcome.Rewrite)
        assertFalse((o as Outcome.Rewrite).text.contains("is booked for 2"))
        val verified = ToolVerify.classify("phone_read", JSONObject(), "Page: Thanks \u2014 https://x\nVERIFIED: the page shows \u201cYour reservation is confirmed\u201d (reference TBL-48213).")
        assertTrue(verified.why.startsWith("page verified"))
        assertEquals(Outcome.Ok, ClaimCheck.review("Your table is booked: reference TBL-48213.", listOf(Done("phone_read", verified.status, verified.why)), ask))
    }

    @Test fun theNewestVerificationLineDecides() {
        val r = "VERIFIED: the page shows \u201cbooked\u201d.\n...\nNow:\nNOT VERIFIED: the page shows a problem: \u201cPayment declined\u201d."
        assertEquals("page not verified", ToolVerify.phoneVerdict(r).why)
    }

    @Test fun bookedCheckOnlyOnPhoneTurns() {
        // A turn that never used Blaze Phone keeps the old behaviour (no new claim check).
        assertEquals(Outcome.Ok, ClaimCheck.review("Booked!", listOf(Done("web_search", Status.VERIFIED)), "how do I book a table at Nando's"))
    }

    private fun fn(n: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n).put("description", "tool $n")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
}
