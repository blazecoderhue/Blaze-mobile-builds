package com.blaze.assistant.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.blaze.assistant.device.CameraFlow
import com.blaze.assistant.device.CameraFlow.Step
import com.blaze.assistant.device.CameraSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.28.x camera-fix: chat "+ → Camera" on every phone ([CameraFlow] decides, this runs it).
 * Camera permission (asked, explained, or sent to Settings) → the phone's camera app with explicit URI grants →
 * Blaze's own CameraX camera → the photo picker. Every failure is logged with its real exception (CameraSupport.log
 * → Settings → Device & diagnostics → crash report) and the toast says what actually happened.
 * The pending photo path survives Blaze being killed behind the camera app (common on low-RAM phones).
 *
 * Returns the function to call from the + menu; [Dialogs] must be placed once in the screen.
 */
class CameraCapture internal constructor(val open: () -> Unit, val dialogs: @Composable () -> Unit)

private class Runner { var go: (externalFailed: Boolean, inAppFailed: Boolean) -> Unit = { _, _ -> } }

private fun Context.activity(): Activity? { var c: Context? = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }

@Composable
fun rememberCameraCapture(onPhoto: (Uri) -> Unit, onPickPhoto: () -> Unit): CameraCapture {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var launchedAt by rememberSaveable { mutableStateOf(0L) }
    var dialog by remember { mutableStateOf<Step?>(null) }
    val runner = remember { Runner() }

    fun file(): File? = path?.let { File(it) }
    fun uriOf(f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    fun deliver(f: File) = scope.launch {
        withContext(Dispatchers.IO) { CameraSupport.normalize(f) }
        onPhoto(uriOf(f))
    }

    val inApp = rememberLauncherForActivityResult(InAppCameraContract()) { r ->
        val f = file()
        when {
            f != null && CameraSupport.bytes(f) > 0 -> deliver(f)
            r.error != null && !r.error.startsWith("PermissionDenied") -> {
                val kind = r.error.substringBefore('|'); val msg = r.error.substringAfter('|', "")
                CameraSupport.log("in-app camera gave up: $kind: $msg")
                toast(CameraFlow.inAppFailNote(kind, msg))
                runner.go(true, true)
            }
            else -> CameraSupport.log("in-app camera closed without a photo")
        }
    }
    val external = rememberLauncherForActivityResult(CaptureContract()) { ok ->
        val f = file() ?: return@rememberLauncherForActivityResult
        val outcome = CameraFlow.externalOutcome(ok, CameraSupport.bytes(f), System.currentTimeMillis() - launchedAt)
        CameraSupport.log("camera app returned ok=$ok bytes=${CameraSupport.bytes(f)} → $outcome")
        when (outcome) {
            CameraFlow.Outcome.PHOTO -> deliver(f)
            CameraFlow.Outcome.CANCELLED -> { }
            CameraFlow.Outcome.BROKEN -> {
                CameraSupport.preferInApp = true
                toast(CameraFlow.externalFailNote("Broken", null))
                runner.go(true, false)
            }
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        CameraSupport.askedBefore = true
        CameraSupport.log("camera permission ${if (ok) "granted" else "denied"}")
        if (ok) runner.go(false, false)
        else { dialog = CameraFlow.afterDenied(ctx.activity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true) }
    }

    runner.go = { externalFailed, inAppFailed ->
        val state = CameraFlow.State(
            hasCamera = CameraSupport.hasCamera(ctx),
            permissionDeclared = CameraSupport.permissionDeclared(ctx),
            permissionGranted = CameraSupport.granted(ctx),
            askedBefore = CameraSupport.askedBefore,
            showRationale = ctx.activity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true,
            externalHandler = CameraSupport.hasCaptureHandler(ctx),
            preferInApp = CameraSupport.preferInApp,
            externalFailed = externalFailed,
            inAppFailed = inAppFailed)
        val step = CameraFlow.next(state)
        CameraSupport.log("camera step $step for $state")
        when (step) {
            Step.PICKER -> {
                if (!state.hasCamera) toast("This phone has no camera, so pick a photo instead.")
                onPickPhoto()
            }
            Step.REQUEST_PERMISSION -> perm.launch(Manifest.permission.CAMERA)
            Step.RATIONALE, Step.SETTINGS -> { dialog = step }
            Step.EXTERNAL -> {
                val (f, u) = CameraSupport.newPhoto(ctx)
                path = f.absolutePath; launchedAt = System.currentTimeMillis()
                try { external.launch(u) } catch (e: Exception) {
                    CameraSupport.log("camera app launch failed", e)
                    if (e is android.content.ActivityNotFoundException || e is SecurityException) CameraSupport.preferInApp = true
                    toast(CameraFlow.externalFailNote(e.javaClass.name, e.message))
                    runner.go(true, inAppFailed)
                }
            }
            Step.IN_APP -> {
                val f = CameraSupport.newPhoto(ctx).first
                path = f.absolutePath
                try { inApp.launch(f) } catch (e: Exception) {
                    CameraSupport.log("in-app camera launch failed", e)
                    runner.go(externalFailed, true)
                }
            }
        }
    }

    return CameraCapture(open = { runner.go(false, false) }, dialogs = {
        when (dialog) {
            Step.RATIONALE -> BlazeDialog(title = "Allow the camera?",
                body = "Android needs Blaze to have the Camera permission before any camera can take a picture for your chat. Nothing is recorded unless you press the shutter.",
                confirm = "Allow", onConfirm = { dialog = null; perm.launch(Manifest.permission.CAMERA) },
                dismiss = "Pick a photo", onDismiss = { dialog = null; onPickPhoto() })
            Step.SETTINGS -> BlazeDialog(title = "Camera is off for Blaze",
                body = "Camera access was turned off for Blaze. Switch it on in Settings → Permissions → Camera, then tap Camera again. Or pick a photo you already have.",
                confirm = "Open Settings", onConfirm = { dialog = null; openAppSettings(ctx) },
                dismiss = "Pick a photo", onDismiss = { dialog = null; onPickPhoto() })
            else -> { }
        }
    })
}
