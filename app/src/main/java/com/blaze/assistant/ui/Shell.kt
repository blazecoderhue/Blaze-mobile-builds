package com.blaze.assistant.ui

import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.ChatMeta
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Project
import kotlinx.coroutines.delay

/*
 * Blaze 0.12 shell: a calm, messaging-app layout (Desktop 1.0 redesign, part A).
 * Everything here is stateless so the screenshot tests can draw it with sample data.
 */

// ---------------------------------------------------------------- top bar

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShellTopBar(
    mode: String, onMode: (String) -> Unit, onLogo: () -> Unit, onSearch: () -> Unit,
    routerOk: Boolean, onRouter: () -> Unit, avatar: String, onAvatar: () -> Unit,
    /** v0.45.2: long-press on the profile icon (light / dark). */
    onAvatarLong: () -> Unit = {},
    mood: Mood = Mood.IDLE, viewChip: String = "", onExit: () -> Unit = {},
    /** v0.24.4: private chat on (ghost filled + "Private" pill) and the ghost button. */
    isPrivate: Boolean = false, onGhost: () -> Unit = {},
    /** v0.39.0: false in Simple mode, where Home sits under the chat (swipe navigation) instead of being a tab. */
    homeTab: Boolean = true,
    /** v0.43.0: who the next message goes to ("blaze" / "halo" / "all"); the orb shows it, and tapping it opens the arc. Blank = the plain icon. */
    talkTo: String = "",
) {
    val c = LocalBlaze.current
    // v0.31: the app bar is a glass strip on the dark ground.
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).glass(RoundedCornerShape(26.dp), GlassIntensity.Low, shadow = false)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 10.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // v0.24.8: the mascot in a circle (was a square pixel "B").
            // v0.43.0: it shows who you're talking to (orb = Blaze, halo = Halo, orb in its halo = All); a tap opens the picker arc.
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onLogo)
                .semantics { contentDescription = if (talkTo.isBlank()) "Blaze" else "Talking to ${TalkArcMath.label(talkTo)}. Tap to choose who to talk to" },
                contentAlignment = Alignment.Center) { if (talkTo.isBlank()) BrandMark(mood, 34.dp) else TalkTargetMark(talkTo, mood, 34.dp) }
            Spacer(Modifier.width(6.dp))
            // v0.29.0: the pill gets the room left over, and shrinks (then turns into icons) on narrow screens / big fonts.
            Box(Modifier.weight(1f)) { if (isPrivate) PrivatePill(onGhost) else Pill(mode, onMode, homeTab) }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onSearch).semantics { contentDescription = "Search" }, contentAlignment = Alignment.Center) { SearchGlyph(c.ink) }
            // v0.24.4: the router status dot is gone (no router dashboard); the ghost opens / leaves a private chat.
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (isPrivate) c.ink else Color.Transparent).clickable(onClick = onGhost)
                .semantics { contentDescription = if (isPrivate) "Leave private chat" else "Private chat" }, contentAlignment = Alignment.Center) {
                GhostGlyph(if (isPrivate) c.paper else c.ink, filled = isPrivate, eyes = c.ink)
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(34.dp).glass(CircleShape, GlassIntensity.High, shadow = false)
                .combinedClickable(onClick = onAvatar, onLongClick = onAvatarLong, onLongClickLabel = if (c.dark) "Switch to light theme" else "Switch to dark theme")
                .semantics { contentDescription = "Account menu. Long-press to switch light or dark" }, contentAlignment = Alignment.Center) {
                Text(avatar.take(1).uppercase().ifBlank { "B" }, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
            }
        }
        if (viewChip.isNotBlank()) Row(Modifier.fillMaxWidth().hairlineTop(c.hair).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
            Spacer(Modifier.width(8.dp))
            Label(viewChip.uppercase(), color = c.ink, size = 10.sp, modifier = Modifier.weight(1f))
            Row(Modifier.glass(RoundedCornerShape(16.dp), GlassIntensity.Low, shadow = false).clickable(onClick = onExit).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("✕  Exit", color = c.ink, size = 10.sp)
            }
        }
    }
}

/**
 * Chat | Projects | Home. It only switches tabs (v0.24.0: no conversation list behind a tap or long-press on Chat).
 * v0.29.0: Home joins; on narrow widths (small phones, large fonts) the pill shrinks its padding, then shows icons only
 * (each still announced by name to TalkBack). Layout rule in [PillFit] (unit tested).
 */
@Composable
private fun Pill(mode: String, onMode: (String) -> Unit, homeTab: Boolean = true) {
    val c = LocalBlaze.current
    androidx.compose.foundation.layout.BoxWithConstraints {
        val font = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val fit = PillFit.pick(maxWidth.value, font)
        Row(Modifier.glass(RoundedCornerShape(20.dp), GlassIntensity.Low, shadow = false).padding(3.dp)) {
            PillFit.tabs.filter { homeTab || it.first != "home" }.forEach { (id, label) ->
                val on = mode == id
                // v0.27.0: the highlight fades across (MotionTokens).
                val fill by androidx.compose.animation.animateColorAsState(if (on) c.ink else Color.Transparent, MotionTokens.medium(), label = "tab")
                val ink by androidx.compose.animation.animateColorAsState(if (on) c.paper else c.sub, MotionTokens.medium(), label = "tabText")
                Box(Modifier.clip(RoundedCornerShape(17.dp)).background(fill)
                    .clickable(onClickLabel = "Switch to $label") { onMode(id) }
                    .semantics { selected = on; if (fit.icons) contentDescription = label }
                    .heightIn(min = 34.dp).padding(horizontal = fit.padDp.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                    if (fit.icons) androidx.compose.material3.Icon(PillFit.icon(id), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                    else Text(label, maxLines = 1, softWrap = false,
                        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = fit.fontSp.sp, color = ink))
                }
            }
        }
    }
}

/** v0.29.0: how the Chat | Projects | Home pill fits the room it has (dp) at the phone's font scale. */
object PillFit {
    val tabs = listOf("chat" to "Chat", "projects" to "Projects", "home" to "Home")
    data class Fit(val padDp: Int, val fontSp: Float, val icons: Boolean)

    /** Text width estimate: ~0.6 em per character of "ChatProjectsHome" (16 chars) + padding per tab + the 6 dp frame. */
    fun need(padDp: Int, fontSp: Float, fontScale: Float): Float = 16 * fontSp * fontScale * 0.6f + 3 * 2 * padDp + 6

    fun pick(width: Float, fontScale: Float): Fit = when {
        need(14, 13f, fontScale) <= width -> Fit(14, 13f, false)
        need(9, 12.5f, fontScale) <= width -> Fit(9, 12.5f, false)
        need(6, 12f, fontScale) <= width -> Fit(6, 12f, false)
        else -> Fit(12, 13f, true)
    }

    fun icon(id: String): androidx.compose.ui.graphics.vector.ImageVector = when (id) {
        "projects" -> androidx.compose.material.icons.Icons.Rounded.Folder
        "home" -> androidx.compose.material.icons.Icons.Rounded.Home
        else -> androidx.compose.material.icons.Icons.Rounded.ChatBubbleOutline
    }
}

/** v0.24.4: shown instead of Chat | Projects while a private chat is open. Tapping it offers to leave. */
@Composable
private fun PrivatePill(onLeave: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(c.ink).clickable(onClickLabel = "Leave private chat", onClick = onLeave)
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        GhostGlyph(c.paper, filled = true, size = 14.dp, eyes = c.ink)
        Spacer(Modifier.width(6.dp))
        Text("Private", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.paper))
    }
}

/** v0.24.4: the private-chat ghost: a rounded head, wavy hem, two eyes. */
@Composable
fun GhostGlyph(color: Color, filled: Boolean = false, size: androidx.compose.ui.unit.Dp = 20.dp, eyes: Color = Color.Black) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 20f
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(4f * u, 18f * u)
            lineTo(4f * u, 9f * u)
            cubicTo(4f * u, 4.6f * u, 6.9f * u, 2f * u, 10f * u, 2f * u)
            cubicTo(13.1f * u, 2f * u, 16f * u, 4.6f * u, 16f * u, 9f * u)
            lineTo(16f * u, 18f * u)
            quadraticBezierTo(14.6f * u, 16.2f * u, 13.3f * u, 18f * u)
            quadraticBezierTo(11.65f * u, 16.2f * u, 10f * u, 18f * u)
            quadraticBezierTo(8.35f * u, 16.2f * u, 6.7f * u, 18f * u)
            quadraticBezierTo(5.4f * u, 16.2f * u, 4f * u, 18f * u)
            close()
        }
        if (filled) drawPath(body, color) else drawPath(body, color, style = Stroke(1.6f * u, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        val eye = if (filled) eyes else color
        drawCircle(eye, 1.2f * u, Offset(8f * u, 9f * u)); drawCircle(eye, 1.2f * u, Offset(12f * u, 9f * u))
    }
}

@Composable
fun SearchGlyph(color: Color, size: androidx.compose.ui.unit.Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 10f
        drawCircle(color, 3.3f * u, Offset(4.2f * u, 4.2f * u), style = Stroke(1.4f * u))
        drawLine(color, Offset(6.7f * u, 6.7f * u), Offset(9.4f * u, 9.4f * u), strokeWidth = 1.6f * u)
    }
}

// ---------------------------------------------------------------- avatar menu

data class MenuItem(val id: String, val label: String, val sub: String = "")

/** Account, Settings, BlazeRouter, Theme, Check for updates, Sign out — dropped from the avatar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AvatarMenu(name: String, email: String, items: List<MenuItem>, onPick: (String) -> Unit, onLongPick: (String) -> Unit = {}, onDismiss: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
        Column(Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 10.dp).width(268.dp).background(c.menuSurface, RoundedCornerShape(16.dp)).glass(RoundedCornerShape(16.dp), GlassIntensity.High)
            .clickable(remember { MutableInteractionSource() }, null) { }) {
            Column(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(name.ifBlank { "Not signed in" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = c.ink))
                if (email.isNotBlank()) Text(email, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
            items.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(BlazeShapes.small).combinedClickable(onClick = { onPick(m.id) }, onLongClick = { onLongPick(m.id) })
                    .padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = if (m.id == "signout") c.accent else c.ink))
                    if (m.sub.isNotBlank()) Label(m.sub, color = c.sub, size = 9.sp)
                }
            }
        }
    }
}

/** v0.45.7: the veil's gradient: even ink at the top, fading to nothing over the bottom [FEATHER] of the chat. */
object SheetVeil {
    const val ALPHA = 0.10f
    const val FEATHER = 0.30f
    fun stops(ink: Color): Array<Pair<Float, Color>> = arrayOf(0f to ink.copy(alpha = ALPHA), (1f - FEATHER) to ink.copy(alpha = ALPHA), 1f to ink.copy(alpha = 0f))
}

/** A light ink veil over the chat while a bottom sheet is open; tap it to close the sheet. */
@Composable
fun BoxScope.SheetScrim(onDismiss: () -> Unit) {
    val c = LocalBlaze.current
    // v0.27.0: fades in with the sheet.
    // v0.45.7: feathered at the bottom (no hard grey band ending above the Halo arc / + sheet), and lighter.
    Box(Modifier.matchParentSize().appearIn(true).background(androidx.compose.ui.graphics.Brush.verticalGradient(*SheetVeil.stops(c.ink)))
        .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
}

// ---------------------------------------------------------------- /views picker

/** The /views picker: filter box + numbered rows. Opens above the composer. */
@Composable
fun ViewsSheet(badges: Map<String, String>, onPick: (BlazeView) -> Unit, onDismiss: () -> Unit, autoFocus: Boolean = true) {
    val c = LocalBlaze.current
    var q by remember { mutableStateOf("") }
    // v0.39.0: Simple mode lists no technical views (their /commands and open_view still open them).
    val simple by com.blaze.assistant.agent.SimpleMode.simpleFlow.collectAsState()
    val rows = remember(q, simple) { Views.filter(q).filter { com.blaze.assistant.agent.SimpleMode.viewVisible(it.id, simple) } }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }
    // v0.24.8: a rounded sheet with a pill search field and rounded rows (no square top rule, no row numbers).
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), GlassIntensity.High, shadow = false)
        .background(GlassTokens.bg.copy(alpha = if (c.dark) 0.6f else 0f)).padding(top = 10.dp)) {
        Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(BlazeShapes.pill).background(c.field).border(1.dp, c.fieldLine, BlazeShapes.pill)
            .padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchGlyph(c.sub, 14.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (q.isEmpty()) Text("Search views", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.weak))
                BasicTextField(q, { q = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { rows.firstOrNull()?.let(onPick) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
            }
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDismiss).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) {
                Text("\u00d7", style = TextStyle(fontFamily = Type.Sans, fontSize = 18.sp, color = c.sub))
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
            rows.forEachIndexed { i, v ->
                Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).background(if (i == 0 && q.isNotEmpty()) c.faint else Color.Transparent)
                    .clickable { onPick(v) }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.name, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                        Text(v.desc, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                    }
                    badges[v.id]?.takeIf { it.isNotBlank() }?.let { Label(it, color = c.accent, size = 9.sp) }
                }
            }
            if (rows.isEmpty()) Text("No view matches.", modifier = Modifier.padding(16.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
    }
}

// ---------------------------------------------------------------- search

/**
 * Search (v0.24.0): views by name, and a one-tap "ask Blaze" that searches every past conversation with
 * search_chat (there is no chat list to browse any more).
 */
@Composable
fun SearchScreen(onView: (BlazeView) -> Unit, onAsk: (String) -> Unit, onDismiss: () -> Unit, initial: String = "", autoFocus: Boolean = true) {
    val c = LocalBlaze.current
    var q by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }
    val views = remember(q) { if (q.isBlank()) Views.all.take(6) else Views.filter(q).take(8) }
    // v0.45.8: Search shows the picked theme (it covers the chat, so it draws the scene itself).
    ThemedScreen(Modifier.fillMaxSize(), own = { background(c.paper).glassBackground(c.dark) }) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // v0.28.3: the shared round BackButton.
            BackButton(onDismiss)
            Spacer(Modifier.width(4.dp))
            Row(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(c.bubble).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SearchGlyph(c.sub, 15.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search views, or ask about past chats", maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
                    BasicTextField(q, { q = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink), modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Label("Past conversations", size = 9.sp, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp))
            if (q.isNotBlank()) SoftPill("Ask Blaze what you said about \u201c${q.trim().take(40)}\u201d", lead = "↳") {
                onAsk("Search our past conversations for \u201c${q.trim()}\u201d and tell me what we said.")
            } else Text("Type a word and Blaze looks through every conversation you've had, old ones included.",
                modifier = Modifier.padding(start = 4.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
            Label(if (q.isBlank()) "Views" else "Matching views", size = 9.sp, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 6.dp))
            views.forEach { v ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onView(v) }.padding(horizontal = 4.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent)); Spacer(Modifier.width(12.dp))
                    Text(v.name, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                    Spacer(Modifier.width(10.dp))
                    Text(v.desc, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                }
            }
            if (views.isEmpty()) Text("No view matches.", modifier = Modifier.padding(4.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
    }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ChatRow(ch: ChatMeta, busy: Boolean, current: Boolean = false, chip: String = "", onMore: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().glass(GlassTokens.tileShape, if (current) GlassIntensity.High else GlassIntensity.Low, shadow = false)
        .combinedClickable(onClick = onClick, onLongClick = onMore).padding(start = 14.dp, end = if (onMore != null) 4.dp else 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ch.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink))
            // v0.28.3: a preview saved before the leak fix can hold raw tool syntax ("[github_open repo=…]"); never show it.
            val preview = remember(ch.preview) { com.blaze.assistant.agent.ToolLeak.forDisplay(ch.preview) }
            if (preview.isNotBlank()) Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        }
        // Pinned mark sits on the right so every title lines up (v0.15.0).
        if (ch.pinned) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent)); Spacer(Modifier.width(8.dp)) }
        if (busy) Label("Working", color = c.accent, size = 9.sp)
        else if (chip.isNotBlank()) Label(chip, color = c.sub, size = 9.sp)
        if (onMore != null) Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Chat options", onClick = onMore)
            .semantics { contentDescription = "Options for ${ch.title.ifBlank { "this chat" }}" }, contentAlignment = Alignment.Center) {
            MoreDots(c.sub)
        }
    }
}

/** v0.28.3: three round dots (the ⋯ menu). */
@Composable
fun MoreDots(color: Color, size: androidx.compose.ui.unit.Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.width / 11f
        listOf(0.2f, 0.5f, 0.8f).forEach { x -> drawCircle(color, r, Offset(this.size.width * x, this.size.height / 2)) }
    }
}

/** v0.28.3: rename a chat (project chats). */
@Composable
fun RenameChatDialog(current: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(current) }
    BlazeDialog(title = "Rename chat", confirm = "Save", onConfirm = { onDone(t.trim()) }, dismiss = "Cancel", onDismiss = onDismiss) {
        BlazeTextField(t, { t = it.take(80) }, "Chat name", modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onDone(t.trim()) }))
    }
}

/**
 * v0.28.3: the top of a project chat screen (inside Projects): back to the project page, the project's name over the
 * chat's title, and ⋯ for Rename / Delete / New chat. The thread under it is the same message list and composer as main Chat.
 */
@Composable
fun ProjectChatHeader(project: String, title: String, onBack: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit, onNewChat: () -> Unit,
                      canEdit: Boolean = true) {
    val c = LocalBlaze.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    BackTitle(title.ifBlank { "New chat" }, onBack = onBack, eyebrow = project, size = 18.sp,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).glass(RoundedCornerShape(26.dp), GlassIntensity.Low, shadow = false)) {
        Box {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Chat options") { menu = true }
                .semantics { contentDescription = "Chat options" }, contentAlignment = Alignment.Center) { MoreDots(c.ink) }
            if (menu) BlazeMenu(true, onDismiss = { menu = false }, items = buildList<Pair<String, () -> Unit>> {
                add("New chat in this project" to onNewChat)
                if (canEdit) { add("Rename" to { renaming = true }); add("Delete chat" to { deleting = true }) }
            })
        }
    }
    if (renaming) RenameChatDialog(title, onDone = { t -> renaming = false; if (t.isNotBlank()) onRename(t) }, onDismiss = { renaming = false })
    if (deleting) BlazeDialog(title = "Delete this chat?", body = "Its messages are removed from this phone. The project and its other chats stay.",
        confirm = "Delete", onConfirm = { deleting = false; onDelete() }, warm = true, dismiss = "Keep", onDismiss = { deleting = false })
}

// ---------------------------------------------------------------- the one live work card

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

/**
 * ONE live card per turn: "● Working · 4 steps · 1m 12s ›". Tap to see each step.
 * When done: "Worked · 13 steps · 3m 11s ›" and the answer follows as plain text.
 * [exact] (Settings › Show every step) adds each step's exact tool call under it.
 */
@Composable
fun WorkCard(steps: List<ChatMessage>, live: Boolean, startAt: Long, endAt: Long, status: String = "", exact: Boolean = false, now: Long? = null,
             meter: String = "", meterHigh: Boolean = false, open0: Boolean = false, simple: Boolean = false) {
    val c = LocalBlaze.current
    var open by rememberSaveable(steps.firstOrNull()?.id ?: -1L) { mutableStateOf(open0) }
    var tick by remember { mutableLongStateOf(now ?: System.currentTimeMillis()) }
    if (live && now == null) LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); delay(1000) } }
    val end = if (live) (now ?: tick) else endAt
    val n = steps.size
    val count = if (n == 1) "1 step" else "$n steps"
    val time = if (startAt > 0 && end >= startAt) " · " + formatDuration(end - startAt) else ""
    val pulse = if (live) rememberInfiniteTransition(label = "p").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value else 1f
    // v0.24.0: inline with the thread, no outline: a soft rounded strip on Blaze's side; a live one gets an accent wash.
    // v0.30.5: the same theme glass as Blaze's bubbles once done; a live one keeps its accent wash and edge.
    Column(Modifier.padding(end = 28.dp).widthIn(max = 340.dp).fillMaxWidth()
        // v0.31.0: a GlassCard-style tile; live = a slightly brighter frost.
        .then(if (live) Modifier.glass(RoundedCornerShape(18.dp), GlassIntensity.Medium, shadow = false)
            else Modifier.chatGlass(c, mine = false, shape = RoundedCornerShape(18.dp)))) {
        Row(Modifier.fillMaxWidth().clickable(enabled = n > 0) { open = !open }.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).alpha(pulse).clip(CircleShape).background(if (live) c.accent else c.weak))
            Spacer(Modifier.width(10.dp))
            // v0.39.0 Simple mode: the plain step line is the title ("Sending Jack the location…"), "Done" after; "Details" opens the steps.
            val plain = status.ifBlank { steps.lastOrNull()?.text.orEmpty() }.trimEnd('.', '\u2026')
            val title = if (simple) (if (live) (plain.ifBlank { "Working on it" } + "\u2026") else "Done") else (if (live) "Working" else "Worked") + (if (n > 0) " · $count" else "") + time
            Text(title, modifier = Modifier.weight(1f), maxLines = if (simple) 2 else 1,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = if (simple) 15.sp else 13.sp, color = if (live) c.ink else c.sub))
            if (simple && n > 0) Text(if (open) "Hide" else "Details", modifier = Modifier.padding(start = 8.dp),
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.sub))
            else if (n > 0) {
                val rot by animateFloatAsState(if (open) 90f else 0f, label = "chev")
                Text("›", modifier = Modifier.rotate(rot), style = TextStyle(fontFamily = Type.Display, fontSize = 18.sp, color = c.sub))
            }
        }
        val line = status.ifBlank { steps.lastOrNull()?.text.orEmpty() }
        if (live && !open && line.isNotBlank() && !simple) Box(Modifier.fillMaxWidth().padding(start = 29.dp, end = 12.dp, bottom = 10.dp)) {
            Shimmer(line)
        }
        // Token meter (v0.15.0): what the last model call sent and got back, on its own line so the title never clips.
        if (meter.isNotBlank()) Label(meter, color = if (meterHigh) c.accent else c.weak, size = 9.sp, maxLines = 1,
            modifier = Modifier.padding(start = 29.dp, end = 12.dp, bottom = 10.dp))
        AnimatedVisibility(open, enter = MotionTokens.expand(), exit = MotionTokens.collapse()) {
            Column(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp)) {
                steps.forEachIndexed { i, s ->
                    val running = live && i == n - 1
                    val nextAt = steps.getOrNull(i + 1)?.at ?: if (live) end else endAt
                    val dur = if (s.at > 0 && nextAt >= s.at) formatDuration(nextAt - s.at) else ""
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        // Desktop's step marks: filled blue when done, a pulsing blue outline while running.
                        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).let { m ->
                            if (running) m.alpha(pulse).border(1.5.dp, c.accent, CircleShape) else m.background(c.accent.copy(alpha = 0.85f)) })
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(Md.inline(s.text, c.accent, c.hair.copy(alpha = 0.35f)), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = if (running) c.sub else c.body))
                            if (exact && s.meta.isNotBlank()) Text(s.meta, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.sub))
                        }
                        if (dur.isNotEmpty() && !running) {
                            Spacer(Modifier.width(8.dp))
                            Text(dur, maxLines = 1, style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.weak))
                        }
                    }
                }
            }
        }
    }
}

/** v0.41.0 Simple mode: the whole work view while Blaze works, one small quiet line ("Looking at your films…"). */
@Composable
fun SimpleWorkHint(text: String) {
    val c = LocalBlaze.current
    val pulse = rememberInfiniteTransition(label = "h").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a").value
    Row(Modifier.padding(start = 4.dp, end = 28.dp, top = 2.dp, bottom = 2.dp).semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).alpha(pulse).clip(CircleShape).background(c.weak))
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
    }
}

// ---------------------------------------------------------------- composer

/** What the + menu offers. [id] is handled by the screen. */
data class PlusItem(val id: String, val label: String, val sub: String = "", val on: Boolean = false)

fun plusItems(work: String, approval: String, model: String, hasClipImage: Boolean, inProject: Boolean = false, hasCamera: Boolean = true): List<PlusItem> = buildList {
    // v0.28.x: camera entries only when the phone really has a camera (PackageManager.FEATURE_CAMERA_ANY).
    if (hasCamera) {
        add(PlusItem("camera", "Camera", "Take a photo"))
        add(PlusItem("blazecam", "Blaze Camera", "Ask, read text, translate, scan"))
    }
    add(PlusItem("photos", "Photos", "Pick pictures"))
    add(PlusItem("files", "Files", "Photos, PDFs, text"))
    add(PlusItem("atfile", "@ files", "Mention a file from Blaze's home"))
    if (hasClipImage) add(PlusItem("paste", "Paste image", "From the clipboard"))
    add(PlusItem("voice", "Voice", "Talk instead of typing"))
    add(PlusItem("call", "Call Blaze", "Hands-free voice call"))
    add(PlusItem("plan", if (work == "plan") "Plan mode" else "Build mode", if (work == "plan") "Read and look only · tap for Build" else "Full access · tap for Plan", on = work == "plan"))
    add(PlusItem("approval", if (approval == "autopilot") "Autopilot" else "Auto", if (approval == "autopilot") "Works on without asking · tap for Auto" else "Asks before risky steps · tap for Autopilot", on = approval == "autopilot"))
    // v0.25.0: no Model row; Blaze Cloud picks the model for every message.
    add(PlusItem("linux", "Desktop", "A real Linux desktop on this phone"))
    add(PlusItem("views", "Views", "Memory, jobs, design, accounts…"))
    // One mind: outside projects there is one conversation. v0.24.0: "Clear screen" (/clear) empties it, nothing is forgotten.
    if (inProject) add(PlusItem("new", "New chat", "A new thread in this project"))
    add(PlusItem("clear", "Clear screen", "Empty this chat · nothing is forgotten"))
}

@Composable
fun PlusMenu(items: List<PlusItem>, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), GlassIntensity.High, shadow = false)
        .background(GlassTokens.bg.copy(alpha = if (c.dark) 0.6f else 0f))
        .heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.hair))
        items.forEach { it ->
            Row(Modifier.fillMaxWidth().clickable { onPick(it.id) }.padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (it.on) c.accent else c.weak))
                Spacer(Modifier.width(14.dp))
                Text(it.label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                Spacer(Modifier.width(10.dp))
                Text(it.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
        }
    }
}

/** Mode chip text, only when it isn't the default (Build · Auto). */
fun modeChip(work: String, approval: String): String {
    val parts = buildList { if (work == "plan") add("PLAN"); if (approval == "autopilot") add("AUTOPILOT") }
    return parts.joinToString(" · ")
}

/**
 * v0.24.1: Luke's reference composer. A fully rounded pill, a fill a step off the background with a thin outline,
 * + on the left, the mic on the right that turns into a round send arrow once there's text (a stop square while
 * Blaze works). The light-grey placeholder rotates through [hints] (on-device suggestions, ComposerHints) every ~3 s
 * with a gentle fade and slide; it stops while the field is focused or has text, and with reduced motion.
 */
@Composable
fun Composer(value: TextFieldValue, onValue: (TextFieldValue) -> Unit, busy: Boolean, canSend: Boolean, plusOpen: Boolean,
             chip: String, agents: Int, listening: Boolean, speaking: Boolean,
             onPlus: () -> Unit, onMic: () -> Unit, onSend: () -> Unit, onStop: () -> Unit, onChip: () -> Unit = {},
             hints: List<String> = emptyList(), animateHints: Boolean = true, hintIndex0: Int = 0) {
    val c = LocalBlaze.current
    var focused by remember { mutableStateOf(false) }
    var hintIdx by remember(hints) { mutableStateOf(if (hints.isEmpty()) 0 else hintIndex0.mod(hints.size)) }
    val empty = value.text.isEmpty()
    LaunchedEffect(hints, focused, empty, animateHints, listening) {
        if (!animateHints || focused || !empty || listening || hints.size < 2) return@LaunchedEffect
        while (true) { delay(com.blaze.assistant.agent.ComposerHints.ROTATE_MS); hintIdx = (hintIdx + 1) % hints.size }
    }
    val placeholder = when {
        listening -> "Listening…"
        hints.isEmpty() -> com.blaze.assistant.agent.ComposerHints.DEFAULT
        else -> hints[hintIdx.coerceIn(0, hints.size - 1)]
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
        if (chip.isNotBlank() || agents > 0) Row(Modifier.padding(start = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (chip.isNotBlank()) Box(Modifier.clip(RoundedCornerShape(12.dp)).background(c.accent).clickable(onClick = onChip).padding(horizontal = 10.dp, vertical = 4.dp)) {
                ButtonText(chip, color = c.onAccent, size = 12.sp)
            }
            if (agents > 0) Label((if (chip.isNotBlank()) "   " else "") + "$agents AGENT" + (if (agents > 1) "S" else "") + " WORKING", color = c.accent, size = 9.sp)
        }
        // v0.31.0: the composer is a frosted glass pill (rim + sheen + soft shadow), a rounded card once it grows.
        val pill = RoundedCornerShape(percent = 50)
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .glass(if (value.text.contains('\n') || value.text.length > 60) RoundedCornerShape(27.dp) else pill, GlassIntensity.Medium)
            .padding(horizontal = 5.dp, vertical = 5.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (plusOpen) c.ink else Color.Transparent).clickable(onClick = onPlus)
                .semantics { contentDescription = if (plusOpen) "Close menu" else "Attach and tools" }, contentAlignment = Alignment.Center) {
                PlusIcon(if (plusOpen) c.paper else c.ink, open = plusOpen)
            }
            Box(Modifier.weight(1f).padding(start = 4.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
                if (empty) androidx.compose.animation.AnimatedContent(targetState = placeholder, label = "hint",
                    transitionSpec = {
                        (fadeIn(tween(420)) + androidx.compose.animation.slideInVertically(tween(420)) { it / 3 }) togetherWith
                            (fadeOut(tween(300)) + androidx.compose.animation.slideOutVertically(tween(300)) { -it / 3 })
                    }) { t ->
                    Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, color = c.sub))
                }
                BasicTextField(value = value, onValueChange = onValue,
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, lineHeight = 22.sp, color = c.ink),
                    cursorBrush = SolidColor(c.accent), maxLines = 6,
                    // v0.28.1: Enter is a new line; only the send button sends (ComposerKeys).
                    keyboardOptions = ComposerKeys.Message,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; if (it.isFocused) com.blaze.assistant.agent.LlmClient.prewarm() /* v0.45.3: warm the connection as soon as the box is tapped */ }
                        .semantics { contentDescription = "Message Blaze" })
            }
            // v0.30.6 rapid messages: with text in the box, Send wins even while Blaze works (the message joins the turn); Stop otherwise.
            when {
                canSend -> Box(Modifier.size(44.dp).glassPrimary(CircleShape).clickable(onClick = onSend)
                    .semantics { contentDescription = "Send" }, contentAlignment = Alignment.Center) { SendMark(GlassTokens.onPrimary(c.dark), stop = false) }
                busy -> Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent).clickable(onClick = onStop)
                    .semantics { contentDescription = "Stop" }, contentAlignment = Alignment.Center) { SendMark(c.onAccent, stop = true) }
                else -> {
                    val micOn = listening || speaking
                    Box(Modifier.size(44.dp).clip(CircleShape).background(if (micOn) c.accent else Color.Transparent).clickable(onClick = onMic)
                        .semantics { contentDescription = if (speaking) "Stop speaking" else if (listening) "Stop listening" else "Talk" }, contentAlignment = Alignment.Center) {
                        if (speaking) SendMark(c.onAccent, stop = true) else MicIcon(if (micOn) c.onAccent else c.ink)
                    }
                }
            }
        }
    }
}

/** The composer pill's fill: a step off the paper (lighter in dark mode, like the reference), and its thin outline. */
val BlazeColors.field: Color get() = if (dark) Color(0x14FFFFFF) else Color(0xFFFFFFFF)
val BlazeColors.fieldLine: Color get() = if (dark) Color(0x2EFFFFFF) else Color(0x1A111112)

/** A thin "+" (× when the menu is open). */
@Composable
fun PlusIcon(color: Color, open: Boolean = false, size: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(Modifier.size(size).rotate(if (open) 45f else 0f)) {
        val w = this.size.width; val sw = 1.9.dp.toPx()
        drawLine(color, Offset(w / 2, w * 0.1f), Offset(w / 2, w * 0.9f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(w * 0.1f, w / 2), Offset(w * 0.9f, w / 2), sw, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** An outline microphone: capsule, cradle and stem. */
@Composable
fun MicIcon(color: Color, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val sw = 1.7.dp.toPx()
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawRoundRect(color, Offset(w * 0.35f, w * 0.06f), Size(w * 0.30f, w * 0.52f), androidx.compose.ui.geometry.CornerRadius(w * 0.15f), style = Stroke(sw))
        drawArc(color, 0f, 180f, false, Offset(w * 0.2f, w * 0.22f), Size(w * 0.6f, w * 0.52f), style = Stroke(sw, cap = cap))
        drawLine(color, Offset(w / 2, w * 0.74f), Offset(w / 2, w * 0.92f), sw, cap)
    }
}

@Composable
fun MicMark(color: Color, speaking: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        val u = size.width / 10f
        // v0.24.8: rounded mic (was square blocks).
        if (speaking) { drawRoundRect(color, Offset(3 * u, 3 * u), Size(4 * u, 4 * u), androidx.compose.ui.geometry.CornerRadius(1.2f * u)); return@Canvas }
        drawRoundRect(color, Offset(3.5f * u, 0f), Size(3 * u, 6 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u))
        drawArc(color, 0f, 180f, false, Offset(1.8f * u, 1.5f * u), Size(6.4f * u, 6.4f * u), style = Stroke(1.2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        drawLine(color, Offset(5f * u, 7.9f * u), Offset(5f * u, 9.4f * u), 1.2f * u, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(3.4f * u, 9.5f * u), Offset(6.6f * u, 9.5f * u), 1f * u, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

// ---------------------------------------------------------------- projects

@Composable
fun ProjectsPane(projects: List<Project>, chats: List<ChatMeta>, busy: Set<String>, needsYou: Set<String>, openId: String,
                 onOpen: (String) -> Unit, onBack: () -> Unit, onCreate: (String) -> Unit, onChat: (String) -> Unit,
                 onNewChat: (String) -> Unit, onInstructions: (String, String) -> Unit, onDelete: (String) -> Unit,
                 onBrief: (String, String) -> Unit = { _, _ -> },
                 /** v0.28.3: rename / delete one of the project's chats. */
                 onRenameChat: (String, String) -> Unit = { _, _ -> }, onDeleteChat: (String) -> Unit = {}) {
    val c = LocalBlaze.current
    val open = projects.firstOrNull { it.id == openId }
    // v0.45.8: no ground of its own with a theme: the chat screen's theme scene shows through (was flat dark).
    Column(Modifier.fillMaxSize().screenGround { background(c.paper).glassBackground(c.dark) }) {
        if (open == null) {
            var name by remember { mutableStateOf("") }
            // v0.24.8: rounded everything: a filled pill field, a gradient pill button, projects as separate rounded cards.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Projects", modifier = Modifier.weight(1f),
                        style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.02).em, color = c.ink))
                    // v0.45.8: the shared theme switcher.
                    ThemeSwitch()
                }
                Spacer(Modifier.height(6.dp))
                Text("Group chats around one goal, with instructions Blaze follows in every chat inside it.", modifier = Modifier.padding(horizontal = 20.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.sub))
                Spacer(Modifier.height(16.dp))
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BlazeTextField(name, { name = it }, "New project name", modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) { onCreate(name); name = "" } }))
                    Spacer(Modifier.width(8.dp))
                    BlazeButton("Create", lead = "+", height = 46.dp) { onCreate(name); name = "" }
                }
                Spacer(Modifier.height(18.dp))
                projects.forEach { p ->
                    val working = p.chats.any { it in busy }
                    val needs = p.chats.any { it in needsYou }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().glass(GlassTokens.tileShape, GlassIntensity.Medium, shadow = false).clickable { onOpen(p.id) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ProjectBadge(p.name)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = c.ink))
                            // v0.28.3: count the chats that exist (an id whose file is gone isn't a chat).
                            val n = p.chats.count { id -> chats.any { it.id == id } }
                            Text(if (n == 1) "1 chat" else "$n chats", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                        }
                        when {
                            needs -> StatusChip("Needs you", c.accent, filled = true)
                            working -> StatusChip("Working", c.accent, filled = false)
                            else -> Text("›", style = TextStyle(fontFamily = Type.Display, fontSize = 20.sp, color = c.weak))
                        }
                    }
                }
                if (projects.isEmpty()) Box(Modifier.padding(16.dp)) { Rows { EmptyState("No projects yet", "Name one above to start. Each project keeps its own chats, instructions and brief.") } }
            }
        } else {
            var ins by remember(open.id) { mutableStateOf(open.instructions) }
            var brief by remember(open.id, open.brief) { mutableStateOf(open.brief) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                // v0.28.3: the shared round BackButton (was a thin "←" glyph); back goes to the projects list.
                BackTitle(open.name, onBack = onBack, modifier = Modifier.padding(top = 4.dp), size = 22.sp)
                BlazeButton("New chat in this project", lead = "+", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(), height = 46.dp) { onNewChat(open.id) }
                Label("Instructions", size = 9.sp, modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 6.dp))
                BlazeTextField(ins, { ins = it; onInstructions(open.id, it) }, "What Blaze should know in every chat here",
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), singleLine = false, minHeight = 72.dp)
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("Brief", size = 9.sp, modifier = Modifier.weight(1f))
                    // v0.28.5: the real count (BlazeDb.tokens) against the hard cap; typing or pasting past it is cut at the cap.
                    val P = com.blaze.assistant.agent.Projects
                    val used = P.tokens(brief)
                    Label("$used / ${P.BRIEF_TOKENS} tokens", color = if (used >= P.BRIEF_TOKENS * 9 / 10) c.accent else c.weak, size = 9.sp)
                }
                var cut by remember(open.id) { mutableStateOf(false) }
                BlazeTextField(brief, { t -> val fit = com.blaze.assistant.agent.Projects.cutTokens(t, com.blaze.assistant.agent.Projects.BRIEF_TOKENS)
                        cut = fit.length < t.length; brief = fit; onBrief(open.id, fit) },
                    "Pinned brief: purpose and rules first, then key files and what's in progress. Blaze keeps it up to date.",
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), singleLine = false, minHeight = 96.dp)
                if (cut) Text("The brief is at its 600-token cap, so the rest was cut. Put longer details in Instructions: Blaze searches them when it needs them.",
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.accent), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
                Label("Chats", size = 9.sp, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
                val list = open.chats.mapNotNull { id -> chats.firstOrNull { it.id == id } }
                // v0.28.3: each chat opens inside Projects; ⋯ (or a long-press) renames or deletes it.
                var menuFor by remember { mutableStateOf("") }
                var renaming by remember { mutableStateOf<ChatMeta?>(null) }
                var deleting by remember { mutableStateOf<ChatMeta?>(null) }
                list.forEach { ch ->
                    Box {
                        ChatRow(ch, ch.id in busy, chip = if (ch.id in needsYou) "Needs you" else "", onMore = { menuFor = ch.id }) { onChat(ch.id) }
                        if (menuFor == ch.id) BlazeMenu(true, onDismiss = { menuFor = "" },
                            items = listOf("Rename" to { renaming = ch }, "Delete chat" to { deleting = ch }))
                    }
                }
                renaming?.let { ch -> RenameChatDialog(ch.title, onDone = { t -> renaming = null; if (t.isNotBlank()) onRenameChat(ch.id, t) }, onDismiss = { renaming = null }) }
                deleting?.let { ch -> BlazeDialog(title = "Delete \u201c${ch.title.ifBlank { "this chat" }.take(40)}\u201d?",
                    body = "Its messages are removed from this phone. The project, its instructions and its other chats stay.",
                    confirm = "Delete", onConfirm = { deleting = null; onDeleteChat(ch.id) }, warm = true, dismiss = "Keep", onDismiss = { deleting = null }) }
                if (list.isEmpty()) Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Rows { EmptyState("No chats yet", "Start one with + New chat. It keeps this project's instructions and brief.", mark = false) } }
                BlazeButton("Delete project (chats stay)", kind = BtnKind.DANGER, modifier = Modifier.padding(20.dp), height = 40.dp) { onDelete(open.id) }
            }
        }
    }
}

@Composable
fun StatusChip(text: String, color: Color, filled: Boolean) {
    val c = LocalBlaze.current
    Box(Modifier.clip(BlazeShapes.pill).background(if (filled) color else Color.Transparent, BlazeShapes.pill).hairline(color, BlazeShapes.pill).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text.sentence(), maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = if (filled) c.paper else color))
    }
}

/** v0.24.8: a project's round initial badge (soft classic-blue wash) instead of the old "01 / 02" numbers. */
@Composable
fun ProjectBadge(name: String) {
    val c = LocalBlaze.current
    Box(Modifier.size(38.dp).glass(CircleShape, GlassIntensity.High, shadow = false), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifBlank { "#" }, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.ink))
    }
}

/** v0.28.3: an empty project chat (just made with + New chat): what this thread is, above the composer. */
@Composable
fun ProjectChatEmpty(project: String, hasInstructions: Boolean) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        ProjectBadge(project)
        Spacer(Modifier.height(14.dp))
        Text("New chat in ${project.ifBlank { "this project" }}", maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        Spacer(Modifier.height(6.dp))
        Text(if (hasInstructions) "Blaze follows this project's instructions and brief here. Your main chat stays as it is."
            else "This thread stays in the project. Your main chat stays as it is.",
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
    }
}
