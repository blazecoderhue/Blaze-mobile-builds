package com.blaze.assistant.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.io.File

/**
 * v0.24.4 dependency setup gate. On launch (new users after the first-run flow, and users who upgraded), Blaze
 * checks which dependencies are missing and shows the setup columns for just those. Required ones block the
 * app until they're done; optional ones have "Later". Once everything is resolved `setup_deps_done` is set and
 * the gate never runs again. Before the gate first runs, prefs and user data are backed up (see [backup]).
 *
 * The decision itself is pure (maps and sets in, steps out) so it is tested without a phone.
 */
object SetupGate {
    const val PREF_DONE = "setup_deps_done"
    const val PREF_LATER = "setup_later"
    const val PREF_BACKUP = "setup_backup_v"

    data class Dep(val id: String, val title: String, val why: String, val required: Boolean)

    /** Order is the order of the columns on screen. */
    val deps = listOf(
        Dep("notifications", "Notifications", "So Blaze can show what it's doing (with Stop) and tell you when a task is done.", required = true),
        Dep("linux", "Terminal (Linux)", "A small Linux on the phone (about 3.5 MB) for scripts, files, code and the tools Blaze runs.", required = true),
        Dep("accessibility", "Phone control", "So Blaze can tap and type in other apps for you. It always asks before sending, paying or deleting.", required = false),
        Dep("mic", "Microphone", "For talking to Blaze and \u201cHey Blaze\u201d.", required = false),
        Dep("overlay", "Show over other apps", "So Blaze can pop up wherever you are.", required = false),
        Dep("battery", "Keep working in the background", "Stops the phone putting Blaze to sleep halfway through a job.", required = false),
        Dep("desktop", "Pair with Blaze Desktop", "Share chats, keys and files with Blaze on your computer (scan the code in Blaze Desktop \u203a Phone).", required = false),
    )

    fun dep(id: String): Dep? = deps.firstOrNull { it.id == id }

    /** Steps still to show: missing, and (for optional ones) not put off with Later. */
    fun pending(have: Map<String, Boolean>, later: Set<String>): List<Dep> =
        deps.filter { have[it.id] != true && !(!it.required && it.id in later) }

    /** Required steps still missing: these block the app. */
    fun blocking(have: Map<String, Boolean>): List<Dep> = deps.filter { it.required && have[it.id] != true }

    /** True when the gate should show. Never again once [done] is set. */
    fun shouldGate(done: Boolean, have: Map<String, Boolean>, later: Set<String>): Boolean =
        !done && pending(have, later).isNotEmpty()

    /** True when everything is installed, granted or put off: the pref can be set for good. */
    fun complete(have: Map<String, Boolean>, later: Set<String>): Boolean = pending(have, later).isEmpty()

    fun parseLater(raw: String): Set<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun joinLater(s: Set<String>): String = s.sorted().joinToString(",")

    // ---------- the phone's side ----------

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** What's already there on this phone. A step that can't apply here (Linux on an unsupported CPU) counts as done. */
    fun probe(ctx: Context): Map<String, Boolean> = mapOf(
        "notifications" to (Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)),
        "linux" to try { com.blaze.assistant.terminal.Linux.installed() || !com.blaze.assistant.terminal.Linux.supported() } catch (_: Throwable) { true },
        "accessibility" to try { com.blaze.assistant.phone.BlazeAccessibilityService.isEnabled(ctx) } catch (_: Throwable) { false },
        "mic" to granted(ctx, Manifest.permission.RECORD_AUDIO),
        "overlay" to android.provider.Settings.canDrawOverlays(ctx),
        "battery" to (ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true),
        "desktop" to try { com.blaze.assistant.link.DesktopLink.paired } catch (_: Throwable) { false },
    )

    fun isDone(): Boolean = com.blaze.assistant.agent.Settings.bool(PREF_DONE, false)
    fun later(): Set<String> = parseLater(com.blaze.assistant.agent.Settings.str(PREF_LATER, ""))
    fun putOff(id: String) {
        if (dep(id)?.required != false) return   // required steps can't be put off
        com.blaze.assistant.agent.Settings.setStr(PREF_LATER, joinLater(later() + id))
    }

    /** Sets the done pref when nothing is left. Returns true when done. */
    fun markIfComplete(have: Map<String, Boolean>): Boolean {
        if (isDone()) return true
        if (!complete(have, later())) return false
        com.blaze.assistant.agent.Settings.setBool(PREF_DONE, true)
        return true
    }

    /** Should the gate show now on this phone? */
    fun shouldGate(ctx: Context): Boolean = !isDone() && shouldGate(false, probe(ctx), later()).also { show -> if (!show) markIfComplete(probe(ctx)) }

    // ---------- backup before anything runs ----------

    /** Big or regenerable things that aren't copied (Linux, voice models, caches, older backups). */
    private val skipDirs = setOf("linux", "backup", "vosk", "models", "model", "update", "designs", "media", "tmp", "cache", "code_cache", "app_webview", "webview")
    private const val MAX_FILE = 64L * 1024 * 1024

    /**
     * Copies shared_prefs and Blaze's own data files (chats, mind.db, longchat.db, vault, memories, skills…) to
     * filesDir/backup/pre-setup-v<code>-<time>/ once per build, before the gate does anything. Keeps the newest 3.
     * Returns the backup folder, or null when this build already backed up (or nothing could be written).
     */
    fun backup(ctx: Context, code: Long, now: Long = System.currentTimeMillis()): File? {
        val marker = try { com.blaze.assistant.agent.Settings.str(PREF_BACKUP, "") } catch (_: Throwable) { "" }
        if (marker == code.toString()) return null
        val root = File(ctx.filesDir, "backup").apply { mkdirs() }
        val out = File(root, "pre-setup-v$code-$now")
        return try {
            val prefs = File(ctx.applicationInfo.dataDir, "shared_prefs")
            copyTree(prefs, File(out, "shared_prefs"))
            ctx.filesDir.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name !in skipDirs) copyTree(f, File(out, "files/" + f.name))
                else if (f.isFile && f.length() <= MAX_FILE) f.copyTo(File(out, "files/" + f.name), overwrite = true)
            }
            ctx.getDatabasePath("x").parentFile?.takeIf { it.exists() }?.let { copyTree(it, File(out, "databases")) }
            root.listFiles()?.filter { it.name.startsWith("pre-setup-") }?.sortedByDescending { it.name.substringAfterLast('-').toLongOrNull() ?: 0L }
                ?.drop(3)?.forEach { it.deleteRecursively() }
            try { com.blaze.assistant.agent.Settings.setStr(PREF_BACKUP, code.toString()) } catch (_: Throwable) { }
            out
        } catch (_: Throwable) { null }
    }

    private fun copyTree(src: File, dst: File) {
        if (!src.exists()) return
        if (src.isFile) { if (src.length() <= MAX_FILE) { dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = true) }; return }
        dst.mkdirs()
        src.listFiles()?.forEach { f -> if (!(f.isDirectory && f.name in skipDirs)) copyTree(f, File(dst, f.name)) }
    }
}
