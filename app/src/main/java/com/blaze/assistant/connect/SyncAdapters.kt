package com.blaze.assistant.connect

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * v0.40.0 cloud sync: one small adapter per kind of thing that syncs. Each lists its items with a cheap fingerprint,
 * reads one as JSON, and stores a copy from another phone. Chats and designs are files, so their adapters are plain
 * JVM code (SyncAdaptersTest); memory and settings live in blaze.db and prefs and are wired up in [CloudSync].
 *
 * What never syncs: the Blaze session, connector tokens and API keys (every "conn_tok_" / key pref), the PC pairing,
 * permissions and other per-phone switches, private chats (never written to disk), and design version history.
 */
object SyncKinds {
    const val CHATS = "chat"
    const val MEMORY = "memory"
    const val SETTINGS = "settings"
    const val DESIGNS = "design"
    val ALL = listOf(CHATS, MEMORY, SETTINGS, DESIGNS)

    fun label(kind: String) = when (kind) {
        CHATS -> "Chats"; MEMORY -> "Memory"; SETTINGS -> "Settings"; DESIGNS -> "Designs"; else -> kind
    }

    fun detail(kind: String) = when (kind) {
        CHATS -> "Your conversations, merged so no message is lost"
        MEMORY -> "What Blaze remembers about you"
        SETTINGS -> "Look & feel, Simple or Advanced, voice and reply style. Never keys or sign-ins"
        DESIGNS -> "Pages, posters and 3D designs (very large ones stay on this phone)"
        else -> ""
    }

    val ID_RE = Regex("^[A-Za-z0-9._:-]{1,128}$")
}

/**
 * Settings that follow you (the user's own choices, the same on every phone). Everything else in prefs stays: keys
 * and tokens, the session, the PC pairing, window and orb positions, permissions, stamps and one-time migrations.
 */
object SyncedSettings {
    val KEYS = setOf(
        // Simple / Advanced, navigation, Look & feel (not "look_photo": a path on this phone).
        "ui_mode", "nav_style", "look_bg", "look_accent", "look_music", "look_still", "theme_mode", "text_scale",
        // How Blaze talks and shows its work.
        "persona", "persona_custom", "user_name", "show_reasoning", "show_steps", "show_route", "fold_answers", "followups",
        "stream", "task_card", "auto_titles", "auto_learn", "learn_approval", "long_chat",
        // Approvals and modes.
        "approval", "work_mode", "autopilot_minutes", "autopilot_steps", "confirm_risky",
        // Voice and sounds (not the installed TTS voice: phones differ).
        "speak_replies", "tts_rate", "tts_pitch", "stt_lang", "call_greet", "call_bye", "sounds", "haptics",
        // Heads-ups and your own commands.
        "morning_brief", "habit_offers", "user_commands",
    )

    /** Never, even if someone adds it to [KEYS] by mistake. */
    fun forbidden(k: String) = k.startsWith("conn_tok_") || k.startsWith("key_") || k.startsWith("keys_extra_") ||
        k.startsWith("mcp_tok_") || k == "mcp_config" || k.contains("token") || k.contains("secret") || k.contains("pair") ||
        k.startsWith("cloudsync_") || k.startsWith("keysync_")

    fun synced(k: String) = k in KEYS && !forbidden(k)

    /** A pref value as JSON with its type, so it comes back as the same type. */
    fun encode(v: Any?): JSONObject? = when (v) {
        is String -> JSONObject().put("t", "s").put("v", v)
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        else -> null
    }

    fun decode(o: JSONObject): Any? = when (o.optString("t")) {
        "s" -> o.optString("v"); "b" -> o.optBoolean("v"); "i" -> o.optInt("v"); "l" -> o.optLong("v")
        "f" -> o.optDouble("v").toFloat(); else -> null
    }

    fun stamp(v: Any?): String = encode(v)?.toString() ?: ""
}

/** Chats: one ChatStore JSON file each (filesDir/chats/<id>.json). Private chats never reach the disk. */
class ChatAdapter(private val dir: () -> File, private val onApplied: (String) -> Unit = {}) : SyncEngine.Adapter {
    override val kind = SyncKinds.CHATS

    private fun file(id: String) = File(dir(), "$id.json")

    override fun stamps(): Map<String, String> {
        val out = HashMap<String, String>()
        for (f in dir().listFiles() ?: emptyArray()) {
            if (!f.isFile || !f.name.endsWith(".json")) continue
            val id = f.name.removeSuffix(".json")
            if (!SyncKinds.ID_RE.matches(id) || id.startsWith("private")) continue
            out[id] = "${f.lastModified()}:${f.length()}"
        }
        return out
    }

    override fun stamp(id: String): String? = file(id).takeIf { it.isFile }?.let { "${it.lastModified()}:${it.length()}" }

    override fun read(id: String): SyncMerge.Item? {
        val o = try { JSONObject(file(id).readText()) } catch (_: Exception) { return null }
        return SyncMerge.Item(kind, id, o.optLong("updated"), o)
    }

    override fun apply(item: SyncMerge.Item) {
        val f = file(item.id)
        if (item.deleted) { f.delete(); onApplied(item.id); return }
        val incoming = item.data!!.put("id", item.id)
        // Never overwrite: a trimmed copy from another phone must not drop older messages kept here.
        val here = try { JSONObject(f.readText()) } catch (_: Exception) { null }
        val out = if (here != null) SyncMerge.mergeChat(here, incoming) else incoming
        out.remove("trimmed")
        val tmp = File(dir(), f.name + ".sync.tmp")
        tmp.writeText(out.toString()); tmp.renameTo(f)
        onApplied(item.id)
    }

    override fun shrink(item: SyncMerge.Item, fits: (SyncMerge.Item) -> Boolean): SyncMerge.Item? {
        val data = item.data ?: return null
        return SyncMerge.trimChat(data) { fits(item.copy(data = it)) }?.let { item.copy(data = it) }
    }
}

/** Designs: ~/designs/<name>/index.html. Only the current page syncs (not its .v<N>.html undo history). */
class DesignAdapter(private val root: () -> File, private val onApplied: (String) -> Unit = {}) : SyncEngine.Adapter {
    override val kind = SyncKinds.DESIGNS
    private val nameRe = Regex("^[A-Za-z0-9._-]{1,120}$")

    private fun index(id: String): File? {
        val name = id.removePrefix("d.")
        if (!id.startsWith("d.") || !nameRe.matches(name) || name.startsWith(".")) return null
        return File(File(root(), name), "index.html")
    }

    override fun stamps(): Map<String, String> {
        val out = HashMap<String, String>()
        for (d in root().listFiles() ?: emptyArray()) {
            if (!d.isDirectory || !nameRe.matches(d.name) || d.name.startsWith(".")) continue
            val f = File(d, "index.html")
            if (f.isFile) out["d." + d.name] = "${f.lastModified()}:${f.length()}"
        }
        return out
    }

    override fun stamp(id: String): String? = index(id)?.takeIf { it.isFile }?.let { "${it.lastModified()}:${it.length()}" }

    override fun read(id: String): SyncMerge.Item? {
        val f = index(id)?.takeIf { it.isFile } ?: return null
        val html = try { f.readText() } catch (_: Exception) { return null }
        return SyncMerge.Item(kind, id, f.lastModified(), JSONObject().put("name", f.parentFile!!.name).put("html", html))
    }

    override fun apply(item: SyncMerge.Item) {
        val f = index(item.id) ?: return
        val dir = f.parentFile!!
        if (item.deleted) {
            f.delete()
            dir.listFiles()?.filter { it.name.startsWith(".v") && it.name.endsWith(".html") }?.forEach { it.delete() }
            if (dir.listFiles()?.isEmpty() == true) dir.delete()
            onApplied(item.id); return
        }
        dir.mkdirs()
        val tmp = File(dir, ".index.sync.tmp")
        tmp.writeText(item.data!!.optString("html")); tmp.renameTo(f)
        if (item.t > 0) f.setLastModified(item.t)
        onApplied(item.id)
    }
}

/** Memory lines, wherever they're kept: [MemoryAdapter] maps them to items by their words. */
interface MemoryStore {
    data class Line(val kind: String, val subject: String, val text: String, val related: String, val updated: Long)
    fun all(): List<Line>
    fun add(l: Line)
    fun remove(l: Line)
}

/**
 * Memory: one item per saved line. Row ids differ per phone, so the item id is a hash of the line's words: the same
 * fact on two phones is one item, and a line rewritten on one phone is a delete plus a new line on the other.
 */
class MemoryAdapter(private val store: MemoryStore) : SyncEngine.Adapter {
    override val kind = SyncKinds.MEMORY

    companion object {
        fun norm(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        fun idOf(text: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(norm(text).toByteArray())
            return "m" + d.take(16).joinToString("") { "%02x".format(it) }
        }
    }

    private fun byId(): Map<String, MemoryStore.Line> = store.all().associateBy { idOf(it.text) }

    override fun stamps(): Map<String, String> = byId().mapValues { (_, l) -> "${l.updated}:${l.kind}:${l.subject}" }

    override fun read(id: String): SyncMerge.Item? {
        val l = byId()[id] ?: return null
        return SyncMerge.Item(kind, id, l.updated, JSONObject().put("kind", l.kind).put("subject", l.subject).put("text", l.text).put("related", l.related))
    }

    override fun apply(item: SyncMerge.Item) {
        val here = byId()[item.id]
        if (item.deleted) { here?.let { store.remove(it) }; return }
        val d = item.data!!
        val text = d.optString("text")
        if (text.isBlank() || idOf(text) != item.id) return
        if (here != null && here.kind == d.optString("kind") && here.subject == d.optString("subject")) return
        if (here != null) store.remove(here)
        store.add(MemoryStore.Line(d.optString("kind"), d.optString("subject"), text, d.optString("related"), item.t))
    }
}

/** Settings: one item per key in [SyncedSettings.KEYS]. */
interface PrefStore {
    fun all(): Map<String, Any?>
    fun put(key: String, v: Any?)
}

class SettingsAdapter(private val prefs: PrefStore, private val onApplied: () -> Unit = {}) : SyncEngine.Adapter {
    override val kind = SyncKinds.SETTINGS

    private fun synced(): Map<String, Any?> = prefs.all().filter { (k, v) -> SyncedSettings.synced(k) && SyncedSettings.encode(v) != null &&
        // v0.45.5 hotfix: never send a boolean setting that holds something else (the old "followups" list did).
        !(k in com.blaze.assistant.agent.PrefCoerce.BOOL_KEYS && v is String && com.blaze.assistant.agent.PrefCoerce.parseBool(v) == null) }

    override fun stamps(): Map<String, String> = synced().mapValues { (_, v) -> SyncedSettings.stamp(v) }

    override fun read(id: String): SyncMerge.Item? {
        if (!SyncedSettings.synced(id)) return null
        val v = SyncedSettings.encode(synced()[id] ?: return null) ?: return null
        // No change time is kept per pref: 0 makes the engine use "now", when it noticed the change.
        return SyncMerge.Item(kind, id, 0, v)
    }

    override fun apply(item: SyncMerge.Item) {
        if (!SyncedSettings.synced(item.id)) return
        // v0.45.5 hotfix: written with the type this phone uses ("true" from a peer becomes a Boolean; a non-boolean string
        // for a boolean key is refused), so a synced setting can never break a turn.
        val v = if (item.deleted) null else com.blaze.assistant.agent.PrefCoerce.forSync(item.id, prefs.all()[item.id], SyncedSettings.decode(item.data!!))
        if (v === com.blaze.assistant.agent.PrefCoerce.SKIP) return
        prefs.put(item.id, v)
        onApplied()
    }
}
