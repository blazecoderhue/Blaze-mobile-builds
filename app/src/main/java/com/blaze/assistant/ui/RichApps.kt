package com.blaze.assistant.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** v0.33.3: the chat's quote box, reachable from cards inside a reply (an app tile's long-press quotes it with its package). */
object ChatQuote {
    val request = MutableStateFlow("")
}

/** App icons, decoded off the main thread at tile size and kept for the session. */
private object AppIcons {
    private val cache = android.util.LruCache<String, ImageBitmap>(160)
    fun cached(pkg: String): ImageBitmap? = cache.get(pkg)
    suspend fun load(ctx: android.content.Context, pkg: String, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(pkg) ?: try {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b); d.setBounds(0, 0, px, px); d.draw(c)
            b.asImageBitmap().also { cache.put(pkg, it) }
        } catch (_: Exception) { null }
    }
}

/**
 * The user's installed apps as a grid of rounded tiles (icon + label). Tap opens the app; long-press quotes the tile into
 * the message box with its real package ("App: Rally Horizon (package …, installed …)"), so "open and play it" launches it.
 * Shows the first 24, then "Show all N".
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun RichApps(card: com.blaze.assistant.ui.RichKit.AppsCard) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    var all by rememberSaveable(card.title, card.apps.size) { mutableStateOf(card.apps.size <= 24) }
    val shown = if (all) card.apps else card.apps.take(24)
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.accentSoft.copy(alpha = 0.35f)).padding(12.dp)) {
        if (card.title.isNotBlank()) Text(card.title, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            shown.forEach { a ->
                val px = (44 * ctx.resources.displayMetrics.density).toInt()
                val icon by produceState(AppIcons.cached(a.pkg), a.pkg) { if (value == null) value = AppIcons.load(ctx, a.pkg, px) }
                Column(Modifier.width(72.dp).clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClickLabel = "Open ${a.label}",
                        onClick = {
                            val i = try { ctx.packageManager.getLaunchIntentForPackage(a.pkg) } catch (_: Exception) { null }
                            if (i != null) try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                            else Toast.makeText(ctx, "${a.label} isn't installed any more.", Toast.LENGTH_SHORT).show()
                        },
                        onLongClickLabel = "Quote ${a.label}",
                        onLongClick = {
                            ChatQuote.request.value = com.blaze.assistant.phone.AppFinder.tileLine(com.blaze.assistant.phone.AppFinder.App(a.label, a.pkg))
                        })
                    .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.paper), contentAlignment = Alignment.Center) {
                        val b = icon
                        if (b != null) Image(b, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                        else Text(a.label.take(1).uppercase(), style = TextStyle(fontFamily = Type.Sans, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.accent))
                    }
                    Text(a.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, lineHeight = 13.sp, color = c.ink))
                }
            }
        }
        if (!all) Text("Show all ${card.apps.size}", modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50))
            .background(c.accentSoft).combinedClickable(onClick = { all = true }).padding(horizontal = 16.dp, vertical = 9.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accent))
    }
}
