package com.blaze.assistant.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.FixCards
import com.blaze.assistant.agent.SimpleHome
import com.blaze.assistant.agent.UndoCenter
import kotlinx.coroutines.delay
import java.util.Calendar

/*
 * v0.39.0 Simple mode pieces for the chat, drawn with the app's own look (glass, greys, the Duo colours): the home
 * greeting and starters, the @blaze / @halo / @all row, the big hold-to-talk mic, reply chips under Blaze's
 * questions, the one-button fix card and the 10-second Undo bar. Layout and wording follow the Simple mockups.
 */

/** "Good afternoon, Sam" and two starters that fit the hour. */
@Composable
fun SimpleWelcome(name: String, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text(SimpleHome.greeting(hour, name), style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 34.sp,
            lineHeight = 38.sp, letterSpacing = (-0.03).em, color = c.ink))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SimpleHome.starters(hour).forEach { s -> SimpleChip(s.label, onClick = { onPick(s.prompt) }) }
        }
        Spacer(Modifier.height(18.dp))
        Text("Hold the mic and talk, or tap Type. I'll check with you before anything that matters.",
            style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 22.sp, color = c.sub))
    }
}

/** A large rounded chip (starters, reply chips). [primary] = the filled one. */
@Composable
fun SimpleChip(text: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalBlaze.current
    val shape = RoundedCornerShape(26.dp)
    Box(modifier.heightIn(min = 48.dp).clip(shape)
        .let { if (primary) it.glassPrimary(shape) else it.glass(shape, GlassIntensity.Low, shadow = false) }
        .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp,
            color = if (primary) GlassTokens.onPrimary(c.dark) else c.ink))
    }
}

/** Yes / No / Later / Do it for me under a question from Blaze. */
@Composable
fun ReplyChipRow(chips: List<SimpleHome.Chip>, onPick: (String) -> Unit) {
    if (chips.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEachIndexed { i, ch -> SimpleChip(ch.label, primary = i == 0) { onPick(ch.prompt) } }
    }
}

/**
 * Type · the big hold-to-talk mic · Photo. Hold the mic to talk (the phone's own speech recogniser, as before),
 * let go to send. A quick tap also starts listening. While Blaze works the mic becomes Stop.
 */
@Composable
fun HoldToTalkBar(listening: Boolean, busy: Boolean, onHold: () -> Unit, onRelease: () -> Unit, onType: () -> Unit, onPhoto: () -> Unit, onStop: () -> Unit) {
    val c = LocalBlaze.current
    val level by com.blaze.assistant.voice.Voice.level.collectAsState()
    val grow by animateFloatAsState(if (listening) 1.06f + level * 0.12f else 1f, tween(160), label = "mic")
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        SideButton(Icons.Rounded.Keyboard, "Type", onType)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(104.dp).scale(grow).clip(CircleShape).background(DuoColors.blazeBlue.copy(alpha = if (listening) 0.28f else 0.14f)))
                Box(Modifier.size(84.dp).clip(CircleShape).background(if (busy) c.ink else DuoColors.blazeBlue)
                    .semantics { contentDescription = if (busy) "Stop Blaze" else "Hold to talk" }
                    .pointerInput(busy) {
                        if (busy) detectTapGestures(onTap = { onStop() })
                        else detectTapGestures(onPress = {
                            onHold()
                            val t0 = System.currentTimeMillis()
                            val released = tryAwaitRelease()
                            // A long hold sends when you let go; a quick tap keeps listening until you stop talking.
                            if (released && System.currentTimeMillis() - t0 > 600) onRelease()
                        })
                    }, contentAlignment = Alignment.Center) {
                    Icon(if (busy) Icons.Rounded.Stop else Icons.Rounded.Mic, null, tint = if (busy) c.paper else Color.White, modifier = Modifier.size(38.dp))
                }
            }
            Text(when { busy -> "Tap to stop"; listening -> "Listening\u2026 let go to send"; else -> "Hold to talk" },
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.ink))
        }
        SideButton(Icons.Rounded.Add, "Photo", onPhoto)
    }
}

@Composable
private fun SideButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).glass(CircleShape, GlassIntensity.Low, shadow = false).clickable(onClick = onClick)
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.ink, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
    }
}

/** The intent that opens the system screen for a fix. */
fun fixIntent(ctx: android.content.Context, k: FixCards.Kind): Intent {
    val pkg = Uri.parse("package:" + ctx.packageName)
    return when (k) {
        FixCards.Kind.PHONE_CONTROL -> try { com.blaze.assistant.phone.PhoneControl.fixIntent(ctx) } catch (_: Throwable) { Intent(k.action) }
        FixCards.Kind.OVERLAY, FixCards.Kind.APP_PERMISSION -> Intent(k.action, pkg)
        else -> Intent(k.action)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** "I couldn't read WhatsApp · Turn on notification access? … [Turn it on] [Not now]". */
@Composable
fun FixCard() {
    val fix by FixCards.current.collectAsState()
    val f = fix ?: return
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    GlassCard(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp).appearIn(true), intensity = GlassIntensity.High) {
        Text(f.headline, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        Spacer(Modifier.height(4.dp))
        Text(f.kind.title, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp, color = c.ink))
        Spacer(Modifier.height(4.dp))
        Text(f.kind.body, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.body))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SimpleChip("Turn it on", primary = true) {
                FixCards.current.value = null
                try { ctx.startActivity(fixIntent(ctx, f.kind)) }
                catch (_: Exception) { try { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } }
            }
            SimpleChip("Not now") { FixCards.current.value = null }
        }
    }
}

/** "Table booked · You have 8 seconds to undo [Undo]" with a countdown ring. */
@Composable
fun BoxScope.UndoCountdownBar(onUndone: (String) -> Unit) {
    val offer by UndoCenter.current.collectAsState()
    val o = offer ?: return
    val c = LocalBlaze.current
    var now by remember(o.n) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(o.n) {
        while (true) { now = System.currentTimeMillis(); if (UndoCenter.expired(o, now)) { if (UndoCenter.current.value?.n == o.n) UndoCenter.dismiss(); break }; delay(200) }
    }
    val left = UndoCenter.secondsLeft(o, now)
    val frac = ((o.at + UndoCenter.WINDOW_MS - now).coerceAtLeast(0).toFloat() / UndoCenter.WINDOW_MS)
    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).swipeAway { UndoCenter.dismiss() }
        .clip(RoundedCornerShape(24.dp)).background(c.ink).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(38.dp)) {
                drawCircle(c.paper.copy(alpha = 0.25f), style = Stroke(3.dp.toPx()))
                drawArc(c.paper, -90f, 360f * frac, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round), topLeft = Offset.Zero, size = Size(size.width, size.height))
            }
            Text("$left", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.paper))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(o.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.paper))
            Text("You have $left seconds to undo", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.paper.copy(alpha = 0.7f)))
        }
        Box(Modifier.clip(RoundedCornerShape(22.dp)).background(c.paper).clickable { UndoCenter.undoNow()?.let(onUndone) }
            .semantics { contentDescription = "Undo" }.padding(horizontal = 18.dp, vertical = 11.dp)) {
            Text("Undo", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink))
        }
    }
}

/** v0.39.0: asks the chat screen to show Home (open_view view=home, /home). */
internal val homeRequests = kotlinx.coroutines.flow.MutableStateFlow(0)

/**
 * v0.39.0 Swipe navigation: Home (the existing Home cards) with a grab handle at the bottom, raised above the system
 * navigation / gesture area by the nav-bar inset plus [com.blaze.assistant.agent.SwipeNav.HANDLE_MARGIN_DP]. Drag
 * the handle up (or tap it) and Chat slides up over Home, following the finger.
 */
@Composable
fun HomeSurface(progress: Float, onAsk: (String) -> Unit, onQuote: (String) -> Unit, onOpen: () -> Unit,
                drag: androidx.compose.foundation.gestures.DraggableState,
                onDragStart: suspend kotlinx.coroutines.CoroutineScope.(Offset) -> Unit,
                onDragStop: suspend kotlinx.coroutines.CoroutineScope.(Float) -> Unit) {
    val c = LocalBlaze.current
    // v0.45.8: the strip sits on Home's own ground (HomePane bottom slot), so the scene behind Home never shows through
    // under "Swipe up to chat" (on a dark Home, a light ink scene used to); with a theme, Home and the strip both show it.
    HomePane(onAsk = onAsk, onQuote = onQuote, edgeToEdge = true, bottom = {
        val hc = LocalBlaze.current   // Home's own ink (Home may draw dark glass on a light app theme)
        Column(Modifier.fillMaxWidth()
            .draggable(drag, androidx.compose.foundation.gestures.Orientation.Vertical, onDragStarted = onDragStart, onDragStopped = onDragStop)
            .clickable(onClick = onOpen).semantics { contentDescription = "Swipe up to chat" }
            .navigationBarsPaddingCompat().padding(top = 10.dp, bottom = com.blaze.assistant.agent.SwipeNav.HANDLE_MARGIN_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Swipe up to chat", style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = hc.sub.copy(alpha = (1f - progress * 2f).coerceIn(0f, 1f))))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.width(134.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(hc.ink.copy(alpha = 0.85f)))
        }
    })
}

private fun Modifier.statusBarsPaddingCompat(): Modifier = this.statusBarsPadding()
private fun Modifier.navigationBarsPaddingCompat(): Modifier = this.navigationBarsPadding()
