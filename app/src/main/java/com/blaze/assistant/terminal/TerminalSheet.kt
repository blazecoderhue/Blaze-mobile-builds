package com.blaze.assistant.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.DarkColors
import com.blaze.assistant.ui.MotionTokens
import com.blaze.assistant.ui.pressScale

/**
 * v0.29.8: Blaze Terminal's menus as a Blaze bottom sheet (rounded, dark, Blaze blue, an icon per row, MotionTokens
 * timing) instead of the plain white Android pop-up that clashed with the black terminal. Used for ⋮ and +.
 */
object TermSheet {
    /** A row in the sheet. [icon] names one of [iconFor]'s keys so the activity stays free of Compose types. */
    data class Item(val id: String, val label: String, val hint: String = "", val icon: String = "", val danger: Boolean = false)

    class State {
        var open by mutableStateOf(false)
        var title by mutableStateOf("")
        var subtitle by mutableStateOf("")
        var items by mutableStateOf(emptyList<Item>())
        var onPick: (Item) -> Unit = {}
        fun show(title: String, subtitle: String, items: List<Item>, onPick: (Item) -> Unit) {
            this.title = title; this.subtitle = subtitle; this.items = items; this.onPick = onPick; open = true
        }
        fun close() { open = false }
    }

    /**
     * The terminal's palette. v0.45.8: derived from the picked theme ([com.blaze.assistant.ui.TermPalette]); the
     * activity sets it on start and whenever the theme changes, and the sheet recomposes.
     */
    object Palette {
        private var p by mutableStateOf(com.blaze.assistant.ui.TermPalette.of(com.blaze.assistant.ui.LivingThemes.CLASSIC, true))
        fun set(colors: com.blaze.assistant.ui.TermPalette.Colors) { p = colors }
        val bg: Color get() = Color(p.bg)
        val sheet: Color get() = Color(p.sheet)
        val row: Color get() = Color(p.row)
        val ink: Color get() = Color(p.fg)
        val sub: Color get() = Color(p.dim)
        val hair: Color get() = Color(p.keyEdge)
        val accent: Color get() = Color(p.accent)
        val danger: Color get() = if (p.dark) Color(0xFFFF6B6B) else Color(0xFFC62828)
    }

    fun iconFor(key: String): ImageVector = when (key) {
        "paste" -> Icons.Rounded.ContentPaste
        "copy" -> Icons.Rounded.ContentCopy
        "keyboard" -> Icons.Rounded.Keyboard
        "bigger" -> Icons.Rounded.ZoomIn
        "smaller" -> Icons.Rounded.ZoomOut
        "restart" -> Icons.Rounded.Refresh
        "close" -> Icons.Rounded.Close
        "help" -> Icons.Rounded.Info
        "phone" -> Icons.Rounded.PhoneAndroid
        "linux" -> Icons.Rounded.Terminal
        "install" -> Icons.Rounded.Download
        "theme" -> Icons.Rounded.Palette
        else -> Icons.Rounded.Add
    }
}

@Composable
fun TermSheetHost(state: TermSheet.State) {
    val p = TermSheet.Palette
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(state.open, enter = MotionTokens.fade(), exit = MotionTokens.fadeAway()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { state.close() })
        }
        AnimatedVisibility(
            state.open, modifier = Modifier.align(Alignment.BottomCenter),
            enter = if (MotionTokens.enabled) slideInVertically(tween(MotionTokens.LONG, easing = MotionTokens.Decelerate)) { it } + fadeIn(tween(MotionTokens.MEDIUM)) else MotionTokens.fade(),
            exit = if (MotionTokens.enabled) slideOutVertically(tween(MotionTokens.MEDIUM, easing = MotionTokens.Accelerate)) { it } + fadeOut(tween(MotionTokens.SHORT)) else MotionTokens.fadeAway(),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(p.sheet)
                    .border(1.dp, p.hair, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { }
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.hair))
                Spacer(Modifier.height(12.dp))
                if (state.title.isNotEmpty()) Text(state.title, color = p.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp))
                if (state.subtitle.isNotEmpty()) Text(state.subtitle, color = p.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp))
                Spacer(Modifier.height(10.dp))
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (item in state.items) SheetRow(item) { state.close(); state.onPick(item) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun SheetRow(item: TermSheet.Item, onClick: () -> Unit) {
    val p = TermSheet.Palette
    val src = remember { MutableInteractionSource() }
    val tint = if (item.danger) p.danger else p.accent
    Row(
        Modifier.fillMaxWidth().pressScale(src).clip(RoundedCornerShape(16.dp)).background(p.row)
            .clickable(src, indication = null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(TermSheet.iconFor(item.icon), contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.label, color = if (item.danger) p.danger else p.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.hint.isNotEmpty()) Text(item.hint, color = p.sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
