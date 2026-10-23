package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.blaze.assistant.connect.BlazeAccount
import com.blaze.assistant.connect.KeySync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip

/** Settings › Account: the Blaze account shared with Blaze Desktop. */
@Composable
fun AccountPage(onOpen: (String) -> Unit = {}) {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v by BlazeAccount.view.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { BlazeAccount.refreshProviders(); BlazeAccount.checkSession() } }

    Spacer(Modifier.height(10.dp))
    val u = v.user
    if (u != null) {
        // ---------- signed in ----------
        Row(Modifier.fillMaxWidth().hairline(c.hair).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(BlazeShapes.pill).background(c.ink, BlazeShapes.pill), contentAlignment = Alignment.Center) {
                Text((u.name.ifBlank { u.email }).take(1).uppercase(),
                    style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = c.paper))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(u.name.ifBlank { u.email }, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
                if (u.name.isNotBlank()) Text(u.email, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
                val how = (u.providers.map { BlazeAccount.title(it) } + if (u.hasPassword) listOf("Email") else emptyList()).distinct()
                if (how.isNotEmpty()) Label("Signs in with " + how.joinToString(", "), color = c.weak, size = 9.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Note("The same account works in Blaze Desktop and Blaze CLI, and it is how Blaze Cloud answers you. Your own skills and MCP servers follow it, so one added on your PC works here and the other way round. Connected accounts stay on this phone.", top = 12)
        if (v.message.isNotEmpty()) Note(v.message)
        // v0.40.0: optional, end-to-end encrypted live sync with your other phones (off by default).
        CloudSyncRow { onOpen("sync") }
        Spacer(Modifier.height(8.dp))
        PlanRow { onOpen("plan") }
        UsageSection { onOpen("plan") }
        Spacer(Modifier.height(12.dp))
        val keyStatus by KeySync.status.collectAsState()
        var syncing by remember { mutableStateOf(false) }
        AccountButton(if (syncing) "Syncing…" else "Sync MCP servers now", primary = false) {
            if (syncing) return@AccountButton
            syncing = true
            scope.launch { KeySync.sync(); syncing = false }
        }
        if (keyStatus.isNotEmpty()) Note(keyStatus, top = 8)
        val skillStatus by com.blaze.assistant.connect.SkillSync.status.collectAsState()
        var syncingSkills by remember { mutableStateOf(false) }
        Spacer(Modifier.height(8.dp))
        AccountButton(if (syncingSkills) "Syncing skills…" else "Sync skills now", primary = false) {
            if (syncingSkills) return@AccountButton
            syncingSkills = true
            scope.launch { com.blaze.assistant.connect.SkillSync.sync(); syncingSkills = false }
        }
        if (skillStatus.isNotEmpty()) Note(skillStatus, top = 8)
        // v0.45.3: password and two-step verification (blaze-auth 0.9.0; each part only when the server offers it).
        SecuritySection(u, v.features)
        Spacer(Modifier.height(8.dp))
        AccountButton("Sign out", primary = false) { scope.launch { BlazeAccount.signOut() } }
        Spacer(Modifier.height(24.dp))
        var confirm by remember { mutableStateOf(false) }
        if (!confirm) ActionText("Delete account", color = c.accent, size = 10.sp, modifier = Modifier.clickable { confirm = true }.padding(vertical = 8.dp))
        else {
            Note("This deletes your Blaze account, its sign-ins and sessions everywhere. Chats, keys and connected accounts on this phone stay.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { AccountButton("Keep it", primary = false) { confirm = false } }
                Box(Modifier.weight(1f)) {
                    AccountButton(if (busy) "Deleting…" else "Delete for good") {
                        if (busy) return@AccountButton
                        busy = true
                        scope.launch { err = BlazeAccount.delete(); busy = false; confirm = false }
                    }
                }
            }
        }
        if (err.isNotEmpty()) Note(err, top = 8)
        return
    }

    // ---------- signed out ----------
    v.mfa?.let { MfaStep(it, v.message); return }
    var signUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Note("Sign in once and Blaze Cloud answers you: it picks the best free model for every message. The same account works in Blaze Desktop.")
    if (!v.online) Note("Can't reach Blaze's sign-in service right now. Check your connection.")
    // v0.45.4: how the browser sign-in ended shows up here, at the top, where the first-run screen can see it.
    if (v.message.isNotEmpty() && err.isEmpty()) Note(v.message)

    if (v.pending.isNotEmpty()) {
        Note("Finish signing in with ${BlazeAccount.title(v.pending)} in your browser, then come back.")
        AccountButton("Cancel", primary = false) { BlazeAccount.cancel() }
    } else {
        for (p in listOf("google", "github")) if (v.login[p] == true) {
            AccountButton("Continue with ${BlazeAccount.title(p)}", primary = false) {
                err = BlazeAccount.signInWith(ctx, p)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (v.login["google"] == true || v.login["github"] == true) Label("or with email", color = c.weak, size = 9.sp, modifier = Modifier.padding(vertical = 10.dp))
    }

    Spacer(Modifier.height(6.dp))
    if (signUp) Field("Name", name, { name = it }, "Optional")
    Field("Email", email, { email = it }, "you@example.com")
    Field("Password", pass, { pass = it }, if (signUp) "At least 8 characters" else "Your password", secret = true)
    AccountButton(if (busy) "One moment…" else if (signUp) "Create account" else "Sign in") {
        if (busy) return@AccountButton
        busy = true; err = ""; BlazeAccount.clearMessage()
        scope.launch {
            err = BlazeAccount.email(signUp, name, email, pass)
            if (err.isEmpty()) pass = ""
            busy = false
        }
    }
    Label(if (signUp) "Have an account? Sign in" else "New to Blaze? Create an account", color = c.accent, size = 10.sp,
        modifier = Modifier.clickable { signUp = !signUp; err = "" }.padding(vertical = 14.dp))
    if (!signUp && v.features["password_reset"] == true) {
        Label("Forgot password?", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
            if (busy) return@clickable
            busy = true; err = ""
            scope.launch { err = BlazeAccount.forgotPassword(email); busy = false }
        }.padding(bottom = 14.dp))
    }
    if (err.isNotEmpty()) Note(err)
}

@Composable
private fun Note(text: String, top: Int = 0) {
    val c = LocalBlaze.current.mono
    Text(text, modifier = Modifier.padding(top = top.dp, bottom = 10.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
}

@Composable
private fun AccountButton(text: String, primary: Boolean = true, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.fillMaxWidth().height(44.dp).then(if (primary) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) { ButtonText(text, color = if (primary) c.paper else c.ink, size = 13.sp) }
}

/** v0.45.3: the second sign-in step when the account has two-step verification on (5 minutes, 5 tries; codes by app, email or recovery). */
@Composable
private fun MfaStep(p: com.blaze.assistant.connect.Mfa.Pending, note: String) {
    val c = LocalBlaze.current.mono
    val scope = rememberCoroutineScope()
    // "app", "email" or "recovery"; an account without the app starts on email (the code was sent automatically).
    var mode by remember(p.token) { mutableStateOf(if (p.app) "app" else "email") }
    var code by remember(p.token) { mutableStateOf("") }
    var busy by remember(p.token) { mutableStateOf(false) }
    var err by remember(p.token) { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.token) {
        while (true) {
            now = System.currentTimeMillis()
            if (p.leftMs(now) == 0L) { BlazeAccount.cancelMfa(com.blaze.assistant.connect.Mfa.EXPIRED); break }
            kotlinx.coroutines.delay(1_000)
        }
    }
    val send = {
        if (!busy) {
            busy = true; err = ""
            scope.launch { BlazeAccount.sendMfaEmail(); busy = false }
        }
    }
    Note("Two-step verification is on for this account" + (if (p.via != "email") " (signed in with ${BlazeAccount.title(p.via)})" else "") + ". " +
        when (mode) {
            "recovery" -> "Enter one of your recovery codes. Each one works once."
            "email" -> if (p.emailSent) "We sent a code to ${com.blaze.assistant.connect.Mfa.sentTo(p)}. Enter the 6-digit code from the email."
                       else "Sending a code to ${com.blaze.assistant.connect.Mfa.sentTo(p)}…"
            else -> "Enter the 6-digit code from your authenticator app."
        })
    Label(com.blaze.assistant.connect.Mfa.stateLine(p, now), color = c.weak, size = 9.sp, modifier = Modifier.padding(bottom = 8.dp))
    Field(if (mode == "recovery") "Recovery code" else "Code", code, { code = it }, if (mode == "recovery") "abcde-12345" else "123456")
    AccountButton(if (busy) "One moment…" else "Verify") {
        if (busy) return@AccountButton
        busy = true; err = ""
        scope.launch { err = BlazeAccount.verifyMfa(code, mode == "recovery"); if (err.isNotEmpty()) code = ""; busy = false }
    }
    if (mode == "email" && p.email) {
        val line = com.blaze.assistant.connect.Mfa.sendLine(p, now)
        Spacer(Modifier.height(8.dp))
        AccountButton(if (p.emailSent) "Send a new code" else "Send the code", primary = false) {
            if (p.canSend(now)) send() else { err = line }
        }
        if (line.isNotEmpty()) Label(line, color = c.weak, size = 9.sp, modifier = Modifier.padding(top = 6.dp))
    }
    if (mode != "email" && p.email) Label("Email me a code instead", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
        mode = "email"; code = ""; err = ""
        if (!p.emailSent) send()
    }.padding(top = 12.dp))
    if (mode != "app" && p.app) Label("Use a code from the app instead", color = c.accent, size = 10.sp,
        modifier = Modifier.clickable { mode = "app"; code = ""; err = "" }.padding(top = 12.dp))
    if (mode != "recovery" && p.recovery) Label("Use a recovery code instead", color = c.accent, size = 10.sp,
        modifier = Modifier.clickable { mode = "recovery"; code = ""; err = "" }.padding(top = 12.dp))
    Label("Back to sign-in", color = c.accent, size = 10.sp,
        modifier = Modifier.clickable { BlazeAccount.cancelMfa() }.padding(vertical = 12.dp))
    val shown = err.ifEmpty { if (mode == "email") note else "" }
    if (shown.isNotEmpty()) Note(shown)
}

/** v0.45.3: Settings › Account › password and two-step verification. */
@Composable
private fun SecuritySection(u: BlazeAccount.User, features: Map<String, Boolean>) {
    val c = LocalBlaze.current.mono
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf("") }   // "", "password", "2fa"
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    Spacer(Modifier.height(8.dp))
    AccountButton(if (u.hasPassword) "Change password" else "Add a password", primary = false) { open = if (open == "password") "" else "password"; msg = "" }
    if (open == "password") {
        var cur by remember { mutableStateOf("") }
        var neu by remember { mutableStateOf("") }
        Spacer(Modifier.height(8.dp))
        if (u.hasPassword) Field("Current password", cur, { cur = it }, "Your password now", secret = true)
        Field("New password", neu, { neu = it }, "At least 8 characters", secret = true)
        AccountButton(if (busy) "Saving…" else "Save password") {
            if (busy) return@AccountButton
            busy = true
            scope.launch {
                val e = BlazeAccount.setPassword(neu, if (u.hasPassword) cur else "")
                msg = e.ifEmpty { "Password saved." + if (u.hasPassword) " Other devices were signed out." else " You can now sign in with your email too." }
                if (e.isEmpty()) { open = ""; cur = ""; neu = "" }
                busy = false
            }
        }
    }
    if (features["two_factor_email"] == true || u.twoFactorEmail) {
        Spacer(Modifier.height(8.dp))
        AccountButton(if (u.twoFactorEmail) "Email codes: on" else "Email codes: off", primary = false) { open = if (open == "email") "" else "email"; msg = "" }
        if (open == "email") {
            var sentTo by remember { mutableStateOf("") }
            var code by remember { mutableStateOf("") }
            val on = !u.twoFactorEmail   // what the change does
            Spacer(Modifier.height(8.dp))
            if (sentTo.isEmpty()) {
                Note(if (on) "Signing in will also ask for a 6-digit code we email to ${u.email}. To turn it on, we'll email you a code first."
                     else "Signing in will no longer email you a code" + (if (u.twoFactorApp) " (your authenticator app still works)." else ".") +
                         " To turn it off, we'll email you a code" + (if (u.twoFactorApp) ", or enter one from your app." else "."))
                AccountButton(if (busy) "Sending…" else "Email me a code") {
                    if (busy) return@AccountButton
                    busy = true
                    scope.launch { BlazeAccount.emailCodeSend(if (on) "enable" else "disable").onSuccess { sentTo = it.ifBlank { u.email } }.onFailure { msg = it.message ?: "" }; busy = false }
                }
                if (!on && u.twoFactorApp) { Spacer(Modifier.height(6.dp)); Label("I'll use a code from the app", color = c.accent, size = 10.sp,
                    modifier = Modifier.clickable { sentTo = "your authenticator app" }.padding(vertical = 6.dp)) }
            } else {
                Note("Enter the 6-digit code" + (if (sentTo == "your authenticator app") " from your authenticator app." else " we sent to $sentTo. It works for 10 minutes."))
                Field("Code", code, { code = it }, "123456")
                AccountButton(if (busy) "Checking…" else if (on) "Turn on email codes" else "Turn off email codes") {
                    if (busy) return@AccountButton
                    busy = true
                    scope.launch {
                        val e = BlazeAccount.emailCodeSet(on, code)
                        msg = e.ifEmpty { if (on) "Email codes are on." else "Email codes are off." }
                        if (e.isEmpty()) { open = ""; sentTo = "" }
                        code = ""; busy = false
                    }
                }
            }
        }
    }
    if (features["two_factor_app"] == true || u.twoFactorApp) {
        Spacer(Modifier.height(8.dp))
        AccountButton(if (u.twoFactorApp) "Authenticator app: on" else "Authenticator app: off", primary = false) {
            open = if (open == "2fa") "" else "2fa"; msg = ""
        }
        if (open == "2fa") {
            var code by remember { mutableStateOf("") }
            var setup by remember { mutableStateOf<Pair<String, String>?>(null) }
            var codes by remember { mutableStateOf<List<String>>(emptyList()) }
            Spacer(Modifier.height(8.dp))
            when {
                codes.isNotEmpty() -> {
                    Note("Two-step verification is on. Save these recovery codes somewhere safe; each works once if you lose your phone. They won't be shown again.")
                    Text(codes.joinToString("\n"), modifier = Modifier.padding(bottom = 10.dp),
                        style = TextStyle(fontFamily = Type.Mono, fontSize = 13.sp, lineHeight = 20.sp, color = c.ink))
                    AccountButton("I saved them", primary = false) { codes = emptyList(); open = "" }
                }
                u.twoFactorApp -> {
                    Note("To turn it off, enter a code from your authenticator app or a recovery code.")
                    Field("Code", code, { code = it }, "123456")
                    AccountButton(if (busy) "One moment…" else "Turn off") {
                        if (busy) return@AccountButton
                        busy = true
                        scope.launch { val e = BlazeAccount.mfaDisable(code); msg = e.ifEmpty { "Two-step verification is off." }; if (e.isEmpty()) open = ""; code = ""; busy = false }
                    }
                }
                setup == null -> {
                    Note("Signing in will also ask for a 6-digit code from an authenticator app (Google Authenticator, Authy, 2FAS…).")
                    AccountButton(if (busy) "One moment…" else "Turn on") {
                        if (busy) return@AccountButton
                        busy = true
                        scope.launch { BlazeAccount.mfaSetup().onSuccess { setup = it }.onFailure { msg = it.message ?: "" }; busy = false }
                    }
                }
                else -> {
                    val (secret, uri) = setup!!
                    val ctx = LocalContext.current
                    Note("Add Blaze to your authenticator app with this key, then enter the 6-digit code it shows.")
                    Text(secret.chunked(4).joinToString(" "), modifier = Modifier.padding(bottom = 6.dp),
                        style = TextStyle(fontFamily = Type.Mono, fontSize = 14.sp, color = c.ink))
                    if (uri.isNotBlank()) Label("Open in authenticator app", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))) }
                            .onFailure { msg = "No authenticator app on this phone opens that link. Type the key in by hand." }
                    }.padding(bottom = 10.dp))
                    Field("Code", code, { code = it }, "123456")
                    AccountButton(if (busy) "Checking…" else "Confirm and turn on") {
                        if (busy) return@AccountButton
                        busy = true
                        scope.launch { BlazeAccount.mfaEnable(code).onSuccess { codes = it; setup = null; msg = "" }.onFailure { msg = it.message ?: "" }; code = ""; busy = false }
                    }
                }
            }
        }
    }
    if (msg.isNotEmpty()) Note(msg, top = 8)
}

/**
 * v0.45.18: the plan, this month's limits and what's left, the parallel cap and the reset date, all from the server
 * (GET /v1/entitlements). The upgrade nudge shows only near a limit.
 */
@Composable
private fun UsageSection(onPlan: () -> Unit) {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val s by com.blaze.assistant.connect.Entitlements.snap.collectAsState()
    var tried by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { com.blaze.assistant.connect.Entitlements.refresh(ctx) }; tried = true }
    val snap = s
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).hairline(c.hair).padding(16.dp)) {
        Text("This month", style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
        if (snap == null) {
            Text(if (tried) "Can't load your limits right now." else "Loading your limits\u2026", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
            return@Column
        }
        Text(com.blaze.assistant.connect.Billing.planName(snap.plan) + " plan", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        Spacer(Modifier.height(8.dp))
        // Wave B 3: a meter per counter, numbers straight from the server.
        snap.song?.let { UsageMeter("Song downloads", it) }
        snap.video?.let { UsageMeter("Video downloads", it) }
        val par = com.blaze.assistant.connect.Entitlements.parallelLine(snap)
        if (par.isNotBlank()) UsageLine("At once", par, false)
        val reset = com.blaze.assistant.connect.Entitlements.resetLine(snap.resetAtMs)
        if (reset.isNotBlank()) Label(reset, color = c.weak, size = 9.sp, modifier = Modifier.padding(top = 6.dp))
        val nudge = com.blaze.assistant.connect.Entitlements.nudge(snap)
        if (nudge.isNotBlank()) {
            Text(nudge, modifier = Modifier.padding(top = 10.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body))
            ActionText("See plans", color = c.accent, size = 10.sp, modifier = Modifier.clickable(onClick = onPlan).padding(vertical = 8.dp))
        }
    }
}

/** Wave B 3: label + "x of y left" on one row, a 6 dp meter under it (full width, so nothing squishes at 360 dp). */
@Composable
fun UsageMeter(label: String, counter: com.blaze.assistant.connect.Entitlements.Counter) {
    val c = LocalBlaze.current.mono
    val E = com.blaze.assistant.connect.Entitlements
    val warn = E.near(counter)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        UsageLine(label, E.line(counter), warn)
        E.fraction(counter)?.let { f ->
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.hair)) {
                Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (warn) BlazeBrush.orange else c.ink))
            }
            Text(E.usedLine(counter), modifier = Modifier.padding(top = 3.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        }
    }
}

@Composable
private fun UsageLine(label: String, value: String, warn: Boolean) {
    val c = LocalBlaze.current.mono
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
        Text(value, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (warn) c.accent else c.ink))
    }
}
