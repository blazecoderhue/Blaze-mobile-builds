package com.blaze.assistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.4 fast actions: tap targets match across skins (Samsung One UI vs Pixel), fuzzily, and safely. */
class LabelMatchTest {
    /** One UI 3/4 Settings, top level, as the accessibility tree lists it. */
    private val samsungTop = listOf("Search", "Samsung account", "Connections", "Connected devices", "Modes and Routines", "Sounds and vibration",
        "Notifications", "Display", "Wallpaper", "Themes", "Home screen", "Lock screen", "Biometrics and security", "Privacy", "Location",
        "Safety and emergency", "Accounts and backup", "Google", "Advanced features", "Digital Wellbeing and parental controls",
        "Battery and device care", "Apps", "General management", "Accessibility", "Software update", "About phone")
    private val pixelTop = listOf("Network & internet", "Connected devices", "Apps", "Notifications", "Battery", "Storage", "Sound & vibration", "Display")

    @Test fun theOneUiFailureNowMatches() {
        // Macro 1 failed at its first tap: "Apps" (and the model's Pixel-style names) on Samsung.
        assertEquals("Apps", samsungTop[LabelMatch.best("Apps", samsungTop)!!])
        assertEquals("Apps", samsungTop[LabelMatch.best("Apps & notifications", samsungTop)!!])
        assertEquals("Apps", pixelTop[LabelMatch.best("Apps", pixelTop)!!])
        // Macro 2 failed at "Opening links": Samsung's App info says "Set as default", Pixel's "Open by default".
        val samsungAppInfo = listOf("GitHub", "Version 1.160.0", "Notifications", "Permissions", "Set as default", "Mobile data", "Battery", "Storage", "Memory", "Uninstall", "Force stop")
        val pixelAppInfo = listOf("GitHub", "Notifications", "Permissions", "Storage & cache", "Mobile data & Wi-Fi", "Screen time", "App battery usage", "Open by default", "Uninstall", "Force stop")
        for (t in listOf("Opening links", "Open by default", "App links", "Set as default", "Open supported links"))
            assertEquals(t, "Set as default", samsungAppInfo[LabelMatch.best(t, samsungAppInfo)!!])
        for (t in listOf("Opening links", "Set as default", "App links"))
            assertEquals(t, "Open by default", pixelAppInfo[LabelMatch.best(t, pixelAppInfo)!!])
        assertEquals("Storage & cache", pixelAppInfo[LabelMatch.best("Storage", pixelAppInfo)!!])
        val defaults = listOf("Browser app", "Calling app", "SMS app", "Home app", "Opening links", "Digital assistant app")
        assertEquals("Opening links", defaults[LabelMatch.best("Open by default", defaults)!!])
        assertEquals("Choose default apps", listOf("All apps", "Choose default apps", "App settings").let { it[LabelMatch.best("Default apps", it)!!] })
    }

    @Test fun safety() {
        // A negated label never answers a plain target, and the other way round.
        assertEquals(0, LabelMatch.score("Allow", "Don't allow"))
        assertEquals(0, LabelMatch.score("Don't allow", "Allow"))
        assertEquals("Don\u2019t allow", listOf("Allow", "Don\u2019t allow").let { it[LabelMatch.best("Dont allow", it)!!] })
        // Destructive rows only on a target that says so.
        assertEquals(0, LabelMatch.score("Stop", "Force stop"))
        assertEquals(0, LabelMatch.score("Install", "Uninstall"))
        assertEquals(0, LabelMatch.score("Storage", "Clear storage"))
        assertTrue(LabelMatch.score("Force stop", "Force stop") == 100)
        // One-word synonyms don't pull in long rows that merely contain them.
        assertTrue(LabelMatch.score("Open by default", "Tap links in the browser to see more") < LabelMatch.MIN)
        assertNull(LabelMatch.best("Bluetooth", listOf("Wallpaper", "Themes", "Lock screen")))
    }

    @Test fun fuzzyAndNormalised() {
        assertEquals(100, LabelMatch.score("Wi-Fi", "Wi\u2011Fi"))
        assertTrue(LabelMatch.score("WiFi", "Wi-Fi") >= LabelMatch.MIN)
        assertTrue(LabelMatch.score("Notifcations", "Notifications") >= LabelMatch.MIN)
        assertTrue(LabelMatch.score("Search", "Searchbar") >= LabelMatch.MIN)
        assertEquals("sound and vibration", LabelMatch.norm("Sound & vibration"))
        assertTrue("Network & internet" in pixelTop && LabelMatch.best("Connections", pixelTop)!!.let { pixelTop[it] } == "Network & internet")
    }

    @Test fun fastEnoughForBigTrees() {
        val labels = (1..600).map { "Row number $it with some words" } + "Open by default"
        repeat(20) { LabelMatch.best("Opening links", labels) }   // warm up (the phone's ART is warm after the first taps)
        val t0 = System.nanoTime()
        repeat(10) { assertEquals(600, LabelMatch.best("Opening links", labels)) }
        val ms = (System.nanoTime() - t0) / 1_000_000 / 10
        println("LabelMatch over 601 labels: $ms ms per lookup")
        assertTrue("$ms ms", ms < 250)
    }
}
