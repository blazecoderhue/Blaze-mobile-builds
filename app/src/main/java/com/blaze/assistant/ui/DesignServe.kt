package com.blaze.assistant.ui

import java.io.File

/**
 * v0.23.3: how the Design view serves designs so 3D always draws, even on the low-end phone (PowerVR GE8320,
 * where Chromium's GPU blocklist often turns WebGL2 off and three.js r163+ refuses to start).
 *
 *  - Designs load from a virtual https origin ([ORIGIN]) instead of file://, so the page, its importmap and
 *    the bundled three.js modules are all same-origin (no opaque "null" origin, no CORS):
 *      /designs/…  → the designs folder in Blaze's home (~/designs)
 *      /home/…     → anything else in Blaze's home
 *      /three/…    → three.js r170 bundled in assets/design/three (WebGL2)
 *      /three/r162/… → three.js r162 bundled in assets/design/three-r162 (the last build with WebGL1)
 *    CDN three.js URLs (jsDelivr, unpkg, cdnjs…) are still served from the APK as a fallback ([bundledThree]).
 *  - Every HTML page served gets [HOOK_JS] first in <head>: it catches errors (window.onerror, failed
 *    module/script loads, unhandled rejections), checks WebGL2/WebGL1, picks the three.js build, and
 *    reports gl / error / ready / fail to the Design view through the `BlazeDesign` bridge.
 *  - A three.js importmap is rewritten so the page imports the chosen same-origin build ([prepareHtml]), through
 *    the safety net (v0.23.5, assets/design/three-safe.js: null geometry / material objects are fixed and hidden
 *    instead of killing the scene), and a 3D page's parts are normalised ([com.blaze.assistant.agent.Designs.normalizeSpec]).
 *
 * Pure (no Android classes) so the mapping, rewriting and fallback choice are unit-tested (DesignRenderTest).
 */
object DesignServe {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val R170 = "r170"
    const val R162 = "r162"
    /** How long a 3D page may take to report ready before the Design view falls back (the test phone parses slowly). */
    const val READY_TIMEOUT_MS = 20_000L
    /** After the first error on a 3D page that isn't ready yet, wait this long before falling back. */
    const val ERROR_GRACE_MS = 2_000L
    const val ERROR_FILE = ".last_error.txt"
    /** v0.23.5: what a three.js importmap's "three" points at: the bundled build wrapped with the safety net. */
    const val SAFE_JS = "three.safe.js"
    const val SAFE_ASSET = "design/three-safe.js"

    // ---------------- URL mapping ----------------

    private fun enc(seg: String): String = java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
    private fun dec(seg: String): String = try { java.net.URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { seg }

    /** The virtual https URL for a file:// design URL under [home] (designs first), or [fileUrl] unchanged. */
    fun virtualUrl(fileUrl: String, home: File?): String {
        if (home == null || !fileUrl.startsWith("file://")) return fileUrl
        val f = File(fileUrl.removePrefix("file://"))
        val path = try { f.canonicalFile } catch (_: Exception) { return fileUrl }
        fun under(root: File): String? = try {
            val r = root.canonicalFile
            val rel = path.relativeToOrNull(r)?.path?.takeIf { !it.startsWith("..") && it.isNotEmpty() }
            rel?.split(File.separatorChar)?.joinToString("/") { enc(it) }
        } catch (_: Exception) { null }
        under(File(home, "designs"))?.let { return "$ORIGIN/designs/$it" }
        under(home)?.let { return "$ORIGIN/home/$it" }
        return fileUrl
    }

    /**
     * v0.28.2: the URL the Design view loads. Like [virtualUrl], and a file:// page outside Blaze's home is served
     * from the virtual origin too (/ext/<name>, with its own folder as the root, see [extRoot]), so the WebView
     * never needs file access. A file inside the app's private data ([appData]) but outside Blaze's home is
     * not served (404).
     */
    fun servedUrl(fileUrl: String, home: File?): String {
        val v = virtualUrl(fileUrl, home)
        if (!v.startsWith("file://")) return v
        val f = File(v.removePrefix("file://"))
        return "$ORIGIN/ext/" + enc(f.name)
    }

    /** The folder a /ext/ page (and its images, scripts) is served from, or null (not a file, or in the app's private data). */
    fun extRoot(fileUrl: String, home: File?, appData: File? = null): File? {
        if (!fileUrl.startsWith("file://") || !virtualUrl(fileUrl, home).startsWith("file://")) return null
        val f = try { File(fileUrl.removePrefix("file://")).canonicalFile } catch (_: Exception) { return null }
        if (appData != null) {
            val d = try { appData.canonicalPath } catch (_: Exception) { return null }
            if (f.path == d || f.path.startsWith(d + File.separator)) return null
        }
        return f.parentFile
    }

    /** "scheme://host[:port]" of [url] (lowercase), or "" for data:, about:, file: and anything unparsable. */
    fun originOf(url: String): String {
        val m = Regex("^(https?)://([^/?#]+)", RegexOption.IGNORE_CASE).find(url.trim()) ?: return ""
        val scheme = m.groupValues[1].lowercase()
        val auth = m.groupValues[2].substringAfterLast('@').lowercase()
        val port = Regex(":(\\d+)$").find(auth)?.groupValues?.get(1)
        val host = if (port != null) auth.removeSuffix(":$port") else auth
        val def = if (scheme == "https") "443" else "80"
        return "$scheme://$host" + if (port != null && port != def) ":$port" else ""
    }

    /**
     * v0.28.2: may the Design view's top frame go to [next]? Only within the origin it was opened on ([loaded],
     * normally [ORIGIN]); everything else is held, and offered as "Open in browser" when the user tapped a link.
     */
    fun allowNavigation(loaded: String, next: String): Boolean {
        val o = originOf(loaded)
        return o.isNotEmpty() && o == originOf(next)
    }

    /** Caps what a page may hand the [DesignBridge] (it only updates the view's state). */
    fun bridgeText(s: String?, max: Int = 500): String = (s ?: "").take(max)

    /** What a request to the virtual origin maps to: a file in Blaze's home or an APK asset. */
    sealed class Target {
        abstract val mime: String
        data class Disk(val file: File, override val mime: String) : Target()
        data class Asset(val path: String, override val mime: String) : Target()
    }

    /** Maps [url] to a [Target], or null when it isn't ours (other origins pass through to the network). */
    fun resolve(url: String, home: File?, ext: File? = null): Target? {
        val clean = url.substringBefore('#').substringBefore('?')
        if (!clean.startsWith("$ORIGIN/", ignoreCase = true)) return null
        val path = clean.substring(ORIGIN.length)
        return when {
            path.startsWith("/three/") -> threeAsset(path.removePrefix("/three/"))?.let { Target.Asset(it, "text/javascript") }
            // v0.26.3: the bundled mermaid.js (offline diagrams).
            path == "/diagram/mermaid.min.js" -> Target.Asset("diagram/mermaid.min.js", "text/javascript")
            path.startsWith("/designs/") -> home?.let { disk(File(home, "designs"), path.removePrefix("/designs/")) }
            path.startsWith("/home/") -> home?.let { disk(home, path.removePrefix("/home/")) }
            path.startsWith("/ext/") -> ext?.let { disk(ext, path.removePrefix("/ext/")) }
            else -> null
        }
    }

    private fun disk(root: File, rel: String): Target.Disk? {
        val parts = rel.split('/').filter { it.isNotEmpty() }.map { dec(it) }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." || it.contains('/') || it.contains('\\') }) return null
        var f = File(root, parts.joinToString(File.separator))
        val ok = try { f.canonicalPath.startsWith(root.canonicalPath + File.separator) || f.canonicalPath == root.canonicalPath } catch (_: Exception) { false }
        if (!ok) return null
        if (f.isDirectory) f = File(f, "index.html")
        return Target.Disk(f, mimeFor(f.name))
    }

    /** The APK asset for a /three/… path ("three.module.js", "addons/controls/OrbitControls.js", "r162/…"). */
    fun threeAsset(rest: String): String? {
        val r162 = rest.startsWith("$R162/")
        val tail = if (r162) rest.removePrefix("$R162/") else rest.removePrefix("$R170/")
        val name = when {
            Regex("^(build/)?three\\.module(\\.min)?\\.js$").matches(tail) -> "three.module.min.js"
            // v0.23.5: the safety net (null geometry / material can't stop a scene); one file for both builds,
            // it imports ./three.module.min.js next to the URL it was loaded from.
            tail == SAFE_JS -> return SAFE_ASSET
            tail.endsWith("/OrbitControls.js") || tail == "OrbitControls.js" -> "OrbitControls.js"
            tail.endsWith("/GLTFExporter.js") || tail == "GLTFExporter.js" -> "GLTFExporter.js"
            r162 && (tail.endsWith("/TextureUtils.js") || tail == "TextureUtils.js") -> "TextureUtils.js"
            else -> return null
        }
        return assetDir(if (r162) R162 else R170) + "/" + name
    }

    /** Where a bundled three.js build lives in the APK. */
    fun assetDir(engine: String): String = if (engine == R162) "design/three-r162" else "design/three"

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "glb" -> "model/gltf-binary"
        "gltf" -> "model/gltf+json"
        "wasm" -> "application/wasm"
        "txt", "md" -> "text/plain"
        "mmd", "mermaid" -> "text/plain"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    // ---------------- engine choice + fallback ----------------

    /** The three.js build for this WebView: r170 needs WebGL2; r162 still runs on WebGL1; null = no WebGL at all. */
    fun engineFor(gl2: Boolean, gl1: Boolean, forceGl1: Boolean = false): String? = when {
        gl2 && !forceGl1 -> R170
        gl1 -> R162
        gl2 -> R170
        else -> null
    }

    enum class Next { RETRY_WEBGL1, SKETCH, BANNER }

    /**
     * What to do when a 3D page fails: try the WebGL1 build once (when WebGL1 exists and it wasn't already
     * used), then the native drawing of the parts when the design has a parts list, else a banner over the page.
     */
    fun onFail(engine: String?, gl1: Boolean, hasParts: Boolean): Next = when {
        engine != R162 && gl1 -> Next.RETRY_WEBGL1
        hasParts -> Next.SKETCH
        else -> Next.BANNER
    }

    /** The URL to load for an attempt (the WebGL1 retry adds bzgl=1, which [HOOK_JS] reads). */
    fun attemptUrl(url: String, forceGl1: Boolean): String =
        if (!forceGl1 || !url.startsWith(ORIGIN)) url else url + (if (url.contains('?')) "&" else "?") + "bzgl=1"

    /** A console message that means the 3D engine failed rather than noise. */
    fun fatalConsole(msg: String): Boolean =
        Regex("(?i)failed to load module script|failed to resolve module specifier|shader error|error creating webgl context|webgl context lost|webgl2? (is )?not supported|cannot use import statement")
            .containsMatchIn(msg)

    // ---------------- HTML rewriting ----------------

    private val importMap = Regex("""<script\s+type\s*=\s*["']importmap["'][^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)

    /** Rewrites three.js entries of an importmap to the same-origin bundled [engine]; other entries stay. */
    fun rewriteImportMap(json: String, engine: String): String {
        val base = "$ORIGIN/three/" + (if (engine == R162) "$R162/" else "")
        return Regex("""("[^"\\]*(?:\\.[^"\\]*)*")\s*:\s*"([^"\\]*)"""").replace(json) { m ->
            val key = m.groupValues[1]; val v = m.groupValues[2]
            val asset = bundledThree(v)
            val nv = when {
                asset == "three.module.min.js" -> base + SAFE_JS
                asset != null -> base + "addons/" + v.substringAfter("/examples/jsm/", v.substringAfter("/addons/"))
                v.endsWith("/") && isThreeCdn(v) && (v.endsWith("/examples/jsm/") || v.endsWith("/addons/")) -> base + "addons/"
                else -> v
            }
            "$key: \"$nv\""
        }
    }

    private fun isThreeCdn(u: String) =
        Regex("^https?://(cdn\\.jsdelivr\\.net|unpkg\\.com|cdnjs\\.cloudflare\\.com|esm\\.sh|ga\\.jspm\\.io)/", RegexOption.IGNORE_CASE).containsMatchIn(u) &&
            u.contains("three", ignoreCase = true)

    /** True when [html] uses three.js through an importmap. */
    fun usesThree(html: String): Boolean = importMap.find(html)?.groupValues?.get(1)?.let { j ->
        Regex("\"([^\"]+)\"").findAll(j).any { isThreeCdn(it.groupValues[1]) }
    } == true

    /** A JavaScript string literal for [s], safe inside an inline <script>. */
    fun jsString(s: String): String {
        val b = StringBuilder("\"")
        for (ch in s) {
            val rep = when (ch) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                '<' -> "\\u003c"
                '>' -> "\\u003e"
                '\u2028' -> "\\u2028"
                '\u2029' -> "\\u2029"
                else -> null
            }
            if (rep != null) b.append(rep) else b.append(ch)
        }
        return b.append('"').toString()
    }

    /**
     * The page as the Design view serves it: [HOOK_JS] first in <head>, the old in-page "didn't load" banner hidden
     * (the Design view shows its own, with the real error and Retry), and a three.js importmap replaced by one
     * that is chosen at load time (r170 with WebGL2, r162 with only WebGL1) and points at the same origin.
     * [is3d] = a parts-based 3D design (expects window.__blazeScene).
     */
    fun prepareHtml(html: String, is3d: Boolean): String {
        var out = html
        // v0.23.5: the parts every renderer gets are normalised (known shapes, unique ids, objects only).
        if (is3d) com.blaze.assistant.agent.Designs.specFrom(out)?.let { out = com.blaze.assistant.agent.Designs.withSpec(out, com.blaze.assistant.agent.Designs.normalizeSpec(it)) }
        importMap.find(out)?.let { m ->
            val json = m.groupValues[1]
            if (usesThree(out)) {
                val tag = { e: String -> "<script type=\"importmap\">" + rewriteImportMap(json, e) + "</script>" }
                val chooser = "<script>document.write(window.__bzEngine===\"$R162\"?" + jsString(tag(R162)) + ":" + jsString(tag(R170)) + ");</script>"
                out = out.replaceRange(m.range, chooser)
            }
        }
        val gl = is3d || usesThree(html)
        val hook = "<script>" + HOOK_JS.replace("__BZ_IS3D__", if (is3d) "true" else "false").replace("__BZ_GL__", if (gl) "true" else "false") + "</script>" +
            "<style>#bz-fail{display:none!important}</style>"
        val head = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(out)
        val html0 = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(out)
        return when {
            head != null -> out.substring(0, head.range.last + 1) + hook + out.substring(head.range.last + 1)
            html0 != null -> out.substring(0, html0.range.last + 1) + "<head>" + hook + "</head>" + out.substring(html0.range.last + 1)
            else -> hook + out
        }
    }

    /**
     * Injected first into every page the Design view serves. Talks to the `BlazeDesign` bridge:
     * gl(level, hasWebGL1, engine), error(msg), ready(engine), fail(msg). The engine choice mirrors [engineFor].
     */
    val HOOK_JS = """
(function(){
var B=window.BlazeDesign||null, IS3D=__BZ_IS3D__, GL=__BZ_GL__;
var errs=window.__bzErrors=window.__bzErrors||[];
function say(k,a,b,c){try{if(B&&B[k]){if(c!==undefined)B[k](a,b,c);else if(b!==undefined)B[k](a,b);else B[k](a===undefined?"":String(a).slice(0,600));}}catch(e){}}
function err(m){m=String(m||"Unknown error");if(errs.length<20)errs.push(m);say("error",m);}
addEventListener("error",function(e){var t=e.target;
 if(t&&t!==window&&t.tagName){err("Couldn't load "+(t.src||t.href||t.tagName));return;}
 err((e.message||"Script error")+(e.filename?" ("+String(e.filename).split("/").pop()+":"+e.lineno+")":""));},true);
addEventListener("unhandledrejection",function(e){var r=e.reason;err("Unhandled: "+((r&&(r.message||r))||"promise rejected"));});
function has(n){try{var c=document.createElement("canvas");var g=c.getContext(n);if(g){var l=g.getExtension("WEBGL_lose_context");if(l)l.loseContext();return true;}}catch(e){}return false;}
var gl2=has("webgl2"), gl1=has("webgl")||has("experimental-webgl");
var force1=/[?&]bzgl=1/.test(location.search);
var eng=(gl2&&!force1)?"r170":(gl1?"r162":(gl2?"r170":""));
window.__bzEngine=eng;
say("gl",gl2?2:(gl1?1:0),gl1,eng);
if(!IS3D&&!GL){addEventListener("load",function(){setTimeout(function(){say("loaded","page");},300);});return;}
if(!eng){say("fail","WebGL is turned off in this phone's WebView (no WebGL 1 or 2), so three.js can't draw.");return;}
function blank(cv){if(!cv||!cv.width||!cv.height)return true;var s=24,c=document.createElement("canvas");c.width=s;c.height=s;
 var x=c.getContext("2d");x.drawImage(cv,0,0,s,s);var d=x.getImageData(0,0,s,s).data,r=d[0],g=d[1],b=d[2],n=0;
 for(var i=0;i<d.length;i+=4){if(Math.abs(d[i]-r)>10||Math.abs(d[i+1]-g)>10||Math.abs(d[i+2]-b)>10)n++;}return n<3;}
window.__bzBlank=blank;
/* v0.45.6: frame a box (min, max) for a perspective camera; mirrored by DesignServe.fit (DesignRenderTest). */
function fit(mn,mx,fovDeg,aspect){var c=[(mn[0]+mx[0])/2,(mn[1]+mx[1])/2,(mn[2]+mx[2])/2],dx=mx[0]-mn[0],dy=mx[1]-mn[1],dz=mx[2]-mn[2];
 var rad=Math.max(1e-6,0.5*Math.sqrt(dx*dx+dy*dy+dz*dz)),vf=fovDeg*Math.PI/180,hf=2*Math.atan(Math.tan(vf/2)*Math.max(aspect,1e-3)),f=Math.min(vf,hf);
 var dist=rad/Math.sin(f/2)*1.15,dir=[1.6,1.1,1.9],n=Math.sqrt(1.6*1.6+1.1*1.1+1.9*1.9);
 return {c:c,r:rad,dist:dist,near:dist/100,far:dist*100,p:[c[0]+dir[0]/n*dist,c[1]+dir[1]/n*dist,c[2]+dir[2]/n*dist]};}
window.__bzFit=fit;
/* v0.45.6: a scene that drew nothing gets a visible size, default lights, a neutral environment and background, and a camera framed on it. */
function rescue(){var T=window.__bzTHREE,bs=window.__blazeScene||{},r=bs.renderer||window.__bzR,sc=bs.scene||window.__bzS,cam=bs.camera||window.__bzC,ctl=bs.controls||null;
 if(!T||!r||!sc||!cam)return false;var did=[];
 try{var cv=r.domElement;if(!cv.clientWidth||cv.clientHeight<2){cv.style.position="fixed";cv.style.left="0";cv.style.top="0";cv.style.width="100vw";cv.style.height="100vh";cv.style.display="block";did.push("size");}
  var w=Math.max(cv.clientWidth||innerWidth||1,1),h=Math.max(cv.clientHeight||innerHeight||1,1);r.setSize(w,h,false);if(cam.isPerspectiveCamera){cam.aspect=w/h;}}catch(e){}
 try{var lights=0;sc.traverse(function(o){if(o.isLight)lights++;});
  if(!lights){sc.add(new T.HemisphereLight(0xffffff,0x444444,1.2));var k=new T.DirectionalLight(0xffffff,2.0);k.position.set(3,5,4);sc.add(k);sc.add(new T.AmbientLight(0xffffff,0.35));did.push("lights");}}catch(e){}
 try{if(!sc.environment&&T.PMREMGenerator){var pm=new T.PMREMGenerator(r),es=new T.Scene();es.background=new T.Color(0x9a9a9a);sc.environment=pm.fromScene(es,0.04).texture;did.push("env");}}catch(e){}
 try{if(!sc.background){sc.background=new T.Color(matchMedia("(prefers-color-scheme: dark)").matches?0x1d1e21:0xf3f2ef);did.push("bg");}}catch(e){}
 try{var box=new T.Box3();sc.traverse(function(o){if((o.isMesh||o.isPoints||o.isLine)&&o.visible!==false&&!o.isGridHelper&&!(o.material&&o.material.isShadowMaterial)&&!(o.geometry&&o.geometry.type==="PlaneGeometry"&&o.receiveShadow))box.expandByObject(o);});
  if(!box.isEmpty()&&isFinite(box.min.x)&&isFinite(box.max.x)){var F=fit([box.min.x,box.min.y,box.min.z],[box.max.x,box.max.y,box.max.z],cam.fov||45,cam.aspect||1);
   if(cam.isPerspectiveCamera){cam.near=F.near;cam.far=F.far;}cam.position.set(F.p[0],F.p[1],F.p[2]);cam.lookAt(F.c[0],F.c[1],F.c[2]);cam.updateProjectionMatrix&&cam.updateProjectionMatrix();
   if(ctl&&ctl.target){ctl.target.set(F.c[0],F.c[1],F.c[2]);ctl.update&&ctl.update();}did.push("fit");}}catch(e){}
 window.__bzRescued=did.join(",");
 try{window.__bzWantSample=true;window.__bzSampleBlank=undefined;r.render(sc,cam);}catch(e){}
 return did.length>0;}
window.__bzRescue=rescue;
var t0=Date.now();
var poll=setInterval(function(){var s=window.__blazeScene,r=(s&&s.renderer)||window.__bzR;
 if(!r){if(Date.now()-t0>15000){clearInterval(poll);say("fail",IS3D?"The 3D page never set up its scene ("+eng+").":"three.js never started drawing on this page ("+eng+").");}return;}
 clearInterval(poll);
 var cv=r.domElement;
 if(cv)cv.addEventListener("webglcontextlost",function(){err("WebGL context lost");say("fail","The 3D view lost its WebGL context ("+eng+").");});
 var keep=false;try{var g=cv&&(cv.getContext("webgl2")||cv.getContext("webgl"));var at=g&&g.getContextAttributes&&g.getContextAttributes();keep=!!(at&&at.preserveDrawingBuffer);}catch(e){}
 var tries=0,blanks=0,rescued=false;(function check(){window.__bzWantSample=true;setTimeout(function(){
  var w=window,frames=w.__bzFrames||0,calls=w.__bzDrawCalls||0,sb=w.__bzSampleBlank,drew;
  if(cv&&(!cv.width||!cv.height||cv.clientHeight<2))drew=false;
  else if(sb===false)drew=true;
  else if(sb===true)drew=false;
  else if(frames===0&&w.__bzFrames!==undefined)drew=false;
  else if(w.__bzFrames===undefined){var bl=true;if(keep){try{bl=blank(cv);}catch(e){}}drew=!bl;}
  else drew=calls>0;
  tries++;
  if(drew){w.__bzReady=true;say("ready",eng+(rescued?"+fit":""));return;}
  blanks++;
  if(!rescued&&(blanks>=3||(cv&&cv.clientHeight<2))){rescued=true;blanks=0;rescue();check();return;}
  if(tries<10&&!(rescued&&blanks>=3))check();
  else say("fail",frames>0?"The 3D scene ("+eng+") rendered but nothing was visible, even after centring the camera and adding lights.":"The 3D engine ("+eng+") started but the scene never rendered a frame.");},1200);})();},100);
})();
""".trim()

    // ---------------- v0.45.6: framing and the never-blank pane ----------------

    /** Where a perspective camera goes to show a box: centre, bounding-sphere radius, distance, near / far, position. */
    data class Frame(val cx: Double, val cy: Double, val cz: Double, val radius: Double, val dist: Double, val near: Double, val far: Double,
                     val px: Double, val py: Double, val pz: Double)

    /**
     * Fits [min]..[max] in a camera with vertical field of view [fovDeg] and [aspect] (width / height): the bounding sphere fills
     * the narrower of the two fields with 15 % margin, viewed from the starter's three-quarter direction, orbiting the centre.
     * Mirrors fit() in [HOOK_JS] (DesignRenderTest runs both).
     */
    fun fit(min: DoubleArray, max: DoubleArray, fovDeg: Double, aspect: Double): Frame {
        val c = DoubleArray(3) { (min[it] + max[it]) / 2 }
        val dx = max[0] - min[0]; val dy = max[1] - min[1]; val dz = max[2] - min[2]
        val rad = maxOf(1e-6, 0.5 * Math.sqrt(dx * dx + dy * dy + dz * dz))
        val vf = Math.toRadians(fovDeg); val hf = 2 * Math.atan(Math.tan(vf / 2) * maxOf(aspect, 1e-3))
        val f = minOf(vf, hf)
        val dist = rad / Math.sin(f / 2) * 1.15
        val n = Math.sqrt(1.6 * 1.6 + 1.1 * 1.1 + 1.9 * 1.9)
        return Frame(c[0], c[1], c[2], rad, dist, dist / 100, dist * 100, c[0] + 1.6 / n * dist, c[1] + 1.1 / n * dist, c[2] + 1.9 / n * dist)
    }

    /** True when every corner of the box projects inside the camera's view (used by tests: a fitted model is fully on screen). */
    fun inView(min: DoubleArray, max: DoubleArray, fovDeg: Double, aspect: Double, f: Frame): Boolean {
        val fwd = doubleArrayOf(f.cx - f.px, f.cy - f.py, f.cz - f.pz).let { v -> val l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); DoubleArray(3) { v[it] / l } }
        val up0 = doubleArrayOf(0.0, 1.0, 0.0)
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun norm(v: DoubleArray) = Math.sqrt(v.sumOf { it * it }).let { l -> DoubleArray(3) { v[it] / l } }
        val right = norm(cross(fwd, up0)); val up = cross(right, fwd)
        val tv = Math.tan(Math.toRadians(fovDeg) / 2); val th = tv * aspect
        for (i in 0 until 8) {
            val p = doubleArrayOf(if (i and 1 == 0) min[0] else max[0], if (i and 2 == 0) min[1] else max[1], if (i and 4 == 0) min[2] else max[2])
            val d = doubleArrayOf(p[0] - f.px, p[1] - f.py, p[2] - f.pz)
            val z = d[0] * fwd[0] + d[1] * fwd[1] + d[2] * fwd[2]
            if (z < f.near || z > f.far) return false
            val x = (d[0] * right[0] + d[1] * right[1] + d[2] * right[2]) / z
            val y = (d[0] * up[0] + d[1] * up[1] + d[2] * up[2]) / z
            if (Math.abs(x) > th || Math.abs(y) > tv) return false
        }
        return true
    }

    /** What the Design stage shows for a page: never a blank pane. */
    enum class Pane { EMPTY, LIVE, LIVE_WITH_ERROR, SKETCH_WITH_ERROR, SKETCH }

    /**
     * [glPage] = 3D or three.js page (it must report that it drew); [hasParts] = a parts-based 3D design (can be drawn natively);
     * [preview] = WebGL previews allowed; [failure] = the reported failure, if any. A failed GL page always shows the error card.
     */
    fun pane(url: String, glPage: Boolean, hasParts: Boolean, preview: Boolean, failure: String?): Pane = when {
        url.isBlank() -> Pane.EMPTY
        hasParts && failure != null -> Pane.SKETCH_WITH_ERROR
        hasParts && !preview -> Pane.SKETCH
        !preview && glPage -> Pane.LIVE_WITH_ERROR
        failure != null -> Pane.LIVE_WITH_ERROR
        else -> Pane.LIVE
    }

    /** The error card's text when WebGL is missing or a load failed (shown over the page or under the simple drawing). */
    fun failText(error: String): String = "The 3D view couldn't draw this: " + error.take(220)

    // ---------------- error file ----------------

    /** The text of ~/designs/.last_error.txt: when, which design, which engine, and the last errors (newest last). */
    fun errorReport(design: String, engine: String?, glLevel: Int, errors: List<String>, at: Long): String {
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))
        return "Design view render error ($time)\n" +
            "design: $design\n" +
            "engine: ${engine?.ifBlank { null } ?: "none"} (WebGL ${if (glLevel <= 0) "unavailable" else glLevel.toString()})\n" +
            errors.takeLast(10).joinToString("\n") { "- " + it.replace('\n', ' ').take(500) } + "\n"
    }

    // ---------------- Mermaid files (v0.26.1) ----------------
    // Jack's phone: a .mmd file opened in Design showed its raw "flowchart TD / Sun[Sunlight] -->…" source as text on a
    // white page. A Mermaid file (by extension, or text that starts like Mermaid) is now drawn as a picture instead.

    val MERMAID_EXT = setOf("mmd", "mermaid")
    private val mermaidStart = Regex("""^\s*(?:%%[^\n]*\n\s*)*(?:---\n[\s\S]*?\n---\s*\n\s*)?""" +
        """(?:flowchart\b|graph\s+(?:TB|TD|BT|RL|LR)\b|sequenceDiagram\b|classDiagram(?:-v2)?\b|stateDiagram(?:-v2)?\b|erDiagram\b|journey\b|gantt\b|""" +
        """pie(?:\s+(?:title|showData)\b|\s*\n)|mindmap\b|timeline\b|gitGraph\b|quadrantChart\b|requirementDiagram\b|C4(?:Context|Container|Component)\b|""" +
        """sankey-beta\b|xychart-beta\b|block-beta\b)""")
    private val mdFence = Regex("""^\s*```\s*mermaid\s*\n([\s\S]*?)\n\s*```\s*$""")

    /** Does [text] read as Mermaid source (it starts with a diagram keyword such as flowchart, graph TD, sequenceDiagram, mindmap…)? */
    fun looksMermaid(text: String): Boolean = mermaidStart.containsMatchIn(text.take(4000))

    /**
     * The Mermaid source to draw for a previewed file, or null when it isn't Mermaid: a .mmd / .mermaid file; a .md
     * that is just one ```mermaid block; or a .txt / extension-less file whose text starts like Mermaid. HTML, SVG,
     * pictures and the rest are never touched.
     */
    fun mermaidSource(name: String, text: String): String? {
        val ext = name.substringAfterLast('/').let { if (it.contains('.')) it.substringAfterLast('.').lowercase() else "" }
        val t = text.replace("\r\n", "\n").trim()
        if (t.isEmpty()) return null
        return when (ext) {
            in MERMAID_EXT -> mdFence.matchEntire(t)?.groupValues?.get(1)?.trim() ?: t
            "md", "markdown" -> mdFence.matchEntire(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: t.takeIf { looksMermaid(it) }
            "txt", "" -> t.takeIf { looksMermaid(it) }
            else -> null
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * A page that draws [code] as a picture. v0.26.3: with the bundled mermaid.js (same origin, /diagram/mermaid.min.js),
     * so it works offline and the text stays on the phone; only if that can't draw it (not a mistake in the code) does
     * it fall back to the mermaid.ink image. The source is an escaped JS string ([DiagramRender.jsString]), never shown
     * as text; when nothing can draw it the page says so in one plain line.
     */
    fun mermaidHtml(code: String, title: String = "Diagram"): String {
        val img = RichReplies.diagramUrl("mermaid", code)
        val l = DiagramRender.look(false)
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>" + esc(title) + "</title><style>html,body{margin:0;background:#fff;font-family:sans-serif}" +
            "#wrap{display:flex;align-items:center;justify-content:center;min-height:100vh;padding:16px;box-sizing:border-box}" +
            "#d{display:none;max-width:100%;height:auto;border-radius:12px}#msg{display:none;color:#5a5a5e;font-size:15px;text-align:center}" +
            "#out svg{max-width:100%;height:auto}</style>" +
            "<script src=\"" + ORIGIN + "/diagram/mermaid.min.js\"></script></head><body><div id=\"wrap\">" +
            "<div id=\"out\"></div><img id=\"d\" alt=\"" + esc(title) + "\" data-src=\"" + esc(img) + "\" onerror=\"blazeMsg('Couldn\u2019t draw this diagram without a connection.')\">" +
            "<p id=\"msg\"></p></div>" +
            "<script>function blazeMsg(t){var m=document.getElementById('msg');m.textContent=t;m.style.display='block';document.getElementById('d').style.display='none';}" +
            "function blazeWeb(){var d=document.getElementById('d');d.src=d.getAttribute('data-src');d.style.display='block';}" +
            "(function(){var code=" + DiagramRender.jsString(code) + ";" +
            "if(typeof mermaid==='undefined'){blazeWeb();return;}" +
            "try{mermaid.initialize(" + DiagramRender.config(l) + ");" +
            "mermaid.parse(code).then(function(){return mermaid.render('blazeM',code).then(function(r){document.getElementById('out').innerHTML=r.svg;},function(){blazeWeb();});}," +
            "function(){blazeMsg('Couldn\u2019t draw this diagram: its code has a mistake.');});}catch(e){blazeWeb();}})();</script></body></html>"
    }
}
