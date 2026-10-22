package com.blaze.assistant.browser

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.BlazeTextToolbar
import com.blaze.assistant.ui.CaptureClipboard
import com.blaze.assistant.ui.Glass
import com.blaze.assistant.ui.SelectionActions
import com.blaze.assistant.ui.SelectionDo
import com.blaze.assistant.ui.SelectionPill
import com.blaze.assistant.ui.GlassIconButton
import com.blaze.assistant.ui.MascotGlyph
import com.blaze.assistant.ui.Type
import kotlinx.coroutines.launch

/** One line in the page chat: the bubble text, a pasted link shown as a chip, and what the model was asked. */
private data class Line(val me: Boolean, val text: String, val link: String? = null, val prompt: String = text)

/**
 * "Ask Blaze about this page": a compact chat sheet scoped to the open page. Nothing here goes into the main chat
 * or memory; the page text is read once (passwords and secure fields are scrubbed by the reader). The find chip
 * searches the page itself with WebView's find-in-page.
 */
@Composable
fun PageAskSheet(title: String, initial: String?, readPage: suspend () -> PageAsk.Capture, web: WebView?, onDismiss: () -> Unit,
                 preview: List<Pair<Boolean, String>>? = null) {
    val scope = rememberCoroutineScope()
    val list = remember { mutableStateListOf<Line>().also { l -> preview?.let { p -> l.addAll(p.map { (me, t) -> Line(me, t) }) } } }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<PageAsk.Capture?>(null) }
    var finding by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    val main = remember { Handler(Looper.getMainLooper()) }
    val lang = remember { try { java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "English" } } catch (_: Exception) { "English" } }

    // v0.27.1: an empty question summarises; a pasted link, one word or a greeting becomes a real request (PageQuestion).
    fun ask(q: String) {
        if (busy) return
        input = ""; busy = true
        val cur = page
        val intent = PageQuestion.parse(q, cur?.url ?: BrowserEngine.url())
        list += Line(true, intent.shown, intent.link, intent.prompt)
        val history = list.map { it.me to it.prompt }
        list += Line(false, "\u2026")
        val idx = list.lastIndex
        scope.launch {
            try {
                // A thin read (Lens results, a page still loading) is read again next time instead of being kept.
                val p = cur ?: try { readPage() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { PageAsk.Capture.NONE }
                page = p.takeUnless { PageAsk.thin(it) }
                val extra = StringBuilder()
                PageAsk.searchFor(intent, p)?.let { s ->
                    val r = try { com.blaze.assistant.agent.Web.search(s, 5) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }
                    if (r.isNotBlank() && !r.startsWith("Error") && !r.startsWith("No results")) extra.append("WEB RESULTS for \u201c").append(s).append("\u201d:\n").append(r)
                }
                intent.fetch?.let { u ->
                    val r = try { com.blaze.assistant.agent.Web.fetch(u, 4000) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }
                    if (r.isNotBlank() && !r.startsWith("Error")) extra.append(if (extra.isEmpty()) "" else "\n\n")
                        .append("THE PAGE THE USER LINKED (").append(PageQuestion.host(u)).append("):\n").append(r)
                }
                val r = com.blaze.assistant.agent.LlmClient.complete(PageAsk.messages(p, history, extra.toString(), lang), null, onText = { s ->
                    val shown = com.blaze.assistant.agent.ReplyParts.split(s, final = false).text
                    main.post { if (idx < list.size && shown.isNotBlank()) list[idx] = Line(false, shown) }
                })
                val out = com.blaze.assistant.agent.ReplyParts.split(r.optString("content"), final = true).text
                    .ifBlank { "I couldn't get an answer for this page just now. Tap a chip below or ask again in a moment." }
                if (idx < list.size) list[idx] = Line(false, out)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                if (idx < list.size) list[idx] = Line(false, "I couldn't answer that: " + (e.message ?: "no connection").take(160))
            } finally { busy = false }
        }
    }
    fun find(q: String, forward: Boolean? = null) {
        val w = web ?: return
        if (forward != null) { w.findNext(forward); return }
        w.setFindListener { active, n, done -> if (done) found = if (n == 0) "No matches" else "${active + 1} of $n" }
        if (q.isBlank()) { w.clearMatches(); found = "" } else w.findAllAsync(q)
    }
    // v0.27.2: the same swipeable selection pill as the chat, for the answers in this sheet.
    val ctx = LocalContext.current
    val realClip = LocalClipboardManager.current
    val capClip = remember(realClip) { CaptureClipboard(realClip) }
    var tbRect by remember { mutableStateOf<Rect?>(null) }
    var tbCopy by remember { mutableStateOf<(() -> Unit)?>(null) }
    var tbAll by remember { mutableStateOf<(() -> Unit)?>(null) }
    val toolbar = remember { BlazeTextToolbar({ r, cp, all -> tbRect = r; tbCopy = cp; tbAll = all }, { tbRect = null }) }
    fun onSelection(a: SelectionActions.Action) {
        val copy = tbCopy
        when (a) {
            SelectionActions.Action.COPY -> { copy?.invoke(); toolbar.hide() }
            SelectionActions.Action.SELECT_ALL -> tbAll?.invoke()
            else -> {
                val t = capClip.capture(copy).trim()
                toolbar.hide()
                if (t.isBlank()) return
                when (SelectionActions.resolve(a, t)) {
                    SelectionActions.Action.SEARCH, SelectionActions.Action.OPEN_LINK -> {
                        try { BrowserEngine.newTab(ctx, SelectionActions.browserUrl(t)); onDismiss() } catch (_: Exception) { SelectionDo.openInBlazeBrowser(ctx, t) }
                    }
                    SelectionActions.Action.ASK -> ask(SelectionActions.askPrompt(t))
                    SelectionActions.Action.TRANSLATE -> ask(SelectionActions.translatePrompt(t, lang))
                    SelectionActions.Action.DEFINE -> ask(SelectionActions.definePrompt(t))
                    SelectionActions.Action.READ_ALOUD -> SelectionDo.readAloud(ctx, t)
                    SelectionActions.Action.SHARE -> SelectionDo.share(ctx, t)
                    else -> {}
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { try { web?.clearMatches() } catch (_: Exception) { } } }
    LaunchedEffect(Unit) { initial?.takeIf { it.isNotBlank() && preview == null }?.let { ask(it) } }
    LaunchedEffect(list.size, list.lastOrNull()?.text?.length) { if (list.isNotEmpty()) state.animateScrollToItem(list.lastIndex) }

    val top = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(remember { MutableInteractionSource() }, null) { onDismiss() },
        contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(top).background(Glass.surface).border(1.dp, Glass.border, top)
            .clickable(remember { MutableInteractionSource() }, null) { }
            .navigationBarsPadding().imePadding().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MascotGlyph(34.dp, pulse = busy)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ask Blaze about this page", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Glass.text))
                    Text(title.ifBlank { "This page" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Glass.sub))
                }
                GlassIconButton(Icons.Rounded.Close, "Close", size = 36.dp, onClick = onDismiss)
            }
            if (list.isNotEmpty()) CompositionLocalProvider(LocalTextToolbar provides toolbar, LocalClipboardManager provides capClip) {
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(list) { _, line ->
                        val me = line.me
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (me) Alignment.CenterEnd else Alignment.CenterStart) {
                            Column(horizontalAlignment = if (me) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                // A pasted link is a compact chip, never a giant URL bubble.
                                line.link?.let { LinkChip(PageQuestion.chip(it)) }
                                if (line.text.isNotBlank()) SelectionContainer {
                                    Text(com.blaze.assistant.ui.Md.render(line.text, Glass.cyan, Glass.raised2), modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                                        .background(if (me) Glass.blue else Glass.raised).padding(horizontal = 12.dp, vertical = 9.dp),
                                        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = Glass.text))
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PageAsk.chips.forEach { ch -> Chip(ch) { finding = false; ask(ch) } }
                if (web != null) Chip(if (finding) "Ask instead" else "Find on page", active = finding) { finding = !finding; found = ""; if (!finding) find("") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).clip(Glass.pill).background(Glass.raised).border(1.dp, Glass.border, Glass.pill)
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (input.isEmpty()) Text(if (finding) "Find words on this page" else "Ask about this page (or send to summarise)", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = Glass.weak))
                    BasicTextField(input, { input = it; if (finding) find(it) }, singleLine = finding, maxLines = if (finding) 1 else 3,
                        cursorBrush = SolidColor(Glass.blue), textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = Glass.text),
                        // v0.28.1: asking: Enter = new line, only the send button sends. Find mode keeps Search (it never sends).
                        keyboardOptions = if (finding) KeyboardOptions(imeAction = ImeAction.Search) else com.blaze.assistant.ui.ComposerKeys.Message,
                        keyboardActions = KeyboardActions(onSearch = { find(input, true) }),
                        modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(8.dp))
                if (finding) {
                    if (found.isNotEmpty()) Text(found, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Glass.sub), modifier = Modifier.padding(end = 6.dp))
                    GlassIconButton(Icons.Rounded.KeyboardArrowUp, "Previous match", size = 40.dp) { find(input, false) }
                    Spacer(Modifier.width(4.dp))
                    GlassIconButton(Icons.Rounded.KeyboardArrowDown, "Next match", size = 40.dp) { find(input, true) }
                } else GlassIconButton(Icons.Rounded.Send, if (input.isBlank()) "Summarise this page" else "Send", size = 46.dp,
                    bg = if (busy) Glass.raised2 else Glass.blue, enabled = !busy) { ask(input) }
            }
        }
        tbRect?.let { r ->
            val d = LocalDensity.current
            val short = with(d) { SelectionActions.likelyShort(r.width / density, r.height / density) }
            val acts = remember(tbAll != null, short) { SelectionActions.forUnknown(short, allowQuote = false, allowSelectAll = tbAll != null) }
            SelectionPill(r, acts, ink = Color(0xFF111114), paper = Color.White) { a -> onSelection(a) }
        }
    }
}

@Composable
private fun Chip(text: String, active: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.clip(Glass.pill).background(if (active) Glass.blue else Glass.raised).border(1.dp, Glass.border, Glass.pill)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (text == "Find on page") {
            androidx.compose.material3.Icon(Icons.Rounded.FindInPage, contentDescription = null, tint = Glass.text, modifier = Modifier.padding(end = 6.dp).width(16.dp))
        }
        Text(text, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Glass.text))
    }
}

@Composable
private fun LinkChip(label: String) {
    Row(Modifier.widthIn(max = 260.dp).clip(Glass.pill).background(Glass.blue.copy(alpha = 0.85f)).border(1.dp, Glass.border, Glass.pill)
        .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(Icons.Rounded.Link, contentDescription = null, tint = Glass.text, modifier = Modifier.padding(end = 6.dp).width(16.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Glass.text))
    }
}
