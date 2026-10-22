package com.blaze.assistant.voice.gemini

import com.blaze.assistant.voice.myvoice.AudioClip
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.41.0 Gemini voice, the pure part (GeminiTtsTest): the request for Gemini text-to-speech, reading the audio out of
 * the reply, and plain errors. Format from ai.google.dev/gemini-api/docs/speech-generation (checked 2026-10-19):
 * POST /v1beta/interactions with header x-goog-api-key; `input` = one user_input with the text; `response_format`
 * {type: audio, mime_type: audio/l16, sample_rate: 24000} asks for raw 16-bit mono PCM; `generation_config.speech_config`
 * = [{voice}]. The audio comes back base64 in steps[].content[] items of type "audio" (a WAV when no mime type is set).
 */
object GeminiTts {
    const val URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
    /** Flash-Lite TTS is Google's fast model for read-aloud and voice agents; Flash TTS is the fallback. */
    val MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts")
    const val SAMPLE_RATE = 24_000
    const val DEFAULT_VOICE = "Achird"

    /** A short list of the prebuilt voices, with Google's one-word description. */
    val VOICES = listOf(
        "Achird" to "Friendly", "Sulafat" to "Warm", "Kore" to "Firm", "Puck" to "Upbeat",
        "Aoede" to "Breezy", "Charon" to "Informative", "Leda" to "Youthful", "Vindemiatrix" to "Gentle",
    )

    fun voiceOrDefault(v: String?): String = VOICES.firstOrNull { it.first.equals(v?.trim(), true) }?.first ?: DEFAULT_VOICE

    fun requestBody(text: String, voice: String, model: String = MODELS.first()): JSONObject = JSONObject()
        .put("model", model)
        .put("input", JSONArray().put(JSONObject().put("type", "user_input")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))))
        .put("response_format", JSONObject().put("type", "audio").put("mime_type", "audio/l16").put("sample_rate", SAMPLE_RATE))
        .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voiceOrDefault(voice)))))

    /** The last audio item's base64 data anywhere in the reply (steps[].content[], or output_audio), or null. */
    fun audioBase64(reply: String): String? {
        val root = try { JSONObject(reply) } catch (_: Exception) { return null }
        var found: String? = null
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> {
                    if (x.optString("type") == "audio" && x.optString("data").isNotBlank()) found = x.optString("data")
                    x.optJSONObject("output_audio")?.optString("data")?.takeIf { it.isNotBlank() }?.let { found = it }
                    for (k in x.keys()) { val v = x.opt(k); if (v is JSONObject || v is JSONArray) walk(v) }
                }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(root)
        return found
    }

    /** Raw L16 (or a WAV, when the server ignored the mime type) to floats at its sample rate. */
    fun decode(bytes: ByteArray): Pair<FloatArray, Int>? {
        if (bytes.size < 2) return null
        if (bytes.size >= 12 && String(bytes, 0, 4) == "RIFF") return AudioClip.readWav(bytes)
        val n = bytes.size / 2
        return FloatArray(n) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f } to SAMPLE_RATE
    }

    fun audio(reply: String): Pair<FloatArray, Int>? = audioBase64(reply)?.let {
        try { decode(java.util.Base64.getDecoder().decode(it.trim())) } catch (_: IllegalArgumentException) { null }
    }

    const val NO_KEY = "Add your Gemini API key to use the Gemini voice."

    /** One plain sentence for a failed request; never a code or a model name. */
    fun friendlyError(code: Int, body: String): String {
        val b = body.lowercase()
        return when {
            code == 0 -> "Couldn't reach Gemini. Check your connection."
            "api_key_invalid" in b || "api key not valid" in b || (code == 400 && "key" in b) -> "Gemini didn't accept that API key. Check it and try again."
            code == 401 || code == 403 -> "This Gemini key isn't allowed to make voices. Check the key in Google AI Studio."
            code == 429 -> "Gemini voice is busy or used up for today. Try again later."
            code == 404 -> "Gemini's voice model isn't available for this key right now."
            code in 500..599 -> "Gemini is having trouble right now. Try again in a moment."
            else -> "Gemini voice didn't work this time."
        }
    }

    /** Worth trying the next model: this one is missing or refused for the key. */
    fun tryNextModel(code: Int, body: String): Boolean = code == 404 || (code == 400 && "model" in body.lowercase() && "key" !in body.lowercase())
}
