package com.blaze.assistant.agent

import com.blaze.assistant.ui.DesignServe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.26.1 (Jack's phone): a diagram / chart asked for in chat is drawn inline in the reply, not as a file in the Design
 * preview; and a Mermaid file opened in Design is drawn as a picture, never shown as raw source.
 */
class VisualRouteTest {
    private val root = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }

    private val inChat = listOf("draw a flowchart of how solar panels work", "make a diagram of the water cycle", "draw a flowchart of the login process",
        "make a chart of my spending by category", "can you visualise how a bill becomes law", "mind map for my essay on climate change",
        "show me a pie chart of Zambia's exports", "map out the steps to register a business", "graph of copper prices over 5 years")

    @Test fun inChatVisualsStayInChat() {
        for (t in inChat) {
            assertTrue(t, VisualRoute.inChat(t))
            assertNull(t, ViewRouter.route(t))   // no REQUIRED VIEW: Design
        }
        assertFalse(VisualRoute.inChat("what's the capital of Zambia?"))
        assertFalse(VisualRoute.inChat("/design flowchart"))
    }

    @Test fun fileAsksStillGoToDesign() {
        for (t in listOf("make a flowchart of the login process and save it as a PNG", "export the diagram as svg", "make a diagram file I can download",
            "draw a flowchart on a poster for the class", "make a website with a chart of sales", "make a 3D chart of the solar system", "edit the diagram file"))
            assertFalse(t, VisualRoute.inChat(t))
        assertEquals("design", ViewRouter.route("make a flowchart of the login process and save it as a PNG")?.id)
        assertEquals("design", ViewRouter.route("make a poster with a diagram of the water cycle")?.id)
        assertEquals("design", ViewRouter.route("design a landing page with a chart")?.id)
        // Non-visual Design work is untouched.
        assertEquals("design", ViewRouter.route("make me a tall modern 3D rocket")?.id)
    }

    @Test fun skillMatchingDoesNotPushTheDiagramsSkill() {
        val cands = listOf("diagrams", "data-analysis", "tutor", "deep-research").mapNotNull { SkillMatch.fromDir(File(root, it), "bundled", true) }
        val q = "draw a flowchart of how solar panels work"
        val r = SkillMatch.match(q, VisualRoute.filter(q, cands))
        assertFalse(r.names.toString(), "diagrams" in r.names)
        assertTrue(VisualRoute.skipSkill(q, "diagrams")); assertTrue(VisualRoute.skipSkill("make a chart of my spending", "data-analysis"))
        // With data to analyse, or a file to make, the skills come back.
        assertFalse(VisualRoute.skipSkill("make a chart from this spreadsheet", "data-analysis"))
        val f = "make a flowchart and export it as svg"
        assertEquals(cands, VisualRoute.filter(f, cands))
        // The HINT for the turn says what to do.
        assertTrue(VisualRoute.HINT.contains("```mermaid") && VisualRoute.HINT.contains("use_skill"))
    }

    @Test fun bundledSkillsSayInChatFirst() {
        val d = File(root, "diagrams/SKILL.md").readText()
        val body = SkillMatch.body(d)
        assertTrue(body.trimStart().startsWith("> **In chat: just reply with a ```mermaid block.**"))
        assertTrue(SkillMatch.fromDir(File(root, "diagrams"), "bundled", true)!!.description.contains("only when the user asks to save"))
        assertTrue(File(root, "data-analysis/SKILL.md").readText().contains("In chat: a chart is a ```chart block"))
        val views = File(root, "blaze-views/SKILL.md").readText()
        assertTrue(CoreSkills.pinned(views).any { it.contains("```mermaid") && it.contains("no Design") })
        assertTrue(ViewRouter.PROMPT.contains("draw it inline in the reply"))
    }

    @Test fun mermaidFilesAreDetected() {
        val src = "flowchart TD\n  Sun[Sunlight] -->|Photons| Cell[Solar cells]\n  Cell --> DC[DC power]"
        assertEquals(src, DesignServe.mermaidSource("solar-panels.mmd", src))
        assertEquals(src, DesignServe.mermaidSource("solar.mermaid", "\n$src\n"))
        assertEquals(src, DesignServe.mermaidSource("notes.txt", src))
        assertEquals(src, DesignServe.mermaidSource("diagram", src))
        assertEquals(src, DesignServe.mermaidSource("flow.md", "```mermaid\n$src\n```"))
        for (s in listOf("graph LR\nA-->B", "sequenceDiagram\nA->>B: hi", "mindmap\n  root((Solar))", "%% comment\nflowchart LR\nA-->B",
            "stateDiagram-v2\n[*] --> On", "pie title Pets\n\"Dogs\": 3", "timeline\n 2020 : x", "erDiagram\nA ||--o{ B : has"))
            assertTrue(s, DesignServe.looksMermaid(s))
        // Not Mermaid: prose, HTML, SVG and pictures are left alone.
        assertNull(DesignServe.mermaidSource("notes.txt", "A graph of my spending, explained in words."))
        assertNull(DesignServe.mermaidSource("index.html", src))
        assertNull(DesignServe.mermaidSource("art.svg", src))
        assertNull(DesignServe.mermaidSource("x.mmd", "   "))
        assertFalse(DesignServe.looksMermaid("Pie is tasty"))
        assertEquals("text/plain", DesignServe.mimeFor("a.mmd"))
    }

    @Test fun mermaidPageIsAPictureNotSource() {
        val src = "flowchart TD\n Sun[Sunlight] --> Cell[\"<script>x</script>\"]"
        val html = DesignServe.mermaidHtml(src, "solar-panels")
        // v0.26.3: drawn by the bundled mermaid.js (same origin), mermaid.ink only as the fallback.
        assertTrue(html.contains("https://appassets.androidplatform.net/diagram/mermaid.min.js"))
        assertTrue(html.contains("https://mermaid.ink/img/"))
        assertFalse(html.contains("cdn.jsdelivr.net"))
        assertEquals(DesignServe.Target.Asset("diagram/mermaid.min.js", "text/javascript"),
            DesignServe.resolve("https://appassets.androidplatform.net/diagram/mermaid.min.js", null))
        // The source is only in the hidden, escaped element: no live <script>x</script> and no visible "flowchart TD" text.
        assertFalse(html.contains("<script>x</script>"))
        val visible = html.replace(Regex("""<script[\s\S]*?</script>"""), "").replace(Regex("""<style[\s\S]*?</style>"""), "")
        assertFalse(visible.contains("flowchart TD"))
    }
}
