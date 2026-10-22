package com.blaze.assistant

import android.content.Intent
import com.blaze.assistant.bank.BankModeLogic
import com.blaze.assistant.blazeos.OsBridgeLimits
import com.blaze.assistant.browser.SavedPages
import com.blaze.assistant.device.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.45.0 P4: security, UX and health items (#8, #9, #21, #24, #29, #33, #13 leftover, #27). */
class SecurityP4Test {
    private val call = "com.blaze.assistant.CALL"

    // ---- #8 exported MainActivity ----
    @Test fun outsideAppCannotTriggerInternalEntryPoints() {
        val a = IntentKey.Policy.allowed(null, listenExtra = true, openChat = true, openForm = true, trusted = false, callAction = call)
        assertFalse(a.listen); assertFalse(a.call); assertFalse(a.openChat); assertFalse(a.openForm)
        assertFalse(IntentKey.Policy.allowed(call, false, false, false, trusted = false, callAction = call).call)
    }

    @Test fun blazeOwnIntentsStillWork() {
        val a = IntentKey.Policy.allowed(null, listenExtra = true, openChat = true, openForm = true, trusted = true, callAction = call)
        assertTrue(a.listen); assertTrue(a.openChat); assertTrue(a.openForm)
        assertTrue(IntentKey.Policy.allowed(call, false, false, false, trusted = true, callAction = call).call)
    }

    @Test fun systemAssistantEntryPointsStayPublic() {
        assertTrue(IntentKey.Policy.allowed(Intent.ACTION_ASSIST, false, false, false, trusted = false, callAction = call).listen)
        assertTrue(IntentKey.Policy.allowed(Intent.ACTION_VOICE_COMMAND, false, false, false, trusted = false, callAction = call).listen)
        assertFalse(IntentKey.Policy.allowed(Intent.ACTION_SEND, false, false, false, trusted = false, callAction = call).listen)
    }

    @Test fun keyCheck() {
        assertTrue(IntentKey.Policy.keyOk("k".repeat(32), "k".repeat(32)))
        assertFalse(IntentKey.Policy.keyOk(null, "k".repeat(32)))
        assertFalse(IntentKey.Policy.keyOk("x".repeat(32), "k".repeat(32)))
    }

    // ---- #9 browser file access follows the page ----
    @Test fun fileAccessOnlyForSavedPages() {
        assertTrue(SavedPages.fileAccessFor("file:///data/user/0/com.blaze.assistant/files/saved_pages/abcd1234.mht"))
        assertFalse(SavedPages.fileAccessFor("https://example.com/"))
        assertFalse(SavedPages.fileAccessFor("http://example.com/saved_pages/abcd1234.mht"))
        assertFalse(SavedPages.fileAccessFor("file:///sdcard/Download/x.html"))
        assertFalse(SavedPages.fileAccessFor("file:///data/x/saved_pages/../../etc/abcd1234.mht"))
        assertFalse(SavedPages.fileAccessFor(null)); assertFalse(SavedPages.fileAccessFor(""))
    }

    // ---- #33 BlazeOS bridge limits ----
    @Test fun notifyIsRateLimited() {
        val r = OsBridgeLimits.Rate(OsBridgeLimits.NOTIFY_MAX, OsBridgeLimits.NOTIFY_WINDOW_MS)
        val ok = (0 until 50).count { r.allow(1_000L + it) }
        assertEquals(OsBridgeLimits.NOTIFY_MAX, ok)
        assertTrue(r.allow(1_000L + OsBridgeLimits.NOTIFY_WINDOW_MS + 1))
    }

    @Test fun storageWritesCoalesce() {
        val d = OsBridgeLimits.WriteDebounce(750)
        assertTrue(d.mark(0)); assertFalse(d.mark(10)); assertFalse(d.mark(500))   // one flush scheduled for the burst
        assertFalse(d.due(700)); assertTrue(d.due(760))
        d.flushed(); assertFalse(d.pending()); assertTrue(d.mark(800))
    }

    // ---- #24 bank mode restore wording ----
    @Test fun bankRestoreAskExplainsThenStops() {
        assertEquals(BankModeLogic.RESTORE_ASK, BankModeLogic.restoreAsk(0, simple = true)!!.text)
        val later = BankModeLogic.restoreAsk(2, simple = true)!!
        assertTrue(later.text.contains("Bank apps won't open"))
        assertFalse(later.text.contains("adb")); assertFalse(later.text.contains("computer command"))
        assertTrue(BankModeLogic.restoreAsk(3, simple = false)!!.text.contains("computer command"))
        assertNull(BankModeLogic.restoreAsk(BankModeLogic.RESTORE_GIVE_UP, simple = true))
        assertNotNull(BankModeLogic.restoreAsk(BankModeLogic.RESTORE_GIVE_UP - 1, simple = true))
    }

    // ---- #13 leftover: Simple never hears about run_shell in the core prompt ----
    @Test fun simpleFilesLineHasNoTerminal() {
        val s = com.blaze.assistant.agent.PromptProfile.filesLine(simple = true)
        assertFalse(s.contains("run_shell")); assertFalse(s.contains("terminal", ignoreCase = true))
        assertTrue(com.blaze.assistant.agent.PromptProfile.filesLine(simple = false).contains("run_shell"))
    }

    // ---- #29 quiet catches log, but never cancellation ----
    @Test fun quietSkipsCancellation() {
        assertFalse(CrashLog.quietLogs(kotlinx.coroutines.CancellationException("x")))
        assertFalse(CrashLog.quietLogs(InterruptedException()))
        assertTrue(CrashLog.quietLogs(IllegalStateException("x")))
        CrashLog.quiet("test", RuntimeException("no file yet"))   // never throws, even before init
    }

    // ---- source checks ----
    private fun mainSources(): List<File> = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** #21: user-facing messages never send people to a page Simple hides (or that no longer does anything). */
    @Test fun noMessagePointsAtSettingsAi() {
        val src = mainSources(); assertTrue("sources not found from ${File(".").absolutePath}", src.size > 100)
        val bad = Regex("""Settings (→|\\u2192|->) ?(AI|Model)\b""")
        val hits = src.flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (bad.containsMatchIn(l) && !l.trim().startsWith("//") && !l.trim().startsWith("*")) "${f.name}:${i + 1}" else null } }
        assertTrue("user-facing text still says Settings → AI: $hits", hits.isEmpty())
    }

    /** #29: the files cleaned in P4 keep no empty catch blocks. */
    @Test fun cleanedFilesHaveNoEmptyCatch() {
        val cleaned = setOf("Agent.kt", "BlazeApp.kt", "Mind.kt", "CloudSync.kt", "BrowserEngine.kt", "Bridge.kt", "OsWebApp.kt", "Dashboard.kt", "DashHttp.kt")
        val empty = Regex("""catch \(_: (Throwable|Exception)\) \{ ?\}""")
        val hits = mainSources().filter { it.name in cleaned }.flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (empty.containsMatchIn(l)) "${f.name}:${i + 1}" else null } }
        assertTrue("empty catch blocks came back: $hits", hits.isEmpty())
    }

    /** #27: the uncalled duo lanes stay deleted. */
    @Test fun deadDuoLanesStayDeleted() {
        val agent = mainSources().first { it.name == "Agent.kt" }.readText()
        listOf("fun runDuo(", "fun duoAll(", "fun duoTeam(", "fun duoSay(", "fun duoType(").forEach { assertFalse(it, agent.contains(it)) }
    }

    /** #8: every internal entry point Blaze builds is signed. */
    @Test fun internalIntentsAreSigned() {
        val src = mainSources().filter { it.name != "MainActivity.kt" && it.name != "IntentKey.kt" }
        val unsigned = src.flatMap { f ->
            val lines = f.readLines()
            lines.mapIndexedNotNull { i, l ->
                val internal = l.contains("MainActivity.EXTRA_LISTEN") || l.contains("MainActivity.ACTION_CALL") ||
                    Regex("""putExtra\(EXTRA_OPEN_(CHAT|FORM)""").containsMatchIn(l)
                if (!internal) null
                else if ((maxOf(0, i - 3)..i).any { lines[it].contains("IntentKey.sign") }) null else "${f.name}:${i + 1}"
            }
        }
        assertTrue("unsigned internal intents: $unsigned", unsigned.isEmpty())
    }
}
