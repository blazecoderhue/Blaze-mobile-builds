package com.blaze.assistant.agent

/**
 * v0.41.0 speed: keeps one value for a short while, keyed. The memory block of the system prompt was rebuilt from the
 * whole memory database for every model call of a turn (a 6-step task read it 6 times); now it is read once and
 * reused until it changes ([key], e.g. the memory version) or [ttlMs] passes. Pure (PromptMemoTest).
 */
class PromptMemo<T>(private val ttlMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private var key: Any? = null
    private var at = 0L
    private var value: T? = null
    private var has = false

    @Synchronized fun get(k: Any?, make: () -> T): T {
        val now = clock()
        if (has && k == key && now - at < ttlMs) @Suppress("UNCHECKED_CAST") return value as T
        val v = make()
        key = k; at = now; value = v; has = true
        return v
    }

    @Synchronized fun clear() { has = false; value = null; key = null }
}
