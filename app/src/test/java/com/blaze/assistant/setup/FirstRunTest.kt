package com.blaze.assistant.setup

import com.blaze.assistant.agent.CloudRuns
import com.blaze.assistant.agent.CloudRunsTest
import com.blaze.assistant.connect.ChatItemsSyncTest
import com.blaze.assistant.connect.MemoryAdapter
import com.blaze.assistant.connect.MemoryStore
import com.blaze.assistant.connect.JsonSyncState
import com.blaze.assistant.connect.SyncCrypto
import com.blaze.assistant.connect.SyncEngine
import com.blaze.assistant.connect.SyncEngineTest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * 0.45.23 first run: 2 steps (sign in, then the chat with a starter prompt), fresh-install resync, offline, sign-in
 * cancel / back, returning users and upgrades. Every scenario runs 5 times (strict 5/5) against fakes of the gateway's
 * /v1/runs and /v1/sync: no network, no model.
 */
class FirstRunTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fiveTimes(name: String, body: (Int) -> Unit) {
        var pass = 0
        for (i in 1..5) { body(i); pass++ }
        assertEquals("$name strict 5/5", 5, pass)
    }

    private class Memory : MemoryStore {
        val lines = ArrayList<MemoryStore.Line>()
        override fun all() = lines.toList()
        override fun add(l: MemoryStore.Line) { lines += l }
        override fun remove(l: MemoryStore.Line) { lines.removeAll { it.text == l.text } }
    }

    /** One phone's memory sync (its own state, like CloudSync keeps per phone). */
    private class MemPhone(val server: SyncEngine.Api, val key: ByteArray) {
        val mem = Memory(); var st: String? = null
        fun sync() = SyncEngine(server, listOf(MemoryAdapter(mem)), key, JsonSyncState({ st }, { st = it }), { 5_000_000L }).run()
    }

    private val pass = "correct horse battery"

    private fun keyFor(server: SyncEngine.Api, passphrase: String): ByteArray {
        val m = server.meta(); return SyncCrypto.deriveKey(passphrase, m.salt, SyncCrypto.iterationsOf(m.kdf))
    }

    /** Device A (the old phone) has chats and memory synced to the account. */
    private fun accountWithHistory(run: Int): Triple<SyncEngineTest.FakeServer, ChatItemsSyncTest.Phone, MemPhone> {
        val server = SyncEngineTest.FakeServer().apply { salt = "Zmlyc3QtcnVuLXNhbHQ="; kdf = "pbkdf2-sha256:10000" }
        val key = keyFor(server, pass)
        server.check = SyncCrypto.makeCheck(key)
        val a = ChatItemsSyncTest.Phone(tmp.newFolder("a$run"), server, key)
        a.write("main", JSONObject().put("id", "main").put("title", "Blaze").put("updated", 10).put("messages", JSONArray()
            .put(JSONObject().put("id", 1).put("role", "USER").put("text", "remember my sister is Mira").put("at", 100))
            .put(JSONObject().put("id", 2).put("role", "BLAZE").put("text", "Saved: your sister is Mira.").put("at", 110))))
        a.sync()
        val am = MemPhone(server, key)
        am.mem.add(MemoryStore.Line("person", "Mira", "The user's sister is Mira.", "", 100))
        am.sync()
        return Triple(server, a, am)
    }

    private fun gatewayClient(gw: CloudRunsTest.FakeGateway): CloudRuns.Client {
        val kv = HashMap<String, String>(); var clock = 1_000_000L
        val store = CloudRuns.RunStore({ kv[it] }, { k, v -> kv[k] = v }, { clock })
        return CloudRuns.Client(gw, store, sleep = { clock += it }, now = { clock }, detached = { f -> runBlocking { f() } })
    }

    private fun ask(text: String) = JSONArray().put(JSONObject().put("role", "system").put("content", "sys")).put(JSONObject().put("role", "user").put("content", text))

    // ---------------------------------------------------------------- the 2 steps

    @Test fun exactlyTwoStepsLikeDesktop() = fiveTimes("two steps") {
        assertEquals(listOf("signin", "chat"), FirstRun.STEPS)
        val out = FirstRun.reduce(FirstRun.Step.SIGN_IN, FirstRun.Event.SignedIn)
        assertEquals(FirstRun.Step.CHAT, out.step)
        assertEquals(FirstRun.Landing(FirstRun.STARTER, signedIn = true), out.land)
        // Step 2 is the last: nothing in the chat moves the flow anywhere else.
        for (e in listOf(FirstRun.Event.SignedIn, FirstRun.Event.NotNow, FirstRun.Event.Cancel, FirstRun.Event.Back(true), FirstRun.Event.Back(false)))
            assertEquals(FirstRun.Out(FirstRun.Step.CHAT), FirstRun.reduce(FirstRun.Step.CHAT, e))
        // Desktop's land(): the starter only goes into an empty composer, and is not sent.
        assertEquals(FirstRun.STARTER, FirstRun.draftFor("", out.land!!))
        assertEquals(FirstRun.STARTER, FirstRun.draftFor("   ", out.land!!))
        assertNull(FirstRun.draftFor("a shared link", out.land!!))
    }

    @Test fun notNowLandsInTheChatToo() = fiveTimes("not now") {
        val out = FirstRun.reduce(FirstRun.Step.SIGN_IN, FirstRun.Event.NotNow)
        assertEquals(FirstRun.Step.CHAT, out.step)
        assertEquals(FirstRun.Landing(FirstRun.STARTER, signedIn = false), out.land)
        assertFalse(out.cancelBrowser); assertFalse(out.leaveApp)
    }

    // ---------------------------------------------------------------- fresh install + sign-in -> resync -> first answer

    @Test fun freshInstallEmptyDbSignInResyncsHistoryAndTheFirstAnswerWorks() = fiveTimes("fresh install resync") { run ->
        val (server, _, _) = accountWithHistory(run)
        // Fresh install: empty chats folder and memory, never onboarded, signed out -> step 1 shows.
        val bDir = tmp.newFolder("b$run")
        assertTrue(FirstRun.shouldShow(onboarded = false, signedIn = false, hasChats = false, upgraded = false))
        assertTrue(File(bDir, "chats").listFiles().orEmpty().isEmpty())
        // Sign in -> step 2.
        val land = FirstRun.reduce(FirstRun.Step.SIGN_IN, FirstRun.Event.SignedIn).land!!
        assertTrue(land.signedIn)
        // The account keeps a copy -> the restore card is offered (sync is off on the new phone).
        val uid = "u_1"
        assertTrue(FirstRun.shouldCheckCopy(signedIn = true, syncOn = false, dismissedFor = "", accountId = uid))
        assertTrue(FirstRun.offerRestore(signedIn = true, syncOn = false, accountHasCopy = server.meta().setUp, dismissedFor = "", accountId = uid))
        // A wrong passphrase doesn't open it (and nothing is pulled).
        assertFalse(SyncCrypto.checks(keyFor(server, "wrong passphrase"), server.meta().check))
        // The right one: the first round pulls chats and memory.
        val key = keyFor(server, pass)
        assertTrue(SyncCrypto.checks(key, server.meta().check))
        val b = ChatItemsSyncTest.Phone(bDir, server, key)
        val r = b.sync()
        assertTrue("pulled ${r.pulled}", r.pulled >= 3)
        assertEquals(listOf("remember my sister is Mira", "Saved: your sister is Mira."), b.texts("main"))
        val bm = MemPhone(server, key)
        bm.sync()
        assertEquals(listOf("The user's sister is Mira."), bm.mem.all().map { it.text })
        // Sync is on now: the card is gone.
        assertFalse(FirstRun.offerRestore(signedIn = true, syncOn = true, accountHasCopy = true, dismissedFor = "", accountId = uid))
        // History appears, so the next launch is a returning user.
        assertFalse(FirstRun.shouldShow(onboarded = true, signedIn = true, hasChats = true))
        // The first answer: the starter the composer holds, through the gateway's /v1/runs (fake), answers.
        val gw = CloudRunsTest.FakeGateway()
        gw.brain = { rr, _ -> rr.emit("status", JSONObject().put("status", "running")); gw.done(rr, "Here are three things I can do.") }
        val m = runBlocking { gatewayClient(gw).complete(CloudRuns.Turn("main", "t-first-$run"), ask(land.draft), null, "chat") }
        assertEquals("Here are three things I can do.", m.getString("content"))
        assertEquals(1, gw.created)
    }

    @Test fun anAccountWithNoCopyOffersNothingAndTheFirstAnswerStillWorks() = fiveTimes("no copy") { run ->
        val server = SyncEngineTest.FakeServer().apply { setUp = false }
        assertFalse(FirstRun.offerRestore(true, false, server.meta().setUp, "", "u"))
        val gw = CloudRunsTest.FakeGateway()
        val m = runBlocking { gatewayClient(gw).complete(CloudRuns.Turn("main", "t-nc-$run"), ask(FirstRun.STARTER), null, "chat") }
        assertEquals("Hi", m.getString("content"))
    }

    @Test fun notNowOnTheRestoreCardHidesItForThatAccountOnly() = fiveTimes("restore not now") {
        assertFalse(FirstRun.offerRestore(true, false, true, dismissedFor = "u_1", accountId = "u_1"))
        assertFalse(FirstRun.shouldCheckCopy(true, false, dismissedFor = "u_1", accountId = "u_1"))
        assertTrue(FirstRun.offerRestore(true, false, true, dismissedFor = "u_1", accountId = "u_2"))
        assertFalse(FirstRun.offerRestore(false, false, true, "", ""))
    }

    // ---------------------------------------------------------------- offline during first run

    @Test fun offlineDuringFirstRunKeepsCheckingAndThenRestoresAndAnswers() = fiveTimes("offline") { run ->
        val (server, _, _) = accountWithHistory(run)
        // Offline: the copy can't be checked (null). No card yet, but it keeps checking.
        assertFalse(FirstRun.offerRestore(true, false, accountHasCopy = null, dismissedFor = "", accountId = "u"))
        assertTrue(FirstRun.shouldCheckCopy(true, false, "", "u"))
        // Offline for the sign-in step itself: "Not now" still lands in the chat.
        assertEquals(FirstRun.Step.CHAT, FirstRun.reduce(FirstRun.Step.SIGN_IN, FirstRun.Event.NotNow).step)
        // A round that starts offline fails and leaves nothing half-written; the next round (back online) restores.
        var down = 2
        val flaky = object : SyncEngine.Api by server {
            override fun pull(since: Long, limit: Int): SyncEngine.Page { if (down-- > 0) throw IOException("no network"); return server.pull(since, limit) }
        }
        val key = keyFor(server, pass)
        val b = ChatItemsSyncTest.Phone(tmp.newFolder("off$run"), SyncEngineTest.FakeServer(), key)
        val bm = MemPhone(flaky, key)
        try { bm.sync(); fail("expected offline") } catch (_: Exception) { }
        assertTrue(bm.mem.all().isEmpty())
        try { bm.sync(); fail("expected offline") } catch (_: Exception) { }
        bm.sync()
        assertEquals(1, bm.mem.all().size)
        assertNotNull(b)
        // The first message sent while offline is retried until it lands, as one run, and answers.
        val gw = CloudRunsTest.FakeGateway().apply { this.down = 3 }
        val m = runBlocking { gatewayClient(gw).complete(CloudRuns.Turn("main", "t-off-$run"), ask(FirstRun.STARTER), null, "chat") }
        assertEquals("Hi", m.getString("content"))
        assertEquals(1, gw.created)
    }

    // ---------------------------------------------------------------- sign-in cancel / back

    @Test fun signInCancelAndBack() = fiveTimes("cancel/back") {
        // Cancel the browser sign-in: stay on the step, the browser wait is dropped.
        val c = FirstRun.reduce(FirstRun.Step.SIGN_IN, FirstRun.Event.Cancel)
        assertEquals(FirstRun.Out(FirstRun.Step.SIGN_IN, cancelBrowser = true), c)
        // Back while the browser waits = cancel, stay.
        assertEquals(FirstRun.Out(FirstRun.Step.SIGN_IN, cancelBrowser = true), FirstRun.reduce(FirstRun.Step.SIGN_IN, FirstRun.Event.Back(pending = true)))
        // Back with nothing waiting leaves the app; the flag isn't set, so the step comes back next launch.
        val b = FirstRun.reduce(FirstRun.Step.SIGN_IN, FirstRun.Event.Back(pending = false))
        assertTrue(b.leaveApp); assertNull(b.land); assertEquals(FirstRun.Step.SIGN_IN, b.step)
        assertTrue(FirstRun.shouldShow(onboarded = false, signedIn = false, hasChats = false))
        // After a cancel, signing in still works and lands in the chat.
        assertEquals(FirstRun.Step.CHAT, FirstRun.reduce(c.step, FirstRun.Event.SignedIn).step)
    }

    // ---------------------------------------------------------------- returning users and upgrades

    @Test fun aReturningUserSkipsOnboarding() = fiveTimes("returning") {
        assertFalse(FirstRun.shouldShow(onboarded = true, signedIn = false, hasChats = false))
        assertFalse(FirstRun.shouldShow(onboarded = false, signedIn = true, hasChats = false))
        assertFalse(FirstRun.shouldShow(onboarded = false, signedIn = false, hasChats = true))
    }

    @Test fun anUpgradeFrom0_45_22DoesNotReShowOnboarding() = fiveTimes("upgrade 0.45.22") {
        // 0.45.22 wrote "onboarded" when its flow ended (or was skipped), and on every launch after it.
        assertEquals("onboarded", FirstRun.PREF)
        assertFalse(FirstRun.shouldShow(onboarded = true, signedIn = true, hasChats = true, upgraded = true))
        // Even a 0.45.22 user who left mid-flow (no flag, signed out, no chats) isn't shown a first run after the update.
        val installed = 1_700_000_000_000L
        assertTrue(FirstRun.upgraded(installed, installed + 86_400_000L))
        assertFalse(FirstRun.shouldShow(onboarded = false, signedIn = false, hasChats = false, upgraded = FirstRun.upgraded(installed, installed + 86_400_000L)))
        // A fresh install has equal times.
        assertFalse(FirstRun.upgraded(installed, installed))
        assertFalse(FirstRun.upgraded(installed, installed + 900))
    }

    // ---------------------------------------------------------------- permissions just in time

    @Test fun notificationsAreAskedWhenALongTaskNeedsThemOnce() = fiveTimes("notify jit") {
        assertFalse(NotifyAsk.shouldAsk(33, granted = false, askedBefore = false, workingMs = 0, foreground = true))
        assertFalse(NotifyAsk.shouldAsk(33, false, false, NotifyAsk.AFTER_MS - 1, true))
        assertTrue(NotifyAsk.shouldAsk(33, false, false, NotifyAsk.AFTER_MS, true))
        assertFalse(NotifyAsk.shouldAsk(33, false, askedBefore = true, workingMs = NotifyAsk.AFTER_MS, foreground = true))
        assertFalse(NotifyAsk.shouldAsk(33, granted = true, askedBefore = false, workingMs = NotifyAsk.AFTER_MS, foreground = true))
        assertFalse(NotifyAsk.shouldAsk(33, false, false, NotifyAsk.AFTER_MS, foreground = false))
        assertFalse(NotifyAsk.shouldAsk(32, false, false, NotifyAsk.AFTER_MS, true))   // below 13 there is no prompt
    }
}
