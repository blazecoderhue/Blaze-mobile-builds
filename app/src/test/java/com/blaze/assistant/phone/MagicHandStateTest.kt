package com.blaze.assistant.phone

import com.blaze.assistant.phone.MagicHandLogic.Action
import com.blaze.assistant.phone.MagicHandLogic.Intent
import com.blaze.assistant.phone.MagicHandLogic.Kind
import com.blaze.assistant.phone.MagicHandLogic.Mode
import com.blaze.assistant.phone.MagicHandState.DockButton
import com.blaze.assistant.phone.MagicHandState.GrabAction
import com.blaze.assistant.phone.MagicHandState.Panel
import com.blaze.assistant.ui.PanelButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Magic Hand (v0.24.7) dock state: close stops all, private captures nothing, every act is confirmed first. */
class MagicHandStateTest {
    @Test fun closeStopsEverything() {
        var u = MagicHandState.start(private = false)
        u = MagicHandState.carry(u, "K1,299")
        u = MagicHandState.teach(u)
        u = MagicHandState.teachSteps(u, listOf("Open WhatsApp"))
        val c = MagicHandState.close()
        assertFalse(c.on); assertFalse(c.carrying); assertEquals(Mode.POINT, c.mode); assertTrue(c.teachSteps.isEmpty()); assertEquals(Panel.STATUS, c.panel)
        assertTrue(u.on)
    }

    @Test fun closeIsAlwaysOffered() {
        val states = listOf(MagicHandState.start(false), MagicHandState.start(true), MagicHandState.teach(MagicHandState.start(false)),
            MagicHandState.carry(MagicHandState.start(false), "x"))
        states.forEach { assertEquals(DockButton.CLOSE, MagicHandState.dockButtons(it).last()) }
        assertTrue(DockButton.TEACH in MagicHandState.dockButtons(MagicHandState.start(false)))
        assertFalse(DockButton.TEACH in MagicHandState.dockButtons(MagicHandState.start(true)))
        assertTrue(DockButton.DONE in MagicHandState.dockButtons(MagicHandState.teach(MagicHandState.start(false))))
        assertTrue(DockButton.DROP_CHAT in MagicHandState.dockButtons(MagicHandState.carry(MagicHandState.start(false), "x")))
    }

    @Test fun privateMenuHasNoCapturingActions() {
        val u = MagicHandState.menu(MagicHandState.start(private = true), "Hello there", Kind.TEXT, "Chrome")
        assertEquals(Panel.MENU, u.panel)
        assertFalse(Action.ASK in u.menu); assertFalse(Action.EXPLAIN in u.menu); assertFalse(Action.TRANSLATE in u.menu)
        assertTrue(Action.COPY in u.menu); assertTrue(Action.CARRY in u.menu)
        val open = MagicHandState.menu(MagicHandState.start(private = false), "Hello there", Kind.TEXT, "Chrome")
        assertEquals(Action.ASK, open.menu.first())
        assertTrue(open.menu.size <= MagicHandLogic.MAX_ACTIONS)
    }

    @Test fun privateTeachIsRefused() {
        val u = MagicHandState.teach(MagicHandState.start(private = true))
        assertEquals(Mode.POINT, u.mode); assertEquals(MagicHandGuard.PRIVATE_NOTE, u.note)
        assertEquals(listOf(GrabAction.COPY, GrabAction.CARRY), MagicHandState.grabActions(true))
        assertEquals(5, MagicHandState.grabActions(false).size)
    }

    @Test fun secretFieldGetsNoMenu() {
        val u = MagicHandState.menu(MagicHandState.start(false), "", Kind.SECRET, "Bank")
        assertEquals(Panel.STATUS, u.panel); assertTrue(u.menu.isEmpty())
    }

    @Test fun dropAndTeachTapAreConfirmed() {
        listOf(Intent.DROP, Intent.TEACH_TAP, Intent.WATCH).forEach { assertTrue(MagicHandGuard.needsConfirm(it)) }
        val carry = MagicHandState.carry(MagicHandState.start(false), "Meet at 6")
        val c = MagicHandState.confirm(carry, Intent.DROP, "Message")
        assertEquals(Panel.CONFIRM, c.panel)
        assertNotNull(c.confirm)
        assertEquals("Drop", c.confirm!!.yes)
        assertTrue(c.confirm!!.line.contains("Meet at 6"))
        assertEquals("Tap it", MagicHandState.confirm(c, Intent.TEACH_TAP, "Send").confirm!!.yes)
        // Dropping clears what was carried; cancelling keeps it.
        assertFalse(MagicHandState.dropped(c).carrying)
        assertTrue(MagicHandState.back(c).carrying)
    }

    @Test fun askThenAnswer() {
        var u = MagicHandState.asking(MagicHandState.start(false), "K1,299")
        assertEquals(Panel.ASKING, u.panel)
        u = MagicHandState.answered(u, "That's the price.")
        assertEquals(Panel.ANSWER, u.panel); assertEquals("That's the price.", u.answer)
        // An answer that lands after the user moved on doesn't hijack the dock.
        val moved = MagicHandState.answered(MagicHandState.back(u), "late")
        assertEquals(Panel.STATUS, moved.panel)
    }

    @Test fun grabEmptyFallsBack() {
        val u = MagicHandState.grabbed(MagicHandState.start(false), "  ", "Chrome")
        assertEquals(Panel.STATUS, u.panel)
        val g = MagicHandState.grabbed(MagicHandState.start(false), " Hello\nworld ", "Chrome")
        assertEquals(Panel.GRAB, g.panel); assertEquals("Hello\nworld", g.grabbed)
        assertTrue(MagicHandState.carry(g, g.grabbed).carrying)
    }

    @Test fun teachReviewAndEnd() {
        var u = MagicHandState.teach(MagicHandState.start(false))
        assertEquals(Panel.TEACH, u.panel)
        u = MagicHandState.review(u, listOf("Open WhatsApp (package com.whatsapp)", "Tap \u201cMum\u201d"), "Taught: WhatsApp")
        assertEquals(Panel.TEACH_REVIEW, u.panel); assertEquals("Taught: WhatsApp", u.teachName)
        u = MagicHandState.endTeach(u, "Saved")
        assertEquals(Mode.POINT, u.mode); assertTrue(u.teachSteps.isEmpty())
        // The dock after a back in teach mode stays on the teach panel.
        assertEquals(Panel.TEACH, MagicHandState.back(MagicHandState.teach(MagicHandState.start(false))).panel)
    }

    @Test fun statusAndSearch() {
        val u = MagicHandState.point(MagicHandState.start(false), "Buy now", Kind.BUTTON, "Jumia")
        assertEquals("Jumia \u00b7 Buy now", MagicHandState.statusLine(u))
        assertEquals("https://duckduckgo.com/?q=hello+world", MagicHandState.searchUrl(" hello world "))
    }

    @Test fun menuIsTheSharedBrowserMapping() {
        assertTrue(MagicHandMenu.mapper === BrowserMenuMapper)
        assertEquals(Action.ASK, MagicHandMenu.actions(Kind.TEXT, false).first())
    }

    @Test fun panelHasTheHand() {
        assertEquals(PanelButtons.B.HAND, PanelButtons.of(true).first())
        assertEquals(PanelButtons.B.HAND, PanelButtons.of(false).first())
        assertEquals(PanelButtons.B.CLOSE, PanelButtons.of(true).last())
    }

    @Test fun watchLines() {
        val w = MagicHandWatch.Watch("1", "com.jumia", "Jumia", "Price", listOf(0, 2), "", "", "K1,099", 0L, lastChecked = 1_000L, previous = "K1,299")
        assertEquals("Jumia \u00b7 K1,099 (was K1,299) \u00b7 checked 2 min ago", MagicHandWatchText.sub(w, 1_000L + 125_000L))
        assertEquals("Jumia \u00b7 K1,099 (was K1,299) \u00b7 not checked yet", MagicHandWatchText.sub(w.copy(lastChecked = 0), 5L))
        assertEquals("3 h ago", MagicHandWatchText.ago(3 * 3_600_000L + 5))
    }
}
