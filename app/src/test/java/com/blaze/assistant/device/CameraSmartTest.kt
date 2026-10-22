package com.blaze.assistant.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.34.0 Blaze Camera: stacking, HDR fusion, document corners, pro maths and reading prices / sums / barcodes. */
class CameraSmartTest {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun alignFindsTheShift() {
        val w = 40; val h = 40
        val ref = IntArray(w * h) { i -> ((i % w) * 7 + (i / w) * 13) % 256 }
        val moved = IntArray(w * h) { i -> val x = i % w; val y = i / w; val sx = (x + 3).coerceAtMost(w - 1); val sy = (y - 2).coerceAtLeast(0); ref[sy * w + sx] }
        assertEquals(-3 to 2, CameraSmart.align(ref, moved, w, h, 5))
        assertEquals(0 to 0, CameraSmart.align(ref, ref, w, h, 5))
    }

    @Test fun stackAveragesNoise() {
        val a = IntArray(4) { grey(100) }; val b = IntArray(4) { grey(120) }
        assertEquals(grey(110), CameraSmart.stack(listOf(a, b), listOf(0 to 0, 0 to 0), 2, 2)[0])
        assertEquals(grey(255), CameraSmart.lift(intArrayOf(grey(255)))[0])
        assertTrue(CameraSmart.luma(CameraSmart.lift(intArrayOf(grey(40)))[0]) > 40)
        assertTrue(CameraSmart.nightGamma(IntArray(64) { grey(10) }) < CameraSmart.nightGamma(IntArray(64) { grey(200) }))
    }

    @Test fun fusionTakesTheWellExposedFrame() {
        val dark = intArrayOf(grey(10), grey(120)); val bright = intArrayOf(grey(128), grey(250))
        val f = CameraSmart.fuse(listOf(dark, bright))
        assertTrue(CameraSmart.luma(f[0]) > 100)   // shadow from the bright frame
        assertTrue(CameraSmart.luma(f[1]) < 160)   // highlight from the dark frame
        assertEquals(listOf(-6, 0, 6), CameraSmart.bracket(-12, 12, 1f / 3))
        assertEquals(listOf(-2, 0, 2), CameraSmart.bracket(-2, 2, 0.5f))
        assertEquals(listOf(0), CameraSmart.bracket(0, 0, 0f))
    }

    @Test fun documentCorners() {
        val o = CameraSmart.order(listOf(90f to 10f, 10f to 12f, 12f to 95f, 92f to 90f))
        assertEquals(listOf(10f to 12f, 90f to 10f, 92f to 90f, 12f to 95f), o)
        assertEquals(listOf(8f to 8f, 92f to 8f, 92f to 92f, 8f to 92f), CameraSmart.defaultCorners(100, 100))
        assertEquals(100 to 50, CameraSmart.pageSize(listOf(0f to 0f, 100f to 0f, 100f to 50f, 0f to 50f)))
        assertEquals(2, CameraSmart.nearest(listOf(0f to 0f, 100f to 0f, 100f to 50f, 0f to 50f), 90f, 45f))
    }

    @Test fun proMaths() {
        assertEquals(15.0, CameraSmart.ev100(16.0, 1.0 / 125, 100.0), 0.05)          // sunny 16
        assertEquals(1.0 / 500, CameraSmart.equivalentShutter(1.0 / 125, 16.0, 100.0, 8.0, 100.0), 1e-9)
        assertEquals(1.0 / 52, CameraSmart.handheld(26.0, 2.0), 1e-9)
        assertEquals(1.0 / 13, CameraSmart.handheld(26.0, 2.0, 2), 1e-9)
        assertEquals(30.0, CameraSmart.ndShutter(30.0 / 1024, 10), 1e-9)
        val hf = CameraSmart.hyperfocal(4.3, 1.8, 0.005)
        assertEquals(2.06, hf, 0.01)
        val (near, far) = CameraSmart.depthOfField(4.3, 1.8, 0.005, 1.0)
        assertTrue(near < 1.0 && far > 1.0)
        assertTrue(CameraSmart.depthOfField(4.3, 1.8, 0.005, 3.0).second.isInfinite())
        assertEquals("1/250 s", CameraSmart.shutterText(0.004)); assertEquals("2 s", CameraSmart.shutterText(2.0)); assertEquals("30 s", CameraSmart.shutterText(30.0))
    }

    @Test fun readsPrices() {
        val p = CameraSmart.prices("Bread K25.00\nMilk ZMW 40\nSugar K 1,250.50\nCoke \$3.99 and 12,50 EUR")
        assertEquals(listOf(CameraSmart.Price("ZMW", 25.0), CameraSmart.Price("ZMW", 40.0), CameraSmart.Price("ZMW", 1250.5),
            CameraSmart.Price("$", 3.99), CameraSmart.Price("EUR", 12.5)), p)
        assertEquals(1315.5, CameraSmart.totals(p)["ZMW"]!!, 1e-9)
        assertEquals(1250.5, CameraSmart.normalizeNumber("1 250,50")!!, 1e-9)
        assertTrue(CameraSmart.prices("no money here 2026").isEmpty())
    }

    @Test fun readsSums() {
        assertEquals(listOf("12 * 4 + 3", "45/9", "(2+3)*4"), CameraSmart.sums("12 × 4 + 3 =\nhello\n45÷9\n(2+3)x4\n2026\n1,000"))
    }

    @Test fun productCodes() {
        assertTrue(CameraSmart.isProductCode("4006381333931"))   // EAN-13
        assertTrue(CameraSmart.isProductCode("96385074"))        // EAN-8
        assertTrue(CameraSmart.isProductCode("036000291452"))    // UPC-A
        assertFalse(CameraSmart.isProductCode("4006381333932")); assertFalse(CameraSmart.isProductCode("https://x"))
        assertEquals("Coca-Cola Coke Zero \u00b7 330 ml", CameraSmart.product("""{"status":1,"product":{"brands":"Coca-Cola,Coke","product_name":"Coke Zero","quantity":"330 ml"}}"""))
        assertNull(CameraSmart.product("""{"status":0}""")); assertNull(CameraSmart.product("x"))
    }
}
