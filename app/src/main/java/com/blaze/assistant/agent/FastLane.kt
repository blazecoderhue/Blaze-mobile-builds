package com.blaze.assistant.agent

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.blaze.assistant.phone.PhoneTools
import com.blaze.assistant.phone.Skills
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device fast lane: Cactus Needle 3 (a ~35 MB function-calling model) picks the tool for
 * direct commands ("open WhatsApp", "flashlight on") in well under a second, with no network.
 * The runner ships inside the APK as libneedle.so; the weights download once into filesDir/needle.
 * Anything it isn't sure about falls through to the cloud agent.
 */
object FastLane {
    private const val MODEL_URL = "https://huggingface.co/Cactus-Compute/needle3/resolve/main/needle3.cact"
    const val SIZE_MB = 35
    private const val PORT = 18734

    /** -1 = not downloading, otherwise 0..100. */
    val progress = MutableStateFlow(-1)

    private var proc: Process? = null
    private val lock = Mutex()
    @Volatile private var lastUse = 0L

    data class Result(val reply: String, val ms: Long, val tool: String)

    fun dir(ctx: Context) = File(ctx.filesDir, "needle")
    private fun model(ctx: Context) = File(dir(ctx), "needle3.cact")
    private fun runner(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libneedle.so")
    fun supported(ctx: Context) = runner(ctx).exists()
    fun ready(ctx: Context) = supported(ctx) && model(ctx).length() > 30_000_000L
    fun enabled(ctx: Context) = Settings.fastLane && ready(ctx)

    /**
     * v0.45.8: the on-device model runs only offline. Online it sat in front of EVERY short message: starting its process
     * (up to 40 tries of 150 ms after an idle stop) and an inference (read timeout 8 s), seconds before the cloud request
     * left. Online, the instant rules and the intent cache still answer direct commands with no model.
     */
    fun useModel(offline: Boolean): Boolean = offline

    suspend fun download(ctx: Context): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            progress.value = 0
            dir(ctx).mkdirs()
            val tmp = File(dir(ctx), "needle3.part")
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000; conn.readTimeout = 30000
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (SIZE_MB * 1_000_000L)
            conn.inputStream.use { inp -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    progress.value = ((done * 100) / total).toInt().coerceAtMost(99)
                }
            } }
            if (tmp.length() < 30_000_000L) { tmp.delete(); error("Download was incomplete") }
            tmp.renameTo(model(ctx))
            progress.value = 100
            Unit
        }.also { if (it.isFailure) progress.value = -1 }
    }

    fun delete(ctx: Context) { stop(); dir(ctx).deleteRecursively() }

    fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps == null && cm == null || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { true }   // can't tell: assume online and let the request decide

    // ---------- The direct-action tools Needle may pick ----------

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)
    private fun e(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))

    private fun toolsJson(): JSONArray = JSONArray().apply {
        put(fn("open_app", "Open an installed app by name", JSONObject().put("name", p("string", "App name")), listOf("name")))
        put(fn("set_alarm", "Set an alarm at a time of day", JSONObject().put("hour", p("integer", "0-23"))
            .put("minute", p("integer", "0-59")).put("label", p("string", "Optional label")), listOf("hour", "minute")))
        put(fn("set_timer", "Start a countdown timer", JSONObject().put("minutes", p("integer", "Minutes"))
            .put("seconds", p("integer", "Extra seconds")), listOf("minutes")))
        // One narrow tool per setting, named the way people say it, with an on/off enum that
        // defaults to "on" (Needle's tool-design rules), plus triggers so odd phrasings still land.
        val single = "(?![\\s\\S]*\\b(and|then)\\b)"
        for ((tool, words, nouns) in listOf(
            Triple("flashlight", "Turn the flashlight (torch) on or off", "torch|flash ?light|flash"),
            Triple("wifi", "Turn Wi-Fi on or off", "wi-?fi|wireless|wlan"),
            Triple("bluetooth", "Turn Bluetooth on or off", "bluetooth|bt"),
            Triple("mobile_data", "Turn mobile data on or off", "mobile data|cellular data|data"),
            Triple("do_not_disturb", "Turn do not disturb (silent focus) on or off", "do not disturb|dnd|disturb"),
            Triple("airplane_mode", "Turn airplane (flight) mode on or off", "airplane|aeroplane|flight mode"))) {
            put(JSONObject().put("type", "function").put("function", JSONObject().put("name", tool).put("description", words)
                .put("triggers", JSONArray(listOf("$single[\\s\\S]*\\b($nouns)\\b")))
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("state", JSONObject().put("type", "string").put("enum", JSONArray(listOf("on", "off"))).put("default", "on")
                        .put("description", "on (enable, start, activate) or off (disable, stop, kill, deactivate)")))
                    .put("required", JSONArray()))))
        }
        put(fn("set_volume", "Change the volume", JSONObject().put("level", e("up", "down", "mute", "max")), listOf("level")))
        put(fn("media_control", "Control music or video playback", JSONObject().put("action", e("play", "pause", "next", "previous")), listOf("action")))
        put(fn("battery_level", "Tell the battery level", JSONObject(), emptyList()))
        put(fn("call_contact", "Phone someone", JSONObject().put("name", p("string", "Contact name or number")), listOf("name")))
        put(fn("send_text", "Send an SMS text message", JSONObject().put("to", p("string", "Contact name or number"))
            .put("message", p("string", "Message text")), listOf("to", "message")))
        put(fn("web_search", "Search the web", JSONObject().put("query", p("string", "Search words")), listOf("query")))
        put(fn("navigate_to", "Get directions to a place in maps", JSONObject().put("place", p("string", "Destination")), listOf("place")))
    }

    // ---------- Runner ----------

    private suspend fun ensureStarted(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (proc?.isAlive == true) return@withContext true
        if (!ready(ctx)) return@withContext false
        val d = dir(ctx)
        File(d, "tools.json").writeText(toolsJson().toString())
        val pb = ProcessBuilder(runner(ctx).absolutePath, "--model", model(ctx).absolutePath,
            "--tools", File(d, "tools.json").absolutePath, "--serve", "--port", PORT.toString(), "--threads", "2", "--max", "160")
            .directory(d).redirectErrorStream(true)
        pb.environment()["NEEDLE_TELEMETRY"] = "0"; pb.environment()["DO_NOT_TRACK"] = "1"
        proc = pb.start()
        // Drain its output so the pipe never blocks.
        Thread { try { proc?.inputStream?.bufferedReader()?.forEachLine { } } catch (_: Exception) { } }.start()
        repeat(40) {
            if (proc?.isAlive != true) return@withContext false
            try { (URL("http://127.0.0.1:$PORT/reset").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 300; readTimeout = 500; doOutput = true
                outputStream.use { it.write("{}".toByteArray()) } }.responseCode; return@withContext true } catch (_: Exception) { }
            delay(150)
        }
        false
    }

    fun stop() { proc?.destroy(); proc = null }

    private fun post(path: String, body: String): String {
        val c = URL("http://127.0.0.1:$PORT$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 1000; c.readTimeout = 8000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * Try to handle [text] on the phone. Returns null to hand it to the cloud agent.
     * [offline] lowers the bar, since the cloud isn't an option.
     */
    suspend fun tryHandle(ctx: Context, text: String, offline: Boolean, confirm: suspend (String, String, String) -> Boolean): Result? {
        if (text.length > 160 || text.contains('\n')) return null
        // "open it for me", "send that": only the full agent knows what "it" is (v0.28.9).
        if (com.blaze.assistant.phone.AppMatch.refersBack(text)) { IntentCache.drop(text); return null }
        val t0 = System.currentTimeMillis()
        // Intent cache: a command that worked before runs again with no model at all.
        IntentCache.get(text)?.let { (n, a) ->
            val r = try { execute(ctx, n, a, confirm) } catch (_: Exception) { null }
            if (r != null && FAIL_STARTS.none { r.startsWith(it) }) return Result(r, System.currentTimeMillis() - t0, n)
            IntentCache.drop(text)
        }
        // Everyday commands by plain rules: instant and offline, no model needed.
        QuickRules.parse(text)?.let { (n, a) ->
            val r = try { execute(ctx, n, a, confirm) } catch (_: Exception) { null }
            if (r != null && FAIL_STARTS.none { r.startsWith(it) }) return Result(r, System.currentTimeMillis() - t0, n)
        }
        if (!useModel(offline) || !enabled(ctx) || compound(QuickRules.polite(text))) return null
        val out = lock.withLock {
            try {
                if (!ensureStarted(ctx)) return null
                withContext(Dispatchers.IO) {
                    post("/reset", "{}")   // each command stands alone
                    JSONObject(post("/complete", JSONObject().put("input", QuickRules.polite(text)).toString()))
                }
            } catch (_: Exception) { stop(); return null }
        }
        lastUse = System.currentTimeMillis()
        val calls = out.optJSONArray("function_calls") ?: return null
        if (out.optString("type") != "call" || calls.length() != 1) return null
        val conf = out.optDouble("confidence", 0.0)
        val ungrounded = out.optJSONObject("validation")?.optJSONArray("ungrounded")?.length() ?: 0
        val min = if (offline) 0.08 else Settings.fastLaneMin.toDouble()
        if (conf < min || (ungrounded > 0 && conf < 0.5 && !offline)) return null
        val call = calls.getJSONObject(0)
        val name = call.optString("name")
        val a = call.optJSONObject("arguments") ?: JSONObject()
        val reply = execute(ctx, name, a, confirm) ?: return null
        if (FAIL_STARTS.any { reply.startsWith(it) }) return null
        if (conf >= 0.5 && name !in setOf("send_text", "call_contact", "web_search")) IntentCache.put(text, name, a)
        return Result(reply, System.currentTimeMillis() - t0, name)
    }

    /**
     * Multi-step or search-style asks ("open YouTube and play lofi", "search for…") go to the
     * full agent: a one-shot model would only do half, or open a browser search.
     */
    private val multi = Regex("\\b(and|then|after that|search|find|look up|look for|google|with|about)\\b", RegexOption.IGNORE_CASE)
    private val playThing = Regex("^\\s*play\\s+\\S+\\s+\\S+", RegexOption.IGNORE_CASE)
    fun compound(text: String) = multi.containsMatchIn(text) || playThing.containsMatchIn(text) || text.count { it == ',' } > 0

    /** Idle runner is shut down to give memory back. */
    fun trimIfIdle() { if (proc != null && System.currentTimeMillis() - lastUse > 5 * 60_000) stop() }

    /** A fast-lane result starting with one of these is a failure: the full agent takes the request instead of a "Done". */
    val FAIL_STARTS = listOf("Couldn't", "Could not", "Error", "Unknown", "No app", "No installed app", "Not done", "Failed", "Can't", "Cannot")

    private suspend fun execute(ctx: Context, name: String, a: JSONObject, confirm: suspend (String, String, String) -> Boolean): String? = when (name) {
        "open_app" -> if (a.optString("name").lowercase().removePrefix("the ").trim() in setOf("browser", "blaze browser", "web browser", "your browser", "internet"))
            { com.blaze.assistant.browser.BrowserTools.show(ctx); "Browser's open." }
            else PhoneTools.run(ctx, "open_app", JSONObject().put("app_name", a.optString("name"))).let {
            if (it.startsWith("Opened") || it.startsWith("Opening")) it else null }
        "open_settings_page" -> com.blaze.assistant.phone.AndroidControl.run(ctx, "open_settings_page", a).takeIf { it.startsWith("Opened") }
        // 0.45.15 (device /phoneqa 22:40, timer 0/5: "Timer started for 2 min." from the intent cache, no timer running):
        // alarms and timers always go through the agent, which names the tool and reads the Clock back (ToolVerify).
        "set_alarm", "set_timer" -> null
        "flashlight", "wifi", "bluetooth", "mobile_data", "do_not_disturb", "airplane_mode" ->
            Skills.run(ctx, "device_setting", JSONObject().put("setting", if (name == "airplane_mode") "airplane" else name)
                .put("value", if (a.optString("state", "on") == "off") "off" else "on"))
        "speed_stats" -> SpeedLog.summary() + SpeedLog.firstWordLine() + SpeedLog.lastPhases().let { if (it.isBlank()) "" else "\nLast turn: $it" } + SpeedLog.screenLine()
        "tool_stats" -> ToolStats.report()
        "list_processes" -> Processes.list(status = "live")
        "toggle_setting" -> Skills.run(ctx, "device_setting", JSONObject().put("setting", a.optString("setting"))
            .put("value", if (a.optBoolean("on", true)) "on" else "off"))
        "set_volume" -> Skills.run(ctx, "device_setting", JSONObject().put("setting", "volume")
            .put("value", when (a.optString("level")) { "max" -> "100"; else -> a.optString("level") }))
        "battery_level" -> Skills.run(ctx, "device_setting", JSONObject().put("setting", "battery"))
        // 0.45.15 (review 22 Oct #7): "Done: play." / "Directions … are open." were said without checking anything. Media keys
        // and directions go through the agent, which reads the player state / the front app back (ToolVerify).
        "media_control" -> null
        "call_contact" -> Skills.run(ctx, "call_number", JSONObject().put("to", a.optString("name")))
        "send_text" -> Skills.run(ctx, "send_sms", JSONObject().put("to", a.optString("to")).put("message", a.optString("message")))
        "web_search" -> null   // the cloud agent searches properly (in-app or web_search)
        "navigate_to" -> null
        "blaze_self" -> SelfSetup.open(ctx, a.optString("target"))
        "life_logs" -> com.blaze.assistant.life.LifeLogs.run(ctx, a)
        else -> null
    }
}
