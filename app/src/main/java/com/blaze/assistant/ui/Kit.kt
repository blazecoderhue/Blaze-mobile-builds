package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * v0.19.0 visual parity: the small kit Blaze Desktop's pages are built from (frontend/dist/style.css:
 * .phead, .group, .rows, .pill, .btn, .empty), in the Blaze look: paper, ink, hairlines, one blue accent.
 * v0.24.8 "no square UI": every piece is rounded ([BlazeShapes]): grouped rows are rounded cards, buttons are pills in
 * sentence case (primary = the mascot's orange-to-blue gradient), inputs are filled pills, tags are pills, the switch is round.
 */

/** Desktop's --raised (cards, rows) and --hair2 (their borders), --accent-soft (on states). */
// v0.31.0: translucent white on the dark ground (glass), so raised surfaces frost instead of looking like grey slabs.
val BlazeColors.raised: Color get() = if (dark) Color(0x17FFFFFF) else Color(0xFFFFFFFF)
val BlazeColors.hair2: Color get() = if (dark) Color(0x2BFFFFFF) else Color(0x2E111112)
val BlazeColors.accentSoft: Color get() = accent.copy(alpha = if (dark) 0.16f else 0.09f)
val BlazeColors.onAccent: Color get() = if (dark) Color(0xFF0E0E10) else Color.White

/** Page header: accent eyebrow, 24sp display title, a lead line. */
@Composable
fun PageHead(eyebrow: String, title: String, lead: String = "", modifier: Modifier = Modifier, right: (@Composable () -> Unit)? = null) {
    val c = LocalBlaze.current
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Label(eyebrow, color = c.accent, size = 10.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 29.sp,
                letterSpacing = (-0.02).em, color = c.ink))
            if (lead.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(lead, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, lineHeight = 20.sp, color = c.sub))
            }
        }
        if (right != null) { Spacer(Modifier.width(12.dp)); right() }
    }
}

/** Group header: mono label, optional action on the right, optional description under it. */
@Composable
fun GroupHead(title: String, desc: String = "", modifier: Modifier = Modifier, right: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(title, size = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
            right?.invoke(this)
        }
        if (desc.isNotBlank()) Text(desc, modifier = Modifier.padding(top = 4.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 18.sp, color = c.sub))
    }
}

/** Desktop's .rows as a rounded grouped card (v0.24.8): a raised 16dp card with soft hairlines between its rows. */
@Composable
fun Rows(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxWidth().glass(BlazeShapes.medium, GlassIntensity.Low, shadow = false), content = content)
}

/**
 * One row: number, title (+ pill), sub line, trailing actions. [first] skips the top hairline.
 * Titles get one line, subs two, so nothing wraps into a wall on a 360dp phone.
 */
@Composable
fun KitRow(title: String, sub: String = "", n: Int = 0, first: Boolean = false, pill: String = "", pillOn: Boolean = false,
           dim: Boolean = false, mono: Boolean = false, onClick: (() -> Unit)? = null, lead: (@Composable () -> Unit)? = null,
           trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().let { if (first) it else it.hairlineTopInset(c.hair) }.let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .heightIn(min = 52.dp).padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // v0.24.8: no "01 / 02" row numbers (they were part of the old square desktop look); [n] is kept for callers.
        if (lead != null) { lead(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = if (mono) Type.Mono else Type.Sans, fontWeight = FontWeight.Medium,
                        fontSize = if (mono) 13.sp else 14.sp, color = if (dim) c.weak else c.ink))
                if (pill.isNotBlank()) { Spacer(Modifier.width(8.dp)); Tag(pill, on = pillOn) }
            }
            if (sub.isNotBlank()) Text(sub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.sub))
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); Row(verticalAlignment = Alignment.CenterVertically, content = trailing) }
    }
}

/** Small pill tag: accent-soft when [on], faint otherwise. */
@Composable
fun Tag(text: String, on: Boolean = false) {
    val c = LocalBlaze.current
    Box(Modifier.clip(BlazeShapes.pill).background(if (on) c.accentSoft else c.faint, BlazeShapes.pill).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text.sentence(), maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 10.5.sp,
            color = if (on) c.accent else c.sub))
    }
}

enum class Btn { ACCENT, INK, LINE, GHOST }

/**
 * Desktop's .btn as a Blaze pill (v0.24.8): 40dp tall, sentence case. ACCENT and INK are the primary gradient pill,
 * LINE a raised outlined pill, GHOST plain text in a pill-shaped touch area.
 */
@Composable
fun KitButton(text: String, kind: Btn = Btn.LINE, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    BlazeButton(text, modifier = modifier, kind = when (kind) { Btn.ACCENT, Btn.INK -> BtnKind.PRIMARY; Btn.LINE -> BtnKind.SECONDARY; Btn.GHOST -> BtnKind.GHOST },
        enabled = enabled, height = 40.dp, onClick = onClick)
}

enum class BtnKind { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * The one Blaze button (v0.24.8): a pill, sentence case. PRIMARY = orange-to-blue gradient with white text,
 * SECONDARY = raised pill with a hairline, GHOST = text only, DANGER = warm orange outline.
 */
@Composable
fun BlazeButton(text: String, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.PRIMARY, enabled: Boolean = true,
                height: androidx.compose.ui.unit.Dp = 44.dp, lead: String = "", onClick: () -> Unit) {
    val c = LocalBlaze.current
    val shape = BlazeShapes.pill
    // v0.27.0: shrinks slightly while pressed (MotionTokens; none with Remove animations on).
    val press = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val base = modifier.pressScale(press).height(height).widthIn(min = 64.dp).clip(shape)
    val filled = when {
        // v0.31.0: glass pills: primary = bright frost (dark) / solid ink (light), secondary = quiet frost, danger = warm rim.
        kind == BtnKind.PRIMARY -> modifier.pressScale(press).height(height).widthIn(min = 64.dp).glassPrimary(shape, enabled)
        !enabled -> base.background(if (c.dark) c.faint else GlassTokens.lightInk.copy(alpha = 0.10f), shape)
        kind == BtnKind.SECONDARY -> modifier.pressScale(press).height(height).widthIn(min = 64.dp).glass(shape, GlassIntensity.Low, shadow = false)
        kind == BtnKind.DANGER -> base.border(1.dp, GlassTokens.error.copy(alpha = 0.7f), shape)
        else -> base
    }
    val fg = when {
        kind == BtnKind.PRIMARY -> GlassTokens.onPrimary(c.dark, enabled)
        !enabled -> if (c.dark) c.weak else GlassTokens.lightInk.copy(alpha = 0.50f)
        kind == BtnKind.DANGER -> GlassTokens.error
        kind == BtnKind.GHOST -> c.sub
        else -> c.ink
    }
    Box(filled.clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, onClick = onClick)
        .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text((if (lead.isNotBlank()) "$lead  " else "") + text.sentence(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = fg))
    }
}

/**
 * The one Blaze text field (v0.24.8): a filled dark surface ([field]) with a soft outline that turns accent when focused.
 * Single line = a pill; multi-line = a 16dp card. No square outline anywhere.
 */
@Composable
fun BlazeTextField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, singleLine: Boolean = true,
                   minHeight: androidx.compose.ui.unit.Dp = if (singleLine) 46.dp else 88.dp, mono: Boolean = false, enabled: Boolean = true,
                   keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
                   keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
                   visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None) {
    val c = LocalBlaze.current
    var focused by remember { mutableStateOf(false) }
    val shape = if (singleLine) BlazeShapes.pill else BlazeShapes.medium
    Box(modifier.heightIn(min = minHeight).clip(shape).background(c.field, shape).border(1.dp, if (focused) c.accent else c.fieldLine, shape)
        .padding(horizontal = if (singleLine) 18.dp else 14.dp, vertical = 12.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
        val style = TextStyle(fontFamily = if (mono) Type.Mono else Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.ink)
        if (value.isEmpty()) Text(hint, maxLines = if (singleLine) 1 else 4, overflow = TextOverflow.Ellipsis, style = style.copy(color = c.weak))
        BasicTextField(value, onChange, singleLine = singleLine, enabled = enabled, cursorBrush = SolidColor(c.accent), textStyle = style,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
    }
}

/** A rounded Blaze card (v0.24.8): raised surface, 16dp corners, soft hairline; [accent] outlines it in blue. */
@Composable
fun BlazeCard(modifier: Modifier = Modifier, accent: Boolean = false, padding: androidx.compose.ui.unit.Dp = 14.dp,
              onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBlaze.current
    val shape = BlazeShapes.medium
    // v0.31.0: a glass card; [accent] brightens the rim.
    Column(modifier.fillMaxWidth().glass(shape, GlassIntensity.Medium, shadow = false)
        .let { if (accent) it.border(1.dp, c.accent.copy(alpha = 0.55f), shape) else it }
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding), content = content)
}

/** "CREATE" / "create" → "Create" (buttons and tags are sentence case since v0.24.8). Leaves mixed case alone. */
fun String.sentence(): String {
    val letters = filter { it.isLetter() }
    if (letters.isEmpty() || letters.any { it.isLowerCase() }) return this
    val low = lowercase()
    val k = low.indexOfFirst { it.isLetter() }
    return low.substring(0, k) + low[k].uppercaseChar() + low.substring(k + 1)
}

/** A hairline across the top, inset from both sides (separators inside rounded grouped cards). */
fun Modifier.hairlineTopInset(color: Color, inset: androidx.compose.ui.unit.Dp = 14.dp) = drawBehind {
    val x = inset.toPx()
    drawLine(color, androidx.compose.ui.geometry.Offset(x, 0.5f), androidx.compose.ui.geometry.Offset(size.width - x, 0.5f), 1f)
}

/** Desktop's .empty: a bold line and a quiet one, centred, with an optional action. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, mark: Boolean = true, action: String = "", onAction: () -> Unit = {}) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (mark) { DotMark(); Spacer(Modifier.height(14.dp)) }
        Text(title, textAlign = TextAlign.Center, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
        Spacer(Modifier.height(4.dp))
        Text(body, textAlign = TextAlign.Center, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
        if (action.isNotBlank()) { Spacer(Modifier.height(14.dp)); KitButton(action, Btn.LINE, onClick = onAction) }
    }
}

/** The particle-dot mark empty states wear: a 3x3 grid of dots, the centre one blue (round since v0.24.8). */
@Composable
fun DotMark(size: androidx.compose.ui.unit.Dp = 30.dp) {
    val c = LocalBlaze.current
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val g = this.size.width / 3f; val d = g * 0.56f; val o = (g - d) / 2f
        for (y in 0 until 3) for (x in 0 until 3) {
            val mid = x == 1 && y == 1
            drawCircle(if (mid) c.accent else c.ink.copy(alpha = if ((x + y) % 2 == 1) 0.35f else 0.85f),
                d / 2f, androidx.compose.ui.geometry.Offset(x * g + g / 2f, y * g + g / 2f))
        }
    }
}

/** Desktop's .msearch as a filled pill (v0.24.8) that turns blue with a soft ring when focused. */
@Composable
fun KitSearch(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().let { if (focused) it.background(c.accentSoft, BlazeShapes.pill).padding(2.dp) else it.padding(2.dp) }
        .background(c.field, BlazeShapes.pill).hairline(if (focused) c.accent else c.fieldLine, BlazeShapes.pill).height(42.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
            val col = if (focused) c.accent else c.weak
            drawCircle(col, this.size.width * 0.34f, androidx.compose.ui.geometry.Offset(this.size.width * 0.42f, this.size.height * 0.42f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
            drawLine(col, androidx.compose.ui.geometry.Offset(this.size.width * 0.68f, this.size.height * 0.68f),
                androidx.compose.ui.geometry.Offset(this.size.width, this.size.height), 1.4.dp.toPx())
        }
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, color = c.weak))
            BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(c.accent),
                textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, color = c.ink),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
        }
        if (value.isNotEmpty()) Box(Modifier.size(28.dp).clip(BlazeShapes.pill).clickable { onChange("") }, contentAlignment = Alignment.Center) {
            Text("\u00d7", style = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, color = c.sub))
        }
    }
}

/** Two- or three-way segmented pill (Graph | List), ink when on. */
@Composable
fun Segmented(options: List<Pair<String, String>>, value: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    Row(modifier.glass(GlassTokens.pillShape, GlassIntensity.Low, shadow = false).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (id, label) ->
            val on = id == value
            // v0.27.0: the selected segment's fill fades across instead of jumping.
            val fill by androidx.compose.animation.animateColorAsState(if (on) c.ink else Color.Transparent, MotionTokens.medium(), label = "seg")
            Box(Modifier.height(30.dp).clip(BlazeShapes.pill).background(fill, BlazeShapes.pill).clickable { onPick(id) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center) {
                Text(label, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.5.sp,
                    color = if (on) c.paper else c.sub))
            }
        }
    }
}

/** Round on/off switch (v0.24.8: a pill track with a round knob, blue when on), usable outside Settings. */
@Composable
fun KitSwitch(on: Boolean, onToggle: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.clip(BlazeShapes.pill).clickable(onClick = onToggle).padding(4.dp)) {
        BlazeSwitchTrack(on, 40.dp, 22.dp)
    }
}

/** The rounded switch drawing shared by [KitSwitch] and Settings' own toggles: pill track, round knob, accent when on. */
@Composable
fun BlazeSwitchTrack(on: Boolean, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    val c = LocalBlaze.current
    val knob = h - 4.dp
    Box(Modifier.width(w).height(h).clip(BlazeShapes.pill).background(if (on) c.accent else c.faint, BlazeShapes.pill)
        .border(1.dp, if (on) c.accent else c.hair2, BlazeShapes.pill)) {
        Box(Modifier.padding(start = if (on) w - knob - 2.dp else 2.dp, top = 2.dp).size(knob).clip(BlazeShapes.pill)
            .background(if (on) c.onAccent else c.weak, BlazeShapes.pill))
    }
}

// ---------------- v0.24.5: Blaze dialog and menu (no Material defaults anywhere) ----------------

/** Dialog surface: always the dark Blaze card (Blazeinchue: matches the app; never Material's lavender). */
/**
 * v0.31.2 theme fix: the surface for menus and pop-up lists whose text uses the theme's ink (the avatar menu, the "/"
 * suggestions). They used the always-dark dialog surface, so in the light theme dark text sat on a dark panel.
 * Near-opaque so the chat behind never shows through the words.
 */
val BlazeColors.menuSurface: Color get() = if (dark) Color(0xF5141416) else Color(0xFAF7F7F8)

object DialogColors {
    // v0.31.0: greyscale glass (near-opaque so dialogs read over anything).
    val surface = Color(0xF2141416)
    val border = Color(0x2EFFFFFF)
    val title = GlassTokens.ink
    val body = GlassTokens.sub
    val primary = Color(0xFFE6E6EA)
    val primaryWarm = GlassTokens.error      // desaturated warm, for a destructive-looking primary
    val onPrimary = Color(0xFFFFFFFF)
    val ghost = Color(0xCCF2F2F3)
    val scrim = Color(0x99000000)
}

/**
 * The shared Blaze dialog: a rounded dark card with a subtle border and shadow, paper-white title, muted body,
 * a primary pill button (brand blue, or orange with [warm]) and an optional ghost secondary button.
 */
@Composable
fun BlazeDialog(
    title: String, body: String = "", confirm: String, onConfirm: () -> Unit,
    dismiss: String? = null, onDismiss: () -> Unit, warm: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) { BlazeDialogCard(title, body, confirm, onConfirm, dismiss, onDismiss, warm, content) }
}

/** The card itself (also used by screenshots, which can't open a real dialog window). */
@Composable
fun BlazeDialogCard(
    title: String, body: String = "", confirm: String, onConfirm: () -> Unit,
    dismiss: String? = null, onDismiss: () -> Unit = {}, warm: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()
        .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .background(DialogColors.surface, shape)
        .glass(shape, GlassIntensity.High, shadow = false)
        .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 16.dp)) {
        Text(title, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, color = DialogColors.title))
        if (body.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = DialogColors.body))
        }
        content?.let { Spacer(Modifier.height(12.dp)); it() }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (dismiss != null) PillButton(dismiss, primary = false, onClick = onDismiss)
            PillButton(confirm, primary = true, warm = warm, onClick = onConfirm)
        }
    }
}

/** Rounded pill button for dialogs, menus and overlays: filled brand blue / orange, or a ghost. */
@Composable
fun PillButton(text: String, primary: Boolean, warm: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    val bg = if (!primary) androidx.compose.ui.graphics.SolidColor(Color.Transparent) else if (warm) androidx.compose.ui.graphics.SolidColor(DialogColors.primaryWarm.copy(alpha = 0.55f)) else BlazeBrush.primary
    val press = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(modifier.pressScale(press).height(40.dp).widthIn(min = 72.dp).clip(shape).background(bg, shape)
        .let { if (!primary) it.border(1.dp, DialogColors.border, shape) else it }
        .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
        .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(text, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp,
            color = if (primary) DialogColors.onPrimary else DialogColors.ghost))
    }
}

/** The shared Blaze menu (replaces Material's DropdownMenu): a rounded dark card in a popup. */
@Composable
fun BlazeMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<Pair<String, () -> Unit>>) {
    if (!expanded) return
    androidx.compose.ui.window.Popup(alignment = Alignment.TopEnd, onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        BlazeMenuCard(items, onDismiss)
    }
}

@Composable
fun BlazeMenuCard(items: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit = {}) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    Column(Modifier.widthIn(min = 200.dp, max = 280.dp).shadow(12.dp, shape).background(DialogColors.surface, shape)
        .glass(shape, GlassIntensity.High, shadow = false).padding(vertical = 6.dp)) {
        items.forEach { (label, act) ->
            Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(BlazeShapes.small).clickable { onDismiss(); act() }.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(label, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = DialogColors.title))
            }
        }
    }
}

/**
 * v0.24.8: a tappable text action ("Delete", "Open", "×"): sentence-case sans in a pill-shaped touch area. Same
 * parameters as [Label] so the old uppercase mono "label buttons" swap over one for one.
 */
@Composable
fun ActionText(text: String, color: Color = LocalBlaze.current.sub, size: androidx.compose.ui.unit.TextUnit = 11.sp, modifier: Modifier = Modifier,
               maxLines: Int = 1) {
    Text(text.sentence(), modifier = Modifier.clip(BlazeShapes.pill).then(modifier).padding(horizontal = 6.dp, vertical = 2.dp), maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = (size.value + 3f).sp, color = color))
}

/** v0.24.8: the old uppercase mono "button" text inside a filled box, as sentence-case button text. */
@Composable
fun ButtonText(text: String, color: Color = LocalBlaze.current.ink, size: androidx.compose.ui.unit.TextUnit = 14.sp) {
    Text(text.sentence(), maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = size, color = color))
}

/** v0.28.3: the back button's fill and outline (Material's surface-variant in the Blaze palette, the Kit's 1 dp hairline). */
val BlazeColors.backFill: Color get() = if (dark) Color(0x1AFFFFFF) else Color(0xFFF0F0EE)
val BlazeColors.backLine: Color get() = if (dark) Color(0x33FFFFFF) else Color(0x1A111110)

/**
 * v0.28.3: the one back button for every page (settings, projects, project chats, search, gallery, Learn…), instead of
 * a thin "←" text glyph. A 40 dp round tonal button with a 1 dp outline and Material's rounded ArrowBack (auto-mirrored
 * for right-to-left), inside a 48 dp touch target, with a ripple and a slight press scale. Pair it with [BackTitle].
 */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier, desc: String = "Back") {
    val c = LocalBlaze.current
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val circle = androidx.compose.foundation.shape.CircleShape
    Box(modifier.size(48.dp)
        .semantics(mergeDescendants = true) { contentDescription = desc }
        .clickable(interactionSource = press, indication = null, onClickLabel = desc, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.pressScale(press).size(40.dp).clip(circle).background(c.backFill, circle).border(1.dp, c.backLine, circle)
            .indication(press, androidx.compose.foundation.LocalIndication.current), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null,
                tint = c.ink, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * v0.28.3: a page header row: [BackButton], then the title (and an optional small line above it, e.g. the project name
 * over a project chat's title), aligned the same on every page. [right] holds trailing actions.
 */
@Composable
fun BackTitle(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, eyebrow: String = "", size: androidx.compose.ui.unit.TextUnit = 20.sp,
              right: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalBlaze.current
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            if (eyebrow.isNotBlank()) Text(eyebrow, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = c.sub))
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = size, letterSpacing = (-0.01).em, color = c.ink))
        }
        right?.invoke(this)
    }
}
