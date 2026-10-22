package com.blaze.assistant.ui

/**
 * v0.45.6 (user 21:34-21:40: "Done. Your 3D rocket is open…" while nothing rendered): what the Design view last proved
 * about each design file. The view reports PENDING when it starts loading, RENDERED on a load / draw event from the
 * WebView (a 3D page only after non-background pixels), FAILED with the real reason. The agent waits briefly on it after
 * the design tool and grounds its reply in it ([com.blaze.assistant.agent.DesignTruth]). Thread-safe, pure.
 */
object DesignStatus {
    enum class State { PENDING, RENDERED, FAILED }
    data class Status(val state: State, val detail: String, val at: Long)

    /** Bumped by the agent after the design tool changes a file, so an open Design view reloads it now (not at turn end). */
    val reload = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun requestReload() { reload.value = reload.value + 1 }

    private val map = java.util.concurrent.ConcurrentHashMap<String, Status>()

    private fun key(path: String) = path.trim().removePrefix("file://")

    fun pending(path: String, now: Long = System.currentTimeMillis()) { if (path.isNotBlank()) map[key(path)] = Status(State.PENDING, "", now) }
    fun rendered(path: String, detail: String, now: Long = System.currentTimeMillis()) { if (path.isNotBlank()) map[key(path)] = Status(State.RENDERED, detail, now) }
    fun failed(path: String, detail: String, now: Long = System.currentTimeMillis()) { if (path.isNotBlank()) map[key(path)] = Status(State.FAILED, detail, now) }
    fun of(path: String): Status? = map[key(path)]
    fun clear() = map.clear()

    /** The status of [path] reported at or after [since], or null when the view hasn't said anything new yet. */
    fun since(path: String, since: Long): Status? = of(path)?.takeIf { it.at >= since }
}
