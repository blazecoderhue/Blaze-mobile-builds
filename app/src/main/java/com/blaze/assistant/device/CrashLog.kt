package com.blaze.assistant.device

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local crash + slowness log the user can share in one tap. Nothing leaves the phone by itself. */
object CrashLog {
    private lateinit var file: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "blaze_log.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { writeNow("CRASH on ${t.name}: ${android.util.Log.getStackTraceString(e).take(6000)}") } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    /**
     * Added to every background scope: a failure inside Blaze's own work is logged and shown as
     * an error in the chat instead of closing the app.
     */
    val guard = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        write("CAUGHT: ${android.util.Log.getStackTraceString(e).take(4000)}")
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { com.blaze.assistant.agent.Agent.reportError("Something went wrong (${e.javaClass.simpleName}: ${e.message?.take(120)}). It's in the crash report; try again.") } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    private val recent = ArrayDeque<Long>()

    /**
     * Keep the app alive through a stray error on the main thread (a tap handler, a UI update):
     * log it, show it in chat, and carry on. If errors pile up (5 in 10 s), let Android restart us.
     */
    fun shieldMainThread() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            while (true) {
                try { android.os.Looper.loop() } catch (e: Throwable) {
                    val now = System.currentTimeMillis()
                    recent.addLast(now); while (recent.isNotEmpty() && now - recent.first() > 10_000) recent.removeFirst()
                    if (recent.size >= 5 || e is OutOfMemoryError) { writeNow("CAUGHT on main (giving up): ${android.util.Log.getStackTraceString(e).take(4000)}"); throw e }
                    write("CAUGHT on main: ${android.util.Log.getStackTraceString(e).take(4000)}")
                    try { com.blaze.assistant.agent.Agent.reportError("Something went wrong (${e.javaClass.simpleName}: ${e.message?.take(120)}). Blaze kept running; it's in the crash report.") } catch (_: Throwable) { }
                }
            }
        }
    }

    /**
     * 0.45.16 smoothness: log lines are written on one background thread, never on the caller's (the main thread wrote
     * and sometimes rewrote a 400 KB file on every caught error). A crash still writes at once ([writeNow]).
     */
    private val writer: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "blaze-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    fun write(line: String) {
        if (!::file.isInitialized) return
        val stamped = "${synchronized(fmt) { fmt.format(Date()) }}  $line\n"
        try { writer.execute { append(stamped) } } catch (_: Throwable) { append(stamped) }
    }

    /** Synchronous write, for the crash handler (the process is about to die). */
    fun writeNow(line: String) { if (::file.isInitialized) append("${synchronized(fmt) { fmt.format(Date()) }}  $line\n") }

    @Synchronized private fun append(stamped: String) {
        try {
            if (file.length() > 400_000) file.writeText(file.readText().takeLast(200_000))
            file.appendText(stamped)
        } catch (_: Exception) { }
    }

    /** Log steps that take long so we can see where time goes. */
    fun slow(what: String, ms: Long) { if (ms >= 8000) write("SLOW ${ms} ms: $what") }
    fun error(what: String, e: Throwable?) = write("ERROR $what: ${e?.javaClass?.simpleName}: ${e?.message}")

    private val quietAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /**
     * v0.45.0 (#29): for a catch that used to be empty. The failure is still not shown to the user, but it is written
     * to the crash log (once a minute per place), so "it just didn't do anything" can be traced. Cancellation is
     * normal control flow and is never logged. Never throws.
     */
    fun quiet(where: String, e: Throwable?) {
        if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) return
        try {
            val now = System.currentTimeMillis()
            val last = quietAt[where] ?: 0L
            if (now - last < 60_000) return
            quietAt[where] = now
            write("QUIET $where: ${e?.javaClass?.simpleName}: ${e?.message?.take(300)}")
        } catch (_: Throwable) { }
    }
    /** Pure: whether [quiet] would log [e] at all (unit-tested). */
    fun quietLogs(e: Throwable?): Boolean = !(e is kotlinx.coroutines.CancellationException || e is InterruptedException)

    fun read(): String = try { if (::file.isInitialized && file.exists()) file.readText() else "" } catch (_: Exception) { "" }
    fun crashes(): Int = read().lines().count { it.contains("CRASH") }
    fun anrs(): Int = read().lines().count { it.contains("  ANR ") }
    fun clear() { try { file.delete() } catch (_: Exception) { } }

    fun shareIntent(ctx: Context): Intent? {
        if (!::file.isInitialized || !file.exists()) return null
        val out = File(ctx.cacheDir, "blaze-report.txt")
        val pi = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (_: Exception) { null }
        out.writeText("Blaze ${pi?.versionName} (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})\n\n" + read().takeLast(150_000))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Blaze report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send report")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
