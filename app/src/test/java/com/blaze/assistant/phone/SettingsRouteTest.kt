package com.blaze.assistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.4 fast actions: one open_settings call lands on the exact page, by intent, on every Android version. */
class SettingsRouteTest {
    private val apps = listOf("GitHub" to "com.github.android", "WhatsApp" to "com.whatsapp", "Settings" to "com.android.settings",
        "Chrome" to "com.android.chrome", "Git Bash Helper" to "com.example.gitbash", "My MTN" to "com.mtn.app")

    @Test fun githubLinksOnAnAndroid12Phone() {
        // "stop GitHub opening links in the GitHub app" → one call.
        assertEquals("open_by_default", SettingsRoute.page("open_by_default", true))
        assertEquals("open_by_default", SettingsRoute.page("opening links", true))
        assertEquals("open_by_default", SettingsRoute.page("Open supported links", true))
        assertEquals("open_by_default", SettingsRoute.page("app links settings", true))
        val pkg = SettingsRoute.resolveApp("GitHub", apps)!!.second
        assertEquals("com.github.android", pkg)
        // Android 12+ (One UI 4/5): the direct per-app page, App info as the fallback.
        val p12 = SettingsRoute.plan("open_by_default", pkg, 31)!!
        assertEquals(SettingsRoute.OPEN_BY_DEFAULT, p12.launches.first().action)
        assertEquals("package:com.github.android", p12.launches.first().data)
        assertEquals(SettingsRoute.DETAILS, p12.launches[1].action)
        assertEquals(1, p12.tapFrom)   // rows only tapped if we fell back to App info
        // Android 11 (One UI 3): no direct intent, so App info, then the "Open by default" / "Set as default" row.
        val p11 = SettingsRoute.plan("open_by_default", pkg, 30)!!
        assertEquals(listOf(SettingsRoute.DETAILS), p11.launches.map { it.action })
        assertEquals(0, p11.tapFrom)
        assertTrue("Set as default" in p11.thenTap && "Open by default" in p11.thenTap)
    }

    @Test fun appPagesAndGlobalPages() {
        val n = SettingsRoute.plan(SettingsRoute.page("notifications", true)!!, "com.whatsapp", 33)!!
        assertEquals(SettingsRoute.APP_NOTIFICATIONS, n.launches.first().action)
        assertEquals("com.whatsapp", n.launches.first().extras[SettingsRoute.EXTRA_APP_PACKAGE])
        assertEquals("app_details", SettingsRoute.page("app info", true))
        assertEquals("app_battery", SettingsRoute.page("battery", true))
        assertEquals("battery", SettingsRoute.page("battery", false))
        assertEquals("app_permissions", SettingsRoute.page("permissions", true))
        assertEquals("wifi", SettingsRoute.page("Wi-Fi settings", false))
        assertEquals("default_apps", SettingsRoute.page("default browser", false))
        assertEquals("default_apps", SettingsRoute.page("open_by_default", false))   // no app: the default-apps page
        assertEquals("android.settings.BLUETOOTH_SETTINGS", SettingsRoute.plan("bluetooth", null, 30)!!.launches.first().action)
        assertEquals("android.settings.MANAGE_DEFAULT_APPS_SETTINGS", SettingsRoute.plan("default_apps", null, 30)!!.launches.first().action)
        assertEquals(listOf("android.settings.DISPLAY_SETTINGS"), SettingsRoute.plan("dark_mode", null, 28)!!.launches.map { it.action })
        // App battery has no public intent: App info + the Battery row.
        val b = SettingsRoute.plan("app_battery", "com.whatsapp", 34)!!
        assertEquals(listOf(SettingsRoute.DETAILS), b.launches.map { it.action }); assertTrue("Battery" in b.thenTap)
        assertNull(SettingsRoute.plan("app_details", null, 30))
        assertNull(SettingsRoute.page("make me a sandwich", false))
        for (p in SettingsRoute.pageNames) assertTrue(p, SettingsRoute.plan(p, "com.whatsapp", 34) != null)
    }

    @Test fun resolvesAppNamesToPackages() {
        assertEquals("com.whatsapp", SettingsRoute.resolveApp("whatsapp", apps)!!.second)
        assertEquals("com.github.android", SettingsRoute.resolveApp("the GitHub app", apps)!!.second)
        assertEquals("com.android.chrome", SettingsRoute.resolveApp("com.android.chrome", apps)!!.second)
        assertEquals("com.mtn.app", SettingsRoute.resolveApp("My MTN", apps)!!.second)
        // A launcher label that doesn't say the name still resolves through the known packages, if installed.
        assertEquals("com.github.android", SettingsRoute.resolveApp("github", listOf("GH" to "com.github.android"))!!.second)
        assertNull(SettingsRoute.resolveApp("github", listOf("GH" to "com.other")))
        assertNull(SettingsRoute.resolveApp("it", apps))
    }
}
