package com.blaze.assistant.home

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.29.0: the tools Blaze uses to control the Home board: home_widget_add / _update / _remove / _list. Pure over a
 * [HomeStore] (tested with sqlite-jdbc in HomeToolsTest); [onChange] tells the app to redraw and fetch.
 */
object HomeTools {
    const val ADD = "home_widget_add"
    const val UPDATE = "home_widget_update"
    const val REMOVE = "home_widget_remove"
    const val LIST = "home_widget_list"
    val NAMES = setOf(ADD, UPDATE, REMOVE, LIST)

    private val typesDoc = "weather {city? | use_location:true, units?: c|f}; news {query (\"\" = top stories) , locale? e.g. en-GB} or {feed: RSS/Atom URL}; " +
        "creators {channel: @handle | YouTube channel URL | TikTok/Instagram URL}; sports {team}; " +
        "prices {kind: crypto, ids: \"bitcoin,ethereum\", vs: usd} | {kind: fx, base: USD, symbols: \"EUR,ZMW\"} | {kind: stock, symbols: \"AAPL\"} (stocks best effort); " +
        "calendar {}; routines {}; deadlines {}; github {repo: owner/name} or {notifications:true} (needs the GitHub connector); apps {} (most-used apps, needs Usage access); " +
        "custom {text?, value?, url?, items:[{title, sub?, url?}]} for anything else Blaze composes itself."

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", name).put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))

    private fun s(d: String) = JSONObject().put("type", "string").put("description", d)

    fun tools(): List<JSONObject> = listOf(
        fn(ADD, "Add a widget to the user's Home board (the Home tab: a scrolling board of live cards). Use it when they ask to see/track/follow something " +
            "(\"show me Arsenal scores\", \"track Bitcoin\", \"new videos from MKBHD\"), and you may offer widgets that fit what you remember about them. " +
            "Free sources only, no keys. Types and config: $typesDoc Same category = the cards stack together.",
            JSONObject().put("type", s("weather | news | creators | sports | prices | calendar | routines | deadlines | github | apps | custom"))
                .put("config", JSONObject().put("type", "object").put("description", "Type settings (see description)"))
                .put("title", s("Short card title, e.g. \"Arsenal\", \"Bitcoin\", \"Weather in Lagos\""))
                .put("category", s("Stack name, e.g. Sports, Finance, News, Creators, Weather, Today, Tech"))
                .put("size", s("S | M | L (default M)")),
            listOf("type")),
        fn(UPDATE, "Change a Home board widget by id (from $LIST): title, config (merged into the old one), category, size S/M/L, hidden, or position (0 = top).",
            JSONObject().put("id", s("Widget id")).put("title", s("New title")).put("config", JSONObject().put("type", "object").put("description", "Settings to change"))
                .put("category", s("New stack")).put("size", s("S | M | L")).put("hidden", JSONObject().put("type", "boolean"))
                .put("position", JSONObject().put("type", "integer").put("description", "New place on the board, 0 = top")),
            listOf("id")),
        fn(REMOVE, "Remove a widget from the Home board by id (from $LIST).", JSONObject().put("id", s("Widget id")), listOf("id")),
        fn(LIST, "List the widgets on the user's Home board (id, type, title, stack, size, shown or hidden, last update).", JSONObject(), emptyList()),
    )

    fun step(name: String, args: JSONObject): String = when (name) {
        ADD -> "Adding \u201c${args.optString("title").ifBlank { WidgetTypes.label(WidgetTypes.norm(args.optString("type")) ?: "custom") }}\u201d to Home"
        UPDATE -> "Changing a Home widget"
        REMOVE -> "Removing a Home widget"
        else -> "Checking your Home board"
    }

    /** Config may arrive as an object or a JSON string. */
    private fun cfgOf(a: JSONObject): JSONObject = when (val c = a.opt("config")) {
        is JSONObject -> c
        is String -> try { JSONObject(c) } catch (_: Exception) { JSONObject() }
        else -> JSONObject()
    }

    /** Fills obvious gaps (a crypto ask with no ids, a sports ask with only a title…). */
    fun normalize(type: String, title: String, cfg: JSONObject): JSONObject {
        when (type) {
            WidgetTypes.SPORTS -> if (cfg.optString("team").isBlank() && title.isNotBlank()) cfg.put("team", title)
            WidgetTypes.PRICES -> {
                if (cfg.optString("kind").isBlank()) cfg.put("kind", when { cfg.has("base") -> "fx"; cfg.has("symbols") && !cfg.has("ids") -> "stock"; else -> "crypto" })
                if (cfg.optString("kind") == "crypto" && cfg.optString("ids").isBlank() && title.isNotBlank()) cfg.put("ids", CoinGecko.id(title))
            }
            WidgetTypes.NEWS -> if (!cfg.has("query") && cfg.optString("feed").isBlank()) cfg.put("query", title.removeSuffix(" news").removeSuffix(" News"))
            WidgetTypes.CREATORS -> if (cfg.optString("channel").isBlank() && title.isNotBlank()) cfg.put("channel", title)
            WidgetTypes.WEATHER -> if (cfg.optString("city").isBlank() && !cfg.has("use_location")) cfg.put("use_location", true)
        }
        return cfg
    }

    fun line(w: Widget, d: WidgetData?, now: Long): String =
        "${w.id} · ${w.type} · \u201c${w.title}\u201d · stack ${w.category.ifBlank { "-" }} · ${WidgetSize.label(w.size).lowercase()} · ${if (w.hidden) "hidden" else "shown"} · " +
            (d?.let { if (it.error.isNotBlank()) "problem: ${it.error}" else Ago.of(it.fetched, now).lowercase() } ?: "not updated yet") + " · config ${w.config}"

    /** Runs a tool. [onChange] receives the id that was added / changed ("" for a removal). */
    fun run(store: HomeStore, name: String, args: JSONObject, now: Long = System.currentTimeMillis(), onChange: (String) -> Unit = {}): String = when (name) {
        ADD -> {
            val type = WidgetTypes.norm(args.optString("type")) ?: WidgetTypes.CUSTOM
            val title = args.optString("title").trim().take(60)
            val cfg = normalize(type, title, cfgOf(args))
            val t = title.ifBlank {
                when (type) {
                    WidgetTypes.SPORTS -> cfg.optString("team"); WidgetTypes.NEWS -> cfg.optString("query").ifBlank { "Top stories" }
                    WidgetTypes.WEATHER -> cfg.optString("city").let { if (it.isBlank()) "Weather" else "Weather in $it" }
                    WidgetTypes.PRICES -> cfg.optString("ids").ifBlank { cfg.optString("symbols") }.let { CoinGecko.pretty(it.substringBefore(',')) }
                    WidgetTypes.CREATORS -> cfg.optString("channel").removePrefix("@")
                    else -> WidgetTypes.label(type)
                }
            }.ifBlank { WidgetTypes.label(type) }
            val dupe = store.all().firstOrNull { it.type == type && it.cfg().toString() == cfg.toString() }
            if (dupe != null) {
                if (dupe.hidden) store.update(dupe.copy(hidden = false), now)
                onChange(dupe.id)
                "Already on Home as \u201c${dupe.title}\u201d (id ${dupe.id})" + (if (dupe.hidden) "; it was hidden, so I showed it again." else ".")
            } else {
                val w = store.add(Widget("", type, t, args.optString("category").trim().take(30).ifBlank { WidgetTypes.defaultCategory(type) },
                    cfg.toString(), WidgetSize.norm(args.optString("size"))), now)
                onChange(w.id)
                "Added \u201c${w.title}\u201d to Home (id ${w.id}, ${w.type}, stack ${w.category}). It fills in within a few seconds; the user sees it on the Home tab."
            }
        }
        UPDATE -> {
            val id = args.optString("id").trim()
            val w = store.get(id)
            if (w == null) "No Home widget with id \u201c$id\u201d. Call $LIST for ids."
            else {
                val cfg = w.cfg()
                if (args.has("config")) { val n = cfgOf(args); for (k in n.keys()) cfg.put(k, n.get(k))
                    // A changed city / team / channel invalidates what was looked up for the old one.
                    if (n.has("city")) { cfg.remove("lat"); cfg.remove("lon"); cfg.remove("geo_city"); cfg.remove("place") }
                    if (n.has("team")) { cfg.remove("team_id"); cfg.remove("team_query") }
                    if (n.has("channel")) { cfg.remove("channel_id"); cfg.remove("seen_at") }
                }
                val nw = w.copy(
                    title = args.optString("title").trim().take(60).ifBlank { w.title },
                    category = if (args.has("category")) args.optString("category").trim().take(30) else w.category,
                    size = if (args.has("size")) WidgetSize.norm(args.optString("size")) else w.size,
                    hidden = if (args.has("hidden")) args.optBoolean("hidden") else w.hidden,
                    config = cfg.toString())
                store.update(nw, now)
                if (args.has("position")) store.moveTo(id, args.optInt("position"))
                onChange(id)
                "Updated \u201c${nw.title}\u201d on Home."
            }
        }
        REMOVE -> {
            val id = args.optString("id").trim()
            val w = store.get(id)
            if (w == null) "No Home widget with id \u201c$id\u201d. Call $LIST for ids."
            else { store.remove(id); onChange(""); "Removed \u201c${w.title}\u201d from Home." }
        }
        LIST -> {
            val all = store.all(); val data = store.allData()
            if (all.isEmpty()) "The Home board is empty. Add widgets with $ADD."
            else "Home board, top to bottom (${all.size}):\n" + all.joinToString("\n") { line(it, data[it.id], now) }
        }
        else -> "Unknown Home tool."
    }
}
