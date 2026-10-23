package com.blaze.assistant.phone

import com.blaze.assistant.agent.ActCheck
import com.blaze.assistant.agent.ClaimCheck
import com.blaze.assistant.agent.ToggleReply
import com.blaze.assistant.agent.ToolVerify
import com.blaze.assistant.agent.UnconfirmedEcho
import com.blaze.assistant.notify.BlazeThread
import com.blaze.assistant.notify.QuickReplies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.45.19 phone tasks: toggles are verified by the phone's STATE (fakes of UiModeManager / Configuration, WifiManager,
 * BluetoothAdapter, Settings.System brightness, AudioManager ringer), in-app switches by re-finding and re-reading the
 * switch, and the reply never contradicts itself. Each case runs 5 times with a different settle latency: 5/5 strict.
 */
class StateCheckTest {
    /** A virtual clock: sleep advances it; readings change once it passes [flipAt]. */
    private class Clock { var now = 0L; suspend fun sleep(ms: Long) { now += ms } }

    /** Fake phone: each setting reads [before] until [flipAt] ms, then [after] (null = can't read). */
    private class FakePhone(val clock: Clock, val flipAt: Long, val settings: Map<StateCheck.Setting, Pair<Boolean?, Boolean?>> = emptyMap(),
                            val nightMode: Pair<Int, Int>? = null, val configNight: Pair<Boolean?, Boolean?>? = null,
                            val bright: Pair<Int?, Int?>? = null, val ringer: Pair<Int?, Int?>? = null) : StateCheck.Reader {
        var reads = 0
        private fun <T> at(p: Pair<T, T>): T = if (clock.now >= flipAt) p.second else p.first
        override fun isOn(s: StateCheck.Setting): Boolean? {
            reads++
            if (s == StateCheck.Setting.DARK_MODE && (nightMode != null || configNight != null))
                return StateCheck.darkFrom(nightMode?.let { at(it) } ?: -1, configNight?.let { at(it) })
            if (s == StateCheck.Setting.SILENT || s == StateCheck.Setting.VIBRATE || s == StateCheck.Setting.SOUND) return StateCheck.ringerOn(s, ringerMode())
            return settings[s]?.let { at(it) }
        }
        override fun brightness(): Int? = bright?.let { at(it) }
        override fun ringerMode(): Int? = ringer?.let { at(it) }
    }

    private val latencies = listOf(0L, 100L, 300L, 700L, 1200L)

    private fun fiveOfFive(case: (Clock, Long) -> Boolean) {
        var pass = 0
        for (l in latencies) if (case(Clock(), l)) pass++
        assertEquals("strict 5/5", 5, pass)
    }

    private fun toggle(label: String, want: Boolean, phone: StateCheck.Reader?, clock: Clock, reread: suspend () -> Boolean? = { null }) =
        runBlocking { StateCheck.verifyToggle(label, want, phone, reread, { clock.sleep(it) }) }

    // ---------- verified by state ----------

    @Test fun darkModeOnBySystemState() = fiveOfFive { c, l ->
        // Samsung "Use Dark theme": the tapped switch node is stale after the theme change (reread = null), the state says on
        val phone = FakePhone(c, l, nightMode = 1 to 2, configNight = false to true)
        val r = toggle("Use Dark theme", true, phone, c)
        r.verified && r.how == "state" && r.word == "Dark mode" && r.tookMs <= l + 100 &&
            ToggleVerify.marker(r) == "Checked on the phone: Dark mode is on (read from the system theme)."
    }

    @Test fun darkModeOffBySystemState() = fiveOfFive { c, l ->
        // nightMode AUTO / CUSTOM: the configuration is the truth
        val phone = FakePhone(c, l, nightMode = 0 to 0, configNight = true to false)
        val r = toggle("Dark mode", false, phone, c)
        r.verified && r.how == "state" && ToggleVerify.marker(r).endsWith("Dark mode is off (read from the system theme).")
    }

    @Test fun wifiByWifiManager() = fiveOfFive { c, l ->
        val phone = FakePhone(c, l, settings = mapOf(StateCheck.Setting.WIFI to (false to true)))
        val r = toggle("Wi-Fi", true, phone, c)
        r.verified && r.word == "Wi-Fi" && r.how == "state"
    }

    @Test fun bluetoothByAdapter() = fiveOfFive { c, l ->
        val phone = FakePhone(c, l, settings = mapOf(StateCheck.Setting.BLUETOOTH to (true to false)))
        val r = toggle("Bluetooth", false, phone, c)
        r.verified && r.word == "Bluetooth" && StateCheck.evidence(r) == "Bluetooth is off (read from Bluetooth)"
    }

    @Test fun brightnessBySettingsSystem() = fiveOfFive { c, l ->
        val phone = FakePhone(c, minOf(l, 700), bright = 40 to 127)
        val r = runBlocking { StateCheck.verifyBrightness(50, phone, { c.sleep(it) }) }
        r.verified && r.how == "state"
    }

    @Test fun ringerByAudioManager() = fiveOfFive { c, l ->
        val phone = FakePhone(c, minOf(l, 700), ringer = 2 to 1)
        val r = runBlocking { StateCheck.verifyRinger("vibrate", phone, { c.sleep(it) }) }
        val viaToggle = toggle("Vibrate", true, FakePhone(Clock(), 0, ringer = 2 to 1), Clock())
        r.verified && r.word == "Vibrate" && viaToggle.verified
    }

    // ---------- in-app toggles: no system state, the switch is re-found and re-read ----------

    @Test fun inAppToggleOnByReRead() = fiveOfFive { c, l ->
        // the old node is stale (null) until the screen redraws, then the re-found switch reads checked
        val r = toggle("Show previews", true, FakePhone(c, l), c, reread = { if (c.now >= l) true else null })
        r.verified && r.how == "switch" && r.word == "Show previews" &&
            ToggleVerify.marker(r) == "Checked on the phone: Show previews is on (read from the switch)."
    }

    @Test fun inAppToggleOffByReRead() = fiveOfFive { c, l ->
        // the switch still reads on for a while (animation), then off
        val r = toggle("Switch, Autoplay videos", false, null, c, reread = { c.now >= l == false })
        r.verified && r.how == "switch" && r.word == "Autoplay videos"
    }

    // ---------- not verified: contradicted, unknown ----------

    @Test fun stateThatNeverAgreesIsContradicted() {
        val c = Clock()
        val r = toggle("Wi-Fi", true, FakePhone(c, 10_000, settings = mapOf(StateCheck.Setting.WIFI to (false to true))), c)
        assertEquals(StateCheck.Status.CONTRADICTED, r.status)
        assertTrue(r.tookMs in 1500..1600)
        assertEquals("Not confirmed: Wi-Fi still reads off. Next step: read_screen, then tap the switch itself.", ToggleVerify.marker(r))
        assertEquals("I tried to turn Wi-Fi on, but it still reads off.", StateCheck.contradicted(r))
    }

    @Test fun honestFallbackWhenNothingCanBeRead() {
        var pass = 0
        for (l in latencies) {
            val c = Clock()
            val r = toggle("Use Dark theme", true, FakePhone(c, l), c, reread = { null })
            assertEquals(StateCheck.Status.UNKNOWN, r.status)
            val result = "Tapped \u201cUse Dark theme\u201d. " + ToggleVerify.marker(r)
            val v = ActCheck.verdictOf(result)!!
            assertEquals(ToolVerify.Status.UNVERIFIED, v.status)
            val done = listOf(ClaimCheck.Done("tap", v.status, v.why))
            // whatever the model wrote, the user reads ONE honest line
            for (reply in listOf("Dark mode is now turned on.", "Done! Dark mode is on.", "I've turned on dark mode.", "Dark mode is on now \ud83c\udf19", "All set."))
                assertEquals(reply, "I turned it on but couldn't check it; it should be on.", UnconfirmedEcho.fix(reply, done))
            pass++
        }
        assertEquals(5, pass)
        assertEquals("I turned it off but couldn't check it; it should be off.", StateCheck.honest(false))
    }

    @Test fun noContradictionEver() {
        // the exact 0.45.18 reply from the phone
        val why = "couldn't read the switch back"
        val done = listOf(ClaimCheck.Done("tap", ToolVerify.Status.UNVERIFIED, why))
        val bad = "I couldn't confirm it: couldn't read the switch back. Dark mode is now turned on."
        for (reply in listOf("Dark mode is now turned on.", bad, "I couldn't confirm it, but dark mode is on.", "Done, it's on.", "Dark mode has been turned on.")) {
            val out = UnconfirmedEcho.fix(reply, done)
            assertFalse(out, UnconfirmedEcho.HONEST.containsMatchIn(out) && UnconfirmedEcho.CLAIM.containsMatchIn(out))
            assertTrue(out, UnconfirmedEcho.HONEST.containsMatchIn(out))
        }
        // verified: the reply is the one Done line, never a "couldn't"
        val ok = listOf(ClaimCheck.Done("tap", ToolVerify.Status.VERIFIED, "Dark mode is on (read from the system theme)"))
        for (reply in listOf("Dark mode is now turned on.", "Done! Dark mode is on.", "Dark mode is on.", "Done.", "I turned on Dark mode for you."))
            assertEquals(reply, "Done \u2713 Dark mode is on.", ToggleReply.fix(UnconfirmedEcho.fix(reply, ok), ok))
        // a reply that asks something or carries more stays the model's
        assertEquals("Dark mode is on. Want me to schedule it from sunset?", ToggleReply.fix("Dark mode is on. Want me to schedule it from sunset?", ok))
        // an unrelated later action that wasn't a switch leaves it alone
        val later = ok + ClaimCheck.Done("open_url", ToolVerify.Status.VERIFIED, "Chrome is in front")
        assertEquals("Opened it.", ToggleReply.fix("Opened it.", later))
    }

    @Test fun quickRepliesFitTheResult() {
        assertEquals(listOf("Turn it off", "Thanks"), QuickReplies.forMessage(StateCheck.doneLine("Dark mode", true), BlazeThread.Kind.RESULT))
        assertEquals(listOf("Turn it on", "Thanks"), QuickReplies.forMessage(StateCheck.doneLine("Wi-Fi", false), BlazeThread.Kind.RESULT))
        assertEquals(listOf("Check it again", "Thanks"), QuickReplies.forMessage(StateCheck.honest(true), BlazeThread.Kind.RESULT))
        assertEquals(listOf("Try again", "What happened?"), QuickReplies.forMessage("I tried to turn Wi-Fi on, but it still reads off.", BlazeThread.Kind.RESULT))
        for (t in listOf(StateCheck.doneLine("Dark mode", true), StateCheck.honest(true), "I tried to turn Wi-Fi on, but it still reads off."))
            assertTrue(QuickReplies.forMessage(t, BlazeThread.Kind.RESULT).none { it.equals("Weird", true) || it.equals("Great!", true) })
    }

    @Test fun labelsMapToSettings() {
        assertEquals(StateCheck.Setting.DARK_MODE, StateCheck.settingOf("Use Dark theme"))
        assertEquals(StateCheck.Setting.DARK_MODE, StateCheck.settingOf("Dark mode"))
        assertEquals(StateCheck.Setting.WIFI, StateCheck.settingOf("Wi-Fi"))
        assertEquals(StateCheck.Setting.WIFI, StateCheck.settingOf("WiFi"))
        assertEquals(StateCheck.Setting.BLUETOOTH, StateCheck.settingOf("Bluetooth"))
        assertEquals(StateCheck.Setting.AIRPLANE, StateCheck.settingOf("Airplane mode"))
        assertEquals(StateCheck.Setting.DND, StateCheck.settingOf("Do not disturb"))
        assertEquals(StateCheck.Setting.BATTERY_SAVER, StateCheck.settingOf("Power saving"))
        assertNull(StateCheck.settingOf("Show previews"))
        assertEquals(true, StateCheck.darkFrom(2, null)); assertEquals(false, StateCheck.darkFrom(1, null)); assertNull(StateCheck.darkFrom(0, null))
        assertEquals(true, StateCheck.darkFrom(1, true)); assertEquals(false, StateCheck.darkFrom(0, false))
        assertEquals("Dark mode" to true, StateCheck.parseEvidence("Dark mode is on (read from the system theme)"))
        assertNull(StateCheck.parseEvidence("the switch reads OFF"))
    }

    @Test fun agentUsesStateVerification() {
        fun src(p: String) = listOf(File("src/main/java/com/blaze/assistant/$p"), File("app/src/main/java/com/blaze/assistant/$p")).first { it.exists() }.readText()
        val a = src("agent/Agent.kt")
        assertFalse("the old stale-node read-back is gone", a.contains("couldn't read the switch back"))
        assertTrue(a.contains("com.blaze.assistant.phone.ToggleVerify.after(app, svc, label, wantId, want)"))
        assertTrue(a.contains("ToggleReply.fix(textOut, synchronized(turnDone) { turnDone.toList() })"))
        assertTrue("run_plan switch steps are verified too", a.contains("planChecks += com.blaze.assistant.phone.ToggleVerify.marker(vr)"))
        val r = src("phone/PhoneStateReader.kt")
        for (api in listOf("nightMode", "Configuration.UI_MODE_NIGHT_MASK", "isWifiEnabled", "BluetoothManager", "AIRPLANE_MODE_ON", "SCREEN_BRIGHTNESS", "ringerMode"))
            assertTrue(api, r.contains(api))
    }
}
