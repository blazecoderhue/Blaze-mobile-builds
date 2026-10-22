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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.connect.Billing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** v0.45.3: the Plan row on the Account page (opens [CheckoutPage]). */
@Composable
fun PlanRow(onOpen: () -> Unit) {
    val c = LocalBlaze.current.mono
    Row(Modifier.fillMaxWidth().hairline(c.hair).clickable(onClick = onOpen).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Plan", style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
            Text("Standard or Pro, monthly or yearly", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
        Text("\u203a", style = TextStyle(fontFamily = Type.Sans, fontSize = 20.sp, color = c.sub))
    }
}

/**
 * v0.45.3 checkout: the paid plans with a Monthly / Yearly toggle (hidden on a gateway without yearly prices), the
 * payment method with its details and the amount for the chosen period, the payment reference, and the history.
 * Sending creates a pending payment an admin confirms; nothing renews by itself.
 */
@Composable
fun CheckoutPage() {
    val c = LocalBlaze.current.mono
    val scope = rememberCoroutineScope()
    var methods by remember { mutableStateOf<Billing.Methods?>(null) }
    var me by remember { mutableStateOf<Billing.Me?>(null) }
    var err by remember { mutableStateOf("") }
    var period by remember { mutableStateOf(Billing.Period.MONTH) }
    var plan by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            Billing.Client.methods().onSuccess { m -> methods = m; plan = m.plans.firstOrNull()?.id.orEmpty(); method = m.methods.firstOrNull()?.id.orEmpty() }.onFailure { err = it.message.orEmpty() }
            Billing.Client.me().onSuccess { me = it }
        }
    }
    val m = methods
    Spacer(Modifier.height(10.dp))
    if (m == null) { Text(err.ifBlank { "Loading plans\u2026" }, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub)); return }
    val per = Billing.effective(m, period)
    me?.let { u -> if (u.plan.isNotBlank()) Text("Your plan: ${Billing.planName(u.plan)}" + (u.daysLeft?.let { " \u00b7 $it days left" } ?: ""),
        modifier = Modifier.padding(bottom = 10.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body)) }
    if (Billing.yearlyOffered(m)) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).hairline(c.hair, PageGlass.row), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        listOf(Billing.Period.MONTH to "Monthly", Billing.Period.YEAR to "Yearly").forEach { (p, label) ->
            val on = per == p
            Box(Modifier.weight(1f).height(40.dp).background(if (on) c.ink else c.paper.copy(alpha = 0f), PageGlass.row).clickable { period = p }
                .semantics { role = Role.Tab; selected = on }, contentAlignment = Alignment.Center) { ButtonText(label, color = if (on) c.paper else c.ink, size = 13.sp) }
        }
    }
    m.plans.forEach { p ->
        val on = p.id == plan
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).hairline(if (on) c.ink else c.hair, PageGlass.row).clickable { plan = p.id }.padding(14.dp)
            .semantics { selected = on }) {
            Text(Billing.planName(p.id), style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = c.ink))
            Text(Billing.priceLine(p, per), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
            val extra = listOfNotNull(Billing.termLine(per, if (per == Billing.Period.YEAR) m.yearDays ?: 365 else m.monthDays),
                if (per == Billing.Period.YEAR) Billing.saving(p) else null).joinToString(" \u00b7 ")
            Text(extra, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        }
    }
    Spacer(Modifier.height(6.dp))
    m.methods.forEach { x ->
        val on = x.id == method
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).hairline(if (on) c.ink else c.hair, PageGlass.row).clickable { method = x.id }.padding(14.dp)) {
            Text(x.name, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
            val amt = Billing.amount(x, plan, per)
            if (amt.isNotBlank()) Text("Pay $amt", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
            if (on) {
                x.details.forEach { d -> Text("${d.label}: ${d.value}", style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, color = c.body)) }
                if (x.hint.isNotBlank()) Text(x.hint, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
        }
    }
    val chosen = m.methods.firstOrNull { it.id == method }
    if (chosen != null) {
        Text(chosen.refLabel, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        BasicTextField(ref, { ref = it.take(80) }, singleLine = true, cursorBrush = SolidColor(c.ink),
            textStyle = TextStyle(fontFamily = Type.Mono, fontSize = 14.sp, color = c.ink),
            modifier = Modifier.fillMaxWidth().hairline(c.hair, PageGlass.row).padding(12.dp))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(44.dp).background(c.ink, PageGlass.row).clickable(enabled = !busy && ref.isNotBlank() && plan.isNotBlank()) {
            busy = true; err = ""
            scope.launch {
                withContext(Dispatchers.IO) { Billing.Client.pay(plan, method, ref, per) }.onSuccess { done = it; ref = "" }.onFailure { err = it.message.orEmpty() }
                withContext(Dispatchers.IO) { Billing.Client.me() }.onSuccess { me = it }
                busy = false
            }
        }, contentAlignment = Alignment.Center) { ButtonText(if (busy) "Sending\u2026" else "I've paid, send the reference", color = c.paper, size = 13.sp) }
    }
    if (done.isNotBlank()) Text(done, modifier = Modifier.padding(top = 10.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body))
    if (err.isNotBlank()) Text(err, modifier = Modifier.padding(top = 10.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
    val pays = me?.payments.orEmpty()
    if (pays.isNotEmpty()) {
        Text("Payments", modifier = Modifier.padding(top = 18.dp, bottom = 6.dp), style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
        pays.forEach { p -> Text(Billing.historyLabel(p) + (if (p.amountLocal.isNotBlank()) " \u00b7 ${p.amountLocal}" else "") + " \u00b7 ${p.status}",
            modifier = Modifier.padding(vertical = 3.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body)) }
    }
}
