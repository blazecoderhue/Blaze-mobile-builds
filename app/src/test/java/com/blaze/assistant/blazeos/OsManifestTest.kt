package com.blaze.assistant.blazeos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.33.2 BlazeOS: app manifests, the App Store catalog, the install registry and per-app permissions. */
class OsManifestTest {
    private fun ok(json: String): OsManifestData = (OsManifest.parse(json) as OsManifest.Parsed.Ok).m
    private fun bad(json: String): String = (OsManifest.parse(json) as OsManifest.Parsed.Bad).why

    // ---------------- manifest ----------------

    @Test fun wasmEntries() {
        val m = ok("""{"id":"dev.you.game","name":"Game","entry":{"type":"wasm","path":"bin/app.wasm"},"permissions":["storage"]}""")
        assertEquals(OsEntry(OsManifest.WASM, path = "bin/app.wasm"), m.entry)
        assertTrue(OsManifest.packaged(m.entry))
        assertEquals(m, (OsManifest.parse(OsManifest.toJson(m)) as OsManifest.Parsed.Ok).m)
        // No path: app.wasm. Not a .wasm, or outside the package: refused.
        assertEquals("app.wasm", ok("""{"id":"a.b","name":"X","entry":{"type":"wasm"}}""").entry.path)
        assertEquals("Bad entry", bad("""{"id":"a.b","name":"X","entry":{"type":"wasm","path":"index.html"}}"""))
        assertEquals("Bad entry", bad("""{"id":"a.b","name":"X","entry":{"type":"wasm","path":"../x.wasm"}}"""))
        assertEquals("Bad entry", bad("""{"id":"a.b","name":"X","entry":{"type":"wasm","path":"/sdcard/x.wasm"}}"""))
        assertTrue(OsManifest.WASM in OsManifest.types)
        // A catalog listing for a wasm app needs a package.
        val c = OsManifest.catalog("""{"apps":[{"id":"a.b","name":"X","entry":{"type":"wasm"}},
            {"id":"a.c","name":"Y","entry":{"type":"wasm"},"package":"asset:blazeos/apps/y.zip"}]}""")
        assertEquals(listOf("a.c"), c.apps.map { it.manifest.id })
        assertEquals(listOf("#0: bad package"), c.errors)
    }

    @Test fun nativeSnakeShipsInTheCatalog() {
        val f = listOf(File("src/main/assets/blazeos/catalog.json"), File("app/src/main/assets/blazeos/catalog.json")).first { it.exists() }
        val c = OsManifest.catalog(f.readText())
        assertTrue(c.errors.toString(), c.errors.isEmpty())
        val s = c.apps.first { it.manifest.id == "blazeos.snake.native" }
        assertEquals(OsManifest.WASM, s.manifest.entry.type)
        assertTrue(c.apps.any { it.manifest.id == "blazeos.snake" })   // the web Snake stays
        val zip = File(f.parentFile, s.pkg.removePrefix("asset:blazeos/"))
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toList() }
        assertTrue(names.toString(), "app.wasm" in names && "manifest.json" in names)
    }

    @Test fun webAppFromAPackage() {
        val m = ok("""{"id":"com.blazeos.snake","name":"Snake","version":"1.2.0","versionCode":3,
            "icon":{"glyph":"snake","from":"#34c759","to":"#0A7D32"},"entry":{"type":"web","path":"index.html"},
            "permissions":["storage","notifications","teleport"],"category":"Games"}""")
        assertEquals("com.blazeos.snake", m.id)
        assertEquals(3, m.versionCode)
        assertEquals(OsEntry(OsManifest.WEB, path = "index.html"), m.entry)
        assertEquals(OsIcon("snake", "#34C759", "#0A7D32"), m.icon)
        assertEquals(listOf("storage", "notifications"), m.permissions)
        assertEquals("Games", m.category)
        // Round trip.
        assertEquals(m, (OsManifest.parse(OsManifest.toJson(m)) as OsManifest.Parsed.Ok).m)
    }

    @Test fun hostedAndBuiltinEntries() {
        assertEquals("https://example.org/app/", ok("""{"id":"org.example.app","name":"Hosted","entry":{"type":"web","url":"https://example.org/app/"}}""").entry.url)
        assertEquals("clock", ok("""{"id":"blazeos.clock","name":"Clock","entry":{"type":"builtin","key":"clock"}}""").entry.key)
        // Wave B-4: "linux" entries (apps for the removed on-phone Linux desktop) are refused.
        assertEquals("Bad entry", bad("""{"id":"linux.xeyes","name":"Eyes","entry":{"type":"linux","command":"xeyes","packages":["xeyes"]}}"""))
        // A web entry with no path opens index.html.
        assertEquals("index.html", ok("""{"id":"a.b","name":"X","entry":{"type":"web"}}""").entry.path)
        assertEquals("1.0", ok("""{"id":"a.b","name":"X","entry":{"type":"web"}}""").version)
        val fileIcon = ok("""{"id":"a.b","name":"X","icon":"img/icon.png","entry":{"type":"web"}}""")
        assertEquals("img/icon.png", fileIcon.icon.file)
    }

    @Test fun refusesUnsafeOrBrokenManifests() {
        assertEquals("Not valid JSON", bad("{nope"))
        assertTrue(bad("""{"id":"Bad Id","name":"X","entry":{"type":"web"}}""").startsWith("Bad id"))
        assertTrue(bad("""{"id":"a.b","name":"","entry":{"type":"web"}}""").startsWith("Name"))
        assertTrue(bad("""{"id":"a.b","name":"${"x".repeat(31)}","entry":{"type":"web"}}""").startsWith("Name"))
        assertTrue(bad("""{"id":"a.b","name":"X","version":"one","entry":{"type":"web"}}""").startsWith("Bad version"))
        assertEquals("No entry", bad("""{"id":"a.b","name":"X"}"""))
        // Path escapes, plain http, unknown types (incl. the removed "linux"), unknown built-ins.
        for (e in listOf("""{"type":"web","path":"../../etc/passwd"}""", """{"type":"web","path":"/sdcard/x.html"}""",
                """{"type":"web","url":"http://example.org"}""", """{"type":"web","url":"file:///data/x"}""", """{"type":"apk"}""",
                """{"type":"builtin","key":"phone"}""", """{"type":"linux","command":"rm -rf / ; reboot"}""",
                """{"type":"linux","command":"x","packages":["ok","Bad Pkg"]}""", """{"type":"web","path":"a\\b.html"}"""))
            assertEquals(e, "Bad entry", bad("""{"id":"a.b","name":"X","entry":$e}"""))
        // An unsafe icon path is dropped, not fatal.
        assertEquals(OsIcon(), ok("""{"id":"a.b","name":"X","icon":"../x.png","entry":{"type":"web"}}""").icon)
    }

    @Test fun pathsAndVersions() {
        assertTrue(OsManifest.safePath("index.html")); assertTrue(OsManifest.safePath("js/app.js"))
        assertFalse(OsManifest.safePath("")); assertFalse(OsManifest.safePath("a//b")); assertFalse(OsManifest.safePath("./a"))
        assertFalse(OsManifest.safePath("c:/x"))
        assertEquals(1, OsManifest.compareVersions("1.10", "1.9"))
        assertEquals(0, OsManifest.compareVersions("1.0", "1"))
        assertEquals(-1, OsManifest.compareVersions("0.9.9", "1.0"))
    }

    // ---------------- catalog ----------------

    @Test fun catalogSkipsBadEntriesAndDuplicates() {
        val c = OsManifest.catalog("""{"version":1,"remote":"https://store.example.org/catalog.json","apps":[
            {"id":"a.one","name":"One","entry":{"type":"web"},"package":"asset:blazeos/apps/one.zip","size":1200},
            {"id":"a.one","name":"One again","entry":{"type":"web"},"package":"asset:blazeos/apps/one.zip"},
            {"id":"a.two","name":"Two","entry":{"type":"web"}},
            {"id":"a.three","name":"Three","entry":{"type":"web","url":"https://three.example.org/"}},
            {"id":"a.four","name":"Four","entry":{"type":"web"},"package":"asset:../secret.zip"},
            {"id":"Nope","name":"Bad"},
            {"id":"a.five","name":"Five","entry":{"type":"linux","command":"xclock"}}]}""")
        assertEquals(listOf("a.one", "a.three"), c.apps.map { it.manifest.id })
        assertEquals(1200L, c.apps[0].size)
        assertEquals("https://store.example.org/catalog.json", c.remote)
        assertEquals(5, c.errors.size)   // + the "linux" entry (wave B-4)
        assertTrue(OsManifest.catalog("[]").apps.isEmpty())
    }

    @Test fun theBundledCatalogParsesWithNoErrors() {
        val f = listOf(File("src/main/assets/blazeos/catalog.json"), File("app/src/main/assets/blazeos/catalog.json")).first { it.exists() }
        val c = OsManifest.catalog(f.readText())
        assertTrue(c.errors.toString(), c.errors.isEmpty())
        val ids = c.apps.map { it.manifest.id }
        assertTrue(ids.toString(), listOf("blazeos.calculator", "blazeos.notes", "blazeos.snake").all { it in ids })
        // Every packaged app's zip is really in the assets.
        for (a in c.apps.filter { it.pkg.startsWith("asset:") })
            assertTrue(a.pkg, File(f.parentFile.parentFile, a.pkg.removePrefix("asset:")).exists())
    }

    // ---------------- registry ----------------

    private val snake = ok("""{"id":"blazeos.snake","name":"Snake","versionCode":2,"entry":{"type":"web"}}""")

    @Test fun installUpdateAndQuota() {
        val r0 = OsRegistry()
        assertEquals(OsRegistry.INSTALL, r0.kindOf(snake))
        val r1 = (r0.install(snake, 1000, quota = 5000, diskUsed = 0, now = 10) as OsRegistry.Result.Ok).reg
        assertTrue(r1.has("blazeos.snake")); assertEquals(1000L, r1.used); assertEquals("pkg:blazeos.snake", r1.get("blazeos.snake")!!.appId)
        assertEquals(OsRegistry.SAME, r1.kindOf(snake))
        val v3 = snake.copy(versionCode = 3)
        assertEquals(OsRegistry.UPDATE, r1.kindOf(v3))
        // An update only needs the extra bytes: 4500 on disk + 500 more fits in 5000.
        val r2 = (r1.install(v3, 1500, quota = 5000, diskUsed = 4500, now = 20) as OsRegistry.Result.Ok).reg
        assertEquals(1, r2.apps.size); assertEquals(3, r2.get("blazeos.snake")!!.manifest.versionCode)
        assertEquals(10L, r2.get("blazeos.snake")!!.installedAt)
        // Downgrades and a full disk are refused.
        assertTrue(r2.install(snake, 10, 5000, 0, 30) is OsRegistry.Result.Refused)
        assertEquals(OsRegistry.OLDER, r2.kindOf(snake))
        val other = ok("""{"id":"a.big","name":"Big","entry":{"type":"web"}}""")
        val refused = r2.install(other, 4000, quota = 5000, diskUsed = 1500, now = 40)
        assertTrue(refused is OsRegistry.Result.Refused)
        assertTrue((refused as OsRegistry.Result.Refused).why.startsWith("Not enough space"))
        // Uninstall frees the space.
        assertEquals(0L, r2.uninstall("blazeos.snake").used)
    }

    @Test fun registryJsonRoundTripDropsJunk() {
        val r = (OsRegistry().install(snake, 99, 1000, 0, 5, preinstalled = true) as OsRegistry.Result.Ok).reg
        val back = OsRegistry.fromJson(r.toJson())
        assertEquals(r, back)
        assertTrue(back.get("blazeos.snake")!!.preinstalled)
        assertEquals(OsRegistry(), OsRegistry.fromJson("garbage"))
        assertEquals(OsRegistry(), OsRegistry.fromJson(null))
        assertEquals(1, OsRegistry.fromJson("""{"apps":[{"manifest":{"id":"x"}},{"manifest":${OsManifest.toJson(snake)},"size":5}]}""").apps.size)
    }

    // ---------------- permissions ----------------

    @Test fun permissionsPerApp() {
        var g = OsGrants()
        assertEquals(OsGrants.Decision.ASK, g.decide("pkg:a", OsPerms.CAMERA))
        g = g.set("pkg:a", OsPerms.CAMERA, true).set("pkg:a", OsPerms.MIC, false).set("pkg:b", OsPerms.CAMERA, false)
        assertEquals(OsGrants.Decision.ALLOWED, g.decide("pkg:a", OsPerms.CAMERA))
        assertEquals(OsGrants.Decision.DENIED, g.decide("pkg:a", OsPerms.MIC))
        assertEquals(OsGrants.Decision.DENIED, g.decide("pkg:b", OsPerms.CAMERA))
        assertEquals(OsGrants.Decision.ASK, g.decide("pkg:b", OsPerms.MIC))
        // Unknown permissions are never stored.
        assertEquals(g, g.set("pkg:a", "contacts", true))
        assertEquals(g, OsGrants.fromJson(g.toJson()))
        assertEquals(OsGrants.Decision.ASK, g.reset("pkg:a", OsPerms.CAMERA).decide("pkg:a", OsPerms.CAMERA))
        assertEquals(OsGrants.Decision.ASK, g.forget("pkg:a").decide("pkg:a", OsPerms.MIC))
        assertFalse("pkg:b" in g.reset("pkg:b", OsPerms.CAMERA).map)
        assertEquals(OsGrants(), OsGrants.fromJson("{bad"))
        assertEquals("android.permission.CAMERA", OsPerms.hostPermission(OsPerms.CAMERA))
        assertEquals(null, OsPerms.hostPermission(OsPerms.STORAGE))
        assertEquals(listOf(OsPerms.CAMERA), OsPerms.forBuiltin("camera"))
    }
}
