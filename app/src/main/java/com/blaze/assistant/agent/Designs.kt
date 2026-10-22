package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.23.2: Design is general-purpose, like Blaze Desktop's Design tab. Anything visual or interactive
 * is made as a real file and previewed live in the Design view: 3D scenes (three.js with orbit
 * controls), 2D art (SVG), sites and landing pages, UI mockups and app screens, diagrams, charts,
 * posters, slides, floor plans, product mockups, games…
 *
 * The `design` tool keeps a **current design** (~/designs/current.json), so follow-on edits
 * ("show the engine", "make it taller") update the same design instead of starting a new one.
 *  - 3D: Blaze sends a parts list (the three-d-design skill's `blaze-parts` JSON: box, cylinder,
 *    cone, sphere, torus, capsule, plane, group); the page is generated from the skill's starter, so
 *    the model never has to write three.js by hand. Edits merge parts by id (`set_parts`, `remove_parts`).
 *  - Everything else: a full HTML document (`html`) or SVG markup (`svg`); edits are exact find/replace
 *    (`edits`) or a new `html`.
 * Each update keeps the previous version (.v<N>.html, last 10) so `undo` works.
 *
 * Files live in Blaze's home (~/designs/<slug>/index.html), so the terminal, skills and Desktop sync can
 * read them; nothing in prefs. The pure helpers are unit-tested in DesignsTest.
 */
object Designs {
    const val TOOL = "design"
    private const val KEEP_VERSIONS = 10

    /** The design being worked on. [path] is absolute. */
    data class Current(val path: String, val kind: String, val title: String, val version: Int, val at: Long) {
        fun toJson(): JSONObject = JSONObject().put("path", path).put("kind", kind).put("title", title).put("version", version).put("at", at)
        companion object {
            fun from(o: JSONObject?): Current? = o?.takeIf { it.optString("path").isNotBlank() }?.let {
                Current(it.optString("path"), it.optString("kind", "page"), it.optString("title"), it.optInt("version", 1), it.optLong("at"))
            }
        }
    }

    /** What a call did: [text] goes back to the model; [open] = the file the Design view should show. */
    data class Outcome(val text: String, val open: String? = null, val ok: Boolean = open != null, val kind: String? = null)

    // ---------------- honesty (v0.27.x) ----------------

    /**
     * The kinds of design shown this turn (create / update / open / undo). The agent clears it at the start of a turn
     * and grounds the final reply in it ([groundReply]): a flat diagram is never described as a 3D model.
     */
    val touched = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun is3d(kind: String?): Boolean = kind.orEmpty().trim().lowercase().replace("-", "") == "3d"

    /** What a kind is, in words: "3D model", "flat 2D diagram", "flat 2D page". */
    fun kindLabel(kind: String?): String {
        val k = kind.orEmpty().trim().lowercase()
        if (is3d(k)) return "3D model"
        val noun = when (k) {
            "diagram" -> "diagram"; "chart" -> "chart"; "svg" -> "drawing"; "site" -> "website"; "ui" -> "app screen mockup"
            "poster" -> "poster"; "slides" -> "slide deck"; "floorplan" -> "floor plan"; "mockup" -> "mockup"; "game" -> "game"
            else -> "page"
        }
        return "flat 2D $noun"
    }

    /** The line every non-3D result carries, so the model doesn't parrot 3D boilerplate. */
    fun flatNote(kind: String?): String = if (is3d(kind)) "" else
        " It is a ${kindLabel(kind)}, not 3D: describe it as that, and don't mention orbiting, zooming, 3D or a fallback drawing."

    private val threeDTalk = Regex("""(?i)\b(3-?d|three\.?js|webgl|orbit\w*|zoom with two|pinch|rotate the model|spin the model|native drawing|falls? back to a simple)\b""")

    /**
     * v0.27.x: the final reply may only describe what the design tool produced. When designs were shown this turn and
     * none is 3D, sentences about 3D, orbiting, zooming or the 3D fallback are dropped (and a plain line given if
     * nothing is left). [kinds] empty = no design this turn: the reply is returned as it is.
     */
    fun groundReply(reply: String, kinds: List<String>): String {
        if (kinds.isEmpty() || kinds.any { is3d(it) }) return reply
        val parts = reply.split(Regex("""(?<=[.!?…])\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val keep = parts.filterNot { threeDTalk.containsMatchIn(it) }
        if (keep.size == parts.size) return reply
        val rest = keep.joinToString(" ").replace(Regex("""(?i)\bthe model\b"""), "the design").trim()
        return if (rest.length < 12) "It's open in Design as a ${kindLabel(kinds.last())}." else rest
    }

    @Volatile var rootOverride: File? = null
    /** Reads the 3D starter (the three-d-design skill's mockup.html). Set by the agent from assets; tests read the file. */
    @Volatile var templateLoader: (() -> String)? = null

    fun root(): File = (rootOverride ?: File(com.blaze.assistant.terminal.Terminal.home(), "designs")).apply { mkdirs() }

    // ---------------- current design ----------------

    fun current(): Current? = try {
        File(root(), "current.json").takeIf { it.isFile }?.let { Current.from(JSONObject(it.readText())) }?.takeIf { File(it.path).isFile }
    } catch (_: Exception) { null }

    private fun setCurrent(c: Current) { File(root(), "current.json").writeText(c.toJson().toString()) }

    /** The last design was touched in the past [withinMs] (a fallback for follow-on edits after the app restarts). */
    fun recent(withinMs: Long = 30 * 60_000L, now: Long = System.currentTimeMillis()): Boolean = current()?.let { now - it.at in 0..withinMs } == true

    // ---------------- pure helpers ----------------

    val shapes = setOf("box", "cylinder", "cone", "sphere", "torus", "capsule", "plane", "group", "custom")

    fun slug(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "design" }

    /** v0.23.5: shape names models use for the starter's presets (kept in step with ALIAS in mockup.html). */
    val shapeAliases: Map<String, String> = mapOf(
        "cube" to "box", "cuboid" to "box", "rect" to "box", "rectangle" to "box", "block" to "box", "wedge" to "box", "fin" to "box", "panel" to "box",
        "tube" to "cylinder", "pipe" to "cylinder", "disc" to "cylinder", "disk" to "cylinder", "frustum" to "cylinder", "rod" to "cylinder",
        "pyramid" to "cone", "nose" to "cone", "nosecone" to "cone", "ball" to "sphere", "ellipsoid" to "sphere", "dome" to "sphere", "hemisphere" to "sphere",
        "ring" to "torus", "donut" to "torus", "doughnut" to "torus", "pill" to "capsule", "ground" to "plane", "floor" to "plane",
    ) + shapesList().associateWith { it }

    private fun shapesList() = listOf("box", "cylinder", "cone", "sphere", "torus", "capsule", "plane", "group", "custom")

    /**
     * v0.23.5: the starter shape for a part (a known name, an alias such as "Cylinder"/"CylinderGeometry"/"nosecone",
     * or a guess from its dimensions), and whether the name had to be guessed. Mirrors shapeOf() in mockup.html, so
     * the page, the native drawing and the tool agree; an unknown shape used to give three.js a null geometry.
     */
    fun normShape(p: JSONObject): Pair<String, Boolean> {
        val given = (p.opt("shape")?.takeIf { it != JSONObject.NULL } ?: p.opt("type")?.takeIf { it != JSONObject.NULL })?.toString().orEmpty()
        val raw = given.lowercase().trim().removePrefix("three.").removeSuffix("geometry").replace(Regex("[^a-z]"), "").removeSuffix("geometry")
        shapeAliases[raw]?.let { return it to false }
        val guess = when {
            p.optJSONArray("size") != null -> if (p.optJSONArray("size")!!.length() == 2) "plane" else "box"
            p.has("tube") -> "torus"
            p.has("radius") -> if (p.has("height")) "cylinder" else "sphere"
            else -> "box"
        }
        return guess to given.isNotBlank()
    }

    /** Normalises a parts list: every part gets an id, a known shape and numbers where they're required. Returns (parts, problems). */
    fun cleanParts(parts: JSONArray): Pair<JSONArray, List<String>> {
        val out = JSONArray(); val problems = ArrayList<String>(); val seen = HashSet<String>()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            val o = JSONObject(p.toString())
            var id = slug(o.optString("id").ifBlank { o.optString("label").ifBlank { "part-${i + 1}" } })
            while (id in seen) id += "-2"
            seen += id
            o.put("id", id)
            if (!o.has("label")) o.put("label", id.replace('-', ' ').replaceFirstChar { it.uppercase() })
            val given = o.optString("shape").ifBlank { o.optString("type") }
            val (shape, guessed) = normShape(o)
            if (guessed) problems += "part $id: unknown shape \"$given\" (use ${shapes.joinToString(", ")}), drawn as a $shape"
            o.put("shape", shape)
            out.put(o)
        }
        return out to problems
    }

    /**
     * v0.23.5: a copy of a blaze-parts spec that every renderer can draw: parts is an array of objects, each with a
     * unique non-blank id and a known shape (see [normShape]). Other keys are kept as they are. Used when a design is
     * served to the Design view and drawn natively, so designs saved by older versions or edited by hand still draw.
     */
    fun normalizeSpec(spec: JSONObject): JSONObject {
        val out = JSONObject(spec.toString())
        val parts = spec.optJSONArray("parts") ?: JSONArray()
        val clean = JSONArray(); val seen = HashSet<String>()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            val o = JSONObject(p.toString())
            var id = o.optString("id").takeIf { o.opt("id") != JSONObject.NULL }.orEmpty().ifBlank { "part-${i + 1}" }
            while (id in seen) id += "-2"
            seen += id
            o.put("id", id)
            o.put("shape", normShape(o).first)
            clean.put(o)
        }
        return out.put("parts", clean)
    }
    fun spec3d(name: String, parts: JSONArray, units: String = "mm", scale: Double? = null, nudge: Double? = null): JSONObject {
        val u = units.ifBlank { "mm" }
        val sc = scale ?: when (u.lowercase()) { "m" -> 1.0; "cm" -> 0.01; else -> 0.001 }
        return JSONObject().put("name", name).put("units", u).put("scale", sc).put("nudge", nudge ?: if (u == "m") 0.1 else 10.0).put("parts", parts)
    }

    private val partsBlock = Regex("""(<script type="application/json" id="blaze-parts">)([\s\S]*?)(</script>)""")

    /** The parts spec inside a 3D page, or null when the page isn't a parts-based 3D design. */
    fun specFrom(html: String): JSONObject? = partsBlock.find(html)?.let { try { JSONObject(it.groupValues[2]) } catch (_: Exception) { null } }

    /** v0.23.5: [html] with its blaze-parts block replaced by [spec] (unchanged when it has none). */
    fun withSpec(html: String, spec: JSONObject): String {
        val json = spec.toString(2).replace("</", "<\\/")
        return partsBlock.find(html)?.let { m -> html.replaceRange(m.range, m.groupValues[1] + "\n" + json + "\n" + m.groupValues[3]) } ?: html
    }

    /**
     * v0.23.5: true when [html] is a page made from the 3D starter with no hand-written code (its CUSTOM builders
     * are still the starter's empty example), so it can be re-made from the current starter without losing anything.
     */
    fun isStockStarter(html: String): Boolean =
        html.contains("Blaze 3D mockup starter") && Regex("""const CUSTOM = \{\s*//[^\n]*\n\s*\};""").containsMatchIn(html)

    /** v0.23.5: a stock starter page re-made from the current [template] (same parts, title and Blaze additions), else [html]. */
    fun upgradeStarter(html: String, template: String?): String {
        if (template == null || !isStockStarter(html) || !isStockStarter(template)) return html
        val spec = specFrom(html) ?: return html
        return render3d(template, spec)
    }

    /** v0.23.2's additions (kept so pages made then are recognised and upgraded on their next update). */
    private const val EXTRA_3D_V1 = """
<style>@media (max-width: 640px) { #panel { max-width: 46vw; font-size: 11px; padding: 6px 8px; } #panel table { font-size: 10px; } }
#bz-fail { position: fixed; inset: auto 12px 12px 12px; padding: 10px 12px; border-radius: 10px; background: #fff3e0; color: #6d3d00; font: 13px system-ui, sans-serif; display: none; }</style>
<div id="bz-fail">The 3D engine didn't load, so the scene can't be drawn. Check the connection and tap Reload.</div>
<script>setTimeout(function () { if (!window.__blazeScene) document.getElementById('bz-fail').style.display = 'block'; }, 8000);</script>
"""

    /**
     * Blaze's additions to every 3D page: a phone-sized panel, and (for browsers outside Blaze, e.g. "Open")
     * a message with the real error when three.js doesn't start within 20 s. Inside Blaze the Design view hides
     * this and shows its own banner, retries with the WebGL1 build and falls back to a native drawing (v0.23.3).
     */
    private const val EXTRA_3D = """<!--bz-extra-->
<style>@media (max-width: 640px) { #panel { max-width: 46vw; font-size: 11px; padding: 6px 8px; } #panel table { font-size: 10px; } }
#bz-fail { position: fixed; inset: auto 12px 12px 12px; padding: 10px 12px; border-radius: 10px; background: #fff3e0; color: #6d3d00; font: 13px system-ui, sans-serif; display: none; }</style>
<div id="bz-fail">The 3D engine didn't load, so the scene can't be drawn. Tap Reload to try again.</div>
<script>(function () { var errs = window.__bzErrors = window.__bzErrors || [];
addEventListener('error', function (e) { errs.push(e && e.message ? e.message : 'a script or module failed to load'); }, true);
addEventListener('unhandledrejection', function (e) { errs.push(String(e.reason && (e.reason.message || e.reason))); });
setTimeout(function () { if (window.__blazeScene) return; var el = document.getElementById('bz-fail'); if (!el) return;
  if (errs.length) el.textContent = "The 3D engine didn't start: " + errs[0] + ". Tap Reload to try again.";
  el.style.display = 'block'; }, 20000); })();</script>
<!--/bz-extra-->
"""

    private val extraBlock = Regex("<!--bz-extra-->[\\s\\S]*?<!--/bz-extra-->\\n?")

    /** A 3D page: the starter with [spec] as its blaze-parts block. */
    fun render3d(template: String, spec: JSONObject): String {
        val json = spec.toString(2).replace("</", "<\\/")
        var html = if (partsBlock.containsMatchIn(template)) partsBlock.replace(template) { m -> m.groupValues[1] + "\n" + json + "\n" + m.groupValues[3] }
            else template
        html = html.replace(Regex("<title>[\\s\\S]*?</title>"), Regex.escapeReplacement("<title>${esc(spec.optString("name", "3D design"))}</title>"))
        html = html.replace(EXTRA_3D_V1, "").replace(extraBlock, "")
        if (!html.contains("id=\"bz-fail\"")) html = html.replace("</body>", EXTRA_3D + "</body>")
        return html
    }

    /**
     * The Design view's last render error (~/designs/.last_error.txt, written by the Design view when a page
     * fails to draw and removed when one draws), as a note for the model; null when there is none.
     */
    fun lastErrorNote(now: Long = System.currentTimeMillis()): String? = try {
        File(root(), ".last_error.txt").takeIf { it.isFile && now - it.lastModified() < 24 * 3_600_000L }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
            val first = t.lines().firstOrNull { it.startsWith("- ") }?.removePrefix("- ")?.take(200) ?: t.lines().first()
            "\nNote: the Design view reported a render error last time it drew a design: $first (details: ~/designs/.last_error.txt; " +
                "the phone falls back to a simple native drawing when three.js can't start)."
        }
    } catch (_: Exception) { null }

    /** Merges part changes by id: [set] entries replace keys of the part with that id (or add a new part); [remove] drops ids (and their children). */
    fun mergeParts(spec: JSONObject, set: JSONArray?, remove: Collection<String>): Pair<JSONObject, String> {
        val parts = JSONObject(spec.toString()).optJSONArray("parts") ?: JSONArray()   // a copy: [spec] stays as it was
        val byId = LinkedHashMap<String, JSONObject>()
        for (i in 0 until parts.length()) parts.optJSONObject(i)?.let { byId[it.optString("id")] = it }
        val changed = ArrayList<String>(); val added = ArrayList<String>(); val removed = ArrayList<String>()
        val drop = HashSet(remove.map { slug(it) })
        // Children of removed groups go too.
        var grew = true
        while (grew) { grew = false; for ((id, p) in byId) if (id !in drop && p.optString("parent") in drop) { drop += id; grew = true } }
        drop.forEach { if (byId.remove(it) != null) removed += it }
        if (set != null) for (i in 0 until set.length()) {
            val p = set.optJSONObject(i) ?: continue
            val id = slug(p.optString("id").ifBlank { p.optString("label") })
            val old = byId[id]
            if (old != null) {
                p.keys().forEach { k -> if (k != "id") old.put(k, p.get(k)) }
                if (p.has("shape") || p.has("type")) old.put("shape", normShape(old).first)   // v0.23.5: never an unknown shape
                changed += id
            }
            else {
                val (clean, _) = cleanParts(JSONArray().put(JSONObject(p.toString()).put("id", id)))
                byId[id] = clean.getJSONObject(0); added += id
            }
        }
        val out = JSONObject(spec.toString()).put("parts", JSONArray(byId.values.toList()))
        val summary = listOf("added" to added, "changed" to changed, "removed" to removed).filter { it.second.isNotEmpty() }
            .joinToString("; ") { (w, l) -> "$w ${l.joinToString(", ")}" }.ifBlank { "no parts changed" }
        return out to summary
    }

    /** A full HTML document for [html] (wraps a fragment with a phone viewport). */
    fun ensureDoc(title: String, html: String): String {
        val h = html.trim()
        if (Regex("(?i)<html[\\s>]|<!doctype").containsMatchIn(h)) return h
        return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>${esc(title)}</title><style>html,body{margin:0;font-family:system-ui,sans-serif}</style></head>\n<body>\n$h\n</body></html>"
    }

    /** SVG art / diagrams, centred and scaled to the screen. */
    fun wrapSvg(title: String, svg: String): String =
        "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>${esc(title)}</title><style>html,body{margin:0;height:100%;background:#f6f5f2}body{display:flex;align-items:center;justify-content:center}" +
            "svg{max-width:100vw;max-height:100vh;width:100%;height:auto}</style></head>\n<body>\n${svg.trim()}\n</body></html>"

    /** Exact find/replace edits. Returns (new html, problems). */
    fun applyEdits(html: String, edits: JSONArray): Pair<String, List<String>> {
        var s = html; val problems = ArrayList<String>()
        for (i in 0 until edits.length()) {
            val e = edits.optJSONObject(i) ?: continue
            val find = e.optString("find"); val repl = e.optString("replace")
            if (find.isEmpty()) { problems += "edit ${i + 1}: find is empty"; continue }
            val n = s.split(find).size - 1
            when {
                n == 0 -> problems += "edit ${i + 1}: text not found (\"${find.take(60)}\")"
                n > 1 && !e.optBoolean("all") -> problems += "edit ${i + 1}: found $n times; add more surrounding text or set all=true"
                else -> s = if (e.optBoolean("all")) s.replace(find, repl) else s.replaceFirst(find, repl)
            }
        }
        return s to problems
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun template(): String = templateLoader?.invoke()
        ?: throw IllegalStateException("3D starter not available")

    // ---------------- the tool ----------------

    fun tool(): JSONObject {
        fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)
        val part = JSONObject().put("type", "object").put("description",
            "A 3D part: id (kebab-case), label, shape (box size=[w,h,d] | cylinder radius,height,radiusTop | cone radius,height | sphere radius | torus radius,tube | capsule radius,height | plane size=[w,d] | group), " +
                "position=[x,y,z] (the part's centre, relative to parent; y is up), rotation=[x,y,z] degrees, color \"#hex\", material (matte|wood|metal|plastic|glass), parent (a group id), scale=[x,y,z], visible (false hides it), opacity.")
        val pageEl = JSONObject().put("type", "object").put("description",
            "A canvas element: id (kebab-case), label, type (text | button | rect | ellipse), x, y, w, h (page px, top-left origin), text, size (font px), " +
                "weight (400-700), color \"#hex\" (text), fill \"#hex\" (background, #RRGGBBAA for translucent), radius, align (left|center|right), z, hidden.")
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
            .put("description", "Make or change anything visual or interactive in Blaze's Design view (live preview, opens automatically): 3D scenes and models " +
                "(a rocket, car, building, room, product, character: pass parts, Blaze renders it with three.js and orbit controls), 2D art and icons (svg), " +
                "websites and landing pages, UI mockups and app screens, diagrams and flowcharts, charts and dashboards, posters, flyers, slides, floor plans, " +
                "product mockups, simple games (html). Posts, stories, slides, icons and posters the user will adjust by hand: kind canvas with elements. create starts a new design and makes it current; update changes the CURRENT design (use it for every " +
                "follow-on like \"show the engine\", \"make it taller\", \"add fins\", \"make it red\": 3D via set_parts/remove_parts, canvas via set_elements/remove_elements, pages via edits or html); " +
                "read returns the current design's source; open shows it; undo restores the previous version; list shows saved designs. " +
                "Never say a design was made, changed or opened unless this tool (or open_view) succeeded this turn.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("create", "update", "read", "open", "undo", "list"))))
                .put("kind", JSONObject().put("type", "string").put("description", "create: 3d | canvas | page | site | ui | svg | diagram | chart | poster | slides | floorplan | mockup | game | other"))
                .put("title", p("string", "create: short name, e.g. \"Tall modern rocket\""))
                .put("parts", JSONObject().put("type", "array").put("items", part).put("description", "3d create: every part of the model (5-40 parts, real proportions, parts that should touch do)"))
                .put("units", p("string", "3d: mm (default, small objects) | cm | m (buildings, rooms, vehicles)"))
                .put("set_parts", JSONObject().put("type", "array").put("items", part).put("description", "3d update: parts to add or change, matched by id; only the keys you give change"))
                .put("remove_parts", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "3d update: part ids to remove"))
                .put("elements", JSONObject().put("type", "array").put("items", pageEl).put("description", "canvas create (posts, stories, slides, icons, posters, app screens the user will tweak by hand): every element, in page px"))
                .put("set_elements", JSONObject().put("type", "array").put("items", pageEl).put("description", "canvas update: elements to add or change, matched by id; only the keys you give change"))
                .put("remove_elements", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "canvas update: element ids to remove"))
                .put("format", p("string", "canvas: post (1080x1350, default) | story (1080x1920) | slide (1920x1080) | icon (1024x1024) | screen (390x844) | poster (794x1123)"))
                .put("background", p("string", "canvas: page colour \"#hex\""))
                .put("html", p("string", "Pages and everything 2D: a complete self-contained HTML document (inline CSS/JS; CDN libraries are fine). update: replaces the whole page"))
                .put("svg", p("string", "2D art, icons, simple diagrams: SVG markup (viewBox set)"))
                .put("edits", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("find", p("string", "exact text in the current file")).put("replace", p("string", "new text")).put("all", p("boolean", "replace every match"))))
                    .put("description", "update (pages/svg): exact find/replace edits to the current file; read it first if unsure"))
                .put("path", p("string", "Optional: a design file to work on instead of the current one (~/designs/<name>/index.html)")))
                .put("required", JSONArray(listOf("action")))))
    }

    fun step(args: JSONObject): String = when (args.optString("action")) {
        "create" -> "Designing \u201c${args.optString("title").ifBlank { "a design" }.take(50)}\u201d"
        "update" -> "Updating the design"
        "read" -> "Reading the design"
        "open" -> "Opening the design"
        "undo" -> "Undoing the last design change"
        else -> "Checking designs"
    }

    fun run(args: JSONObject, now: Long = System.currentTimeMillis()): Outcome = runAndSync(args, now)

    /** v0.40.0: a design that changed goes to cloud sync (when it's on; off by default). */
    private fun runAndSync(args: JSONObject, now: Long): Outcome = runLocal(args, now).also { if (it.ok) synced() }
    private fun synced() { com.blaze.assistant.connect.CloudSync.localChange(com.blaze.assistant.connect.SyncKinds.DESIGNS) }

    private fun runLocal(args: JSONObject, now: Long): Outcome =
        withErrorNote(args, runInner(args, now)).also { o -> if (o.open != null && o.kind != null) touched.add(o.kind) }

    /** v0.23.3: create / update / open / read results mention ~/designs/.last_error.txt when the Design view left one. */
    private fun withErrorNote(args: JSONObject, o: Outcome): Outcome {
        if (args.optString("action").lowercase() !in setOf("create", "update", "edit", "open", "read")) return o
        // v0.27.x: the three.js render error (and its native-drawing fallback) only concerns 3D designs.
        if (o.kind != null && !is3d(o.kind)) return o
        val note = lastErrorNote() ?: return o
        return o.copy(text = o.text + note)
    }

    private fun runInner(args: JSONObject, now: Long): Outcome = try {
        when (args.optString("action").lowercase()) {
            "create" -> create(args, now)
            "update", "edit" -> update(args, now)
            "read" -> target(args)?.let { f -> Outcome("Current design ${f.absolutePath} (${current()?.kind ?: "page"}):\n" + f.readText().let { if (it.length > 24_000) it.take(24_000) + "\n…(cut)" else it }, null, true) }
                ?: Outcome("Error: there's no current design yet. Use action=create.")
            "open" -> target(args)?.let { f -> openOutcome(f, now) }
                ?: Outcome("Error: there's no current design yet. Use action=create.")
            "undo" -> undo(args, now)
            else -> Outcome(list(), null, true)
        }
    } catch (e: Exception) { Outcome("Error: ${e.message ?: e.javaClass.simpleName}") }

    private fun openOutcome(f: File, now: Long): Outcome {
        val before = current()?.takeIf { it.path == f.absolutePath }
        touch(f, now)
        val cur = current()?.takeIf { it.path == f.absolutePath }
        val kind = cur?.kind ?: kindOf(f.readText())
        val title = cur?.title?.ifBlank { null } ?: f.parentFile?.name ?: "design"
        val age = before?.at?.let { now - it }?.takeIf { it > 30 * 60_000L }
        return Outcome("Opened \u201c$title\u201d (${kindLabel(kind)}) from ${f.absolutePath} in Design." +
            (if (age != null) " It was made in an earlier conversation, not this turn." else "") +
            (if (is3d(kind)) " The user can orbit with one finger and zoom with two." else flatNote(kind)) +
            " The user can tap Exit to come back to the chat.", f.absolutePath, kind = kind)
    }

    private fun target(args: JSONObject): File? {
        val p = args.optString("path").trim()
        if (p.isNotEmpty()) {
            val f = if (rootOverride != null && !p.startsWith("/")) File(root(), p.removePrefix("~/designs/")) else com.blaze.assistant.terminal.DevTools.resolve(p)
            return (if (f.isDirectory) File(f, "index.html") else f).takeIf { it.isFile }
        }
        return current()?.let { File(it.path) }?.takeIf { it.isFile }
    }

    private fun touch(f: File, now: Long) {
        val c = current()
        setCurrent(if (c != null && c.path == f.absolutePath) c.copy(at = now) else Current(f.absolutePath, kindOf(f.readText()), f.parentFile?.name ?: "design", 1, now))
    }

    /** v0.35.0: the kind a design file is, from its content: 3d (blaze-parts), canvas (a studio page) or page. */
    fun kindOf(html: String): String = when {
        specFrom(html) != null -> "3d"
        DesignPage.fromHtml(html) != null -> CANVAS
        else -> "page"
    }

    /** Kind of a studio page (Page mode: elements the user moves, resizes and recolours by hand). */
    const val CANVAS = "canvas"

    // ---------------- studio (v0.35.0) ----------------

    /** Writes [html] as the next version of [f] (the previous one kept as .v<N>.html for undo); returns the new Current. */
    private fun commit(f: File, old: String, html: String, now: Long): Current {
        val cur = current()?.takeIf { it.path == f.absolutePath } ?: Current(f.absolutePath, kindOf(old), f.parentFile?.name ?: "design", 1, now)
        val v = cur.version
        File(f.parentFile, ".v$v.html").writeText(old)
        File(f.parentFile, ".v${v - KEEP_VERSIONS}.html").delete()
        f.writeText(html)
        return cur.copy(version = v + 1, at = now).also { setCurrent(it) }
    }

    /**
     * Saves a page the user edited by hand in the studio (move, resize, colour, text, format) as a new version of
     * the current design, so Blaze's next edit and Undo see it. Returns the new version, or null when there's no
     * current studio page to save to.
     */
    fun savePage(doc: DesignPage.Doc, now: Long = System.currentTimeMillis()): Current? = try {
        val f = current()?.let { File(it.path) }?.takeIf { it.isFile }
        val old = f?.readText()
        if (f == null || old == null || DesignPage.fromHtml(old) == null) null
        else { val html = DesignPage.render(doc); if (html == old) current() else commit(f, old, html, now).also { synced() } }
    } catch (_: Exception) { null }

    /** "New page" in the studio: a starter page in [format], made the current design. */
    fun createPage(title: String, format: String, now: Long = System.currentTimeMillis()): Current {
        val doc = DesignPage.starter(title, DesignPage.format(format))
        var dir = File(root(), slug(title)); var n = 2
        while (dir.exists() && File(dir, "index.html").isFile) dir = File(root(), slug(title) + "-" + n++)
        dir.mkdirs()
        val f = File(dir, "index.html"); f.writeText(DesignPage.render(doc))
        return Current(f.absolutePath, CANVAS, title, 1, now).also { setCurrent(it); synced() }
    }

    /** The studio page in the current design, or null (none, or not a studio page). */
    fun currentPage(): DesignPage.Doc? = try { current()?.let { File(it.path) }?.takeIf { it.isFile }?.let { DesignPage.fromHtml(it.readText()) } } catch (_: Exception) { null }

    /** Undo from the studio's Undo button: the same as the tool's undo. */
    fun undoCurrent(now: Long = System.currentTimeMillis()): Outcome = try { undo(JSONObject(), now) } catch (e: Exception) { Outcome("Error: ${e.message}") }

    private fun create(args: JSONObject, now: Long): Outcome {
        val title = args.optString("title").ifBlank { "Design" }
        val parts = args.optJSONArray("parts")
        var kind = args.optString("kind").lowercase().ifBlank { if (parts != null) "3d" else if (args.optString("svg").isNotBlank()) "svg" else "page" }
        val problems = ArrayList<String>()
        val html = when {
            // v0.35.0: a studio page (elements by id) the user can also edit by hand in Page mode.
            args.optJSONArray("elements")?.let { it.length() > 0 } == true -> {
                kind = CANVAS
                val fmt = DesignPage.format(args.optString("format"))
                DesignPage.render(DesignPage.docFrom(JSONObject().put("name", title).put("format", fmt.id)
                    .put("background", args.optString("background")).put("elements", args.getJSONArray("elements"))))
            }
            parts != null && parts.length() > 0 -> {
                kind = "3d"
                val (clean, pr) = cleanParts(parts); problems += pr
                render3d(template(), spec3d(title, clean, args.optString("units", "mm")))
            }
            kind == "3d" -> return Outcome("Error: a 3D design needs parts=[…] (box, cylinder, cone, sphere, torus, capsule, plane, group), or html with your own three.js scene.")
            args.optString("svg").isNotBlank() -> wrapSvg(title, args.optString("svg"))
            args.optString("html").isNotBlank() -> ensureDoc(title, args.optString("html"))
            else -> return Outcome("Error: nothing to draw. Pass parts (3D), svg (2D art) or html (pages and everything else).")
        }
        var dir = File(root(), slug(title)); var n = 2
        while (dir.exists() && File(dir, "index.html").isFile) dir = File(root(), slug(title) + "-" + n++)
        dir.mkdirs()
        val f = File(dir, "index.html"); f.writeText(html)
        setCurrent(Current(f.absolutePath, kind, title, 1, now))
        val count = if (kind == "3d") " with ${specFrom(html)?.optJSONArray("parts")?.length() ?: 0} parts"
            else if (kind == CANVAS) " with ${DesignPage.fromHtml(html)?.els?.size ?: 0} elements the user can move, resize and recolour by hand" else ""
        return Outcome("Created \u201c$title\u201d, a ${kindLabel(kind)}$count, at ${f.absolutePath} and opened it in Design" +
            (if (kind == "3d") " (the user can orbit with one finger and zoom with two)." else "." + flatNote(kind)) +
            " It's the current design: follow-on changes go through design action=update." + problems.joinToString("") { "\nNote: $it" }, f.absolutePath, kind = kind)
    }

    private fun update(args: JSONObject, now: Long): Outcome {
        val f = target(args) ?: return Outcome("Error: there's no current design to update. Use action=create first.")
        val old = f.readText()
        val spec = specFrom(old)
        val problems = ArrayList<String>()
        var summary: String
        val page = DesignPage.fromHtml(old)
        val html: String = when {
            page != null && (args.has("set_elements") || args.has("remove_elements") || args.has("format") || args.has("background")) -> {
                val remove = args.optJSONArray("remove_elements")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                var (d, s) = DesignPage.merge(page, args.optJSONArray("set_elements"), remove)
                if (args.optString("format").isNotBlank()) { d = DesignPage.reformat(d, DesignPage.format(args.optString("format"))); s += "; format ${d.format}" }
                DesignPage.parseHex(args.optString("background"))?.let { d = d.copy(background = DesignPage.toHex(it)); s += "; background" }
                if (args.optString("title").isNotBlank()) d = d.copy(name = args.optString("title"))
                if (d == page) return Outcome("Error: that changed nothing (unknown ids? the current elements are: " + page.els.joinToString(", ") { it.id } + ").")
                summary = s
                DesignPage.render(d)
            }
            spec != null && (args.has("set_parts") || args.has("remove_parts") || args.has("parts")) -> {
                val remove = args.optJSONArray("remove_parts")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                val base = if (args.has("parts")) JSONObject(spec.toString()).put("parts", cleanParts(args.getJSONArray("parts")).first) else spec
                val (merged, s) = mergeParts(base, args.optJSONArray("set_parts"), remove)
                if (args.optString("title").isNotBlank()) merged.put("name", args.optString("title"))
                summary = if (args.has("parts") && !args.has("set_parts") && remove.isEmpty()) "replaced all parts" else s
                if (merged.toString() == spec.toString()) return Outcome("Error: that changed nothing (unknown ids? the current parts are: " +
                    (0 until (spec.optJSONArray("parts")?.length() ?: 0)).joinToString(", ") { spec.getJSONArray("parts").getJSONObject(it).optString("id") } + ").")
                // v0.23.5: pages from an older starter get the current one (bad parts can't stop the scene).
                render3d(upgradeStarter(old, try { templateLoader?.invoke() } catch (_: Exception) { null }), merged)
            }
            args.optJSONArray("edits")?.let { it.length() > 0 } == true -> {
                val (s, pr) = applyEdits(old, args.getJSONArray("edits")); problems += pr
                if (s == old) return Outcome("Error: no edit applied. " + pr.joinToString("; ") + ". Read the design (action=read) and retry with exact text.")
                summary = "${args.getJSONArray("edits").length() - pr.size} edit(s)"
                s
            }
            args.optString("html").isNotBlank() -> { summary = "new page"; ensureDoc(current()?.title ?: "Design", args.optString("html")) }
            args.optString("svg").isNotBlank() -> { summary = "new artwork"; wrapSvg(current()?.title ?: "Design", args.optString("svg")) }
            spec != null -> return Outcome("Error: this is a 3D design; pass set_parts (add or change parts by id) and/or remove_parts.")
            page != null -> return Outcome("Error: this is a studio page; pass set_elements (add or change elements by id), remove_elements, format or background.")
            else -> return Outcome("Error: pass edits=[{find, replace}] or a new html/svg.")
        }
        val cur = commit(f, old, html, now)
        return Outcome("Updated \u201c${cur.title}\u201d, a ${kindLabel(cur.kind)} ($summary), version ${cur.version}, and showed it in Design." + flatNote(cur.kind) +
            problems.joinToString("") { "\nNote: $it" }, f.absolutePath, kind = cur.kind)
    }

    private fun undo(args: JSONObject, now: Long): Outcome {
        val f = target(args) ?: return Outcome("Error: there's no current design.")
        val cur = current()?.takeIf { it.path == f.absolutePath } ?: return Outcome("Error: nothing to undo for that file.")
        val prev = File(f.parentFile, ".v${cur.version - 1}.html")
        if (cur.version <= 1 || !prev.isFile) return Outcome("Error: there's no earlier version to go back to.")
        f.writeText(prev.readText()); prev.delete()
        setCurrent(cur.copy(version = cur.version - 1, at = now))
        return Outcome("Went back to version ${cur.version - 1} of \u201c${cur.title}\u201d (${kindLabel(cur.kind)}) and showed it in Design." + flatNote(cur.kind), f.absolutePath, kind = cur.kind)
    }

    fun list(): String {
        val dirs = root().listFiles()?.filter { File(it, "index.html").isFile }?.sortedByDescending { File(it, "index.html").lastModified() } ?: emptyList()
        if (dirs.isEmpty()) return "No designs yet."
        val cur = current()?.path
        return dirs.take(30).joinToString("\n") { d -> val f = File(d, "index.html"); "- ${f.absolutePath}" + (if (f.absolutePath == cur) "  (current)" else "") }
    }
}
