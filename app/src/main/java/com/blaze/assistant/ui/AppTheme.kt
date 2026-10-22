package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v0.45.8 one theme everywhere. The picked theme ([Look.theme], pref `look_theme`) and light / dark ([ThemeMode]) are
 * the ONE source of truth. Every screen and view reads them: Home, Projects, Chat, Settings, sheets, Music, Video,
 * Design and the Terminal. Before this, Projects drew its own flat dark ground, Home drew its Home look (navy) and
 * only Chat showed the theme's scene.
 *
 * Every theme switcher (the carousel, [ThemeSwitch] on each screen, the Terminal's menu) writes through [pick], so a
 * change made anywhere shows everywhere: the state lives in process-wide flows that every activity observes.
 * ThemeEverywhereTest checks the rule, the write path, and that each screen's source reads it.
 */
object AppTheme {
    /** Every screen and view that follows the theme (ThemeEverywhereTest maps each to the file that draws it). */
    val SCREENS = listOf("home", "projects", "chat", "settings", "sheets", "music", "video", "design", "terminal")

    enum class Ground {
        /** A living theme is picked: the screen is see-through over the theme's scene (or draws the scene itself). */
        SCENE,
        /** Classic: the screen keeps its own ground as before (Look & feel background, the Home look, the glass page). */
        OWN,
    }

    /** Pure: the same answer for every screen, so no screen can disagree with another. */
    fun ground(themeId: String?, @Suppress("UNUSED_PARAMETER") screen: String = ""): Ground =
        if (LivingThemes.of(themeId).motion != LivingThemes.Motion.NONE) Ground.SCENE else Ground.OWN

    fun themed(themeId: String?): Boolean = ground(themeId) == Ground.SCENE

    /** The options every switcher offers: exactly the carousel's list. */
    val options: List<LivingThemes.Theme> get() = LivingThemes.PICKER

    fun current(): String = Look.theme.value

    /** The single write path. Light themes switch to light mode (and back), the same rule as the carousel. */
    fun pick(id: String) {
        val th = LivingThemes.of(id)
        Look.setTheme(th.id)
        val m = LivingThemes.modeAfterPick(th, ThemeMode.mode.value)
        if (m != ThemeMode.mode.value) ThemeMode.set(m)
    }

    /** Whether the app draws dark now (for non-Compose screens: the Terminal). Same rule as [BlazeTheme]. */
    fun darkNow(hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): Boolean =
        when (ThemeMode.mode.value) { "dark" -> true; "light" -> false; "auto" -> LivingLogic.darkFor("auto", hour); else -> true }
}

/** Whether a living theme is picked right now (recomposes on change, from any screen or activity). */
@Composable
fun themeOn(): Boolean {
    val id by Look.theme.collectAsState()
    return AppTheme.themed(id)
}

/**
 * A screen that sits INSIDE a themed host (the chat screen already draws the scene): with a theme its own opaque ground
 * goes away so the one scene shows through; Classic keeps [own].
 */
@Composable
fun Modifier.screenGround(own: Modifier.() -> Modifier): Modifier = if (themeOn()) this else this.own()

/**
 * A screen that stands ALONE (its own activity, or a full-screen layer over another screen): with a theme it draws the
 * theme's scene itself over the page colour (opaque, so nothing underneath leaks through); Classic keeps [own].
 */
@Composable
fun ThemedScreen(modifier: Modifier, own: Modifier.() -> Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = LocalBlaze.current
    val on = themeOn()
    Box(if (on) modifier.background(c.paper) else modifier.own()) {
        if (on) LivingBackground()
        content()
    }
}

/**
 * v0.45.8: the theme switcher on every screen (top bar). The same options as the carousel; picking one goes through
 * [AppTheme.pick], so every other screen changes too.
 */
@Composable
fun ThemeSwitch(modifier: Modifier = Modifier, tint: Color = LocalBlaze.current.ink) {
    var open by remember { mutableStateOf(false) }
    val current by Look.theme.collectAsState()
    val name = LivingThemes.of(current).name
    Box(modifier) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Change theme") { open = true }
            .semantics { contentDescription = "Theme: $name. Change theme" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Palette, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (th in AppTheme.options) {
                val on = th.id == current
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(th.accent)))
                            Spacer(Modifier.width(10.dp))
                            Text(th.name, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp))
                        }
                    },
                    trailingIcon = if (on) ({ Icon(Icons.Rounded.Check, contentDescription = "In use", modifier = Modifier.size(18.dp)) }) else null,
                    onClick = { open = false; AppTheme.pick(th.id) },
                    modifier = Modifier.semantics { contentDescription = th.name + if (on) ", in use" else "" })
            }
        }
    }
}
