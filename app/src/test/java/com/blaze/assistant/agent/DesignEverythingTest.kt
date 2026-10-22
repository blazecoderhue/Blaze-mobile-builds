package com.blaze.assistant.agent

import com.blaze.assistant.ui.PartsSketch
import com.blaze.assistant.ui.bundledThree
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

/** v0.23.2 "design everything": routing, follow-on edits, the design tool, the claim guard and token stripping. */
class DesignEverythingTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("designs").toFile()
        Designs.rootOverride = dir
        Designs.templateLoader = { File("src/main/assets/skills/three-d-design/assets/mockup.html").readText() }
    }

    @After fun tearDown() { Designs.rootOverride = null; dir.deleteRecursively() }

    // ---------------- routing ----------------

    @Test fun threeDAndVisualRequestsGoToDesign() {
        for (t in listOf("make me a tall modern 3D rocket", "can you build a 3d model of a house", "render a car", "design a mobile app UI for a bakery",
            // v0.26.1: plain diagram / chart asks are drawn inline in chat (VisualRouteTest); file asks still go to Design.
            "make a diagram of the water cycle and save it as a PNG", "draw a flowchart of the login process on a poster", "export a chart of my spending by category", "show me a 3D scene of a room",
            "build a rocket", "create a floor plan for a 2 bedroom house", "mockup of a product box", "make a prototype of a todo app", "make a poster for the show"))
            assertEquals(t, "design", ViewRouter.route(t)?.id)
    }

    @Test fun nonDesignStaysWhereItWas() {
        assertEquals("gallery", ViewRouter.route("generate an image of a sunset over Lusaka")?.id)
        assertEquals("gallery", ViewRouter.route("make a picture of a red car")?.id)
        assertEquals("schedule", ViewRouter.route("remind me to service my car every week")?.id)
        assertNull(ViewRouter.route("what's the best car to buy in Zambia?"))
        assertNull(ViewRouter.route("switch to a faster model"))
        assertNull(ViewRouter.route("show engine"))   // not without a design just before
    }

    @Test fun followOnEditsGoToDesign() {
        for (t in listOf("show engine", "add fins", "make it taller", "open it for me so I can see", "now make it red", "can you hide the boosters?", "rotate it"))
            assertEquals(t, "design", ViewRouter.route(t, followOn = true)?.id)
        // A different request after a design is still itself.
        assertNull(ViewRouter.route("what's the weather today?", followOn = true))
        assertNull(ViewRouter.route("show my calendar", followOn = true))
        assertEquals("schedule", ViewRouter.route("remind me at 6 to check it", followOn = true)?.id)
        assertFalse(ViewRouter.isFollowOnEdit("tell me a long story about rockets and why they fly so high into space and beyond the moon"))
        val h = ViewRouter.followOnHint("Tall rocket", "3d", "/x/index.html")
        assertTrue(h.contains("design action=update") && h.contains("set_parts") && h.contains("Tall rocket"))
    }

    @Test fun promptAndToolSayDesignIsForEverything() {
        val p = ViewRouter.PROMPT
        assertTrue(p.contains("3D") && p.contains("diagrams") && p.contains("design action=update"))
        assertTrue(p.contains("Never say you created, updated or opened"))
        val d = Designs.tool().getJSONObject("function").getString("description")
        for (w in listOf("3D", "UI mockups", "diagrams", "charts", "posters", "slides", "floor plans", "follow-on")) assertTrue(w, d.contains(w))
        assertTrue(ViewRouter.hint(ViewRouter.byId("design")!!).contains("kind=3d"))
        assertTrue(Designs.TOOL in ViewRouter.byId("design")!!.tools)
    }

    // ---------------- the design tool ----------------

    private fun rocketParts() = JSONArray()
        .put(JSONObject().put("id", "body").put("label", "Body").put("shape", "cylinder").put("radius", 0.6).put("height", 8).put("position", JSONArray(listOf(0, 5, 0))).put("color", "#e8e8ea").put("material", "metal"))
        .put(JSONObject().put("id", "nose").put("shape", "cone").put("radius", 0.6).put("height", 2).put("position", JSONArray(listOf(0, 10, 0))).put("color", "#d33"))
        .put(JSONObject().put("id", "fins").put("shape", "group"))
        .put(JSONObject().put("id", "fin-1").put("parent", "fins").put("shape", "box").put("size", JSONArray(listOf(0.1, 1.6, 1.2))).put("position", JSONArray(listOf(0.7, 1.6, 0))).put("color", "#d33"))

    @Test fun create3dThenFollowOnEditsUpdateTheSameDesign() {
        val c = Designs.run(JSONObject().put("action", "create").put("kind", "3d").put("title", "Tall modern rocket").put("units", "m").put("parts", rocketParts()), now = 1000)
        assertTrue(c.text, c.ok)
        val f = File(c.open!!)
        assertTrue(f.isFile)
        val html = f.readText()
        assertTrue(html.contains("OrbitControls") && html.contains("__blazeScene") && html.contains("bz-fail"))
        assertTrue(html.contains("<title>Tall modern rocket</title>"))
        val spec = Designs.specFrom(html)!!
        assertEquals(1.0, spec.getDouble("scale"), 0.0)
        assertEquals(4, spec.getJSONArray("parts").length())
        assertEquals("Tall modern rocket", Designs.current()!!.title)

        // "show engine": add parts to the same design.
        val u = Designs.run(JSONObject().put("action", "update").put("set_parts", JSONArray()
            .put(JSONObject().put("id", "engine").put("shape", "cylinder").put("radius", 0.5).put("radiusTop", 0.3).put("height", 1).put("position", JSONArray(listOf(0, 0.5, 0))).put("color", "#333"))), now = 2000)
        assertTrue(u.text, u.ok)
        assertEquals(f.absolutePath, u.open)
        val s2 = Designs.specFrom(f.readText())!!
        assertEquals(5, s2.getJSONArray("parts").length())
        assertTrue(u.text.contains("added engine"))
        assertEquals(2, Designs.current()!!.version)

        // "make it taller": change only the body's height.
        val t = Designs.run(JSONObject().put("action", "update").put("set_parts", JSONArray().put(JSONObject().put("id", "body").put("height", 12))))
        assertTrue(t.ok)
        val body = (0 until 5).map { Designs.specFrom(f.readText())!!.getJSONArray("parts").getJSONObject(it) }.first { it.getString("id") == "body" }
        assertEquals(12, body.getInt("height")); assertEquals("#e8e8ea", body.getString("color"))

        // Removing a group removes its children; undo goes back.
        val r = Designs.run(JSONObject().put("action", "update").put("remove_parts", JSONArray().put("fins")))
        assertTrue(r.text.contains("removed") && r.text.contains("fin-1"))
        assertEquals(3, Designs.specFrom(f.readText())!!.getJSONArray("parts").length())
        assertTrue(Designs.run(JSONObject().put("action", "undo")).ok)
        assertEquals(5, Designs.specFrom(f.readText())!!.getJSONArray("parts").length())

        // An update that changes nothing is an error (so it can't back a claim).
        assertFalse(Designs.run(JSONObject().put("action", "update").put("set_parts", JSONArray())).ok)
        // "open it" shows the current design.
        assertEquals(f.absolutePath, Designs.run(JSONObject().put("action", "open")).open)
    }

    @Test fun pagesSvgAndEdits() {
        val p = Designs.run(JSONObject().put("action", "create").put("kind", "ui").put("title", "Bakery app").put("html", "<h1>Fresh bread</h1><button>Order</button>"))
        assertTrue(p.ok)
        val f = File(p.open!!)
        assertTrue(f.readText().contains("<meta name=\"viewport\""))
        val e = Designs.run(JSONObject().put("action", "update").put("edits", JSONArray().put(JSONObject().put("find", "Fresh bread").put("replace", "Hot bread"))))
        assertTrue(e.ok); assertTrue(f.readText().contains("Hot bread"))
        val bad = Designs.run(JSONObject().put("action", "update").put("edits", JSONArray().put(JSONObject().put("find", "nope").put("replace", "x"))))
        assertFalse(bad.ok); assertTrue(bad.text.contains("not found"))
        val s = Designs.run(JSONObject().put("action", "create").put("title", "Logo sketch").put("svg", "<svg viewBox=\"0 0 10 10\"><circle cx=\"5\" cy=\"5\" r=\"4\"/></svg>"))
        assertTrue(s.ok); assertEquals("svg", Designs.current()!!.kind)
        assertFalse(Designs.run(JSONObject().put("action", "create").put("kind", "3d").put("title", "x")).ok)
        assertTrue(Designs.list().contains("(current)"))
    }

    @Test fun unknownShapesAreDrawnAsBoxesWithANote() {
        val (parts, problems) = Designs.cleanParts(JSONArray().put(JSONObject().put("label", "Wing").put("shape", "wing")))
        assertEquals("box", parts.getJSONObject(0).getString("shape"))
        assertEquals("wing", parts.getJSONObject(0).getString("id"))
        assertEquals(1, problems.size)
    }

    @Test fun threeJsIsServedFromTheApk() {
        assertEquals("three.module.min.js", bundledThree("https://cdn.jsdelivr.net/npm/three@0.170.0/build/three.module.js"))
        assertEquals("three.module.min.js", bundledThree("https://unpkg.com/three@0.160.0/build/three.module.min.js"))
        assertEquals("OrbitControls.js", bundledThree("https://cdn.jsdelivr.net/npm/three@0.170.0/examples/jsm/controls/OrbitControls.js"))
        assertEquals("GLTFExporter.js", bundledThree("https://cdn.jsdelivr.net/npm/three@0.170.0/examples/jsm/exporters/GLTFExporter.js"))
        assertNull(bundledThree("https://cdn.jsdelivr.net/npm/chart.js"))
        assertNull(bundledThree("https://example.com/three/build/three.module.js"))
        for (n in listOf("three.module.min.js", "OrbitControls.js", "GLTFExporter.js")) assertTrue(n, File("src/main/assets/design/three/$n").length() > 10_000)
    }

    @Test fun partsSketchDrawsTheModel() {
        val spec = Designs.spec3d("Rocket", Designs.cleanParts(rocketParts()).first, "m")
        val faces = PartsSketch.faces(spec, 360f, 600f)
        assertTrue(faces.size > 40)
        assertTrue(faces.all { f -> f.pts.all { it.first in -1f..361f && it.second in -1f..601f } })
        // Hidden parts aren't drawn.
        spec.getJSONArray("parts").getJSONObject(0).put("visible", false)
        assertTrue(PartsSketch.faces(spec, 360f, 600f).size < faces.size)
    }

    // ---------------- claim guard ----------------

    @Test fun claimsWithoutABackingToolAreCaught() {
        val design = ViewRouter.byId("design")!!
        val lies = listOf(
            "I'm on that tall modern 3D rocket! You can view it in the design section.",
            "I've updated the design to show the engine.",
            "I've opened the design view so you can see it.",
            "Done! I've added fins to your rocket model.",
            "Here's your updated 3D rocket.",
        )
        for (l in lies) assertEquals(l, "design", ClaimGuard.unbacked(l, emptyList())?.id)
        // A tool that succeeded backs the claim.
        for (l in lies) assertNull(l, ClaimGuard.unbacked(l, listOf(ClaimGuard.Call("design", JSONObject().put("action", "update")))))
        assertNull(ClaimGuard.unbacked(lies[2], listOf(ClaimGuard.Call("open_view", JSONObject().put("view", "design")))))
        assertNull(ClaimGuard.unbacked(lies[1], listOf(ClaimGuard.Call("file_write", JSONObject().put("path", "~/rocket/index.html")))))
        // open_view of another view, or a .py file, does not.
        assertEquals("design", ClaimGuard.unbacked(lies[1], listOf(ClaimGuard.Call("open_view", JSONObject().put("view", "learn"))))?.id)
        assertEquals("design", ClaimGuard.unbacked(lies[1], listOf(ClaimGuard.Call("file_write", JSONObject().put("path", "~/a.py"))))?.id)
        assertTrue(ClaimGuard.backs(design, ClaimGuard.Call("design")))
    }

    @Test fun claimGuardCoversEveryView() {
        assertEquals("learn", ClaimGuard.unbacked("I've made a deck of 12 flashcards for you.", emptyList())?.id)
        assertEquals("schedule", ClaimGuard.unbacked("I've set up a reminder for 6 pm.", emptyList())?.id)
        assertEquals("gallery", ClaimGuard.unbacked("I've generated the image, it's in your gallery.", emptyList())?.id)
        assertEquals("skills", ClaimGuard.unbacked("I've saved this as a skill.", emptyList())?.id)
        assertNull(ClaimGuard.unbacked("I've set up a reminder for 6 pm.", listOf(ClaimGuard.Call("create_routine"))))
        assertNull(ClaimGuard.unbacked("I've made a deck of 12 flashcards for you.", listOf(ClaimGuard.Call("flashcards"))))
        // Every routed view except read-only Archive has claim words.
        for (s in ViewRouter.specs) if (s.id != "archive") assertTrue(s.id, ClaimGuard.claims("I've updated the ${s.name.lowercase()}.", s.id).isNotEmpty() || s.id in setOf("browser", "memory"))
    }

    @Test fun honestTextIsNotAClaim() {
        for (t in listOf("I couldn't update the design this time.", "I'll build the 3D rocket in Design now.", "Want me to make a 3D model of it?",
            "Once it's ready you can view it in the design section.", "I updated the design earlier, as you asked yesterday.",
            "I've switched to a faster model.", "Rockets fly because of thrust.", "I've opened the website in the browser."))
            assertNull(t, ClaimGuard.unbacked(t, emptyList())?.takeIf { it.id == "design" })
        assertTrue(ClaimGuard.succeeded("Created the 3d design"))
        assertFalse(ClaimGuard.succeeded("Error: there's no current design"))
        assertFalse(ClaimGuard.succeeded("The user did NOT approve"))
    }

    @Test fun nudgeThenHonestLine() {
        val d = ViewRouter.byId("design")!!
        val n = ClaimGuard.nudge(d, Designs.Current("/x/index.html", "3d", "Rocket", 2, 0))
        assertTrue(n.contains("design action=update") && n.contains("Rocket"))
        assertTrue(ClaimGuard.nudge(d, null).contains("design action=create"))
        val h = ClaimGuard.honest("I've updated the design to show the engine.", d)
        assertTrue(h.startsWith("I didn't manage to build or change it in Design"))
        assertFalse(h.contains("I've updated"))
        assertNull(ClaimGuard.unbacked(h, emptyList()))
        val keep = ClaimGuard.honest("The engine sits at the base under the fins, with three nozzles. I've updated the design to show it.", d)
        assertTrue(keep.contains("three nozzles") && !keep.contains("I've updated"))
    }

    // ---------------- leaked tokens ----------------

    @Test fun stripsGemmaChannelTokens() {
        val raw = "<|channel>thought\n<channel|>I'm on that tall modern 3D rocket!"
        assertEquals("I'm on that tall modern 3D rocket!", ReplyParts.split(raw, final = true).text)
        val r2 = ReplyClean.clean("<|channel>thought\nThe user wants a rocket. Use the design tool.<channel|>Here it is.")
        assertEquals("Here it is.", r2.text)
        assertEquals("The user wants a rocket. Use the design tool.", r2.thinking)
        assertEquals("Hello", ReplyParts.split("<|channel|>thought\n<|channel|>Hello", final = true).text)
    }

    @Test fun stripsHarmonyAnalysis() {
        val raw = "<|channel|>analysis<|message|>Need to call design tool.<|end|><|start|>assistant<|channel|>final<|message|>Done, it's open in Design."
        val p = ReplyParts.split(raw, final = true)
        assertEquals("Done, it's open in Design.", p.text)
        assertTrue(p.thinking.contains("Need to call design tool."))
        // With a zero-width space inside the token, as it sometimes arrives.
        assertEquals("Hi there", ReplyParts.split("<\u200B|channel|>analysis<|message|>thinking…<|end|>Hi there", final = true).text)
    }

    @Test fun stripsThinkTagsAndBareLeads() {
        assertEquals("Answer", ReplyParts.split("<think>plan it</think>Answer", final = true).text)
        val lone = ReplyParts.split("I should build it first.</think>Built it.", final = true)
        assertEquals("Built it.", lone.text); assertTrue(lone.thinking.contains("I should build it first."))
        assertEquals("Sure, here's the plan.", ReplyParts.split("thought\nSure, here's the plan.", final = true).text)
        assertEquals("Thought you'd like this.", ReplyParts.split("Thought you'd like this.", final = true).text)
        assertEquals("Hi!", ReplyParts.split("<start_of_turn>model\nHi!<end_of_turn>", final = true).text)
        assertEquals("Hey", ReplyParts.split("<|im_start|>assistant\nHey<|im_end|>", final = true).text)
        // Ordinary text and HTML are left alone.
        assertEquals("Use a <div> and 3 < 5.", ReplyParts.split("Use a <div> and 3 < 5.", final = true).text)
    }

    @Test fun streamingNeverFlashesTokens() {
        for (partial in listOf("<|chan", "<|channel>", "<|channel>thou", "<|channel>thought\nThe user wants", "thou", "<|channel|>analysis<|message|>Need to"))
            assertEquals(partial, "", ReplyParts.split(partial).text)
        assertEquals("I'm on", ReplyParts.split("<|channel>thought\n<channel|>I'm on").text)
        assertEquals("Hello wor", ReplyParts.split("Hello wor").text)
    }
}
