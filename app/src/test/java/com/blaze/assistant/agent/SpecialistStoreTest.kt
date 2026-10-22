package com.blaze.assistant.agent

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.26.2 create_specialist: the `specialists` pref stays backward compatible, and a playbook must pass before saving. */
class SpecialistStoreTest {
    private val root = listOf(File("src/main/assets/specialists"), File("app/src/main/assets/specialists")).first { it.isDirectory }
    private val builtIns = Specialists.builtIn.map { File(root, "${it.id}.md").readText() }

    @Test fun oldPrefsLoadAsBefore() {
        val old = """[{"id":"shopify","name":"Shopify helper","prompt":"Runs my store","groups":"web,memory"},{"name":"broken"}]"""
        val l = Specialists.parse(old)
        assertEquals(1, l.size)
        val s = l[0]
        assertEquals("shopify", s.id); assertEquals(setOf("web", "memory"), s.groups); assertTrue(s.custom)
        assertEquals("", s.playbook); assertEquals(Specialists.USER, s.madeBy); assertFalse(s.byBlaze)
        assertEquals(emptyList<Specialists.Spec>(), Specialists.parse("not json"))
        assertEquals(emptyList<Specialists.Spec>(), Specialists.parse(""))
    }

    @Test fun roundTripKeepsOldKeysAndAddsTheNewOnes() {
        val s = Specialists.Spec("farm", "Farming advisor", "Advises farmers", setOf("web"), true, playbook = "# P", madeBy = Specialists.BLAZE)
        val json = Specialists.toJson(listOf(s))
        val o = JSONArray(json).getJSONObject(0)
        assertEquals("farm", o.getString("id")); assertEquals("web", o.getString("groups")); assertEquals("Advises farmers", o.getString("prompt"))
        assertEquals("# P", o.getString("playbook")); assertEquals("blaze", o.getString("made_by"))
        assertEquals(listOf(s), Specialists.parse(json))
        // A spec without a playbook writes no playbook key (an older Blaze reads it exactly as before).
        assertFalse(JSONArray(Specialists.toJson(listOf(s.copy(playbook = "")))).getJSONObject(0).has("playbook"))
    }

    private val playbook = File(root, "researcher.md").readText()
        .replace("Researcher", "Market analyst")

    @Test fun createRejectsCopiesAndExplainsThenAcceptsARealOne() {
        val no = Specialists.make("market-analyst", "Market analyst", "Sizes markets and competitors for a product idea, with sources and numbers.",
            listOf("web", "memory"), playbook, emptyList(), builtIns, attempt = 1)
        assertFalse(no.ok); assertNull(no.spec)
        assertTrue(no.message, no.message.contains("Copied from") && no.message.contains("2 tries left"))
        val last = Specialists.make("market-analyst", "Market analyst", "Sizes markets and competitors for a product idea, with sources and numbers.",
            listOf("web"), "## Principles of the field\nx", emptyList(), builtIns, attempt = 3)
        assertTrue(last.message.contains("last try"))
        assertTrue(Specialists.make("x", "Researcher", "Advises on the farm: crops, planting dates and pests for the district.", listOf("web"), playbook, emptyList(), builtIns).message.contains("built-in"))
        assertTrue(Specialists.make("farm", "Farm", "Advises on the farm: crops, planting dates and pests for the district.", listOf("lasers"), playbook, emptyList(), builtIns).message.contains("Unknown tool group"))
        val user = Specialists.Spec("farm", "Farm", "p", setOf("web"), true, madeBy = Specialists.USER)
        assertTrue(Specialists.make("farm", "Farm", "Advises on the farm: crops, planting dates and pests for the district.", listOf("web"), playbook, listOf(user), builtIns).message.contains("already has"))

        val ok = Specialists.make("Farming Advisor", "Farming advisor",
            "Advises smallholder farmers on crops, planting windows, fertiliser and pests for their district, with the extension source.",
            listOf("web", "memory"), farm, emptyList(), builtIns)
        assertTrue(ok.message, ok.ok)
        val s = ok.spec!!
        assertEquals("farming-advisor", s.id); assertTrue(s.byBlaze); assertTrue(s.custom); assertEquals(farm.trim(), s.playbook)
        // Saved and loaded back like any custom one; matched in later turns by its name.
        val back = Specialists.parse(Specialists.toJson(listOf(s))).single()
        assertEquals(s, back)
        assertEquals("farming-advisor", SpecialistMatch.match("ask the farming advisor about maize in Mkushi", listOf(back)).first().id)
    }

    private val farm = """
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
"""

    @Test fun createToolDeclaresTheSixSections() {
        val d = Specialists.createTool().getJSONObject("function")
        assertEquals("create_specialist", d.getString("name"))
        assertTrue(Playbooks.SECTIONS.all { d.getString("description").contains(it.title) })
    }
}
