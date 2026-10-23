package com.blaze.assistant.browser.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.blaze.assistant.ui.BlazeTheme
import com.blaze.assistant.ui.Glass
import com.blaze.assistant.ui.GlassIconButton
import com.blaze.assistant.ui.PhoneFrame
import com.blaze.assistant.ui.StatusPill
import com.blaze.assistant.ui.Type

/**
 * 0.45.19 Blaze Phone full screen. Watching: Blaze's screen, live, with the step it's on and "Take control". In control:
 * the real page (the same WebView, moved here, fully interactive with the keyboard), a clear "Blaze paused, you're in
 * control" bar and "Hand back" (an agent is waiting: it resumes with a fresh read) or "Done" (the user browsed on their
 * own: the session is saved and everything is freed). Leaving the screen while in control hands back too.
 */
class PhoneBrowserActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CONTROL = "control"
        fun open(ctx: Context, control: Boolean) {
            ctx.startActivity(Intent(ctx, PhoneBrowserActivity::class.java).putExtra(EXTRA_CONTROL, control)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private val control = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneBrowser.init(this)
        // Opened by the user with nothing running: their own Blaze Phone session, in control from the start.
        if (!PhoneBrowser.hasPage()) { PhoneBrowser.openForUser(this); take() }
        else if (intent?.getBooleanExtra(EXTRA_CONTROL, false) == true) take()
        else control.value = PhoneBrowser.live.value.mode == PhonePolicy.Mode.USER
        setContent { BlazeTheme { PhoneScreen(control.value, onTake = { take() }, onHandBack = { handBack() }, onExit = { finish() }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CONTROL, false)) take()
    }

    private fun take() { if (PhoneBrowser.takeControl(this) != null) control.value = true }

    private fun handBack() {
        val agentWaiting = PhoneBrowser.live.value.agentWaiting
        control.value = false
        PhoneBrowser.handBackNow()
        if (!agentWaiting) finish()
    }

    override fun onDestroy() {
        // Leaving while in control hands back: the WebView never stays in a dead window.
        if (control.value && PhoneBrowser.live.value.mode == PhonePolicy.Mode.USER) { control.value = false; PhoneBrowser.handBackNow() }
        super.onDestroy()
    }
}

@Composable
private fun PhoneScreen(control: Boolean, onTake: () -> Unit, onHandBack: () -> Unit, onExit: () -> Unit) {
    val live by PhoneBrowser.live.collectAsState()
    val gen by PhoneBrowser.webGen.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    BackHandler { if (control) onHandBack() else onExit() }
    val host = live.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
    Column(Modifier.fillMaxSize().background(Glass.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        // Top: back, title + site, menu.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Rounded.ArrowBackIosNew, if (control) "Hand back and close" else "Close", size = 36.dp) { if (control) onHandBack() else onExit() }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Blaze Phone", maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Glass.text))
                Text(host.ifBlank { "No page" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = Glass.sub))
            }
            Box {
                GlassIconButton(Icons.Rounded.MoreVert, "Blaze Phone menu", size = 36.dp) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    if (host.isNotBlank()) DropdownMenuItem(text = { Text("Sign out of ${PhonePolicy.siteOf(live.url).ifBlank { host }}") }, onClick = {
                        menu = false; val s = PhonePolicy.siteOf(live.url); PhoneBrowser.signOut(s); note = "Signed out of $s on Blaze Phone."; onExit() })
                    DropdownMenuItem(text = { Text("Clear Blaze Phone data") }, onClick = {
                        menu = false; val n = PhoneBrowser.clearAll(); note = "Cleared Blaze Phone data ($n sessions)."; onExit() })
                }
            }
        }
        if (control) AddressBar(live.url)
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.White)) {
            if (control) {
                AndroidView(factory = { c -> FrameLayout(c) }, update = { f ->
                    if (gen >= 0) PhoneBrowser.webView()?.let { w ->
                        if (w.parent !== f) {
                            (w.parent as? ViewGroup)?.removeView(w)
                            f.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            w.requestFocus()
                        }
                    }
                }, modifier = Modifier.fillMaxSize())
            } else {
                DisposableEffect(Unit) { PhoneBrowser.watch(true); onDispose { PhoneBrowser.watch(false) } }
                PhoneFrame(live.frameNo, Modifier.fillMaxSize().padding(0.dp))
                if (!live.surfaceOk && live.mode != PhonePolicy.Mode.OFF) Text("Live view isn't available on this phone. Take control to see the page.",
                    Modifier.align(Alignment.Center).padding(24.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Color(0xFF555555)))
            }
        }
        // Bottom: who is driving, and the one button that changes it.
        Row(Modifier.fillMaxWidth().background(Glass.surface).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                when {
                    control -> Text(if (live.agentWaiting) "Blaze paused, you're in control" else "You're in control",
                        maxLines = 2, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Glass.text))
                    live.mode == PhonePolicy.Mode.WAITING -> StatusPill("Blaze needs you: ${live.reason ?: "take control"}", working = false)
                    else -> StatusPill(live.step.ifBlank { if (live.mode == PhonePolicy.Mode.OFF) "Idle" else "Browsing" }, working = live.mode == PhonePolicy.Mode.AGENT)
                }
            }
            Spacer(Modifier.width(10.dp))
            val label = if (control) (if (live.agentWaiting) "Hand back" else "Done") else "Take control"
            Text(label, maxLines = 1, modifier = Modifier.clip(Glass.pill).background(Color.White).clickable(onClickLabel = label) { if (control) onHandBack() else onTake() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.Black))
        }
        note?.let { Text(it, Modifier.padding(8.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = Glass.sub)) }
    }
}


@Composable
private fun AddressBar(url: String) {
    var text by remember(url) { mutableStateOf(url) }
    BasicTextField(text, { text = it }, singleLine = true,
        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Glass.text), cursorBrush = SolidColor(Glass.text),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { PhoneBrowser.userLoad(text) }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Glass.raised, Glass.pill).padding(horizontal = 14.dp, vertical = 9.dp))
}
