package com.blaze.assistant.agent

/**
 * v0.39.0 Swipe navigation (Settings › Navigation, default; Classic tabs is the old pill), pure part (SwipeNavTest). Blaze opens on Home (the Home cards); Chat is a sheet
 * that slides up over it. [progress] 0 = Home showing, 1 = Chat fully up. Dragging the handle at the bottom of Home
 * up opens Chat; dragging down from Chat's top edge brings Home back; a release settles to whichever end the speed
 * or the distance points at, like a notification shade.
 */
object SwipeNav {
    /** A fling faster than this (dp per second) decides by its direction alone. */
    const val FLING_DP_S = 900f
    /** Without a fling, the sheet completes once dragged this far from where it started (fraction of the screen). */
    const val COMPLETE_FRACTION = 0.3f
    /** Extra room between the handle and the system navigation / gesture area (dp). */
    const val HANDLE_MARGIN_DP = 32

    const val HOME = "home"
    const val CHAT = "chat"

    /** The sheet's progress after a vertical drag of [dyPx] (down = positive) from [start] on a screen [heightPx] tall. */
    fun drag(start: Float, dyPx: Float, heightPx: Float): Float {
        if (heightPx <= 0f) return start
        return (start - dyPx / heightPx).coerceIn(0f, 1f)
    }

    /**
     * Where the sheet goes when the finger lifts: 1 (Chat) or 0 (Home). [velocityDpS] is the release speed in dp/s
     * (down = positive); [from] is where the drag started (0 or 1).
     */
    fun settle(progress: Float, velocityDpS: Float, from: Float): Float {
        if (velocityDpS <= -FLING_DP_S) return 1f
        if (velocityDpS >= FLING_DP_S) return 0f
        return if (from < 0.5f) (if (progress >= COMPLETE_FRACTION) 1f else 0f)
        else (if (progress <= 1f - COMPLETE_FRACTION) 0f else 1f)
    }

    /** What the app opens on: Home with Swipe navigation once set up (onboarding finished); Chat with Classic tabs, as before. */
    fun startSurface(swipe: Boolean, onboarded: Boolean): String = if (swipe && onboarded) HOME else CHAT

    /** Space under the handle: the navigation bar plus the margin, so a swipe on the handle never hits the system gesture. */
    fun handleBottomDp(navBarDp: Float): Float = navBarDp + HANDLE_MARGIN_DP

    fun surfaceOf(progress: Float): String = if (progress >= 0.5f) CHAT else HOME

    /** The Awareness line for the surface in front. */
    // ---------------- Settings › Navigation (independent of Simple / Advanced) ----------------

    const val PREF = "nav_style"
    const val SWIPE = "swipe"
    const val CLASSIC = "classic"

    /** What a saved value means: Swipe unless "classic" was chosen (pure). */
    fun styleOf(saved: String?): String = if (saved?.trim()?.lowercase() == CLASSIC) CLASSIC else SWIPE

    private val _swipe = kotlinx.coroutines.flow.MutableStateFlow(true)
    val swipeFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _swipe
    val swipe: Boolean get() = _swipe.value
    fun load() { _swipe.value = try { styleOf(Settings.str(PREF, SWIPE)) == SWIPE } catch (_: Throwable) { true } }
    fun setSwipe(on: Boolean) { try { Settings.setStr(PREF, if (on) SWIPE else CLASSIC) } catch (_: Throwable) { }; _swipe.value = on }

    fun awarenessLine(surface: String): String = if (surface == HOME) "Home (weather, news, heads-ups; swipe up for Chat)" else "Chat (pull down from the top for Home)"
}
