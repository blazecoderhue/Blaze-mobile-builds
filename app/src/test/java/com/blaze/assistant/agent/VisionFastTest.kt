package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.3: the screen look is fast (small pictures, text first, a picture seen once, a 12 s cap with a text fallback). */
class VisionFastTest {
    private val note = "[Screenshot of the phone screen]"
    private fun shot() = JSONObject().put("role", "user").put("content", JSONArray()
        .put(JSONObject().put("type", "text").put("text", note))
        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,AAA"))))
    private fun msg(r: String, c: String) = JSONObject().put("role", r).put("content", c)

    @Test fun scale() {
        assertEquals(0.5f, VisionFast.scaleFor(720, 1440), 0.001f)
        assertEquals(1f, VisionFast.scaleFor(400, 600), 0.001f)
        assertTrue(VisionFast.MAX_SIDE <= 720 && VisionFast.JPEG_QUALITY <= 60 && VisionFast.TIMEOUT_MS <= 12_000)
    }

    @Test fun textEnough() {
        val rich = "Settings (com.android.settings). Elements, use the [number]:\n" +
            (0 until 10).joinToString("\n") { "[$it] item \"Row $it\"" } + "\n[10] img\n[11] list scrolls"
        assertTrue(VisionFast.textEnough(rich))
        val game = "Rally Horizon (com.x.rally). Elements, use the [number]:\n[0] web\n[1] img\n[2] btn (Pause)"
        assertFalse(VisionFast.textEnough(game))
        assertFalse(VisionFast.textEnough("I can't see the screen right now (no active window)."))
        assertFalse(VisionFast.textEnough("X (y). Elements, use the [number]:\n(nothing readable on screen)\n"))
    }

    @Test fun aPictureIsSeenOnce() {
        val h = JSONArray().put(msg("system", "s")).put(msg("user", "what's on screen")).put(shot()).put(msg("assistant", "ok")).put(msg("tool", "x"))
        assertEquals(1, VisionFast.pruneShots(h, note))
        assertEquals(VisionFast.PRUNED, h.getJSONObject(2).getString("content"))
        // a user's own photo (other text) is never touched
        val photo = JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "look at my cat"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:x"))))
        val h2 = JSONArray().put(photo).put(msg("assistant", "cute"))
        assertEquals(0, VisionFast.pruneShots(h2, note))
    }

    @Test fun freshShotAndTextFallback() {
        val r = JSONArray().put(msg("system", "s")).put(shot())
        assertTrue(VisionFast.hasFreshShot(r, note))
        assertFalse(VisionFast.hasFreshShot(JSONArray().put(shot()).put(msg("assistant", "a")), note))
        val t = VisionFast.withoutShots(r, note, "[0] btn \"Play\"")
        assertEquals(2, t.length())
        assertTrue(t.getJSONObject(1).getString("content").contains("[0] btn \"Play\""))
        assertFalse(t.toString().contains("image_url"))
    }
}
