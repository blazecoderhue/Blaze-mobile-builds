package com.blaze.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.link.DesktopLink
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

/** Settings › Blaze Desktop: pair this phone with Blaze on a computer by scanning its QR code. */
@Composable
internal fun DesktopPage() {
    val c = LocalBlaze.current.mono
    val scope = rememberCoroutineScope()
    val paired by DesktopLink.pairedFlow.collectAsState()
    val connected by DesktopLink.connected.collectAsState()
    val linkStatus by DesktopLink.status.collectAsState()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var inboxOn by remember { mutableStateOf(Settings.bool("pc_inbox", true)) }
    val deviceName = remember { (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL).trim() }

    fun doPair(link: String) {
        busy = true; msg = "Pairing…"
        scope.launch { msg = DesktopLink.pair(link, deviceName); busy = false }
    }
    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { doPair(it) }
    }
    LaunchedEffect(Unit) { DesktopLink.start(); if (DesktopLink.paired) msg = DesktopLink.ping() }

    Spacer(Modifier.height(22.dp))
    Text("Link this phone to Blaze on your computer. Ask your PC to work on files and code from here, and let Blaze on the PC text, call and check this phone. Same Wi-Fi, or anywhere when both are on Tailscale.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    Spacer(Modifier.height(18.dp))

    if (paired) {
        Label("Paired computer", modifier = Modifier.padding(bottom = 8.dp))
        Row(Modifier.fillMaxWidth().hairline(c.hair).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(DesktopLink.pcName, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
                Text(if (connected) "Connected" else linkStatus.ifBlank { "Not connected right now" },
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = if (connected) c.accent else c.sub))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f).height(46.dp).hairline(c.hair).clickable(enabled = !busy) {
                busy = true; msg = "Checking…"; scope.launch { msg = DesktopLink.ping(); busy = false }
            }, contentAlignment = Alignment.Center) { Label("Check connection", color = c.ink, size = 11.sp) }
            Box(Modifier.weight(1f).height(46.dp).hairline(c.hair).clickable { DesktopLink.unpair(); msg = "Unpaired." },
                contentAlignment = Alignment.Center) { Label("Unpair", color = c.ink, size = 11.sp) }
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Let the PC use this phone", "Blaze on the PC can ask this phone to text, call, check notifications and more. Risky actions still ask you here.", inboxOn) {
            inboxOn = !inboxOn
            Settings.setBool("pc_inbox", inboxOn)
            if (inboxOn) DesktopLink.start() else DesktopLink.stopInbox()
        }
    } else {
        Label("Pair a computer", modifier = Modifier.padding(bottom = 8.dp))
        Text("On the computer, open Blaze Desktop › Phone › Show pairing QR code, then scan it.",
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.body))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(50.dp).hairline(c.accent).clickable(enabled = !busy) {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the QR code in Blaze Desktop › Phone")
                .setBeepEnabled(false).setOrientationLocked(false))
        }, contentAlignment = Alignment.Center) { Label("Scan QR code", color = c.accent, size = 12.sp) }
        Spacer(Modifier.height(16.dp))
        Label("Or paste the pairing link", modifier = Modifier.padding(bottom = 6.dp), size = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(pasted, { pasted = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                textStyle = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, color = c.ink),
                modifier = Modifier.weight(1f).hairline(c.hair).padding(12.dp),
                decorationBox = { inner -> if (pasted.isEmpty()) Text("blaze://pair?…", style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, color = c.weak)); inner() })
            Box(Modifier.height(44.dp).hairline(c.hair).clickable(enabled = !busy && pasted.isNotBlank()) { doPair(pasted) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center) { Label("Pair", color = c.ink, size = 11.sp) }
        }
    }
    if (msg.isNotEmpty()) Text(msg, modifier = Modifier.padding(top = 10.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 17.sp, color = c.accent))
}
