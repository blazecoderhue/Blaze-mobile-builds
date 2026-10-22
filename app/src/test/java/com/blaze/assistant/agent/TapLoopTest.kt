package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.30.7 faster phone actions. The v0.30.4 trace of "stop GitHub opening links in the GitHub app" took 18 steps and
 * 4 min 25 s (one model round trip per tap). These are the pure rules that let the phone run the plan itself.
 */
class TapLoopTest {

    private fun o(s: String) = JSONObject(s)

    @Test fun failedResults() {
        for (r in listOf("Error: no steps.", "Couldn't tap [3].", "No element [9]. Call read_screen again", "No app called Foo",
            "No installed app matches \u201cFoo\u201d.", "Unknown settings page \u201cx\u201d.", "Not done yet: I looked at the screen first",
            "Stopped before tapping [3]: the user tapped Stop. Nothing was tapped.", "The user did NOT approve tapping \"Pay\".",
            "The target [4] went away while Blaze was aiming", "Phone control isn't connected.", ""))
            assertTrue(r, TapLoop.failed(r))
        for (r in listOf("Tapped \"Open supported links\".", "Opened GitHub \u203a Open by default (direct). Screen now:\n…",
            "Typed.", "Scrolled down.", "Already off: \u201cOpen supported links\u201d. Not tapped, it's already how you wanted it.", "Opened YouTube."))
            assertFalse(r, TapLoop.failed(r))
    }

    @Test fun switchesAreOnlyTappedWhenNeeded() {
        assertEquals(true, TapLoop.wantOf("on")); assertEquals(false, TapLoop.wantOf("OFF")); assertEquals(true, TapLoop.wantOf("enable"))
        assertNull(TapLoop.wantOf("")); assertNull(TapLoop.wantOf(null)); assertNull(TapLoop.wantOf("maybe"))
        assertFalse("already off: no tap", TapLoop.needsTap(false, false))
        assertTrue("on → off needs the tap", TapLoop.needsTap(false, true))
        assertTrue("not a switch: always tap", TapLoop.needsTap(false, null))
        assertTrue("no want: always tap", TapLoop.needsTap(null, false))
        assertEquals("on", TapLoop.wantWord(true)); assertEquals("off", TapLoop.wantWord(false))
    }

    @Test fun settleWaitsForTheNextTargetInstead() {
        val tap = o("""{"do":"tap","target":"Open supported links"}""")
        assertEquals(450L to 120L, TapLoop.settleAfter("tap", tap))
        assertEquals(1200L to 180L, TapLoop.settleAfter("tap", null))
        assertEquals(1200L to 180L, TapLoop.settleAfter("tap", o("""{"do":"back"}""")))
        assertEquals(1200L to 180L, TapLoop.settleAfter("tap", o("""{"do":"tap"}""")))
        assertEquals(600L to 150L, TapLoop.settleAfter("open_app", tap))
        assertEquals(1500L to 250L, TapLoop.settleAfter("open_app", null))
        assertEquals(0L to 0L, TapLoop.settleAfter("wait", tap))
        // The next step's own wait is longer after a short settle, so nothing is cut short overall.
        assertTrue(TapLoop.waitMs(true) >= TapLoop.waitMs(false) + 450)
        assertTrue(TapLoop.waitMs(true, field = true) > TapLoop.waitMs(false, field = true))
    }

    @Test fun noCompactionMidPhoneTask() {
        assertTrue(TapLoop.compactNow(0, false)); assertTrue(TapLoop.compactNow(0, true))
        assertTrue("a turn that never touched the screen still compacts", TapLoop.compactNow(3, false))
        assertFalse(TapLoop.compactNow(1, true)); assertFalse(TapLoop.compactNow(7, true))
    }

    @Test fun bareRowsLeaveTheReadout() {
        assertTrue(TapLoop.bareRow("[12] item")); assertTrue(TapLoop.bareRow("[3] item disabled"))
        for (l in listOf("[12] item \"Wi-Fi\"", "[4] item selected", "[5] btn", "[6] list scrolls", "[7] switch on", "[8] text \"Apps\""))
            assertFalse(l, TapLoop.bareRow(l))
    }

    @Test fun stepsFromTools() {
        val a = TapLoop.stepOf("open_app", o("""{"app_name":"YouTube"}"""), "", null)!!
        assertEquals("open_app", a.getString("do")); assertEquals("YouTube", a.getString("app"))
        val st = TapLoop.stepOf("open_settings", o("""{"page":"open_by_default","app":"GitHub"}"""), "", null)!!
        assertEquals("open_settings", st.getString("do")); assertEquals("open_by_default", st.getString("page")); assertEquals("GitHub", st.getString("app"))
        assertNull(TapLoop.stepOf("open_settings", o("""{}"""), "", null))
        val t = TapLoop.stepOf("tap", o("""{"text":"Open supported links"}"""), "", false)!!
        assertEquals("tap", t.getString("do")); assertEquals("Open supported links", t.getString("target")); assertEquals("off", t.getString("want"))
        assertEquals("the model's own want wins", "on", TapLoop.stepOf("tap", o("""{"text":"Wi-Fi","want":"on"}"""), "", false)!!.getString("want"))
        assertEquals("a numbered tap replays by its words", "Notifications", TapLoop.stepOf("tap", o("""{"element":7}"""), "Notifications", null)!!.getString("target"))
        assertFalse(TapLoop.stepOf("tap", o("""{"element":7}"""), "Notifications", null)!!.has("want"))
        assertNull("an unlabelled tap can't be replayed", TapLoop.stepOf("tap", o("""{"element":7}"""), "", null))
        assertEquals("back", TapLoop.stepOf("press_button", o("""{"button":"back"}"""), "", null)!!.getString("do"))
        assertNull(TapLoop.stepOf("press_button", o("""{"button":"volume_up"}"""), "", null))
        assertEquals("type", TapLoop.stepOf("type_text", o("""{"element":2,"text":"lofi","submit":true}"""), "Search", null)!!.getString("do"))
    }

    @Test fun recorderKeepsOnlyCleanEndings() {
        val r = TapLoop.Recorder()
        assertNull("nothing done", r.recipe())
        r.ok(o("""{"do":"open_settings","page":"open_by_default","app":"GitHub"}"""))
        r.fail()
        assertNull("ended on a failure", r.recipe())
        r.ok(o("""{"do":"tap","target":"Open supported links","want":"off"}"""))
        assertEquals(2, r.recipe()!!.size)
        r.ok(null)
        assertNull("an unreplayable step spoils it", r.recipe())
        r.reset()
        r.ok(o("""{"do":"open_app","app":"WhatsApp"}""")); r.ok(o("""{"do":"tap","target":"Send"}"""))
        assertNull("never keeps a plan that sends / pays / deletes", r.recipe())
        r.reset()
        repeat(TapLoop.MAX_STEPS + 1) { r.ok(o("""{"do":"scroll","direction":"down"}""")) }
        assertNull("too long", r.recipe())
    }

    @Test fun requestsMatchOnlyTheSameTask() {
        val k = TapLoop.key("Stop GitHub opening links in the GitHub app")
        assertEquals(k, TapLoop.key("please stop github opening links in the github app"))
        assertTrue(TapLoop.sameTask(k, TapLoop.key("stop GitHub from opening links")))
        assertTrue(TapLoop.sameTask(TapLoop.key("turn on wifi"), TapLoop.key("wifi turn on")))
        assertFalse("opposite", TapLoop.sameTask(TapLoop.key("turn on wifi"), TapLoop.key("turn off wifi")))
        assertFalse("opposite", TapLoop.sameTask(TapLoop.key("allow chrome to use location"), TapLoop.key("block chrome to use location")))
        assertFalse("other app", TapLoop.sameTask(TapLoop.key("disable notifications for WhatsApp"), TapLoop.key("disable notifications for Telegram")))
        assertFalse("other number", TapLoop.sameTask(TapLoop.key("set screen timeout to 2 minutes"), TapLoop.key("set screen timeout to 5 minutes")))
        assertFalse(TapLoop.sameTask("", k))
    }

    @Test fun plansRoundTripAndHint() {
        val f = File.createTempFile("plan_memory", ".json"); f.delete()
        TapLoop.Plans.init(f)
        assertNull(TapLoop.Plans.find("stop GitHub opening links"))
        val steps = listOf(o("""{"do":"open_settings","page":"open_by_default","app":"GitHub"}"""), o("""{"do":"tap","target":"Open supported links","want":"off"}"""))
        TapLoop.Plans.put("Stop GitHub opening links in the GitHub app", steps, now = 1000)
        TapLoop.Plans.init(f)   // reload from disk
        val r = TapLoop.Plans.find("stop github from opening links")
        assertNotNull(r)
        assertEquals(2, r!!.steps.length())
        assertNull(TapLoop.Plans.find("let GitHub open links in the GitHub app"))
        val h = TapLoop.hint(r)
        assertTrue(h, h.startsWith("[Done before]") && h.contains("run_plan") && h.contains("\"want\":\"off\"") && h.contains("open_by_default"))
        // The hint parses back into a valid run_plan argument.
        val json = h.substring(h.indexOf("{\"steps\""), h.lastIndexOf("}") + 1)
        assertEquals(2, JSONObject(json).getJSONArray("steps").length())
        TapLoop.Plans.put("Stop GitHub opening links in the GitHub app", steps, now = 2000)
        assertEquals(2, TapLoop.Plans.find("stop github opening links github")!!.uses)
        TapLoop.Plans.drop("Stop GitHub opening links in the GitHub app"); assertEquals(0, TapLoop.Plans.size())
        f.delete()
    }

    @Test fun orientNoLongerAsksForAReplyPerTap() {
        val plan = JSONObject().put("steps", JSONArray().put(JSONObject().put("do", "open_settings").put("page", "open_by_default").put("app", "GitHub")))
        assertTrue("run_plan steps say do, not action", Orient.startsByOpening(plan))
        assertTrue(Orient.startsByOpening(JSONObject().put("steps", JSONArray().put(JSONObject().put("do", "open_app").put("app", "YouTube")))))
        assertTrue(Orient.startsByOpening(JSONObject().put("steps", JSONArray().put(JSONObject().put("action", "open_app")))))
        assertFalse(Orient.startsByOpening(JSONObject().put("steps", JSONArray().put(JSONObject().put("do", "tap").put("target", "x")))))
        assertEquals(Orient.Decision.GO, Orient.decide("run_plan", plan, Orient.State(), "com.android.settings"))
        assertFalse(Orient.contextNote("Screen: Settings").contains("after each action"))
        assertTrue(Orient.RULE.contains("ONE reply"))
        assertTrue(FastActions.RULE.contains("want="))
    }

    /**
     * Model calls and time for the trace's task, before and after (estimate from the v0.30.4 trace: ≈ 7 s per model
     * round trip incl. the step around it, ≈ 5.5 s of it the call). Settings tasks: v0.30.4 = 18 calls;
     * now open_settings + tap in one run_plan, then the one-line answer.
     */
    @Test fun estimatedCallsForTheTraceTask() {
        val before = 18
        val firstTime = 1 /* plan: open_settings + tap want=off */ + 1 /* answer */
        val replay = 1 /* the remembered run_plan */ + 1 /* answer */
        assertTrue(firstTime <= 3 && replay <= 3)
        val callS = 5.5; val onPhoneS = 3.5   // settings intent ≈ 1.5 s + reveal / tap ≈ 2 s
        assertTrue("≈ ${before * 7} s → ≈ ${firstTime * callS + onPhoneS} s", firstTime * callS + onPhoneS < 20.0)
    }
}
