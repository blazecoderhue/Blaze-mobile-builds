package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * The orient loop for phone control (v0.20.0): context → orientation → next action → orientation.
 *
 * Before Blaze acts on the screen it knows where it is: a compact summary of the accessibility
 * tree (the foreground app plus the handful of fields and buttons that matter), never a full dump
 * every step. After each action the agent already re-reads the screen as a diff (Agent.screenAfter),
 * which is the second orientation that verifies the result before the next step.
 *
 * Pure logic here (tested in OrientTest); the agent wires it to the accessibility service.
 */
object Orient {

    /** Screen actions that need to know where they are. */
    val acting = setOf("tap", "type_text", "scroll", "press_button", "run_plan", "do_actions")
    /** Tools whose result is itself a fresh look at the screen. */
    val looking = setOf("read_screen", "look_at_screen")
    /** Tools that change the screen by themselves (they're verified afterwards, not oriented before). */
    val moving = setOf("open_app", "open_url", "search_in_app", "browser_show")

    enum class Decision {
        /** Fine: Blaze knows where it is (or the action doesn't depend on the current screen). */
        GO,
        /** Act, but glance first and hand the model a compact "you were here" line with the result. */
        GLANCE,
        /** Don't act: the element numbers are stale or unknown. Look, return the screen, let the model aim again. */
        LOOK_FIRST,
    }

    /** What Blaze knows about the screen during one turn. */
    class State {
        /** Package of the app on screen at the last look this turn ("" = haven't looked yet). */
        @Volatile var pkg: String = ""
        @Volatile var looks: Int = 0
        @Volatile var bounced: Int = 0
        fun reset() { pkg = ""; looks = 0; bounced = 0 }
        fun saw(p: String) { if (p.isNotBlank()) { pkg = p; looks++ } }
        val oriented get() = pkg.isNotBlank()
    }

    /** True when this call aims at an element by its number (which only means something for the screen it was read from). */
    fun byNumber(name: String, args: JSONObject): Boolean = when (name) {
        "tap" -> args.has("element") && args.optString("text").isBlank()
        "type_text" -> args.has("element")
        "scroll" -> args.has("element")
        else -> false
    }

    /**
     * Decide what to do before running [name]. [nowPkg] is the app in front right now ("" if unknown).
     * A numbered action on a screen Blaze hasn't looked at (or after the app changed under it) is
     * bounced once with the fresh screen; a second bounce in the same turn just glances and goes, so
     * the guard can never loop.
     */
    fun decide(name: String, args: JSONObject, s: State, nowPkg: String): Decision {
        if (name !in acting) return Decision.GO
        val moved = nowPkg.isNotBlank() && s.oriented && nowPkg != s.pkg
        if (byNumber(name, args) && (!s.oriented || moved)) return if (s.bounced >= 2) Decision.GLANCE else Decision.LOOK_FIRST
        if (name == "run_plan" && startsByOpening(args)) return Decision.GO
        if (!s.oriented || moved) return Decision.GLANCE
        return Decision.GO
    }

    /**
     * A run_plan that opens an app (or a settings page) first doesn't depend on what's on screen now. v0.30.7: run_plan
     * steps say "do", not "action", so before this every such plan paid for an extra full screen read and glance.
     */
    fun startsByOpening(args: JSONObject): Boolean {
        val steps = args.optJSONArray("steps") ?: return false
        val first = steps.optJSONObject(0) ?: return false
        return first.optString("do").ifBlank { first.optString("action") } in setOf("open_app", "open_url", "search_in_app", "open_settings")
    }

    private val kept = Regex("^\\[\\d+] (field|btn|switch|check|radio|link|item)\\b")
    private val header = Regex("^(.+?) \\(([\\w.]+)\\)\\. Elements")

    /**
     * Compact summary of a read_screen result: the app line, then fields first, then buttons and
     * other tappable things, then a few texts (titles, errors), at most [maxLines] lines and
     * [maxChars] characters, with numbers kept so the model can act on them.
     */
    fun compact(screen: String, maxLines: Int = 14, maxChars: Int = 900): String {
        val lines = screen.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return "Screen: nothing readable."
        val head = lines.first()
        val app = header.find(head)?.groupValues?.get(1) ?: head.substringBefore(". Elements").take(60)
        val items = lines.drop(1).filter { it.startsWith("[") }
        if (items.isEmpty()) return "Screen: $app. ${lines.drop(1).joinToString(" ").take(200)}".trim()
        fun rank(l: String) = when {
            l.contains("] field") -> 0
            Regex("(?i)error|wrong|incorrect|not support|failed|invalid|denied").containsMatchIn(l) -> 0
            kept.containsMatchIn(l) -> 1
            else -> 2
        }
        val picked = items.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).take(maxLines).sortedBy { it.index }.map { it.value }
        val sb = StringBuilder("Screen: ").append(app).append(" (").append(items.size).append(" elements)")
        for (l in picked) {
            val line = if (l.length > 90) l.take(88) + "…\"" else l
            if (sb.length + line.length + 1 > maxChars) break
            sb.append('\n').append(line)
        }
        val more = items.size - picked.size
        if (more > 0) sb.append("\n(+").append(more).append(" more; read_screen for all)")
        return sb.toString()
    }

    /** What the model gets when a numbered action was held back. */
    fun lookFirst(name: String, screen: String): String =
        "Not done yet: I looked at the screen first, because the element numbers you used weren't from this screen. " +
            "Screen now:\n$screen\nCall $name again with a number from this screen (or use its visible text)."

    /** The "you were here" line put in front of an action's result after a glance. */
    fun glanceNote(compact: String): String = "[Before acting] $compact\n\n"

    /** The line added to the first message of a phone-control turn (the "context" step), or "" when not useful. */
    fun contextNote(compact: String): String =
        if (compact.isBlank()) "" else "[Where the phone is right now, before you act]\n$compact\n[Send the whole task at once (run_plan, or several calls in one reply): the phone checks each step itself and you get the screen at the end.]"

    /** One-line prompt rule. */
    const val RULE = "ORIENT LOOP for phone control: context → plan → act → look. You get a compact screen summary before your first screen action. Plan the whole task and send it in ONE reply (run_plan by visible words, or several calls in a row): the phone checks each step itself and you get the screen once, at the end or where it got stuck. Don't spend a reply per tap. Numbered taps on a screen you haven't seen are bounced back with the fresh screen."
}
