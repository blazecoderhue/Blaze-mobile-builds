package com.blaze.assistant.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v0.25.1: Blaze's RFB viewer: VNC auth, wire messages, RGB565 pixels, CopyRect. */
class RfbTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun vncAuthMatchesReferenceDes() {
        // Reference: DES-ECB with the bit-reversed password key (computed with PyCryptodome).
        assertEquals("f599816b0beca0d711fa078aedb50e42", hex(Rfb.vncResponse(ByteArray(16) { it.toByte() }, "blaze123")))
        assertEquals(0x80.toByte(), Rfb.reverseBits(1)); assertEquals(0x01.toByte(), Rfb.reverseBits(0x80.toByte()))
    }

    @Test fun messages() {
        assertArrayEquals(byteArrayOf(5, 1, 0, 10, 0, 20), Rfb.Msg.pointer(1, 10, 20))
        assertArrayEquals(byteArrayOf(4, 1, 0, 0, 0, 0, 0xff.toByte(), 0x0d), Rfb.Msg.key(true, Rfb.XK_RETURN))
        assertArrayEquals(byteArrayOf(3, 1, 0, 0, 0, 0, 4, 0, 2, 64), Rfb.Msg.updateRequest(true, 0, 0, 1024, 576))
        val enc = Rfb.Msg.setEncodings(); assertEquals(2.toByte(), enc[0]); assertEquals(3, enc[3].toInt())
        val pf = Rfb.Msg.setPixelFormat565(); assertEquals(20, pf.size); assertEquals(16.toByte(), pf[4]); assertEquals(11.toByte(), pf[14])
    }

    @Test fun keysyms() {
        assertEquals('a'.code, Rfb.keysym('a')); assertEquals(Rfb.XK_RETURN, Rfb.keysym('\n'))
        assertEquals(0x01000000 or 0x20AC, Rfb.keysym('\u20ac'))
    }

    @Test fun pixels() {
        assertEquals(0xFFFF0000.toInt(), Rfb.rgb565(0xF800)); assertEquals(0xFF00FF00.toInt(), Rfb.rgb565(0x07E0))
        assertEquals(0xFF0000FF.toInt(), Rfb.rgb565(0x001F)); assertEquals(0xFFFFFFFF.toInt(), Rfb.rgb565(0xFFFF))
        val fb = ShortArray(4 * 3)
        Rfb.blitRaw(fb, 4, 3, 1, 1, 2, 1, byteArrayOf(0x1F, 0, 0, 0xF8.toByte()))
        assertEquals(0x001F.toShort(), fb[5]); assertEquals(0xF800.toShort(), fb[6])
        Rfb.copyRect(fb, 4, 3, 1, 1, 2, 2, 2, 1)
        assertEquals(0x001F.toShort(), fb[10]); assertEquals(0xF800.toShort(), fb[11])
        Rfb.blitRaw(fb, 4, 3, 3, 2, 2, 1, byteArrayOf(1, 0, 2, 0))   // clipped at the edge, no crash
        assertEquals(1.toShort(), fb[11])
    }

    @Test fun versions() {
        assertEquals(8, Rfb.version("RFB 003.008\n")); assertEquals(7, Rfb.version("RFB 003.007\n"))
        assertEquals(3, Rfb.version("RFB 003.003\n")); assertNull(Rfb.version("HTTP/1.1 200"))
    }
}
