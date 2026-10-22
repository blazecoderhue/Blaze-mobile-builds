package com.blaze.assistant

import android.content.Context
import android.content.Intent

/**
 * v0.45.0: MainActivity is exported (launcher, assistant, share sheet), so any installed app can start it with any
 * extras. The internal entry points (listen now, start a Blaze call, open a chat, open the secure form) are honoured
 * only when the intent carries this app's private key, which only Blaze's own notifications, tile, shortcut, overlay
 * and wake word add. The key lives in Blaze's private prefs (other apps can't read it) and survives restarts, so
 * notifications posted before a restart still work.
 */
object IntentKey {
    const val EXTRA = "com.blaze.assistant.k"
    private const val PREFS = "blaze_intent"
    @Volatile private var cached: String? = null

    fun key(ctx: Context): String = cached ?: synchronized(this) {
        cached ?: run {
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString("k", null)?.takeIf { it.length >= 32 } ?: com.blaze.assistant.dashboard.DashHttp.newToken().also { p.edit().putString("k", it).apply() }
        }.also { cached = it }
    }

    /** Marks [i] as coming from Blaze itself. */
    fun sign(ctx: Context, i: Intent): Intent = i.putExtra(EXTRA, key(ctx))

    fun trusted(ctx: Context, i: Intent): Boolean = Policy.keyOk(i.getStringExtra(EXTRA), key(ctx))

    /** Pure parts (unit-tested in IntentKeyTest). */
    object Policy {
        fun keyOk(given: String?, expected: String): Boolean = com.blaze.assistant.dashboard.DashHttp.tokenOk(given, expected)

        /** What MainActivity may act on. ASSIST / VOICE_COMMAND / SEND / MAIN are public by design. */
        data class Allowed(val listen: Boolean, val call: Boolean, val openChat: Boolean, val openForm: Boolean)

        fun allowed(action: String?, listenExtra: Boolean, openChat: Boolean, openForm: Boolean, trusted: Boolean, callAction: String): Allowed {
            val systemListen = action == Intent.ACTION_ASSIST || action == Intent.ACTION_VOICE_COMMAND
            return Allowed(
                listen = systemListen || (listenExtra && trusted),
                call = action == callAction && trusted,
                openChat = openChat && trusted,
                openForm = openForm && trusted,
            )
        }
    }
}
