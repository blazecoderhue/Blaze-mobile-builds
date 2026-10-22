package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.33.3: "Looking at the screen" took 44 s on a low-end phone. Three causes, three rules (pure, tested in VisionFastTest):
 *  1. The screenshot was 1280 px / JPEG 70 and stayed in the history, so EVERY later step of the turn re-sent it and was
 *     routed to the slow vision lineup. Now a picture is seen by exactly one model call ([pruneShots]) and is 720 px / JPEG 60.
 *  2. look_at_screen took a screenshot even when the accessibility tree already said everything. Now a screen with
 *     enough readable text answers as text ([textEnough]); pixels=true forces the picture (images, games, video, maps).
 *  3. A slow vision call had no cap. Now it gets [TIMEOUT_MS]; past that the picture is swapped for the screen as text
 *     and the same request goes on the gateway's (faster) text route ([withoutShots]).
 */
object VisionFast {
    const val TIMEOUT_MS = 12_000L
    const val MAX_SIDE = 720
    const val JPEG_QUALITY = 60
    const val PRUNED = "[An earlier screenshot was here. Call look_at_screen again if you need to see the screen now.]"

    /** Scale factor so the long side is at most [maxSide] (never upscales). */
    fun scaleFor(w: Int, h: Int, maxSide: Int = MAX_SIDE): Float {
        val m = maxOf(w, h); if (m <= 0) return 1f
        return minOf(1f, maxSide.toFloat() / m)
    }

    private val line = Regex("""^\[\d+]\s+(\w+)(.*)$""")

    /**
     * True when a read_screen dump carries enough text to answer without a picture: at least [minText] elements with a
     * label, and pictures / web views / unlabelled items are under 40 % of the elements.
     */
    fun textEnough(readout: String, minText: Int = 8): Boolean {
        if (readout.isBlank() || readout.startsWith("I can't see") || readout.contains("(nothing readable on screen)")) return false
        var labelled = 0; var visual = 0; var total = 0
        readout.lineSequence().forEach { l ->
            val m = line.find(l.trim()) ?: return@forEach
            total++
            val role = m.groupValues[1]; val rest = m.groupValues[2]
            val hasLabel = rest.contains('"') || rest.contains('(')
            if (role == "img" || role == "web" || (!hasLabel && role != "list")) visual++
            if (hasLabel && role != "img") labelled++
        }
        if (total == 0) return false
        return labelled >= minText && visual * 10 < total * 4
    }

    private fun isShot(m: JSONObject?): Boolean {
        if (m == null || m.optString("role") != "user") return false
        val c = m.opt("content") as? JSONArray ?: return false
        for (i in 0 until c.length()) if (c.optJSONObject(i)?.optString("type") == "image_url") return true
        return false
    }

    /**
     * Replaces every screenshot message except one that is the very last message with a short text note, in place.
     * Returns how many were pruned. Only screenshot notes the agent added are touched (user photos keep their image).
     */
    fun pruneShots(history: JSONArray, note: String, keepLast: Boolean = false): Int {
        var n = 0
        val last = history.length() - 1
        for (i in 0..last) {
            val m = history.optJSONObject(i)
            if (!isShot(m)) continue
            val c = m!!.opt("content") as JSONArray
            val text = (0 until c.length()).mapNotNull { c.optJSONObject(it) }.firstOrNull { it.optString("type") == "text" }?.optString("text").orEmpty()
            if (text != note) continue
            if (keepLast && i == last) continue
            m.put("content", PRUNED); n++
        }
        return n
    }

    /** True when the request ends with a fresh screenshot (the call that must be fast). */
    fun hasFreshShot(req: JSONArray, note: String): Boolean {
        val m = req.optJSONObject(req.length() - 1) ?: return false
        if (!isShot(m)) return false
        val c = m.opt("content") as JSONArray
        return (0 until c.length()).any { c.optJSONObject(it)?.optString("text") == note }
    }

    /** A copy of [req] whose screenshot messages say [screenText] instead (the text route fallback). */
    fun withoutShots(req: JSONArray, note: String, screenText: String): JSONArray {
        val out = JSONArray()
        for (i in 0 until req.length()) {
            val m = req.optJSONObject(i)
            if (m != null && isShot(m) && (m.opt("content") as JSONArray).let { c -> (0 until c.length()).any { c.optJSONObject(it)?.optString("text") == note } }) {
                out.put(JSONObject().put("role", "user").put("content",
                    "[The screenshot took too long to read, so here is the screen as text instead]\n" + screenText.take(6000)))
            } else out.put(req.opt(i))
        }
        return out
    }
}
