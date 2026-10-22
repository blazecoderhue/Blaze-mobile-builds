package com.blaze.assistant.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebView

/**
 * v0.33.3: pure rules for "should this navigation leave the WebView for another app?" (tested in AppLinksTest).
 * Used by the Blaze browser, the BlazeOS Browser and SiteAppActivity.
 */
object RedirectRules {
    enum class Kind {
        /** Stay in the page (normal http(s) navigation, about:, data:, blob:, javascript:). */
        STAY,
        /** intent://…#Intent;…;end: parse it, try its package, then browser_fallback_url. */
        INTENT,
        /** Any other non-web scheme (market:, mailto:, tel:, whatsapp:, hark:…): ACTION_VIEW. */
        SCHEME,
        /** A cross-site https main-frame navigation: open the app that verifiably handles it, else stay. */
        APP_LINK,
    }

    private val inPage = setOf("about", "data", "blob", "javascript", "file", "content")

    /**
     * @param url the target. @param currentUrl the page it is leaving (null/blank = none yet).
     * @param mainFrame only main-frame navigations ever leave (never subresources or iframes).
     * Same-site links stay inside (browsing github.com never bounces to the GitHub app); cross-site link taps,
     * server redirects and JS / meta-refresh redirects are all checked for an App Link.
     */
    fun classify(url: String, currentUrl: String?, mainFrame: Boolean): Kind {
        if (!mainFrame) return Kind.STAY
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme.isEmpty() || scheme in inPage) return Kind.STAY
        if (scheme == "intent") return Kind.INTENT
        if (scheme != "http" && scheme != "https") return Kind.SCHEME
        if (scheme == "http") return Kind.STAY   // App Links are https only
        val to = site(url) ?: return Kind.STAY
        val from = currentUrl?.let { site(it) } ?: return Kind.STAY   // typed / first load: never leave
        return if (from == to) Kind.STAY else Kind.APP_LINK
    }

    /** The registrable-ish site of a URL: the last two labels (three for short second-level labels like co.uk). */
    fun site(url: String): String? {
        val host = hostOf(url) ?: return null
        if (host.isEmpty()) return null
        if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return host
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val sld = parts[parts.size - 2]
        val n = if (sld.length <= 3 && parts.last().length == 2) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        var h = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        h = h.substringAfterLast('@')
        if (!h.startsWith("[")) h = h.substringBefore(':')
        return h.lowercase().trimEnd('.')
    }

    /** Loop guard: the same URL is never handed to an app twice within [windowMs]. */
    class Guard(private val windowMs: Long = 4000) {
        private var last = ""; private var at = 0L
        fun allow(url: String, now: Long): Boolean {
            if (url == last && now - at < windowMs) return false
            last = url; at = now; return true
        }
    }
}

/** Android side: actually hands a URL to another app. Never throws; false = keep it in the page. */
object AppLinks {
    private val guard = RedirectRules.Guard()

    /**
     * Call from shouldOverrideUrlLoading. Returns true when the navigation was handled (opened elsewhere, a fallback
     * loaded, or swallowed because it is a non-web scheme nobody handles), false to let the WebView load it.
     */
    fun handle(ctx: Context, view: WebView?, req: WebResourceRequest, currentUrl: String? = view?.url, appLinks: Boolean = true): Boolean {
        val url = req.url.toString()
        return when (RedirectRules.classify(url, currentUrl, req.isForMainFrame)) {
            RedirectRules.Kind.STAY -> false
            RedirectRules.Kind.INTENT -> { if (guard.allow(url, System.currentTimeMillis())) openIntentUri(ctx, view, url); true }
            RedirectRules.Kind.SCHEME -> { if (guard.allow(url, System.currentTimeMillis())) openScheme(ctx, req.url); true }
            RedirectRules.Kind.APP_LINK -> appLinks && guard.allow(url, System.currentTimeMillis()).let { ok -> ok && openAppLink(ctx, req.url) }
        }
    }

    /** For a URL that already isn't http(s) (download links, window.open of a scheme): same handling, no frame info. */
    fun openExternal(ctx: Context, view: WebView?, uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "intent" -> openIntentUri(ctx, view, uri.toString())
        "http", "https" -> openAppLink(ctx, uri)
        null -> false
        else -> openScheme(ctx, uri)
    }

    fun openIntentUri(ctx: Context, view: WebView?, url: String): Boolean {
        val i = try { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) } catch (_: Exception) { return false }
        val fallback = i.getStringExtra("browser_fallback_url")
        val pkg = i.`package`
        // Untrusted page: never let it target a component or our own package directly.
        i.component = null; i.selector = null
        i.addCategory(Intent.CATEGORY_BROWSABLE); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != ctx.packageName) {
            try { ctx.startActivity(i); return true } catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { } catch (_: Exception) { }
        }
        if (!fallback.isNullOrBlank() && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
            view?.post { view.loadUrl(fallback) }; return true
        }
        if (!pkg.isNullOrBlank() && pkg != ctx.packageName) {
            return openScheme(ctx, Uri.parse("market://details?id=$pkg"))
        }
        return false
    }

    fun openScheme(ctx: Context, uri: Uri): Boolean {
        val i = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (_: Exception) {
            try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            catch (_: Exception) { false }
        }
    }

    /** Opens an https URL in the installed app that handles it (verified App Link / specific host), never a browser or Blaze. */
    fun openAppLink(ctx: Context, uri: Uri): Boolean {
        val i = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                // Only an app that handles this host specifically; browsers (Blaze's own wildcard filter too) are refused.
                i.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
                ctx.startActivity(i); true
            } else {
                val target = nonBrowserHandler(ctx, uri) ?: return false
                i.setPackage(target); ctx.startActivity(i); true
            }
        } catch (_: ActivityNotFoundException) { false } catch (_: Exception) { false }
    }

    /** API < 30: the one app that handles [uri] but not a generic https URL (so not a browser), excluding Blaze. */
    private fun nonBrowserHandler(ctx: Context, uri: Uri): String? {
        val pm = ctx.packageManager
        val handlers = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE), 0)
            .map { it.activityInfo.packageName }.toSet()
        if (handlers.isEmpty()) return null
        val browsers = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://blaze-generic-probe.invalid/"))
            .addCategory(Intent.CATEGORY_BROWSABLE), 0).map { it.activityInfo.packageName }.toSet()
        return (handlers - browsers - ctx.packageName).firstOrNull()
    }
}
