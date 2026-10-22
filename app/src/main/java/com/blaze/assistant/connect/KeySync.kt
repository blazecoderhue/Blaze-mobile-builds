package com.blaze.assistant.connect

import android.util.Base64
import com.blaze.assistant.agent.KeyRing
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Model API keys follow the Blaze account: Blaze Desktop, Blaze CLI and this app merge the
 * same encrypted copy through blaze-auth (GET/POST /v1/keys). Same wire format and merge rules
 * as internal/keystore/sync.go in Blaze-dekstop and Blaze-cli; change them together.
 *
 *  blob      = base64(nonce[12] || AES-256-GCM(plaintext, aad "blaze-keys-v1"))
 *  plaintext = {"v":1,"keys":{"gemini":{"k":["main","extra"],"t":<unix ms>}, ...}}
 *
 * Entry "mcp" (since Android v0.9.0) carries the MCP server list instead of keys: k = [the mcp.json text,
 * {"mcpServers": {name: {url, headers}}}], t = when it last changed here. The newer list wins as a whole.
 * Older clients keep entries they don't know, so it passes through them untouched.
 *
 * Per provider the newer "t" wins; an empty "k" is a deletion; keys whose age is unknown here
 * (saved before syncing existed) are merged with the account's instead of replaced. Only model
 * keys sync: connector tokens, the session and the PC pairing never leave the phone.
 * The Worker stores the blob and hands its key to signed-in sessions, so this keeps keys out of
 * logs and leaked values but is not end-to-end encryption.
 */
object KeySync {
    val SYNCED = listOf("gemini", "groq", "cerebras", "openrouter", "mistral", "nvidia", "deepseek", "moonshot", "openai", "anthropic", "xai")
    /** Pseudo-provider for the MCP server list (see the header). */
    const val MCP = "mcp"
    /**
     * v0.25.0: Blaze Cloud holds the model keys, so the phone no longer uploads or downloads provider keys: only the
     * MCP server list syncs. Provider entries already on the account pass through untouched (Desktop still reads them),
     * and the old key prefs on this phone stay as they were, unused.
     */
    private fun applies(p: String) = p == MCP
    private const val AAD = "blaze-keys-v1"
    private const val LAST = "keysync_last"   // unix ms of the last good sync
    private const val DEBOUNCE_MS = 8_000L    // the key field saves on every keystroke

    class Unsupported : Exception("Key sync isn't switched on in Blaze's service yet.")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var pending: Job? = null

    private val _status = MutableStateFlow("")
    /** Last sync outcome as a plain sentence, for Settings › Account. */
    val status: StateFlow<String> = _status

    fun stampKey(pid: String) = "keysync_t_$pid"
    fun stamp(pid: String): Long = Settings.str(stampKey(pid)).toLongOrNull() ?: 0L
    fun lastSync(): Long = Settings.str(LAST).toLongOrNull() ?: 0L

    /** A key was added, changed or removed on this phone: remember when, and sync shortly. */
    fun localChange(pid: String) {
        if (!applies(pid)) return
        Settings.setStr(stampKey(pid), System.currentTimeMillis().toString())
        if (!BlazeAccount.signedIn()) return
        pending?.cancel()
        pending = scope.launch { delay(DEBOUNCE_MS); runCatching { sync() } }
    }

    /** At app start: sync when signed in and the last sync is over an hour old. */
    fun syncIfStale() {
        if (!BlazeAccount.signedIn() || System.currentTimeMillis() - lastSync() < 3_600_000L) return
        scope.launch { runCatching { sync() } }
    }

    /** Right after signing in. */
    fun syncSoon() { scope.launch { runCatching { sync() } } }

    // ---------------- merge ----------------

    data class Entry(val k: List<String>, val t: Long)

    private fun clean(l: List<String>) = l.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun local(): Map<String, Entry> {
        val out = HashMap<String, Entry>()
        if (Mcp.hasConfig() || stamp(MCP) > 0) {
            // A list from before syncing existed gets an age now, so it is never merged line by line.
            if (stamp(MCP) == 0L) Settings.setStr(stampKey(MCP), System.currentTimeMillis().toString())
            out[MCP] = Entry(listOf(Mcp.syncedText()), stamp(MCP))
        }
        return out
    }

    /** Returns (merged, toApply, push). Mirrors keystore.Merge in Go. */
    fun merge(local: Map<String, Entry>, remote: Map<String, Entry>, now: Long): Triple<Map<String, Entry>, Map<String, Entry>, Boolean> {
        val merged = HashMap<String, Entry>(); val apply = HashMap<String, Entry>(); var push = false
        for (p in local.keys + remote.keys) {
            val l0 = local[p]; val r0 = remote[p]
            val l = l0?.let { Entry(clean(it.k), it.t) }; val r = r0?.let { Entry(clean(it.k), it.t) }
            when {
                r == null && l != null -> {
                    var e: Entry = l
                    if (l.t == 0L) { e = Entry(l.k, now); if (applies(p)) apply[p] = e }
                    merged[p] = e; push = true
                }
                l == null && r != null -> { merged[p] = r; if (r.k.isNotEmpty() && applies(p)) apply[p] = r }
                l != null && r != null && l.t == 0L -> {
                    val u = clean(r.k + l.k)
                    val e = if (u == r.k) r else { push = true; Entry(u, now) }
                    merged[p] = e; if (applies(p)) apply[p] = e
                }
                l != null && r != null && l.t > r.t -> { merged[p] = l; push = true }
                l != null && r != null && r.t > l.t -> { merged[p] = r; if (applies(p)) apply[p] = r }
                l != null && r != null -> { merged[p] = r; if (l.k != r.k && applies(p)) apply[p] = r }
            }
        }
        return Triple(merged, apply, push)
    }

    // ---------------- wire ----------------

    private fun encrypt(key: ByteArray, plain: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(AAD.toByteArray())
        return Base64.encodeToString(nonce + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(key: ByteArray, blob: String): String {
        val raw = Base64.decode(blob, Base64.DEFAULT)
        require(raw.size > 28) { "blob too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        c.updateAAD(AAD.toByteArray())
        return String(c.doFinal(raw, 12, raw.size - 12))
    }

    private fun http(method: String, token: String, body: JSONObject? = null): Pair<Int, JSONObject> {
        val c = UrlSafety.open(BlazeAccount.base() + "/v1/keys")
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "Blaze-Android")
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to (runCatching { JSONObject(text) }.getOrElse { JSONObject() })
        } finally { c.disconnect() }
    }

    private fun parse(o: JSONObject?): Map<String, Entry> {
        val out = HashMap<String, Entry>()
        if (o == null) return out
        for (p in o.keys()) {
            val e = o.optJSONObject(p) ?: continue
            val a = e.optJSONArray("k")
            out[p] = Entry((0 until (a?.length() ?: 0)).map { a!!.optString(it) }, e.optLong("t"))
        }
        return out
    }

    private fun applyEntry(pid: String, e: Entry) {
        if (pid == MCP) {
            Mcp.applySynced(e.k.firstOrNull { it.isNotBlank() } ?: "")
            Settings.setStr(stampKey(pid), e.t.toString())
            return
        }
        val k = clean(e.k)
        Settings.setKeyForSynced(pid, k.firstOrNull() ?: "")
        KeyRing.setExtrasSynced(pid, k.drop(1))
        Settings.setStr(stampKey(pid), e.t.toString())
    }

    /**
     * Merges this phone's model keys with the account's copy. Returns a plain sentence.
     * Signed out, offline or an older Worker (404) leave everything as it is.
     */
    suspend fun sync(): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val msg = try { syncLocked() } catch (e: Unsupported) { e.message!! } catch (e: Exception) {
                "Couldn't sync keys: " + (e.message ?: e.javaClass.simpleName)
            }
            _status.value = msg
            msg
        }
    }

    private fun syncLocked(): String {
        val token = BlazeAccount.sessionToken()
        if (token.isBlank()) return "Sign in to your Blaze account to sync your MCP servers."
        repeat(3) {
            val (code, st) = http("GET", token)
            when {
                code == 404 || code == 405 -> throw Unsupported()
                code == 401 -> { BlazeAccount.checkSession(); return "You're signed out. Sign in again." }
                code >= 400 -> throw Exception("http $code")
            }
            val key = Base64.decode(st.optString("key"), Base64.DEFAULT)
            if (key.size != 32) throw Exception("the service sent no usable key")
            val rev = st.optLong("rev")
            val blob = st.optString("blob")
            var remote: Map<String, Entry> = emptyMap()
            if (blob.isNotEmpty()) {
                val p = JSONObject(decrypt(key, blob))
                if (p.optInt("v", 1) > 1) return "Your keys were saved by a newer Blaze. Update Blaze to sync them."
                remote = parse(p.optJSONObject("keys"))
            }
            val (merged, toApply, push) = merge(local(), remote, System.currentTimeMillis())
            val pulled = toApply.filter { (pid, e) -> if (pid == MCP) clean(e.k).firstOrNull() != Mcp.syncedText() else KeyRing.all(pid) != clean(e.k) }.keys.sorted()
            for ((pid, e) in toApply) applyEntry(pid, e)
            if (!push) {
                Settings.setStr(LAST, System.currentTimeMillis().toString())
                return if (pulled.isNotEmpty()) "Got ${names(pulled)} from your Blaze account." else "Up to date."
            }
            val keys = JSONObject()
            for ((pid, e) in merged) keys.put(pid, JSONObject().put("k", JSONArray(e.k)).put("t", e.t))
            val out = encrypt(key, JSONObject().put("v", 1).put("keys", keys).toString())
            val (pc, _) = http("POST", token, JSONObject().put("rev", rev).put("blob", out))
            when {
                pc == 409 -> return@repeat   // another device wrote first: merge on top of it
                pc == 401 -> { BlazeAccount.checkSession(); return "You're signed out. Sign in again." }
                pc >= 400 -> throw Exception("http $pc")
            }
            Settings.setStr(LAST, System.currentTimeMillis().toString())
            return if (pulled.isNotEmpty()) "Got ${names(pulled)} from your Blaze account." else "Your MCP servers are on your Blaze account."
        }
        return "Other devices kept changing keys; try again."
    }

    private fun names(ids: List<String>) = ids.joinToString(", ") { if (it == MCP) "MCP servers" else com.blaze.assistant.agent.Providers.byId(it).name.substringBefore(" (") }
}
