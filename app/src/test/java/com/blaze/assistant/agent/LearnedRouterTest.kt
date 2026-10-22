package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.1 learned tool router: words → groups, from the phone's own turns; adds only, never on thin evidence. */
class LearnedRouterTest {
    @Test fun tokensDropStopWordsAndStem() {
        val t = LearnedRouter.tokens("Please open my timetables and the Lectures for tomorrow")
        assertTrue("timetable" in t); assertTrue("lecture" in t); assertTrue("tomorrow" in t)
        assertFalse("please" in t); assertFalse("the" in t); assertFalse("my" in t)
    }

    @Test fun learnsAfterEnoughEvidence() {
        val r = LearnedRouter()
        r.learn("send my timetable to Mapalo", setOf("files", "messages"))
        r.learn("where is my timetable", setOf("files"))
        assertTrue(r.predict("timetable for monday").isEmpty())      // seen twice: not enough yet
        r.learn("print the timetable", setOf("files"))
        assertEquals(listOf("files"), r.predict("timetable for monday"))
        assertTrue(r.predict("timetable", already = setOf("files")).isEmpty())
    }

    @Test fun noisyWordsDontPredict() {
        val r = LearnedRouter()
        repeat(3) { r.learn("music now", setOf("media")) }
        repeat(3) { r.learn("music history essay", emptySet()) }
        assertTrue(r.predict("music").isEmpty())                     // 3 of 7: under the bar
    }

    @Test fun atMostTwoGroups() {
        val r = LearnedRouter()
        repeat(4) { r.learn("zed", setOf("a", "b", "c")) }
        assertEquals(2, r.predict("zed").size)
    }

    @Test fun roundTripsAndForgets() {
        val r = LearnedRouter(maxWords = 3)
        repeat(3) { r.learn("alpha beta", setOf("g")) }
        r.learn("gamma delta", emptySet())
        assertEquals(3, r.size())
        val c = LearnedRouter().apply { load(JSONObject(r.toJson().toString())) }
        assertEquals(r.predict("alpha beta"), c.predict("alpha beta"))
        assertEquals(r.turns, c.turns)
    }
}
