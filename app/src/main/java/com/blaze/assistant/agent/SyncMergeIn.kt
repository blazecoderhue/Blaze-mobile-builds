package com.blaze.assistant.agent

/**
 * v0.45.0 (#37): a chat file that cloud sync changed is merged INTO the open chat instead of replacing it.
 *
 * Replacing the session with the file (the old reloadFromSync) lost a message the app had in memory but not yet on
 * disk, and a busy chat was skipped, so its next save wrote over what sync had added. Now:
 *  - messages are matched by global id; one only in the file is added in time order with a fresh local id;
 *  - nothing only in memory is ever dropped (except by a newer /clear from another phone);
 *  - an existing message takes the file's copy when it is fuller (a reply finished on another phone), never while
 *    [busy] (the turn in progress owns its bubbles);
 *  - reactions come from the file unless this phone changed them and hasn't saved yet ([pendingReact]).
 * Pure (SyncMergeInTest).
 */
object SyncMergeIn {
    class Result(val msgs: List<ChatMessage>, val clearedAt: Long)

    fun merge(cur: List<ChatMessage>, file: List<ChatMessage>, curCleared: Long, fileCleared: Long, busy: Boolean,
              pendingReact: Set<String>, newId: () -> Long): Result? {
        val cleared = maxOf(curCleared, fileCleared)
        var changed = cleared != curCleared
        val byGid = HashMap<String, Int>()
        val out = ArrayList<ChatMessage>(cur.size + 8)
        for (m in cur) {
            if (cleared > curCleared && m.at in 1..cleared) { changed = true; continue }
            byGid[m.gid] = out.size; out += m
        }
        // Local ids of the file's messages → this session's (for "answers m7").
        val fileGidOf = file.associate { it.id to it.gid }
        fun localReplies(f: ChatMessage): List<Long> = f.replyTo.mapNotNull { fileGidOf[it] }.mapNotNull { g -> byGid[g]?.let { out[it].id } }
        val adds = ArrayList<ChatMessage>()
        for (f in file) {
            if (cleared > 0 && f.at in 1..cleared) continue
            val i = byGid[f.gid]
            if (i == null) { adds += f; continue }
            val m = out[i]
            var n = m
            if (!busy && fuller(f, m)) n = f.copy(id = m.id, replyTo = localReplies(f).ifEmpty { m.replyTo }, reactions = m.reactions)
            if (f.gid !in pendingReact && f.reactions != m.reactions) n = n.copy(reactions = f.reactions)
            if (n != m) { out[i] = n; changed = true }
        }
        for (f in adds) {
            val n = f.copy(id = newId(), replyTo = localReplies(f))
            // Time order; no time = at the end.
            var pos = out.size
            if (n.at > 0) { pos = out.indexOfFirst { it.at > n.at }; if (pos < 0) pos = out.size }
            out.add(pos, n)
            byGid.clear(); out.forEachIndexed { k, x -> byGid[x.gid] = k }
            changed = true
        }
        return if (changed) Result(out, cleared) else null
    }

    /** The file's copy says more (longer text, then more chips / media / follow-ups). */
    private fun fuller(f: ChatMessage, m: ChatMessage): Boolean {
        if (f.text.length != m.text.length) return f.text.length > m.text.length
        if (f.role != m.role) return false
        return f.meta.length + f.media.size + f.followups.size + f.receipt.length > m.meta.length + m.media.size + m.followups.size + m.receipt.length
    }
}
