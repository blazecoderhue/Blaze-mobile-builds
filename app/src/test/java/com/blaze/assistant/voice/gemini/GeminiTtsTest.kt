package com.blaze.assistant.voice.gemini

import com.blaze.assistant.voice.myvoice.AudioClip
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeminiTtsTest {
    @Test fun requestMatchesTheInteractionsFormat() {
        val b = GeminiTts.requestBody("Hi there.", "kore")
        assertEquals("gemini-3.8-flash-lite-tts", b.getString("model"))
        val item = b.getJSONArray("input").getJSONObject(0)
        assertEquals("user_input", item.getString("type"))
        assertEquals("Hi there.", item.getJSONArray("content").getJSONObject(0).getString("text"))
        val rf = b.getJSONObject("response_format")
        assertEquals("audio", rf.getString("type")); assertEquals("audio/l16", rf.getString("mime_type")); assertEquals(24000, rf.getInt("sample_rate"))
        assertEquals("Kore", b.getJSONObject("generation_config").getJSONArray("speech_config").getJSONObject(0).getString("voice"))
        assertEquals(GeminiTts.DEFAULT_VOICE, GeminiTts.voiceOrDefault("nobody"))
    }

    private fun reply(data: String) = JSONObject().put("steps", JSONArray()
        .put(JSONObject().put("type", "user_input"))
        .put(JSONObject().put("type", "model_output").put("content", JSONArray().put(JSONObject().put("type", "audio").put("data", data))))).toString()

    @Test fun readsRawPcmFromSteps() {
        val pcm = byteArrayOf(0, 0, 0, 0x40, 0, 0xC0.toByte())   // 0, +0.5, -0.5
        val (x, rate) = GeminiTts.audio(reply(Base64.getEncoder().encodeToString(pcm)))!!
        assertEquals(24000, rate)
        assertEquals(3, x.size)
        assertEquals(0.5f, x[1], 1e-4f); assertEquals(-0.5f, x[2], 1e-4f)
    }

    @Test fun readsWavWhenTheServerSendsOne() {
        val wav = AudioClip.wavBytes(FloatArray(100) { 0.25f }, 16000)
        val (x, rate) = GeminiTts.audio(reply(Base64.getEncoder().encodeToString(wav)))!!
        assertEquals(16000, rate); assertEquals(100, x.size)
    }

    @Test fun noAudioIsNull() {
        assertNull(GeminiTts.audio("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"hi"}]}]}"""))
        assertNull(GeminiTts.audio("not json"))
        assertNotNull(GeminiTts.audioBase64("""{"output_audio":{"data":"AAAA"}}"""))
    }

    @Test fun errorsArePlain() {
        assertTrue(GeminiTts.friendlyError(400, """{"error":{"message":"API key not valid. Please pass a valid API key.","details":[{"reason":"API_KEY_INVALID"}]}}""").contains("key"))
        assertTrue(GeminiTts.friendlyError(429, "RESOURCE_EXHAUSTED").contains("busy"))
        assertTrue(GeminiTts.friendlyError(0, "").contains("connection"))
        for (code in listOf(0, 400, 403, 404, 429, 500, 418)) {
            val m = GeminiTts.friendlyError(code, "")
            assertFalse(m, Regex("""\d{3}|gemini-\d""").containsMatchIn(m))
        }
        assertTrue(GeminiTts.tryNextModel(404, "models/x is not found"))
        assertFalse(GeminiTts.tryNextModel(400, "API key not valid"))
    }
}
