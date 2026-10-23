package com.blaze.store.ui

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.activity.compose.BackHandler
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import coil3.compose.AsyncImage
import com.blaze.store.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.min

@Composable
fun ErrorNote(msg: String) = AnimatedVisibility(msg.isNotEmpty()) {
    Surface(shape = RoundedCornerShape(16.dp), color = cs.errorContainer, contentColor = cs.onErrorContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Sym.ErrorOutline, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(msg, style = ty.bodyMedium)
        }
    }
}

/** Choice tile whose container morphs Circle → [to] when selected. */
@Composable
private fun MorphPick(selected: Boolean, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 56.dp, to: RoundedPolygon = MaterialShapes.Cookie9Sided, color: Color = cs.primaryContainer, content: @Composable BoxScope.() -> Unit) {
    val morph = remember(to) { Morph(MaterialShapes.Circle, to) }
    val p by animateFloatAsState(if (selected) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "pick")
    val bg by animateColorAsState(if (selected) color else cs.surfaceContainerHighest, MaterialTheme.motionScheme.fastEffectsSpec(), label = "pickc")
    Box(Modifier.size(size).clip(MorphShape(morph, p)).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center, content = content)
}

@Composable
private fun Section(step: Int, title: String, subtitle: String? = null, done: Boolean = false, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Numbered cookie per step; it fills and turns into a check once the step has what it needs.
            Box(Modifier.size(36.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(if (done) cs.primary else cs.primaryContainer), contentAlignment = Alignment.Center) {
                if (done) Icon(Sym.Check, "Done", Modifier.size(18.dp), tint = cs.onPrimary) else Text("$step", style = ty.titleSmallEmphasized, color = cs.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column { Text(title, style = ty.titleMediumEmphasized); subtitle?.let { Text(it, style = ty.bodySmall, color = cs.onSurfaceVariant) } }
        }
        content()
    }

@Composable
private fun fieldColors() = TextFieldDefaults.colors(focusedContainerColor = cs.surfaceContainerHighest, unfocusedContainerColor = cs.surfaceContainerHighest,
    disabledContainerColor = cs.surfaceContainerHigh, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent)

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, placeholder: String, modifier: Modifier = Modifier, lines: Int = 1, mono: Boolean = false,
                  enabled: Boolean = true, readOnly: Boolean = false, uri: Boolean = false, supporting: String? = null, trailing: (@Composable () -> Unit)? = null, onDone: (() -> Unit)? = null,
                  leading: (@Composable () -> Unit)? = null) =
    TextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(placeholder) }, singleLine = lines == 1, minLines = lines,
        enabled = enabled, readOnly = readOnly, shape = RoundedCornerShape(16.dp), colors = fieldColors(), leadingIcon = leading, trailingIcon = trailing, supportingText = supporting?.let { { Text(it) } },
        textStyle = if (mono) ty.bodySmall.copy(fontFamily = FontFamily.Monospace) else LocalTextStyle.current,
        keyboardOptions = KeyboardOptions(keyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }))

private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

// ---------------- Custom bundle creator ----------------

private val ICON_CHOICES = listOf("fa-layer-group" to "Layers", "fa-cubes" to "Cubes", "fa-rocket" to "Rocket", "fa-bolt" to "Bolt", "fa-star" to "Star", "fa-heart" to "Heart",
    "fa-gamepad" to "Gaming", "fa-music" to "Music", "fa-palette" to "Creative", "fa-shield-halved" to "Privacy", "fa-graduation-cap" to "Learn", "fa-briefcase" to "Work")
private val COLOR_CHOICES = listOf("linear-gradient(135deg, #6366f1, #ec4899)", "linear-gradient(135deg, #0ea5e9, #6366f1)", "linear-gradient(135deg, #10b981, #0ea5e9)",
    "linear-gradient(135deg, #f59e0b, #ef4444)", "linear-gradient(135deg, #8b5cf6, #ec4899)", "linear-gradient(135deg, #111827, #4f46e5)")

fun bundleIssueBody(title: String, description: String, icon: String, color: String, appIds: List<String>, apps: List<AppItem>, submitter: String?): String {
    val lines = appIds.joinToString("\n") { id ->
        apps.firstOrNull { it.id == id }?.let { a -> "- **${a.name}** (`${a.id}`)${a.packageName?.takeIf { it.isNotEmpty() }?.let { " · `$it`" } ?: ""}" } ?: "- $id"
    }
    return listOf("## Custom Bundle Submission", "", "**Title:** $title", "**Description:** $description", "**Icon:** `$icon`", "**Color:** `$color`",
        submitter?.takeIf { it.isNotEmpty() }?.let { "**Submitted by:** $it" } ?: "", "", "### Apps (${appIds.size})", lines, "", "---",
        "_Submitted via Blaze Store Custom Bundle Creator. Add the `approve` label to publish._").filter { it.isNotEmpty() }.joinToString("\n")
}

@Composable
fun CustomBundleSheet(vm: MainViewModel, onDismiss: () -> Unit) = BlazeSheet(onDismiss) {
    val s = LocalS.current; val close = LocalSheetClose.current; val scope = rememberCoroutineScope()
    val platform = vm.tab.targetPlatform(); val all = remember { vm.allApps() }
    val pool = remember(all) { all.filter { it.platform.trim().equals(platform, true) } }
    var step by rememberSaveable { mutableIntStateOf(1) }
    var title by rememberSaveable { mutableStateOf("") }; var desc by rememberSaveable { mutableStateOf("") }
    var icon by rememberSaveable { mutableStateOf(ICON_CHOICES[0].first) }; var color by rememberSaveable { mutableIntStateOf(0) }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    var saved by remember { mutableStateOf<String?>(null) }; var err by remember { mutableStateOf("") }
    val can = when (step) { 1 -> title.trim().length >= 3 && desc.trim().length >= 8; 2 -> picked.size in 2..24; else -> true }
    fun save(submitted: Boolean): String {
        val now = System.currentTimeMillis(); val id = "cb_${now.toString(36)}"
        vm.set { it.copy(customBundles = it.customBundles + CustomBundle(id, title.trim(), desc.trim(), icon, picked, now, if (submitted) now else null)) }
        return id
    }
    val stops = remember(color) { Regex("#[0-9a-fA-F]{6}").findAll(COLOR_CHOICES[color]).map { hex(it.value) }.toList().let { if (it.size == 1) it + it else it } }
    val pickedApps = remember(picked, pool) { val m = pool.associateBy { it.id }; picked.mapNotNull(m::get) }
    BundleSteps(step)
    AnimatedContent(step, Modifier.weight(1f, fill = false), label = "cb") { st ->
        when (st) {
            1 -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BundleCard(title, desc, icon, stops, pickedApps)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(title, { title = it.take(48) }, "Title", "e.g. Privacy Essentials", supporting = if (title.isNotEmpty() && title.trim().length < 3) "At least 3 characters" else null)
                    Field(desc, { desc = it.take(180) }, "Description", "A short pitch for this bundle", lines = 3, supporting = "${desc.length}/180")
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Icon", style = ty.titleMediumEmphasized)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ICON_CHOICES.forEach { (id, label) ->
                            MorphPick(icon == id, { icon = id; vm.haptic(H.SELECTION) }, to = MaterialShapes.Cookie6Sided) {
                                Icon(faIcon(id), label, tint = if (icon == id) cs.onPrimaryContainer else cs.onSurfaceVariant)
                            }
                        }
                    }
                    Text("Color", Modifier.padding(top = 4.dp), style = ty.titleMediumEmphasized)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        COLOR_CHOICES.forEachIndexed { i, g ->
                            val sw = Regex("#[0-9a-fA-F]{6}").findAll(g).map { hex(it.value) }.toList().let { if (it.size == 1) it + it else it }
                            val on = color == i
                            val k by animateFloatAsState(if (on) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "sw")
                            val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
                            Box(Modifier.size(52.dp).springClick(MorphShape(morph, k)) { color = i }.graphicsLayer { rotationZ = k * 20f }.background(Brush.linearGradient(sw)),
                                contentAlignment = Alignment.Center) {
                                if (on) Icon(Sym.Check, "Selected color", Modifier.graphicsLayer { rotationZ = -k * 20f }, tint = Color.White)
                            }
                        }
                    }
                }
            }
            2 -> Column(Modifier.fillMaxWidth()) {
                // Picked tray: what the bundle holds so far, tap one to drop it.
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (picked.size < 2) "Pick at least 2 apps" else "${picked.size} apps in your bundle", Modifier.weight(1f), style = ty.titleSmallEmphasized)
                    Text("${picked.size}/24", style = ty.labelLargeEmphasized, color = if (picked.size in 2..24) cs.primary else cs.onSurfaceVariant)
                }
                androidx.compose.animation.AnimatedVisibility(pickedApps.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pickedApps, key = { it.id }) { a ->
                            Box(Modifier.animateItem().springClick(RoundedCornerShape(16.dp)) { picked = picked - a.id }) {
                                AppIcon(a, 52.dp)
                                Box(Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(cs.surface).padding(2.dp).clip(CircleShape).background(cs.errorContainer),
                                    contentAlignment = Alignment.Center) { Icon(Sym.Close, "Remove ${a.name}", Modifier.size(12.dp), tint = cs.onErrorContainer) }
                            }
                        }
                    }
                }
                AppPickerList(pool, platform, selected = picked.toSet(), status = { v, f -> "Showing $v of $f · max 24" }) { a ->
                    picked = if (a.id in picked) picked - a.id else if (picked.size < 24) picked + a.id else picked; vm.haptic(H.SELECTION)
                }
            }
            else -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BundleCard(title, desc, icon, stops, pickedApps)
                Lead("Submitted!", "Your bundle was saved locally and a GitHub issue was opened. We'll review it for the public catalog.", center = true) {
                    Blob(Sym.TaskAlt, Safe, 72, MaterialShapes.Cookie9Sided)
                }
                Text("Your saved bundles show up in the bundle row on the home page.", style = ty.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ErrorNote(err)
        saved?.let { Tag(it, Safe, Sym.CheckCircle) }
        when (step) {
            1 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(close, modifier = Modifier.weight(1f).height(56.dp), shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button({ step = 2 }, modifier = Modifier.weight(1f).height(56.dp), enabled = can, shapes = ButtonDefaults.shapes()) {
                    Text("Continue"); Spacer(Modifier.width(6.dp)); Icon(Sym.ArrowForward, null, Modifier.size(18.dp))
                }
            }
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BackHandler { step = 1 }
                OutlinedButton({
                    err = ""; runCatching { save(false) }.onSuccess { saved = "Saved to your bundles"; vm.haptic(H.SUCCESS); scope.launch { delay(650); close() } }
                        .onFailure { err = it.message ?: "Could not save bundle." }
                }, modifier = Modifier.weight(1f).height(56.dp), enabled = can, shapes = ButtonDefaults.shapes()) { Text("Keep Local") }
                Button({
                    err = ""
                    runCatching {
                        save(true)
                        val body = bundleIssueBody(title.trim(), desc.trim(), icon, COLOR_CHOICES[color], picked, all, s.userProfile?.name)
                        vm.tracker.open(issueUrl("[Store bundle] ${title.trim()}", body, "store-bundle"))
                    }.onSuccess { step = 3; vm.haptic(H.SUCCESS) }.onFailure { err = it.message ?: "Could not submit bundle. Try again." }
                }, modifier = Modifier.weight(1f).height(56.dp), enabled = can, shapes = ButtonDefaults.shapes()) { Text("Submit") }
            }
            else -> CtaButton("Done", close, icon = Sym.Check)
        }
    }
}

/** Three labelled step cookies joined by a track that fills as you go. */
@Composable
private fun BundleSteps(step: Int) = Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    listOf("Details", "Apps", "Done").forEachIndexed { i, label ->
        val n = i + 1; val on = n <= step
        val bg by animateColorAsState(if (on) cs.primary else cs.surfaceContainerHighest, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "step")
        Box(Modifier.size(30.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(bg), contentAlignment = Alignment.Center) {
            if (n < step) Icon(Sym.Check, "Done", Modifier.size(16.dp), tint = cs.onPrimary)
            else Text("$n", style = ty.labelLargeEmphasized, color = if (on) cs.onPrimary else cs.onSurfaceVariant)
        }
        Text(label, Modifier.padding(start = 6.dp), style = if (n == step) ty.labelLargeEmphasized else ty.labelLarge, color = if (on) cs.onSurface else cs.onSurfaceVariant)
        if (i < 2) {
            val track by animateColorAsState(if (n < step) cs.primary else cs.surfaceContainerHighest, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "track")
            Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(4.dp).clip(Pill).background(track))
        }
    }
}

private val TILT = listOf(-8f, 6f, -3f, 9f)

/** Live preview of the bundle as the store will show it: its colour, icon, words and the first apps fanned out. */
@Composable
private fun BundleCard(title: String, desc: String, icon: String, stops: List<Color>, apps: List<AppItem>) =
    Box(Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(stops))) {
        Box(Modifier.align(Alignment.TopEnd).offset(48.dp, (-40).dp).size(170.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(Color.White.copy(.12f)))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(if (apps.isEmpty()) 1f else .58f).padding(20.dp)) {
            Box(Modifier.size(44.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(Color.White.copy(.22f)), contentAlignment = Alignment.Center) {
                Icon(faIcon(icon), null, tint = Color.White)
            }
            Text(title.trim().ifEmpty { "Your bundle" }, Modifier.padding(top = 12.dp), style = ty.titleLargeEmphasized, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(desc.trim().ifEmpty { "What ties these apps together" }, style = ty.bodySmall, color = Color.White.copy(.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)) {
            apps.take(4).forEachIndexed { i, a ->
                AppIcon(a, 52.dp, Modifier.offset(x = (-(i % 2) * 44).dp, y = ((i / 2) * 44 - 22).dp).graphicsLayer { rotationZ = TILT[i]; shadowElevation = 8.dp.toPx(); shape = RoundedCornerShape(14.dp) })
            }
        }
    }

// ---------------- App submission ----------------

private val CATEGORIES = listOf("Utility", "Privacy", "Media", "Development", "Social", "Educational")
private const val KEYWORD_HELP = "1. Tap 'Check Releases Page' below.\n2. Go to the 'Assets' section of the latest version.\n3. Look at the APK filename.\n4. Copy a unique word from it.\n\nEXAMPLE:\nFile: \"app-release-arm64.apk\"\nKeyword: \"app-release\"\n\nIf the file is just \"app.apk\", simply use \"apk\"."
private val pretty = Json { prettyPrint = true; @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class) prettyPrintIndent = "  " }

private data class SubDraft(
    val mode: String, val json: String = "", val shots: List<String> = emptyList(), val oIcon: String = "", val oKeyword: String = "", val oDesc: String = "",
    val manualKeyword: Boolean = false, val name: String = "", val description: String = "", val icon: String = "", val repoUrl: String = "", val keyword: String = "apk",
    val packageName: String = "", val category: String = "Utility", val author: String = "", val downloadUrl: String = "",
    val autoName: String = "", val autoDesc: String = "", val autoPkg: String = "", val autoStatus: String = "idle", val autoMsg: String = "",
)

// Note: draft lives for the process like the web's sessionStorage; persist to disk if users lose long drafts to process death.
private var draft: SubDraft? = null

private data class Detected(val name: String?, val description: String?, val packageName: String?, val author: String?)

private suspend fun detectRepo(src: RepoSource, token: String): Detected {
    val h = if (src.provider == "github" && token.isNotEmpty()) mapOf("Authorization" to "Bearer $token") else emptyMap()
    val api = when (src.provider) {
        "github" -> "https://api.github.com/repos/${src.owner}/${src.repo}"
        "gitlab" -> "https://${src.domain}/api/v4/projects/${enc(src.repoPath)}"
        else -> "https://codeberg.org/api/v1/repos/${src.owner}/${src.repo}"
    }
    val info = json.parseToJsonElement(Net.text(api, h)).jsonObject
    val desc = info["description"]?.jsonPrimitive?.contentOrNull.orEmpty(); val branch = info["default_branch"]?.jsonPrimitive?.contentOrNull ?: "main"
    val raw = when (src.provider) {
        "github" -> "https://raw.githubusercontent.com/${src.owner}/${src.repo}/$branch"
        "gitlab" -> "https://${src.domain}/${src.repoPath}/-/raw/$branch"
        else -> "https://codeberg.org/${src.owner}/${src.repo}/raw/branch/$branch"
    }
    val pkg = ANDROID_META_PATHS.firstNotNullOfOrNull { p -> runCatching { Net.text("$raw/$p", h) }.getOrNull()?.let(::extractPackageName) }
    return Detected(humanizeRepoName(src.repo), desc.ifEmpty { null }, pkg, src.owner.ifEmpty { null })
}

private fun autofillMessage(d: Detected) = when {
    d.name != null && d.packageName != null && d.description != null -> "App name, package name, and description detected automatically."
    d.name != null && d.packageName != null -> "App name and package name detected automatically."
    d.packageName != null -> "Package name detected. Add a custom description if needed."
    d.name != null -> "App name detected. Blaze Store still needs Android metadata."
    d.description != null -> "Description detected. Package name still needs confirmation."
    else -> "Repo found, but Blaze Store could not detect Android metadata."
}

@Composable
fun SubmissionSheet(vm: MainViewModel, onDismiss: () -> Unit) = BlazeSheet(onDismiss) {
    val s = LocalS.current; val close = LocalSheetClose.current; val scope = rememberCoroutineScope(); val clip = LocalClipboardManager.current; val t = rememberSheetToast()
    val platform = vm.tab.targetPlatform(); val android = platform == Platform.ANDROID
    var f by remember { mutableStateOf(draft ?: SubDraft("manual")) }
    SideEffect { draft = f }
    var err by remember { mutableStateOf("") }; var shot by remember { mutableStateOf("") }; var issue by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }; var badge by remember { mutableStateOf(false) }
    val obtainium = android && f.mode == "obtainium"
    val id = slugId(f.name)
    val cooldown = 180 - min(s.submissionCount * 15, 150)
    val source = remember(f.repoUrl, f.mode) { if (android && f.mode == "manual" && f.repoUrl.isNotBlank()) parseRepoSource(f.repoUrl) else null }
    var lastKey by remember { mutableStateOf("") }
    LaunchedEffect(source) {
        val src = source ?: run { lastKey = ""; f = f.copy(autoStatus = "idle", autoMsg = ""); return@LaunchedEffect }
        val key = "${src.provider}:${src.domain.orEmpty()}:${src.repoPath}"; if (key == lastKey) return@LaunchedEffect
        f = f.copy(autoStatus = "loading", autoMsg = "Detecting description and package name from the repo...")
        delay(650)
        runCatching { detectRepo(src, s.githubToken) }.onSuccess { d ->
            lastKey = key
            val p = f
            f = p.copy(
                author = if (d.author != null && p.author.isEmpty()) d.author else p.author,
                name = if (d.name != null && (p.name.isEmpty() || p.name == p.autoName)) d.name else p.name,
                description = if (d.description != null && (p.description.isEmpty() || p.description == p.autoDesc)) d.description else p.description,
                packageName = if (d.packageName != null && (p.packageName.isEmpty() || p.packageName == p.autoPkg)) d.packageName else p.packageName,
                autoName = d.name ?: p.autoName, autoDesc = d.description ?: p.autoDesc, autoPkg = d.packageName ?: p.autoPkg,
                autoStatus = if (d.packageName != null || d.description != null) "success" else "error", autoMsg = autofillMessage(d))
        }.onFailure { f = f.copy(autoStatus = "error", autoMsg = "Could not auto-detect repo metadata right now.") }
    }
    val releases = f.repoUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { u ->
        when { "github.com" in u -> "$u/releases"; "gitlab" in u -> "$u/-/releases"; "codeberg.org" in u -> "$u/releases"; else -> null }
    }
    fun fail(m: String) { err = m; vm.haptic(H.ERROR) }
    fun addShot() {
        val u = shot.trim(); if (u.isEmpty()) return
        if (!u.startsWith("http")) { fail("Screenshot must be a valid URL starting with http/https"); scope.launch { delay(3000); err = "" }; return }
        f = f.copy(shots = f.shots + sanitizeGitHubImageUrl(u)); shot = ""
    }
    fun switchMode(m: String) {
        if (m == f.mode) return
        err = ""; issue = null; draft = null
        f = f.copy(mode = m, shots = emptyList(), oDesc = "", oIcon = "", oKeyword = "", json = "", autoName = "")
    }
    fun submit() {
        err = ""
        if (f.shots.size < 3) return fail("At least 3 screenshots are required.")
        f.shots.forEachIndexed { i, u -> explainRejectedImageLink(u)?.let { return fail("Screenshot #${i + 1}: $it") } }
        val shots = JsonArray(f.shots.map(::JsonPrimitive))
        val app: JsonObject = if (obtainium) {
            if (f.json.isBlank()) return fail("Please paste JSON content.")
            if (f.oIcon.isBlank()) return fail("Icon URL is required.")
            explainRejectedImageLink(f.oIcon)?.let { return fail("Icon: $it") }
            val parsed = runCatching { json.parseToJsonElement(f.json).jsonObject }.getOrElse { return fail("Failed to parse data. Please check inputs.") }
            JsonObject(parsed + mapOf("icon" to JsonPrimitive(sanitizeGitHubImageUrl(f.oIcon)), "releaseKeyword" to JsonPrimitive(f.oKeyword.trim().ifEmpty { "apk" }),
                "description" to JsonPrimitive(f.oDesc.trim()), "screenshots" to shots, "platform" to JsonPrimitive(Platform.ANDROID)))
        } else {
            if (f.name.isBlank()) return fail("App name is required.")
            if (f.description.isBlank()) return fail("Description is required.")
            if (f.icon.isBlank()) return fail("Icon URL is required.")
            explainRejectedImageLink(f.icon)?.let { return fail("Icon: $it") }
            if (android) {
                if (f.repoUrl.isBlank()) return fail("Repo URL is required.")
                if (f.packageName.isBlank()) return fail("Package name is required.")
            } else if (f.downloadUrl.isBlank()) return fail("Direct download link is required.")
            val url = f.repoUrl; val parts = runCatching { URI(url.trim()).path.split('/').filter { it.isNotEmpty() } }.getOrDefault(emptyList())
            val host = runCatching { URI(url.trim()).host.orEmpty() }.getOrDefault("")
            val gh = if (android && "github.com" in url) url.substringAfter("github.com/").split('/').takeIf { it.size >= 2 }?.let { "${it[0]}/${it[1].replace(".git", "").trimEnd('/')}" } else null
            val gl = android && gh == null && "gitlab" in url && parts.size >= 2; val cb = android && gh == null && !gl && "codeberg.org" in url && parts.size >= 2
            val owner = gh?.substringBefore('/') ?: parts.firstOrNull().takeIf { gl || cb }
            buildJsonObject {
                put("name", f.name); put("id", id); put("description", f.description); put("icon", sanitizeGitHubImageUrl(f.icon)); put("repoUrl", f.repoUrl)
                put("githubRepo", gh.orEmpty()); put("gitlabRepo", if (gl) parts.joinToString("/") else ""); put("gitlabDomain", if (gl) host else "")
                put("codebergRepo", if (cb) parts.joinToString("/") else "")
                put("releaseKeyword", if (android) (if (f.manualKeyword) f.keyword.trim().ifEmpty { "apk" } else "apk") else f.keyword)
                put("packageName", f.packageName); put("category", f.category); put("author", f.author.ifEmpty { owner.orEmpty() }); put("downloadUrl", f.downloadUrl)
                put("screenshots", shots); put("platform", platform)
            }
        }
        issue = submissionIssueUrl(pretty.encodeToString(JsonArray.serializer(), JsonArray(listOf(app))), 1, app["name"]?.jsonPrimitive?.contentOrNull, STORE_VERSION)
        vm.set { it.registerSubmission(System.currentTimeMillis()) }; vm.haptic(H.SUCCESS)
    }
    issue?.let { url ->
        AlertDialog({ issue = null }, icon = { Icon(Sym.Warning, null) }, title = { Text("Before You Continue") },
            text = { Text("Blaze Store already generated the correct JSON for your submission. Please do not manually edit the JSON block on the GitHub issue page, or the submission may break.") },
            confirmButton = { TextButton({ vm.tracker.open(url); draft = null; issue = null; close() }) { Text("Open GitHub Issue") } },
            dismissButton = { TextButton({ issue = null }) { Text("Go Back") } })
    }
    SheetToastHost(t)
    val sectionsDone = if (obtainium) listOf(f.json.isNotBlank(), f.oIcon.isNotBlank(), f.shots.size >= 3)
        else listOf(if (android) f.repoUrl.isNotBlank() else f.downloadUrl.isNotBlank(), f.name.isNotBlank() && f.author.isNotBlank(), f.description.isNotBlank() && f.icon.isNotBlank()) +
            (if (android) listOf(f.packageName.isNotBlank()) else emptyList()) + (f.shots.size >= 3)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SubmitHero(platform, cooldown, sectionsDone.count { it }, sectionsDone.size)
        if (android) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeCard("From repo", "GitHub, GitLab or Codeberg", Sym.Code, MaterialShapes.Clover4Leaf, f.mode == "manual", Modifier.weight(1f)) { switchMode("manual") }
            ModeCard("Obtainium", "Paste an export", Sym.FileDownload, MaterialShapes.Cookie9Sided, f.mode == "obtainium", Modifier.weight(1f)) { switchMode("obtainium") }
        }
        ErrorNote(err)
        val screenshots: @Composable (Int) -> Unit = { n ->
            Section(n, "Screenshots", "At least 3 direct image links, from the README, Play Store or F-Droid.", f.shots.size >= 3) {
                Field(shot, { shot = it }, "Screenshot URL", "https://image.url/screenshot.jpg", uri = true, onDone = ::addShot,
                    trailing = { IconButton(::addShot, enabled = shot.isNotBlank()) { Icon(Sym.AddPhotoAlternate, "Add screenshot") } })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val p by animateFloatAsState((f.shots.size / 3f).coerceAtMost(1f), MaterialTheme.motionScheme.defaultSpatialSpec(), label = "shots")
                    LinearWavyProgressIndicator({ p }, Modifier.weight(1f), color = if (f.shots.size >= 3) Safe else cs.primary)
                    Text("${f.shots.size}/3", Modifier.padding(start = 12.dp), style = ty.labelLargeEmphasized, color = if (f.shots.size >= 3) Safe else cs.onSurfaceVariant)
                }
                var viewing by remember { mutableStateOf<Int?>(null) }
                viewing?.let { Lightbox(f.shots, it) { viewing = null } }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    f.shots.forEachIndexed { i, u ->
                        Box(Modifier.size(84.dp, 150.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHighest).clickable(onClickLabel = "View screenshot ${i + 1}") { viewing = i }) {
                            AsyncImage(u, "Screenshot ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Text("#${i + 1}", Modifier.align(Alignment.BottomStart).padding(6.dp).clip(Pill).background(cs.scrim.copy(.6f)).padding(horizontal = 8.dp, vertical = 2.dp), style = ty.labelSmall, color = Color.White)
                            FilledTonalIconButton({ f = f.copy(shots = f.shots.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp)) {
                                Icon(Sym.Close, "Remove screenshot ${i + 1}", Modifier.size(16.dp))
                            }
                        }
                    }
                    repeat(maxOf(3 - f.shots.size, 1)) { k ->
                        val line = cs.outlineVariant
                        Box(Modifier.size(84.dp, 150.dp).drawBehind {
                            drawRoundRect(line, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
                        }, contentAlignment = Alignment.Center) {
                            if (k == 0) Icon(Sym.AddPhotoAlternate, null, tint = cs.onSurfaceVariant) else Text("${f.shots.size + k + 1}", style = ty.titleLarge, color = cs.onSurfaceVariant.copy(.5f))
                        }
                    }
                }
            }
        }
        if (obtainium) {
            Section(1, "Obtainium export", "Paste the JSON Obtainium exports for this app.", f.json.isNotBlank()) {
                Field(f.json, { f = f.copy(json = it) }, "Obtainium JSON", "{\"apps\": [{\"url\": \"https://gitlab.com/...\"}]}", lines = 6, mono = true)
            }
            Section(2, "How it appears", "The icon is required, the rest is optional.", f.oIcon.isNotBlank()) {
                Field(f.oIcon, { f = f.copy(oIcon = it) }, "Icon URL *", "https://...", uri = true, leading = iconPreview(f.oIcon))
                Field(f.oKeyword, { f = f.copy(oKeyword = it) }, "Release keyword", "e.g. app-release")
                Field(f.oDesc, { f = f.copy(oDesc = it) }, "Description", "What does this app do?", lines = 3)
            }
            screenshots(3)
        } else {
            Section(1, "Source", if (android) "Where the releases live. Blaze Store reads the rest from the repo." else "Where people download it.", sectionsDone[0]) {
                if (android) {
                    Field(f.repoUrl, { f = f.copy(repoUrl = it) }, "Repo URL *", "https://github.com/owner/repo", uri = true)
                    if (f.repoUrl.isNotBlank()) {
                        val (c, ic) = when (f.autoStatus) { "error" -> cs.error to Sym.Warning; "success" -> Safe to Sym.CheckCircle; else -> cs.onSurfaceVariant to Sym.AutoFixHigh }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.copy(.1f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (f.autoStatus == "loading") LoadingIndicator(Modifier.size(20.dp)) else Icon(ic, null, Modifier.size(18.dp), tint = c)
                            Spacer(Modifier.width(10.dp))
                            Text(f.autoMsg.ifEmpty { "Blaze Store will try to detect the package name and description from this repo." }, style = ty.bodySmall, color = c)
                        }
                    }
                } else Field(f.downloadUrl, { f = f.copy(downloadUrl = it) }, "Direct download link *", "https://example.com/download", uri = true)
            }
            Section(2, "Details", null, sectionsDone[1]) {
                Field(f.name, { f = f.copy(name = it) }, "Name *", "App name")
                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(open, { open = it }) {
                    TextField(f.category, {}, Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable), readOnly = true, label = { Text("Category *") },
                        shape = RoundedCornerShape(16.dp), colors = fieldColors(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) })
                    ExposedDropdownMenu(open, { open = false }, shape = RoundedCornerShape(20.dp)) {
                        CATEGORIES.forEach { c -> DropdownMenuItem({ Text(c) }, { f = f.copy(category = c); open = false; vm.haptic(H.SELECTION) }) }
                    }
                }
                Field(f.author, { f = f.copy(author = it) }, "Author *", "Developer or publisher")
                Field(id, {}, "App ID", "auto-generated", readOnly = true, mono = true)
            }
            Section(3, "Store listing", "What people see first.", sectionsDone[2]) {
                Field(f.description, { f = f.copy(description = it) }, "Description *", "A short summary users will read first.", lines = 3)
                Field(f.icon, { f = f.copy(icon = it) }, "Icon URL *", "https://...", uri = true, leading = iconPreview(f.icon))
            }
            if (android) Section(4, "Release", "How Blaze Store picks the right APK from each release.", sectionsDone[3]) {
                Field(f.packageName, { f = f.copy(packageName = it) }, "Package name *", "com.example.app", mono = true)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHighest).padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Release keyword", style = ty.titleSmall)
                        Text(if (f.manualKeyword) "Manual: matches your keyword" else "Auto: first APK in the release", style = ty.bodySmall, color = cs.onSurfaceVariant)
                    }
                    IconButton({ help = !help }) { Icon(Sym.Info, "Help: Release keyword") }
                    Switch(f.manualKeyword, { f = f.copy(manualKeyword = it); vm.haptic(H.SELECTION) })
                }
                AnimatedVisibility(help) {
                    Surface(shape = RoundedCornerShape(16.dp), color = cs.secondaryContainer) { Text(KEYWORD_HELP, Modifier.padding(14.dp), style = ty.bodySmall) }
                }
                AnimatedVisibility(f.manualKeyword) { Field(f.keyword, { f = f.copy(keyword = it) }, "Keyword", "apk") }
                releases?.let { r ->
                    FilledTonalButton({ vm.tracker.open(r) }, shapes = ButtonDefaults.shapes()) { Icon(Sym.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Check releases page") }
                }
            }
            screenshots(if (android) 5 else 4)
        }
        Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CtaButton("Generate GitHub issue", ::submit, icon = Sym.OpenInNew)
            Text("Opens GitHub Issues in your browser with everything filled in.", style = ty.bodySmall, color = cs.onSurfaceVariant)
        }
        val md = "[![Get it on Blaze Store](https://blazeinchue.pages.dev/store/badge.png)](https://blazeinchue.pages.dev/store/redirect.html?id=${enc(id)})"
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainer).clickable { badge = !badge; vm.haptic(H.SELECTION) }
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(MaterialShapes.Sunny.toShape()).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Sym.Verified, null, Modifier.size(18.dp), tint = cs.onTertiaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Badge for your README", style = ty.titleMediumEmphasized)
                    Text("Link people straight to this app in Blaze Store", style = ty.bodySmall, color = cs.onSurfaceVariant)
                }
                Icon(if (badge) Sym.ExpandLess else Sym.ExpandMore, null)
            }
            if (badge) {
                Eyebrow("Markdown", cs.onSurfaceVariant)
                Text(md, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHighest).padding(12.dp), style = ty.bodySmall, fontFamily = FontFamily.Monospace)
                FilledTonalButton({ clip.setText(AnnotatedString(md)); t.show("Markdown copied"); vm.haptic(H.SUCCESS) }, shapes = ButtonDefaults.shapes()) {
                    Icon(Sym.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy Markdown")
                }
                Eyebrow("Deep link", cs.onSurfaceVariant)
                Text("blazestore://app/${enc(id)}", style = ty.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** Slim submission header: title, cooldown and live step progress. No hero box, the form is the point. */
@Composable
private fun SubmitHero(platform: String, cooldown: Int, done: Int, total: Int) = Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
    Text("Submit ${if (platform == Platform.ANDROID) "an app" else if (platform == Platform.TV) "a TV app" else "software"}", style = ty.headlineSmallEmphasized)
    Text("Filed as an issue on the Blaze GitLab tracker, reviewed, then it lands in the catalog.", style = ty.bodyMedium, color = cs.onSurfaceVariant)
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Tag("${cooldown / 60}h ${cooldown % 60}m cooldown", cs.onSurfaceVariant, Sym.Timer)
        Spacer(Modifier.weight(1f))
        Text("$done/$total", style = ty.labelLargeEmphasized, color = cs.primary)
    }
    val p by animateFloatAsState(done / total.toFloat().coerceAtLeast(1f), MaterialTheme.motionScheme.defaultSpatialSpec(), label = "steps")
    LinearWavyProgressIndicator({ p }, Modifier.fillMaxWidth().padding(top = 10.dp))
}

/** Submission mode as a pair of selectable tiles: the selected one fills and its shape turns primary. */
@Composable
private fun ModeCard(title: String, desc: String, icon: ImageVector, shape: RoundedPolygon, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fx = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val bg by animateColorAsState(if (on) cs.secondaryContainer else cs.surfaceContainer, fx, label = "bg")
    Column(modifier.springClick(RoundedCornerShape(24.dp), onClick = onClick).background(bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(shape.toShape()).background(if (on) cs.primary else cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = if (on) cs.onPrimary else cs.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(on, enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(), exit = scaleOut() + fadeOut()) { Icon(Sym.CheckCircle, "Selected", tint = cs.primary) }
        }
        Text(title, Modifier.padding(top = 12.dp), style = ty.titleSmallEmphasized)
        Text(desc, style = ty.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Live preview of a pasted icon link, shown inside the field. */
private fun iconPreview(url: String): (@Composable () -> Unit)? = url.trim().takeIf { it.startsWith("http") }?.let { u ->
    { AsyncImage(sanitizeGitHubImageUrl(u), "Icon preview", Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
}
