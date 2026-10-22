package com.blaze.assistant.home

import com.blaze.assistant.agent.GatewayWeather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** v0.45.7 (user bug 07:28): Home weather showed °F and mph in Ndola. Metric outside the US; imperial only when picked or in the US. */
class HomeWeatherTest {
    private val ndola = -12.97 to 28.64
    private val chicago = 41.88 to -87.63

    @Test fun metricOutsideTheUsEvenOnAnEnUsPhone() {
        assertEquals("metric", GatewayWeather.unitsFor("", "US", ndola.first, ndola.second))
        assertEquals("metric", GatewayWeather.unitsFor("", "ZM", ndola.first, ndola.second))
        assertEquals("metric", GatewayWeather.unitsFor("", "LR", ndola.first, ndola.second))
        assertEquals("metric", GatewayWeather.unitsFor("", "GB", 51.5, -0.12))
        assertEquals("imperial", GatewayWeather.unitsFor("", "US", chicago.first, chicago.second))
        assertEquals("imperial", GatewayWeather.unitsFor("", "US", Double.NaN, Double.NaN))
        assertEquals("metric", GatewayWeather.unitsFor("", "ZM", chicago.first, chicago.second))
        // The user's own pick wins either way.
        assertEquals("imperial", GatewayWeather.unitsFor("f", "ZM", ndola.first, ndola.second))
        assertEquals("imperial", GatewayWeather.unitsFor("imperial", "ZM", ndola.first, ndola.second))
        assertEquals("metric", GatewayWeather.unitsFor("c", "US", chicago.first, chicago.second))
        assertTrue(GatewayWeather.inUs(21.3, -157.8)); assertTrue(GatewayWeather.inUs(61.2, -149.9)); assertFalse(GatewayWeather.inUs(ndola.first, ndola.second))
    }

    @Test fun theRequestAsksForMetric() {
        val u = GatewayWeather.unitsFor("", "US", ndola.first, ndola.second)
        val url = GatewayWeather.url("https://gw.example/v1", ndola.first, ndola.second, u)
        assertEquals("https://gw.example/v1/weather?lat=-12.97&lon=28.64&units=metric", url)
    }

    @Test fun theCardShowsCelsiusAndKmh() {
        val n = GatewayWeather.parse("""{"temperature":24.6,"feels_like":25.1,"temperature_unit":"°C","condition":"partly cloudy","is_day":true,
            "humidity_pct":40,"wind_speed":11.4,"wind_unit":"km/h","observed_at":"2026-10-21T05:30:00Z","source":"Open-Meteo"}""")!!
        val d = HomeWeather.toData(n, "Ndola", "metric", emptyList(), TimeZone.getTimeZone("Africa/Lusaka"))
        assertEquals("25°C", d.value)
        assertTrue(d.change, d.change.contains("wind 11 km/h"))
        assertTrue(d.change, d.change.contains("Open-Meteo · 07:30"))
        assertFalse(d.change.contains("mph") || d.value.contains("F"))
        // A gateway answer missing its unit labels still reads in the system that was asked for.
        val bare = n.copy(unit = "", windUnit = "")
        assertEquals("25°C", HomeWeather.toData(bare, "Ndola", "metric", emptyList()).value)
        assertTrue(HomeWeather.toData(bare, "Chicago", "imperial", emptyList()).change.contains("mph"))
        assertEquals("°F", GatewayWeather.tempLabel("imperial", "°C"))
        assertEquals("km/h", GatewayWeather.windLabel("metric", "mph"))
    }
}
