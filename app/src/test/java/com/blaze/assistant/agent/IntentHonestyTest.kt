package com.blaze.assistant.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * v0.27.x intent + honesty: "show me your memories" lists memories (no Design), retries stay on the request,
 * the design reply is grounded in what the tool made (2D is not 3D), and Blaze offers to do things only when it can.
 */
class IntentHonestyTest {

    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("designs").toFile(); Designs.rootOverride = dir; Designs.touched.clear() }
    @After fun tearDown() { Designs.rootOverride = null; Designs.touched.clear(); dir.deleteRecursively() }

    // ---------------- memory intent ----------------

    @Test fun memoryPhrasingIsAListRequest() {
        for (t in listOf("show me your memories", "Show me your memories", "show your memories", "list my memories", "what are your memories?",
            "what's in your memory?", "what do you know about me", "What do you remember about me?", "what have you saved about me",
            "memories?", "my memories", "can I see your memory"))
            assertTrue(t, MemoryIntent.isListRequest(t))
        for (t in listOf("remember that I take my coffee black", "forget my old address", "how much memory is free on my phone",
            "show me memories from our Lusaka trip", "show me a 3D rocket", "hi", "delete all your memories", "what is RAM memory used for",
            "/memory"))
            assertFalse(t, MemoryIntent.isListRequest(t))
    }

    @Test fun memoryPhrasingRoutesToMemoryNotDesign() {
        // Even right after a design (follow-on mode), "show me your memories" is not a design edit.
        assertFalse(ViewRouter.isFollowOnEdit("show me your memories"))
        assertEquals("memory", ViewRouter.route("show me your memories", followOn = true)?.id)
        assertEquals("memory", ViewRouter.route("show me your memories")?.id)
        assertEquals("memory", ViewRouter.route("what do you know about me", followOn = true)?.id)
        assertTrue(ViewRouter.hint(ViewRouter.byId("memory")!!).contains("query=all"))
        // Follow-on design edits still work.
        assertEquals("design", ViewRouter.route("show the engine", followOn = true)?.id)
    }

    @Test fun memoriesAreGroupedPlainlyWithAManageHint() {
        val mems = listOf(
            Mind.Mem(1, "profile", "You", "Lives in Ndola", created = 1),
            Mind.Mem(2, "person", "Gloria", "Sister, studies in Lusaka", created = 2),
            Mind.Mem(3, "preference", "You", "Takes coffee black.", created = 3),
            Mind.Mem(4, "weird", "Blaze", "Building the Blaze app", created = 4),
        )
        val r = MemoryIntent.format(mems)
        assertTrue(r.startsWith("Here's what I remember about you:"))
        assertTrue(r.contains("**About you**\n- Lives in Ndola"))
        assertTrue(r.contains("**People**\n- Gloria: Sister, studies in Lusaka"))
        assertTrue(r.contains("- Takes coffee black\n"))
        assertTrue(r.contains("**Other things**\n- Blaze: Building the Blaze app"))
        assertTrue(r.indexOf("About you") < r.indexOf("Preferences") && r.indexOf("Preferences") < r.indexOf("People"))
        assertTrue(r.endsWith(MemoryIntent.MANAGE))
        assertFalse(r.contains("Design"))
        assertEquals(MemoryIntent.EMPTY, MemoryIntent.format(emptyList()))
        assertTrue(MemoryIntent.formatPlain(listOf("Likes tea")).contains("- Likes tea"))
        val many = (1..80L).map { Mind.Mem(it, "fact", "You", "Fact $it", created = it) }
        assertTrue(MemoryIntent.format(many).contains("and 20 more"))
        assertTrue(MemoryIntent.meansAll("all") && MemoryIntent.meansAll("") && MemoryIntent.meansAll("everything"))
        assertFalse(MemoryIntent.meansAll("Gloria"))
    }

    // ---------------- retries stay on the request ----------------

    @Test fun aLookupBacksICheckedMyMemory() {
        val tools = listOf("memory_search", "recall_memory", "web_search")
        val txt = "I checked my memory: you take your coffee black and live in Ndola."
        assertTrue(FakeCall.looksFake(txt, tools, acted = false, userText = "show me your memories"))
        assertFalse(FakeCall.looksFake(txt, tools, acted = false, userText = "show me your memories", looked = true))
        // A lookup doesn't back an action claim.
        assertTrue(FakeCall.looksFake("I installed the TTS model for you.", tools, acted = false, userText = "get me a tts model", looked = true))
        // Showing memories is backed by a memory lookup.
        assertTrue(ClaimGuard.backs(ViewRouter.byId("memory")!!, ClaimGuard.Call("recall_memory")))
        assertTrue(ClaimGuard.backs(ViewRouter.byId("memory")!!, ClaimGuard.Call("memory_search")))
    }

    @Test fun aRetryStaysOnTheLatestRequest() {
        val design = ViewRouter.byId("design")!!
        // A claim about Design is not a reason to go and work in Design when the user asked about memories.
        assertFalse(ClaimGuard.relevant(design, "show me your memories"))
        assertTrue(ClaimGuard.relevant(design, "make a 3D rocket"))
        assertTrue(ClaimGuard.relevant(design, "open the design again"))
        assertTrue(ClaimGuard.relevant(design, "show engine", turnView = "design"))
        val stray = "You take your coffee black and live in Ndola. I've opened the Solar Panel Working diagram in Design."
        assertEquals("design", ClaimGuard.unbacked(stray, emptyList())?.id)
        val kept = ClaimGuard.drop(stray, design)
        assertTrue(kept.contains("coffee black") && !kept.contains("Solar"))
        assertEquals("", ClaimGuard.drop("Opened the Solar Panel Working diagram in Design view.", design))
        // Every corrective re-prompt names the user's request and says to stay on it.
        val n = RetryGuard.anchor(FakeCall.NUDGE, "show me your memories")
        assertTrue(n.startsWith(FakeCall.NUDGE))
        assertTrue(n.contains("\u201cshow me your memories\u201d") && n.contains("Stay on exactly that request") && n.contains("plain text answer"))
        assertTrue(n.contains("don't reopen or show work from earlier turns"))
        assertTrue(RetryGuard.MAX in 1..2)
        // Friendly labels, never the old ones.
        for (k in RetryGuard.Kind.values()) for (old in RetryGuard.RETIRED) assertFalse(RetryGuard.label(k, "Design").contains(old))
        assertEquals("Double-checking my answer", RetryGuard.label(RetryGuard.Kind.FAKE_CALL))
        assertFalse(ClaimGuard.honest("I've updated the design.", design).contains("properly"))
    }

    // ---------------- design reply grounding ----------------

    private val solarHtml = "<!doctype html><html><body><div>Sunlight</div><div>Silicon Cell</div><div>Inverter</div></body></html>"

    @Test fun aFlatDiagramIsNeverDescribedAs3d() {
        val c = Designs.run(JSONObject().put("action", "create").put("kind", "diagram").put("title", "Solar Panel Working").put("html", solarHtml), now = 1000)
        assertTrue(c.ok); assertEquals("diagram", c.kind)
        assertTrue(c.text.contains("flat 2D diagram") && c.text.contains("not 3D"))
        assertFalse(c.text.contains("orbit with one finger"))
        val o = Designs.run(JSONObject().put("action", "open"), now = 1000 + 3 * 3_600_000L)
        assertTrue(o.ok); assertEquals("diagram", o.kind)
        assertTrue(o.text.contains("Solar Panel Working") && o.text.contains("flat 2D diagram"))
        assertTrue(o.text.contains("earlier conversation"))
        assertFalse(o.text.contains("The user can orbit"))
        assertEquals(listOf("diagram", "diagram"), Designs.touched.toList())

        val lie = "Opened the Solar Panel Working diagram in Design view. You can orbit the model with one finger and zoom with two. " +
            "If the 3D doesn't load, it falls back to a simple native drawing. Exit anytime to return to chat."
        val g = Designs.groundReply(lie, listOf("diagram"))
        assertFalse(g.contains("orbit") || g.contains("3D") || g.contains("native drawing") || g.contains("zoom"))
        assertTrue(g.contains("Solar Panel Working") && g.contains("Exit anytime"))
        assertEquals("It's open in Design as a flat 2D diagram.", Designs.groundReply("You can orbit it in 3D.", listOf("diagram")))
        // A real 3D model, or no design this turn: untouched.
        assertEquals(lie, Designs.groundReply(lie, listOf("3d")))
        assertEquals(lie, Designs.groundReply(lie, emptyList()))
        assertEquals("3D model", Designs.kindLabel("3d"))
        assertEquals("flat 2D page", Designs.kindLabel("page"))
        assertEquals("", Designs.flatNote("3d"))
    }

    // ---------------- hands-on answers ----------------

    @Test fun howToGetSomethingEndsWithAConcreteOffer() {
        assertEquals("install", AnswerStyle.offerVerb("how do I get a lightweight TTS model?"))
        assertEquals("install", AnswerStyle.offerVerb("How can I install the Blaze APK"))
        assertEquals("turn on", AnswerStyle.offerVerb("how do I enable the built-in TTS"))
        assertEquals("download", AnswerStyle.offerVerb("where can I download the offline voice?"))
        assertEquals("set up", AnswerStyle.offerVerb("how to set up the wake word"))
        assertEquals("open", AnswerStyle.offerVerb("how do I open developer options"))
        assertEquals("Want me to install it for you?", AnswerStyle.offerLine("install"))
        val h = AnswerStyle.turnHint("how do I get a lightweight TTS model?")
        assertTrue(h.contains("Want me to install it for you?") && h.contains("Yes, install it"))
        // The always-on rule: offer only a concrete related action; facts, explanations, greetings and finished tasks get no question.
        assertTrue(AnswerStyle.RULE.contains("Want me to install it for you?"))
        assertTrue(AnswerStyle.RULE.contains("just do it"))
        assertTrue(AnswerStyle.RULE.contains("Never tack on generic"))
        assertTrue(AnswerStyle.RULE.contains("Plain facts, explanations, greetings and finished tasks end without a question"))
    }

    @Test fun noOfferForAPureFactQuestionOrACommand() {
        for (t in listOf("what is the capital of Zambia?", "how does text to speech work?", "hi", "thanks!", "who wrote Things Fall Apart",
            "explain photosynthesis", "how do I get a job in Lusaka", "install WhatsApp", "turn on the flashlight", "why is the sky blue"))
            assertNull(t, AnswerStyle.offerVerb(t))
        assertEquals("", AnswerStyle.turnHint("what is the capital of Zambia?"))
        assertEquals("", AnswerStyle.turnHint("how does text to speech work?"))
        assertNotEquals("", AnswerStyle.turnHint("how can I get the offline voice"))
    }
}
