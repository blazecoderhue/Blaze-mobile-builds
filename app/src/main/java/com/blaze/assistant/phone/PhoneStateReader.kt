package com.blaze.assistant.phone

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.provider.Settings

/**
 * 0.45.19: the phone's own state for [StateCheck] (verify by state, not by a switch node that a configuration change
 * just made stale). Every reading is cheap (a system-service getter or a settings row) and returns null when it
 * can't tell.
 */
class PhoneStateReader(ctx: Context) : StateCheck.Reader {
    private val app = ctx.applicationContext

    override fun isOn(s: StateCheck.Setting): Boolean? = try {
        when (s) {
            StateCheck.Setting.DARK_MODE -> {
                val um = app.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
                // the system configuration as it is NOW (Resources.getSystem() follows config changes; the app's own
                // resources may be pinned to the app's theme)
                val cfg = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                val configNight = when (cfg) { Configuration.UI_MODE_NIGHT_YES -> true; Configuration.UI_MODE_NIGHT_NO -> false; else -> null }
                StateCheck.darkFrom(um?.nightMode ?: -1, configNight)
            }
            StateCheck.Setting.WIFI -> (app.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.isWifiEnabled
            StateCheck.Setting.BLUETOOTH -> (app.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled
            StateCheck.Setting.AIRPLANE -> Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            StateCheck.Setting.DND -> (app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).currentInterruptionFilter.let {
                it != android.app.NotificationManager.INTERRUPTION_FILTER_ALL && it != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            StateCheck.Setting.MOBILE_DATA -> Settings.Global.getInt(app.contentResolver, "mobile_data", -1).takeIf { it >= 0 }?.let { it != 0 }
            StateCheck.Setting.LOCATION -> if (android.os.Build.VERSION.SDK_INT >= 28)
                (app.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager)?.isLocationEnabled else null
            StateCheck.Setting.AUTO_ROTATE -> Settings.System.getInt(app.contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1).takeIf { it >= 0 }?.let { it != 0 }
            StateCheck.Setting.BATTERY_SAVER -> (app.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode
            StateCheck.Setting.NFC -> android.nfc.NfcAdapter.getDefaultAdapter(app)?.isEnabled
            StateCheck.Setting.SILENT, StateCheck.Setting.VIBRATE, StateCheck.Setting.SOUND -> StateCheck.ringerOn(s, ringerMode())
        }
    } catch (_: Throwable) { null }

    override fun brightness(): Int? = try { Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Throwable) { null }

    override fun ringerMode(): Int? = try { (app.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.ringerMode } catch (_: Throwable) { null }

    override fun volume(stream: Int): Pair<Int, Int>? = try {
        (app.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.let { it.getStreamVolume(stream) to it.getStreamMaxVolume(stream) }
    } catch (_: Throwable) { null }
}

/**
 * 0.45.19: verify a switch tap toward [want] and give the tool result's marker (ActCheck), so the reply can't
 * contradict itself. State first ([PhoneStateReader]); for an in-app switch the node is re-found (the old one may be
 * stale after the screen redrew) and its checked state read again. Polls with a timeout instead of a fixed settle.
 */
object ToggleVerify {
    suspend fun after(ctx: Context, svc: BlazeAccessibilityService, label: String, wantId: Int, want: Boolean, timeoutMs: Long = 1500): StateCheck.Result {
        var tries = 0
        return StateCheck.verifyToggle(label, want, PhoneStateReader(ctx), reread = {
            tries++
            // the same node first (cheap); when it is gone or stale, find the switch again by its label
            svc.switchState(wantId) ?: if (label.isNotBlank() && tries % 2 == 0) svc.findTarget(label)?.let { svc.switchState(it) } else null
        }, sleep = { ms -> svc.awaitEvent(ms) }, timeoutMs = timeoutMs)
    }

    /** The tool result's ending for [r]: "Checked on the phone: …" or one honest "Not confirmed: …". */
    fun marker(r: StateCheck.Result): String = when (r.status) {
        StateCheck.Status.VERIFIED -> com.blaze.assistant.agent.ActCheck.checked(StateCheck.evidence(r))
        StateCheck.Status.CONTRADICTED -> com.blaze.assistant.agent.ActCheck.notConfirmed(
            "${r.word} still reads ${StateCheck.onOff(!r.want)}", "read_screen, then tap the switch itself")
        StateCheck.Status.UNKNOWN -> com.blaze.assistant.agent.ActCheck.notConfirmed(StateCheck.unchecked(r), "read_screen and check it")
    }
}
