package com.blaze.kiln.diag

// On-device diagnostics for the Kiln M1 test harness (ENGINE_M1_PHONE_TEST.md). Pure JVM: the main process and the
// guest process (:g0) both append one line per event to files/kiln/diag.log; the harness parses it and says which step
// failed and which system service refused the guest. Sources: own design; Throwable/StackTraceElement are java.lang;
// the "I<Name>$Stub$Proxy" class shape of AIDL client proxies is from AOSP (Apache-2.0) generated code.

/** The launch pipeline in the order a tester sees it. */
enum class HarnessStep(val label: String, val inGuestProcess: Boolean) {
    READ_APK("Read APK (manifest, verdict)", false),
    INSTALL("Install into Kiln (read-only copy + record)", false),
    NATIVE_LIBS("Native libraries", false),
    PREPARE_LAUNCH("Prepare launch (slot + token)", false),
    START_SLOT("Ask Android to start the slot", false),
    GUEST_HOOKS("Guest process: install hooks", true),
    GUEST_LOADED("Guest process: load guest code + resources", true),
    GUEST_SWAP("Guest process: swap slot for guest activity", true),
    GUEST_ACTIVITY("Guest activity ran onCreate", true),
    GUEST_STORAGE("Guest files under Kiln storage", true),
}

enum class DiagKind { STEP, REFUSAL, CRASH, INFO }

data class DiagEntry(
    val atMillis: Long,
    val process: String,
    val kind: DiagKind,
    val key: String,
    /** null = not a pass/fail event. */
    val ok: Boolean?,
    val detail: String,
)

object DiagLog {
    const val MAX_FIELD = 4000

    fun format(e: DiagEntry): String = listOf(
        e.atMillis.toString(), esc(e.process), e.kind.name, esc(e.key),
        when (e.ok) { true -> "1"; false -> "0"; null -> "-" }, esc(e.detail.take(MAX_FIELD)),
    ).joinToString("\t")

    /** Null for a line that is not ours (torn write, older format): the reader skips it instead of failing. */
    fun parse(line: String): DiagEntry? {
        val f = line.split('\t')
        if (f.size != 6) return null
        val at = f[0].toLongOrNull() ?: return null
        val kind = DiagKind.entries.firstOrNull { it.name == f[2] } ?: return null
        val ok = when (f[4]) { "1" -> true; "0" -> false; "-" -> null; else -> return null }
        return DiagEntry(at, unesc(f[1]), kind, unesc(f[3]), ok, unesc(f[5]))
    }

    fun parseAll(text: String): List<DiagEntry> = text.lineSequence().mapNotNull { if (it.isBlank()) null else parse(it) }.toList()

    fun esc(s: String): String {
        val b = StringBuilder(s.length)
        for (c in s) when (c) {
            '\\' -> b.append("\\\\"); '\t' -> b.append("\\t"); '\n' -> b.append("\\n"); '\r' -> b.append("\\r")
            else -> b.append(c)
        }
        return b.toString()
    }

    fun unesc(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> b.append('\\'); 't' -> b.append('\t'); 'n' -> b.append('\n'); 'r' -> b.append('\r')
                    else -> { b.append(c); b.append(s[i + 1]) }
                }
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }
}

/** Which system service refused the guest, read from an exception's stack (no Android types needed). */
data class Refusal(
    val exception: String,
    val message: String,
    /** AIDL interface, e.g. "INotificationManager"; null when no binder proxy frame was found. */
    val aidlInterface: String?,
    val method: String?,
    /** ServiceManager name, e.g. "notification"; null when unknown. */
    val serviceName: String?,
    /** True when Kiln shims this service in M1 (so the refusal got past our identity rewrite). */
    val hooked: Boolean,
    val security: Boolean,
) {
    fun describe(): String {
        val where = when {
            aidlInterface != null && method != null -> "$aidlInterface.$method" + (serviceName?.let { " (service \"$it\")" } ?: "")
            method != null -> method
            else -> "unknown call"
        }
        val tag = if (hooked) "hooked by Kiln" else "NOT hooked in M1"
        return "$exception from $where, $tag: $message"
    }
}

object RefusalClassifier {
    /** Services Kiln wraps in the guest process in M1 (ENGINE_DESIGN.md 1.4); keep in sync with BlazeKiln. */
    val HOOKED_SERVICES: Set<String> = linkedSetOf(
        "activity", "activity_task", "package",
        "input_method", "clipboard", "appops", "notification", "audio", "vibrator", "power", "user", "permissionmgr",
    )

    /** Service names the guest process asks ServiceManager for, wrapped through the binder cache (subset of above). */
    val CACHE_SHIMMED: List<String> = listOf(
        "input_method", "clipboard", "appops", "notification", "audio", "vibrator", "power", "user", "permissionmgr",
    )

    /** AIDL interface simple name -> ServiceManager name (names from AOSP's Context.*_SERVICE constants). */
    val SERVICE_OF: Map<String, String> = mapOf(
        "IActivityManager" to "activity", "IActivityTaskManager" to "activity_task", "IPackageManager" to "package",
        "IInputMethodManager" to "input_method", "IClipboard" to "clipboard", "IAppOpsService" to "appops",
        "INotificationManager" to "notification", "IAudioService" to "audio", "IVibratorService" to "vibrator",
        "IVibratorManagerService" to "vibrator_manager", "IPowerManager" to "power", "IUserManager" to "user",
        "IPermissionManager" to "permissionmgr", "IWindowManager" to "window", "IWindowSession" to "window",
        "IContentService" to "content", "IContentProvider" to "content provider", "ILocationManager" to "location",
        "IAlarmManager" to "alarm", "IJobScheduler" to "jobscheduler", "IConnectivityManager" to "connectivity",
        "IStorageManager" to "mount", "IAccountManager" to "account", "ITelephony" to "phone",
        "IDisplayManager" to "display", "IAccessibilityManager" to "accessibility", "IAutoFillManager" to "autofill",
        "ITextServicesManager" to "textservices", "IUiModeManager" to "uimode", "IWallpaperManager" to "wallpaper",
    )

    private val PROXY = Regex("""^(?:.*\.)?(I[A-Za-z0-9_]+)\$Stub\$Proxy$""")
    private val MANAGER = Regex("""^android\.[a-z.]+\.([A-Z][A-Za-z0-9]*(?:Manager|Resolver))$""")

    /** One stack frame as (className, methodName). */
    data class Frame(val className: String, val method: String)

    /**
     * [chain] is the exception and its causes, outermost first: (class name, message, frames).
     * The innermost SecurityException wins; otherwise the outermost exception is described.
     */
    fun classify(chain: List<Triple<String, String?, List<Frame>>>, hooked: Set<String> = HOOKED_SERVICES): Refusal? {
        if (chain.isEmpty()) return null
        val pick = chain.lastOrNull { it.first == "java.lang.SecurityException" } ?: chain.first()
        val security = pick.first == "java.lang.SecurityException"
        // Binder proxy frames usually sit on the deepest exception; search the picked one, then all of them.
        val frames = pick.third + chain.flatMap { it.third }
        var iface: String? = null
        var method: String? = null
        for (f in frames) {
            val m = PROXY.matchEntire(f.className) ?: continue
            iface = m.groupValues[1]; method = f.method; break
        }
        if (iface == null) {
            for (f in frames) {
                val m = MANAGER.matchEntire(f.className) ?: continue
                method = m.groupValues[1] + "." + f.method; break
            }
        }
        val service = iface?.let { SERVICE_OF[it] }
        return Refusal(
            exception = pick.first.substringAfterLast('.'),
            message = (pick.second ?: "").take(500),
            aidlInterface = iface,
            method = method,
            serviceName = service,
            hooked = service != null && service in hooked,
            security = security,
        )
    }

    /** Same, straight from a Throwable (JVM tests and the device share this path). */
    fun classify(t: Throwable, hooked: Set<String> = HOOKED_SERVICES): Refusal? {
        val chain = ArrayList<Triple<String, String?, List<Frame>>>()
        var c: Throwable? = t
        val seen = HashSet<Throwable>()
        while (c != null && seen.add(c) && chain.size < 12) {
            chain += Triple(c.javaClass.name, c.message, c.stackTrace.map { Frame(it.className, it.methodName) })
            c = c.cause
        }
        return classify(chain, hooked)
    }
}

/** A step result recorded by the harness in the main process. */
data class StepResult(val step: HarnessStep, val ok: Boolean?, val detail: String)

/** What the harness shows: per-step state, the first failure, refusals and crashes since the session started. */
data class HarnessReport(
    val steps: List<StepResult>,
    val firstFailure: HarnessStep?,
    val refusals: List<String>,
    val crashes: List<String>,
    val verdict: String,
) {
    fun lines(): List<String> {
        val out = ArrayList<String>()
        out += verdict
        for (s in steps) {
            val mark = when (s.ok) { true -> "OK  "; false -> "FAIL"; null -> " .. " }
            out += "$mark ${s.step.ordinal + 1}. ${s.step.label}" + if (s.detail.isNotEmpty()) " - ${s.detail}" else ""
        }
        if (refusals.isNotEmpty()) { out += "System services that refused:"; refusals.forEach { out += "  - $it" } }
        if (crashes.isNotEmpty()) { out += "Guest process crashes:"; crashes.forEach { out += "  - $it" } }
        return out
    }

    companion object {
        /**
         * Builds the report from what the main process recorded ([local]) plus the shared diag log ([entries]),
         * keeping only events at or after [sessionStart]. The newest event per step wins.
         */
        fun build(sessionStart: Long, local: List<StepResult>, entries: List<DiagEntry>): HarnessReport {
            val fresh = entries.filter { it.atMillis >= sessionStart }
            val byStep = LinkedHashMap<HarnessStep, StepResult>()
            for (r in local) byStep[r.step] = r
            for (e in fresh.filter { it.kind == DiagKind.STEP }.sortedBy { it.atMillis }) {
                val step = HarnessStep.entries.firstOrNull { it.name == e.key } ?: continue
                byStep[step] = StepResult(step, e.ok, e.detail)
            }
            val steps = HarnessStep.entries.map { byStep[it] ?: StepResult(it, null, "") }
            val refusals = fresh.filter { it.kind == DiagKind.REFUSAL }.map { "${it.key}: ${it.detail}" }.distinct()
            val crashes = fresh.filter { it.kind == DiagKind.CRASH }.map { "[${it.process}] ${it.detail}" }.distinct()
            var first = steps.firstOrNull { it.ok == false }?.step
            // A later step that ran proves the earlier ones did, even if their own line was lost; a gap after the last
            // passing step with a crash recorded is reported as that next step failing.
            val lastOk = steps.indexOfLast { it.ok == true }
            if (first == null && crashes.isNotEmpty() && lastOk >= 0 && lastOk + 1 < steps.size) first = steps[lastOk + 1].step
            val reached = steps.count { it.ok == true }
            val verdict = when {
                first != null -> "FAILED at step ${first.ordinal + 1}: ${first.label}"
                reached == steps.size -> "PASS: all ${steps.size} steps OK"
                reached == 0 -> "Not started"
                else -> "In progress: ${reached}/${steps.size} steps OK (tap Refresh after using the guest)"
            }
            return HarnessReport(steps, first, refusals, crashes, verdict)
        }

        /** The guest's own files must live under Kiln's per-guest data dir (A6). */
        fun storageOk(expectedDataDir: String, reportedFilesDir: String?): Boolean {
            if (reportedFilesDir == null) return false
            val exp = expectedDataDir.trimEnd('/')
            val got = reportedFilesDir.trimEnd('/')
            if (got.contains("/../") || got.endsWith("/..")) return false
            return got == "$exp/files"
        }

        /** Parses the guest's start file (key=value lines). */
        fun parseKeyValues(text: String): Map<String, String> =
            text.lineSequence().mapNotNull { l -> val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1) }.toMap()
    }
}
