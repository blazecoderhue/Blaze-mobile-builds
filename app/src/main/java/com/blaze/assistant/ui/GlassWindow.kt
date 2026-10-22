package com.blaze.assistant.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * v0.29.3: a web page inside a floating pane of glass, like a small monitor sitting on the Home board. The page scrolls and
 * its links open inside the pane. Tapping outside does nothing (only the × closes it, or Back).
 *
 * v0.29.9: selecting words on the page shows the chat's selection pill (Copy, Quote, Select all, Search, Ask Blaze,
 * Translate, Define, Read aloud, Share).
 *
 * v0.30.5: back to the first window: a fixed size, no moving, no resizing ([TiltGlassPane]). The page is only for
 * scrolling; sliding on the glass frame or the backdrop around it tilts the pane slightly. The live page never sits in
 * the leaning layer (it came out black there) and the frame is near-opaque so the title reads over Home.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GlassWindow(startUrl: String, onClose: () -> Unit, onOpenOutside: (String) -> Unit,
                onAsk: ((String) -> Unit)? = null, onQuote: ((String) -> Unit)? = null) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val d = LocalDensity.current
    var title by remember { mutableStateOf(host(startUrl)) }
    var current by remember { mutableStateOf(startUrl) }
    var progress by remember { mutableStateOf(0) }
    var web by remember { mutableStateOf<PillWebView?>(null) }
    // v0.29.9: the page's text selection → Blaze's selection pill.
    var sel by remember { mutableStateOf<PageSelectMode?>(null) }
    var selTick by remember { mutableIntStateOf(0) }
    var selText by remember { mutableStateOf("") }
    var boxPos by remember { mutableStateOf(Offset.Zero) }
    var leaning by remember { mutableStateOf(false) }
    fun endSelection() { web?.clearSelection(); sel = null; selText = "" }
    BackHandler {
        val w = web
        when {
            sel != null -> endSelection()
            w != null && w.canGoBack() -> w.goBack()
            else -> onClose()
        }
    }

    TiltGlassPane(
        // As in v0.29.3: 94% of the width, 78% of the height, centred. Fixed.
        paneSize = { mw, mh -> DpSize(mw * 0.94f, mh * 0.78f) },
        capture = { GlassPaneLook.capture(web) },
        onBackdropTap = { if (sel != null) endSelection() /* else tapping outside does nothing */ },
        onPositioned = { boxPos = it },
        leaning = { on -> leaning = on; if (on && sel != null) endSelection() },
        header = {
            // Title bar: back, the site, open outside, ×.
            GlassButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { val w = web; if (w != null && w.canGoBack()) w.goBack() }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = c.ink))
                Text(host(current), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.sub))
            }
            Spacer(Modifier.width(6.dp))
            GlassButton(Icons.Rounded.OpenInNew, "Open in browser") { onOpenOutside(current) }
            Spacer(Modifier.width(6.dp))
            GlassButton(Icons.Rounded.Close, "Close", strong = true, onClick = onClose)
        },
        screen = { showLive ->
            val radius = with(d) { GlassPaneLook.SCREEN_RADIUS_DP.dp.toPx() }
            AndroidView(factory = { vctx ->
                PillWebView(vctx) { m -> sel = m; selTick++; if (m == null) selText = "" else web?.selectedText { t -> if (sel === m) selText = t } }.apply {
                    setBackgroundColor(android.graphics.Color.WHITE)
                    GlassPaneLook.roundCorners(this, radius)
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true; settings.useWideViewPort = true
                    settings.builtInZoomControls = true; settings.displayZoomControls = false
                    isVerticalScrollBarEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                            val u = req.url.toString()
                            return if (u.startsWith("http://") || u.startsWith("https://")) false else { onOpenOutside(u); true }
                        }
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { current = url; progress = 10 }
                        override fun onPageFinished(view: WebView, url: String) { current = url; progress = 100; view.title?.takeIf { it.isNotBlank() }?.let { title = it } }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, p: Int) { progress = p }
                        override fun onReceivedTitle(view: WebView, t: String?) { if (!t.isNullOrBlank()) title = t }
                    }
                    loadUrl(startUrl)
                    web = this
                }
            }, update = { v -> v.visibility = if (showLive) View.VISIBLE else View.INVISIBLE },
                onRelease = { it.clearSelection(); it.stopLoading(); it.destroy() }, modifier = Modifier.fillMaxSize())
            if (showLive && progress in 1..99) Box(Modifier.fillMaxWidth(progress / 100f).height(2.dp).background(c.accent))
        },
        above = {
            // v0.29.9: the chat's selection pill for words selected on the page.
            val m = sel
            if (m != null && !m.hidden && !leaning) {
                val w = web
                if (w != null && selTick >= 0) {   // selTick: redraw when the selection moves
                    val loc = IntArray(2).also { w.getLocationInWindow(it) }
                    val r = m.contentRect()
                    val rect = Rect(r.left + loc[0] - boxPos.x, r.top + loc[1] - boxPos.y, r.right + loc[0] - boxPos.x, r.bottom + loc[1] - boxPos.y)
                    val acts = if (selText.isNotBlank()) SelectionActions.forText(selText, allowQuote = onQuote != null, allowSelectAll = m.has(android.R.id.selectAll))
                        else SelectionActions.forUnknown(SelectionActions.likelyShort(rect.width / d.density, rect.height / d.density),
                            allowQuote = onQuote != null, allowSelectAll = m.has(android.R.id.selectAll))
                    SelectionPill(rect, acts, ink = Color(0xFF111114), paper = Color.White) { a ->
                        fun act(t0: String) {
                            val t = t0.trim()
                            endSelection()
                            if (t.isBlank()) return
                            when (SelectionActions.resolve(a, t)) {
                                SelectionActions.Action.QUOTE -> onQuote?.invoke(t)
                                // Search stays in the pane (Back returns to the page); a selected link opens there too.
                                SelectionActions.Action.SEARCH, SelectionActions.Action.OPEN_LINK -> web?.loadUrl(SelectionActions.browserUrl(t))
                                SelectionActions.Action.ASK -> onAsk?.invoke(SelectionActions.askPrompt(t)) ?: SelectionDo.openInBlazeBrowser(ctx, t)
                                SelectionActions.Action.TRANSLATE -> onAsk?.invoke(SelectionActions.translatePrompt(t, SelectionDo.phoneLanguage()))
                                SelectionActions.Action.DEFINE -> onAsk?.invoke(SelectionActions.definePrompt(t))
                                SelectionActions.Action.READ_ALOUD -> SelectionDo.readAloud(ctx, t)
                                SelectionActions.Action.SHARE -> SelectionDo.share(ctx, t)
                                else -> {}
                            }
                        }
                        when (a) {
                            SelectionActions.Action.COPY -> {
                                if (!m.click(android.R.id.copy)) copyText(ctx, selText)
                                endSelection()
                            }
                            SelectionActions.Action.SELECT_ALL -> { m.click(android.R.id.selectAll) }
                            else -> if (selText.isNotBlank()) act(selText) else w.selectedText { t ->
                                if (t.isNotBlank()) act(t)
                                else { // The page couldn't say (a frame): copy through the page, then read it back.
                                    m.click(android.R.id.copy); act(clipText(ctx))
                                }
                            }
                        }
                    }
                }
            }
        })
}

private fun copyText(ctx: android.content.Context, t: String) {
    if (t.isBlank()) return
    try {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Blaze", t))
    } catch (_: Exception) { }
}

private fun clipText(ctx: android.content.Context): String = try {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
} catch (_: Exception) { "" }

/** v0.30.5: round title-bar buttons that read on the near-opaque frame in light and dark (the × is the strong one). */
@Composable
internal fun GlassButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, strong: Boolean = false, onClick: () -> Unit) {
    val c = LocalBlaze.current
    val fill = when {
        strong && c.dark -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.92f), Color.White.copy(alpha = 0.78f)))
        strong -> Brush.linearGradient(listOf(Color(0xFF111110), Color(0xFF2A2A2E)))
        c.dark -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.08f)))
        else -> Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.03f)))
    }
    val tint = if (strong) (if (c.dark) Color(0xFF111110) else Color.White) else c.ink
    Box(Modifier.size(36.dp).clip(CircleShape).background(fill)
        .border(1.dp, GlassPaneLook.hairline(c.dark), CircleShape)
        .clickable(onClickLabel = desc, onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(if (strong) 18.dp else 17.dp))
    }
}

private fun host(u: String): String = try { android.net.Uri.parse(u).host.orEmpty().removePrefix("www.").ifBlank { u } } catch (_: Exception) { u }
