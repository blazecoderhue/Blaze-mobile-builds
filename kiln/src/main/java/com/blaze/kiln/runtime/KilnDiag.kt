package com.blaze.kiln.runtime

import android.content.Context
import com.blaze.kiln.diag.DiagEntry
import com.blaze.kiln.diag.DiagKind
import com.blaze.kiln.diag.DiagLog
import com.blaze.kiln.diag.HarnessStep
import com.blaze.kiln.diag.RefusalClassifier
import java.io.File
import java.io.FileOutputStream

// Writes diagnostics lines (format: diag/DiagLog) to files/kiln/diag.log, shared by the main process and :gN (same
// app uid, same files dir). Appends are single small writes with O_APPEND, so two processes do not interleave lines.
// Never records guest content (notes, field values), only step names, class/method names and exception messages.

object KilnDiag {
    private const val MAX_BYTES = 256 * 1024L
    @Volatile private var file: File? = null
    @Volatile private var proc: String = "?"

    fun init(context: Context) {
        if (file != null) return
        val f = File(File(context.applicationContext.filesDir, "kiln"), "diag.log")
        f.parentFile?.mkdirs()
        file = f
        val pn = BlazeKiln.processName(context)
        proc = if (pn == null) "?" else if (pn.contains(':')) ":" + pn.substringAfter(':') else "main"
    }

    fun step(step: HarnessStep, ok: Boolean, detail: String = "") = append(DiagKind.STEP, step.name, ok, detail)

    fun info(key: String, detail: String) = append(DiagKind.INFO, key, null, detail)

    /** A system service threw at the guest through one of our shims. */
    fun refusal(service: String, method: String, t: Throwable) {
        val r = RefusalClassifier.classify(t)
        val hooked = service in RefusalClassifier.HOOKED_SERVICES || service.startsWith("android.app.Activity")
        append(DiagKind.REFUSAL, "$service.$method", false,
            "${t.javaClass.simpleName}: ${t.message.orEmpty().take(400)}" + (if (hooked) " (hooked by Kiln)" else "") +
                (r?.aidlInterface?.let { " [$it]" } ?: ""))
    }

    fun crash(t: Throwable) {
        val r = RefusalClassifier.classify(t)
        append(DiagKind.CRASH, t.javaClass.name, false, r?.describe() ?: "${t.javaClass.simpleName}: ${t.message}")
    }

    @Synchronized
    private fun append(kind: DiagKind, key: String, ok: Boolean?, detail: String) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(keep.substringAfter('\n'))
            }
            val line = DiagLog.format(DiagEntry(System.currentTimeMillis(), proc, kind, key, ok, detail)) + "\n"
            FileOutputStream(f, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        } catch (t: Throwable) {
            KilnLog.w("diag write failed: ${t.javaClass.simpleName}")
        }
    }

    fun read(context: Context): List<DiagEntry> {
        init(context)
        val f = file ?: return emptyList()
        return try { if (f.isFile) DiagLog.parseAll(f.readText()) else emptyList() } catch (t: Throwable) { emptyList() }
    }

    fun clear(context: Context) {
        init(context)
        file?.delete()
    }

    /** Records an uncaught exception in this process before the previous handler (usually the system's) runs. */
    fun watchCrashes() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashWatch) return
        Thread.setDefaultUncaughtExceptionHandler(CrashWatch(prev))
    }

    private class CrashWatch(private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try { crash(e) } catch (_: Throwable) {}
            next?.uncaughtException(t, e)
        }
    }
}
