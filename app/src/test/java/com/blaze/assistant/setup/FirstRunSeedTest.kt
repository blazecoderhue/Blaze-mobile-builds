package com.blaze.assistant.setup

import com.blaze.assistant.connect.ChatItemsSyncTest
import com.blaze.assistant.connect.JsonSyncState
import com.blaze.assistant.connect.MemoryAdapter
import com.blaze.assistant.connect.MemoryStore
import com.blaze.assistant.connect.SyncCrypto
import com.blaze.assistant.connect.SyncEngine
import com.blaze.assistant.connect.SyncEngineTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The account copy the emulator clean-install job restores (tools/firstrun): an "other phone" syncs a chat and a memory
 * line with the real adapters and crypto, and the server rows are written as the mock gateway's seed. Runs in every
 * unit-test run (checks the seed builds); writes the file only when BLAZE_SEED_OUT is set.
 */
class FirstRunSeedTest {
    @get:Rule val tmp = TemporaryFolder()

    companion object {
        const val PASSPHRASE = "blaze-ci-passphrase"
        const val SALT = "YmxhemUtY2ktc2FsdC0wMQ=="   // fixed: the seed is reproducible
        const val KDF = "pbkdf2-sha256:10000"
        const val HISTORY = "CI-HISTORY-OK: this chat came back from your other phone."
    }

    @Test fun seedForTheEmulatorJob() {
        val server = SyncEngineTest.FakeServer().apply { salt = SALT; kdf = KDF }
        val key = SyncCrypto.deriveKey(PASSPHRASE, SALT, SyncCrypto.iterationsOf(KDF))
        server.check = SyncCrypto.makeCheck(key)
        val a = ChatItemsSyncTest.Phone(tmp.newFolder("a"), server, key)
        a.write("main", JSONObject().put("id", "main").put("title", "Blaze").put("updated", 10).put("messages", JSONArray()
            .put(JSONObject().put("id", 1).put("role", "USER").put("text", "Remember that my sister is Mira.").put("at", 1_700_000_000_000L))
            .put(JSONObject().put("id", 2).put("role", "BLAZE").put("text", HISTORY).put("at", 1_700_000_001_000L))))
        a.sync()
        val mem = object : MemoryStore {
            val l = arrayListOf(MemoryStore.Line("person", "Mira", "The user's sister is Mira.", "", 1_700_000_000_000L))
            override fun all() = l.toList(); override fun add(x: MemoryStore.Line) { l += x }; override fun remove(x: MemoryStore.Line) { l.removeAll { it.text == x.text } }
        }
        var st: String? = null
        SyncEngine(server, listOf(MemoryAdapter(mem)), key, JsonSyncState({ st }, { st = it }), { 5_000_000L }).run()
        assertTrue(server.rows.keys.any { it.startsWith("memory|") })
        assertTrue(server.rows.keys.any { it.startsWith("msg|") })
        val rows = JSONArray()
        server.rows.forEach { (k, r) -> rows.put(JSONObject().put("kind", k.substringBefore('|')).put("id", k.substringAfter('|')).put("seq", r.seq)
            .put("rev", r.rev).put("deleted", r.deleted).put("blob", r.blob ?: JSONObject.NULL).put("updated_at", r.at)) }
        val seed = JSONObject().put("meta", JSONObject().put("set_up", true).put("salt", SALT).put("kdf", KDF).put("check", server.check).put("epoch", server.epoch))
            .put("seq", server.seq).put("rows", rows).put("passphrase", PASSPHRASE).put("history", HISTORY)
        System.getenv("BLAZE_SEED_OUT")?.takeIf { it.isNotBlank() }?.let { File(it).apply { parentFile?.mkdirs() }.writeText(seed.toString(2)) }
    }
}
