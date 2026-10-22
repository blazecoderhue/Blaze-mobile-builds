package com.blaze.assistant.desktop

import android.content.Context
import com.blaze.assistant.agent.LlmClient
import com.blaze.assistant.desktop.DesktopAgentLogic.Act
import com.blaze.assistant.desktop.DesktopAgentLogic.Parsed
import com.blaze.assistant.desktop.DesktopAgentLogic.Risk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.39.0 hands-free Linux desktop: Blaze works the desktop by itself, like a person at a computer. One tool call
 * (`desktop_task goal=…`) runs the loop: screenshot → the vision model picks ONE step (DesktopAgentLogic) → xdotool
 * does it → a fresh screenshot, until the model says done, needs the user, or the step cap is hit.
 *
 * The user watches it in the chat (ui/DesktopTaskCard: a watch-only live view, "Step n of m", Watch full screen /
 * Pause / Stop). Paying, sending and deleting still wait for the user's tap ([Risk]); everything else runs on its own
 * once the task is approved (Autopilot passes the first card by itself).
 */
object DesktopAgent {
    const val TOOL = "desktop_task"

    data class Run(
        val active: Boolean = false, val goal: String = "", val n: Int = 0, val total: Int = 0, val line: String = "",
        val paused: Boolean = false, val stopped: Boolean = false, val result: String = "",
    ) {
        val progress: String get() = if (n <= 0) "Getting started" else DesktopAgentLogic.progress(n, total)
    }

    private val _state = MutableStateFlow(Run())
    val state: StateFlow<Run> = _state

    fun pause() { _state.value = _state.value.copy(paused = true) }
    fun resume() { _state.value = _state.value.copy(paused = false) }
    fun stop() { _state.value = _state.value.copy(stopped = true, paused = false) }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Do a whole task on the Linux desktop by yourself, hands-free, like a person at a computer: browse sites, fill in forms, " +
            "use desktop apps. It looks at the screen, clicks, types, presses keys, scrolls and opens apps step by step until the goal is reached, " +
            "while the user watches a live view in the chat (they can pause or stop it). Paying, sending or posting and deleting still ask the user. " +
            "Starts the desktop if it's installed but not running. Use it instead of many separate desktop clicks for anything that takes several steps, " +
            "and for ANY request to do something on the Linux desktop (\"open the browser in it\", open an app, visit a site): not run_shell, and never tell the user to use a VNC client. " +
            "Returns what was done, or what it needs from the user.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("goal", JSONObject().put("type", "string").put("description", "The task in plain words, with every detail needed (names, what to type, the site)."))
            .put("title", JSONObject().put("type", "string").put("description", "2-5 words for the live card, e.g. \"Filling in the form\""))
            .put("max_steps", JSONObject().put("type", "integer").put("description", "Step cap, default 25, at most 40")))
            .put("required", JSONArray(listOf("goal")))))

    /**
     * Runs the loop. [confirm] is Blaze's approval card (always = true makes it show even under Autopilot);
     * [open] shows the Desktop view when the desktop has to be installed first.
     */
    suspend fun run(ctx: Context, args: JSONObject, confirm: suspend (String, String, Boolean) -> Boolean, open: () -> Unit): String {
        val goal = args.optString("goal").trim()
        if (goal.isBlank()) return "desktop_task needs a goal."
        if (!LinuxDesktop.cpuOk()) return DesktopPlan.UNSUPPORTED
        if (!LinuxDesktop.alive()) {
            if (!LinuxDesktop.installed()) { open(); return "The Linux desktop isn't installed yet. Call desktop action=start first (it asks the user and installs), then try again." }
            val r = LinuxDesktop.start(ctx)
            if (!LinuxDesktop.alive()) return "Couldn't start the desktop: $r"
        }
        val title = args.optString("title").ifBlank { goal.take(40) }
        if (!confirm("Let Blaze use the computer by itself?", "$title. You can watch, pause or stop it. Paying, sending and deleting still ask you.", false))
            return "The user said no; nothing was done on the desktop."
        val max = args.optInt("max_steps", 25).coerceIn(3, DesktopAgentLogic.MAX_STEPS)
        _state.value = Run(active = true, goal = title, line = "Taking a look")
        DesktopLive.begin("Taking a look")
        val log = ArrayList<String>()
        var bad = 0
        var lastResult = "Started."
        try {
            var n = 0
            while (n < max) {
                while (_state.value.paused && !_state.value.stopped) delay(300)
                if (_state.value.stopped || DesktopLive.stopped) return finish("The user stopped it at step $n.", log)
                val shot = LinuxDesktop.screenshot(ctx) ?: return finish("Couldn't see the desktop (screenshot failed).", log)
                val w = LinuxDesktop.running.w; val h = LinuxDesktop.running.h
                val msgs = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", DesktopAgentLogic.system(goal, minOf(w, 1024), if (w > 1024) h * 1024 / w else h)))
                    .put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text",
                            (if (log.isEmpty()) "" else "Steps so far:\n" + log.takeLast(12).joinToString("\n") + "\n") + DesktopAgentLogic.after(n, lastResult)))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", shot)))))
                val reply = try { LlmClient.complete(msgs, null, lane = LlmClient.Lane.BACKGROUND).optString("content") }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { return finish("The vision model didn't answer (${e.message?.take(80)}).", log) }
                val step = when (val p = DesktopAgentLogic.parse(reply)) {
                    is Parsed.Bad -> { bad++; lastResult = "Not done: ${p.why}"; if (bad > 3) return finish("The model kept giving steps I couldn't use.", log); continue }
                    is Parsed.Ok -> p.step
                }
                bad = 0
                if (step.act == Act.DONE) return finish("Done: " + step.answer.ifBlank { step.say }, log, done = true)
                if (step.act == Act.ASK) return finish("Paused for the user: " + step.answer.ifBlank { step.say } + " Ask them, then call desktop_task again to carry on.", log)
                n++
                val line = DesktopAgentLogic.line(step)
                val dx = DesktopAgentLogic.scale(step.x, w, h); val dy = DesktopAgentLogic.scale(step.y, w, h)
                _state.value = _state.value.copy(n = n, total = maxOf(step.total, n), line = line)
                when (DesktopAgentLogic.risk(step)) {
                    Risk.NONE -> { }
                    Risk.MONEY -> if (!confirm("Spend money on the computer?", "$line. Blaze is about to pay or order.", true)) return finish("The user said no to paying at step $n.", log)
                    Risk.MESSAGE -> if (!confirm("Send this from the computer?", line + (if (step.text.isNotBlank()) ": \u201c${step.text.take(160)}\u201d" else ""), true)) return finish("The user said no to sending at step $n.", log)
                    Risk.DELETE -> if (!confirm("Delete on the computer?", line + (if (step.app.isNotBlank()) ": ${step.app.take(160)}" else ""), true)) return finish("The user said no to deleting at step $n.", log)
                }
                if (_state.value.stopped) return finish("The user stopped it at step $n.", log)
                DesktopLive.begin(line, if (step.x >= 0) dx else -1, if (step.y >= 0) dy else -1)
                lastResult = if (step.act == Act.WAIT) { delay(2_000); "waited" } else {
                    val cmd = DesktopAgentLogic.command(step, dx, dy) ?: ""
                    if (cmd.isBlank()) "nothing to do" else LinuxDesktop.exec(cmd, if (step.act == Act.TYPE) 60 else 15).let { if (it.contains("rror")) it.take(120) else "ok" }
                }
                DesktopLive.end()
                log += "$n. $line (${lastResult.take(40)})"
                delay(if (step.act == Act.RUN) 2_500 else 800)
            }
            return finish("Stopped after $max steps without finishing.", log)
        } finally {
            _state.value = _state.value.copy(active = false, paused = false)
            DesktopLive.end()
        }
    }

    private fun finish(head: String, log: List<String>, done: Boolean = false): String {
        _state.value = _state.value.copy(result = head, line = if (done) "Done" else _state.value.line)
        try { com.blaze.assistant.agent.Awareness.did("desktop", "Used the computer: ${_state.value.goal.take(60)}" + if (done) "" else " (stopped)") } catch (_: Throwable) { }
        return head + if (log.isEmpty()) "" else "\nSteps:\n" + log.joinToString("\n")
    }
}
