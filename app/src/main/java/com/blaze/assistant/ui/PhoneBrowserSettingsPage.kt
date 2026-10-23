package com.blaze.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.browser.phone.PhoneBrowser
import com.blaze.assistant.browser.phone.PhoneBrowserActivity

/**
 * 0.45.19 Settings › Advanced › Blaze Phone: open it, sign out of one site, or clear every Blaze Phone session. Blaze
 * Phone's sign-ins are its own (a WebView profile per site), apart from the user's browser and its private tabs.
 */
@Composable
fun PhoneBrowserSettingsPage() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var sure by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    PhoneBrowser.init(ctx)
    val sites = remember(tick) { PhoneBrowser.sites() }
    Spacer(Modifier.height(10.dp))
    Text("Blaze Phone is Blaze's own phone screen: it browses, signs in and books for you in the background, and you can watch it " +
        "live in the chat or take control. It keeps its sign-ins per site, apart from your own browser." +
        if (!PhoneBrowser.multiProfile) " This phone's WebView can't keep separate profiles, so Blaze Phone shares cookies with Blaze's browser (not its private tabs)." else "",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
    Spacer(Modifier.height(14.dp))
    Rows {
        KitRow("Open Blaze Phone", "Watch it, or use it yourself", first = true, onClick = { PhoneBrowserActivity.open(ctx, control = false) }) {
            Text("\u2192", style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
        }
    }
    Spacer(Modifier.height(18.dp))
    GroupHead("Signed-in sites", if (sites.isEmpty()) "None yet." else "Sign out of one site; the others stay signed in.")
    if (sites.isNotEmpty()) Rows {
        sites.forEachIndexed { i, s ->
            KitRow(s, first = i == 0) {
                ActionText("Sign out", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
                    PhoneBrowser.signOut(s); note = "Signed out of $s."; tick++ }.padding(8.dp))
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    Rows {
        KitRow(if (sure) "Tap again to clear everything" else "Clear Blaze Phone data", "Every Blaze Phone sign-in, cookie and saved site data", first = true,
            onClick = { if (!sure) sure = true else { val n = PhoneBrowser.clearAll(); sure = false; note = "Cleared Blaze Phone data ($n sessions)."; tick++ } })
    }
    if (note.isNotBlank()) { Spacer(Modifier.height(10.dp)); Label(note) }
}
