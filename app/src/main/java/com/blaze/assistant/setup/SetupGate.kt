package com.blaze.assistant.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.io.File

/**
 * What Blaze can use on this phone (Settings › Permissions & setup) and the one-time data backup per build.
 *
 * 0.45.23: no launch gate any more. 0.24.4 to 0.45.22 blocked the app on first run with required columns (notifications,
 * the Linux download) and optional ones; now nothing is asked up front: each is asked or installed when a task first
 * needs it (notifications: NotifyAsk; Linux: the agent's linux_install / the terminal; phone control: PermissionGate), and
 * all of them stay on the Settings page. The list and probe are pure enough to test without a phone.
 */
object SetupGate {
    const val PREF_DONE = "setup_deps_done"
    const val PREF_BACKUP = "setup_backup_v"

    /** [required] = Blaze can't do its core work without it (shown as such on the Settings page; never blocks the app). */
    data class Dep(val id: String, val title: String, val why: String, val required: Boolean)

    /** Order is the order of the columns on screen. */
    val deps = listOf(
        Dep("notifications", "Notifications", "So Blaze can show what it's doing (with Stop) and tell you when a task is done.", required = true),
        Dep("linux", "Terminal (Linux)", "A small Linux on the phone (about 3.5 MB) for scripts, files, code and the tools Blaze runs.", required = true),
        // 0.45.20 (item 15): no "accessibility" step; the first task that needs phone control asks in chat (agent/PermissionGate).
        Dep("mic", "Microphone", "For talking to Blaze and \u201cHey Blaze\u201d.", required = false),
        Dep("overlay", "Show over other apps", "So Blaze can pop up wherever you are.", required = false),
        Dep("battery", "Keep working in the background", "Stops the phone putting Blaze to sleep halfway through a job.", required = false),
        Dep("desktop", "Pair with Blaze Desktop", "Share chats, keys and files with Blaze on your computer (scan the code in Blaze Desktop \u203a Phone).", required = false),
    )

    fun dep(id: String): Dep? = deps.firstOrNull { it.id == id }

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

    /** 0.45.22 and earlier set this once their launch gate finished; it now only decides the one-time backup (BlazeApp). */
    fun isDone(): Boolean = com.blaze.assistant.agent.Settings.bool(PREF_DONE, false)

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
