package com.blaze.assistant.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.Type
import kotlin.math.abs

/** Colours for the two sides of the grid: normal tabs follow Blaze's theme, private tabs are always dark, like Chrome's Incognito. */
private data class GridColors(val bg: Color, val card: Color, val ink: Color, val sub: Color, val accent: Color, val bar: Color)

/**
 * Chrome-style tab switcher: a two-column grid of rounded cards (favicon, title, close, page preview),
 * a normal / private switch at the top, swipe a card sideways to close it, tap to open it.
 */
@Composable
fun TabGrid(startPrivate: Boolean, onDone: () -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    var priv by remember { mutableStateOf(startPrivate) }
    val g = if (priv) GridColors(Color(0xFF202124), Color(0xFF35363A), Color(0xFFE8EAED), Color(0xFF9AA0A6), Color(0xFF8AB4F8), Color(0xFF202124))
        else GridColors(if (c.dark) Color(0xFF1E1E22) else Color(0xFFF1F3F4), c.paper, c.ink, c.sub, c.accent, if (c.dark) Color(0xFF1E1E22) else Color(0xFFF1F3F4))
    val list = BrowserEngine.tabs.filter { it.isPrivate == priv }
    val cur = BrowserEngine.tab()
    val grid = rememberLazyGridState()
    LaunchedEffect(priv) { list.indexOf(cur).takeIf { it > 0 }?.let { grid.scrollToItem(it) } }

    Column(Modifier.fillMaxSize().background(g.bg)) {
        // Top bar: new tab · [ tabs | private ] · close all / done
        Row(Modifier.fillMaxWidth().background(g.bar).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { BrowserEngine.newTab(ctx, isPrivate = priv); onDone() }, contentAlignment = Alignment.Center) {
                T("+", g.ink, 26)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(22.dp)).background(if (priv) Color(0xFF35363A) else g.card).padding(3.dp)) {
                Seg(selected = !priv, g = g, onClick = { priv = false }) {
                    Box(Modifier.size(20.dp).border(1.5.dp, if (!priv) g.accent else g.sub, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                        T("${BrowserEngine.normalTabs().size.coerceAtMost(99)}", if (!priv) g.accent else g.sub, 11, weight = FontWeight.Bold)
                    }
                }
                Seg(selected = priv, g = g, onClick = { priv = true }) {
                    T("\uD83D\uDD76", if (priv) g.accent else g.sub, 16)   // dark glasses: private
                    if (BrowserEngine.privateTabs().isNotEmpty()) { Spacer(Modifier.width(4.dp)); T("${BrowserEngine.privateTabs().size}", if (priv) g.accent else g.sub, 12, weight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.weight(1f))
            var menu by remember { mutableStateOf(false) }
            Box {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) { T("⋮", g.ink, 22) }
                com.blaze.assistant.ui.BlazeMenu(menu, onDismiss = { menu = false }, items = listOf(
                    (if (priv) "Close all private tabs" else "Close all tabs") to {
                        BrowserEngine.closeAll(priv)
                        if (BrowserEngine.normalTabs().isEmpty()) BrowserEngine.newTab(ctx)
                        if (!priv) onDone()
                    },
                    (if (priv) "New private tab" else "New tab") to { BrowserEngine.newTab(ctx, isPrivate = priv); onDone() }))
            }
            Box(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).clickable {
                if (cur?.isPrivate != priv) list.lastOrNull()?.let { BrowserEngine.current = BrowserEngine.tabs.indexOf(it) }
                onDone()
            }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { T("Done", g.accent, 15, weight = FontWeight.Medium) }
        }

        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (priv) {
                    T("\uD83D\uDD76", g.ink, 48)
                    Spacer(Modifier.height(16.dp))
                    T("You've gone private", g.ink, 22, weight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    T("Private tabs don't save history, and their cookies and site data are wiped when you close the last one. " +
                        (if (BrowserVpn.maskLocation) "Your location is masked: sites can't read your GPS, time zone or real IP over WebRTC. " else "") +
                        (if (BrowserVpn.status == BrowserVpn.Status.ON) "You're browsing through the VPN." else "Turn on the VPN (⋯ › VPN & privacy) to hide your IP too."),
                        g.sub, 14, center = true)
                } else T("No open tabs", g.sub, 15)
                Spacer(Modifier.height(20.dp))
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(g.accent).clickable { BrowserEngine.newTab(ctx, isPrivate = priv); onDone() }
                    .padding(horizontal = 20.dp, vertical = 10.dp)) { T(if (priv) "New private tab" else "New tab", if (priv) Color(0xFF202124) else Color.White, 14, weight = FontWeight.Medium) }
            }
        } else {
            LazyVerticalGrid(GridCells.Fixed(2), state = grid, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.id }) { t ->
                    TabCard(t, selected = t === cur, g = g,
                        open = { BrowserEngine.current = BrowserEngine.tabs.indexOf(t); onDone() },
                        close = {
                            val i = BrowserEngine.tabs.indexOf(t)
                            if (i >= 0) BrowserEngine.close(i)
                            if (BrowserEngine.tabs.isEmpty()) BrowserEngine.newTab(ctx)
                        })
                }
            }
        }
    }
}

@Composable
private fun Seg(selected: Boolean, g: GridColors, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(19.dp)).background(if (selected) g.accent.copy(alpha = 0.18f) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun TabCard(t: BrowserEngine.Tab, selected: Boolean, g: GridColors, open: () -> Unit, close: () -> Unit) {
    var dx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(dx, label = "swipe")
    val widthPx = with(LocalDensity.current) { 170.dp.toPx() }
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth()
        .graphicsLayer { translationX = if (dragging) dx else shown; alpha = 1f - (abs(if (dragging) dx else shown) / (widthPx * 1.4f)).coerceIn(0f, 0.8f) }
        .pointerInput(t.id) {
            detectHorizontalDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = { dragging = false; if (abs(dx) > widthPx * 0.45f) close() else dx = 0f },
                onDragCancel = { dragging = false; dx = 0f },
                onHorizontalDrag = { ch, d -> ch.consume(); dx += d })
        }
        .clip(shape).background(g.card)
        .then(if (selected) Modifier.border(3.dp, g.accent, shape) else Modifier)
        .clickable(onClick = open)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = t.icon
            if (icon != null) Image(icon, null, Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)))
            else Box(Modifier.size(16.dp).clip(CircleShape).background(g.sub.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                T(t.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•", g.card, 9, weight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            T(t.title.ifBlank { if (t.isPrivate) "Private tab" else "New tab" }, g.ink, 13, modifier = Modifier.weight(1f), maxLines = 1)
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = close), contentAlignment = Alignment.Center) { T("✕", g.sub, 13) }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(0.78f).padding(start = 4.dp, end = 4.dp, bottom = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(if (t.isPrivate) Color(0xFF2A2B2E) else Color.White), contentAlignment = Alignment.Center) {
            val th = t.thumb
            val blank = t.url.isBlank() || t.url == "about:blank"
            when {
                th != null && !blank -> Image(th, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                blank -> T(if (t.isPrivate) "\uD83D\uDD76" else "Blaze", if (t.isPrivate) g.sub else Color(0xFF9AA0A6), if (t.isPrivate) 28 else 16, weight = FontWeight.Medium)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(10.dp)) {
                    val icon = t.icon
                    if (icon != null) Image(icon, null, Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    T(t.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/'), Color(0xFF5F6368), 11, maxLines = 2, center = true)
                }
            }
        }
    }
}

@Composable
private fun T(s: String, color: Color, size: Int, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, weight: FontWeight = FontWeight.Normal, center: Boolean = false) =
    androidx.compose.material3.Text(s, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Type.Sans, fontWeight = weight, fontSize = size.sp, color = color, textAlign = if (center) TextAlign.Center else TextAlign.Start))
