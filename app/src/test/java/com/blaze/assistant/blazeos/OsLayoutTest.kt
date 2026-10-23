package com.blaze.assistant.blazeos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.2 BlazeOS: grid layout, page packing, Home order, the dock, icon maths and prefs migration. */
class OsLayoutTest {
    private fun inst(n: Int) = (1..n).map { OsApp(OsApps.pkgId("com.example.app$it"), "App $it", OsManifest.WEB, removable = true) }
    private fun ids(n: Int, p: String = "a") = (1..n).map { "$p$it" }

    // ---------------- grid ----------------

    @Test fun gridParsesTheThreeSizesAndFallsBack() {
        assertEquals(OsGrid(4, 5), OsGrid.parse("4x5"))
        assertEquals(OsGrid(5, 6), OsGrid.parse(" 5X6 "))
        assertEquals(OsGrid.DEFAULT, OsGrid.parse("7x9"))
        assertEquals(OsGrid.DEFAULT, OsGrid.parse(null))
        assertEquals(24, OsGrid(4, 6).slots)
        assertEquals("4x6", OsGrid.DEFAULT.id)
    }

    @Test fun widgetRowsPairHalvesAndKeepFullsAlone() {
        assertEquals(listOf(listOf("calendar", "ask"), listOf("music"), listOf("battery")), OsWidgets.rows(OsWidgets.DEFAULT))
        // The first drafts' "devices" widget reads as battery.
        assertEquals(listOf("battery"), OsWidgets.norm(listOf("devices", "battery")))
        // A half followed by a full sits alone.
        assertEquals(listOf(listOf("ask"), listOf("music"), listOf("calendar")), OsWidgets.rows(listOf("ask", "music", "calendar")))
        // Unknown kinds and repeats are dropped.
        assertEquals(listOf(listOf("music")), OsWidgets.rows(listOf("music", "nope", "music")))
    }

    // ---------------- page packing ----------------

    @Test fun mockupLayoutTwoWidgetRowsThenTwoIconRows() {
        val pages = OsPages.pack(OsWidgets.rows(OsWidgets.DEFAULT), ids(41), OsGrid(4, 6))
        // Page 1: calendar + ask, music, then 2 rows of 4 icons (like the mockup).
        assertEquals(listOf(listOf("calendar", "ask"), listOf("music")), pages[0].widgetRows)
        assertEquals(ids(8), pages[0].apps)
        // Page 2: battery, then 16 more icons.
        assertEquals(listOf(listOf("battery")), pages[1].widgetRows)
        assertEquals((9..24).map { "a$it" }, pages[1].apps)
        // Page 3: the rest.
        assertEquals((25..41).map { "a$it" }, pages[2].apps)
        assertEquals(3, pages.size)
        assertEquals(41, pages.sumOf { it.apps.size })
    }

    @Test fun smallGridKeepsOneWidgetRowAPage() {
        val pages = OsPages.pack(OsWidgets.rows(OsWidgets.DEFAULT), ids(5), OsGrid(4, 5))
        assertTrue(pages.take(3).all { it.widgetRows.size == 1 })
        assertEquals(5, pages[0].apps.size)
        assertEquals(5, pages.sumOf { it.apps.size })
        assertEquals(3, pages.count { it.widgetRows.isNotEmpty() })
    }

    @Test fun noWidgetsAndNoAppsStillGivesOnePage() {
        val p = OsPages.pack(emptyList(), emptyList(), OsGrid(4, 6))
        assertEquals(1, p.size); assertTrue(p[0].apps.isEmpty())
        val q = OsPages.pack(emptyList(), ids(48), OsGrid(4, 6))
        assertEquals(2, q.size); assertEquals(24, q[1].apps.size)
        val r = OsPages.pack(emptyList(), ids(49), OsGrid(4, 6))
        assertEquals(3, r.size); assertEquals(listOf("a49"), r[2].apps)
    }

    @Test fun fiveColumnGridFillsWider() {
        val p = OsPages.pack(listOf(listOf("music")), ids(40), OsGrid(5, 6))
        assertEquals(20, p[0].apps.size)
        assertEquals(20, p[1].apps.size)
    }

    @Test fun pageLookupsAndDragSlots() {
        val pages = OsPages.pack(OsWidgets.rows(OsWidgets.DEFAULT), ids(30), OsGrid(4, 6))
        assertEquals(0, OsPages.pageOf(pages, "a3"))
        assertEquals(1, OsPages.pageOf(pages, "a9"))
        assertEquals(-1, OsPages.pageOf(pages, "zz"))
        assertEquals(8 + 2, OsPages.flatIndex(pages, 1, 2))
        assertEquals(1, OsPages.pageOf(pages, "a24"))
        assertEquals(2, OsPages.pageOf(pages, "a25"))
        // One cell right, one row down on a 4-wide grid.
        assertEquals(6, OsPages.dropSlot(1, 1, 1, 4, 8))
        assertEquals(7, OsPages.dropSlot(6, 3, 3, 4, 8))
        assertEquals(0, OsPages.dropSlot(2, -5, 0, 4, 8))
    }

    // ---------------- Home order ----------------

    @Test fun homeOrderDropsUninstalledRemovedAndDockedThenAppendsNewApps() {
        val all = OsApps.merge(inst(3))
        val a1 = inst(3)[0].id; val a2 = inst(3)[1].id; val a3 = inst(3)[2].id
        val saved = listOf(a2, "pkg:gone", a1, a2, "blaze:music")
        val o = OsHome.order(saved, all, removed = setOf("blaze:video"), dock = listOf("blaze:browser"))
        assertEquals(listOf(a2, a1, "blaze:music"), o.take(3))
        assertFalse("pkg:gone" in o)
        assertFalse("blaze:video" in o)
        assertFalse("blaze:browser" in o)
        assertTrue(a3 in o)
        assertEquals(o.distinct(), o)
    }

    @Test fun firstRunIsBuiltinsThenInstalledAppsAToZ() {
        val all = OsApps.merge(listOf(OsApp("pkg:z", "Zed", OsManifest.WEB), OsApp("pkg:b", "bravo", OsManifest.WASM)))
        val o = OsHome.order(emptyList(), all, emptySet(), emptyList())
        assertEquals(OsApps.builtinApps().map { it.id } + listOf("pkg:b", "pkg:z"), o)
        // Every built-in the brief lists is there, once, and only BlazeOS's own apps (never the host phone's).
        assertEquals(13, OsApps.builtins.size)   // wave B-4: the "linux" Desktop built-in was removed
        assertTrue(listOf("blaze", "camera", "photos", "music", "video", "browser", "calendar", "clock", "weather", "files", "terminal", "store", "settings")
            .all { it in OsApps.builtinKeys })
        assertTrue(all.all { it.id.startsWith(OsApps.BUILTIN) || it.id.startsWith(OsApps.PKG) })
        assertEquals("z", all.first { it.id == "pkg:z" }.key)
        assertEquals("music", OsApp("blaze:music", "Music").key)
    }

    @Test fun moveRemoveRestore() {
        val o = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), OsHome.move(o, "a", 2))
        assertEquals(listOf("a", "b", "c", "d"), OsHome.move(o, "x", 0))
        assertEquals(listOf("d", "a", "b", "c"), OsHome.move(o, "d", -3))
        val (o2, r2) = OsHome.remove(o, emptySet(), "b")
        assertEquals(listOf("a", "c", "d"), o2); assertEquals(setOf("b"), r2)
        val (o3, r3) = OsHome.restore(o2, r2, "b")
        assertEquals(listOf("a", "c", "d", "b"), o3); assertTrue(r3.isEmpty())
    }

    // ---------------- dock ----------------

    @Test fun dockRules() {
        val all = OsApps.merge(emptyList())
        assertEquals(listOf("blaze:blaze", "blaze:browser", "blaze:music", "blaze:camera"), OsDock.defaults(all))
        val installed = all.map { it.id }.toSet()
        assertEquals(listOf("blaze:music", "blaze:blaze"), OsDock.normalize(listOf("blaze:music", "pkg:gone", "blaze:music", "blaze:blaze"), installed))
        assertEquals(4, OsDock.normalize(OsApps.builtinApps().map { it.id }, installed).size)
        val full = listOf("a", "b", "c", "d")
        assertFalse(OsDock.canAdd(full, "e"))
        assertEquals(full, OsDock.toggle(full, "e"))
        assertEquals(listOf("a", "c", "d"), OsDock.toggle(full, "b"))
        assertEquals(listOf("a", "c", "d", "e"), OsDock.toggle(listOf("a", "c", "d"), "e"))
        assertEquals(listOf("b", "a", "c", "d"), OsDock.move(full, "a", 1))
        assertEquals(full, OsDock.move(full, "a", -1))
    }

    // ---------------- search / library ----------------

    @Test fun searchPrefersLabelStarts() {
        val apps = listOf(OsApp("pkg:com.msg", "Messages"), OsApp("pkg:com.google.maps", "Google Maps"), OsApp("pkg:com.google.gm", "Gmail"), OsApp("pkg:com.cam", "Camera"))
        assertEquals(listOf("pkg:com.google.gm", "pkg:com.google.maps"), OsApps.search(apps, "g").map { it.id }.take(2))
        assertEquals(listOf("pkg:com.google.maps"), OsApps.search(apps, "maps").map { it.id })
        assertEquals(listOf("pkg:com.google.maps", "pkg:com.google.gm"), OsApps.search(apps, "google").map { it.id })
        assertEquals(4, OsApps.search(apps, " ").size)
        val g = OsApps.groups(apps + OsApp("pkg:p", "1Password"))
        assertEquals(listOf("C", "G", "M", "#"), g.map { it.first })
    }

    // ---------------- icons ----------------

    @Test fun iconMaths() {
        assertEquals(96, OsIcons.sizePx(48f, 2f))
        assertEquals(OsIcons.MAX_PX, OsIcons.sizePx(60f, 4f))
        assertEquals(24, OsIcons.sizePx(4f, 1f))
        val pts = OsIcons.squircle(64)
        assertEquals(64, pts.size)
        assertTrue(pts.all { (x, y) -> x in 0f..1f && y in 0f..1f })
        // A squircle reaches further into the corner than a circle (|x|^5 + |y|^5 = 1 at 45° ≈ 0.87 of the half-size).
        val corner = pts[8]
        assertTrue(corner.first > 0.5f + 0.35355f + 0.05f)
        assertEquals(0xFF4DA3FFL, OsIcons.parseColor("#4da3ff"))
        assertNull(OsIcons.parseColor("#12345"))
        assertNull(OsIcons.parseColor("zzzzzz"))
        assertEquals("#4DA3FF", OsIcons.hex(0xFF4DA3FFL))
        assertEquals("squircle", OsIcons.normShape("blob"))
        // Decode sampling: a 4000 x 3000 photo for a 1280 px wallpaper decodes at 1/2 (1500 short side), an icon at 1/8.
        assertEquals(2, OsIcons.sampleSize(4000, 3000, 1280))
        assertEquals(16, OsIcons.sampleSize(4000, 3000, 144))
        assertEquals(1, OsIcons.sampleSize(100, 100, 144))
        assertEquals(1, OsIcons.sampleSize(100, 100, 0))
        assertEquals("circle", OsIcons.normShape("Circle"))
    }

    @Test fun tintMatrixIsIdentityAtZeroAndGreyTimesTintAtOne() {
        val id = OsIcons.tintMatrix(1f, 0f, 0f, strength = 0f)
        assertEquals(20, id.size)
        val expect = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        for (i in 0 until 20) assertEquals("i=$i", expect[i], id[i], 1e-6f)
        val red = OsIcons.tintMatrix(1f, 0f, 0f, strength = 1f)
        // Green and blue rows are zero; the red row is the luminance weights (lifted); alpha untouched.
        for (i in 5 until 15) assertEquals(0f, red[i], 1e-6f)
        assertTrue(red[1] > red[0] && red[0] > red[2])
        assertEquals(1f, red[18], 1e-6f)
    }

    // ---------------- prefs + migration ----------------

    @Test fun prefsRoundTrip() {
        val p = OsPrefs(classic = true, wallpaper = "solid:#1f2a44", iconShape = "circle", iconTint = "#4da3ff", grid = OsGrid(5, 6), labels = false,
            dockOn = false, dock = listOf("blaze:music", "blaze:camera"), glass = "high", accent = "#69DB7C", theme = "light", font = "l",
            order = listOf("blaze:blaze", "pkg:a"), removed = setOf("blaze:video"), widgets = listOf("music", "ask"),
            volume = 35, brightness = 80, lastApp = "blaze:clock")
        val m = OsPrefs.write(p)
        val back = OsPrefs.read { m[it] }
        assertEquals(p.copy(wallpaper = "solid:#1F2A44", iconTint = "#4DA3FF"), back)
        assertEquals(1.15f, back.fontScale, 1e-6f)
        assertFalse(back.dark)
    }

    @Test fun emptyDockAndNoWidgetsSurviveARoundTrip() {
        val m = OsPrefs.write(OsPrefs(dock = emptyList(), widgets = emptyList()))
        val back = OsPrefs.read { m[it] }
        assertEquals(emptyList<String>(), back.dock)
        assertEquals(emptyList<String>(), back.widgets)
    }

    @Test fun missingOrJunkValuesReadAsDefaults() {
        val junk = mapOf(OsPrefs.K_GRID to "9x9", OsPrefs.K_WALL to "preset:mars", OsPrefs.K_GLASS to "ultra", OsPrefs.K_FONT to "xl",
            OsPrefs.K_TINT to "red", OsPrefs.K_LABELS to "maybe", OsPrefs.K_WIDGETS to "nope\nmusic")
        val p = OsPrefs.read { junk[it] }
        assertEquals(OsGrid.DEFAULT, p.grid)
        assertEquals(OsWallpaper.DEFAULT, p.wallpaper)
        assertEquals("medium", p.glass)
        assertEquals("m", p.font)
        assertEquals("", p.iconTint)
        assertTrue(p.labels)
        assertEquals(listOf("music"), p.widgets)
        // Brightness never reads below the floor; volume and brightness clamp to 0..100.
        assertEquals(OsPrefs.MIN_BRIGHTNESS, OsPrefs.read { mapOf(OsPrefs.K_BRIGHTNESS to "0")[it] }.brightness)
        assertEquals(100, OsPrefs.read { mapOf(OsPrefs.K_VOLUME to "250")[it] }.volume)
        // Nothing stored at all = all defaults (dock never set, widgets = the default set).
        assertEquals(OsPrefs(), OsPrefs.read { null })
        assertNull(OsPrefs().dock)
    }

    @Test fun migrationCarriesTheOldBoardLookOnce() {
        val old = mapOf("home_bg" to "image", "home_accent" to "teal", "theme_mode" to "light", "home_onboarded" to "true")
        val w = OsPrefs.migrate { old[it] }
        assertEquals("photo", w[OsPrefs.K_WALL])
        assertEquals("#66D9E8", w[OsPrefs.K_ACCENT])
        assertEquals("light", w[OsPrefs.K_THEME])
        assertEquals("1", w[OsPrefs.K_VERSION])
        // Never writes an old key.
        assertTrue(w.keys.all { it.startsWith("bos_") })
        // Applied → read back.
        val p = OsPrefs.read { (old + w)[it] }
        assertEquals("photo", p.wallpaper); assertEquals("#66D9E8", p.accent); assertEquals("light", p.theme)
        assertFalse(p.classic)
        // Second run: nothing to do.
        assertTrue(OsPrefs.migrate { (old + w)[it] }.isEmpty())
    }

    @Test fun migrationOfAFreshInstallOnlyStampsTheVersion() {
        assertEquals(mapOf(OsPrefs.K_VERSION to "1"), OsPrefs.migrate { null })
        // A glass / sky board and the system theme keep BlazeOS's own defaults.
        val m = OsPrefs.migrate { mapOf("home_bg" to "glass", "theme_mode" to "system", "home_accent" to "")[it] }
        assertEquals(mapOf(OsPrefs.K_VERSION to "1"), m)
        // A user who already picked a BlazeOS wallpaper keeps it.
        val keep = OsPrefs.migrate { mapOf("home_bg" to "image", OsPrefs.K_WALL to "preset:ocean")[it] }
        assertFalse(OsPrefs.K_WALL in keep)
    }

    @Test fun wallpaperValues() {
        assertEquals("photo", OsWallpaper.norm("photo"))
        assertEquals("preset:ember", OsWallpaper.norm("preset:ember"))
        assertEquals("solid:#E9E4DC", OsWallpaper.norm("solid:e9e4dc"))
        assertEquals(OsWallpaper.DEFAULT, OsWallpaper.norm("solid:nope"))
        assertEquals("ocean", OsWallpaper.preset("preset:ocean"))
        assertEquals("", OsWallpaper.preset("photo"))
    }
}
