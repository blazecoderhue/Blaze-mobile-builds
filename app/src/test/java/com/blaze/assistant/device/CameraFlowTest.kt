package com.blaze.assistant.device

import com.blaze.assistant.device.CameraFlow.Outcome
import com.blaze.assistant.device.CameraFlow.State
import com.blaze.assistant.device.CameraFlow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.28.x camera-fix: + → Camera never dead-ends on any phone (the Galaxy A07 "No camera app found." bug). */
class CameraFlowTest {
    private val ready = State(hasCamera = true, permissionDeclared = true, permissionGranted = true)

    @Test fun a07FreshInstallAsksForThePermissionFirst() {
        // CAMERA is in the merged manifest (zxing) and not granted: ACTION_IMAGE_CAPTURE would throw SecurityException.
        assertEquals(Step.REQUEST_PERMISSION, CameraFlow.next(ready.copy(permissionGranted = false)))
    }
    @Test fun rationaleThenSettingsWhenPermanentlyDenied() {
        assertEquals(Step.RATIONALE, CameraFlow.next(ready.copy(permissionGranted = false, askedBefore = true, showRationale = true)))
        assertEquals(Step.SETTINGS, CameraFlow.next(ready.copy(permissionGranted = false, askedBefore = true, showRationale = false)))
        assertEquals(Step.SETTINGS, CameraFlow.afterDenied(showRationale = false))
        assertEquals(Step.RATIONALE, CameraFlow.afterDenied(showRationale = true))
    }
    @Test fun grantedGoesToTheCameraApp() = assertEquals(Step.EXTERNAL, CameraFlow.next(ready))
    @Test fun noCameraAppGoesToBlazeCamera() = assertEquals(Step.IN_APP, CameraFlow.next(ready.copy(externalHandler = false)))
    @Test fun failedCameraAppGoesToBlazeCamera() = assertEquals(Step.IN_APP, CameraFlow.next(ready.copy(externalFailed = true)))
    @Test fun rememberedFailureSkipsTheCameraApp() = assertEquals(Step.IN_APP, CameraFlow.next(ready.copy(preferInApp = true)))
    @Test fun blazeCameraFailedFallsBackToTheCameraAppThenThePicker() {
        assertEquals(Step.EXTERNAL, CameraFlow.next(ready.copy(preferInApp = true, inAppFailed = true)))
        assertEquals(Step.PICKER, CameraFlow.next(ready.copy(externalFailed = true, inAppFailed = true)))
    }
    @Test fun noCameraAtAllPicksAPhoto() = assertEquals(Step.PICKER, CameraFlow.next(ready.copy(hasCamera = false, permissionGranted = false)))
    @Test fun withoutTheDeclaredPermissionNoBlazeCamera() {
        val s = State(hasCamera = true, permissionDeclared = false, permissionGranted = false)
        assertEquals(Step.EXTERNAL, CameraFlow.next(s))
        assertEquals(Step.PICKER, CameraFlow.next(s.copy(externalFailed = true)))
    }
    @Test fun cameraAppAnswers() {
        assertEquals(Outcome.PHOTO, CameraFlow.externalOutcome(true, 120_000, 5_000))
        assertEquals(Outcome.PHOTO, CameraFlow.externalOutcome(false, 120_000, 5_000))   // saved, then said "cancelled"
        assertEquals(Outcome.BROKEN, CameraFlow.externalOutcome(true, 0, 5_000))          // ignored EXTRA_OUTPUT
        assertEquals(Outcome.BROKEN, CameraFlow.externalOutcome(false, 0, 200))           // bounced straight back
        assertEquals(Outcome.CANCELLED, CameraFlow.externalOutcome(false, 0, 6_000))      // the user backed out
    }
    @Test fun preciseMessagesNotABlanketOne() {
        val notes = listOf(
            CameraFlow.externalFailNote("android.content.ActivityNotFoundException", "No Activity found to handle Intent"),
            CameraFlow.externalFailNote("java.lang.SecurityException", "Permission Denial: ... requires android.permission.CAMERA"),
            CameraFlow.externalFailNote("java.lang.IllegalArgumentException", "Failed to find configured root"),
            CameraFlow.externalFailNote("Broken", null))
        assertEquals(notes.size, notes.toSet().size)
        assertTrue(notes[1].contains("camera permission"))
        notes.forEach { assertFalse(it, it.contains("No camera app found")) }
        assertTrue(CameraFlow.inAppFailNote("CameraUnavailableException", "CAMERA_IN_USE").contains("busy"))
    }
    @Test fun lensesAndFlash() {
        assertEquals("back", CameraFlow.startLens(true, true))
        assertEquals("front", CameraFlow.startLens(false, true))   // front-only tablets
        assertEquals("front", CameraFlow.startLens(true, true, "front"))
        assertEquals("back", CameraFlow.startLens(true, false, "front"))
        assertEquals(null, CameraFlow.startLens(false, false))
        assertFalse(CameraFlow.canFlip(true, false))
        assertEquals(emptyList<String>(), CameraFlow.flashModes(false))
        assertEquals("off", CameraFlow.nextFlash("auto", hasFlash = false))
        assertEquals("auto", CameraFlow.nextFlash("off", hasFlash = true))
        assertEquals("off", CameraFlow.nextFlash("on", hasFlash = true))
    }
    @Test fun sizingAndExif() {
        assertEquals(2, CameraFlow.sampleFor(4000, 3000))           // 2000 px long edge ≥ 1600
        assertEquals(1, CameraFlow.sampleFor(1600, 1200))
        assertEquals(1600 to 1200, CameraFlow.scaledSize(2000, 1500))
        assertEquals(1200 to 1600, CameraFlow.scaledSize(1500, 2000))
        assertEquals(800 to 600, CameraFlow.scaledSize(800, 600))   // never upscaled
        assertEquals(90, CameraFlow.exifDegrees(6)); assertEquals(270, CameraFlow.exifDegrees(8)); assertEquals(180, CameraFlow.exifDegrees(3))
        assertEquals(0, CameraFlow.exifDegrees(1)); assertTrue(CameraFlow.exifMirrored(2)); assertFalse(CameraFlow.exifMirrored(6))
    }
}
