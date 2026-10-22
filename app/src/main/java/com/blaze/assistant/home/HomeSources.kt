package com.blaze.assistant.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.blaze.assistant.connect.Http
import org.json.JSONObject
import java.util.Locale

/**
 * v0.29.0: fetches one widget's content. Online widgets use free, keyless sources over https (Open-Meteo, Google News RSS,
 * YouTube channel RSS, TheSportsDB, CoinGecko, open.er-api.com, Yahoo's public chart, GitHub's public API); the others
 * read the phone (calendar, routines, memories, usage stats). Never throws: a failure is a [WidgetData] with a plain line.
 */
object HomeSources {
    private const val UA = "Mozilla/5.0 (Linux; Android 13) BlazeHome/1.0"

    /** Swappable for tests. Returns (status, body). */
    @Volatile var net: (String, Map<String, String>) -> Pair<Int, String> = { url, h ->
        Http.req("GET", url, headers = mapOf("User-Agent" to UA, "Accept-Language" to Locale.getDefault().toLanguageTag()) + h, timeoutMs = 20_000)
    }

    private fun get(url: String, what: String, headers: Map<String, String> = emptyMap()): String {
        val (code, body) = net(url, headers)
        if (code !in 200..299) throw SourceError(Plain.http(code, what))
        return body
    }

    /** The result plus a config the fetch learned (a resolved channel id, a city's coordinates) to save on the widget. */
    data class Result(val data: WidgetData, val learned: JSONObject? = null)

    suspend fun fetch(ctx: Context, w: Widget, now: Long = System.currentTimeMillis()): Result = try {
        when (w.type) {
            WidgetTypes.WEATHER -> weather(ctx, w)
            WidgetTypes.NEWS -> Result(news(w, now))
            WidgetTypes.CREATORS -> creators(w, now)
            WidgetTypes.SPORTS -> sports(w)
            WidgetTypes.PRICES -> prices(w)
            WidgetTypes.GITHUB -> Result(github(w, now))
            WidgetTypes.CALENDAR -> Result(calendar(ctx, now))
            WidgetTypes.ROUTINES -> Result(routines(now))
            WidgetTypes.DEADLINES -> Result(deadlines(now))
            WidgetTypes.APPS -> Result(apps(ctx, now))
            WidgetTypes.CUSTOM -> Result(custom(w))
            WidgetTypes.INTO -> Result(WidgetData(headline = "Tell me what you're into", change = "Pick a few topics and I'll fill your board with them.", action = "interests"))
            else -> Result(WidgetData.failed("This widget type isn't supported in this version."))
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { Result(WidgetData.failed(Plain.of(e))) }

    // ---------------- weather ----------------

    @Suppress("MissingPermission")
    private fun weather(ctx: Context, w: Widget): Result {
        val cfg = w.cfg()
        val country = Locale.getDefault().country
        val useLoc = cfg.optBoolean("use_location", false)
        var learned: JSONObject? = null
        var lat = Double.NaN; var lon = Double.NaN; var place = cfg.optString("place").ifBlank { cfg.optString("city") }
        if (useLoc && hasPerm(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            try {
                val lm = ctx.getSystemService(android.location.LocationManager::class.java)
                val best = lm?.getProviders(true)?.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }?.maxByOrNull { it.time }
                if (best != null) {
                    lat = best.latitude; lon = best.longitude
                    place = try {
                        @Suppress("DEPRECATION")
                        android.location.Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                    } catch (_: Exception) { null } ?: place.ifBlank { "Here" }
                }
            } catch (_: Exception) { }
        }
        if (lat.isNaN()) {
            if (cfg.has("lat") && cfg.has("lon") && cfg.optString("city").isNotBlank() && cfg.optString("geo_city") == cfg.optString("city")) {
                lat = cfg.optDouble("lat"); lon = cfg.optDouble("lon"); place = cfg.optString("place").ifBlank { cfg.optString("city") }
            } else if (cfg.optString("city").isNotBlank()) {
                val p = OpenMeteo.parsePlace(get(OpenMeteo.geocodeUrl(cfg.optString("city")), "that city"))
                    ?: return Result(WidgetData.failed("Couldn't find the city \u201c${cfg.optString("city")}\u201d. Try another spelling.", "city"))
                lat = p.lat; lon = p.lon; place = p.name
                learned = JSONObject().put("lat", p.lat).put("lon", p.lon).put("place", p.name).put("geo_city", cfg.optString("city"))
            } else {
                return Result(WidgetData.failed(if (useLoc) "Allow location, or type a city, to see the weather." else "Type a city to see the weather.", if (useLoc) "location" else "city"))
            }
        }
        // v0.45.7: metric outside the US (a phone set to en-US in Ndola got °F / mph); imperial only when picked or in the US.
        val units = com.blaze.assistant.agent.GatewayWeather.unitsFor(cfg.optString("units"), country, lat, lon)
        val f = units == "imperial"
        val forecast = try { OpenMeteo.parse(get(OpenMeteo.forecastUrl(lat, lon, f), "the weather"), place) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        // The same source as chat's get_weather: gateway /v1/weather (signed in). Open-Meteo direct is the fallback.
        val token = com.blaze.assistant.agent.Settings.gatewayToken()
        val now = if (token.isBlank()) null else try {
            val (code, body) = com.blaze.assistant.agent.GatewayWeather.fetch(lat, lon, units, token)
            if (code in 200..299) com.blaze.assistant.agent.GatewayWeather.parse(body) else null
        } catch (e: Exception) { null }
        if (now != null) return Result(HomeWeather.toData(now, place, units, forecast?.lines.orEmpty()), learned)
        val d = forecast ?: OpenMeteo.parse(get(OpenMeteo.forecastUrl(lat, lon, f), "the weather"), place)
        return Result(d.copy(link = "https://open-meteo.com/"), learned)
    }

    // ---------------- news ----------------

    /** Google News RSS for the phone's language and region (or any RSS / Atom feed URL). */
    fun newsUrl(query: String, locale: Locale = Locale.getDefault()): String {
        val lang = locale.language.ifBlank { "en" }; val cc = locale.country.ifBlank { "US" }
        val hl = "$lang-$cc"; val ceid = "$cc:$lang"
        return if (query.isBlank()) "https://news.google.com/rss?hl=$hl&gl=$cc&ceid=$ceid"
        else "https://news.google.com/rss/search?q=" + Http.enc(query) + "&hl=$hl&gl=$cc&ceid=$ceid"
    }

    private fun news(w: Widget, now: Long): WidgetData {
        val feed = w.opt("feed")
        val url = if (feed.isNotBlank()) feed.let { if (it.startsWith("http")) it else "https://$it" } else newsUrl(w.opt("query"),
            w.opt("locale").takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it.replace('_', '-')) } ?: Locale.getDefault())
        val xml = get(url, "that feed")
        var entries = Rss.parse(xml, 20)
        if (entries.isEmpty()) throw SourceError("No stories right now.")
        // v0.29.3: Google News has no pictures; Bing News' feed for the same query does. Borrow them for matching stories.
        if (entries.none { it.image.isNotBlank() }) entries = try {
            val q = w.opt("query").ifBlank { if (feed.isBlank()) "top stories" else "" }
            if (q.isBlank()) entries else Rss.borrowImages(entries, Rss.parse(get("https://www.bing.com/news/search?format=rss&q=" + Http.enc(q), "pictures"), 30))
        } catch (_: Exception) { entries }
        val head = if (feed.isNotBlank()) Rss.feedTitle(xml).ifBlank { w.title } else w.title
        return Rss.toData(entries, head, now)
    }

    // ---------------- creators ----------------

    private fun creators(w: Widget, now: Long): Result {
        val who = w.opt("channel")
        val platform = w.opt("platform").ifBlank { YouTube.platformOf(who) }
        if (platform != "YouTube") {
            val url = if (who.startsWith("http")) who else YouTube.profileUrl(platform, who)
            return Result(WidgetData(headline = w.title, change = "New posts from $platform open in the app or browser.",
                lines = listOf(WidgetLine("Open ${who.substringAfterLast('/').ifBlank { who }} on $platform", "Blaze can't read $platform feeds for free, so this is a link.", url)), link = url))
        }
        var learned: JSONObject? = null
        val id = w.opt("channel_id").ifBlank { null } ?: YouTube.directId(who) ?: run {
            val page = YouTube.pageUrl(who) ?: throw SourceError("Type a YouTube handle like @MKBHD or a channel link.")
            val html = get(page, "that channel", mapOf("Cookie" to "SOCS=CAI; CONSENT=YES+1"))
            YouTube.idFromPage(html)?.also { learned = JSONObject().put("channel_id", it) } ?: throw SourceError("Couldn't find that YouTube channel.")
        }
        val xml = get(YouTube.feedUrl(id), "that channel's videos")
        val entries = Rss.parse(xml, 15)
        val seen = w.cfg().optLong("seen_at", 0L)
        val name = Rss.feedTitle(xml).ifBlank { w.title }
        val d = Rss.toData(entries, name, now, seen, video = true).copy(link = "https://www.youtube.com/channel/$id")
        return Result(if (entries.isEmpty()) d.copy(change = "No videos yet.") else d, learned)
    }

    // ---------------- sports ----------------

    private fun sports(w: Widget): Result {
        val q = w.opt("team").ifBlank { w.title }
        var learned: JSONObject? = null
        val team = if (w.opt("team_id").isNotBlank() && w.opt("team_query") == q)
            SportsDb.Team(w.opt("team_id"), w.opt("team_name").ifBlank { q }, w.opt("league"), w.opt("sport"))
        else {
            val t = SportsDb.parseTeam(get(SportsDb.searchUrl(q), "that team"), q) ?: throw SourceError("Couldn't find the team \u201c$q\u201d. Try its full name.")
            learned = JSONObject().put("team_id", t.id).put("team_name", t.name).put("team_query", q).put("league", t.league).put("sport", t.sport)
            t
        }
        val last = try { SportsDb.parseEvents(get(SportsDb.lastUrl(team.id), "results"), "results") } catch (_: SourceError) { emptyList() }
        val next = try { SportsDb.parseEvents(get(SportsDb.nextUrl(team.id), "fixtures"), "events") } catch (_: SourceError) { emptyList() }
        return Result(SportsDb.toData(team, last, next), learned)
    }

    // ---------------- prices ----------------

    private fun prices(w: Widget): Result {
        val cfg = w.cfg()
        return when (cfg.optString("kind").ifBlank { "crypto" }) {
            "fx" -> {
                val base = cfg.optString("base").ifBlank { "USD" }
                val targets = cfg.optString("symbols").split(',', ' ').filter { it.isNotBlank() }.ifEmpty { listOf("EUR") }
                Result(Fx.parse(get(Fx.url(base), "exchange rates"), base, targets).copy(link = "https://www.exchangerate-api.com"))
            }
            "stock" -> {
                val syms = cfg.optString("symbols").split(',', ' ').filter { it.isNotBlank() }.take(6)
                if (syms.isEmpty()) throw SourceError("Add a stock symbol, like AAPL.")
                val lines = syms.mapNotNull { s -> try { Stocks.parse(get(Stocks.url(s), "the stock $s"), s) } catch (_: SourceError) { null } }
                if (lines.isEmpty()) throw SourceError("Stock prices aren't available right now (the free source didn't answer).")
                val top = lines.first()
                Result(WidgetData(headline = top.title, value = top.note.substringBefore("  "), change = top.note.substringAfter("  ", ""),
                    lines = if (lines.size > 1) lines else emptyList(), link = top.url))
            }
            else -> {
                val vs = cfg.optString("vs").ifBlank { "usd" }
                var learned: JSONObject? = null
                val raw = cfg.optString("ids").ifBlank { w.title }.split(',').map { it.trim() }.filter { it.isNotBlank() }.take(6)
                var ids = raw.map { CoinGecko.id(it) }
                var body = get(CoinGecko.priceUrl(ids, vs), "that coin")
                val o = JSONObject(body)
                val missing = ids.filter { !o.has(it) }
                if (missing.isNotEmpty()) {
                    // A name or symbol CoinGecko doesn't use as an id: look it up once and remember the id.
                    ids = ids.map { id -> if (id in missing) (CoinGecko.parseSearch(get(CoinGecko.searchUrl(id), "that coin")) ?: id) else id }
                    body = get(CoinGecko.priceUrl(ids, vs), "that coin")
                    learned = JSONObject().put("ids", ids.joinToString(","))
                }
                Result(CoinGecko.parse(body, ids, vs).copy(link = "https://www.coingecko.com/en/coins/${ids.first()}"), learned)
            }
        }
    }

    // ---------------- GitHub ----------------

    private fun github(w: Widget, now: Long): WidgetData {
        val repo = GitHubFeed.repo(w.opt("repo"))
        val token = try { if (com.blaze.assistant.connect.Connectors.connected("github")) com.blaze.assistant.connect.Connectors.access("github") else null } catch (_: Throwable) { null }
        val h = mapOf("Accept" to "application/vnd.github+json")
        if (repo == null) {
            if (token.isNullOrBlank()) throw SourceError("Add a repo like owner/name, or connect GitHub in Accounts for your notifications.")
            return GitHubFeed.parseNotifications(get("https://api.github.com/notifications?per_page=15", "your GitHub notifications", h + ("Authorization" to "Bearer $token")), now)
        }
        val auth = if (!token.isNullOrBlank()) h + ("Authorization" to "Bearer $token") else h
        val (code, body) = net(GitHubFeed.eventsUrl(repo), auth)
        if (code == 403 || code == 429) throw SourceError("GitHub's free limit is used up for now. I'll try again later.")
        if (code == 404) throw SourceError("Couldn't find the repo $repo (it may be private).")
        if (code !in 200..299) throw SourceError(Plain.http(code, "the repo"))
        return GitHubFeed.parseEvents(body, repo, now)
    }

    // ---------------- on the phone ----------------

    private fun hasPerm(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun calendar(ctx: Context, now: Long): WidgetData {
        if (!hasPerm(ctx, Manifest.permission.READ_CALENDAR)) return WidgetData.failed("Allow calendar access to see your next events.", "calendar")
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(b, now); android.content.ContentUris.appendId(b, now + 7 * 86_400_000L)
        val lines = ArrayList<WidgetLine>()
        ctx.contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION), null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && lines.size < 8) {
                val title = c.getString(0).orEmpty().ifBlank { "(No title)" }
                val begin = c.getLong(1); val allDay = c.getInt(2) == 1
                val loc = c.getString(3).orEmpty()
                val day = if (allDay) java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault()).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(begin)) + " · all day"
                    else Dates.dayTime(begin)
                lines += WidgetLine(title, listOf(day, loc).filter { it.isNotBlank() }.joinToString(" · "), "calendar:$begin")
            }
        }
        return if (lines.isEmpty()) WidgetData(headline = "Calendar", change = "Nothing in the next 7 days.")
        else WidgetData(headline = "Next up", value = lines.first().title, change = lines.first().sub, lines = lines.drop(1))
    }

    private fun routines(now: Long): WidgetData {
        val R = com.blaze.assistant.routine.Routines
        val list = try { R.all.value.filter { it.enabled }.map { it to R.next(it, now) }.filter { it.second > now }.sortedBy { it.second } } catch (_: Throwable) { emptyList() }
        if (list.isEmpty()) return WidgetData(headline = "Next routine", change = "No routines yet. Ask Blaze, e.g. \u201cevery weekday at 7, brief me\u201d.", action = "ask:Set up a morning routine for me")
        val (r, at) = list.first()
        return WidgetData(headline = "Next routine", value = r.name, change = Dates.dayTime(at) + " · " + r.daysLabel(),
            lines = list.drop(1).take(5).map { (x, t) -> WidgetLine(x.name, Dates.dayTime(t) + " · " + x.daysLabel()) })
    }

    private val deadlineRe = Regex("(?i)\\b(due|deadline|exam|test|assignment|submit|submission|quiz|presentation|project due)\\b")

    private fun deadlines(now: Long): WidgetData {
        val R = com.blaze.assistant.routine.Routines
        val oneOffs = try { R.all.value.filter { it.enabled && it.at > now }.sortedBy { it.at }.map { WidgetLine(it.name, Dates.dayTime(it.at), "", "Reminder") } } catch (_: Throwable) { emptyList() }
        val mems = try { com.blaze.assistant.agent.Mind.all().filter { deadlineRe.containsMatchIn(it.text) }.sortedByDescending { it.id }.take(6)
            .map { WidgetLine(it.text.take(120), it.subject.takeIf { s -> s.isNotBlank() }.orEmpty(), "", "From memory") } } catch (_: Throwable) { emptyList() }
        val lines = (oneOffs + mems).take(8)
        if (lines.isEmpty()) return WidgetData(headline = "Deadlines", change = "No deadlines yet. Tell Blaze about one, e.g. \u201cmy essay is due Friday\u201d.", action = "ask:Help me track my deadlines")
        return WidgetData(headline = "Deadlines", value = lines.first().title, change = lines.first().sub, lines = lines.drop(1))
    }

    /** Usage access granted (opt-in; never asked for without the explanation on the card). */
    fun usageAllowed(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
            else ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        mode == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    private fun apps(ctx: Context, now: Long): WidgetData {
        if (!usageAllowed(ctx)) return WidgetData.failed("Blaze can show the apps you use most. It needs Usage access, which stays on this phone.", "usage")
        val usm = ctx.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return WidgetData.failed("Usage stats aren't available on this phone.")
        val stats = try { usm.queryAndAggregateUsageStats(now - 7 * 86_400_000L, now) } catch (_: Exception) { emptyMap() }
        val pm = ctx.packageManager
        val lines = stats.values.filter { it.totalTimeInForeground > 60_000 && it.packageName != ctx.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedByDescending { it.totalTimeInForeground }.take(8).map {
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(it.packageName, 0)).toString() } catch (_: Exception) { it.packageName }
                val m = it.totalTimeInForeground / 60_000 / 7
                WidgetLine(label, "", "app:" + it.packageName, if (m >= 60) "${m / 60} h ${m % 60} min a day" else "$m min a day")
            }
        return if (lines.isEmpty()) WidgetData(headline = "Most-used apps", change = "Not enough use yet this week.") else WidgetData(headline = "Most-used apps", lines = lines)
    }

    /** Blaze-composed widget: {"text": "…", "items": [{"title": "…", "sub": "…", "url": "…"}], "value": "…"}. */
    fun custom(w: Widget): WidgetData {
        val cfg = w.cfg()
        val a = cfg.optJSONArray("items")
        val lines = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            when (val x = a.opt(i)) {
                is JSONObject -> WidgetLine(x.optString("title").ifBlank { x.optString("t") }, x.optString("sub").ifBlank { x.optString("s") }, safeUrl(x.optString("url").ifBlank { x.optString("u") }), x.optString("note"))
                is String -> WidgetLine(x)
                else -> null
            }
        }.filter { it.title.isNotBlank() }.take(12)
        return WidgetData(headline = w.title, value = cfg.optString("value").take(40), change = cfg.optString("text").take(600), lines = lines, link = safeUrl(cfg.optString("url")))
    }

    /** Only http(s) links on a Blaze-composed card. */
    fun safeUrl(u: String): String = u.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()
}
