package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Brand
import com.blaze.assistant.agent.Seller
import androidx.compose.ui.draw.clip

/**
 * Seller (marketer pack, v0.17.0): the brand kit every post, ad and listing follows, the weekly store
 * digest job, and how WhatsApp replies drafted on the PC reach customers (always with your tap).
 */
@Composable
internal fun SellerPage(digest0: Boolean? = null) {
    val c = LocalBlaze.current.mono
    val b0 = remember { Seller.brand() }
    var name by remember { mutableStateOf(b0.name) }
    var colors by remember { mutableStateOf((listOf(b0.paper, b0.ink) + b0.accents).filter { it.isNotBlank() }.joinToString(", ")) }
    var fonts by remember { mutableStateOf(b0.fonts.joinToString(", ")) }
    var notes by remember { mutableStateOf(b0.notes) }
    var saved by remember { mutableStateOf("") }
    var digest by remember { mutableStateOf(digest0 ?: runCatching { Seller.digestOn() }.getOrDefault(false)) }

    Spacer(Modifier.height(18.dp))
    Text("Run and grow your store from here: posts for the week, product listings from a supplier link, ad variants, competitor briefs and replies to customers. Everything follows your brand kit.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 21.sp, color = c.body))
    Spacer(Modifier.height(18.dp))
    Label("Brand kit", modifier = Modifier.padding(bottom = 10.dp))
    val swatches = colors.split(',').map { Brand.color(it) }.filter { it.startsWith("#") }
    if (swatches.isNotEmpty()) Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swatches.take(6).forEach { h ->
            val col = runCatching { Color(android.graphics.Color.parseColor(h)) }.getOrNull()
            if (col != null) Box(Modifier.size(28.dp).background(col, BlazeShapes.medium).hairline(c.hair))
        }
    }
    Field("Store or brand name", name, { name = it; saved = "" }, "HomeGlow")
    Field("Colours (background, text, accents)", colors, { colors = it; saved = "" }, "#fffaf2, #1d1a16, #e8743b")
    Field("Fonts (headings, body)", fonts, { fonts = it; saved = "" }, "Playfair Display, Inter")
    Field("Voice and audience", notes, { notes = it; saved = "" }, "Warm, cosy home decor for young renters")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.height(44.dp).clip(BlazeShapes.pill).glassButton(c).clickable {
            val cs = colors.split(',').map { it.trim() }.filter { it.isNotBlank() }
            Seller.saveBrand(Seller.brand().copy(name = name.trim(), paper = cs.getOrElse(0) { "" }, ink = cs.getOrElse(1) { "" }, accents = cs.drop(2),
                fonts = fonts.split(',').map { it.trim() }.filter { it.isNotBlank() }, notes = notes.trim()))
            saved = "Saved"
        }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) { ButtonText("Save brand kit", color = Color.White) }
        if (saved.isNotBlank()) Label(saved, color = c.accent, size = 9.sp, modifier = Modifier.padding(start = 12.dp))
    }
    Text("Or ask Blaze: \u201cread my brand from homeglow.store\u201d. Kept in ~/.blaze/brand.json, the same shape as Blaze Desktop's.",
        modifier = Modifier.padding(top = 8.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.weak))
    Spacer(Modifier.height(18.dp))
    Label("Jobs", modifier = Modifier.padding(bottom = 4.dp))
    ToggleRow("Weekly store digest", "Every Monday at 08:00: last week's orders (from your store's order emails) and top posts, with the next 3 posts to make", digest) {
        digest = !digest; Seller.setDigest(digest) }
    Spacer(Modifier.height(8.dp))
    Label("Customer replies from your PC", modifier = Modifier.padding(bottom = 6.dp))
    Text("Blaze Desktop can draft WhatsApp replies and send them through this phone over Blaze Link. Every one shows a card here first (even in Autopilot) and only goes when you tap Send.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    Text("Skills: content-calendar, product-listing, ad-lab, research-brief, whatsapp-replies, weekly-digest (Settings \u203a Skill library).",
        modifier = Modifier.padding(top = 14.dp, bottom = 18.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.weak))
}
