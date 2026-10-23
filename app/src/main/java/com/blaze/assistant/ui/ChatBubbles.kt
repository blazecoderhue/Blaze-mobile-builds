package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import java.util.Locale

/*
 * 0.45.20 chat bubbles (user spec A). Yours on the right, Blaze's on the left; rounded, a small tail on the first bubble
 * of a run, at most ~80 % of the width, the time inside at the bottom right, "Today" / "Yesterday" chips between days,
 * runs grouped with tighter spacing. Frosted (semi-opaque) so the theme's scene shows through, in Blaze's own greys and
 * the theme accent (never WhatsApp green), and readable in light and dark over every theme: each fill's opacity is the
 * least that keeps the text at WCAG AA (4.5:1) over the darkest AND brightest thing that can sit behind it
 * ([BubblePalette]); ChatUi20Test checks every theme. No backdrop blur (a RenderEffect behind every bubble is too heavy
 * for low-end phones): the frost is the fill plus a soft top sheen.
 *
 * Bubbles.kt keeps the old public composables (UserBubble, BlazeBubble) and draws them in [ChatBubble].
 */

/** The time shown inside the bubble being drawn ("" = none). Entry provides it for the last bubble of a run. */
val LocalBubbleTime = staticCompositionLocalOf { "" }

/** Long-press on a bubble (outside selectable text) opens its actions menu; null = no menu. */
val LocalBubbleMenu = staticCompositionLocalOf<(() -> Unit)?> { null }

object BubblePalette {
    /** The widest a bubble gets, as a share of the thread's width. */
    const val MAX_SHARE = 0.80f
    /** The tail's width (it sits outside the rounded body, on the sender's side). */
    val TAIL_W = 7.dp
    val TAIL_H = 11.dp
    const val RADIUS = 18f
    /** Muted, never solid: the frost's opacity floor (the AA search can raise it, never lower it). */
    const val FROST = 0.80f

    /** Everything that can sit behind a bubble: pure black, pure white and the page colour (scenes sit between). */
    fun grounds(c: BlazeColors): List<Color> = listOf(Color.Black, Color.White, c.paper.copy(alpha = 1f))

    private fun base(c: BlazeColors, mine: Boolean): Color = when {
        c.dark && mine -> lerp(Color(0xFF26262C), c.accent.copy(alpha = 1f), 0.26f)
        c.dark -> Color(0xFF1B1B20)
        mine -> lerp(Color.White, c.accent.copy(alpha = 1f), 0.14f)
        else -> Color.White
    }

    /** The text drawn in the bubble: your words in full ink, Blaze's in the body ink (as everywhere in the app). */
    fun text(c: BlazeColors, mine: Boolean): Color = if (mine) c.ink else c.body

    private val memo = java.util.concurrent.ConcurrentHashMap<String, Float>()

    /**
     * The least opacity (>= [floor], steps of 0.01) at which [fg] over [b] stays at [ratio] on every ground in [gs].
     * 1 when even a solid fill can't (never for Blaze's palettes).
     */
    fun minAlpha(b: Color, fg: Color, gs: List<Color>, floor: Float, ratio: Double = Contrast.AA_TEXT + 0.1): Float =
        memo.getOrPut("${b.value}|${fg.value}|${gs.joinToString { it.value.toString() }}|$floor|$ratio") {
            // Integer steps: a float step (a * 100 + 1) can land on the same value again and never end.
            var i = kotlin.math.ceil(floor * 100f - 0.001f).toInt().coerceIn(0, 100)
            while (i < 100) {
                val a = i / 100f
                if (gs.all { g -> val f = Contrast.over(b, g, a); Contrast.ratio(Contrast.over(fg, f), f) >= ratio }) return@getOrPut a
                i++
            }
            1f
        }

    /** The bubble's fill (theme-aware, semi-opaque). */
    fun fill(c: BlazeColors, mine: Boolean): Color {
        val b = base(c, mine)
        // The time / labels may use more ink than META_FLOOR (meta() raises it), so only the text sets the opacity.
        return b.copy(alpha = minAlpha(b, text(c, mine), grounds(c), FROST))
    }

    /** The fill as it looks over [ground] (solid): for contrast checks and fades inside answer cards. */
    fun solid(c: BlazeColors, mine: Boolean, ground: Color = c.paper): Color = Contrast.over(fill(c, mine), ground.copy(alpha = 1f))

    /** A whisper of an edge so a bubble ends cleanly on any scene. */
    fun edge(c: BlazeColors): Color = if (c.dark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.07f)

    /** The soft sheen at the top of the frost. */
    fun sheen(c: BlazeColors): Brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (c.dark) 0.05f else 0.35f), Color.Transparent))

    /** Small muted labels (the time in a bubble, the delivery label, day chips) never go below this ink alpha. */
    const val META_FLOOR = 0.62f

    /** The time inside a bubble: ink at the least alpha >= [META_FLOOR] that reads at AA on that bubble over any ground. */
    private val colours = java.util.concurrent.ConcurrentHashMap<String, Color>()

    fun meta(c: BlazeColors, mine: Boolean): Color = colours.getOrPut("meta|$c|$mine") { metaOf(c, mine) }
    private fun metaOf(c: BlazeColors, mine: Boolean): Color {
        val f = fill(c, mine)
        val solids = grounds(c).map { Contrast.over(f, it) }
        var a = META_FLOOR
        while (a < 1f && !solids.all { s -> Contrast.ratio(Contrast.over(c.ink.copy(alpha = a), s), s) >= Contrast.AA_TEXT + 0.1 }) a += 0.01f
        return c.ink.copy(alpha = a.coerceAtMost(1f))
    }

    /** A small capsule on the scene (day chips, the delivery label): its fill and ink, AA over any ground. */
    fun capsule(c: BlazeColors): Color {
        val b = c.paper.copy(alpha = 1f)
        return b.copy(alpha = minAlpha(b, c.ink.copy(alpha = META_FLOOR), grounds(c), 0.55f))
    }
    fun capsuleInk(c: BlazeColors): Color = colours.getOrPut("cap|$c") { capsuleInkOf(c) }
    private fun capsuleInkOf(c: BlazeColors): Color {
        val f = capsule(c)
        val solids = grounds(c).map { Contrast.over(f, it) }
        var a = META_FLOOR
        while (a < 1f && !solids.all { s -> Contrast.ratio(Contrast.over(c.ink.copy(alpha = a), s), s) >= Contrast.AA_TEXT + 0.1 }) a += 0.01f
        return c.ink.copy(alpha = a.coerceAtMost(1f))
    }
}

/** A rounded bubble with a small tail at the top on the sender's side ([tail]); the body is inset by the tail's width. */
class TailBubbleShape(private val mine: Boolean, private val pos: GroupPos, private val tail: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tw = with(density) { BubblePalette.TAIL_W.toPx() }
        val th = with(density) { BubblePalette.TAIL_H.toPx() }
        val (ts, te, be, bs) = Bubbles.corners(if (mine) Side.ME else Side.BLAZE, pos).map { with(density) { it.dp.toPx() } }
        // Body: inset on the tail side so the tail sits inside the bubble's own bounds.
        val left = if (mine) 0f else tw
        val right = if (mine) size.width - tw else size.width
        val body = Path().apply {
            addRoundRect(RoundRect(left, 0f, right, size.height,
                topLeftCornerRadius = CornerRadius(if (tail && !mine) 0f else ts), topRightCornerRadius = CornerRadius(if (tail && mine) 0f else te),
                bottomRightCornerRadius = CornerRadius(be), bottomLeftCornerRadius = CornerRadius(bs)))
        }
        if (!tail) return Outline.Generic(body)
        val t = Path().apply {
            if (mine) { moveTo(right - 1f, 0f); lineTo(size.width, 0f); lineTo(size.width - tw * 0.45f, th * 0.3f); lineTo(right - 1f, th) }
            else { moveTo(left + 1f, 0f); lineTo(0f, 0f); lineTo(tw * 0.45f, th * 0.3f); lineTo(left + 1f, th) }
            close()
        }
        return Outline.Generic(Path.combine(PathOperation.Union, body, t))
    }
}

/** A long-press that never steals taps, swipes or double-taps from the bubble's content (links, chips, swipe to quote). */
fun Modifier.bubbleLongPress(onLong: (() -> Unit)?): Modifier = if (onLong == null) this else pointerInput(onLong) {
    val timeout = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val up = withTimeoutOrNull(timeout) { waitForUpOrCancellation() }
        // Still pressed after the timeout, and nothing inside (a text selection, a drag) took the press: the menu.
        if (up == null && !down.isConsumed && currentEvent.changes.none { it.isConsumed }) onLong()
    }
}

/**
 * The bubble frame (spec A). [side] picks the side, colours and tail; the content goes inside with the time, from
 * [LocalBubbleTime], at the bottom right. Max [BubblePalette.MAX_SHARE] of the thread's width.
 */
@Composable
fun ChatBubble(side: Side, pos: GroupPos, modifier: Modifier = Modifier, wide: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBlaze.current
    val mine = side == Side.ME
    val time = LocalBubbleTime.current
    val menu = LocalBubbleMenu.current
    val tail = pos.starts
    val shape = remember(mine, pos, tail) { TailBubbleShape(mine, pos, tail) }
    val fill = BubblePalette.fill(c, mine)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val max = maxWidth * (if (wide) 0.92f else BubblePalette.MAX_SHARE)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
            Column(Modifier.widthIn(min = 56.dp, max = max).clip(shape).background(fill, shape).background(BubblePalette.sheen(c), shape)
                .border(1.dp, BubblePalette.edge(c), shape).bubbleLongPress(menu)
                .padding(start = if (mine) 12.dp else 12.dp + BubblePalette.TAIL_W, end = if (mine) 12.dp + BubblePalette.TAIL_W else 12.dp, top = 7.dp, bottom = 5.dp)) {
                CompositionLocalProvider(LocalBubble provides BubblePalette.solid(c, mine)) { content() }
                if (time.isNotBlank()) Text(time, modifier = Modifier.align(Alignment.End).padding(top = 1.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 10.5.sp, lineHeight = 13.sp, color = BubblePalette.meta(c, mine)))
            }
        }
    }
}

/** Day chips between days ("Today", "Yesterday", "Mon", "12 Oct 2025"). Pure; ChatUi20Test. */
object BubbleDays {
    private fun dayNo(at: Long): Int = Calendar.getInstance().apply { timeInMillis = at }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }

    /** True when a chip goes above the message at [at] (the first dated message, or a new calendar day). */
    fun startsDay(prevAt: Long, at: Long): Boolean = at > 0 && (prevAt <= 0 || dayNo(prevAt) != dayNo(at))

    fun label(at: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        val d = dayNo(now) - dayNo(at)
        val sameYear = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.YEAR) == Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
        return when {
            d <= 0 -> "Today"
            d == 1 -> "Yesterday"
            d < 7 -> java.text.SimpleDateFormat("EEEE", locale).format(java.util.Date(at))
            sameYear -> java.text.SimpleDateFormat("d MMM", locale).format(java.util.Date(at))
            else -> java.text.SimpleDateFormat("d MMM yyyy", locale).format(java.util.Date(at))
        }
    }

    /** The time inside a bubble: "14:02" (the day is on the chip above). */
    fun time(at: Long, locale: Locale = Locale.getDefault()): String =
        if (at <= 0) "" else java.text.SimpleDateFormat("HH:mm", locale).format(java.util.Date(at))
}

/** The small centred day chip. */
@Composable
fun DayChip(label: String, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(BubblePalette.capsule(c), RoundedCornerShape(10.dp))
            .semantics { heading() }.padding(horizontal = 12.dp, vertical = 4.dp),
            style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, color = BubblePalette.capsuleInk(c)))
    }
}
