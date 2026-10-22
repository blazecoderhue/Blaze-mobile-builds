package com.blaze.assistant.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.SkillLibrary

/**
 * Settings › Skill library (v0.19.0 look, Desktop's Settings › Skills): bundled skills (from Blaze Desktop)
 * and your own, grouped, searchable, a switch each; tap one for its description, Delete and Restore.
 * [rows0] and [open0] are for screenshots.
 */
@Composable
fun SkillLibraryPage(rows0: List<SkillLibrary.Row>? = null, open0: String = "", query0: String = "") {
    val c = LocalBlaze.current.mono
    var tick by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(open0) }
    var query by remember { mutableStateOf(query0) }
    val local = rows0
    val rows = remember(tick, local) { local ?: runCatching { SkillLibrary.rows() }.getOrDefault(emptyList()) }
    fun change(f: () -> Unit) { if (local != null) return; runCatching(f); tick++ }

    PageHead("Skills", "Skill library", "Methods Blaze reads before a matching task: research, emails, plans, documents, data, code and more. " +
        "Bundled skills update themselves unless you edit them.")
    if (rows.isEmpty()) {
        Rows { EmptyState("No skills yet", "After a task, say \u201csave this as a skill\u201d and Blaze keeps the steps for next time.") }
        return
    }
    val on = rows.count { it.installed && it.on }
    KitSearch(query, { query = it }, "Search ${rows.size} skills")
    Label("${rows.count { it.installed }} installed \u00b7 $on on" + (rows.count { it.update }.takeIf { it > 0 }?.let { " \u00b7 $it to update" } ?: ""),
        size = 9.sp, color = c.weak, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    val q = query.trim()
    val shown = rows.filter { q.isEmpty() || it.name.contains(q, true) || it.description.contains(q, true) }
    if (shown.isEmpty()) { Rows { EmptyState("No skill matches \u201c$q\u201d", "Try a word from what the skill does, like \u201cemail\u201d or \u201cplan\u201d.", mark = false) }; return }
    val groups = listOf(
        Triple("Core", "Always on: how Blaze talks, works, codes, uses views and specialists, and saves workflows. Can't be switched off.", shown.filter { it.installed && it.core }),
        Triple("Yours", "Skills you or Blaze saved on this phone.", shown.filter { it.installed && !it.bundled }),
        Triple("Bundled", "Shipped with Blaze, the same SKILL.md files as Blaze Desktop.", shown.filter { it.installed && it.bundled && !it.core }),
        Triple("Deleted", "Bundled skills you removed. Restore brings one back.", shown.filter { !it.installed }))
    var n = 0
    groups.forEach { (title, desc, list) ->
        if (list.isEmpty()) return@forEach
        Spacer(Modifier.height(10.dp))
        GroupHead("$title \u00b7 %02d".format(list.size), desc)
        Rows {
            list.forEachIndexed { i, r ->
                n++
                val tag = when { r.update -> "update"; r.core -> "core"; r.edited -> "edited"; else -> "" }
                KitRow(r.name, r.description.lineSequence().firstOrNull().orEmpty(), n = n, first = i == 0, pill = tag, pillOn = r.update,
                    dim = !r.installed || !r.on, mono = true, onClick = { open = if (open == r.name) "" else r.name }) {
                    if (r.installed && !r.core) KitSwitch(r.on) { change { SkillLibrary.setOn(r.name, !r.on) } }
                }
                if (open == r.name) Column(Modifier.fillMaxWidth().glassInset(c).hairlineTop(c.hair).padding(start = 46.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
                    if (r.description.isNotBlank() && r.description.trim() != r.description.lineSequence().firstOrNull().orEmpty().trim()) Text(r.description, modifier = Modifier.padding(bottom = 10.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 18.sp, color = c.body))
                    Label(when { r.core -> if (r.edited) "Core \u00b7 always on \u00b7 edited by you" else "Core \u00b7 always on"; !r.installed -> "Deleted \u00b7 bundled"; r.edited -> "Bundled \u00b7 edited by you"; r.bundled -> "Bundled"; else -> "Yours" },
                        size = 9.sp, color = c.weak, modifier = Modifier.padding(bottom = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r.bundled && (!r.installed || r.edited || r.update)) KitButton(if (r.installed) "Restore original" else "Restore", Btn.LINE) {
                            change { SkillLibrary.restore(r.name) } }
                        if (r.installed && !r.core) KitButton("Delete", Btn.GHOST) { change { SkillLibrary.delete(r.name) }; open = "" }
                    }
                }
            }
        }
    }
}
