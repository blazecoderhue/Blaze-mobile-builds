package com.blaze.assistant.browser.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 0.45.19 Blaze Phone UI fixes 4 (picture-in-picture), 6 (Take control: only Hand back) and 9 (sweep), strict 5/5.
 * The window itself (floating, its buttons, tap to expand) needs a real phone; see PHONE_UI_FIXES.md.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class PhoneUiFixesTest {
    private fun root(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private fun src(p: String) = File(root(), "com/blaze/assistant/$p").readText()
    private fun manifest(): String = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
    private fun activityBlock(name: String) = manifest().substringAfter("android:name=\"$name\"").substringBefore(">")

    private fun fiveTimes(name: String, body: (Int) -> Unit) {
        var pass = 0
        for (i in 1..5) { body(i); pass++ }
        assertEquals("$name strict 5/5", 5, pass)
    }

    // ------------------------------------------------------------------ fix 4: picture-in-picture
    @Test fun pipFollowsOnlyWhileBlazeWorksOnBlazePhone() = fiveTimes("pip working") { run ->
        val chat = "chat-$run"
        assertTrue(PhonePip.working(PhonePolicy.Mode.AGENT, chat, setOf(chat)))
        assertTrue("waiting for the user counts", PhonePip.working(PhonePolicy.Mode.WAITING, chat, setOf(chat, "x")))
        assertFalse("turn finished", PhonePip.working(PhonePolicy.Mode.AGENT, chat, emptySet()))
        assertFalse("user drives", PhonePip.working(PhonePolicy.Mode.USER, chat, setOf(chat)))
        assertFalse("nothing alive", PhonePip.working(PhonePolicy.Mode.OFF, chat, setOf(chat)))
        assertFalse("no owner", PhonePip.working(PhonePolicy.Mode.AGENT, null, setOf(chat)))
        assertFalse("another chat is busy, not the driver", PhonePip.working(PhonePolicy.Mode.AGENT, chat, setOf("other")))
        assertTrue(PhonePip.wanted(true, true, 26 + run, locked = false))
        assertFalse("never over a locked Blaze", PhonePip.wanted(true, true, 34, locked = true))
        assertFalse("no PiP feature", PhonePip.wanted(true, false, 34, false))
        assertFalse(PhonePip.wanted(false, true, 34, false))
    }

    @Test fun autoEnterOn31AndLeaveHintBelow() = fiveTimes("pip entry") {
        for (sdk in 31..35) { assertTrue(PhonePip.autoEnter(sdk)); assertFalse(PhonePip.enterOnLeaveHint(sdk, true, false)) }
        for (sdk in 26..30) {
            assertFalse(PhonePip.autoEnter(sdk))
            assertTrue(PhonePip.enterOnLeaveHint(sdk, true, false))
            assertFalse("opening Blaze's own screen is not leaving", PhonePip.enterOnLeaveHint(sdk, true, true))
            assertFalse(PhonePip.enterOnLeaveHint(sdk, false, false))
        }
        assertTrue(PhonePip.ownScreen("com.blaze.assistant", "com.blaze.assistant"))
        assertFalse(PhonePip.ownScreen("com.google.android.apps.maps", "com.blaze.assistant"))
        assertFalse(PhonePip.ownScreen(null, "com.blaze.assistant"))
    }

    @Test fun pipWindowShapeButtonsAndLabel() = fiveTimes("pip window") { run ->
        val (w, h) = PhonePip.aspect(1080, 2400 + run)
        assertTrue("clamped to Android's 1:2.39", w.toDouble() / h >= 1 / 2.39 - 1e-9)
        assertEquals(1080 to 1920, PhonePip.aspect(1080, 1920))
        assertEquals(9 to 16, PhonePip.aspect(0, 0))
        assertEquals(listOf(PhonePip.ACTION_STOP, PhonePip.ACTION_OPEN), PhonePip.actions(true))
        assertEquals(listOf(PhonePip.ACTION_OPEN), PhonePip.actions(false))
        assertEquals("Stop", PhonePip.title(PhonePip.ACTION_STOP)); assertEquals("Open Blaze", PhonePip.title(PhonePip.ACTION_OPEN))
        assertNull("live view only while working", PhonePip.label(true, PhonePolicy.Mode.AGENT, "Typing on Blaze Phone"))
        assertEquals("Stopped", PhonePip.label(false, PhonePolicy.Mode.AGENT, "Stopped"))
        assertEquals("Blaze needs you", PhonePip.label(true, PhonePolicy.Mode.WAITING, ""))
        assertEquals("Done", PhonePip.label(false, PhonePolicy.Mode.AGENT, "Reading Blaze Phone's page"))
    }

    @Test fun stopReallyCancelsTheDrivingTurn() = fiveTimes("pip stop") { run ->
        val stopped = mutableListOf<String>(); val cancelled = mutableListOf<String>()
        // A main-chat turn drives Blaze Phone.
        PhoneBrowser.own("chat-$run", null)
        assertTrue(PhoneBrowser.stopTask({ stopped += it; true }, { cancelled += it }))
        assertEquals(listOf("chat-$run"), stopped); assertTrue(cancelled.isEmpty())
        assertNull(PhoneBrowser.ownerChat); assertNull(PhoneBrowser.ownerAgent)
        assertEquals("Stopped", PhoneBrowser.live.value.step)
        assertFalse(PhoneBrowser.live.value.agentWaiting)
        assertFalse("nothing works there any more", PhonePip.working(PhoneBrowser.live.value.mode, PhoneBrowser.ownerChat, setOf("chat-$run")))
        // A background agent drives it: the agent is cancelled (BgAgents.cancel), not just its turn.
        PhoneBrowser.own("agent-chat-$run", "B$run")
        assertTrue(PhoneBrowser.stopTask({ stopped += it; false }, { cancelled += it }))
        assertEquals(listOf("B$run"), cancelled)
        // Nothing driving: Stop says so.
        assertFalse(PhoneBrowser.stopTask({ stopped += it; true }, { cancelled += it }))
    }

    @Test fun pipIsWiredIntoBothScreens() = fiveTimes("pip wiring") {
        for (a in listOf(".MainActivity", ".browser.phone.PhoneBrowserActivity")) {
            val b = activityBlock(a)
            assertTrue("$a supportsPictureInPicture", b.contains("android:supportsPictureInPicture=\"true\""))
            assertTrue("$a survives the PiP resize", b.contains("screenSize") && b.contains("smallestScreenSize") && b.contains("screenLayout"))
        }
        assertTrue(manifest().contains("android:name=\".browser.phone.PhonePipReceiver\" android:exported=\"false\""))
        val host = src("browser/phone/PhonePipHost.kt")
        assertTrue(host.contains("setAutoEnterEnabled(autoEnter)") && host.contains("Build.VERSION.SDK_INT >= 31"))
        assertTrue(host.contains("a.enterPictureInPictureMode(params(a, w, false))"))
        assertTrue(host.contains("RemoteAction(") && host.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertTrue("Stop cancels the turn and the agent", host.contains("Agent.stopChat(it)") && host.contains("BgAgents.cancel(it)"))
        assertTrue("tapping Open returns to Blaze", host.contains("moveToFront()"))
        val main = src("MainActivity.kt")
        assertTrue(main.contains("override fun onUserLeaveHint()") && main.contains("PhonePipHost.enterOnLeave(this"))
        assertTrue(main.contains("override fun onPictureInPictureModeChanged"))
        assertTrue("only the live view in PiP", main.contains("if (pip.value && !locked.value) com.blaze.assistant.browser.phone.PhonePipView()"))
        assertTrue("the chat is not drawn in PiP", main.contains("if (!pip.value) p.place(0, 0)"))
        assertTrue(main.contains("FLAG_ACTIVITY_NO_USER_ACTION"))
        val screen = src("browser/phone/PhoneBrowserActivity.kt")
        assertTrue(screen.contains("if (pip.value) PhonePipView()"))
        assertTrue(screen.contains("override fun onUserLeaveHint()"))
        assertTrue(src("agent/Agent.kt").contains("com.blaze.assistant.browser.phone.PhoneBrowser.own(id, agentId)"))
    }

    // ------------------------------------------------------------------ fix 6: Take control → only Hand back
    @Test fun inControlOnlyHandBack() = fiveTimes("hand back only") {
        assertEquals(listOf("Hand back"), PhoneUiRules.buttons(control = true))
        assertEquals(listOf("Take control"), PhoneUiRules.buttons(control = false))
        assertFalse(PhoneUiRules.buttons(true).contains("Done"))
        assertEquals("Blaze paused, you're in control", PhoneUiRules.controlLine(true))
        assertEquals("You're in control", PhoneUiRules.controlLine(false))
        val screen = src("browser/phone/PhoneBrowserActivity.kt")
        assertFalse("no Done button", screen.contains("\"Done\""))
        assertTrue(screen.contains("for (label in PhoneUiRules.buttons(control))"))
        // Hand back with nobody waiting closes to the chat; closing (back / arrow) while in control hands back first.
        val hb = screen.substringAfter("private fun handBack() {").substringBefore("\n    }\n")
        assertTrue(hb.contains("PhoneBrowser.handBackNow()") && hb.contains("if (!agentWaiting) finishToChat()"))
        val btc = screen.substringAfter("override fun backToChat() {").substringBefore("\n    }\n")
        assertTrue(btc.contains("if (control.value) { control.value = false; PhoneBrowser.handBackNow() }") && btc.contains("finishToChat()"))
    }

    // ------------------------------------------------------------------ fix 9: sweep
    @Test fun tileRulesAndHonestStepLines() = fiveTimes("tile") { run ->
        val now = 1_000_000L + run
        assertTrue(PhoneUiRules.showTile(PhonePolicy.Mode.AGENT, now - 1000, now, -1))
        assertFalse("leaves 2 minutes after the last step", PhoneUiRules.showTile(PhonePolicy.Mode.AGENT, now - PhoneUiRules.RECENT_MS - 1, now, -1))
        assertTrue("always while Blaze needs the user, even if hidden", PhoneUiRules.showTile(PhonePolicy.Mode.WAITING, now - 999_999, now, now))
        assertFalse("hidden until the next step", PhoneUiRules.showTile(PhonePolicy.Mode.AGENT, now - 10, now, now))
        assertTrue(PhoneUiRules.showTile(PhonePolicy.Mode.AGENT, now, now, now - 1))
        assertFalse(PhoneUiRules.showTile(PhonePolicy.Mode.OFF, now, now, -1))
        assertEquals("automationintesting.online", PhoneUiRules.host("https://www.automationintesting.online/reservation/1?x"))
        assertEquals("Blaze needs you: Solve the captcha", PhoneUiRules.pill(PhonePolicy.Mode.WAITING, "x", "Solve the captcha"))
        // The tile said "Tapping e61": an element id is never shown as if it were a button's name.
        assertEquals("Tapping on the page", PhoneBrowserTools.describe("phone_act", JSONObject().put("action", "click").put("target", "e${run}1")))   // rel merge kept chat's readable() wording
        assertEquals("Tapping Reserve Now", PhoneBrowserTools.describe("phone_act", JSONObject().put("action", "click").put("target", "Reserve Now")))
    }

    @Test fun sweepFixesStayInPlace() = fiveTimes("sweep") {
        val pb = src("browser/phone/PhoneBrowser.kt")
        assertTrue("atomic live updates (frame thread vs main thread)", pb.contains("private fun set(f: (Live) -> Live) { _live.update(f) }"))
        assertTrue("a cancelled wait clears agentWaiting and the dead hand-back", pb.contains("if (handBack === d) handBack = null") && pb.contains("catch (e: kotlinx.coroutines.CancellationException)"))
        assertTrue("watchers counted under a lock", pb.contains("@Synchronized fun watch(on: Boolean)"))
        assertTrue("release forgets the driver", pb.substringAfter("fun release() {").substringBefore("\n    }\n").contains("ownerChat = null"))
        val tile = src("ui/PhoneLiveCard.kt")
        assertTrue("the tile has a clock for its 2-minute window", tile.contains("LaunchedEffect(live.at, live.mode)") && tile.contains("PhoneUiRules.showTile("))
        val screen = src("browser/phone/PhoneBrowserActivity.kt")
        assertTrue("WebView context never left on a destroyed screen", screen.contains("PhoneBrowser.forget(this)"))
        assertTrue("the hosted WebView is detached when its holder goes", screen.contains("onRelease = { f -> f.removeAllViews() }"))
        assertFalse("no note set on a screen that closes in the same tap", screen.contains("note = \""))
        val tools = src("browser/phone/PhoneBrowserTools.kt")
        assertTrue(tools.contains("d.step(\"Tapping \\u201c\${t.label.take(40)}\\u201d\")"))
    }
}
