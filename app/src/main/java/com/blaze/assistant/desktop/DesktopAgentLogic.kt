package com.blaze.assistant.desktop

import org.json.JSONObject

/**
 * v0.39.0 hands-free desktop agent, pure part (DesktopAgentLogicTest). Each turn the vision model sees the newest
 * desktop screenshot and answers with ONE JSON step; this file parses it, decides whether the step needs the user's
 * tap (money, messages, deleting), builds the xdotool line for it and words the "Step n of m" line.
 */
object DesktopAgentLogic {
    const val MAX_STEPS = 40

    enum class Act { CLICK, DOUBLE_CLICK, RIGHT_CLICK, TYPE, KEY, SCROLL, RUN, WAIT, DONE, ASK }

    enum class Risk { NONE, MONEY, MESSAGE, DELETE }

    data class Step(
        val act: Act, val say: String, val x: Int = -1, val y: Int = -1, val text: String = "", val keys: String = "",
        val down: Boolean = true, val amount: Int = 3, val app: String = "", val risk: Risk = Risk.NONE,
        val total: Int = 0, val answer: String = "",
    )

    sealed class Parsed {
        data class Ok(val step: Step) : Parsed()
        data class Bad(val why: String) : Parsed()
    }

    /** The first balanced {...} object in a reply (handles ```json fences and words around it). */
    fun jsonIn(reply: String): String? {
        val s = reply.indexOf('{'); if (s < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in s until reply.length) {
            val ch = reply[i]
            if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
            when (ch) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return reply.substring(s, i + 1) }
            }
        }
        return null
    }

    private fun actOf(s: String): Act? = when (s.trim().lowercase().replace('-', '_').replace(' ', '_')) {
        "click", "left_click", "tap" -> Act.CLICK
        "double_click", "doubleclick" -> Act.DOUBLE_CLICK
        "right_click", "rightclick" -> Act.RIGHT_CLICK
        "type", "type_text", "write" -> Act.TYPE
        "key", "keys", "press", "hotkey" -> Act.KEY
        "scroll" -> Act.SCROLL
        "run", "open", "launch", "command" -> Act.RUN
        "wait", "sleep" -> Act.WAIT
        "done", "finish", "finished", "complete" -> Act.DONE
        "ask", "ask_user", "question" -> Act.ASK
        else -> null
    }

    private fun riskOf(s: String): Risk = when (s.trim().lowercase()) {
        "money", "payment", "pay", "purchase", "spend" -> Risk.MONEY
        "message", "send", "post", "email" -> Risk.MESSAGE
        "delete", "remove", "destroy" -> Risk.DELETE
        else -> Risk.NONE
    }

    private val moneyWords = Regex("(?i)\\b(pay|payment|buy|purchase|checkout|check out|place (the )?order|order now|subscribe|donate|card number|cvv|transfer money|send money)\\b")
    private val messageWords = Regex("(?i)\\b(send|post|publish|tweet|reply|email|message|submit)\\b")
    private val deleteWords = Regex("(?i)\\b(delete|remove|erase|wipe|empty (the )?trash|uninstall|format|rm -|rm\\s)")

    /** The step's risk: what the model said, or what its words show (whichever is not NONE first). */
    fun risk(step: Step): Risk {
        if (step.risk != Risk.NONE) return step.risk
        if (step.act == Act.WAIT || step.act == Act.DONE || step.act == Act.ASK || step.act == Act.SCROLL) return Risk.NONE
        val words = listOf(step.say, step.text, step.app).joinToString(" ")
        return when {
            moneyWords.containsMatchIn(words) -> Risk.MONEY
            deleteWords.containsMatchIn(words) || (step.act == Act.RUN && DesktopExtras.dangerous(step.app)) -> Risk.DELETE
            // Typing a message body isn't sending; clicking / pressing Send is.
            step.act != Act.TYPE && messageWords.containsMatchIn(step.say) -> Risk.MESSAGE
            else -> Risk.NONE
        }
    }

    /** One model reply to a step, or why it can't be used (sent back to the model). */
    fun parse(reply: String): Parsed {
        val js = jsonIn(reply) ?: return Parsed.Bad("Answer with one JSON object only.")
        val o = try { JSONObject(js) } catch (e: Exception) { return Parsed.Bad("That JSON didn't parse: ${e.message?.take(80)}") }
        val act = actOf(o.optString("action")) ?: return Parsed.Bad("Unknown action \"${o.optString("action")}\".")
        val say = o.optString("say").ifBlank { o.optString("step") }.lineSequence().firstOrNull().orEmpty().trim().take(80)
        val x = o.optInt("x", -1); val y = o.optInt("y", -1)
        val step = Step(act, say, x, y, o.optString("text"), o.optString("keys"),
            down = !o.optString("direction", o.optString("dir", "down")).trim().lowercase().startsWith("up"),
            amount = o.optInt("amount", 3).coerceIn(1, 15), app = o.optString("app").ifBlank { o.optString("command") },
            risk = riskOf(o.optString("risk")), total = o.optInt("total", o.optInt("plan_total", 0)).coerceIn(0, MAX_STEPS),
            answer = o.optString("answer").ifBlank { o.optString("summary") }.trim())
        return when (act) {
            Act.CLICK, Act.DOUBLE_CLICK, Act.RIGHT_CLICK -> if (x < 0 || y < 0) Parsed.Bad("A click needs x and y in the screenshot's pixels.") else Parsed.Ok(step)
            Act.TYPE -> if (step.text.isEmpty()) Parsed.Bad("type needs text.") else Parsed.Ok(step)
            Act.KEY -> if (DesktopNarration.keys(step.keys).isEmpty()) Parsed.Bad("key needs keys (xdotool names like ctrl+l Return).") else Parsed.Ok(step)
            Act.RUN -> if (step.app.isBlank()) Parsed.Bad("run needs app.") else Parsed.Ok(step)
            else -> Parsed.Ok(step)
        }
    }

    /** "Step 4 of 9" (m never below n). */
    fun progress(n: Int, total: Int): String = "Step $n of ${maxOf(n, total)}"

    /** The plain line the user reads under the live PC view. */
    fun line(step: Step): String = step.say.ifBlank {
        when (step.act) {
            Act.CLICK, Act.DOUBLE_CLICK, Act.RIGHT_CLICK -> "Clicking"
            Act.TYPE -> "Typing"
            Act.KEY -> "Pressing " + DesktopNarration.keys(step.keys)
            Act.SCROLL -> if (step.down) "Scrolling down" else "Scrolling up"
            Act.RUN -> "Opening " + DesktopNarration.appName(step.app)
            Act.WAIT -> "Waiting for the page"
            Act.DONE -> "Done"
            Act.ASK -> "Checking with you"
        }
    }.replaceFirstChar { it.uppercase() }

    /** Screenshot pixels to desktop pixels (screenshots are at most 1024 px on the long side). */
    fun scale(v: Int, deskW: Int, deskH: Int): Int = (v * maxOf(1.0, maxOf(deskW, deskH) / 1024.0)).toInt()

    /** The xdotool line for a step (null for wait / done / ask). Coordinates are already in desktop pixels. */
    fun command(step: Step, dx: Int = step.x, dy: Int = step.y): String? = when (step.act) {
        Act.CLICK -> DesktopPlan.clickCmd(dx, dy, 1, false)
        Act.DOUBLE_CLICK -> DesktopPlan.clickCmd(dx, dy, 1, true)
        Act.RIGHT_CLICK -> DesktopPlan.clickCmd(dx, dy, 3, false)
        Act.TYPE -> DesktopPlan.typeCmd(step.text)
        Act.KEY -> DesktopPlan.keyCmd(step.keys)
        Act.SCROLL -> scrollCmd(dx, dy, step.down, step.amount)
        Act.RUN -> DesktopPlan.runCmd(step.app)
        else -> null
    }

    fun scrollCmd(x: Int, y: Int, down: Boolean, amount: Int): String {
        val move = if (x >= 0 && y >= 0) "mousemove $x $y " else ""
        return "DISPLAY=${DesktopPlan.DISPLAY} xdotool ${move}click --repeat ${amount.coerceIn(1, 15)} ${if (down) 5 else 4}"
    }

    /** The instructions the vision model gets with every screenshot. */
    fun system(goal: String, w: Int, h: Int): String =
        "You operate a Linux desktop (XFCE) for the user, like a careful person at a computer. GOAL: $goal\n" +
            "Each turn you get a screenshot (${w}x$h pixels at most 1024 on the long side; give coordinates in THAT picture's pixels). " +
            "Reply with exactly ONE JSON object and nothing else:\n" +
            "{\"say\":\"one short plain-English line for a non-technical user, e.g. Filling in your surname\", \"action\":\"click|double_click|right_click|type|key|scroll|run|wait|done|ask\", " +
            "\"x\":0, \"y\":0, \"text\":\"for type\", \"keys\":\"for key, xdotool names like ctrl+l Return\", \"direction\":\"down|up\", \"amount\":3, " +
            "\"app\":\"for run: browser, terminal, files, editor or a command\", \"risk\":\"none|money|message|delete\", \"total\":\"your best guess of how many steps the whole task takes\", " +
            "\"answer\":\"for done/ask: what you did or what you need\"}\n" +
            "Rules: one action per turn; click a field before typing in it; use key Return to submit a search; scroll to find things below the fold. " +
            "Set risk to money for paying or ordering, message for sending or posting anything, delete for deleting: those wait for the user's tap. " +
            "Never type passwords or card numbers you were not given; use ask instead. When the goal is reached, action done with a one-line answer."

    /** What the model is told after each action (keeps the loop honest). */
    fun after(n: Int, result: String): String = "Step $n result: ${result.take(200)}. Here is the screen now."
}
