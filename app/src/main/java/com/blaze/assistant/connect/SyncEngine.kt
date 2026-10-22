package com.blaze.assistant.connect

import org.json.JSONObject

/**
 * v0.40.0 cloud sync, one round (pure: the gateway, the local stores and the saved state are interfaces, so
 * SyncEngineTest runs it against a fake server). [CloudSync] owns the Android side and calls [run].
 *
 * A round: read the account's sync meta → pull every page after the saved cursor and apply what changed elsewhere
 * (merging where this phone changed the same item) → push what changed here since the last round, rebasing on
 * conflicts → save the state. Changes are found by comparing each item's cheap local [Adapter.stamp] with the stamp
 * saved after the last good sync, so no store needs a change log; an item the state knows but the store no longer
 * has was deleted here and is pushed as a tombstone.
 */
class SyncEngine(
    private val api: Api,
    private val adapters: List<Adapter>,
    private val key: ByteArray,
    private val state: State,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // ---------------- the outside world ----------------

    data class Meta(val setUp: Boolean, val salt: String = "", val kdf: String = "", val check: String = "", val epoch: String = "", val bytes: Long = 0)
    data class Remote(val kind: String, val id: String, val seq: Long, val rev: Long, val deleted: Boolean, val blob: String?, val updatedAt: Long)
    data class Page(val items: List<Remote>, val next: Long, val more: Boolean, val epoch: String)
    data class Out(val kind: String, val id: String, val baseRev: Long, val blob: String?)
    data class Accepted(val kind: String, val id: String, val rev: Long)
    data class Rejected(val kind: String, val id: String, val error: String)
    data class PushResult(val accepted: List<Accepted>, val conflicts: List<Remote>, val rejected: List<Rejected>)

    /** The gateway (the /v1/sync routes). Errors are exceptions; [Busy] = another phone was writing (409 busy). */
    interface Api {
        fun meta(): Meta
        fun pull(since: Long, limit: Int): Page
        fun push(items: List<Out>): PushResult
    }
    class Busy : Exception("busy")

    /** One kind of thing on this phone (chats, memory, settings, designs). */
    interface Adapter {
        val kind: String
        /** Every item here: id → a cheap fingerprint that changes when the item does. */
        fun stamps(): Map<String, String>
        /** One item's fingerprint (null when it isn't here). */
        fun stamp(id: String): String? = stamps()[id]
        /** The item as it is now (null when gone). */
        fun read(id: String): SyncMerge.Item?
        /** Store a copy from another phone; for a tombstone, delete it here. */
        fun apply(item: SyncMerge.Item)
        /** Shrinks an item that doesn't fit the gateway's per-item limit, or null to skip it. Default: skip. */
        fun shrink(item: SyncMerge.Item, fits: (SyncMerge.Item) -> Boolean): SyncMerge.Item? = null
        /**
         * v0.45.0 (#37): false = an item that is no longer here is NOT pushed as a delete (chat messages: a message that
         * left this phone's file, e.g. by a trim or a save racing a sync, must never delete it on the other phones).
         */
        val pushDeletes: Boolean get() = true
        /** v0.45.0 (#37): applies are buffered; this writes them (after the pull, after conflicts, at the end). */
        fun flush() {}
    }

    /** What the last good round left behind, per item: the server rev and the local stamp then. */
    interface State {
        var epoch: String
        var cursor: Long
        fun get(kind: String, id: String): Entry?
        fun put(kind: String, id: String, e: Entry?)
        fun ids(kind: String): Set<String>
        fun clearItems()
        fun save()
    }
    data class Entry(val rev: Long, val stamp: String, val t: Long = 0, val deleted: Boolean = false, val skipped: Boolean = false)

    // ---------------- results ----------------

    data class Report(
        val pulled: Int = 0, val pushed: Int = 0, val merged: Int = 0, val skipped: List<String> = emptyList(),
        val full: Boolean = false, val failedOpen: Int = 0,
    )

    class NotSetUp : Exception("not set up")
    class WrongKey : Exception("wrong key")

    companion object {
        /** Gateway limits (src/sync.ts): base64 characters per item, items per push; pushes stay under ~3 MB. */
        const val ITEM_LIMIT = 512 * 1024
        const val PUSH_ITEMS = 200
        const val PUSH_BYTES = 3 * 1024 * 1024
        const val PULL_LIMIT = 200
        private const val ROUNDS = 3
    }

    private val byKind = adapters.associateBy { it.kind }
    private var pulled = 0; private var pushed = 0; private var merged = 0; private var failedOpen = 0; private var full = false
    private val skipped = ArrayList<String>()

    private fun seal(i: SyncMerge.Item) = SyncCrypto.seal(key, i.kind, i.id, i.envelope())

    private fun openRemote(r: Remote): SyncMerge.Item? {
        if (r.deleted || r.blob == null) return SyncMerge.Item(r.kind, r.id, r.updatedAt, null)
        return try { SyncMerge.Item.parse(r.kind, r.id, SyncCrypto.open(key, r.kind, r.id, r.blob)) } catch (_: Exception) { failedOpen++; null }
    }

    /** One full round. Throws [NotSetUp] (the cloud copy is gone), [WrongKey] (it was reset with another passphrase). */
    fun run(): Report {
        val meta = api.meta()
        if (!meta.setUp) throw NotSetUp()
        if (meta.epoch != state.epoch) {
            // A new cloud copy (first round, or another phone reset it): check the key, then start over against it.
            if (meta.check.isNotEmpty() && !SyncCrypto.checks(key, meta.check)) throw WrongKey()
            state.clearItems(); state.cursor = 0; state.epoch = meta.epoch
        }
        pullAll()
        flushAll()
        try { pushAll() } finally { flushAll() }
        state.save()
        return Report(pulled, pushed, merged, skipped.toList(), full, failedOpen)
    }

    private fun flushAll() { for (a in adapters) a.flush() }

    // ---------------- pull ----------------

    private fun pullAll() {
        var since = state.cursor
        var guard = 0
        while (guard++ < 10_000) {
            val page = api.pull(since, PULL_LIMIT)
            for (r in page.items) take(r)
            since = page.next
            state.cursor = since
            if (!page.more || page.items.isEmpty()) break
        }
    }

    /** A copy from the server (pulled, or the server's side of a push conflict). */
    private fun take(r: Remote) {
        val a = byKind[r.kind] ?: return            // a kind this phone doesn't sync (switched off, or a newer Blaze's)
        val st = state.get(r.kind, r.id)
        if (st != null && st.rev >= r.rev && !st.skipped) return   // already have it (our own push coming back)
        val remote = openRemote(r) ?: return
        val stampNow = a.stamp(r.id)
        val changedHere = stampNow != (st?.stamp) && !(stampNow == null && (st == null || st.deleted))
        if (!changedHere) {
            if (!(remote.deleted && stampNow == null)) a.apply(remote)
            pulled++
            state.put(r.kind, r.id, Entry(r.rev, a.stamp(r.id) ?: "", remote.t, remote.deleted))
            return
        }
        // Changed on both sides since the last sync.
        val local = a.read(r.id) ?: SyncMerge.Item(r.kind, r.id, st?.t?.takeIf { it > 0 } ?: now(), null)
        when (val o = SyncMerge.resolve(local, remote)) {
            SyncMerge.Outcome.TakeRemote -> {
                a.apply(remote); pulled++
                state.put(r.kind, r.id, Entry(r.rev, a.stamp(r.id) ?: "", remote.t, remote.deleted))
            }
            SyncMerge.Outcome.KeepLocal -> {
                // Push ours on top of the server's rev: the old stamp stays, so it still counts as changed.
                state.put(r.kind, r.id, Entry(r.rev, st?.stamp ?: "", st?.t ?: 0, st?.deleted ?: false))
            }
            is SyncMerge.Outcome.Merged -> {
                a.apply(o.item); merged++
                // Applied here, still to be pushed: a stamp that can't match makes the push pick it up.
                state.put(r.kind, r.id, Entry(r.rev, "merged", o.item.t))
            }
        }
    }

    // ---------------- push ----------------

    private fun fits(i: SyncMerge.Item) = seal(i).length <= ITEM_LIMIT

    private fun pushAll() {
        repeat(ROUNDS) {
            val out = ArrayList<Pair<Out, String>>()   // (item, the local stamp it was read at)
            for (a in adapters) {
                val stamps = a.stamps()
                for ((id, stamp) in stamps) {
                    val st = state.get(a.kind, id)
                    if (st != null && st.stamp == stamp && !st.deleted) continue
                    if (st != null && st.skipped && st.stamp == stamp) continue
                    var item = a.read(id) ?: continue
                    if (item.t <= 0) item = item.copy(t = now())
                    var blob = seal(item)
                    if (blob.length > ITEM_LIMIT) {
                        val small = a.shrink(item, ::fits)
                        if (small == null) {
                            skipped += "${a.kind}/$id"
                            state.put(a.kind, id, Entry(st?.rev ?: 0, stamp, item.t, skipped = true))
                            continue
                        }
                        blob = seal(small)
                    }
                    out += Out(a.kind, id, st?.rev ?: 0, blob) to stamp
                }
                // Gone here since the last sync: a tombstone (once).
                if (a.pushDeletes) for (id in state.ids(a.kind)) {
                    if (id in stamps) continue
                    val st = state.get(a.kind, id) ?: continue
                    if (st.deleted) continue
                    out += Out(a.kind, id, st.rev, null) to ""
                }
            }
            if (out.isEmpty()) return
            var conflicts = 0
            for (batch in batches(out)) {
                val res = pushWithRetry(batch.map { it.first })
                val stampOf = batch.associate { (it.first.kind + "\u0000" + it.first.id) to it.second }
                for (ac in res.accepted) {
                    val stamp = stampOf[ac.kind + "\u0000" + ac.id] ?: ""
                    val wasDelete = batch.firstOrNull { it.first.kind == ac.kind && it.first.id == ac.id }?.first?.blob == null
                    pushed++
                    state.put(ac.kind, ac.id, Entry(ac.rev, stamp, now(), deleted = wasDelete))
                }
                for (r in res.rejected) {
                    if (r.error == "quota_exceeded") full = true
                    else skipped += "${r.kind}/${r.id}"
                    val st = state.get(r.kind, r.id)
                    // Don't send it again every round; it goes again when it changes (or after Sync now clears this).
                    state.put(r.kind, r.id, Entry(st?.rev ?: 0, stampOf[r.kind + "\u0000" + r.id] ?: "", st?.t ?: 0, skipped = true))
                }
                for (c in res.conflicts) { conflicts++; take(c) }
            }
            if (conflicts == 0) return
        }
    }

    private fun batches(out: List<Pair<Out, String>>): List<List<Pair<Out, String>>> {
        val res = ArrayList<List<Pair<Out, String>>>()
        var cur = ArrayList<Pair<Out, String>>(); var bytes = 0
        for (o in out) {
            val n = o.first.blob?.length ?: 0
            if (cur.isNotEmpty() && (cur.size >= PUSH_ITEMS || bytes + n > PUSH_BYTES)) { res += cur; cur = ArrayList(); bytes = 0 }
            cur += o; bytes += n
        }
        if (cur.isNotEmpty()) res += cur
        return res
    }

    private fun pushWithRetry(items: List<Out>): PushResult {
        var last: Busy? = null
        repeat(3) { i ->
            try { return api.push(items) } catch (e: Busy) { last = e; Thread.sleep(300L * (i + 1)) }
        }
        throw last ?: Busy()
    }
}

/** [SyncEngine.State] as one JSON document (CloudSync keeps it in a file; tests keep it in memory). */
class JsonSyncState(private val load: () -> String?, private val store: (String) -> Unit) : SyncEngine.State {
    private val items = HashMap<String, SyncEngine.Entry>()
    override var epoch: String = ""
    override var cursor: Long = 0

    init {
        try {
            load()?.takeIf { it.isNotBlank() }?.let { s ->
                val o = JSONObject(s)
                epoch = o.optString("epoch"); cursor = o.optLong("cursor")
                val it = o.optJSONObject("items") ?: JSONObject()
                for (k in it.keys()) {
                    val e = it.getJSONObject(k)
                    items[k] = SyncEngine.Entry(e.optLong("r"), e.optString("s"), e.optLong("t"), e.optBoolean("d"), e.optBoolean("x"))
                }
            }
        } catch (_: Exception) { items.clear(); epoch = ""; cursor = 0 }
    }

    private fun k(kind: String, id: String) = "$kind\u0000$id"
    override fun get(kind: String, id: String) = items[k(kind, id)]
    override fun put(kind: String, id: String, e: SyncEngine.Entry?) { if (e == null) items.remove(k(kind, id)) else items[k(kind, id)] = e }
    override fun ids(kind: String): Set<String> = items.keys.filter { it.startsWith("$kind\u0000") }.map { it.substringAfter('\u0000') }.toSet()
    override fun clearItems() = items.clear()
    /** Forget one kind (switched off, so switching it on again starts clean). */
    fun forgetKind(kind: String) { items.keys.removeAll { it.startsWith("$kind\u0000") } }
    /** Items skipped for size or quota get another try (Sync now). */
    fun retrySkipped() { for ((key, e) in items.entries.toList()) if (e.skipped) items[key] = e.copy(stamp = "", skipped = false) }

    override fun save() {
        val it = JSONObject()
        for ((key, e) in items) it.put(key, JSONObject().put("r", e.rev).put("s", e.stamp).put("t", e.t).apply { if (e.deleted) put("d", true); if (e.skipped) put("x", true) })
        store(JSONObject().put("epoch", epoch).put("cursor", cursor).put("items", it).toString())
    }
}
