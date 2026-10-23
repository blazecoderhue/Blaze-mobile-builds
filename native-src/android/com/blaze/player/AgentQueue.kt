// Blaze Player (Apache-2.0). Added by Blazeinchue.
package com.blaze.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The play queue. Source of truth for both the UI (via BlazeAgent module) and the agent API. */
object AgentQueue {
    private const val PREFS = "blaze_player_agent"
    private const val KEY = "queue"
    const val MAX = 200
    private val lock = Any()

    fun list(ctx: Context): JSONArray = synchronized(lock) {
        try { JSONArray(prefs(ctx).getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
    }

    fun set(ctx: Context, json: String) = synchronized(lock) {
        val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        val trimmed = JSONArray()
        for (i in 0 until minOf(arr.length(), MAX)) trimmed.put(arr.get(i))
        prefs(ctx).edit().putString(KEY, trimmed.toString()).apply()
    }

    fun add(ctx: Context, item: JSONObject): Int = synchronized(lock) {
        val arr = list(ctx)
        if (arr.length() < MAX) arr.put(item)
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        arr.length()
    }

    fun clear(ctx: Context) = synchronized(lock) {
        prefs(ctx).edit().putString(KEY, "[]").apply()
    }

    /** Builds a queue entry in the same shape as the JS MediaItem. */
    fun itemFor(sourceId: String, title: String?): JSONObject {
        val o = JSONObject()
        o.put("id", sourceId)
        if (sourceId.startsWith("archive:")) {
            val ident = sourceId.removePrefix("archive:")
            o.put("source", "archive")
            o.put("title", title ?: ident)
            o.put("poster", "https://archive.org/services/img/$ident")
        } else {
            val uri = sourceId.removePrefix("local:")
            o.put("source", "local")
            o.put("title", title ?: (android.net.Uri.parse(uri).lastPathSegment ?: "File"))
            o.put("uri", uri)
        }
        return o
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
