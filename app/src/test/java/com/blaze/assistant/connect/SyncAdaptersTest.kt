package com.blaze.assistant.connect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncAdaptersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun settingsNeverIncludeSecretsOrPerPhoneThings() {
        val prefs = mapOf<String, Any?>(
            "ui_mode" to "advanced", "look_bg" to "aurora", "text_scale" to 1.2f, "speak_replies" to true,
            "conn_tok_blaze_session" to "enc:abc", "conn_tok_cloudsync_key" to "enc:k", "key_gemini" to "enc:x", "mcp_config" to "{}",
            "look_photo" to "/sdcard/me.jpg", "orb_x" to 10, "app_lock" to true, "last_chat" to "main", "cloudsync_on" to true,
        )
        val store = object : PrefStore { val m = prefs.toMutableMap(); override fun all() = m; override fun put(key: String, v: Any?) { if (v == null) m.remove(key) else m[key] = v } }
        val a = SettingsAdapter(store)
        assertEquals(setOf("ui_mode", "look_bg", "text_scale", "speak_replies"), a.stamps().keys)
        assertNull(a.read("conn_tok_blaze_session"))
        // Coming back keeps the type.
        a.apply(SyncMerge.Item("settings", "text_scale", 5, SyncedSettings.encode(1.5f)))
        assertEquals(1.5f, store.m["text_scale"])
        // A forbidden key from a broken or hostile copy is ignored.
        a.apply(SyncMerge.Item("settings", "conn_tok_blaze_session", 5, SyncedSettings.encode("stolen")))
        assertEquals("enc:abc", store.m["conn_tok_blaze_session"])
        assertTrue(SyncedSettings.KEYS.none { SyncedSettings.forbidden(it) })
    }

    @Test fun chatApplyMergesAndNeverDropsMessages() {
        val dir = tmp.newFolder("chats")
        val a = ChatAdapter({ dir })
        fun m(id: Long, t: String, at: Long) = JSONObject().put("id", id).put("role", "USER").put("text", t).put("at", at)
        File(dir, "main.json").writeText(JSONObject().put("id", "main").put("updated", 10).put("messages", JSONArray().put(m(1, "old here", 1)).put(m(2, "only here", 2))).toString())
        // A trimmed copy from the other phone with just its newest message.
        a.apply(SyncMerge.Item("chat", "main", 20, JSONObject().put("id", "main").put("updated", 20).put("trimmed", true).put("messages", JSONArray().put(m(2, "from there", 3)))))
        val o = JSONObject(File(dir, "main.json").readText())
        val arr = o.getJSONArray("messages")
        assertEquals(listOf("old here", "only here", "from there"), (0 until arr.length()).map { arr.getJSONObject(it).getString("text") })
        assertFalse(o.has("trimmed"))
        assertTrue(a.stamps().containsKey("main"))
        a.apply(SyncMerge.Item("chat", "main", 30, null))
        assertFalse(File(dir, "main.json").exists())
    }

    @Test fun privateAndOddChatFilesAreLeftAlone() {
        val dir = tmp.newFolder("chats2")
        File(dir, "private-1.json").writeText("{}"); File(dir, "bad id!.json").writeText("{}"); File(dir, "x.json.tmp").writeText("{}")
        File(dir, "abc123.json").writeText("""{"id":"abc123","updated":1}""")
        assertEquals(setOf("abc123"), ChatAdapter({ dir }).stamps().keys)
    }

    @Test fun designsRoundTripWithoutTheirHistory() {
        val root = tmp.newFolder("designs"); val other = tmp.newFolder("designs2")
        File(root, "poster").mkdirs(); File(root, "poster/index.html").writeText("<h1>Poster</h1>"); File(root, "poster/.v1.html").writeText("old")
        File(root, "current.json").writeText("{}")
        val a = DesignAdapter({ root }); val b = DesignAdapter({ other })
        assertEquals(setOf("d.poster"), a.stamps().keys)
        val item = a.read("d.poster")!!
        b.apply(item)
        assertEquals("<h1>Poster</h1>", File(other, "poster/index.html").readText())
        assertFalse(File(other, "poster/.v1.html").exists())
        // Ids can't escape the designs folder.
        assertNull(a.read("d.../etc")); b.apply(SyncMerge.Item("design", "d..", 1, JSONObject().put("html", "x")))
        assertFalse(File(other.parentFile, "index.html").exists())
        b.apply(SyncMerge.Item("design", "d.poster", 2, null))
        assertFalse(File(other, "poster").exists())
    }

    @Test fun memoryIdsFollowTheWords() {
        assertEquals(MemoryAdapter.idOf("Sister is Mary, lives in Ndola."), MemoryAdapter.idOf("sister is mary lives in ndola"))
        assertTrue(SyncKinds.ID_RE.matches(MemoryAdapter.idOf("x")))
        val lines = ArrayList<MemoryStore.Line>()
        val store = object : MemoryStore {
            override fun all() = lines.toList()
            override fun add(l: MemoryStore.Line) { lines += l }
            override fun remove(l: MemoryStore.Line) { lines.removeAll { it.text == l.text } }
        }
        val a = MemoryAdapter(store)
        val id = MemoryAdapter.idOf("Likes tea")
        a.apply(SyncMerge.Item("memory", id, 5, JSONObject().put("kind", "preference").put("subject", "You").put("text", "Likes tea")))
        assertEquals(1, lines.size); assertEquals(setOf(id), a.stamps().keys)
        // A blob whose words don't match its id is refused.
        a.apply(SyncMerge.Item("memory", id, 6, JSONObject().put("text", "Something else")))
        assertEquals("Likes tea", lines.single().text)
        a.apply(SyncMerge.Item("memory", id, 7, null))
        assertTrue(lines.isEmpty())
    }
}
