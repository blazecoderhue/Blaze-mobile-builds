package com.blaze.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * v0.24.0 messaging-style thread. Luke: "everything looks too uniform and boxed, I want it to look like a
 * messaging platform just with our classic theme."
 *
 * Same palette, fonts and mono labels; what changes is the shape: your messages are ink bubbles on the right,
 * Blaze's are soft bubbles on the left, consecutive messages from one side group with tight spacing and
 * asymmetric corners (the sharp corner points at the sender), and one quiet timestamp closes each group.
 * The grouping logic is pure (unit-tested in BubblesTest); the composables are stateless (ShotsTest draws them).
 */

/** Blaze's bubble: a soft fill a step off the paper (not a box, no outline). */
val BlazeColors.bubble: Color get() = if (dark) Color(0x1AFFFFFF) else Color(0xFFEFEFEC)
/** A card inside a Blaze bubble (code, tables): lighter than the bubble so it reads as inset. */
val BlazeColors.inset: Color get() = if (dark) Color(0x12FFFFFF) else Color(0xFFFAFAF8)

/** The fill behind the content being drawn, so fades inside answer cards match it (null = the paper). */
val LocalBubble = staticCompositionLocalOf<Color?> { null }

enum class Side { ME, BLAZE }

/** Where a message sits in a run of messages from the same side. */
enum class GroupPos {
    SINGLE, FIRST, MIDDLE, LAST;
    val starts: Boolean get() = this == SINGLE || this == FIRST
    val ends: Boolean get() = this == SINGLE || this == LAST
}

object Bubbles {
    /** v0.26.2: the most follow-up chips shown in the swipe row under a reply, and their longest label. */
    const val CHIPS_MAX = 5
    const val CHIP_CHARS = 42

    /** The chips for the one-row strip: trimmed, blank and repeated ones dropped, long ones cut with an ellipsis. Pure. */
    fun chipRow(list: List<String>): List<String> = list.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }.take(CHIPS_MAX).map { if (it.length > CHIP_CHARS) it.take(CHIP_CHARS - 1).trimEnd() + "\u2026" else it }

    /** A pause this long between two messages from one side starts a new group (and a new timestamp). */
    const val GAP_MS = 10 * 60_000L

    /** Group positions for a thread, given each item's side and time (0 = unknown time, never splits). */
    fun positions(sides: List<Side>, ats: List<Long>, gapMs: Long = GAP_MS): List<GroupPos> {
        val n = sides.size
        fun joins(i: Int): Boolean { // item i continues item i-1's group
            if (i <= 0 || i >= n) return false
            if (sides[i] != sides[i - 1]) return false
            val a = ats.getOrElse(i - 1) { 0L }; val b = ats.getOrElse(i) { 0L }
            return a <= 0 || b <= 0 || b - a < gapMs
        }
        return List(n) { i ->
            val prev = joins(i); val next = joins(i + 1)
            when {
                !prev && !next -> GroupPos.SINGLE
                !prev -> GroupPos.FIRST
                next -> GroupPos.MIDDLE
                else -> GroupPos.LAST
            }
        }
    }

    /** Space above an item: tight inside a group, a breath between groups. */
    fun gapAbove(pos: GroupPos, first: Boolean): Dp = when {
        first -> 0.dp
        pos.starts -> 16.dp
        else -> 3.dp
    }

    const val BIG = 18
    const val JOIN = 6
    const val TAIL = 4

    /** Corner radii (topStart, topEnd, bottomEnd, bottomStart) in dp: the sender's side is sharp where messages join. */
    fun corners(side: Side, pos: GroupPos): List<Int> {
        val outerTop = if (pos.starts) TAIL else JOIN
        val outerBottom = if (pos.ends) BIG else JOIN
        return if (side == Side.ME) listOf(BIG, outerTop, outerBottom, BIG) else listOf(outerTop, BIG, BIG, outerBottom)
    }

    fun shape(side: Side, pos: GroupPos): RoundedCornerShape = corners(side, pos).let { (ts, te, be, bs) ->
        RoundedCornerShape(topStart = ts.dp, topEnd = te.dp, bottomEnd = be.dp, bottomStart = bs.dp)
    }

    /** "14:02" today, "Yesterday 14:02", "Mon 14:02" this week, else "12 Oct 14:02". "" when unknown. */
    fun time(at: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        if (at <= 0) return ""
        val hm = SimpleDateFormat("HH:mm", locale).format(Date(at))
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        fun dayNo(x: Calendar) = x.get(Calendar.YEAR) * 400 + x.get(Calendar.DAY_OF_YEAR)
        val days = dayNo(n) - dayNo(c)
        return when {
            days <= 0 -> hm
            days == 1 -> "Yesterday $hm"
            days < 7 -> SimpleDateFormat("EEE", locale).format(Date(at)) + " " + hm
            else -> SimpleDateFormat("d MMM", locale).format(Date(at)) + " " + hm
        }
    }
}

/**
 * v0.29.4: see-through bubbles. The fill is a light tint (so particles and the page show through) and a clear 1.2dp
 * outline marks where each bubble starts and ends. Yours is tinted with the accent blue, Blaze's is neutral.
 *
 * v0.29.5/0.29.6: nearly clear glass. The fills are a whisper of tint (no opaque backing anywhere, cards inside included)
 * and the particles drift across the thread. The text keeps a soft halo in the page colour ([halo]) so it stays readable.
 *
 * v0.30.5: theme-fitted glass instead of the blue outline (see below).
 */
object GlassBubble {
    /*
     * v0.30.5: glass that fits the theme (Luke: "give chat boxes the glass look as well fitted to the theme").
     * Dark: a translucent white-tinted glass with a faint light hairline. Light: a translucent white frost with a soft
     * darker hairline. Both are brighter at the top and keep the text halo, so particles still drift through. Your
     * bubbles carry a whisper of the accent; Blaze's are neutral. No blue outline.
     */
    private fun tint(c: BlazeColors, mine: Boolean): Color = if (mine) lerp(Color.White, c.accent, if (c.dark) 0.35f else 0.10f) else Color.White
    /** Top and bottom alpha of a bubble's glass. */
    private fun alphas(c: BlazeColors, mine: Boolean): Pair<Float, Float> = when {
        c.dark && mine -> 0.16f to 0.09f
        c.dark -> 0.10f to 0.05f
        mine -> 0.66f to 0.50f
        else -> 0.60f to 0.44f
    }
    /** The glass fill of a bubble (and the work card). */
    fun fill(c: BlazeColors, mine: Boolean): Brush = alphas(c, mine).let { (top, bottom) ->
        val t = tint(c, mine); Brush.verticalGradient(listOf(t.copy(alpha = top), t.copy(alpha = bottom)))
    }
    /** The hairline: a light rim catching the light at the top, the theme's soft edge below. */
    fun edge(c: BlazeColors): Brush = Brush.verticalGradient(listOf(hairTop(c), hairline(c)))
    fun hairline(c: BlazeColors): Color = if (c.dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.11f)
    fun hairTop(c: BlazeColors): Color = if (c.dark) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.95f)
    /** The most opaque the glass gets, per theme (GlassBubbleTest): frosted, never solid. */
    fun maxAlpha(dark: Boolean): Float = if (dark) 0.20f else 0.70f

    /** The glass's top colour (for fades inside answer cards, [LocalBubble]). */
    fun mineFill(c: BlazeColors): Color = alphas(c, true).let { tint(c, true).copy(alpha = it.first) }
    fun blazeFill(c: BlazeColors): Color = alphas(c, false).let { tint(c, false).copy(alpha = it.first) }
    /** Strips and chips in the thread (follow-ups): clear with a hairline instead of a grey slab. */
    fun stripFill(c: BlazeColors): Color = Color.Transparent
    fun stripEdge(c: BlazeColors): Color = c.ink.copy(alpha = if (c.dark) 0.22f else 0.16f)
    /** Code / table cards inside a bubble: a soft inset, still see-through. */
    fun cardFill(c: BlazeColors): Color = c.inset.copy(alpha = if (c.dark) 0.30f else 0.40f)
    /** A soft glow in the page colour behind every letter, so the words stay crisp with particles drifting across them. */
    fun halo(c: BlazeColors): Shadow = Shadow(color = c.paper.copy(alpha = if (c.dark) 0.85f else 0.95f), offset = Offset.Zero, blurRadius = 6f)
}

/** v0.30.5: a chat box's glass (bubble, work card): theme fill, then the hairline, in [shape]. */
fun Modifier.chatGlass(c: BlazeColors, mine: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    clip(shape).background(GlassBubble.fill(c, mine), shape).border(1.dp, GlassBubble.edge(c), shape)

/*
 * v0.31.0 borderless thread: no bubbles. Nothing boxes a message in to say whose it is. Blaze's text runs full width,
 * plain on the background; yours is set right-aligned, medium weight and a touch dimmer. Cards (work, confirm, rich
 * previews) are the only glass in the thread. [chatGlass] stays for those cards.
 */

/** Your message: right-aligned plain text (medium weight, slightly dimmer). No box. */
@Composable
fun UserBubble(text: String, quoted: String = "", pos: GroupPos = GroupPos.SINGLE, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Column(Modifier.padding(start = 56.dp).widthIn(max = 320.dp).padding(vertical = 2.dp), horizontalAlignment = Alignment.End) {
            if (quoted.isNotEmpty()) {
                Row(Modifier.padding(bottom = 4.dp).height(IntrinsicSize.Min)) {
                    Text(quoted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.weak))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(c.ink.copy(alpha = 0.25f)))
                }
            }
            // v0.28.1: your own message renders its markdown too (## App, 1. **Offline mode**), never raw symbols.
            val shown = remember(text, c.ink) { Md.render(text, c.ink, c.ink.copy(alpha = 0.10f)) }
            SelectionContainer {
                Text(shown, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    style = TextStyle(fontFamily = Type.sansFor(text), fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp,
                        color = c.ink.copy(alpha = 0.78f), shadow = GlassBubble.halo(c)))
            }
        }
    }
}

/** Blaze's message: full-width plain content on the background. No box. [fill] is kept for callers (errors). */
@Composable
fun BlazeBubble(pos: GroupPos = GroupPos.SINGLE, modifier: Modifier = Modifier, fill: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(end = 4.dp, top = 2.dp, bottom = 2.dp)) {
        CompositionLocalProvider(LocalBubble provides null) { content() }
    }
}

/** A Blaze reply drawn in its bubble (screenshots and the thread use the same one). */
@Composable
fun BlazeReply(key: Long, text: String, pos: GroupPos = GroupPos.SINGLE, live: Boolean = false) {
    BlazeBubble(pos) { AnswerBody(key, text, live = live) }
}

/** An error: plain text with a small desaturated warm label; no box. */
@Composable
fun ErrorBubble(text: String, pos: GroupPos = GroupPos.SINGLE) {
    val c = LocalBlaze.current
    BlazeBubble(pos) {
        Label("Error", color = GlassTokens.error, size = 9.sp)
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 21.sp, color = c.body, shadow = GlassBubble.halo(c)))
        }
    }
}

/** The quiet line under the last message of a group: time, then small icon actions (v0.45.2). */
@Composable
fun MetaLine(side: Side, time: String, actions: List<Pair<String, (() -> Unit)?>> = emptyList(), chip: String = "", chipHot: Boolean = false,
             orbSlot: ((androidx.compose.ui.layout.LayoutCoordinates) -> Unit)? = null) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().padding(top = 1.dp), horizontalArrangement = if (side == Side.ME) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically) {
        if (time.isNotBlank()) Text(time, style = TextStyle(fontFamily = Type.Mono, fontSize = 10.sp, color = c.weak),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        actions.forEachIndexed { i, a ->
            val act = a.second
            // v0.45.2: known actions are compact icons (40 dp target, label on long-press); others stay words.
            val kind = MessageActionIcons.kindOf(a.first)
            if (kind != null) { MessageActionIcon(kind, act != null) { act?.invoke() }; return@forEachIndexed }
            if (i > 0 || time.isNotBlank()) MetaDot()
            Text(a.first, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = if (act != null) c.sub else c.weak),
                modifier = Modifier.clickable(enabled = act != null) { act?.invoke() }.padding(horizontal = 6.dp, vertical = 8.dp))
        }
        // v0.31.2: a small slot after Retry where the drop + halo rest when a reply finishes (off the words).
        if (orbSlot != null) Box(Modifier.padding(start = 6.dp).size(26.dp).onGloballyPositioned { orbSlot(it) })
        if (chip.isNotBlank()) Text(chip, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Mono, fontSize = 9.sp, color = if (chipHot) c.accent else c.weak),
            modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false))
    }
}

@Composable
private fun MetaDot() {
    val c = LocalBlaze.current
    Box(Modifier.size(2.dp).clip(CircleShape).background(c.weak))
}

/** A tappable rounded pill (follow-ups, answers to a question, Try suggestions). */
@Composable
fun SoftPill(text: String, modifier: Modifier = Modifier, lead: String = "", fill: Color? = null, ink: Color? = null, onClick: () -> Unit) {
    val c = LocalBlaze.current
    // v0.45.7: always opaque (a see-through fill is laid on the page colour) with a visible border.
    Row(modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp)).background(if (fill == null || fill == c.bubble) ChipStyle.fill(c) else Contrast.solid(fill, c.paper))
        .border(1.dp, ChipStyle.edge(c), RoundedCornerShape(20.dp)).clickable(onClick = onClick)
        .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (lead.isNotBlank()) {
            Text(lead, style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = ink ?: c.accent))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, modifier = Modifier.weight(1f, fill = false), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 19.sp, color = ink ?: c.accent))
    }
}

/** The floating "Chat cleared · Undo" bar (no dialog: /clear only hides the screen). */
@Composable
fun BoxScope.UndoBar(text: String, onUndo: () -> Unit, modifier: Modifier = Modifier, onDismiss: () -> Unit = {}) {
    val c = LocalBlaze.current
    // v0.25.0: swipe it left, right or down to dismiss (it slides out that way).
    Row(modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).swipeAway(onDismiss).clip(RoundedCornerShape(24.dp)).background(c.ink)
        .padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.paper))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onUndo).semantics { contentDescription = "Undo clear" }
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            ButtonText("Undo", color = c.paper)
        }
    }
}

/** Send: an up arrow; Stop: a soft rounded square (v0.24.8). Drawn so they match the pixel-clean marks elsewhere. */
@Composable
fun SendMark(color: Color, stop: Boolean, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val u = w / 10f
        if (stop) { drawRoundRect(color, Offset(2.5f * u, 2.5f * u), Size(5 * u, 5 * u), androidx.compose.ui.geometry.CornerRadius(1.4f * u)); return@Canvas }
        drawLine(color, Offset(5 * u, 9 * u), Offset(5 * u, 1.5f * u), 1.8f * u, StrokeCap.Round)
        drawLine(color, Offset(1.6f * u, 4.8f * u), Offset(5 * u, 1.4f * u), 1.8f * u, StrokeCap.Round)
        drawLine(color, Offset(8.4f * u, 4.8f * u), Offset(5 * u, 1.4f * u), 1.8f * u, StrokeCap.Round)
    }
}

/**
 * v0.26.2: follow-up suggestions as one horizontal, swipeable row of compact chips under the reply
 * (was a stack of full-width pills). Each chip is one line; the row scrolls sideways when they don't fit.
 * v0.43.0: long-press a chip and drop it on another to ask for both ("… and …"); the combined chip can be tapped to send
 * or dropped on a third. A tick on pickup and over a target, which glows. Dropped anywhere else, a chip goes back (a
 * combined one splits into the chips it was made of). A plain tap sends one chip as before. Text rules in [ChipMerge].
 */
@Composable
fun FollowupRow(list: List<String>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    val labels = Bubbles.chipRow(list)
    if (labels.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var chips by remember(labels) { mutableStateOf(ChipMerge.start(labels)) }
    val boxes = remember(labels) { mutableStateMapOf<Int, ChipMerge.Box>() }
    var dragging by remember { mutableIntStateOf(-1) }
    var over by remember { mutableIntStateOf(-1) }
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    var start by remember { mutableStateOf<ChipMerge.Box?>(null) }
    var eatTap by remember { mutableStateOf(false) }
    val slop = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
    fun reset() {
        dragging = -1; over = -1; dx = 0f; dy = 0f; start = null
        scope.launch { kotlinx.coroutines.delay(350); eatTap = false }
    }
    Row(modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState(), enabled = dragging < 0)
        .padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEachIndexed { i, parts ->
            key(parts.joinToString("\u0000")) {
                val text = ChipMerge.text(parts)
                val combined = parts.size > 1
                val lifted = dragging == i
                val target = over == i
                val shape = RoundedCornerShape(18.dp)
                Row(Modifier.appearIn(true, delayMs = 40 * i.coerceAtMost(4))
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        if (lifted) { translationX = dx; translationY = dy; scaleX = 1.05f; scaleY = 1.05f; alpha = 0.94f }
                        else if (target) { scaleX = 1.06f; scaleY = 1.06f }
                    }
                    .onGloballyPositioned { co -> if (dragging != i) co.boundsInRoot().let { r -> boxes[i] = ChipMerge.Box(r.left, r.top, r.right, r.bottom) } }
                    .let { if (target) it.background(c.accent.copy(alpha = 0.18f), shape).border(1.5.dp, c.accent.copy(alpha = 0.75f), shape) else it }
                    // v0.45.7: solid fill and a 3:1 border instead of see-through glass (readable over any scene).
                    .background(ChipStyle.fill(c), shape).border(1.dp, ChipStyle.edge(c), shape)
                    .pointerInput(i, parts) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = i; over = -1; dx = 0f; dy = 0f; start = boxes[i]; eatTap = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                val t = over
                                chips = if (t >= 0) ChipMerge.merge(chips, i, t) else ChipMerge.split(chips, i)
                                reset()
                            },
                            onDragCancel = { reset() },
                        ) { change, d ->
                            change.consume()
                            dx += d.x; dy += d.y
                            val b = start ?: return@detectDragGesturesAfterLongPress
                            val t = ChipMerge.target(List(chips.size) { boxes[it] }, i, b.cx + dx, b.cy + dy, slop)
                            if (t != over) { over = t; if (t >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        }
                    }
                    .clickable { if (eatTap) eatTap = false else onPick(text) }
                    .semantics { contentDescription = (if (combined) "Combined suggestion: " else "Suggestion: ") + text }
                    .widthIn(max = 300.dp).heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (combined) "+" else "\u21b3", style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.accent))
                    Spacer(Modifier.width(6.dp))
                    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, lineHeight = 18.sp, color = c.accent))
                }
            }
        }
    }
}
