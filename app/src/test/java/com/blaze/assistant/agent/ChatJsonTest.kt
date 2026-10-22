package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.0 P3 #11: cached message JSON makes exactly the file the old full build made. */
class ChatJsonTest {
    private fun old(m: ChatMessage, mark: Int?) = JSONObject().put("id", m.id).put("role", m.role.name).put("text", m.text)
        .put("files", JSONArray(m.files)).apply {
            mark?.let { put("mark", it) }
            if (m.thinking.isNotEmpty()) put("thinking", m.thinking.take(20000))
            if (m.followups.isNotEmpty()) put("followups", JSONArray(m.followups))
            if (m.meta.isNotEmpty()) put("meta", m.meta)
            if (m.at > 0) put("at", m.at)
            if (m.media.isNotEmpty()) put("media", JSONArray(m.media))
            if (m.thinkMs > 0) put("think_ms", m.thinkMs)
            if (m.startAt > 0) put("start_at", m.startAt)
            if (m.replyTo.isNotEmpty()) put("reply_to", JSONArray(m.replyTo))
            if (m.speaker.isNotEmpty()) put("speaker", m.speaker)
            if (m.receipt.isNotEmpty()) put("receipt", m.receipt)
            if (m.gid.isNotEmpty()) put("gid", m.gid)
            if (m.reactions.isNotEmpty()) put("reactions", Reaction.json(m.reactions))
        }

    @Test fun sameFileAsTheFullBuild() {
        val msgs = listOf(
            ChatMessage(1, Role.USER, "Hi \"quoted\" \n line \u2713", listOf("a.pdf"), at = 5),
            ChatMessage(2, Role.BLAZE, "Hello", thinking = "hmm", followups = listOf("More"), meta = "chip", media = listOf("/x.png"),
                at = 6, thinkMs = 10, startAt = 4, replyTo = listOf(1), speaker = "halo", receipt = "[]"),
            ChatMessage(3, Role.STEP, "Opening"))
        val marks = mapOf(1L to 0, 3L to 7)
        val head = JSONObject().put("id", "main").put("title", "Blaze").put("pinned", false).put("updated", 9L)
        val hist = JSONArray().put(JSONObject().put("role", "user").put("content", "Hi"))
        val text = ChatJson.file(head, ChatJson.messages(msgs, marks), hist)
        val expected = JSONObject(head.toString()).put("messages", JSONArray().apply { msgs.forEach { put(old(it, marks[it.id])) } }).put("history", hist)
        assertTrue(JSONObject(text).similar(expected))
        assertEquals("main", JSONObject(text).getString("id"))
    }

    @Test fun aMessageIsSerialisedOnce() {
        val m = ChatMessage(10, Role.BLAZE, "x".repeat(1000))
        assertSame(ChatJson.message(m), ChatJson.message(m))
        val changed = m.copy(text = "y")
        assertTrue(ChatJson.message(changed).contains("\"text\":\"y\""))
    }
}
