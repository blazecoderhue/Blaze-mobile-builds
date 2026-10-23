package com.blaze.assistant.ui

import android.view.KeyEvent
import com.blaze.assistant.agent.ChatNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.45.19 Blaze Phone UI fix 7, strict 5/5: the system Back button returns to the Blaze chat from Blaze Phone and from
 * every other window / view / sheet / page, through ONE rule ([BackRule]). This enumerates every view id (Views.all),
 * every settings page, every overlay of the chat shell and every activity in the manifest, and checks Back lands on
 * the chat. BackRuleWindowTest drives real key events through the window interceptor.
 */
class BackRuleTest {
    private fun root(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private fun src(p: String) = File(root(), "com/blaze/assistant/$p").readText()
    private fun manifest(): String = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()

    private fun fiveTimes(name: String, body: (Int) -> Unit) {
        var pass = 0
        for (i in 1..5) { body(i); pass++ }
        assertEquals("$name strict 5/5", 5, pass)
    }

    /** Every activity class the manifest declares (relative names resolved). */
    private fun activities(): List<String> = Regex("""<activity\s[^>]*?android:name="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
        .findAll(manifest()).map { it.groupValues[1] }.map { if (it.startsWith(".")) "com.blaze.assistant$it" else it }.toList()

    @Test fun everyViewIdGoesBackToChat() = fiveTimes("every view") {
        assertTrue(Views.all.size >= 30)
        for (v in Views.all) {
            val sh = BackRule.shellFor(v)
            if (sh == null) {
                val act = BackRule.SPECIAL_ACTIVITY[v.special]
                assertNotNull("${v.id}: an activity", act)
                assertTrue("${v.id} ($act) Back goes to chat", BackRule.activityGoesToChat(act!!))
                assertTrue("${v.id} ($act) is in the manifest", act in activities())
            } else {
                assertEquals("${v.id} front=${BackRule.front(sh)}", BackRule.Action.TO_CHAT, BackRule.action(sh))
                assertFalse(v.id, BackRule.front(sh) == BackRule.CHAT)
            }
        }
    }

    @Test fun everySettingsPageAndEveryOverlayGoesBackToChat() = fiveTimes("pages and overlays") { run ->
        val pages = Views.all.mapNotNull { it.page }.toSet() + setOf("", "linux", "ai", "account", "control", "look", "phonebrowser", "page-$run")
        for (p in pages) assertEquals("page '$p'", BackRule.Action.TO_CHAT, BackRule.action(BackRule.Shell(settingsPage = p)))
        val overlays = listOf(
            BackRule.Shell(viewsSheet = true), BackRule.Shell(plusMenu = true), BackRule.Shell(arc = true),
            BackRule.Shell(avatarMenu = true), BackRule.Shell(search = true), BackRule.Shell(gallery = true),
            BackRule.Shell(room = true), BackRule.Shell(themePicker = true), BackRule.Shell(view = "design"),
            BackRule.Shell(tab = ChatNav.PROJECTS), BackRule.Shell(tab = ChatNav.PROJECTS, inProjectChat = true),
            BackRule.Shell(tab = ChatNav.HOME), BackRule.Shell(swipeHome = true),
            // Stacked: a page over a sheet over Projects still goes straight to the chat in one press.
            BackRule.Shell(tab = ChatNav.PROJECTS, viewsSheet = true, settingsPage = "memory"),
        )
        val fronts = mutableSetOf<String>()
        for (o in overlays) {
            assertEquals("$o", BackRule.Action.TO_CHAT, BackRule.action(o))
            fronts += BackRule.front(o)
        }
        assertTrue("every overlay has its own front id", fronts.size >= 13)
    }

    @Test fun theChatIsTheRootAndTheLockSetupAndCallKeepTheirBack() = fiveTimes("root") {
        assertEquals(BackRule.CHAT, BackRule.front(BackRule.Shell()))
        assertEquals(BackRule.Action.OWN, BackRule.action(BackRule.Shell()))
        assertEquals(BackRule.Action.OWN, BackRule.action(BackRule.Shell(onboarding = true, settingsPage = "ai")))
        assertEquals(BackRule.Action.OWN, BackRule.action(BackRule.Shell(gate = true)))
        assertEquals(BackRule.Action.OWN, BackRule.action(BackRule.Shell(inCall = true)))
        // Back never skips the app lock.
        BackRule.toChat = { }; BackRule.shellNow = { BackRule.Shell(settingsPage = "memory") }
        assertTrue(BackRule.shellTakesBack(locked = false))
        assertFalse(BackRule.shellTakesBack(locked = true))
        BackRule.shellNow = { BackRule.Shell() }
        assertFalse(BackRule.shellTakesBack(locked = false))
        BackRule.toChat = null; BackRule.shellNow = { BackRule.Shell(settingsPage = "memory") }
        assertFalse("no shell yet", BackRule.shellTakesBack(locked = false))
        BackRule.shellNow = { BackRule.Shell() }
    }

    @Test fun everyActivityInTheManifestGoesBackToChatOrIsAnExplainedHelper() = fiveTimes("activities") {
        val acts = activities().filter { it.startsWith("com.blaze.assistant.") }
        assertTrue(acts.size >= 9)
        for (a in acts) {
            when {
                a == BackRule.MAIN_ACTIVITY -> assertFalse(BackRule.activityGoesToChat(a))
                a in BackRule.EXEMPT_ACTIVITIES -> assertTrue(BackRule.EXEMPT_ACTIVITIES[a]!!.length > 20)
                else -> assertTrue("$a: Back goes to chat", BackRule.activityGoesToChat(a))
            }
        }
        assertTrue(BackRule.activityGoesToChat("com.blaze.assistant.browser.phone.PhoneBrowserActivity"))
        assertTrue("a future activity is covered without changes", BackRule.activityGoesToChat("com.blaze.assistant.newthing.FutureActivity"))
        assertFalse("not other libraries' screens", BackRule.activityGoesToChat("com.journeyapps.barcodescanner.CaptureActivity"))
    }

    @Test fun keyStepsTakeTheWholePressOnce() = fiveTimes("key steps") {
        val back = KeyEvent.KEYCODE_BACK
        assertEquals(BackRule.KeyStep(true, false), BackRule.keyStep(back, down = true, up = false, canceled = false, repeat = 0, takes = true, wasArmed = false))
        assertEquals(BackRule.KeyStep(true, true), BackRule.keyStep(back, down = false, up = true, canceled = false, repeat = 0, takes = false, wasArmed = true))
        assertEquals("canceled (predictive gesture abandoned)", BackRule.KeyStep(true, false), BackRule.keyStep(back, false, true, true, 0, false, true))
        assertEquals("not taken at down: untouched", BackRule.KeyStep(false, false), BackRule.keyStep(back, true, false, false, 0, false, false))
        assertEquals(BackRule.KeyStep(false, false), BackRule.keyStep(back, false, true, false, 0, false, false))
        assertEquals("other keys pass", BackRule.KeyStep(false, false), BackRule.keyStep(KeyEvent.KEYCODE_ENTER, true, false, false, 0, true, true))
    }

    @Test fun theRuleIsWiredInOnePlace() = fiveTimes("wiring") {
        val app = src("BlazeApp.kt"); val main = src("MainActivity.kt"); val chat = src("ui/ChatScreen.kt")
        assertTrue(app.contains("com.blaze.assistant.ui.BackRule.install(this)"))
        assertTrue(main.contains("BackRule.Shelled") && main.contains("BackRule.shellTakesBack(locked.value)"))
        assertTrue(chat.contains("BackRule.shellNow = {") && chat.contains("BackRule.toChat = { backToChat() }"))
        // backToChat closes everything the Shell can name.
        val btc = chat.substringAfter("fun backToChat() {").substringBefore("\n    }\n")
        for (k in listOf("settingsPage = null", "showViews = false", "plusOpen = false", "arcOpen = false", "avatarMenu = false", "showSearch = false",
            "GalleryUi.open.value = false", "Agent.roomOpen.value = -1L", "ThemePickerState.open.value = false", "Views.showing.value = \"\"",
            "switchTab(ChatNav.CHAT)", "settleTo(1f)")) assertTrue("backToChat: $k", btc.contains(k))
        // Back from Chat no longer drops to Home (that loop made Back unable to leave Blaze).
        assertFalse(chat.contains("BackHandler(enabled = swipeMode && sheetP > 0.5f && nav.tab == ChatNav.CHAT && !nav.inProjectChat) { settleTo(0f) }"))
        // openView handles exactly the specials BackRule knows.
        val ov = chat.substringAfter("fun openView(v: BlazeView").substringBefore("\n    }\n")
        val specials = Regex(""""([a-z]+)" ->""").findAll(ov).map { it.groupValues[1] }.toSet()
        assertEquals(BackRule.SPECIAL_ACTIVITY.keys + BackRule.SPECIAL_SHELL, specials)
        // Back still arrives as a key event (no predictive-back opt-in that would bypass the interceptor).
        assertFalse(manifest().contains("enableOnBackInvokedCallback"))
        // Blaze Phone and Video take their last step through ToChat.
        assertTrue(src("browser/phone/PhoneBrowserActivity.kt").contains("BackRule.ToChat"))
        assertTrue(src("mediaapps/VideoActivity.kt").contains("BackRule.ToChat"))
    }

    @Test fun shellForMapsEachKindOfView() = fiveTimes("shellFor") {
        assertNull(BackRule.shellFor(Views.find("phonebrowser")!!))
        assertEquals("gallery", BackRule.front(BackRule.shellFor(Views.find("gallery")!!)!!))
        assertEquals("home", BackRule.front(BackRule.shellFor(Views.find("home")!!)!!))
        assertEquals("projects", BackRule.front(BackRule.shellFor(Views.find("projects")!!)!!))
        assertEquals("view:design", BackRule.front(BackRule.shellFor(Views.find("design")!!)!!))
        assertEquals("page:memory", BackRule.front(BackRule.shellFor(Views.find("memory")!!)!!))
        assertEquals("page:settings", BackRule.front(BackRule.shellFor(Views.find("settings")!!)!!))
    }
}
