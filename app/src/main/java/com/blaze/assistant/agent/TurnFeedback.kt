package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.45.0 reactions as feedback (R4), cheap: no model call.
 *
 * When a turn ends, [record] keeps what it taught Blaze, keyed by the global id of every bubble of its reply: the
 * request, whether its phone plan was saved (TapLoop.Plans) and which tool groups the learned router counted.
 * A 👍 / 👎 on any of those bubbles then:
 *  - 👍  reinforces the saved plan (one more good use);
 *  - 👎  removes the saved plan for that request (kept in the record so taking the 👎 back restores it), takes the
 *        router's count for that turn back, and excludes the turn from the idle memory review ([excluded]);
 *  - taking a reaction back undoes exactly what it did. Applying the same state twice does nothing.
 * Private chats never get here (Agent.react checks). Kept in a small file (newest [MAX] turns).
 */
object TurnFeedback {
    const val MAX = 200

    data class Rec(
        val gids: List<String>, val conv: String, val request: String, val plan: Boolean, val groups: Set<String>?,
        val fromSec: Long, val toSec: Long,
        /** The user's current 👍 / 👎 on this turn ("" = none). */
        val state: String = "",
        /** The plan taken out by a 👎 (to restore). */
        val removed: TapLoop.Recipe? = null,
    )

    /** What applying a reaction does (pure: [decide]); [apply] runs it against the real stores via [Ports]. */
    sealed class Effect {
        object Reinforce : Effect(); object Unreinforce : Effect()
        object DropPlan : Effect(); object RestorePlan : Effect()
        object Unlearn : Effect(); object Relearn : Effect()
    }

    fun decide(prev: String, now: String, rec: Rec): List<Effect> {
        if (prev == now) return emptyList()
        val out = ArrayList<Effect>()
        // Undo the old state first.
        when (prev) {
            Reaction.UP -> if (rec.plan) out += Effect.Unreinforce
            Reaction.DOWN -> { if (rec.plan) out += Effect.RestorePlan; if (rec.groups != null) out += Effect.Relearn }
        }
        when (now) {
            Reaction.UP -> if (rec.plan) out += Effect.Reinforce
            Reaction.DOWN -> { if (rec.plan) out += Effect.DropPlan; if (rec.groups != null) out += Effect.Unlearn }
        }
        return out
    }

    interface Ports {
        fun take(request: String): TapLoop.Recipe?
        fun restore(r: TapLoop.Recipe)
        fun reinforce(request: String, by: Int)
        fun unlearn(request: String, groups: Set<String>)
        fun learn(request: String, groups: Set<String>)
    }

    object RealPorts : Ports {
        override fun take(request: String) = TapLoop.Plans.take(request)
        override fun restore(r: TapLoop.Recipe) = TapLoop.Plans.restore(r)
        override fun reinforce(request: String, by: Int) { TapLoop.Plans.reinforce(request, by) }
        override fun unlearn(request: String, groups: Set<String>) = LearnedRoutes.unlearn(request, groups)
        override fun learn(request: String, groups: Set<String>) = LearnedRoutes.learn(request, groups)
    }

    @Volatile var ports: Ports = RealPorts
    private var file: File? = null
    private val recs = LinkedHashMap<String, Rec>()   // first gid → rec
    private val byGid = HashMap<String, String>()

    @Synchronized fun init(f: File?) {
        file = f; recs.clear(); byGid.clear()
        try {
            val a = JSONArray(f?.takeIf { it.isFile }?.readText() ?: return)
            for (i in 0 until a.length()) fromJson(a.optJSONObject(i))?.let { put(it) }
        } catch (_: Exception) { recs.clear(); byGid.clear() }
    }

    private fun put(r: Rec) {
        val k = r.gids.firstOrNull() ?: return
        recs.remove(k); recs[k] = r
        r.gids.forEach { byGid[it] = k }
        while (recs.size > MAX) { val old = recs.keys.first(); recs.remove(old)?.gids?.forEach { byGid.remove(it) } }
    }

    /** A turn ended: what it taught (nothing to record when it saved no plan and taught the router nothing). */
    @Synchronized fun record(gids: List<String>, conv: String, request: String, plan: Boolean, groups: Set<String>?, fromSec: Long, toSec: Long) {
        if (gids.isEmpty() || PrivateChat.isPrivateId(conv)) return
        put(Rec(gids, conv, request, plan, groups, fromSec, toSec))
        save()
    }

    @Synchronized fun rec(gid: String): Rec? = byGid[gid]?.let { recs[it] }

    /** The user's 👍 / 👎 on a bubble is now [now] (null = neither). Returns the effects applied. */
    @Synchronized fun react(gid: String, now: String?): List<Effect> {
        val k = byGid[gid] ?: return emptyList()
        val r = recs[k] ?: return emptyList()
        val target = now ?: ""
        val fx = decide(r.state, target, r)
        var removed = r.removed
        for (e in fx) when (e) {
            Effect.Reinforce -> ports.reinforce(r.request, 1)
            Effect.Unreinforce -> ports.reinforce(r.request, -1)
            Effect.DropPlan -> ports.take(r.request)?.let { removed = it }
            Effect.RestorePlan -> { removed?.let { ports.restore(it) }; removed = null }
            Effect.Unlearn -> ports.unlearn(r.request, r.groups.orEmpty())
            Effect.Relearn -> ports.learn(r.request, r.groups.orEmpty())
        }
        recs[k] = r.copy(state = target, removed = removed)
        save()
        return fx
    }

    /** A journal row ([atSec]) from a turn the user gave 👎: the idle memory review skips it. */
    @Synchronized fun excluded(conv: String, atSec: Long): Boolean =
        recs.values.any { it.state == Reaction.DOWN && it.conv == conv && atSec >= it.fromSec - 2 && atSec <= it.toSec + 60 }

    @Synchronized fun clear() { recs.clear(); byGid.clear(); save() }

    private fun save() {
        val f = file ?: return
        val a = JSONArray(); recs.values.forEach { a.put(toJson(it)) }
        try { val t = File(f.parentFile, f.name + ".tmp"); t.writeText(a.toString()); if (!t.renameTo(f)) f.writeText(a.toString()) } catch (_: Exception) { }
    }

    private fun toJson(r: Rec) = JSONObject().put("g", JSONArray(r.gids)).put("c", r.conv).put("q", r.request).put("p", r.plan)
        .apply { r.groups?.let { put("r", JSONArray(it.toList())) } }.put("f", r.fromSec).put("t", r.toSec).put("s", r.state)
        .apply { r.removed?.let { put("x", JSONObject().put("k", it.key).put("steps", it.steps).put("u", it.uses).put("at", it.at)) } }

    private fun fromJson(o: JSONObject?): Rec? {
        o ?: return null
        val g = o.optJSONArray("g") ?: return null
        val gids = (0 until g.length()).map { g.optString(it) }.filter { it.isNotEmpty() }
        if (gids.isEmpty()) return null
        val groups = o.optJSONArray("r")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
        val removed = o.optJSONObject("x")?.let { x -> TapLoop.Recipe(x.optString("k"), x.optJSONArray("steps") ?: JSONArray(), x.optInt("u"), x.optLong("at")) }
        return Rec(gids, o.optString("c"), o.optString("q"), o.optBoolean("p"), groups, o.optLong("f"), o.optLong("t"), o.optString("s"), removed)
    }
}
