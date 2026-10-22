package com.blaze.assistant.assist

import android.app.assist.AssistStructure
import android.graphics.Bitmap
import android.util.Base64
import com.blaze.assistant.agent.Attachment
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * v0.33.0 Screen Doctor: long-press home / power (Blaze as the phone's assistant) over any app and tap "What's this?".
 * Android hands the assistant the screen's text (AssistStructure) and a screenshot when "Use text / screenshot from
 * screen" is on; Blaze sends both with a prompt that explains an error, translates a menu, says what a button does, or
 * offers to fill the form. Nothing is captured unless you summon Blaze. Pure prompt / text tidy in [prompt] / [tidy]
 * (ScreenDoctorTest).
 */
object ScreenDoctor {
    @Volatile var text: String = ""; private set
    @Volatile var app: String = ""; private set
    @Volatile private var shot: Bitmap? = null
    @Volatile private var at: Long = 0

    fun fresh(now: Long = System.currentTimeMillis()) = now - at < 120_000 && (text.isNotBlank() || shot != null)

    fun clear() { text = ""; app = ""; shot = null; at = 0 }

    fun onStructure(s: AssistStructure?) {
        if (s == null) return
        val out = ArrayList<String>()
        fun walk(n: AssistStructure.ViewNode?, depth: Int) {
            if (n == null || depth > 40 || out.size > 400) return
            listOfNotNull(n.text?.toString(), n.contentDescription?.toString(), n.hint).forEach { if (it.isNotBlank()) out += it }
            for (i in 0 until n.childCount) walk(n.getChildAt(i), depth + 1)
        }
        for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode, 0)
        text = tidy(out); app = s.activityComponent?.packageName.orEmpty(); at = System.currentTimeMillis()
    }

    fun onScreenshot(b: Bitmap?) {
        if (b == null) return
        val s = minOf(1f, 1280f / maxOf(b.width, b.height))
        shot = try { if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null }
        at = System.currentTimeMillis()
    }

    /** De-duplicated screen strings, capped (the model gets the gist, not a dump). */
    fun tidy(parts: List<String>, cap: Int = 3500): String {
        val seen = LinkedHashSet<String>()
        parts.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length > 1 }.forEach { seen += it }
        val sb = StringBuilder()
        for (p in seen) { if (sb.length + p.length > cap) break; if (sb.isNotEmpty()) sb.append(" | "); sb.append(p) }
        return sb.toString()
    }

    fun prompt(screenText: String, pkg: String, ask: String = ""): String = buildString {
        append("[Screen Doctor] ").append(ask.ifBlank { "What's this on my screen?" })
        append("\nExplain what this screen is and what I can do here in plain words. If it's an error, say what it means and the fix step by step; ")
        append("if it's in another language, translate it; if it's a form, offer to fill it with phone control (ask before submitting anything); ")
        append("if it looks like a scam or phishing, warn me first.")
        if (pkg.isNotBlank()) append("\nApp: ").append(pkg)
        if (screenText.isNotBlank()) append("\nScreen text: ").append(screenText)
    }

    fun attachments(): List<Attachment> {
        val b = shot ?: return emptyList()
        return try {
            val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 80, out)
            listOf(Attachment("screen.jpg", listOf(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))))))
        } catch (_: Throwable) { emptyList() }
    }
}
