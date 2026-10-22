package com.blaze.assistant.phone

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Magic Hand (v0.24.7) rules, pure Kotlin (no Android types) so they're unit tested (MagicHandTest).
 *
 * The hand is Blaze's mascot pointer summoned by the user from the quick assistant panel and moved only by the user.
 * These rules turn what the user's finger did on the hand into a gesture, the gesture plus what's under the flame tip
 * into an intent, and the element into the actions offered in the radial menu.
 */
object MagicHandLogic {
    /** A touch sample in screen px, [t] in ms. */
    data class P(val x: Float, val y: Float, val t: Long = 0L)

    /** v0.25.4: where the drawing line is for a hand window at (left, top) of [sizePx]: its centre, so the line comes
     *  out of the middle of the mascot instead of above the flame tip (users found the offset line confusing). */
    fun trailPoint(left: Int, top: Int, sizePx: Int, t: Long = 0L): P = P(left + sizePx / 2f, top + sizePx / 2f, t)

    /** A screen rectangle in px (left, top, right, bottom). */
    data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w: Int get() = r - l
        val h: Int get() = b - t
        val cx: Int get() = (l + r) / 2
        val cy: Int get() = (t + b) / 2
        fun contains(x: Int, y: Int) = x in l until r && y in t until b
        fun isEmpty() = w <= 0 || h <= 0
    }

    enum class Gesture { NONE, TAP, DOUBLE_TAP, HOLD, DRAG, LOOP }

    /** What the hand does about a gesture. */
    enum class Intent { NONE, HIGHLIGHT, MENU, DROP, WATCH, GRAB, TEACH_TAP }

    /** What kind of thing sits under the tip (drives the radial menu). */
    enum class Kind { EMPTY, TEXT, VALUE, LINK, IMAGE, BUTTON, FIELD, SECRET }

    /** The radial menu's actions. [label] is what the menu shows. */
    enum class Action(val label: String) {
        ASK("Ask Blaze"), COPY("Copy"), EXPLAIN("Explain"), TRANSLATE("Translate"), SHARE("Share"), REMIND("Remind me"),
        SEARCH("Search"), OPEN_LINK("Open link"), COPY_LINK("Copy link"), SAVE_IMAGE("Save image"), DESCRIBE("Describe"),
        CARRY("Carry"), WATCH("Watch this"), DROP("Drop here"), DROP_IN_CHAT("Drop in chat")
    }

    /** What the accessibility node under the tip says about itself (filled from AccessibilityNodeInfo by MagicHand). */
    data class Element(
        val text: String = "",
        val desc: String = "",
        val className: String = "",
        val viewId: String = "",
        val clickable: Boolean = false,
        val editable: Boolean = false,
        val password: Boolean = false,
        val url: String = "",
    ) {
        /** The best human label: text, else the description, else the view id turned into words. */
        val label: String get() = text.ifBlank { desc }.ifBlank { viewId.substringAfterLast('/').replace('_', ' ') }.trim()
    }

    // ---------- gestures ----------
    const val SLOP_DP = 16f
    const val HOLD_MS = 600L
    const val DOUBLE_TAP_MIN_MS = 40L
    const val DOUBLE_TAP_MAX_MS = 350L
    /** A loop must be at least this big on both sides (dp), so a wobble isn't a loop. */
    const val LOOP_MIN_DP = 48f
    /** It must turn at least this far around its centre (radians, ~300°). */
    const val LOOP_MIN_TURN = 5.2f

    /**
     * Classifies one touch on the hand (down…up) from its samples. [prevTapUpAt] is when the previous plain tap ended
     * (0 if none), so a second quick tap is a double-tap. Stationary for [HOLD_MS] is a hold; a closed path that winds
     * around its centre is a loop; any other movement is a drag.
     */
    fun classify(points: List<P>, density: Float, prevTapUpAt: Long = 0L): Gesture {
        if (points.isEmpty()) return Gesture.NONE
        val d = density.coerceAtLeast(0.5f)
        val s = points.first()
        val travel = points.maxOf { hypot(it.x - s.x, it.y - s.y) } / d
        if (travel < SLOP_DP) {
            val dur = points.last().t - s.t
            if (dur >= HOLD_MS) return Gesture.HOLD
            if (prevTapUpAt > 0 && s.t - prevTapUpAt in DOUBLE_TAP_MIN_MS..DOUBLE_TAP_MAX_MS) return Gesture.DOUBLE_TAP
            return Gesture.TAP
        }
        return if (isLoop(points, density)) Gesture.LOOP else Gesture.DRAG
    }

    /** A closed, roughly round path: big enough, ends near where it started, and winds ≥ ~300° around its centre. */
    fun isLoop(points: List<P>, density: Float): Boolean {
        if (points.size < 8) return false
        val d = density.coerceAtLeast(0.5f)
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val w = maxX - minX; val h = maxY - minY
        if (w / d < LOOP_MIN_DP || h / d < LOOP_MIN_DP) return false
        val s = points.first(); val e = points.last()
        val gap = hypot(e.x - s.x, e.y - s.y)
        if (gap > max(LOOP_MIN_DP * d, 0.45f * min(w, h))) return false
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        var turn = 0.0
        var prev = atan2((s.y - cy).toDouble(), (s.x - cx).toDouble())
        for (i in 1 until points.size) {
            val a = atan2((points[i].y - cy).toDouble(), (points[i].x - cx).toDouble())
            var da = a - prev
            while (da > Math.PI) da -= 2 * Math.PI
            while (da < -Math.PI) da += 2 * Math.PI
            turn += da; prev = a
        }
        return abs(turn) >= LOOP_MIN_TURN
    }

    /**
     * v0.27.0: true while a drag on the hand is turning into a circle (at least [LOOP_MIN_DP] each way and wound ~225°
     * round its middle; a quarter arc or a straight drag winds at most ~180°), so the live grab box shows while drawing a circle but never while just pointing.
     */
    fun looping(points: List<P>, density: Float): Boolean {
        if (points.size < 6) return false
        val d = density.coerceAtLeast(0.5f)
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        if ((maxX - minX) / d < LOOP_MIN_DP || (maxY - minY) / d < LOOP_MIN_DP) return false
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        var turn = 0.0
        var prev = atan2((points[0].y - cy).toDouble(), (points[0].x - cx).toDouble())
        for (i in 1 until points.size) {
            val a = atan2((points[i].y - cy).toDouble(), (points[i].x - cx).toDouble())
            var da = a - prev
            while (da > Math.PI) da -= 2 * Math.PI
            while (da < -Math.PI) da += 2 * Math.PI
            turn += da; prev = a
        }
        return abs(turn) >= 1.25 * Math.PI
    }

    /**
     * The region a loop selects: its bounding box grown by [padPx] and clamped to the screen ([screenW] × [screenH]).
     * Null when nothing usable is left (smaller than [minPx] on a side, or no points).
     */
    fun loopBounds(points: List<P>, screenW: Int, screenH: Int, padPx: Int = 0, minPx: Int = 24): Box? {
        if (points.isEmpty() || screenW <= 0 || screenH <= 0) return null
        val l = (points.minOf { it.x }.toInt() - padPx).coerceIn(0, screenW)
        val t = (points.minOf { it.y }.toInt() - padPx).coerceIn(0, screenH)
        val r = (kotlin.math.ceil(points.maxOf { it.x }.toDouble()).toInt() + padPx).coerceIn(0, screenW)
        val b = (kotlin.math.ceil(points.maxOf { it.y }.toDouble()).toInt() + padPx).coerceIn(0, screenH)
        val box = Box(l, t, r, b)
        return if (box.w < minPx || box.h < minPx) null else box
    }

    /** A square-ish crop around an element for "point & ask": the element grown by [padPx], clamped, at most [maxPx] a side around its centre. */
    fun cropAround(el: Box, screenW: Int, screenH: Int, padPx: Int, maxPx: Int): Box {
        val w = min(el.w + 2 * padPx, maxPx).coerceAtLeast(1); val h = min(el.h + 2 * padPx, maxPx).coerceAtLeast(1)
        val l = (el.cx - w / 2).coerceIn(0, max(0, screenW - w)); val t = (el.cy - h / 2).coerceIn(0, max(0, screenH - h))
        return Box(l, t, min(screenW, l + w), min(screenH, t + h))
    }

    // ---------- gesture + element → intent ----------
    enum class Mode { POINT, TEACH }

    /**
     * What a gesture on the hand means. Drags only highlight (they never click). Double-tap opens the radial menu, or
     * drops what the hand carries onto an editable field. Hold on a value (or text) offers a watch. A loop grabs that
     * region. In Teach mode a double-tap asks to tap that element for the user (after a confirm) and records it;
     * nothing is ever captured from a password field.
     */
    fun intentFor(g: Gesture, mode: Mode, carrying: Boolean, kind: Kind): Intent = when (g) {
        Gesture.NONE, Gesture.TAP -> Intent.NONE
        Gesture.DRAG -> Intent.HIGHLIGHT
        Gesture.LOOP -> if (mode == Mode.TEACH) Intent.HIGHLIGHT else Intent.GRAB
        Gesture.DOUBLE_TAP -> when {
            mode == Mode.TEACH -> if (kind == Kind.EMPTY) Intent.NONE else Intent.TEACH_TAP
            carrying && kind == Kind.FIELD -> Intent.DROP
            kind == Kind.SECRET -> Intent.NONE
            else -> Intent.MENU
        }
        Gesture.HOLD -> when {
            mode == Mode.TEACH || kind == Kind.SECRET || kind == Kind.EMPTY -> Intent.NONE
            kind == Kind.VALUE || kind == Kind.TEXT -> Intent.WATCH
            else -> Intent.MENU
        }
    }

    // ---------- element → kind → actions ----------
    private val valueRe = Regex(
        """^\s*(?:[$€£¥₹₦]|K|ZMW|USD|EUR|R|KES|NGN|ZAR)?\s*[-+]?\d[\d,. ]*(?:%|k|m|pts?|°[CF]?)?\s*(?:[$€£]|ZMW|USD)?\s*$|^\s*\d+\s*[-:–]\s*\d+\s*$""",
        RegexOption.IGNORE_CASE)
    private val statusRe = Regex("""\b(in stock|out of stock|sold out|available|unavailable|delivered|shipped|out for delivery|pending|processing|on time|delayed|cancelled|canceled|live|final|open|closed|online|offline|arrived|departed|boarding)\b""", RegexOption.IGNORE_CASE)
    private val urlRe = Regex("""^(https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)

    /** True for short text that reads like a price, score, count or status: the things worth watching. */
    fun looksLikeValue(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length > 32) return false
        return valueRe.matches(t) || (t.length <= 24 && statusRe.containsMatchIn(t))
    }

    fun kindOf(e: Element): Kind {
        val cls = e.className.substringAfterLast('.').lowercase()
        return when {
            e.password -> Kind.SECRET
            e.editable || cls.contains("edittext") -> Kind.FIELD
            e.url.isNotBlank() || urlRe.matches(e.text.trim()) -> Kind.LINK
            cls.contains("image") && e.text.isBlank() -> Kind.IMAGE
            looksLikeValue(e.text.ifBlank { e.desc }) -> Kind.VALUE
            e.clickable && (cls.contains("button") || e.text.length in 1..24) && !cls.contains("textview") -> Kind.BUTTON
            e.label.isNotBlank() -> Kind.TEXT
            else -> Kind.EMPTY
        }
    }

    /** The radial menu for an element, at most [MAX_ACTIONS], "Ask Blaze" first (or "Drop here" when carrying onto a field). */
    fun actionsFor(kind: Kind, carrying: Boolean = false): List<Action> {
        val list = when (kind) {
            Kind.EMPTY -> listOf(Action.ASK, Action.EXPLAIN, Action.SHARE)
            Kind.SECRET -> emptyList()
            Kind.FIELD -> if (carrying) listOf(Action.DROP, Action.ASK, Action.COPY) else listOf(Action.ASK, Action.COPY, Action.EXPLAIN, Action.TRANSLATE, Action.CARRY)
            Kind.LINK -> listOf(Action.ASK, Action.OPEN_LINK, Action.COPY_LINK, Action.EXPLAIN, Action.SHARE, Action.REMIND, Action.CARRY)
            Kind.IMAGE -> listOf(Action.ASK, Action.DESCRIBE, Action.SAVE_IMAGE, Action.SHARE, Action.REMIND, Action.CARRY)
            Kind.VALUE -> listOf(Action.ASK, Action.WATCH, Action.COPY, Action.EXPLAIN, Action.SHARE, Action.REMIND, Action.CARRY)
            Kind.BUTTON -> listOf(Action.ASK, Action.EXPLAIN, Action.COPY, Action.TRANSLATE, Action.REMIND)
            Kind.TEXT -> listOf(Action.ASK, Action.COPY, Action.EXPLAIN, Action.TRANSLATE, Action.SHARE, Action.REMIND, Action.SEARCH, Action.CARRY)
        }
        val withDrop = if (carrying && kind != Kind.FIELD && kind != Kind.SECRET) list.take(MAX_ACTIONS - 1) + Action.DROP_IN_CHAT else list
        return withDrop.distinct().take(MAX_ACTIONS)
    }
    const val MAX_ACTIONS = 8

    /** Positions of [n] menu items on a ring of radius [r] around (cx, cy), first at the top, clockwise. */
    fun ring(n: Int, cx: Float, cy: Float, r: Float): List<Pair<Float, Float>> = (0 until n).map { i ->
        val a = -Math.PI / 2 + 2 * Math.PI * i / n.coerceAtLeast(1)
        (cx + r * kotlin.math.cos(a)).toFloat() to (cy + r * kotlin.math.sin(a)).toFloat()
    }

    /** The prompt sent to Blaze for an action on an element (text actions only; the screenshot crop rides along as an image). */
    fun prompt(a: Action, text: String, app: String): String {
        val t = text.trim().take(4000)
        val where = if (app.isBlank()) "" else " (from $app)"
        val q = if (t.isEmpty()) "this part of my screen" else "\u201c$t\u201d"
        return when (a) {
            Action.ASK -> "I'm pointing at $q$where. What is it, and what should I know?"
            Action.EXPLAIN -> "Explain $q$where simply."
            Action.DESCRIBE -> "Describe this picture$where."
            Action.TRANSLATE -> "Translate $q$where into English (or the language I usually use). Just the translation, then one line on anything tricky."
            Action.REMIND -> "Remind me about $q$where. Ask me when if it isn't obvious."
            else -> "About $q$where:"
        }
    }

    /** Short one-line preview of carried / picked text for chips (≤ [n] chars). */
    fun preview(s: String, n: Int = 28): String {
        val l = s.replace(Regex("\\s+"), " ").trim()
        return if (l.length > n) l.take(n - 1).trimEnd() + "\u2026" else l
    }
}
