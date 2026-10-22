package com.blaze.assistant.device

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.34.0 agent tool `camera_math`: the pro-control maths behind Blaze Camera (exposure equivalents, EV, hand-held
 * shutter, depth of field / hyperfocal, ND filters) and reading prices and sums out of text the camera read.
 */
object CameraMathTool {
    const val TOOL = "camera_math"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Photography maths and camera reading. action: exposure (aperture, shutter_s, iso; optional new_aperture / new_iso → the " +
            "equivalent shutter and EV100), handheld (focal_mm, crop, stab_stops → slowest safe shutter), dof (focal_mm, aperture, distance_m, coc_mm " +
            "default 0.005 for phones → near / far / hyperfocal), nd (shutter_s, stops), read (text: prices with totals and sums worked out, e.g. OCR of a receipt).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("exposure", "handheld", "dof", "nd", "read"))))
            .put("aperture", JSONObject().put("type", "number")).put("shutter_s", JSONObject().put("type", "number")).put("iso", JSONObject().put("type", "number"))
            .put("new_aperture", JSONObject().put("type", "number")).put("new_iso", JSONObject().put("type", "number"))
            .put("focal_mm", JSONObject().put("type", "number")).put("crop", JSONObject().put("type", "number")).put("stab_stops", JSONObject().put("type", "integer"))
            .put("distance_m", JSONObject().put("type", "number")).put("coc_mm", JSONObject().put("type", "number")).put("stops", JSONObject().put("type", "integer"))
            .put("text", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action")))))

    fun run(args: JSONObject): String {
        fun d(k: String, def: Double) = if (args.has(k)) args.optDouble(k, def) else def
        return when (args.optString("action")) {
            "exposure" -> {
                val n = d("aperture", 1.8); val t = d("shutter_s", 1.0 / 100); val iso = d("iso", 100.0)
                val ev = CameraSmart.ev100(n, t, iso)
                val nn = d("new_aperture", n); val ni = d("new_iso", iso)
                "EV100 %.1f at f/%.1f, %s, ISO %.0f. At f/%.1f ISO %.0f: %s.".format(ev, n, CameraSmart.shutterText(t), iso, nn, ni,
                    CameraSmart.shutterText(CameraSmart.equivalentShutter(t, n, iso, nn, ni)))
            }
            "handheld" -> "Slowest safe hand-held shutter: " + CameraSmart.shutterText(CameraSmart.handheld(d("focal_mm", 26.0), d("crop", 1.0), args.optInt("stab_stops", 0)))
            "dof" -> {
                val f = d("focal_mm", 4.3); val n = d("aperture", 1.8); val c = d("coc_mm", 0.005); val s = d("distance_m", 1.0)
                val (near, far) = CameraSmart.depthOfField(f, n, c, s)
                "Sharp from %.2f m to %s (hyperfocal %.2f m).".format(near, if (far.isInfinite()) "infinity" else "%.2f m".format(far), CameraSmart.hyperfocal(f, n, c))
            }
            "nd" -> "With a ${args.optInt("stops", 6)}-stop ND: " + CameraSmart.shutterText(CameraSmart.ndShutter(d("shutter_s", 1.0 / 125), args.optInt("stops", 6)))
            "read" -> {
                val text = args.optString("text")
                val p = CameraSmart.prices(text)
                val sums = CameraSmart.sums(text).mapNotNull { e -> com.blaze.assistant.ui.Expr.eval(e)?.let { "$e = $it" } }
                buildString {
                    if (p.isNotEmpty()) append("Prices: " + p.joinToString(", ") { "${it.currency} ${"%,.2f".format(it.amount)}" } + ". Total: " +
                        CameraSmart.totals(p).entries.joinToString(" + ") { (k, v) -> "$k ${"%,.2f".format(v)}" } + ".")
                    if (sums.isNotEmpty()) { if (isNotEmpty()) append('\n'); append(sums.joinToString("\n")) }
                    if (isEmpty()) append("No prices or sums in that text.")
                }
            }
            else -> "Unknown action."
        }
    }
}
