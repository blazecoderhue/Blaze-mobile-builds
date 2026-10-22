package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoTest {
    @Test fun tagsRoute() {
        assertEquals(Duo.Mode.HALO, Duo.route("@halo what do you think?").mode)
        assertEquals("what do you think?", Duo.route("@halo what do you think?").text)
        assertEquals(Duo.Mode.BLAZE, Duo.route("@Blaze open WhatsApp").mode)
        assertEquals(Duo.Mode.ALL, Duo.route("@all best pizza topping?").mode)
        assertEquals(Duo.Mode.ALL, Duo.route("hey @blaze and @halo, ideas?").mode)
        assertEquals(Duo.Mode.HALO, Duo.route("hey @halo, how are you").mode)
        assertTrue(Duo.route("@halo").explicit)
        // an email address is not a tag
        assertEquals(Duo.Mode.BLAZE, Duo.route("email me@halo.com").mode)
    }

    @Test fun teamJobs() {
        assertEquals(Duo.Mode.TEAM, Duo.route("create a long story about a dragon in Lusaka").mode)
        assertEquals(Duo.Mode.TEAM, Duo.route("write a business plan for my bakery").mode)
        assertEquals(Duo.Mode.BLAZE, Duo.route("turn on the flashlight").mode)
        assertEquals(Duo.Mode.BLAZE, Duo.route("send a poem to Mum on WhatsApp").mode)
        assertEquals(Duo.Mode.BLAZE, Duo.route("what's the time").mode)
        assertEquals(Duo.Mode.BLAZE, Duo.route("create a long story about a dragon", teamOn = false).mode)
    }

    @Test fun planParsing() {
        val raw = "```json\n{\"kickoff\":[{\"who\":\"halo\",\"line\":\"[Halo] I'll dream it up\"},{\"who\":\"blaze\",\"line\":\"I write.\"}]," +
            "\"phases\":[{\"step\":1,\"who\":\"halo\",\"role\":\"Idea creator\",\"brief\":\"ideas\"},{\"step\":1,\"who\":\"blaze\",\"role\":\"Researcher\",\"brief\":\"facts\"}," +
            "{\"step\":4,\"who\":\"blaze\",\"role\":\"Writer\",\"brief\":\"the story\"},{\"step\":4,\"who\":\"halo\",\"role\":\"Editor\",\"brief\":\"polish\"}]}\n```"
        val p = Duo.parsePlan(raw, "story")
        assertEquals("I'll dream it up", p.kickoff[0].text)
        assertEquals(listOf(1, 1, 2, 3), p.phases.map { it.step })
        assertEquals(3, p.groups.size)
        assertEquals(2, p.groups[0].size)
        assertEquals("Editor", p.last!!.role)
        assertEquals(3, Duo.parsePlan("not json", "x").phases.size)
    }

    @Test fun cleanAndPass() {
        assertEquals("Hello", Duo.clean("[Blaze] Hello"))
        assertEquals("Hi there", Duo.clean("**Halo:** Hi there"))
        assertTrue(Duo.isPass("PASS"))
        assertTrue(Duo.isPass(" pass. "))
        assertFalse(Duo.isPass("Passionate take!"))
    }

    @Test fun contextSkipsTools() {
        val h = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "hi"))
            .put(JSONObject().put("role", "assistant").put("content", "x").put("tool_calls", JSONArray()))
            .put(JSONObject().put("role", "tool").put("content", "result"))
            .put(JSONObject().put("role", "assistant").put("content", "[Halo] hello"))
        val c = Duo.context(h)
        assertEquals(2, c.length())
        assertEquals("[Halo] hello", c.getJSONObject(1).getString("content"))
    }

    // v0.32.0 open conversation
    @Test fun signals() {
        val b = Duo.Who.BLAZE
        assertEquals(Duo.Next.PARTNER, Duo.parseSignal("Nice. [[to: halo]]", b, Duo.Next.DONE).next)
        assertEquals(Duo.Next.USER, Duo.parseSignal("Which colour do you like? [[to: user]]", b, Duo.Next.PARTNER).next)
        assertEquals(Duo.Next.DONE, Duo.parseSignal("haha [[done]]", b, Duo.Next.PARTNER).next)
        val a = Duo.parseSignal("Ship it. [[agree: halo]]", b, Duo.Next.PARTNER)
        assertEquals(Duo.Next.AGREE, a.next); assertEquals(Duo.Who.HALO, a.deliver)
        assertEquals(Duo.Next.PARTNER, Duo.parseSignal("no tag here", b, Duo.Next.PARTNER).next)
        assertEquals("Ship it.", Duo.stripSignal("Ship it. [[agree: halo]]"))
        assertEquals("Hold on", Duo.stripSignal("Hold on [[ag", final = false))
    }

    @Test fun consensusNeedsBoth() {
        val agreeH = Duo.Signal(Duo.Next.AGREE, Duo.Who.HALO)
        val agreeB = Duo.Signal(Duo.Next.AGREE, Duo.Who.BLAZE)
        val go = Duo.Signal(Duo.Next.PARTNER)
        assertEquals(null, Duo.consensus(Duo.Who.BLAZE, go, Duo.Who.HALO, agreeH))
        assertEquals(null, Duo.consensus(null, null, Duo.Who.HALO, agreeH))
        assertEquals(Duo.Who.HALO, Duo.consensus(Duo.Who.BLAZE, agreeH, Duo.Who.HALO, agreeH))
        // they named different people: the second one's pick (it read the first)
        assertEquals(Duo.Who.BLAZE, Duo.consensus(Duo.Who.HALO, agreeH, Duo.Who.BLAZE, agreeB))
        // nobody can agree before hearing the other; a room needs a proposal, a critique and a revision first
        assertEquals(Duo.Next.PARTNER, Duo.effective(agreeH, Duo.Kind.CHAT, 1).next)
        assertEquals(Duo.Next.PARTNER, Duo.effective(agreeH, Duo.Kind.ROOM, 2).next)
        assertEquals(Duo.Next.AGREE, Duo.effective(agreeH, Duo.Kind.ROOM, 3).next)
    }

    @Test fun chatOrRoom() {
        assertEquals(Duo.Kind.CHAT, Duo.kindFor("how are we feeling?"))
        assertEquals(Duo.Kind.ROOM, Duo.kindFor("write a function that reverses a linked list"))
        assertEquals(Duo.Kind.ROOM, Duo.kindFor("should I buy a Pixel or a Samsung?"))
        assertEquals(Duo.Kind.ROOM, Duo.kindFor("name ideas for my cat"))
        assertTrue(Duo.isCommand("open WhatsApp"))
        assertFalse(Duo.isCommand("the blue one please"))
        assertEquals(Duo.WAITING, Duo.cardState(Duo.cardMeta(Duo.WAITING)))
        assertEquals(Duo.Who.HALO, Duo.cardDeliverer(Duo.cardMeta(Duo.DONE, Duo.Who.HALO)))
    }
}
