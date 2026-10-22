package com.blaze.assistant.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * USSD codes (*123#, balance, bundles, mobile money menus) answered in-app via TelephonyManager.
 * Codes for mobile money / transfers always ask the user first.
 */
object Ussd {
    private val money = Regex("^\\*(115|303|444|778|150|182|165|170)", RegexOption.IGNORE_CASE)

    fun risky(code: String) = money.containsMatchIn(code.trim()) || code.count { it == '*' } >= 3

    suspend fun run(ctx: Context, code: String): String {
        val c = code.trim().replace(" ", "")
        if (!Regex("^[*#][0-9*#]+#$").matches(c)) return "Error: '$code' isn't a USSD code (like *123#)."
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return "Blaze needs the Calls permission for USSD (Settings → Phone control → Skills → Calls)."
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return "No phone service on this device."
        val r = withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<String> { cont ->
                try {
                    @Suppress("MissingPermission")
                    tm.sendUssdRequest(c, object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(t: TelephonyManager, req: String, resp: CharSequence) { if (cont.isActive) cont.resume(resp.toString()) }
                        override fun onReceiveUssdResponseFailed(t: TelephonyManager, req: String, code: Int) {
                            if (cont.isActive) cont.resume("Error: the network didn't answer that code (failure $code). Multi-step menus may need the dialer: use dial with the code.")
                        }
                    }, Handler(Looper.getMainLooper()))
                } catch (e: Exception) { if (cont.isActive) cont.resume("Error: ${e.message}") }
            }
        }
        return r ?: "Error: no reply in 30 s."
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", "run_ussd")
        .put("description", "Dial a USSD code and read the network's reply in-app, e.g. *123# (MTN balance), bundle menus. Single-reply codes work best; mobile money codes ask the user first.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("code", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("code")))))
}
