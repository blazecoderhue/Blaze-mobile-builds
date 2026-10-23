// Blaze Player (Apache-2.0). Added by Blazeinchue.
package com.blaze.player

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.Executors

/**
 * Bound Messenger API, exported and protected by com.blaze.player.permission.AGENT
 * (protectionLevel=signature): only apps signed with the Blaze key can bind.
 * Every request gets one reply on msg.replyTo with the same `what`, arg1 = OK/ERR and a Bundle
 * holding "json" or "errorCode"/"error".
 */
class AgentService : Service() {
    companion object {
        private const val TAG = "BlazeAgent"
        @Volatile var queueChanged: (() -> Unit)? = null

        fun parseSources(raw: String?): List<String> {
            val all = listOf("local", "archive")
            if (raw.isNullOrBlank()) return all
            return raw.split(',').map { it.trim().lowercase() }.filter { it in all }.ifEmpty { all }
        }
    }

    private val io = Executors.newSingleThreadExecutor()

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo
            val data = msg.data ?: Bundle()
            val what = msg.what
            fun reply(ok: Boolean, json: String? = null, code: String? = null, err: String? = null) {
                val r = Message.obtain(null, what, if (ok) AgentContract.OK else AgentContract.ERR, 0)
                r.data = Bundle().apply {
                    json?.let { putString(AgentContract.EXTRA_RESULT_JSON, it) }
                    code?.let { putString(AgentContract.EXTRA_ERROR_CODE, it) }
                    err?.let { putString(AgentContract.EXTRA_ERROR, it) }
                }
                try { replyTo?.send(r) } catch (e: RemoteException) { Log.w(TAG, "client gone") }
            }
            try {
                when (what) {
                    AgentContract.MSG_PING -> reply(true, "{\"api\":${AgentContract.API_VERSION},\"app\":\"com.blaze.player\"}")
                    AgentContract.MSG_OPEN, AgentContract.MSG_PLAY, AgentContract.MSG_CAST -> {
                        val action = when (what) { AgentContract.MSG_OPEN -> "open"; AgentContract.MSG_PLAY -> "play"; else -> "cast" }
                        val uri = data.getString(AgentContract.EXTRA_URI)
                        val sid = data.getString(AgentContract.EXTRA_SOURCE_ID)
                        val id = if (action == "open" && uri.isNullOrBlank() && sid.isNullOrBlank()) null
                            else AgentValidator.normalize(uri, sid)
                        if (id != null && id.startsWith("local:") && !hasPersistedGrant(Uri.parse(id.removePrefix("local:")))) {
                            throw AgentValidator.Rejected("E_NO_URI_GRANT",
                                "content:// over the bound API needs a persisted grant; use the AgentEntry intent with FLAG_GRANT_READ_URI_PERMISSION")
                        }
                        AgentCommandBus.post(AgentCommandBus.commandJson(action, id,
                            data.getString(AgentContract.EXTRA_TITLE), data.getString(AgentContract.EXTRA_SCREEN)))
                        bringToFront()
                        reply(true, "{\"accepted\":true}")
                    }
                    AgentContract.MSG_QUEUE_ADD -> {
                        val id = AgentValidator.normalize(data.getString(AgentContract.EXTRA_URI), data.getString(AgentContract.EXTRA_SOURCE_ID))
                        val n = AgentQueue.add(this@AgentService, AgentQueue.itemFor(id, data.getString(AgentContract.EXTRA_TITLE)))
                        queueChanged?.invoke()
                        reply(true, "{\"size\":$n}")
                    }
                    AgentContract.MSG_QUEUE_LIST -> reply(true, AgentQueue.list(this@AgentService).toString())
                    AgentContract.MSG_QUEUE_CLEAR -> {
                        AgentQueue.clear(this@AgentService)
                        queueChanged?.invoke()
                        reply(true, "{\"size\":0}")
                    }
                    AgentContract.MSG_SEARCH -> {
                        val q = data.getString(AgentContract.EXTRA_QUERY) ?: ""
                        val sources = parseSources(data.getString(AgentContract.EXTRA_SOURCES))
                        val limit = data.getInt(AgentContract.EXTRA_LIMIT, 20).coerceIn(1, 100)
                        io.execute {
                            try {
                                val res = AgentSearch.search(this@AgentService, q, sources, limit).toString()
                                post { reply(true, res) }
                            } catch (e: Exception) {
                                post { reply(false, code = "E_NETWORK", err = e.message ?: "search failed") }
                            }
                        }
                    }
                    else -> reply(false, code = "E_UNKNOWN_ACTION", err = "unknown what=$what")
                }
            } catch (e: AgentValidator.Rejected) {
                reply(false, code = e.code, err = e.message)
            } catch (e: Exception) {
                reply(false, code = "E_INTERNAL", err = e.message)
            }
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == AgentContract.ACTION_BIND) messenger.binder else null

    private fun hasPersistedGrant(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun bringToFront() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try { startActivity(launch) } catch (e: Exception) {
            // Background activity starts can be blocked; the command stays parked and runs
            // the next time Blaze Player comes to the foreground.
            Log.i(TAG, "could not bring Blaze Player to front: ${e.message}")
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
