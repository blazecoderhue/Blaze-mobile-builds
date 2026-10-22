package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.45.6 P1 (user 21:35: Design -> 3d-rocket -> 3D tab was blank): the camera is framed on the model's bounds, lights and a
 * neutral environment are added when a scene drew nothing, and a failed or WebGL-less view shows an error card, never a
 * blank pane. WebGL can't run on the JVM, so this checks the framing math (and that the page's JS does the same math),
 * the hook's rescue and pixel check, and the pane decision.
 */
class Design3dViewTest {
    private fun d(vararg v: Double) = v

    @Test fun framingFitsAnyScaleAndOffset() {
        // A rocket in mm read as metres (2 km tall, far from the origin), a tiny part, and a wide flat model, on phone and landscape aspects.
        val boxes = listOf(d(-150.0, 0.0, -150.0) to d(150.0, 2000.0, 150.0), d(5000.0, 5000.0, 5000.0) to d(5000.2, 5000.5, 5000.1),
            d(-0.01, -0.01, -0.01) to d(0.01, 0.03, 0.01), d(-40.0, 0.0, -2.0) to d(40.0, 1.0, 2.0))
        for ((mn, mx) in boxes) for (aspect in listOf(0.45, 1.0, 2.2)) {
            val f = DesignServe.fit(mn, mx, 40.0, aspect)
            assertTrue("in view: ${mn.toList()} ${mx.toList()} @ $aspect", DesignServe.inView(mn, mx, 40.0, aspect, f))
            assertEquals((mn[1] + mx[1]) / 2, f.cy, 1e-9)            // orbits around the centre
            assertTrue(f.near < f.dist - f.radius && f.far > f.dist + f.radius)   // never clipped by near / far
        }
    }

    @Test fun theOldFixedFarPlaneClippedAMetresRocket() {
        // The starter used near 0.01 / far 200: a model ~2000 units across sat ~4000 away, past the far plane.
        val f = DesignServe.fit(d(-150.0, 0.0, -150.0), d(150.0, 2000.0, 150.0), 40.0, 0.5)
        assertTrue(f.dist > 200)
        assertTrue(f.far > f.dist + f.radius)
        val starter = File("src/main/assets/skills/three-d-design/assets/mockup.html").takeIf { it.exists() } ?: File("app/src/main/assets/skills/three-d-design/assets/mockup.html")
        if (starter.exists()) assertTrue(starter.readText().contains("camera.far = Math.max(r * 100, 200)"))
    }

    @Test fun pageJavaScriptFramesExactlyLikeKotlin() {
        val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() } ?: return
        val hook = DesignServe.HOOK_JS
        val fitJs = Regex("""function fit\(mn,mx,fovDeg,aspect\)\{[\s\S]*?return \{[^}]*\};\}""").find(hook)!!.value
        val js = File.createTempFile("fit", ".js").apply { writeText(fitJs + "\nconsole.log(JSON.stringify(fit([-150,0,-150],[150,2000,150],40,0.45)));") }
        val p = ProcessBuilder(node, js.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim(); assertEquals(out, 0, p.waitFor()); js.delete()
        val k = DesignServe.fit(d(-150.0, 0.0, -150.0), d(150.0, 2000.0, 150.0), 40.0, 0.45)
        fun num(key: String) = Regex("\"$key\":([-0-9.eE]+)").find(out)!!.groupValues[1].toDouble()
        assertEquals(k.dist, num("dist"), 1e-6); assertEquals(k.near, num("near"), 1e-6); assertEquals(k.far, num("far"), 1e-3)
        val p0 = Regex("\"p\":\\[([-0-9.eE]+),([-0-9.eE]+),([-0-9.eE]+)\\]").find(out)!!.groupValues
        assertEquals(k.px, p0[1].toDouble(), 1e-6); assertEquals(k.py, p0[2].toDouble(), 1e-6); assertEquals(k.pz, p0[3].toDouble(), 1e-6)
    }

    @Test fun hookRescuesABlankSceneAndOnlyCallsItReadyOnPixels() {
        val h = DesignServe.HOOK_JS
        assertTrue(h.contains("HemisphereLight") && h.contains("DirectionalLight") && h.contains("PMREMGenerator"))   // default lights + neutral environment
        assertTrue(h.contains("cv.style.height=\"100vh\""))                    // a zero-height canvas gets a size
        assertTrue(h.contains("ctl.target.set(F.c[0],F.c[1],F.c[2])"))       // orbit around the model's centre
        assertTrue(h.contains("if(sb===false)drew=true") && h.contains("else if(sb===true)drew=false"))   // pixels decide before draw calls
        assertTrue(h.contains("even after centring the camera and adding lights"))
        val safe = File("src/main/assets/design/three-safe.js").takeIf { it.exists() } ?: File("app/src/main/assets/design/three-safe.js")
        if (safe.exists()) safe.readText().let { assertTrue(it.contains("w.__bzS = scene") && it.contains("window.__bzTHREE = T")) }
    }

    @Test fun threePagesAreCheckedToo() {
        val page = "<html><head><script type=\"importmap\">{\"imports\":{\"three\":\"https://cdn.jsdelivr.net/npm/three@0.170.0/build/three.module.js\"}}</script></head><body></body></html>"
        val out = DesignServe.prepareHtml(page, is3d = false)
        assertTrue(out.contains("IS3D=false, GL=true"))
        assertTrue(DesignServe.prepareHtml("<html><head></head><body>hi</body></html>", false).contains("GL=false"))
    }

    @Test fun webglMissingShowsTheErrorCardNeverABlankPane() {
        assertTrue(DesignServe.HOOK_JS.contains("if(!eng){say(\"fail\",\"WebGL is turned off"))
        // The hook's fail reaches DesignPreview.fail -> onFail: parts are drawn natively with the card, other pages get the card over them.
        assertEquals(DesignServe.Next.SKETCH, DesignServe.onFail(null, gl1 = false, hasParts = true))
        assertEquals(DesignServe.Next.BANNER, DesignServe.onFail(null, gl1 = false, hasParts = false))
        assertEquals(DesignServe.Pane.SKETCH_WITH_ERROR, DesignServe.pane("u", glPage = true, hasParts = true, preview = true, failure = "WebGL is turned off"))
        assertEquals(DesignServe.Pane.LIVE_WITH_ERROR, DesignServe.pane("u", glPage = true, hasParts = false, preview = true, failure = "WebGL is turned off"))
        assertEquals(DesignServe.Pane.LIVE_WITH_ERROR, DesignServe.pane("u", glPage = true, hasParts = false, preview = false, failure = null))
        assertEquals(DesignServe.Pane.LIVE, DesignServe.pane("u", glPage = true, hasParts = true, preview = true, failure = null))
        assertEquals(DesignServe.Pane.EMPTY, DesignServe.pane("", glPage = false, hasParts = false, preview = true, failure = null))
        assertTrue(DesignServe.failText("x").startsWith("The 3D view couldn't draw this: x"))
    }

    @Test fun chipsFitTheArtifactAndDesignIsBeta() {
        val model = studioSuggestions(StudioMode.PAGE, artifactOf(hasFile = true, studioPage = false, is3d = true, usesThree = false))
        assertFalse(model.contains("Make the headline bigger"))
        assertFalse(studioSuggestions(StudioMode.THREE_D, Artifact.MODEL_3D).any { it.contains("halo") || it.contains("orb") })
        assertFalse(studioSuggestions(StudioMode.PAGE, Artifact.THREE_PAGE).contains("Make the headline bigger"))
        assertTrue(studioSuggestions(StudioMode.PAGE, Artifact.STUDIO_PAGE).contains("Make the headline bigger"))
        assertEquals(Artifact.THREE_PAGE, artifactOf(true, false, false, true))
        assertEquals("BETA", DESIGN_BETA)
        assertTrue(STAGE_INSET_DP >= 48)   // live pages sit below the Phone / Desktop and version pills
    }
}
