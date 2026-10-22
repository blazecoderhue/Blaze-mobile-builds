package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.3: Autopilot sent "Sending…" another turn after a finished answer that ended with an offer (screenshot 14:37). */
class AutoContinueTest {
    private val screenshot = """You've got three active routines:
- **Church** — Sundays 08:30
- **Communication Skills class** — Tuesdays 10:00 (that was this morning at 10:00)
- **Clean up unused apps** — Thursday 22 Oct at 10:00

Your startup podcast alarm went off at 06:00 today. You've been in Blaze Music the last couple of hours.

Nothing urgent right now — Thursday's cleanup is the next scheduled thing. Want me to run the unused-apps check early or set a reminder for anything else?"""

    @Test fun screenshotCaseStops() {
        val d = AutoContinue.decide(screenshot, 0)
        assertFalse(d.go); assertEquals(AutoContinue.Why.ASKS_USER, d.why)
        // Even with the model's continue marker, a reply that ends in a question is never followed by an automatic turn.
        assertFalse(AutoContinue.decide("$screenshot <<continue>>".replace("? <<continue>>", "?"), 0).go)
        assertFalse(AutoContinue.decide(screenshot + "\n<<continue>>", 0).go)
    }

    @Test fun offersAndQuestionsStop() {
        for (t in listOf("Done. I can also set a reminder if you like.", "Which one do you want: the red or the blue?",
                "Here are two ways:\n1. Use the app\n2. Use the website\nLet me know which you'd like.", "Ready when you are.",
                "Should I go ahead and book it")) assertFalse(t, AutoContinue.decide(t, 0).go)
    }

    @Test fun completeAnswersAndChitChatStop() {
        for (t in listOf("Lusaka is the capital of Zambia.", "Haha, same here. Enjoy your evening!", "Your alarm is set for 7:00.",
                "All three files are converted and saved in Downloads. <<done>>"))
            assertFalse(t, AutoContinue.decide(t, 0).go)
        assertEquals(AutoContinue.Why.DONE_MARK, AutoContinue.decide("Finished. <<done>>", 0).why)
    }

    @Test fun continuesOnlyWhenUnfinished() {
        assertTrue(AutoContinue.decide("Installed the packages. Next I'll build the project. <<continue>>", 0).go)
        val plan = AutoContinue.decide("Step 2 of 4 done: the repo is cloned and the tests ran.", 0)
        assertTrue(plan.go); assertEquals(AutoContinue.Why.PLAN_LEFT, plan.why)
        assertFalse(AutoContinue.decide("Step 4 of 4 done: everything is built.", 0).go)
        val retry = AutoContinue.decide("The build failed on a missing import.", 0, toolsThisTurn = 3, failedCheck = true)
        assertTrue(retry.go); assertEquals(AutoContinue.Why.FAILED_CHECK, retry.why)
        // A failed check with no tool work this turn is just an answer.
        assertFalse(AutoContinue.decide("That didn't work last time.", 0, toolsThisTurn = 0, failedCheck = true).go)
    }

    @Test fun capped() {
        val t = "Next I'll do the rest. <<continue>>"
        assertTrue(AutoContinue.decide(t, AutoContinue.MAX - 1).go)
        val d = AutoContinue.decide(t, AutoContinue.MAX)
        assertFalse(d.go); assertEquals(AutoContinue.Why.CAP, d.why)
    }

    @Test fun markersNeverShown() {
        assertEquals("All done.", AutoContinue.strip("All done. <<done>>").trim())
        assertEquals("Working on it.", AutoContinue.strip("Working on it.<<continue>>").trim())
        assertEquals("Almost", AutoContinue.strip("Almost<<con", final = false))
        assertEquals("Almost", AutoContinue.strip("Almost<", final = false))
        // Plain text with < stays.
        assertEquals("a < b and c << d", AutoContinue.strip("a < b and c << d"))
        assertEquals("Here you go.", ReplyParts.split("Here you go. <<done>>", final = true).text)
    }

    @Test fun promptTellsTheModel() {
        assertTrue(AutoContinue.PROMPT.contains(AutoContinue.DONE) && AutoContinue.PROMPT.contains(AutoContinue.CONTINUE))
    }
}
