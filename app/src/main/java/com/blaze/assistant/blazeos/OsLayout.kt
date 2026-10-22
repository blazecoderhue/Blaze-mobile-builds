package com.blaze.assistant.blazeos

/*
 * v0.33.2 BlazeOS: the pure layout rules. Grid sizes, which icons sit on the BlazeOS home screen and in what order,
 * packing widgets and icons into pages, the dock, App Library search, icon shape / tint maths and the BlazeOS prefs
 * with their one-time migration. Plain Kotlin (no Android imports), unit tested on the JVM in OsLayoutTest.
 * BlazeOS is Blaze's own virtual phone: every icon is a BlazeOS app (a built-in or one installed from its App Store),
 * never an app of the host phone.
 */

/**
 * One icon: a built-in BlazeOS app ("blaze:<key>") or one installed from the BlazeOS App Store ("pkg:<manifest id>").
 * [kind] is the entry type: builtin, web or linux.
 */
data class OsApp(val id: String, val label: String, val kind: String = OsManifest.BUILTIN, val removable: Boolean = false) {
    val builtin: Boolean get() = id.startsWith(OsApps.BUILTIN)
    /** The built-in's key ("music", "settings" …), or the installed app's manifest id. */
    val key: String get() = if (builtin) id.removePrefix(OsApps.BUILTIN) else id.removePrefix(OsApps.PKG)
}

object OsApps {
    const val BUILTIN = "blaze:"
    const val PKG = "pkg:"

    fun pkgId(manifestId: String) = "$PKG$manifestId"

    /** The built-in apps, in their first-run order: (key, label). */
    val builtins = listOf(
        "blaze" to "Blaze", "camera" to "Camera", "photos" to "Photos", "music" to "Music", "video" to "Video",
        "browser" to "Browser", "calendar" to "Calendar", "clock" to "Clock", "weather" to "Weather", "files" to "Files",
        "terminal" to "Terminal", "linux" to "Desktop", "store" to "App Store", "settings" to "Settings",
    )
    val builtinKeys: Set<String> = builtins.map { it.first }.toHashSet()

    fun builtinApps(): List<OsApp> = builtins.map { (k, l) -> OsApp(BUILTIN + k, l) }

    /** Built-ins first (their own order), then installed apps A to Z; one entry per id. */
    fun merge(installed: List<OsApp>): List<OsApp> {
        val seen = HashSet<String>()
        return (builtinApps() + sorted(installed)).filter { seen.add(it.id) }
    }

    /** A to Z by label (case-insensitive), ties by id, so the order never jumps between reloads. */
    fun sorted(apps: List<OsApp>): List<OsApp> = apps.sortedWith(compareBy<OsApp>({ it.label.trim().lowercase() }, { it.id }))

    /** App Library search: label (or a word of it) starts with the query first, then label contains, then id contains. */
    fun search(apps: List<OsApp>, q: String): List<OsApp> {
        val f = q.trim().lowercase()
        if (f.isEmpty()) return sorted(apps)
        val s = sorted(apps)
        val starts = s.filter { it.label.lowercase().startsWith(f) || it.label.lowercase().split(' ').any { w -> w.startsWith(f) } }
        val has = s.filter { it !in starts && it.label.lowercase().contains(f) }
        val ids = s.filter { it !in starts && it !in has && it.key.lowercase().contains(f) }
        return starts + has + ids
    }

    /** App Library sections by first letter ("#" for anything that isn't a letter), A to Z. */
    fun groups(apps: List<OsApp>): List<Pair<String, List<OsApp>>> =
        sorted(apps).groupBy { a -> a.label.trim().firstOrNull()?.takeIf { it.isLetter() }?.uppercaseChar()?.toString() ?: "#" }
            .toList().sortedWith(compareBy({ it.first == "#" }, { it.first }))
}

/** The icon grid. */
data class OsGrid(val cols: Int, val rows: Int) {
    val slots: Int get() = cols * rows
    val id: String get() = "${cols}x$rows"

    companion object {
        val options = listOf(OsGrid(4, 5), OsGrid(4, 6), OsGrid(5, 6))
        val DEFAULT = OsGrid(4, 6)
        fun parse(s: String?): OsGrid = options.firstOrNull { it.id == s?.trim()?.lowercase() } ?: DEFAULT
    }
}

/** Home widgets. Half widgets are 2x2 cells (two share a row); full ones take the row. Every widget row is 2 icon rows tall. */
object OsWidgets {
    const val CALENDAR = "calendar"
    const val ASK = "ask"
    const val MUSIC = "music"
    const val BATTERY = "battery"
    val all = listOf(CALENDAR, ASK, MUSIC, BATTERY)
    val DEFAULT = listOf(CALENDAR, ASK, MUSIC, BATTERY)
    /** Names used by the first BlazeOS drafts, read as today's kinds. */
    private val aliases = mapOf("devices" to BATTERY)
    const val ROW_CELLS = 2

    fun half(kind: String) = kind == CALENDAR || kind == ASK
    fun label(kind: String) = when (kind) {
        CALENDAR -> "Calendar"; ASK -> "Ask Blaze"; MUSIC -> "Now playing"; BATTERY -> "Battery"
        else -> kind
    }
    fun describe(kind: String) = when (kind) {
        CALENDAR -> "Today's date and the next event in BlazeOS Calendar"
        ASK -> "Opens the Blaze assistant app"
        MUSIC -> "What's playing, with controls"
        BATTERY -> "Battery, BlazeOS disk and memory rings"
        else -> ""
    }

    /** Known kinds only, once each, in the given order. */
    fun norm(list: List<String>): List<String> = list.map { it.trim().lowercase() }.map { aliases[it] ?: it }.filter { it in all }.distinct()

    /** Rows of widgets: a half widget pairs with the half right after it; otherwise it sits alone. */
    fun rows(list: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var i = 0
        val l = norm(list)
        while (i < l.size) {
            val k = l[i]
            if (half(k) && i + 1 < l.size && half(l[i + 1])) { out += listOf(k, l[i + 1]); i += 2 }
            else { out += listOf(k); i++ }
        }
        return out
    }

    fun add(list: List<String>, kind: String): List<String> = if (kind !in all || kind in list) list else list + kind
    fun remove(list: List<String>, kind: String): List<String> = list - kind
}

/** One Home page: its widget rows at the top, then its icons (ids) left to right, top to bottom. */
data class OsPage(val widgetRows: List<List<String>>, val apps: List<String>) {
    val widgetCells: Int get() = widgetRows.size * OsWidgets.ROW_CELLS
}

object OsPages {
    /** A page with widgets keeps at least this many icon rows (as in the mockup: two widget rows, then icons). */
    const val MIN_APP_ROWS = 2

    /**
     * Packs widget rows then icons into pages. Widgets go first, as many rows per page as leave [MIN_APP_ROWS] for icons
     * (always at least one widget row on a page that starts with widgets); icons fill every free slot in order. Always at
     * least one page.
     */
    fun pack(widgetRows: List<List<String>>, apps: List<String>, grid: OsGrid): List<OsPage> {
        val pages = ArrayList<OsPage>()
        var w = 0; var a = 0
        do {
            val here = ArrayList<List<String>>()
            var used = 0
            while (w < widgetRows.size && used + OsWidgets.ROW_CELLS <= grid.rows &&
                (here.isEmpty() || used + OsWidgets.ROW_CELLS <= grid.rows - MIN_APP_ROWS)) {
                here += widgetRows[w]; w++; used += OsWidgets.ROW_CELLS
            }
            val slots = (grid.rows - used).coerceAtLeast(0) * grid.cols
            val end = minOf(apps.size, a + slots)
            pages += OsPage(here, apps.subList(a, end).toList())
            a = end
        } while (w < widgetRows.size || a < apps.size)
        return pages
    }

    /** The page index an icon is on (-1 when not on Home). */
    fun pageOf(pages: List<OsPage>, id: String): Int = pages.indexOfFirst { id in it.apps }

    /** The flat Home index of the icon at [slot] on page [page] (for drag-to-rearrange). */
    fun flatIndex(pages: List<OsPage>, page: Int, slot: Int): Int {
        var n = 0
        for (i in 0 until page.coerceIn(0, pages.size)) n += pages[i].apps.size
        val onPage = pages.getOrNull(page)?.apps?.size ?: 0
        return n + slot.coerceIn(0, (onPage - 1).coerceAtLeast(0))
    }

    /** Where a dragged icon lands: its slot moved by whole cells, clamped to the page. */
    fun dropSlot(slot: Int, dxCells: Int, dyCells: Int, cols: Int, count: Int): Int =
        (slot + dxCells + dyCells * cols).coerceIn(0, (count - 1).coerceAtLeast(0))
}

/** Which icons sit on Home and in what order. */
object OsHome {
    /**
     * The Home icons: the saved order minus uninstalled / removed / docked apps (once each), then every app that is new
     * (never placed, never removed) appended. On a first run (nothing saved) that is the built-ins, then installed apps A to Z.
     */
    fun order(saved: List<String>, all: List<OsApp>, removed: Set<String>, dock: List<String>): List<String> {
        val ids = all.map { it.id }
        val have = ids.toHashSet()
        val skip = removed + dock
        val kept = saved.filter { it in have && it !in skip }.distinct()
        val keptSet = kept.toHashSet()
        val fresh = ids.filter { it !in keptSet && it !in skip }
        return kept + fresh
    }

    /** Moves [id] to position [to] in [order] (clamped). */
    fun move(order: List<String>, id: String, to: Int): List<String> {
        val from = order.indexOf(id)
        if (from < 0) return order
        val m = order.toMutableList()
        m.removeAt(from)
        m.add(to.coerceIn(0, m.size), id)
        return m
    }

    /** Removes an icon from Home (it stays in the App Library). */
    fun remove(order: List<String>, removed: Set<String>, id: String): Pair<List<String>, Set<String>> = (order - id) to (removed + id)

    /** Puts an icon back on Home (from the App Library), at the end. */
    fun restore(order: List<String>, removed: Set<String>, id: String): Pair<List<String>, Set<String>> =
        (if (id in order) order else order + id) to (removed - id)
}

/** The dock: up to four apps, no duplicates, only installed ones. */
object OsDock {
    const val MAX = 4
    /** First run, as in design B: Blaze, Browser, Music, Camera. */
    val DEFAULT = listOf("blaze:blaze", "blaze:browser", "blaze:music", "blaze:camera")

    fun normalize(saved: List<String>, installed: Set<String>): List<String> = saved.filter { it in installed }.distinct().take(MAX)

    fun defaults(all: List<OsApp>): List<String> {
        val ids = all.map { it.id }.toHashSet()
        return DEFAULT.filter { it in ids }
    }

    fun canAdd(dock: List<String>, id: String) = id !in dock && dock.size < MAX

    /** In the dock → out; out and there's room → in; full → unchanged. */
    fun toggle(dock: List<String>, id: String): List<String> = when {
        id in dock -> dock - id
        dock.size < MAX -> dock + id
        else -> dock
    }

    fun move(dock: List<String>, id: String, by: Int): List<String> {
        val i = dock.indexOf(id); if (i < 0) return dock
        val j = (i + by).coerceIn(0, dock.size - 1); if (i == j) return dock
        val m = dock.toMutableList(); m.removeAt(i); m.add(j, id); return m
    }
}

/** Icon shapes, tints and sizes. */
object OsIcons {
    const val SQUIRCLE = "squircle"
    const val CIRCLE = "circle"
    const val ROUNDED = "rounded"
    val shapes = listOf(SQUIRCLE, CIRCLE, ROUNDED)
    fun normShape(s: String?): String = s?.trim()?.lowercase()?.takeIf { it in shapes } ?: SQUIRCLE

    /** Largest icon bitmap kept (px): plenty for a 60 dp icon on an xhdpi low-end phone, small enough for its RAM. */
    const val MAX_PX = 144

    /**
     * The decode sample (a power of two) that shrinks a [w] x [h] picture as far as possible while its shorter side
     * stays at or above [maxPx], so the final exact scale-down starts from the least pixels (icons, wallpaper, photos).
     */
    fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
        var s = 1
        if (maxPx <= 0) return 1
        while (minOf(w, h) / (s * 2) >= maxPx) s *= 2
        return s
    }

    /** Bitmap size for an icon of [dp] at [density], capped at [MAX_PX] (never below 24). */
    fun sizePx(dp: Float, density: Float, cap: Int = MAX_PX): Int = (dp * density).toInt().coerceIn(24, cap)

    /**
     * Outline of a superellipse |x|^n + |y|^n = 1 (n = 5 is close to iOS's squircle) as [points] (x, y) pairs in a unit
     * box (0..1). Starts at the right middle and goes round once.
     */
    fun squircle(points: Int = 72, n: Double = 5.0): List<Pair<Float, Float>> = (0 until points).map { i ->
        val t = 2.0 * Math.PI * i / points
        val c = Math.cos(t); val s = Math.sin(t)
        val x = Math.signum(c) * Math.pow(Math.abs(c), 2.0 / n)
        val y = Math.signum(s) * Math.pow(Math.abs(s), 2.0 / n)
        ((x * 0.5 + 0.5).toFloat()) to ((y * 0.5 + 0.5).toFloat())
    }

    /**
     * A 4x5 colour matrix (row-major, 20 floats, the layout Android and Compose use): the icon in greys by luminance,
     * multiplied by the tint (r, g, b in 0..1), blended with the original by [strength] (0 = untouched, 1 = fully
     * tinted). Alpha untouched.
     */
    fun tintMatrix(r: Float, g: Float, b: Float, strength: Float = 0.85f): FloatArray {
        val s = strength.coerceIn(0f, 1f)
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        // Lift the tint a little so dark icons don't go black.
        val k = 1.15f
        fun row(t: Float, self: Int): FloatArray {
            val out = floatArrayOf(s * lr * t * k, s * lg * t * k, s * lb * t * k, 0f, 0f)
            out[self] += 1f - s
            return out
        }
        return row(r, 0) + row(g, 1) + row(b, 2) + floatArrayOf(0f, 0f, 0f, 1f, 0f)
    }

    /** "#RRGGBB" / "RRGGBB" → 0xFFRRGGBB, or null. */
    fun parseColor(s: String?): Long? {
        val t = s?.trim()?.removePrefix("#") ?: return null
        if (t.length != 6 || t.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        return 0xFF000000L or t.toLong(16)
    }

    fun hex(argb: Long): String = "#" + (argb and 0xFFFFFFL).toString(16).padStart(6, '0').uppercase()
}

/** Wallpaper values: "preset:<id>", "photo" or "solid:#RRGGBB". */
object OsWallpaper {
    val presets = listOf("dusk" to "Blaze dusk", "ocean" to "Ocean", "ember" to "Ember", "graphite" to "Graphite")
    const val DEFAULT = "preset:dusk"
    val solids = listOf("#14161C", "#1F2A44", "#2B1F3A", "#203A2E", "#3A2418", "#E9E4DC")

    fun norm(v: String?): String {
        val t = v?.trim().orEmpty()
        return when {
            t == "photo" -> t
            t.startsWith("preset:") && presets.any { it.first == t.removePrefix("preset:") } -> t
            t.startsWith("solid:") && OsIcons.parseColor(t.removePrefix("solid:")) != null -> "solid:" + OsIcons.hex(OsIcons.parseColor(t.removePrefix("solid:"))!!)
            else -> DEFAULT
        }
    }
    fun preset(v: String): String = if (v.startsWith("preset:")) v.removePrefix("preset:") else ""
}

/**
 * Everything the BlazeOS Settings app changes. Stored as new string prefs `bos_*` only (nothing old is changed or
 * renamed), so older builds simply ignore them and the Classic board keeps working.
 */
data class OsPrefs(
    val classic: Boolean = false,
    val wallpaper: String = OsWallpaper.DEFAULT,
    val iconShape: String = OsIcons.SQUIRCLE,
    /** "" = the apps' own colours, else "#RRGGBB". */
    val iconTint: String = "",
    val grid: OsGrid = OsGrid.DEFAULT,
    val labels: Boolean = true,
    val dockOn: Boolean = true,
    /** null = never set (use [OsDock.defaults]); empty = the user emptied it. */
    val dock: List<String>? = null,
    /** low | medium | high. */
    val glass: String = "medium",
    /** "" = Blaze's own warm accent, else "#RRGGBB". */
    val accent: String = "",
    /** dark | light. */
    val theme: String = "dark",
    /** s | m | l. */
    val font: String = "m",
    val order: List<String> = emptyList(),
    val removed: Set<String> = emptySet(),
    val widgets: List<String> = OsWidgets.DEFAULT,
    /** BlazeOS's own volume and brightness, 0..100 (never the host phone's). */
    val volume: Int = 60,
    val brightness: Int = 100,
    /** The app open when BlazeOS last paused ("" = the home screen), restored on resume. */
    val lastApp: String = "",
) {
    val fontScale: Float get() = when (font) { "s" -> 0.9f; "l" -> 1.15f; else -> 1f }
    val dark: Boolean get() = theme != "light"

    companion object {
        const val VERSION = 1
        const val K_VERSION = "bos_v"
        const val K_CLASSIC = "bos_classic"
        const val K_WALL = "bos_wallpaper"
        const val K_SHAPE = "bos_icon_shape"
        const val K_TINT = "bos_icon_tint"
        const val K_GRID = "bos_grid"
        const val K_LABELS = "bos_labels"
        const val K_DOCK_ON = "bos_dock_on"
        const val K_DOCK = "bos_dock"
        const val K_GLASS = "bos_glass"
        const val K_ACCENT = "bos_accent"
        const val K_THEME = "bos_theme"
        const val K_FONT = "bos_font"
        const val K_ORDER = "bos_order"
        const val K_REMOVED = "bos_removed"
        const val K_WIDGETS = "bos_widgets"
        const val K_VOLUME = "bos_volume"
        const val K_BRIGHTNESS = "bos_brightness"
        const val K_LAST_APP = "bos_last_app"
        /** Written for an emptied dock (an empty string reads as "never set"). */
        const val EMPTY = "-"

        val glassLevels = listOf("low", "medium", "high")
        val fonts = listOf("s", "m", "l")
        val accents = listOf("" to "Blaze", "#FF9A3C" to "Orange", "#4DA3FF" to "Blue", "#69DB7C" to "Green", "#B197FC" to "Purple", "#F783AC" to "Pink", "#66D9E8" to "Teal")
        val tints = listOf("" to "Off", "#4DA3FF" to "Blue", "#FF9A3C" to "Orange", "#69DB7C" to "Green", "#B197FC" to "Purple", "#E6E6EA" to "Mono")

        private fun list(s: String?): List<String> = s.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() && it != EMPTY }
        private fun bool(s: String?, def: Boolean): Boolean = when (s?.trim()) { "1", "true" -> true; "0", "false" -> false; else -> def }
        /** BlazeOS never dims below this, so the screen can't go black by accident. */
        const val MIN_BRIGHTNESS = 15
        private fun pct(s: String?, def: Int): Int = s?.trim()?.toIntOrNull()?.coerceIn(0, 100) ?: def
        private fun color(s: String?): String = OsIcons.parseColor(s)?.let { OsIcons.hex(it) } ?: ""

        /** Reads (and normalises) the prefs; a missing or unknown value falls back to its default. */
        fun read(get: (String) -> String?): OsPrefs {
            val dockRaw = get(K_DOCK)
            val widgetsRaw = get(K_WIDGETS)
            return OsPrefs(
                classic = bool(get(K_CLASSIC), false),
                wallpaper = OsWallpaper.norm(get(K_WALL)),
                iconShape = OsIcons.normShape(get(K_SHAPE)),
                iconTint = color(get(K_TINT)),
                grid = OsGrid.parse(get(K_GRID)),
                labels = bool(get(K_LABELS), true),
                dockOn = bool(get(K_DOCK_ON), true),
                dock = if (dockRaw.isNullOrEmpty()) null else list(dockRaw).distinct().take(OsDock.MAX),
                glass = get(K_GLASS)?.trim()?.lowercase()?.takeIf { it in glassLevels } ?: "medium",
                accent = color(get(K_ACCENT)),
                theme = if (get(K_THEME)?.trim() == "light") "light" else "dark",
                font = get(K_FONT)?.trim()?.lowercase()?.takeIf { it in fonts } ?: "m",
                order = list(get(K_ORDER)).distinct(),
                removed = list(get(K_REMOVED)).toSet(),
                widgets = if (widgetsRaw.isNullOrEmpty()) OsWidgets.DEFAULT else OsWidgets.norm(list(widgetsRaw)),
                volume = pct(get(K_VOLUME), 60),
                brightness = pct(get(K_BRIGHTNESS), 100).coerceAtLeast(MIN_BRIGHTNESS),
                lastApp = get(K_LAST_APP)?.trim().orEmpty(),
            )
        }

        /** Every key with its stored text (the reverse of [read]). */
        fun write(p: OsPrefs): Map<String, String> = linkedMapOf(
            K_VERSION to VERSION.toString(),
            K_CLASSIC to if (p.classic) "1" else "0",
            K_WALL to OsWallpaper.norm(p.wallpaper),
            K_SHAPE to OsIcons.normShape(p.iconShape),
            K_TINT to color(p.iconTint),
            K_GRID to p.grid.id,
            K_LABELS to if (p.labels) "1" else "0",
            K_DOCK_ON to if (p.dockOn) "1" else "0",
            K_DOCK to (p.dock?.let { if (it.isEmpty()) EMPTY else it.joinToString("\n") } ?: ""),
            K_GLASS to p.glass,
            K_ACCENT to color(p.accent),
            K_THEME to p.theme,
            K_FONT to p.font,
            K_ORDER to p.order.joinToString("\n"),
            K_REMOVED to p.removed.joinToString("\n"),
            K_WIDGETS to (OsWidgets.norm(p.widgets).joinToString("\n").ifEmpty { EMPTY }),
            K_VOLUME to p.volume.coerceIn(0, 100).toString(),
            K_BRIGHTNESS to p.brightness.coerceIn(MIN_BRIGHTNESS, 100).toString(),
            K_LAST_APP to p.lastApp,
        )

        /** The old Home board's accent names (HomeLook.accents, dark values) as BlazeOS accents. */
        private val legacyAccents = mapOf("orange" to "#FF9A3C", "green" to "#69DB7C", "purple" to "#B197FC", "pink" to "#F783AC", "teal" to "#66D9E8")

        /**
         * One-time migration from before BlazeOS (no `bos_v` yet): the keys to write. A Home board photo background
         * becomes the photo wallpaper (BlazeOS copies the file onto its own disk at boot), the board accent carries over, and the app's light theme
         * gives BlazeOS's light look. Nothing old is touched. Empty when already migrated.
         */
        fun migrate(get: (String) -> String?): Map<String, String> {
            val v = get(K_VERSION)?.trim()?.toIntOrNull()
            if (v != null && v >= VERSION) return emptyMap()
            val out = LinkedHashMap<String, String>()
            if (get(K_WALL).isNullOrBlank() && get("home_bg")?.trim() == "image") out[K_WALL] = "photo"
            if (get(K_ACCENT).isNullOrBlank()) legacyAccents[get("home_accent")?.trim().orEmpty()]?.let { out[K_ACCENT] = it }
            if (get(K_THEME).isNullOrBlank() && get("theme_mode")?.trim() == "light") out[K_THEME] = "light"
            out[K_VERSION] = VERSION.toString()
            return out
        }
    }
}
