package com.blaze.assistant.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v0.29.7: site matching for saved passwords, the password store, tab state JSON and the saved-page index. */
class BrowserPersistenceTest {
    private lateinit var tmp: File

    @Before fun setUp() {
        tmp = Files.createTempDirectory("bzbrowser").toFile()
        BrowserLogins.fileOverride = File(tmp, "logins.json")
        // Fake "encryption" (reversed + prefix) so the test can see nothing is stored in plain text.
        BrowserLogins.seal = { "x:" + it.reversed() }
        BrowserLogins.open = { if (it.startsWith("x:")) it.removePrefix("x:").reversed() else "" }
        SavedPages.root = tmp
    }

    @After fun tearDown() { BrowserLogins.fileOverride = null; SavedPages.root = null; tmp.deleteRecursively() }

    @Test fun registrableDomains() {
        assertEquals("google.com", SiteDomain.registrable("accounts.google.com"))
        assertEquals("google.com", SiteDomain.registrable("www.google.com"))
        assertEquals("google.com", SiteDomain.registrable("GOOGLE.COM."))
        assertEquals("bbc.co.uk", SiteDomain.registrable("login.bbc.co.uk"))
        assertEquals("zesco.co.zm", SiteDomain.registrable("portal.zesco.co.zm"))
        assertEquals("joey.github.io", SiteDomain.registrable("joey.github.io"))
        assertEquals("joey.github.io", SiteDomain.registrable("a.joey.github.io"))
        assertEquals("blaze-app.workers.dev", SiteDomain.registrable("blaze-gateway.blaze-app.workers.dev"))
        assertEquals("bucket.s3.amazonaws.com", SiteDomain.registrable("bucket.s3.amazonaws.com"))
        assertEquals("192.168.1.5", SiteDomain.registrable("192.168.1.5"))
        assertEquals("localhost", SiteDomain.registrable("localhost"))
        assertEquals("", SiteDomain.registrable(""))
    }

    @Test fun sameSiteAndHosts() {
        assertTrue(SiteDomain.sameSite("m.facebook.com", "www.facebook.com"))
        assertFalse(SiteDomain.sameSite("facebook.com", "facebook.com.evil.net"))
        assertFalse(SiteDomain.sameSite("evil-facebook.com", "facebook.com"))
        assertFalse(SiteDomain.sameSite("alice.github.io", "bob.github.io"))
        assertFalse(SiteDomain.sameSite("a.co.uk", "b.co.uk"))
        assertFalse(SiteDomain.sameSite("", ""))
        assertEquals("github.com", SiteDomain.hostOf("https://www.GitHub.com:443/login?x=1"))
        assertEquals("example.com", SiteDomain.hostOf("https://user:pw@example.com/a"))
        assertEquals("", SiteDomain.hostOf("about:blank"))
        assertEquals("google.com", SiteDomain.siteOfUrl("https://accounts.google.com/signin"))
    }

    @Test fun saveDecideUpdateDelete() {
        assertEquals(BrowserLogins.Decision.Save, BrowserLogins.decide("github.com", "joey", "pw1"))
        val l = BrowserLogins.save("github.com", "joey", "pw1")!!
        assertEquals("github.com", l.site)
        // Stored sealed, never in plain text.
        val raw = BrowserLogins.fileOverride!!.readText()
        assertFalse(raw.contains("\"pw1\""))
        assertTrue(raw.contains("x:1wp"))
        // Same account on a subdomain is the same site.
        assertEquals(BrowserLogins.Decision.Same(l.id), BrowserLogins.decide("gist.github.com", "JOEY", "pw1"))
        assertEquals(BrowserLogins.Decision.Update(l.id), BrowserLogins.decide("github.com", "joey", "pw2"))
        assertEquals(BrowserLogins.Decision.Save, BrowserLogins.decide("github.com", "other", "pw1"))
        assertEquals(BrowserLogins.Decision.Save, BrowserLogins.decide("gitlab.com", "joey", "pw1"))
        // Update by saving again with the same username: still one entry.
        BrowserLogins.save("github.com", "joey", "pw2")
        BrowserLogins.save("github.com", "work@x.io", "pw3")
        assertEquals(2, BrowserLogins.forHost("www.github.com").size)
        assertEquals("pw2", BrowserLogins.forHost("github.com").first { it.user == "joey" }.password)
        assertTrue(BrowserLogins.forHost("evilgithub.com").isEmpty())
        // Edit and delete.
        val w = BrowserLogins.forHost("github.com").first { it.user == "work@x.io" }
        assertTrue(BrowserLogins.update(w.id, "work@y.io", "pw4"))
        assertEquals("pw4", BrowserLogins.all().first { it.id == w.id }.password)
        assertFalse(BrowserLogins.update(w.id, "a", ""))
        assertTrue(BrowserLogins.delete(w.id))
        assertFalse(BrowserLogins.delete(w.id))
        assertEquals(1, BrowserLogins.all().size)
        assertNull(BrowserLogins.save("", "a", "b"))
        assertNull(BrowserLogins.save("x.com", "a", ""))
    }

    @Test fun neverList() {
        assertFalse(BrowserLogins.isNever("bank.co.zm"))
        BrowserLogins.addNever("online.bank.co.zm")
        assertTrue(BrowserLogins.isNever("www.bank.co.zm"))
        assertEquals(listOf("bank.co.zm"), BrowserLogins.never())
        // Saving on purpose clears "never".
        BrowserLogins.save("bank.co.zm", "me", "pw")
        assertFalse(BrowserLogins.isNever("bank.co.zm"))
        BrowserLogins.addNever("x.com"); BrowserLogins.removeNever("x.com")
        assertTrue(BrowserLogins.never().isEmpty())
    }

    @Test fun scriptsAreSafe() {
        val js = BrowserLogins.fillJs("a\"b'</script>", "p\\w\"d")
        assertTrue(js.contains("\"a\\\"b'<\\/script>\""))
        assertTrue(js.contains("data-bz-secret"))
        assertFalse(js.contains("submit"))   // filling never sends the form
        assertTrue(BrowserLogins.CAPTURE_JS.contains("window.blazeLogin"))
        assertTrue(BrowserLogins.CAPTURE_JS.contains("data-bz-secret"))
    }

    @Test fun tabStateRoundTrip() {
        val a = TabStore.Snap("https://bbc.com/news", "BBC News", mapOf("WEBVIEW_CHROMIUM_STATE" to "b:AAEC"))
        val b = TabStore.Snap("", "")
        val c = TabStore.Snap("https://github.com/", "GitHub")
        val json = TabStore.encode(TabStore.Saved(listOf(a, b, c), 2))
        val back = TabStore.decode(json)
        assertEquals(listOf(a, b, c), back.tabs)
        assertEquals(2, back.active)
    }

    @Test fun tabStateFiltersAndLimits() {
        val big = TabStore.Snap("https://x.com/", "X", mapOf("k" to "b:" + "A".repeat(TabStore.MAX_STATE + 1)))
        val junk = TabStore.Snap("javascript:alert(1)", "bad")
        val file = TabStore.Snap("file:///sdcard/secret.html", "file")
        val blank = TabStore.Snap("about:blank", "New tab")
        val keep = TabStore.Snap("https://keep.me/", "Keep")
        val back = TabStore.decode(TabStore.encode(TabStore.Saved(listOf(big, junk, file, blank, keep), 4)))
        assertEquals(listOf("https://x.com/", "", "https://keep.me/"), back.tabs.map { it.url })
        assertTrue(back.tabs[0].state.isEmpty())   // too big: reopens by URL
        assertEquals(2, back.active)               // still the "Keep" tab
        assertEquals("", TabStore.decode("{not json").tabs.joinToString())
        assertEquals(0, TabStore.decode(null).tabs.size)
        assertEquals(0, TabStore.decode("""{"v":1,"active":9,"tabs":[{"u":"https://a.b/","t":"A"}]}""").active)
        val many = (1..60).map { TabStore.Snap("https://s$it.com/", "S$it") }
        assertEquals(TabStore.MAX_TABS, TabStore.decode(TabStore.encode(TabStore.Saved(many, 0))).tabs.size)
    }

    @Test fun activeTabIgnoresPrivate() {
        // tabs: normal, private, normal, private
        val priv = listOf(false, true, false, true)
        assertEquals(0, TabStore.activeIndex(priv, 0))
        assertEquals(1, TabStore.activeIndex(priv, 2))
        assertEquals(1, TabStore.activeIndex(priv, 3))   // on a private tab: remember the last normal one
        assertEquals(0, TabStore.activeIndex(listOf(true), 0))
    }

    @Test fun savedPageIndex() {
        val id1 = SavedPages.newId(1_700_000_000_000L, emptyList())
        val id2 = SavedPages.newId(1_700_000_000_000L, listOf(id1))
        assertTrue(SavedPages.validId(id1)); assertTrue(id1 != id2)
        assertFalse(SavedPages.validId("../etc")); assertFalse(SavedPages.validId("a/b"))
        val p1 = SavedPages.Page(id1, "Old", "https://a.com/x", 1000, 2048, true)
        val p2 = SavedPages.Page(id2, "New", "https://b.com/y", 2000, 5_000_000, false)
        val back = SavedPages.decode(SavedPages.encode(listOf(p1, p2)))
        assertEquals(listOf(p2, p1), back)   // newest first
        assertEquals(1, SavedPages.decode("""{"pages":[{"id":"../../x","t":"bad"},{"id":"$id1","t":"ok"}]}""").size)
        assertTrue(SavedPages.isSavedUrl("file:///data/user/0/com.blaze.assistant/files/saved_pages/$id1.mht"))
        assertFalse(SavedPages.isSavedUrl("file:///sdcard/Download/x.mht"))
        assertFalse(SavedPages.isSavedUrl("file:///data/x/saved_pages/../../secret.mht"))
        assertEquals(id1, SavedPages.idOfUrl("file:///d/saved_pages/$id1.mht"))
        assertTrue(TabStore.keepUrl("file:///d/saved_pages/$id1.mht"))
        assertEquals("2 KB", SavedPages.size(2048)); assertEquals("4.8 MB", SavedPages.size(5_000_000))
        assertTrue(SavedPages.textCopy("T", "https://a", "today", "a\n\n\n\nb").contains("a\n\nb"))
    }

    @Test fun savedPageFiles() {
        val id = SavedPages.newId(1_700_000_000_042L, emptyList())
        val d = SavedPages.dir()!!; d.mkdirs()
        File(d, SavedPages.archiveName(id)).writeText("MIME")
        File(d, SavedPages.textName(id)).writeText("hello")
        SavedPages.add(SavedPages.Page(id, "T", "https://a.com", 1, 4, true))
        assertEquals(1, SavedPages.list().size)
        assertEquals("hello", SavedPages.text(id))
        assertNotNull(SavedPages.archive(id))
        SavedPages.delete(id)
        assertEquals(0, SavedPages.list().size)
        assertFalse(File(d, SavedPages.archiveName(id)).exists())
        assertNull(SavedPages.text("../x"))
    }
}
