package com.blaze.assistant.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.update.Updater
import com.blaze.assistant.update.Updater.State

/** Checking → Downloading N% → Installing, with a plain error and Retry. */
@Composable
fun UpdateCard(preview: State? = null) {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val live by Updater.state.collectAsState()
    val st = preview ?: live
    val (title, line, pct) = UpdateCardText.of(st, Updater.installed(ctx))
    val busy = st is State.Checking || st is State.Downloading || st is State.Installing
    val bad = st is State.Failed
    Column(Modifier.widthIn(max = 330.dp).fillMaxWidth().pageGlass(c, PageGlass.row, strongRim = bad).padding(14.dp)) {
        // v0.28.1: sentence case (no shouty "UPDATE DIDN'T WORK"), plain words.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) Shimmer(title, Modifier.weight(1f))
            else Text(title, modifier = Modifier.weight(1f), maxLines = 1,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = if (bad) c.accent else c.ink))
        }
        Spacer(Modifier.height(6.dp))
        Text(line, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.body))
        if (pct >= 0) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(BlazeShapes.pill).background(c.faint)) {
                Box(Modifier.fillMaxWidth(pct / 100f).height(4.dp).clip(BlazeShapes.pill).background(c.accent))
            }
        }
        val action = when (st) {
            is State.Failed, is State.NeedsPermission -> "Retry"
            is State.Ready -> "Install"
            is State.Idle, is State.UpToDate -> "Check again"
            else -> null
        }
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            Text(action, color = c.accent, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), modifier = Modifier.hairline(c.accent).clip(BlazeShapes.pill).clickable { Updater.retry(ctx) }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/** v0.28.1: the card's words, sentence case and plain (pure, tested in UpdateDownloadTest). Title, line, progress (-1 = none). */
object UpdateCardText {
    fun of(st: State, have: String): Triple<String, String, Int> = when (val s = st) {
        is State.Idle -> Triple("Blaze update", "You're on v$have.", -1)
        is State.Checking -> Triple("Checking for an update", "Looking for a newer Blaze\u2026", -1)
        is State.Downloading -> Triple("Downloading ${s.pct}%", s.note.ifBlank { "Blaze v${s.version}. Works on mobile data too." }, s.pct)
        is State.Installing -> Triple("Installing", "Confirm on the Android screen. Blaze restarts by itself, and your keys, settings and models stay.", 100)
        is State.UpToDate -> Triple("Up to date", "You have the newest Blaze, v${s.version}.", -1)
        is State.Ready -> Triple("Update available", "Blaze v${s.version} is ready to install.", -1)
        is State.NeedsPermission -> Triple("One permission needed", "Android asks once before Blaze can install its own updates. Turn on \"Allow from this source\" for Blaze, come back, then tap Retry.", -1)
        is State.Done -> Triple("Updated", "Blaze v${s.version} is installed.", 100)
        is State.Failed -> Triple("The update didn't finish", s.message, -1)
    }
}

/** v0.45.4: "Blaze X.Y.Z is available · Update · ×", shown at most once a day on app open (Updater.checkInBackground). */
@Composable
fun UpdateBanner() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val b by Updater.banner.collectAsState()
    val banner = b ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).glass(GlassTokens.tileShape, GlassIntensity.Medium, shadow = false).padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(banner.line, size = 10.sp, modifier = Modifier.weight(1f))
            ActionText("Update", color = c.accent, size = 10.sp, modifier = Modifier.clickable { Updater.updateFromBanner(ctx) }.padding(8.dp))
            ActionText("\u00d7", color = c.sub, size = 12.sp, modifier = Modifier.clickable { Updater.dismissBanner() }.padding(8.dp))
        }
        if (banner.notes.isNotBlank()) Text(banner.notes, modifier = Modifier.padding(bottom = 4.dp), maxLines = 2,
            style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, lineHeight = 15.sp, color = c.sub))
    }
}
