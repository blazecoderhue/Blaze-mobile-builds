package com.blaze.assistant.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** The actions Blaze can take on the phone, exposed to the AI as tools. */
object PhoneTools {

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put(
            "function", JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("parameters", JSONObject().put("type", "object").put("properties", props)
                    .put("required", JSONArray(required)))
        )

    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    fun definitions(): JSONArray = JSONArray().apply {
        put(fn("open_app", "Open an installed app by its name, e.g. 'WhatsApp', 'Camera', 'Settings'.",
            JSONObject().put("app_name", p("string", "App name as shown on the home screen")), listOf("app_name")))
        put(fn("read_screen", "Read what is currently on the screen: the foreground app and a numbered list of elements. Call this before tapping or typing, and again after acting to check the result."))
        if (android.os.Build.VERSION.SDK_INT >= 30)
            put(fn("look_at_screen", "Look at the screen. Returns the screen as text when that says enough (fast); set pixels=true for a real screenshot when you must see images, maps, games, video, or how something looks.",
                JSONObject().put("pixels", p("boolean", "true = always take a screenshot")), emptyList()))
        put(fn("tap", "Tap an element from the latest screen by its number, or by its visible text with 'text' (safer when numbers may be stale).",
            JSONObject().put("element", p("integer", "Element number"))
                .put("text", p("string", "Visible text or description of the element, instead of a number"))
                .put("long_press", p("boolean", "Long-press instead of tap"))
                .put("want", JSONObject().put("type", "string").put("enum", JSONArray(listOf("on", "off")))
                    .put("description", "For a switch: the state it should end in. Blaze reads it first and doesn't tap if it already is."))))
        put(fn("type_text", "Type into a text field (replaces its content). A search icon/bar works too: it's tapped first. Set submit=true to press Enter/Search after typing.",
            JSONObject().put("element", p("integer", "Element number of the field"))
                .put("text", p("string", "Text to type"))
                .put("submit", p("boolean", "Press the keyboard's Enter/Search key after typing")), listOf("element", "text")))
        put(fn("search_in_app", "Open an app straight to search results for a query, with no tapping: YouTube, YouTube Music, Spotify, Play Store, Google Maps, Google, Chrome, TikTok, X, Netflix, Amazon. Use play=true to start playing the top music/video match where the app supports it. Best first step for 'play X on Y' or 'search Y for X'.",
            JSONObject().put("app", p("string", "App name, e.g. YouTube"))
                .put("query", p("string", "What to search for"))
                .put("play", p("boolean", "Try to start playing the first result (music/video apps)")), listOf("app", "query")))
        put(fn("scroll", "Scroll the screen or a scrollable element.",
            JSONObject().put("direction", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("up", "down", "left", "right"))))
                .put("element", p("integer", "Optional scrollable element number")), listOf("direction")))
        put(fn("press_button", "Press a system button.",
            JSONObject().put("button", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("back", "home", "recents", "notifications", "quick_settings", "lock")))),
            listOf("button")))
        put(fn("open_url", "Open a web address in the browser, or a Google search for a query.",
            JSONObject().put("url", p("string", "Full URL, or plain words to search Google")), listOf("url")))
        put(fn("dial", "Open the phone dialer with a number filled in (the user presses call).",
            JSONObject().put("number", p("string", "Phone number")), listOf("number")))
        put(fn("compose_sms", "Open the messaging app with a text drafted to a number (the user presses send).",
            JSONObject().put("number", p("string", "Phone number"))
                .put("message", p("string", "Message text")), listOf("number", "message")))
        put(fn("set_alarm", "Set an alarm in the clock app.",
            JSONObject().put("hour", p("integer", "0-23")).put("minute", p("integer", "0-59"))
                .put("label", p("string", "Optional label")), listOf("hour", "minute")))
        put(fn("set_timer", "Start a countdown timer.",
            JSONObject().put("seconds", p("integer", "Length in seconds"))
                .put("label", p("string", "Optional label")), listOf("seconds")))
        put(fn("wait", "Wait for the screen to load.",
            JSONObject().put("seconds", p("number", "1 to 5")), listOf("seconds")))
    }

    /** Human-friendly line shown in the chat while a tool runs. */
    fun describe(name: String, args: JSONObject): String = when (name) {
        "open_app" -> "Opening ${args.optString("app_name")}"
        "read_screen" -> "Looking at the screen"
        "tap" -> if (args.optBoolean("long_press")) "Long-pressing" else "Tapping"
        "type_text" -> "Typing \"${args.optString("text").take(40)}\""
        "scroll" -> "Scrolling ${args.optString("direction")}"
        "press_button" -> "Pressing ${args.optString("button")}"
        "open_url" -> "Opening ${args.optString("url").take(40)}"
        "dial" -> "Opening the dialer"
        "compose_sms" -> "Drafting a text"
        "set_alarm" -> "Setting an alarm for %02d:%02d".format(args.optInt("hour"), args.optInt("minute"))
        "set_timer" -> "Starting a timer"
        "search_in_app" -> "Searching ${args.optString("app")} for \u201c${args.optString("query").take(40)}\u201d"
        "wait" -> "Waiting a moment"
        else -> name
    }

    suspend fun run(ctx: Context, name: String, args: JSONObject): String {
        val svc = BlazeAccessibilityService.instance
        fun needSvc() = "Phone control is off right now. Blaze's browser tools (browser_*) and background tools don't need it, so keep going with those if they fit. " +
            "For the screen itself, the chat shows a one-tap 'Turn on' banner; tell the user to tap it (it opens Blaze's switch in Accessibility settings)."
        // v0.45.1: any phone tool but a read may change the screen, so the next read walks the tree again (ScreenReadCache).
        if (name != "read_screen") BlazeAccessibilityService.touched()
        return try {
            when (name) {
                "open_app" -> openApp(ctx, args.getString("app_name"))
                "read_screen" -> svc?.readScreen() ?: needSvc()
                "tap" -> when {
                    svc == null -> needSvc()
                    args.optString("text").isNotBlank() && !args.has("element") -> svc.tapText(args.optString("text"), args.optBoolean("long_press"))
                    args.has("element") -> svc.tap(args.getInt("element"), args.optBoolean("long_press"))
                    else -> "Give an element number or its text."
                }
                "type_text" -> svc?.type(args.getInt("element"), args.getString("text"), args.optBoolean("submit")) ?: needSvc()
                "search_in_app" -> AppSearch.run(ctx, args.optString("app"), args.optString("query"), args.optBoolean("play"))
                "scroll" -> svc?.scroll(args.getString("direction"),
                    if (args.has("element")) args.getInt("element") else null) ?: needSvc()
                "press_button" -> svc?.press(args.getString("button")) ?: needSvc()
                "open_url" -> {
                    val raw = com.blaze.assistant.agent.ToolLeak.fixBlazeUrl(args.getString("url").trim())
                    val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                    else if (raw.contains('.') && !raw.contains(' ')) "https://$raw"
                    else "https://www.google.com/search?q=" + Uri.encode(raw)
                    // v0.33.3: a Play Store page for an app that is installed opens the app instead; a details link
                    // for an app that isn't there is checked first (a guessed id shows "Item not found").
                    AppFinder.playId(url)?.let { id ->
                        if (InstalledApps.launch(ctx, id)) return "That app ($id) is installed, so I opened it instead of the Play Store. Never offer to install it."
                        if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { playMissing(id) })
                            return "There is no Play Store app with the id $id (a guessed id). Don't guess ids: open a Play Store search instead, " +
                                "https://play.google.com/store/search?q=<name>&c=apps, or check installed apps with list_apps."
                    }
                    start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    "Opened $url"
                }
                "dial" -> { start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + args.getString("number")))); "Dialer opened." }
                "compose_sms" -> {
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + args.getString("number")))
                        .putExtra("sms_body", args.getString("message"))
                    start(ctx, i); "Message drafted; the user needs to press send."
                }
                "set_alarm" -> {
                    // The registry ID goes into the clock label so the alarm can be found and deleted later.
                    val h = args.getInt("hour"); val m = args.getInt("minute")
                    val pid = com.blaze.assistant.agent.Processes.nextId("alarm")
                    val name = args.optString("label").trim()
                    val label = (if (name.isNotBlank()) "$name " else "") + "(Blaze $pid)"
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, h)
                        .putExtra(AlarmClock.EXTRA_MINUTES, m)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    start(ctx, i)
                    com.blaze.assistant.agent.Processes.register("alarm", name.ifBlank { "Alarm %02d:%02d".format(h, m) }, label,
                        com.blaze.assistant.agent.Processes.nextAt(h, m),
                        org.json.JSONObject().put("label", label).put("name", name).put("time", "%02d:%02d".format(h, m)), id = pid)
                    // v0.45.13 (phone 17:12, "reminder at 18:30" for an 18:15 alarm): the result names the exact time and label set.
                    "Alarm set for ${"%02d:%02d".format(h, m)}, label \u201c$label\u201d. [ID $pid]"
                }
                "set_timer" -> {
                    val secs = args.getInt("seconds")
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (args.has("label")) i.putExtra(AlarmClock.EXTRA_MESSAGE, args.getString("label"))
                    start(ctx, i)
                    val pid = com.blaze.assistant.agent.Processes.register("timer",
                        args.optString("label").ifBlank { if (secs >= 60) "${secs / 60} min timer" else "$secs s timer" },
                        System.currentTimeMillis().toString(), System.currentTimeMillis() + secs * 1000L,
                        org.json.JSONObject().put("seconds", secs))
                    "Timer started for ${if (secs >= 60) "${secs / 60} min" + (if (secs % 60 > 0) " ${secs % 60} s" else "") else "$secs s"}. [ID $pid]"
                }
                "wait" -> {
                    delay((args.optDouble("seconds", 1.0).coerceIn(0.5, 5.0) * 1000).toLong()); "Waited."
                }
                else -> "Unknown tool $name"
            }
        } catch (e: ActivityNotFoundException) {
            "No app on this phone can do that."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /** A screenshot as a JPEG data URL, or an explanation when it can't be taken. */
    suspend fun screenshot(): String {
        if (android.os.Build.VERSION.SDK_INT < 30) return "Screenshots need Android 11 or newer."
        val svc = BlazeAccessibilityService.instance ?: return "Phone control is off, so I can't take a screenshot."
        return svc.screenshot() ?: "Couldn't take a screenshot (some apps block it)."
    }

    /** True only when Play answers 404 for this id (network trouble or anything else = not proven missing). */
    private fun playMissing(id: String): Boolean = try {
        val c = java.net.URL("https://play.google.com/store/apps/details?id=" + Uri.encode(id) + "&hl=en").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = true; c.requestMethod = "GET"
        try { c.responseCode == 404 } finally { c.disconnect() }
    } catch (_: Exception) { false }

    private fun start(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private suspend fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        AppFinder.pkgIn(name)?.let { p -> pm.getLaunchIntentForPackage(p)?.let { i ->
            start(ctx, i); BlazeAccessibilityService.instance?.settle(maxMs = 2000, quietMs = 300) ?: delay(900)
            return "Opened $p. It is installed, so never offer to install it." } }
        val q = AppMatch.clean(name)
        if (q.isEmpty()) return "Couldn't tell which app '$name' means. Ask the user, or use the earlier context."
        // v0.45.6: Blaze's own screens open in-app (open_view), never by launching a look-alike app.
        AppMatch.blazeView(name)?.let { v -> return "\u201c$name\u201d is part of Blaze itself: call open_view with view=$v (or music_ops for playback) instead of open_app." }
        val labelled = apps.map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        // v0.45.6: exact package or exact label only, never a fuzzy match ("Blaze*" opened BlazeFX).
        val best = AppMatch.exact(name, labelled, ctx.packageName)
            ?: return AppMatch.near(name, labelled, ctx.packageName).let { near ->
                if (near.isNotEmpty()) "No installed app is called exactly '$name'. Close names: ${near.joinToString(", ")}. Ask the user which one they mean; never open a near match yourself."
                else "No installed app matches '$name' exactly (checked every launchable app by name and package). Installed apps include: " +
                    labelled.map { it.first }.sorted().take(60).joinToString(", ") +
                    ". Only if the user wants to get it: open a Play Store SEARCH (https://play.google.com/store/search?q=...&c=apps), never a guessed details?id= link."
            }
        val intent = pm.getLaunchIntentForPackage(best.first)
            ?: return "Couldn't open ${best.second}."
        start(ctx, intent)
        BlazeAccessibilityService.instance?.settle(maxMs = 2000, quietMs = 300) ?: delay(900)
        // v0.45.6: check what actually came to the front.
        val front = try { BlazeAccessibilityService.instance?.currentPkg().orEmpty() } catch (_: Exception) { "" }
        if (front.isNotEmpty() && front != best.first && !front.startsWith("com.android.systemui"))
            return "Tried to open ${best.second} (${best.first}), but $front is in front instead. Tell the user it didn't open; don't act on the wrong app."
        return "Opened ${best.second} (${best.first})" + (if (front == best.first) ", checked: it's in front" else "") + ". It is installed, so never offer to install it."
    }
}
