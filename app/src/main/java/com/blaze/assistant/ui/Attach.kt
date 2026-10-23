package com.blaze.assistant.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.24.6: pictures from the keyboard and attachment previews.
 * Keyboards (Samsung Keyboard's clipboard, Gboard's GIFs and stickers) send rich content through the input
 * connection's commitContent, which Compose's text field didn't accept. [AcceptImages] advertises image types on the
 * field's EditorInfo and copies whatever the keyboard commits into the cache, then hands it on like a picked photo.
 */
object RichContent {
    val MIMES = arrayOf("image/*", "image/png", "image/jpeg", "image/gif", "image/webp", "image/heic")

    /** File extension for a committed image's MIME type (unknown → jpg). */
    fun extFor(mime: String): String = when (mime.lowercase().substringBefore(';').trim()) {
        "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; "image/heic", "image/heif" -> "heic"; "image/bmp" -> "bmp"
        else -> "jpg"
    }

    fun isImageMime(mime: String) = mime.lowercase().startsWith("image/")

    /** Copies [uri] (readable only while the keyboard's grant lasts) into cache/kb_images; null if it can't be read. */
    fun copyToCache(ctx: Context, uri: Uri, mime: String): File? = try {
        val dir = File(ctx.cacheDir, "kb_images").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86_400_000L }?.forEach { it.delete() }
        val f = File(dir, "kb_${System.currentTimeMillis()}.${extFor(mime)}")
        ctx.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } ?: throw IllegalStateException("empty")
        if (f.length() == 0L) { f.delete(); null } else f
    } catch (_: Exception) { null }
}

/** Lets every text field inside [content] take images from the keyboard; [onImage] gets a cache file URI and its MIME type. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AcceptImages(onImage: (Uri, String) -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val cb by rememberUpdatedState(onImage)
    val interceptor = remember {
        PlatformTextInputInterceptor { request, next ->
            val wrapped = PlatformTextInputMethodRequest { info: EditorInfo ->
                val ic = request.createInputConnection(info)
                EditorInfoCompat.setContentMimeTypes(info, RichContent.MIMES)
                @Suppress("DEPRECATION")
                InputConnectionCompat.createWrapper(ic, info) { item, flags, _ ->
                    if (Build.VERSION.SDK_INT >= 25 && (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                        try { item.requestPermission() } catch (_: Exception) { return@createWrapper false }
                    }
                    val desc = item.description
                    val mime = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { RichContent.isImageMime(it) } ?: "image/jpeg"
                    val uri = item.contentUri
                    Thread {
                        val f = RichContent.copyToCache(ctx, uri, mime)
                        try { item.releasePermission() } catch (_: Exception) { }
                        if (f != null) Handler(Looper.getMainLooper()).post { cb(Uri.fromFile(f), mime) }
                    }.start()
                    true
                }
            }
            next.startInputMethod(wrapped)
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

/** Pure helpers for attachment previews (AttachTest). */
object AttachView {
    private val imageExt = Regex("""\.(jpe?g|png|gif|webp|heic|heif|bmp)$""", RegexOption.IGNORE_CASE)
    fun isImageName(name: String) = imageExt.containsMatchIn(name.trim())
    fun isPdfName(name: String) = name.trim().endsWith(".pdf", true)

    /**
     * Names to show as file chips under a sent message: the files beyond the ones that have a thumbnail ([media]),
     * which come first (Agent.send orders them that way since v0.24.6).
     */
    fun fileChips(files: List<String>, media: List<String>): List<String> = if (media.isEmpty()) files else files.drop(media.size)

    /** The placeholder text Blaze stores when you send pictures with no words: hidden under the thumbnails. */
    const val NO_TEXT = "Take a look at this."
    fun bubbleText(text: String, media: List<String>): String = if (media.isNotEmpty() && text.trim() == NO_TEXT) "" else text
}

@Composable
fun rememberBitmap(path: String?, px: Int): ImageBitmap? {
    val v by produceState<ImageBitmap?>(null, path, px) {
        value = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1; while (o.outWidth / (s * 2) >= px) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            } catch (_: Throwable) { null }
        }
    }
    return v
}

/** A rounded picture thumbnail ([size]) with an optional small × in the corner. Tap opens it. */
@Composable
fun ThumbChip(path: String?, name: String, size: Dp = 60.dp, onOpen: () -> Unit, onRemove: (() -> Unit)? = null, bitmap: ImageBitmap? = null) {
    val c = LocalBlaze.current
    val bmp = bitmap ?: rememberBitmap(path, 240)
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.size(size + 6.dp)) {
        Box(Modifier.padding(top = 6.dp, end = 6.dp).size(size).clip(shape).background(c.bubble).border(1.dp, c.hair, shape)
            .clickable(onClickLabel = "Preview $name") { onOpen() }.semantics { contentDescription = "Picture $name" }) {
            if (bmp != null) Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (onRemove != null) Box(Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(c.ink).border(1.5.dp, c.paper, CircleShape)
            .clickable(onClickLabel = "Remove $name") { onRemove() }.semantics { contentDescription = "Remove $name" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = c.paper, modifier = Modifier.size(13.dp))
        }
    }
}

/** A non-picture file: icon + name in a rounded chip (× to remove when [onRemove] is set). */
@Composable
fun FileChip(name: String, onRemove: (() -> Unit)? = null) {
    val c = LocalBlaze.current
    Row(Modifier.padding(top = 6.dp).height(40.dp).clip(RoundedCornerShape(14.dp)).background(c.bubble).padding(start = 10.dp, end = if (onRemove != null) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (AttachView.isPdfName(name)) Icons.Rounded.PictureAsPdf else Icons.Rounded.Description, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.body))
        if (onRemove != null) Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClickLabel = "Remove $name") { onRemove() }
            .semantics { contentDescription = "Remove $name" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = c.sub, modifier = Modifier.size(15.dp))
        }
    }
}

/** The composer's attachments: rounded picture thumbnails (× to remove, tap to preview) and file chips. */
@Composable
fun AttachmentThumbs(files: List<com.blaze.assistant.agent.Attachment>, loading: Boolean, onRemove: (com.blaze.assistant.agent.Attachment) -> Unit) {
    val c = LocalBlaze.current
    var preview by remember { mutableStateOf<String?>(null) }
    // v0.28.1: with several pictures, tapping one opens the full-screen viewer at it (swipe between them). The row of
    // thumbnails stays (each needs its own ×), so the composer keeps its height.
    val pics = files.filter { it.thumb != null && !AttachView.isPdfName(it.name) }.mapNotNull { it.thumb }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        files.forEach { f ->
            if (f.thumb != null && !AttachView.isPdfName(f.name)) ThumbChip(f.thumb, f.name, onOpen = { preview = f.thumb }, onRemove = { onRemove(f) })
            else FileChip(f.name, onRemove = { onRemove(f) })
        }
        if (loading) Label("Reading\u2026", color = c.accent, size = 10.sp)
    }
    preview?.let { p -> if (pics.size > 1 && p in pics) ImagePager(pics, pics.indexOf(p)) { preview = null } else ImagePreview(p) { preview = null } }
}

/** Full-screen picture preview with a round close button. */
@Composable
fun ImagePreview(path: String, onDismiss: () -> Unit) {
    // v0.28.1: one viewer for one or many pictures: close (X) top left, save top right, pinch to zoom.
    ImagePager(listOf(path), 0, onDismiss)
}

/**
 * Your sent message with pictures. v0.30.5: the pictures stand on their own (rounded, right-aligned, no bubble around
 * them; Blazeinchue: "images should not be in the blue bubble thingy"), tap to preview; the words get their own normal bubble
 * underneath, and non-picture files sit as icon chips below. Old messages without thumbnails show the file chips only.
 */
@Composable
fun SentBubble(text: String, quoted: String, media: List<String>, files: List<String>, pos: GroupPos, bitmaps: List<ImageBitmap?>? = null) {
    val c = LocalBlaze.current
    var preview by remember { mutableStateOf<String?>(null) }
    var previewAt by remember { mutableStateOf(-1) }
    val words = AttachView.bubbleText(text, media)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (media.isNotEmpty()) {
            val single = media.size == 1
            // v0.28.1: several pictures = a fanned card stack (swipe to flip, tap or ⤢ for the full-screen viewer).
            if (!single) Box(Modifier.padding(start = 48.dp)) { ImageStack(media.take(12), bitmaps, onOpen = { previewAt = it }) }
            else {
                val p = media[0]
                val bmp = bitmaps?.getOrNull(0) ?: rememberBitmap(p, 720)
                val shape = RoundedCornerShape(18.dp)
                Box(Modifier.padding(start = 48.dp).widthIn(min = 120.dp, max = 260.dp).heightIn(min = 90.dp, max = 300.dp)
                    .clip(shape).background(if (bmp == null) c.faint else Color.Transparent)
                    .clickable(onClickLabel = "Preview picture") { preview = p }
                    .semantics { contentDescription = "Sent picture 1" }) {
                    if (bmp != null) Image(bmp, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 300.dp).clip(shape))
                    else Box(Modifier.size(160.dp, 120.dp))
                }
            }
            if (words.isNotBlank() || quoted.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                UserBubble(words, quoted, pos)
            }
        } else UserBubble(text, quoted, pos)
        val chips = AttachView.fileChips(files, media)
        if (chips.isNotEmpty()) Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips.take(4).forEach { FileChip(it) } }
    }
    preview?.let { p -> ImagePreview(p) { preview = null } }
    if (previewAt >= 0) ImagePager(media.take(12), previewAt) { previewAt = -1 }
}

/** "Open in Design?": the chip Blaze shows instead of switching views on its own. */
@Composable
fun ViewOfferChip(label: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.glass(GlassTokens.pillShape, GlassIntensity.High, shadow = false).clickable(onClickLabel = label) { onOpen() }
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            MascotGlyph(24.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(32.dp).glass(CircleShape, GlassIntensity.Low, shadow = false).clickable(onClickLabel = "Not now") { onDismiss() }
            .semantics { contentDescription = "Not now" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = c.sub, modifier = Modifier.size(16.dp))
        }
    }
}
