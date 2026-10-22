package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Black-and-white frosted glass for the non-chat pages (settings, memory, gallery, setup, home board…).
 * Built only on Compose foundation + the stable [Glass] / [BlazeColors] tokens so it does not depend on the
 * shared design system that is being rebuilt elsewhere. Dark theme: translucent white over near-black;
 * light theme: the same recipe with translucent ink so every page stays legible.
 */
internal object PageGlass {
    val card: Shape = RoundedCornerShape(24.dp)
    val row: Shape = RoundedCornerShape(20.dp)
    val sheet: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val pill: Shape = RoundedCornerShape(50)

    /** Desaturated red, the only colour allowed on these pages (errors). */
    val error = Color(0xFFD9A3A3)

    fun fill(c: BlazeColors, alpha: Float = 0.08f): Color =
        if (c.dark) Color.White.copy(alpha = alpha) else GlassTokens.lightInk.copy(alpha = (alpha * 0.9f).coerceIn(0.04f, 0.14f))   // light: ink ~6–10 %

    /** 1dp rim: white 30% at the top-left fading to 5% at the bottom-right. */
    fun rim(c: BlazeColors, strong: Boolean = false): Brush {
        val base = if (c.dark) Color.White else Color(0xFF111110)
        val top = if (c.dark) (if (strong) 0.45f else 0.30f) else (if (strong) 0.22f else 0.15f)   // light: ink 15 % → 5 %
        return Brush.linearGradient(listOf(base.copy(alpha = top), base.copy(alpha = 0.05f)), start = Offset.Zero, end = Offset.Infinite)
    }

    fun sheen(c: BlazeColors): Brush = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (c.dark) 0.07f else 0.30f), 0.45f to Color.Transparent,
        start = Offset.Zero, end = Offset(600f, 600f))

    fun backdrop(c: BlazeColors): Brush =
        if (c.dark) Brush.verticalGradient(listOf(Color(0xFF111114), Color(0xFF070708), Color.Black))
        else Brush.verticalGradient(listOf(Color(0xFFF7F7F7), Color(0xFFEDEDEE)))
}

/** Monochrome palette for glass pages: the accent becomes the ink (white on dark, near-black on light). */
internal val BlazeColors.mono: BlazeColors get() = copy(accent = ink)

/** Full-screen page background: near-black gradient (dark) or soft grey (light). */
internal fun Modifier.pageBackdrop(c: BlazeColors): Modifier = background(PageGlass.backdrop(c))

/** A frosted glass surface: translucent fill, faint top-left sheen, 1dp gradient rim, content clipped to [shape]. */
internal fun Modifier.pageGlass(
    c: BlazeColors, shape: Shape = PageGlass.card, alpha: Float = 0.08f, strongRim: Boolean = false,
): Modifier = clip(shape)
    .background(PageGlass.fill(c, alpha), shape)
    .background(PageGlass.sheen(c), shape)
    .border(1.dp, PageGlass.rim(c, strongRim), shape)

/** Same as [pageGlass] but toggles to a brighter "selected" state. */
internal fun Modifier.pageGlassSelectable(c: BlazeColors, on: Boolean, shape: Shape = PageGlass.row): Modifier =
    pageGlass(c, shape, alpha = if (on) 0.16f else 0.07f, strongRim = on)

/** Primary call-to-action pill: brighter glass (dark) or solid ink (light) so white labels read either way. */
internal fun Modifier.glassButton(c: BlazeColors, shape: Shape = PageGlass.pill): Modifier =
    if (c.dark) pageGlass(c, shape, alpha = 0.16f, strongRim = true)
    else clip(shape).background(GlassTokens.primaryFill(false), shape).border(1.dp, Color.White.copy(alpha = 0.10f), shape)

/** Faint inner wash for nested/expanded areas inside a glass card (replaces c.faint panels). */
internal fun Modifier.glassInset(c: BlazeColors, shape: Shape = RoundedCornerShape(16.dp)): Modifier =
    clip(shape).background(PageGlass.fill(c, 0.05f), shape).border(1.dp, PageGlass.rim(c).let { it }, shape)

/** Soft top-down sheen on an already-drawn surface (e.g. an image tile). */
internal fun Modifier.glassSheen(c: BlazeColors): Modifier = drawWithContent {
    drawContent(); drawRect(PageGlass.sheen(c))
}

@Composable
internal fun GlassSection(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxWidth().pageGlass(c).padding(padding), content = content)
}

@Composable
internal fun GlassHeader(title: String, sub: String = "", modifier: Modifier = Modifier, right: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalBlaze.current
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = c.ink))
            if (sub.isNotBlank()) BasicText(sub, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
        if (right != null) Row(verticalAlignment = Alignment.CenterVertically, content = right)
    }
}

@Composable
internal fun GlassRow(title: String, sub: String = "", modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
                      trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalBlaze.current
    Row(modifier.fillMaxWidth().pageGlass(c, PageGlass.row, alpha = 0.06f)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
            if (sub.isNotBlank()) BasicText(sub, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        }
        if (trailing != null) trailing()
    }
}

/** Glass row with a monochrome switch (track = white glass when on). */
@Composable
internal fun GlassToggleRow(title: String, sub: String = "", on: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val c = LocalBlaze.current
    GlassRow(title, sub, modifier, onClick = onToggle) {
        Box(Modifier.width(40.dp).padding(vertical = 2.dp).clip(PageGlass.pill)
            .background(if (on) c.ink.copy(alpha = 0.85f) else PageGlass.fill(c, 0.12f), PageGlass.pill)
            .border(1.dp, PageGlass.rim(c), PageGlass.pill).padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(18.dp).background(if (on) (if (c.dark) Color.Black else Color.White) else c.sub, PageGlass.pill))
        }
        Spacer(Modifier.width(2.dp))
    }
}

/** Opaque-enough frosted sheet/dialog/menu surface (no blur behind it, so it keeps a dark base for legibility). */
internal fun Modifier.glassSheet(c: BlazeColors, shape: Shape = PageGlass.sheet): Modifier =
    clip(shape).background(if (c.dark) Color(0xF2121214) else Color(0xF7FAFAFA), shape).pageGlass(c, shape, alpha = 0.06f)
