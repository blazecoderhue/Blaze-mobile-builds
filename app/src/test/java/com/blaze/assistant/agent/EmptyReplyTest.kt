package com.blaze.assistant.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v0.26.2 root cause of Jack's "Done." replies: a reasoning-only stream (content "", _reasoning "The user asks:") was
 * taken as the answer. Now it's a failed try: same route without streaming + nudge, then another model, then an error.
 */
class EmptyReplyTest {
    private fun msg(content: Any?, reasoning: String = "", calls: JSONArray? = null) = JSONObject().put("role", "assistant")
        .put("content", content ?: JSONObject.NULL).apply { if (reasoning.isNotEmpty()) put("_reasoning", reasoning); calls?.let { put("tool_calls", it) } }
    private val reasoningOnly get() = msg("", "The user asks:")
    private val call = JSONArray().put(JSONObject().put("id", "c1").put("type", "function").put("function", JSONObject().put("name", "device_status").put("arguments", "{}")))

    @Test fun emptyMeansNoVisibleTextAndNoToolCalls() {
        assertTrue(EmptyReply.isEmpty(reasoningOnly))
        assertTrue(EmptyReply.isEmpty(msg(null)))
        assertTrue(EmptyReply.isEmpty(msg("<think>The user asks:</think>")))
        assertTrue(EmptyReply.isEmpty(msg("  <<follow-ups: a | b | c>>")))
        assertFalse(EmptyReply.isEmpty(msg(null, calls = call)))
        assertFalse(EmptyReply.isEmpty(msg("Your phone is on vibrate.")))
        assertFalse(EmptyReply.isEmpty(msg(JSONArray().put(JSONObject().put("type", "text").put("text", "Hi")))))
    }

    @Test fun reasoningOnlyStreamIsRetriedThenGoesToTheNextModelAndNeverEndsAsAReply() = runBlocking {
        val tries = mutableListOf<Cloud.Attempt>()
        var served = ""
        val out = Cloud.drive(true, call = { a ->
            tries += a
            served = if (a.avoid.isEmpty()) "nvidia/deepseek-r1" else "groq/llama-3.3-70b"
            if (served.startsWith("nvidia")) reasoningOnly else msg("You're not on mute: the ringer is on normal.")
        }, served = { served }, sleep = {})
        assertEquals("You're not on mute: the ringer is on normal.", out.getString("content"))
        assertEquals(3, tries.size)
        assertTrue("first try streams", tries[0].stream && !tries[0].nudge)
        assertTrue("second: same route, still streaming (0.45.5), with the nudge", tries[1].stream && tries[1].nudge && tries[1].avoid.isEmpty())
        assertEquals("third: the next model", listOf("nvidia/deepseek-r1"), tries[2].avoid)
        assertTrue(EmptyReply.withNudge(JSONArray().put(JSONObject().put("role", "user").put("content", "x"))).getJSONObject(1).getString("content").contains("Answer the user now"))
    }

    @Test fun threeEmptyRepliesFailPlainlyInsteadOfDone() = runBlocking {
        var n = 0
        try {
            Cloud.drive(true, call = { n++; reasoningOnly }, served = { "nvidia/m" }, sleep = {})
            fail("must not return an empty reply")
        } catch (e: LlmClient.LlmException) {
            assertEquals(EmptyReply.CODE, e.code); assertEquals(EmptyReply.FAILED, e.message)
            assertNotEquals("Done.", e.message)
        }
        assertEquals(EmptyReply.TRIES, n)
    }

    @Test fun aStreamThatTimesOutMidReasoningMovesToTheNextModel() = runBlocking {
        val tries = mutableListOf<Cloud.Attempt>()
        val out = Cloud.drive(true, call = { a ->
            tries += a
            if (tries.size == 1) throw LlmClient.LlmException("Blaze Cloud · blaze-auto stopped sending (timed out mid-reply).", 0) else msg("ok")
        }, served = { "nvidia/m" }, sleep = {})
        assertEquals("ok", out.getString("content"))
        assertEquals(listOf("nvidia/m"), tries[1].avoid)
        // Tool calls are an answer, not an empty reply.
        assertEquals(1, Cloud.drive(false, call = { msg(null, calls = call) }, served = { "" }, sleep = {}).getJSONArray("tool_calls").length())
    }

    @Test fun signInAndQuotaErrorsStillSurfaceAsBefore() = runBlocking {
        try { Cloud.drive(true, call = { throw LlmClient.LlmException("nope", 401) }, served = { "" }, sleep = {}); fail() }
        catch (e: LlmClient.LlmException) { assertEquals(Cloud.EXPIRED, e.message) }
        try { Cloud.drive(true, call = { throw LlmClient.LlmException("quota_exceeded allowance", 429) }, served = { "" }, sleep = {}); fail() }
        catch (e: LlmClient.LlmException) { assertTrue(e.message!!.contains("free messages")) }
    }

    @Test fun anActionThatSucceededGetsASpecificLineAQuestionNever() {
        assertEquals("Turned on Do Not Disturb.", EmptyReply.finalText("turn on do not disturb", "Turning on Do Not Disturb"))
        assertEquals("Set an alarm for 06:30.", EmptyReply.finalText("set an alarm for 6:30", "Setting an alarm for 06:30"))
        assertEquals("Done: alarm set for 06:30.", EmptyReply.doneLine("Alarm set for 06:30"))
        assertNull("a question never becomes Done", EmptyReply.finalText("what sound profile is my phone set to?", "Checking the phone"))
        assertNull(EmptyReply.finalText("am I on mute in terms of device sound profile", "Checking the phone"))
        assertNull("no tool succeeded", EmptyReply.finalText("turn on do not disturb", ""))
        assertTrue(EmptyReply.isAction("Can you mute my phone")); assertFalse(EmptyReply.isAction("is my phone muted"))
    }
}
