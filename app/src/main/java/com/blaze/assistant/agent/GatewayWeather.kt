package com.blaze.assistant.agent

import org.json.JSONObject
import java.util.Locale

/**
 * v0.45.5 (#7/7b): one weather source for chat and the Home card (user rule: "for weather we stick to a reliable
 * source always"). Gateway 0.6.8+ GET /v1/weather?lat=&lon=[&units=] (Open-Meteo, MET Norway fallback, cached 10 min per
 * 0.01° cell, day/night aware, source + observed_at on every answer). A 503 weather_unavailable means say so, never guess.
 * Chat calls it through the get_weather tool (Site's schema); Home calls [fetch] with the same rounding and units.
 * Pure parts tested in GatewayWeatherTest.
 */
object GatewayWeather {
    const val TOOL = "get_weather"
    const val SYSTEM_LINE = "\nFor weather, call get_weather and quote its source and time. Never use web search or guesses for weather. "
    const val UNAVAILABLE = "Weather is unavailable right now (both weather sources failed). Tell the user that plainly; don't guess."

    data class Now(val temperature: Double, val feelsLike: Double, val unit: String, val condition: String, val isDay: Boolean,
                   val humidity: Int, val wind: Double, val windUnit: String, val observedAt: String, val source: String, val summary: String)

    fun tool(): JSONObject = JSONObject("""{"type":"function","function":{"name":"get_weather","description":"Current weather for a place. ALWAYS use this for any weather question; never answer weather from web search or memory. Report temperature, condition, and the source + observed time it returns.","parameters":{"type":"object","properties":{"lat":{"type":"number"},"lon":{"type":"number"},"units":{"type":"string","enum":["metric","imperial"]}},"required":["lat","lon"]}}}""")

    /** Same 0.01° rounding everywhere, so chat and Home hit the same cache cell. */
    fun round(v: Double): String = "%.2f".format(Locale.US, v)

    /** metric unless the user picked imperial, or the phone is set to a °F country and they picked nothing. */
    fun units(pref: String, country: String): String = when {
        pref.startsWith("f", true) || pref.equals("imperial", true) -> "imperial"
        pref.startsWith("c", true) || pref.equals("metric", true) -> "metric"
        country.uppercase() in setOf("US", "LR", "MM") -> "imperial"
        else -> "metric"
    }

    /**
     * v0.45.7 (user rule: metric outside the US). Imperial only when the user picked it, or when the phone's locale is
     * US AND the place is in the US (or no place is known yet). A phone set to en-US in Ndola gets °C and km/h.
     */
    fun unitsFor(pref: String, country: String, lat: Double, lon: Double): String = when {
        pref.startsWith("f", true) || pref.equals("imperial", true) -> "imperial"
        pref.startsWith("c", true) || pref.equals("metric", true) -> "metric"
        country.uppercase() == "US" && (lat.isNaN() || lon.isNaN() || inUs(lat, lon)) -> "imperial"
        else -> "metric"
    }

    /** Rough boxes: the lower 48, Alaska, Hawaii, Puerto Rico. Enough to tell the US from Zambia. */
    fun inUs(lat: Double, lon: Double): Boolean =
        (lat in 24.4..49.5 && lon in -125.0..-66.8) || (lat in 51.0..71.5 && lon in -170.0..-129.0) ||
            (lat in 18.5..22.5 && lon in -161.0..-154.5) || (lat in 17.8..18.6 && lon in -67.3..-65.2)

    /** The labels the card shows for [units] (the gateway's own labels win when it sends them in the same system). */
    fun tempLabel(units: String, fromGateway: String = ""): String =
        if (units == "imperial") (fromGateway.takeIf { it.contains("F") } ?: "°F") else (fromGateway.takeIf { it.contains("C") } ?: "°C")
    fun windLabel(units: String, fromGateway: String = ""): String =
        if (units == "imperial") (fromGateway.takeIf { it.contains("mph", true) } ?: "mph") else (fromGateway.takeIf { it.contains("km", true) } ?: "km/h")

    fun url(base: String, lat: Double, lon: Double, units: String): String =
        base.trimEnd('/') + "/weather?lat=" + round(lat) + "&lon=" + round(lon) + "&units=" + units

    fun parse(json: String): Now? = try {
        val o = JSONObject(json)
        if (!o.has("temperature")) null else Now(o.getDouble("temperature"), o.optDouble("feels_like", Double.NaN), o.optString("temperature_unit", "°C"),
            o.optString("condition"), o.optBoolean("is_day", true), o.optInt("humidity_pct", -1), o.optDouble("wind_speed", Double.NaN),
            o.optString("wind_unit", "km/h"), o.optString("observed_at"), o.optString("source"), o.optString("summary"))
    } catch (_: Exception) { null }

    /** "18:45" in the phone's zone from an ISO observed_at ("" when it doesn't parse). */
    fun localTime(iso: String, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String = try {
        val inF = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val d = inF.parse(iso.take(19))!!
        java.text.SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }.format(d)
    } catch (_: Exception) { "" }

    /** What the model reads: the numbers plus "source · local time" to quote. */
    fun forModel(n: Now, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val t = localTime(n.observedAt, tz)
        return "${Math.round(n.temperature)}${n.unit}, ${n.condition}" + (if (!n.isDay) " (it's night)" else "") +
            (if (!n.feelsLike.isNaN()) ", feels ${Math.round(n.feelsLike)}${n.unit}" else "") +
            (if (n.humidity >= 0) ", humidity ${n.humidity}%" else "") + (if (!n.wind.isNaN()) ", wind ${Math.round(n.wind)} ${n.windUnit}" else "") +
            ". Source: ${n.source.ifBlank { "Blaze weather" }}" + (if (t.isNotBlank()) " · $t" else "") + ". Quote the source and time."
    }

    /** Raw GET; returns (code, body). Blocking: call off the main thread. */
    fun fetch(lat: Double, lon: Double, units: String, token: String, base: String = Providers.gatewayBase): Pair<Int, String> {
        val c = com.blaze.assistant.connect.UrlSafety.open(url(base, lat, lon, units))
        c.connectTimeout = 8000; c.readTimeout = 10000
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        return code to body
    }

    /** The tool: [lat]/[lon] from the model, else the phone's last fix ([here]). */
    fun run(args: JSONObject, token: String, here: () -> Pair<Double, Double>?): String {
        var lat = args.optDouble("lat", Double.NaN); var lon = args.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) here()?.let { lat = it.first; lon = it.second }
        if (lat.isNaN() || lon.isNaN()) return "No location: call get_location (or ask which place), then call get_weather with lat and lon."
        if (token.isBlank()) return "Weather needs the Blaze sign-in. Ask the user to sign in to Blaze; don't guess the weather."
        val units = unitsFor(args.optString("units"), Locale.getDefault().country, lat, lon)   // v0.45.7: metric outside the US
        val (code, body) = try { fetch(lat, lon, units, token) } catch (e: Exception) { return UNAVAILABLE }
        if (code == 503 || code !in 200..299) return UNAVAILABLE
        return parse(body)?.let { forModel(it) } ?: UNAVAILABLE
    }

}
