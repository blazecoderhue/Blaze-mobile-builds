package com.blaze.assistant.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v0.28.1 table card (Blazeinchue, from Hark's chat): a rounded dark card, bold title, thin divider, muted grey column
 * headers, wrapping white cells with generous row spacing and no grid lines. When the columns don't fit, the table
 * scrolls sideways inside the card with edge fades, and the first column stays in view ([RichKit.TableCard.sticky]).
 * Copy gives plain text, TSV gives tab-separated cells for a spreadsheet. Used for ```table blocks and Markdown pipe
 * tables ([RichReplies.expand]).
 */
object TableCardStyle {
    val Fill = Color(0xFF1C1D21)
    val Head = Color(0xFF9B9BA3)
    val Cell = Color(0xFFF4F4F6)
    val Divider = Color(0x24FFFFFF)
    const val MIN_COL_DP = 84
    const val MAX_COL_DP = 200
    const val FIRST_SHOWN = 8

    /**
     * Column widths (px) from each column's natural width: clamped, then stretched to fill [avail] when they fit.
     * v0.30.5 (a user's "unreadable table": columns slid under a sticky first column and got cut on both edges): too
     * wide now shrinks every column above [minPx] proportionally so the grid always fits the card; nothing scrolls
     * sideways. A table whose columns can't fit even at [minPx] is shown as stacked rows instead ([stacked]).
     */
    fun widths(natural: List<Int>, minPx: Int, maxPx: Int, avail: Int): List<Int> {
        val w = natural.map { it.coerceIn(minPx, maxPx) }
        val total = w.sum()
        if (total == 0) return w
        if (total > avail) {
            val floor = minPx * w.size
            if (floor >= avail) return w.map { minPx }
            val over = total - floor
            val room = avail - floor
            val out = w.map { v -> minPx + ((v - minPx).toLong() * room / over).toInt() }.toMutableList()
            out[out.lastIndex] += avail - out.sum()
            return out
        }
        if (total >= avail) return w
        val extra = avail - total
        val out = w.mapIndexed { i, v -> v + extra * v / total }.toMutableList()
        if (out.isNotEmpty()) out[out.lastIndex] += avail - out.sum()
        return out
    }

    /** v0.30.5: more columns than fit at the minimum width: each row becomes a small block (first cell bold, then "Header: value"). */
    fun stacked(cols: Int, availDp: Float): Boolean = cols >= 3 && cols * MIN_COL_DP > availDp

    /** The sticky first column is only worth it with 3+ columns that need scrolling. */
    fun sticky(wanted: Boolean, cols: Int, scrolls: Boolean): Boolean = wanted && cols >= 3 && scrolls
}

@Composable
fun TableCardView(t: RichKit.TableCard, modifier: Modifier = Modifier) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    var all by remember(t) { mutableStateOf(t.rows.size <= TableCardStyle.FIRST_SHOWN) }
    val rows = if (all) t.rows else t.rows.take(TableCardStyle.FIRST_SHOWN)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(TableCardStyle.Fill)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 10.dp)) {
            Text(t.title.ifBlank { " " }, modifier = Modifier.weight(1f).padding(top = 4.dp),
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp, color = TableCardStyle.Cell))
            fun copy(s: String, what: String) { clip.setText(AnnotatedString(s)); Toast.makeText(ctx, what, Toast.LENGTH_SHORT).show() }
            Text("Copy", modifier = Modifier.clip(RoundedCornerShape(50)).clickable { copy(RichKit.tablePlain(t), "Copied") }.padding(horizontal = 10.dp, vertical = 6.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TableCardStyle.Head))
            Text("TSV", modifier = Modifier.clip(RoundedCornerShape(50)).clickable { copy(RichKit.tableTsv(t), "Copied as TSV") }.padding(horizontal = 10.dp, vertical = 6.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TableCardStyle.Head))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(TableCardStyle.Divider))
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = TableCardStyle.stacked(t.cols, maxWidth.value - 8f)
            SelectionContainer { if (narrow) TableStacked(t.copy(rows = rows)) else TableGridCard(t.copy(rows = rows)) }
        }
        if (!all) Text("Show all ${t.rows.size} rows", modifier = Modifier.padding(start = 10.dp, bottom = 10.dp).clip(RoundedCornerShape(50))
            .clickable { all = true }.padding(horizontal = 8.dp, vertical = 6.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TableCardStyle.Head))
        else Spacer(Modifier.height(8.dp))
    }
}

/** v0.30.5: a table too wide for the phone, one block per row: the first cell bold, then each other column as "Header  value". */
@Composable
private fun TableStacked(t: RichKit.TableCard) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        t.rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(TableCardStyle.Divider))
            val first = row.getOrElse(0) { "" }
            Text(remember(first) { Md.render(first, c.accent, Color.White.copy(alpha = 0.12f)) }, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = TableCardStyle.Cell))
            for (k in 1 until t.cols) {
                val v = row.getOrElse(k) { "" }
                if (v.isBlank()) continue
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(t.headers.getOrElse(k) { "" }, modifier = Modifier.weight(0.38f).padding(end = 10.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = TableCardStyle.Head))
                    Text(remember(v) { Md.render(v, c.accent, Color.White.copy(alpha = 0.12f)) }, modifier = Modifier.weight(0.62f),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = TableCardStyle.Cell))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** The grid: one Layout so a wrapped cell sets its whole row's height. v0.30.5: always fits the card (no sideways scroll). */
@Composable
private fun TableGridCard(t: RichKit.TableCard) {
    val cols = t.cols
    if (cols == 0) return
    val all = listOfNotNull(t.headers.takeIf { it.isNotEmpty() }) + t.rows
    val hasHead = t.headers.isNotEmpty()
    var off by remember { mutableFloatStateOf(0f) }
    var maxOff by remember { mutableIntStateOf(0) }
    var stickyW by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollableState { d -> val old = off; off = (off - d).coerceIn(0f, maxOff.toFloat()); old - off }
    val c = LocalBlaze.current
    Layout(
        modifier = Modifier.fillMaxWidth().clipToBounds()
            .scrollable(scroll, Orientation.Horizontal, enabled = maxOff > 0)
            .drawWithContent {
                drawContent()
                // Edge fades where more columns are hidden.
                val fw = 28.dp.toPx()
                if (off > 1f) drawRect(Brush.horizontalGradient(listOf(TableCardStyle.Fill, Color.Transparent), startX = stickyW.toFloat(), endX = stickyW + fw),
                    topLeft = Offset(stickyW.toFloat(), 0f), size = Size(fw, size.height))
                if (off < maxOff - 1f) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, TableCardStyle.Fill), startX = size.width - fw, endX = size.width),
                    topLeft = Offset(size.width - fw, 0f), size = Size(fw, size.height))
            },
        content = {
            all.forEachIndexed { r, row ->
                val head = hasHead && r == 0
                for (k in 0 until cols) {
                    val cell = row.getOrElse(k) { "" }
                    val shown = remember(cell, head) { Md.render(cell, c.accent, Color.White.copy(alpha = 0.12f)) }
                    Text(shown, modifier = Modifier.background(TableCardStyle.Fill).padding(horizontal = 10.dp, vertical = if (head) 8.dp else 11.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = if (head) 12.5.sp else 14.sp, lineHeight = if (head) 17.sp else 20.sp,
                            fontWeight = if (head) FontWeight.Medium else FontWeight.Normal, color = if (head) TableCardStyle.Head else TableCardStyle.Cell))
                }
            }
        },
    ) { ms, cons ->
        val avail = (if (cons.hasBoundedWidth) cons.maxWidth else 720) - 8.dp.roundToPx()
        val natural = (0 until cols).map { k -> (all.indices).maxOf { r -> ms[r * cols + k].maxIntrinsicWidth(Constraints.Infinity) } }
        val w = TableCardStyle.widths(natural, TableCardStyle.MIN_COL_DP.dp.roundToPx(), TableCardStyle.MAX_COL_DP.dp.roundToPx(), avail)
        val total = w.sum()
        val scrolls = total > avail
        val sticky = TableCardStyle.sticky(t.sticky, cols, scrolls)
        val pl = ms.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(w[i % cols])) }
        val heights = all.indices.map { r -> (0 until cols).maxOf { k -> pl[r * cols + k].height } }
        val xs = w.runningFold(0) { a, b -> a + b }
        val pad = 4.dp.roundToPx()
        val newMax = if (scrolls) total - avail else 0
        if (newMax != maxOff) maxOff = newMax
        if (off > newMax) off = newMax.toFloat()
        val sw = if (sticky) w[0] + pad else 0
        if (sw != stickyW) stickyW = sw
        val width = if (cons.hasBoundedWidth) cons.maxWidth else total + 2 * pad
        layout(width, heights.sum()) {
            var y = 0
            all.indices.forEach { r ->
                // Scrolling columns first, then the sticky one on top of them.
                for (k in (if (sticky) 1 else 0) until cols) pl[r * cols + k].place(pad + xs[k] - off.toInt(), y)
                if (sticky) pl[r * cols].place(pad, y)
                y += heights[r]
            }
        }
    }
}
