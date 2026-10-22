package com.blaze.assistant.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.routine.Routine
import com.blaze.assistant.routine.Routines

/**
 * Schedule (Settings › Routines, /schedule), v0.19.0 look after Desktop's Schedule view: a page header,
 * one numbered row per routine with its time as a pill and when it runs next, a switch and Remove,
 * a real empty state, and the new-routine form in a raised card. [list0], [adding0], [now] are for screenshots.
 */
@Composable
fun SchedulePage(list0: List<Routine>? = null, adding0: Boolean = false, now: Long? = null) {
    val c = LocalBlaze.current.mono
    val live by Routines.all.collectAsState()
    val list = list0 ?: live
    var adding by remember { mutableStateOf(adding0) }
    var open by remember { mutableIntStateOf(-1) }
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("07:00") }
    var days by remember { mutableIntStateOf(31) }
    val at = now ?: System.currentTimeMillis()
    val fmt = remember { java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()) }
    val short = remember { java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()) }

    PageHead("Schedule", "Routines", "Things Blaze does on a schedule, even when the app is closed. Or just ask: \u201cevery weekday at 7, brief me on my calendar\u201d.")
    if (adding) {
        Column(Modifier.fillMaxWidth().pageGlass(c, PageGlass.row, strongRim = true).padding(14.dp)) {
            Label("New routine", size = 10.sp, color = c.accent, modifier = Modifier.padding(bottom = 12.dp))
            Field("Name", name, { name = it }, "Morning brief")
            Field("What should Blaze do?", prompt, { prompt = it }, "Summarise today's calendar and unread texts")
            Field("Time (24h)", time, { time = it }, "07:00")
            Label("Days", modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d ->
                    val on = days and (1 shl i) != 0
                    Box(Modifier.weight(1f).height(38.dp).then(if (on) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f))
                        .clickable { days = days xor (1 shl i) }, contentAlignment = Alignment.Center) {
                        Text(d, style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, color = if (on) c.paper else c.sub))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("Save routine", Btn.ACCENT, enabled = prompt.isNotBlank()) {
                    val parts = time.split(":")
                    val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 7
                    val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    if (list0 == null) Routines.add(name, prompt, h, m, days)
                    adding = false; name = ""; prompt = ""
                }
                KitButton("Cancel", Btn.GHOST) { adding = false }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
    if (list.isEmpty()) {
        if (!adding) Rows { EmptyState("Nothing scheduled yet", "Ask in chat, or add one here.", action = "+ New routine") { adding = true } }
        return
    }
    val onCount = list.count { it.enabled }
    GroupHead("Scheduled \u00b7 %02d \u00b7 $onCount on".format(list.size)) {
        if (!adding) KitButton("+ New", Btn.INK) { adding = true }
    }
    Rows {
        list.forEachIndexed { i, r ->
            val whenTxt = if (r.everyMinutes > 0) r.daysLabel() else if (r.at > 0) short.format(java.util.Date(r.at)) else r.timeLabel()
            val sub = when {
                !r.enabled -> "Off" + " \u00b7 " + (if (r.isCommand) "$ " + r.command else r.prompt)
                else -> "Next " + fmt.format(java.util.Date(runCatching { Routines.next(r, at) }.getOrDefault(at))) +
                    (if (r.at == 0L && r.everyMinutes == 0) " \u00b7 " + r.daysLabel() else "")
            }
            KitRow(r.name, sub, n = i + 1, first = i == 0, pill = whenTxt, pillOn = r.enabled, dim = !r.enabled,
                onClick = { open = if (open == r.id) -1 else r.id }) {
                KitSwitch(r.enabled) { if (list0 == null) Routines.setEnabled(r.id, !r.enabled) }
            }
            if (open == r.id) Column(Modifier.fillMaxWidth().glassInset(c).hairlineTop(c.hair).padding(start = 46.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
                Label(if (r.isCommand) "Shell job" + (if (r.linux) " \u00b7 Linux" else "") else "Blaze does", size = 9.sp, color = c.weak)
                Text(if (r.isCommand) "$ " + r.command else r.prompt, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    style = TextStyle(fontFamily = if (r.isCommand) Type.Mono else Type.Sans, fontSize = 12.5.sp, lineHeight = 18.sp, color = c.body))
                KitButton("Remove", Btn.LINE) { if (list0 == null) Routines.delete(r.id); open = -1 }
            }
        }
    }
}
