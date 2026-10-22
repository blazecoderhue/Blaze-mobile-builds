package com.blaze.assistant.phone

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * v0.33.3: every launchable app on the phone (ACTION_MAIN / CATEGORY_LAUNCHER; the manifest's <queries> declares that
 * intent, so Android 11+ package visibility shows them all) with its label and category, cached for 60 s.
 * Call off the main thread (loadLabel for 150 apps is slow on a low-end phone).
 */
object InstalledApps {
    @Volatile private var cache: Pair<Long, List<AppFinder.App>>? = null

    fun all(ctx: Context, maxAgeMs: Long = 60_000): List<AppFinder.App> {
        cache?.let { (at, l) -> if (System.currentTimeMillis() - at < maxAgeMs) return l }
        val pm = ctx.packageManager
        val list = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .distinctBy { it.activityInfo.packageName }
                .map { ri ->
                    val ai = ri.activityInfo.applicationInfo
                    @Suppress("DEPRECATION")
                    val cat = when {
                        Build.VERSION.SDK_INT >= 26 && ai.category != ApplicationInfo.CATEGORY_UNDEFINED -> ai.category
                        ai.flags and ApplicationInfo.FLAG_IS_GAME != 0 -> AppFinder.CAT_GAME
                        else -> AppFinder.CAT_UNDEFINED
                    }
                    AppFinder.App(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, cat)
                }
                .filter { it.pkg != ctx.packageName }
                .sortedBy { it.label.lowercase() }
        } catch (_: Exception) { emptyList() }
        cache = System.currentTimeMillis() to list
        return list
    }

    fun installed(ctx: Context, pkg: String): Boolean = try { ctx.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }

    /** Launches an installed app by package. True when Android started it. */
    fun launch(ctx: Context, pkg: String): Boolean = try {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    /** list_apps: all apps (empty filter) or fuzzy matches; returns the model's text and the chat card. */
    fun listTool(ctx: Context, filter: String): Pair<String, String?> {
        val apps = all(ctx)
        if (apps.isEmpty()) return "Couldn't read the app list on this phone." to null
        val f = filter.trim()
        fun line(a: AppFinder.App) = "${a.label} (${a.pkg})" + if (a.isGame) " \u00b7 game" else ""
        if (f.isEmpty()) {
            val card = AppFinder.fence("Your apps (${apps.size})", apps)
            return ("${apps.size} apps installed (every launchable app). The chat shows them as a tappable card with icons under your reply, " +
                "so don't retype the whole list; mention a few if useful.\n" + apps.take(200).joinToString("\n") { line(it) }) to card
        }
        val hits = AppFinder.search(f, apps)
        if (hits.isEmpty()) {
            val (_, cat) = AppFinder.parse(f)
            val games = if (cat == AppFinder.CAT_GAME) apps.filter { it.isGame } else emptyList()
            return ("No installed app matches \u201c$f\u201d by name." +
                (if (games.isNotEmpty()) " Installed games: " + games.joinToString(", ") { line(it) } + "." else "") +
                " All ${apps.size} apps: " + apps.joinToString(", ") { it.label }) to null
        }
        val shown = hits.map { it.app }
        return ("${hits.size} installed app(s) match \u201c$f\u201d, best first (the chat shows them as a tappable card):\n" +
            shown.joinToString("\n") { line(it) } + "\nTo open one, call open_app with its exact name; it is installed, so never send the user to the Play Store for it.") to
            AppFinder.fence("Apps matching \u201c$f\u201d", shown)
    }
}
