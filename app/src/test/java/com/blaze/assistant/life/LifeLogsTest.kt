package com.blaze.assistant.life

import com.blaze.assistant.agent.QuickRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Life logs (v0.19.0): chunking by silence / topic / size, spoken stop commands, quick rules, brainstorm summary parsing. */
class LifeLogsTest {
    private fun u(startS: Long, endS: Long, text: String) = LogChunker.Utterance(startS * 1000, endS * 1000, text)

    @Test fun silenceClosesAChunk() {
        val ch = LogChunker(silenceMs = 30_000)
        assertTrue(ch.add(u(0, 3, "we should call the landlord on friday")).isEmpty())
        assertTrue(ch.add(u(5, 8, "and ask about the water bill")).isEmpty())
        val out = ch.add(u(60, 62, "what time is the match"))
        assertEquals(1, out.size)
        assertEquals("We should call the landlord on friday. And ask about the water bill.", out[0].text)
        assertEquals(0L, out[0].start); assertEquals(8_000L, out[0].end); assertEquals(2, out[0].utterances)
        // tick closes the open one after the gap, flush closes whatever is left.
        assertNull(ch.tick(70_000))
        assertEquals("What time is the match.", ch.tick(95_000)?.text)
        assertNull(ch.flush())
    }

    @Test fun topicCueStartsANewChunk() {
        val ch = LogChunker()
        ch.add(u(0, 2, "the lamp listing needs new photos"))
        val out = ch.add(u(4, 6, "anyway next week is exams"))
        assertEquals(1, out.size)
        assertEquals("The lamp listing needs new photos.", out[0].text)
        assertEquals("Anyway next week is exams.", ch.flush()?.text)
    }

    @Test fun sizeAndDurationCapChunks() {
        val ch = LogChunker(maxChars = 60, maxMs = 8 * 60_000)
        assertTrue(ch.add(u(0, 2, "one two three four five six seven")).isEmpty())
        val out = ch.add(u(3, 5, "eight nine ten eleven twelve thirteen"))
        assertEquals(1, out.size); assertEquals(0, ch.pending)
        val long = LogChunker(maxChars = 10_000, maxMs = 60_000)
        long.add(u(0, 20, "first part of a long talk"))
        assertEquals(1, long.add(u(25, 61, "still talking a minute later")).size)
    }

    @Test fun noiseIsDropped() {
        val ch = LogChunker()
        assertTrue(ch.add(u(0, 1, "the")).isEmpty())
        assertTrue(ch.add(u(1, 2, "  huh  ")).isEmpty())
        assertEquals(0, ch.pending)
        assertNull(LogChunker.clean("um uh"))
        assertEquals("the plan", LogChunker.clean(" the   plan "))
    }

    @Test fun spokenCommands() {
        assertEquals("stop", LogChunker.command("stop logs"))
        assertEquals("stop", LogChunker.command("Hey Blaze, stop live logs"))
        assertEquals("stop", LogChunker.command("blaze end brainstorm"))
        assertEquals("pause", LogChunker.command("pause logging"))
        assertNull(LogChunker.command("we need to stop the logs from the server filling up"))
        assertNull(LogChunker.command("stop"))
    }

    @Test fun entryMarksItAsOverheard() {
        val c = LogChunker.Chunk(0, 60_000, "Gloria said the meeting moved to Thursday.", 1)
        val e = LogChunker.entry(c, brainstorm = false)
        assertTrue(e.startsWith("Heard nearby "))
        assertTrue(e.contains("may be other people"))
        assertTrue(e.endsWith("Gloria said the meeting moved to Thursday."))
        assertTrue(LogChunker.entry(c, brainstorm = true).startsWith("Brainstorm"))
    }

    @Test fun quickRules() {
        fun a(s: String) = QuickRules.parse(s)?.let { (n, o) -> if (n == "life_logs") o.optString("action") else "other:$n" }
        assertEquals("start", a("start logs"))
        assertEquals("start", a("Blaze, activate live logs"))
        assertEquals("start", a("live logs"))
        assertEquals("start", a("turn on life logs please"))
        assertEquals("stop", a("stop logs"))
        assertEquals("stop", a("turn off the logs"))
        assertEquals("pause", a("pause logs"))
        assertEquals("brainstorm", a("brainstorm with me"))
        assertEquals("brainstorm", a("let's brainstorm"))
        assertEquals("stop", a("end the brainstorm"))
        assertEquals("status", a("are logs on"))
        assertTrue(a("show me the server logs") != "start")
    }

    @Test fun brainstormSummary() {
        val raw = "Sure!\n```json\n{\"title\":\"HomeGlow launch\",\"ideas\":[\"Moon lamp bundle\",\" \"],\"decisions\":[\"Launch on 1 Nov\"],\"next_steps\":[\"Shoot photos\",\"Write captions\"]}\n```"
        val sm = LifeLogs.parseSummary(raw)!!
        assertEquals("HomeGlow launch", sm.title)
        assertEquals(listOf("Moon lamp bundle"), sm.ideas)
        assertEquals(listOf("Launch on 1 Nov"), sm.decisions)
        val md = LifeLogs.format(sm, 12)
        assertTrue(md.startsWith("**Brainstorm: HomeGlow launch** (12 min)"))
        assertTrue(md.contains("**Next steps**\n- Shoot photos\n- Write captions"))
        assertNull(LifeLogs.parseSummary("no json here"))
        assertNull(LifeLogs.parseSummary("{\"title\":\"x\",\"ideas\":[],\"decisions\":[],\"next_steps\":[]}"))
    }

    @Test fun hoursLabel() {
        assertEquals("45s", LifeLogs.hours(45_000))
        assertEquals("12 min", LifeLogs.hours(12 * 60_000L))
        assertEquals("1.5 h", LifeLogs.hours(90 * 60_000L).replace(',', '.'))
    }
}
