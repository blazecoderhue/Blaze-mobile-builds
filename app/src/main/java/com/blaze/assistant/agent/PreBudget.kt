package com.blaze.assistant.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v0.45.3 speed: the optional look-ups before a turn's first model call (the screen read for orientation, specialist
 * matching, a remembered plan) get a time budget. The request goes out when the budget is spent; a late look-up is
 * dropped for this call (the model can still read the screen or look things up with its tools). The tool list, the
 * skills and the memory block are not optional and are never cut. Misses are named in the speed line ("late: orient").
 */
object PreBudget {
    const val ORIENT_MS = 400L
    const val SPECS_MS = 250L
    const val PLAN_MS = 250L

    /** [d]'s value if it is ready within [ms], else [fallback] (and [late] is told the [name]). */
    suspend fun <T> within(d: Deferred<T>, ms: Long, fallback: T, name: String, late: (String) -> Unit): T {
        if (d.isCompleted) return try { d.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { fallback }
        val r = try { withTimeoutOrNull(ms) { d.await() } } catch (e: CancellationException) { throw e } catch (_: Exception) { return fallback }
        if (r == null) { late(name); d.cancel(); return fallback }
        return r
    }
}
