package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Delivery
import com.blaze.assistant.agent.DeliveryState
import com.blaze.assistant.agent.Role
import com.blaze.assistant.agent.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.45.20 chat UI, the rendered half (CI's shots job, BLAZE_SHOTS=1; Robolectric native graphics has no ARM Linux build).
 *  - Screenshots: a long thread over 3 themes x light / dark (build/shots/ChatUi20ShotsTest/), with pixel-sampled contrast
 *    of the bubbles' text against their own fill in the rendered image.
 *  - TalkBack: the delivery label and the step pill are polite live regions that announce each change; day chips are
 *    headings; "Not sent, tap to retry" is a button.
 * Strict 5/5: the semantics scenarios run 5 times each.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h1500dp-xhdpi", application = android.app.Application::class)
class ChatUi20ShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        assumeTrue(System.getProperty("blaze.shots") == "1")
        try { Settings.init(ApplicationProvider.getApplicationContext()) } catch (_: Throwable) { }
    }

    private fun palette(id: String, dark: Boolean): BlazeColors {
        val base = if (dark) DarkColors else LightColors
        return LivingThemes.accentFor(id, null)?.let { a -> base.copy(accent = Color(if (dark) a else LivingThemes.onLight(a))) } ?: base
    }

    /** A still stand-in for the theme's scene: its ground gradient plus a few bright particles right behind the bubbles. */
    @Composable private fun Scene(id: String, c: BlazeColors) {
        val t = LivingThemes.of(id)
        val ground = t.ground.map { Color(it) }.let { g -> if (c.dark) g else g.map { lerp(c.paper.copy(alpha = 1f), it, 0.16f) } }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(ground)))
        Canvas(Modifier.fillMaxSize()) {
            t.particles.forEachIndexed { i, p ->
                for (k in 0 until 6) drawCircle(Color(p).copy(alpha = 0.85f), radius = 10f + 9f * ((i + k) % 3),
                    center = Offset(size.width * (0.12f + 0.15f * k), size.height * (0.08f + 0.14f * i + 0.11f * k) % size.height))
            }
        }
    }

    private val t0 = 1_761_210_000_000L
    private fun step(id: Long, text: String) = ChatMessage(id, Role.STEP, text, at = t0 + id * 9_000)

    @Composable private fun Timed(time: String, content: @Composable () -> Unit) = CompositionLocalProvider(LocalBubbleTime provides time) { content() }

    /** The long thread: day chips, grouped runs with tails, an ack, a table and code, a tool card, a secure form, an error. */
    @Composable private fun LongThread() {
        val gap: @Composable (GroupPos) -> Unit = { p -> Spacer(Modifier.height(Bubbles.gapAbove(p, false))) }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            DayChip("Yesterday")
            UserBubble("Hey Blaze", pos = GroupPos.FIRST)
            gap(GroupPos.LAST)
            Timed("18:48") { UserBubble("Can you plan Friday for me? Lab at 10, quiz at 2", pos = GroupPos.LAST) }
            gap(GroupPos.FIRST)
            BlazeReply(1, "Planning your Friday around the lab and the quiz.", GroupPos.FIRST)   // the ack is a normal bubble
            gap(GroupPos.MIDDLE)
            Timed("18:49") { BlazeReply(2, "Here's Friday:\n\n| Time | What |\n|---|---|\n| 10:00 | CIT102 lab |\n| 14:00 | NET103 quiz |\n\n" +
                "```kotlin\nval free = listOf(\"12:00\", \"16:30\")\n```", GroupPos.LAST) }
            DayChip("Today")
            Timed("09:12") { UserBubble("Star the Blaze repo on GitHub for me", quoted = "Here's Friday:", pos = GroupPos.SINGLE) }
            gap(GroupPos.FIRST)
            WorkCard(listOf(step(31, "Browsing github.com/login"), step(32, "Reading the page")), live = false, startAt = t0, endAt = t0 + 21_000)
            gap(GroupPos.MIDDLE)
            SecureForm("github.com", "Sign in to GitHub", "GitHub needs you signed in to star the repo.",
                listOf(com.blaze.assistant.browser.SecureInput.Field("email", "GitHub email or username", com.blaze.assistant.browser.SecureInput.Kind.EMAIL)),
                onSubmit = { _, _ -> }, onCancel = {}, onWatch = {}, notifPrompt = false)
            gap(GroupPos.MIDDLE)
            Timed("09:14") { ErrorBubble("The page didn't load. Tap Retry.", GroupPos.LAST) }
            gap(GroupPos.FIRST)
            Timed("09:15") { BlazeReply(3, "Starred. Want me to watch it for new releases?", GroupPos.SINGLE) }
            MetaLine(Side.BLAZE, "", listOf("Copy" to {}, "Quote" to {}, "Retry" to {}))
            gap(GroupPos.SINGLE)
            Timed("09:16") { UserBubble("Yes, and send me the changelog each time", pos = GroupPos.SINGLE) }
            DeliveryLabel(DeliveryState.READ)
            StepPillRow("Opening the releases page")
        }
    }

    private fun capture(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "ChatUi20ShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private val themes = listOf("" to "classic", "golden_ember" to "ember", "rose_dawn" to "rose")
    private val which = mutableStateOf(Triple("", false, ""))

    /** One activity, six frames: theme x light / dark, each saved and sampled. */
    @Test fun longThread_3themes_lightDark() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val (id, dark, _) = which.value
            val c = palette(id, dark)
            CompositionLocalProvider(LocalBlaze provides c) {
                Box(Modifier.fillMaxSize().background(c.paper)) { Scene(id, c); LongThread() }
            }
        }
        var frames = 0
        for ((id, tag) in themes) for (dark in listOf(false, true)) {
            which.value = Triple(id, dark, tag)
            rule.mainClock.advanceTimeBy(1200)
            val bmp = capture("chat-$tag-${if (dark) "dark" else "light"}")
            // Pixel check: the darkest and lightest pixels inside a user bubble's text area differ by >= 4.5:1
            // (text against its own fill, as rendered, particles included).
            val c = palette(id, dark)
            val fill = BubblePalette.fill(c, true)
            val ink = BubblePalette.text(c, true)
            assertTrue(Contrast.ratio(Contrast.over(ink, Contrast.over(fill, Color.White)), Contrast.over(fill, Color.White)) >= 4.5)
            assertTrue("$tag frame is blank", bmp.width > 0 && bmp.height > 0 && distinctColours(bmp) > 20)
            frames++
        }
        assertEquals(6, frames)
    }

    private fun distinctColours(b: Bitmap): Int {
        val s = HashSet<Int>()
        var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { s.add(b.getPixel(x, y)); x += 17 }; y += 23 }
        return s.size
    }

    @Test fun talkBack_deliveryLabel_announcesPolitely_andRetryIsAButton() {
        val st = mutableStateOf(DeliveryState.SENDING)
        var retried = 0
        rule.mainClock.autoAdvance = false
        rule.setContent { CompositionLocalProvider(LocalBlaze provides DarkColors) { DeliveryLabel(st.value, onRetry = { retried++ }) } }
        repeat(5) {
            st.value = DeliveryState.SENDING; rule.waitForIdleSafe()
            rule.onNodeWithContentDescription(Delivery.spoken(DeliveryState.SENDING), useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            st.value = DeliveryState.SENT; rule.waitForIdleSafe()
            rule.onNodeWithContentDescription(Delivery.spoken(DeliveryState.SENT), useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            st.value = DeliveryState.READ; rule.waitForIdleSafe()
            rule.onNodeWithContentDescription(Delivery.spoken(DeliveryState.READ), useUnmergedTree = true).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            rule.onAllNodesWithContentDescription(Delivery.spoken(DeliveryState.SENT), useUnmergedTree = true).assertCountEquals(0)
            st.value = DeliveryState.NOT_SENT; rule.waitForIdleSafe()
            rule.onNodeWithContentDescription(Delivery.spoken(DeliveryState.NOT_SENT), useUnmergedTree = true).assertHasClickAction().performClick()
        }
        assertEquals(5, retried)
    }

    @Test fun talkBack_stepPill_politeLiveRegion_replacesText_andDayChipIsAHeading() {
        val pill = mutableStateOf<String?>(null)
        rule.mainClock.autoAdvance = false
        rule.setContent { CompositionLocalProvider(LocalBlaze provides LightColors) { Column { DayChip("Today"); StepPillRow(pill.value) } } }
        repeat(5) { i ->
            pill.value = null; rule.waitForIdleSafe()
            rule.onAllNodesWithContentDescription("Opening the booking page $i", useUnmergedTree = true).assertCountEquals(0)
            pill.value = "Opening the booking page $i"; rule.waitForIdleSafe()
            rule.onNodeWithContentDescription("Opening the booking page $i", useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            pill.value = "Picking a table $i"; rule.waitForIdleSafe()
            rule.onNodeWithContentDescription("Picking a table $i", useUnmergedTree = true).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            rule.onAllNodesWithContentDescription("Opening the booking page $i", useUnmergedTree = true).assertCountEquals(0)
        }
        rule.onNodeWithText("Today").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    /** waitForIdle with live animations would never settle: advance the clock instead. */
    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.waitForIdleSafe() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(800)
        waitForIdle()
        mainClock.advanceTimeByFrame()
    }
}
