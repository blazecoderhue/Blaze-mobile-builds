package com.blaze.assistant.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 0.45.19 App Store catalog: the bundled 442-app file and the 10-app seed both parse, search and resolve; every listing
 * carries the policy line, its license and the source repo; no price/ads/gates; no provider or owner name in store
 * strings; the cache keeps the last good copy.
 */
class StoreCatalogTest {
    private val bundled by lazy { CatalogParser.parse(File("src/main/assets/store/catalog.json").readText()) }
    private val seed by lazy { CatalogParser.parse(File("../store/seed.json").readText()) }

    @Test fun bundledCatalogParsesEveryApp() {
        assertEquals(442, bundled.apps.size)
        assertEquals("the repo copy and the bundled copy are the same file", File("../store/catalog.json").readText(), File("src/main/assets/store/catalog.json").readText())
        assertTrue(bundled.apps.all { it.source.url.startsWith("https://github.com/") || it.source.url.startsWith("https://gitlab.com/") })
        assertEquals(bundled.apps.size, bundled.apps.map { it.id }.toSet().size)
        assertTrue(bundled.shelf().isNotEmpty())
        // Seed identity facts (signer pins) carried onto the full catalog.
        assertTrue(bundled.byPackage("org.schabi.newpipe")!!.signers.isNotEmpty())
        assertTrue(bundled.byPackage("org.schabi.newpipe")!!.knownSha("v0.29.1", "NewPipe_v0.29.1.apk") != null)
    }

    @Test fun seedCatalogParses() {
        assertEquals(10, seed.apps.size)
        assertTrue(seed.apps.all { it.signers.isNotEmpty() })
    }

    @Test fun searchFindsByNameTagAndSummary() {
        for (cat in listOf(seed, bundled)) {
            val np = StoreSearch.search(cat.apps, "newpipe")
            assertEquals("org.schabi.newpipe", np.first().pkg)
            assertNotNull(StoreSearch.resolve(cat.apps, "NewPipe"))
            assertTrue(StoreSearch.search(cat.apps, "zzzz-nothing-like-this").isEmpty())
        }
        assertTrue(StoreSearch.search(seed.apps, "youtube").any { it.id == "newpipe" })
        assertNull("blank never resolves", StoreSearch.resolve(bundled.apps, "  "))
    }

    @Test fun policyLineOnEveryListing() {
        for (a in bundled.apps + seed.apps) {
            val l = StoreTools.line(a)
            assertTrue(l, l.contains(StorePolicy.NOTICE))
            assertTrue(l, l.contains("Source repo: ${a.source.url}"))
            assertTrue(l, l.contains(a.license.ifBlank { "license not stated" }))
        }
        assertEquals("Open-source, not made by Blazeinchue. Install at your own risk.", StorePolicy.NOTICE)
        assertEquals("Source repo", StorePolicy.SOURCE_BUTTON)
        val page = File("src/main/java/com/blaze/assistant/ui/AppStorePage.kt").readText()
        assertTrue("each row shows the notice", page.contains("Text(StorePolicy.NOTICE"))
        assertTrue("each row has the Source repo button", page.contains("OutlinePill(StorePolicy.SOURCE_BUTTON)"))
        assertTrue("each row shows its license", page.contains("a.license.ifBlank"))
    }

    @Test fun noPriceAdsOrGates() {
        val strings = kotlinStrings().lowercase()
        for (w in listOf("price", "subscribe", "premium", "paywall", "advert", "admob", "in-app purchase", "upgrade to", "unlock"))
            assertFalse("store strings must not mention '$w'", strings.contains(w))
        assertTrue(StorePolicy.FREE.startsWith("Free."))
    }

    @Test fun noProviderOrOwnerNamesInStoreStrings() {
        val text = storeStrings()
        for (n in listOf("lack" + "son", "kap" + "apa")) assertFalse("'$n' in store strings", text.lowercase().contains(n))
        assertFalse("owner first name in store strings", Regex("\\b" + "lu" + "ke" + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
    }

    @Test fun toolsAreStorePrefixedAndHonest() {
        val defs = StoreTools.definitions().map { it.getJSONObject("function") }
        assertEquals(StoreTools.names, defs.map { it.getString("name") }.toSet())
        assertTrue(StoreTools.names.containsAll(setOf("store_search", "store_install", "store_update", "store_open", "store_status")))
        val install = defs.first { it.getString("name") == StoreTools.INSTALL }.getString("description")
        assertTrue(install.contains("Android's"))
        assertFalse(install.contains("Blaze Phone"))
    }

    @Test fun versions() {
        assertTrue(StoreVersions.newer("v0.29.2", "0.29.1"))
        assertFalse(StoreVersions.newer("v0.29.1", "0.29.1"))
        assertFalse(StoreVersions.newer("v1.0", null))
        assertEquals("24.0", StoreVersions.display("THUNDERBIRD_24_0"))
        assertEquals("0.29.1", StoreVersions.display("v0.29.1"))
    }

    @Test fun badEntriesAreSkippedNotFatal() {
        val c = CatalogParser.parse("""{"schema":1,"apps":[{"id":"BAD ID"},{"id":"ok","name":"Ok","package":"a.b","summary":"s","category":"x",
            "source":{"type":"github","repo":"o/r"},"asset":{"pattern":"^r\\.apk$"}}]}""")
        assertEquals(listOf("ok"), c.apps.map { it.id })
        assertEquals(listOf("other"), c.shelf().map { it.id })
    }

    @Test fun cacheKeepsLastGoodCopy() {
        val dir = Files.createTempDirectory("storecache").toFile()
        val good = File("../store/seed.json").readText()
        val cache = CatalogCache(dir) { good }
        assertEquals(10, cache.load().apps.size)   // bundled seed on first run
        val big = File("../store/catalog.json").readText()
        assertEquals(CatalogCache.Outcome.UPDATED, cache.refresh("u", { _, _ -> CatalogCache.Response(200, big, "\"e1\"") }).outcome)
        assertEquals(442, cache.load().apps.size)   // the live 442 file replaces the 10-app copy
        assertEquals(CatalogCache.Outcome.BAD_BODY, cache.refresh("u", { _, _ -> CatalogCache.Response(200, "{nope", null) }).outcome)
        assertEquals(442, cache.load().apps.size)
        assertEquals(CatalogCache.Outcome.NOT_MODIFIED, cache.refresh("u", { _, e -> assertEquals("\"e1\"", e); CatalogCache.Response(304, null, null) }).outcome)
        assertEquals(CatalogCache.Outcome.OFFLINE, cache.refresh("u", { _, _ -> throw java.io.IOException("down") }).outcome)
        assertEquals(442, cache.load().apps.size)
        // Site still serving the 10-app seed is a valid 200 too.
        assertEquals(CatalogCache.Outcome.UPDATED, cache.refresh("u", { _, _ -> CatalogCache.Response(200, good, "\"e2\"") }).outcome)
        assertEquals(10, cache.load().apps.size)
        dir.deleteRecursively()
    }

    @Test fun resolverPicksAbiAssetAndGithubDigest() {
        val a = seed.byId("newpipe")!!.copy(asset = AssetRule(Regex("^app-universal\\.apk$"), mapOf("arm64-v8a" to Regex("^app-arm64\\.apk$"))))
        val body = """[{"tag_name":"v2","draft":false,"prerelease":true,"assets":[{"name":"app-arm64.apk"}]},
            {"tag_name":"v1","draft":false,"prerelease":false,"assets":[{"name":"app-universal.apk","browser_download_url":"https://github.com/x/u","size":5},
            {"name":"app-arm64.apk","browser_download_url":"https://github.com/x/a","size":4,"digest":"sha256:${"c".repeat(64)}"},{"name":"app-arm64.apk.sha256"}]}]"""
        val r = ReleaseResolver({ body }, listOf("arm64-v8a")).resolve(a)
        assertEquals("v1", r.tag); assertEquals("app-arm64.apk", r.name); assertEquals("c".repeat(64), r.hostSha256)
        val u = ReleaseResolver({ body }, listOf("x86")).resolve(a)
        assertEquals("app-universal.apk", u.name)
    }

    private fun storeFiles() = (File("src/main/java/com/blaze/assistant/appstore").listFiles()!!.filter { it.extension == "kt" } +
        File("src/main/java/com/blaze/assistant/ui/AppStorePage.kt"))

    private fun kotlinStrings(): String = storeFiles().joinToString("\n") { f -> Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"").findAll(f.readText()).joinToString("\n") { it.value } }

    private fun storeStrings(): String = storeFiles().joinToString("\n") { it.readText() } + File("../store/catalog.json").readText() + File("../store/seed.json").readText() +
        File("../store/CATALOG_SCHEMA.md").readText()
}
