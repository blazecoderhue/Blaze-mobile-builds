package com.blaze.assistant.agent

/**
 * v0.45.1 speed: when a screen read may reuse the previous accessibility-tree walk instead of walking again
 * (BlazeAccessibilityService.readScreen). Walking costs one binder call per uncached node; waitFor polls every 150 ms and
 * findTarget / anyField / visibleWords / look_at_screen each read again right after a read.
 *
 * Reused only when nothing can have changed: the same window and app, no accessibility event since the walk started
 * (content, window and text changes all send one), no action by Blaze since (taps, typing, scrolls, gestures, any phone
 * tool, apps it opened), and the walk is at most [MAX_AGE_MS] old. The element numbering, diff and output that follow
 * the walk run exactly as before. Pure (ScreenReadCacheTest).
 */
object ScreenReadCache {
    const val MAX_AGE_MS = 1_000L

    fun reusable(sameWindow: Boolean, eventSince: Boolean, actedSince: Boolean, ageMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean =
        sameWindow && !eventSince && !actedSince && ageMs in 0..maxAgeMs
}
