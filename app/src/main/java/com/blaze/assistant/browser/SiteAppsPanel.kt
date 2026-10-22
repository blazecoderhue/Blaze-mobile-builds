package com.blaze.assistant.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.siteapps.SiteApp
import com.blaze.assistant.siteapps.SiteInstaller
import com.blaze.assistant.siteapps.SiteLogic
import com.blaze.assistant.ui.Glass
import com.blaze.assistant.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * v0.33.2 Blaze browser › "Add to Home screen / Install app" (the site's real icon, its name, then a pinned launcher
 * icon that opens it as its own app) and › "Installed apps" (open or uninstall).
 */

private fun st(size: Int, color: androidx.compose.ui.graphics.Color = Glass.text, w: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Type.Sans, fontSize = size.sp, fontWeight = w, color = color)

@Composable
private fun Btn(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(if (primary) Glass.text else Glass.raised).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(text, style = st(15, if (primary) Glass.surface else Glass.text, FontWeight.SemiBold))
    }
}

@Composable
internal fun ColumnScope.InstallSiteBody(url: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ins by remember { mutableStateOf<SiteInstaller.Inspected?>(null) }
    var status by remember { mutableStateOf("Reading the site…") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(url) {
        val (r, why) = SiteInstaller.inspect(url)
        ins = r; name = r?.app?.name.orEmpty(); status = if (r == null) why else ""
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Install app", style = st(20, w = FontWeight.SemiBold))
        Spacer(Modifier.height(4.dp))
        Text("Adds this site to your home screen. It opens on its own, like an app: no address bar, its own window and sign-ins.", style = st(13, Glass.sub))
        Spacer(Modifier.height(16.dp))
        val i = ins
        if (i == null) Text(status, style = st(14, Glass.sub))
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(i.icon.asImageBitmap(), null, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Glass.raised).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        BasicTextField(name, { name = it.take(30) }, singleLine = true, textStyle = st(16), cursorBrush = SolidColor(Glass.text), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(SiteLogic.hostOf(i.app.startUrl) + if (i.iconSource == "letter") " · no icon on the site" else "", style = st(12, Glass.sub))
                }
            }
            Spacer(Modifier.height(16.dp))
            if (status.isNotEmpty()) { Text(status, style = st(14, Glass.sub)); Spacer(Modifier.height(10.dp)) }
            Row {
                Btn(if (busy) "Adding…" else "Add to Home screen", primary = true) {
                    if (busy) return@Btn
                    busy = true
                    scope.launch { status = SiteInstaller.installOnPhone(ctx, i, name); busy = false }
                }
                Spacer(Modifier.width(10.dp))
                Btn("Close") { onDone() }
            }
        }
        if (i == null) { Spacer(Modifier.height(12.dp)); Btn("Close") { onDone() } }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
internal fun ColumnScope.InstalledSitesBody(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<SiteApp>>(emptyList()) }
    LaunchedEffect(tick) { apps = withContext(Dispatchers.IO) { SiteInstaller.installed(ctx) } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Installed apps", style = st(20, w = FontWeight.SemiBold))
        Spacer(Modifier.height(10.dp))
        if (apps.isEmpty()) Text("No sites installed yet. Open a site, then menu › Install app.", style = st(14, Glass.sub))
        for (a in apps) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = remember(a.id, tick) { SiteInstaller.icon(ctx, a.id) }
            if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, style = st(15, w = FontWeight.Medium))
                Text(SiteLogic.hostOf(a.startUrl), style = st(12, Glass.sub))
            }
            Text("Open", style = st(14, Glass.text, FontWeight.SemiBold), modifier = Modifier.clip(CircleShape)
                .clickable { ctx.startActivity(SiteInstaller.intent(ctx, a.id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); onDone() }.padding(10.dp))
            Text("Uninstall", style = st(14, Glass.sub), modifier = Modifier.clip(CircleShape).clickable { SiteInstaller.uninstall(ctx, a.id); tick++ }.padding(10.dp))
        }
        Spacer(Modifier.height(12.dp))
        Btn("Close") { onDone() }
        Spacer(Modifier.height(12.dp))
    }
}
