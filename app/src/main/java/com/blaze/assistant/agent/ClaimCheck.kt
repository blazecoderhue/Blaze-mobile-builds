package com.blaze.assistant.agent

/**
 * v0.44.0 the final self-check on tool turns. Before the final reply is shown, every claim in it ("sent", "alarm set",
 * "Wi-Fi is on", "the desktop is running", "opened WhatsApp") must be backed by a tool result from THIS turn whose
 * status is verified ([ToolVerify]). Deterministic first, the model only when needed:
 *
 *  - backed by a verified result (the newest call of that kind) → the reply stands ([Outcome.Ok]);
 *  - only by a result that ran but wasn't confirmed, or failed → the claim sentences are rewritten on the phone to
 *    "I tried to … but couldn't confirm it worked (why)." / "… but it didn't work: why." ([Outcome.Rewrite]), no model call;
 *  - by nothing at all → [Outcome.Unbacked]: the loop's existing single re-prompt asks the model to really do it
 *    (ActionProof.nudge), then the honest line (ActionProof.honest).
 *
 * The claim patterns are ActionProof's plus [RUNNING] ("X is running / up / started", the desktop case). Pure Kotlin;
 * ClaimCheckTest.
 */
object ClaimCheck {
    /** One tool call this turn and its verdict. */
    data class Done(val name: String, val status: ToolVerify.Status, val why: String = "")

    sealed class Outcome {
        object Ok : Outcome()
        data class Rewrite(val text: String, val action: ActionProof.Action) : Outcome()
        data class Unbacked(val action: ActionProof.Action) : Outcome()
    }

    private val ic = setOf(RegexOption.IGNORE_CASE)
    private fun r(s: String) = Regex(s, ic)

    /** "The Linux desktop is running", "Dillo is open now", "the server is up", "I've started the desktop". */
    val RUNNING = ActionProof.Action("running", "get that running",
        r("""\b(?:is|are|it's|they're)\s+(?:now\s+|already\s+|all\s+)?(?:running|up and running|listening|started|booted)\b|""" +
            """\b(?:i|i've|i have|i just|i've just)\s+(?:also\s+|now\s+|just\s+|successfully\s+)?(?:started|launched|booted|brought up|spun up|fired up)\b|""" +
            """^\s*(?:started|launched|booted)\b"""),
        setOf("desktop", "desktop_task", "run_shell", "termux_run", "preview_server", "linux_install", "open_app", "start_agent", "ask_pc",
            "process", "terminal_jobs", "browser_open", "open_url"),
        r("""\b(start|launch|run|running|boot|desktop|linux|vnc|xfce|dillo|server|serve)\b"""))

    val ACTIONS: List<ActionProof.Action> = ActionProof.ACTIONS + RUNNING

    private fun sentences(text: String): List<String> = text.split(Regex("""(?<=[.!?…])\s+|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }

    /** The newest call of [a]'s kind this turn, or null. */
    private fun newest(done: List<Done>, a: ActionProof.Action): Done? = done.lastOrNull { it.name in a.tools }

    /** Review [reply] against this turn's [done] calls. [userText] = the request (claims about other things are ignored). */
    fun review(reply: String, done: List<Done>, userText: String, intentTools: Collection<String> = emptyList()): Outcome {
        if (reply.isBlank()) return Outcome.Ok
        var text = reply
        var rewrote: ActionProof.Action? = null
        for (a in ACTIONS) {
            if (!ActionProof.asked(userText, a, intentTools)) continue
            val bad = ActionProof.claims(text, a) + (if (a.id == "setting") stateClaims(text, userText).filter { it !in ActionProof.claims(text, a) } else emptyList())
            if (bad.isEmpty()) continue
            // v0.45.0 (#4): a verified STRONG tool backs the claim; a weak one (the settings page for "Wi-Fi is on")
            // never does on its own, whatever its own check said.
            if (done.any { it.name in a.strong && it.status == ToolVerify.Status.VERIFIED && done.lastOrNull { d -> d.name in a.strong }?.status == ToolVerify.Status.VERIFIED }) continue
            val last = newest(done, a)?.let { d ->
                if (d.name in a.weak && d.status == ToolVerify.Status.VERIFIED && !readBack(a, d, direction(bad)))
                    d.copy(status = ToolVerify.Status.UNVERIFIED, why = unprovenWhy(a, d, direction(bad))) else d
            }
            if (last == null) {
                // Some other verified tool of the group is fine for the older, looser ActionProof check (kept as is).
                return if (rewrote != null) Outcome.Rewrite(text, rewrote) else Outcome.Unbacked(a)
            }
            if (last.status == ToolVerify.Status.VERIFIED) continue
            text = rewrite(text, bad, a, last)
            rewrote = a
        }
        return if (rewrote != null) Outcome.Rewrite(text, rewrote) else Outcome.Ok
    }

    /**
     * v0.45.13: a weak tool whose own check read back exactly this claim does prove it: search_in_app whose check saw
     * the media session playing, a tap on a switch read back on/off.
     */
    fun readBack(a: ActionProof.Action, d: Done, claimed: Boolean? = null): Boolean = when (a.id) {
        "music" -> d.name == "search_in_app" && d.why.contains(" is playing ")
        // v0.45.13 round 2: only a tap asked for on/off (want) whose switch was READ BACK that way proves "turned X on":
        // Agent's tap adds "the switch “X” now reads on" only then. A bare tap, or one read back the other way, doesn't.
        "setting" -> d.name == "tap" && switchRead(d.why)?.let { claimed == null || it == claimed } == true
        else -> false
    }

    private val switchRe = Regex("""^the switch \u201c[^\u201d]*\u201d now reads (on|off)\b""")

    /** The state a tap's read-back saw (true = on), or null when the tap carried no read-back. */
    fun switchRead(why: String): Boolean? = switchRe.find(why.trim())?.groupValues?.get(1)?.let { it == "on" }

    /** The state the claim sentences say (true = on, false = off), or null when they say neither or both. */
    fun direction(bad: List<String>): Boolean? {
        val j = bad.joinToString(" ")
        val on = Regex("""(?i)\b(on|enabled)\b""").containsMatchIn(j)
        val off = Regex("""(?i)\b(off|disabled)\b""").containsMatchIn(j)
        return if (on == off) null else on
    }

    /** What wasn't confirmed, for the honest line, when a weak tool can't back the claim. */
    fun unprovenWhy(a: ActionProof.Action, d: Done, claimed: Boolean?): String {
        if (a.id == "setting" && d.name == "tap") {
            val read = switchRead(d.why)
            val w = when (claimed) { true -> "on"; false -> "off"; null -> "on or off" }
            return if (read == null) "the tap wasn't asked to turn it $w, and nothing read the switch back as $w"
                else "the switch read back ${if (read) "on" else "off"}, not $w"
        }
        return "only ${d.name.replace('_', ' ')} ran, which doesn't confirm it"
    }

    private val changeAsk = Regex("""(?i)\b(turn|switch|toggle|enable|disable|set|put|change)\b""")
    private const val SETTING_NAMES = "wi-?fi|bluetooth|flashlight|torch|hotspot|airplane mode|flight mode|dark mode|dark theme|night mode|do not disturb|dnd|mobile data|location|gps|nfc|auto-?rotate|rotation|battery saver|power saving|eye comfort|always on display"
    private val stateRe = Regex("""(?i)\b(?:$SETTING_NAMES)\b[^.!?\n]{0,20}\b(?:is|are|'s)\s+(?:now\s+|already\s+)?(?:on|off|enabled|disabled)\b|\b(?:it'?s|it is)\s+(?:now\s+)?(?:on|off|enabled|disabled)\b""")
    private val stateNeg = Regex("""(?i)\b(not|never|couldn't|could not|didn't|did not|wasn't|isn't|haven't|hasn't|unable|failed|can't|cannot|won't|if|until|want me|shall i|do you want|confirm)\b|\?\s*$""")

    /**
     * v0.45.13 round 2 (rpS settings-value #5: "Dark mode is on." after a bare tap passed unchecked): on a request to
     * change a setting, "X is on" / "it's on now" is a claim too. Only when the user asked to change something, so an
     * answer to "is Wi-Fi on?" isn't one.
     */
    fun stateClaims(text: String, userText: String): List<String> {
        if (!changeAsk.containsMatchIn(userText)) return emptyList()
        return sentences(text).filter { stateRe.containsMatchIn(it) && !stateNeg.containsMatchIn(it) }
    }

    /** The honest line for an unconfirmed or failed [d]. */
    fun line(a: ActionProof.Action, d: Done): String {
        val why = d.why.trim().trimEnd('.').takeIf { it.isNotBlank() && it != "not checked yet" }
        return when (d.status) {
            ToolVerify.Status.FAILED -> "I tried to ${a.what}, but it didn't work" + (why?.let { ": $it." } ?: ".")
            else -> "I tried to ${a.what}, but I couldn't confirm it worked" + (why?.let { " ($it)." } ?: ".")
        }
    }

    /** [text] with the claim sentences [bad] replaced by one honest line (where the first one was); the rest keeps its layout. */
    fun rewrite(text: String, bad: List<String>, a: ActionProof.Action, d: Done): String {
        var t = text
        var put = false
        for (s in bad.distinct()) {
            val i = t.indexOf(s)
            if (i < 0) continue
            // A list item keeps its marker ("- ", "* ", "• ", "1. ").
            val marker = Regex("""^\s*(?:[-*\u2022]|\d+[.)])\s+""").find(s)?.value.orEmpty()
            t = t.substring(0, i) + (if (!put) marker + line(a, d) else "") + t.substring(i + s.length)
            put = true
        }
        if (!put) t = line(a, d) + "\n\n" + t
        return t.replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }
}
