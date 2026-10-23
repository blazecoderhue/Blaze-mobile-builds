package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * v0.44.0 verify before claiming. Every tool result the model sees now ends with an explicit status line:
 *
 *  - `[verified=true]` the tool read back what it did (a file's size, a setting's new state, the app in front),
 *    or it only looked something up, or it changed Blaze's own data (memory, routines,
 *    designs) where success IS the result.
 *  - `[verified=false: …]` it ran but nothing confirmed the effect: an intent handed to another app (timer, call,
 *    media), a settings page opened instead of the setting changed, an app that isn't in front.
 *  - `[verified=false: FAILED …]` it failed, or its check failed.
 *
 * [classify] is pure (the result's own words); [check] adds cheap post-condition probes for the tools that act outside
 * Blaze ([Probes], Android side in VerifyProbes). The system prompt ([PROMPT_RULE]) forbids claiming success without
 * verified=true, and ClaimCheck rewrites a final reply that still does. Pure Kotlin; ToolVerifyTest.
 */
object ToolVerify {
    enum class Status { VERIFIED, UNVERIFIED, FAILED }

    data class Verdict(val status: Status, val why: String = "") {
        val ok get() = status == Status.VERIFIED
    }

    /** Post-condition checks. null = can't tell (no permission, not installed): the verdict stays unverified. */
    interface Probes {
        /** Package of the app in front now. */
        suspend fun foreground(): String? = null
        /** Blaze's own package (an app that "opened" while Blaze is still in front didn't open). */
        val self: String get() = "com.blaze.assistant"
        /** (hour, minute) of the next alarm the system knows about. */
        suspend fun nextAlarm(): Pair<Int, Int>? = null
        /** Size in bytes of a file Blaze wrote (-1 = missing, null = can't tell). */
        fun fileSize(path: String): Long? = null
        /** v0.45.0 (#4): the setting read back from the system (wifi, bluetooth, airplane, do_not_disturb, mobile_data); null = can't tell. */
        suspend fun settingOn(setting: String): Boolean? = null
        /** v0.45.13: alarm [pid] at [hhmm] reads ON in the Clock app's list (phone control); null = can't read. */
        suspend fun clockAlarmOn(pid: String, hhmm: String): Boolean? = null
        /** v0.45.13: the calendar provider has an event titled [title] starting at [startMs] (±1 min); null = can't read. */
        suspend fun calendarHas(title: String, startMs: Long): Boolean? = null
        /** v0.45.13: the contacts provider has [name] (with [phone] when given); null = can't read. */
        suspend fun contactHas(name: String, phone: String): Boolean? = null
        /** v0.45.13: a Clock app timer is running (its ongoing notification); null = can't tell. */
        suspend fun timerRunning(): Boolean? = null
        /** v0.45.13: title of what [pkg] is playing now ("" = nothing playing), null = can't tell. */
        suspend fun nowPlaying(pkg: String): String? = null
        /** v0.45.13: the visible words on the screen (accessibility tree, lower case); null = phone control off. */
        suspend fun screenWords(): String? = null
    }

    object NoProbes : Probes

    private val ic = setOf(RegexOption.IGNORE_CASE)

    /** Tools that act through another app or the system with nothing to read back. */
    val HANDED_OFF = mapOf(
        "call_number" to "the call was handed to the phone app; nothing confirmed it connected",
        "call_contact" to "the call was handed to the phone app; nothing confirmed it connected",
        "media_control" to "the media key was sent; nothing confirmed what is playing",
        "spotify_play" to "Spotify was asked to play; nothing confirmed it is playing",
        "spotify_control" to "Spotify was asked; nothing confirmed the change",
        "system_action" to "the system action was sent; nothing read it back",
        "phone_mode" to "the mode change was sent; nothing read it back",
        "uninstall_app" to "the uninstall screen was opened; the user has to confirm it",
        "github_install_apk" to "the installer was opened; the user has to confirm it",
        "share_to_app" to "the share sheet / app was opened; nothing confirmed it was sent",
        "share_text" to "the share sheet was opened; nothing confirmed it was sent",
        "post_notification" to "the notification was posted; nothing read it back",
    )

    /** Tools whose effect is checked by a probe in [check]. */
    val PROBED = setOf("open_app", "open_settings_page", "open_camera", "open_url", "navigate_to", "set_alarm",
        "export_file", "download_file", "device_setting", "toggle_setting",
        // v0.45.13 verify-after-act: these are read back too.
        "set_timer", "add_calendar_event", "add_contact", "compose_sms", "search_in_app")

    /**
     * v0.45.0 (#4): tools that send something to a person. Only a result that says it went ("Sent to …", "Sent.")
     * counts as done; anything else (opened the chat, drafted, queued, a pop-up) is unverified at best.
     */
    val SEND_TOOLS = setOf("message_contact", "send_sms", "send_text", "reply_to_notification", "notif_reply", "gmail_send", "send_email", "slack_post")

    /** v0.45.0 (#4): settings the system can read back after a change ([Probes.settingOn]). */
    val READABLE_SETTINGS = setOf("wifi", "bluetooth", "airplane", "do_not_disturb", "mobile_data")

    private val sentWords = Regex("""^\s*(?:Sent\b|Message sent\b|Email sent\b|Posted\b|Replied\b|Delivered\b)""", ic)

    private val failWords = Regex("""\b(was NOT|NOT sent|NOT changed|NOT running|isn't running|is not running|never (?:appeared|started|opened)|nothing was (?:sent|done|changed|installed)|didn't (?:open|start|send|work))\b""")
    private val stateRead = Regex("""(?:\bis now\b|\bnow (?:on|off)\b|:\s*(?:on|off)\b|\b(?:flashlight|torch) (?:on|off)\b|\bvolume (?:is|:)\s*\d+|\b(?:on|off) now\b|\bset to\s+\d+)""", ic)
    private val openedOnly = Regex("""^\s*Opened\b[^.]*\b(?:settings|panel|page)\b""", ic)

    private fun firstLine(r: String) = r.trim().lineSequence().firstOrNull().orEmpty().take(140)

    /** The verdict from the result's own words, with no probe. */
    fun classify(name: String, args: JSONObject, result: String): Verdict {
        // v0.45.0: an action OneShot didn't repeat is judged by its earlier result.
        val r = result.trim().let { if (it.startsWith(OneShot.NOTE.trim())) it.removePrefix(OneShot.NOTE.trim()).trim() else it }
        if (r.startsWith("Couldn't confirm", true)) return Verdict(Status.UNVERIFIED, firstLine(r))
        // v0.45.13: a tool that read its own effect back says so with an ActCheck marker; that reading decides.
        ActCheck.verdictOf(r)?.let { return it }
        // v0.45.13: an alarm / timer acted on in the Clock app without a read-back is never done (17:13 "Alarm removed.").
        if (name == "process" && args.optString("action") in setOf("delete", "pause", "resume", "reschedule") &&
            Regex("(?i)^(A\\d+|T\\d+|clock@.*)$").matches(args.optString("id").trim().removePrefix("#")))
            return Verdict(Status.UNVERIFIED, "the Clock app wasn't read back")
        if (r.startsWith("Drafted", true)) return Verdict(Status.UNVERIFIED, "only drafted, not sent")
        if (!ClaimGuard.succeeded(r)) return Verdict(Status.FAILED, firstLine(r))
        if (failWords.containsMatchIn(r.take(400))) return Verdict(Status.FAILED, firstLine(r))
        HANDED_OFF[name]?.let { return Verdict(Status.UNVERIFIED, it) }
        // 0.45.19 Blaze Phone: a phone tool worked; whether its page PROVES a booking / order is in [why] ("page verified: …").
        if (name in com.blaze.assistant.browser.phone.PhonePolicy.TOOLS) return phoneVerdict(r)
        if (name in SEND_TOOLS) return if (sentWords.containsMatchIn(r)) Verdict(Status.VERIFIED, firstLine(r))
            else Verdict(Status.UNVERIFIED, "no “Sent” confirmation came back")
        return when (name) {
            "device_setting", "toggle_setting" -> when {
                stateRead.containsMatchIn(r) -> Verdict(Status.VERIFIED)
                openedOnly.containsMatchIn(r) -> Verdict(Status.UNVERIFIED, "only the settings screen was opened; the setting itself was not changed")
                else -> Verdict(Status.UNVERIFIED, "the new state wasn't read back")
            }
            "file_write", "file_edit" -> if (Regex("""\(\d+ bytes""").containsMatchIn(r)) Verdict(Status.VERIFIED) else Verdict(Status.UNVERIFIED, "the file wasn't read back")
            "add_calendar_event" -> if (r.startsWith("Opened the calendar", true)) Verdict(Status.UNVERIFIED, "the event form was opened; the user still has to tap Save")
                else Verdict(Status.UNVERIFIED, "not checked yet")
            in PROBED -> Verdict(Status.UNVERIFIED, "not checked yet")
            else -> Verdict(Status.VERIFIED)
        }
    }

    /**
     * 0.45.19 Blaze Phone: the newest verification line in a phone tool's result (PhonePolicy.Verdict.line). VERIFIED with
     * why "page verified: …" only when the page itself showed a confirmation; otherwise VERIFIED (the step worked) with
     * why "page not verified", which never backs a "booked / ordered / paid" claim (ClaimCheck BOOKED).
     */
    fun phoneVerdict(r: String): Verdict {
        val last = r.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("VERIFIED:") || it.startsWith("NOT VERIFIED:") || it.startsWith("SIGNED IN:") }
        return if (last != null && last.startsWith("VERIFIED:")) Verdict(Status.VERIFIED, "page verified: " + last.removePrefix("VERIFIED:").trim().take(120))
            else Verdict(Status.VERIFIED, "page not verified" + if (last != null && last.startsWith("SIGNED IN:")) " (signed in)" else "")
    }

    /** v0.45.13: the destination of a Maps directions link (google.com/maps/dir/?…destination=X, google.navigation:q=X), else null. */
    fun mapsDest(url: String): String? {
        val u = url.trim()
        if (!Regex("(?i)google\\.[a-z.]+/maps/dir|maps\\.google\\.[a-z.]+/.*daddr=|^google\\.navigation:").containsMatchIn(u)) return null
        val raw = Regex("(?i)[?&](?:destination|daddr)=([^&]+)").find(u)?.groupValues?.get(1)
            ?: Regex("(?i)^google\\.navigation:q=([^&]+)").find(u)?.groupValues?.get(1)
            ?: Regex("(?i)/maps/dir/[^/]*/([^/?]+)").find(u)?.groupValues?.get(1)
            ?: return ""
        return try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
    }

    private val routeWords = Regex("\\b(\\d+ ?min|\\d+ ?hr|\\d+(?:[.,]\\d+)? ?km|\\d+(?:[.,]\\d+)? ?mi|start|steps)\\b")

    /** Maps in front and its screen names [dest] with a route (minutes / km / Start): directions really showing. */
    private suspend fun mapsRoute(dest: String, p: Probes, waitMs: Long, poll: suspend (Long, suspend () -> Boolean) -> Boolean): Verdict? {
        if (p.foreground() == null) return null
        val words = keyWords(dest)
        return if (poll(maxOf(waitMs, 3000)) { p.foreground().orEmpty().contains("maps", true) && p.screenWords().orEmpty().let { w -> (words.isEmpty() || words.any { it in w }) && routeWords.containsMatchIn(w) } })
            Verdict(Status.VERIFIED, "Maps is in front and shows a route to ${dest.take(40)}")
        else if (p.foreground().orEmpty().contains("maps", true)) Verdict(Status.UNVERIFIED, "Maps is open but no route to ${dest.take(40)} showed up yet")
        else Verdict(Status.UNVERIFIED, "Maps didn't come to the front")
    }

    /** v0.45.13: "2026-10-22 15:00" → epoch ms in the phone's zone; null when it doesn't parse. */
    fun startMs(s: String): Long? = try {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(s.trim())?.time
    } catch (_: Exception) { null }

    /** v0.45.13: search_in_app's app name → the package whose media session proves "playing". */
    fun mediaPkg(app: String): String? = when (app.trim().lowercase()) {
        "youtube" -> "com.google.android.youtube"
        "youtube music", "yt music" -> "com.google.android.apps.youtube.music"
        "spotify" -> "com.spotify.music"
        else -> null
    }

    /** Lower-case words of [s] with 3+ letters (for "the screen shows the destination / the draft"). */
    fun keyWords(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.take(4)

    /** "Opened WhatsApp (com.whatsapp)." / "Opened com.whatsapp." → the package. */
    fun packageIn(result: String): String? =
        Regex("""\(([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)\)""").find(result)?.groupValues?.get(1)
            ?: Regex("""^Opened ([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)""").find(result.trim())?.groupValues?.get(1)

    /** A path Blaze wrote that the result names ("Saved to /sdcard/Download/a.pdf", "Downloaded … → /x/y"). */
    fun pathIn(result: String, args: JSONObject): String? =
        args.optString("path").takeIf { it.startsWith("/") }
            ?: Regex("""(/(?:sdcard|storage|data|root)[^\s"'),]+)""").find(result)?.groupValues?.get(1)?.trimEnd('.')

    /**
     * [classify] plus the cheap post-condition checks (each a few hundred ms at most; [poll] re-reads up to [waitMs]
     * for things that take a moment, like an app coming to the front).
     */
    suspend fun check(name: String, args: JSONObject, result: String, p: Probes = NoProbes, waitMs: Long = 1500,
                      poll: suspend (Long, suspend () -> Boolean) -> Boolean = ::defaultPoll): Verdict {
        val v = classify(name, args, result)
        // v0.45.0 (#4): a setting the system can read back is always read back (the tool's own words aren't proof).
        if ((name == "device_setting" || name == "toggle_setting") && v.status != Status.FAILED) {
            settingCheck(args, p, waitMs, poll)?.let { return it }
            return v
        }
        if (v.status != Status.UNVERIFIED || name !in PROBED) return v
        return try { probe(name, args, result, p, waitMs, poll) ?: v } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { v }
    }

    private suspend fun probe(name: String, args: JSONObject, result: String, p: Probes, waitMs: Long,
                              poll: suspend (Long, suspend () -> Boolean) -> Boolean): Verdict? = when (name) {
        "open_app" -> {
            val want = packageIn(result)
            if (p.foreground() == null) Verdict(Status.UNVERIFIED, "couldn't see which app is in front (phone control is off)")
            else if (want != null && poll(waitMs) { p.foreground() == want }) Verdict(Status.VERIFIED, "$want is in front")
            else if (want == null && poll(waitMs) { p.foreground().let { it != null && it != p.self } }) Verdict(Status.VERIFIED, "${p.foreground()} is in front")
            else Verdict(Status.UNVERIFIED, "${want ?: "the app"} isn't in front (${p.foreground()} is)")
        }
        "open_settings_page" -> if (p.foreground() == null) null
            else if (poll(waitMs) { p.foreground().orEmpty().contains("settings") }) Verdict(Status.VERIFIED, "Settings is in front")
            else Verdict(Status.UNVERIFIED, "Settings isn't in front")
        "navigate_to" -> mapsRoute(args.optString("place").ifBlank { args.optString("destination") }, p, waitMs, poll)
        "open_url" -> mapsDest(args.optString("url"))?.let { mapsRoute(it, p, waitMs, poll) }
            ?: if (p.foreground() == null) null
            else if (poll(waitMs) { p.foreground().let { it != null && it != p.self } }) Verdict(Status.VERIFIED, "${p.foreground()} is in front")
            else Verdict(Status.UNVERIFIED, "no other app came to the front")
        "open_camera" -> if (p.foreground() == null) null
            else if (poll(waitMs) { p.foreground().let { it != null && it != p.self } }) Verdict(Status.VERIFIED, "${p.foreground()} is in front")
            else Verdict(Status.UNVERIFIED, "no other app came to the front")
        "set_alarm" -> {
            val h = args.optInt("hour", -1); val m = args.optInt("minute", 0)
            val pid = Regex("""\[ID (A\d+)]""").find(result)?.groupValues?.get(1)
            if (poll(waitMs) { p.nextAlarm() == (h to m) }) Verdict(Status.VERIFIED, "the system lists the next alarm at %02d:%02d".format(h, m))
            // v0.45.13: an earlier alarm hides this one from nextAlarmClock; read the Clock app's list instead.
            else if (pid != null && p.clockAlarmOn(pid, "%02d:%02d".format(h, m)) == true) Verdict(Status.VERIFIED, "the Clock app lists alarm $pid at %02d:%02d, switched on".format(h, m))
            else Verdict(Status.UNVERIFIED, "the Clock app didn't list an alarm at %02d:%02d as the next one".format(h, m))
        }
        "set_timer" -> when (p.timerRunning()) {
            null -> null
            true -> Verdict(Status.VERIFIED, "the Clock app shows a running timer")
            false -> if (poll(waitMs) { p.timerRunning() == true }) Verdict(Status.VERIFIED, "the Clock app shows a running timer")
                else Verdict(Status.UNVERIFIED, "no running timer showed up in the Clock app")
        }
        "add_calendar_event" -> {
            val t0 = startMs(args.optString("start"))
            val title = args.optString("title")
            if (t0 == null || title.isBlank() || result.trim().startsWith("Opened the calendar", true)) null else when (p.calendarHas(title, t0)) {
                null -> null
                true -> Verdict(Status.VERIFIED, "the phone's calendar lists \u201c$title\u201d at ${args.optString("start").trim()}")
                false -> if (poll(waitMs) { p.calendarHas(title, t0) == true }) Verdict(Status.VERIFIED, "the phone's calendar lists \u201c$title\u201d")
                    else Verdict(Status.FAILED, "the calendar has no \u201c$title\u201d at ${args.optString("start").trim()}")
            }
        }
        "add_contact" -> {
            val nm = args.optString("name")
            when (p.contactHas(nm, args.optString("phone"))) {
                null -> null
                true -> Verdict(Status.VERIFIED, "Contacts lists $nm" + args.optString("phone").takeIf { it.isNotBlank() }?.let { " with $it" }.orEmpty())
                false -> Verdict(Status.FAILED, "Contacts has no $nm")
            }
        }
        "compose_sms" -> {
            val words = keyWords(args.optString("message"))
            if (p.foreground() == null) null
            else if (poll(waitMs) { p.foreground().let { it != null && it != p.self } && p.screenWords().orEmpty().let { w -> words.isNotEmpty() && words.all { it in w } } })
                Verdict(Status.VERIFIED, "the draft is in ${p.foreground()}'s message box and has NOT been sent")
            else Verdict(Status.UNVERIFIED, "the drafted text didn't show up in a message box")
        }
        "search_in_app" -> {
            val pkg = mediaPkg(args.optString("app"))
            if (args.optBoolean("play") && pkg != null) {
                val words = keyWords(args.optString("query"))
                val got = if (poll(maxOf(waitMs, 4000)) { p.nowPlaying(pkg).orEmpty().lowercase().let { t -> t.isNotBlank() && (words.isEmpty() || words.any { it in t }) } }) p.nowPlaying(pkg) else p.nowPlaying(pkg)
                when {
                    got == null -> null
                    got.isNotBlank() && (words.isEmpty() || words.any { it in got.lowercase() }) -> Verdict(Status.VERIFIED, "${args.optString("app")} is playing \u201c${got.take(60)}\u201d")
                    got.isNotBlank() -> Verdict(Status.UNVERIFIED, "${args.optString("app")} is playing \u201c${got.take(60)}\u201d, which doesn't match the ask")
                    else -> Verdict(Status.UNVERIFIED, "the search opened but nothing is playing yet; tap the right video, then check again")
                }
            } else if (p.foreground() == null) null
            else if (poll(waitMs) { p.foreground().let { it != null && it != p.self } }) Verdict(Status.VERIFIED, "${p.foreground()} shows the search (opened only: finish the task)")
            else Verdict(Status.UNVERIFIED, "the app didn't come to the front")
        }
        "export_file", "download_file" -> pathIn(result, args)?.let { path ->
            when (val n = p.fileSize(path)) {
                null -> null
                in 1..Long.MAX_VALUE -> Verdict(Status.VERIFIED, "$path exists ($n bytes)")
                else -> Verdict(Status.FAILED, "$path is missing or empty")
            }
        }
        else -> null
    }

    /** "on"/"off"-ish value → wanted state (null = not a switch: volume levels, "toggle"). */
    fun wanted(value: String): Boolean? = when (value.trim().lowercase()) {
        "on", "enable", "enabled", "true", "1", "yes" -> true
        "off", "disable", "disabled", "false", "0", "no" -> false
        else -> null
    }

    private suspend fun settingCheck(args: JSONObject, p: Probes, waitMs: Long, poll: suspend (Long, suspend () -> Boolean) -> Boolean): Verdict? {
        val s = args.optString("setting").trim().lowercase()
        if (s !in READABLE_SETTINGS) return null
        val want = wanted(args.optString("value")) ?: return null
        if (p.settingOn(s) == null) return null
        val label = s.replace('_', ' ').replace("wifi", "Wi-Fi")
        return if (poll(waitMs) { p.settingOn(s) == want }) Verdict(Status.VERIFIED, "$label reads ${if (want) "on" else "off"}")
            else Verdict(Status.UNVERIFIED, "$label still reads ${if (want) "off" else "on"}")
    }

    private suspend fun defaultPoll(waitMs: Long, ok: suspend () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + waitMs
        while (true) {
            if (ok()) return true
            if (System.currentTimeMillis() >= end) return false
            kotlinx.coroutines.delay(250)
        }
    }

    /** The result as the model sees it: the original text plus one status line. */
    fun tag(result: String, v: Verdict): String {
        val line = when (v.status) {
            Status.VERIFIED -> "[verified=true]"
            Status.UNVERIFIED -> "[verified=false: ${v.why.ifBlank { "couldn't confirm it worked" }}. Don't say it worked; say you tried and couldn't confirm it.]"
            Status.FAILED -> "[verified=false: FAILED: ${v.why.ifBlank { "it didn't work" }}. Tell the user plainly it didn't work.]"
        }
        return result.trimEnd() + "\n" + line
    }

    /** True when [result] already carries a status line (a result handed on twice is tagged once). */
    fun tagged(result: String): Boolean = result.trimEnd().let { it.endsWith("]") && it.substringAfterLast('\n').startsWith("[verified=") }

    /** Static system-prompt rule (stays in the cached prefix). */
    const val PROMPT_RULE = "\nVERIFIED RESULTS: every tool result ends with [verified=true] or [verified=false: why]. " +
        "Say something worked (sent, set, opened, started, running, saved, turned on, installed, playing) ONLY when its result in THIS turn says verified=true. " +
        "When it says verified=false, say plainly \u201cI tried to … but couldn't confirm it\u201d (or that it failed and why) and offer the next step; " +
        "for a check you can do (a screenshot, read_screen) do it first. Earlier messages saying it worked are not proof. " +
        "A result marked \u201cchecked on the phone\u201d needs no extra check. verified=pending means the phone is still checking: say you started it, not that it's done. " +
        "Opening an app or a settings page is only the first step of a task, never the task: keep going (read_screen, tap, type, search) until the thing the user asked for is read back verified=true. " +
        "Say the exact time, name or value the tool result reports (an alarm set for 18:15 is 18:15)."
}
