package com.blaze.store

import com.blaze.store.data.*
import com.blaze.store.ui.bundleIssueBody
import com.blaze.store.ui.cleanPermName
import com.blaze.store.ui.webSettingsImport

import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    @Test fun versions() {
        assertEquals("1.2.3", normalizeVersion("v1.2.3-beta+5"))
        assertTrue(compareVersions("1.10", "1.9") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertEquals(0, compareVersions(null, "1.0"))
        assertTrue(isSameVersion("v2.0", "2.0-rc"))
        assertTrue(isStoreUpdateAvailable("1.5.5", "1.6.0"))
        assertFalse(shouldForceStoreUpdate("1.5.5", "1.5.5"))
    }

    @Test fun imageLinks() {
        assertNull(explainRejectedImageLink("https://example.com/a.png"))
        assertNull(explainRejectedImageLink("https://raw.githubusercontent.com/o/r/main/x"))
        assertEquals("Link must start with http:// or https://", explainRejectedImageLink("ftp://x/a.png"))
        assertNotNull(explainRejectedImageLink("https://example.com/page"))
        assertEquals("https://raw.githubusercontent.com/o/r/main/s.png", sanitizeGitHubImageUrl("https://github.com/o/r/blob/main/s.png"))
        assertEquals("https://x.com/a.png", sanitizeGitHubImageUrl("https://x.com/a.png"))
        assertEquals("https://play-lh.googleusercontent.com/abc=h720-rw", optimizedImageUrl("https://play-lh.googleusercontent.com/abc=w526-h296", null, 720))
        assertEquals("https://play-lh.googleusercontent.com/abc=w256-h256-rw", optimizedImageUrl("https://play-lh.googleusercontent.com/abc", 256, 256))
        assertTrue(optimizedImageUrl("https://raw.githubusercontent.com/o/r/main/s.png", null, 720).startsWith("https://wsrv.nl/?url="))
    }

    @Test fun repoParsing() {
        assertEquals(RepoSource("github", "o/app", "o", "app"), parseRepoSource("https://github.com/o/app.git"))
        assertEquals(RepoSource("github", "o/app", "o", "app"), parseRepoSource("https://github.com/o/app/tree/main/src"))
        assertEquals(RepoSource("gitlab", "g/sub/app", "g", "app", "gitlab.com"), parseRepoSource("https://gitlab.com/g/sub/app/-/releases"))
        assertEquals("codeberg", parseRepoSource("https://codeberg.org/o/r")?.provider)
        assertNull(parseRepoSource("https://example.com/o/r"))
        assertNull(parseRepoSource("https://github.com/o"))
        assertEquals("com.foo.bar", extractPackageName("android {\n  namespace = \"x.y\"\n  defaultConfig { applicationId \"com.foo.bar\" }\n}"))
        assertEquals("org.x", extractPackageName("<manifest package=\"org.x\">"))
        assertEquals("My Cool App", humanizeRepoName("my-cool_app.git"))
        assertEquals("my-app-2", slugId("  My App 2!! "))
    }

    @Test fun submissionUrls() {
        val u = submissionIssueUrl("[]", 2, "Foo", "1.5.5")
        assertTrue(u.startsWith("https://gitlab.com/blazeinchue/Blaze-agent/-/issues/new?issue%5Btitle%5D="))
        assertTrue(enc("App Submission [2 Apps] - Foo") in u)
        val body = bundleIssueBody("T", "D", "fa-bolt", "c", listOf("a", "zz"), listOf(AppItem(id = "a", name = "A", packageName = "p.a")), null)
        assertTrue("- **A** (`a`) · `p.a`" in body)
        assertTrue("- zz" in body)
        assertFalse("Submitted by" in body)
        assertFalse("\n\n" in body)
    }

    @Test fun categories() {
        val music = AppItem(id = "m", name = "Tunes", description = "Listen to your album collection", category = "Media")
        val tool = AppItem(id = "t", name = "Box", category = "Utility", tags = listOf("Privacy"))
        assertTrue(appMatchesCategoryFilter(music, "Music")) // preset keyword in description
        assertTrue(appMatchesCategoryFilter(tool, "privacy")) // tag, case-insensitive
        assertTrue(appMatchesCategoryFilter(tool, "Utilities")) // preset raw category
        assertFalse(appMatchesCategoryFilter(tool, "Music"))
        val cards = homepageCategoryCards(listOf(music, tool, tool.copy(id = "t2")))
        assertEquals(1, cards.first { it.label == "Music" }.count)
        assertEquals(2, cards.first { it.label == "Utilities" }.count)
        assertEquals(listOf("All", "Music", "Privacy", "Utilities", "Media", "Utility"), availableFilterCategories(listOf(music, tool)))
    }

    @Test fun catalogCacheRoundTrip() {
        val c = CatalogCache(listOf(AppItem(id = "a", name = "A", variants = listOf(AppVariant("ARM64", "https://x/a.apk", 42)))), null, "GitHub")
        assertEquals(c, json.decodeFromString(CatalogCache.serializer(), json.encodeToString(CatalogCache.serializer(), c)))
    }

    @Test fun webCarryOver() {
        val s = webSettingsImport("""{"state":{"dinoHighScore":420,"theme":"dark"},"version":0}""", Settings())
        assertEquals(420L, s.dinoHighScore); assertEquals("dark", s.theme)
        // A clashing field type drops to the identity fields
        val f = webSettingsImport("""{"state":{"theme":"light","hiddenTabs":{"bad":1}}}""", Settings())
        assertEquals("light", f.theme)
        assertEquals(Settings(), webSettingsImport("not json", Settings()))
    }

    @Test fun releaseNotes() {
        val body = "# Blaze Store 2.1.5\r\n\r\nIntro text.\r\n\r\n> **Updating?** Install over it.\r\n\r\n## 🔄 Hands-Free Updates\r\n\r\n" +
            "* **Updates That Install Themselves:** Turn on **Auto-update apps** in Settings.\r\n* **Safe by Design:** Checks every download.\r\n\r\n---\r\n\r\n" +
            "### Fixes\r\n- Fixed apps showing as unavailable. Your list is kept.\r\n- See [the docs](https://x.y) for `more`.\r\n## Empty section\r\n"
        val s = releaseHighlights(body)
        assertEquals(listOf("🔄 Hands-Free Updates", "Fixes"), s.map { it.title })
        assertEquals(listOf("Updates That Install Themselves", "Safe by Design"), s[0].items)
        assertEquals(listOf("Fixed apps showing as unavailable", "See the docs for more"), s[1].items)
        assertTrue(releaseHighlights("- " + "word ".repeat(40)).single().items.single().let { it.length <= 90 && it.endsWith("…") })
        val r = parseReleases("""[{"tag_name":"v2.0.5","name":"Orion-RE : The Labyrinth Update","published_at":"2026-10-14T06:47:22Z","html_url":"u","body":"- Fix"},{"draft":true,"tag_name":"x"}]""")
        assertEquals(1, r.size); assertEquals("2.0.5", r[0].version); assertEquals("The Labyrinth Update", r[0].name); assertEquals(1_791_960_442_000L, r[0].publishedAt)
    }

    @Test fun channelPackages() {
        val cs = AppItem(id = "cs", name = "CloudStream", packageName = "com.lagradost.cloudstream3")
        val installed = listOf("com.lagradost.cloudstream3.prerelease", "com.lagradost.cloudstream3.plugins.manager", "com.lagradost.cloudstream3x", "com.lagradost.cloudstream3.beta2")
        val c = packageCandidates(cs, null, installed)
        assertTrue("com.lagradost.cloudstream3.prerelease" in c); assertTrue("com.lagradost.cloudstream3.beta2" in c)
        assertFalse("com.lagradost.cloudstream3.plugins.manager" in c); assertFalse("com.lagradost.cloudstream3x" in c)
        assertEquals("com.lagradost.cloudstream3", c[0]); assertEquals("x.y", packageCandidates(cs, "x.y").first())
    }

    @Test fun aiAnswers() {
        val a = com.blaze.store.platform.StoreAi.parse("<think>hmm</think>Try Metrolist [3] or ViMusic [7, 9]. **Both** are free.")
        assertEquals("Try Metrolist or ViMusic. Both are free.", a.reply); assertEquals(listOf(3, 7, 9), a.picks)
        val apps = listOf(AppItem(id = "m", name = "Metrolist", category = "Music", description = "YouTube Music client"), AppItem(id = "k", name = "Keyboard", category = "Tools"))
        assertEquals(listOf(0), com.blaze.store.platform.StoreAi.matches(apps, "I want to listen to songs for free", 5))
        assertTrue(com.blaze.store.platform.StoreAi.matches(apps, "hey whats orion", 5).isEmpty())
    }

    @Test fun misc() {
        assertEquals("FINE LOCATION", cleanPermName("android.permission.ACCESS_FINE_LOCATION"))
    }
}
