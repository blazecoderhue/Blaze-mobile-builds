package com.blaze.assistant.ui

import org.json.JSONObject
import org.json.JSONTokener

/**
 * v0.26.3 offline diagrams, the pure part (DiagramRenderTest). Mermaid is drawn on the phone by the bundled
 * mermaid.js (assets/diagram/mermaid.min.js) in an offscreen WebView ([DiagramRenderer]); the diagram's text never
 * leaves the phone. This object decides the order of renderers, the cache key, the look (Blaze's colours, light and
 * dark) and writes the small HTML page that does the drawing, with the diagram text embedded as an escaped JS string
 * so it can never become markup or script.
 *
 * Order: Mermaid → on the phone first; mermaid.ink only if that fails and the chat isn't private. Graphviz, PlantUML
 * and D2 have no renderer on the phone → kroki.io, except in a private chat (nothing goes out: a plain line and
 * "Show code" instead). The raw source is never shown as the picture.
 */
object DiagramRender {
    /** The bundled mermaid.js (npm mermaid, MIT; see THIRD_PARTY_NOTICES.md). Part of the cache key. */
    const val MERMAID_VERSION = "11.17.2"
    const val ASSET_HOST = "https://appassets.androidplatform.net"
    const val SCRIPT_URL = "$ASSET_HOST/diagram/mermaid.min.js"
    /** Base URL the harness page is loaded at (same origin as the script, so the canvas export isn't blocked). */
    const val PAGE_URL = "$ASSET_HOST/diagram/"
    /** A local render that takes longer than this is given up (then the fallback, if any). */
    const val TIMEOUT_MS = 15_000L
    /** Longer diagram text isn't drawn (mermaid's own limit is 50k; a phone wants far less). */
    const val MAX_CODE = 20_000
    /** The picture's longest side in pixels (2× for sharpness, capped for memory). */
    const val MAX_WIDTH_PX = 1600
    const val MAX_HEIGHT_PX = 4096

    enum class Source { LOCAL, REMOTE }

    /** Which renderers to try, in order. Empty = nothing may draw it (private chat, no local renderer). */
    fun plan(kind: String, private: Boolean): List<Source> = when {
        kind == "mermaid" -> if (private) listOf(Source.LOCAL) else listOf(Source.LOCAL, Source.REMOTE)
        private -> emptyList()
        else -> listOf(Source.REMOTE)
    }

    private fun normCode(code: String) = code.replace("\r\n", "\n").replace('\r', '\n').trim()

    /** The cache file name for a picture: sha-256 over renderer version, kind, look and the code (hex, .png added by the caller). */
    fun cacheKey(kind: String, code: String, dark: Boolean, source: Source = Source.LOCAL): String {
        val s = "blaze-diagram-v1\n$MERMAID_VERSION\n${source.name}\n${kind.trim().lowercase()}\n${if (dark) "dark" else "light"}\n${normCode(code)}"
        return java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Blaze's colours for a diagram (Theme.kt: paper / ink / accent), as CSS hex. */
    data class Look(val dark: Boolean, val bg: String, val ink: String, val sub: String, val node: String, val border: String,
                    val line: String, val cluster: String, val note: String)

    fun look(dark: Boolean): Look = if (dark)
        Look(true, bg = "#26262A", ink = "#F0F0EE", sub = "#B4B4B8", node = "#34343A", border = "#6B8CFF", line = "#A7A7AE", cluster = "#2E2E33", note = "#3A3A44")
    else
        Look(false, bg = "#FFFFFF", ink = "#111110", sub = "#5A5A5E", node = "#F1F1EF", border = "#1F4BD8", line = "#5A5A5E", cluster = "#F7F7F5", note = "#EEF2FF")

    /**
     * [s] as a JS string literal (double quotes) that is also valid JSON. Every character that could end the string,
     * close the <script> element or start markup is escaped (\" \\ \n, and < > & ' / U+2028 U+2029 and all control
     * characters as \uXXXX), so diagram text can't inject script.
     */
    fun jsString(s: String): String {
        val b = StringBuilder(s.length + 16).append('"')
        for (ch in s) {
            when (ch) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                '<', '>', '&', '\'', '/', '\u2028', '\u2029', '`' -> b.append(String.format("\\u%04x", ch.code))
                else -> if (ch < ' ' || ch == '\u007f') b.append(String.format("\\u%04x", ch.code)) else b.append(ch)
            }
        }
        return b.append('"').toString()
    }

    /** A random-enough nonce for the page's Content-Security-Policy (letters and digits only). */
    fun nonce(r: java.util.Random = java.security.SecureRandom()): String {
        val a = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..24).map { a[r.nextInt(a.length)] }.joinToString("")
    }

    /** mermaid.initialize options: strict security (no clicks, no HTML in labels), Blaze's colours, SVG text labels. */
    fun config(l: Look): String = JSONObject()
        .put("startOnLoad", false).put("securityLevel", "strict").put("theme", "base").put("htmlLabels", true)
        .put("fontFamily", "sans-serif").put("deterministicIds", true).put("maxTextSize", MAX_CODE)
        .put("flowchart", JSONObject().put("htmlLabels", true).put("curve", "basis").put("padding", 12))
        .put("themeVariables", JSONObject()
            .put("darkMode", l.dark).put("background", l.bg).put("fontFamily", "sans-serif").put("fontSize", "15px")
            .put("primaryColor", l.node).put("primaryTextColor", l.ink).put("primaryBorderColor", l.border)
            .put("secondaryColor", l.cluster).put("secondaryTextColor", l.ink).put("secondaryBorderColor", l.border)
            .put("tertiaryColor", l.cluster).put("tertiaryTextColor", l.ink).put("tertiaryBorderColor", l.line)
            .put("lineColor", l.line).put("textColor", l.ink).put("titleColor", l.ink)
            .put("mainBkg", l.node).put("nodeBorder", l.border).put("clusterBkg", l.cluster).put("clusterBorder", l.line)
            .put("edgeLabelBackground", l.bg).put("noteBkgColor", l.note).put("noteTextColor", l.ink).put("noteBorderColor", l.border)
            .put("actorBkg", l.node).put("actorBorder", l.border).put("actorTextColor", l.ink).put("actorLineColor", l.line)
            .put("signalColor", l.line).put("signalTextColor", l.ink).put("labelBoxBkgColor", l.node).put("labelTextColor", l.ink)
            .put("pie1", l.border).put("pie2", "#4C7DFF").put("pie3", "#8AA6FF").put("pie4", "#2A3F8F").put("pie5", "#B9C8FF")
            .put("pie6", "#5A5A5E").put("pie7", "#9A9AA2").put("pieStrokeColor", l.bg).put("pieTitleTextColor", l.ink)
            .put("pieSectionTextColor", "#FFFFFF").put("pieLegendTextColor", l.ink).put("pieOpacity", "1")
            .also { tv -> scale(l).forEachIndexed { i, c -> tv.put("cScale$i", c).put("cScaleLabel$i", l.ink).put("cScalePeer$i", l.border).put("git$i", c).put("gitBranchLabel$i", l.ink) } })
        .toString()

    /** Section colours (mindmap branches, timeline sections, git branches): Blaze blues and greys that keep the label readable. */
    fun scale(l: Look): List<String> = if (l.dark)
        listOf("#2E3F7A", "#3A3A44", "#26407F", "#44444E", "#35477F", "#30303A", "#2B3B6E", "#4A4A55", "#3C4F8C", "#383842", "#22356B", "#42424C")
    else
        listOf("#DCE5FF", "#ECECEA", "#C9D7FF", "#E4E4E2", "#D3DEFF", "#F1F1EF", "#BFD0FF", "#E8E8E6", "#E1E8FF", "#EDEDEB", "#CCD9FF", "#E2E2E0")

    /**
     * The page that draws [code]: loads the bundled mermaid.js, renders to SVG, paints it on a canvas over the
     * card colour and leaves the PNG (base64) or an error in window.__blaze for the app to collect. The CSP allows
     * only this page's two scripts (by nonce) and data: images; nothing can be fetched.
     */
    fun harness(code: String, dark: Boolean, nonce: String = nonce(), scriptUrl: String = SCRIPT_URL): String {
        val l = look(dark)
        val n = nonce.filter { it.isLetterOrDigit() }.ifEmpty { "n" }
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'nonce-$n'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:\">" +
            "<meta name=\"viewport\" content=\"width=800,initial-scale=1\">" +
            "<style>html,body{margin:0;background:${l.bg};font-family:sans-serif}#out{width:800px}</style>" +
            "<script nonce=\"$n\" src=\"$scriptUrl\"></script></head><body><div id=\"out\"></div>" +
            "<script nonce=\"$n\">(function(){\n" +
            "var code=" + jsString(code) + ";\nvar bg=" + jsString(l.bg) + ";\n" +
            "var MAXW=$MAX_WIDTH_PX,MAXH=$MAX_HEIGHT_PX;\n" +
            "function done(r){window.__blaze=JSON.stringify(r);}\n" +
            "function fail(e,syntax){done({ok:false,syntax:!!syntax,error:String((e&&(e.message||e.str))||e||'error').slice(0,300)});}\n" +
            "var cfg=" + config(l) + ";\n" +
            // HTML labels look best (mindmaps, wrapped text); if this WebView won't export a picture with them, draw again with SVG text.
            "function run(html){\n" +
            " cfg.htmlLabels=html;cfg.flowchart.htmlLabels=html;mermaid.initialize(cfg);\n" +
            " mermaid.parse(code).then(function(){return mermaid.render(html?'blazeD1':'blazeD2',code);},function(e){fail(e,true);return null;}).then(function(r){\n" +
            "  if(!r)return;\n" +
            "  var box=document.getElementById('out');box.innerHTML=r.svg;var svg=box.querySelector('svg');\n" +
            "  if(!svg){fail('no picture');return;}\n" +
            "  var vb=svg.viewBox&&svg.viewBox.baseVal;var w=(vb&&vb.width)||svg.getBBox().width;var h=(vb&&vb.height)||svg.getBBox().height;\n" +
            "  if(!(w>0&&h>0)){fail('empty picture');return;}\n" +
            "  var s=Math.min(2,MAXW/w,MAXH/h);\n" +
            "  svg.setAttribute('width',w);svg.setAttribute('height',h);svg.style.maxWidth='none';\n" +
            "  svg.setAttribute('xmlns','http://www.w3.org/2000/svg');\n" +
            "  var data=new XMLSerializer().serializeToString(svg);box.innerHTML='';\n" +
            "  var img=new Image();\n" +
            "  img.onload=function(){var u;try{var c=document.createElement('canvas');c.width=Math.max(1,Math.round(w*s));c.height=Math.max(1,Math.round(h*s));\n" +
            "   var g=c.getContext('2d');g.fillStyle=bg;g.fillRect(0,0,c.width,c.height);g.drawImage(img,0,0,c.width,c.height);\n" +
            "   u=c.toDataURL('image/png');}catch(e){if(html){run(false);}else{fail(e);}return;}\n" +
            "   done({ok:true,png:u.substring(u.indexOf(',')+1),w:Math.round(w*s),h:Math.round(h*s)});};\n" +
            "  img.onerror=function(){if(html){run(false);}else{fail('the picture could not be painted');}};\n" +
            "  img.src='data:image/svg+xml;charset=utf-8,'+encodeURIComponent(data);\n" +
            " }).catch(function(e){fail(e);});\n" +
            "}\n" +
            "try{\n" +
            " if(typeof mermaid==='undefined'){fail('mermaid.js did not load');return;}\n" +
            " run(true);\n" +
            "}catch(e){fail(e);}\n" +
            "})();</script></body></html>"
    }

    /** What the page left in window.__blaze. */
    data class Result(val ok: Boolean, val png: String = "", val w: Int = 0, val h: Int = 0, val error: String = "", val syntax: Boolean = false)

    /**
     * Reads evaluateJavascript's answer for window.__blaze: a JSON-encoded string holding the JSON result, or "null"
     * / "\"\"" while the page is still drawing (→ null).
     */
    fun parseResult(evaluated: String?): Result? {
        val raw = evaluated?.trim().orEmpty()
        if (raw.isEmpty() || raw == "null" || raw == "undefined" || raw == "\"\"") return null
        return try {
            val inner = JSONTokener(raw).nextValue() as? String ?: return null
            if (inner.isBlank()) return null
            val o = JSONObject(inner)
            if (o.optBoolean("ok")) Result(true, o.optString("png"), o.optInt("w"), o.optInt("h"))
            else Result(false, error = o.optString("error"), syntax = o.optBoolean("syntax"))
        } catch (_: Exception) { Result(false, error = "unreadable result") }
    }

    /** Graphviz, PlantUML, D2 as people call them. */
    fun label(kind: String): String = when (kind) {
        "graphviz" -> "Graphviz"; "plantuml" -> "PlantUML"; "d2" -> "D2"; "mermaid" -> "Mermaid"
        else -> kind.replaceFirstChar { it.uppercase() }
    }

    enum class Why { PRIVATE_REMOTE_ONLY, SYNTAX, TIMEOUT, OFFLINE, TOO_LONG, FAILED }

    /** The one plain line shown instead of a picture (never the source; that's behind "Show code"). */
    fun errorLine(kind: String, why: Why): String = when (why) {
        Why.PRIVATE_REMOTE_ONLY -> "${label(kind)} diagrams can't be drawn on the phone, and a private chat doesn't send them out."
        Why.SYNTAX -> "Couldn't draw this diagram: its code has a mistake."
        Why.TIMEOUT -> "Couldn't draw this diagram: it took too long."
        Why.OFFLINE -> "Couldn't draw this diagram without a connection."
        Why.TOO_LONG -> "This diagram is too big to draw on the phone."
        Why.FAILED -> "Couldn't draw this diagram."
    }
}
