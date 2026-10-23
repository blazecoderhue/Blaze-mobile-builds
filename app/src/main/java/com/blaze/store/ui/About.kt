package com.blaze.store.ui

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blaze.store.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

const val ORION_SITE = "https://blazeinchue.pages.dev"
/** GPL-3.0 corresponding source for this exact build, published by Blaze Site next to the APK. */
fun sourceZipUrl(version: String = STORE_VERSION) = "https://blazeinchue.pages.dev/dl/store/$version/source.zip"
const val UPSTREAM_URL = "https://github.com/RookieEnough/Orion-Store"
const val FORK_CREDIT = "Blaze Store is a fork of Orion Store by RookieEnough (https://github.com/RookieEnough/Orion-Store), GPL-3.0"

// region about hero
/** Developer coin: flips to the source code link; a burst halo turns behind it. */
@Composable
private fun DevCoin(vm: MainViewModel, profile: DevProfile, flipped: Boolean, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme; val still = LocalS.current.disableAnimations
    val rot by animateFloatAsState(if (flipped) 180f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "flip")
    val spin by rememberInfiniteTransition(label = "halo").animateFloat(0f, if (still) 0f else 360f, infiniteRepeatable(tween(30_000, easing = LinearEasing)), label = "s")
    Box(Modifier.size(260.dp, 200.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(176.dp).graphicsLayer { rotationZ = spin }.clip(MaterialShapes.SoftBurst.toShape()).background(cs.primary.copy(.28f)))
        // Same flip as the pilot card: no mirrored back face, and the shadow fades out edge-on
        Box(Modifier.size(128.dp).graphicsLayer { rotationY = if (rot > 90f) rot - 180f else rot; cameraDistance = 12 * density
                shadowElevation = 16.dp.toPx() * kotlin.math.abs(kotlin.math.cos(Math.toRadians(rot.toDouble()))).toFloat(); shape = CircleShape }
            .springClick(MaterialShapes.Cookie12Sided.toShape(), onClick = onTap), contentAlignment = Alignment.Center) {
            if (rot <= 90f) Box(Modifier.fillMaxSize().background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                // The initial paints at once; the photo fades over it if it ever loads (proxy, then direct), so a slow or dead link never shows as a wait.
                Text(profile.name.take(1), fontSize = 60.sp, fontWeight = FontWeight.Black, color = cs.onPrimaryContainer)
                val img = profile.image.orEmpty()
                var stage by remember(img) { mutableIntStateOf(if (img.isEmpty() || img in deadImages) 2 else 0) }
                if (stage < 2) AsyncImage(if (stage == 0) optimizedImageUrl(img, 256, 256) else img, profile.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    onError = { if (++stage == 2) deadImages += img })
            } else Column(Modifier.fillMaxSize().background(cs.tertiaryContainer), Arrangement.Center, Alignment.CenterHorizontally) {
                Icon(Sym.Code, null, Modifier.size(40.dp), tint = cs.onTertiaryContainer)
                Text("Source\ncode", textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLargeEmphasized, color = cs.onTertiaryContainer)
            }
        }
    }
}


@Composable
fun AboutTab(vm: MainViewModel, padding: PaddingValues) {
    val s = LocalS.current; val d = LocalD.current; val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val clip = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme
    val profile = vm.config?.devProfile ?: Defaults.DEV_PROFILE; val socials = vm.config?.socials ?: Defaults.DEV_SOCIALS; val donation = vm.config?.donation ?: Defaults.DONATION
    var flipped by remember { mutableStateOf(false) }
    LaunchedEffect(flipped) { if (flipped) { delay(6000); flipped = false } }
    LaunchedEffect(Unit) { if (s.coinFlipHintCount < 3) { delay(1600); flipped = true; vm.set { it.incrementCoinFlipHint() } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item("header") { Header(vm) }
        item("hero") {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).reveal(0), horizontalAlignment = Alignment.CenterHorizontally) {
                DevCoin(vm, profile, flipped) { if (flipped) { vm.tracker.open(sourceZipUrl()); vm.haptic(H.MEDIUM) } else { flipped = true; vm.haptic(H.SELECTION) } }
                Text(profile.name, style = MaterialTheme.typography.displaySmallEmphasized)
                Text(if (flipped) "Tap again for the source code" else "Tap the coin to flip it", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                Text(profile.bio, Modifier.padding(horizontal = 36.dp, vertical = 10.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            }
        }
        item("connect") { Group("Connect", Modifier.reveal(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(Triple(Sym.Code, "GitLab", Gray) to socials.github, Triple(Sym.BrandX, "X", Color(0xFF64748B)) to socials.x, Triple(Sym.BrandDiscord, "Discord", Color(0xFF5865F2)) to socials.discord,
                    Triple(Sym.Public, "Website", Color(0xFF6366F1)) to ORION_SITE)
                    .forEachIndexed { i, (spec, url) ->
                        val (bg, fg) = tonal(spec.third)
                        val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val pressed by src.collectIsPressedAsState()
                        val morph = remember(i) { androidx.graphics.shapes.Morph(listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.Pentagon)[i], MaterialShapes.Circle) }
                        val p by animateFloatAsState(if (pressed) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "m")
                        Column(Modifier.springClick(RoundedCornerShape(28.dp), src = src) { vm.haptic(H.LIGHT); vm.tracker.open(url) }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(64.dp).clip(MorphShape(morph, p)).background(bg), contentAlignment = Alignment.Center) { Icon(spec.first, spec.second, Modifier.size(28.dp), tint = fg) }
                            Text(spec.second, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                        }
                    }
            }
        } }
        item("source") { Group("Open source", Modifier.reveal(3)) {
            val (bg, fg) = tonal(Fuchsia)
            Column(Modifier.fillMaxWidth().springClick(RoundedCornerShape(28.dp)) { vm.haptic(H.LIGHT); vm.tracker.open(sourceZipUrl()) }.background(bg).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(fg.copy(.14f)), contentAlignment = Alignment.Center) { Icon(Sym.Code, null, tint = fg) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Source code (GPL-3.0)", style = MaterialTheme.typography.titleLargeEmphasized, color = fg)
                        Text("Blaze Store v$STORE_VERSION source zip", style = MaterialTheme.typography.bodyMedium, color = fg.copy(.85f))
                    }
                    Icon(Sym.OpenInNew, null, tint = fg)
                }
                Text(FORK_CREDIT, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = fg.copy(.85f))
            }
            TextButton({ vm.tracker.open(UPSTREAM_URL) }, Modifier.fillMaxWidth()) { Text("Upstream: Orion Store by RookieEnough") }
            if (donation.kofiUrl != null || donation.upiId != null || donation.paypalUrl != null) Row(Modifier.fillMaxWidth().padding(top = 8.dp)
                .springClick(RoundedCornerShape(28.dp)) { vm.donateOpen = true; vm.haptic(H.SELECTION) }.background(cs.surfaceContainerHigh).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TonalIcon(Sym.Coffee, Caution); Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Buy me a coffee", style = MaterialTheme.typography.titleMedium)
                    Text("Ko-fi, UPI or PayPal", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Icon(Sym.ChevronRight, null)
            }
        } }
        item("help") { Group("Help", Modifier.reveal(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple(Sym.HelpOutline, "FAQs", "Secrets & safety") to { vm.open(Overlay.FAQ) }, Triple(Sym.NewReleases, "What's new", "Release notes") to { vm.open(Overlay.RELEASE_NOTES) })
                    .forEach { (spec, go) ->
                        Column(Modifier.weight(1f).springClick(RoundedCornerShape(28.dp)) { vm.haptic(H.LIGHT); go() }.background(cs.surfaceContainerHigh).padding(18.dp)) {
                            TonalIcon(spec.first, cs.primary)
                            Text(spec.second, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
                            Text(spec.third, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
            }
        } }
        item("footer") {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = Pill, color = cs.surfaceContainerHigh) { Text("v$STORE_VERSION", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge) }
                    Surface(shape = Pill, color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer) { Text("GPL-3.0", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLargeEmphasized) }
                    val c = if (s.useRemoteJson) Safe else Caution
                    Surface(shape = Pill, color = tonal(c).first, contentColor = tonal(c).second) {
                        Text("Source: ${if (s.useRemoteJson) "Remote" else "Local"}", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Text("Part of Blaze by Blazeinchue", Modifier.padding(top = 12.dp).clip(Pill).clickable { vm.profileClick() }.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
        }
    }
    if (vm.donateOpen) DonationSheet(vm, donation) { vm.donateOpen = false }
}
// endregion

@Composable
private fun Group(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(modifier.padding(horizontal = 16.dp).padding(top = 28.dp).widthIn(max = 560.dp).fillMaxWidth()) {
    Text(title, Modifier.padding(start = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLargeEmphasized)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Developer tools, shown at the bottom of Settings > Developer once dev mode is unlocked. */
@Composable
fun DevTools(vm: MainViewModel) {
    val s = LocalS.current; val d = LocalD.current; val clip = LocalClipboardManager.current; val ctx = LocalContext.current
    val scope = rememberCoroutineScope(); var nuke by remember { mutableStateOf(false) }
    val onNuke = { nuke = true }
    var editing by remember { mutableStateOf(false) }; var draft by remember { mutableStateOf("") }; var show by remember { mutableStateOf(false) }
    var rate by remember { mutableStateOf<Triple<Int, Int, Long>?>(null) }
    var pkg by remember { mutableStateOf("") }; var pkgResult by remember { mutableStateOf("") }; var showJson by remember { mutableStateOf(false) }
    LaunchedEffect(s.githubToken) {
        rate = runCatching {
            val core = json.parseToJsonElement(Net.text("https://api.github.com/rate_limit", if (s.githubToken.isNotEmpty()) mapOf("Authorization" to "token ${s.githubToken}") else emptyMap()))
                .jsonObject["resources"]!!.jsonObject["core"]!!.jsonObject
            Triple(core["limit"]!!.jsonPrimitive.int, core["remaining"]!!.jsonPrimitive.int, core["reset"]!!.jsonPrimitive.long)
        }.getOrNull()
    }
    fun save(t: String) { vm.set { it.copy(githubToken = t) }; editing = false; scope.launch { delay(500); vm.reload() } }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Developer tools", Modifier.padding(start = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMediumEmphasized)
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("GitHub Access", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("Unlock 5000 requests/hr (Authenticated)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ editing = !editing; draft = s.githubToken }) { Text(if (editing) "Cancel" else "Edit Token") }
                }
                if (editing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("ghp_...") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save(draft) }),
                        trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Sym.VisibilityOff else Sym.Visibility, if (show) "Hide token" else "Show token") } })
                    Button({ save(draft) }, shapes = ButtonDefaults.shapes()) { Text("Save") }
                } else Row(Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (s.githubToken.isNotEmpty()) Safe else MaterialTheme.colorScheme.error))
                    Text(if (s.githubToken.isEmpty()) "No Token Set (Rate Limited)" else if (show) s.githubToken else "••••••••••••${s.githubToken.takeLast(4)}",
                        Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 14.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.githubToken.isNotEmpty()) {
                        IconButton({ show = !show }) { Icon(if (show) Sym.VisibilityOff else Sym.Visibility, if (show) "Hide token" else "Reveal token") }
                        IconButton({ clip.setText(AnnotatedString(s.githubToken)) }) { Icon(Sym.ContentCopy, "Copy token") }
                        IconButton({ save("") }) { Icon(Sym.DeleteOutline, "Remove token") }
                    }
                }
            }
        }
        Text("UI Debugger (Alignment Check)", Modifier.padding(start = 8.dp, top = 6.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ vm.allApps().firstOrNull()?.let { vm.installToast(it, "test.apk") } }) { Text("Toast: Install") }
            OutlinedButton({ vm.error("This is a test error message for alignment checking.") }) { Text("Toast: Error") }
            OutlinedButton({ val t = System.currentTimeMillis(); vm.upd { it.copy(pendingCleanup = mapOf("test-1" to CleanupEntry("a", t), "test-2" to CleanupEntry("b", t), "test-3" to CleanupEntry("c", t))) } }) { Text("Deck: Clean") }
            OutlinedButton({ vm.set { it.copy(hasSeenModernUITutorial = false) }; vm.tour = false; vm.open(Overlay.TUTORIAL) }) { Text("Trigger: Try New UI") }
            Button({ vm.forcedTest = "checking" }, colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error)) { Text("TEST FORCED UPDATE") }
        }
        rate?.let { (limit, rem, reset) ->
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row { Text("GitHub API Quota", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text("$rem/$limit", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                    LinearProgressIndicator({ if (limit > 0) rem / limit.toFloat() else 0f }, Modifier.fillMaxWidth(), color = if (rem < limit * .2f) MaterialTheme.colorScheme.error else Safe)
                    Row { Text(if (s.githubToken.isNotEmpty()) "Authenticated" else "Public IP", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        Text("Resets: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(reset * 1000))}", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        Text("Package Detective", Modifier.padding(start = 8.dp, top = 6.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(pkg, { pkg = it }, Modifier.weight(1f), placeholder = { Text("com.example.app") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            FilledIconButton({
                if (pkg.isNotEmpty()) { pkgResult = "Checking..."; scope.launch {
                    pkgResult = runCatching { withContext(Dispatchers.IO) { vm.tracker.appInfo(pkg.trim()) }.let { "{\n  \"installed\": ${it.installed},\n  \"version\": ${it.version?.let { v -> "\"$v\"" }},\n  \"versionCode\": ${it.versionCode}\n}" } }.getOrElse { "Error: ${it.message}" }
                } }
            }, shapes = IconButtonDefaults.shapes()) { Icon(Sym.Search, "Check package") }
        }
        if (pkgResult.isNotEmpty()) Code(pkgResult)
        OutlinedButton({ showJson = !showJson }, Modifier.fillMaxWidth()) { Text("Raw Metadata Explorer", Modifier.weight(1f)); Icon(if (showJson) Sym.ExpandLess else Sym.ExpandMore, null) }
        if (showJson) {
            val raw = remember(d.apps) { if (d.apps.isEmpty()) "Cache Empty" else runCatching { Json(json) { prettyPrint = true }.encodeToString(ListSerializer(AppItem.serializer()), d.apps) }.getOrDefault("Error parsing cache") }
            Code(raw, Modifier.heightIn(max = 320.dp))
        }
        Row(Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Active Mirror", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(vm.mirrorSource.ifEmpty { "Unknown" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            FilledTonalButton({ vm.set { it.copy(useRemoteJson = !it.useRemoteJson) } }, shapes = ButtonDefaults.shapes()) { Text(if (s.useRemoteJson) "Remote Mode" else "Local Mode") }
        }
        OutlinedButton({ vm.set { it.withDevUnlocked(false) }; vm.settingsMenu = "none"; vm.haptic(H.WARNING) }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("CLOSE DEVELOPER OPTIONS", fontWeight = FontWeight.Black) }
        Button(onNuke, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)) {
            Icon(Sym.DeleteForever, null); Spacer(Modifier.width(8.dp)); Text("NUCLEAR RESET (Wipe & Restart)", fontWeight = FontWeight.Black)
        }
    }
    if (nuke) AlertDialog({ nuke = false }, icon = { Icon(Sym.DeleteForever, null) }, title = { Text("Nuclear reset?") },
        text = { Text("Clears every setting, cache and library record, then restarts Blaze Store. Downloaded APK files stay on disk.") },
        confirmButton = { Button({
            nuke = false; vm.set { Settings() }; vm.upd { DataState() }
            scope.launch { delay(700); ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }; exitProcess(0) }
        }, colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error)) { Text("Wipe & Restart") } },
        dismissButton = { TextButton({ nuke = false }) { Text("Cancel") } })
}

@Composable
private fun Code(text: String, modifier: Modifier = Modifier) = SelectionContainer {
    Text(text, modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).verticalScroll(rememberScrollState()).padding(12.dp),
        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Safe)
}

@Composable
fun DonationSheet(vm: MainViewModel, donation: DonationConfig, onDismiss: () -> Unit) {
    data class DRow(val key: String, val label: String, val helper: String, val value: String, val icon: ImageVector, val c: Color, val copy: String?, val open: String?)
    val rows = listOfNotNull(
        donation.kofiUrl?.let { DRow("kofi", "Ko-fi", "Support Blaze Store through Ko-fi", it, Sym.Coffee, Color(0xFF29ABE0), null, "Open Ko-fi") },
        donation.upiId?.let { DRow("upi", "UPI", "Copy the UPI ID to pay from your app", it, Sym.QrCode2, Color(0xFF22C55E), "Copy UPI", null) },
        donation.paypalUrl?.let { DRow("paypal", "PayPal", "Open PayPal or copy the payment link", it, Sym.Payments, Color(0xFF0070BA), "Copy PayPal", "Open PayPal") },
    )
    if (rows.isEmpty()) return
    val clip = LocalClipboardManager.current; val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }; var copied by remember { mutableStateOf<String?>(null) }
    BlazeSheet(onDismiss) {
        SheetTitle("Support Blaze Store", Sym.Favorite, "Choose a payment method to support development.")
        rows.forEach { r ->
            Card({ open = if (open == r.key) null else r.key }, Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(if (open == r.key) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                ListItem({ Text(r.label, fontWeight = FontWeight.Bold) }, supportingContent = { Text(r.helper) }, colors = ListItemDefaults.colors(Color.Transparent),
                    leadingContent = { Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(r.c.copy(.15f)), contentAlignment = Alignment.Center) { Icon(r.icon, null, tint = r.c) } },
                    trailingContent = { Icon(if (open == r.key) Sym.ExpandLess else Sym.ExpandMore, null) })
                AnimatedVisibility(open == r.key) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SelectionContainer { Text(r.value, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            r.open?.let { Button({ vm.haptic(H.SELECTION); vm.tracker.open(if (r.value.startsWith("http://") || r.value.startsWith("https://")) r.value else "https://${r.value}") }, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) {
                                Icon(Sym.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(it) } }
                            r.copy?.let { FilledTonalButton({ clip.setText(AnnotatedString(r.value)); copied = r.key; vm.haptic(H.SUCCESS); scope.launch { delay(2000); copied = null } }, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) {
                                Icon(if (copied == r.key) Sym.Check else Sym.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if (copied == r.key) "Copied" else it) } }
                        }
                    }
                }
            }
        }
    }
}

/** Dicebear serves SVG by default; coil has no SVG decoder here, so render the PNG twin. */
fun pngAvatar(url: String) = url.replace("/svg?", "/png?")
