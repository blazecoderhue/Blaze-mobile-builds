package com.blaze.kiln.manifest

// Model of a guest app's manifest, read from compiled binary XML. Element/attribute semantics follow the
// <manifest> reference on developer.android.com and AOSP's package parser (Apache-2.0); names and layout are ours.

/** Framework attribute resource ids (android.R.attr, public and stable since their API level). */
object AndroidAttr {
    const val THEME = 0x01010000
    const val LABEL = 0x01010001
    const val ICON = 0x01010002
    const val NAME = 0x01010003
    const val PERMISSION = 0x01010006
    const val SHARED_USER_ID = 0x0101000b
    const val HAS_CODE = 0x0101000c
    const val ENABLED = 0x0101000e
    const val EXPORTED = 0x01010010
    const val PROCESS = 0x01010011
    const val TASK_AFFINITY = 0x01010012
    const val MULTIPROCESS = 0x01010013
    const val AUTHORITIES = 0x01010018
    const val PRIORITY = 0x0101001c
    const val LAUNCH_MODE = 0x0101001d
    const val SCREEN_ORIENTATION = 0x0101001e
    const val CONFIG_CHANGES = 0x0101001f
    const val VALUE = 0x01010024
    const val RESOURCE = 0x01010025
    const val MIME_TYPE = 0x01010026
    const val SCHEME = 0x01010027
    const val HOST = 0x01010028
    const val PATH = 0x0101002a
    const val TARGET_ACTIVITY = 0x01010202
    const val MIN_SDK_VERSION = 0x0101020c
    const val VERSION_CODE = 0x0101021b
    const val VERSION_NAME = 0x0101021c
    const val WINDOW_SOFT_INPUT_MODE = 0x0101022b
    const val TARGET_SDK_VERSION = 0x01010270
    const val EXTRACT_NATIVE_LIBS = 0x010104ea
    const val USES_CLEARTEXT_TRAFFIC = 0x010104ec
    const val APP_COMPONENT_FACTORY = 0x0101057a
    const val VERSION_CODE_MAJOR = 0x01010576
}

enum class LaunchMode(val code: Int) {
    STANDARD(0), SINGLE_TOP(1), SINGLE_TASK(2), SINGLE_INSTANCE(3), SINGLE_INSTANCE_PER_TASK(4);

    companion object {
        fun of(code: Int?): LaunchMode = entries.firstOrNull { it.code == code } ?: STANDARD
    }
}

enum class ComponentKind { ACTIVITY, ACTIVITY_ALIAS, SERVICE, PROVIDER, RECEIVER }

data class IntentFilterDecl(
    val actions: List<String>,
    val categories: List<String>,
    val schemes: List<String>,
    val hosts: List<String>,
    val mimeTypes: List<String>,
    val priority: Int,
) {
    val isLauncher: Boolean
        get() = "android.intent.action.MAIN" in actions && "android.intent.category.LAUNCHER" in categories
}

data class ComponentDecl(
    val kind: ComponentKind,
    val className: String,
    val exported: Boolean,
    val enabled: Boolean,
    /** Fully qualified process name, or null for the app's default process. */
    val process: String?,
    val permission: String?,
    val intentFilters: List<IntentFilterDecl>,
    val metaData: Map<String, String>,
    // Activity-only fields (defaults elsewhere).
    val launchMode: LaunchMode = LaunchMode.STANDARD,
    val themeRes: Int = 0,
    val taskAffinity: String? = null,
    val configChanges: Int = 0,
    val screenOrientation: Int = -1,
    val windowSoftInputMode: Int = 0,
    val targetActivity: String? = null,
    // Provider-only.
    val authorities: List<String> = emptyList(),
)

data class GuestManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val applicationClass: String?,
    val appComponentFactory: String?,
    val appThemeRes: Int,
    val labelRes: Int,
    val labelText: String?,
    val iconRes: Int,
    val hasCode: Boolean,
    val extractNativeLibs: Boolean,
    val usesCleartextTraffic: Boolean?,
    val sharedUserId: String?,
    val appProcess: String?,
    val usesPermissions: List<String>,
    val usesLibraries: List<String>,
    val metaData: Map<String, String>,
    val components: List<ComponentDecl>,
    /** Unknown/extra manifest-level flags kept for inspection (e.g. isSplitRequired). */
    val flags: Map<String, String>,
) {
    val activities: List<ComponentDecl> get() = components.filter { it.kind == ComponentKind.ACTIVITY }
    val services: List<ComponentDecl> get() = components.filter { it.kind == ComponentKind.SERVICE }
    val providers: List<ComponentDecl> get() = components.filter { it.kind == ComponentKind.PROVIDER }
    val receivers: List<ComponentDecl> get() = components.filter { it.kind == ComponentKind.RECEIVER }

    /** The activity the launcher would start (aliases resolve to their target). */
    val launcherActivity: String?
        get() {
            val c = components.firstOrNull { (it.kind == ComponentKind.ACTIVITY || it.kind == ComponentKind.ACTIVITY_ALIAS) && it.enabled && it.intentFilters.any { f -> f.isLauncher } }
                ?: return null
            return if (c.kind == ComponentKind.ACTIVITY_ALIAS) c.targetActivity else c.className
        }

    /** Process names used by any component (app default excluded). */
    val extraProcesses: Set<String>
        get() = components.mapNotNull { it.process }.filter { it != packageName && it != appProcess }.toSet()

    fun activity(className: String): ComponentDecl? = activities.firstOrNull { it.className == className }
}

object ManifestReader {
    fun read(axml: ByteArray): GuestManifest = read(AxmlReader.parse(axml))

    fun read(root: AxmlElement): GuestManifest {
        if (root.name != "manifest") throw AxmlFormatException("root is <${root.name}>, not <manifest>")
        val pkg = root.plain("package")?.asString()?.takeIf { it.isNotBlank() }
            ?: throw AxmlFormatException("manifest has no package")
        val vcMinor = root.attr(AndroidAttr.VERSION_CODE, "versionCode")?.asInt() ?: 0
        val vcMajor = root.attr(AndroidAttr.VERSION_CODE_MAJOR, "versionCodeMajor")?.asInt() ?: 0
        val versionCode = (vcMajor.toLong() shl 32) or (vcMinor.toLong() and 0xffffffffL)
        val versionName = root.attr(AndroidAttr.VERSION_NAME, "versionName")?.asString()

        val usesSdk = root.firstChild("uses-sdk")
        val minSdk = usesSdk?.attr(AndroidAttr.MIN_SDK_VERSION, "minSdkVersion")?.asInt() ?: 1
        val targetSdk = usesSdk?.attr(AndroidAttr.TARGET_SDK_VERSION, "targetSdkVersion")?.asInt() ?: minSdk

        val perms = root.childrenNamed("uses-permission").mapNotNull { it.attr(AndroidAttr.NAME, "name")?.asString() }
        val app = root.firstChild("application")
        val appProcess = app?.attr(AndroidAttr.PROCESS, "process")?.asString()?.let { processName(pkg, it) }

        val components = ArrayList<ComponentDecl>()
        if (app != null) {
            for (el in app.children) {
                val kind = when (el.name) {
                    "activity" -> ComponentKind.ACTIVITY
                    "activity-alias" -> ComponentKind.ACTIVITY_ALIAS
                    "service" -> ComponentKind.SERVICE
                    "provider" -> ComponentKind.PROVIDER
                    "receiver" -> ComponentKind.RECEIVER
                    else -> null
                } ?: continue
                component(pkg, appProcess, kind, el)?.let { components.add(it) }
            }
        }

        val flags = LinkedHashMap<String, String>()
        root.attrs.filter { it.name == "isSplitRequired" || it.name == "requiredSplitTypes" || it.name == "isFeatureSplit" || it.name == "split" }
            .forEach { flags[it.name] = it.raw ?: it.data.toString() }
        app?.attrs?.filter { it.name == "requestLegacyExternalStorage" || it.name == "largeHeap" || it.name == "debuggable" }
            ?.forEach { flags[it.name] = it.raw ?: (it.asBool()?.toString() ?: it.data.toString()) }

        return GuestManifest(
            packageName = pkg,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            applicationClass = app?.attr(AndroidAttr.NAME, "name")?.asString()?.let { className(pkg, it) },
            appComponentFactory = app?.attr(AndroidAttr.APP_COMPONENT_FACTORY, "appComponentFactory")?.asString()?.let { className(pkg, it) },
            appThemeRes = app?.attr(AndroidAttr.THEME, "theme")?.asRef() ?: 0,
            labelRes = app?.attr(AndroidAttr.LABEL, "label")?.asRef() ?: 0,
            labelText = app?.attr(AndroidAttr.LABEL, "label")?.takeIf { !it.isReference }?.asString(),
            iconRes = app?.attr(AndroidAttr.ICON, "icon")?.asRef() ?: 0,
            hasCode = app?.attr(AndroidAttr.HAS_CODE, "hasCode")?.asBool() ?: true,
            extractNativeLibs = app?.attr(AndroidAttr.EXTRACT_NATIVE_LIBS, "extractNativeLibs")?.asBool() ?: true,
            usesCleartextTraffic = app?.attr(AndroidAttr.USES_CLEARTEXT_TRAFFIC, "usesCleartextTraffic")?.asBool(),
            sharedUserId = root.attr(AndroidAttr.SHARED_USER_ID, "sharedUserId")?.asString(),
            appProcess = appProcess,
            usesPermissions = perms,
            usesLibraries = app?.childrenNamed("uses-library")?.mapNotNull { it.attr(AndroidAttr.NAME, "name")?.asString() } ?: emptyList(),
            metaData = metaData(app),
            components = components,
            flags = flags,
        )
    }

    private fun component(pkg: String, appProcess: String?, kind: ComponentKind, el: AxmlElement): ComponentDecl? {
        val rawName = el.attr(AndroidAttr.NAME, "name")?.asString() ?: return null
        val filters = el.childrenNamed("intent-filter").map { filter(it) }
        val exported = el.attr(AndroidAttr.EXPORTED, "exported")?.asBool()
            ?: (filters.isNotEmpty() && kind != ComponentKind.PROVIDER) // pre-API 31 default; providers default false since 17
        val process = el.attr(AndroidAttr.PROCESS, "process")?.asString()?.let { processName(pkg, it) } ?: appProcess
        val isActivity = kind == ComponentKind.ACTIVITY || kind == ComponentKind.ACTIVITY_ALIAS
        return ComponentDecl(
            kind = kind,
            className = className(pkg, rawName),
            exported = exported,
            enabled = el.attr(AndroidAttr.ENABLED, "enabled")?.asBool() ?: true,
            process = process,
            permission = el.attr(AndroidAttr.PERMISSION, "permission")?.asString(),
            intentFilters = filters,
            metaData = metaData(el),
            launchMode = if (isActivity) LaunchMode.of(el.attr(AndroidAttr.LAUNCH_MODE, "launchMode")?.asInt()) else LaunchMode.STANDARD,
            themeRes = el.attr(AndroidAttr.THEME, "theme")?.asRef() ?: 0,
            taskAffinity = el.attr(AndroidAttr.TASK_AFFINITY, "taskAffinity")?.asString(),
            configChanges = el.attr(AndroidAttr.CONFIG_CHANGES, "configChanges")?.asInt() ?: 0,
            screenOrientation = el.attr(AndroidAttr.SCREEN_ORIENTATION, "screenOrientation")?.asInt() ?: -1,
            windowSoftInputMode = el.attr(AndroidAttr.WINDOW_SOFT_INPUT_MODE, "windowSoftInputMode")?.asInt() ?: 0,
            targetActivity = el.attr(AndroidAttr.TARGET_ACTIVITY, "targetActivity")?.asString()?.let { className(pkg, it) },
            authorities = el.attr(AndroidAttr.AUTHORITIES, "authorities")?.asString()
                ?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        )
    }

    private fun filter(el: AxmlElement): IntentFilterDecl {
        fun names(tag: String) = el.childrenNamed(tag).mapNotNull { it.attr(AndroidAttr.NAME, "name")?.asString() }
        val data = el.childrenNamed("data")
        return IntentFilterDecl(
            actions = names("action"),
            categories = names("category"),
            schemes = data.mapNotNull { it.attr(AndroidAttr.SCHEME, "scheme")?.asString() },
            hosts = data.mapNotNull { it.attr(AndroidAttr.HOST, "host")?.asString() },
            mimeTypes = data.mapNotNull { it.attr(AndroidAttr.MIME_TYPE, "mimeType")?.asString() },
            priority = el.attr(AndroidAttr.PRIORITY, "priority")?.asInt() ?: 0,
        )
    }

    private fun metaData(el: AxmlElement?): Map<String, String> {
        if (el == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (m in el.childrenNamed("meta-data")) {
            val name = m.attr(AndroidAttr.NAME, "name")?.asString() ?: continue
            val v = m.attr(AndroidAttr.VALUE, "value") ?: m.attr(AndroidAttr.RESOURCE, "resource")
            out[name] = when {
                v == null -> ""
                v.raw != null -> v.raw
                v.isReference -> "@0x" + Integer.toHexString(v.data)
                v.type == AxmlTypes.TYPE_INT_BOOLEAN -> (v.data != 0).toString()
                else -> v.data.toString()
            }
        }
        return out
    }

    /** AOSP class-name rule: ".Foo" and "Foo" (no dot) are relative to the package. */
    fun className(pkg: String, name: String): String = when {
        name.startsWith(".") -> pkg + name
        !name.contains('.') -> "$pkg.$name"
        else -> name
    }

    /** AOSP process-name rule: ":foo" is private to the package; anything else is used as is. */
    fun processName(pkg: String, name: String): String = if (name.startsWith(":")) pkg + name else name
}
