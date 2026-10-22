package com.blaze.assistant.siteapps

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * v0.33.2 an installed website as an app (outside BlazeOS): opened from its pinned launcher icon. No URL bar or browser
 * chrome; the status / navigation bars take the manifest's theme_color; a splash with the icon and name on its
 * background_color until the first page has loaded; its own task in Recents (setTaskDescription with its name, icon and
 * colour; a distinct document per site); its own WebView profile (cookies and storage) when the phone's WebView supports
 * profiles; Back goes back inside the app; links off its scope open in Blaze's browser.
 */
class SiteAppActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ID = "site_id"
        fun profile(id: String) = "site-" + id.replace(Regex("[^A-Za-z0-9._-]"), "-")

        /** Uninstall: the app's own profile is emptied (never Blaze's browser data). */
        fun wipeProfile(id: String) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
            try {
                val p = ProfileStore.getInstance().getOrCreateProfile(profile(id))
                p.cookieManager.removeAllCookies(null); p.webStorage.deleteAllData()
            } catch (_: Exception) { }
        }
    }

    private var web: WebView? = null
    private var splash: View? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID) ?: intent.data?.lastPathSegment.orEmpty()
        val app = SiteInstaller.find(this, id)
        if (app == null) { Toast.makeText(this, "This app was uninstalled.", Toast.LENGTH_SHORT).show(); finishAndRemoveTask(); return }
        val icon = SiteInstaller.icon(this, id)
        val theme = parse(app.themeColor, Color.WHITE)
        val bg = parse(app.backgroundColor, theme)

        // Bars in the app's colour, icons dark or light to match.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        @Suppress("DEPRECATION") run { window.statusBarColor = theme; window.navigationBarColor = bg }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !SiteLogic.isDark(app.themeColor.ifEmpty { "#FFFFFF" })
            isAppearanceLightNavigationBars = !SiteLogic.isDark(app.backgroundColor.ifEmpty { "#FFFFFF" })
        }
        // Its own Recents entry.
        try {
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(app.name, icon, theme or 0xFF000000.toInt()))
        } catch (_: Exception) { }

        val w = WebView(this)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) { try { WebViewCompat.setProfile(w, profile(id)) } catch (_: Exception) { } }
        w.setBackgroundColor(bg)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            setSupportMultipleWindows(false)
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (SiteLogic.inScope(u, app.scope)) return false
                // v0.33.3: intent://, market:, mailto:, tel:… and https links another installed app verifiably handles
                // go to that app; other out-of-scope pages open in the Blaze browser. Subframes stay in the page.
                if (!req.isForMainFrame) return false
                if (com.blaze.assistant.browser.AppLinks.handle(this@SiteAppActivity, view, req, view.url ?: app.startUrl)) return true
                val sc = req.url.scheme.orEmpty().lowercase()
                if (sc == "http" || sc == "https") openOutside(req.url)
                return true
            }
            override fun onPageFinished(view: WebView, url: String?) { hideSplash() }
        }
        w.setDownloadListener { url, _, _, _, _ -> openOutside(Uri.parse(url)) }

        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        root.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val s = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(bg)
            val d = resources.displayMetrics.density
            addView(ImageView(context).apply {
                setImageBitmap(icon)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, v.width * 0.22f) }
                }
            }, LinearLayout.LayoutParams((96 * d).toInt(), (96 * d).toInt()))
            addView(TextView(context).apply {
                text = app.name; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(if (SiteLogic.isDark(app.backgroundColor.ifEmpty { "#FFFFFF" })) Color.WHITE else Color.parseColor("#111114"))
                setPadding(0, (16 * d).toInt(), 0, 0)
            })
        }
        root.addView(s, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        splash = s
        setContentView(root)
        web = w
        // The splash never stays more than 6 s (a slow or failing page shows what it has).
        root.postDelayed({ hideSplash() }, 6000)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { val v = web; if (v != null && v.canGoBack()) v.goBack() else finish() }
        })
        if (savedInstanceState != null) w.restoreState(savedInstanceState) ?: w.loadUrl(app.startUrl) else w.loadUrl(app.startUrl)
    }

    private fun parse(hex: String, def: Int): Int = try { if (hex.isBlank()) def else Color.parseColor(hex) } catch (_: Exception) { def }

    private fun hideSplash() {
        val s = splash ?: return
        splash = null
        s.animate().alpha(0f).setDuration(220).withEndAction { (s.parent as? ViewGroup)?.removeView(s) }.start()
    }

    /** Off-scope links: http(s) in Blaze's browser, anything else (mailto:, tel:) to the phone. */
    private fun openOutside(u: Uri) {
        try {
            if (u.scheme == "http" || u.scheme == "https")
                startActivity(Intent(Intent.ACTION_VIEW, u).setClass(this, com.blaze.assistant.browser.BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else com.blaze.assistant.browser.AppLinks.openExternal(this, web, u)
        } catch (_: Exception) { }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web?.saveState(outState) }
    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
}
