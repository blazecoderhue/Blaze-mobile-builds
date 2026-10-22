package com.blaze.assistant.agent

import com.blaze.assistant.ui.Views
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v0.23.0 views + skills: view routing, the Learn safety net, skill matching and disabled-skill exclusion. */
class ViewsSkillsTest {

    // ---------------- view routing ----------------

    @Test fun routesRequestsToTheirView() {
        assertEquals("learn", ViewRouter.route("Make me flashcards on photosynthesis")?.id)
        assertEquals("learn", ViewRouter.route("quiz me on the OSI layers")?.id)
        assertEquals("learn", ViewRouter.route("help me study for my CIT102 exam")?.id)
        assertEquals("learn", ViewRouter.route("Flash cards for French verbs please")?.id)
        assertEquals("schedule", ViewRouter.route("remind me to call mum at 6")?.id)
        assertEquals("schedule", ViewRouter.route("every morning send me the weather")?.id)
        assertEquals("design", ViewRouter.route("design a landing page for my lamp store")?.id)
        assertEquals("design", ViewRouter.route("make a slide deck about our launch")?.id)   // not Learn
        assertEquals("gallery", ViewRouter.route("generate an image of a sunset over Lusaka")?.id)
        assertEquals("skills", ViewRouter.route("save this as a skill")?.id)
        assertEquals("memory", ViewRouter.route("remember that I take my coffee black")?.id)
        assertEquals("archive", ViewRouter.route("what did we say in our old chats about the logo?")?.id)
        assertEquals("projects", ViewRouter.route("start a new project for the HomeGlow store")?.id)
        assertEquals("browser", ViewRouter.route("log in to the portal at https://school.example.com")?.id)
    }

    @Test fun plainChatHasNoView() {
        assertNull(ViewRouter.route("hi"))
        assertNull(ViewRouter.route("what's the capital of Zambia?"))
        assertNull(ViewRouter.route("this is a case study about Zambia's copper"))
        assertNull(ViewRouter.route("/learn"))
        assertNull(ViewRouter.route("[Scheduled routine: flashcards review]"))
    }

    @Test fun everyRoutedViewExistsAndIsInThePrompt() {
        for (s in ViewRouter.specs) {
            // Archive is search only since v0.24.0 (no chat list screen); every other routed view has a screen.
            if (s.id == "archive") assertTrue(ViewRouter.hint(s).contains("search_chat") && !ViewRouter.hint(s).contains("open_view"))
            else assertTrue("no view ${s.id}", Views.find(s.id) != null)
            assertTrue("prompt misses ${s.name}", ViewRouter.PROMPT.contains("${s.name} (/${s.id})"))
            assertTrue(ViewRouter.hint(s).startsWith("\nREQUIRED VIEW THIS TURN: ${s.name}"))
        }
        assertTrue(ViewRouter.PROMPT.contains("HARD RULE"))
        assertTrue(ViewRouter.PROMPT.contains("flashcards action=add"))
        assertEquals("projects", Views.find("project")?.id)
    }

    @Test fun learnHintNamesTheDeckTool() {
        val h = ViewRouter.hint(ViewRouter.byId("learn")!!)
        assertTrue(h.contains("flashcards action=add")); assertTrue(h.contains("open_view view=learn")); assertTrue(h.contains("Do NOT"))
    }

    // ---------------- Learn safety net ----------------

    @Test fun parsesQAndATextIntoCards() {
        val text = """
            Here are your flashcards:
            1. **Q:** What is photosynthesis?
               **A:** How plants turn light, water and CO2 into glucose and oxygen.
            2. Q: Where does it happen?
               A: In the chloroplasts.
            3. Question: What pigment absorbs light?
               Answer: Chlorophyll
        """.trimIndent()
        val c = ViewRouter.cardsFrom(text)
        assertEquals(3, c.size)
        assertEquals("What is photosynthesis?", c[0].first)
        assertEquals("In the chloroplasts.", c[1].second)
        assertEquals("Chlorophyll", c[2].second)
    }

    @Test fun parsesFrontBackTablesAndBoldTerms() {
        val table = "| Front | Back |\n|---|---|\n| Layer 3 | Network |\n| Layer 4 | Transport |\n| Layer 7 | Application |"
        assertEquals(listOf("Layer 3" to "Network", "Layer 4" to "Transport", "Layer 7" to "Application"), ViewRouter.cardsFrom(table))
        val terms = "- **Mitosis** – cell division into two identical cells\n- **Meiosis** – division into four sex cells\n- **Gamete** – a sex cell"
        assertEquals(3, ViewRouter.cardsFrom(terms).size)
        assertEquals("Mitosis", ViewRouter.cardsFrom(terms)[0].first)
        assertTrue(ViewRouter.cardsFrom("Photosynthesis is how plants make food - it needs light.").isEmpty())
    }

    @Test fun decidesConvertRepromptOrNothing() {
        val cards = "Q: 2+2?\nA: 4\nQ: 3+3?\nA: 6"
        assertEquals(ViewRouter.Fix.CONVERT, ViewRouter.shouldConvert("make flashcards on sums", cards, calledFlashcards = false, alreadyNudged = false))
        assertEquals(ViewRouter.Fix.NONE, ViewRouter.shouldConvert("make flashcards on sums", cards, calledFlashcards = true, alreadyNudged = false))
        assertEquals(ViewRouter.Fix.REPROMPT, ViewRouter.shouldConvert("make flashcards on sums", "Sure! What topic exactly?", false, false))
        assertEquals(ViewRouter.Fix.NONE, ViewRouter.shouldConvert("make flashcards on sums", "Sure! What topic exactly?", false, alreadyNudged = true))
        // An interactive quiz (one question at a time) is allowed in chat.
        assertEquals(ViewRouter.Fix.NONE, ViewRouter.shouldConvert("quiz me on sums", "First one: what's 7 × 8?", false, false))
        // Not a Learn turn at all.
        assertEquals(ViewRouter.Fix.NONE, ViewRouter.shouldConvert("what is 2+2", cards, false, false))
    }

    @Test fun deckNamesComeFromTheRequest() {
        assertEquals("Photosynthesis", ViewRouter.deckName("make me flashcards on photosynthesis"))
        assertEquals("The French revolution", ViewRouter.deckName("Flashcards about the French revolution please"))
        assertEquals("CIT102 loops", ViewRouter.deckName("quiz me on CIT102 loops"))
        assertEquals("Cards", ViewRouter.deckName("make flashcards"))
    }

    @Test fun convertedCardsLandInALearnDeck() {
        Flashcards.fileOverride = File(Files.createTempDirectory("learn").toFile(), "cards.json"); Flashcards.reset()
        val pairs = ViewRouter.cardsFrom("Q: 2+2?\nA: 4\nQ: 3+3?\nA: 6")
        val n = Flashcards.add(ViewRouter.deckName("flashcards on sums"), pairs)
        assertEquals(2, n)
        assertEquals("Sums", Flashcards.decks().single().name)
        assertTrue(ViewRouter.converted("Sums", n, pairs.size).contains("Sums"))
        // The deck tool itself (create a Learn deck with front/back cards) still works as before.
        assertTrue(Flashcards.run(JSONObject("""{"action":"add","deck":"Sums","cards":[{"front":"4+4?","back":"8"}]}""")).startsWith("Added 1 card"))
        Flashcards.fileOverride = null; Flashcards.reset()
    }

    // ---------------- skill matching ----------------

    private fun cand(name: String, desc: String, tags: List<String> = emptyList(), body: String = "# $name\n1. step\n2. step", source: String = "bundled", on: Boolean = true) =
        SkillMatch.Cand(name, desc, tags, body, source, on)

    private val lib = listOf(
        cand("study-flashcards", "Make and study flashcards in the Learn view. Use for quiz me, test me, revise, exam prep.",
            listOf("flashcards", "quiz", "quiz me", "study", "revise", "exam")),
        cand("tutor", "Teach a topic step by step, then quiz the user; turn notes into flashcards.", listOf("student", "tutoring", "flashcards", "quiz")),
        cand("landing-page-builder", "Build a one-page landing page with a hero and call to action.", listOf("web", "landing", "html")),
        cand("email-drafting", "Draft clear emails and replies.", listOf("email", "gmail")),
        cand("airtime-topup", "Top up MTN airtime with USSD", emptyList(), "1. dial *303#", source = "learned"),
    )

    @Test fun matchesByNameKeywordsAndDescription() {
        val r = SkillMatch.match("Make me flashcards on photosynthesis", lib)
        assertEquals("study-flashcards", r.full.first().name)
        assertTrue(r.names.contains("tutor"))
        assertFalse(r.names.contains("email-drafting"))
        assertEquals("landing-page-builder", SkillMatch.match("build a landing page for my store", lib).full.first().name)
        assertEquals("airtime-topup", SkillMatch.match("top up my airtime", lib).full.first().name)
        assertTrue(SkillMatch.match("hi there", lib).isEmpty)
        assertTrue(SkillMatch.match("", lib).isEmpty)
    }

    @Test fun stemsPluralsAndIng() {
        assertEquals("flashcard", SkillMatch.stem("flashcards"))
        assertEquals("study", SkillMatch.stem("studying"))
        assertEquals("study", SkillMatch.stem("studies"))
        assertEquals("quiz", SkillMatch.stem("quizzes"))
        assertTrue("flashcard" in SkillMatch.words("Flash cards please"))
    }

    @Test fun disabledSkillsNeverMatch() {
        val off = lib.map { if (it.name == "study-flashcards") it.copy(on = false) else it }
        val r = SkillMatch.match("Make me flashcards on photosynthesis", off)
        assertFalse(r.names.contains("study-flashcards"))
        assertEquals("tutor", r.full.first().name)
        assertTrue(SkillMatch.match("quiz me", off.map { it.copy(on = false) }).isEmpty)
    }

    @Test fun topThreeInFullWithinTheBudgetRestByName() {
        val many = (1..6).map { cand("quiz-helper-$it", "quiz helper number $it for flashcards", listOf("quiz", "flashcards"), body = "x".repeat(1_000)) }
        val r = SkillMatch.match("quiz me with flashcards", many)
        assertEquals(3, r.full.size)
        assertEquals(3, r.others.size)
        val big = many.map { it.copy(body = "y".repeat(2_500)) }
        val r2 = SkillMatch.match("quiz me with flashcards", big, budget = 6_000)
        assertEquals(2, r2.full.size)    // a third 2,500-char body would pass the 6,000 budget
        assertEquals(4, r2.others.size)
        val block = SkillMatch.block(r2)
        assertTrue(block.contains("SKILLS FOR THIS REQUEST")); assertTrue(block.contains("### quiz-helper-1 (bundled)"))
        assertTrue(block.contains("Other matching skills")); assertTrue(block.length < 6_000 + 600)
        assertEquals("", SkillMatch.block(SkillMatch.Result.NONE))
        assertTrue(SkillMatch.cardLine(r).startsWith("Skills: quiz-helper-1"))
        assertEquals("", SkillMatch.cardLine(SkillMatch.Result.NONE))
    }

    @Test fun readsSkillFoldersWithTagsAndTheShippedSkill() {
        val dir = File(Files.createTempDirectory("sk").toFile(), "my-skill").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: my-skill\ndescription: Do the thing\ntags: [alpha, beta gamma]\n---\n\n# Body\n1. go\n")
        val c = SkillMatch.fromDir(dir, "user", on = false)!!
        assertEquals("my-skill", c.name); assertEquals(listOf("alpha", "beta gamma"), c.keywords)
        assertEquals("# Body\n1. go", c.body); assertFalse(c.on)
        // The bundled study-flashcards skill points at Learn and wins for a flashcards request.
        val root = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }
        val shipped = SkillMatch.fromDir(File(root, "study-flashcards"), "bundled", true)!!
        assertTrue(shipped.body.contains("flashcards") && shipped.body.contains("open_view") && shipped.body.contains("view=learn"))
        val tutor = SkillMatch.fromDir(File(root, "tutor"), "bundled", true)!!
        assertEquals("study-flashcards", SkillMatch.match("make flashcards on loops", listOf(tutor, shipped)).full.first().name)
    }
}
