package com.blaze.assistant.connect

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v0.40.0 Cloud sync (Settings › Account › Cloud sync, `/sync`): optional, end-to-end encrypted live sync of chats,
 * memory, settings and designs between phones signed in to the same Blaze account. **Off by default.**
 *
 *  - The user picks a sync passphrase. The key is derived from it on the phone ([SyncCrypto], PBKDF2 with the salt the
 *    gateway keeps) and stored Keystore-encrypted here ("conn_tok_" pref, so it never leaves the phone and a backup
 *    restored elsewhere can't read it). The passphrase itself is never stored or sent. A second phone checks the
 *    passphrase against the encrypted check blob before it syncs anything.
 *  - The Blaze Gateway (/v1/sync, gateway 0.4.0) keeps only ciphertext per item; [SyncEngine] does a round: pull,
 *    merge, push. Live: a push ~5 s after a local change, a round when Blaze comes to the front and every 60 s while
 *    it's open, and a WorkManager job every 15 min in the background (network required).
 *  - Never synced: the Blaze session, connector tokens, API keys, the PC pairing, permissions (see [SyncedSettings]).
 */
object CloudSync {
    private const val ON = "cloudsync_on"
    private const val KINDS_OFF = "cloudsync_kinds_off"
    private const val LAST = "cloudsync_last"
    private const val UID = "cloudsync_uid"
    private const val KEY = "conn_tok_cloudsync_key"   // Keystore-encrypted (Secrets.isSecretPref)
    private const val WORK = "cloud_sync"
    private const val DEBOUNCE_MS = 5_000L
    private const val LOOP_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var app: Context? = null
    @Volatile private var pending: Job? = null
    @Volatile private var loop: Job? = null
    @Volatile private var applying = false
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** What Settings › Account › Cloud sync draws. */
    data class View(val on: Boolean = false, val busy: Boolean = false, val status: String = "", val lastOk: Long = 0,
                    val needsPassphrase: Boolean = false, val off: Set<String> = emptySet(), val skipped: List<String> = emptyList())

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    // ---------------- state ----------------

    fun isOn(): Boolean = try { Settings.bool(ON, false) } catch (_: Throwable) { false }
    private fun kindsOff(): Set<String> = Settings.str(KINDS_OFF).split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun kindOn(kind: String) = kind !in kindsOff()
    private fun key(): ByteArray? = Settings.secret(KEY).takeIf { it.isNotBlank() }?.let { runCatching { SyncCrypto.unb64(it) }.getOrNull() }?.takeIf { it.size == 32 }

    private fun stateFile(): File? = app?.let { File(File(it.filesDir, "cloudsync").apply { mkdirs() }, "state.json") }
    private fun state() = JsonSyncState({ stateFile()?.takeIf { it.isFile }?.readText() }, { s ->
        stateFile()?.let { f -> val t = File(f.parentFile, "state.json.tmp"); t.writeText(s); t.renameTo(f) }
    })

    private fun publish(status: String? = null, busy: Boolean? = null, skipped: List<String>? = null) {
        val v = _view.value
        _view.value = v.copy(on = isOn(), busy = busy ?: v.busy, status = status ?: v.status,
            lastOk = Settings.str(LAST).toLongOrNull() ?: 0L, needsPassphrase = isOn() && key() == null,
            off = kindsOff(), skipped = skipped ?: v.skipped)
    }

    /** At app start (BlazeApp). Nothing happens while sync is off. */
    fun init(ctx: Context) {
        app = ctx.applicationContext
        publish()
        if (prefListener == null) {
            prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
                if (k != null && !applying && SyncedSettings.synced(k)) localChange(SyncKinds.SETTINGS)
            }.also { Settings.listen(it) }
        }
        if (isOn()) schedule(ctx, true)
    }

    // ---------------- adapters ----------------

    /** v0.45.0 (#37): the chats folder parsed once per change, kept between rounds (only changed files are read again). */
    @Volatile private var split: ChatSplit? = null
    private fun split(ctx: Context): ChatSplit = split ?: ChatSplit { File(ctx.filesDir, "chats").apply { mkdirs() } }.also { split = it }

    /** Message and reaction items follow the Chats switch. */
    private fun group(kind: String) = if (kind == ChatItems.MSG || kind == ChatItems.REACT) SyncKinds.CHATS else kind

    private fun adapters(st: SyncEngine.State): List<SyncEngine.Adapter> {
        val ctx = app ?: return emptyList()
        val sp = split(ctx)
        val all = listOf(
            // v0.45.0 (#37): chats as small items: the chat's own fields, one item per message, one per reaction.
            ChatHeadAdapter(sp) { id ->
                try { com.blaze.assistant.agent.Agent.reloadFromSync(id) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("CloudSync: Agent.reloadFr", e) }
            },
            MessageAdapter(sp) { id -> st.get(SyncKinds.CHATS, id).let { e -> e != null && (e.deleted || sp.peek(id) == null) } },
            ReactionAdapter(sp),
            MemoryAdapter(object : MemoryStore {
                override fun all() = com.blaze.assistant.agent.Mind.all().map { MemoryStore.Line(it.kind, it.subject, it.text, it.related, it.updated) }
                override fun add(l: MemoryStore.Line) { com.blaze.assistant.agent.Mind.save(l.kind, l.subject, l.text, l.related) }
                override fun remove(l: MemoryStore.Line) {
                    com.blaze.assistant.agent.Mind.all().filter { MemoryAdapter.idOf(it.text) == MemoryAdapter.idOf(l.text) }
                        .forEach { com.blaze.assistant.agent.Mind.delete(it.id) }
                }
            }),
            SettingsAdapter(object : PrefStore {
                override fun all(): Map<String, Any?> = Settings.rawAll()
                override fun put(key: String, v: Any?) = Settings.rawPut(key, v)
            }) { reloadSettings() },
            DesignAdapter({ com.blaze.assistant.agent.Designs.root() }),
        )
        return all.filter { kindOn(group(it.kind)) }
    }

    /** Settings that are live state elsewhere pick up the synced values. */
    private fun reloadSettings() {
        try { com.blaze.assistant.agent.SimpleMode.load() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("CloudSync: SimpleMode.loa", e) }
        try { com.blaze.assistant.agent.SwipeNav.load() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("CloudSync: SwipeNav.load", e) }
        try { com.blaze.assistant.ui.Look.reload() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("CloudSync: Look.reload", e) }
    }

    // ---------------- the gateway ----------------

    /** Blaze Gateway root (the same Worker that answers chat; its /v1/sync routes). */
    fun base(): String = com.blaze.assistant.agent.Providers.gatewayBase.trimEnd('/').removeSuffix("/v1")

    class HttpError(val code: Int, val err: String) : Exception("http $code $err")
    class Offline(cause: Exception) : Exception(cause.message, cause)

    private fun http(method: String, path: String, body: JSONObject? = null): JSONObject {
        val token = BlazeAccount.sessionToken()
        if (token.isBlank()) throw HttpError(401, "signed_out")
        val c = try { UrlSafety.open(base() + path) } catch (e: Exception) { throw Offline(e) }
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000; c.readTimeout = 45_000
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
            val o = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code >= 400) throw HttpError(code, o.optJSONObject("error")?.optString("code").orEmpty().ifBlank { "http_$code" })
            return o
        } catch (e: java.io.IOException) { throw Offline(e) } finally { c.disconnect() }
    }

    private fun metaOf(o: JSONObject) = SyncEngine.Meta(o.optBoolean("set_up"), o.optString("salt"), o.optString("kdf"), o.optString("check"), o.optString("epoch"), o.optLong("bytes"))

    private fun remoteOf(o: JSONObject) = SyncEngine.Remote(o.optString("kind"), o.optString("id"), o.optLong("seq"), o.optLong("rev"),
        o.optBoolean("deleted"), if (o.isNull("blob")) null else o.optString("blob").ifBlank { null }, o.optLong("updated_at"))

    private val api = object : SyncEngine.Api {
        override fun meta() = metaOf(http("GET", "/v1/sync/meta"))
        override fun pull(since: Long, limit: Int): SyncEngine.Page {
            val o = http("GET", "/v1/sync/pull?since=$since&limit=$limit")
            val a = o.optJSONArray("items") ?: JSONArray()
            return SyncEngine.Page((0 until a.length()).map { remoteOf(a.getJSONObject(it)) }, o.optLong("next", since), o.optBoolean("more"), o.optString("epoch"))
        }
        override fun push(items: List<SyncEngine.Out>): SyncEngine.PushResult {
            val arr = JSONArray()
            for (i in items) arr.put(JSONObject().put("kind", i.kind).put("id", i.id).put("base_rev", i.baseRev).apply { if (i.blob == null) put("deleted", true) else put("blob", i.blob) })
            val o = try { http("POST", "/v1/sync/push", JSONObject().put("items", arr)) } catch (e: HttpError) { if (e.code == 409 && e.err == "busy") throw SyncEngine.Busy() else throw e }
            fun list(n: String) = o.optJSONArray(n) ?: JSONArray()
            val acc = list("accepted").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { SyncEngine.Accepted(it.optString("kind"), it.optString("id"), it.optLong("rev")) } }
            val con = list("conflicts").let { a -> (0 until a.length()).map { remoteOf(a.getJSONObject(it)) } }
            val rej = list("rejected").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { SyncEngine.Rejected(it.optString("kind"), it.optString("id"), it.optString("error")) } }
            return SyncEngine.PushResult(acc, con, rej)
        }
    }

    /** A plain sentence for anything that stopped a round. */
    fun plain(e: Throwable): String = when (e) {
        is Offline -> "Waiting for internet."
        is HttpError -> when {
            e.code == 401 -> "You're signed out. Sign in to your Blaze account to keep syncing."
            e.code == 404 || e.code == 405 -> "Cloud sync isn't switched on in Blaze's service yet."
            e.err == "already_set_up" -> "Cloud sync already has a passphrase from another phone. Enter that one."
            e.code == 413 -> "That was too much to send at once. Blaze will try again in smaller parts."
            e.code == 429 -> "Blaze's service is busy. Trying again in a minute."
            e.code >= 500 -> "Blaze's service had a problem. Trying again in a minute."
            else -> "Couldn't sync (${e.err})."
        }
        is SyncEngine.Busy -> "Your other phone is syncing right now. Trying again shortly."
        is SyncCrypto.BadPassphrase -> e.message!!
        else -> "Couldn't sync: " + (e.message ?: e.javaClass.simpleName)
    }

    // ---------------- turning it on and off ----------------

    /**
     * Turns sync on with [passphrase]. The first phone sets it up on the account; a second phone checks it against the
     * account's check blob. Returns "" on success, else a plain sentence. Slow (key derivation): call from a coroutine.
     */
    suspend fun enable(passphrase: String): String = withContext(Dispatchers.IO) {
        val pass = passphrase.trim()
        if (!BlazeAccount.signedIn()) return@withContext "Sign in to your Blaze account first. Cloud sync keeps your copy with your account."
        if (pass.length < 8) return@withContext "Use a passphrase of at least 8 characters."
        try {
            val m = metaOf(http("GET", "/v1/sync/meta"))
            val key: ByteArray
            if (!m.setUp) {
                val salt = SyncCrypto.newSalt()
                key = SyncCrypto.deriveKey(pass, salt)
                http("PUT", "/v1/sync/meta", JSONObject().put("salt", salt).put("kdf", SyncCrypto.KDF).put("check", SyncCrypto.makeCheck(key)))
            } else {
                key = SyncCrypto.deriveKey(pass, m.salt, SyncCrypto.iterationsOf(m.kdf))
                if (!SyncCrypto.checks(key, m.check)) throw SyncCrypto.BadPassphrase()
            }
            Settings.setSecret(KEY, SyncCrypto.b64(key))
            Settings.setStr(UID, BlazeAccount.user()?.id.orEmpty())
            Settings.setBool(ON, true)
            stateFile()?.delete()   // a fresh start against the account's copy
            app?.let { schedule(it, true) }
            publish("Turning on…")
            syncSoon()
            ""
        } catch (e: Exception) { plain(e) }
    }

    /** True when the account already has a cloud copy (so the page asks for the existing passphrase, not a new one). */
    suspend fun accountHasCopy(): Boolean? = withContext(Dispatchers.IO) {
        try { metaOf(http("GET", "/v1/sync/meta")).setUp } catch (_: Exception) { null }
    }

    /** Stops syncing on this phone. Everything here stays; the cloud copy stays for your other phones. */
    fun turnOff(message: String = "Cloud sync is off. Everything on this phone stays.") {
        Settings.setBool(ON, false)
        Settings.setSecret(KEY, "")
        stateFile()?.delete()
        pending?.cancel(); loop?.cancel()
        app?.let { schedule(it, false) }
        publish(message, busy = false, skipped = emptyList())
    }

    /** Deletes the account's cloud copy (every phone stops syncing) and turns sync off here. "" or a plain sentence. */
    suspend fun deleteCloudCopy(): String = withContext(Dispatchers.IO) {
        lock.withLock {
            try { http("DELETE", "/v1/sync") } catch (e: Exception) { return@withLock plain(e) }
            turnOff("Your cloud copy is deleted. Everything on this phone stays.")
            ""
        }
    }

    fun setKind(kind: String, on: Boolean) {
        val off = kindsOff().toMutableSet()
        if (on) off -= kind else off += kind
        Settings.setStr(KINDS_OFF, off.sorted().joinToString(","))
        // Switching a kind back on re-reads the account's copy from the start, so nothing from the gap is missed.
        val st = state(); st.forgetKind(kind); if (on) st.cursor = 0
        if (kind == SyncKinds.CHATS) { st.forgetKind(ChatItems.MSG); st.forgetKind(ChatItems.REACT) }
        st.save()
        publish()
        if (on) syncSoon()
    }

    // ---------------- live ----------------

    /** Something changed here (a chat saved, a memory line, a synced setting): push in ~5 s. */
    fun localChange(kind: String) {
        try {
            if (applying || !isOn() || !kindOn(kind) || key() == null) return
            pending?.cancel()
            pending = scope.launch { delay(DEBOUNCE_MS); runCatching { sync() } }
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("CloudSync: if applying isOn kindOnkind key null ret", e) }   // unit tests and early start: prefs may not be ready
    }

    fun syncSoon() { if (isOn()) scope.launch { runCatching { sync() } } }

    /** MainActivity: a round when Blaze comes to the front, then one a minute while it stays there. */
    fun foreground(front: Boolean) {
        try {
            loop?.cancel(); loop = null
            if (!front || !isOn()) return
            loop = scope.launch {
                while (isActive) { runCatching { sync() }; delay(LOOP_MS) }
            }
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("CloudSync: loop.cancel loop null", e) }
    }

    private fun schedule(ctx: Context, on: Boolean) {
        try {
            val wm = WorkManager.getInstance(ctx)
            if (!on) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("CloudSync: val wm WorkManager.getInstancectx", e) }
    }

    /** Sync now: also retries items that were skipped for size or a full cloud copy. Returns the status line. */
    suspend fun syncNow(): String {
        try { val st = state(); st.retrySkipped(); st.save() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("CloudSync: val st state st.retrySkipped st.save", e) }
        return sync()
    }

    /** One round. Returns the status line (also in [view]). */
    suspend fun sync(): String = withContext(Dispatchers.IO) {
        if (!isOn()) return@withContext "Cloud sync is off."
        lock.withLock {
            val key = key()
            if (key == null) { publish("Enter your sync passphrase to keep syncing."); return@withLock _view.value.status }
            if (!BlazeAccount.signedIn() || BlazeAccount.sessionToken().isBlank()) {
                publish("You're signed out. Sign in to your Blaze account to keep syncing."); return@withLock _view.value.status
            }
            val uid = BlazeAccount.user()?.id.orEmpty()
            if (Settings.str(UID).isNotEmpty() && uid.isNotEmpty() && uid != Settings.str(UID)) {
                turnOff("You signed in to a different Blaze account, so cloud sync is off. Turn it on again for this account.")
                return@withLock _view.value.status
            }
            publish(busy = true)
            val msg = try {
                applying = true
                val st = state()
                val r = try { SyncEngine(api, adapters(st), key, st).run() } finally { split?.release() }
                Settings.setStr(LAST, System.currentTimeMillis().toString())
                publish(skipped = r.skipped)
                when {
                    r.full -> "Your cloud copy is full (25 MB). Turn off Designs or delete some, and Blaze syncs the rest."
                    r.failedOpen > 0 -> "Synced, but ${r.failedOpen} item(s) couldn't be opened with this passphrase."
                    else -> "Synced"
                }
            } catch (e: SyncEngine.NotSetUp) {
                applying = false
                turnOff("Your cloud copy was deleted from another phone, so sync is off here. Everything on this phone stays.")
                return@withLock _view.value.status
            } catch (e: SyncEngine.WrongKey) {
                applying = false
                Settings.setSecret(KEY, "")
                "The passphrase was changed on another phone. Enter the new one to keep syncing."
            } catch (e: HttpError) {
                if (e.code == 401) runCatching { BlazeAccount.checkSession() }
                plain(e)
            } catch (e: Exception) { plain(e) } finally { applying = false }
            publish(msg, busy = false)
            msg
        }
    }

    /** "Synced just now", "Synced 4 min ago", or the last problem, in plain words. */
    fun statusLine(v: View, now: Long = System.currentTimeMillis()): String {
        if (!v.on) return "Off"
        if (v.needsPassphrase) return "Enter your sync passphrase to keep syncing."
        if (v.busy && v.lastOk == 0L) return "Syncing…"
        if (v.status.isNotEmpty() && v.status != "Synced" && v.status != "Turning on…") return v.status
        if (v.lastOk == 0L) return if (v.status == "Turning on…") "Turning on…" else "Not synced yet"
        return "Synced " + ago(now - v.lastOk)
    }

    fun ago(ms: Long): String = when {
        ms < 60_000 -> "just now"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        ms < 86_400_000 -> "${ms / 3_600_000} h ago"
        else -> "${ms / 86_400_000} days ago"
    }
}

/** Every 15 minutes with a network, while cloud sync is on (WorkManager is already used for the memory review). */
class CloudSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!CloudSync.isOn()) return Result.success()
        runCatching { CloudSync.sync() }
        return Result.success()
    }
}
