package com.blaze.assistant.ui

import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.blaze.assistant.browser.BrowserEngine
import com.blaze.assistant.browser.BrowserLive
import com.blaze.assistant.browser.BrowserTools

/**
 * v0.30.5: while Blaze works in its background browser, Home shows what it's doing in a fixed glass window (the same
 * window as Home's web pages, [TiltGlassPane]). Preview only: the page never gets a tap or a key from here. Tapping
 * the page or Watch opens the full browser (BrowserTools.show); × hides the window until the next task.
 *
 * The page is Blaze's own tab WebView, borrowed while the window is up: it keeps the phone-screen size Blaze browses
 * at (so nothing re-lays out under Blaze) and is only scaled down to the window's width. The full browser takes it
 * back whenever it opens; the window gives it back (unscaled, offscreen) when it closes.
 */
@Composable
fun BrowserLivePreview(onHide: () -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val step by BrowserLive.step.collectAsState()
    val tab = BrowserEngine.tab()
    // The background browser's page size (BrowserEngine.layoutOffscreen uses the same numbers).
    val dm = ctx.applicationContext.resources.displayMetrics
    val vw = dm.widthPixels.coerceAtLeast(360); val vh = dm.heightPixels.coerceAtLeast(640)
    val ratio = vh.toFloat() / vw
    var boxW by remember { mutableIntStateOf(0) }
    val scale = if (boxW > 0) boxW.toFloat() / vw else 0f
    BackHandler { onHide() }

    TiltGlassPane(
        paneSize = { mw, mh -> previewSize(mw, mh, ratio) },
        capture = { GlassPaneLook.capture(BrowserEngine.tab()?.web?.takeIf { it.isAttachedToWindow }, scale.coerceIn(0.1f, 1f)) },
        onBackdropTap = { },
        header = {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(step?.what?.ifBlank { null } ?: "Blaze is browsing", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = c.ink))
                Text(tab?.url?.let { hostOf(it) }?.ifBlank { null } ?: "Blaze's browser", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.sub))
            }
            Spacer(Modifier.width(6.dp))
            GlassButton(Icons.Rounded.Visibility, "Watch in the browser") { BrowserTools.show(ctx) }
            Spacer(Modifier.width(6.dp))
            GlassButton(Icons.Rounded.Close, "Hide", strong = true, onClick = onHide)
        },
        screen = { showLive ->
            val radius = with(d) { GlassPaneLook.SCREEN_RADIUS_DP.dp.toPx() }
            val stepAt = step?.at ?: 0L   // a new step re-runs update: takes the page back after the full browser closes
            AndroidView(factory = { vctx ->
                FrameLayout(vctx).apply {
                    setBackgroundColor(android.graphics.Color.WHITE)
                    GlassPaneLook.roundCorners(this, radius)
                    // Preview only: nothing reaches the page from here.
                    isClickable = false
                }
            }, update = { f ->
                f.visibility = if (showLive) View.VISIBLE else View.INVISIBLE
                if (stepAt >= 0L) hostPage(f, BrowserEngine.tab()?.web, vw, vh, scale)
            }, onRelease = { f -> giveBack(f) }, modifier = Modifier.fillMaxSize().onSizeChanged { boxW = it.width })
            if (tab == null) Text("Starting the browser\u2026", modifier = Modifier.align(Alignment.Center).padding(12.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = LightColors.sub))
            // Over the page, so taps and drags never reach it; a tap opens the full browser to watch or take over.
            Box(Modifier.matchParentSize().semantics { contentDescription = "Blaze's browser, live. Tap to watch." }
                .clickable(onClickLabel = "Watch in the browser") { BrowserTools.show(ctx) })
        })
}

/** A phone-shaped window: the whole page Blaze sees, scaled down, no taller than 80% of Home. */
private fun previewSize(mw: Dp, mh: Dp, ratio: Float): DpSize {
    val chrome = 66.dp   // title bar + rims
    val rims = (GlassPaneLook.RIM_DP * 2).dp
    var sw = minOf(mw * 0.62f, 300.dp)
    val maxH = mh * 0.8f
    if (sw * ratio + chrome > maxH) sw = ((maxH - chrome) / ratio).coerceAtLeast(120.dp)
    return DpSize(sw + rims, sw * ratio + chrome)
}

/** Puts Blaze's tab page in the window at its own size, scaled to fit. Never takes it from the full browser. */
private fun hostPage(f: FrameLayout, w: WebView?, vw: Int, vh: Int, scale: Float) {
    // An old tab (switched or closed) goes back offscreen.
    for (i in f.childCount - 1 downTo 0) {
        val v = f.getChildAt(i)
        if (v !== w) { f.removeViewAt(i); if (v is WebView) { v.scaleX = 1f; v.scaleY = 1f; BrowserEngine.layoutOffscreen(v) } }
    }
    if (w == null || scale <= 0f) return
    if (w.parent == null) f.addView(w, FrameLayout.LayoutParams(vw, vh))
    if (w.parent === f) { w.pivotX = 0f; w.pivotY = 0f; w.scaleX = scale; w.scaleY = scale }
}

/** The window closed: the page goes back to the background browser, full size, offscreen. */
private fun giveBack(f: FrameLayout) {
    for (i in f.childCount - 1 downTo 0) {
        val v = f.getChildAt(i)
        f.removeViewAt(i)
        if (v is WebView) { v.scaleX = 1f; v.scaleY = 1f; BrowserEngine.layoutOffscreen(v) }
    }
}

private fun hostOf(u: String): String = try { android.net.Uri.parse(u).host.orEmpty().removePrefix("www.") } catch (_: Exception) { "" }
