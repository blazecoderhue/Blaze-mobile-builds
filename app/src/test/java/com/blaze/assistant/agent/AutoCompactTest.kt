package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.26.0: long chats compact themselves in the same chat; only what's sent shrinks, the recent turns stay whole. */
class AutoCompactTest {
    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    /** [n] turns of user → assistant tool call → tool result → assistant answer, [size] chars of tool output each. */
    private fun chat(n: Int, size: Int = 1200): JSONArray {
        val h = JSONArray()
        for (i in 0 until n) {
            h.put(msg("user", "Question $i"))
            h.put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject().put("id", "c$i")
                .put("type", "function").put("function", JSONObject().put("name", "web_search").put("arguments", "{}")))))
            h.put(JSONObject().put("role", "tool").put("tool_call_id", "c$i").put("content", "r".repeat(size)))
            h.put(msg("assistant", "Answer $i"))
        }
        return h
    }

    @Test fun nearLimitIsThreeQuartersOfTheWindow() {
        assertFalse(AutoCompact.nearLimit(23_999, 32_000))
        assertTrue(AutoCompact.nearLimit(24_000, 32_000))
        // Unknown window: 32k assumed, like the meter.
        assertTrue(AutoCompact.nearLimit(24_576, 0))
        assertFalse(AutoCompact.nearLimit(24_575, 0))
    }

    @Test fun shortChatNeverCompacts() {
        val h = chat(1)
        assertFalse(AutoCompact.shouldCompact(h, LongChat.State(), LongChat.budget(32_000), 30_000, 32_000))
        // Even forced: the whole chat is the tail, nothing older to fold.
        assertFalse(AutoCompact.shouldCompact(h, LongChat.State(), LongChat.budget(32_000), 30_000, 32_000, forced = true))
    }

    @Test fun overBudgetCompacts() {
        val h = chat(60)   // ~18k tokens of history, over the 12k budget
        assertTrue(AutoCompact.shouldCompact(h, LongChat.State(), LongChat.budget(128_000), 2_000, 128_000))
    }

    @Test fun bigSystemPromptAndToolsTriggerCompactionUnderBudget() {
        val h = chat(20)   // ~6k tokens: under the 12k budget on its own
        val budget = LongChat.budget(16_384)
        assertTrue(LongChat.tokens(h) < budget)
        assertFalse(AutoCompact.shouldCompact(h, LongChat.State(), budget, 1_000, 16_384))
        // 7k of system prompt + tools on a 16k model: the request nears the limit, so older turns fold.
        assertTrue(AutoCompact.shouldCompact(h, LongChat.State(), budget, 7_000, 16_384))
        assertTrue(AutoCompact.pressure(7_000, LongChat.tokens(h), 16_384, squeezed = false))
        assertFalse(AutoCompact.pressure(1_000, LongChat.tokens(h), 16_384, squeezed = false))
        assertTrue(AutoCompact.pressure(1_000, LongChat.tokens(h), 16_384, squeezed = true))
    }

    @Test fun foldsOlderTurnsAndKeepsRecentOnesVerbatim() {
        val h = chat(30)   // 120 messages
        val r = AutoCompact.range(h, LongChat.State())
        assertEquals(0, r.first)
        val tail = LongChat.tailStart(h)
        assertEquals(tail, r.last + 1)
        assertEquals("user", h.getJSONObject(tail).optString("role"))     // the kept part starts on a user turn
        assertTrue(h.length() - tail >= LongChat.KEEP_TAIL)
        // After folding, what's sent starts at the tail; the summary covers the rest.
        val st = LongChat.folded(h, "Goal: answer questions.")
        assertEquals(tail, st.upto)
        assertEquals(tail, LongChat.sendFrom(h, st))
        // A second fold only covers what came after the first one.
        for (i in 0 until 10) { h.put(msg("user", "More $i")); h.put(msg("assistant", "Ok $i")) }
        assertEquals(st.upto, AutoCompact.range(h, st).first)
    }

    @Test fun forcedFoldKeepsFewerMessages() {
        val h = chat(30)
        val normal = AutoCompact.range(h, LongChat.State())
        val forced = AutoCompact.range(h, LongChat.State(), forced = true)
        assertTrue(forced.last > normal.last)
        val keptForced = h.length() - (forced.last + 1)
        assertTrue(keptForced in AutoCompact.FORCED_KEEP until LongChat.KEEP_TAIL)
        assertEquals("user", h.getJSONObject(forced.last + 1).optString("role"))
        assertEquals(forced.last + 1, LongChat.folded(h, "note", AutoCompact.keep(true)).upto)
    }

    @Test fun staleIndexResets() {
        val h = chat(30)
        assertEquals(0, AutoCompact.upto(h, LongChat.State("old", 9_999)))
    }

    @Test fun fallbackNoteKeepsPreviousNoteAndNewestTurns() {
        val h = chat(40, size = 100)
        val n = AutoCompact.fallbackNote("Goal: lamps.", h, 0, 150)
        assertTrue(n.startsWith("Goal: lamps."))
        assertTrue(n.length <= LongChat.SUMMARY_MAX)
        assertTrue(n.contains("Question 37"))          // newest of the folded turns survive the cut
        assertFalse(n.contains("[0 user] Question 0\n"))
    }

    @Test fun contextErrorsAreRecognised() {
        assertTrue(AutoCompact.isContextError("This model's maximum context length is 8192 tokens. However, your messages resulted in 9100 tokens."))
        assertTrue(AutoCompact.isContextError("context_length_exceeded"))
        assertTrue(AutoCompact.isContextError("prompt is too long: 210000 tokens > 200000 maximum"))
        assertTrue(AutoCompact.isContextError("", code = 413))
        assertFalse(AutoCompact.isContextError("rate limit reached"))
        assertFalse(AutoCompact.isContextError("Unable to resolve host"))
    }

    @Test fun plainErrorNeverSaysStartANewChat() {
        val raw = "Groq said (400): {\"error\":{\"message\":\"Please reduce the length of the messages or completion. context_length_exceeded\"}}"
        val p = PlainError.of(raw)
        assertEquals(PlainError.CONTEXT, p)
        assertFalse(p.contains("new chat", ignoreCase = true))
    }
}
