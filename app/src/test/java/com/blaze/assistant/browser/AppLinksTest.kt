package com.blaze.assistant.browser

import com.blaze.assistant.browser.RedirectRules.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.3: which navigations leave the WebView for another app (Blaze browser, BlazeOS Browser, site apps). */
class AppLinksTest {
    private val gh = "https://github.com/apps/hark/installations/new"

    @Test fun schemesAndIntents() {
        assertEquals(Kind.INTENT, RedirectRules.classify("intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end", gh, true))
        listOf("market://details?id=com.whatsapp", "mailto:a@b.co", "tel:+260977000000", "whatsapp://send?text=hi", "hark://connect?code=1")
            .forEach { assertEquals(it, Kind.SCHEME, RedirectRules.classify(it, gh, true)) }
    }

    @Test fun inPageSchemesAndSubframesStay() {
        listOf("about:blank", "data:text/html,hi", "blob:https://x.com/1", "javascript:void(0)").forEach { assertEquals(it, Kind.STAY, RedirectRules.classify(it, gh, true)) }
        assertEquals(Kind.STAY, RedirectRules.classify("market://details?id=x", gh, false))       // an iframe never launches an app
        assertEquals(Kind.STAY, RedirectRules.classify("https://hark.com/android/connect?x=1", gh, false))
    }

    @Test fun crossSiteHttpsChecksAppLinksSameSiteStays() {
        assertEquals(Kind.APP_LINK, RedirectRules.classify("https://hark.com/android/connect?installation_id=5", gh, true))
        assertEquals(Kind.STAY, RedirectRules.classify("https://github.com/settings/installations", gh, true))
        assertEquals(Kind.STAY, RedirectRules.classify("https://gist.github.com/x", gh, true))           // same site, other subdomain
        assertEquals(Kind.STAY, RedirectRules.classify("http://hark.com/x", gh, true))                    // App Links are https only
        assertEquals(Kind.STAY, RedirectRules.classify("https://hark.com/x", "about:blank", true))        // typed / first load
        assertEquals(Kind.STAY, RedirectRules.classify("https://hark.com/x", null, true))
    }

    @Test fun sites() {
        assertEquals("github.com", RedirectRules.site("https://api.github.com/x"))
        assertEquals("bbc.co.uk", RedirectRules.site("https://www.bbc.co.uk/news"))
        assertEquals("example.com", RedirectRules.site("https://user:pw@example.com:8443/p?q#f"))
        assertEquals("192.168.1.4", RedirectRules.site("http://192.168.1.4:8080/"))
        assertEquals(null, RedirectRules.site("about:blank"))
    }

    @Test fun loopGuard() {
        val g = RedirectRules.Guard(4000)
        assertTrue(g.allow("hark://x", 1000)); assertFalse(g.allow("hark://x", 2000)); assertTrue(g.allow("hark://x", 6000))
        assertTrue(g.allow("hark://y", 6100))
    }

    @Test fun toolbarScroll() {
        assertEquals(true, ToolbarScroll.direction(300, 200)); assertEquals(false, ToolbarScroll.direction(200, 300))
        assertEquals(null, ToolbarScroll.direction(205, 200)); assertEquals(false, ToolbarScroll.direction(0, 40))
    }
}
