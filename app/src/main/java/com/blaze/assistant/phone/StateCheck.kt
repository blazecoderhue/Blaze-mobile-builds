package com.blaze.assistant.phone

/**
 * 0.45.19 phone tasks (user testing 0.45.18, Fri 23 Oct 09:21): "turn on dark mode" answered
 * "I couldn't confirm it: couldn't read the switch back. Dark mode is now turned on." while Dark theme WAS on.
 * Root cause: switching the theme recreates the Settings screen (a configuration change), so the switch node read
 * back after the tap is stale (refresh() fails) and the read-back came out "unknown"; the reply then said both.
 *
 * Now a toggle is verified by the phone's STATE first: dark mode from UiModeManager.getNightMode() and the system
 * configuration's uiMode, Wi-Fi from WifiManager, Bluetooth from BluetoothAdapter, airplane mode / mobile data from
 * Settings.Global, the ringer from AudioManager, brightness from Settings.System. Only a switch with no system state
 * behind it (an in-app toggle) is checked by re-finding the switch and reading its checked state again. Polled with a
 * timeout, never a fixed sleep. One honest reply comes out of it, never two that disagree:
 *   verified      -> "Done ✓ Dark mode is on."
 *   can't verify  -> "I turned it on but couldn't check it; it should be on."
 *   contradicted  -> "I tried to turn Dark mode on, but it still reads off."
 * Pure (StateCheckTest), the Android readings live in [PhoneStateReader].
 */
object StateCheck {
    enum class Setting(val word: String, val source: String) {
        DARK_MODE("Dark mode", "the system theme"),
        WIFI("Wi-Fi", "Wi-Fi"),
        BLUETOOTH("Bluetooth", "Bluetooth"),
        AIRPLANE("Airplane mode", "the system setting"),
        DND("Do Not Disturb", "the system setting"),
        MOBILE_DATA("Mobile data", "the system setting"),
        LOCATION("Location", "the system setting"),
        AUTO_ROTATE("Auto-rotate", "the system setting"),
        BATTERY_SAVER("Battery saver", "the system setting"),
        SILENT("Silent mode", "the ringer"),
        VIBRATE("Vibrate", "the ringer"),
        SOUND("Sound", "the ringer"),
        NFC("NFC", "the system setting"),
    }

    /** The phone's readings. null = can't tell (no permission, no such hardware). */
    interface Reader {
        fun isOn(s: Setting): Boolean?
        /** Settings.System.SCREEN_BRIGHTNESS, 0..255. */
        fun brightness(): Int? = null
        /** AudioManager ringer mode: 0 silent, 1 vibrate, 2 normal. */
        fun ringerMode(): Int? = null
        /** AudioManager.getStreamVolume / getStreamMaxVolume for [stream]. */
        fun volume(stream: Int): Pair<Int, Int>? = null
    }

    enum class Status { VERIFIED, CONTRADICTED, UNKNOWN }

    /** How a toggle came out. [how] = "state" (system reading) or "switch" (the switch re-read) or "" (neither). */
    data class Result(val status: Status, val word: String, val want: Boolean, val how: String, val tookMs: Long = 0) {
        val verified get() = status == Status.VERIFIED
    }

    private val rules: List<Pair<Regex, Setting>> = listOf(
        Regex("(?i)\\b(dark (mode|theme)|night (mode|theme)|use dark theme|dark)\\b") to Setting.DARK_MODE,
        Regex("(?i)\\b(airplane|aeroplane|flight) mode\\b") to Setting.AIRPLANE,
        Regex("(?i)\\bwi-?\\s?fi\\b|\\bwlan\\b") to Setting.WIFI,
        Regex("(?i)\\bbluetooth\\b") to Setting.BLUETOOTH,
        Regex("(?i)\\b(do not disturb|dnd)\\b") to Setting.DND,
        Regex("(?i)\\b(mobile|cellular) data\\b") to Setting.MOBILE_DATA,
        Regex("(?i)\\b(location|gps)\\b") to Setting.LOCATION,
        Regex("(?i)\\b(auto-?\\s?rotate|rotation|screen rotation)\\b") to Setting.AUTO_ROTATE,
        Regex("(?i)\\b(battery saver|power sav(ing|er)( mode)?)\\b") to Setting.BATTERY_SAVER,
        Regex("(?i)\\bnfc\\b") to Setting.NFC,
        Regex("(?i)\\b(silent|mute)\\b") to Setting.SILENT,
        Regex("(?i)\\bvibrat\\w*\\b") to Setting.VIBRATE,
    )

    /** The system setting a switch's label names (Samsung "Use Dark theme", "Dark mode", "Wi-Fi"…), or null (in-app). */
    fun settingOf(label: String): Setting? {
        val l = label.trim()
        if (l.isEmpty() || l.length > 80) return null
        return rules.firstOrNull { it.first.containsMatchIn(l) }?.second
    }

    /**
     * Dark mode from both readings: UiModeManager.getNightMode() ([nightMode]: 1 no, 2 yes, 0 auto, 3 custom, -1 unknown)
     * and the system configuration's UI_MODE_NIGHT bits ([configNight], null = unknown). Either saying yes is on;
     * the configuration is the truth when the mode is auto / custom.
     */
    fun darkFrom(nightMode: Int, configNight: Boolean?): Boolean? = when {
        nightMode == 2 || configNight == true -> true
        configNight == false -> false
        nightMode == 1 -> false
        else -> null
    }

    /** Ringer settings from the ringer mode. */
    fun ringerOn(s: Setting, mode: Int?): Boolean? = when (s) {
        Setting.SILENT -> mode?.let { it == 0 }
        Setting.VIBRATE -> mode?.let { it == 1 }
        Setting.SOUND -> mode?.let { it == 2 }
        else -> null
    }

    /**
     * Verify a switch the user wanted [want] (on = true) after it was tapped. The state of [label]'s system setting is
     * polled every [stepMs] up to [timeoutMs]; with no system state, [reread] (re-find the switch node, read
     * isChecked) is polled the same way. [sleep] waits (a real delay on the phone, a fake clock in tests).
     */
    suspend fun verifyToggle(label: String, want: Boolean, reader: Reader?, reread: suspend () -> Boolean?,
                             sleep: suspend (Long) -> Unit, timeoutMs: Long = 1500, stepMs: Long = 100): Result {
        val s = settingOf(label)
        val word = s?.word ?: cleanLabel(label)
        var waited = 0L
        var stateSeen: Boolean? = null
        var switchSeen: Boolean? = null
        while (true) {
            val st = if (s != null && reader != null) safe { reader.isOn(s) } else null
            if (st != null) {
                stateSeen = st
                if (st == want) return Result(Status.VERIFIED, word, want, "state", waited)
            } else {
                val sw = safe { reread() }
                if (sw != null) {
                    switchSeen = sw
                    if (sw == want) return Result(Status.VERIFIED, word, want, "switch", waited)
                }
            }
            if (waited >= timeoutMs) break
            sleep(stepMs); waited += stepMs
        }
        // A state reading that never agreed is the truth; a switch that never agreed too. Nothing read = unknown.
        return when {
            stateSeen != null -> Result(Status.CONTRADICTED, word, want, "state", waited)
            switchSeen != null -> Result(Status.CONTRADICTED, word, want, "switch", waited)
            else -> Result(Status.UNKNOWN, word, want, "", waited)
        }
    }

    /** Brightness [pct] 0..100 written: read back as 0..255 within one step of rounding. */
    fun brightnessMatches(pct: Int, raw: Int?): Boolean? = raw?.let { kotlin.math.abs(it - (pct * 255 / 100).coerceAtLeast(1)) <= 3 }

    /** Polls the brightness until it reads [pct] or the timeout passes. */
    suspend fun verifyBrightness(pct: Int, reader: Reader, sleep: suspend (Long) -> Unit, timeoutMs: Long = 800, stepMs: Long = 100): Result {
        var waited = 0L; var seen: Boolean? = null
        while (true) {
            val ok = brightnessMatches(pct, safe { reader.brightness() })
            if (ok == true) return Result(Status.VERIFIED, "Brightness", true, "state", waited)
            if (ok != null) seen = ok
            if (waited >= timeoutMs) break
            sleep(stepMs); waited += stepMs
        }
        return Result(if (seen == null) Status.UNKNOWN else Status.CONTRADICTED, "Brightness", true, if (seen == null) "" else "state", waited)
    }

    /** Polls the ringer until it is [mode] ("normal" | "vibrate" | "silent"). */
    suspend fun verifyRinger(mode: String, reader: Reader, sleep: suspend (Long) -> Unit, timeoutMs: Long = 800, stepMs: Long = 100): Result {
        val want = when (mode.lowercase()) { "silent" -> 0; "vibrate" -> 1; else -> 2 }
        val word = when (want) { 0 -> "Silent mode"; 1 -> "Vibrate"; else -> "Sound" }
        var waited = 0L; var seen = false
        while (true) {
            val m = safe { reader.ringerMode() }
            if (m == want) return Result(Status.VERIFIED, word, true, "state", waited)
            if (m != null) seen = true
            if (waited >= timeoutMs) break
            sleep(stepMs); waited += stepMs
        }
        return Result(if (seen) Status.CONTRADICTED else Status.UNKNOWN, word, true, if (seen) "state" else "", waited)
    }

    private inline fun <T> safe(f: () -> T?): T? = try { f() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }

    fun onOff(on: Boolean) = if (on) "on" else "off"

    /** "Switch, Use Dark theme" / "Dark mode switch" -> a short name to say. */
    fun cleanLabel(label: String): String {
        val t = label.replace(Regex("(?i)\\b(switch|toggle|button|on|off)\\b"), " ").replace(Regex("[,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
        return t.ifEmpty { "it" }.take(40)
    }

    // ---------- what is said ----------

    /** The read-back evidence for the tool result ("Dark mode is on (read from the system theme)"). Parsed by [parseEvidence]. */
    fun evidence(r: Result): String = "${r.word} is ${onOff(r.want)} (read from ${if (r.how == "state") settingOf(r.word)?.source ?: "the phone" else "the switch"})"

    /** (word, on) from an [evidence] string, or null. */
    fun parseEvidence(why: String): Pair<String, Boolean>? =
        Regex("^(.{1,40}?) is (on|off) \\(read from ").find(why.trim())?.let { it.groupValues[1] to (it.groupValues[2] == "on") }

    /** The one reply for a verified toggle: "Done ✓ Dark mode is on." */
    fun doneLine(word: String, on: Boolean) = "Done \u2713 $word is ${onOff(on)}."

    /** The one honest line when the phone couldn't be read: "I turned it on but couldn't check it; it should be on." */
    fun honest(want: Boolean) = "I turned it ${onOff(want)} but couldn't check it; it should be ${onOff(want)}."

    /** The "Not confirmed" what-part of the tool result when nothing could be read back (parsed by [honestFrom]). */
    fun unchecked(r: Result) = "turned ${r.word} ${onOff(r.want)} but couldn't check it"

    /** The honest line from an [unchecked] reason, or null when the reason is something else. */
    fun honestFrom(why: String): String? =
        Regex("turned (.{1,40}?) (on|off) but couldn't check it").find(why)?.let { honest(it.groupValues[2] == "on") }

    /** The line when the state read back the other way. */
    fun contradicted(r: Result) = "I tried to turn ${r.word} ${onOff(r.want)}, but it still reads ${onOff(!r.want)}."
}
