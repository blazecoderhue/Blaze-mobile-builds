package com.blaze.assistant.desktop

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * v0.25.1: a small native RFB (VNC) client for Blaze's own Linux desktop. Protocol 3.8 (3.3 / 3.7 accepted), VNC
 * password auth, 16-bit RGB565 pixels, Raw + CopyRect + DesktopSize encodings, pointer and key events. It only ever
 * connects to 127.0.0.1. The message builders and pixel maths are pure ([Msg], [vncResponse], [rgb565]) and tested
 * (RfbTest); [Client] runs the socket on its own thread and keeps a [ShortArray] framebuffer the viewer draws.
 */
object Rfb {
    // ---------- auth ----------
    /** VNC auth: DES-encrypt the 16-byte challenge with the password (8 bytes, each byte's bits reversed). */
    fun vncResponse(challenge: ByteArray, password: String): ByteArray {
        val key = ByteArray(8)
        val pw = password.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until 8) key[i] = reverseBits(if (i < pw.size) pw[i] else 0)
        val c = Cipher.getInstance("DES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return c.doFinal(challenge.copyOf(16))
    }

    fun reverseBits(b: Byte): Byte {
        var x = b.toInt() and 0xFF; var r = 0
        repeat(8) { r = (r shl 1) or (x and 1); x = x shr 1 }
        return r.toByte()
    }

    // ---------- client → server messages ----------
    object Msg {
        /** SetPixelFormat: 16 bpp, depth 16, little-endian, true colour, RGB565. */
        fun setPixelFormat565(): ByteArray = byteArrayOf(0, 0, 0, 0,
            16, 16, 0, 1, 0, 31, 0, 63, 0, 31, 11, 5, 0, 0, 0, 0)
        /** SetEncodings: CopyRect (1), Raw (0), DesktopSize (-223). */
        fun setEncodings(encs: IntArray = intArrayOf(1, 0, -223)): ByteArray {
            val b = java.io.ByteArrayOutputStream(); val o = DataOutputStream(b)
            o.writeByte(2); o.writeByte(0); o.writeShort(encs.size); encs.forEach { o.writeInt(it) }
            return b.toByteArray()
        }
        fun updateRequest(incremental: Boolean, x: Int, y: Int, w: Int, h: Int): ByteArray {
            val b = java.io.ByteArrayOutputStream(); val o = DataOutputStream(b)
            o.writeByte(3); o.writeByte(if (incremental) 1 else 0); o.writeShort(x); o.writeShort(y); o.writeShort(w); o.writeShort(h)
            return b.toByteArray()
        }
        fun key(down: Boolean, keysym: Int): ByteArray {
            val b = java.io.ByteArrayOutputStream(); val o = DataOutputStream(b)
            o.writeByte(4); o.writeByte(if (down) 1 else 0); o.writeShort(0); o.writeInt(keysym)
            return b.toByteArray()
        }
        /** [mask]: bit 0 left, 1 middle, 2 right, 3 wheel up, 4 wheel down. */
        fun pointer(mask: Int, x: Int, y: Int): ByteArray {
            val b = java.io.ByteArrayOutputStream(); val o = DataOutputStream(b)
            o.writeByte(5); o.writeByte(mask and 0xFF); o.writeShort(x.coerceIn(0, 65535)); o.writeShort(y.coerceIn(0, 65535))
            return b.toByteArray()
        }
    }

    // ---------- keys ----------
    const val XK_RETURN = 0xff0d; const val XK_BACKSPACE = 0xff08; const val XK_TAB = 0xff09; const val XK_ESCAPE = 0xff1b
    const val XK_LEFT = 0xff51; const val XK_UP = 0xff52; const val XK_RIGHT = 0xff53; const val XK_DOWN = 0xff54
    const val XK_CONTROL_L = 0xffe3; const val XK_ALT_L = 0xffe9; const val XK_SUPER_L = 0xffeb; const val XK_DELETE = 0xffff

    /** The X keysym for a typed character (Latin-1 directly, everything else as a Unicode keysym). */
    fun keysym(ch: Char): Int = when (ch) {
        '\n' -> XK_RETURN; '\t' -> XK_TAB; '\b' -> XK_BACKSPACE
        else -> if (ch.code in 0x20..0xFF) ch.code else 0x01000000 or ch.code
    }

    // ---------- pixels ----------
    /** RGB565 → opaque ARGB (for screenshots / tests). */
    fun rgb565(v: Int): Int {
        val r = (v shr 11) and 31; val g = (v shr 5) and 63; val b = v and 31
        return (0xFF shl 24) or ((r * 255 / 31) shl 16) or ((g * 255 / 63) shl 8) or (b * 255 / 31)
    }

    /** Copies a w×h rectangle of little-endian RGB565 [src] bytes into the [fb] (stride [fbW]) at (x, y), clipped. */
    fun blitRaw(fb: ShortArray, fbW: Int, fbH: Int, x: Int, y: Int, w: Int, h: Int, src: ByteArray) {
        for (row in 0 until h) {
            val fy = y + row; if (fy !in 0 until fbH) continue
            var si = row * w * 2
            for (col in 0 until w) {
                val fx = x + col
                if (fx in 0 until fbW) fb[fy * fbW + fx] = ((src[si].toInt() and 0xFF) or ((src[si + 1].toInt() and 0xFF) shl 8)).toShort()
                si += 2
            }
        }
    }

    /** CopyRect: moves a w×h block from (sx, sy) to (x, y), safe for overlapping areas. */
    fun copyRect(fb: ShortArray, fbW: Int, fbH: Int, sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int) {
        val tmp = ShortArray(w * h)
        for (r in 0 until h) for (c in 0 until w) {
            val yy = sy + r; val xx = sx + c
            tmp[r * w + c] = if (yy in 0 until fbH && xx in 0 until fbW) fb[yy * fbW + xx] else 0
        }
        for (r in 0 until h) for (c in 0 until w) {
            val yy = y + r; val xx = x + c
            if (yy in 0 until fbH && xx in 0 until fbW) fb[yy * fbW + xx] = tmp[r * w + c]
        }
    }

    /** Parses "RFB 003.008\n" → minor version (3, 7 or 8); anything else → null. */
    fun version(banner: String): Int? = Regex("""^RFB 003\.(\d{3})\n?$""").find(banner)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it >= 8) 8 else if (it == 7) 7 else 3 }

    class AuthFailed(msg: String) : Exception(msg)

    /**
     * One connection. [onFrame] runs on the socket thread after each update (the viewer just invalidates);
     * [onClosed] gets the reason ("" for a normal stop).
     */
    class Client(private val password: String, private val onFrame: () -> Unit, private val onClosed: (String) -> Unit,
                 private val onCutText: (String) -> Unit = {}) {
        @Volatile var width = 0; private set
        @Volatile var height = 0; private set
        @Volatile var fb = ShortArray(0); private set
        @Volatile var running = false; private set
        private var sock: Socket? = null
        private var out: DataOutputStream? = null
        private var thread: Thread? = null
        private val lock = Any()

        /** True once the TCP connection is made (a failure before that is worth retrying while the desktop starts). */
        @Volatile private var connected = false

        /**
         * v0.43.0: [retryWindowMs] > 0 keeps retrying a refused / timed-out connection for that long
         * ([DesktopHealth.retryDelay]) instead of failing at once while the desktop is still starting. [onRetry] gets
         * the attempt number. [onClosed] still gets the raw reason; the viewer shows [DesktopHealth.plainError] of it.
         */
        fun connect(host: String = "127.0.0.1", port: Int = DesktopPlan.PORT, retryWindowMs: Long = 0, onRetry: (Int) -> Unit = {}) {
            require(host == "127.0.0.1" || host == "localhost") { "local only" }
            running = true
            thread = Thread({
                var reason = ""
                val t0 = System.currentTimeMillis(); var attempt = 0
                while (true) {
                    connected = false
                    try { session(host, port); reason = ""; break }
                    catch (e: AuthFailed) { reason = e.message ?: "auth failed"; break }
                    catch (e: EOFException) { reason = if (running) "The desktop closed the connection." else ""; break }
                    catch (e: Exception) {
                        val raw = e.message ?: e.javaClass.simpleName
                        val wait = if (running && !connected && retryWindowMs > 0 && DesktopHealth.retryable(raw))
                            DesktopHealth.retryDelay(attempt, System.currentTimeMillis() - t0, retryWindowMs) else null
                        if (wait == null) { reason = if (running) raw else ""; break }
                        attempt++
                        try { onRetry(attempt) } catch (_: Exception) { }
                        try { sock?.close() } catch (_: Exception) { }
                        try { Thread.sleep(wait) } catch (_: InterruptedException) { }
                        if (!running) { reason = ""; break }
                    }
                }
                running = false
                try { sock?.close() } catch (_: Exception) { }
                onClosed(reason)
            }, "rfb").apply { isDaemon = true; start() }
        }

        fun close() { running = false; try { sock?.close() } catch (_: Exception) { } }

        private fun send(b: ByteArray) { synchronized(lock) { try { out?.write(b); out?.flush() } catch (_: Exception) { } } }
        fun pointer(mask: Int, x: Int, y: Int) = send(Msg.pointer(mask, x, y))
        fun click(x: Int, y: Int, button: Int = 1) { val m = 1 shl (button - 1); pointer(0, x, y); pointer(m, x, y); pointer(0, x, y) }
        fun scroll(x: Int, y: Int, up: Boolean) { val m = if (up) 8 else 16; pointer(m, x, y); pointer(0, x, y) }
        fun key(keysym: Int) { send(Msg.key(true, keysym)); send(Msg.key(false, keysym)) }
        fun type(text: String) = text.forEach { key(keysym(it)) }
        /** v0.34.0: the phone's clipboard into the desktop's (ClientCutText). */
        fun cutText(text: String) = send(DesktopExtras.clientCutText(text))

        private fun session(host: String, port: Int) {
            val s = Socket(); s.tcpNoDelay = true; sock = s; s.connect(InetSocketAddress(host, port), 5_000); s.soTimeout = 0
            connected = true
            val inp = DataInputStream(s.getInputStream().buffered(64 * 1024))
            val o = DataOutputStream(s.getOutputStream().buffered(8 * 1024)); out = o
            val banner = ByteArray(12).also { inp.readFully(it) }
            val minor = version(String(banner, Charsets.ISO_8859_1)) ?: throw AuthFailed("Not a VNC server.")
            send("RFB 003.00$minor\n".toByteArray(Charsets.ISO_8859_1))
            val secType: Int = if (minor >= 7) {
                val n = inp.readUnsignedByte()
                if (n == 0) throw AuthFailed(reasonOf(inp))
                val types = ByteArray(n).also { inp.readFully(it) }.map { it.toInt() and 0xFF }
                val pick = if (2 in types) 2 else if (1 in types) 1 else throw AuthFailed("No supported sign-in.")
                send(byteArrayOf(pick.toByte())); pick
            } else inp.readInt()
            if (secType == 2) {
                val ch = ByteArray(16).also { inp.readFully(it) }
                send(vncResponse(ch, password))
            }
            if (secType == 2 || minor >= 8) {
                val res = inp.readInt()
                if (res != 0) throw AuthFailed(if (minor >= 8) reasonOf(inp) else "Wrong desktop password.")
            }
            send(byteArrayOf(1))   // ClientInit: shared
            val w = inp.readUnsignedShort(); val h = inp.readUnsignedShort()
            inp.readFully(ByteArray(16))
            inp.readFully(ByteArray(inp.readInt()))
            resize(w, h)
            send(Msg.setPixelFormat565()); send(Msg.setEncodings())
            send(Msg.updateRequest(false, 0, 0, width, height))
            while (running) {
                when (val t = inp.readUnsignedByte()) {
                    0 -> {
                        inp.readByte()
                        val n = inp.readUnsignedShort()
                        repeat(n) {
                            val x = inp.readUnsignedShort(); val y = inp.readUnsignedShort(); val rw = inp.readUnsignedShort(); val rh = inp.readUnsignedShort()
                            when (val enc = inp.readInt()) {
                                0 -> { val buf = ByteArray(rw * rh * 2); inp.readFully(buf); blitRaw(fb, width, height, x, y, rw, rh, buf) }
                                1 -> { val sx = inp.readUnsignedShort(); val sy = inp.readUnsignedShort(); copyRect(fb, width, height, sx, sy, x, y, rw, rh) }
                                -223 -> resize(rw, rh)
                                else -> throw IllegalStateException("unexpected encoding $enc")
                            }
                        }
                        onFrame()
                        send(Msg.updateRequest(true, 0, 0, width, height))
                    }
                    1 -> { inp.readByte(); inp.readUnsignedShort(); val k = inp.readUnsignedShort(); inp.readFully(ByteArray(k * 6)) }
                    2 -> Unit
                    3 -> {
                        // v0.34.0 ServerCutText: something was copied in the desktop -> the phone's clipboard (up to 1 MB; more is skipped).
                        inp.readFully(ByteArray(3))
                        val n = inp.readInt()
                        if (n in 0..(1 shl 20)) { val b = ByteArray(n); inp.readFully(b); try { onCutText(DesktopExtras.serverCutText(b)) } catch (_: Exception) { } }
                        else { var left = n.toLong() and 0xFFFFFFFFL; while (left > 0) { val k = inp.skipBytes(minOf(left, 65536L).toInt()); if (k <= 0) { inp.readByte(); left-- } else left -= k } }
                    }
                    else -> throw IllegalStateException("unexpected message $t")
                }
            }
        }

        private fun resize(w: Int, h: Int) { width = w; height = h; fb = ShortArray(w * h) }
        private fun reasonOf(inp: DataInputStream): String = try { String(ByteArray(inp.readInt().coerceIn(0, 4096)).also { inp.readFully(it) }) } catch (_: Exception) { "Sign-in refused." }
    }
}
