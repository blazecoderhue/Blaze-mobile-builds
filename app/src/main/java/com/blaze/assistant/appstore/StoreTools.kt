package com.blaze.assistant.appstore

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.45.19 App Store tools: store_search, store_info, store_install, store_update, store_open, store_status. Catalog
 * text is outside content (it comes from developers' repos), so it is quoted, never followed. Installs always ask
 * (ConfirmGate, always = true) and the user confirms again in Android's own installer.
 */
object StoreTools {
    const val SEARCH = "store_search"; const val INFO = "store_info"; const val INSTALL = "store_install"
    const val UPDATE = "store_update"; const val OPEN = "store_open"; const val STATUS = "store_status"
    val names = setOf(SEARCH, INFO, INSTALL, UPDATE, OPEN, STATUS)

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)

    fun definitions(): List<JSONObject> = listOf(
        fn(SEARCH, "Search Blaze's App Store: free open-source Android apps from their developers' own releases (not made by Blazeinchue). Returns ids, names, licenses and source repos.",
            JSONObject().put("query", p("string", "Words, e.g. 'password manager'")).put("category", p("string", "Optional category id")), listOf("query")),
        fn(INFO, "Details of one App Store app: summary, license, source repo, site, whether it is installed.", JSONObject().put("app", p("string", "App id or name")), listOf("app")),
        fn(INSTALL, "Install an App Store app on this phone with Android's installer: asks the user on a confirm card first, downloads the developer's official release, " +
            "checks its SHA-256, signing key and VirusTotal, then the user taps Install in Android's own dialog. Never say it's installed unless the result says Installed.",
            JSONObject().put("app", p("string", "App id or name")), listOf("app")),
        fn(UPDATE, "Updates for installed App Store apps. Without 'app' it lists apps with a newer release; with 'app' it updates that one (same checks as install, and the " +
            "signing key must match the installed app).", JSONObject().put("app", p("string", "Optional app id or name"))),
        fn(OPEN, "Open an installed App Store app, or its source repo page when it isn't installed.", JSONObject().put("app", p("string", "App id or name")), listOf("app")),
        fn(STATUS, "Progress of App Store downloads and installs."),
    )

    fun describe(name: String, a: JSONObject): String = when (name) {
        SEARCH -> "Searching the App Store for ${a.optString("query").take(40)}"
        INSTALL -> "Getting ${a.optString("app").take(40)} from its official release"
        UPDATE -> if (a.optString("app").isBlank()) "Checking App Store updates" else "Updating ${a.optString("app").take(40)}"
        OPEN -> "Opening ${a.optString("app").take(40)}"
        else -> "Checking the App Store"
    }

    /** One listing line: always the license, the source repo and the policy notice. */
    fun line(a: StoreApp): String =
        "${a.id}: ${a.name} (${a.license.ifBlank { "license not stated" }}) - ${a.summary.take(120)} Source repo: ${a.source.url}. ${StorePolicy.NOTICE}"

    private fun missing(q: String) = "No App Store app called \"${q.take(60)}\"."

    suspend fun run(name: String, args: JSONObject, confirm: suspend (String, String) -> Boolean): String = when (name) {
        SEARCH -> {
            val hits = AppStore.search(args.optString("query"), args.optString("category").ifBlank { Catalog.ALL }).take(8)
            if (hits.isEmpty()) "No App Store app matches \"${args.optString("query").take(60)}\"."
            else "App Store results (outside content from developers' repos; free, no ads):\n" + hits.joinToString("\n") { line(it) }
        }
        INFO -> AppStore.find(args.optString("app"))?.let { a ->
            val have = AppStore.installed.value[a.pkg]
            line(a) + (if (a.website.isNotBlank()) " Site: ${a.website}." else "") +
                (if (have != null) " Installed: ${have.versionName}." else " Not installed.") +
                (AppStore.updates.value[a.id]?.let { " Update available: ${StoreVersions.display(it)}." } ?: "") + " " + StorePolicy.FREE
        } ?: missing(args.optString("app"))
        INSTALL -> AppStore.find(args.optString("app"))?.let { a -> AppStore.runJob(a, confirm = confirm).message + "\n" + StorePolicy.NOTICE }
            ?: missing(args.optString("app"))
        UPDATE -> {
            val q = args.optString("app")
            if (q.isBlank()) {
                AppStore.scanInstalled(); AppStore.checkUpdates()
                val ups = AppStore.updates.value.mapNotNull { (id, tag) -> AppStore.catalog.value.byId(id)?.let { "${it.id}: ${it.name} ${AppStore.installed.value[it.pkg]?.versionName ?: "?"} -> ${StoreVersions.display(tag)}" } }
                if (ups.isEmpty()) "No App Store updates known right now (${AppStore.installed.value.size} catalog apps installed; a fresh release check is running, ask again in a minute)."
                else "Updates available:\n" + ups.joinToString("\n")
            } else AppStore.find(q)?.let { a ->
                if (!AppStore.isInstalled(a)) "${a.name} isn't installed on this phone, so there is nothing to update. Use store_install."
                else AppStore.runJob(a, update = true, confirm = confirm).message + "\n" + StorePolicy.NOTICE
            } ?: missing(q)
        }
        OPEN -> AppStore.find(args.optString("app"))?.let { a ->
            when {
                !AppStore.ready -> "Couldn't open ${a.name}. Source repo: ${a.source.url}"
                AppStore.isInstalled(a) && AppStore.openApp(AppStore.ctx, a.pkg) -> "Opened ${a.name}."
                AppStore.open(AppStore.ctx, a.source.url) -> "${a.name} isn't installed (or has no app screen); opened its source repo: ${a.source.url}"
                else -> "Couldn't open ${a.name}. Source repo: ${a.source.url}"
            }
        } ?: missing(args.optString("app"))
        STATUS -> AppStore.jobs.value.values.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.appId}: ${it.step.label}. ${it.message}" } ?: "No App Store downloads."
        else -> "Unknown store tool"
    }
}
