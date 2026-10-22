package com.blaze.assistant.siteapps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v0.33.2 "Install app" for websites, the Android side. [inspect] reads the page and its web app manifest and fetches
 * the real icon in SiteLogic.iconOrder (manifest icons → apple-touch-icon → largest rel=icon → favicon.ico), drawing a
 * letter icon only when none decodes. Then:
 * - outside BlazeOS ([installOnPhone]): saved in filesDir/siteapps/ and pinned as a real launcher shortcut
 *   (ShortcutManagerCompat.requestPinShortcut, IconCompat bitmap) that opens [SiteAppActivity];
 * - inside BlazeOS ([installInBlazeOs]): an OsRegistry web-app entry with the icon and its own data folder.
 */
object SiteInstaller {
    const val ICON_PX = 192
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    data class Inspected(val app: SiteApp, val icon: Bitmap, val iconSource: String, val maskable: Boolean)

    private fun get(url: String, max: Int): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        if (c.responseCode !in 200..299) null else c.inputStream.use { input ->
            val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
            while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > max) return null; out.write(buf, 0, n) }
            out.toByteArray()
        }
    } catch (_: Exception) { null }

    private fun decode(bytes: ByteArray): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth < 16 || o.outHeight < 16) null else {
            var s = 1
            while (minOf(o.outWidth, o.outHeight) / (s * 2) >= ICON_PX) s *= 2
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            val side = minOf(b.width, b.height)
            val sq = if (b.width != b.height) Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side) else b
            if (side != ICON_PX) Bitmap.createScaledBitmap(sq, ICON_PX, ICON_PX, true) else sq
        }
    } catch (_: Throwable) { null }

    /** The fallback when a site has no usable icon: its first letter on a colour (the theme colour when it has one). */
    fun letterIcon(name: String, host: String, theme: String): Bitmap {
        val b = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val bg = (SiteLogic.letterColor(host, theme).removePrefix("#").toLongOrNull(16) ?: 0x1971C2L).toInt() or 0xFF000000.toInt()
        c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (SiteLogic.isDark(SiteLogic.letterColor(host, theme))) 0xFFFFFFFF.toInt() else 0xFF111114.toInt()
            textSize = ICON_PX * 0.5f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val y = ICON_PX / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(SiteLogic.letter(name), ICON_PX / 2f, y, p)
        return b
    }

    /** Reads [pageUrl] (https only) and gathers everything an installed app needs. Null with the reason in the pair. */
    suspend fun inspect(pageUrl: String): Pair<Inspected?, String> = withContext(Dispatchers.IO) {
        if (!pageUrl.startsWith("https://")) return@withContext null to "Only secure (https) sites can be installed as apps."
        val html = get(pageUrl, 2 * 1024 * 1024)?.toString(Charsets.UTF_8).orEmpty()
        val head = SiteLogic.parseHead(html, pageUrl)
        val manifest = head.manifestUrl.takeIf { it.startsWith("https://") }?.let { u -> get(u, 512 * 1024)?.let { SiteLogic.parseManifest(it.toString(Charsets.UTF_8), u) } }
        val app = SiteLogic.appFor(manifest, head, pageUrl, System.currentTimeMillis())
        for (c in SiteLogic.iconOrder(manifest, head, pageUrl).take(8)) {
            if (!c.url.startsWith("https://")) continue
            val bmp = get(c.url, 3 * 1024 * 1024)?.let { decode(it) } ?: continue
            return@withContext Inspected(app, bmp, c.source, c.maskable) to ""
        }
        Inspected(app, letterIcon(app.name, SiteLogic.hostOf(app.startUrl), app.themeColor), "letter", false) to ""
    }

    fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    // ---------------- outside BlazeOS: the phone's launcher ----------------

    private fun root(ctx: Context) = File(ctx.filesDir, "siteapps")
    private fun listFile(ctx: Context) = File(root(ctx), "apps.json")
    fun iconFile(ctx: Context, id: String) = File(File(root(ctx), id), "icon.png")

    fun installed(ctx: Context): List<SiteApp> = SiteApp.listFromJson(try { listFile(ctx).takeIf { it.exists() }?.readText() } catch (_: Exception) { null })
    fun find(ctx: Context, id: String): SiteApp? = installed(ctx).firstOrNull { it.id == id }
    fun icon(ctx: Context, id: String): Bitmap? = try { BitmapFactory.decodeFile(iconFile(ctx, id).absolutePath) } catch (_: Throwable) { null }

    private fun save(ctx: Context, list: List<SiteApp>) {
        root(ctx).mkdirs()
        val tmp = File(root(ctx), "apps.json.tmp"); tmp.writeText(SiteApp.listToJson(list)); if (!tmp.renameTo(listFile(ctx))) { listFile(ctx).writeText(SiteApp.listToJson(list)); tmp.delete() }
    }

    fun intent(ctx: Context, id: String): Intent = Intent(ctx, SiteAppActivity::class.java).setAction(Intent.ACTION_VIEW)
        // A distinct data uri per site gives each installed app its own task in Recents (documentLaunchMode).
        .setData(Uri.parse("blaze-site://app/$id")).putExtra(SiteAppActivity.EXTRA_ID, id)

    /**
     * Saves the app and asks the launcher to pin it. Returns a line for the user: pinned, or (when the launcher can't
     * pin) where to open it from.
     */
    suspend fun installOnPhone(ctx: Context, ins: Inspected, name: String = ins.app.name): String = withContext(Dispatchers.IO) {
        val app = ins.app.copy(name = name.trim().ifEmpty { ins.app.name }.take(30))
        iconFile(ctx, app.id).parentFile?.mkdirs()
        iconFile(ctx, app.id).writeBytes(png(ins.icon))
        save(ctx, installed(ctx).filter { it.id != app.id } + app)
        val icon = if (ins.maskable) IconCompat.createWithAdaptiveBitmap(ins.icon) else IconCompat.createWithBitmap(ins.icon)
        val info = ShortcutInfoCompat.Builder(ctx, app.id).setShortLabel(app.name).setLongLabel(app.name).setIcon(icon).setIntent(intent(ctx, app.id)).build()
        val ok = try { ShortcutManagerCompat.isRequestPinShortcutSupported(ctx) && ShortcutManagerCompat.requestPinShortcut(ctx, info, null) } catch (_: Exception) { false }
        if (ok) "Adding ${app.name} to your home screen." else "Installed ${app.name}. Your launcher doesn't let apps pin icons; open it from the browser menu › Installed apps."
    }

    /** Uninstall (outside BlazeOS): the shortcut is disabled, the app's files go and its own WebView profile is wiped. */
    fun uninstall(ctx: Context, id: String) {
        try { ShortcutManagerCompat.disableShortcuts(ctx, listOf(id), "This app was uninstalled") } catch (_: Exception) { }
        try { ShortcutManagerCompat.removeDynamicShortcuts(ctx, listOf(id)) } catch (_: Exception) { }
        try { File(root(ctx), id).deleteRecursively() } catch (_: Exception) { }
        save(ctx, installed(ctx).filter { it.id != id })
        SiteAppActivity.wipeProfile(id)
    }

    // ---------------- inside BlazeOS ----------------

    /** Adds the site to BlazeOS: an OsRegistry web-app entry (hosted url), its icon file and its own data folder. Null = done. */
    suspend fun installInBlazeOs(ins: Inspected, name: String = ins.app.name): String? {
        val a = ins.app
        val m = com.blaze.assistant.blazeos.OsManifestData(
            id = a.id, name = name.trim().ifEmpty { a.name }.take(30), version = "1.0", versionCode = 1,
            icon = com.blaze.assistant.blazeos.OsIcon(file = "icon.png"),
            entry = com.blaze.assistant.blazeos.OsEntry(com.blaze.assistant.blazeos.OsManifest.WEB, url = a.startUrl),
            description = "Installed from ${SiteLogic.hostOf(a.startUrl)}", author = SiteLogic.hostOf(a.startUrl), category = "Websites",
            themeColor = a.themeColor, backgroundColor = a.backgroundColor, scope = a.scope)
        return com.blaze.assistant.blazeos.BlazeOs.installSite(m, png(ins.icon))
    }
}
