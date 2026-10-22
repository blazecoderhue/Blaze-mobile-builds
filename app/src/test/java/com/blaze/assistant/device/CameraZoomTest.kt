package com.blaze.assistant.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** v0.30.x Blaze Camera Pro: zoom presets, the protractor dial (log scale), digital zoom, focus, exposure, timer. */
class CameraZoomTest {
    private fun near(expected: Float, actual: Float, tol: Float = 1e-3f) =
        assertTrue("expected $expected, got $actual", abs(expected - actual) <= tol)

    // ---------------------------------------------------------------- presets

    @Test fun presetsOnAPhoneWithoutAnUltrawide() {
        // low-end phone through CameraX: min 1x, hardware max ~8x, digital takes it to 10x.
        val max = CameraZoom.maxTotal(8f)
        assertEquals(10f, max)
        assertEquals(listOf(1f, 2f, 5f, 10f), CameraZoom.presets(1f, max))
    }
    @Test fun ultrawideAddsTheHalfButtonAtTheRealMinimum() {
        assertEquals(listOf(0.5f, 1f, 2f, 5f, 10f), CameraZoom.presets(0.5f, 10f))
        // A 0.6x ultrawide: the button sits at 0.6, not at a 0.5 the camera can't reach.
        assertEquals(listOf(0.6f, 1f, 2f, 5f, 10f), CameraZoom.presets(0.6f, 10f))
        // 0.9 is no ultrawide.
        assertEquals(listOf(1f, 2f, 5f, 10f), CameraZoom.presets(0.9f, 10f))
    }
    @Test fun frontCameraOnlyShowsWhatFits() {
        val max = CameraZoom.maxTotal(1f, front = true)
        assertEquals(4f, max)
        assertEquals(listOf(1f, 2f), CameraZoom.presets(1f, max))
    }
    @Test fun aLensBeyondTenKeepsItsOwnMax() {
        assertEquals(30f, CameraZoom.maxTotal(30f))
        assertEquals(10f, CameraZoom.maxTotal(Float.NaN))
        assertEquals(1f, CameraZoom.minTotal(0f))
        assertEquals(0.5f, CameraZoom.minTotal(0.5f))
    }
    @Test fun activePresetIsTheLargestAtOrBelow() {
        val p = listOf(0.5f, 1f, 2f, 5f, 10f)
        assertEquals(1f, CameraZoom.activePreset(1f, p))
        assertEquals(2f, CameraZoom.activePreset(3.4f, p))
        assertEquals(2f, CameraZoom.activePreset(1.99f, p))    // within 2% counts as 2x
        assertEquals(5f, CameraZoom.activePreset(9.5f, p))
        assertEquals(10f, CameraZoom.activePreset(10f, p))
        assertEquals(0.5f, CameraZoom.activePreset(0.7f, p))
        assertEquals(0.5f, CameraZoom.activePreset(0.4f, p))   // below the first: the first
        assertNull(CameraZoom.activePreset(2f, emptyList()))
        assertTrue(CameraZoom.onPreset(2.01f, 2f)); assertFalse(CameraZoom.onPreset(2.3f, 2f))
    }
    @Test fun labels() {
        assertEquals("1x", CameraZoom.label(1f)); assertEquals("3.4x", CameraZoom.label(3.42f)); assertEquals("10x", CameraZoom.label(9.98f))
        assertEquals("0.5x", CameraZoom.label(0.5f))
        assertEquals("1x", CameraZoom.presetLabel(1f)); assertEquals("0.5", CameraZoom.presetLabel(0.5f)); assertEquals("5", CameraZoom.presetLabel(5f))
    }

    // ---------------------------------------------------------------- dial: angle <-> ratio, log scale

    @Test fun arcEndsAreTheRangeEnds() {
        near(180f, CameraZoom.angleFor(1f, 1f, 10f)); near(0f, CameraZoom.angleFor(10f, 1f, 10f))
        near(1f, CameraZoom.ratioFor(180f, 1f, 10f)); near(10f, CameraZoom.ratioFor(0f, 1f, 10f))
    }
    @Test fun logScaleGivesEqualArcToEqualFactors() {
        // 1→10 on a log scale: the middle of the arc is √10 ≈ 3.16x, and 1→2 spans as much as 5→10.
        near(3.1623f, CameraZoom.ratioFor(90f, 1f, 10f))
        val oneToTwo = CameraZoom.angleFor(1f, 1f, 10f) - CameraZoom.angleFor(2f, 1f, 10f)
        val fiveToTen = CameraZoom.angleFor(5f, 1f, 10f) - CameraZoom.angleFor(10f, 1f, 10f)
        near(oneToTwo, fiveToTen, 1e-2f)
    }
    @Test fun angleAndRatioRoundTrip() {
        for (r in listOf(0.5f, 0.8f, 1f, 1.7f, 2f, 3.4f, 5f, 7.3f, 10f)) near(r, CameraZoom.ratioFor(CameraZoom.angleFor(r, 0.5f, 10f), 0.5f, 10f), 2e-3f)
        for (a in listOf(0f, 12.5f, 45f, 90f, 133f, 180f)) near(a, CameraZoom.angleFor(CameraZoom.ratioFor(a, 1f, 10f), 1f, 10f), 1e-2f)
    }
    @Test fun outOfRangeClamps() {
        near(1f, CameraZoom.ratioFor(250f, 1f, 10f)); near(10f, CameraZoom.ratioFor(-30f, 1f, 10f))
        near(0f, CameraZoom.angleFor(40f, 1f, 10f)); near(180f, CameraZoom.angleFor(0.1f, 1f, 10f))
        near(180f, CameraZoom.angleFor(3f, 2f, 2f))   // degenerate range
    }
    @Test fun touchAngleFollowsTheFingerAroundTheArc() {
        near(0f, CameraZoom.touchAngle(100f, 0f))      // right
        near(90f, CameraZoom.touchAngle(0f, -100f))    // straight up (screen y is down)
        near(180f, CameraZoom.touchAngle(-100f, 0f))   // left
        near(45f, CameraZoom.touchAngle(100f, -100f))
        near(0f, CameraZoom.touchAngle(80f, 40f))      // slid below the centre on the right: pinned to max end
        near(180f, CameraZoom.touchAngle(-80f, 40f))   // and on the left: min end
    }
    @Test fun stopsAndTicks() {
        assertEquals(listOf(1f, 2f, 5f, 10f), CameraZoom.stops(1f, 10f))
        assertEquals(listOf(0.5f, 1f, 2f, 5f, 10f), CameraZoom.stops(0.5f, 10f))
        assertEquals(listOf(1f, 2f, 4f), CameraZoom.stops(1f, 4f))       // front: the far end gets a label
        assertEquals(listOf(1f, 2f, 5f, 10f, 30f), CameraZoom.stops(1f, 30f))
        val ticks = CameraZoom.minorTicks(1f, 10f)
        assertEquals(37, ticks.size); near(1f, ticks.first()); near(10f, ticks.last())
        assertTrue(ticks.zipWithNext().all { (a, b) -> b > a })
    }
    @Test fun hapticTicksAtLabelledStops() {
        val s = listOf(1f, 2f, 5f, 10f)
        assertEquals(2f, CameraZoom.crossedStop(1.8f, 2.3f, s))
        assertEquals(5f, CameraZoom.crossedStop(1.8f, 6f, s))     // a fast flick past two: tick for the last one
        assertEquals(2f, CameraZoom.crossedStop(5.5f, 1.5f, s))   // going down: the last one passed is 2
        assertEquals(2f, CameraZoom.crossedStop(1.9f, 2f, s))     // landing exactly on a stop ticks
        assertNull(CameraZoom.crossedStop(2f, 2.2f, s))           // leaving it doesn't tick again
        assertNull(CameraZoom.crossedStop(2.2f, 2.4f, s))
        assertNull(CameraZoom.crossedStop(3f, 3f, s))
    }
    @Test fun releaseSnapsToANearbyStop() {
        val s = CameraZoom.stops(1f, 10f)
        near(2f, CameraZoom.snap(2.05f, 1f, 10f, s))
        near(3.4f, CameraZoom.snap(3.4f, 1f, 10f, s))
    }
    @Test fun pinchScalesTheTotal() {
        near(4f, CameraZoom.pinch(2f, 2f, 1f, 10f)); near(10f, CameraZoom.pinch(8f, 2f, 1f, 10f)); near(1f, CameraZoom.pinch(1.2f, 0.5f, 1f, 10f))
        near(3f, CameraZoom.pinch(3f, Float.NaN, 1f, 10f))
    }

    // ---------------------------------------------------------------- hardware / digital

    @Test fun splitBelowTheLensMaxIsAllHardware() {
        val s = CameraZoom.split(3f, 8f)
        near(3f, s.hw); near(1f, s.digital); assertFalse(s.isDigital)
    }
    @Test fun splitPastTheLensMaxGoesDigitalAndMultipliesBack() {
        val s = CameraZoom.split(10f, 4f)
        near(4f, s.hw); near(2.5f, s.digital); assertTrue(s.isDigital)
        near(10f, s.hw * s.digital)
        // Seamless across the boundary: just above the max, digital is just above 1.
        near(1.0025f, CameraZoom.split(4.01f, 4f).digital, 1e-4f)
        near(1f, CameraZoom.split(4f, 4f).digital)
        // A fixed-focus front camera with no zoom at all: everything is digital.
        near(3f, CameraZoom.split(3f, 1f).digital)
    }
    @Test fun cropIsCentredAndMatchesTheFactor() {
        assertEquals(CameraZoom.Crop(0, 0, 4000, 3000), CameraZoom.cropRect(4000, 3000, 1f))
        assertEquals(CameraZoom.Crop(1000, 750, 2000, 1500), CameraZoom.cropRect(4000, 3000, 2f))
        val c = CameraZoom.cropRect(1200, 1600, 2.5f)
        assertEquals(480, c.width); assertEquals(640, c.height); assertEquals(360, c.left); assertEquals(480, c.top)
        assertEquals(CameraZoom.Crop(0, 0, 100, 100), CameraZoom.cropRect(100, 100, 0.5f))   // never zooms out
        assertEquals(1, CameraZoom.cropRect(10, 10, 1000f).width)                              // never empty
    }
    @Test fun cropDecodeSampleAndOutputSize() {
        assertEquals(1, CameraZoom.cropSample(2000, 1500, 1600))
        assertEquals(2, CameraZoom.cropSample(4000, 3000, 1600))
        assertEquals(1600 to 1200, CameraZoom.outputSize(4000, 3000, 1600))
        assertEquals(800 to 600, CameraZoom.outputSize(800, 600, 1600))
    }
    @Test fun sharpeningGrowsGentlyWithDigitalZoom() {
        near(0f, CameraZoom.sharpenAmount(1f)); near(0.5f, CameraZoom.sharpenAmount(2f)); near(0.9f, CameraZoom.sharpenAmount(10f))
        assertTrue(CameraZoom.sharpenAmount(3f) > CameraZoom.sharpenAmount(1.5f))
    }
    @Test fun tapOnAScaledPreviewMapsBackToTheView() {
        val (x, y) = CameraZoom.unscale(300f, 100f, 200f, 200f, 2f)
        near(250f, x); near(150f, y)
        val (cx, cy) = CameraZoom.unscale(200f, 200f, 200f, 200f, 5f)   // the centre stays the centre
        near(200f, cx); near(200f, cy)
        near(300f, CameraZoom.unscale(300f, 0f, 200f, 200f, 1f).first)
    }

    // ---------------------------------------------------------------- exposure

    @Test fun exposureSlider() {
        assertEquals(-12, CameraZoom.evIndex(0f, -12, 12)); assertEquals(12, CameraZoom.evIndex(1f, -12, 12)); assertEquals(0, CameraZoom.evIndex(0.5f, -12, 12))
        assertEquals(0, CameraZoom.evIndex(0.7f, 0, 0))       // no exposure compensation on this camera
        near(0.75f, CameraZoom.evFraction(6, -12, 12))
        assertEquals("+0.7", CameraZoom.evLabel(4, 1f / 6f)); assertEquals("-2.0", CameraZoom.evLabel(-12, 1f / 6f)); assertEquals("0", CameraZoom.evLabel(0, 0.5f))
    }

    // ---------------------------------------------------------------- timer

    @Test fun timerCyclesOffThreeTen() {
        assertEquals(3, CameraTimer.next(0)); assertEquals(10, CameraTimer.next(3)); assertEquals(0, CameraTimer.next(10))
        assertEquals(3, CameraTimer.next(7))
        assertEquals(0, CameraTimer.normalize(5)); assertEquals(10, CameraTimer.normalize(10))
        assertEquals("Timer off", CameraTimer.label(0)); assertEquals("Timer 3s", CameraTimer.label(3))
    }
    @Test fun countdownShowsWholeSeconds() {
        assertEquals(3, CameraTimer.remaining(0, 0, 3))
        assertEquals(3, CameraTimer.remaining(0, 1, 3))
        assertEquals(2, CameraTimer.remaining(0, 1000, 3))
        assertEquals(1, CameraTimer.remaining(0, 2500, 3))
        assertEquals(0, CameraTimer.remaining(0, 3000, 3))
        assertEquals(10, CameraTimer.remaining(5000, 4000, 10))   // clock went backwards: full count
        assertEquals(0, CameraTimer.remaining(0, 99, 0))
    }

    // ---------------------------------------------------------------- unsharp mask

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun flatImageIsUnchanged() {
        val px = IntArray(16) { gray(120) }
        assertArrayEquals(px, Sharpen.unsharp(px, 4, 4, 0.8f))
    }
    @Test fun edgesGetMoreContrastAndStayInRange() {
        // A vertical edge: dark left half, light right half.
        val w = 6; val h = 3
        val px = IntArray(w * h) { i -> if (i % w < 3) gray(60) else gray(200) }
        val out = Sharpen.unsharp(px, w, h, 0.8f)
        val darkEdge = out[1 * w + 2] and 255; val lightEdge = out[1 * w + 3] and 255
        assertTrue("dark side darker: $darkEdge", darkEdge < 60)
        assertTrue("light side lighter: $lightEdge", lightEdge > 200)
        assertEquals(60, out[1 * w + 0] and 255)   // far from the edge: untouched
        out.forEach { p -> assertEquals(0xFF, p ushr 24); assertTrue((p and 255) in 0..255) }
        val hard = IntArray(w * h) { i -> if (i % w < 3) gray(5) else gray(250) }
        Sharpen.unsharp(hard, w, h, 0.9f).forEach { p -> assertTrue((p shr 16 and 255) in 0..255) }
    }
    @Test fun zeroAmountOrBadSizeReturnsTheInput() {
        val px = IntArray(4) { gray(it * 40) }
        assertTrue(px === Sharpen.unsharp(px, 2, 2, 0f))
        assertTrue(px === Sharpen.unsharp(px, 3, 3, 0.5f))
    }
}
