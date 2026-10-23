package com.blaze.store.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.blaze.store.data.Tab
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One thing Blaze Store does. [go] jumps straight to it, when it has a home of its own. */
class Feature(val icon: ImageVector, val title: String, val body: String, val go: (MainViewModel.() -> Unit)? = null)

private class Chapter(val id: String, val title: String, val blurb: String, val icon: ImageVector, val color: Color, val shape: RoundedPolygon, val features: List<Feature>)

/** Every feature with its chapter, in reading order: Store AI answers "how do I..." from this and can open the right screen. */
val GUIDE: List<Pair<String, Feature>> by lazy { CHAPTERS.flatMap { c -> c.features.map { c.title to it } } }

private fun settings(page: String): MainViewModel.() -> Unit = { openSettings(page) }

/** Every feature in the app, chapter by chapter. Anything new that users can see or do gets a line here. */
private val CHAPTERS = listOf(
    Chapter("store", "The Store", "Three catalogs, two layouts and a lot of ways to find the right app.", Sym.Apps, Color(0xFF6366F1), MaterialShapes.Cookie9Sided, listOf(
        Feature(Sym.Android, "Apps, PC and TV", "Separate catalogs for Android apps, PC software and TV apps, each with its own tab, search, sort and filters.", { navTo(Tab.ANDROID) }),
        Feature(Sym.ViewQuilt, "Modern and Classic layouts", "Modern is a storefront with a hero, lanes and shelves. Classic is a clean grouped list. Switch any time in Settings, Interface.", settings("interface")),
        Feature(Sym.Star, "Featured hero", "Hand-picked apps glide past at the top of the modern home, wearing their own icon colors with screenshots fanned out."),
        Feature(Sym.AutoAwesome, "Recommended for you", "A swipeable carousel of daily picks, plus a numbered spotlight chart of curated apps."),
        Feature(Sym.NewReleases, "New and updated", "The latest additions and refreshed apps in their own lane, with a full list one tap away."),
        Feature(Sym.Category, "Category shelves", "Expressive category blobs and lanes like Media, Social, Privacy and Utilities, each with an app count."),
        Feature(Sym.Layers, "App bundles", "Curated folders of apps that belong together. Open one to see every app, add or remove apps and install them one by one."),
        Feature(Sym.LibraryAdd, "Your own bundles", "Build a bundle in three steps: pick apps, give it a name, color and icon, then keep it local or submit it for everyone.", { open(Overlay.CUSTOM_BUNDLE) }),
        Feature(Sym.AutoAwesome, "Store AI", "Tap the star and describe what you need, like a free music app. Use your own Gemini, ChatGPT or Claude key, or a model that runs on the phone. Drag the star anywhere.", { open(Overlay.AI) }),
        Feature(Sym.Search, "Search", "The search pill at the top finds apps by name, developer, category or description, and forgives accents and typos in spacing."),
        Feature(Sym.SwapVert, "Sort and filter", "Sort by newest, oldest, name or size, and narrow the list to a single category with the filter buttons."),
        Feature(Sym.Favorite, "Favorites", "Long-press any card, swipe a classic row, or tap the heart on an app page. The heart filter shows only your favorites."),
        Feature(Sym.Description, "About this app previews", "Classic rows show the first lines of each description with See more to read the rest in place. Turn it off in Settings, Interface.", settings("interface")),
        Feature(Sym.Sell, "Category icons", "Every category wears its own icon on classic rows, so a long list scans at a glance."),
        Feature(Sym.SystemUpdate, "Update deck", "When updates are waiting, a slim strip on the Apps home shows who is waiting with a one-tap Update all."),
        Feature(Sym.Campaign, "Announcements", "News from the Blaze Store team drops from a megaphone in the search pill. It pulses until you have read it."),
        Feature(Sym.AutoFixHigh, "Pull to refresh characters", "Pull down to reload the catalog with a pixel cat, dog, owl, ghost and friends, or an expressive shape. Pick yours in Blaze Xtra.", settings("installer")),
        Feature(Sym.KeyboardArrowUp, "Back to top", "A floating arrow appears once you scroll far down. One tap and you are back at the top."),
        Feature(Sym.AddCircleOutline, "Submit an app", "Suggest an app from GitHub, GitLab or Codeberg. Blaze Store detects its name, package and description, accepts Obtainium exports and opens a ready GitHub issue.", { open(Overlay.SUBMISSION) }),
    )),
    Chapter("page", "App Pages", "Everything about one app, and every way to get it.", Sym.Info, Color(0xFF0EA5E9), MaterialShapes.Sunny, listOf(
        Feature(Sym.Download, "One-tap install", "Download, install, update or open an app from its page. Progress wraps the icon while it downloads."),
        Feature(Sym.Image, "Screenshots", "Scroll through screenshots at their real shape and tap one to view it full screen."),
        Feature(Sym.History, "Versions and channels", "Pick Stable, Beta, Alpha or Nightly per app, or choose an exact older version from the version list."),
        Feature(Sym.Memory, "Right build for your phone", "Blaze Store picks the APK that matches your phone's processor, and lets you choose another architecture yourself."),
        Feature(Sym.Info, "App info", "Developer, size, package name, source and patches, folded away until you want them."),
        Feature(Sym.Policy, "VirusTotal scan", "Check a download, an installed APK or the release file against 70+ antivirus engines with your own free key."),
        Feature(Sym.Code, "Source and safety", "Jump to the source code, the official releases page or the developer's site in one tap."),
        Feature(Sym.NotificationsOff, "Ignore updates", "Hide an update for a week, until the next version, or for good. Ignored updates wait in My Apps with an Unignore button."),
        Feature(Sym.SaveAlt, "Export and redownload", "Save the APK to your Downloads folder, fetch it again, or delete a saved copy to free space."),
        Feature(Sym.Share, "Share links and badges", "Copy a deep link that opens the app straight in Blaze Store, a web link, or a Markdown or HTML badge for your README."),
        Feature(Sym.EditNote, "Request a change", "Spotted a wrong icon, dead link or old description? Send a correction for exactly that field."),
        Feature(Sym.Report, "Report an issue", "A download broken? Report it from the app page and the details are filled in for you."),
        Feature(Sym.Extension, "MicroG helper", "Apps that need MicroG say so, with a link to install it and a guide to learn more."),
        Feature(Sym.Link, "Deep links", "blazestore:// links and Android's App details in store both open the right page in Blaze Store."),
    )),
    Chapter("library", "My Apps and Downloads", "Your library, your queue and updates that look after themselves.", Sym.GridView, Color(0xFF22C55E), MaterialShapes.Clover4Leaf, listOf(
        Feature(Sym.Inventory2, "Library", "Every app you have downloaded or installed through Blaze Store, filtered by All, Installed, Downloaded and Updates, with its own search.", { navTo(Tab.MYAPPS) }),
        Feature(Sym.Downloading, "Download queue", "Active downloads, ready installs and pending updates in one place, with Update all when installs can run silently.", settings("queue")),
        Feature(Sym.Notifications, "Background downloads", "Downloads keep going when you leave Blaze Store, with live progress in your notifications and a tap to install when they finish."),
        Feature(Sym.Autorenew, "Auto-update", "Blaze Store checks for updates every few hours, even when closed and after a reboot, and downloads them for you.", settings("network")),
        Feature(Sym.SystemUpdate, "Install automatically", "Let Blaze Store install the updates it downloads. Anything that cannot finish is reported the next time you open the app.", settings("network")),
        Feature(Sym.Verified, "Stable releases only", "Skip Beta, Alpha and Nightly builds whenever a stable release exists.", settings("network")),
        Feature(Sym.Wifi, "Wi-Fi only", "Keep downloads off mobile data.", settings("network")),
        Feature(Sym.CleaningServices, "Janitor", "Leftover APKs are cleaned a week after install, or right away with Auto-cleanup on. A pill offers a one-tap clean when files pile up.", settings("storage")),
    )),
    Chapter("install", "Installing, Your Way", "From one-tap installs to fully silent updates.", Sym.InstallMobile, Color(0xFFF97316), MaterialShapes.Gem, listOf(
        Feature(Sym.Android, "Choose your installer", "Use the system installer, pick one every time, or always hand APKs to a specific installer app.", settings("installer")),
        Feature(Sym.Speed, "Skip the 24-hour wait", "Built-in wireless debugging installs apps instantly as the shell user, set up once with a few guided steps.", settings("installer")),
        Feature(Sym.Bolt, "Silent installs with Shizuku", "With Shizuku running, installs and updates go through without a prompt each time.", settings("installer")),
        Feature(Sym.Terminal, "Root mode", "Silent installs, updates and every Guardian tool through root, with no Shizuku or wireless debugging needed.", settings("installer")),
        Feature(Sym.Sync, "Adopt installed apps", "With root, Blaze Store takes over updates for catalog apps you got elsewhere. Their data stays exactly as it was.", settings("installer")),
        Feature(Sym.Lock, "Update lock", "On Android 14 and newer with root, other stores must ask you before updating apps Blaze Store installed.", settings("installer")),
        Feature(Sym.DeleteSweep, "Clear every app's cache", "With root, free space across all your apps in a single tap.", settings("installer")),
        Feature(Sym.Refresh, "Force rescan", "Reload the list of installed apps whenever something looks out of date.", settings("installer")),
    )),
    Chapter("xtra", "Guardian and Sentinel", "Security and control tools built right in.", Sym.Shield, Color(0xFFEF4444), MaterialShapes.Cookie6Sided, listOf(
        Feature(Sym.Shield, "Guardian: permissions", "Spot apps holding risky permissions like location, microphone, contacts and storage, and revoke them.", settings("installer")),
        Feature(Sym.Unarchive, "Guardian: extract APK", "Pull the APK out of any installed app and save it.", settings("installer")),
        Feature(Sym.DeleteForever, "Guardian: debloat", "Remove preinstalled system apps reversibly, guided by a community list that marks what is safe to cut.", settings("installer")),
        Feature(Sym.MonitorHeart, "Sentinel: rapid and deep scans", "Check installed apps against a threat signature database, and sweep your storage for infected files.", settings("installer")),
        Feature(Sym.Lan, "Sentinel: network sentry", "A live audit of your connection: VPN, DNS, proxies, captive portals and Wi-Fi encryption.", settings("installer")),
        Feature(Sym.DeveloperMode, "ADB watchdog", "See at a glance whether the developer bridge on your phone is open."),
    )),
    Chapter("look", "Look and Feel", "Make Blaze Store look and move the way you like.", Sym.Palette, Color(0xFFA855F7), MaterialShapes.Flower, listOf(
        Feature(Sym.LightMode, "Light, Dusk, Dark and OLED", "Four themes. The button in the search pill cycles them from anywhere with a circular reveal.", settings("visuals")),
        Feature(Sym.Palette, "Color palettes", "Fourteen palettes from Blaze Store to Cocoa, each tuned for every theme.", settings("visuals")),
        Feature(Sym.FormatColorFill, "Make your own palette", "Tap Make in the palette row, pick a primary color plus optional secondary and tertiary ones, choose Soft, Balanced or Vivid and preview it in every theme. Long-press your palette to edit it.", settings("visuals")),
        Feature(Sym.AutoAwesome, "Material You", "Take your colors from your wallpaper on Android 12 and newer.", settings("visuals")),
        Feature(Sym.FontDownload, "Fonts", "Eleven built-in fonts, or import your own TTF, OTF or WOFF2 file.", settings("visuals")),
        Feature(Sym.Compress, "Compact mode", "Smaller tiles, slimmer rows and an extra column, so more fits on every screen.", settings("visuals")),
        Feature(Sym.BlurOn, "Glass dock", "A floating, slightly translucent navigation dock that slides away while you scroll down.", settings("visuals")),
        Feature(Sym.OpenInFull, "Dock size", "Scale the dock between compact and large with a live preview. It starts compact.", settings("visuals")),
        Feature(Sym.TouchApp, "Haptic feedback", "Tactile ticks on taps, toggles and successes.", settings("visuals")),
        Feature(Sym.Speed, "Smooth motion", "Prefer the highest refresh rate your screen offers.", settings("visuals")),
        Feature(Sym.MotionPhotosOff, "Disable animations", "Every transition snaps instantly.", settings("visuals")),
        Feature(Sym.ViewAgenda, "Hide tabs", "Hide the Apps, PC, TV or My Apps tab. The Dev tab always stays.", settings("interface")),
        Feature(Sym.Tv, "Android TV (Beta)", "Blaze Store runs on Android TV: it opens on the TV catalog, installs TV apps right on the TV, and every card lights up as your remote moves. Tap the TV Beta pill to report a bug."),
        Feature(Sym.DesktopWindows, "Tablets and landscape", "Blaze Store rotates freely on tablets and foldables, with a side rail and a centered, readable column."),
    )),
    Chapter("settings", "Settings and Backup", "Settings arranged your way, and progress that never gets lost.", Sym.Settings, Color(0xFFEC4899), MaterialShapes.Pentagon, listOf(
        Feature(Sym.ToggleOn, "Quick toggles", "Pin the switches you flip most to the top of Settings, and drag them into your own order.", { openSettings() }),
        Feature(Sym.DragHandle, "Arrange the Settings hub", "Long-press a settings card and drag it to a new spot. Reset brings the original order back.", { openSettings() }),
        Feature(Sym.Backup, "Identity backup", "Save your profile, favorites and settings to a signed .osf file, and restore it on any phone.", settings("identity")),
        Feature(Sym.Cloud, "Automatic cloud backup", "Connect Google Drive, OneDrive, Proton Drive or any storage app, and Blaze Store keeps your backup fresh daily, weekly or monthly.", settings("identity")),
        Feature(Sym.CloudOff, "Serverless by design", "No Blaze Store account and no Blaze Store server. Your data stays on your phone and in the cloud you choose."),
    )),
    Chapter("play", "Play", "A mini game for when the store is resting.", Sym.SportsEsports, Color(0xFF14B8A6), MaterialShapes.SoftBurst, listOf(
        Feature(Sym.Egg, "Blaze Run", "A Dino runner that keeps you company while the store is under maintenance. Beat your own high score."),
    )),
    Chapter("dev", "The Dev Tab and Beyond", "Help, support and a few things hidden on purpose.", Sym.Code, Color(0xFF64748B), MaterialShapes.Pill, listOf(
        Feature(Sym.MenuBook, "Know Blaze Store", "This page. Every feature in one place, from the book button at the top of the Dev tab."),
        Feature(Sym.HelpOutline, "FAQs", "Answers on safety, ReVanced builds, failed installs, trackers and more.", { open(Overlay.FAQ) }),
        Feature(Sym.NewReleases, "What's new", "Release notes straight from Blaze Store's GitHub releases, with the full notes one tap away.", { open(Overlay.RELEASE_NOTES) }),
        Feature(Sym.Forum, "Connect", "GitHub, X, Discord and the Blaze Store website, right on the Dev tab.", { navTo(Tab.ABOUT) }),
        Feature(Sym.Coffee, "Support Blaze Store", "Buy the developer a coffee through Ko-fi, UPI or PayPal.", { navTo(Tab.ABOUT) }),
        Feature(Sym.Public, "Mirrors and offline", "The catalog loads from GitHub, GitLab, Codeberg or JSDelivr, whichever answers, and stays cached for offline use."),
        Feature(Sym.Construction, "Maintenance and store updates", "Blaze Store tells you when the store is being worked on, and checks, scans and installs its own updates."),
        Feature(Sym.Terminal, "Developer options", "Tap the Blaze Store logo seven times to unlock tools for testing catalogs, configs and mirrors."),
    )),
)

/** Blaze Store hides the status bar, so the plain insets read zero and the floating chips slid under the camera cutout. Its slot (and any cutout) is kept clear instead. */
private val GuideTop @Composable get() = WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout.only(WindowInsetsSides.Top))

/** Know Blaze Store: the whole app as an expressive, searchable field guide. */
@Composable
fun KnowBlazeScreen(vm: MainViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val cs = MaterialTheme.colorScheme; val list = rememberLazyListState(); val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = remember(q) {
        if (q.isEmpty()) CHAPTERS else CHAPTERS.map { c -> Chapter(c.id, c.title, c.blurb, c.icon, c.color, c.shape, c.features.filter { q in it.title.lowercase() || q in it.body.lowercase() }) }
            .filter { it.features.isNotEmpty() }
    }
    val total = remember { CHAPTERS.sumOf { it.features.size } }
    fun go(f: Feature) { vm.haptic(H.SELECTION); onClose(); scope.launch { delay(120); f.go?.invoke(vm) } }
    // Hero, search and chips come first; each chapter is a header plus its rows
    val starts = remember(shown) { shown.runningFold(3) { at, c -> at + c.features.size + 1 }.dropLast(1) }
    val current by remember(starts) { derivedStateOf { if (list.firstVisibleItemIndex < 3) -1 else starts.indexOfLast { it <= list.firstVisibleItemIndex + 1 } } }
    val chips = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Rows land just below the floating chip bar instead of under it
    val pin = GuideTop.getTop(density) + with(density) { 84.dp.roundToPx() }
    fun jump(ci: Int) {
        // Far chapters snap straight there: an animated fling across dozens of rows composes every one of them on the way
        val at = starts[ci]
        scope.launch { if (kotlin.math.abs(at - list.firstVisibleItemIndex) <= 8) list.animateScrollToItem(at, -pin) else list.scrollToItem(at, -pin) }
    }
    val floating by remember { derivedStateOf { list.firstVisibleItemIndex >= 3 } }
    LaunchedEffect(current) { if (current >= 0) { if (floating) chips.animateScrollToItem(current) else chips.requestScrollToItem(current) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().background(cs.surface), list, contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
        item("hero") { GuideHero(total) }
        item("search") { GuideSearch(query) { query = it } }
        item("chips") { ChapterChips(shown, -1, rememberLazyListState(), ::jump) }
        if (shown.isEmpty()) item("empty") { EmptyState(Sym.SearchOff, "Nothing matches “$query”", "Try a shorter word, like backup or theme.") }
        shown.forEach { c ->
            item("h-${c.id}", contentType = "header") { ChapterHeader(c, CHAPTERS.indexOfFirst { it.id == c.id }) }
            itemsIndexed(c.features, key = { _, f -> "${c.id}-${f.title}" }, contentType = { _, _ -> "feature" }) { i, f -> FeatureRow(c, f, i, c.features.size) { go(f) } }
        }
        item("end") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(painterResource(com.blaze.store.R.drawable.ic_splash_logo), null, Modifier.size(40.dp), tint = cs.onPrimaryContainer)
                }
                Text("That's all of Blaze Store", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleLargeEmphasized)
                Text("$total features in ${CHAPTERS.size} chapters, and more on the way.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                Surface(Modifier.padding(top = 14.dp), shape = Pill, color = cs.surfaceContainerHigh) {
                    Text("v$STORE_VERSION", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    // Past the top, chapters float in a rounded bar under the status bar, marking where you are
    androidx.compose.animation.AnimatedVisibility(floating, Modifier.align(Alignment.TopCenter).windowInsetsPadding(GuideTop).padding(horizontal = 12.dp, vertical = 8.dp),
        enter = androidx.compose.animation.slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { -it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut()) {
        Surface(shape = RoundedCornerShape(32.dp), color = cs.surfaceContainerHigh, shadowElevation = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ scope.launch { list.animateScrollToItem(0) } }, Modifier.padding(start = 4.dp)) { Icon(Sym.KeyboardArrowUp, "Back to top") }
                ChapterChips(shown, current, chips, ::jump, start = 0.dp)
            }
        }
    }
    }
}

@Composable
private fun ChapterChips(shown: List<Chapter>, current: Int, state: androidx.compose.foundation.lazy.LazyListState, onJump: (Int) -> Unit, start: Dp = 16.dp) =
    LazyRow(Modifier.fillMaxWidth(), state, contentPadding = PaddingValues(start = start, end = 16.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(shown, key = { _, c -> c.id }) { ci, c -> Choice(ci == current, c.title, c.icon) { onJump(ci) } }
    }

private val HERO_SHAPES = listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny, MaterialShapes.Clover4Leaf, MaterialShapes.Gem, MaterialShapes.Flower, MaterialShapes.SoftBurst)

@Composable
private fun GuideHero(total: Int) {
    val cs = MaterialTheme.colorScheme; val still = LocalS.current.disableAnimations
    var shape by remember { mutableIntStateOf(0) }
    LaunchedEffect(still) { if (!still) while (true) { delay(2600); shape++ } }
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(cs.primaryContainer, cs.surface)))) {
        Turning(MaterialShapes.Cookie12Sided, cs.primary.copy(.08f), 280.dp, Modifier.align(Alignment.TopEnd).offset(90.dp, (-40).dp), 50_000)
        Column(Modifier.windowInsetsPadding(GuideTop).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 12.dp)) {
            MorphBlob(HERO_SHAPES, shape, 104.dp, cs.primary, Modifier.padding(top = 12.dp)) {
                Icon(painterResource(com.blaze.store.R.drawable.ic_splash_logo), null, Modifier.size(72.dp), tint = cs.onPrimary)
            }
            Text("FIELD GUIDE", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.labelLargeEmphasized, color = cs.primary)
            Text("Know Blaze Store", style = MaterialTheme.typography.displayMediumEmphasized)
            Text("Every one of Blaze Store's $total features, from backups and compact mode to Guardian, Sentinel and the Dino run. Tap a feature with an arrow to go straight to it.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun GuideSearch(query: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(56.dp).clip(Pill).background(cs.surfaceContainerHigh).padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Sym.Search, null, tint = cs.onSurfaceVariant)
        BasicTextField(query, onChange, Modifier.weight(1f).padding(horizontal = 12.dp), singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
            decorationBox = { field -> Box { if (query.isEmpty()) Text("Search features", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant); field() } })
        if (query.isNotEmpty()) IconButton({ onChange("") }) { Icon(Sym.Close, "Clear search") }
    }
}

@Composable
private fun ChapterHeader(c: Chapter, index: Int) {
    val (bg, fg) = tonal(c.color)
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 10.dp).fillMaxWidth().background(bg, RoundedCornerShape(32.dp)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // Drawn shapes, not clipped layers or endless spins: cheap to bring on screen when jumping between chapters
        Box(Modifier.size(64.dp).background(fg.copy(.16f), c.shape.toShape()), contentAlignment = Alignment.Center) {
            Icon(c.icon, null, Modifier.size(30.dp), tint = fg)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("CHAPTER ${index + 1}", style = MaterialTheme.typography.labelMedium, color = fg.copy(.75f))
            Text(c.title, style = MaterialTheme.typography.headlineSmallEmphasized, color = fg)
            Text(c.blurb, style = MaterialTheme.typography.bodyMedium, color = fg.copy(.85f))
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.background(fg, Pill).padding(horizontal = 10.dp, vertical = 4.dp)) { Text("${c.features.size}", style = MaterialTheme.typography.labelLargeEmphasized, color = bg) }
    }
}

/** One feature as a plain segmented row: a much lighter build than the interactive list item, so long jumps stay smooth. */
@Composable
private fun FeatureRow(c: Chapter, f: Feature, i: Int, n: Int, onGo: () -> Unit) {
    val (bg, fg) = tonal(c.color); val cs = MaterialTheme.colorScheme
    val shape = colShape(i, n)
    Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 2.dp).fillMaxWidth().clip(shape).background(cs.surfaceContainer)
        .then(if (f.go != null) Modifier.clickable(onClick = onGo) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(bg, MaterialShapes.Cookie6Sided.toShape()), contentAlignment = Alignment.Center) { Icon(f.icon, null, Modifier.size(22.dp), tint = fg) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(f.title, style = MaterialTheme.typography.titleMedium)
            Text(f.body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        if (f.go != null) { Spacer(Modifier.width(12.dp)); Icon(Sym.ArrowForward, "Open ${f.title}", tint = fg) }
    }
}

/** A MaterialShape turning slowly in place; holds still when animations are off. */
@Composable
private fun Turning(shape: RoundedPolygon, color: Color, size: Dp, modifier: Modifier = Modifier, periodMs: Int = 20_000) {
    val still = LocalS.current.disableAnimations
    val r by rememberInfiniteTransition(label = "turn").animateFloat(0f, if (still) 0f else 360f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "r")
    Box(modifier.size(size).graphicsLayer { rotationZ = r }.clip(shape.toShape()).background(color))
}
