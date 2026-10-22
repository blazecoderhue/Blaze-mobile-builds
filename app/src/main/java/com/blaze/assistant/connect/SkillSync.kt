package com.blaze.assistant.connect

import android.util.Base64
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.agent.SkillLibrary
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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Skill sync through the Blaze account (blaze-auth `/v1/skills`, Worker 0.8.0): the user's own
 * skills (learned by Blaze, written or installed by them; not the bundled library, which every
 * client ships in its own adapted form) follow the account to Blaze Desktop, the CLI and other phones.
 *
 *   blob = base64(nonce[12] || AES-256-GCM(gzip(json), aad "blaze-skills-v1"))
 *   json = {"v":1,"skills":{"<name>":{"t":<unix ms>,"files":{"SKILL.md":"…"}}}}
 *
 * `~/skills/.sync.json` remembers what was last synced ({name: {t, sha}}) so a local edit gets a
 * new time and a local delete becomes a deletion record. Merge rules: [SkillMerge].
 */
object SkillSync {
    private const val AAD = "blaze-skills-v1"
    private const val LAST = "skillsync_last"
    private const val MAX_FILE = 256 * 1024
    private const val MAX_BLOB = 2 * 1024 * 1024

    class Unsupported : Exception("Skill sync isn't switched on in Blaze's service yet (it needs blaze-auth 0.8.0).")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var pending: Job? = null

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    fun lastSync(): Long = Settings.str(LAST).toLongOrNull() ?: 0L

    /** A skill was written, installed or deleted here: sync shortly. */
    fun localChange() {
        if (!BlazeAccount.signedIn()) return
        pending?.cancel()
        pending = scope.launch { delay(5_000); runCatching { sync() } }
    }

    /** At app start: sync when signed in and the last sync is over an hour old. */
    fun syncIfStale() {
        if (!BlazeAccount.signedIn() || System.currentTimeMillis() - lastSync() < 3_600_000L) return
        scope.launch { runCatching { sync() } }
    }

    fun syncSoon() { scope.launch { runCatching { sync() } } }

    // ---------------- local skills ----------------

    private fun root(): File = File(com.blaze.assistant.terminal.Terminal.home(), "skills").apply { mkdirs() }
    private fun manifestFile() = File(root(), ".sync.json")
    private fun manifest(): JSONObject = try { JSONObject(manifestFile().readText()) } catch (_: Exception) { JSONObject() }

    private fun bundledNames(): Set<String> = SkillLibrary.bundled().map { it.name }.toSet()

    private fun sha(files: Map<String, String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for ((p, t) in files.toSortedMap()) { md.update(p.toByteArray()); md.update(0); md.update(t.toByteArray()); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readSkill(dir: File): Map<String, String> {
        val out = sortedMapOf<String, String>()
        dir.walkTopDown().filter { it.isFile && it.length() <= MAX_FILE }.forEach { f ->
            val rel = f.relativeTo(dir).path.replace('\\', '/')
            if (!SkillMerge.safePath(rel) || rel.split('/').any { it.startsWith(".") }) return@forEach
            val bytes = f.readBytes()
            val text = String(bytes, Charsets.UTF_8)
            if (text.contains('\u0000') || text.contains('\uFFFD')) return@forEach   // binary: not synced
            out[rel] = text
        }
        return out
    }

    private fun local(): Map<String, SkillMerge.Rec> {
        val man = manifest(); val skip = bundledNames(); val out = HashMap<String, SkillMerge.Rec>()
        val dirs = (root().listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") && it.name !in skip && SkillMerge.safeName(it.name) }
        for (d in dirs) {
            val files = readSkill(d)
            if (!files.containsKey("SKILL.md")) continue
            val rec = man.optJSONObject(d.name)
            val mtime = d.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: d.lastModified()
            val t = when {
                rec == null -> mtime
                rec.optString("sha") == sha(files) -> rec.optLong("t")
                else -> maxOf(mtime, rec.optLong("t") + 1)
            }
            out[d.name] = SkillMerge.Rec(t, files)
        }
        // Synced before but gone now: deleted on this phone (or a kept deletion record).
        for (n in man.keys()) if (n !in out && n !in skip) {
            val rec = man.getJSONObject(n)
            out[n] = SkillMerge.Rec(if (rec.optBoolean("deleted")) rec.optLong("t") else maxOf(System.currentTimeMillis(), rec.optLong("t") + 1), emptyMap())
        }
        return out
    }

    private fun applyLocal(name: String, r: SkillMerge.Rec) {
        if (!SkillMerge.safeName(name) || name in bundledNames()) return
        val dir = File(root(), name)
        if (r.deleted) { dir.deleteRecursively(); return }
        val staging = File(root(), ".incoming-$name").apply { deleteRecursively(); mkdirs() }
        for ((p, text) in r.files) if (SkillMerge.safePath(p)) File(staging, p).apply { parentFile?.mkdirs(); writeText(text) }
        dir.deleteRecursively(); staging.renameTo(dir)
    }

    private fun saveManifest(merged: Map<String, SkillMerge.Rec>) {
        val m = JSONObject()
        for ((n, r) in merged) m.put(n, JSONObject().put("t", r.t).put("sha", if (r.deleted) "" else sha(r.files)).put("deleted", r.deleted))
        runCatching { manifestFile().writeText(m.toString()) }
    }

    // ---------------- wire ----------------

    private fun gzip(s: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()
    private fun gunzip(b: ByteArray): String = GZIPInputStream(ByteArrayInputStream(b)).use { String(it.readBytes()) }

    private fun encrypt(key: ByteArray, plain: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(AAD.toByteArray())
        return Base64.encodeToString(nonce + c.doFinal(gzip(plain)), Base64.NO_WRAP)
    }

    private fun decrypt(key: ByteArray, blob: String): String {
        val raw = Base64.decode(blob, Base64.DEFAULT)
        require(raw.size > 28) { "blob too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        c.updateAAD(AAD.toByteArray())
        return gunzip(c.doFinal(raw, 12, raw.size - 12))
    }

    private fun http(method: String, token: String, body: JSONObject? = null): Pair<Int, JSONObject> {
        val c = UrlSafety.open(BlazeAccount.base() + "/v1/skills")
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000; c.readTimeout = 30_000
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

    private fun parse(o: JSONObject?): Map<String, SkillMerge.Rec> {
        val out = HashMap<String, SkillMerge.Rec>()
        if (o == null) return out
        for (n in o.keys()) {
            if (!SkillMerge.safeName(n)) continue
            val e = o.optJSONObject(n) ?: continue
            val f = e.optJSONObject("files") ?: JSONObject()
            val files = sortedMapOf<String, String>()
            for (p in f.keys()) if (SkillMerge.safePath(p)) files[p] = f.optString(p)
            out[n] = SkillMerge.Rec(e.optLong("t"), files)
        }
        return out
    }

    /** Merges this phone's own skills with the account's. Returns a plain sentence. */
    suspend fun sync(): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val msg = try { syncLocked() } catch (e: Unsupported) { e.message!! } catch (e: Exception) {
                "Couldn't sync skills: " + (e.message ?: e.javaClass.simpleName)
            }
            _status.value = msg
            msg
        }
    }

    private fun syncLocked(): String {
        val token = BlazeAccount.sessionToken()
        if (token.isBlank()) return "Sign in to your Blaze account to take your skills to all your devices."
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
            var remote: Map<String, SkillMerge.Rec> = emptyMap()
            if (blob.isNotEmpty()) {
                val p = JSONObject(decrypt(key, blob))
                if (p.optInt("v", 1) > 1) return "Your skills were saved by a newer Blaze. Update Blaze to sync them."
                remote = parse(p.optJSONObject("skills"))
            }
            val res = SkillMerge.merge(local(), remote)
            for ((n, r) in res.apply) applyLocal(n, r)
            val got = res.apply.filterValues { !it.deleted }.keys.sorted()
            val gone = res.apply.filterValues { it.deleted }.keys.sorted()
            if (!res.push) {
                saveManifest(res.merged)
                Settings.setStr(LAST, System.currentTimeMillis().toString())
                return summary(got, gone, pushed = false)
            }
            val skills = JSONObject()
            for ((n, r) in res.merged) skills.put(n, JSONObject().put("t", r.t).put("files", JSONObject(r.files as Map<*, *>)))
            val out = encrypt(key, JSONObject().put("v", 1).put("skills", skills).toString())
            if (out.length > MAX_BLOB) return "Your skills are too big to sync (over 2 MB). Remove big files from ~/skills and try again."
            val (pc, _) = http("POST", token, JSONObject().put("rev", rev).put("blob", out))
            when {
                pc == 409 -> return@repeat
                pc == 401 -> { BlazeAccount.checkSession(); return "You're signed out. Sign in again." }
                pc >= 400 -> throw Exception("http $pc")
            }
            saveManifest(res.merged)
            Settings.setStr(LAST, System.currentTimeMillis().toString())
            return summary(got, gone, pushed = true)
        }
        return "Other devices kept changing skills; try again."
    }

    private fun summary(got: List<String>, gone: List<String>, pushed: Boolean): String {
        val parts = mutableListOf<String>()
        if (got.isNotEmpty()) parts += "Got ${got.size} skill${if (got.size == 1) "" else "s"} from your account (${got.take(4).joinToString(", ")}${if (got.size > 4) "…" else ""})"
        if (gone.isNotEmpty()) parts += "removed ${gone.size} deleted elsewhere"
        if (pushed) parts += if (parts.isEmpty()) "Your skills are on your Blaze account" else "sent yours up"
        return if (parts.isEmpty()) "Skills are up to date." else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }
}
