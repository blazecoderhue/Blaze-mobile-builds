package com.blaze.assistant.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Blaze Phone (spike) as one agent tool, `blaze_phone`. Offered to the model only in a device-test build
 * (-Pblaze.phoneSpike=true) or when the hidden setting "blaze_phone_spike" is on, so everyone else's tool list is unchanged.
 */
object BlazePhoneTool {
    const val NAME = "blaze_phone"
    const val SETTING = "blaze_phone_spike"

    fun enabled(): Boolean = com.blaze.assistant.BuildConfig.BLAZE_PHONE_SPIKE || com.blaze.assistant.agent.Settings.bool(SETTING, false)

    fun def(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", NAME)
        .put("description", "Blaze Phone (experimental): apps that run only inside Blaze's own sandbox, never installed on the phone. " +
            "install (an APK file path; the user confirms), uninstall (confirms), list, open, tap x,y (pixels on the app's screen), type text, back, " +
            "view_tree (the app's screen as a tree with bounds), memory, close.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("install", "uninstall", "list", "open", "tap", "type", "back", "view_tree", "memory", "close"))))
            .put("apk", JSONObject().put("type", "string").put("description", "APK path (install)"))
            .put("package", JSONObject().put("type", "string"))
            .put("x", JSONObject().put("type", "integer")).put("y", JSONObject().put("type", "integer"))
            .put("text", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action")))))

    suspend fun run(ctx: Context, a: JSONObject): String {
        val p = BlazePhone.get(ctx)
        val pkg = a.optString("package")
        val r = when (a.optString("action")) {
            "install" -> p.installGuest(File(a.optString("apk")))
            "uninstall" -> p.uninstallGuest(pkg)
            "list" -> return p.installed().let { if (it.isEmpty()) "Blaze Phone has no apps yet." else "Blaze Phone apps: " + it.joinToString() + ". Open now: " + p.liveGuests().joinToString().ifBlank { "none" } + "." }
            "open" -> p.launch(pkg)
            "tap" -> p.tap(pkg, a.optInt("x"), a.optInt("y"))
            "type" -> p.type(pkg, a.optString("text"))
            "back" -> p.back(pkg)
            "view_tree" -> p.dumpViewTree(pkg).let { if (it.ok) return it.data?.optJSONObject("tree")?.toString() ?: it.message else it }
            "memory" -> p.memory(pkg).let { if (it.ok) return "$pkg: pid ${it.data?.optInt("pid")}, ${(it.data?.optInt("pssKb") ?: 0) / 1024} MB (PSS)." else it }
            "close" -> p.handBack(pkg)
            else -> return "Error: unknown action."
        }
        return if (r.ok) r.message else "Error: ${r.message}"
    }
}
