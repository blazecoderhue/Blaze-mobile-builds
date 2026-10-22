package com.blaze.assistant.agent

import com.blaze.assistant.agent.ToolVerify.Status
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolVerifyTest {
    private fun a(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { put(it.first, it.second) } }

    private class Fake(val fg: String? = null, val alive: Boolean? = null, val proc: Boolean? = null,
                       val alarm: Pair<Int, Int>? = null, val size: Long? = null) : ToolVerify.Probes {
        override suspend fun foreground() = fg
        override val self = "com.blaze.assistant"
        override suspend fun desktopAlive() = alive
        override suspend fun desktopProcess(command: String) = proc
        override suspend fun nextAlarm() = alarm
        override fun fileSize(path: String) = size
    }
    private val noWait: suspend (Long, suspend () -> Boolean) -> Boolean = { _, ok -> ok() }
    private fun check(name: String, args: JSONObject, result: String, p: ToolVerify.Probes) = runBlocking { ToolVerify.check(name, args, result, p, 0, noWait) }

    @Test fun failuresAndReadOnlyResults() {
        assertEquals(Status.FAILED, ToolVerify.classify("send_sms", a(), "Couldn't send: no number.").status)
        assertEquals(Status.FAILED, ToolVerify.classify("message_contact", a(), "Not sent yet: WhatsApp is open on Mary's chat").status)
        assertEquals(Status.UNVERIFIED, ToolVerify.classify("message_contact", a(), "Couldn't confirm it was sent: I pressed Send in Mary's WhatsApp").status)
        assertEquals(Status.VERIFIED, ToolVerify.classify("web_search", a(), "1. Lusaka weather: 28C").status)
        assertEquals(Status.VERIFIED, ToolVerify.classify("message_contact", a(), "Sent to Mary on WhatsApp: hi").status)
    }

    @Test fun settingsPageOpenedIsNotASettingChanged() {
        val v = ToolVerify.classify("device_setting", a("setting" to "wifi", "value" to "on"), "Opened Wi-Fi settings.")
        assertEquals(Status.UNVERIFIED, v.status)
        assertTrue(v.why.contains("not changed"))
        assertEquals(Status.VERIFIED, ToolVerify.classify("device_setting", a(), "Flashlight on.").status)
        assertEquals(Status.VERIFIED, ToolVerify.classify("device_setting", a(), "Media volume is 40%.").status)
    }

    @Test fun handedOffIntentsStayUnverified() {
        assertEquals(Status.UNVERIFIED, ToolVerify.classify("set_timer", a(), "Timer started for 10 minutes.").status)
        assertEquals(Status.UNVERIFIED, ToolVerify.classify("call_number", a(), "Calling 0977.").status)
    }

    @Test fun desktopStartIsCheckedOnItsPort() {
        val dead = check("desktop", a("action" to "start"), "Started Debian at 1280x720.", Fake(alive = false))
        assertEquals(Status.FAILED, dead.status)
        assertTrue(dead.why.contains("5901"))
        assertEquals(Status.VERIFIED, check("desktop", a("action" to "start"), "Started Debian.", Fake(alive = true)).status)
        assertEquals(Status.UNVERIFIED, check("desktop", a("action" to "start"), "Started.", Fake(alive = null)).status)
        assertEquals(Status.UNVERIFIED, check("desktop", a("action" to "start"), "Installing the Debian desktop in the background", Fake(alive = false)).status)
    }

    @Test fun desktopAppNeedsItsProcess() {
        assertEquals(Status.FAILED, check("desktop", a("action" to "run", "app" to "dillo"), "Opened dillo.", Fake(alive = false)).status)
        assertEquals(Status.UNVERIFIED, check("desktop", a("action" to "run", "app" to "dillo"), "Opened dillo.", Fake(alive = true, proc = false)).status)
        assertEquals(Status.VERIFIED, check("desktop", a("action" to "run", "app" to "dillo"), "Opened dillo.", Fake(alive = true, proc = true)).status)
        assertEquals(Status.VERIFIED, check("desktop", a("action" to "status"), "Running Debian at 1280x720", Fake()).status)
    }

    @Test fun appLaunchNeedsTheAppInFront() {
        assertEquals("com.whatsapp", ToolVerify.packageIn("Opened WhatsApp (com.whatsapp). It is installed"))
        assertEquals("com.whatsapp", ToolVerify.packageIn("Opened com.whatsapp. It is installed"))
        val r = "Opened WhatsApp (com.whatsapp). It is installed, so never offer to install it."
        assertEquals(Status.VERIFIED, check("open_app", a(), r, Fake(fg = "com.whatsapp")).status)
        assertEquals(Status.UNVERIFIED, check("open_app", a(), r, Fake(fg = "com.blaze.assistant")).status)
        assertEquals(Status.UNVERIFIED, check("open_app", a(), r, Fake(fg = null)).status)
    }

    @Test fun alarmMustBeListed() {
        assertEquals(Status.VERIFIED, check("set_alarm", a("hour" to 7, "minute" to 0), "Alarm set. [ID A3]", Fake(alarm = 7 to 0)).status)
        assertEquals(Status.UNVERIFIED, check("set_alarm", a("hour" to 7, "minute" to 0), "Alarm set. [ID A3]", Fake(alarm = 6 to 30)).status)
    }

    @Test fun filesAreReadBack() {
        assertEquals(Status.VERIFIED, ToolVerify.classify("file_write", a(), "Wrote /root/a.txt (12 bytes, 1 lines).").status)
        assertEquals(Status.VERIFIED, check("export_file", a(), "Saved to /sdcard/Download/a.pdf", Fake(size = 2048)).status)
        assertEquals(Status.FAILED, check("export_file", a(), "Saved to /sdcard/Download/a.pdf", Fake(size = -1)).status)
    }

    @Test fun tagLinesAndTheRule() {
        assertTrue(ToolVerify.tag("ok", ToolVerify.Verdict(Status.VERIFIED)).endsWith("[verified=true]"))
        val u = ToolVerify.tag("Timer started.", ToolVerify.Verdict(Status.UNVERIFIED, "handed to Clock"))
        assertTrue(u.contains("[verified=false: handed to Clock"))
        assertTrue(ToolVerify.tagged(u))
        assertFalse(ToolVerify.tagged("plain"))
        assertTrue(ToolVerify.tag("x", ToolVerify.Verdict(Status.FAILED, "port closed")).contains("FAILED: port closed"))
        // The tag never makes a result look failed to the older checks.
        assertTrue(ClaimGuard.succeeded(ToolVerify.tag("Opened Settings.", ToolVerify.Verdict(Status.VERIFIED))))
        assertTrue(ToolVerify.PROMPT_RULE.contains("verified=true"))
    }
}
