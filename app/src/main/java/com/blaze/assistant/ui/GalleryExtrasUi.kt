package com.blaze.assistant.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blaze.assistant.media.GalleryClean
import com.blaze.assistant.media.GalleryModel
import com.blaze.assistant.media.GalleryModel.Entry
import com.blaze.assistant.media.GalleryRepo
import com.blaze.assistant.media.GalleryTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** v0.34.0 Gallery extras: Clean up (duplicates + blurry), Trash, Backup plan, On this day and the basic editor. */

@Composable
private fun thumbOf(e: Entry, px: Int): ImageBitmap? {
    val v by produceState(GalleryRepo.cachedThumb(e, px)?.asImageBitmap(), e.id, px) {
        if (value == null) value = GalleryRepo.thumb(e, px)?.asImageBitmap()
    }
    return v
}

@Composable
private fun Thumb(e: Entry, size: Int, onClick: () -> Unit = {}) {
    val b = thumbOf(e, 192)
    Box(Modifier.size(size.dp).clip(BlazeShapes.small).background(Color(0x22888888)).clickable(onClick = onClick)) {
        if (b != null) Image(b, e.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun DText(t: String, size: Float = 13f, color: Color = DialogColors.body, weight: FontWeight = FontWeight.Normal) =
    Text(t, style = TextStyle(fontSize = size.sp, color = color, fontWeight = weight))

/** On this day: a row of pictures from this date in earlier years, at the top of All. */
@Composable
fun OnThisDayCard(groups: List<Pair<Int, List<Entry>>>, now: Long, onOpen: (Entry) -> Unit) {
    val c = LocalBlaze.current.mono
    if (groups.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text("On this day", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { (year, list) ->
                items(list.take(12), key = { "otd" + it.id }) { e ->
                    Column {
                        Thumb(e, 104) { onOpen(e) }
                        Text(GalleryTools.yearsAgo(year, now), style = TextStyle(fontSize = 11.sp, color = c.weak))
                    }
                }
            }
        }
    }
}

/** Clean up: scans the pictures, then offers the extra copies of near-duplicates and the blurry ones for the Trash. */
@Composable
fun CleanUpDialog(all: List<Entry>, onTrash: (List<Entry>) -> Unit, onDismiss: () -> Unit) {
    var progress by remember { mutableStateOf("Looking at your pictures\u2026") }
    var scans by remember { mutableStateOf<List<GalleryTools.Scan>?>(null) }
    LaunchedEffect(Unit) {
        scans = GalleryClean.scan(all) { i, n -> progress = "Looking at your pictures \u00b7 $i of $n" }
    }
    val byId = remember(all) { all.associateBy { it.id } }
    val s = scans
    val groups = remember(s) { s?.let { GalleryTools.duplicates(it) }.orEmpty() }
    val blur = remember(s) { s?.let { GalleryTools.blurry(it) }.orEmpty() }
    var skipBlur by remember { mutableStateOf<Set<String>>(emptySet()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(14.dp).fillMaxWidth().clip(BlazeShapes.large).background(DialogColors.surface).padding(18.dp).heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())) {
            DText("Clean up", 18f, DialogColors.title, FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (s == null) DText(progress) else {
            DText("Checked ${s.size} pictures on the phone (nothing leaves it).")
            Spacer(Modifier.height(12.dp))
            DText("Near-duplicates \u00b7 ${groups.size} groups", 14f, DialogColors.title, FontWeight.Medium)
            if (groups.isEmpty()) DText("None found.")
            groups.take(30).forEach { g ->
                Row(Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    g.forEachIndexed { i, sc -> byId[sc.id]?.let { e -> Column { Thumb(e, 72); DText(if (i == 0) "Keep" else "Extra", 11f) } } }
                }
            }
            if (groups.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                PillButton("Move ${groups.sumOf { it.size - 1 }} extra copies to Trash (${GalleryModel.size(GalleryTools.savings(groups))})", primary = true) {
                    onTrash(groups.flatMap { it.drop(1) }.mapNotNull { byId[it.id] })
                }
            }
            Spacer(Modifier.height(16.dp))
            DText("Blurry \u00b7 ${blur.size}", 14f, DialogColors.title, FontWeight.Medium)
            if (blur.isEmpty()) DText("None found.") else DText("Tap a picture to keep it.", 12f)
            Row(Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                blur.take(60).forEach { sc -> byId[sc.id]?.let { e ->
                    Column { Thumb(e, 72) { skipBlur = if (e.id in skipBlur) skipBlur - e.id else skipBlur + e.id }; DText(if (e.id in skipBlur) "Keep" else "Trash", 11f) }
                } }
            }
            val pick = blur.filter { it.id !in skipBlur }
            if (pick.isNotEmpty()) PillButton("Move ${pick.size} blurry to Trash", primary = false) { onTrash(pick.mapNotNull { byId[it.id] }) }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PillButton("Close", primary = false, onClick = onDismiss) }
        }
    }
}

/** The 30-day Trash: Blaze's own items (restore / delete now) and phone photos in Android's trash (restore). */
@Composable
fun TrashDialog(onRestorePhone: (android.content.IntentSender) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(GalleryClean.trashed(ctx)) }
    var phone by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    LaunchedEffect(Unit) { phone = withContext(Dispatchers.IO) { GalleryClean.phoneTrash(ctx) } }
    val now = System.currentTimeMillis()
    BlazeDialog("Trash", "Things stay here for ${GalleryTools.TRASH_DAYS} days, then go for good.", confirm = "Done", onConfirm = onDismiss, onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (items.isEmpty() && phone.isEmpty()) DText("The Trash is empty.")
            items.sortedByDescending { it.at }.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        DText(t.title.ifBlank { t.original.substringAfterLast('/') }, 13.5f, DialogColors.title)
                        DText("${GalleryTools.daysLeft(t, now)} days left", 11.5f)
                    }
                    PillButton("Restore", primary = false) { scope.launch { GalleryClean.restore(ctx, t); items = GalleryClean.trashed(ctx); GalleryRepo.refresh() } }
                    Spacer(Modifier.width(4.dp))
                    PillButton("Delete", primary = false, warm = true) { GalleryClean.deleteForever(ctx, t); items = GalleryClean.trashed(ctx) }
                }
            }
            if (phone.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                DText("${phone.size} phone photos are in Android's trash.", 13f, DialogColors.title)
                PillButton("Restore them", primary = false) { GalleryClean.restorePhoneTrash(ctx, phone)?.let(onRestorePhone) }
            }
        }
    }
}

/** Backup plan: how much there is, how fast it grows, and which cloud sizes it fits. */
@Composable
fun BackupPlanDialog(all: List<Entry>, onDismiss: () -> Unit) {
    val p = remember(all) { GalleryTools.plan(all, System.currentTimeMillis()) }
    BlazeDialog("Backup plan", confirm = "OK", onConfirm = onDismiss, onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            DText("Everything: ${GalleryModel.size(p.totalBytes)}", 14f, DialogColors.title, FontWeight.Medium)
            DText("Photos ${GalleryModel.size(p.photos)} \u00b7 videos ${GalleryModel.size(p.videos)} \u00b7 music ${GalleryModel.size(p.music)}")
            DText("Grows about ${GalleryModel.size(p.perMonth)} a month (last 6 months).")
            Spacer(Modifier.height(8.dp))
            p.lines.forEach { DText("\u2022 $it") }
            if (p.biggest.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                DText("Biggest videos", 13.5f, DialogColors.title, FontWeight.Medium)
                p.biggest.forEach { DText("${it.title.take(40)} \u00b7 ${GalleryModel.size(it.size)}", 12.5f) }
            }
            Spacer(Modifier.height(8.dp))
            DText("Blaze doesn't upload anything itself; use this to pick a plan in Google Photos, OneDrive or a computer backup.", 12f)
        }
    }
}

/** Basic editor: rotate, crop to a shape, brightness / contrast / colour; saves a copy into Blaze's Gallery. */
@Composable
fun PhotoEditor(e: Entry, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var src by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(e.id) { src = GalleryClean.load(ctx, e, 1600) }
    var rotate by remember { mutableIntStateOf(0) }
    var aspect by remember { mutableFloatStateOf(0f) }
    var bright by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var saving by remember { mutableStateOf(false) }
    val preview by produceState<ImageBitmap?>(null, src, rotate, aspect, bright, contrast, sat) {
        val s = src ?: return@produceState
        val small = withContext(Dispatchers.Default) {
            val k = 720f / maxOf(s.width, s.height); val sm = if (k < 1f) Bitmap.createScaledBitmap(s, (s.width * k).toInt().coerceAtLeast(1), (s.height * k).toInt().coerceAtLeast(1), true) else s
            GalleryClean.render(sm, rotate, aspect, bright, contrast, sat)
        }
        value = small.asImageBitmap()
    }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val p = preview
                if (p == null) Text("Opening\u2026", style = TextStyle(color = Color.White))
                else Image(p, "Preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton("Rotate", primary = false) { rotate = (rotate + 90) % 360 }
                listOf("Original" to 0f, "Square" to 1f, "4:3" to 4f / 3f, "16:9" to 16f / 9f, "9:16" to 9f / 16f).forEach { (n, r) ->
                    PillButton(n, primary = aspect == r) { aspect = r }
                }
                PillButton("Reset", primary = false) { rotate = 0; aspect = 0f; bright = 0f; contrast = 1f; sat = 1f }
            }
            @Composable fun Knob(label: String, v: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.width(86.dp), style = TextStyle(color = Color.White, fontSize = 12.sp))
                    Slider(v, set, valueRange = range, modifier = Modifier.weight(1f))
                }
            }
            Knob("Brightness", bright, -0.5f..0.5f) { bright = it }
            Knob("Contrast", contrast, 0.5f..1.5f) { contrast = it }
            Knob("Colour", sat, 0f..2f) { sat = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                PillButton("Cancel", primary = false, onClick = onDone)
                PillButton(if (saving) "Saving\u2026" else "Save copy", primary = true) {
                    val s = src ?: return@PillButton
                    if (saving) return@PillButton
                    saving = true
                    scope.launch {
                        try {
                            val out = withContext(Dispatchers.Default) { GalleryClean.render(s, rotate, aspect, bright, contrast, sat) }
                            GalleryClean.saveCopy(ctx, out, e.title)
                            GalleryRepo.refresh()
                            Toast.makeText(ctx, "Saved a copy in the Gallery.", Toast.LENGTH_SHORT).show()
                            onDone()
                        } catch (t: Throwable) { Toast.makeText(ctx, "Couldn't save: ${t.message}", Toast.LENGTH_SHORT).show() }
                        finally { saving = false }
                    }
                }
            }
        }
    }
}
