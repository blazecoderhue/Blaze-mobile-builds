package com.blaze.assistant.home

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * v0.29.0: parsers for the Home board's free, keyless sources. Pure (strings in, [WidgetData] / small values out) so they
 * are tested on fixture data (HomeParsersTest). The fetching lives in [HomeSources].
 */
object Rss {
    data class Entry(val title: String, val link: String, val source: String, val published: Long, val videoId: String = "", val image: String = "")

    private val itemRe = Regex("(?is)<(item|entry)\\b[^>]*>(.*?)</\\1>")

    /** RSS 2.0 `<item>` or Atom `<entry>` (YouTube channel feeds), newest first as the feed gives them. */
    fun parse(xml: String, max: Int = 20): List<Entry> = itemRe.findAll(xml).take(max).mapNotNull { m ->
        val b = m.groupValues[2]
        val title = text(tag(b, "title")).ifBlank { return@mapNotNull null }
        val link = tag(b, "link").let { text(it) }.ifBlank { attr(b, "link", "href") }
        val source = text(tag(b, "source")).ifBlank { text(tag(tag(b, "author"), "name")) }
        val date = tag(b, "pubDate").ifBlank { tag(b, "published") }.ifBlank { tag(b, "updated") }.ifBlank { tag(b, "dc:date") }
        val vid = text(tag(b, "yt:videoId"))
        val source2 = source.ifBlank { text(tag(b, "News:Source")) }
        Entry(cleanTitle(title, source2), link, source2, Dates.parse(text(date)), vid, image(b, vid))
    }.toList()

    /**
     * v0.29.3: the picture for one feed item: media:thumbnail / media:content, an image enclosure, Bing's News:Image, an
     * <img> in the description, or a YouTube thumbnail. Always https (http thumbnails are rewritten, others dropped).
     */
    fun image(b: String, videoId: String = ""): String {
        if (videoId.isNotBlank()) return "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        val raw = attr(b, "media:thumbnail", "url").ifBlank { attr(b, "media:content", "url") }
            .ifBlank { Regex("(?is)<enclosure\\b[^>]*type=\"image[^>]*>").find(b)?.value?.let { Regex("(?i)url\\s*=\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }.orEmpty() }
            .ifBlank { text(tag(b, "News:Image")) }
            .ifBlank { Regex("(?i)<img[^>]+src=[\"']([^\"']+)").find(unescape(Regex("(?s)<!\\[CDATA\\[(.*?)]]>").replace(tag(b, "description")) { it.groupValues[1] }))?.groupValues?.get(1).orEmpty() }
        val u = unescape(raw.trim())
        return when {
            u.contains("bing.com/th?") -> "https://" + u.substringAfter("://") + "&w=480&h=270&c=7"
            u.startsWith("https://") -> u
            u.startsWith("http://") -> "https://" + u.removePrefix("http://")
            u.startsWith("//") -> "https:$u"
            else -> ""
        }
    }

    /** Words of a headline for matching the same story across two feeds. */
    fun words(t: String): Set<String> = t.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

    /** Gives entries without a picture the picture of the same story in [other] (at least 60% of the shorter headline's words shared). */
    fun borrowImages(entries: List<Entry>, other: List<Entry>): List<Entry> {
        val pics = other.filter { it.image.isNotBlank() }.map { words(it.title) to it.image }
        if (pics.isEmpty()) return entries
        return entries.map { e ->
            if (e.image.isNotBlank()) e else {
                val w = words(e.title)
                val best = pics.maxByOrNull { (o, _) -> (w intersect o).size.toDouble() / maxOf(1, minOf(w.size, o.size)) }
                val score = best?.let { (o, _) -> (w intersect o).size.toDouble() / maxOf(1, minOf(w.size, o.size)) } ?: 0.0
                if (best != null && score >= 0.6 && w.size >= 3) e.copy(image = best.second) else e
            }
        }
    }

    /** The feed's own title (channel / feed name). */
    fun feedTitle(xml: String): String {
        val head = xml.substringBefore("<item").substringBefore("<entry")
        return text(tag(head, "title"))
    }

    /** Google News appends " - Source" to every title; drop it when the source is shown separately. */
    fun cleanTitle(t: String, source: String): String =
        if (source.isNotBlank() && t.endsWith(" - $source")) t.removeSuffix(" - $source").trim() else t

    private fun tag(s: String, name: String): String {
        val r = Regex("(?is)<" + Regex.escape(name) + "\\b[^>]*?(?:/>|>(.*?)</" + Regex.escape(name) + ">)")
        return r.find(s)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun attr(s: String, name: String, a: String): String {
        val tags = Regex("(?is)<" + Regex.escape(name) + "\\b([^>]*)>").findAll(s).map { it.groupValues[1] }.toList()
        val pick = tags.firstOrNull { it.contains("rel=\"alternate\"") } ?: tags.firstOrNull() ?: return ""
        return Regex("(?i)\\b" + a + "\\s*=\\s*\"([^\"]*)\"").find(pick)?.groupValues?.get(1)?.let(::unescape).orEmpty()
    }

    /** CDATA unwrapped, tags stripped, entities decoded, whitespace squashed. */
    fun text(s: String): String {
        var t = s.trim()
        t = Regex("(?s)<!\\[CDATA\\[(.*?)]]>").replace(t) { it.groupValues[1] }
        t = unescape(t)
        t = Regex("(?s)<[^>]+>").replace(t, " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&amp;", "&")

    /** Entries → widget content: "N new" headline when entries are newer than [seenAfter]. */
    fun toData(entries: List<Entry>, headline: String, now: Long, seenAfter: Long = 0L, video: Boolean = false): WidgetData {
        val fresh = if (seenAfter > 0) entries.count { it.published > seenAfter } else 0
        return WidgetData(
            headline = headline,
            value = if (video && fresh > 0) (if (fresh == 1) "1 new video" else "$fresh new videos") else "",
            lines = entries.map { e ->
                WidgetLine(e.title, listOf(e.source.takeIf { !video }.orEmpty(), Dates.relative(e.published, now)).filter { it.isNotBlank() }.joinToString(" · "),
                    e.link, if (video && seenAfter > 0 && e.published > seenAfter) "New" else "", e.image)
            })
    }
}

/** Dates in feeds and APIs: RFC 822 (RSS), ISO 8601 (Atom, APIs). 0 when unreadable. */
object Dates {
    private val rfc = listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm zzz")
    private val iso = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mmXXX")

    fun parse(s: String): Long {
        val t = s.trim(); if (t.isEmpty()) return 0L
        for (p in rfc) try { return java.text.SimpleDateFormat(p, Locale.US).parse(t)?.time ?: continue } catch (_: Exception) { }
        val z = t.replace(Regex("Z$"), "+00:00")
        for (p in iso) try { return java.text.SimpleDateFormat(p, Locale.US).parse(z)?.time ?: continue } catch (_: Exception) { }
        return utc(t)
    }

    /** "2026-10-18T15:30:00" / "2026-10-18 15:30:00" / "2026-10-18" with no zone = UTC. */
    fun utc(s: String): Long {
        val t = s.trim().replace(' ', 'T')
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd")) try {
            val f = java.text.SimpleDateFormat(p, Locale.US); f.timeZone = TimeZone.getTimeZone("UTC")
            return f.parse(t)?.time ?: continue
        } catch (_: Exception) { }
        return 0L
    }

    fun relative(t: Long, now: Long): String {
        if (t <= 0L) return ""
        val m = (now - t) / 60_000L
        return when {
            m < 0 -> ""
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> "${m / 60} h ago"
            m < 7 * 24 * 60 -> "${m / (24 * 60)} d ago"
            else -> java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(t))
        }
    }

    /** "Sat 18 Oct · 17:30" in the phone's zone. */
    fun dayTime(t: Long, tz: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
        if (t <= 0L) return ""
        val f = java.text.SimpleDateFormat("EEE d MMM · HH:mm", locale); f.timeZone = tz
        return f.format(java.util.Date(t))
    }
}

/** Open-Meteo forecast + geocoding (no key). */
object OpenMeteo {
    data class Place(val name: String, val lat: Double, val lon: Double, val country: String = "")

    fun forecastUrl(lat: Double, lon: Double, fahrenheit: Boolean): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${"%.4f".format(Locale.US, lat)}&longitude=${"%.4f".format(Locale.US, lon)}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=4" +
            (if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else "")

    fun geocodeUrl(city: String, lang: String = Locale.getDefault().language.ifBlank { "en" }): String =
        "https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&language=$lang&name=" + java.net.URLEncoder.encode(city.trim(), "UTF-8")

    fun parsePlace(json: String): Place? = try {
        val r = JSONObject(json).optJSONArray("results")?.optJSONObject(0)
        r?.let { Place(it.optString("name"), it.getDouble("latitude"), it.getDouble("longitude"), it.optString("country")) }
    } catch (_: Exception) { null }

    /** Countries that use °F by default. */
    fun usesFahrenheit(country: String): Boolean = country.uppercase() in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")

    fun parse(json: String, place: String, now: Long = System.currentTimeMillis()): WidgetData {
        val o = JSONObject(json)
        if (o.optBoolean("error")) throw SourceError("The weather service is busy. I'll try again later.")
        val cur = o.getJSONObject("current")
        val unit = o.optJSONObject("current_units")?.optString("temperature_2m").orEmpty().ifBlank { "°C" }
        val windUnit = o.optJSONObject("current_units")?.optString("wind_speed_10m").orEmpty().ifBlank { "km/h" }
        val t = Math.round(cur.getDouble("temperature_2m")).toInt()
        val feels = cur.optDouble("apparent_temperature", Double.NaN)
        val code = cur.optInt("weather_code", -1)
        val lines = ArrayList<WidgetLine>()
        val d = o.optJSONObject("daily")
        if (d != null) {
            val days = d.optJSONArray("time") ?: JSONArray()
            val hi = d.optJSONArray("temperature_2m_max"); val lo = d.optJSONArray("temperature_2m_min")
            val wc = d.optJSONArray("weather_code"); val rain = d.optJSONArray("precipitation_probability_max")
            for (i in 0 until days.length()) {
                val day = if (i == 0) "Today" else dayName(days.optString(i))
                val r = rain?.optInt(i, -1) ?: -1
                lines += WidgetLine(day, label(wc?.optInt(i, -1) ?: -1) + (if (r > 0) " · rain $r%" else ""), "",
                    "${Math.round(hi?.optDouble(i) ?: 0.0)}° / ${Math.round(lo?.optDouble(i) ?: 0.0)}°")
            }
        }
        return WidgetData(headline = place, value = "$t${unit.take(2)}", change = label(code) +
            (if (!feels.isNaN()) " · feels ${Math.round(feels)}°" else "") + " · wind ${Math.round(cur.optDouble("wind_speed_10m", 0.0))} $windUnit",
            lines = lines)
    }

    private fun dayName(iso: String): String = try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US); f.timeZone = TimeZone.getTimeZone("UTC")
        val out = java.text.SimpleDateFormat("EEE", Locale.getDefault()); out.timeZone = TimeZone.getTimeZone("UTC")
        out.format(f.parse(iso)!!)
    } catch (_: Exception) { iso }

    /** WMO weather codes in plain words. */
    fun label(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "—"
    }
}

/** CoinGecko simple price (no key). */
object CoinGecko {
    private val common = mapOf("btc" to "bitcoin", "eth" to "ethereum", "sol" to "solana", "bnb" to "binancecoin", "xrp" to "ripple",
        "ada" to "cardano", "doge" to "dogecoin", "usdt" to "tether", "usdc" to "usd-coin", "trx" to "tron", "ton" to "the-open-network",
        "dot" to "polkadot", "ltc" to "litecoin", "avax" to "avalanche-2", "shib" to "shiba-inu", "matic" to "matic-network", "link" to "chainlink")

    /** "BTC" / "bitcoin" / "Bitcoin" → a CoinGecko id when we know it without asking (else the lowercased word). */
    fun id(s: String): String = s.trim().lowercase().let { common[it] ?: it.replace(' ', '-') }

    fun priceUrl(ids: List<String>, vs: String): String =
        "https://api.coingecko.com/api/v3/simple/price?ids=" + ids.joinToString(",") + "&vs_currencies=" + vs.lowercase() + "&include_24hr_change=true"

    fun searchUrl(q: String) = "https://api.coingecko.com/api/v3/search?query=" + java.net.URLEncoder.encode(q.trim(), "UTF-8")

    fun parseSearch(json: String): String? = try { JSONObject(json).optJSONArray("coins")?.optJSONObject(0)?.optString("id")?.ifBlank { null } } catch (_: Exception) { null }

    fun parse(json: String, ids: List<String>, vs: String): WidgetData {
        val o = JSONObject(json)
        val v = vs.lowercase()
        val lines = ids.mapNotNull { id ->
            val c = o.optJSONObject(id) ?: return@mapNotNull null
            if (!c.has(v)) return@mapNotNull null
            WidgetLine(pretty(id), "", "https://www.coingecko.com/en/coins/$id", Money.fmt(c.getDouble(v), v.uppercase()) + "  " + Money.pct(c.optDouble("${v}_24h_change", Double.NaN)))
        }
        if (lines.isEmpty()) throw SourceError("Couldn't find that coin. Try its full name, like Bitcoin.")
        val first = o.getJSONObject(ids.first { o.has(it) })
        return WidgetData(headline = pretty(ids.first { o.has(it) }), value = Money.fmt(first.getDouble(v), v.uppercase()),
            change = Money.pct(first.optDouble("${v}_24h_change", Double.NaN)).let { if (it.isBlank()) "" else "$it today" }, lines = if (lines.size > 1) lines else emptyList())
    }

    fun pretty(id: String): String = id.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
}

/** open.er-api.com latest rates (no key). */
object Fx {
    fun url(base: String) = "https://open.er-api.com/v6/latest/" + base.trim().uppercase()

    fun parse(json: String, base: String, targets: List<String>): WidgetData {
        val o = JSONObject(json)
        if (o.optString("result") != "success") throw SourceError("Couldn't get exchange rates for ${base.uppercase()}.")
        val rates = o.getJSONObject("rates")
        val b = base.uppercase()
        val lines = targets.map { it.trim().uppercase() }.filter { it.isNotBlank() && it != b }.mapNotNull { t ->
            if (!rates.has(t)) null else WidgetLine("1 $b", "", "", Money.fmt(rates.getDouble(t), t))
        }
        if (lines.isEmpty()) throw SourceError("Couldn't find those currencies.")
        return WidgetData(headline = "$b → " + targets.joinToString(", ") { it.trim().uppercase() }, value = lines.first().note,
            change = "1 $b", lines = if (lines.size > 1) lines else emptyList())
    }
}

/**
 * Stocks: Yahoo Finance's public chart endpoint (no key). It is unofficial, so it is best effort: when it stops answering
 * the card says so in one line and nothing else breaks.
 */
object Stocks {
    fun url(symbol: String) = "https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(symbol.trim().uppercase(), "UTF-8") + "?range=1d&interval=1d"

    fun parse(json: String, symbol: String): WidgetLine {
        val r = JSONObject(json).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)?.optJSONObject("meta")
            ?: throw SourceError("Couldn't find the stock ${symbol.uppercase()}.")
        val price = r.optDouble("regularMarketPrice", Double.NaN)
        if (price.isNaN()) throw SourceError("No price for ${symbol.uppercase()} right now.")
        val prev = r.optDouble("chartPreviousClose", r.optDouble("previousClose", Double.NaN))
        val pct = if (!prev.isNaN() && prev != 0.0) (price - prev) / prev * 100.0 else r.optDouble("regularMarketChangePercent", Double.NaN)
        val cur = r.optString("currency").ifBlank { "USD" }
        return WidgetLine(r.optString("symbol").ifBlank { symbol.uppercase() }, r.optString("fullExchangeName"), "https://finance.yahoo.com/quote/${symbol.uppercase()}",
            Money.fmt(price, cur) + "  " + Money.pct(pct))
    }
}

object Money {
    fun fmt(v: Double, cur: String): String {
        val digits = when { v >= 1000 -> 0; v >= 1 -> 2; v >= 0.01 -> 4; else -> 6 }
        val n = java.text.NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = digits; minimumFractionDigits = if (digits == 0) 0 else 2.coerceAtMost(digits) }
        return "${n.format(v)} $cur"
    }
    fun pct(p: Double): String = if (p.isNaN()) "" else (if (p >= 0) "▲ " else "▼ ") + String.format(Locale.getDefault(), "%.1f%%", kotlin.math.abs(p))
}

/** TheSportsDB free tier (public test key "3"): team search, last and next events. */
object SportsDb {
    private const val BASE = "https://www.thesportsdb.com/api/v1/json/3"
    data class Team(val id: String, val name: String, val league: String, val sport: String)

    fun searchUrl(team: String) = "$BASE/searchteams.php?t=" + java.net.URLEncoder.encode(team.trim(), "UTF-8")
    fun lastUrl(id: String) = "$BASE/eventslast.php?id=$id"
    fun nextUrl(id: String) = "$BASE/eventsnext.php?id=$id"

    /** The best match for [query]: an exact name / short name / alternate name first, then the first result. */
    fun parseTeam(json: String, query: String): Team? = try {
        val a = JSONObject(json).optJSONArray("teams") ?: JSONArray()
        val q = query.trim().lowercase()
        val all = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        val pick = all.firstOrNull { it.optString("strTeam").lowercase() == q }
            ?: all.firstOrNull { it.optString("strTeamShort").lowercase() == q }
            ?: all.firstOrNull { it.optString("strTeamAlternate").lowercase().split(',').any { s -> s.trim() == q } }
            ?: all.firstOrNull()
        pick?.let { Team(it.optString("idTeam"), it.optString("strTeam"), it.optString("strLeague"), it.optString("strSport")) }
    } catch (_: Exception) { null }

    data class Event(val name: String, val home: String, val away: String, val homeScore: String, val awayScore: String,
                     val league: String, val time: Long, val status: String)

    fun parseEvents(json: String, key: String): List<Event> = try {
        val a = JSONObject(json).optJSONArray(key) ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { e ->
            val ts = e.optString("strTimestamp").ifBlank { (e.optString("dateEvent") + " " + e.optString("strTime")).trim() }
            Event(e.optString("strEvent"), e.optString("strHomeTeam"), e.optString("strAwayTeam"), e.optString("intHomeScore").let { if (it == "null") "" else it },
                e.optString("intAwayScore").let { if (it == "null") "" else it }, e.optString("strLeague"), Dates.utc(ts.removeSuffix("+00:00")), e.optString("strStatus").let { if (it == "null") "" else it })
        }
    } catch (_: Exception) { emptyList() }

    fun toData(team: Team, last: List<Event>, next: List<Event>, tz: TimeZone = TimeZone.getDefault()): WidgetData {
        val lines = ArrayList<WidgetLine>()
        val url = "https://www.thesportsdb.com/team/${team.id}"
        next.sortedBy { it.time }.take(3).forEach { e -> lines += WidgetLine("${e.home} vs ${e.away}", Dates.dayTime(e.time, tz) + (if (e.league.isNotBlank()) " · ${e.league}" else ""), url, "Next") }
        last.sortedByDescending { it.time }.take(3).forEach { e ->
            val score = if (e.homeScore.isNotBlank() && e.awayScore.isNotBlank()) "${e.home} ${e.homeScore}–${e.awayScore} ${e.away}" else e.name
            lines += WidgetLine(score, listOf(e.status.ifBlank { "Result" }, e.league).filter { it.isNotBlank() }.joinToString(" · "), url, "")
        }
        val top = last.maxByOrNull { it.time }
        val value = top?.let { if (it.homeScore.isNotBlank()) "${it.homeScore}–${it.awayScore}" else "" }.orEmpty()
        val change = top?.let { "${it.home} vs ${it.away}" }.orEmpty()
        if (lines.isEmpty()) return WidgetData(headline = team.name, change = "No recent or upcoming games listed.", link = url)
        return WidgetData(headline = team.name, value = value, change = change, lines = lines, link = url)
    }
}

/** YouTube: resolve a handle / URL / id to a channel id, and the channel's public RSS feed. */
object YouTube {
    private val idRe = Regex("\\b(UC[A-Za-z0-9_-]{22})\\b")

    fun feedUrl(channelId: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

    /** An id already in what the user typed (a /channel/UC… link or the id itself). */
    fun directId(s: String): String? = idRe.find(s)?.groupValues?.get(1)

    /** The page to read the channel id from: "@MKBHD", "MKBHD", "youtube.com/@MKBHD", "youtube.com/c/x", "youtube.com/user/x". */
    fun pageUrl(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        if (t.contains("youtube.com/") || t.contains("youtu.be/")) {
            val u = if (t.startsWith("http")) t else "https://$t"
            return u.replace("://m.youtube.com", "://www.youtube.com").replace("://youtube.com", "://www.youtube.com").substringBefore('?')
        }
        val h = t.removePrefix("@").replace(Regex("\\s+"), "")
        if (!h.matches(Regex("[A-Za-z0-9._-]{2,64}"))) return null
        return "https://www.youtube.com/@$h"
    }

    /** The channel id on a channel page (canonical link, externalId, or the RSS link in its head). */
    fun idFromPage(html: String): String? {
        Regex("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})\"").find(html)?.let { return it.groupValues[1] }
        Regex("\"externalId\":\"(UC[A-Za-z0-9_-]{22})\"").find(html)?.let { return it.groupValues[1] }
        Regex("channel_id=(UC[A-Za-z0-9_-]{22})").find(html)?.let { return it.groupValues[1] }
        Regex("\"channelId\":\"(UC[A-Za-z0-9_-]{22})\"").find(html)?.let { return it.groupValues[1] }
        return null
    }

    /** Non-YouTube creators (TikTok, Instagram, X, Twitch…): a link card only. */
    fun platformOf(s: String): String {
        val l = s.lowercase()
        return when {
            "tiktok" in l -> "TikTok"; "instagram" in l -> "Instagram"; "twitch" in l -> "Twitch"; "x.com" in l || "twitter" in l -> "X"
            "youtube" in l || "youtu.be" in l -> "YouTube"; else -> "YouTube"
        }
    }

    fun profileUrl(platform: String, handle: String): String {
        val h = handle.trim().removePrefix("@").substringAfterLast('/').substringAfterLast('@')
        return when (platform) {
            "TikTok" -> "https://www.tiktok.com/@$h"; "Instagram" -> "https://www.instagram.com/$h"; "Twitch" -> "https://www.twitch.tv/$h"
            "X" -> "https://x.com/$h"; else -> "https://www.youtube.com/@$h"
        }
    }
}

/** GitHub: public repo events (no key) or the user's notifications through the GitHub connector. */
object GitHubFeed {
    fun repo(s: String): String? {
        val t = s.trim().removePrefix("https://").removePrefix("http://").removePrefix("github.com/").removeSuffix(".git").trim('/')
        val parts = t.split('/')
        return if (parts.size >= 2 && parts[0].matches(Regex("[A-Za-z0-9-]{1,39}")) && parts[1].matches(Regex("[A-Za-z0-9._-]{1,100}"))) "${parts[0]}/${parts[1]}" else null
    }

    fun eventsUrl(repo: String) = "https://api.github.com/repos/$repo/events?per_page=15"

    fun parseEvents(json: String, repo: String, now: Long): WidgetData {
        val a = try { JSONArray(json) } catch (_: Exception) {
            val msg = try { JSONObject(json).optString("message") } catch (_: Exception) { "" }
            throw SourceError(if (msg.contains("rate limit", true)) "GitHub's free limit is used up for now. I'll try again later." else "Couldn't find the repo $repo.")
        }
        val lines = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { e ->
            val who = e.optJSONObject("actor")?.optString("login").orEmpty()
            val p = e.optJSONObject("payload") ?: JSONObject()
            val at = Dates.parse(e.optString("created_at"))
            val (what, url) = when (e.optString("type")) {
                "PushEvent" -> {
                    val c = p.optJSONArray("commits"); val n = p.optInt("size", c?.length() ?: 0)
                    val msg = if (c != null && c.length() > 0) c.optJSONObject(c.length() - 1)?.optString("message")?.lineSequence()?.firstOrNull().orEmpty() else ""
                    (if (msg.isNotBlank()) msg else "Pushed $n commit${if (n == 1) "" else "s"}") to "https://github.com/$repo/commits"
                }
                "PullRequestEvent" -> "${p.optString("action").replaceFirstChar { it.uppercase() }} PR #${p.optJSONObject("pull_request")?.optInt("number")}: ${p.optJSONObject("pull_request")?.optString("title")}" to
                    p.optJSONObject("pull_request")?.optString("html_url").orEmpty()
                "IssuesEvent" -> "${p.optString("action").replaceFirstChar { it.uppercase() }} issue #${p.optJSONObject("issue")?.optInt("number")}: ${p.optJSONObject("issue")?.optString("title")}" to
                    p.optJSONObject("issue")?.optString("html_url").orEmpty()
                "IssueCommentEvent" -> "Commented on #${p.optJSONObject("issue")?.optInt("number")}: ${p.optJSONObject("issue")?.optString("title")}" to
                    p.optJSONObject("comment")?.optString("html_url").orEmpty()
                "ReleaseEvent" -> "Released ${p.optJSONObject("release")?.optString("tag_name")}" to p.optJSONObject("release")?.optString("html_url").orEmpty()
                "CreateEvent" -> "Created ${p.optString("ref_type")} ${p.optString("ref")}".trim() to "https://github.com/$repo"
                "WatchEvent" -> "Starred the repo" to "https://github.com/$repo"
                "ForkEvent" -> "Forked the repo" to "https://github.com/$repo"
                else -> return@mapNotNull null
            }
            WidgetLine(what, listOf(who, Dates.relative(at, now)).filter { it.isNotBlank() }.joinToString(" · "), url)
        }
        return WidgetData(headline = repo, change = if (lines.isEmpty()) "No recent activity." else "", lines = lines, link = "https://github.com/$repo")
    }

    fun parseNotifications(json: String, now: Long): WidgetData {
        val a = try { JSONArray(json) } catch (_: Exception) { throw SourceError("GitHub didn't answer. Check the GitHub connection in Accounts.") }
        val lines = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { n ->
            WidgetLine(n.optJSONObject("subject")?.optString("title").orEmpty(),
                listOf(n.optJSONObject("repository")?.optString("full_name").orEmpty(), n.optString("reason").replace('_', ' '), Dates.relative(Dates.parse(n.optString("updated_at")), now))
                    .filter { it.isNotBlank() }.joinToString(" · "), "https://github.com/notifications")
        }
        return WidgetData(headline = "GitHub", value = if (lines.isEmpty()) "" else "${lines.size} unread", change = if (lines.isEmpty()) "No unread notifications." else "",
            lines = lines, link = "https://github.com/notifications")
    }
}

/** A failure with a plain line for the card. */
class SourceError(msg: String) : Exception(msg)

/** Plain sentence-case lines for anything that went wrong, never a stack trace. */
object Plain {
    fun of(e: Throwable): String = when (e) {
        is SourceError -> e.message.orEmpty().ifBlank { "Couldn't update this right now." }
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> "No connection right now. Showing what I had."
        is java.net.SocketTimeoutException -> "The source took too long to answer. I'll try again later."
        is javax.net.ssl.SSLException -> "Couldn't open a secure connection. I'll try again later."
        is java.io.IOException -> "Couldn't reach the source. I'll try again later."
        is org.json.JSONException -> "The source sent something I couldn't read. I'll try again later."
        else -> "Couldn't update this right now."
    }

    fun http(code: Int, what: String): String = when (code) {
        404 -> "Couldn't find $what."
        429 -> "The source is busy (too many requests). I'll try again later."
        in 500..599 -> "The source is having trouble. I'll try again later."
        else -> "Couldn't update $what (error $code)."
    }
}

/**
 * v0.45.7: the Home weather card from gateway /v1/weather (the same source and units as chat's get_weather), plus the
 * 4-day lines from Open-Meteo in the same units. Pure (HomeWeatherTest).
 */
object HomeWeather {
    fun toData(n: com.blaze.assistant.agent.GatewayWeather.Now, place: String, units: String, lines: List<WidgetLine>,
               tz: TimeZone = TimeZone.getDefault()): WidgetData {
        val t = com.blaze.assistant.agent.GatewayWeather.tempLabel(units, n.unit)
        val w = com.blaze.assistant.agent.GatewayWeather.windLabel(units, n.windUnit)
        val time = com.blaze.assistant.agent.GatewayWeather.localTime(n.observedAt, tz)
        val cond = n.condition.ifBlank { n.summary }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        return WidgetData(headline = place, value = "${Math.round(n.temperature)}$t",
            change = listOfNotNull(cond.takeIf { it.isNotBlank() } ?: (if (n.isDay) null else "Clear night"),
                if (!n.feelsLike.isNaN()) "feels ${Math.round(n.feelsLike)}°" else null,
                if (!n.wind.isNaN()) "wind ${Math.round(n.wind)} $w" else null,
                (n.source.ifBlank { "Blaze weather" } + if (time.isNotBlank()) " · $time" else "")).joinToString(" · "),
            lines = lines)
    }
}
