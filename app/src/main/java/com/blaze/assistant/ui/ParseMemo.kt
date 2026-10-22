package com.blaze.assistant.ui

/**
 * v0.45.6 jank ("really laggy", scrolling back through a chat): LazyColumn drops rows that leave the screen, and with them
 * their `remember`ed parses, so every reply scrolled back into view was cleaned, parsed into cards, expanded into rich
 * blocks and rendered to styled text again on the main thread. These process-wide LRUs keep the results by input, so a
 * row re-entering the screen costs a lookup. Results are immutable (strings, data classes, AnnotatedString). Pure; ParseMemoTest.
 */
class MemoLru<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }
    @Volatile var hits = 0L; private set
    @Volatile var misses = 0L; private set

    @Synchronized fun get(key: K, make: () -> V): V {
        map[key]?.let { hits++; return it }
        misses++
        return make().also { map[key] = it }
    }
    @Synchronized fun size(): Int = map.size
    @Synchronized fun clear() { map.clear(); hits = 0; misses = 0 }
}

object ParseMemo {
    /** Long live texts are not kept (a streaming reply changes every frame; only settled texts are worth keeping). */
    const val MAX_KEY = 40_000
    val bubble = MemoLru<String, String>(400)
    val segs = MemoLru<String, List<AnswerCards.Seg>>(300)
    val blocks = MemoLru<String, List<RichReplies.Block>>(300)
    val md = MemoLru<String, androidx.compose.ui.text.AnnotatedString>(1200)

    inline fun <V> memo(cache: MemoLru<String, V>, key: String, live: Boolean, crossinline make: () -> V): V =
        if (live || key.length > MAX_KEY) make() else cache.get(key) { make() }
}
