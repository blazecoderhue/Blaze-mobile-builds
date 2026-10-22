package com.blaze.assistant.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * v0.31.0 "frosted glass" design system (phase 1).
 *
 * One look everywhere, taken from the app icon: a dark ground, greyscale frosted tiles with a translucent white fill
 * (6–12 % alpha), a 1 dp rim that catches the light at the top-left (white 30 % → 5 %), a faint specular sheen in the
 * top-left corner and a soft shadow. Accents are white / greys; colour is kept only for errors and warnings, desaturated.
 *
 * Backdrop blur: Compose cannot blur what is *behind* a layer, only a layer's own content. So the frost is drawn
 * (gradient fill + sheen + rim), and on API 31+ decorative backgrounds can be blurred with [Modifier.glassBlur]
 * (RenderEffect). minSdk 26 and low-RAM phones get the same look without the blur ([GlassTokens.blurSupported]).
 *
 * API for phase 2 (every screen restyle should use these, not raw backgrounds):
 *   Modifier.glass(shape, intensity)       the surface itself (fill + sheen + rim + optional shadow)
 *   GlassSurface { }                         Box on glass, optional onClick
 *   GlassCard { }                            Column card (22 dp corners, 16 dp padding)
 *   GlassPill { }                            Row pill (chips, inputs, segmented controls)
 *   GlassButton("Save", primary = true) { }  text button (primary = brighter glass, white ink)
 *   GlassIconButton(icon, desc) { }          round icon button (BlazeGlass.kt, restyled)
 *   GlassTopBar(title, navigation, actions)  page header strip
 *   GlassSheet(onDismiss) { }                bottom sheet surface with grab handle
 *   GlassViewer(title, onDismiss) { }        full-screen glass window (dialog) with a close button
 *   GlassTokens.*                            colours, shapes, alphas
 */

/** How strong the frost is. Low = quiet rows / inputs, Medium = cards, High = sheets, primary buttons, dialogs. */
enum class GlassIntensity(val top: Float, val bottom: Float, val rim: Float, val shadow: Dp) {
    Low(0.07f, 0.04f, 0.70f, 0.dp),
    Medium(0.10f, 0.06f, 1f, 6.dp),
    High(0.14f, 0.08f, 1f, 14.dp),
}

object GlassTokens {
    /** The dark ground every glass tile sits on. */
    val bg = Color(0xFF0A0A0C)
    val bgTop = Color(0xFF141417)
    val ink = Color(0xFFF2F2F3)
    val body = Color(0xDEF2F2F3)
    val sub = Color(0x9EF2F2F3)
    val weak = Color(0x5CF2F2F3)
    /** Monochrome accent (focus rings, "on" states, links). */
    val accent = Color(0xFFE6E6EA)
    val onAccent = Color(0xFF0E0E10)
    /** Semantic colours, desaturated. */
    val error = Color(0xFFD9918A)
    val warning = Color(0xFFD6C08E)
    val success = Color(0xFFA6C9A8)

    /** Light theme: the same glass inverted. Near-black ink is the fill / rim / primary-button colour on light grounds. */
    val lightInk = Color(0xFF111112)

    val rimTop = Color.White.copy(alpha = 0.30f)
    val rimBottom = Color.White.copy(alpha = 0.05f)
    val sheen = Color.White.copy(alpha = 0.10f)

    val cardShape = RoundedCornerShape(22.dp)
    val tileShape = RoundedCornerShape(16.dp)
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
    val pillShape = RoundedCornerShape(50)

    /** RenderEffect blur exists from API 31; skipped on low-RAM phones. */
    fun blurSupported(ctx: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !(try { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true } catch (_: Throwable) { false })

    /** Fill brush for a glass tile (pure; [dark] = the theme). */
    fun fill(dark: Boolean, i: GlassIntensity): Brush = if (dark)
        Brush.verticalGradient(listOf(Color.White.copy(alpha = i.top), Color.White.copy(alpha = i.bottom)))
    else Brush.verticalGradient(listOf(lightInk.copy(alpha = 0.03f + i.top * 0.5f), lightInk.copy(alpha = 0.02f + i.bottom * 0.5f)))   // ink ~6–10 %

    /** The 1 dp rim: bright at the top-left, almost gone at the bottom-right. */
    fun rim(dark: Boolean, i: GlassIntensity): Brush = if (dark)
        Brush.linearGradient(listOf(rimTop.copy(alpha = rimTop.alpha * i.rim), rimBottom.copy(alpha = rimBottom.alpha * i.rim)),
            start = Offset.Zero, end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))
    else Brush.linearGradient(listOf(lightInk.copy(alpha = 0.15f * i.rim.coerceAtLeast(0.8f)), lightInk.copy(alpha = 0.05f)),
        start = Offset.Zero, end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))   // ink 15 % → 5 %

    /** Primary action fill: bright frost on dark; solid near-black ink on light (disabled: ink 25 %). */
    fun primaryFill(dark: Boolean, enabled: Boolean = true): Brush = when {
        dark && enabled -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.18f)))
        dark -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.06f)))
        enabled -> Brush.verticalGradient(listOf(Color(0xFF1C1C1F), lightInk))
        else -> androidx.compose.ui.graphics.SolidColor(lightInk.copy(alpha = 0.25f))
    }
    /** Text / icon colour on [primaryFill]: white (dark & light); disabled = weak white on dark, ink 50 % on light. */
    fun onPrimary(dark: Boolean, enabled: Boolean = true): Color = when {
        enabled -> Color.White
        dark -> Color.White.copy(alpha = 0.40f)
        else -> lightInk.copy(alpha = 0.50f)
    }
}

/** The primary action surface (buttons, Send, Confirm), theme-aware: frost on dark, solid ink on light. */
fun Modifier.glassPrimary(shape: Shape = GlassTokens.pillShape, enabled: Boolean = true): Modifier = composed {
    val dark = LocalBlaze.current.dark
    if (dark) glass(shape, GlassIntensity.High, shadow = false).background(GlassTokens.primaryFill(true, enabled), shape)
    else clip(shape).background(GlassTokens.primaryFill(false, enabled), shape).border(1.dp, Color.White.copy(alpha = 0.10f), shape)
}

/**
 * The glass surface: soft shadow, clip to [shape], translucent white fill, a specular sheen in the top-left and a
 * gradient rim. Put content after it. [shadow] = false for tiles inside other glass (no double shadows).
 */
fun Modifier.glass(shape: Shape = GlassTokens.cardShape, intensity: GlassIntensity = GlassIntensity.Medium, shadow: Boolean = true,
                   sheen: Boolean = true): Modifier = composed {
    val dark = LocalBlaze.current.dark
    // v0.31.2 ("remove those weird white rectangles in chat boxes and just leave the grey"): Android draws an elevation
    // shadow as if its caster were opaque, so under this see-through fill the shadow's hollow middle showed as a pale
    // rectangle inside a dark ring. Glass gets no elevation shadow now: just the grey frost, the sheen and the rim.
    @Suppress("UNUSED_VARIABLE") val wantsShadow = shadow && intensity.shadow > 0.dp
    val base = Modifier
    base.clip(shape).background(GlassTokens.fill(dark, intensity), shape)
        .let { m ->
            if (!sheen) m else m.drawWithContent {
                drawRect(Brush.radialGradient(listOf(GlassTokens.sheen.copy(alpha = if (dark) 0.10f else 0.30f), Color.Transparent),
                    center = Offset(size.width * 0.12f, 0f), radius = (size.minDimension * 0.9f).coerceAtLeast(1f)))
                drawContent()
            }
        }
        .border(1.dp, GlassTokens.rim(dark, intensity), shape)
}

/** API 31+: blurs this layer's own content (use it on decorative backgrounds behind glass). No-op elsewhere. */
fun Modifier.glassBlur(radius: Dp = 24.dp): Modifier = composed {
    val ctx = LocalContext.current
    val ok = remember { GlassTokens.blurSupported(ctx) }
    if (!ok) this else {
        val px = with(LocalDensity.current) { radius.toPx() }
        graphicsLayer { renderEffect = BlurEffect(px, px, TileMode.Decal) }
    }
}

/** A Box on glass. */
@Composable
fun GlassSurface(modifier: Modifier = Modifier, shape: Shape = GlassTokens.cardShape, intensity: GlassIntensity = GlassIntensity.Medium,
                 onClick: (() -> Unit)? = null, contentAlignment: Alignment = Alignment.TopStart, content: @Composable BoxScope.() -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(modifier.pressScale(press).glass(shape, intensity)
        .let { if (onClick != null) it.clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick) else it },
        contentAlignment = contentAlignment, content = content)
}

/** A card on glass (cards, confirm cards, previews). */
@Composable
fun GlassCard(modifier: Modifier = Modifier, intensity: GlassIntensity = GlassIntensity.Medium, shape: Shape = GlassTokens.cardShape,
              padding: PaddingValues = PaddingValues(16.dp), onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val press = remember { MutableInteractionSource() }
    Column(modifier.pressScale(press).glass(shape, intensity)
        .let { if (onClick != null) it.clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick) else it }
        .padding(padding), content = content)
}

/** A pill on glass (chips, the composer, segmented controls). */
@Composable
fun GlassPill(modifier: Modifier = Modifier, intensity: GlassIntensity = GlassIntensity.Low, onClick: (() -> Unit)? = null,
              padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp), content: @Composable RowScope.() -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(modifier.pressScale(press).glass(GlassTokens.pillShape, intensity, shadow = false)
        .let { if (onClick != null) it.clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick) else it }
        .padding(padding), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Text button on glass. [primary] = brighter frost (the main action); not primary = quiet frost. */
@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true,
                height: Dp = 44.dp, danger: Boolean = false) {
    val c = LocalBlaze.current
    val press = remember { MutableInteractionSource() }
    val fg = when {
        primary && !danger -> GlassTokens.onPrimary(c.dark, enabled)
        !enabled -> if (c.dark) c.weak else GlassTokens.lightInk.copy(alpha = 0.50f)
        danger -> if (c.dark) GlassTokens.error else Color(0xFFA8554D)
        else -> c.ink
    }
    Box(modifier.pressScale(press).height(height).widthIn(min = 64.dp)
        .let { if (primary && !danger) it.glassPrimary(GlassTokens.pillShape, enabled) else it.glass(GlassTokens.pillShape, if (primary) GlassIntensity.High else GlassIntensity.Low, shadow = primary) }
        .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled,
            role = Role.Button, onClick = onClick)
        .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(text.sentence(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Sans, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium, fontSize = 14.sp, color = fg))
    }
}

/** A page header strip on glass: optional navigation (e.g. [BackButton]), title, actions. */
@Composable
fun GlassTopBar(title: String, modifier: Modifier = Modifier, subtitle: String = "", navigation: (@Composable () -> Unit)? = null,
                actions: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalBlaze.current
    Row(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
        .glass(RoundedCornerShape(24.dp), GlassIntensity.Low, shadow = false).heightIn(min = 52.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (navigation != null) navigation() else Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
            if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        }
        if (actions != null) Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A bottom sheet surface on glass with a grab handle. Place it at the bottom of a Box; [onDismiss] is for callers' scrims. */
@Composable
fun GlassSheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBlaze.current
    Column(modifier.fillMaxWidth().glass(GlassTokens.sheetShape, GlassIntensity.High).padding(top = 8.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape)
            .background(c.ink.copy(alpha = 0.25f)))
        content()
    }
}

/**
 * A full-screen glass window (a Dialog over everything): a dim scrim, a large glass pane with a title row and a round
 * close button, and [content] filling the rest. Back and the × close it.
 */
@Composable
fun GlassViewer(title: String, onDismiss: () -> Unit, subtitle: String = "", content: @Composable BoxScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val c = LocalBlaze.current
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)).padding(horizontal = 10.dp, vertical = 28.dp)) {
            Column(Modifier.fillMaxSize().appearIn(true, riseDp = 18f).glass(RoundedCornerShape(28.dp), GlassIntensity.High)
                .background(GlassTokens.bg.copy(alpha = if (c.dark) 0.55f else 0f))) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink))
                        if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.5.sp, color = c.sub))
                    }
                    GlassIconButton(Icons.Rounded.Close, "Close", size = 40.dp, onClick = onDismiss)
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(start = 10.dp, end = 10.dp, bottom = 10.dp).clip(RoundedCornerShape(20.dp)), content = content)
            }
        }
    }
}

/** A thin glass hairline between rows inside a glass card. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    val line = if (LocalBlaze.current.dark) Color.White.copy(alpha = 0.12f) else GlassTokens.lightInk.copy(alpha = 0.10f)
    Box(modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, line, Color.Transparent))))
}

/** Screen background: the dark ground with two very soft grey glows (gives the glass something to frost). */
fun Modifier.glassBackground(dark: Boolean): Modifier = if (!dark) this else drawWithContent {
    drawRect(Brush.verticalGradient(listOf(GlassTokens.bgTop, GlassTokens.bg)))
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent), center = Offset(size.width * 0.15f, size.height * 0.1f),
        radius = size.width * 0.8f), radius = size.width * 0.8f, center = Offset(size.width * 0.15f, size.height * 0.1f))
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.035f), Color.Transparent), center = Offset(size.width * 0.9f, size.height * 0.75f),
        radius = size.width * 0.7f), radius = size.width * 0.7f, center = Offset(size.width * 0.9f, size.height * 0.75f))
    drawContent()
}

/** A content description shortcut for glass tiles that are only a picture. */
fun Modifier.glassLabel(text: String): Modifier = semantics { contentDescription = text }
