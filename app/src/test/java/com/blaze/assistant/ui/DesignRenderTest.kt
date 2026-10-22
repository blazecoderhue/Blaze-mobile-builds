package com.blaze.assistant.ui

import com.blaze.assistant.agent.Designs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v0.23.3: 3D designs always render: virtual-origin URL mapping, importmap rewriting, error hook and fallback choice. */
class DesignRenderTest {

    private lateinit var home: File
    private val O = DesignServe.ORIGIN
    private val template get() = File("src/main/assets/skills/three-d-design/assets/mockup.html").readText()

    @Before fun setUp() {
        home = Files.createTempDirectory("home").toFile()
        Designs.rootOverride = File(home, "designs")
        Designs.templateLoader = { template }
    }

    @After fun tearDown() { Designs.rootOverride = null; home.deleteRecursively() }

    private fun rocket(): JSONObject {
        val parts = JSONArray()
            .put(JSONObject().put("id", "body").put("shape", "cylinder").put("radius", 0.6).put("height", 7).put("position", JSONArray(listOf(0, 4.5, 0))))
            .put(JSONObject().put("id", "nose").put("shape", "cone").put("radius", 0.6).put("height", 2).put("position", JSONArray(listOf(0, 9, 0))))
        return Designs.spec3d("Simple Rocket", Designs.cleanParts(parts).first, "m")
    }

    // ---------------- URL mapping ----------------

    @Test fun designsLoadFromTheVirtualOrigin() {
        val f = File(home, "designs/simple-rocket/index.html").apply { parentFile!!.mkdirs(); writeText("<html></html>") }
        assertEquals("$O/designs/simple-rocket/index.html", DesignServe.virtualUrl("file://" + f.absolutePath, home))
        val other = File(home, "site/my page.html").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals("$O/home/site/my%20page.html", DesignServe.virtualUrl("file://" + other.absolutePath, home))
        // Outside Blaze's home, web URLs and no home: unchanged (file:// fallback with the CDN interception).
        assertEquals("file:///sdcard/x.html", DesignServe.virtualUrl("file:///sdcard/x.html", home))
        assertEquals("https://example.com/", DesignServe.virtualUrl("https://example.com/", home))
        assertEquals("file://" + f.absolutePath, DesignServe.virtualUrl("file://" + f.absolutePath, null))
        // designUrl → virtualUrl for what the tool passes (~/designs/<name>/index.html).
        assertEquals("$O/designs/simple-rocket/index.html", DesignServe.virtualUrl(designUrl("~/designs/simple-rocket/index.html", home), home))
    }

    @Test fun virtualUrlsMapBackToFiles() {
        val f = File(home, "designs/simple-rocket/index.html").apply { parentFile!!.mkdirs(); writeText("<html></html>") }
        val t = DesignServe.resolve("$O/designs/simple-rocket/index.html?bzgl=1", home) as DesignServe.Target.Disk
        assertEquals(f.canonicalPath, t.file.canonicalPath); assertEquals("text/html", t.mime)
        // A directory serves its index.html.
        assertEquals(f.canonicalPath, (DesignServe.resolve("$O/designs/simple-rocket/", home) as DesignServe.Target.Disk).file.canonicalPath)
        val other = File(home, "site/my page.html").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals(other.canonicalPath, (DesignServe.resolve("$O/home/site/my%20page.html", home) as DesignServe.Target.Disk).file.canonicalPath)
        assertEquals("model/gltf-binary", (DesignServe.resolve("$O/designs/a/model.glb", home) as DesignServe.Target.Disk).mime)
        // No way out of Blaze's home.
        assertNull(DesignServe.resolve("$O/designs/../../etc/passwd", home))
        assertNull(DesignServe.resolve("$O/designs/%2e%2e/%2e%2e/etc/passwd", home))
        assertNull(DesignServe.resolve("$O/home/a%2F..%2F..%2Fx", home))
        // Not ours: the network has it.
        assertNull(DesignServe.resolve("https://example.com/designs/x.html", home))
        assertNull(DesignServe.resolve("$O/designs/x.html", null))
    }

    @Test fun threeIsServedFromTheApkForBothBuilds() {
        fun asset(u: String) = (DesignServe.resolve(u, home) as? DesignServe.Target.Asset)?.also { assertEquals("text/javascript", it.mime) }?.path
        assertEquals("design/three/three.module.min.js", asset("$O/three/three.module.min.js"))
        assertEquals("design/three/three.module.min.js", asset("$O/three/build/three.module.js"))
        assertEquals("design/three/OrbitControls.js", asset("$O/three/addons/controls/OrbitControls.js"))
        assertEquals("design/three/GLTFExporter.js", asset("$O/three/addons/exporters/GLTFExporter.js"))
        assertEquals("design/three-r162/three.module.min.js", asset("$O/three/r162/three.module.min.js"))
        assertEquals("design/three-r162/OrbitControls.js", asset("$O/three/r162/addons/controls/OrbitControls.js"))
        // r162's GLTFExporter imports ./../utils/TextureUtils.js relative to its own URL.
        assertEquals("design/three-r162/TextureUtils.js", asset("$O/three/r162/addons/utils/TextureUtils.js"))
        assertNull(asset("$O/three/addons/utils/TextureUtils.js"))
        assertNull(asset("$O/three/addons/loaders/GLTFLoader.js"))
        // Every file the mapping can name is really in the APK, and both builds say which revision they are.
        listOf("three", "three-r162").forEach { d ->
            listOf("three.module.min.js", "OrbitControls.js", "GLTFExporter.js", "LICENSE").forEach { assertTrue("$d/$it", File("src/main/assets/design/$d/$it").isFile) }
        }
        assertTrue(File("src/main/assets/design/three-r162/TextureUtils.js").isFile)
        assertTrue(File("src/main/assets/design/three/three.module.min.js").readText().take(400).contains("\"170\""))
        val r162 = File("src/main/assets/design/three-r162/three.module.min.js").readText()
        assertTrue(r162.take(400).contains("\"162\""))
        assertTrue("r162 still falls back to WebGL1", r162.contains("experimental-webgl"))
        // CDN fallback still maps r162's TextureUtils import.
        assertEquals("TextureUtils.js", bundledThree("https://cdn.jsdelivr.net/npm/three@0.162.0/examples/jsm/utils/TextureUtils.js"))
    }

    // ---------------- importmap rewriting ----------------

    @Test fun importMapPointsAtTheSameOriginBuild() {
        val json = Regex("""<script type="importmap">([\s\S]*?)</script>""").find(template)!!.groupValues[1]
        val r170 = JSONObject(DesignServe.rewriteImportMap(json, DesignServe.R170)).getJSONObject("imports")
        assertEquals("$O/three/three.safe.js", r170.getString("three"))
        assertEquals("$O/three/addons/", r170.getString("three/addons/"))
        val r162 = JSONObject(DesignServe.rewriteImportMap(json, DesignServe.R162)).getJSONObject("imports")
        assertEquals("$O/three/r162/three.safe.js", r162.getString("three"))
        assertEquals("$O/three/r162/addons/", r162.getString("three/addons/"))
        // Explicit addon files and other libraries.
        val m = """{ "imports": { "three": "https://unpkg.com/three@0.160.0/build/three.module.min.js",
            "three/examples/jsm/controls/OrbitControls.js": "https://unpkg.com/three@0.160.0/examples/jsm/controls/OrbitControls.js",
            "chart": "https://cdn.jsdelivr.net/npm/chart.js" } }"""
        val out = JSONObject(DesignServe.rewriteImportMap(m, DesignServe.R162)).getJSONObject("imports")
        assertEquals("$O/three/r162/three.safe.js", out.getString("three"))
        assertEquals("$O/three/r162/addons/controls/OrbitControls.js", out.getString("three/examples/jsm/controls/OrbitControls.js"))
        assertEquals("https://cdn.jsdelivr.net/npm/chart.js", out.getString("chart"))
        // Each rewritten URL resolves to a bundled asset.
        listOf(r170.getString("three"), r162.getString("three"), r162.getString("three/addons/") + "controls/OrbitControls.js",
            r170.getString("three/addons/") + "exporters/GLTFExporter.js").forEach { assertNotNull(it, DesignServe.resolve(it, home)) }
    }

    @Test fun servedPagesGetTheHookAndAChosenImportMap() {
        val page = Designs.render3d(template, rocket())
        val out = DesignServe.prepareHtml(page, is3d = true)
        val hookAt = out.indexOf("window.BlazeDesign")
        assertTrue(hookAt in 0 until out.indexOf("id=\"blaze-parts\""))
        assertTrue("hook sits right after <head>", out.indexOf("<head>") < hookAt && hookAt < out.indexOf("<title>"))
        assertTrue(out.contains("IS3D=true"))
        assertFalse("the static CDN importmap is gone", out.contains("<script type=\"importmap\">"))
        val chooser = Regex("""<script>document\.write\(window\.__bzEngine==="r162"\?("(?:[^"\\]|\\.)*"):("(?:[^"\\]|\\.)*")\);</script>""").find(out)
        assertNotNull("importmap chooser", chooser)
        // The JS strings can't close the inline script early.
        assertFalse(chooser!!.groupValues[1].contains("</") || chooser.groupValues[2].contains("<"))
        assertTrue(chooser.groupValues[1].contains("$O/three/r162/three.safe.js"))
        assertTrue(chooser.groupValues[2].contains("$O/three/three.safe.js"))
        assertTrue("chooser comes before the module script", out.indexOf("document.write") < out.indexOf("<script type=\"module\">"))
        assertTrue("old in-page banner hidden in the app", out.contains("#bz-fail{display:none!important}"))
        // The parts spec is untouched, so the native fallback and the tool still read it.
        assertEquals(Designs.specFrom(page).toString(), Designs.specFrom(out).toString())
        // Pages that aren't 3D still get error capture, without the ready/fallback logic.
        val plain = DesignServe.prepareHtml("<!doctype html><html><head><title>x</title></head><body>hi</body></html>", is3d = false)
        assertTrue(plain.contains("IS3D=false") && plain.contains("<body>hi</body>"))
        assertTrue(DesignServe.prepareHtml("<p>fragment</p>", false).startsWith("<script>"))
        assertTrue(DesignServe.prepareHtml("<html><body>x</body></html>", false).startsWith("<html><head><script>"))
        assertEquals("\"a\\u003c/script\\u003e\\\"b\\n\"", DesignServe.jsString("a</script>\"b\n"))
    }

    @Test fun hookJavaScriptParses() {
        // Checked by node when it's on the machine (CI runners and the dev sandbox have it).
        val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() } ?: return
        val js = File.createTempFile("hook", ".js").apply { writeText(DesignServe.HOOK_JS.replace("__BZ_IS3D__", "true")) }
        val p = ProcessBuilder(node, "--check", js.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        js.delete()
    }

    @Test fun drawCheckReadsFramesNotACleared() {
        // v0.36.1: the old check sampled the WebGL canvas from a timer, after the browser had cleared it, so every
        // 3D design "drew nothing". It now reads the draw calls three-safe.js records and a sample taken in the frame.
        val hook = DesignServe.HOOK_JS
        assertTrue(hook.contains("__bzDrawCalls") && hook.contains("__bzWantSample") && hook.contains("preserveDrawingBuffer"))
        val safe = File("src/main/assets/design/three-safe.js").takeIf { it.exists() } ?: File("app/src/main/assets/design/three-safe.js")
        if (safe.exists()) { val s = safe.readText(); assertTrue(s.contains("noteFrame(this)") && s.contains("__bzSampleBlank")) }
    }

    // ---------------- fallback selection ----------------

    @Test fun engineAndFallbackChoice() {
        assertEquals("r170", DesignServe.engineFor(gl2 = true, gl1 = true))
        assertEquals("r162", DesignServe.engineFor(gl2 = false, gl1 = true))       // the test phone with WebGL2 blocklisted
        assertEquals("r162", DesignServe.engineFor(gl2 = true, gl1 = true, forceGl1 = true))
        assertEquals("r170", DesignServe.engineFor(gl2 = true, gl1 = false, forceGl1 = true))
        assertNull(DesignServe.engineFor(gl2 = false, gl1 = false))
        // r170 failed and WebGL1 exists: retry with r162 once.
        assertEquals(DesignServe.Next.RETRY_WEBGL1, DesignServe.onFail("r170", gl1 = true, hasParts = true))
        assertEquals(DesignServe.Next.RETRY_WEBGL1, DesignServe.onFail(null, gl1 = true, hasParts = false))
        // r162 failed too, or no WebGL: native drawing for parts designs, banner otherwise.
        assertEquals(DesignServe.Next.SKETCH, DesignServe.onFail("r162", gl1 = true, hasParts = true))
        assertEquals(DesignServe.Next.SKETCH, DesignServe.onFail("", gl1 = false, hasParts = true))
        assertEquals(DesignServe.Next.SKETCH, DesignServe.onFail(null, gl1 = false, hasParts = true))
        assertEquals(DesignServe.Next.BANNER, DesignServe.onFail("r162", gl1 = true, hasParts = false))
        // The retry URL.
        assertEquals("$O/designs/a/index.html?bzgl=1", DesignServe.attemptUrl("$O/designs/a/index.html", true))
        assertEquals("$O/designs/a/index.html", DesignServe.attemptUrl("$O/designs/a/index.html", false))
        assertEquals("file:///x.html", DesignServe.attemptUrl("file:///x.html", true))
        assertTrue(DesignServe.HOOK_JS.contains("bzgl=1"))
        assertTrue(DesignServe.READY_TIMEOUT_MS >= 20_000L)
        // Console errors that mean the engine is dead vs noise.
        assertTrue(DesignServe.fatalConsole("Failed to load module script: Expected a JavaScript module script but the server responded with a MIME type of \"text/html\"."))
        assertTrue(DesignServe.fatalConsole("THREE.WebGLProgram: Shader Error 0 - VALIDATE_STATUS false"))
        assertTrue(DesignServe.fatalConsole("THREE.WebGLRenderer: Error creating WebGL context."))
        assertFalse(DesignServe.fatalConsole("THREE.WebGLRenderer: WebGL 1 support was deprecated in r153 and will be removed in r163."))
        assertFalse(DesignServe.fatalConsole("favicon.ico 404"))
    }

    // ---------------- errors for the agent ----------------

    @Test fun errorsReachTheDesignTool() {
        val report = DesignServe.errorReport("/home/designs/simple-rocket/index.html", "r170", 1,
            listOf("THREE.WebGLRenderer: A WebGL 2 context could not be created", "second"), 0L)
        assertTrue(report.startsWith("Design view render error ("))
        assertTrue(report.contains("design: /home/designs/simple-rocket/index.html") && report.contains("engine: r170 (WebGL 1)"))
        assertTrue(report.contains("- THREE.WebGLRenderer: A WebGL 2 context could not be created\n- second"))
        assertTrue(DesignServe.errorReport("x", null, 0, emptyList(), 0L).contains("engine: none (WebGL unavailable)"))

        val made = Designs.run(JSONObject().put("action", "create").put("title", "Simple Rocket").put("parts", rocket().getJSONArray("parts")))
        assertTrue(made.ok); assertFalse(made.text.contains(".last_error.txt"))
        File(Designs.root(), DesignServe.ERROR_FILE).writeText(report)
        val opened = Designs.run(JSONObject().put("action", "open"))
        assertTrue(opened.ok)
        assertTrue(opened.text.contains("~/designs/.last_error.txt") && opened.text.contains("A WebGL 2 context could not be created"))
        assertFalse(Designs.run(JSONObject().put("action", "list")).text.contains(".last_error"))
        File(Designs.root(), DesignServe.ERROR_FILE).delete()
        assertNull(Designs.lastErrorNote())
    }

    @Test fun oldPagesUpgradeAndNewPagesWaitLonger() {
        val page = Designs.render3d(template, rocket())
        assertEquals(1, Regex("id=\"bz-fail\"").findAll(page).count())
        assertTrue(page.contains("20000") && !page.contains("}, 8000);"))
        // A v0.23.2 page (8 s timer) gets the new block on its next update, once.
        val v1 = page.replace(Regex("<!--bz-extra-->[\\s\\S]*?<!--/bz-extra-->\\n?"), "") .replace("</body>", """
<style>@media (max-width: 640px) { #panel { max-width: 46vw; font-size: 11px; padding: 6px 8px; } #panel table { font-size: 10px; } }
#bz-fail { position: fixed; inset: auto 12px 12px 12px; padding: 10px 12px; border-radius: 10px; background: #fff3e0; color: #6d3d00; font: 13px system-ui, sans-serif; display: none; }</style>
<div id="bz-fail">The 3D engine didn't load, so the scene can't be drawn. Check the connection and tap Reload.</div>
<script>setTimeout(function () { if (!window.__blazeScene) document.getElementById('bz-fail').style.display = 'block'; }, 8000);</script>
</body>""")
        assertTrue(v1.contains("8000"))
        val again = Designs.render3d(v1, rocket())
        assertEquals(1, Regex("id=\"bz-fail\"").findAll(again).count())
        assertTrue(again.contains("20000") && !again.contains("8000"))
        assertEquals(1, Regex("id=\"bz-fail\"").findAll(Designs.render3d(again, rocket())).count())
        // Old files still open: their parts are still read.
        assertNotNull(Designs.specFrom(v1))
    }

    // ---------------- native fallback ----------------

    @Test fun sketchTurnsAndZooms() {
        val v = SketchView()
        val d = v.drag(100f, 0f)
        assertEquals(v.yaw - 40.0, d.yaw, 1e-9)
        assertEquals(85.0, v.drag(0f, 10_000f).pitch, 1e-9)
        assertEquals(-85.0, v.drag(0f, -10_000f).pitch, 1e-9)
        assertEquals(6.0, v.pinch(100f).zoom, 1e-9)
        assertEquals(0.3, v.pinch(0.001f).zoom, 1e-9)
        val spec = rocket()
        fun width(z: Double) = PartsSketch.faces(spec, 360f, 600f, zoom = z).flatMap { f -> f.pts.map { it.first } }.let { it.max() - it.min() }
        assertTrue(width(2.0) > width(1.0) * 1.9)
        val turned = PartsSketch.faces(spec, 360f, 600f, yawDeg = 120.0, pitchDeg = 60.0)
        assertTrue(turned.isNotEmpty())
    }

    // ---------------- v0.23.5: bad objects can't stop a design ----------------

    private fun badRocket(): JSONObject = JSONObject().put("name", "Bad Rocket").put("units", "m").put("parts", JSONArray()
        .put(JSONObject().put("id", "body").put("shape", "Cylinder").put("radius", 0.6).put("height", 7))
        .put(JSONObject().put("id", "nose").put("shape", "nosecone").put("radius", 0.6).put("height", 2).put("position", JSONArray(listOf(0, 4.5, 0))))
        .put(JSONObject().put("id", "fin").put("shape", "wing-thing").put("size", JSONArray(listOf(0.1, 1.5, 1))))
        .put(JSONObject().put("id", "engine").put("type", "THREE.CylinderGeometry").put("radius", 0.4).put("height", 1))
        .put(JSONObject().put("id", "nose").put("shape", JSONObject.NULL))
        .put("not a part"))

    @Test fun shapeAliasesResolve() {
        fun s(shape: Any?, extra: JSONObject.() -> Unit = {}) = Designs.normShape(JSONObject().put("shape", shape ?: JSONObject.NULL).apply(extra))
        assertEquals("cylinder" to false, s("Cylinder"))
        assertEquals("cylinder" to false, s("CylinderGeometry"))
        assertEquals("cylinder" to false, s("THREE.CylinderGeometry"))
        assertEquals("cone" to false, s("nosecone"))
        assertEquals("box" to false, s("cube"))
        assertEquals("torus" to false, s("donut"))
        assertEquals("sphere" to false, s("Ball"))
        assertEquals("group" to false, s("group"))
        assertEquals("cylinder" to false, Designs.normShape(JSONObject().put("type", "tube")))
        // Unknown names are guessed from the dimensions and flagged.
        assertEquals("box" to true, s("wing"))
        assertEquals("cylinder" to true, s("rocketbody") { put("radius", 1).put("height", 2) })
        assertEquals("sphere" to true, s("blob") { put("radius", 1) })
        assertEquals("plane" to true, s("sheet") { put("size", JSONArray(listOf(1, 2))) })
        assertEquals("torus" to true, s("hoop") { put("radius", 1).put("tube", 0.1) })
        // No name at all: a guess, not a problem.
        assertEquals("box" to false, s(null))
        // Every alias maps to a shape the starter knows.
        assertTrue(Designs.shapeAliases.values.all { it in Designs.shapes })
    }

    @Test fun normalizeSpecMakesEveryPartDrawable() {
        val n = Designs.normalizeSpec(badRocket())
        val parts = n.getJSONArray("parts")
        assertEquals(5, parts.length())   // the string entry is dropped
        val ids = (0 until parts.length()).map { parts.getJSONObject(it).getString("id") }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals(listOf("cylinder", "cone", "box", "cylinder", "box"), (0 until parts.length()).map { parts.getJSONObject(it).getString("shape") })
        assertEquals("Bad Rocket", n.getString("name"))
        assertEquals(0.6, parts.getJSONObject(0).getDouble("radius"), 1e-9)
        // No parts array at all still gives one.
        assertEquals(0, Designs.normalizeSpec(JSONObject().put("name", "x")).getJSONArray("parts").length())
    }

    @Test fun stockStarterPagesUpgrade() {
        val old = Designs.render3d(template.replace("const CUSTOM = {", "/* OLD-STARTER */\nconst CUSTOM = {"), rocket())
        assertTrue(Designs.isStockStarter(old))
        val up = Designs.upgradeStarter(old, template)
        assertFalse(up.contains("OLD-STARTER"))
        assertEquals(Designs.specFrom(old).toString(), Designs.specFrom(up).toString())
        // Hand-written CUSTOM code, no template, or not a starter page: untouched.
        val custom = old.replace("const CUSTOM = {\n", "const CUSTOM = {\n  \"wing\": p => null,\n")
        assertFalse(Designs.isStockStarter(custom))
        assertEquals(custom, Designs.upgradeStarter(custom, template))
        assertEquals(old, Designs.upgradeStarter(old, null))
        assertEquals("<html>hi</html>", Designs.upgradeStarter("<html>hi</html>", template))
    }

    @Test fun sketchDrawsARocketWithBadShapeNames() {
        val spec = badRocket()
        val faces = PartsSketch.faces(spec, 360f, 600f)
        assertTrue("faces for a rocket with bad shape names", faces.size > 40)
        assertTrue(faces.all { f -> f.pts.all { it.first.isFinite() && it.second.isFinite() } })
        // Non-finite numbers are skipped, not allowed to break the fit.
        spec.getJSONArray("parts").getJSONObject(0).put("radius", 100).put("scale", JSONArray(listOf(1e308, 1e308, 1e308)))   // overflows to Infinity
        val rest = PartsSketch.faces(spec, 360f, 600f)
        assertTrue(rest.isNotEmpty())
        assertTrue(rest.all { f -> f.pts.all { it.first in -1f..361f && it.second in -1f..601f } })
        // Nothing drawable: no faces (the view shows NO_PARTS).
        assertTrue(PartsSketch.faces(JSONObject().put("parts", JSONArray()), 360f, 600f).isEmpty())
        assertTrue(PartsSketch.faces(JSONObject(), 360f, 600f).isEmpty())
        assertTrue(PartsSketch.NO_PARTS.isNotBlank())
    }

    @Test fun preparedPagesHaveCleanParts() {
        val page = Designs.withSpec(Designs.render3d(template, rocket()), badRocket())
        assertEquals("Cylinder", Designs.specFrom(page)!!.getJSONArray("parts").getJSONObject(0).getString("shape"))
        val out = DesignServe.prepareHtml(page, true)
        val parts = Designs.specFrom(out)!!.getJSONArray("parts")
        assertEquals(5, parts.length())
        assertTrue((0 until parts.length()).all { parts.getJSONObject(it).getString("shape") in Designs.shapes })
        // A non-3D page isn't touched by the parts cleaner.
        assertEquals(Designs.specFrom(page).toString(), Designs.specFrom(DesignServe.prepareHtml(page, false)).toString())
    }

    @Test fun safetyNetIsServedForBothBuilds() {
        assertEquals("design/three-safe.js", DesignServe.threeAsset("three.safe.js"))
        assertEquals("design/three-safe.js", DesignServe.threeAsset("r162/three.safe.js"))
        assertEquals(DesignServe.SAFE_ASSET, DesignServe.threeAsset(DesignServe.SAFE_JS))
        assertTrue(File("src/main/assets/design/three-safe.js").length() > 500)
    }
}

