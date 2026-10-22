package com.blaze.assistant.desktop

import com.blaze.assistant.desktop.DesktopNarration.Live
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * v0.25.5: what the live desktop card shows while the agent works in the Linux desktop. [LinuxDesktop.run] calls
 * [begin] / [end] around each action; a small watcher notices when the agent's run ends (the card hides a few seconds
 * later) and resets the state. The last screenshot is kept as the card's picture until the live VNC view connects.
 */
object DesktopLive {
    private val _state = MutableStateFlow(Live())
    val state: StateFlow<Live> = _state
    /** The newest desktop screenshot (PNG bytes), shown when the live view isn't connected yet. */
    @Volatile var lastShot: ByteArray? = null; private set
    val shotVersion = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watch: Job? = null

    private fun now() = System.currentTimeMillis()

    val stopped: Boolean get() = _state.value.stopped

    fun begin(status: String, x: Int = -1, y: Int = -1) { _state.value = _state.value.begin(status, now(), x, y); watch() }
    fun end() { _state.value = _state.value.end(now()) }
    fun shot(png: ByteArray) { lastShot = png; shotVersion.value++ }

    /** The X on the card: stop the agent's desktop work for the rest of this run. */
    fun stop() { _state.value = _state.value.stop(); watch() }

    private fun busy(): Boolean = try { com.blaze.assistant.agent.Agent.busyChats.value.isNotEmpty() } catch (_: Throwable) { false }

    private fun watch() {
        if (watch?.isActive == true) return
        watch = scope.launch {
            while (true) {
                delay(500)
                val s = _state.value
                if (!busy() && (s.active || s.stopped)) _state.value = s.runEnded(now())
                val n = _state.value
                if (n.expired(now())) { _state.value = Live(); lastShot = null; break }
                if (!n.active && !n.stopped) break
            }
        }
    }
}
