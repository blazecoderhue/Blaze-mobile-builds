package com.blaze.assistant.ui

import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Role
import com.blaze.assistant.agent.ToolLeak
import com.blaze.assistant.browser.phone.PhoneBrowserTools
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.19 chat fix 4: the chat sweep. Blaze Phone's status pill was white on light grey; element refs ("Tapping e61")
 * reached the status line; Halo's bubble and Blaze's answer shared one group (Blaze's words under Halo's name, one action
 * row); raw call text must never reach a bubble. Strict 5/5.
 */
class ChatFixes19Test {

    private fun fiveTimes(body: (Int) -> Unit) = repeat(5) { body(it) }

    @Test fun statusPillIsReadable() = fiveTimes {
        val r = Contrast.ratio(StatusPillColors.fg, StatusPillColors.bg)
        assertTrue("pill text contrast $r", r >= Contrast.AA_TEXT)
        assertTrue(Contrast.ratio(StatusPillColors.dot, StatusPillColors.bg) >= Contrast.AA_UI)
        // The old white-on-light pill fails, which is why it changed.
        assertTrue(Contrast.ratio(androidx.compose.ui.graphics.Color.White, StatusPillColors.bg) < Contrast.AA_UI)
    }

    @Test fun statusLineNeverShowsElementRefs() = fiveTimes {
        fun tap(t: String) = PhoneBrowserTools.describe("phone_act", JSONObject().put("action", "click").put("target", t))
        for (ref in listOf("e61", "E3", "[e12]", "ref=e7", "#e9", "#submit", ".btn-primary", "//button[1]", "button[type='submit']", "div > a", "li:nth-child(2)"))
            assertEquals(ref, "Tapping on the page", tap(ref))
        assertEquals("Tapping Reserve Now", tap("Reserve Now"))
        assertEquals("Tapping Logout", tap("Logout"))
        assertEquals("Choosing an option", PhoneBrowserTools.describe("phone_act", JSONObject().put("action", "select").put("option", "e44")))
        assertEquals("Choosing 2 nights", PhoneBrowserTools.describe("phone_act", JSONObject().put("action", "select").put("option", "2 nights")))
    }

    @Test fun haloAndBlazeNeverShareAGroup() = fiveTimes { r ->
        val t = 1_000_000L + r
        val msgs = listOf(
            ChatMessage(1, Role.USER, "cancle booking", at = t),
            ChatMessage(2, Role.BLAZE, "I can see the reservation page.", at = t + 1_000, speaker = "halo"),
            ChatMessage(3, Role.BLAZE, "No, the booking was not completed.", at = t + 2_000),
        )
        val items = buildItems(msgs, working = false)
        assertEquals(listOf(GroupPos.SINGLE, GroupPos.SINGLE, GroupPos.SINGLE), items.map { it.pos })
        // Blaze's own ack and result still share one group: one timestamp / action row, on the last one.
        val ackThenResult = listOf(
            ChatMessage(1, Role.USER, "turn on dark mode", at = t),
            ChatMessage(2, Role.BLAZE, "Turning on dark mode.", meta = "ack", at = t + 2_100),
            ChatMessage(3, Role.BLAZE, "Done \u2713 Dark mode is on.", at = t + 4_000),
        )
        assertEquals(listOf(GroupPos.SINGLE, GroupPos.FIRST, GroupPos.LAST), buildItems(ackThenResult, working = false).map { it.pos })
        // The incremental builder agrees with the full build.
        val b = ItemBuilder()
        assertEquals(items.map { it.pos }, b.build(msgs, working = false, hide = false).map { it.pos })
        assertEquals(listOf(GroupPos.SINGLE, GroupPos.SINGLE), Bubbles.positions(listOf(Side.BLAZE, Side.BLAZE), listOf(t, t + 1), whos = listOf("halo", "")))
        assertEquals(listOf(GroupPos.FIRST, GroupPos.LAST), Bubbles.positions(listOf(Side.BLAZE, Side.BLAZE), listOf(t, t + 1)))
    }

    @Test fun rawCallTextNeverReachesABubble() = fiveTimes {
        for (raw in listOf(
            "<tool_call>{\"name\": \"phone_status\", \"arguments\": {}}</tool_call>",
            "[github_open repo=\"Blazeinchue/Blaze-Agent\"]",
            "```tool\nphone_act(action=\"click\", target=\"e61\")\n```",
        )) {
            val shown = ToolLeak.bubbleText(raw)
            assertFalse("$raw -> $shown", shown.contains("phone_status") || shown.contains("github_open") || shown.contains("phone_act(") || shown.contains("e61"))
            assertTrue(shown.isNotBlank())
        }
        // A normal answer is untouched, byte for byte.
        val plain = "Logged in. You're in the secure area \u2014 \"You logged into a secure area!\" with a Logout link."
        assertEquals(plain, ToolLeak.bubbleText(plain))
    }
}
