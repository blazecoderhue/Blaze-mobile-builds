package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.phone.PhoneControl

/** "Android switched off Blaze phone control · Turn on" (v0.20.0): which way it dropped, one-tap fix, and why. */
@Composable
fun PhoneControlBanner(state: PhoneControl.State, onFix: () -> Unit) {
    val c = LocalBlaze.current
    val (text, action) = PhoneControl.banner(state)
    if (text.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).glass(GlassTokens.tileShape, GlassIntensity.Medium, shadow = false).padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(text, size = 10.sp, modifier = Modifier.weight(1f))
            ActionText(action, color = c.accent, size = 10.sp, modifier = Modifier.clickable(onClick = onFix).padding(8.dp))
        }
        if (state == PhoneControl.State.OFF) Text(PhoneControl.TIP, modifier = Modifier.padding(bottom = 4.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, lineHeight = 15.sp, color = c.sub))
    }
}
