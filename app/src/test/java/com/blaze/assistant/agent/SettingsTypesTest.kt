package com.blaze.assistant.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v0.45.5 hotfix, end to end on real SharedPreferences: a phone upgraded from 0.45.2/0.45.3/0.45.4 with the Follow-ups
 * tracker's list under "followups" (and other mistyped values a sync peer could write) starts a plain turn
 * ("hey how's it going") without "String cannot be cast to Boolean", and keeps its data.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class SettingsTypesTest {
    private val list = """[{"id":"Fabc","pkg":"com.whatsapp","app":"WhatsApp","conv":"Cyp home","person":"Stefano","topic":"Thursday 9:00","whenText":"Thursday 9:00","whenAt":0,"needsReply":true,"status":"ASKED","created":1}]"""

    private fun seed(f: (android.content.SharedPreferences.Editor) -> Unit): Context {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val p = ctx.getSharedPreferences("blaze", Context.MODE_PRIVATE)
        p.edit().clear().also(f).commit()
        return ctx
    }

    @Test fun anUpgradedPhoneWithTheTrackersListStartsAPlainTurn() {
        val ctx = seed { e ->
            e.putString("followups", list)          // 0.38.0–0.45.4: the tracker's list on the switch's key
            e.putString("stream", "true")           // a peer that synced a string
            e.putString("show_route", "0")
            e.putBoolean("user_name", true)          // and the reverse: a boolean where a string is read
            e.putString("persona", "blaze"); e.putString("last_chat", "c1"); e.putBoolean("fast_lane", false)
        }
        Settings.init(ctx)
        // The exact read that crashed every turn, and the chat lane's request for the user's message.
        assertTrue("default switch back", Settings.followups)
        val req = ChatLane.messages(JSONArray(), "hey how's it going", "You are Blaze.", null)
        assertTrue(req.getJSONObject(0).getString("content").contains("follow-ups"))
        assertTrue(Settings.streamReplies); assertFalse(Settings.showRoute); assertFalse(Settings.fastLane)
        assertEquals("true", Settings.userName)
        // Data kept: the list moved to its own key; other prefs untouched.
        assertEquals(list, Settings.str(PrefCoerce.FOLLOWUP_ITEMS))
        assertEquals("blaze", Settings.personaId); assertEquals("c1", Settings.str("last_chat"))
        val p = ctx.getSharedPreferences("blaze", Context.MODE_PRIVATE)
        assertTrue("stored as a real boolean now", p.all["stream"] is Boolean)
        assertTrue(p.all["show_route"] is Boolean)
    }

    @Test fun aStringWrittenAfterStartupNeverCrashesARead() {
        val ctx = seed { e -> e.putBoolean(PrefCoerce.MIGRATED, true) }
        Settings.init(ctx)
        ctx.getSharedPreferences("blaze", Context.MODE_PRIVATE).edit().putString("followups", list).putString("low_data", "1").commit()
        assertTrue(Settings.followups)
        assertTrue(Settings.lowData)
        assertTrue(Settings.bool("followups", true))
        ChatLane.messages(JSONArray(), "hey how's it going", "You are Blaze.", null)
    }

    @Test fun aCleanInstallKeepsDefaults() {
        val ctx = seed { }
        Settings.init(ctx)
        assertTrue(Settings.followups); assertTrue(Settings.streamReplies)
        assertTrue(ctx.getSharedPreferences("blaze", Context.MODE_PRIVATE).getBoolean(PrefCoerce.MIGRATED, false))
    }
}
