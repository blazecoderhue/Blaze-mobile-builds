package com.blaze.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** v0.45.2: whether the full-screen theme picker is up (profile menu › Themes, Settings › Look & feel). */
object ThemePickerState { val open = mutableStateOf(false) }

/**
 * v0.45.2 the theme picker: a full-screen 3D carousel of phone-shaped previews on a cylinder. The front card faces the
 * user, its neighbours turn away (graphicsLayer rotationY / cameraDistance / scale / alpha). Swipe either way (it
 * loops); the round arrows beside the front card are tinted with the accent of the theme they lead to. Tap the front
 * card to apply and close; tap a side card to bring it to the front; back closes without changing anything.
 * Only the front preview animates (side cards are drawn once), so it stays smooth on a low-end phone.
 */
@Composable
fun ThemePicker(onClose: () -> Unit) {
    val list = LivingThemes.PICKER
    val n = list.size
    val current by Look.theme.collectAsState()
    val pos = remember { Animatable(LivingThemes.indexOf(current).toFloat()) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val c = LocalBlaze.current
    BackHandler { onClose() }
    // The clock for the front card's preview only.
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        if (!MotionTokens.enabled) return@LaunchedEffect
        val start = withFrameMillis { it }; var last = 0L
        while (isActive) withFrameMillis { ms -> if (ms - last >= 33) { last = ms; t = (ms - start) / 1000f } }
    }
    fun go(target: Float) { scope.launch { pos.animateTo(target, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) } }
    val front = LivingThemes.frontOf(pos.value, n)
    fun apply(i: Int) {
        val th = list[LivingThemes.wrap(i, n)]
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        // v0.45.8: the one write path every switcher shares.
        AppTheme.pick(th.id)
        onClose()
    }
    Box(Modifier.fillMaxSize().zIndex(50f).background(Color(0xF2050608))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Themes", modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = Color.White))
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { onClose() }.semantics { contentDescription = "Close themes" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, null, tint = Color.White.copy(alpha = 0.8f))
                }
            }
            Text("Swipe to look, tap the front one to use it.", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f)))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val cardW = maxWidth * 0.56f
                val cardH = minOf(maxHeight * 0.82f, cardW * 2.05f)
                val density = LocalDensity.current
                val cardWpx = with(density) { cardW.toPx() }
                val cardHpx = with(density) { cardH.toPx() }
                // v0.45.3: the scene pictures of the front card and two either side are decoded ahead (no jank while swiping).
                val pctx = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(front, cardWpx.toInt()) {
                    ThemeSceneImages.preload(pctx, (-2..2).mapNotNull { d -> ThemeScenes.of(list[LivingThemes.wrap(front + d, n)].id, pickerWeather()) }, cardWpx.toInt(), cardHpx.toInt())
                }
                Box(Modifier.fillMaxSize().pointerInput(n) {
                    detectHorizontalDragGestures(
                        onDragEnd = { go(kotlin.math.round(pos.value)) },
                        onDragCancel = { go(kotlin.math.round(pos.value)) },
                    ) { ch, dx -> ch.consume(); scope.launch { pos.snapTo(pos.value - dx / cardWpx) } }
                }, contentAlignment = Alignment.Center) {
                    // Draw from the back to the front.
                    val order = (0 until n).map { it to LivingThemes.distance(it, pos.value, n) }.filter { kotlin.math.abs(it.second) < 2.4f }
                        .sortedByDescending { kotlin.math.abs(it.second) }
                    for ((i, d) in order) {
                        val pl = LivingThemes.place(d)
                        val isFront = kotlin.math.abs(d) < 0.5f
                        Box(Modifier.zIndex(10f - kotlin.math.abs(d)).width(cardW).height(cardH)
                            .graphicsLayer {
                                rotationY = pl.rotationY; cameraDistance = 14f * density.density
                                translationX = pl.shift * cardWpx * 1.05f; scaleX = pl.scale; scaleY = pl.scale; alpha = pl.alpha
                            }
                            .clip(RoundedCornerShape(30.dp))
                            .border(2.dp, if (isFront) Color(list[i].accent).copy(alpha = 0.9f) else Color.White.copy(alpha = 0.18f), RoundedCornerShape(30.dp))
                            .clickable { if (isFront) apply(i) else go(pos.value + d) }
                            .semantics { contentDescription = if (isFront) "${list[i].name}. Tap to use this theme" else "${list[i].name}. Tap to bring to the front" }) {
                            ThemePreview(list[i], if (isFront) t else 0f, dark = !list[i].light, reqW = cardWpx.toInt(), reqH = cardHpx.toInt())
                        }
                    }
                    // Round arrows beside the front card, each tinted with the accent of the theme it leads to.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        ArrowButton(left = true, tint = Color(LivingThemes.arrowTint(list, front, -1)), label = "Previous: ${LivingThemes.neighbour(list, front, -1).name}") { go(kotlin.math.round(pos.value) - 1f) }
                        ArrowButton(left = false, tint = Color(LivingThemes.arrowTint(list, front, 1)), label = "Next: ${LivingThemes.neighbour(list, front, 1).name}") { go(kotlin.math.round(pos.value) + 1f) }
                    }
                }
            }
            val th = list[front]
            Text(th.name, textAlign = TextAlign.Center, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = Color.White))
            Text(th.sub + if (th.id == current) " \u00b7 in use" else "", textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Color.White.copy(alpha = 0.65f)))
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ArrowButton(left: Boolean, tint: Color, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(tint.copy(alpha = 0.22f)).border(1.5.dp, tint, CircleShape)
        .clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(if (left) Icons.AutoMirrored.Rounded.KeyboardArrowLeft else Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = tint, modifier = Modifier.size(28.dp))
    }
}

/** A phone-shaped mini Home for a theme: its living background, the droplet in its halo, the welcome line and two glass chips. */
@Composable
fun ThemePreview(th: LivingThemes.Theme, t: Float, dark: Boolean, reqW: Int = 0, reqH: Int = 0) {
    val ink = if (dark) Color.White else Color(0xFF2A1A20)
    // v0.45.3: the theme's scene (the Blaze Site's picture), centre-cropped to the portrait card; Classic stays a gradient.
    val scene = rememberScene(ThemeScenes.of(th.id, pickerWeather()), reqW, reqH)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            if (th.motion == LivingThemes.Motion.NONE) {
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(th.ground.map { Color(it) }))
            } else {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val w = if (th.motion == LivingThemes.Motion.WEATHER) WeatherLook.look(LivingLogic.Weather.UNKNOWN, null, false, hour) else null
                drawTheme(th, t, 0f, dark, w, dense = false, scene = scene, scrim = ThemeScenes.scrim(dark, card = true),
                    scrimColor = if (dark) Color.Black else Color.White)
            }
            drawDropletHalo(th, Offset(size.width / 2f, size.height * 0.42f), size.width * 0.12f, t)
        }
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Text("Welcome\nto Blaze.", style = TextStyle(fontFamily = Type.Display, fontSize = 20.sp, lineHeight = 22.sp, color = ink))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Today", "Music").forEach { l ->
                    Text(l, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(ink.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 5.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 10.sp, color = ink))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** v0.45.3: the Weather card's scene in the picker (no live weather there: the time of day decides). */
internal fun pickerWeather(): WeatherLook.State =
    WeatherLook.state(LivingLogic.Weather.UNKNOWN, null, false, java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
