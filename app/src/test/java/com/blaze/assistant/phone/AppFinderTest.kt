package com.blaze.assistant.phone

import com.blaze.assistant.phone.AppFinder.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.3: "a car rally game" finds Rally Horizon; "list my apps" is about the phone; "open and play it" launches the real package. */
class AppFinderTest {
    private val apps = listOf(
        App("Rally Horizon", "com.rallyhorizon.game", AppFinder.CAT_GAME),
        App("Bus Fever Party!", "com.busfever.party", AppFinder.CAT_GAME),
        App("WhatsApp", "com.whatsapp", AppFinder.CAT_SOCIAL),
        App("Spotify", "com.spotify.music", AppFinder.CAT_AUDIO),
        App("Google Maps", "com.google.android.apps.maps", AppFinder.CAT_MAPS),
        App("Settings", "com.android.settings"),
        App("Zed Bank", "zm.zedbank.mobile"),
    )

    @Test fun carRallyGameFindsRallyHorizon() {
        val hits = AppFinder.search("car rally game", apps)
        assertEquals("Rally Horizon", hits.first().app.label)
        assertTrue(hits.none { it.app.label.startsWith("Bus") })
    }

    @Test fun partialWordsPackageAndCase() {
        assertEquals("Rally Horizon", AppFinder.search("RALLY", apps).first().app.label)
        assertEquals("Rally Horizon", AppFinder.search("horiz", apps).first().app.label)
        assertEquals("Zed Bank", AppFinder.search("zedbank", apps).first().app.label)        // package part
        assertEquals("Spotify", AppFinder.search("spot", apps).first().app.label)
        assertTrue(AppFinder.search("telegram", apps).isEmpty())
    }

    @Test fun categories() {
        assertEquals(setOf("Rally Horizon", "Bus Fever Party!"), AppFinder.search("my games", apps).map { it.app.label }.toSet())
        assertEquals("Spotify", AppFinder.search("music app", apps).first().app.label)
        assertEquals("Google Maps", AppFinder.search("maps", apps).first().app.label)
    }

    @Test fun emptyListsEverything() {
        assertEquals(apps.size, AppFinder.search("", apps, limit = 100).size)
        assertEquals("Bus Fever Party!", AppFinder.search("", apps).first().app.label)   // A to Z
    }

    @Test fun resolver() {
        assertEquals("com.rallyhorizon.game", AppFinder.resolve("Rally Horizon", apps)?.pkg)
        assertEquals("com.rallyhorizon.game", AppFinder.resolve("the rally game", apps)?.pkg)
        // a quoted app tile carries the real package
        val quoted = AppFinder.tileLine(apps[0]) + "\nopen and play it"
        assertEquals("com.rallyhorizon.game", AppFinder.resolve(quoted, apps)?.pkg)
        assertNull(AppFinder.resolve("play a game", apps))          // which one is the user's call
        assertNull(AppFinder.resolve("telegram", apps))
        assertEquals("com.rallyhorizon.game", AppFinder.pkgIn("App: Rally Horizon (package com.rallyhorizon.game, installed)"))
        assertNull(AppFinder.pkgIn("go to github.com now"))
    }

    @Test fun playIds() {
        assertEquals("com.rallyhorizon.game", AppFinder.playId("https://play.google.com/store/apps/details?id=com.rallyhorizon.game&hl=en"))
        assertEquals("com.x.y", AppFinder.playId("https://play.google.com/store/apps/details?hl=en&id=com.x.y"))
        assertEquals("com.x.y", AppFinder.playId("market://details?id=com.x.y"))
        assertNull(AppFinder.playId("https://play.google.com/store/search?q=rally&c=apps"))
    }

    @Test fun appsAsksRouteToTheListNotASkill() {
        listOf("list my apps", "List my apps", "show me all my apps", "which games do I have?", "what apps are installed",
            "apps on my phone", "do I have a car rally game app").forEach { assertTrue(it, AppFinder.isAppsAsk(it)) }
        listOf("make a listing for this product", "write a shopify product listing", "what's the weather").forEach { assertFalse(it, AppFinder.isAppsAsk(it)) }
    }

    @Test fun replyThatClaimsAListGetsTheCard() {
        val fence = AppFinder.fence("Your apps (2)", apps.take(2))
        assertTrue(fence.startsWith("```apps\n{")); assertTrue(fence.contains("\"pkg\":\"com.rallyhorizon.game\""))
        val out = AppFinder.ensureCard("Here's your app list:", fence, "list my apps")
        assertTrue(out.endsWith(fence))
        assertEquals("Opened it.", AppFinder.ensureCard("Opened it.", fence, "open rally"))
        assertEquals("x", AppFinder.ensureCard("x", null, "list my apps"))
        assertEquals(out, AppFinder.ensureCard(out, fence, "list my apps"))       // never twice
        val card = com.blaze.assistant.ui.RichKit.apps(fence.removePrefix("```apps\n").removeSuffix("\n```"))!!
        assertEquals(listOf("com.rallyhorizon.game", "com.busfever.party"), card.apps.map { it.pkg })
        assertTrue(card.apps[0].game)
    }
}
