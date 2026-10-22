package com.blaze.assistant.blazeos

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.blaze.assistant.ui.BlazeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * v0.33.2 BlazeOS fullscreen: the host window for the virtual phone. Immersive sticky (system bars hidden, they come
 * back for a moment on an edge swipe), no Blaze chrome or chat, the screen kept on while BlazeOS boots or runs, the
 * volume keys routed to BlazeOS's own volume, Back to BlazeOS's back, BlazeOS's brightness on this window only.
 *
 * Android apps can't catch the physical power button, so BlazeOS has its own power menu (long-press the status pill,
 * or the control centre); pressing the real power button or leaving Blaze pauses BlazeOS (onStop).
 */
class BlazeOsActivity : ComponentActivity() {
    companion object {
        fun open(ctx: Context) {
            BlazeOs.open()
            ctx.startActivity(Intent(ctx, BlazeOsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private var hostAsk: CompletableDeferred<Boolean>? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> hostAsk?.complete(ok); hostAsk = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        immersive()
        BlazeOs.hostRequester = { perm ->
            val d = CompletableDeferred<Boolean>()
            hostAsk?.complete(false)
            hostAsk = d
            try { permLauncher.launch(perm) } catch (_: Exception) { d.complete(false) }
            d.await()
        }
        if (savedInstanceState == null) BlazeOs.open()
        setContent { BlazeTheme { BlazeOsScreen(onExit = { exitToBlaze() }) } }
        // Screen on while booting / on; BlazeOS brightness on this window only.
        lifecycleScope.launch {
            combine(BlazeOs.power, BlazeOs.prefs) { p, pr -> p to pr.brightness }.collect { (p, b) ->
                if (OsMachine.keepScreenOn(p)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.attributes = window.attributes.apply { screenBrightness = (b.coerceIn(OsPrefs.MIN_BRIGHTNESS, 100) / 100f) }
            }
        }
    }

    private fun immersive() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onStart() {
        super.onStart()
        // Back from a Blaze screen BlazeOS opened (Video, Browser, Terminal, Camera): BlazeOS was never paused.
        BlazeOs.handoff = false
        if (BlazeOs.power.value == Power.PAUSED) BlazeOs.resume()
    }

    override fun onStop() {
        // The real power button, Recents, Home or another app: BlazeOS pauses (not while a BlazeOS app opened a Blaze screen).
        if (!BlazeOs.handoff && !isChangingConfigurations) BlazeOs.pause()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) BlazeOs.hostRequester = null
        super.onDestroy()
    }

    /** The volume keys change BlazeOS's own volume (shown as BlazeOS's volume bar), never the host's. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val step = 100 / 15 + 1
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { BlazeOs.setVolume(BlazeOs.prefs.value.volume + step); OsUiState.showVolume(); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { BlazeOs.setVolume(BlazeOs.prefs.value.volume - step); OsUiState.showVolume(); return true }
            KeyEvent.KEYCODE_VOLUME_MUTE -> { BlazeOs.setVolume(0); OsUiState.showVolume(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) return true
        return super.onKeyUp(keyCode, event)
    }

    /** Exit to Blaze: BlazeOS pauses (its state kept) and Blaze's chat shows again. */
    private fun exitToBlaze() {
        BlazeOs.pause()
        finish()
    }
}
