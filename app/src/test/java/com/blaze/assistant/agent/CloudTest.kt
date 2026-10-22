package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.25.0: every call goes to Blaze Cloud with a task hint; model-gone and busy errors retry quietly; no jargon. */
class CloudTest {
    private fun user(text: String) = JSONArray().put(JSONObject().put("role", "user").put("content", text))

    @Test fun taskHints() {
        assertEquals("chat", Cloud.task(user("how are you?"), null))
        assertEquals("quick", Cloud.task(user("hi"), null, lite = true))
        assertEquals("code", Cloud.task(user("fix this kotlin function"), null))
        assertEquals("agent", Cloud.task(user("open whatsapp"), JSONArray().put(JSONObject().put("type", "function"))))
        val pic = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "what is this"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:x")))))
        assertEquals("vision", Cloud.task(pic, JSONArray().put(JSONObject())))
        assertEquals("long", Cloud.task(user("x".repeat(Cloud.LONG_CHARS + 10)), null))
        Cloud.TASKS.containsAll(listOf("chat", "quick", "code", "vision", "long", "agent")).let { assertTrue(it) }
    }

    @Test fun tagSetsAutoModelAndTask() {
        val b = Cloud.tag(JSONObject().put("model", "gemini-3-pro-preview"), "code")
        assertEquals("blaze-auto", b.getString("model")); assertEquals("code", b.getString("blaze_task"))
    }

    @Test fun modelGoneAndBusyAreRetried() {
        assertTrue(Cloud.retryable(404, "model not found"))
        assertTrue(Cloud.retryable(400, "The AI doesn't offer that model any more"))
        assertTrue(Cloud.retryable(400, "gemini 3 pro is paid-only"))
        assertTrue(Cloud.retryable(503, "Every model for 'chat' is busy"))
        assertTrue(Cloud.retryable(0, "couldn't reach the service"))
        assertTrue(Cloud.retryable(429, "rate limit"))
        assertFalse(Cloud.retryable(401, "expired"))
        assertFalse(Cloud.retryable(429, "You've used today's message allowance"))
        assertFalse(Cloud.retryable(400, "bad request"))
    }

    @Test fun backoffGrows() {
        assertEquals(1_000L, Cloud.backoffMs(0)); assertEquals(3_000L, Cloud.backoffMs(1))
        assertEquals(5_000L, Cloud.backoffMs(0, retryAfterSec = 5))
        assertEquals(1_000L, Cloud.backoffMs(0, retryAfterSec = 600))   // a huge Retry-After doesn't freeze the chat
    }

    @Test fun friendlyLinesHaveNoJargon() {
        val lines = listOf(Cloud.friendly(404, "model not found"), Cloud.friendly(0, ""), Cloud.friendly(503, "busy"),
            Cloud.friendly(401, ""), Cloud.friendly(429, "allowance"))
        lines.forEach { l ->
            assertFalse(l, l.contains("model", true)); assertFalse(l, l.contains("Settings")); assertFalse(l, l.contains("key", true))
            assertTrue(l.length < 120)
        }
        assertEquals(Cloud.BUSY, Cloud.friendly(404, "model not found"))
        assertEquals(Cloud.UNREACHABLE, Cloud.friendly(0, "timeout"))
    }

    @Test fun waitingListIsNotAnExpiredSignIn() {
        assertEquals(Cloud.WAITLISTED, Cloud.friendly(403, "waitlisted You're on the Blaze waiting list."))
        assertEquals(Cloud.REJECTED, Cloud.friendly(403, "access_rejected nope"))
        assertEquals(Cloud.EXPIRED, Cloud.friendly(403, "forbidden"))
        assertFalse(Cloud.retryable(403, "waitlisted"))
        assertEquals(Cloud.WAITLISTED, PlainError.of("""{"error":{"code":"waitlisted","message":"x"}} (403)"""))
        assertEquals(Cloud.REJECTED, PlainError.of(Cloud.REJECTED))
    }

    @Test fun oldPrefsAreIgnored() {
        // Model / provider choices are gone: the getters are fixed and the setters do nothing (old prefs stay on disk).
        assertEquals(Providers.GATEWAY, Settings.providerId)
        Settings.providerId = "gemini"; Settings.model = "gemini-3-pro-preview"
        assertEquals(Providers.GATEWAY, Settings.providerId); assertEquals(Cloud.MODEL, Settings.model)
        assertTrue(Commands.actions.none { it.name == "model" || it.name == "provider" })
    }
}
