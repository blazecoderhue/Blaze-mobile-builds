package com.blaze.assistant.phone

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.browser.PointerTile
import com.blaze.assistant.browser.PointerTiles
import com.blaze.assistant.phone.MagicHandLogic.Action
import com.blaze.assistant.phone.MagicHandLogic.Kind
import com.blaze.assistant.phone.MagicHandState.DockButton
import com.blaze.assistant.phone.MagicHandState.GrabAction
import com.blaze.assistant.phone.MagicHandState.Panel
import com.blaze.assistant.ui.EmptyState
import com.blaze.assistant.ui.Glass
import com.blaze.assistant.ui.GlassIconButton
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MascotGlyph
import com.blaze.assistant.ui.MotionTokens
import com.blaze.assistant.ui.pressScale
import com.blaze.assistant.ui.shimmer
import com.blaze.assistant.ui.Md
import com.blaze.assistant.ui.PageHead
import com.blaze.assistant.ui.raised
import com.blaze.assistant.ui.Type

/**
 * Magic Hand's dock (v0.24.7): one rounded dark Blaze card at the bottom of the screen with the mascot, a status
 * line, the menu (the v0.24.6 pointer tiles, through MagicHandMenu's mapper), the answer, the confirm card, grabbed
 * text, the teach review, and Close. Everything renders from [MagicHandState.Ui], so ShotsTest draws it as is.
 */
class DockCalls(
    val onButton: (DockButton) -> Unit = {},
    val onMenu: (Action) -> Unit = {},
    val onConfirm: (Boolean) -> Unit = {},
    val onGrab: (GrabAction) -> Unit = {},
    val onBack: () -> Unit = {},
    val onTeachName: (String) -> Unit = {},
    val onTeachUndo: () -> Unit = {},
    val onTeachSave: () -> Unit = {},
    val onTeachDiscard: () -> Unit = {},
    val onTeachStart: () -> Unit = {},
    /** v0.27.0: the grab box goes back to its previous shape. */
    val onGrabUndo: () -> Unit = {},
    /** v0.27.0: drop this grab and draw a new one (Grab mode on). */
    val onGrabAgain: () -> Unit = {},
)

object MagicHandIcons {
    fun of(a: Action): ImageVector = when (a) {
        Action.ASK -> Icons.Rounded.AutoAwesome
        Action.COPY -> Icons.Rounded.ContentCopy
        Action.EXPLAIN -> Icons.Rounded.Lightbulb
        Action.TRANSLATE -> Icons.Rounded.Translate
        Action.SHARE -> Icons.Rounded.Share
        Action.REMIND -> Icons.Rounded.Notifications
        Action.SEARCH -> Icons.Rounded.Search
        Action.OPEN_LINK -> Icons.Rounded.OpenInBrowser
        Action.COPY_LINK -> Icons.Rounded.Link
        Action.SAVE_IMAGE -> Icons.Rounded.SaveAlt
        Action.DESCRIBE -> Icons.Rounded.Image
        Action.CARRY, Action.DROP -> Icons.Rounded.PanTool
        Action.WATCH -> Icons.Rounded.Visibility
        Action.DROP_IN_CHAT -> Icons.Rounded.Forum
    }
    fun of(g: GrabAction): ImageVector = when (g) {
        GrabAction.COPY -> Icons.Rounded.ContentCopy
        GrabAction.TRANSLATE -> Icons.Rounded.Translate
        GrabAction.SEARCH -> Icons.Rounded.Search
        GrabAction.SAVE -> Icons.Rounded.SaveAlt
        GrabAction.CARRY -> Icons.Rounded.PanTool
    }
    fun of(k: Kind): ImageVector = when (k) {
        Kind.LINK -> Icons.Rounded.Link
        Kind.IMAGE -> Icons.Rounded.Image
        Kind.VALUE -> Icons.Rounded.Visibility
        else -> Icons.Rounded.TextFields
    }
}

private val sans = TextStyle(fontFamily = Type.Sans, color = Glass.text)

/**
 * v0.26.0: the text selection menu for the dock. The dock is a bare accessibility overlay window (no Activity, no
 * DecorView), so Android's own floating Copy toolbar can't open there (View.startActionMode returns null) and
 * selecting part of the grabbed text left no way to copy it. This one is drawn inside the card instead.
 */
class DockToolbar : TextToolbar {
    var copy by mutableStateOf<(() -> Unit)?>(null)
        private set
    var selectAll by mutableStateOf<(() -> Unit)?>(null)
        private set
    override var status: TextToolbarStatus by mutableStateOf(TextToolbarStatus.Hidden)
        private set
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                          onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        copy = onCopyRequested; selectAll = onSelectAllRequested; status = TextToolbarStatus.Shown
    }
    override fun hide() { copy = null; selectAll = null; status = TextToolbarStatus.Hidden }
}

@Composable
fun MagicHandDock(u: MagicHandState.Ui, calls: DockCalls, widthDp: Float = 340f) {
    val bar = remember { DockToolbar() }
    CompositionLocalProvider(LocalTextToolbar provides bar) { DockCard(u, calls, widthDp, bar) }
}

/** "Copy · Select all" for selected text in the dock (shown while some text is selected). */
@Composable
private fun SelectionBar(bar: DockToolbar) {
    if (bar.status != TextToolbarStatus.Shown) return
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.End) {
        bar.selectAll?.let { f -> SmallPill("Select all", false) { f() }; Spacer(Modifier.width(6.dp)) }
        bar.copy?.let { f -> SmallPill("Copy", true) { f(); bar.hide() } }
    }
}

@Composable
private fun SmallPill(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(Modifier.pressScale(press).height(32.dp).clip(Glass.pill).background(if (!enabled) Glass.raised else if (primary) Glass.blue else Glass.raised2)
        .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, onClickLabel = text, onClick = onClick)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = sans.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (enabled) Color.White else Glass.weak))
    }
}

/** v0.27.0: the dock's visible Grab toggle (blue while Grab mode is on). */
@Composable
private fun GrabToggle(on: Boolean, withLabel: Boolean = true, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(Modifier.pressScale(press).height(36.dp).clip(Glass.pill).background(if (on) Glass.blue else Glass.raised)
        .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current,
            onClickLabel = if (on) "Stop grabbing" else DockButton.GRAB.desc, onClick = onClick)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Gesture, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        if (withLabel) {
            Spacer(Modifier.width(5.dp))
            Text("Grab", style = sans.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White))
        }
    }
}

@Composable
private fun DockCard(u: MagicHandState.Ui, calls: DockCalls, widthDp: Float, bar: DockToolbar) {
    val card = RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)
        .shadow(20.dp, card, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(card).background(Glass.surface).border(1.dp, Glass.border, card)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            MascotGlyph(34.dp, pulse = u.panel == Panel.ASKING)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (u.mode == MagicHandLogic.Mode.TEACH) "Teaching" else "Magic Hand", maxLines = 1, softWrap = false,
                        style = sans.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
                    if (u.private) { Spacer(Modifier.width(6.dp)); Chip("Private", Glass.raised2, Glass.sub) }
                    if (u.carrying) { Spacer(Modifier.width(6.dp)); Chip("\u270b " + MagicHandLogic.preview(u.carried, 16), Glass.orange.copy(alpha = 0.18f), Glass.orange) }
                }
                Text(MagicHandState.statusLine(u), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = sans.copy(fontSize = 12.sp, lineHeight = 16.sp, color = if (u.note.isNotBlank()) Glass.sub else Glass.cyan))
            }
            val buttons = MagicHandState.dockButtons(u)
            buttons.forEach { b ->
                Spacer(Modifier.width(4.dp))
                // With four buttons (carrying adds Drop in chat) the toggle drops its word so the title keeps one line.
                if (b == DockButton.GRAB) { GrabToggle(u.grabMode, withLabel = buttons.count { it != DockButton.CAMERA } <= 3) { calls.onButton(b) }; return@forEach }
                GlassIconButton(when (b) {
                    DockButton.GRAB -> Icons.Rounded.Gesture
                    DockButton.TEACH -> Icons.Rounded.School; DockButton.DONE -> Icons.Rounded.Check
                    DockButton.DROP_CHAT -> Icons.Rounded.Forum; DockButton.CAMERA -> Icons.Rounded.PhotoCamera; DockButton.CLOSE -> Icons.Rounded.Close
                }, b.desc, size = 36.dp, bg = if (b == DockButton.DONE) Glass.blue else Glass.raised) { calls.onButton(b) }
            }
        }
        // v0.27.0: panels cross-fade and the card resizes smoothly (instant with Remove animations on).
        // v0.28.1: the grab card appears at once (no fade, no resize animation): it must be up < 150 ms after release.
        val instant = u.panel == Panel.GRAB
        AnimatedContent(u.panel, modifier = Modifier.fillMaxWidth().animateContentSize(if (instant) androidx.compose.animation.core.snap<androidx.compose.ui.unit.IntSize>() else MotionTokens.medium<androidx.compose.ui.unit.IntSize>()),
            transitionSpec = {
                if (targetState == Panel.GRAB || initialState == Panel.GRAB) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                else MotionTokens.fade() togetherWith MotionTokens.fadeAway()
            }, label = "dock-panel") { panel ->
        Column(Modifier.fillMaxWidth()) {
        when (panel) {
            Panel.STATUS -> Unit
            Panel.MENU -> Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                MagicHandMenuCard(u, widthDp - 36f, calls.onMenu)
            }
            Panel.ASKING -> Line("Asking Blaze about \u201c${MagicHandLogic.preview(u.question.ifBlank { u.pointing }, 40)}\u201d\u2026")
            Panel.ANSWER -> {
                Box(Modifier.padding(horizontal = 16.dp)) { SelectionBar(bar) }
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SelectionContainer {
                        Text(Md.render(u.answer, Glass.cyan, Glass.raised2), style = sans.copy(fontSize = 14.5.sp, lineHeight = 21.sp))
                    }
                }
                Buttons(listOf("Done" to true)) { calls.onBack() }
            }
            Panel.CONFIRM -> u.confirm?.let { c ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(Glass.raised).padding(12.dp)) {
                    Text(c.line, style = sans.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
                    Spacer(Modifier.height(4.dp))
                    Text("Magic Hand never acts without your yes.", style = sans.copy(fontSize = 12.sp, color = Glass.sub))
                }
                Buttons(listOf(c.no to false, c.yes to true)) { calls.onConfirm(it == 1) }
            }
            Panel.GRAB -> GrabCard(u, calls, bar)
            Panel.TEACH_INTRO -> {
                Text(u.intro, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = sans.copy(fontSize = 14.sp, lineHeight = 20.sp))
                Buttons(listOf("Cancel" to false, "Start" to true)) { if (it == 1) calls.onTeachStart() else calls.onBack() }
            }
            Panel.TEACH -> {
                if (u.teachSteps.isEmpty()) Line("No steps yet.")
                else Column(Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    u.teachSteps.takeLast(6).forEachIndexed { i, s -> StepLine(u.teachSteps.size - minOf(6, u.teachSteps.size) + i + 1, s) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                    Pill("Undo last step", false, enabled = u.teachSteps.isNotEmpty()) { calls.onTeachUndo() }
                    Spacer(Modifier.width(8.dp))
                    Pill("Cancel", false) { calls.onTeachDiscard() }
                    Spacer(Modifier.width(8.dp))
                    Pill("Done", true) { calls.onButton(DockButton.DONE) }
                }
            }
            Panel.TEACH_REVIEW -> TeachReviewCard(u, calls)
        }
        }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun MagicHandMenuCard(u: MagicHandState.Ui, widthDp: Float, onPick: (Action) -> Unit) =
    PointerTiles(MagicHandIcons.of(u.kind), MagicHandLogic.preview(u.pointing.ifBlank { "This part of the screen" }, 40),
        u.menu.map { a -> PointerTile(a.label, a.label, MagicHandIcons.of(a), a == Action.ASK || a == Action.DROP) { onPick(a) } }, widthDp)

@Composable
private fun GrabCard(u: MagicHandState.Ui, calls: DockCalls, bar: DockToolbar = DockToolbar()) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(Glass.raised).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Grabbed" + if (u.grabSource.isNotBlank()) " \u00b7 ${u.grabSource}" else "", modifier = Modifier.weight(1f),
                style = sans.copy(fontSize = 11.5.sp, color = Glass.orange, fontWeight = FontWeight.Medium))
            if (u.grabBusy) Text(u.queued?.let { "${it.label} when ready" } ?: "Reading\u2026", style = sans.copy(fontSize = 11.5.sp, color = Glass.sub))
        }
        // v0.27.0: a short coach mark on the first few grabs (the old long hint after that is gone).
        if (u.coach) {
            Spacer(Modifier.height(4.dp))
            Text("\u2196 " + MagicHandState.COACH, style = sans.copy(fontSize = 11.5.sp, color = Color.White, fontWeight = FontWeight.Medium),
                modifier = Modifier.clip(Glass.pill).background(Glass.blue.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 3.dp))
        }
        Spacer(Modifier.height(6.dp))
        SelectionBar(bar)
        val scroll = rememberScrollState()
        // A new read scrolls back to its first line, so the result is always in view.
        LaunchedEffect(u.grabbed) { if (scroll.value != 0) scroll.animateScrollTo(0, MotionTokens.medium()) }
        Column(Modifier.heightIn(max = 120.dp).verticalScroll(scroll)) {
            // v0.27.0: shimmer lines while the new area is read, instead of a bare "Reading…".
            if (u.grabbed.isBlank() && u.grabBusy) Column(Modifier.padding(top = 2.dp)) {
                listOf(1f, 0.82f, 0.6f).forEach { f ->
                    Box(Modifier.fillMaxWidth(f).height(12.dp).shimmer(Glass.raised2, Color.White.copy(alpha = 0.12f), Glass.pill))
                    Spacer(Modifier.height(8.dp))
                }
            }
            else if (u.grabbed.isBlank()) Text(MagicHandState.GRAB_EMPTY, style = sans.copy(fontSize = 14.sp, lineHeight = 20.sp, color = Glass.sub))
            // A new key per text, so an old selection never points into replaced text after the box moves.
            else androidx.compose.runtime.key(u.grabbed) { SelectionContainer { Text(u.grabbed, style = sans.copy(fontSize = 14.sp, lineHeight = 20.sp)) } }
        }
    }
    // v0.28.1: usable while reading (a tap waits for the text, shown as "Copy when ready").
    val enabled = MagicHandState.grabActionsEnabled(u)
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        MagicHandState.grabActions(u.private).forEach { g ->
            val press = remember { MutableInteractionSource() }
            Column(Modifier.pressScale(press).clip(RoundedCornerShape(16.dp))
                .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, onClickLabel = g.label) { calls.onGrab(g) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(if (!enabled) Glass.raised else if (g == GrabAction.COPY || g == u.queued) Glass.blue else Glass.raised2), contentAlignment = Alignment.Center) {
                    Icon(MagicHandIcons.of(g), contentDescription = null, tint = if (enabled) Color.White else Glass.weak, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(g.label, style = sans.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (enabled) Glass.text else Glass.weak))
            }
        }
    }
    // v0.27.0: fix a wrong grab in one tap.
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        SmallPill("Undo", false, enabled = u.canUndo && !u.grabBusy) { calls.onGrabUndo() }
        Spacer(Modifier.width(6.dp))
        SmallPill("Grab again", false) { calls.onGrabAgain() }
    }
}

@Composable
private fun TeachReviewCard(u: MagicHandState.Ui, calls: DockCalls) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(Glass.raised).padding(12.dp)) {
        Text("Save these ${u.teachSteps.size} steps as a workflow?", style = sans.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().clip(Glass.pill).background(Glass.raised2).padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (u.teachName.isEmpty()) Text("Name it", style = sans.copy(fontSize = 14.sp, color = Glass.weak))
            BasicTextField(u.teachName, calls.onTeachName, singleLine = true, textStyle = sans.copy(fontSize = 14.sp),
                cursorBrush = SolidColor(Glass.blue), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
            u.teachSteps.forEachIndexed { i, s -> StepLine(i + 1, s) }
        }
        Spacer(Modifier.height(4.dp))
        Text("Saved as not tested yet. Blaze follows it once with you before trusting it.", style = sans.copy(fontSize = 11.5.sp, color = Glass.sub))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(Icons.Rounded.Delete, "Discard", size = 40.dp, bg = Glass.raised2) { calls.onTeachDiscard() }
        Spacer(Modifier.width(6.dp))
        GlassIconButton(Icons.Rounded.Undo, "Remove the last step", size = 40.dp, bg = Glass.raised2, enabled = u.teachSteps.isNotEmpty()) { calls.onTeachUndo() }
        Spacer(Modifier.weight(1f))
        Pill("Save workflow", true, enabled = u.teachSteps.isNotEmpty()) { calls.onTeachSave() }
    }
}

@Composable
private fun StepLine(n: Int, s: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Glass.raised2), contentAlignment = Alignment.Center) {
            Text("$n", style = sans.copy(fontSize = 10.5.sp, color = Glass.orange))
        }
        Spacer(Modifier.width(8.dp))
        Text(s, maxLines = 2, overflow = TextOverflow.Ellipsis, style = sans.copy(fontSize = 12.5.sp, lineHeight = 17.sp))
    }
}

@Composable
private fun Line(s: String) = Text(s, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = sans.copy(fontSize = 13.sp, color = Glass.sub))

@Composable
private fun Chip(s: String, bg: Color, fg: Color) =
    Text(s, maxLines = 1, modifier = Modifier.clip(Glass.pill).background(bg).padding(horizontal = 8.dp, vertical = 2.dp), style = sans.copy(fontSize = 10.5.sp, color = fg))

@Composable
private fun Pill(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(Modifier.pressScale(press).height(40.dp).clip(Glass.pill).background(if (!enabled) Glass.raised else if (primary) Glass.blue else Glass.raised2)
        .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, onClick = onClick)
        .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(text, style = sans.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = if (enabled) Color.White else Glass.weak))
    }
}

@Composable
private fun Buttons(labels: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
        labels.forEachIndexed { i, (l, primary) ->
            if (i > 0) Spacer(Modifier.width(8.dp))
            Pill(l, primary) { onPick(i) }
        }
    }
}

/** The hand itself: Blaze's mascot with a ring that lights orange while something is under its tip. */
@Composable
fun MagicHandGlyph(sizeDp: Int, lit: Boolean, carrying: Boolean) {
    Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        if (lit || carrying) Box(Modifier.size((sizeDp - 6).dp).clip(CircleShape).border(2.dp, if (carrying) Glass.orange else Glass.blue, CircleShape))
        MascotGlyph((sizeDp - 10).dp, pulse = false)
    }
}

/** Settings › Advanced › Watches: what Magic Hand is watching, newest first, each removable. */
@Composable
fun MagicHandWatchesPage(preview: List<MagicHandWatch.Watch>? = null, now: Long = System.currentTimeMillis()) {
    val ctx = LocalContext.current
    val live by MagicHandWatches.list.collectAsState()
    if (preview == null) androidx.compose.runtime.LaunchedEffect(Unit) { MagicHandWatches.load(ctx) }
    val list = (preview ?: live).sortedByDescending { it.created }
    val c = LocalBlaze.current
    PageHead("Magic Hand", "Watches", "Values you held the hand on. Blaze re-reads each one about once an hour while its app is open and tells you when it changes.")
    if (list.isEmpty()) EmptyState("Nothing watched yet", "Summon Magic Hand from the assistant panel, then hold it on a price, score or status and tap Watch.")
    else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised)) {
        list.forEachIndexed { i, w ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.label.ifBlank { w.value }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                    Text(MagicHandWatchText.sub(w, now), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, color = c.sub))
                }
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Stop watching") { MagicHandWatches.remove(ctx, w.id) },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Stop watching ${w.label}", tint = c.sub, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Plain lines for the watches page (pure, tested). */
object MagicHandWatchText {
    fun ago(ms: Long): String = when {
        ms < 60_000 -> "just now"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        ms < 86_400_000 -> "${ms / 3_600_000} h ago"
        else -> "${ms / 86_400_000} d ago"
    }
    fun sub(w: MagicHandWatch.Watch, now: Long): String = buildString {
        append(w.app.ifBlank { w.pkg }).append(" \u00b7 ").append(w.value)
        if (w.previous.isNotBlank()) append(" (was ").append(w.previous).append(")")
        append(" \u00b7 ").append(if (w.lastChecked > 0) "checked " + ago(now - w.lastChecked) else "not checked yet")
    }
}
