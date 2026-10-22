package com.blaze.assistant.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Timer10
import androidx.compose.material.icons.rounded.Timer3
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.blaze.assistant.device.BlazeCamera
import com.blaze.assistant.device.CameraFlow
import com.blaze.assistant.device.CameraModes
import com.blaze.assistant.device.CameraModes.Mode
import com.blaze.assistant.device.CameraModes.Origin
import com.blaze.assistant.device.CameraModes.Route
import com.blaze.assistant.device.CameraSupport
import com.blaze.assistant.device.CameraTimer
import com.blaze.assistant.device.CameraZoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * v0.28.x Blaze Camera (CameraX). Two jobs:
 *  1. The fallback for + → Camera when the phone's camera app can't be used (none installed / exported, blocked by
 *     a work profile, Secure Folder or policy, or it ignored EXTRA_OUTPUT): Origin.CHAT_ATTACH, one photo into the
 *     file the chat passed, no mode bar.
 *  2. Blaze Camera with a mode bar (Scan · Text · Ask · Photo · Translate · Document), opened from + → Blaze Camera,
 *     Magic Hand, the blaze_camera tool, or a plain request ("read this sign"). Where a capture goes is
 *     [CameraModes.route]; [BlazeCamera.deliver] hands it over.
 * Preview, shutter, front/back flip only when both lenses exist, flash only when the lens has one, pinch-zoom and
 * tap-to-focus, its own permission handling, ~1600 px upright JPEGs (1280 px on low-RAM phones), insets-aware.
 * v0.30.x Camera Pro (ui/CameraPro.kt, device/CameraZoom.kt): zoom presets, the half-circle zoom dial, digital zoom
 * past the lens's max (preview scaled, photo cropped + sharpened off the main thread), tap-to-focus ring with an
 * exposure slider, rule-of-thirds grid and a 3 s / 10 s timer (grid and timer remembered).
 */
class CameraActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PATH = "blaze_camera_path"
        const val EXTRA_MODE = "blaze_camera_mode"
        const val EXTRA_ERROR = "blaze_camera_error"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra(EXTRA_PATH)
        val origin = if (path != null) Origin.CHAT_ATTACH
            else runCatching { Origin.valueOf(intent.getStringExtra(BlazeCamera.EXTRA_ORIGIN) ?: "") }.getOrDefault(Origin.STANDALONE)
        val asked = CameraModes.parse(intent.getStringExtra(EXTRA_MODE))
        val start = CameraModes.startMode(asked, BlazeCamera.lastMode, origin)
        val chat = intent.getStringExtra(BlazeCamera.EXTRA_CHAT)
        val prompt = intent.getStringExtra(BlazeCamera.EXTRA_PROMPT)
        setContent {
            BlazeTheme {
                CameraScreen(start, origin, path?.let { File(it) }, chat, prompt,
                    onDone = { ok, err ->
                        setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, Intent().apply { err?.let { putExtra(EXTRA_ERROR, it) } })
                        finish()
                    })
            }
        }
    }
}

/** Launches [CameraActivity] to take one photo into the given file. Result: ok, or the error ("Kind|message") it hit. */
class InAppCameraContract : ActivityResultContract<File, InAppCameraContract.Result>() {
    data class Result(val ok: Boolean, val error: String?)
    override fun createIntent(context: Context, input: File): Intent =
        Intent(context, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_PATH, input.absolutePath).putExtra(CameraActivity.EXTRA_MODE, "photo")
    override fun parseResult(resultCode: Int, intent: Intent?): Result =
        Result(resultCode == Activity.RESULT_OK, intent?.getStringExtra(CameraActivity.EXTRA_ERROR))
}

/** The phone's camera app with explicit URI grants (see [CameraSupport.captureIntent]). Result: RESULT_OK or not. */
class CaptureContract : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent = CameraSupport.captureIntent(input)
    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
}

@Composable
private fun CameraScreen(start: Mode, origin: Origin, attachTo: File?, chat: String?, asked: String?, onDone: (Boolean, String?) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(CameraSupport.granted(ctx)) }
    var denied by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        CameraSupport.askedBefore = true
        granted = ok; denied = !ok
        if (!ok) CameraSupport.log("Blaze Camera: permission denied")
    }
    LaunchedEffect(Unit) { if (!granted) perm.launch(Manifest.permission.CAMERA) }
    // Back from Settings with the permission switched on.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !granted && CameraSupport.granted(ctx)) { granted = true; denied = false }
        }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    when {
        granted -> LiveCamera(start, origin, attachTo, chat, asked, onDone)
        denied -> PermissionNeeded(
            onAllow = {
                val act = ctx as? Activity
                if (act != null && act.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) perm.launch(Manifest.permission.CAMERA)
                else openAppSettings(ctx)
            },
            onClose = { onDone(false, "PermissionDenied|camera permission denied") })
        else -> Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

fun openAppSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        CameraSupport.log("couldn't open app settings", e)
        try { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }
}

@Composable
private fun PermissionNeeded(onAllow: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera access is off", style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = Color.White))
            Spacer(Modifier.height(10.dp))
            Text("Blaze needs the Camera permission to take a picture. Nothing is recorded unless you press the shutter.",
                textAlign = TextAlign.Center, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = Color.White.copy(alpha = 0.75f)))
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CamPill("Not now", filled = false, onClick = onClose)
                CamPill("Allow camera", filled = true, onClick = onAllow)
            }
        }
    }
}

@Composable
private fun CamPill(label: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape).then(if (filled) Modifier.background(Color.White) else Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), shape))
        .clickable(onClickLabel = label, onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp)) {
        Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (filled) Color.Black else Color.White))
    }
}

/** A scanned code on screen: its raw value plus what ML Kit typed it as. */
private data class Scanned(val raw: String, val kind: CameraModes.CodeKind, val name: String = "", val phone: String = "", val email: String = "")

@Composable
private fun LiveCamera(start: Mode, origin: Origin, attachTo: File?, chat: String?, asked: String?, onDone: (Boolean, String?) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lowRam = remember { BlazeCamera.lowRam(ctx) }
    val modes = CameraModes.barFor(origin)
    var mode by rememberSaveable { mutableStateOf(start.id) }
    val m = CameraModes.parse(mode) ?: Mode.PHOTO
    var lens by rememberSaveable { mutableStateOf("back") }
    var flash by rememberSaveable { mutableStateOf("off") }
    var hasFlash by remember { mutableStateOf(false) }
    var hasBack by remember { mutableStateOf(true) }
    var hasFront by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var scanned by remember { mutableStateOf<Scanned?>(null) }
    var textCard by remember { mutableStateOf<Pair<File, String>?>(null) }
    // v0.34.0: Night / HDR shots in Photo mode, and the 4-corner crop for documents without Google's scanner.
    var shotKind by rememberSaveable { mutableStateOf("normal") }
    var cropDoc by remember { mutableStateOf<File?>(null) }
    val analysisExec = remember { Executors.newSingleThreadExecutor() }

    // v0.30.x Camera Pro. One total zoom (hardware × digital, see CameraZoom), focus + exposure, grid, timer.
    var zoom by remember { mutableFloatStateOf(1f) }
    var camMin by remember { mutableFloatStateOf(1f) }
    var hwMax by remember { mutableFloatStateOf(1f) }
    var dialOpen by remember { mutableStateOf(false) }
    var dialTouch by remember { mutableLongStateOf(0L) }
    var grid by remember { mutableStateOf(CameraSupport.grid) }
    var timer by remember { mutableIntStateOf(CameraSupport.timer) }
    var countdown by remember { mutableIntStateOf(0) }
    val countJob = remember { arrayOfNulls<Job>(1) }
    var focus by remember { mutableStateOf<Offset?>(null) }
    var focusOk by remember { mutableStateOf<Boolean?>(null) }
    var focusTouch by remember { mutableLongStateOf(0L) }
    var ev by remember { mutableIntStateOf(0) }
    var evLower by remember { mutableIntStateOf(0) }
    var evUpper by remember { mutableIntStateOf(0) }
    var evStep by remember { mutableFloatStateOf(0f) }
    val lastHw = remember { floatArrayOf(-1f) }
    val previewRef = remember { arrayOfNulls<PreviewView>(1) }
    val zoomAnim = remember { Animatable(1f) }

    val controller = remember {
        LifecycleCameraController(ctx).apply {
            // ~1600 px (1280 on low-RAM phones) is plenty for the model and OCR, and quick on cheap phones.
            // Gallery photos (open_camera outside a chat) keep the camera's default size.
            if (origin != Origin.STANDALONE || attachTo != null) {
                val long = if (lowRam) 1280 else CameraFlow.TARGET_LONG_EDGE; val short = long * 3 / 4
                imageCaptureTargetSize = CameraController.OutputSize(if (landscape) Size(long, short) else Size(short, long))
            }
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            // Blaze handles pinch and tap itself (CameraPro): pinch has to run on past the lens's max into digital zoom,
            // and a tap shows the focus ring and exposure slider.
            isPinchToZoomEnabled = false
            isTapToFocusEnabled = false
            setEnabledUseCases(if (CameraModes.live(m)) CameraController.IMAGE_ANALYSIS else CameraController.IMAGE_CAPTURE)
        }
    }
    fun refreshLensInfo() {
        hasBack = try { controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) } catch (_: Exception) { false }
        hasFront = try { controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) } catch (_: Exception) { false }
        hasFlash = try { controller.cameraInfo?.hasFlashUnit() == true } catch (_: Exception) { false }
        if (!hasFlash) flash = "off"
        try {
            val es = controller.cameraInfo?.exposureState
            if (es != null && es.isExposureCompensationSupported) {
                evLower = es.exposureCompensationRange.lower; evUpper = es.exposureCompensationRange.upper; evStep = es.exposureCompensationStep.toFloat()
            } else { evLower = 0; evUpper = 0 }
        } catch (_: Exception) { evLower = 0; evUpper = 0 }
    }
    fun zoomRange(): Pair<Float, Float> = CameraZoom.minTotal(camMin) to CameraZoom.maxTotal(hwMax, lens == "front")
    /** Sets the total zoom: the lens takes up to its max (only when that part changed), the preview scale does the rest. */
    fun setZoom(r: Float) {
        val (lo, hi) = zoomRange()
        val z = CameraZoom.clamp(r, lo, hi)
        zoom = z
        val hw = CameraZoom.split(z, hwMax).hw
        if (abs(hw - lastHw[0]) > 0.0005f) {
            lastHw[0] = hw
            try { controller.setZoomRatio(hw) } catch (e: Exception) { CameraSupport.log("zoom failed", e) }
        }
    }
    /** Preset taps and dial snaps glide there (~220 ms, instant with Remove animations). */
    fun glideTo(target: Float) {
        scope.launch {
            try { zoomAnim.snapTo(zoom); zoomAnim.animateTo(target, animationSpec = MotionTokens.medium()) { setZoom(value) } } catch (_: Exception) { }
        }
    }
    fun setEv(i: Int) {
        if (i == ev) return
        ev = i
        try { controller.cameraControl?.setExposureCompensationIndex(i) } catch (e: Exception) { CameraSupport.log("exposure failed", e) }
    }
    fun tapFocus(o: Offset) {
        val v = previewRef[0] ?: return
        if (!ready) return
        // The preview may be scaled up for digital zoom: map the tap back into the PreviewView's own coordinates.
        val (x, y) = CameraZoom.unscale(o.x, o.y, v.width / 2f, v.height / 2f, CameraZoom.split(zoom, hwMax).digital)
        focus = o; focusOk = null; focusTouch = SystemClock.uptimeMillis()
        if (ev != 0) setEv(0)
        try {
            val action = FocusMeteringAction.Builder(v.meteringPointFactory.createPoint(x, y)).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
            val f = controller.cameraControl?.startFocusAndMetering(action)
            if (f == null) { focusOk = false; return }
            f.addListener({ focusOk = try { f.get().isFocusSuccessful } catch (_: Exception) { false } }, ContextCompat.getMainExecutor(ctx))
        } catch (e: Exception) { CameraSupport.log("tap to focus failed", e); focusOk = false }
    }
    fun applyLens(want: String): Boolean {
        val l = CameraFlow.startLens(hasBack, hasFront, want) ?: return false
        if (l != lens) { zoom = 1f; focus = null; ev = 0; dialOpen = false }
        lastHw[0] = -1f
        lens = l
        controller.cameraSelector = if (l == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        return true
    }

    DisposableEffect(owner) {
        val init = controller.initializationFuture
        init.addListener({
            try {
                init.get()
                refreshLensInfo()
                if (!applyLens(lens)) { CameraSupport.log("Blaze Camera: no lens"); onDone(false, "NoCamera|No available camera on this device"); return@addListener }
                controller.bindToLifecycle(owner)
                controller.imageCaptureFlashMode = flashMode(flash)
                ready = true
                CameraSupport.log("Blaze Camera ready: lens=$lens back=$hasBack front=$hasFront flash=$hasFlash mode=$mode origin=${origin.name} lowRam=$lowRam")
            } catch (e: Throwable) {
                val root = e.cause ?: e
                CameraSupport.log("Blaze Camera failed to start", root)
                onDone(false, "${root.javaClass.simpleName}|${root.message}")
            }
        }, ContextCompat.getMainExecutor(ctx))
        // Flash support is only known once a lens is bound: re-read it as the camera state settles.
        // v0.30.x: also the lens's zoom range (min < 1 = an ultrawide; max = where digital zoom takes over).
        val obs = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { zs ->
            refreshLensInfo()
            if (zs != null && (zs.minZoomRatio != camMin || zs.maxZoomRatio != hwMax)) {
                camMin = zs.minZoomRatio; hwMax = zs.maxZoomRatio
                lastHw[0] = -1f; setZoom(zoom)
            }
        }
        controller.zoomState.observe(owner, obs)
        onDispose {
            try { controller.zoomState.removeObserver(obs) } catch (_: Exception) { }
            try { controller.clearImageAnalysisAnalyzer() } catch (_: Exception) { }
            try { controller.unbind() } catch (_: Exception) { }
            analysisExec.shutdown()
        }
    }

    // Scan: ML Kit barcode scanning (bundled model) on live frames.
    DisposableEffect(m) {
        if (!CameraModes.live(m)) {
            try { controller.clearImageAnalysisAnalyzer() } catch (_: Exception) { }
            try { controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE) } catch (_: Exception) { }
            onDispose { }
        } else {
            val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient(
                com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_ALL_FORMATS).build())
            try { controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS) } catch (_: Exception) { }
            controller.setImageAnalysisAnalyzer(analysisExec, ImageAnalysis.Analyzer { img ->
                if (scanned != null) { img.close(); return@Analyzer }
                scanFrame(scanner, img) { s -> ContextCompat.getMainExecutor(ctx).execute { if (scanned == null) {
                    scanned = s
                    com.blaze.assistant.device.Haptics.tick(ctx)
                    if (CameraModes.route(Mode.SCAN, origin) == Route.SEND_TURN) {
                        BlazeCamera.deliver(ctx, Mode.SCAN, origin, chat, asked, code = s.raw); onDone(true, null)
                    }
                } } }
            })
            onDispose { try { controller.clearImageAnalysisAnalyzer() } catch (_: Exception) { }; try { scanner.close() } catch (_: Exception) { } }
        }
    }

    // Document: Google's on-device document scanner (edge detection, crop, clean-up) when Play services has it.
    val docScan = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val res = if (r.resultCode == Activity.RESULT_OK) com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(r.data) else null
        val uri = res?.pdf?.uri ?: res?.pages?.firstOrNull()?.imageUri
        if (uri != null) { BlazeCamera.deliver(ctx, Mode.DOCUMENT, origin, chat, asked, doc = uri); onDone(true, null) }
        else note = "Hold the page flat inside the frame and tap the shutter."
    }
    var triedDocScanner by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(m) {
        if (m != Mode.DOCUMENT || triedDocScanner) return@LaunchedEffect
        triedDocScanner = true
        val act = ctx as? Activity ?: return@LaunchedEffect
        val gms = try { com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == com.google.android.gms.common.ConnectionResult.SUCCESS } catch (_: Throwable) { false }
        if (!gms) { CameraSupport.log("document scanner: no Google Play services, using the framed photo"); return@LaunchedEffect }
        try {
            val opts = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false).setPageLimit(10)
                .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                    com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
            com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
                .addOnSuccessListener { sender -> docScan.launch(IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener { e -> CameraSupport.log("document scanner unavailable, using the framed photo", e) }
        } catch (e: Throwable) { CameraSupport.log("document scanner failed, using the framed photo", e) }
    }

    fun handlePhoto(out: File, digital: Float) {
        scope.launch {
            val route = CameraModes.route(m, origin)
            // Digital zoom: the same centred crop the preview showed, upscaled and sharpened off the main thread.
            if (digital > 1.01f) {
                val maxLong = if (origin != Origin.STANDALONE || attachTo != null) (if (lowRam) 1280 else CameraFlow.TARGET_LONG_EDGE) else (if (lowRam) 1600 else 2400)
                withContext(Dispatchers.Default) { CameraSupport.digitalZoom(out, digital, maxLong) }
            }
            when {
                route == Route.RETURN_FILE -> { withContext(Dispatchers.IO) { CameraSupport.normalize(out) }; busy = false; onDone(true, null) }
                route == Route.GALLERY -> {
                    val saved = withContext(Dispatchers.IO) { saveToGallery(ctx, out) }
                    busy = false; Toast.makeText(ctx, saved ?: "Couldn't save the photo.", Toast.LENGTH_SHORT).show(); onDone(true, null)
                }
                m == Mode.TEXT || m == Mode.TRANSLATE -> {
                    note = "Reading the text…"
                    val words = withContext(Dispatchers.Default) { CameraSupport.normalize(out); readText(out) }
                    busy = false; note = ""
                    if (route == Route.CARD) textCard = out to words
                    else { BlazeCamera.deliver(ctx, m, origin, chat, asked, photo = out, ocr = words); onDone(true, null) }
                }
                m == Mode.DOCUMENT -> {
                    withContext(Dispatchers.IO) { CameraSupport.normalize(out) }
                    busy = false
                    cropDoc = out   // drag the 4 corners onto the page, then it's flattened and sent
                }
                else -> {
                    withContext(Dispatchers.IO) { CameraSupport.normalize(out) }
                    busy = false
                    BlazeCamera.deliver(ctx, m, origin, chat, asked, photo = out); onDone(true, null)
                }
            }
        }
    }

    fun shoot() {
        if (busy || !ready || CameraModes.live(m)) return
        busy = true; note = ""
        val digital = CameraZoom.split(zoom, hwMax).digital
        val out = attachTo ?: CameraSupport.newPhoto(ctx).first
        out.parentFile?.mkdirs()
        if (m == Mode.PHOTO && shotKind != "normal") {
            scope.launch {
                val ok = com.blaze.assistant.device.CameraStack.capture(ctx, controller, shotKind, out, lowRam, evLower, evUpper, evStep, ev) { note = it }
                if (ok) handlePhoto(out, digital) else { busy = false; note = "The camera didn't give the frames for that shot. Try a normal photo." }
            }
            return
        }
        controller.takePicture(ImageCapture.OutputFileOptions.Builder(out).build(), ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) { handlePhoto(out, digital) }
            override fun onError(e: ImageCaptureException) {
                CameraSupport.log("Blaze Camera capture failed (code ${e.imageCaptureError})", e)
                busy = false
                note = "That shot didn't save (${e.message?.take(60) ?: "camera error"}). Try again."
            }
        })
    }

    /** The shutter: straight away, or after the 3 s / 10 s countdown (a second press cancels it). */
    fun shutter() {
        countJob[0]?.let { if (it.isActive) { it.cancel(); countJob[0] = null; countdown = 0; return } }
        if (timer <= 0 || busy || !ready || CameraModes.live(m)) { shoot(); return }
        countJob[0] = scope.launch {
            val start = SystemClock.elapsedRealtime(); var shown = -1
            while (true) {
                val left = CameraTimer.remaining(start, SystemClock.elapsedRealtime(), timer)
                if (left != shown) { shown = left; countdown = left; if (left > 0) com.blaze.assistant.device.Haptics.tick(ctx) }
                if (left <= 0) break
                delay(40)
            }
            countdown = 0; countJob[0] = null
            shoot()
        }
    }

    LaunchedEffect(focus, focusTouch) { if (focus != null) { delay(3000); focus = null } }
    LaunchedEffect(dialOpen, dialTouch) { if (dialOpen) { delay(3500); dialOpen = false } }

    val proActions = remember {
        CameraProActions(
            onZoom = { setZoom(it) },
            onPreset = { p -> com.blaze.assistant.device.Haptics.tick(ctx); glideTo(p) },
            onDial = { open -> dialOpen = open; dialTouch = SystemClock.uptimeMillis() },
            onDialTouch = { dialTouch = SystemClock.uptimeMillis() },
            onDialRelease = {
                val (lo, hi) = zoomRange()
                val snapped = CameraZoom.snap(zoom, lo, hi, CameraZoom.stops(lo, hi))
                if (snapped != zoom) glideTo(snapped)
                dialTouch = SystemClock.uptimeMillis()
            },
            onGrid = { grid = !grid; CameraSupport.grid = grid },
            onTimer = { timer = CameraTimer.next(timer); CameraSupport.timer = timer },
            onEv = { setEv(it) },
            onFocusTouch = { focusTouch = SystemClock.uptimeMillis() },
        )
    }
    val (zLo, zHi) = zoomRange()
    val pro = CameraProState(zoom = zoom, minZoom = zLo, maxZoom = zHi, hwMax = hwMax, presets = CameraZoom.presets(camMin, zHi),
        dialOpen = dialOpen, grid = grid && m != Mode.SCAN, timer = timer, countdown = countdown, focus = focus, focusOk = focusOk,
        evIndex = ev, evLower = evLower, evUpper = evUpper, evStep = evStep, tools = !CameraModes.live(m))

    val cam: CameraController = controller
    CameraChrome(
        preview = {
            Box(Modifier.fillMaxSize().clipToBounds()) {
                AndroidView(factory = { c ->
                    PreviewView(c).apply {
                        // TextureView: the most compatible path on old / low-end GPUs and through rotations.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        this.controller = cam
                        previewRef[0] = this
                    }
                }, modifier = Modifier.fillMaxSize().graphicsLayer {
                    // Digital zoom past the lens's max: scale the preview about its centre (read here, so only this layer redraws).
                    val d = CameraZoom.split(zoom, hwMax).digital
                    scaleX = d; scaleY = d
                })
                // Pinch (across the hardware / digital boundary) and tap-to-focus.
                Box(Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, z, _ ->
                            if (z != 1f) {
                                val (lo, hi) = zoomRange()
                                setZoom(CameraZoom.pinch(zoom, z, lo, hi))
                                if (dialOpen) dialTouch = SystemClock.uptimeMillis()
                            }
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onTap = { o -> tapFocus(o) }) })
                if (m == Mode.PHOTO && !busy) Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    listOf("normal" to "Photo", "night" to "Night", "hdr" to "HDR").forEach { (k, l) -> CamPill(l, shotKind == k) { shotKind = k } }
                }
            }
        },
        pro = pro, proActions = proActions,
        modes = modes, mode = m,
        flash = if (hasFlash && !CameraModes.live(m)) flash else null,
        canFlip = CameraFlow.canFlip(hasBack, hasFront),
        busy = busy || !ready,
        note = note,
        onMode = { nm ->
            if (nm != m) {
                mode = nm.id; note = ""; scanned = null
                countJob[0]?.cancel(); countJob[0] = null; countdown = 0
                if (CameraModes.remembers(origin)) BlazeCamera.lastMode = nm.id
            }
        },
        onClose = { onDone(false, null) },
        onFlash = { flash = CameraFlow.nextFlash(flash, hasFlash); controller.imageCaptureFlashMode = flashMode(flash) },
        onFlip = { if (applyLens(if (lens == "back") "front" else "back")) { flash = "off"; controller.imageCaptureFlashMode = flashMode("off") } },
        onShutter = { shutter() },
    )
    scanned?.takeIf { CameraModes.route(Mode.SCAN, origin) == Route.CARD }?.let { s ->
        ScanCard(s, onAsk = { BlazeCamera.deliver(ctx, Mode.SCAN, origin, chat, null, code = s.raw, routeOverride = Route.DRAFT); onDone(true, null) },
            onAgain = { scanned = null }, onDone = { onDone(true, null) })
    }
    cropDoc?.let { f ->
        DocumentCrop(f, onCancel = { cropDoc = null }, onDone = { corners ->
            scope.launch {
                if (corners != null) com.blaze.assistant.device.CameraStack.cropDocument(f, corners)
                cropDoc = null
                BlazeCamera.deliver(ctx, Mode.DOCUMENT, origin, chat, asked, photo = f); onDone(true, null)
            }
        })
    }
    textCard?.let { (file, words) ->
        TextCard(words,
            onTranslate = { BlazeCamera.deliver(ctx, Mode.TRANSLATE, origin, chat, null, photo = file, ocr = words, routeOverride = Route.SEND_TURN); onDone(true, null) },
            onAsk = { BlazeCamera.deliver(ctx, Mode.TEXT, origin, chat, null, photo = file, ocr = words, routeOverride = Route.DRAFT); onDone(true, null) },
            onAgain = { textCard = null }, onDone = { onDone(true, null) })
    }
}

/** One frame → ML Kit barcode scanner. The frame is closed when ML Kit is done with it. */
private fun scanFrame(scanner: com.google.mlkit.vision.barcode.BarcodeScanner, img: ImageProxy, onCode: (Scanned) -> Unit) {
    @Suppress("UnsafeOptInUsageError")
    val media = img.image
    if (media == null) { img.close(); return }
    try {
        scanner.process(com.google.mlkit.vision.common.InputImage.fromMediaImage(media, img.imageInfo.rotationDegrees))
            .addOnSuccessListener { list ->
                val b = list.firstOrNull { !it.rawValue.isNullOrBlank() } ?: return@addOnSuccessListener
                val raw = b.rawValue.orEmpty()
                val kind = when (b.valueType) {
                    com.google.mlkit.vision.barcode.common.Barcode.TYPE_URL -> CameraModes.CodeKind.URL
                    com.google.mlkit.vision.barcode.common.Barcode.TYPE_WIFI -> CameraModes.CodeKind.WIFI
                    com.google.mlkit.vision.barcode.common.Barcode.TYPE_CONTACT_INFO -> CameraModes.CodeKind.CONTACT
                    com.google.mlkit.vision.barcode.common.Barcode.TYPE_PHONE -> CameraModes.CodeKind.PHONE
                    com.google.mlkit.vision.barcode.common.Barcode.TYPE_EMAIL -> CameraModes.CodeKind.EMAIL
                    else -> CameraModes.kindOf(raw)
                }
                val c = b.contactInfo
                onCode(Scanned(raw, kind,
                    name = c?.name?.formattedName.orEmpty(),
                    phone = c?.phones?.firstOrNull()?.number ?: b.phone?.number.orEmpty(),
                    email = c?.emails?.firstOrNull()?.address ?: b.email?.address.orEmpty()))
            }
            .addOnFailureListener { e -> CameraSupport.log("barcode scan failed", e) }
            .addOnCompleteListener { img.close() }
    } catch (e: Throwable) { CameraSupport.log("barcode scan failed", e); img.close() }
}

/** On-device OCR with Magic Hand's warm ML Kit recogniser, lines in reading order. */
private suspend fun readText(f: File): String = try {
    val bmp = BitmapFactory.decodeFile(f.path) ?: throw IllegalStateException("couldn't decode the photo")
    val lines = com.blaze.assistant.phone.MagicHandScreen.lines(bmp)
    com.blaze.assistant.phone.MagicHandOcr.keep(lines, com.blaze.assistant.phone.MagicHandLogic.Box(0, 0, bmp.width, bmp.height), 0.01f)
} catch (e: Throwable) { CameraSupport.log("OCR failed", e); "" }

private fun flashMode(f: String) = when (f) { "on" -> ImageCapture.FLASH_MODE_ON; "auto" -> ImageCapture.FLASH_MODE_AUTO; else -> ImageCapture.FLASH_MODE_OFF }

/** Gallery copy for open_camera outside a chat: Pictures/Blaze (Android 10+, no storage permission), else Blaze's own Pictures folder. */
private fun saveToGallery(ctx: Context, f: File): String? = try {
    if (Build.VERSION.SDK_INT >= 29) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, f.name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Blaze"); put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("MediaStore insert failed")
        cr.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        f.delete()
        "Saved to Pictures/Blaze."
    } else {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Blaze").apply { mkdirs() }
        f.copyTo(File(dir, f.name), overwrite = true); f.delete()
        "Saved in Blaze's Pictures folder."
    }
} catch (e: Exception) { CameraSupport.log("gallery save failed", e); null }

/** Text read by Blaze Camera as a .txt in Downloads/Blaze (Android 10+), else Blaze's own Documents folder. */
private fun saveText(ctx: Context, text: String): String? = try {
    val name = "blaze-text-${System.currentTimeMillis()}.txt"
    if (Build.VERSION.SDK_INT >= 29) {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Blaze")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("MediaStore insert failed")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        "Saved to Downloads/Blaze."
    } else {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Blaze").apply { mkdirs() }
        File(dir, name).writeText(text); "Saved in Blaze's Documents folder."
    }
} catch (e: Exception) { CameraSupport.log("text save failed", e); null }

private fun camCopy(ctx: Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Copied.", Toast.LENGTH_SHORT).show()
}

private fun camStart(ctx: Context, i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
    catch (e: Exception) { CameraSupport.log("couldn't open ${i.action}", e); false }

@Composable
private fun ResultSheet(title: String, content: @Composable () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(22.dp)).background(DialogColors.surface, RoundedCornerShape(22.dp))
            .glass(RoundedCornerShape(22.dp), GlassIntensity.High, shadow = false).padding(18.dp)) {
            Text(title, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = GlassTokens.sub))
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun CamChips(items: List<Pair<String, () -> Unit>>) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, (label, f) ->
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (i == 0) c.accent else c.paper).clickable(onClickLabel = label, onClick = f)
                .padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = if (i == 0) Color.White else GlassTokens.ink))
            }
        }
    }
}

@Composable
private fun TextCard(words: String, onTranslate: () -> Unit, onAsk: () -> Unit, onAgain: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalBlaze.current
    ResultSheet(if (words.isBlank()) "No text found" else "Text") {
        if (words.isBlank()) Text("Blaze couldn't read any words. Move closer, hold steady and try again.",
            style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.ink))
        else SelectionContainer(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text(words, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, color = c.ink))
        }
        // v0.34.0: prices and sums the camera read, worked out.
        val prices = remember(words) { com.blaze.assistant.device.CameraSmart.prices(words) }
        val sums = remember(words) { com.blaze.assistant.device.CameraSmart.sums(words).mapNotNull { e -> Expr.eval(e)?.let { v -> e to v } }.take(4) }
        if (prices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val tot = com.blaze.assistant.device.CameraSmart.totals(prices).entries.joinToString(" + ") { (k, v) -> "$k ${"%,.2f".format(v)}" }
            Text("${prices.size} price" + (if (prices.size == 1) "" else "s") + " \u00b7 total $tot", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
        }
        sums.forEach { (e, v) ->
            Text("$e = ${if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else "%.6g".format(v)}",
                style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
        }
        Spacer(Modifier.height(14.dp))
        CamChips(if (words.isBlank()) listOf("Try again" to onAgain, "Close" to onDone) else listOf(
            "Copy" to { camCopy(ctx, "Text", words) },
            "Translate" to onTranslate,
            "Search" to { camStart(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(words.take(300)))).setPackage(ctx.packageName)); Unit },
            "Save" to { Toast.makeText(ctx, saveText(ctx, words) ?: "Couldn't save the text.", Toast.LENGTH_SHORT).show() },
            "Ask Blaze" to onAsk,
            "Again" to onAgain))
    }
}

@Composable
private fun ScanCard(s: Scanned, onAsk: () -> Unit, onAgain: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalBlaze.current
    val wifi = CameraModes.parseWifi(s.raw)
    val shown = when {
        wifi != null -> "Wi-Fi \u201c${wifi.ssid}\u201d"
        s.kind == CameraModes.CodeKind.CONTACT && s.name.isNotBlank() -> s.name + (if (s.phone.isNotBlank()) " \u00b7 ${s.phone}" else "")
        else -> s.raw.take(600)
    }
    fun act(label: String) {
        when (label) {
            "Open link" -> { camStart(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(s.raw.trim()))); onDone() }
            "Join Wi-Fi" -> wifi?.let { joinWifi(ctx, it); onDone() }
            "Copy password" -> camCopy(ctx, "Wi-Fi password", wifi?.password.orEmpty())
            "Add contact" -> { camStart(ctx, Intent(android.provider.ContactsContract.Intents.Insert.ACTION).setType(android.provider.ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(android.provider.ContactsContract.Intents.Insert.NAME, s.name)
                .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, s.phone)
                .putExtra(android.provider.ContactsContract.Intents.Insert.EMAIL, s.email)); onDone() }
            "Call" -> { camStart(ctx, Intent(Intent.ACTION_DIAL, Uri.parse(if (s.raw.startsWith("tel:", true)) s.raw else "tel:" + s.phone.ifBlank { s.raw }))); onDone() }
            "Email" -> { camStart(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse(if (s.raw.startsWith("mailto:", true)) s.raw else "mailto:" + s.email))); onDone() }
            "Ask Blaze" -> onAsk()
            else -> camCopy(ctx, "Code", s.raw)
        }
    }
    ResultSheet(when (s.kind) { CameraModes.CodeKind.URL -> "Link"; CameraModes.CodeKind.WIFI -> "Wi-Fi network"; CameraModes.CodeKind.CONTACT -> "Contact"
        CameraModes.CodeKind.PHONE -> "Phone number"; CameraModes.CodeKind.EMAIL -> "Email"; else -> "Code" }) {
        SelectionContainer { Text(shown, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, color = c.ink)) }
        // v0.34.0: a product barcode (EAN / UPC) is looked up on Open Food Facts (free, open data).
        if (com.blaze.assistant.device.CameraSmart.isProductCode(s.raw)) {
            val product by androidx.compose.runtime.produceState<String?>(null, s.raw) {
                value = "Looking up the product\u2026"
                value = withContext(Dispatchers.IO) {
                    try {
                        val h = java.net.URL("https://world.openfoodfacts.org/api/v2/product/${s.raw.trim()}.json?fields=product_name,brands,quantity").openConnection() as java.net.HttpURLConnection
                        h.connectTimeout = 8000; h.readTimeout = 10000; h.setRequestProperty("User-Agent", "Blaze/0.34 (Android)")
                        com.blaze.assistant.device.CameraSmart.product(h.inputStream.bufferedReader().use { it.readText() }) ?: "Not in Open Food Facts yet."
                    } catch (_: Exception) { "Couldn't look it up (no connection)." }
                }
            }
            product?.let { Spacer(Modifier.height(6.dp)); Text(it, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink)) }
        }
        Spacer(Modifier.height(14.dp))
        CamChips(CameraModes.scanActions(s.kind).map { l -> l to { act(l) } } + ("Scan again" to onAgain))
    }
}

/** Android 11+: the system "add network" sheet. Older: copy the password and open Wi-Fi settings. */
private fun joinWifi(ctx: Context, w: CameraModes.Wifi) {
    if (Build.VERSION.SDK_INT >= 30) try {
        val b = android.net.wifi.WifiNetworkSuggestion.Builder().setSsid(w.ssid)
        when {
            w.security.equals("SAE", true) -> b.setWpa3Passphrase(w.password)
            w.security.startsWith("WPA", true) && w.password.isNotEmpty() -> b.setWpa2Passphrase(w.password)
        }
        val i = Intent(android.provider.Settings.ACTION_WIFI_ADD_NETWORKS)
            .putParcelableArrayListExtra(android.provider.Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(b.build()))
        if (camStart(ctx, i)) return
    } catch (e: Exception) { CameraSupport.log("wifi add sheet failed", e) }
    if (w.password.isNotEmpty()) camCopy(ctx, "Wi-Fi password", w.password)
    camStart(ctx, Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
    Toast.makeText(ctx, "Pick \u201c${w.ssid}\u201d" + (if (w.password.isNotEmpty()) " and paste the password." else "."), Toast.LENGTH_LONG).show()
}

/**
 * Blaze Camera's controls over any preview (stateless, so ShotsTest draws it with a fake preview). Insets-aware for
 * gesture nav, 3-button nav, cut-outs and landscape. The mode bar is TalkBack tabs; motion follows Remove animations.
 */
@Composable
fun CameraChrome(
    preview: @Composable () -> Unit,
    modes: List<Mode>,
    mode: Mode,
    flash: String?,
    canFlip: Boolean,
    busy: Boolean,
    note: String = "",
    onMode: (Mode) -> Unit = {},
    onClose: () -> Unit,
    onFlash: () -> Unit,
    onFlip: () -> Unit,
    onShutter: () -> Unit,
    pro: CameraProState? = null,
    proActions: CameraProActions = CameraProActions(),
) {
    val live = CameraModes.live(mode)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        preview()
        if (pro != null) {
            if (pro.grid) CameraGrid()
            FocusRing(pro, proActions)
            CameraCountdown(pro.countdown)
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(10.dp).align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
                CamButton(Icons.Rounded.Close, "Close camera", onClose)
                Spacer(Modifier.weight(1f))
                if (modes.isNotEmpty()) Text("Blaze Camera", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White))
                Spacer(Modifier.weight(1f))
                if (pro != null && pro.tools) {
                    CamTool(if (pro.grid) Icons.Rounded.GridOn else Icons.Rounded.GridOff, if (pro.grid) "Grid on" else "Grid off", pro.grid, proActions.onGrid)
                    Spacer(Modifier.width(6.dp))
                    CamTool(when (pro.timer) { 3 -> Icons.Rounded.Timer3; 10 -> Icons.Rounded.Timer10; else -> Icons.Rounded.TimerOff },
                        CameraTimer.label(pro.timer), pro.timer > 0, proActions.onTimer)
                    Spacer(Modifier.width(6.dp))
                }
                if (flash != null) CamButton(when (flash) { "on" -> Icons.Rounded.FlashOn; "auto" -> Icons.Rounded.FlashAuto; else -> Icons.Rounded.FlashOff },
                    "Flash $flash. Double-tap to change.", onFlash)
                else Spacer(Modifier.size(52.dp))
            }
            // Framing guides: a square for codes, a page for documents.
            when (mode) {
                Mode.SCAN -> Box(Modifier.align(Alignment.Center).size(230.dp).border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(26.dp)))
                Mode.DOCUMENT -> Box(Modifier.align(Alignment.Center).fillMaxWidth(0.78f).aspectRatio(0.72f).border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(14.dp)))
                else -> {}
            }
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val hint = note.ifBlank { hintFor(mode, modes.isNotEmpty()) }
                if (hint.isNotBlank()) Text(hint, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 7.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Color.White))
                if (pro != null) {
                    ZoomControls(pro, proActions)
                    Spacer(Modifier.height(14.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(52.dp))
                    Spacer(Modifier.weight(1f))
                    val counting = (pro?.countdown ?: 0) > 0
                    val shutterDesc = if (counting) "Cancel the timer" else if ((pro?.timer ?: 0) > 0) shutterLabel(mode) + " in ${pro?.timer} seconds" else shutterLabel(mode)
                    if (!live) Box(Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                        .clickable(enabled = !busy, onClickLabel = shutterDesc, onClick = onShutter).padding(8.dp)
                        .semantics { contentDescription = shutterDesc; role = Role.Button }) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(when { busy -> Color.White.copy(alpha = 0.4f); counting -> CamAccent; else -> Color.White }))
                    } else Spacer(Modifier.size(76.dp))
                    Spacer(Modifier.weight(1f))
                    if (canFlip) CamButton(Icons.Rounded.Cameraswitch, "Switch between front and back camera", onFlip) else Spacer(Modifier.width(52.dp))
                }
                if (modes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    CameraModeBar(modes, mode, onMode)
                }
            }
        }
    }
}

private fun hintFor(mode: Mode, withBar: Boolean): String = if (!withBar) "" else when (mode) {
    Mode.PHOTO -> ""
    Mode.ASK -> "Snap it, then ask Blaze"
    Mode.TEXT -> "Snap the words to read them"
    Mode.TRANSLATE -> "Snap the words to translate them"
    Mode.SCAN -> "Point at a QR code or barcode"
    Mode.DOCUMENT -> "Fit the page inside the frame"
}

private fun shutterLabel(mode: Mode) = when (mode) {
    Mode.ASK -> "Take a photo and ask Blaze"; Mode.TEXT -> "Take a photo and read the text"; Mode.TRANSLATE -> "Take a photo and translate it"
    Mode.DOCUMENT -> "Scan the page"; else -> "Take photo"
}

/** The mode bar: tabs for TalkBack, scrolls on narrow phones, highlight cross-fades (instant with Remove animations). */
@Composable
fun CameraModeBar(modes: List<Mode>, mode: Mode, onMode: (Mode) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
        modes.forEach { md ->
            val on = md == mode
            val bg by animateColorAsState(if (on) Color.White else Color.Black.copy(alpha = 0.35f), MotionTokens.fast(), label = "mode")
            Box(Modifier.clip(RoundedCornerShape(50)).background(bg)
                .clickable(onClickLabel = md.talkback) { onMode(md) }
                .semantics { role = Role.Tab; selected = on; contentDescription = md.talkback }
                .padding(horizontal = 13.dp, vertical = 8.dp)) {
                Text(md.label, style = TextStyle(fontFamily = Type.Sans, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, fontSize = 13.sp,
                    color = if (on) Color.Black else Color.White))
            }
        }
    }
}

@Composable
private fun CamButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClickLabel = desc, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}


/** v0.34.0 document crop: the photo with 4 draggable corners; Crop flattens the page inside them. */
@Composable
private fun DocumentCrop(f: File, onCancel: () -> Unit, onDone: (List<Pair<Float, Float>>?) -> Unit) {
    val bmp by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            var smp = 1; while (maxOf(o.outWidth, o.outHeight) / (smp * 2) >= 1400) smp *= 2
            android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = smp })
        }
    }
    val b = bmp
    var corners by remember(b) { mutableStateOf(if (b != null) com.blaze.assistant.device.CameraSmart.defaultCorners(b.width, b.height) else emptyList()) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
            Text("Drag the corners onto the page", style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = Color.White))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (b != null) {
                    val img = remember(b) { b.asImageBitmap() }
                    Box(Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)) {
                        androidx.compose.foundation.Image(img, "Document", Modifier.fillMaxSize())
                        var drag by remember { mutableIntStateOf(-1) }
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().pointerInput(b) {
                            val sx = b.width / size.width.toFloat(); val sy = b.height / size.height.toFloat()
                            detectDragGestures(
                                onDragStart = { o -> drag = com.blaze.assistant.device.CameraSmart.nearest(corners, o.x * sx, o.y * sy) },
                                onDragEnd = { drag = -1 },
                                onDrag = { ch, d ->
                                    ch.consume()
                                    val i = drag
                                    if (i >= 0) corners = corners.mapIndexed { k, p -> if (k == i) ((p.first + d.x * sx).coerceIn(0f, b.width.toFloat()) to (p.second + d.y * sy).coerceIn(0f, b.height.toFloat())) else p }
                                })
                        }) {
                            val kx = size.width / b.width; val ky = size.height / b.height
                            val pts = corners.map { Offset(it.first * kx, it.second * ky) }
                            if (pts.size == 4) {
                                for (k in 0 until 4) drawLine(Color(0xFF4F8CFF), pts[k], pts[(k + 1) % 4], strokeWidth = 4f)
                                pts.forEach { drawCircle(Color.White, 18f, it); drawCircle(Color(0xFF4F8CFF), 12f, it) }
                            }
                        }
                    }
                } else Text("Opening\u2026", style = TextStyle(color = Color.White))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.End)) {
                CamPill("Use as is", false) { onDone(null) }
                CamPill("Crop page", true) {
                    val bb = b
                    if (bb == null) onDone(null) else {
                        // Corners are in the preview bitmap's pixels: scale them to the full photo.
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(f.path, o)
                        val k = o.outWidth.toFloat() / bb.width
                        onDone(corners.map { (x, y) -> x * k to y * k })
                    }
                }
            }
        }
    }
}
