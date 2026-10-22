package com.blaze.assistant.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.browser.SecureInput
import com.blaze.assistant.browser.Vault

/**
 * Settings › Saved logins (v0.20.0): what the secure form saved, per site. Values are shown masked,
 * can be changed or deleted, and never go to the model. One-time codes are never saved.
 */
@Composable
fun SavedLoginsPage() {
    val c = LocalBlaze.current.mono
    var tick by remember { mutableIntStateOf(0) }
    val sites = remember(tick) { Vault.sites().filter { it.second.isNotEmpty() }.sortedBy { it.first } }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var draft by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }   // v0.45.7: eye on the password being edited
    Spacer(Modifier.height(10.dp))
    Text("Sign-ins you typed into Blaze's secure form. Blaze fills them in on the same site next time without asking. " +
        "They're encrypted with this phone's Keystore and never shown to the AI.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
    Spacer(Modifier.height(14.dp))
    if (sites.isEmpty()) {
        Text("Nothing saved yet. When a site asks Blaze for your email, password, phone or an API key, it's saved here.",
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        return
    }
    sites.forEach { (host, names) ->
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).hairline(c.hair)) {
            Row(Modifier.fillMaxWidth().glassInset(c).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(host, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = c.ink))
                ActionText("Delete", color = c.accent, size = 10.sp, modifier = Modifier.clickable { Vault.forget(host); tick++ }.padding(6.dp))
            }
            names.forEach { name ->
                val kind = SecureInput.kindOf(name)
                val isEditing = editing == host to name
                Row(Modifier.fillMaxWidth().hairlineTop(c.hair).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Label(name.replace('_', ' '), size = 10.sp)
                        if (!isEditing) Text(Vault.get(host, name)?.let { Vault.masked(name, it) } ?: "(can't read on this phone)",
                            style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink))
                        else BasicTextField(draft, { draft = it }, singleLine = true,
                            visualTransformation = if (SecretField.masked(kind.masked, reveal)) PasswordVisualTransformation() else VisualTransformation.None,
                            textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink), cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    if (isEditing) {
                        if (kind.masked) RevealEye(reveal) { reveal = !reveal }
                        ActionText("Save", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
                            if (draft.isNotEmpty()) Vault.save(host, name, draft); draft = ""; editing = null; tick++ }.padding(10.dp))
                        ActionText("Cancel", size = 10.sp, modifier = Modifier.clickable { draft = ""; editing = null }.padding(10.dp))
                    } else {
                        ActionText("Edit", color = c.accent, size = 10.sp, modifier = Modifier.clickable { draft = ""; reveal = false; editing = host to name }.padding(10.dp))
                        ActionText("×", size = 12.sp, modifier = Modifier.clickable { Vault.remove(host, name); tick++ }.padding(10.dp))
                    }
                }
            }
        }
    }
    Box(Modifier.height(4.dp).width(1.dp))
}
