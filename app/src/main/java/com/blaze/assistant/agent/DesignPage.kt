package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * v0.35.0 Design studio, Page mode: a page (post, story, slide, icon, app screen, poster) as a list of
 * positioned elements the user can select, move, resize and recolour by hand, and Blaze can edit by id.
 *
 * A page is saved like every other design (~/designs/<slug>/index.html, versions, undo): a plain HTML
 * document with absolutely placed elements (so the browser, Desktop and export read it as is) that
 * carries its spec in a `<script type="application/json" id="blaze-page">` block. The studio draws the
 * spec natively (Compose); the HTML is regenerated from the spec on every save.
 *
 * Everything here is pure (no Android), so it is unit-tested in DesignPageTest.
 */
object DesignPage {

    // ---------------- formats ----------------

    data class Format(val id: String, val label: String, val ratio: String, val w: Int, val h: Int)

    val FORMATS: List<Format> = listOf(
        Format("post", "Post", "4:5", 1080, 1350),
        Format("story", "Story", "9:16", 1080, 1920),
        Format("slide", "Slide", "16:9", 1920, 1080),
        Format("icon", "Icon", "1:1", 1024, 1024),
        Format("screen", "App screen", "9:19.5", 390, 844),
        Format("poster", "Poster A4", "1:1.41", 794, 1123),
    )

    fun format(id: String?): Format = FORMATS.firstOrNull { it.id == id?.trim()?.lowercase() } ?: FORMATS[0]

    // ---------------- model ----------------

    /** A placed element. Coordinates are page pixels (top-left origin); colours are #RRGGBB or #RRGGBBAA. */
    data class El(
        val id: String,
        val label: String = id,
        val type: String = "text",          // text | rect | ellipse | button
        val x: Double = 0.0, val y: Double = 0.0, val w: Double = 100.0, val h: Double = 40.0,
        val text: String = "",
        val size: Double = 32.0,            // font size, page px
        val weight: Int = 500,
        val color: String = "#F2F2F3",      // text colour
        val fill: String = "",              // background; blank = none
        val radius: Double = 0.0,
        val align: String = "left",         // left | center | right
        val z: Int = 0,
        val hidden: Boolean = false,
    ) {
        val isText get() = type == "text" || type == "button"
        fun frame() = Frame(x, y, w, h)
        fun at(f: Frame) = copy(x = f.x, y = f.y, w = f.w, h = f.h)
    }

    data class Doc(val name: String, val format: String, val w: Int, val h: Int, val background: String, val els: List<El>) {
        fun el(id: String?) = els.firstOrNull { it.id == id }
        /** Elements in paint order (z, then list order). */
        fun painted(): List<El> = els.withIndex().sortedWith(compareBy({ it.value.z }, { it.index })).map { it.value }.filter { !it.hidden }
        fun replace(e: El) = copy(els = els.map { if (it.id == e.id) e else it })
    }

    data class Frame(val x: Double, val y: Double, val w: Double, val h: Double)

    // ---------------- move / resize ----------------

    enum class Handle { MOVE, TL, TR, BL, BR, L, R, T, B }

    const val MIN_SIZE = 12.0

    /**
     * The frame after dragging [handle] by (dx, dy) page px. Corners resize from the opposite corner, edges from the
     * opposite edge; [keepAspect] keeps the ratio on corner drags. Size never goes under [MIN_SIZE]; with [bounds]
     * the frame is kept at least partly on the page (a quarter of it, so nothing can be lost off the edge).
     */
    fun drag(f: Frame, handle: Handle, dx: Double, dy: Double, keepAspect: Boolean = false, bounds: Pair<Double, Double>? = null): Frame {
        var x = f.x; var y = f.y; var w = f.w; var h = f.h
        when (handle) {
            Handle.MOVE -> { x += dx; y += dy }
            else -> {
                val left = handle == Handle.TL || handle == Handle.BL || handle == Handle.L
                val right = handle == Handle.TR || handle == Handle.BR || handle == Handle.R
                val top = handle == Handle.TL || handle == Handle.TR || handle == Handle.T
                val bottom = handle == Handle.BL || handle == Handle.BR || handle == Handle.B
                var nw = w + (if (right) dx else if (left) -dx else 0.0)
                var nh = h + (if (bottom) dy else if (top) -dy else 0.0)
                if (keepAspect && (left || right) && (top || bottom) && w > 0 && h > 0) {
                    val s = max(nw / w, nh / h)
                    nw = w * s; nh = h * s
                }
                nw = max(MIN_SIZE, nw); nh = max(MIN_SIZE, nh)
                if (left) x = f.x + f.w - nw
                if (top) y = f.y + f.h - nh
                w = nw; h = nh
            }
        }
        if (bounds != null) {
            val (bw, bh) = bounds
            x = x.coerceIn(-w * 0.75, bw - w * 0.25)
            y = y.coerceIn(-h * 0.75, bh - h * 0.25)
        }
        return Frame(x, y, w, h)
    }

    /** The doc in another format: positions and sizes follow the page, type follows the smaller ratio. */
    fun reformat(doc: Doc, to: Format): Doc {
        if (doc.w == to.w && doc.h == to.h) return doc.copy(format = to.id)
        val sx = to.w.toDouble() / doc.w; val sy = to.h.toDouble() / doc.h; val s = min(sx, sy)
        return doc.copy(format = to.id, w = to.w, h = to.h, els = doc.els.map { e ->
            if (e.isText) {
                // Text keeps its proportions: scale by the smaller ratio, keep the centre where the page put it.
                val nw = min(e.w * s, to.w.toDouble()); val nh = e.h * s
                val cx = (e.x + e.w / 2) * sx; val cy = (e.y + e.h / 2) * sy
                e.copy(x = r1(cx - nw / 2), y = r1(cy - nh / 2), w = r1(nw), h = r1(nh), size = r1(e.size * s))
            } else e.copy(x = r1(e.x * sx), y = r1(e.y * sy), w = r1(e.w * s), h = r1(e.h * s), radius = r1(e.radius * s))
        })
    }

    fun bringToFront(doc: Doc, id: String): Doc {
        val top = (doc.els.maxOfOrNull { it.z } ?: 0) + 1
        return doc.el(id)?.let { doc.replace(it.copy(z = top)) } ?: doc
    }

    private fun r1(v: Double) = (v * 10).roundToInt() / 10.0

    // ---------------- colour ----------------

    /** #RGB, #RGBA, #RRGGBB or #RRGGBBAA (with or without #) → ARGB, or null. */
    fun parseHex(s: String?): Long? {
        var t = s?.trim()?.removePrefix("#") ?: return null
        if (!Regex("^[0-9a-fA-F]+$").matches(t)) return null
        if (t.length == 3 || t.length == 4) t = t.map { "$it$it" }.joinToString("")
        return when (t.length) {
            6 -> 0xFF000000L or t.toLong(16)
            8 -> { val rgb = t.substring(0, 6).toLong(16); val a = t.substring(6).toLong(16); (a shl 24) or rgb }
            else -> null
        }
    }

    /** ARGB → "#RRGGBB" (opaque) or "#RRGGBBAA". */
    fun toHex(argb: Long, alpha: Boolean = true): String {
        val a = (argb shr 24) and 0xFF
        val rgb = String.format("#%06X", argb and 0xFFFFFF)
        return if (alpha && a != 0xFFL) rgb + String.format("%02X", a) else rgb
    }

    /** Hue (0-360), saturation and value (0-1), alpha (0-1) → ARGB. */
    fun hsvToArgb(h: Double, s: Double, v: Double, a: Double = 1.0): Long {
        val hh = ((h % 360) + 360) % 360 / 60.0
        val c = v * s; val x = c * (1 - abs(hh % 2 - 1)); val m = v - c
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        fun ch(d: Double) = ((d + m) * 255).roundToInt().coerceIn(0, 255).toLong()
        return ((a * 255).roundToInt().coerceIn(0, 255).toLong() shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** ARGB → [hue 0-360, saturation, value, alpha]. */
    fun argbToHsv(argb: Long): DoubleArray {
        val r = ((argb shr 16) and 0xFF) / 255.0; val g = ((argb shr 8) and 0xFF) / 255.0; val b = (argb and 0xFF) / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val h = when {
            d == 0.0 -> 0.0
            mx == r -> 60 * (((g - b) / d) % 6)
            mx == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }.let { if (it < 0) it + 360 else it }
        return doubleArrayOf(h, if (mx == 0.0) 0.0 else d / mx, mx, ((argb shr 24) and 0xFF) / 255.0)
    }

    /** WCAG relative luminance of an ARGB colour (alpha ignored). */
    fun luminance(argb: Long): Double {
        fun lin(c: Long): Double { val s = c / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)
    }

    /** [top] (may be translucent) composited over opaque [under]. */
    fun over(top: Long, under: Long): Long {
        val a = ((top shr 24) and 0xFF) / 255.0
        fun mix(sh: Int) = (((top shr sh) and 0xFF) * a + ((under shr sh) and 0xFF) * (1 - a)).roundToInt().toLong()
        return 0xFF000000L or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    // ---------------- critic ----------------

    data class Critique(val score: Int, val note: String, val weakest: String? = null)

    /**
     * A quick, honest check the studio shows on the page: text contrast against what is under it (WCAG: 4.5 for
     * body text, 3 for large text), and elements pushed off the page. Score 0-100.
     */
    fun critique(doc: Doc): Critique {
        val bg = parseHex(doc.background) ?: 0xFF0B0B0EL
        var score = 100.0
        var worst: Pair<El, Double>? = null
        val painted = doc.painted()
        for (e in painted.filter { it.isText && it.text.isNotBlank() }) {
            // What's under the text: its own fill, else the topmost filled element below it that covers its centre, else the page.
            val cx = e.x + e.w / 2; val cy = e.y + e.h / 2
            val under = parseHex(e.fill)?.let { over(it, bg) }
                ?: painted.takeWhile { it !== e }.lastOrNull { it.fill.isNotBlank() && cx in it.x..(it.x + it.w) && cy in it.y..(it.y + it.h) }
                    ?.let { parseHex(it.fill) }?.let { over(it, bg) } ?: bg
            val fg = over(parseHex(e.color) ?: 0xFFFFFFFFL, under)
            val c = contrast(fg, under)
            val need = if (e.size >= 24 || (e.size >= 18.5 && e.weight >= 700)) 3.0 else 4.5
            if (c < need) score -= ((need - c) / need) * 40
            val ratio = c / need
            if (worst == null || ratio < worst.second) worst = e to ratio
        }
        val off = painted.filter { it.x + it.w < 0 || it.y + it.h < 0 || it.x > doc.w || it.y > doc.h }
        score -= off.size * 10
        val s = score.roundToInt().coerceIn(0, 100)
        val w = worst
        val note = when {
            off.isNotEmpty() -> "${off.first().label} is off the page"
            w != null && w.second < 1.0 -> "Low contrast: ${w.first.label.lowercase()}"
            else -> "Contrast ok"
        }
        return Critique(s, note, if (w != null && w.second < 1.0) w.first.id else off.firstOrNull()?.id)
    }

    // ---------------- JSON ----------------

    fun elFrom(o: JSONObject, i: Int = 0): El {
        val type = o.optString("type", "text").lowercase().let { if (it in setOf("text", "rect", "ellipse", "button")) it else "text" }
        val id = o.optString("id").ifBlank { "$type-${i + 1}" }.replace(Regex("[^A-Za-z0-9_-]"), "-")
        fun col(k: String, d: String) = o.optString(k).trim().let { if (parseHex(it) != null) toHex(parseHex(it)!!) else d }
        return El(
            id = id, label = o.optString("label").ifBlank { id.replaceFirstChar { it.uppercase() } }, type = type,
            x = o.optDouble("x", 0.0).finite(0.0), y = o.optDouble("y", 0.0).finite(0.0),
            w = o.optDouble("w", if (type == "text") 600.0 else 200.0).finite(200.0).coerceAtLeast(MIN_SIZE),
            h = o.optDouble("h", if (type == "text") 80.0 else 200.0).finite(80.0).coerceAtLeast(MIN_SIZE),
            text = o.optString("text"), size = o.optDouble("size", 32.0).finite(32.0).coerceIn(4.0, 600.0),
            weight = o.optInt("weight", 500).coerceIn(100, 900),
            color = col("color", "#F2F2F3"), fill = col("fill", ""), radius = o.optDouble("radius", 0.0).finite(0.0).coerceAtLeast(0.0),
            align = o.optString("align", "left").lowercase().let { if (it in setOf("left", "center", "right")) it else "left" },
            z = o.optInt("z", 0), hidden = o.optBoolean("hidden", false),
        )
    }

    private fun Double.finite(d: Double) = if (isNaN() || isInfinite()) d else this

    fun elJson(e: El): JSONObject = JSONObject().put("id", e.id).put("label", e.label).put("type", e.type)
        .put("x", e.x).put("y", e.y).put("w", e.w).put("h", e.h).apply {
            if (e.text.isNotEmpty()) put("text", e.text)
            if (e.isText) { put("size", e.size); put("weight", e.weight); put("align", e.align) }
            put("color", e.color)
            if (e.fill.isNotBlank()) put("fill", e.fill)
            if (e.radius > 0) put("radius", e.radius)
            if (e.z != 0) put("z", e.z)
            if (e.hidden) put("hidden", true)
        }

    fun docFrom(o: JSONObject): Doc {
        val f = format(o.optString("format"))
        val w = o.optInt("w", f.w).takeIf { it in 16..8192 } ?: f.w
        val h = o.optInt("h", f.h).takeIf { it in 16..8192 } ?: f.h
        val arr = o.optJSONArray("elements") ?: JSONArray()
        val seen = HashSet<String>()
        val els = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { elFrom(it, i) } }.map { e ->
            var id = e.id; var n = 2
            while (!seen.add(id)) id = e.id + "-" + n++
            e.copy(id = id)
        }
        return Doc(o.optString("name").ifBlank { "Page" }, f.id, w, h,
            parseHex(o.optString("background"))?.let { toHex(it) } ?: "#0B0B0E", els)
    }

    fun docJson(d: Doc): JSONObject = JSONObject().put("name", d.name).put("format", d.format).put("w", d.w).put("h", d.h)
        .put("background", d.background).put("elements", JSONArray(d.els.map { elJson(it) }))

    // ---------------- HTML ----------------

    private val block = Regex("""<script type="application/json" id="blaze-page">([\s\S]*?)</script>""")

    /** The page spec embedded in a design file, or null (not a studio page). */
    fun fromHtml(html: String): Doc? = block.find(html)?.let { m ->
        try { docFrom(JSONObject(m.groupValues[1].replace("<\\/", "</"))) } catch (_: Exception) { null }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** CSS colour for #RRGGBB / #RRGGBBAA (8-digit hex is fine in every current browser). */
    private fun css(c: String) = parseHex(c)?.let { toHex(it) } ?: "transparent"

    private fun pct(v: Double, of: Int) = String.format(java.util.Locale.US, "%.4f%%", v * 100 / of)

    /**
     * The page as a self-contained HTML document: the page keeps its aspect ratio and fits the window, elements are
     * placed in % of the page and type is sized in container units, so it looks the same at any size.
     */
    fun render(d: Doc): String {
        val sb = StringBuilder()
        sb.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(esc(d.name)).append("</title>\n<style>\n")
        sb.append("html,body{margin:0;height:100%;background:#111;display:grid;place-items:center;font-family:Inter,system-ui,-apple-system,sans-serif}\n")
        sb.append(".page{position:relative;container-type:size;overflow:hidden;width:min(100vw,calc(100vh*${d.w}/${d.h}));aspect-ratio:${d.w}/${d.h};background:${css(d.background)}}\n")
        sb.append(".el{position:absolute;box-sizing:border-box;display:flex;align-items:center;white-space:pre-wrap;line-height:1.1;overflow:hidden}\n")
        sb.append("</style></head><body>\n<div class=\"page\">\n")
        for (e in d.painted()) {
            val cqw = String.format(java.util.Locale.US, "%.4fcqw", e.size * 100 / d.w)
            val style = StringBuilder("left:${pct(e.x, d.w)};top:${pct(e.y, d.h)};width:${pct(e.w, d.w)};height:${pct(e.h, d.h)};")
            if (e.fill.isNotBlank()) style.append("background:${css(e.fill)};")
            if (e.type == "ellipse") style.append("border-radius:50%;")
            else if (e.radius > 0) style.append("border-radius:" + String.format(java.util.Locale.US, "%.4fcqw", e.radius * 100 / d.w) + ";")
            if (e.isText) style.append("color:${css(e.color)};font-size:$cqw;font-weight:${e.weight};justify-content:" +
                (when (e.align) { "center" -> "center"; "right" -> "flex-end"; else -> "flex-start" }) + ";text-align:${e.align};")
            if (e.z != 0) style.append("z-index:${e.z};")
            sb.append("<div class=\"el\" data-id=\"").append(esc(e.id)).append("\" style=\"").append(style).append("\">")
            if (e.isText) sb.append(esc(e.text))
            sb.append("</div>\n")
        }
        sb.append("</div>\n<script type=\"application/json\" id=\"blaze-page\">")
        sb.append(docJson(d).toString().replace("</", "<\\/"))
        sb.append("</script>\n</body></html>\n")
        return sb.toString()
    }

    // ---------------- edits by id (Blaze) ----------------

    /** Adds or changes elements by id (only the keys given change) and removes ids; returns the doc and a summary. */
    fun merge(d: Doc, set: JSONArray?, remove: Collection<String>): Pair<Doc, String> {
        val added = ArrayList<String>(); val changed = ArrayList<String>()
        var els = d.els.filter { it.id !in remove }
        val removed = d.els.filter { it.id in remove }.map { it.id }
        if (set != null) for (i in 0 until set.length()) {
            val o = set.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val old = els.firstOrNull { it.id == id }
            if (old == null) { els = els + elFrom(o, els.size); added += els.last().id; continue }
            val merged = elJson(old)
            o.keys().forEach { k -> merged.put(k, o.get(k)) }
            els = els.map { if (it.id == id) elFrom(merged) else it }
            changed += id
        }
        val summary = listOf("added" to added, "changed" to changed, "removed" to removed).filter { it.second.isNotEmpty() }
            .joinToString("; ") { (k, v) -> "$k " + v.joinToString(", ") }.ifBlank { "no changes" }
        return d.copy(els = els) to summary
    }

    /** A clean starter page (used by "New page" in the studio): headline, body, button. */
    fun starter(title: String, fmt: Format = FORMATS[0]): Doc {
        val base = Doc(title, "post", 1080, 1350, "#0B0B0E", listOf(
            El("eyebrow", "Eyebrow", "text", 86.0, 110.0, 600.0, 40.0, "NEW", 26.0, 600, "#9A9AA2"),
            El("headline", "Headline", "text", 86.0, 190.0, 900.0, 300.0, title, 104.0, 600, "#F2F2F3"),
            El("body", "Body", "text", 86.0, 520.0, 640.0, 160.0, "Say what it is and why it matters, in one or two lines.", 34.0, 400, "#B8B8BF"),
            El("shape", "Shape", "ellipse", 560.0, 760.0, 440.0, 440.0, fill = "#4DA8FF59"),
            El("button", "Button", "button", 86.0, 1170.0, 300.0, 84.0, "Get started", 30.0, 600, "#0B0B0E", "#F2F2F3", 42.0, "center"),
        ))
        return if (fmt.id == "post") base else reformat(base, fmt)
    }
}
