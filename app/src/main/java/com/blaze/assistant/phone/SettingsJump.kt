package com.blaze.assistant.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.30.4 fast actions: the open_settings tool. One call lands on the exact settings page (for one app or the whole
 * phone) by intent, using SettingsRoute's chain for this Android version, instead of the model tapping through
 * Settings menus whose labels differ on every skin. When only a fallback works (App info on Android 11 for
 * "Open by default"), it taps the right row itself (LabelMatch synonyms + scroll-to-find) and returns the page.
 */
object SettingsJump {
    const val TOOL = "open_settings"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "FASTEST way into Android settings: jumps straight to the exact page by intent, no menu tapping, " +
            "and returns that page's screen. With app (its name or package) it opens that app's own page: " +
            SettingsRoute.appPages.entries.joinToString("; ") { "${it.key} = ${it.value}" } +
            ". Without app, a phone-wide page (wifi, bluetooth, location, display, sound, battery, default_apps, notifications, apps, …). " +
            "Always use this instead of open_app Settings + tapping. Then flip the switch with tap text=\"…\" (tap by text scrolls to it).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("page", JSONObject().put("type", "string").put("enum", JSONArray(SettingsRoute.pageNames)))
            .put("app", JSONObject().put("type", "string").put("description", "App name or package, for app pages, e.g. GitHub")))
            .put("required", JSONArray(listOf("page")))))

    /** Launcher apps on the phone as (label, package). */
    fun installed(ctx: Context): List<Pair<String, String>> = try {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
    } catch (_: Exception) { emptyList() }

    private fun intentOf(l: SettingsRoute.Launch): Intent = Intent(l.action).apply {
        l.data?.let { data = Uri.parse(it) }
        l.extras.forEach { (k, v) -> putExtra(k, v) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    suspend fun run(ctx: Context, args: JSONObject): String {
        val appQ = args.optString("app").trim()
        var pkg: String? = null; var appLabel = ""
        if (appQ.isNotEmpty()) {
            val hit = SettingsRoute.resolveApp(appQ, installed(ctx))
                ?: (if (appQ.contains('.')) try { ctx.packageManager.getApplicationInfo(appQ, 0); appQ to appQ } catch (_: Exception) { null } else null)
                ?: return "No installed app matches \u201c$appQ\u201d. list_apps filter=\u2026 shows what's installed."
            appLabel = hit.first; pkg = hit.second
        }
        val raw = args.optString("page").ifBlank { if (pkg != null) "app_details" else "settings" }
        val page = SettingsRoute.page(raw, pkg != null)
            ?: return "Unknown settings page \u201c$raw\u201d. Pages: ${SettingsRoute.pageNames.joinToString(", ")}."
        val plan = SettingsRoute.plan(page, pkg, Build.VERSION.SDK_INT)
            ?: return "That page needs an app: call open_settings again with app=\u2026"
        val svc = BlazeAccessibilityService.instance
        var used = -1
        for ((i, l) in plan.launches.withIndex()) {
            val intent = intentOf(l)
            // No resolveActivity check: package visibility (Android 11+) can hide Settings from queries, while
            // startActivity with an implicit intent still works. A missing page throws and the next one is tried.
            val before = svc?.currentPkg().orEmpty()
            try { ctx.startActivity(intent) } catch (_: Exception) { continue }
            used = i
            if (svc != null) {
                val end = android.os.SystemClock.uptimeMillis() + 2500
                while (svc.currentPkg() == before && android.os.SystemClock.uptimeMillis() < end) kotlinx.coroutines.delay(60)
                svc.settle(1200, 200)
            }
            break
        }
        val what = (if (pkg != null) "$appLabel \u203a " else "") + (SettingsRoute.appPages[page] ?: page.replace('_', ' '))
        if (used < 0) return "Couldn't open $what: this phone has no page for it. Open the app's App info with open_settings page=app_details" +
            (if (pkg != null) " app=$appQ" else "") + " and tap from there."
        if (svc == null) return "Opened $what. (Phone control is off, so I can't read or change the page; the user can flip it now.)"
        var via = "direct"
        if (used >= plan.tapFrom && plan.thenTap.isNotEmpty()) {
            via = "App info"
            val row = plan.thenTap.firstNotNullOfOrNull { r -> svc.reveal(r, maxScrolls = 6)?.let { r to it } }
            if (row != null) {
                val name = svc.labelOf(row.second).ifBlank { row.first }
                svc.tap(row.second)
                svc.settle(1200, 200)
                via = "App info \u203a $name"
            } else via = "App info (couldn't find the ${plan.thenTap.first()} row; it's on this page somewhere)"
        }
        return "Opened $what ($via). Screen now:\n" + svc.readScreen()
    }
}
