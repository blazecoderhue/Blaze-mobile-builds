package com.blaze.assistant.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v0.33.0 Offline Brain: a small model on the phone (Qwen2.5 0.5B Instruct, LiteRT / MediaPipe LLM Inference, ~0.5 GB,
 * downloaded once over Wi-Fi) takes over chat when there's no internet or data runs out, so Blaze still answers, drafts
 * notes and reminders, and on-device commands keep working. When the network is back, the next message goes to Blaze
 * Cloud again with the whole chat (the offline turns are already in the history). No tools in offline mode: it says
 * what it will do once you're online. Pure prompt building in [prompt] (OfflineBrainTest).
 */
object OfflineBrain {
    const val PREF = "offline_brain"
    const val URL_MODEL = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    private const val FILE = "qwen2.5-0.5b-q8.task"

    private lateinit var app: Context
    val progress = MutableStateFlow(-1)   // -1 idle, 0..100 downloading
    @Volatile private var engine: Any? = null
    @Volatile var lastUsed = false; private set

    fun init(ctx: Context) { app = ctx.applicationContext }

    var on: Boolean
        get() = try { Settings.bool(PREF, true) } catch (_: Throwable) { true }
        set(v) = Settings.setBool(PREF, v)

    fun file(): File = File(app.filesDir, "offline-brain/$FILE")
    fun ready(): Boolean = ::app.isInitialized && file().length() > 100_000_000
    fun sizeMb(): Long = if (::app.isInitialized) file().length() / 1_048_576 else 0

    fun online(): Boolean = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } == true
    } catch (_: Throwable) { true }

    /** Qwen chat template from OpenAI-shape [messages]: the system prompt cut down, the last turns that fit [budget] chars. */
    fun prompt(messages: JSONArray, budget: Int = 3000): String {
        val turns = ArrayList<Pair<String, String>>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role"); if (role != "user" && role != "assistant") continue
            val c = m.opt("content")
            val text = when (c) { is String -> c; is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.takeIf { p -> p.optString("type") == "text" }?.optString("text") }.joinToString(" "); else -> "" }
                .replace(Regex("""(?m)^\[m\d+[^\]]*] ?"""), "").trim()
            if (text.isNotEmpty()) turns += role to text.take(1200)
        }
        val keep = ArrayList<Pair<String, String>>(); var used = 0
        for (t in turns.asReversed()) { if (used + t.second.length > budget && keep.isNotEmpty()) break; keep.add(0, t); used += t.second.length }
        if (keep.firstOrNull()?.first == "assistant") keep.removeAt(0)
        val sys = "You are Blaze, a helpful assistant on the user's phone, running OFFLINE (no internet, no tools). Answer briefly and kindly. " +
            "If the request needs the internet or the phone (search, sending, booking, settings), say you'll do it when the connection is back and offer a short draft or plan now."
        return buildString {
            append("<|im_start|>system\n").append(sys).append("<|im_end|>\n")
            keep.forEach { (r, t) -> append("<|im_start|>").append(r).append('\n').append(t).append("<|im_end|>\n") }
            append("<|im_start|>assistant\n")
        }
    }

    suspend fun download(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val f = file().apply { parentFile?.mkdirs() }; val part = File(f.path + ".part")
            var have = if (part.exists()) part.length() else 0L
            val c = URL(URL_MODEL).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true; c.connectTimeout = 20_000; c.readTimeout = 60_000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            if (c.responseCode == 200) have = 0L else if (c.responseCode != 206) throw Exception("HTTP ${c.responseCode}")
            val total = have + c.contentLengthLong.coerceAtLeast(0)
            java.io.FileOutputStream(part, have > 0).use { out -> c.inputStream.use { inp ->
                val buf = ByteArray(1 shl 16); var got = have; var lastP = -1
                while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); got += n
                    if (total > 0) { val p = (got * 100 / total).toInt(); if (p != lastP) { lastP = p; progress.value = p } } } } }
            if (!part.renameTo(f)) throw Exception("couldn't save the model")
            progress.value = -1; Result.success(Unit)
        } catch (e: Exception) { progress.value = -1; Result.failure(e) }
    }

    fun delete() { close(); file().delete(); File(file().path + ".part").delete() }

    @Synchronized private fun llm(): com.google.mediapipe.tasks.genai.llminference.LlmInference {
        (engine as? com.google.mediapipe.tasks.genai.llminference.LlmInference)?.let { return it }
        val o = com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file().absolutePath).setMaxTokens(1280).build()
        return com.google.mediapipe.tasks.genai.llminference.LlmInference.createFromOptions(app, o).also { engine = it }
    }

    fun close() { try { (engine as? com.google.mediapipe.tasks.genai.llminference.LlmInference)?.close() } catch (_: Throwable) { }; engine = null }

    /** From LlmClient: an assistant message when offline and ready, else null (the cloud handles it). */
    suspend fun maybe(messages: JSONArray, onText: ((String) -> Unit)?): JSONObject? {
        lastUsed = false
        if (!::app.isInitialized || !on || !ready() || online()) return null
        val out = withContext(Dispatchers.Default) {
            try { llm().generateResponse(prompt(messages)).replace("<|im_end|>", "").trim() } catch (e: Throwable) { null }
        } ?: return null
        val text = out.ifBlank { "I'm offline right now and couldn't think that one through. I'll pick it up when the connection is back." } + "\n\n_(offline brain)_"
        onText?.invoke(text); lastUsed = true
        return JSONObject().put("role", "assistant").put("content", text)
    }

    fun status(): String = when {
        !::app.isInitialized -> "not ready"
        progress.value >= 0 -> "downloading ${progress.value}%"
        ready() -> "ready (${sizeMb()} MB on the phone)" + if (!on) ", switched off" else ""
        else -> "not downloaded"
    }
}
