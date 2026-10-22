package com.blaze.assistant.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Every planned step Blaze runs (app, step, success, time) goes to filesDir/traces.jsonl, kept
 * under ~2 MB. It never leaves the phone; it's the training data for a future on-device policy.
 */
object Traces {
    private var file: File? = null
    fun init(ctx: Context) { file = File(ctx.filesDir, "traces.jsonl") }

    @Synchronized fun log(pkg: String, step: JSONObject, ok: Boolean, ms: Long, note: String = "") {
        val f = file ?: return
        try {
            if (f.length() > 2_000_000) { val keep = f.readLines().takeLast(3000); f.writeText(keep.joinToString("\n") + "\n") }
            f.appendText(JSONObject().put("t", System.currentTimeMillis()).put("app", pkg).put("step", step)
                .put("ok", ok).put("ms", ms).apply { if (note.isNotBlank()) put("note", note.take(200)) }.toString() + "\n")
        } catch (_: Exception) { }
    }

    fun count(): Int = try { file?.takeIf { it.exists() }?.useLines { it.count() } ?: 0 } catch (_: Exception) { 0 }
    fun clear() { try { file?.delete() } catch (_: Exception) { } }
}
