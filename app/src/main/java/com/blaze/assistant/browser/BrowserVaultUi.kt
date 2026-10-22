package com.blaze.assistant.browser

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.Glass
import com.blaze.assistant.ui.MascotGlyph
import com.blaze.assistant.ui.Type

/** v0.29.7: the browser's password prompt, saved-account picker, Saved passwords and Saved pages (Blaze glass look). */

@Composable private fun T(s: String, color: Color, size: Int, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, weight: FontWeight = FontWeight.Normal) =
    androidx.compose.material3.Text(s, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = if (size >= 20) Type.Display else Type.Sans, fontWeight = weight, fontSize = size.sp, lineHeight = (size + 5).sp, color = color))

@Composable private fun Pill(text: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(38.dp).clip(Glass.pill).background(if (primary) Glass.blue else Glass.raised2)
        .border(1.dp, if (primary) Color.Transparent else Glass.border, Glass.pill).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) { T(text, if (primary) Color.White else Glass.text, 14, weight = FontWeight.Medium, maxLines = 1) }
}

@Composable private fun SmallAction(icon: ImageVector, desc: String, tint: Color = Glass.sub, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = desc, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
}

/** Floating card over the page after a sign-in: "Save password for github.com?" */
@Composable
internal fun SaveLoginCard(offer: BrowserEngine.LoginOffer, onSave: () -> Unit, onNever: () -> Unit, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().widthIn(max = 520.dp).shadow(20.dp, shape).clip(shape).background(Glass.surface)
            .border(1.dp, Glass.blue.copy(alpha = 0.45f), shape).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Glass.raised2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Key, null, tint = Glass.blue, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(if (offer.update) "Update password?" else "Save password?", Glass.text, 17, weight = FontWeight.SemiBold)
                    T(offer.site, Glass.sub, 13, maxLines = 1)
                }
                SmallAction(Icons.Rounded.Close, "Not now", onClick = onDismiss)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass.raised).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Person, null, tint = Glass.sub, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                T(offer.user.ifEmpty { "(no username)" }, Glass.text, 14, maxLines = 1, modifier = Modifier.weight(1f))
                T("•".repeat(offer.pass.length.coerceIn(6, 12)), Glass.sub, 14, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            T("Encrypted on this phone with its Keystore. Never saved from private tabs, never shown to the AI.", Glass.weak, 11)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!offer.update) T("Never for this site", Glass.sub, 13, modifier = Modifier.clip(Glass.pill).clickable(onClick = onNever).padding(horizontal = 6.dp, vertical = 10.dp))
                Spacer(Modifier.weight(1f))
                Pill("Not now", onClick = onDismiss)
                Pill(if (offer.update) "Update" else "Save", primary = true, onClick = onSave)
            }
        }
    }
}

/** Bar above the page when it has a sign-in form and Blaze has saved accounts for the site. */
@Composable
internal fun LoginFillBar(accounts: List<BrowserLogins.Login>, onPick: (BrowserLogins.Login) -> Unit, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().shadow(14.dp, shape).clip(shape).background(Glass.surface).border(1.dp, Glass.border, shape).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, null, tint = Glass.blue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                T(if (accounts.size == 1) "Use your saved password" else "Use a saved password (${accounts.size})", Glass.text, 14, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                SmallAction(Icons.Rounded.Close, "Hide", onClick = onDismiss)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.take(8).forEach { l ->
                    Row(Modifier.height(40.dp).clip(Glass.pill).background(Glass.raised2).border(1.dp, Glass.blue.copy(alpha = 0.5f), Glass.pill)
                        .clickable { onPick(l) }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Person, null, tint = Glass.sub, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        T(l.user.ifEmpty { "(no username) · ${l.site}" }, Glass.text, 13, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Device unlock (fingerprint / face / PIN) before a password is shown, copied or edited; stays unlocked 2 minutes. */
internal fun withUnlock(ctx: Context, reason: String, ok: () -> Unit) {
    if (System.currentTimeMillis() < BrowserUi.unlockedUntil) { ok(); return }
    val auth = BrowserUi.authenticate
    if (auth == null) { Toast.makeText(ctx, "Open the browser to unlock saved passwords.", Toast.LENGTH_SHORT).show(); return }
    auth(reason) { BrowserUi.unlockedUntil = System.currentTimeMillis() + 120_000; ok() }
}

private fun copySecret(ctx: Context, label: String, value: String) {
    try {
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= 24) clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true) }
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) { }
}

/** ⋯ › Saved passwords: every site and account; unlock to show, copy or edit; delete; sites set to "never". */
@Composable
internal fun SavedPasswordsPanel() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val logins = remember(tick) { try { BrowserLogins.all() } catch (_: Throwable) { emptyList() } }
    val never = remember(tick) { try { BrowserLogins.never() } catch (_: Throwable) { emptyList() } }
    var shown by remember { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmDel by remember { mutableStateOf<String?>(null) }
    var eu by remember { mutableStateOf("") }; var ep by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val list = logins.filter { query.isBlank() || it.site.contains(query.trim(), true) || it.user.contains(query.trim(), true) }

    Row(Modifier.padding(start = 18.dp, end = 12.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        T("Saved passwords", Glass.text, 16, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.Lock, null, tint = Glass.weak, modifier = Modifier.size(15.dp))
    }
    T("Saved from sign-in forms in Blaze Browser (never from private tabs). Encrypted with this phone's Keystore. Unlock with your fingerprint or PIN to see, copy or change one.",
        Glass.sub, 12, modifier = Modifier.padding(horizontal = 18.dp))
    Spacer(Modifier.height(8.dp))
    if (logins.size > 5) Row(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(40.dp).clip(Glass.pill).background(Glass.raised)
        .border(1.dp, Glass.border, Glass.pill).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, tint = Glass.weak, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) T("Search sites or usernames", Glass.weak, 13)
            BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(Glass.blue),
                textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Glass.text), modifier = Modifier.fillMaxWidth())
        }
    }
    LazyColumn(Modifier.heightIn(max = 460.dp).padding(horizontal = 10.dp)) {
        if (logins.isEmpty()) item {
            T("No saved passwords yet. When you sign in to a site, Blaze offers to save it.", Glass.sub, 13, modifier = Modifier.padding(8.dp, 12.dp))
        }
        items(list, key = { it.id }) { l ->
            val open = l.id in shown; val isEdit = editing == l.id
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Glass.raised).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Glass.raised2), contentAlignment = Alignment.Center) {
                        T(l.site.take(1).uppercase(), Glass.text, 13, weight = FontWeight.SemiBold) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        T(l.site, Glass.text, 14, weight = FontWeight.Medium, maxLines = 1)
                        T(BrowserLogins.maskUser(l.user), Glass.sub, 12, maxLines = 1)
                        if (!isEdit) T(if (open) l.password.ifEmpty { "(can't read on this phone)" } else "••••••••", if (open) Glass.text else Glass.weak, 13, maxLines = 1)
                    }
                    if (!isEdit) {
                        SmallAction(if (open) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (open) "Hide password" else "Show password") {
                            if (open) shown = shown - l.id else withUnlock(ctx, "Show the password for ${l.site}") { shown = shown + l.id } }
                        SmallAction(Icons.Rounded.ContentCopy, "Copy password") { withUnlock(ctx, "Copy the password for ${l.site}") { copySecret(ctx, l.site, l.password) } }
                        SmallAction(Icons.Rounded.Edit, "Edit") { withUnlock(ctx, "Edit the password for ${l.site}") { eu = l.user; ep = l.password; editing = l.id } }
                        SmallAction(Icons.Rounded.DeleteOutline, "Delete", tint = if (confirmDel == l.id) Glass.orange else Glass.sub) {
                            if (confirmDel == l.id) { BrowserLogins.delete(l.id); confirmDel = null; shown = shown - l.id; tick++ }
                            else { confirmDel = l.id; Toast.makeText(ctx, "Tap delete again to remove it", Toast.LENGTH_SHORT).show() } }
                    }
                }
                if (isEdit) {
                    Spacer(Modifier.height(6.dp))
                    EditField("Username", eu, false) { eu = it }
                    Spacer(Modifier.height(6.dp))
                    EditField("Password", ep, true) { ep = it }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        Pill("Cancel") { editing = null; eu = ""; ep = "" }
                        Pill("Save", primary = true) {
                            if (ep.isEmpty()) Toast.makeText(ctx, "The password can't be empty.", Toast.LENGTH_SHORT).show()
                            else { BrowserLogins.update(l.id, eu.trim(), ep); editing = null; eu = ""; ep = ""; tick++ }
                        }
                    }
                }
            }
        }
        if (never.isNotEmpty()) {
            item { T("Never saved on", Glass.sub, 12, weight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp)) }
            items(never, key = { "never:$it" }) { site ->
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(site, Glass.text, 13, modifier = Modifier.weight(1f))
                    SmallAction(Icons.Rounded.Close, "Allow saving on $site") { BrowserLogins.removeNever(site); tick++ }
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable private fun EditField(label: String, value: String, secret: Boolean, onChange: (String) -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(end = 8.dp).clip(RoundedCornerShape(12.dp)).background(Glass.surface).border(1.dp, Glass.border, RoundedCornerShape(12.dp))
        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(label, Glass.weak, 10)
            BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(Glass.blue),
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = Glass.text), modifier = Modifier.fillMaxWidth())
        }
        if (secret) SmallAction(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide" else "Show") { reveal = !reveal }
    }
}

/** ⋯ › Saved pages: offline copies, newest first. Tap to open (offline), the text icon for the readable copy. */
@Composable
internal fun SavedPagesPanel(onOpen: (SavedPages.Page) -> Unit, onRead: (SavedPages.Page) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val pages = remember(tick) { try { SavedPages.list() } catch (_: Throwable) { emptyList() } }
    var confirmDel by remember { mutableStateOf<String?>(null) }
    T("Saved pages", Glass.text, 16, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 2.dp))
    T("Pages saved on this phone. They open without internet.", Glass.sub, 12, modifier = Modifier.padding(horizontal = 18.dp))
    Spacer(Modifier.height(6.dp))
    LazyColumn(Modifier.heightIn(max = 460.dp).padding(horizontal = 8.dp)) {
        if (pages.isEmpty()) item { T("Nothing saved yet. Open a page and choose ⋯ › Save page for offline.", Glass.sub, 13, modifier = Modifier.padding(10.dp, 12.dp)) }
        items(pages, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp)).clickable { onOpen(p) }
                .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Glass.raised2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.OfflinePin, null, tint = Glass.blue, modifier = Modifier.size(17.dp)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    T(p.title.ifBlank { p.url }, Glass.text, 14, maxLines = 1)
                    val whenText = remember(p.saved) { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(p.saved)) }
                    T("${SiteDomain.hostOf(p.url)} · $whenText · ${SavedPages.size(p.bytes)}", Glass.sub, 12, maxLines = 1)
                }
                if (p.hasText) SmallAction(Icons.Rounded.Article, "Read as text") { onRead(p) }
                SmallAction(Icons.Rounded.DeleteOutline, "Delete", tint = if (confirmDel == p.id) Glass.orange else Glass.sub) {
                    if (confirmDel == p.id) { SavedPages.delete(p.id); confirmDel = null; tick++ }
                    else { confirmDel = p.id; Toast.makeText(ctx, "Tap delete again to remove it", Toast.LENGTH_SHORT).show() } }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

/** The text copy of a saved page, full screen (selectable). */
@Composable
internal fun SavedTextReader(page: SavedPages.Page, onClose: () -> Unit) {
    val text = remember(page.id) { SavedPages.text(page.id) ?: "This page has no text copy." }
    Column(Modifier.fillMaxSize().background(Glass.bg)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            com.blaze.assistant.ui.GlassIconButton(Icons.Rounded.Close, "Close", size = 38.dp, onClick = onClose)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                T(page.title.ifBlank { page.url }, Glass.text, 15, weight = FontWeight.Medium, maxLines = 1)
                T("Offline text copy", Glass.sub, 12)
            }
            MascotGlyph(28.dp)
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                T(text, Glass.text, 15)
            }
        }
    }
}
