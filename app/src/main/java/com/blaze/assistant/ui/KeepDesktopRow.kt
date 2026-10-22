package com.blaze.assistant.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.desktop.DesktopAlive
import com.blaze.assistant.desktop.KeepAlive

/**
 * v0.45.2 "Keep desktop running" switch (Desktop page and Settings). Turning it on the first time asks Android to let
 * Blaze skip battery optimisation; the copy says plainly that Android 12+ can still close it in rare cases.
 */
@Composable
fun KeepDesktopRow(first: Boolean = true) {
    val ctx = LocalContext.current
    val c = LocalBlaze.current.mono
    val on by DesktopAlive.on.collectAsState()
    Rows {
        KitRow(KeepAlive.TITLE, sub = KeepAlive.SUB, first = first,
            trailing = { KitSwitch(on) { if (DesktopAlive.set(ctx, !on)) DesktopAlive.requestExemption(ctx) } })
    }
    if (on) {
        Spacer(Modifier.height(6.dp))
        Text(KeepAlive.CAVEAT + if (!DesktopAlive.exempt(ctx)) " Blaze isn't allowed to skip battery optimisation yet, so Android may close it sooner." else "",
            modifier = Modifier.padding(horizontal = 4.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 17.sp, color = c.sub))
        if (!DesktopAlive.exempt(ctx)) BlazeButton("Allow background running", kind = BtnKind.GHOST) { DesktopAlive.requestExemption(ctx) }
    }
}
