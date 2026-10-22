package com.blaze.assistant.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.File

/**
 * Full clipboard access for Blaze.
 *
 * Android 10+ only lets the app that has input focus read the clipboard. When Blaze isn't on
 * screen we briefly add a 1-pixel focusable window (through the accessibility service, or the
 * "draw over apps" permission), read the clipboard while it has focus, and remove it again: the
 * same trick clipboard-manager apps use. Text clips Blaze sees are kept in a short history.
 */
object ClipAccess {
    private const val HISTORY = "clip_history"
    private const val MAX_HISTORY = 25

    data class Clip(val text: String, val uris: List<Pair<Uri, String>>) // (uri, mime)

    private fun cm(ctx: Context) = ctx.getSystemService(ClipboardManager::class.java)

    /** Run [block] while Blaze holds input focus, so Android allows clipboard reads. */
    private suspend fun <T> withFocus(ctx: Context, block: () -> T): T = withContext(Dispatchers.Main) {
        if (Agent.inForeground) return@withContext block()
        val (host, type) = BlazeAccessibilityService.instance?.let { it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
            ?: if (android.provider.Settings.canDrawOverlays(ctx)) ctx.applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else return@withContext block() // no way to take focus; try anyway (works on older Android)
        val wm = host.getSystemService(WindowManager::class.java)
        val focused = CompletableDeferred<Unit>()
        val v = object : View(host) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) focused.complete(Unit)
            }
        }
        val lp = WindowManager.LayoutParams(1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(v, lp)
            withTimeoutOrNull(800) { focused.await() }
            block()
        } finally {
            try { wm.removeView(v) } catch (_: Exception) { }
        }
    }

    /** What's on the clipboard now: its text and any files/images. */
    suspend fun read(ctx: Context): Clip? {
        val clip = try { withFocus(ctx) { cm(ctx).primaryClip } } catch (_: Exception) { null } ?: return null
        val d = clip.description
        val mime = (0 until d.mimeTypeCount).map { d.getMimeType(it) }.firstOrNull { it != "text/plain" && it != "text/html" }.orEmpty()
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri?.let { u -> u to mime } }
        val text = (0 until clip.itemCount).mapNotNull { i ->
            val item = clip.getItemAt(i)
            if (item.uri != null && item.text == null) null else item.coerceToText(ctx)?.toString()
        }.joinToString("\n").trim()
        // Passwords and codes copied from password managers are flagged sensitive: never keep those.
        val sensitive = d.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        if (text.isNotEmpty() && !sensitive) remember(text)
        return Clip(text, uris)
    }

    fun setText(ctx: Context, text: String) {
        cm(ctx).setPrimaryClip(ClipData.newPlainText("Blaze", text))
        remember(text)
    }

    /** Put an image (or any file) on the clipboard so it can be pasted into another app. */
    fun setFile(ctx: Context, f: File): String {
        val dir = File(ctx.cacheDir, "clip").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val copy = File(dir, f.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        f.copyTo(copy, overwrite = true)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", copy)
        cm(ctx).setPrimaryClip(ClipData.newUri(ctx.contentResolver, copy.name, uri))
        return "Copied ${f.name} to the clipboard. Paste it anywhere with a long-press › Paste."
    }

    fun clear(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 28) cm(ctx).clearPrimaryClip() else cm(ctx).setPrimaryClip(ClipData.newPlainText("", ""))
    }

    private fun remember(text: String) {
        val t = text.take(2000)
        val old = runCatching { JSONArray(Settings.str(HISTORY, "[]")) }.getOrDefault(JSONArray())
        val out = JSONArray().put(t)
        for (i in 0 until old.length()) if (old.optString(i) != t && out.length() < MAX_HISTORY) out.put(old.optString(i))
        Settings.setStr(HISTORY, out.toString())
    }

    fun history(): List<String> = runCatching { JSONArray(Settings.str(HISTORY, "[]")).let { a -> (0 until a.length()).map { a.optString(it) } } }.getOrDefault(emptyList())

    fun clearHistory() = Settings.setStr(HISTORY, "[]")
}
