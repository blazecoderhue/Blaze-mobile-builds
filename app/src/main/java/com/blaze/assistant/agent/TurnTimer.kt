package com.blaze.assistant.agent

/**
 * v0.41.0 speed: where one turn's time goes, phase by phase ("tools 12 ms · route 3 ms · prep 41 ms · prompt 18 ms ·
 * first word 1.9 s · model 4.2 s · tool work 9.8 s"). The agent loop marks phases; the line goes to logcat
 * (tag BlazeSpeed), the crash/slow log and the speed log, so a slow answer can be traced to its phase on any phone.
 * Pure (TurnTimerTest): the clock is passed in.
 */
class TurnTimer(private val clock: () -> Long = System::currentTimeMillis) {
    val start = clock()
    private var last = start
    private val phases = LinkedHashMap<String, Long>()

    /** Ends the phase running since the previous mark (or the start) and books it under [name] (adds up on repeats). */
    fun mark(name: String): Long {
        val now = clock()
        val d = (now - last).coerceAtLeast(0)
        phases[name] = (phases[name] ?: 0L) + d
        last = now
        return d
    }

    /** Books a duration measured elsewhere (e.g. time to first token inside a model call), without moving the mark. */
    fun add(name: String, ms: Long) { phases[name] = (phases[name] ?: 0L) + ms.coerceAtLeast(0) }

    /** Moves the mark to now without booking anything (time already accounted for in [add]). */
    fun skip() { last = clock() }

    fun get(name: String): Long = phases[name] ?: 0L
    fun total(): Long = (clock() - start).coerceAtLeast(0)
    fun phases(): Map<String, Long> = phases.toMap()

    /** Time before the first model request went out: what the phone itself spent. */
    fun prepMs(): Long = PREP.sumOf { get(it) }

    fun slowest(): Pair<String, Long>? = phases.maxByOrNull { it.value }?.toPair()

    fun line(): String = phases.entries.filter { it.value > 0 || it.key in PREP }.joinToString(" · ") { (k, v) -> "$k ${fmt(v)}" } +
        " · total ${fmt(total())}"

    companion object {
        /** The on-phone phases before the first model call. */
        val PREP = listOf("tools", "route", "prep", "prompt")
        fun fmt(ms: Long): String = if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000.0)
    }
}
