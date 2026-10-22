package com.blaze.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.ChatMessage

/**
 * Calm steps: while Blaze works, one shimmering plain-English line ("Searching the web for …").
 * When done, "Worked for 12s · 5 steps"; tap to see each step in plain words. Exact calls and
 * commands only show with Settings › "Show every step" (StepsGroup), never here.
 */
@Composable
fun CalmSteps(steps: List<ChatMessage>, live: Boolean, endAt: Long) {
    val c = LocalBlaze.current
    var open by rememberSaveable(steps.first().id) { mutableStateOf(false) }
    val n = steps.size
    val count = if (n == 1) "1 step" else "$n steps"
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (live) c.accent else c.weak, BlazeShapes.pill))
            Spacer(Modifier.width(10.dp))
            if (live && !open) Shimmer(steps.last().text, Modifier.weight(1f))
            else {
                val start = steps.first().at
                val secs = if (start > 0 && endAt >= start) (endAt - start) / 1000 else -1
                val head = when {
                    live -> steps.last().text + " · $count"
                    secs >= 60 -> "Worked for ${secs / 60}m ${secs % 60}s · $count"
                    secs >= 1 -> "Worked for ${secs}s · $count"
                    else -> "Worked · $count"
                }
                Text(head, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
            }
            Text(if (open) "⌃" else "⌄", modifier = Modifier.padding(start = 6.dp), style = TextStyle(fontSize = 13.sp, color = c.sub))
        }
        AnimatedVisibility(open, enter = MotionTokens.expand(), exit = MotionTokens.collapse()) {
            Column(Modifier.padding(start = 3.dp).drawBehind { drawRect(c.hair, Offset.Zero, Size(1.dp.toPx(), size.height)) }
                .padding(start = 13.dp, top = 2.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                steps.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.Top) {
                        Label("%02d".format(i + 1), color = c.weak, size = 10.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(s.text, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Shimmer(text: String, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-400f, 1200f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "x")
    val brush = Brush.linearGradient(listOf(c.sub, c.accent, c.sub), start = Offset(x, 0f), end = Offset(x + 320f, 0f))
    Text(text, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = TextStyle(brush = brush, fontFamily = Type.Sans, fontSize = 13.sp))
}
