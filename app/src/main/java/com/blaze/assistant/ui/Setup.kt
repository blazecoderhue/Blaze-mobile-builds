package com.blaze.assistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blaze.assistant.agent.Brains
import com.blaze.assistant.agent.Providers
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.phone.BlazeAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag

/**
 * Watches for a system toggle the user was sent to flip, and brings Blaze back to the front the
 * moment it's on, so nobody has to find their way back through Android settings.
 */
object ComeBack {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** On Wi-Fi, quietly fetch the small Linux (≈3.5 MB) so the first beat or script starts at once. */
    fun prefetch(ctx: Context) {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        if (cm.isActiveNetworkMetered) return
        scope.launch {
            try { if (!com.blaze.assistant.terminal.Linux.installed() && com.blaze.assistant.terminal.Linux.supported()) com.blaze.assistant.terminal.Linux.install() } catch (_: Throwable) { }
        }
    }
    fun watch(ctx: Context, done: () -> Boolean) {
        job?.cancel()
        val app = ctx.applicationContext
        job = scope.launch {
            val until = System.currentTimeMillis() + 180_000
            delay(1200)
            while (System.currentTimeMillis() < until) {
                if (done()) { launch(Dispatchers.Main) { Brains.bringBack(app) }; return@launch }
                delay(700)
            }
        }
    }
}

/**
 * 0.45.23 first run, step 1 of 2: sign in (Desktop's "signin" step). Step 2 is the chat itself, with a starter prompt
 * in the composer (setup/FirstRun). No permissions, themes, voice or tours here: they are asked when a task needs them
 * or live in Settings. Signing in moves on by itself; "Not now" lands in the chat too (it says to sign in when asked).
 */
@Composable
fun FirstRunSignIn(onDone: (signedIn: Boolean) -> Unit) {
    val c = LocalBlaze.current.mono
    val account by com.blaze.assistant.connect.BlazeAccount.view.collectAsState()
    var left by remember { mutableStateOf(false) }
    fun go(e: com.blaze.assistant.setup.FirstRun.Event) {
        val out = com.blaze.assistant.setup.FirstRun.reduce(com.blaze.assistant.setup.FirstRun.Step.SIGN_IN, e)
        if (out.cancelBrowser) com.blaze.assistant.connect.BlazeAccount.cancel()
        out.land?.let { if (!left) { left = true; onDone(it.signedIn) } }
    }
    // Back while the browser sign-in waits cancels it; otherwise Back leaves the app as usual (the step comes back next time).
    BackHandler(enabled = account.pending.isNotEmpty()) { go(com.blaze.assistant.setup.FirstRun.Event.Back(pending = true)) }
    LaunchedEffect(account.user) { if (account.user != null) { delay(900); go(com.blaze.assistant.setup.FirstRun.Event.SignedIn) } }

    Box(Modifier.fillMaxSize().pageBackdrop(c).semantics { testTag = "firstrun_signin" }) {
        ParticleField(com.blaze.assistant.agent.Mood.IDLE)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Two segments, like Desktop's "1 of 2 · Sign in".
                (0 until com.blaze.assistant.setup.FirstRun.STEPS.size).forEach { i ->
                    Box(Modifier.padding(end = 4.dp).width(22.dp).height(3.dp).clip(BlazeShapes.pill).background(if (i == 0) c.accent else c.hair2))
                }
                Spacer(Modifier.width(8.dp))
                Label("1 of 2 \u00b7 Sign in", color = c.weak, size = 10.sp)
                Spacer(Modifier.weight(1f))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 18.dp)) {
                OrbFace(64.dp)
                Spacer(Modifier.height(22.dp))
                val signedIn = account.user != null
                Text(if (signedIn) "You're signed in." else "Sign in to Blaze.",
                    style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 38.sp, color = c.ink))
                Spacer(Modifier.height(12.dp))
                Text("Blaze Cloud answers with no keys and picks a good model for every message. It's the same account as Blaze Desktop, " +
                    "and your synced chats and memory come back with it.",
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, lineHeight = 24.sp, color = c.sub))
                Spacer(Modifier.height(22.dp))
                AccountPage()
            }
            val signedIn = account.user != null
            Box(Modifier.fillMaxWidth().height(56.dp).clip(BlazeShapes.pill).glassButton(c)
                .semantics { testTag = if (signedIn) "firstrun_start" else "firstrun_notnow" }
                .clickable { go(if (signedIn) com.blaze.assistant.setup.FirstRun.Event.SignedIn else com.blaze.assistant.setup.FirstRun.Event.NotNow) },
                contentAlignment = Alignment.Center) { ButtonText(if (signedIn) "Start chatting" else "Not now", color = Color.White) }
            if (!signedIn) Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Label("Blaze answers once you sign in.", color = c.weak, size = 10.sp)
            }
        }
    }
}

/** Settings › Brain (v0.25.0): one brain, Blaze Cloud, and whether this phone can reach it. */
@Composable
fun BrainCard(onSignIn: () -> Unit = {}) {
    val c = LocalBlaze.current.mono
    val account by com.blaze.assistant.connect.BlazeAccount.view.collectAsState()
    val signedIn = account.user != null
    var reach by remember { mutableStateOf<Boolean?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        reach = null
        reach = kotlinx.coroutines.withContext(Dispatchers.IO) {
            try { com.blaze.assistant.connect.Http.req("GET", com.blaze.assistant.agent.Providers.gatewayBase.removeSuffix("/").removeSuffix("/v1") + "/health", timeoutMs = 8_000).first == 200 }
            catch (_: Exception) { false }
        }
    }
    val (dot, line) = BrainStatus.of(signedIn, reach)
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Label("Brain", size = 10.sp)
        Spacer(Modifier.height(6.dp))
        Text("Blaze Cloud", style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = c.ink))
        Text(BrainStatus.TAGLINE, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().pageGlass(c, PageGlass.row).clickable { if (!signedIn) onSignIn() else tick++ }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(BlazeShapes.pill).background(when (dot) { BrainStatus.Dot.OK -> c.accent; BrainStatus.Dot.BAD -> PageGlass.error; else -> c.weak }))
            Spacer(Modifier.width(10.dp))
            Text(line, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
            ActionText(if (signedIn) "Check" else "Sign in", color = c.accent, size = 10.sp)
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
    }
}

/** Pure: the Brain page's status line (tested). */
object BrainStatus {
    const val TAGLINE = "Picks the best free model for every message and skips busy ones by itself."
    enum class Dot { OK, BAD, WAIT }
    fun of(signedIn: Boolean, reachable: Boolean?): Pair<Dot, String> = when {
        !signedIn -> Dot.BAD to "Not signed in. Sign in to your Blaze account to start."
        reachable == null -> Dot.WAIT to "Checking the connection\u2026"
        reachable -> Dot.OK to "Connected"
        else -> Dot.BAD to "Can't reach Blaze Cloud right now. Check your data or Wi-Fi."
    }
}
