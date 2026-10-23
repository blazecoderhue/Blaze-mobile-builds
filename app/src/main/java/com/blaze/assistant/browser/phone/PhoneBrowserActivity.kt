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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.blaze.assistant.ui.BackRule
import com.blaze.assistant.ui.BlazeTheme
import com.blaze.assistant.ui.phonePalette
import com.blaze.assistant.ui.Glass
import com.blaze.assistant.ui.GlassIconButton
import com.blaze.assistant.ui.PhoneFrame
import com.blaze.assistant.ui.StatusPill
import com.blaze.assistant.ui.Type

/**
 * 0.45.19 Blaze Phone full screen. Watching: Blaze's screen, live, with the step it's on and "Take control". In control:
 * the real page (the same WebView, moved here, fully interactive with the keyboard), a clear "Blaze paused, you're in
 * control" bar and only "Hand back" (fix 6: the old Done button is gone; closing the screen hands back too). Back goes to the chat
 * (fix 7, BackRule: handing back first when the user has control). Leaving Blaze while Blaze works turns this screen
 * into picture-in-picture showing only the live view (fix 4).
 */
class PhoneBrowserActivity : ComponentActivity(), BackRule.ToChat {
    companion object {
        const val EXTRA_CONTROL = "control"
        fun open(ctx: Context, control: Boolean) {
            ctx.startActivity(Intent(ctx, PhoneBrowserActivity::class.java).putExtra(EXTRA_CONTROL, control)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private val control = mutableStateOf(false)
    private val pip = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneBrowser.init(this)
        // Opened by the user with nothing running: their own Blaze Phone session, in control from the start.
        if (!PhoneBrowser.hasPage()) { PhoneBrowser.openForUser(this); take() }
        else if (intent?.getBooleanExtra(EXTRA_CONTROL, false) == true) take()
        else if (PhoneBrowser.live.value.mode == PhonePolicy.Mode.USER) take()   // re-host the WebView in this window
        setContent { BlazeTheme {
            if (pip.value) PhonePipView()
            else PhoneScreen(control.value, onTake = { take() }, onHandBack = { handBack() }, onExit = { backToChat() })
        } }
        lifecycleScope.launch {
            combine(PhoneBrowser.live, com.blaze.assistant.agent.Agent.busyChats) { l, b -> PhonePip.working(l.mode, PhoneBrowser.ownerChat, b) && !control.value }
                .distinctUntilChanged().collect { w -> PhonePipHost.update(this@PhoneBrowserActivity, w, locked = false) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CONTROL, false)) take()
    }

    private fun take() {
        if (PhoneBrowser.takeControl(this) != null) {
            control.value = true
            PhonePipHost.update(this, working = false, locked = false)   // the user drives: no picture-in-picture
        }
    }

    /** Hand back: Blaze resumes (an agent was waiting) and the screen keeps watching; with nobody waiting it closes. */
    private fun handBack() {
        val agentWaiting = PhoneBrowser.live.value.agentWaiting
        control.value = false
        PhoneBrowser.handBackNow()
        if (!agentWaiting) finishToChat()
    }

    /** Back / the top-left arrow / closing: hand back if the user has control, then the chat (BackRule). */
    override fun backToChat() {
        if (control.value) { control.value = false; PhoneBrowser.handBackNow() }
        finishToChat()
    }

    private fun finishToChat() = com.blaze.assistant.mediaapps.BackToChat.go(this)

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!control.value) PhonePipHost.enterOnLeave(this, locked = false, launchingOwnScreen = false)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.value = isInPictureInPictureMode
    }

    override fun onDestroy() {
        // Leaving while in control hands back: the WebView never stays in a dead window (or keeps this activity alive).
        if (control.value && PhoneBrowser.live.value.mode == PhonePolicy.Mode.USER) { control.value = false; PhoneBrowser.handBackNow() }
        else PhoneBrowser.forget(this)   // fix 9: never leave the WebView's dialogs pointing at a destroyed activity
        super.onDestroy()
    }
}

@Composable
private fun PhoneScreen(control: Boolean, onTake: () -> Unit, onHandBack: () -> Unit, onExit: () -> Unit) {
    val live by PhoneBrowser.live.collectAsState()
    val gen by PhoneBrowser.webGen.collectAsState()
    val ctx = LocalContext.current
    val pal = phonePalette()
    var menu by remember { mutableStateOf(false) }
    // Fallback only: BackRule's window interceptor takes Back before this (same action).
    BackHandler { onExit() }
    val host = PhoneUiRules.host(live.url)
    Column(Modifier.fillMaxSize().background(Color(pal.screen)).statusBarsPadding().navigationBarsPadding().imePadding()) {
        // Top: back, title + site, menu.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Rounded.ArrowBackIosNew, if (control) "Hand back and close" else "Close", size = 36.dp,
                tint = Color(pal.onButton), bg = Color(pal.button)) { onExit() }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Blaze Phone", maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color(pal.screenText)))
                Text(host.ifBlank { "No page" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = Color(pal.screenSub)))
            }
            Box {
                GlassIconButton(Icons.Rounded.MoreVert, "Blaze Phone menu", size = 36.dp, tint = Color(pal.onButton), bg = Color(pal.button)) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    if (host.isNotBlank()) DropdownMenuItem(text = { Text("Sign out of ${PhonePolicy.siteOf(live.url).ifBlank { host }}") }, onClick = {
                        menu = false; val s = PhonePolicy.siteOf(live.url); PhoneBrowser.signOut(s)
                        // fix 9: the note used to be set on a screen that closed in the same tap (never seen); a toast survives.
                        Toast.makeText(ctx, "Signed out of $s on Blaze Phone.", Toast.LENGTH_SHORT).show(); onExit() })
                    DropdownMenuItem(text = { Text("Clear Blaze Phone data") }, onClick = {
                        menu = false; val n = PhoneBrowser.clearAll()
                        Toast.makeText(ctx, "Cleared Blaze Phone data ($n sessions).", Toast.LENGTH_SHORT).show(); onExit() })
                }
            }
        }
        if (control) AddressBar(live.url, pal)
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(pal.frame))) {
            if (control) {
                AndroidView(factory = { c -> FrameLayout(c) }, update = { f ->
                    if (gen >= 0) PhoneBrowser.webView()?.let { w ->
                        if (w.parent !== f) {
                            (w.parent as? ViewGroup)?.removeView(w)
                            f.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            w.requestFocus()
                        }
                    }
                }, onRelease = { f -> f.removeAllViews() }, modifier = Modifier.fillMaxSize())
            } else {
                DisposableEffect(Unit) { PhoneBrowser.watch(true); onDispose { PhoneBrowser.watch(false) } }
                PhoneFrame(live.frameNo, Modifier.fillMaxSize().padding(0.dp), pal)
                if (!live.surfaceOk && live.mode != PhonePolicy.Mode.OFF) Text("Live view isn't available on this phone. Take control to see the page.",
                    Modifier.align(Alignment.Center).padding(24.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Color(pal.frameText)))
            }
        }
        // Bottom: who is driving, and the one button that changes it (in control: only Hand back).
        Row(Modifier.fillMaxWidth().background(Color(pal.bar)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                when {
                    control -> Text(PhoneUiRules.controlLine(live.agentWaiting),
                        maxLines = 2, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Color(pal.barText)))
                    else -> StatusPill(PhoneUiRules.pill(live.mode, live.step, live.reason), working = live.mode == PhonePolicy.Mode.AGENT,
                        chip = Color(pal.chip), onChip = Color(pal.onChip))
                }
            }
            for (label in PhoneUiRules.buttons(control)) {
                Spacer(Modifier.width(10.dp))
                Text(label, maxLines = 1, modifier = Modifier.clip(Glass.pill).background(Color(pal.strong))
                    .clickable(onClickLabel = label) { if (control) onHandBack() else onTake() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(pal.onStrong)))
            }
        }
    }
}


@Composable
private fun AddressBar(url: String, pal: com.blaze.assistant.ui.PhoneColors.Palette) {
    var text by remember(url) { mutableStateOf(url) }
    BasicTextField(text, { text = it }, singleLine = true,
        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Color(pal.fieldText)), cursorBrush = SolidColor(Color(pal.fieldText)),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { PhoneBrowser.userLoad(text) }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Color(pal.field), Glass.pill).padding(horizontal = 14.dp, vertical = 9.dp))
}
