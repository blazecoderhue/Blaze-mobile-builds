package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Mood
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.fillMaxSize().pageBackdrop(c)) {
        ParticleField(Mood.IDLE, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(Mood.IDLE, 56.dp)
            Spacer(Modifier.height(20.dp))
            Text("Blaze is locked", style = TextStyle(fontFamily = Type.Display, fontSize = 24.sp, color = c.ink))
            Spacer(Modifier.height(18.dp))
            Box(Modifier.clip(BlazeShapes.pill).glassButton(c).clickable(onClick = onUnlock).padding(horizontal = 22.dp, vertical = 14.dp)) {
                ButtonText("Unlock", color = Color.White)
            }
        }
    }
}
