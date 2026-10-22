package com.blaze.assistant.siteapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.2 "Install app": reading a page's head, web app manifests, the icon order, ids, scopes and colours. */
class SiteLogicTest {
    private val page = "https://app.example.org/inbox/today?x=1"
    private val html = """<!doctype html><html><head>
        <title>Inbox - Example Mail</title>
        <base href="/static/">
        <link rel="manifest" href="/manifest.webmanifest">
        <meta name="theme-color" content="#1a73e8">
        <meta name="apple-mobile-web-app-title" content="Mail">
        <link rel="icon" href="fav-16.png" sizes="16x16">
        <link rel="icon" type="image/png" href="fav-32.png" sizes="32x32">
        <link rel='shortcut icon' href='/favicon.ico'>
        <link rel="icon" href="logo.svg" type="image/svg+xml" sizes="any">
        <link rel="apple-touch-icon" href="/apple-180.png">
        <link rel="apple-touch-icon" sizes="120x120" href="/apple-120.png">
        </head><body></body></html>"""
    private val manifest = """{"name":"Example Mail","short_name":"Mail","start_url":"/inbox/?source=pwa","scope":"/inbox/",
        "display":"standalone","theme_color":"#1A73E8","background_color":"#fff",
        "icons":[{"src":"/i/192.png","sizes":"192x192","type":"image/png"},
                 {"src":"/i/512.png","sizes":"512x512","type":"image/png"},
                 {"src":"/i/512m.png","sizes":"512x512","type":"image/png","purpose":"maskable"},
                 {"src":"/i/mono.png","sizes":"512x512","purpose":"monochrome"},
                 {"src":"/i/any.svg","sizes":"any","type":"image/svg+xml"}]}"""

    // ---------------- head ----------------

    @Test fun readsTheHead() {
        val h = SiteLogic.parseHead(html, page)
        assertEquals("Inbox - Example Mail", h.title)
        assertEquals("Mail", h.appName)
        assertEquals("https://app.example.org/manifest.webmanifest", h.manifestUrl)
        assertEquals("#1A73E8", h.themeColor)
        // <base href> applies to relative icon links.
        assertEquals("https://app.example.org/static/fav-32.png", h.icons.first { it.size == 32 }.url)
        assertEquals(listOf(180, 120), h.icons.filter { it.source == SiteLogic.APPLE }.map { it.size })
        assertEquals(SiteLogic.ANY, h.icons.first { it.url.endsWith(".svg") }.size)
    }

    @Test fun emptyOrBrokenHtmlStillParses() {
        val h = SiteLogic.parseHead("<html><link rel=icon><link href=x.png></html>", page)
        assertTrue(h.icons.isEmpty()); assertEquals("", h.manifestUrl); assertEquals("", h.title)
        val u = SiteLogic.parseHead("<link rel=icon href=/i.png sizes=48x48>", page)
        assertEquals("https://app.example.org/i.png", u.icons.single().url); assertEquals(48, u.icons.single().size)
    }

    // ---------------- manifest ----------------

    @Test fun parsesAManifest() {
        val m = SiteLogic.parseManifest(manifest, "https://app.example.org/manifest.webmanifest")!!
        assertEquals("Example Mail", m.name); assertEquals("Mail", m.shortName)
        assertEquals("https://app.example.org/inbox/?source=pwa", m.startUrl)
        assertEquals("https://app.example.org/inbox/", m.scope)
        assertEquals("standalone", m.display)
        assertEquals("#1A73E8", m.themeColor); assertEquals("#FFFFFF", m.backgroundColor)
        // The monochrome-only icon is skipped; the maskable one is marked.
        assertEquals(4, m.icons.size)
        assertTrue(m.icons.first { it.url.endsWith("512m.png") }.maskable)
        assertNull(SiteLogic.parseManifest("not json", "https://x.org/m.json"))
        assertTrue(SiteLogic.parseManifest("{}", "https://x.org/m.json")!!.icons.isEmpty())
    }

    // ---------------- icon order ----------------

    @Test fun iconOrderIsManifestThenAppleThenLinksThenFavicon() {
        val h = SiteLogic.parseHead(html, page)
        val m = SiteLogic.parseManifest(manifest, "https://app.example.org/manifest.webmanifest")
        val order = SiteLogic.iconOrder(m, h, page).map { it.url.removePrefix("https://app.example.org") }
        assertEquals(listOf(
            "/i/512m.png", "/i/512.png", "/i/192.png",          // manifest: largest first, maskable before plain, no SVG
            "/apple-180.png", "/apple-120.png",                 // apple-touch-icon, largest first
            "/static/fav-32.png", "/static/fav-16.png", "/favicon.ico",   // link rel=icon largest first; the favicon link once
        ), order)
    }

    @Test fun noManifestFallsBackToAppleThenLinksThenFavicon() {
        val h = SiteLogic.parseHead("<link rel=\"icon\" href=\"/a.png\" sizes=\"64x64\">", "https://plain.example.net/x")
        assertEquals(listOf("https://plain.example.net/a.png", "https://plain.example.net/favicon.ico"), SiteLogic.iconOrder(null, h, "https://plain.example.net/x").map { it.url })
        // Nothing at all: only favicon.ico is tried, then the caller draws the letter icon.
        assertEquals(listOf(SiteLogic.FAVICON), SiteLogic.iconOrder(null, PageHead(), "https://bare.example.net/").map { it.source })
        assertFalse(SiteLogic.raster(IconCandidate("https://x/logo.svg?v=2", SiteLogic.LINK, 0)))
        assertFalse(SiteLogic.raster(IconCandidate("https://x/logo", SiteLogic.LINK, 0, type = "image/svg+xml")))
    }

    // ---------------- the app ----------------

    @Test fun appFromManifestAndHead() {
        val h = SiteLogic.parseHead(html, page)
        val m = SiteLogic.parseManifest(manifest, "https://app.example.org/manifest.webmanifest")
        val a = SiteLogic.appFor(m, h, page, 5)
        assertEquals("Mail", a.name)
        assertEquals("https://app.example.org/inbox/?source=pwa", a.startUrl)
        assertEquals("https://app.example.org/inbox/", a.scope)
        assertEquals("#1A73E8", a.themeColor); assertEquals("#FFFFFF", a.backgroundColor)
        assertEquals("site.app.example.org.inbox", a.id)
        assertEquals(a, SiteApp.fromJson(a.toJson()))
        assertEquals(listOf(a), SiteApp.listFromJson(SiteApp.listToJson(listOf(a, a))))
    }

    @Test fun appWithoutManifestUsesThePage() {
        val h = SiteLogic.parseHead("<title>Weather | Example</title><meta name=theme-color content=\"rgb(10, 20, 30)\">", "https://www.example.com/")
        val a = SiteLogic.appFor(null, h, "https://www.example.com/", 0)
        assertEquals("Weather", a.name)
        assertEquals("https://www.example.com/", a.scope)
        assertEquals("#0A141E", a.themeColor); assertEquals("#0A141E", a.backgroundColor)
        assertEquals("site.example.com", a.id)
        // A manifest start_url on another origin is ignored.
        val other = WebManifest(startUrl = "https://evil.example.net/", name = "X")
        assertEquals("https://www.example.com/", SiteLogic.appFor(other, h, "https://www.example.com/", 0).startUrl)
        assertEquals("example.com", SiteLogic.nameFor(null, PageHead(), "https://www.example.com/"))
    }

    @Test fun scopeDecidesWhichLinksStayInTheApp() {
        val scope = "https://app.example.org/inbox/"
        assertTrue(SiteLogic.inScope("https://app.example.org/inbox/42", scope))
        assertTrue(SiteLogic.inScope("https://app.example.org/inbox", scope))
        assertFalse(SiteLogic.inScope("https://app.example.org/settings", scope))
        assertFalse(SiteLogic.inScope("https://other.example.org/inbox/", scope))
        assertFalse(SiteLogic.inScope("http://app.example.org/inbox/", scope))
        assertTrue(SiteLogic.inScope("https://app.example.org/anything", "https://app.example.org/"))
        // A manifest scope on another origin falls back to the whole site; a file scope becomes its folder.
        assertEquals("https://app.example.org/", SiteLogic.scopeFor("https://app.example.org/a", "https://cdn.example.net/"))
        assertEquals("https://app.example.org/inbox/", SiteLogic.scopeFor("https://app.example.org/inbox/x", "https://app.example.org/inbox/index.html"))
    }

    @Test fun coloursLettersAndSizes() {
        assertEquals("#AABBCC", SiteLogic.color("#abc"))
        assertEquals("#112233", SiteLogic.color("#112233ff"))
        assertEquals("#FFFFFF", SiteLogic.color("white"))
        assertEquals("", SiteLogic.color("hsl(1,2%,3%)"))
        assertTrue(SiteLogic.isDark("#1A73E8")); assertFalse(SiteLogic.isDark("#FFFFFF"))
        assertEquals("E", SiteLogic.letter("  example")); assertEquals("?", SiteLogic.letter("!!"))
        assertEquals("#123456", SiteLogic.letterColor("x.org", "#123456"))
        assertEquals(SiteLogic.letterColor("x.org", ""), SiteLogic.letterColor("x.org", ""))
        assertEquals(512, SiteLogic.sizeOf("192x192 512x512"))
        assertEquals(SiteLogic.ANY, SiteLogic.sizeOf("any"))
        assertEquals(0, SiteLogic.sizeOf(""))
    }
}
