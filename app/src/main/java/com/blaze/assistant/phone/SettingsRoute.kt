package com.blaze.assistant.phone

/**
 * v0.30.4 fast actions: settings by direct intent. "Stop GitHub opening links" used to be 18 steps of tapping
 * through Settings › Apps › Default apps › Opening links… with labels that differ on every skin. Android has a
 * public intent for most of these pages, most of them per app ("package:com.github.android"), so one open_settings
 * call lands on the exact page. This file is the pure part: page names and their aliases, the intent chain for a page
 * on a given Android version (newest first, then fallbacks), the rows to tap after a fallback lands one level up, and
 * the app-name → package resolver. Pure (SettingsRouteTest); SettingsJump runs it on the phone.
 */
object SettingsRoute {

    /** One intent to try: action, optional data URI ("package:…") and string extras. */
    data class Launch(val action: String, val data: String? = null, val extras: Map<String, String> = emptyMap(), val minSdk: Int = 1)

    /** How to reach [page]: try [launches] in order; after a launch at index ≥ [tapFrom], tap [thenTap] (first that matches). */
    data class Plan(val page: String, val launches: List<Launch>, val thenTap: List<String> = emptyList(), val tapFrom: Int = Int.MAX_VALUE)

    const val DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val OPEN_BY_DEFAULT = "android.settings.APP_OPEN_BY_DEFAULT_SETTINGS"   // Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, API 31
    const val APP_NOTIFICATIONS = "android.settings.APP_NOTIFICATION_SETTINGS"
    const val EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"

    /** Pages for one app (need an app). */
    val appPages = linkedMapOf(
        "open_by_default" to "which links the app opens itself (Open by default / Set as default / Opening links)",
        "app_details" to "App info",
        "app_notifications" to "the app's notifications",
        "app_permissions" to "the app's permissions",
        "app_battery" to "the app's battery use / background limits",
        "app_storage" to "storage and cache",
        "app_data" to "mobile data and Wi-Fi use",
        "install_unknown" to "install unknown apps from it",
        "overlay" to "display over other apps",
        "all_files_access" to "all files access",
        "app_language" to "the app's language",
        "alarms" to "alarms and reminders",
    )

    /** Phone-wide pages: page → intent actions, newest first. */
    val globalPages: Map<String, List<Launch>> = linkedMapOf(
        "settings" to listOf(Launch("android.settings.SETTINGS")),
        "wifi" to listOf(Launch("android.settings.WIFI_SETTINGS")),
        "bluetooth" to listOf(Launch("android.settings.BLUETOOTH_SETTINGS")),
        "hotspot" to listOf(Launch("android.settings.TETHER_SETTINGS"), Launch("android.settings.WIRELESS_SETTINGS")),
        "mobile_network" to listOf(Launch("android.settings.NETWORK_OPERATOR_SETTINGS"), Launch("android.settings.DATA_ROAMING_SETTINGS")),
        "data_usage" to listOf(Launch("android.settings.DATA_USAGE_SETTINGS")),
        "airplane" to listOf(Launch("android.settings.AIRPLANE_MODE_SETTINGS")),
        "nfc" to listOf(Launch("android.settings.NFC_SETTINGS")),
        "location" to listOf(Launch("android.settings.LOCATION_SOURCE_SETTINGS")),
        "display" to listOf(Launch("android.settings.DISPLAY_SETTINGS")),
        "sound" to listOf(Launch("android.settings.SOUND_SETTINGS")),
        "dnd" to listOf(Launch("android.settings.ZEN_MODE_SETTINGS"), Launch("android.settings.SOUND_SETTINGS")),
        "battery" to listOf(Launch("android.intent.action.POWER_USAGE_SUMMARY"), Launch("android.settings.BATTERY_SAVER_SETTINGS")),
        "battery_saver" to listOf(Launch("android.settings.BATTERY_SAVER_SETTINGS")),
        "battery_optimization" to listOf(Launch("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")),
        "apps" to listOf(Launch("android.settings.MANAGE_APPLICATIONS_SETTINGS"), Launch("android.settings.APPLICATION_SETTINGS")),
        "default_apps" to listOf(Launch("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")),
        "notifications" to listOf(Launch("android.settings.ALL_APPS_NOTIFICATION_SETTINGS"), Launch("android.settings.NOTIFICATION_SETTINGS")),
        "notification_access" to listOf(Launch("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")),
        "storage" to listOf(Launch("android.settings.INTERNAL_STORAGE_SETTINGS")),
        "accessibility" to listOf(Launch("android.settings.ACCESSIBILITY_SETTINGS")),
        "security" to listOf(Launch("android.settings.SECURITY_SETTINGS")),
        "privacy" to listOf(Launch("android.settings.PRIVACY_SETTINGS"), Launch("android.settings.SECURITY_SETTINGS")),
        "language" to listOf(Launch("android.settings.LOCALE_SETTINGS")),
        "keyboard" to listOf(Launch("android.settings.INPUT_METHOD_SETTINGS")),
        "date_time" to listOf(Launch("android.settings.DATE_SETTINGS")),
        "developer" to listOf(Launch("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")),
        "usage_access" to listOf(Launch("android.settings.USAGE_ACCESS_SETTINGS")),
        "write_settings" to listOf(Launch("android.settings.action.MANAGE_WRITE_SETTINGS")),
        "cast" to listOf(Launch("android.settings.CAST_SETTINGS")),
        "vpn" to listOf(Launch("android.settings.VPN_SETTINGS")),
        "print" to listOf(Launch("android.settings.ACTION_PRINT_SETTINGS")),
        "about_phone" to listOf(Launch("android.settings.DEVICE_INFO_SETTINGS")),
        "night_light" to listOf(Launch("android.settings.NIGHT_DISPLAY_SETTINGS"), Launch("android.settings.DISPLAY_SETTINGS")),
        "dark_mode" to listOf(Launch("android.settings.DARK_THEME_SETTINGS", minSdk = 29), Launch("android.settings.DISPLAY_SETTINGS")),
    )

    val pageNames: List<String> get() = appPages.keys.toList() + globalPages.keys

    private val aliases: List<Pair<Regex, String>> = listOf(
        "open(ing)?( supported)? links|app links|supported links|default links|open by default|set as default|links? (it )?opens?|links in (the )?app|opens? links" to "open_by_default",
        "app ?info|app details|application info|details" to "app_details",
        "notif\\w*" to "notifications",
        "permissions?" to "app_permissions",
        "battery saver|power sav\\w*" to "battery_saver",
        "battery optimi[sz]ation|unrestricted battery|background (limits?|usage)" to "battery_optimization",
        "battery|power" to "battery",
        "storage|cache" to "storage",
        "mobile data|data usage|data" to "data_usage",
        "unknown (apps|sources)|install unknown" to "install_unknown",
        "overlay|draw over|display over|appear on top" to "overlay",
        "all files|files access|manage storage" to "all_files_access",
        "app language" to "app_language",
        "alarms?( and reminders)?|exact alarm" to "alarms",
        "wi[- ]?fi|wlan|internet" to "wifi",
        "bluetooth|bt" to "bluetooth",
        "hotspot|tether\\w*" to "hotspot",
        "location|gps" to "location",
        "display|brightness|screen timeout|font size" to "display",
        "dark (mode|theme)" to "dark_mode",
        "night (light|mode)|eye comfort|blue light" to "night_light",
        "sound|ringtone|vibrat\\w*|volume" to "sound",
        "do not disturb|dnd|zen" to "dnd",
        "default apps?|default browser|browser app" to "default_apps",
        "all apps|apps|applications" to "apps",
        "accessibility" to "accessibility",
        "security|lock ?screen|fingerprint|biometrics?" to "security",
        "privacy" to "privacy",
        "language|locale" to "language",
        "keyboard" to "keyboard",
        "date|time" to "date_time",
        "developer( options)?|usb debugging" to "developer",
        "usage access" to "usage_access",
        "vpn" to "vpn",
        "cast|screen mirror\\w*" to "cast",
        "airplane|flight mode" to "airplane",
        "nfc" to "nfc",
        "about( phone| device)?|build number" to "about_phone",
        "network|sim|mobile network|carrier" to "mobile_network",
    ).map { (p, v) -> Regex("^(?:$p)$", RegexOption.IGNORE_CASE) to v }

    /** The page id for what the model or user called it ("opening links" → open_by_default), with or without an app. */
    fun page(raw: String, hasApp: Boolean): String? {
        val low = raw.trim().lowercase()
        val key = low.replace('-', '_').replace(' ', '_')
        if (key in appPages || key in globalPages) return fixForApp(key, hasApp)
        val words = low.replace('_', ' ').replace(Regex("\\s+(settings?|page|screen|menu)$"), "").trim()
        val hit = aliases.firstOrNull { it.first.matches(words) }?.second ?: return null
        return fixForApp(hit, hasApp)
    }

    /** "notifications" for an app is that app's page; "app_details" with no app is the apps list. */
    private fun fixForApp(p: String, hasApp: Boolean): String = when {
        hasApp && p == "notifications" -> "app_notifications"
        hasApp && p == "battery" || hasApp && p == "battery_optimization" -> "app_battery"
        hasApp && p == "storage" -> "app_storage"
        hasApp && p == "data_usage" -> "app_data"
        hasApp && p == "language" -> "app_language"
        hasApp && p in setOf("apps", "settings") -> "app_details"
        !hasApp && p == "app_notifications" -> "notifications"
        !hasApp && p in setOf("app_details", "open_by_default", "app_permissions") -> if (p == "open_by_default") "default_apps" else "apps"
        !hasApp && p == "app_battery" -> "battery"
        !hasApp && p == "app_storage" -> "storage"
        !hasApp && p == "app_data" -> "data_usage"
        else -> p
    }

    /** Rows to tap on an app's App info page when there's no direct intent (labels across skins; LabelMatch adds more). */
    val rowsFor: Map<String, List<String>> = mapOf(
        "open_by_default" to listOf("Open by default", "Set as default", "Opening links", "Open supported links", "App links"),
        "app_permissions" to listOf("Permissions", "App permissions"),
        "app_battery" to listOf("Battery", "App battery usage", "Battery usage", "Battery optimization"),
        "app_storage" to listOf("Storage", "Storage & cache", "Storage usage"),
        "app_data" to listOf("Mobile data", "Mobile data & Wi-Fi", "Data usage", "Mobile data usage"),
        "app_notifications" to listOf("Notifications", "App notifications"),
    )

    /** The intent chain for [page] on Android [sdk]; [pkg] is required for app pages. Null = unknown page or app missing. */
    fun plan(page: String, pkg: String?, sdk: Int): Plan? {
        globalPages[page]?.let { l -> return Plan(page, l.filter { sdk >= it.minSdk }) }
        if (page !in appPages || pkg.isNullOrBlank()) return null
        val uri = "package:$pkg"
        val details = Launch(DETAILS, uri)
        val l = when (page) {
            "app_details" -> listOf(details)
            "open_by_default" -> listOf(Launch(OPEN_BY_DEFAULT, uri, minSdk = 31), details)
            "app_notifications" -> listOf(Launch(APP_NOTIFICATIONS, null, mapOf(EXTRA_APP_PACKAGE to pkg, "app_package" to pkg), minSdk = 26), details)
            "install_unknown" -> listOf(Launch("android.settings.MANAGE_UNKNOWN_APP_SOURCES", uri, minSdk = 26), details)
            "overlay" -> listOf(Launch("android.settings.action.MANAGE_OVERLAY_PERMISSION", uri), details)
            "all_files_access" -> listOf(Launch("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", uri, minSdk = 30), details)
            "app_language" -> listOf(Launch("android.settings.APP_LOCALE_SETTINGS", uri, minSdk = 33), details)
            "alarms" -> listOf(Launch("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", uri, minSdk = 31), details)
            else -> listOf(details)   // permissions, battery, storage, data: App info, then the row
        }.filter { sdk >= it.minSdk }
        val rows = rowsFor[page].orEmpty()
        // Rows are tapped only when we landed on App info (the details intent), never after a direct page.
        val tapFrom = l.indexOfFirst { it.action == DETAILS }.takeIf { it >= 0 && rows.isNotEmpty() } ?: Int.MAX_VALUE
        return Plan(page, l, rows, tapFrom)
    }

    /** Well-known packages, for apps whose launcher label doesn't say their name. */
    val known = mapOf(
        "github" to "com.github.android", "whatsapp" to "com.whatsapp", "whatsapp business" to "com.whatsapp.w4b",
        "youtube" to "com.google.android.youtube", "chrome" to "com.android.chrome", "gmail" to "com.google.android.gm",
        "google maps" to "com.google.android.apps.maps", "maps" to "com.google.android.apps.maps", "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana", "messenger" to "com.facebook.orca", "tiktok" to "com.zhiliaoapp.musically",
        "x" to "com.twitter.android", "twitter" to "com.twitter.android", "telegram" to "org.telegram.messenger",
        "spotify" to "com.spotify.music", "reddit" to "com.reddit.frontpage", "linkedin" to "com.linkedin.android",
        "play store" to "com.android.vending", "google play" to "com.android.vending", "snapchat" to "com.snapchat.android",
        "netflix" to "com.netflix.mediaclient", "discord" to "com.discord", "slack" to "com.Slack", "zoom" to "us.zoom.videomeetings",
        "outlook" to "com.microsoft.office.outlook", "teams" to "com.microsoft.teams", "samsung internet" to "com.sec.android.app.sbrowser",
        "blaze" to "com.blaze.assistant",
    )

    /**
     * The package for [query] among installed [apps] (label, package): a package name as given, then the launcher
     * label (AppMatch rules, whole words), then the well-known map (only if that package is installed). Null if none.
     */
    fun resolveApp(query: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val q = query.trim()
        if (q.isEmpty()) return null
        apps.firstOrNull { it.second.equals(q, true) }?.let { return it }
        val scored = apps.mapNotNull { (label, pkg) -> AppMatch.score(q, label, pkg)?.let { Triple(label, pkg, it) } }
            .sortedWith(compareBy({ it.third }, { it.first.length }))
        scored.firstOrNull()?.let { return it.first to it.second }
        val k = AppMatch.clean(q)
        known[k]?.let { p -> apps.firstOrNull { it.second == p }?.let { return it } }
        return null
    }
}
