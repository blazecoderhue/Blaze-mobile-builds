package com.blaze.assistant.device

/**
 * v0.28.x camera-fix: the pure decisions behind "+ → Camera" (and the open_camera tool), so every phone ends up
 * with a working way to take a picture instead of a blanket "No camera app found." (CameraFlowTest).
 *
 * The chain: the phone's camera app (ACTION_IMAGE_CAPTURE with explicit URI grants) → Blaze's own CameraX camera
 * (works with no camera app, in work profiles, on low-end phones) → the photo picker as the last resort.
 *
 * Why the permission step exists at all: zxing-android-embedded (the Desktop pairing QR scanner) declares
 * android.permission.CAMERA in its manifest, so it is in Blaze's merged manifest. Android then refuses
 * ACTION_IMAGE_CAPTURE with a SecurityException until the user grants CAMERA. That was the Galaxy A07 bug:
 * a fresh install had never granted it (the test phone had, via QR pairing), and the old code turned the
 * SecurityException into "No camera app found."
 */
object CameraFlow {
    enum class Step { PICKER, REQUEST_PERMISSION, RATIONALE, SETTINGS, EXTERNAL, IN_APP }

    data class State(
        /** PackageManager.FEATURE_CAMERA_ANY. */
        val hasCamera: Boolean,
        /** CAMERA is in the merged manifest (then Android enforces it for ACTION_IMAGE_CAPTURE too). */
        val permissionDeclared: Boolean,
        val permissionGranted: Boolean,
        /** Blaze asked for CAMERA before (with no rationale now, that means "Don't ask again"). */
        val askedBefore: Boolean = false,
        /** shouldShowRequestPermissionRationale(CAMERA). */
        val showRationale: Boolean = false,
        /** Some activity answers ACTION_IMAGE_CAPTURE (needs the <queries> entry on Android 11+). */
        val externalHandler: Boolean = true,
        /** The camera app already failed on this phone once: go straight to Blaze's camera. */
        val preferInApp: Boolean = false,
        /** The camera app failed in this attempt. */
        val externalFailed: Boolean = false,
        /** Blaze's own camera failed in this attempt. */
        val inAppFailed: Boolean = false,
    )

    fun next(s: State): Step {
        if (!s.hasCamera) return Step.PICKER
        if (s.permissionDeclared && !s.permissionGranted) return when {
            s.showRationale -> Step.RATIONALE
            s.askedBefore -> Step.SETTINGS
            else -> Step.REQUEST_PERMISSION
        }
        val externalOk = s.externalHandler && !s.externalFailed
        // Blaze's camera needs CAMERA itself, so it only exists when the permission is declared (and, here, granted).
        val inAppOk = s.permissionDeclared && !s.inAppFailed
        return when {
            externalOk && !s.preferInApp -> Step.EXTERNAL
            inAppOk -> Step.IN_APP
            externalOk -> Step.EXTERNAL
            else -> Step.PICKER
        }
    }

    /** What to offer after the user said no to CAMERA. Permanently denied = no rationale any more. */
    fun afterDenied(showRationale: Boolean): Step = if (showRationale) Step.RATIONALE else Step.SETTINGS

    enum class Outcome { PHOTO, CANCELLED, BROKEN }

    /**
     * How the camera app's answer reads. A new, empty file is handed over each time, so bytes in it mean a photo,
     * whatever result code came back (some camera apps answer RESULT_CANCELED after saving). "OK" with nothing
     * saved means the app ignored EXTRA_OUTPUT; bouncing straight back means it refused (policy, work profile,
     * locked camera): both are BROKEN and fall through to Blaze's camera.
     */
    fun externalOutcome(resultOk: Boolean, savedBytes: Long, elapsedMs: Long): Outcome = when {
        savedBytes > 0 -> Outcome.PHOTO
        resultOk -> Outcome.BROKEN
        elapsedMs in 0 until INSTANT_BOUNCE_MS -> Outcome.BROKEN
        else -> Outcome.CANCELLED
    }
    const val INSTANT_BOUNCE_MS = 1200L

    /** A short, honest line for the toast when the camera app can't be used (the full error goes to the log). */
    fun externalFailNote(kind: String, message: String?): String {
        val m = message.orEmpty()
        return when {
            kind.endsWith("ActivityNotFoundException") -> "This phone has no camera app Blaze can use, so Blaze's own camera is opening."
            kind.endsWith("SecurityException") && (m.contains("CAMERA", true) || m.contains("permission", true)) ->
                "Android blocked the camera app (camera permission), so Blaze's own camera is opening."
            kind.endsWith("SecurityException") -> "Android blocked the camera app (${m.take(80).ifBlank { "security policy" }}), so Blaze's own camera is opening."
            kind.endsWith("IllegalArgumentException") || kind.endsWith("FileNotFoundException") ->
                "Blaze couldn't prepare the photo file for the camera app, so Blaze's own camera is opening."
            kind == "Broken" -> "The camera app didn't hand the photo back, so Blaze's own camera is opening."
            else -> "The camera app failed (${kind.substringAfterLast('.')}), so Blaze's own camera is opening."
        }
    }

    /** The message when Blaze's own camera can't start either (then the photo picker opens). */
    fun inAppFailNote(kind: String, message: String?): String {
        val m = message.orEmpty()
        return when {
            m.contains("in use", true) || m.contains("CAMERA_IN_USE", true) || m.contains("max cameras", true) ->
                "The camera is busy in another app. Close it and try again, or pick a photo."
            m.contains("disabled", true) || m.contains("policy", true) -> "The camera is turned off on this phone (device policy). Pick a photo instead."
            m.contains("No available camera", true) || m.contains("unavailable", true) -> "Blaze couldn't find a working camera on this phone. Pick a photo instead."
            else -> "Blaze's camera couldn't start (${kind.substringAfterLast('.')}). Pick a photo instead."
        }
    }

    // ---------------------------------------------------------------- in-app camera controls

    /** Flash modes the shutter can cycle through; empty when the lens has no flash unit (most front cameras, cheap phones). */
    fun flashModes(hasFlash: Boolean): List<String> = if (hasFlash) listOf("off", "auto", "on") else emptyList()
    fun nextFlash(current: String, hasFlash: Boolean): String {
        val modes = flashModes(hasFlash); if (modes.isEmpty()) return "off"
        return modes[(modes.indexOf(current).coerceAtLeast(0) + 1) % modes.size]
    }

    /** Which lens to start on: the back one when there is one (single-camera tablets / Chromebooks only have a front one). */
    fun startLens(hasBack: Boolean, hasFront: Boolean, wanted: String = "back"): String? = when {
        wanted == "front" && hasFront -> "front"
        hasBack -> "back"
        hasFront -> "front"
        else -> null
    }
    fun canFlip(hasBack: Boolean, hasFront: Boolean) = hasBack && hasFront

    /** Target size for a saved photo: long edge ~1600 px (plenty for the model, small enough for low-end phones). */
    const val TARGET_LONG_EDGE = 1600

    /** Power-of-two decode sample that keeps the long edge at or above [max] (BitmapFactory.inSampleSize). */
    fun sampleFor(width: Int, height: Int, max: Int = TARGET_LONG_EDGE): Int {
        var s = 1
        val long = maxOf(width, height)
        while (long / (s * 2) >= max) s *= 2
        return s
    }

    /** Final size after scaling the long edge down to [max] (never up). */
    fun scaledSize(width: Int, height: Int, max: Int = TARGET_LONG_EDGE): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= max || long <= 0) return width to height
        val f = max.toFloat() / long
        return maxOf(1, Math.round(width * f)) to maxOf(1, Math.round(height * f))
    }

    /** Degrees to rotate for an EXIF orientation value (1..8; mirrored ones rotate like their plain twin). */
    fun exifDegrees(orientation: Int): Int = when (orientation) {
        3, 4 -> 180
        5, 6 -> 90
        7, 8 -> 270
        else -> 0
    }
    fun exifMirrored(orientation: Int): Boolean = orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7
}
