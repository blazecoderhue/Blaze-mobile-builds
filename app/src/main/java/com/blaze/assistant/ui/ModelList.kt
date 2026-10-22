package com.blaze.assistant.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.ModelCatalog

/** The model field (v0.19.0): raised, mono id, blue edge while the list is open. */
@Composable
fun ModelField(selected: String, open: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Row(Modifier.fillMaxWidth().pageGlass(c, PageGlass.row, strongRim = open).clickable(onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(if (selected.isBlank()) c.weak else c.accent, BlazeShapes.pill)); Spacer(Modifier.width(10.dp))
        Text(selected.ifBlank { "Choose a model" }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Mono, fontSize = 13.5.sp, color = if (selected.isBlank()) c.weak else c.ink))
        Text(if (open) "\u25B4" else "\u25BE", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = if (open) c.accent else c.sub))
    }
}

/**
 * Desktop's model picker on the phone: search on top, one row per model (name, id, a FREE tag when the
 * catalogue says so), the current one marked in blue. Used by Brain › Model.
 */
@Composable
fun ModelList(models: List<ModelCatalog.Model>, selected: String, query: String, onQuery: (String) -> Unit, onPick: (String) -> Unit) {
    val c = LocalBlaze.current.mono
    val shown = models.filter { query.isBlank() || it.id.contains(query, true) || it.name.contains(query, true) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).pageGlass(c, PageGlass.row)) {
        Box(Modifier.padding(8.dp)) { KitSearch(query, onQuery, "Search ${models.size} models") }
        if (shown.isEmpty()) EmptyState("No model matches \u201c$query\u201d", "Try part of the name, like \u201cllama\u201d or \u201cflash\u201d.", mark = false)
        shown.forEach { m ->
            val on = m.id == selected
            val free = m.note.contains("free", true) || m.id.endsWith(":free")
            Row(Modifier.fillMaxWidth().hairlineTop(c.hair).then(if (on) Modifier.pageGlass(c, BlazeShapes.small, alpha = 0.14f) else Modifier.clip(BlazeShapes.small)).clickable { onPick(m.id) }
                .heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (on) c.accent else c.hair2, BlazeShapes.pill)); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (on) c.accent else c.ink))
                        if (free) { Spacer(Modifier.width(8.dp)); Tag("Free", on = true) }
                    }
                    Text(m.id + if (m.note.isNotBlank() && !free) "  \u00b7  ${m.note}" else "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.sub))
                }
                if (on) { Spacer(Modifier.width(8.dp)); Text("\u2713", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.accent)) }
            }
        }
    }
}
