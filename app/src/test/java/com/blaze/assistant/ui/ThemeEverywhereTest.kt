package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import com.blaze.assistant.home.StackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.45.8 one theme everywhere (user screenshots 07:28: Projects flat dark, Home navy, Chat desert).
 *
 * - One source of truth: [AppTheme] answers the same for every screen, and every screen's source reads it.
 * - Every switcher writes through [AppTheme.pick]; a pick is visible to every reader at once.
 * - The Terminal palette is derived from the theme with 4.5:1 or more for every text colour.
 * - Home: the strip sits on Home's ground; the ghost stack shows only strips below the main card.
 *
 * What this does NOT cover: rendered pixels (no ShotsTest on Linux aarch64) and two activities on screen at once.
 */
class ThemeEverywhereTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    /** Each screen in [AppTheme.SCREENS] -> the file that draws it and what in it must read the shared theme. */
    private val readers = mapOf(
        "home" to listOf("ui/HomeBoardUi.kt" to "themeOn()", "ui/SimpleUi.kt" to "bottom = {"),
        "projects" to listOf("ui/Shell.kt" to "screenGround {"),
        "chat" to listOf("ui/ChatScreen.kt" to "themeOn()"),
        "settings" to listOf("ui/SettingsSheet.kt" to "ThemedScreen("),
        "sheets" to listOf("mediaapps/MediaUi.kt" to "themeOn()", "terminal/TerminalSheet.kt" to "TermPalette"),
        "music" to listOf("mediaapps/MusicActivity.kt" to "ThemedScreen("),
        "video" to listOf("mediaapps/VideoActivity.kt" to "ThemedScreen("),
        "design" to listOf("ui/DesignView.kt" to "screenGround {"),
        "terminal" to listOf("terminal/BlazeTerminalActivity.kt" to "TermPalette.of(com.blaze.assistant.ui.Look.theme.value"),
    )

    @Test fun oneRuleForEveryScreen() {
        for (th in LivingThemes.PICKER) {
            val grounds = AppTheme.SCREENS.map { AppTheme.ground(th.id, it) }.toSet()
            assertEquals("${th.name}: every screen must agree", 1, grounds.size)
            val want = if (th.id == LivingThemes.CLASSIC) AppTheme.Ground.OWN else AppTheme.Ground.SCENE
            assertEquals(th.name, want, grounds.single())
        }
        // The user's case: desert on every screen, none keeping its own ground.
        for (s in AppTheme.SCREENS) assertEquals(s, AppTheme.Ground.SCENE, AppTheme.ground("desert_dune", s))
        assertEquals(AppTheme.Ground.OWN, AppTheme.ground("not-a-theme", "home"))
    }

    @Test fun everyScreenReadsTheSharedTheme() {
        assertEquals(AppTheme.SCREENS.toSet(), readers.keys)
        for ((screen, files) in readers) for ((f, needle) in files) assertTrue("$screen: $f must use $needle", needle in src(f))
        // The old fixed grounds are gone where the screenshots showed them.
        val shell = src("ui/Shell.kt")
        assertFalse("Projects still paints its own flat ground", "Column(Modifier.fillMaxSize().background(c.paper).glassBackground(c.dark)) {\n        if (open == null)" in shell)
        assertFalse("Music still paints c.paper over the theme", "Box(Modifier.fillMaxSize().background(c.paper)) {\n        Column(Modifier.fillMaxSize().statusBarsPadding())" in src("mediaapps/MusicActivity.kt"))
        assertFalse("Terminal colours still fixed", "private val accent = Color.parseColor" in src("terminal/BlazeTerminalActivity.kt"))
        // Home draws its look only without a theme.
        assertTrue("if (themed) Modifier else Modifier.homeBackground(" in src("ui/HomeBoardUi.kt"))
    }

    @Test fun everyScreenHasTheSwitcher() {
        val switchers = mapOf(
            "home" to "ui/HomeBoardUi.kt", "projects" to "ui/Shell.kt", "settings" to "ui/SettingsSheet.kt",
            "music" to "mediaapps/MusicActivity.kt", "video" to "mediaapps/VideoActivity.kt", "design" to "ui/DesignView.kt")
        for ((s, f) in switchers) assertTrue("$s has no ThemeSwitch", "ThemeSwitch(" in src(f))
        // Chat: the profile menu's Themes (the carousel, the same list); Terminal: ⋮ › Theme.
        assertTrue("MenuItem(\"themes\", \"Themes\"" in src("ui/ChatScreen.kt"))
        assertTrue("themeMenu()" in src("terminal/BlazeTerminalActivity.kt"))
        // Every switcher writes through the one path.
        for (f in listOf("ui/AppTheme.kt", "ui/ThemePicker.kt", "terminal/BlazeTerminalActivity.kt")) assertTrue("$f bypasses AppTheme.pick", "AppTheme.pick(" in src(f))
        assertEquals(LivingThemes.PICKER, AppTheme.options)
    }

    @Test fun aSwitchAnywhereShowsEverywhere() {
        val before = Look.theme.value to ThemeMode.mode.value
        try {
            // Start dark on a dark theme.
            AppTheme.pick("deep_sea"); ThemeMode.set("dark")
            // "From Music": a light theme. Every reader sees it: the flows Chat / Home / Video observe, the Terminal's palette.
            AppTheme.pick("desert_dune")
            assertEquals("desert_dune", Look.theme.value)
            assertEquals("desert_dune", AppTheme.current())
            assertEquals("light", ThemeMode.mode.value)
            assertEquals("Desert Dune", ThemeTint.current.value?.name)
            assertTrue(AppTheme.themed(Look.theme.value))
            assertFalse(AppTheme.darkNow())
            val term = TermPalette.of(Look.theme.value, AppTheme.darkNow())
            assertFalse(term.dark)
            // "From the Terminal": a dark theme flips back to dark.
            AppTheme.pick("neon_rain")
            assertEquals("neon_rain", Look.theme.value); assertEquals("dark", ThemeMode.mode.value)
            assertTrue(TermPalette.of(Look.theme.value, AppTheme.darkNow()).dark)
            // Classic: every screen gets its own ground back; the mode stays.
            AppTheme.pick(LivingThemes.CLASSIC)
            assertEquals(LivingThemes.CLASSIC, Look.theme.value); assertEquals("dark", ThemeMode.mode.value)
            assertFalse(AppTheme.themed(Look.theme.value)); assertEquals(null, ThemeTint.current.value)
        } finally { Look.theme.value = before.first; ThemeTint.set(before.first); ThemeMode.mode.value = before.second }
    }

    @Test fun terminalPaletteKeepsAa() {
        var checked = 0
        for (th in LivingThemes.PICKER) for (dark in listOf(true, false)) {
            val p = TermPalette.of(th.id, dark)
            val tag = "${th.name} ${if (dark) "dark" else "light"}"
            assertTrue("$tag text ${TermPalette.ratio(p.fg, p.bg)}", TermPalette.ratio(p.fg, p.bg) >= 7.0)
            for ((name, col) in listOf("cursor" to p.cursor, "accent" to p.accent, "dim" to p.dim))
                assertTrue("$tag $name ${TermPalette.ratio(col, p.bg)}", TermPalette.ratio(col, p.bg) >= TermPalette.MIN)
            for (i in 1 until 16) assertTrue("$tag ANSI $i ${TermPalette.ratio(p.ansi[i], p.bg)}", TermPalette.ratio(p.ansi[i], p.bg) >= TermPalette.MIN)
            // ANSI 0 is a background slot: text on it reads.
            assertTrue("$tag text on ANSI 0", TermPalette.ratio(TermPalette.onAnsi0(p), p.ansi[0]) >= TermPalette.MIN)
            // Key labels on key caps and on the bars; the selected tab's label (the ground colour) on the accent.
            assertTrue("$tag keys", TermPalette.ratio(p.fg, p.keyBg) >= TermPalette.MIN)
            assertTrue("$tag bar", TermPalette.ratio(p.fg, p.barBg) >= TermPalette.MIN)
            assertTrue("$tag selected tab", TermPalette.ratio(p.bg, p.accent) >= TermPalette.MIN)
            assertTrue("$tag sheet rows", TermPalette.ratio(p.fg, p.row) >= TermPalette.MIN && TermPalette.ratio(p.dim, p.row) >= 3.0)
            assertEquals(dark, p.dark)
            checked++
        }
        assertEquals(LivingThemes.PICKER.size * 2, checked)
    }

    @Test fun terminalFollowsTheTheme() {
        val desert = TermPalette.of("desert_dune", true)
        val sea = TermPalette.of("deep_sea", true)
        assertTrue("themes differ", desert.bg != sea.bg)
        assertEquals(Color(LivingThemes.of("desert_dune").ground.first()).copy(alpha = 1f), Color(desert.bg))
        // Classic keeps the near-black terminal it always had.
        assertEquals(Color(0xFF0B0B0D), Color(TermPalette.of(LivingThemes.CLASSIC, true).bg))
        // The accent stays the theme's hue where it already reads.
        assertEquals(Color(LivingThemes.of("desert_dune").accent), Color(desert.accent))
    }

    @Test fun ghostStackShowsOnlyStrips() {
        assertTrue(StackState.strips(1).isEmpty())
        val s = StackState.strips(5)
        assertEquals(2, s.size)
        assertEquals(8 * StackState.SHIFT, s[0].shiftDp, 0.001f); assertEquals(s[0].shiftDp, s[0].visibleDp, 0.001f)
        assertEquals(16 * StackState.SHIFT, s[1].shiftDp, 0.001f); assertEquals(s[1].shiftDp - s[0].shiftDp, s[1].visibleDp, 0.001f)
        // The strips never overlap the main card or each other, and fit the 26 dp gap under a collapsed stack.
        assertTrue(s.sumOf { it.visibleDp.toDouble() } <= 26.0)
        assertTrue("drawWithContent" in src("ui/HomeBoardUi.kt") && "st.visibleDp" in src("ui/HomeBoardUi.kt"))
    }
}
