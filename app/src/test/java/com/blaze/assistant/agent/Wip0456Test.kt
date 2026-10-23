package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.45.6: the parked WIP (wrong-reply guard, What happened trace, warm-up, shared persona, weather) gets its tests. */
class Wip0456Test {
    @Test fun echoGuardCatchesThe1842Reply() {
        // 18:42: "Hi! Introduce yourself" while signed in came back as the Awareness block (heading, quote, a table of apps).
        val awareness = "## Live now\nCurrent Blaze UI: Chat (pull down from the top for Home)\n| Recent | App | When |\n| App | Terminal | 27 min ago |\n| Music | Blaze Music | 5 h ago |"
        val bad = "Current Blaze UI\n> Chat (pull down from the top for Home)\n| Recent | App | When |\n| App | Terminal | 27 min ago |"
        assertTrue(EchoGuard.echoes(bad, "Hi! Introduce yourself", listOf(awareness), emptyList()))
        assertFalse(EchoGuard.echoes("Hi Sam! I'm Blaze. I can chat, run your phone, play music and build things.", "Hi! Introduce yourself", listOf(awareness), emptyList()))
        assertFalse("asking for the status makes quoting it the answer", EchoGuard.echoes(bad, "what's going on right now?", listOf(awareness), emptyList()))
        assertTrue(EchoGuard.HONEST.isNotBlank() && EchoGuard.NUDGE.isNotBlank())
    }

    @Test fun warmUpIsThrottled() {
        WarmGate.reset()
        assertTrue(WarmGate.take(1_000_000))
        assertFalse(WarmGate.take(1_010_000))
        assertTrue(WarmGate.take(1_000_000 + WarmGate.GAP_MS))
        assertEquals("https://g.example/v1/warm", WarmGate.url("https://g.example/v1/"))
        WarmGate.reset()
    }

    @Test fun whatHappenedTraceRoundTrips() {
        val gw = mapOf("x-request-id" to "req-123", "x-blaze-model" to "gpt-oss", "x-blaze-provider" to "groq", "x-blaze-ttft-ms" to "640", "x-blaze-hedged" to "1")
        val t = TurnTrace.build(gw, "cloud", "fast", listOf("req-122", "req-123"), 812, 300, 2400, 2,
            listOf(TurnTrace.Tool("music_ops", "{\"action\":\"play\"}", "Playing in Blaze Music.")), emptyList())
        assertEquals("req-123", t.requestId); assertTrue(t.hedged); assertEquals(812, t.clientTtftMs)
        val back = TurnTrace.fromJson(TurnTrace.toJson(t))!!
        assertEquals(t.requestId, back.requestId); assertEquals("music_ops", back.tools.single().name); assertEquals("gpt-oss", back.model)
        assertTrue(TurnTrace.copyText(t).contains("req-123"))
        assertNull(TurnTrace.fromJson("not json"))
    }

    @Test fun sharedPersonaBundledCopyParsesAndRefreshesDaily() {
        val asset = File("src/main/assets/persona-v1.json").takeIf { it.exists() } ?: File("app/src/main/assets/persona-v1.json")
        val p = SharedPersona.parse(asset.readText())
        assertNotNull(p); assertTrue(p!!.system.startsWith("You are Blaze"))
        assertTrue("evals become regressions", p.evals.isNotEmpty())
        assertEquals(p, SharedPersona.pick("{broken", asset.readText()))
        assertTrue(SharedPersona.due(0, 5)); assertFalse(SharedPersona.due(1000, 1000 + SharedPersona.DAY_MS - 1)); assertTrue(SharedPersona.due(1000, 1000 + SharedPersona.DAY_MS))
        assertTrue(SharedPersona.applies("blaze")); assertFalse(SharedPersona.applies("pirate"))
        assertEquals("App context for Blaze on the user's Android phone. User's first name: Sam. Local time: 9:40 PM on Tuesday. ", SharedPersona.contextLine("Sam Banda", "9:40 PM on Tuesday"))
        assertTrue(SharedPersona.MIN_MAX_TOKENS >= 400)
    }

    @Test fun weatherHasOneSourceAndNeverGuesses() {
        assertEquals("metric", GatewayWeather.units("", "ZM")); assertEquals("imperial", GatewayWeather.units("", "US")); assertEquals("imperial", GatewayWeather.units("F", "ZM"))
        assertEquals("https://g/v1/weather?lat=-12.97&lon=28.64&units=metric", GatewayWeather.url("https://g/v1", -12.9688, 28.6366, "metric"))
        val n = GatewayWeather.parse("""{"temperature":24.4,"feels_like":25,"temperature_unit":"°C","condition":"clear night","is_day":false,"humidity_pct":40,"wind_speed":9,"wind_unit":"km/h","observed_at":"2026-10-20T19:45:00Z","source":"Open-Meteo"}""")!!
        val s = GatewayWeather.forModel(n, java.util.TimeZone.getTimeZone("Africa/Lusaka"))
        assertTrue(s, s.startsWith("24°C, clear night (it's night)") && s.contains("Source: Open-Meteo · 21:45"))
        assertNull(GatewayWeather.parse("{\"error\":\"weather_unavailable\"}"))
        assertTrue(GatewayWeather.UNAVAILABLE.contains("don't guess"))
        assertTrue(GatewayWeather.SYSTEM_LINE.contains("get_weather"))
    }
}
