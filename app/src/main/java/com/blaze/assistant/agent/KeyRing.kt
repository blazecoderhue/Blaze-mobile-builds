package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Several keys per provider. The first is the normal key (key_<pid>); extras live encrypted in
 * keys_extra_<pid>. When one key runs out of quota on a model it rests (per key + model) and the
 * next key carries on with the same model. When every key is resting on a model, that model is
 * "used up" and the chat offers a quick switch to another provider/model.
 */
object KeyRing {
    private const val REST_MS = 60 * 60_000L        // a used-up key/model pair rests an hour, then is tried again

    fun extras(pid: String): List<String> = try {
        val a = JSONArray(Settings.secret("keys_extra_$pid").ifBlank { "[]" })
        (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    private fun saveExtras(pid: String, l: List<String>) {
        if (l.distinct() == extras(pid)) return
        setExtrasSynced(pid, l)
        com.blaze.assistant.connect.KeySync.localChange(pid)   // follows the Blaze account to the PC
    }

    /** Replaces the extras without a new change stamp (used by key sync). */
    fun setExtrasSynced(pid: String, l: List<String>) {
        val c = l.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        Settings.setSecret("keys_extra_$pid", if (c.isEmpty()) "" else JSONArray(c).toString())
    }

    fun all(pid: String): List<String> = (listOf(Settings.keyFor(pid)) + extras(pid)).filter { it.isNotBlank() }.distinct()

    /** Adds a key; becomes the main key if there is none yet. Returns false if it's already saved. */
    fun add(pid: String, key: String): Boolean {
        val k = key.trim(); if (k.isBlank() || k in all(pid)) return false
        if (Settings.keyFor(pid).isBlank()) Settings.setKeyFor(pid, k) else saveExtras(pid, extras(pid) + k)
        return true
    }

    fun removeExtra(pid: String, key: String) = saveExtras(pid, extras(pid) - key)

    // ---- resting ----
    private fun restMap(): JSONObject = try { JSONObject(Settings.str("key_rest", "{}")) } catch (_: Exception) { JSONObject() }
    private fun tag(pid: String, key: String, model: String) = pid + "|" + model + "|" + key.hashCode()

    fun rest(pid: String, key: String, model: String, ms: Long = REST_MS) {
        val m = restMap(); m.put(tag(pid, key, model), System.currentTimeMillis() + ms)
        // drop stale entries
        val now = System.currentTimeMillis()
        m.keys().asSequence().toList().forEach { if (m.optLong(it) < now) m.remove(it) }
        Settings.setStr("key_rest", m.toString())
    }

    fun resting(pid: String, key: String, model: String) = restMap().optLong(tag(pid, key, model)) > System.currentTimeMillis()

    /** The key to use now for this model: the first one that isn't resting (or the main key). */
    fun active(pid: String, model: String): String = all(pid).firstOrNull { !resting(pid, it, model) } ?: Settings.keyFor(pid)

    /** Keys still fresh for this model, other than [except]. */
    fun fresh(pid: String, model: String, except: String) = all(pid).filter { it != except && !resting(pid, it, model) }

    /** True when every key for this provider is resting on [model]. */
    fun usedUp(pid: String, model: String) = all(pid).isNotEmpty() && all(pid).all { resting(pid, it, model) }

    /** Clears resting marks (e.g. after the user adds a key or a new day starts). */
    fun clear(pid: String) {
        val m = restMap(); m.keys().asSequence().toList().forEach { if (it.startsWith("$pid|")) m.remove(it) }
        Settings.setStr("key_rest", m.toString())
    }

    fun isQuota(e: Exception): Boolean {
        val code = (e as? LlmClient.LlmException)?.code ?: 0
        val m = e.message.orEmpty().lowercase()
        return code == 429 || "quota" in m || "resource_exhausted" in m || "rate limit" in m || "credits" in m || code == 402
    }
}
