package com.blaze.assistant.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.FreeRouter
import com.blaze.assistant.agent.ModelMemory
import com.blaze.assistant.agent.ProviderCatalog

/** One model line when a provider is opened: id, tools tag, and its state. */
data class PoolModel(val id: String, val tools: Boolean, val state: String, val cooling: Boolean)

/** "12 models · 3 cooling · 9 with tools" (and when the next one frees up). */
fun poolLine(s: FreeRouter.PoolSummary): String {
    val parts = mutableListOf("${s.models} model" + if (s.models == 1) "" else "s")
    parts += if (s.cooling > 0) "${s.cooling} cooling" else "none cooling"
    if (s.pid != "pollinations") parts += "${s.toolModels} with tools"
    if (s.cooling > 0 && s.cooling == s.models && s.nextFreeMs > 0) parts += "next free in ${FreeRouter.fmt(s.nextFreeMs)}"
    if (s.fetchedAt == 0L) parts += "not listed yet" else if (s.error.isNotBlank()) parts += "list from cache"
    return parts.joinToString(" · ")
}

/** The models of one provider with their state, for the expanded row. */
fun poolModels(pid: String, now: Long = System.currentTimeMillis()): List<PoolModel> {
    val pool = (try { FreeRouter.pools() } catch (_: Exception) { emptyList() }).firstOrNull { it.first == pid }?.second
        ?: ProviderCatalog.models(pid).map { com.blaze.assistant.agent.RoutePlan.Cand(pid, it.id, it.tools) }
    return pool.map { c ->
        val m = ModelMemory.mark(pid, c.model)
        val until = ModelMemory.coolingUntil(pid, c.model)
        val state = when {
            until > now -> (if (m.state == ModelMemory.State.LIMITED) "limited" else "failed") + " · back in " + FreeRouter.fmt(until - now) +
                (if (m.reason.isNotBlank()) " · " + m.reason.take(40) else "")
            ModelMemory.demoted(pid, c.model, now) -> "failed earlier · tried last"
            m.okAt > 0 -> "worked"
            else -> "untried"
        }
        PoolModel(c.model, c.tools && !ModelMemory.noTools(pid, c.model, now), state, until > now)
    }
}

/**
 * Settings › Brains / Auto: per provider, how many models Auto can use and how many are cooling,
 * with "Refresh models". Tap a provider to see its models. Data comes in as parameters so the
 * screenshot test can draw it without keys or network.
 */
@Composable
fun AutoPoolPanel(rows: List<FreeRouter.PoolSummary>, refreshing: Boolean, onRefresh: () -> Unit,
                  models: (String) -> List<PoolModel> = { poolModels(it) }, initiallyOpen: String = "") {
    val c = LocalBlaze.current
    var open by remember { mutableStateOf(initiallyOpen) }
    GroupHead("Models Auto can use", "Every chat model each provider offers takes part. Providers take turns; a model that fails or hits a limit rests until its cooldown passes.") {
        KitButton(if (refreshing) "Refreshing…" else "Refresh models", Btn.LINE, enabled = !refreshing, onClick = onRefresh)
    }
    if (rows.isEmpty()) { EmptyState("No providers yet", "Add a free key (Gemini, Groq, OpenRouter, NVIDIA…) and its models show up here.", mark = false); return }
    Rows {
        rows.forEachIndexed { i, s ->
            val allCool = s.models > 0 && s.cooling == s.models
            KitRow(s.name, poolLine(s), first = i == 0,
                pill = when { allCool -> "resting"; s.cooling > 0 -> "${s.models - s.cooling} ready"; else -> "ready" }, pillOn = !allCool,
                onClick = { open = if (open == s.pid) "" else s.pid },
                lead = { Box(Modifier.size(6.dp).background(when { s.models == 0 -> c.weak; allCool -> c.sub; else -> c.accent }, BlazeShapes.pill)) },
                trailing = { Label(if (open == s.pid) "\u25B2" else "\u25BC", size = 10.sp) })
            if (open == s.pid) {
                val list = remember(s.pid, s.cooling, s.fetchedAt) { models(s.pid) }
                Column(Modifier.fillMaxWidth().clip(BlazeShapes.small).background(c.faint).padding(start = 32.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)) {
                    list.take(60).forEach { m ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(m.id + if (m.tools) "  · tools" else "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Type.Mono, fontSize = 11.5.sp, color = if (m.cooling) c.weak else c.ink))
                            Text(m.state, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = if (m.cooling) c.accent else c.sub))
                        }
                    }
                    if (list.size > 60) Text("…and ${list.size - 60} more", style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.sub))
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}
