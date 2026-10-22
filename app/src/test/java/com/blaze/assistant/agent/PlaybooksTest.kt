package com.blaze.assistant.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.26.2 field playbooks: every built-in ships one that passes the validator; the validator rejects thin, generic, copied or secret ones. */
class PlaybooksTest {
    private val root = listOf(File("src/main/assets/specialists"), File("app/src/main/assets/specialists")).first { it.isDirectory }
    private fun pb(id: String) = File(root, "$id.md").readText()
    private fun field(s: Specialists.Spec) = "${s.name} ${s.id} ${s.prompt}"

    @After fun tearDown() { Playbooks.loader = null; Playbooks.reset() }

    @Test fun everyBuiltInHasAValidFieldPlaybook() {
        assertEquals(10, Specialists.builtIn.size)
        val all = Specialists.builtIn.associate { it.id to pb(it.id) }
        for (s in Specialists.builtIn) {
            val c = Playbooks.validate(all.getValue(s.id), field(s), all.filterKeys { it != s.id }.values.toList())
            assertTrue("${s.id}: ${c.problems}", c.ok)
            val secs = Playbooks.sections(all.getValue(s.id))
            assertEquals("${s.id} has all six sections", Playbooks.SECTIONS.map { it.key }.toSet(), secs.keys)
            assertTrue("${s.id} self-check", Playbooks.checklist(all.getValue(s.id)).size >= Playbooks.MIN_CHECKS)
        }
    }

    @Test fun designerAndCoderPlaybooksSayWhatJackAskedFor() {
        val d = pb("designer").lowercase()
        listOf("hierarchy", "grid", "spacing", "contrast", "type scale", "real content", "mermaid", "arrows", "design action=read", "palette").forEach { assertTrue(it, d.contains(it)) }
        val c = pb("coder").lowercase()
        listOf("run before you report", "tests", "idioms", "clean code").forEach { assertTrue(it, c.contains(it)) }
    }

    private val good = """
# Farming advisor playbook

## Principles of the field
- Crop advice starts from the farmer's agro-ecological region, soil type and rainfall pattern; maize in Zambia's region IIa is not maize in region III.
- Recommend practices proven for smallholder farms: conservation farming, crop rotation with legumes, and timely planting at the first effective rains.
- Fertiliser rates follow a soil test or the national extension recommendation (basal D compound, top dressing urea), never a guess.

## Quality bar
A good answer names the crop, the planting window for the farmer's region, the seed variety class (early, medium or late maturing), fertiliser type and rate per hectare, and the main pest to scout for, with the extension source.

## Best practices
- Ask for or infer the district, then map it to the agro-ecological region before advising.
- Give rates per hectare and per lima so smallholders can apply them; convert kg to 50 kg bags.
- Name fall armyworm, stalk borer and grey leaf spot scouting steps with thresholds before spraying.
- Point to ZARI or the Ministry of Agriculture extension office for certified seed and soil testing.

## Common mistakes to avoid
- Copying commercial-farm fertiliser rates to a one-hectare smallholding.
- Planting dates from another country's season calendar.
- Recommending pesticides without the active ingredient, the dose and the pre-harvest interval.

## Self-check
- [ ] The region, crop and planting window match the farmer's district.
- [ ] Fertiliser type and rate are given per hectare and per lima.
- [ ] Pest and disease scouting has a threshold before any spray.
- [ ] Every chemical has an active ingredient, dose and pre-harvest interval.
- [ ] A local extension source is named.

## Output format
"Plan for <crop> in <district>:" then a numbered list: planting window, seed, fertiliser, weeding, pests, harvest. End with the extension contact.
""".trim()

    @Test fun aRealFieldPlaybookPasses() {
        val c = Playbooks.validate(good, "Farming advisor farming-advisor Advises smallholder farmers on crops, planting, fertiliser and pests")
        assertTrue(c.problems.toString(), c.ok)
    }

    @Test fun missingThinGenericCopiedOrSecretPlaybooksAreRejectedWithExactFeedback() {
        val f = "Farming advisor Advises smallholder farmers on crops, planting, fertiliser and pests"
        val noOutput = good.substringBefore("## Output format")
        val c1 = Playbooks.validate(noOutput, f)
        assertFalse(c1.ok); assertTrue(c1.feedback(), c1.problems.any { it.contains("Missing section \"## Output format\"") })

        val thin = Playbooks.SECTIONS.joinToString("\n\n") { "## ${it.title}\n- Be accurate." }
        val c2 = Playbooks.validate(thin, f)
        assertFalse(c2.ok); assertTrue(c2.problems.any { it.contains("too thin") }); assertTrue(c2.problems.any { it.contains("checklist item") })

        val generic = Playbooks.SECTIONS.joinToString("\n\n") { s -> "## ${s.title}\n" + (1..8).joinToString("\n") { "- Make sure the work is good and complete number $it." } +
            "\n- Always be professional.\n- Be thorough.\n- Ensure everything is right.\n- Do your best." }
        val c3 = Playbooks.validate(generic, f)
        assertFalse(c3.ok); assertTrue(c3.problems.toString(), c3.problems.any { it.contains("generic", true) || it.contains("specific to the field") })

        val researcher = pb("researcher")
        val c4 = Playbooks.validate(researcher.replace("Researcher", "Farming advisor"), "$f farm research sources", listOf(researcher))
        assertFalse(c4.ok); assertTrue(c4.problems.toString(), c4.problems.any { it.startsWith("Copied from") })

        val c5 = Playbooks.validate(good + "\nUse api_key: sk-abcdefghijklmnopqrstuvwx to call it.", f)
        assertFalse(c5.ok); assertTrue(c5.problems.any { it.contains("credentials") })

        val c6 = Playbooks.validate(good, "Tax accountant Prepares VAT returns and payroll filings")
        assertFalse(c6.ok); assertTrue(c6.problems.any { it.contains("Not specific to the field") })
        assertTrue(c6.feedback().contains("Principles of the field, Quality bar"))
    }

    @Test fun playbookIsInjectedIntoTheSpecialistPrompt() {
        val s = Specialists.builtIn.first { it.id == "designer" }
        Playbooks.loader = { id -> File(root, "$id.md").takeIf { it.isFile }?.readText() }
        val p = Playbooks.forSpec(s)
        assertTrue(p.contains("## Self-check"))
        val sys = Playbooks.specialistSystem(s, p, "\nSKILLS (follow them):\n### blaze-views", "Wednesday", "", agentId = "B3")
        assertTrue(sys.contains("You are Blaze's Designer specialist, working as background agent B3"))
        assertTrue(sys.contains("PLAYBOOK — Designer")); assertTrue(sys.contains("## Quality bar")); assertTrue(sys.contains(s.prompt))
        assertTrue(sys.contains("Output format"))
        // A custom specialist uses its own playbook; one without gets none here (it's generated on first use).
        val custom = Specialists.Spec("farm", "Farming advisor", "x", setOf("web"), custom = true, playbook = good)
        assertEquals(good, Playbooks.forSpec(custom))
        assertEquals("", Playbooks.forSpec(custom.copy(playbook = "")))
        assertEquals("", Playbooks.inject("X", ""))
    }

    @Test fun reviewIsOnePassAndParsesStrictly() {
        val m = Playbooks.reviewMessages("Designer", pb("designer"), "Make a poster", "Here is a page with arrows -> ->")
        assertEquals(2, m.length())
        val u = m.getJSONObject(1).getString("content")
        assertTrue(u.contains("SELF-CHECK:")); assertTrue(u.contains("DRAFT:")); assertTrue(u.contains("focal point"))
        assertTrue(Playbooks.parseReview("PASS").pass)
        assertTrue(Playbooks.parseReview("<think>hmm</think>\n**PASS**").pass)
        assertTrue("unreadable counts as a pass", Playbooks.parseReview("I think it's mostly fine").pass)
        val r = Playbooks.parseReview("FAIL\n- Contrast: body text is #999 on white\n- Visual: no section has a visual")
        assertFalse(r.pass); assertEquals(2, r.fixes.size)
        assertTrue(Playbooks.redoPrompt(r.fixes).contains("Contrast: body text"))
        assertEquals(listOf("missing sources"), Playbooks.parseReview("FAIL: missing sources").fixes)
    }

    @Test fun generationRetryCarriesTheValidatorProblems() {
        val m = Playbooks.generateMessages("Farming advisor", "Advises farmers", listOf("Missing section \"## Output format\"."), "# old")
        val u = m.getJSONObject(1).getString("content")
        assertTrue(u.contains("rejected")); assertTrue(u.contains("Output format")); assertTrue(u.contains("# old"))
        assertEquals("# A\n## B", Playbooks.cleanGenerated("<think>x</think>```markdown\n# A\n## B\n```"))
    }
}
