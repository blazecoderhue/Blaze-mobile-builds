package com.blaze.assistant.agent

/**
 * v0.45.0 (#17) one model family for the whole turn. Each step of a turn is its own gateway request, so after a
 * rate-limit or a health mark step 2 of a phone task could land on a different model than step 1, with other tool-call
 * habits (more text-form calls and loops). Now every request of a turn carries:
 *
 *  - `X-Blaze-Turn: <id>`: the same id for every step (and for a Cloud run's create), so Blaze Cloud can keep the turn
 *    on one route;
 *  - `X-Blaze-Prefer: provider/model`: the route that served the turn's first step ([pin]), to be tried first while it
 *    is healthy (the gateway still falls back on an error).
 *
 * Both are plain headers, so a gateway that doesn't read them yet ignores them. The phone also counts how often the
 * route changed inside a turn ([switches]) for the speed log. Pure Kotlin; TurnModelTest.
 */
class TurnModel(val id: String) {
    /** The route ("provider/model") that served this turn's first answered step; "" until then. */
    @Volatile var pin: String = ""
        private set
    @Volatile var last: String = ""
        private set
    /** Steps answered by a different route than the step before. */
    @Volatile var switches = 0
        private set

    /** Records the route that answered one step ("" = unknown: ignored). */
    fun served(route: String) {
        val r = route.trim()
        if (r.isEmpty()) return
        if (pin.isEmpty()) pin = r
        if (last.isNotEmpty() && last != r) switches++
        last = r
    }

    /** Headers for one gateway request of this turn. */
    fun headers(): Map<String, String> = buildMap {
        put(TURN_HEADER, safe(id))
        if (pin.isNotEmpty()) put(PREFER_HEADER, safe(pin))
    }

    /** The speed-log note ("" when the route never changed). */
    fun line(): String = if (switches > 0) "route changed $switches×" else ""

    companion object {
        const val TURN_HEADER = "X-Blaze-Turn"
        const val PREFER_HEADER = "X-Blaze-Prefer"

        /** Header-safe: visible ASCII only, at most 120 chars. */
        fun safe(s: String): String = s.filter { it in '!'..'~' }.take(120)

        /** The turn being answered now (null = none: a background call). */
        /** v0.45.0 (#3): per turn (CallState), so two chats working at once never share a route pin. */
        var current: TurnModel? get() = CallState.current().turnModel; set(v) { CallState.current().turnModel = v }
    }
}
