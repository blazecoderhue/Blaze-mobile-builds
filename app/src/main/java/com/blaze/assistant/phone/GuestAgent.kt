package com.blaze.assistant.phone

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Blaze Phone (spike): the small agent that runs INSIDE each guest process (":p0".."p3", Blaze's own uid). Blaze's main
 * process drives the guest through it: tap / type / back are injected straight into the guest's own windows on its main
 * thread, and the view tree is read from them, so no Accessibility service, ADB or Shizuku is involved.
 *
 * Transport: one abstract-namespace unix socket per slot ([socketName]), one JSON request per line, one JSON reply per
 * line. Only Blaze's own uid may talk to it (peer credentials are checked on every connection).
 *
 * Requests: {"op":"hello"} | {"op":"tap","x":..,"y":..} | {"op":"type","text":".."} | {"op":"back"} | {"op":"tree"}
 * Replies:  {"ok":true,...} or {"ok":false,"error":"…"}
 */
object GuestAgent {
    fun socketName(hostPkg: String, slotProcess: String): String = "$hostPkg.phone." + slotProcess.substringAfterLast(':')

    @Volatile private var started = false
    @Volatile var guestPkg: String? = null
    @Volatile var lastCrash: String? = null

    /** Called in a guest process once Blaze's Application is attached (BlazePhoneEngine.attach). */
    fun start(hostPkg: String, processName: String) {
        if (started) return
        started = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            lastCrash = android.util.Log.getStackTraceString(e).take(4000)
            prev?.uncaughtException(t, e)
        }
        Thread({
            val server = try { LocalServerSocket(socketName(hostPkg, processName)) } catch (e: Exception) { android.util.Log.w("BlazePhone", "guest agent socket: $e"); return@Thread }
            while (true) {
                val s = try { server.accept() } catch (e: Exception) { continue }
                Thread({ serve(s) }, "blaze-phone-guest-conn").start()
            }
        }, "blaze-phone-guest").apply { isDaemon = true }.start()
    }

    private fun serve(s: LocalSocket) {
        try {
            if (s.peerCredentials.uid != android.os.Process.myUid()) return
            val r = s.inputStream.bufferedReader(); val w = s.outputStream.bufferedWriter()
            while (true) {
                val line = r.readLine() ?: break
                val reply = try { handle(JSONObject(line)) } catch (e: Throwable) { err(e.message ?: e.javaClass.simpleName) }
                w.write(reply.toString()); w.write("\n"); w.flush()
            }
        } catch (_: Exception) {
        } finally { try { s.close() } catch (_: Exception) { } }
    }

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    private fun handle(q: JSONObject): JSONObject = when (q.optString("op")) {
        "hello" -> JSONObject().put("ok", true).put("pkg", guestPkg ?: JSONObject.NULL).put("pid", android.os.Process.myPid())
            .put("pssKb", android.os.Debug.getPss()).put("crash", lastCrash ?: JSONObject.NULL)
        "tap" -> onMain { tap(q.getDouble("x").toFloat(), q.getDouble("y").toFloat()) }
        "type" -> onMain { type(q.getString("text")) }
        "back" -> onMain { back() }
        "tree" -> onMain { tree() }
        else -> err("unknown op")
    }

    private fun onMain(block: () -> JSONObject): JSONObject {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val out = AtomicReference<JSONObject>(); val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { out.set(try { block() } catch (e: Throwable) { err(e.message ?: e.javaClass.simpleName) }); done.countDown() }
        return if (done.await(5, TimeUnit.SECONDS)) out.get() else err("the app's main thread is busy (no answer in 5 s)")
    }

    /** Every visible root view of this process, last = top (WindowManagerGlobal.mViews; hidden API, the engine unseals it). */
    @Suppress("UNCHECKED_CAST")
    private fun roots(): List<View> = try {
        val c = Class.forName("android.view.WindowManagerGlobal")
        val g = c.getMethod("getInstance").invoke(null)
        val f = c.getDeclaredField("mViews").apply { isAccessible = true }
        (f.get(g) as List<View>).filter { it.isShown && it.width > 0 && it.height > 0 }
    } catch (_: Throwable) { emptyList() }

    private fun top(): View? = roots().lastOrNull()

    private fun tap(x: Float, y: Float): JSONObject {
        // The topmost window that contains the point (a dialog over the activity, else the activity).
        val target = roots().lastOrNull { v -> val l = IntArray(2); v.getLocationOnScreen(l); x >= l[0] && y >= l[1] && x < l[0] + v.width && y < l[1] + v.height }
            ?: return err("nothing on screen at ($x, $y)")
        val l = IntArray(2); target.getLocationOnScreen(l)
        val lx = x - l[0]; val ly = y - l[1]
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, lx, ly, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, lx, ly, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        val a = target.dispatchTouchEvent(down); val b = target.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
        return JSONObject().put("ok", true).put("handled", a || b).put("window", target.javaClass.simpleName)
    }

    private fun type(text: String): JSONObject {
        val root = top() ?: return err("no window")
        val focus = root.findFocus()
        val edit = focus as? android.widget.EditText ?: return err("no text field has focus (tap one first)")
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        if (events != null) { for (e in events) root.dispatchKeyEvent(e) }
        else edit.text?.insert(edit.selectionStart.coerceAtLeast(0), text)
        return JSONObject().put("ok", true).put("field", edit.javaClass.simpleName)
    }

    private fun back(): JSONObject {
        val root = top() ?: return err("no window")
        val t = SystemClock.uptimeMillis()
        val a = root.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        val b = root.dispatchKeyEvent(KeyEvent(t, t + 30, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        return JSONObject().put("ok", true).put("handled", a || b)
    }

    private fun tree(): JSONObject {
        val root = top() ?: return err("no window")
        val window = (root.context as? android.app.Activity)?.javaClass?.simpleName
            ?: generateSequence(root.context) { (it as? android.content.ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? android.app.Activity }?.javaClass?.simpleName
            ?: root.javaClass.simpleName
        val json = GuestViewTree.serialize(GuestViewTree.capture(root), guestPkg.orEmpty(), window, root.width, root.height)
        return JSONObject().put("ok", true).put("tree", JSONObject(json))
    }

    /** Main-process side: one request to the agent in [slotProcess] ("…:p0"), or null when it isn't there. */
    fun call(hostPkg: String, slotProcess: String, req: JSONObject, timeoutMs: Int = 6000): JSONObject? = try {
        LocalSocket().use { s ->
            s.connect(LocalSocketAddress(socketName(hostPkg, slotProcess)))
            s.soTimeout = timeoutMs
            s.outputStream.write((req.toString() + "\n").toByteArray()); s.outputStream.flush()
            s.inputStream.bufferedReader().readLine()?.let { JSONObject(it) }
        }
    } catch (_: Exception) { null }
}
