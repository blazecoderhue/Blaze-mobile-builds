package com.blaze.assistant.bank

import com.blaze.assistant.bank.BankModeLogic.Action
import com.blaze.assistant.bank.BankModeLogic.Event
import com.blaze.assistant.bank.BankModeLogic.Saved
import com.blaze.assistant.bank.BankModeLogic.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.43.0 Bank mode: which apps count as banks, and the on / off / restore rules. */
class BankModeTest {
    @Test fun spotsBankingPaymentAndWalletApps() {
        listOf(
            "com.chase.sig.android" to "Chase", "com.revolut.revolut" to "Revolut", "com.paypal.android.p2pmobile" to "PayPal",
            "com.google.android.apps.walletnfcrel" to "Google Wallet", "com.example.mybank" to "My Bank", "ng.gtbank.mobile" to "GTWorld",
            "com.acme.banking" to "Acme", "com.foo.app" to "First Credit Union", "com.foo.money" to "Banco Popular", "com.xyz.wallet" to "XYZ",
            "net.one97.paytm" to "Paytm", "com.bar.app" to "Bar Mobile Banking", "com.mtn.momo" to "MoMo", "com.foo.pay" to "Foo",
        ).forEach { (p, l) -> assertTrue("$p / $l", BankApps.looksLikeBank(p, l)) }
    }

    @Test fun leavesOtherAppsAlone() {
        listOf(
            "com.whatsapp" to "WhatsApp", "com.android.chrome" to "Chrome", "com.android.vending" to "Google Play Store", "com.android.settings" to "Settings",
            "com.spotify.music" to "Spotify", "com.king.candycrushsaga" to "Candy Crush Saga", "com.foo.display" to "Display Tuner", "com.foo.replay" to "Replay",
            "com.foo.powerbank" to "Power Bank Monitor", "com.blaze.assistant" to "Blaze", "com.google.android.gms" to "Google Play services",
            "com.foo.games" to "Piggy Bank Game", "com.google.android.youtube" to "YouTube",
        ).forEach { (p, l) -> assertFalse("$p / $l", BankApps.looksLikeBank(p, l)) }
    }

    @Test fun theUsersListWins() {
        assertTrue(BankApps.isBank("com.my.taxapp", "Taxes", added = setOf("com.my.taxapp"), removed = emptySet()))
        assertFalse(BankApps.isBank("com.chase.sig.android", "Chase", added = emptySet(), removed = setOf("com.chase.sig.android")))
        val seeded = BankApps.preseed(mapOf("com.whatsapp" to "WhatsApp", "com.revolut.revolut" to "Revolut", "com.acme.bank" to "Acme Bank", "com.blaze.assistant" to "Blaze"))
        assertEquals(listOf("com.acme.bank", "com.revolut.revolut"), seeded)
    }

    @Test fun passingWindowsDontCountAsLeaving() {
        listOf("com.android.systemui", "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard", "com.touchtype.swiftkey").forEach { assertTrue(it, BankApps.passing(it)) }
        assertFalse(BankApps.passing("com.whatsapp"))
    }

    private val all = Saved(accessibility = true, orb = true, notifications = true, screenGlances = true, cameraLooks = true)

    @Test fun manualOnAndOffRestoresExactlyWhatWasOn() {
        val (on, a1) = BankModeLogic.reduce(State(), Event.TurnOn(all), autoDetect = true)
        assertTrue(on.on); assertFalse(on.auto)
        assertTrue(Action.DISABLE_ACCESSIBILITY in a1); assertTrue(Action.HIDE_OVERLAYS in a1); assertTrue(Action.PAUSE_NOTIFICATIONS in a1)
        assertTrue(Action.PAUSE_GLANCES in a1); assertTrue(Action.SHOW_NOTICE in a1)
        // Manual: leaving a bank app or opening another app doesn't turn it off.
        val (still, a2) = BankModeLogic.reduce(on, Event.Front("com.whatsapp", false, 1000), true)
        assertTrue(still.on); assertTrue(a2.isEmpty())
        assertTrue(BankModeLogic.reduce(still, Event.Tick(60_000), true).first.on)
        val (off, a3) = BankModeLogic.reduce(still, Event.TurnOff, true)
        assertFalse(off.on)
        assertEquals(listOf(Action.RESTORE_ACCESSIBILITY, Action.SHOW_ORB, Action.RESUME_GLANCES, Action.RESUME_CAMERA, Action.RESUME_NOTIFICATIONS, Action.CLEAR_NOTICE, Action.STOP_WATCH), a3)
        // Only what was on comes back.
        val (on2, _) = BankModeLogic.reduce(State(), Event.TurnOn(Saved(accessibility = false, orb = true)), true)
        val (_, a4) = BankModeLogic.reduce(on2, Event.TurnOff, true)
        assertFalse(Action.RESTORE_ACCESSIBILITY in a4); assertTrue(Action.SHOW_ORB in a4); assertFalse(Action.RESUME_NOTIFICATIONS in a4)
        // Twice is harmless.
        assertTrue(BankModeLogic.reduce(off, Event.TurnOff, true).second.isEmpty())
        assertTrue(BankModeLogic.reduce(on, Event.TurnOn(all), true).second.isEmpty())
    }

    @Test fun autoOnWhenABankOpensOffAfterLeaving() {
        val (on, a1) = BankModeLogic.reduce(State(), Event.Front("com.chase.sig.android", true, 0, all), autoDetect = true)
        assertTrue(on.on); assertTrue(on.auto); assertEquals("com.chase.sig.android", on.bankPkg); assertTrue(Action.DISABLE_ACCESSIBILITY in a1)
        // Keyboard / fingerprint prompt over the bank: still banking.
        val (kb, _) = BankModeLogic.reduce(on, Event.Front("com.google.android.inputmethod.latin", false, 500), true)
        assertEquals(0L, kb.leftAt)
        // Left for the browser (a payment page) and back within the grace: stays on.
        val (left, _) = BankModeLogic.reduce(kb, Event.Front("com.android.chrome", false, 1000), true)
        assertEquals(1000L, left.leftAt)
        assertTrue(BankModeLogic.reduce(left, Event.Tick(3000), true).first.on)
        val (back, _) = BankModeLogic.reduce(left, Event.Front("com.chase.sig.android", true, 3500), true)
        assertEquals(0L, back.leftAt); assertTrue(back.on)
        // Left for good: off after the grace, everything restored.
        val (gone, _) = BankModeLogic.reduce(back, Event.Front("com.whatsapp", false, 10_000), true)
        val (stillOn, none) = BankModeLogic.reduce(gone, Event.Tick(10_000 + BankModeLogic.LEAVE_GRACE_MS - 1), true)
        assertTrue(stillOn.on); assertTrue(none.isEmpty())
        val (off, a2) = BankModeLogic.reduce(gone, Event.Tick(10_000 + BankModeLogic.LEAVE_GRACE_MS), true)
        assertFalse(off.on); assertTrue(Action.RESTORE_ACCESSIBILITY in a2); assertTrue(Action.CLEAR_NOTICE in a2)
    }

    @Test fun autoDetectOffMeansManualOnly() {
        val (s, a) = BankModeLogic.reduce(State(), Event.Front("com.chase.sig.android", true, 0, all), autoDetect = false)
        assertFalse(s.on); assertTrue(a.isEmpty())
    }

    @Test fun manualWinsOverAuto() {
        val (auto, _) = BankModeLogic.reduce(State(), Event.Front("com.chase.sig.android", true, 0, all), true)
        val (manual, _) = BankModeLogic.reduce(auto, Event.TurnOn(Saved()), true)
        assertTrue(manual.on); assertFalse(manual.auto); assertEquals(all, manual.saved)
        val (_, a) = BankModeLogic.reduce(manual, Event.Front("com.whatsapp", false, 100), true)
        assertTrue(a.isEmpty())
        assertTrue(BankModeLogic.reduce(manual, Event.Tick(100_000), true).first.on)
    }

    @Test fun accessibilityListEdit() {
        val me = "com.blaze.assistant/com.blaze.assistant.phone.BlazeAccessibilityService"
        assertEquals(me, BankModeLogic.editServices(null, me, true))
        assertEquals("com.other/.Svc:$me", BankModeLogic.editServices("com.other/.Svc", me, true))
        // Already there (short form): not added twice.
        assertEquals("com.other/.Svc:$me", BankModeLogic.editServices("com.blaze.assistant/.phone.BlazeAccessibilityService:com.other/.Svc", me, true))
        assertEquals("com.other/.Svc", BankModeLogic.editServices("com.other/.Svc:$me", me, false))
        assertEquals("", BankModeLogic.editServices(me, me, false))
    }

    @Test fun honestWords() {
        assertTrue(BankModeLogic.NOTICE_TITLE.startsWith("Bank mode on"))
        assertTrue(BankModeLogic.STILL_BLOCKED.contains("USB debugging")); assertTrue(BankModeLogic.STILL_BLOCKED.contains("can't change"))
        assertTrue(BankModeLogic.ADB_GRANT.contains("WRITE_SECURE_SETTINGS"))
        assertTrue(BankModeLogic.REFUSE.contains("doesn't read or touch"))
    }
}
