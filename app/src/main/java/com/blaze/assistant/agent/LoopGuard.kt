package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * v0.45.3: a turn never spins on a tool that does nothing. Seen on a phone: "@halo yes, clean them up" (remove
 * duplicate memories by their #ids) ran from 14:43 to 14:52 until the user tapped Stop. There was no way to delete a
 * memory by its id, so `forget "#12"` answered "Nothing matched" and the model tried again and again (Autopilot
 * allows 150 steps / 45 min).
 *
 *  - The same call (tool + arguments) that already gave the same answer twice in one turn is not run a third time:
 *    the model is told it already ran twice (a re-check that gets a NEW answer is fine).
 *  - [NO_EFFECT_MAX] results in a row from one tool that changed nothing ("Nothing matched", "No saved line #9",
 *    "not found") block that tool for the rest of the turn.
 *  - Screen, desktop and wait tools are exempt (repeating them is how a phone task makes progress).
 * Pure Kotlin; LoopGuardTest.
 */
class LoopGuard {
    companion object {
        const val SAME_MAX = 2
        const val NO_EFFECT_MAX = 3
        val EXEMPT = setOf("tap", "scroll", "swipe", "press_button", "type_text", "read_screen", "look_at_screen", "screenshot",
            "wait", "wait_for", "terminal_jobs", "check_agent", "browser_scroll", "browser_read", "ask_user", "find_tool", "load_tools", "run_plan")
        private val noEffect = Regex("""(?i)^(nothing matched|nothing deleted|no saved line|not found|no such|nothing to (save|delete|remove)|couldn't find|no match)|\bnothing matched\b|\bnot found\b""")
        fun noEffect(result: String): Boolean = noEffect.containsMatchIn(result.trim().take(200))

        fun sameNote(name: String) = "Not run: $name with exactly these arguments already ran twice in this task and didn't get anywhere. " +
            "Don't call it again. Use a different approach (for memories: memory_delete with the #ids from memory_search), or tell the user plainly what was done and what wasn't."
        fun blockedNote(name: String) = "Not run: $name changed nothing $NO_EFFECT_MAX times in a row, so it is stopped for this task. " +
            "Don't call it again. Tell the user plainly what was done and what couldn't be done."
    }

    private val seen = HashMap<String, Int>()
    private val misses = HashMap<String, Int>()
    private val lastOf = HashMap<String, String>()

    /** The note to return instead of running [name], or null to run it. */
    @Synchronized fun check(name: String, args: JSONObject): String? {
        if (name in EXEMPT) return null
        if ((misses[name] ?: 0) >= NO_EFFECT_MAX) return blockedNote(name)
        if ((seen[OneShot.key(name, args)] ?: 0) >= SAME_MAX) return sameNote(name)
        return null
    }

    @Synchronized fun record(name: String, args: JSONObject, result: String) {
        if (name in EXEMPT) return
        val k = OneShot.key(name, args)
        val r = result.take(4_000)
        seen[k] = if (lastOf[k] == r) (seen[k] ?: 0) + 1 else 1
        lastOf[k] = r
        misses[name] = if (noEffect(result)) (misses[name] ?: 0) + 1 else 0
    }

    @Synchronized fun clear() { seen.clear(); misses.clear(); lastOf.clear() }
}
