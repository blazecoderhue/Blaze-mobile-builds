package com.blaze.assistant.browser.phone

import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Rational
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.blaze.assistant.ui.Glass
import com.blaze.assistant.ui.PhoneFrame
import com.blaze.assistant.ui.Type
import com.blaze.assistant.ui.phonePalette

/**
 * 0.45.19 fix 4, the Android side of [PhonePip]: the picture-in-picture params (aspect, Stop / Open Blaze actions,
 * auto-enter on API 31+), entering on onUserLeaveHint below API 31, the live-view-only content, and what Stop does.
 * Used by MainActivity (the chat) and PhoneBrowserActivity (the Blaze Phone screen).
 */
object PhonePipHost {
    // minSdk is 26, so every picture-in-picture call here exists; only auto-enter needs API 31.
    fun supported(ctx: Context): Boolean =
        try { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) } catch (_: Throwable) { false }

    /** Blaze works on Blaze Phone right now. */
    fun working(): Boolean = PhonePip.working(PhoneBrowser.live.value.mode, PhoneBrowser.ownerChat,
        try { com.blaze.assistant.agent.Agent.busyChats.value } catch (_: Throwable) { emptySet() })

    private fun icon(action: String): Int =
        if (action == PhonePip.ACTION_STOP) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_view

    private fun pending(ctx: Context, action: String): PendingIntent = PendingIntent.getBroadcast(ctx,
        if (action == PhonePip.ACTION_STOP) PhonePip.REQ_STOP else PhonePip.REQ_OPEN,
        Intent(ctx, PhonePipReceiver::class.java).setAction(action).setPackage(ctx.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun params(a: Activity, working: Boolean, autoEnter: Boolean): PictureInPictureParams {
        val (w, h) = PhonePip.aspect(PhoneBrowser.frameW, PhoneBrowser.frameH)
        val acts = PhonePip.actions(working).map { id ->
            RemoteAction(Icon.createWithResource(a, icon(id)), PhonePip.title(id), PhonePip.title(id), pending(a, id)) }
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(w, h)).setActions(acts)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(false)
        return b.build()
    }

    /** Keeps [a]'s params current (auto-enter only while Blaze works and Blaze isn't locked). */
    fun update(a: Activity, working: Boolean, locked: Boolean) {
        if (!supported(a)) return
        val wanted = PhonePip.wanted(working, true, Build.VERSION.SDK_INT, locked)
        try { a.setPictureInPictureParams(params(a, working, PhonePip.autoEnter(Build.VERSION.SDK_INT) && wanted)) }
        catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("PhonePip.update", t) }
    }

    /** Below API 31: the user is leaving (Home, Recents) while Blaze works, so enter now. True when it did. */
    fun enterOnLeave(a: Activity, locked: Boolean, launchingOwnScreen: Boolean): Boolean {
        if (!supported(a)) return false
        val w = working()
        if (!PhonePip.enterOnLeaveHint(Build.VERSION.SDK_INT, PhonePip.wanted(w, true, Build.VERSION.SDK_INT, locked), launchingOwnScreen)) return false
        return try { a.enterPictureInPictureMode(params(a, w, false)) }
        catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("PhonePip.enter", t); false }
    }

    /** Stop on the window: really cancels the turn driving Blaze Phone (its background agent too). Main thread. */
    fun stop(): Boolean = PhoneBrowser.stopTask(
        stopChat = { com.blaze.assistant.agent.Agent.stopChat(it) },
        cancelAgent = { com.blaze.assistant.agent.BgAgents.cancel(it) })

    /** Open Blaze (and tapping the window's expand): bring the chat's task to the front, full screen. */
    fun open(ctx: Context) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val main = com.blaze.assistant.MainActivity::class.java.name
        val t = try { am?.appTasks?.firstOrNull { it.taskInfo?.baseActivity?.className == main } } catch (_: Throwable) { null }
        val moved = try { t?.moveToFront(); t != null } catch (_: Throwable) { false }
        if (!moved) try {
            ctx.startActivity(Intent(ctx, com.blaze.assistant.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (t2: Throwable) { com.blaze.assistant.device.CrashLog.quiet("PhonePip.open", t2) }
    }
}

/** The window's Stop / Open Blaze buttons (RemoteAction broadcasts; not exported). */
class PhonePipReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            when (i.action) {
                PhonePip.ACTION_STOP -> PhonePipHost.stop()
                PhonePip.ACTION_OPEN -> PhonePipHost.open(app)
            }
        }
    }
}

/** What the picture-in-picture window shows: only Blaze Phone's live view (and one short line when it matters). */
@Composable
fun PhonePipView() {
    val live by PhoneBrowser.live.collectAsState()
    val busy by com.blaze.assistant.agent.Agent.busyChats.collectAsState()
    val working = PhonePip.working(live.mode, PhoneBrowser.ownerChat, busy)
    val pal = phonePalette()
    DisposableEffect(Unit) { PhoneBrowser.watch(true); onDispose { PhoneBrowser.watch(false) } }
    Box(Modifier.fillMaxSize().background(Color(pal.frame))) {
        PhoneFrame(live.frameNo, Modifier.fillMaxSize(), pal)
        PhonePip.label(working, live.mode, live.step)?.let { t ->
            Text(t, Modifier.align(Alignment.BottomCenter).padding(6.dp).clip(Glass.pill).background(Color(pal.chip)).padding(horizontal = 8.dp, vertical = 3.dp),
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = Color(pal.onChip)))
        }
    }
}
