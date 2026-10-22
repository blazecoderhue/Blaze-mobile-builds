package com.blaze.assistant.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.bank.BankMode
import com.blaze.assistant.bank.BankModeLogic

/**
 * v0.43.0 Settings › Bank mode: one switch, "Turn on by itself for banking apps", the list of apps that count as banks
 * (found on the phone, each with a switch, plus "Add an app"), and the honest note about what Blaze can't change.
 * Advanced mode also shows the optional adb command that lets Blaze switch phone control back on by itself.
 */
@Composable
fun BankModePage() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val st by BankMode.state.collectAsState()
    val simple by com.blaze.assistant.agent.SimpleMode.simpleFlow.collectAsState()
    var auto by remember { mutableStateOf(BankMode.autoDetect) }
    var note by remember { mutableStateOf("") }
    var rev by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<Triple<String, String, Boolean>>>(emptyList()) }
    var others by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(rev) { list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BankMode.listFor(ctx) } }
    LaunchedEffect(adding, rev) { if (adding) others = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BankMode.others(ctx) } }

    PageHead("Bank mode", "Use banking apps with Blaze", BankModeLogic.SETTING_LINE)
    Spacer(Modifier.height(10.dp))
    ToggleRow("Bank mode", if (st.on) (if (st.auto) "On while your banking app is open" else "On. Blaze is stepping back.") else "Off", st.on) {
        note = if (st.on) BankMode.turnOff(ctx) else BankMode.turnOn(ctx)
    }
    ToggleRow("Turn on by itself", "When an app on the list below opens, Bank mode turns on, and off again when you leave it.", auto) {
        auto = !auto; BankMode.autoDetect = auto
    }
    if (note.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(note, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
        if (!st.on && note.contains("phone control back on")) {
            Spacer(Modifier.height(8.dp))
            BlazeButton("Turn phone control on", Modifier.fillMaxWidth(), kind = BtnKind.SECONDARY) {
                try { ctx.startActivity(com.blaze.assistant.phone.PhoneControl.fixIntent(ctx)) } catch (_: Exception) { }
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    GroupHead("Banking apps", if (list.isEmpty()) "No banking apps found yet. Add yours below." else "Bank mode turns on for the ones that are switched on.")
    list.forEach { (pkg, name, on) ->
        ToggleRow(name, if (simple) "" else pkg, on) { BankMode.setBank(pkg, !on); rev++ }
    }
    Spacer(Modifier.height(10.dp))
    BlazeButton(if (adding) "Done adding" else "Add an app", Modifier.fillMaxWidth(), kind = BtnKind.GHOST) { adding = !adding }
    if (adding) others.take(80).forEach { (pkg, name) ->
        ToggleRow(name, if (simple) "" else pkg, false) { BankMode.setBank(pkg, true); rev++ }
    }
    Spacer(Modifier.height(18.dp))
    GroupHead("If a bank still says no", "")
    Text(BankModeLogic.STILL_BLOCKED + " If the bank showed a warning before Bank mode turned on, close the bank app and open it again.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    // v0.45.0 (#24): say plainly what happens to phone control, and when Blaze stopped asking.
    var stopped by remember { mutableStateOf(BankMode.restoreIgnored() >= BankModeLogic.RESTORE_GIVE_UP) }
    if (simple && !BankMode.canRestoreA11y(ctx)) {
        Spacer(Modifier.height(18.dp))
        GroupHead("Phone control after banking", "")
        Text("Bank apps won't open while Blaze can control the phone, so Bank mode switches that off. When you leave the bank app, Blaze shows a notification: tap it, then Blaze's switch, to turn it back on.",
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    }
    if (stopped) {
        Spacer(Modifier.height(10.dp))
        Text(BankModeLogic.RESTORE_STOPPED, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.ink))
        Spacer(Modifier.height(6.dp))
        BlazeButton("Remind me again", Modifier.fillMaxWidth(), kind = BtnKind.GHOST) { BankMode.resetRestoreReminders(); stopped = false }
    }
    if (!simple) {
        Spacer(Modifier.height(18.dp))
        GroupHead("Phone control after Bank mode", if (BankMode.canRestoreA11y(ctx)) "Blaze can switch it back on by itself." else "You switch it back on with one tap.")
        Text(BankModeLogic.ADB_LINE, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
        Spacer(Modifier.height(6.dp))
        Text(BankModeLogic.ADB_GRANT, style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink))
    }
    Spacer(Modifier.height(18.dp))
}
