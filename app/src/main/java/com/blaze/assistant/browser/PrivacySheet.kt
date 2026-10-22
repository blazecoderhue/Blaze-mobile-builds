package com.blaze.assistant.browser

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.Label
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.Type
import com.blaze.assistant.ui.hairlineTop

/** ⋯ › VPN & privacy: the browser VPN (WireGuard locations by country), location masking and private tabs. */
@Composable
fun PrivacySheet(onImport: () -> Unit, onPrivateTab: () -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val st = BrowserVpn.status
    val cur = BrowserVpn.current()
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
        Label("VPN & privacy", modifier = Modifier.padding(16.dp))

        if (!BrowserVpn.supported) {
            P("The browser VPN needs a newer Android System WebView. Update it in the Play Store.", c.sub, 13, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        } else {
            // Status + master switch
            Row(Modifier.fillMaxWidth().hairlineTop(c.hair).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    P("VPN", c.ink, 15)
                    P(BrowserVpn.statusLine(), if (st == BrowserVpn.Status.FAILED) Color(0xFFD93025) else if (st == BrowserVpn.Status.ON) c.accent else c.sub, 12)
                }
                // v0.25.3: one tap works with nothing imported (Blaze Free).
                Switch(checked = BrowserVpn.on, onCheckedChange = { v ->
                    if (v) BrowserVpn.connect(BrowserVpn.defaultId()) else BrowserVpn.disconnect()
                }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
            }

            // Blaze Free: always there, free, no setup.
            Label("Location", modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            val freeSel = BrowserVpn.isFree && BrowserVpn.on
            Row(Modifier.fillMaxWidth().background(if (freeSel) c.faint else c.paper).hairlineTop(c.hair)
                .clickable { BrowserVpn.connect(BrowserVpn.FREE_ID) }.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, tint = c.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    P(WarpFree.NAME, c.ink, 14, maxLines = 1)
                    P("Free, no account. Hides your IP; exits near you.", c.sub, 11, maxLines = 1)
                }
                if (freeSel) P(if (st == BrowserVpn.Status.ON) "Connected" else if (st == BrowserVpn.Status.CONNECTING) "…" else "Retrying",
                    if (st == BrowserVpn.Status.FAILED) Color(0xFFD93025) else c.accent, 12)
            }

            // Countries
            if (BrowserVpn.locations.isNotEmpty()) {
                Label("Your countries", modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(BrowserVpn.locations.sortedBy { it.label }, key = { it.id }) { l ->
                        val sel = l.id == BrowserVpn.selected && BrowserVpn.on
                        Row(Modifier.fillMaxWidth().background(if (sel) c.faint else c.paper).hairlineTop(c.hair)
                            .clickable { BrowserVpn.connect(l.id) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            P(l.flag, c.ink, 20)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                P(l.label, c.ink, 14, maxLines = 1)
                                if (l.name != l.label) P(l.name, c.sub, 11, maxLines = 1)
                            }
                            if (sel) P(if (st == BrowserVpn.Status.ON) "Connected" else if (st == BrowserVpn.Status.CONNECTING) "…" else "Failed",
                                if (st == BrowserVpn.Status.FAILED) Color(0xFFD93025) else c.accent, 12)
                            P("✕", c.weak, 14, Modifier.clickable { BrowserVpn.remove(l.id) }.padding(horizontal = 14.dp, vertical = 6.dp))
                        }
                    }
                }
            }
            Row2("Import WireGuard config", ".conf files, or the .zip your VPN provider gives you", onImport)
            Row2("Paste config", "From the clipboard") {
                val t = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                try { val l = BrowserVpn.add("Pasted config", t); toast(ctx, "Added ${l.flag} ${l.label}") }
                catch (e: Exception) { toast(ctx, e.message ?: "That isn't a WireGuard config.") }
            }
        }

        // Masking
        Row(Modifier.fillMaxWidth().hairlineTop(c.hair).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                P("Mask my location", c.ink, 15)
                P("In private tabs, and every tab while the VPN is on. Sites get the VPN country's position, time zone and language (or no position without the VPN), and WebRTC can't reveal your real IP.", c.sub, 12)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = BrowserVpn.maskLocation, onCheckedChange = { BrowserVpn.setMask(it) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
        }
        Row2("New private tab", if (BrowserEngine.privateIsolated) "No history; cookies and site data wiped when you close it" else "No history (cookies are shared on this phone's WebView)", onPrivateTab)
        P("Blaze Free needs nothing. To pick a country, import configs: Proton VPN's free plan (account.protonvpn.com › Downloads › WireGuard) covers a few countries; Mullvad, IVPN, Windscribe and Surfshark give WireGuard files for most. One file per country.",
            c.weak, 11, Modifier.hairlineTop(c.hair).padding(16.dp))
    }
}

@Composable private fun Row2(title: String, sub: String, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().hairlineTop(c.hair).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp)) {
        P(title, c.ink, 15); P(sub, c.sub, 12)
    }
}

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

@Composable private fun P(s: String, color: Color, size: Int, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    androidx.compose.material3.Text(s, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Normal, fontSize = size.sp, color = color))
