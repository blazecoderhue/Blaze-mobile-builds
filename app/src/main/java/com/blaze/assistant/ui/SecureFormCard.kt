package com.blaze.assistant.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.browser.SecureInput
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border

/** The live card: whatever form the background browser is waiting on. */
@Composable
fun SecureFormCard() {
    val p by Agent.pendingForm.collectAsState()
    val req = p ?: return
    val ctx = LocalContext.current
    SecureForm(req.site, req.title, req.reason, req.fields,
        onSubmit = { values, save -> Agent.answerForm(SecureInput.Answer(values, save)) },
        onCancel = { Agent.answerForm(null) },
        onWatch = { com.blaze.assistant.browser.BrowserTools.show(ctx) })
}

/**
 * "Action required" form in the chat (v0.20.0). Typed values go from here straight into the page;
 * the model only gets placeholders. Passwords and keys are masked with a show/hide toggle.
 */
@Composable
fun SecureForm(site: String, title: String, reason: String, fields: List<SecureInput.Field>,
               onSubmit: (Map<String, String>, Boolean) -> Unit, onCancel: () -> Unit, onWatch: () -> Unit,
               preset: Map<String, String> = emptyMap(), notifPrompt: Boolean? = null) {
    val c = LocalBlaze.current
    val values = remember(fields) { mutableStateMapOf<String, String>().apply { putAll(preset) } }
    val shown = remember(fields) { mutableStateMapOf<String, Boolean>() }
    // v0.20.0 (Blazeinchue): saved by default (one-time codes never are); untick to use once.
    var save by remember(fields) { mutableStateOf(true) }
    val ready = fields.all { values[it.name].orEmpty().isNotBlank() }
    // v0.24.8: a rounded card (accent outline) instead of a square strip with a top rule.
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).glass(BlazeShapes.large, GlassIntensity.High).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(BlazeShapes.pill).background(c.accent))
            Spacer(Modifier.width(8.dp))
            Label("Action required · ${site.take(36)}", color = c.accent, size = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
            ActionText("Watch", color = c.sub, size = 10.sp, modifier = Modifier.clickable(onClick = onWatch).padding(horizontal = 6.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp, color = c.ink))
        if (reason.isNotBlank()) Text(reason, modifier = Modifier.padding(top = 4.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
        Spacer(Modifier.height(10.dp))
        fields.forEachIndexed { i, f ->
            val masked = f.kind.masked && shown[f.name] != true
            Label(f.label, size = 10.sp, modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp, bottom = 4.dp))
            Row(Modifier.fillMaxWidth().clip(BlazeShapes.pill).background(c.field, BlazeShapes.pill).hairline(c.fieldLine, BlazeShapes.pill).padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (values[f.name].isNullOrEmpty()) Text(hint(f.kind), style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
                    BasicTextField(values[f.name].orEmpty(), { values[f.name] = it.trimEnd('\n') }, singleLine = true,
                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink),
                        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboard(f.kind),
                            imeAction = if (i == fields.lastIndex) ImeAction.Done else ImeAction.Next),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
                if (f.kind.masked) ActionText(if (masked) "Show" else "Hide", color = c.sub, size = 10.sp,
                    modifier = Modifier.clickable { shown[f.name] = masked }.padding(horizontal = 12.dp, vertical = 12.dp))
            }
        }
        if (fields.any { it.kind.saveable }) Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(BlazeShapes.small).clickable { save = !save }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).hairline(if (save) c.accent else c.hair, BlazeShapes.xs).background(if (save) c.accent else c.paper), contentAlignment = Alignment.Center) {
                if (save) Text("✓", style = TextStyle(fontSize = 11.sp, color = c.paper))
            }
            Spacer(Modifier.width(10.dp))
            Text("Save to Blaze's vault for $site (encrypted on this phone)", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body))
        }
        Text("Goes straight into the page. Blaze's AI never sees it.", modifier = Modifier.padding(top = 8.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        NotificationsLine(notifPrompt)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BlazeButton("Cancel", modifier = Modifier.weight(1f), kind = BtnKind.SECONDARY, height = 46.dp, onClick = onCancel)
            BlazeButton("Fill in", modifier = Modifier.weight(1f), enabled = ready, height = 46.dp) { onSubmit(fields.associate { it.name to values[it.name].orEmpty() }, save) }
        }
    }
}

/** Android 13+: without notification permission Blaze can't say "Action required" from the background. Offer it once here. */
@Composable
private fun NotificationsLine(force: Boolean?) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val sdk = android.os.Build.VERSION.SDK_INT
    var granted by remember { mutableStateOf(sdk < 33 || androidx.core.content.ContextCompat.checkSelfPermission(ctx, "android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val ask = if (force != null) force else !granted
    if (!ask) return
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted = it }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Allow notifications so Blaze can tell you when it needs you.", modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        ActionText("Allow", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
            if (sdk >= 33) try { launcher.launch("android.permission.POST_NOTIFICATIONS") } catch (_: Exception) { }
        }.padding(8.dp))
    }
}

private fun hint(k: SecureInput.Kind) = when (k) {
    SecureInput.Kind.EMAIL -> "you@example.com"
    SecureInput.Kind.PHONE -> "+260…"
    SecureInput.Kind.OTP -> "6-digit code"
    SecureInput.Kind.PASSWORD -> "Password"
    SecureInput.Kind.API_KEY -> "Paste the key"
    else -> "Type here"
}

private fun keyboard(k: SecureInput.Kind) = when (k) {
    SecureInput.Kind.EMAIL -> KeyboardType.Email
    SecureInput.Kind.PHONE -> KeyboardType.Phone
    SecureInput.Kind.PASSWORD, SecureInput.Kind.API_KEY -> KeyboardType.Password
    SecureInput.Kind.OTP, SecureInput.Kind.NUMBER -> KeyboardType.Number
    else -> KeyboardType.Text
}
