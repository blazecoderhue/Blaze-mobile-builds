package com.blaze.assistant.life

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v0.33.0 Life Log 2: voices, review parsing, formatting. */
class LifeLog2Test {
    private fun voice(seed: Int): DoubleArray { val r = Random(seed); return DoubleArray(128) { r.nextDouble(-1.0, 1.0) } }
    private fun near(v: DoubleArray, seed: Int, noise: Double = 0.35): DoubleArray { val r = Random(seed); return DoubleArray(v.size) { v[it] + r.nextDouble(-noise, noise) } }

    @Test fun twoVoicesAreTwoSpeakers() {
        val a = voice(1); val b = voice(2)
        val utts = (0 until 12).map { i ->
            val v = if (i % 3 == 0) near(b, 100 + i) else near(a, 200 + i)
            Diarize.Utt(i * 5000L, i * 5000L + 3000, "line $i", v, 300) }
        val g = Diarize.cluster(utts)
        assertEquals(2, g.toSet().size)
        assertEquals(g[0], g[3]); assertNotEquals(g[0], g[1]); assertEquals(g[1], g[2])
    }

    @Test fun shortUtteranceJoinsAGroup() {
        val a = voice(3)
        val utts = listOf(Diarize.Utt(0, 1, "long", near(a, 1), 400), Diarize.Utt(2, 3, "ok", null, 0), Diarize.Utt(4, 5, "long again", near(a, 2), 400))
        assertEquals(1, Diarize.cluster(utts).toSet().size)
    }

    @Test fun knownVoicesAreRecognisedAndLearned() {
        val mum = voice(9)
        val known = Diarize.learn(emptyList(), "Mum", mum, 10)
        assertEquals(mapOf(0 to "Mum"), Diarize.matchKnown(mapOf(0 to near(mum, 5, 0.2), 1 to voice(10)), known))
        val back = Diarize.knownFromJson(Diarize.knownToJson(Diarize.learn(known, "mum", near(mum, 6, 0.2), 5)))
        assertEquals(1, back.size); assertEquals(15, back[0].n)
    }

    @Test fun reviewParsesAndFormats() {
        val raw = """<think>x</think>{"title":"Lunch with Chanda","speakers":{"S1":"You","S2":"Chanda"},
          "scenes":[{"from":"12:10","to":"12:50","setting":"a restaurant","who":["You","Chanda"],"summary":"Planned the Lusaka trip."}],
          "memories":[{"kind":"person","subject":"Chanda","text":"Chanda is the user's cousin and lives in Lusaka."}],
          "todos":["Send Chanda the bus times"],"halo":"Chanda sounded excited; I added that she's your cousin."}"""
        val r = LifeReview.parse(raw)!!
        assertEquals("Chanda", r.speakers["S2"]); assertEquals(1, r.memories.size); assertEquals("a restaurant", r.scenes[0].setting)
        val f = LifeReview.format(r, 40, 2, 1)
        assertTrue(f.contains("Chanda (S2)")); assertTrue(f.contains("Halo:")); assertTrue(f.contains("Send Chanda"))
        assertNull(LifeReview.parse("no json"))
    }

    @Test fun names() {
        assertTrue(LifeReview.nameable("You")); assertTrue(LifeReview.nameable("Chanda"))
        assertFalse(LifeReview.nameable("Unknown")); assertFalse(LifeReview.nameable("S3")); assertFalse(LifeReview.nameable(""))
    }

    @Test fun transcriptMarksSceneGaps() {
        val t = LifeReview.transcript(listOf(LifeReview.Line(0, "S1", "hi", "quiet"), LifeReview.Line(10 * 60_000L, "S2", "hello", "noisy")), mapOf("S2" to "Mum"))
        assertTrue(t.contains("10 min later")); assertTrue(t.contains("S2=Mum (noisy): hello"))
        assertEquals("quiet", LifeAudio.roomLabel(100)); assertEquals("noisy", LifeAudio.roomLabel(2000))
    }
}
