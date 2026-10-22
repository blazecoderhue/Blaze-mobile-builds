package com.blaze.assistant.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** v0.29.0: the Home board's free-source parsers on fixture data (shapes copied from the real APIs, Oct 2026). */
class HomeParsersTest {
    @Before fun setUp() { Locale.setDefault(Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    private val now = Dates.parse("2026-10-15T17:00:00+00:00")

    private val googleNews = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><generator>NFE/5.0</generator><title>"Arsenal" - Google News</title><link>https://news.google.com/search?q=Arsenal&amp;hl=en-US&amp;gl=US&amp;ceid=US:en</link><language>en-US</language>
<item><title>Arsenal Player Ratings vs. Lille: Rotated Gunners Extend Brilliant League Phase Record - Sports Illustrated</title><link>https://news.google.com/rss/articles/CBMidEFVX3lx?oc=5</link><guid isPermaLink="false">CBMidEFVX3lx</guid><pubDate>Tue, 13 Oct 2026 21:12:21 GMT</pubDate><description>&lt;a href="https://news.google.com/rss/articles/CBMidEFVX3lx?oc=5" target="_blank"&gt;Arsenal Player Ratings&lt;/a&gt;&amp;nbsp;&lt;font color="#6f6f6f"&gt;Sports Illustrated&lt;/font&gt;</description><source url="https://www.si.com">Sports Illustrated</source></item>
<item><title><![CDATA[Arsenal showed strength in depth & more - ESPN]]></title><link>https://news.google.com/rss/articles/CBMi2?oc=5</link><pubDate>Thu, 15 Oct 2026 15:00:00 GMT</pubDate><source url="https://www.espn.com">ESPN</source></item>
</channel></rss>"""

    @Test fun googleNewsRss() {
        val e = Rss.parse(googleNews)
        assertEquals(2, e.size)
        assertEquals("Arsenal Player Ratings vs. Lille: Rotated Gunners Extend Brilliant League Phase Record", e[0].title)
        assertEquals("Sports Illustrated", e[0].source)
        assertEquals("https://news.google.com/rss/articles/CBMidEFVX3lx?oc=5", e[0].link)
        assertEquals(Dates.parse("2026-10-13T21:12:21Z"), e[0].published)
        assertEquals("CDATA + entity + source suffix", "Arsenal showed strength in depth & more", e[1].title)
        val d = Rss.toData(e, "Arsenal news", now)
        assertEquals("Arsenal news", d.headline)
        assertEquals("ESPN · 2 h ago", d.lines[1].sub)
        assertEquals("\"Arsenal\" - Google News", Rss.feedTitle(googleNews))
    }

    private val youtube = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
 <link rel="self" href="http://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ"/>
 <id>yt:channel:BJycsmduvYEL83R_U4JriQ</id>
 <title>Marques Brownlee</title>
 <link rel="alternate" href="https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ"/>
 <author><name>Marques Brownlee</name><uri>https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ</uri></author>
 <published>2008-03-21T15:25:54+00:00</published>
 <entry>
  <id>yt:video:QVv3mQzlrN0</id>
  <yt:videoId>QVv3mQzlrN0</yt:videoId>
  <title>Apple Homeview Impressions: Not Convinced!</title>
  <link rel="alternate" href="https://www.youtube.com/watch?v=QVv3mQzlrN0"/>
  <author><name>Marques Brownlee</name></author>
  <published>2026-10-14T22:42:00+00:00</published>
  <updated>2026-10-15T16:37:00+00:00</updated>
  <media:group><media:title>Apple Homeview Impressions: Not Convinced!</media:title><media:description>New smart home display &amp; more.</media:description></media:group>
 </entry>
 <entry>
  <id>yt:video:_0G4OG39XjI</id>
  <yt:videoId>_0G4OG39XjI</yt:videoId>
  <title>WTF is a Googlebook?</title>
  <link rel="alternate" href="https://www.youtube.com/watch?v=_0G4OG39XjI"/>
  <published>2026-10-08T21:00:00+00:00</published>
 </entry>
</feed>"""

    @Test fun youtubeAtom() {
        val e = Rss.parse(youtube)
        assertEquals(2, e.size)
        assertEquals("Apple Homeview Impressions: Not Convinced!", e[0].title)
        assertEquals("https://www.youtube.com/watch?v=QVv3mQzlrN0", e[0].link)
        assertEquals("QVv3mQzlrN0", e[0].videoId)
        assertEquals("Marques Brownlee", Rss.feedTitle(youtube))
        val seen = Dates.parse("2026-10-10T00:00:00Z")
        val d = Rss.toData(e, "Marques Brownlee", now, seen, video = true)
        assertEquals("1 new video", d.value)
        assertEquals("New", d.lines[0].note); assertEquals("", d.lines[1].note)
        assertEquals("18 h ago", d.lines[0].sub)
    }

    @Test fun youtubeChannelResolving() {
        assertEquals("UCBJycsmduvYEL83R_U4JriQ", YouTube.directId("https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ"))
        assertNull(YouTube.directId("@MKBHD"))
        assertEquals("https://www.youtube.com/@MKBHD", YouTube.pageUrl("@MKBHD"))
        assertEquals("https://www.youtube.com/@MKBHD", YouTube.pageUrl("MKBHD"))
        assertEquals("https://www.youtube.com/@mkbhd", YouTube.pageUrl("https://m.youtube.com/@mkbhd?si=x"))
        assertNull(YouTube.pageUrl("not a handle!"))
        val html = """<html><head><link rel="canonical" href="https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ"></head></html>"""
        assertEquals("UCBJycsmduvYEL83R_U4JriQ", YouTube.idFromPage(html))
        assertEquals("UCBJycsmduvYEL83R_U4JriQ", YouTube.idFromPage("""{"metadata":{"channelMetadataRenderer":{"externalId":"UCBJycsmduvYEL83R_U4JriQ"}}}"""))
        assertNull(YouTube.idFromPage("<html>consent</html>"))
        assertEquals("TikTok", YouTube.platformOf("https://www.tiktok.com/@khaby.lame"))
        assertEquals("https://www.tiktok.com/@khaby.lame", YouTube.profileUrl("TikTok", "https://www.tiktok.com/@khaby.lame"))
    }

    private val openMeteo = """{"latitude":-12.95,"longitude":28.625,"timezone":"Africa/Lusaka","current_units":{"time":"iso8601","temperature_2m":"°C","apparent_temperature":"°C","weather_code":"wmo code","wind_speed_10m":"km/h"},
"current":{"time":"2026-10-15T17:00","temperature_2m":29.4,"apparent_temperature":28.1,"weather_code":2,"wind_speed_10m":11.3,"is_day":1},
"daily_units":{"temperature_2m_max":"°C"},"daily":{"time":["2026-10-15","2026-10-16","2026-10-17","2026-10-18"],"weather_code":[2,61,95,0],"temperature_2m_max":[31.2,27.8,26.0,30.5],"temperature_2m_min":[16.1,17.0,15.2,14.9],"precipitation_probability_max":[0,70,85,5]}}"""

    @Test fun openMeteoForecast() {
        val d = OpenMeteo.parse(openMeteo, "Ndola")
        assertEquals("Ndola", d.headline)
        assertEquals("29°C", d.value)
        assertEquals("Partly cloudy · feels 28° · wind 11 km/h", d.change)
        assertEquals(4, d.lines.size)
        assertEquals(WidgetLine("Today", "Partly cloudy", "", "31° / 16°"), d.lines[0])
        assertEquals(WidgetLine("Fri", "Rain · rain 70%", "", "28° / 17°"), d.lines[1])
        assertEquals("Thunderstorm · rain 85%", d.lines[2].sub)
        assertEquals("Thunderstorm with hail", OpenMeteo.label(99))
        assertTrue(OpenMeteo.usesFahrenheit("US")); assertFalse(OpenMeteo.usesFahrenheit("ZM"))
        assertTrue(OpenMeteo.forecastUrl(-12.95, 28.6, true).contains("temperature_unit=fahrenheit"))
        assertTrue(OpenMeteo.forecastUrl(-12.95, 28.6, false).startsWith("https://api.open-meteo.com/v1/forecast?latitude=-12.9500&longitude=28.6000"))
        try { OpenMeteo.parse("""{"error":true,"reason":"Daily API request limit exceeded."}""", "x"); fail() } catch (e: SourceError) {
            assertEquals("The weather service is busy. I'll try again later.", e.message) }
    }

    @Test fun openMeteoGeocoding() {
        val p = OpenMeteo.parsePlace("""{"results":[{"id":901344,"name":"Ndola","latitude":-12.95867,"longitude":28.63659,"country_code":"ZM","country":"Zambia"}]}""")!!
        assertEquals("Ndola", p.name); assertEquals(-12.95867, p.lat, 1e-6); assertEquals("Zambia", p.country)
        assertNull(OpenMeteo.parsePlace("""{"generationtime_ms":0.2}"""))
        assertTrue(OpenMeteo.geocodeUrl("São Paulo", "pt").endsWith("name=S%C3%A3o+Paulo"))
    }

    @Test fun coinGecko() {
        val one = CoinGecko.parse("""{"bitcoin":{"usd":82115,"usd_24h_change":0.32220531319906626}}""", listOf("bitcoin"), "usd")
        assertEquals("Bitcoin", one.headline); assertEquals("82,115 USD", one.value); assertEquals("▲ 0.3% today", one.change)
        assertTrue(one.lines.isEmpty())
        val two = CoinGecko.parse("""{"bitcoin":{"eur":70000.5,"eur_24h_change":-1.25},"ethereum":{"eur":2512.4,"eur_24h_change":2.0}}""", listOf("bitcoin", "ethereum"), "EUR")
        assertEquals(2, two.lines.size)
        assertEquals("Ethereum", two.lines[1].title); assertEquals("2,512 EUR  ▲ 2.0%", two.lines[1].note)
        assertEquals("▼ 1.3% today", two.change)
        assertEquals("bitcoin", CoinGecko.id("BTC")); assertEquals("avalanche-2", CoinGecko.id("avax")); assertEquals("pepe", CoinGecko.id("Pepe"))
        assertEquals("bitcoin", CoinGecko.parseSearch("""{"coins":[{"id":"bitcoin","symbol":"BTC"},{"id":"x"}]}"""))
        try { CoinGecko.parse("{}", listOf("nope"), "usd"); fail() } catch (e: SourceError) { assertTrue(e.message!!.startsWith("Couldn't find that coin")) }
    }

    @Test fun fxRates() {
        val json = """{"result":"success","base_code":"USD","rates":{"USD":1,"EUR":0.8612,"ZMW":26.4512,"NGN":1530.2}}"""
        val d = Fx.parse(json, "usd", listOf("EUR", "ZMW"))
        assertEquals("USD → EUR, ZMW", d.headline); assertEquals("0.8612 EUR", d.value)
        assertEquals("26.45 ZMW", d.lines[1].note)
        try { Fx.parse("""{"result":"error","error-type":"unsupported-code"}""", "XXX", listOf("EUR")); fail() } catch (_: SourceError) { }
    }

    @Test fun stocksBestEffort() {
        val l = Stocks.parse("""{"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAPL","fullExchangeName":"NasdaqGS","regularMarketPrice":344.15,"chartPreviousClose":342.96}}],"error":null}}""", "aapl")
        assertEquals("AAPL", l.title); assertEquals("344.15 USD  ▲ 0.3%", l.note)
        try { Stocks.parse("""{"chart":{"result":null,"error":{"code":"Not Found"}}}""", "zzzz"); fail() } catch (e: SourceError) { assertEquals("Couldn't find the stock ZZZZ.", e.message) }
    }

    private val teams = """{"teams":[{"idTeam":"999","strTeam":"Arsenal Tula","strTeamShort":"","strTeamAlternate":"","strLeague":"Russian League","strSport":"Soccer"},
{"idTeam":"133604","strTeam":"Arsenal","strTeamShort":"ARS","strTeamAlternate":"Arsenal Football Club, AFC, Arsenal FC","strLeague":"English Premier League","strSport":"Soccer"}]}"""

    @Test fun sportsDb() {
        val t = SportsDb.parseTeam(teams, "arsenal")!!
        assertEquals("133604", t.id); assertEquals("English Premier League", t.league)
        assertEquals("133604", SportsDb.parseTeam(teams, "AFC")!!.id)
        assertEquals("first result when nothing matches exactly", "999", SportsDb.parseTeam(teams, "Gunners")!!.id)
        assertNull(SportsDb.parseTeam("""{"teams":null}""", "zzz"))
        val last = SportsDb.parseEvents("""{"results":[{"strEvent":"Arsenal vs Lille","strTimestamp":"2026-10-13T19:00:00","strHomeTeam":"Arsenal","strAwayTeam":"Lille","intHomeScore":"3","intAwayScore":"0","strLeague":"UEFA Champions League","strStatus":"FT"}]}""", "results")
        val next = SportsDb.parseEvents("""{"events":[{"strEvent":"Nottingham Forest vs Arsenal","strTimestamp":"2026-10-18T15:30:00","strHomeTeam":"Nottingham Forest","strAwayTeam":"Arsenal","intHomeScore":null,"intAwayScore":null,"strLeague":"English Premier League","strStatus":"Not Started"}]}""", "events")
        assertEquals(Dates.parse("2026-10-18T15:30:00Z"), next[0].time)
        val d = SportsDb.toData(t, last, next, TimeZone.getTimeZone("Africa/Lusaka"))
        assertEquals("Arsenal", d.headline); assertEquals("3–0", d.value); assertEquals("Arsenal vs Lille", d.change)
        assertEquals(WidgetLine("Nottingham Forest vs Arsenal", "Sun 18 Oct · 17:30 · English Premier League", "https://www.thesportsdb.com/team/133604", "Next"), d.lines[0])
        assertEquals("Arsenal 3–0 Lille", d.lines[1].title); assertEquals("FT · UEFA Champions League", d.lines[1].sub)
        assertEquals("No recent or upcoming games listed.", SportsDb.toData(t, emptyList(), emptyList()).change)
    }

    @Test fun githubEvents() {
        val json = """[{"type":"PushEvent","actor":{"login":"octocat"},"created_at":"2026-10-15T16:30:00Z","payload":{"size":2,"commits":[{"message":"first"},{"message":"v0.28.4: blaze-docs\n\nmore"}]}},
{"type":"PullRequestEvent","actor":{"login":"bot"},"created_at":"2026-10-15T12:00:00Z","payload":{"action":"opened","pull_request":{"number":70,"title":"Home board","html_url":"https://github.com/o/r/pull/70"}}},
{"type":"GollumEvent","actor":{"login":"x"},"created_at":"2026-10-15T12:00:00Z","payload":{}}]"""
        val d = GitHubFeed.parseEvents(json, "octocat/Blaze-agent", now)
        assertEquals(2, d.lines.size)
        assertEquals("v0.28.4: blaze-docs", d.lines[0].title); assertEquals("octocat · 30 min ago", d.lines[0].sub)
        assertEquals("Opened PR #70: Home board", d.lines[1].title); assertEquals("https://github.com/o/r/pull/70", d.lines[1].url)
        try { GitHubFeed.parseEvents("""{"message":"API rate limit exceeded for 1.2.3.4."}""", "o/r", now); fail() } catch (e: SourceError) { assertTrue(e.message!!.contains("free limit")) }
        assertEquals("octocat/Blaze-agent", GitHubFeed.repo("https://github.com/octocat/Blaze-agent.git"))
        assertNull(GitHubFeed.repo("not a repo"))
        val n = GitHubFeed.parseNotifications("""[{"reason":"review_requested","updated_at":"2026-10-15T16:00:00Z","subject":{"title":"Fix it"},"repository":{"full_name":"o/r"}}]""", now)
        assertEquals("1 unread", n.value); assertEquals("o/r · review requested · 1 h ago", n.lines[0].sub)
    }

    @Test fun plainErrorsNeverShowTraces() {
        assertEquals("No connection right now. Showing what I had.", Plain.of(java.net.UnknownHostException("api.coingecko.com")))
        assertEquals("The source took too long to answer. I'll try again later.", Plain.of(java.net.SocketTimeoutException()))
        assertEquals("Couldn't update this right now.", Plain.of(IllegalStateException("boom at Foo.kt:12")))
        assertEquals("The source is busy (too many requests). I'll try again later.", Plain.http(429, "x"))
        listOf(Plain.of(RuntimeException("x")), Plain.http(500, "x"), Plain.http(404, "that team")).forEach { l ->
            assertFalse(l.contains("Exception")); assertTrue(l[0].isUpperCase()); assertTrue(l.endsWith("."))
        }
    }

    @Test fun datesAndAgo() {
        assertEquals(Dates.parse("2026-10-13T21:12:21+00:00"), Dates.parse("Tue, 13 Oct 2026 21:12:21 GMT"))
        assertEquals(0L, Dates.parse("garbage"))
        assertEquals("Updated 12 min ago", Ago.of(now - 12 * 60_000, now))
        assertEquals("Updated just now", Ago.of(now - 10_000, now))
        assertEquals("Updated 3 h ago", Ago.of(now - 3 * 3_600_000, now))
        assertEquals("Not updated yet", Ago.of(0, now))
        assertTrue(Ago.stale(now - 60_000, now, offline = true, error = ""))
        assertFalse(Ago.stale(now - 60_000, now, offline = false, error = ""))
        assertTrue(Ago.stale(now - 4 * 3_600_000, now, offline = false, error = ""))
        assertFalse("never fetched is not stale, it's loading", Ago.stale(0, now, true, "x"))
    }

    @Test fun customWidgetKeepsOnlyWebLinks() {
        val w = Widget("w1", "custom", "Reading list", "Notes", """{"text":"This week","items":[{"title":"Kotlin docs","url":"https://kotlinlang.org"},{"title":"Bad","url":"javascript:alert(1)"},"Plain line"]}""")
        val d = HomeSources.custom(w)
        assertEquals("This week", d.change)
        assertEquals(3, d.lines.size)
        assertEquals("https://kotlinlang.org", d.lines[0].url); assertEquals("", d.lines[1].url); assertEquals("Plain line", d.lines[2].title)
    }

    @Test fun newsUrlFollowsThePhonesRegion() {
        assertEquals("https://news.google.com/rss/search?q=Arsenal+scores&hl=fr-FR&gl=FR&ceid=FR:fr", HomeSources.newsUrl("Arsenal scores", Locale.FRANCE))
        assertEquals("https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en", HomeSources.newsUrl("", Locale("en")))
    }
}
