package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** v0.45.0 P5 reactions as feedback (R4): 👍 reinforces, 👎 removes the plan and the turn's lessons; undo is exact. */
class ReactionLearnTest {
    @get:Rule val tmp = TemporaryFolder()

    private val router = LearnedRouter()

    @Before fun setUp() {
        TapLoop.Plans.init(tmp.newFile("plans.json"))
        TurnFeedback.init(tmp.newFile("fb.json").apply { delete() })
        TurnFeedback.ports = object : TurnFeedback.Ports {
            override fun take(request: String) = TapLoop.Plans.take(request)
            override fun restore(r: TapLoop.Recipe) = TapLoop.Plans.restore(r)
            override fun reinforce(request: String, by: Int) { TapLoop.Plans.reinforce(request, by) }
            override fun unlearn(request: String, groups: Set<String>) = router.unlearn(request, groups)
            override fun learn(request: String, groups: Set<String>) = router.learn(request, groups)
        }
    }

    @After fun tearDown() { TurnFeedback.ports = TurnFeedback.RealPorts; TurnFeedback.init(null) }

    private val req = "turn on the wifi please"
    private fun step() = listOf(JSONObject().put("tool", "tap").put("args", JSONObject().put("text", "Wi-Fi")))

    private fun turn(gids: List<String> = listOf("dev-1", "dev-2")) {
        TapLoop.Plans.put(req, step())
        repeat(3) { router.learn(req, setOf("settings")) }
        TurnFeedback.record(gids, "main", req, plan = true, groups = setOf("settings"), fromSec = 1000, toSec = 1010)
    }

    @Test fun thumbsDownRemovesThePlanAndTheLessonsAndUndoRestoresThem() {
        turn()
        assertEquals(listOf("settings"), router.predict(req))
        // 👎 on the second bubble of the reply maps back to the same turn.
        val fx = TurnFeedback.react("dev-2", Reaction.DOWN)
        assertEquals(listOf(TurnFeedback.Effect.DropPlan, TurnFeedback.Effect.Unlearn), fx)
        assertNull(TapLoop.Plans.get(req))
        assertTrue(TurnFeedback.excluded("main", 1005))            // the idle review skips this turn
        assertFalse(TurnFeedback.excluded("other", 1005))
        assertFalse(TurnFeedback.excluded("main", 5000))
        // The same 👎 again (sync echo, double tap) does nothing.
        assertEquals(emptyList<TurnFeedback.Effect>(), TurnFeedback.react("dev-1", Reaction.DOWN))
        // Taking it back puts the plan back exactly and counts the turn again.
        TurnFeedback.react("dev-2", null)
        assertNotNull(TapLoop.Plans.get(req))
        assertEquals(1, TapLoop.Plans.get(req)!!.uses)
        assertFalse(TurnFeedback.excluded("main", 1005))
    }

    @Test fun thumbsUpReinforcesAndSwitchingIsClean() {
        turn()
        TurnFeedback.react("dev-1", Reaction.UP)
        assertEquals(2, TapLoop.Plans.get(req)!!.uses)
        // 👍 → 👎: the reinforcement is taken back before the plan is removed.
        val fx = TurnFeedback.react("dev-1", Reaction.DOWN)
        assertEquals(listOf(TurnFeedback.Effect.Unreinforce, TurnFeedback.Effect.DropPlan, TurnFeedback.Effect.Unlearn), fx)
        assertNull(TapLoop.Plans.get(req))
        TurnFeedback.react("dev-1", Reaction.UP)
        assertEquals(2, TapLoop.Plans.get(req)!!.uses)          // restored (1 use) + 👍
    }

    @Test fun recordsSurviveARestartAndUnknownBubblesDoNothing() {
        turn()
        TurnFeedback.react("dev-1", Reaction.DOWN)
        val f = tmp.root.resolve("fb.json")
        TurnFeedback.init(f)
        assertEquals(Reaction.DOWN, TurnFeedback.rec("dev-2")!!.state)
        assertNotNull(TurnFeedback.rec("dev-2")!!.removed)
        TurnFeedback.react("dev-2", null)
        assertNotNull(TapLoop.Plans.get(req))
        assertEquals(emptyList<TurnFeedback.Effect>(), TurnFeedback.react("nobody", Reaction.DOWN))
        // Private chats are never recorded.
        TurnFeedback.record(listOf("p-1"), PrivateChat.PREFIX + "x", req, true, null, 1, 2)
        assertNull(TurnFeedback.rec("p-1"))
    }

    @Test fun unlearnNeverGoesNegative() {
        val r = LearnedRouter()
        repeat(3) { r.learn("open my timetable file", setOf("files")) }
        assertEquals(listOf("files"), r.predict("timetable"))
        repeat(5) { r.unlearn("open my timetable file", setOf("files")) }
        assertEquals(emptyList<String>(), r.predict("timetable"))
        assertEquals(0, r.size())
    }

    @Test fun aTurnWithNothingLearnedHasNoEffects() {
        val rec = TurnFeedback.Rec(listOf("g"), "main", "hi", plan = false, groups = null, fromSec = 0, toSec = 0)
        assertEquals(emptyList<TurnFeedback.Effect>(), TurnFeedback.decide("", Reaction.DOWN, rec))
        assertEquals(emptyList<TurnFeedback.Effect>(), TurnFeedback.decide("", "❤️", rec))
    }
}
