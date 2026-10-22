package com.blaze.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.HaloStudio

/** v0.33.0 Halo Persona Studio (Settings › Personality): sliders, language mix, boundaries, saved personas. Theme-aware via mono colours. */
@Composable
internal fun HaloStudioSection() {
    val c = LocalBlaze.current.mono
    var p by remember { mutableStateOf(HaloStudio.active().let { if (it.name == "Halo") it.copy(name = "My Halo") else it }) }
    var saved by remember { mutableStateOf(HaloStudio.all().map { it.name }) }
    var active by remember { mutableStateOf(HaloStudio.active().name) }
    var note by remember { mutableStateOf("") }
    V18Section("Halo Persona Studio")
    V18Note("Tune how Halo talks. Active: $active")
    @Composable fun slider(label: String, v: Int, lo: String, hi: String, set: (Int) -> Unit) = Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.ink))
            Text("$lo \u2194 $hi", style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.sub))
        }
        Slider(value = v.toFloat(), onValueChange = { set(it.toInt()) }, valueRange = 0f..10f, steps = 9,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.hair,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent), modifier = Modifier.fillMaxWidth().height(30.dp))
    }
    slider("Warmth", p.warmth, "cool", "affectionate") { p = p.copy(warmth = it) }
    slider("Humour", p.humour, "serious", "funny") { p = p.copy(humour = it) }
    slider("Energy", p.play, "calm", "bubbly") { p = p.copy(play = it) }
    slider("Formality", p.formality, "casual", "formal") { p = p.copy(formality = it) }
    slider("Length", p.length, "short", "detailed") { p = p.copy(length = it) }
    listOf("Bemba", "Nyanja").forEach { lang ->
        slider("$lang mix", (p.langs[lang] ?: 0) / 10, "none", "a lot") { v -> p = p.copy(langs = (p.langs + (lang to v * 10)).filterValues { it > 0 }) }
    }
    V18Field("Boundaries (e.g. never swear, don't call me babe)", p.boundaries) { p = p.copy(boundaries = it) }
    V18Field("Persona name", p.name) { p = p.copy(name = it.take(30)) }
    V18Button("Save and use") { HaloStudio.save(p.copy(name = p.name.ifBlank { "My Halo" })); saved = HaloStudio.all().map { it.name }; active = HaloStudio.active().name; note = "Halo is now ${active}." }
    if (saved.isNotEmpty()) {
        V18Section("Saved personas")
        saved.forEach { n -> ToggleRow(n, if (n == active) "In use" else "Tap to switch", n == active) {
            if (n == active) HaloStudio.reset() else HaloStudio.switch(n); active = HaloStudio.active().name
            HaloStudio.all().firstOrNull { it.name == n }?.let { if (active == n) p = it } } }
        V18Button("Back to default Halo", primary = false) { HaloStudio.reset(); active = HaloStudio.active().name }
    }
    if (note.isNotBlank()) V18Note(note, top = 6)
}
