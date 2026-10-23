package com.blaze.store.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true; encodeDefaults = true }

object Platform { const val ANDROID = "Android"; const val PC = "PC"; const val TV = "TV" }

/** What this device runs, read once at process start before any screen or background job asks. */
object Device { @Volatile var isTv = false }

/** Android apps install everywhere; TV apps install natively too when Blaze Store runs on a TV (beta). */
fun AppItem.installsHere() = platform == Platform.ANDROID || (platform == Platform.TV && Device.isTv)

enum class SortOption(val label: String) {
    HOME("Home Page"), NEWEST("Recently Added"), OLDEST("Oldest Added"), NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"), SIZE_ASC("Size (Smallest)"), SIZE_DESC("Size (Largest)")
}

enum class Tab(val platform: String?) { ANDROID(Platform.ANDROID), PC(Platform.PC), TV(Platform.TV), MYAPPS(null), ABOUT(null), UPDATES(null) }

val STREAMS = listOf("Stable", "Beta", "Alpha", "Nightly")

@Serializable data class AppVariant(val arch: String = "", val url: String = "", val size: Long? = null)

@Serializable data class VersionOption(val type: String = "Stable", val version: String = "", val date: String = "", val variants: List<AppVariant> = emptyList())

@Serializable data class AppItem(
    val id: String = "",
    val name: String = "Unknown App",
    val description: String = "",
    val icon: String = "",
    val version: String = "Latest",
    val latestVersion: String = "Latest",
    val downloadUrl: String = "#",
    val variants: List<AppVariant> = emptyList(),
    val availableVersions: List<VersionOption> = emptyList(),
    val repoUrl: String? = null,
    val githubRepo: String? = null,
    val gitlabRepo: String? = null,
    val codebergRepo: String? = null,
    val gitlabDomain: String? = null,
    val releaseKeyword: String? = null,
    val packageName: String? = null,
    val category: String = "Utility",
    val tags: List<String> = emptyList(),
    val platform: String = Platform.ANDROID,
    val size: String = "",
    val author: String = "Unknown",
    val screenshots: List<String> = emptyList(),
    val officialSite: String? = null,
    val patches: List<String> = emptyList(),
)

@Serializable data class SocialLinks(val github: String = "", val x: String = "", val discord: String = "", val coffee: String = "")
@Serializable data class DonationConfig(val kofiUrl: String? = null, val upiId: String? = null, val paypalUrl: String? = null)
@Serializable data class DevProfile(val name: String = "", val image: String? = null, val bio: String = "")
@Serializable data class FAQItem(val question: String = "", val answer: String = "", val icon: String = "")
@Serializable data class Notice(val id: String = "", val title: String = "", val message: String = "", val show: Boolean = false)
@Serializable data class PillConfig(val id: String? = null, val label: String? = null, val appId: String? = null, val tone: String? = null)
@Serializable data class ModuleBundleConfig(val id: String = "", val title: String = "", val description: String? = null, val appIds: List<String> = emptyList(), val color: String? = null, val badge: String? = null, val icon: String? = null, val monogram: String? = null)
@Serializable data class ModuleConfig(
    val id: String = "", val type: String = "", val title: String = "", val subtitle: String? = null, val platform: String? = null,
    val insertAfterCategory: Double? = null, val animation: String? = null, val appIds: List<String>? = null,
    val pills: List<PillConfig>? = null, val bundles: List<ModuleBundleConfig>? = null,
)

@Serializable data class StoreConfig(
    val appsJsonUrl: String = "",
    val mirrorJsonUrl: String? = null,
    val maintenanceMode: Boolean = false,
    val maintenanceMessage: String? = null,
    val announcement: String? = null,
    val notice: Notice? = null,
    val minStoreVersion: String? = null,
    val latestStoreVersion: String? = null,
    val storeDownloadUrl: String? = null,
    val socials: SocialLinks? = null,
    val donation: DonationConfig? = null,
    val devProfile: DevProfile? = null,
    val faqs: List<FAQItem>? = null,
    val supportEmail: String? = null,
    val easterEggUrl: String? = null,
    val storefrontModules: List<ModuleConfig>? = null,
)


data class BundleItem(val id: String, val title: String, val description: String, val appIds: List<String>, val apps: List<AppItem> = emptyList(), val color: String? = null, val badge: String? = null, val icon: String? = null, val monogram: String? = null)
data class CategoryCard(val id: String, val label: String, val icon: String, val count: Int, val accent: String, val gradient: List<String>)

enum class CollectionType { HERO, SWIMLANE, BUNDLE, RECOMMENDATION_BUNDLES, CATEGORY_CARDS, SORTED_GRID }

data class StoreCollection(
    val id: String, val title: String, val type: CollectionType, val subtitle: String? = null, val filter: String? = null,
    val animation: String? = null, val apps: List<AppItem> = emptyList(), val bundles: List<BundleItem> = emptyList(),
    val categoryCards: List<CategoryCard> = emptyList(), val totalAppCount: Int? = null,
)

object Defaults {
    const val CACHE_VERSION = "v1_6"
    const val NETWORK_TIMEOUT_MS = 8000L
    /** Blaze Store catalog, hosted by Blaze Site on Cloudflare Pages. Same JSON schema as Orion-Data (see CATALOG_SCHEMA.md). */
    const val CATALOG_BASE = "https://blazeinchue.pages.dev/store"
    /** Release notes in GitHub Releases JSON shape, published by Site next to the catalog. */
    const val RELEASES_API = "$CATALOG_BASE/releases.json"
    const val MIRROR_JSON = "$CATALOG_BASE/mirror.json"
    val CONFIG_URLS = listOf("$CATALOG_BASE/config.json")
    val APPS_URLS = listOf("$CATALOG_BASE/catalog.json")
    val MIRROR_LABELS = listOf("Blaze")
    val MICROG_DEPENDENT_APPS = setOf("youtube-revanced", "yt-music-revanced", "google-photos-revanced")
    const val MICROG_INFO_URL = "https://github.com/microg/GmsCore/wiki"
    val DEV_SOCIALS = SocialLinks("https://gitlab.com/blazeinchue/Blaze-agent", "https://x.com/blazeinchue", "", "")
    val DONATION = DonationConfig(null, null, null)
    val DEV_PROFILE = DevProfile("Blazeinchue", null, "Blaze Store is the app store of the Blaze assistant. No ads, no tracking. A GPL-3.0 fork of Orion Store by RookieEnough.")
    const val SUPPORT_EMAIL = ""
    const val EASTER_EGG = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    const val SELF_PACKAGE = "com.blaze.store"
    val PACKAGE_SUFFIXES = listOf(".preview", ".debug", ".test", ".beta", ".canary", ".dev", ".alpha", ".nightly", ".staging", ".release")
    val FONTS = listOf(
        "spaceGrotesk" to "Space Grotesk", "inter" to "Inter", "poppins" to "Poppins", "manrope" to "Manrope", "outfit" to "Outfit",
        "dmSans" to "DM Sans", "plusJakartaSans" to "Plus Jakarta Sans", "rubik" to "Rubik", "nunitoSans" to "Nunito Sans",
        "publicSans" to "Public Sans", "systemDefault" to "System Default",
    )
    val PTR_CHARACTERS = listOf("cat", "dog", "pokeball", "shield", "owl", "robot", "ghost", "kitty", "bunny", "batman")
    val FAQS = listOf(
        FAQItem("Is Blaze Store safe?", "Blaze Store is open source under GPL-3.0 (a fork of Orion Store by RookieEnough). It has no ads, no ad SDK and no trackers. The source of every release is published next to its APK on blazeinchue.pages.dev.", "fa-shield-cat"),
        FAQItem("Where do the apps come from?", "From each developer's own official releases (GitHub, GitLab, Codeberg or the project's site). The catalog only points at them; nothing is repackaged.", "fa-check-double"),
        FAQItem("Does Blaze Store scan apps?", "Yes. Before every install Blaze Store checks the APK's SHA-256 with VirusTotal (add your free VirusTotal API key once) and stops when engines flag it.", "fa-magnifying-glass"),
        FAQItem("Installation failed?", "If you see 'App not installed' or 'Package conflicts', it's likely a signature mismatch. You cannot install over an app signed by a different developer. Uninstall the existing app first, then install it from Blaze Store.", "fa-file-signature"),
        FAQItem("Download not working?", "Open the app's page and use the Report option. It opens a prefilled issue on the Blaze GitLab tracker.", "fa-bug"),
        FAQItem("Can Blaze use the store?", "Yes. The Blaze assistant can search, install, update and open apps through Blaze Store, and it always asks you before installing or updating anything.", "fa-robot"),
    )
}
