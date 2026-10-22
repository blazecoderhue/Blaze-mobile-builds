package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * v0.30.4 fast phone actions: the pure rules behind making a simple phone action ("stop GitHub opening links in
 * the GitHub app") take a few seconds and a few steps instead of 18 steps and 4+ minutes. Pure (FastActionsTest).
 *
 *  - [plainDevice]: a plain device / settings action. It gets no skill packs, no specialists and no skills lookup
 *    (they were picked by word overlap: "github" + "app" pulled in android-app-builder and git-pr-workflow, ≈ 6,000
 *    chars, before the first call) and the screen tools up front, so open_settings + tap can go in one reply.
 *  - [isScreen] / [stripScreen]: which tool results carry a screen readout, so only the newest is sent in full.
 *    Since v1.9.7 the check looked for "Foreground app:", a header readScreen no longer writes, so EVERY old screen
 *    went to the model on every step and the prompt grew with each tap.
 *  - [screenReadAt]: several screen actions in one reply run back to back with a short settle, and the screen is read
 *    once, after the last of them.
 *  - [PlanRun] / [skipDone]: a run_plan that failed at step k left the phone on step k's screen; if the model sends
 *    the same script again, the k-1 steps already done are skipped instead of reopening the app from scratch.
 */
object FastActions {

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val verb = r("""^(?:hey blaze,?\s+|blaze,?\s+|ok(?:ay)?,?\s+)?(?:please\s+|pls\s+)?(?:can you\s+|could you\s+|would you\s+)?(?:please\s+)?""" +
        """(stop|turn|switch|set|change|make|disable|enable|allow|block|don'?t let|do not let|let|open|show|go to|take me to|clear|mute|unmute|""" +
        """toggle|prevent|keep|put|lower|raise|increase|decrease|reduce|remove (?:the )?(?:notification|permission)|give|revoke|grant|connect|disconnect|forget)\b""")
    private val noun = r("""\b(settings?|links?|default (?:apps?|browser)|by default|notifications?|wi-?fi|bluetooth|battery|brightness|location|gps|permissions?|""" +
        """app info|storage|cache|dark (?:mode|theme)|night (?:light|mode)|ringtone|ringer|volume|hotspot|airplane|flight mode|data saver|mobile data|screen timeout|""" +
        """font size|keyboard|vpn|nfc|auto-?rotat\w*|rotation|do not disturb|dnd|wallpaper|lock ?screen|fingerprint|developer options|usb debugging|""" +
        """unknown (?:apps|sources)|background (?:data|usage|activity)|overlay|display over|pop-?ups?|auto-?update|language|date|time zone)\b""")
    private val notPlain = r("""\b(build|code|coding|repo|repository|commit|pull request|\bpr\b|merge|write|draft|essay|research|compare|explain|why|""" +
        """how (?:do|can|should) i|how to|what is|what's|summari[sz]e|email|e-mail|send|post|buy|order|book|pay|search the web|look up|""" +
        """flash ?cards?|quiz|design|chart|diagram|document|pdf|install (?:the )?apk|create (?:an? )?app|make (?:an? )?app|workflow|skill|every (?:day|week|morning)|remind)\b""")

    /** True for a plain one-off device / settings action, short enough to be one ("turn off GitHub's link opening"). */
    fun plainDevice(text: String): Boolean {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.length > 160 || t.startsWith("/") || t.startsWith("[")) return false
        if (notPlain.containsMatchIn(t)) return false
        return verb.containsMatchIn(t) && noun.containsMatchIn(t)
    }

    /** Tool groups that a plain device action never needs (they cost schema tokens on every call). */
    val heavyGroups = setOf("terminal", "apps", "media", "seller", "browser", "google", "study", "home", "pc", "logs")

    // ---------------- screen readouts in the history ----------------

    /** The markers a screen readout starts with (readScreen's header, its diff form, screenAfter, look-first). */
    val screenMarks = listOf("\nScreen now:\n", "Screen now:\n", "). Elements, use the [number]:", "Same screen in ")

    fun isScreen(content: String): Boolean = screenMarks.any { content.contains(it) }

    /** The result with its screen readout dropped ("[older screen omitted]"); the action's own line stays. */
    fun stripScreen(content: String): String {
        val cut = screenMarks.mapNotNull { m -> content.indexOf(m).takeIf { it >= 0 }?.let { i ->
            // For readScreen's own header, cut at the start of that line (the app name before "(pkg). Elements").
            if (m.startsWith(")")) content.lastIndexOf('\n', i).let { if (it < 0) 0 else it } else i } }.minOrNull() ?: return content
        return content.substring(0, cut).trimEnd() + (if (cut > 0) "\n" else "") + "[older screen omitted]"
    }

    /** A readout that only lists what changed since the previous one ("Same screen in X. Changed or new: …"). */
    fun isDiff(content: String): Boolean = content.contains("Same screen in ") && !content.contains("). Elements, use the [number]:")

    /**
     * Which screen readouts the model still needs, given each history entry's tool content (null = not a tool result):
     * the newest one, and when that is a diff, the readouts back to the last full one (the diff's "same numbers"
     * refer to it). Everything else is stale.
     */
    fun screensToKeep(contents: List<String?>): Set<Int> {
        val screens = contents.indices.filter { contents[it]?.let { c -> isScreen(c) && !c.endsWith("[older screen omitted]") } == true }
        if (screens.isEmpty()) return emptySet()
        val keep = LinkedHashSet<Int>()
        for (i in screens.reversed()) { keep += i; if (!isDiff(contents[i]!!)) break }
        return keep
    }

    /**
     * Drops stale screen readouts from the tool results in [history], in place ([screensToKeep] decides). Old screens
     * were never meant to be sent; keeping them made the history (and so the auto-compaction check) grow by a whole
     * screen per tap, which could trigger a blocking summarise call in the middle of a phone task. Returns chars removed.
     */
    fun pruneScreens(history: org.json.JSONArray): Int {
        val contents = (0 until history.length()).map { i -> history.optJSONObject(i)?.takeIf { it.optString("role") == "tool" }?.optString("content") }
        val keep = screensToKeep(contents)
        var saved = 0
        contents.forEachIndexed { i, c ->
            if (c == null || i in keep || !isScreen(c) || c.endsWith("[older screen omitted]")) return@forEachIndexed
            val cut = stripScreen(c)
            saved += c.length - cut.length
            history.optJSONObject(i)?.put("content", cut)
        }
        return saved
    }

    // ---------------- batching ----------------

    /** Indices of [names] (one reply's tool calls) whose result gets the fresh screen: only the last screen action. */
    fun screenReadAt(names: List<String>, screenActions: Set<String>): Set<Int> {
        val last = names.indices.lastOrNull { names[it] in screenActions } ?: return emptySet()
        return setOf(last)
    }

    // ---------------- run_plan resume ----------------

    /** What the last run_plan in this turn did: its step keys, how many finished, whether it failed, and where. */
    data class PlanRun(val keys: List<String>, val done: Int, val failed: Boolean, val pkg: String)

    /** A step's identity: what it does and to what, normalised ("tap|opening links"). */
    fun key(step: JSONObject): String {
        val d = step.optString("do").lowercase()
        val what = listOf("target", "app", "text", "query", "direction").map { step.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        return d + "|" + com.blaze.assistant.phone.LabelMatch.norm(what)
    }

    /**
     * How many leading steps of a new script to skip: the ones the last run already did, when it failed and the phone
     * is still in the app it failed in. A script that differs early, or a different app, skips nothing.
     */
    fun skipDone(newKeys: List<String>, last: PlanRun?, nowPkg: String): Int {
        if (last == null || !last.failed || last.done <= 0 || nowPkg.isBlank() || last.pkg != nowPkg) return 0
        var k = 0
        while (k < newKeys.size - 1 && k < last.done && k < last.keys.size && newKeys[k] == last.keys[k]) k++
        return if (k == last.done) k else 0
    }

    /** The resume hint a failed run_plan returns, so the model fixes only the failed step. */
    fun resumeHint(failedAt: Int, total: Int): String =
        "Steps 1-${failedAt - 1} are done and the phone is on the screen below. Don't start over: fix only step $failedAt " +
            "(a real label from this screen, or open_settings for a settings page) and send run_plan with steps $failedAt-$total, or tap it directly."

    // ---------------- prompt ----------------

    const val RULE = "\n- SETTINGS: never tap through Settings menus. Call open_settings(page, app) first: it jumps straight to the exact page " +
        "(e.g. page=open_by_default app=GitHub lands on GitHub's Open by default / Set as default / Opening links page; also app_notifications, " +
        "app_permissions, app_battery, app_details, wifi, bluetooth, location, display, default_apps…). Then flip the one switch with tap text=\"…\"." +
        "\n- tap with text finds the element by its words (OEM synonyms too) and scrolls to it if it's off screen: no separate scroll or read_screen turns." +
        "\n- Several tool calls in ONE reply are fine when you already know them (open_settings, then tap text=\"Open supported links\"): they run in order and you get the screen once, at the end." +
        "\n- If run_plan stops at step k, steps before k are done: fix and resend only step k onwards. Don't call list_apps to find an app: open_app and open_settings take the app's name." +
        // v0.30.7: whole settings tasks in one call, and switches that are never flipped the wrong way.
        "\n- A switch tap takes want=\"on\"/\"off\" (tap and run_plan steps): Blaze reads the switch and only taps when it isn't already that way. run_plan steps can open settings too: " +
        "{do:open_settings, page:open_by_default, app:GitHub} then {do:tap, target:\"Open supported links\", want:\"off\"} is the whole task in one call."
}
