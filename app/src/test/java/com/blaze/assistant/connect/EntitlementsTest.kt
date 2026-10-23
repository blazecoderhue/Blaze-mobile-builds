package com.blaze.assistant.connect

import com.blaze.assistant.connect.Entitlements.Gate
import com.blaze.assistant.mediaapps.DlJob
import com.blaze.assistant.mediaapps.DlLogic
import com.blaze.assistant.mediaapps.DlPart
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** v0.45.18 usage view against Site's GET /v1/entitlements contract (gateway 0.8.38, ENTITLEMENTS_CONTRACT.md). */
class EntitlementsTest {
    private fun body(plan: String, songL: Int, songU: Int, vidL: Int, vidU: Int, par: String, unl: Boolean = false) =
        """{"v":1,"sub":"u1","email":"a@b.c","plan":"$plan","features":${if (unl) "[\"unlimited\"]" else "[]"},
        "downloads":{"song":{"limit":$songL,"used":$songU,"remaining":${if (songL < 0) -1 else songL - songU},"unlimited":${songL < 0}},
                     "video":{"limit":$vidL,"used":$vidU,"remaining":${if (vidL < 0) -1 else vidL - vidU},"unlimited":${vidL < 0}}},
        "max_parallel_downloads":$par,"month":"2026-10","reset_at":"2026-11-01T00:00:00.000Z","reset_at_ms":1793491200000,
        "iat":1792719904272,"exp":1792720804272,"payload":"x","sig":"y"}"""

    private val lusaka = ZoneId.of("Africa/Lusaka")

    @Test fun basicPlanFromServerNumbers() {
        val s = Entitlements.parse(body("basic", 100, 3, 10, 1, "1"))!!
        assertEquals("basic", s.plan)
        assertEquals("97 of 100 left", Entitlements.line(s.song!!))
        assertEquals("9 of 10 left", Entitlements.line(s.video!!))
        assertEquals("1 download at a time", Entitlements.parallelLine(s))
        assertEquals("Resets Nov 1", Entitlements.resetLine(s.resetAtMs, lusaka, Locale.US))
        assertEquals(1792720804272L, s.expMs)
        assertEquals("", Entitlements.nudge(s))
    }

    @Test fun everyPlanShowsItsOwnLimitsNotHardcoded() {
        for ((plan, songs, videos, par) in listOf(Quad("basic", 100, 10, 1), Quad("standard", 500, 30, 2), Quad("pro", 2000, 50, 4), Quad("someday", 777, 7, 3))) {
            val s = Entitlements.parse(body(plan, songs, 0, videos, 0, par.toString()))!!
            assertEquals("${String.format(Locale.US, "%,d", songs)} of ${String.format(Locale.US, "%,d", songs)} left", Entitlements.line(s.song!!))
            assertEquals("$videos of $videos left", Entitlements.line(s.video!!))
            assertEquals(par, s.maxParallel)
        }
        assertEquals("2,000 of 2,000 left", Entitlements.line(Entitlements.parse(body("pro", 2000, 0, 50, 0, "4"))!!.song!!))
    }

    @Test fun hiddenTierIsUnlimitedAndNeverNudged() {
        val s = Entitlements.parse(body("lifetime", -1, 60, -1, 60, "null", unl = true))!!
        assertTrue(s.song!!.unlimited); assertTrue(s.video!!.unlimited)
        assertEquals("Unlimited", Entitlements.line(s.song!!))
        assertNull(s.maxParallel); assertTrue(s.parallelUncapped)
        assertEquals("No limit on downloads at a time", Entitlements.parallelLine(s))
        assertEquals("", Entitlements.nudge(s)); assertEquals("", Entitlements.sheetLine(s, "video")); assertEquals("", Entitlements.sheetLine(s, "audio"))
    }

    @Test fun nudgeOnlyNearALimit() {
        assertEquals("", Entitlements.nudge(Entitlements.parse(body("basic", 100, 89, 10, 8, "1"))!!))     // 11 songs, 2 videos left
        assertTrue(Entitlements.near(Entitlements.parse(body("basic", 100, 89, 10, 9, "1"))!!.video))     // 1 video left
        assertEquals("You're close to this month's video downloads. A bigger plan gives you more.", Entitlements.nudge(Entitlements.parse(body("basic", 100, 0, 10, 9, "1"))!!))
        assertEquals("You've used this month's song downloads. A bigger plan gives you more.", Entitlements.nudge(Entitlements.parse(body("basic", 100, 100, 10, 0, "1"))!!))
        assertTrue(Entitlements.near(Entitlements.parse(body("pro", 2000, 1995, 50, 0, "4"))!!.song))    // 5 of 2,000 left
        assertFalse(Entitlements.near(Entitlements.parse(body("pro", 2000, 1994, 50, 0, "4"))!!.song))
    }

    @Test fun sheetLinesSayWhatIsLeft() {
        val s = Entitlements.parse(body("basic", 100, 3, 10, 9, "1"))!!
        assertEquals("1 video download left this month", Entitlements.sheetLine(s, "video"))
        assertEquals("97 song downloads left this month", Entitlements.sheetLine(s, "audio"))
        val none = Entitlements.parse(body("basic", 100, 3, 10, 10, "1"))!!
        assertTrue(Entitlements.sheetLine(none, "video").startsWith("No video downloads left this month. Resets "))
        assertEquals("", Entitlements.sheetLine(null, "video"))
    }

    @Test fun consumeAnswersDecideTheDownload() {
        assertEquals(Gate.Ok, Entitlements.gateFor(200, """{"ok":true,"kind":"video","used":2,"limit":10,"remaining":8}"""))
        val msg = "You've used all 10 video downloads on your plan this month. They reset on 2026-11-01."
        assertEquals(Gate.Blocked(msg), Entitlements.gateFor(429, """{"error":{"code":"download_quota_exceeded","kind":"video","limit":10,"used":10,"remaining":0,"message":"$msg"}}"""))
        assertEquals(Gate.Skip, Entitlements.gateFor(401, "{}"))
        assertTrue(Entitlements.gateFor(503, "") is Gate.Offline)
        assertTrue(Entitlements.gateFor(400, """{"error":{"code":"bad_kind"}}""") is Gate.Blocked)
        assertEquals("song", Entitlements.kindOf("audio")); assertEquals("video", Entitlements.kindOf("video"))
    }

    @Test fun staleAfterExpOrFifteenMinutes() {
        val s = Entitlements.parse(body("basic", 100, 3, 10, 1, "1"))!!
        assertTrue(Entitlements.stale(null, 0, 1))
        assertFalse(Entitlements.stale(s, 1792719904272L, 1792719904272L + 60_000))
        assertTrue(Entitlements.stale(s, 1792719904272L, 1792720804272L))
        assertNull(Entitlements.parse("""{"error":{"code":"invalid_api_key"}}"""))
        assertNull(Entitlements.parse("not json"))
    }

    @Test fun downloadIsCountedOnceAndOldJobsAreNotCharged() {
        val part = DlPart("140", "u", 100, 0, "m4a")
        val j = DlJob("id1", "v", "audio", "t", "c", 0, "", "q", "cid", "m4a", listOf(part))
        assertFalse(j.counted)
        assertTrue(DlLogic.fromJson(DlLogic.toJson(j.copy(counted = true))).counted)
        assertFalse(DlLogic.fromJson(DlLogic.toJson(j)).counted)
        val legacy = DlLogic.toJson(j.copy(parts = listOf(part.copy(done = 40)))).apply { remove("cnt") }
        assertTrue(DlLogic.fromJson(JSONObject(legacy.toString())).counted)          // started before counting existed
        val legacyFresh = DlLogic.toJson(j).apply { remove("cnt") }
        assertFalse(DlLogic.fromJson(JSONObject(legacyFresh.toString())).counted)
    }

    /** Strict 5/5: the same server answers give the same screen every time. */
    @Test fun strictFiveOfFive() {
        repeat(5) {
            val s = Entitlements.parse(body("standard", 500, 120, 30, 28, "2"))
            assertNotNull(s)
            assertEquals("380 of 500 left", Entitlements.line(s!!.song!!))
            assertEquals("2 of 30 left", Entitlements.line(s.video!!))
            assertEquals("2 downloads at a time", Entitlements.parallelLine(s))
            assertEquals("You're close to this month's video downloads. A bigger plan gives you more.", Entitlements.nudge(s))
        }
    }

    private data class Quad(val a: String, val b: Int, val c: Int, val d: Int)
}
