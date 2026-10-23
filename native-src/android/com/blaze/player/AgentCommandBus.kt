// Blaze Player (Apache-2.0). Added by Blazeinchue.
package com.blaze.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * Hands UI commands (open/play/cast) to the JS bridge. A command is parked here and JS takes it
 * atomically (consumePendingCommand), so it runs exactly once whether JS is up or still booting.
 */
object AgentCommandBus {
    private const val TAG = "BlazeAgent"
    /** Name of the permission-protected activity-alias that targets MainActivity. */
    const val ALIAS_SIMPLE_NAME = "AgentEntry"

    @Volatile private var pending: String? = null
    @Volatile var signal: (() -> Unit)? = null

    fun post(json: String) {
        pending = json
        try { signal?.invoke() } catch (e: Exception) { Log.w(TAG, "signal failed", e) }
    }

    fun consume(): String? = synchronized(this) { val p = pending; pending = null; p }

    fun commandJson(action: String, sourceId: String?, title: String?, screen: String?): String {
        val o = JSONObject().put("action", action)
        if (sourceId != null) o.put("sourceId", sourceId)
        if (title != null) o.put("title", title.take(300))
        if (screen != null) o.put("screen", screen.take(20))
        return o.toString()
    }

    /** Takes a persistable read grant when the caller offered one (SAF documents). */
    fun keepGrant(ctx: Context, intent: Intent, uri: Uri) {
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { Log.i(TAG, "no persistable grant for $uri") }
        }
    }

    /**
     * Called from MainActivity.onCreate/onNewIntent. Acts only when the intent arrived through the
     * AgentEntry alias, which is protected by com.blaze.player.permission.AGENT; MainActivity itself
     * is exported for the launcher, so a direct intent to it is ignored.
     */
    fun fromIntent(activity: Activity, intent: Intent?) {
        if (intent == null) return
        val cls = intent.component?.className ?: return
        if (cls != "${activity.packageName}.$ALIAS_SIMPLE_NAME") return
        val action = when (intent.action) {
            AgentContract.ACTION_OPEN -> "open"
            AgentContract.ACTION_PLAY -> "play"
            AgentContract.ACTION_CAST -> "cast"
            else -> return
        }
        val uri = intent.data?.toString() ?: intent.getStringExtra(AgentContract.EXTRA_URI)
        val sourceId = intent.getStringExtra(AgentContract.EXTRA_SOURCE_ID)
        val title = intent.getStringExtra(AgentContract.EXTRA_TITLE)
        val screen = intent.getStringExtra(AgentContract.EXTRA_SCREEN)
        try {
            val normalized = if (action == "open" && uri.isNullOrBlank() && sourceId.isNullOrBlank()) null
                else AgentValidator.normalize(uri, sourceId)
            if (normalized != null && normalized.startsWith("local:")) {
                keepGrant(activity, intent, Uri.parse(normalized.removePrefix("local:")))
            }
            post(commandJson(action, normalized, title, screen))
        } catch (e: AgentValidator.Rejected) {
            Log.w(TAG, "rejected ${intent.action}: ${e.code}")
        }
        // Consume so a config change / recreation does not replay it.
        intent.action = Intent.ACTION_MAIN
    }
}
