package com.blaze.assistant.appstore

import java.io.File

/**
 * 0.45.19 Store: one install or update on the user's own phone through Android's installer, in a fixed order, with an
 * honest status at every step (user decision 2026-10-23 10:29, destination A):
 *
 *   ConfirmGate card with the disclaimer (before anything is downloaded) → "install unknown apps" permission →
 *   resolve the official release asset → download → SHA-256 check → APK identity (package + catalog signer) →
 *   installed-signer check for updates → VirusTotal hash lookup → Android's PackageInstaller session (the user confirms
 *   in Android's own dialog, every time) → the session's status → package manager check.
 *
 *  - No silent installs, no Shizuku, ADB or root. Blaze Phone's sandbox and the release page are not install targets.
 *  - A SHA-256 mismatch, a wrong package or a wrong signer stops before the scan and before any install; the file is
 *    deleted. An update whose signer differs from the installed app's signer stops the same way.
 *  - VirusTotal runs before every install. malicious > 0 blocks. No key / unknown file / lookup failed / suspicious =
 *    a plain warning and an explicit yes ([Env.confirmRisk]).
 *  - DONE only when Android's installer reported success and the package manager shows the new version.
 * Everything outside is behind [Env], so the order is unit tested (StoreInstallTest).
 */
enum class StoreStep(val label: String) {
    QUEUED("Waiting"), CONFIRM("Waiting for your OK"), PERMISSION("Waiting for the install permission"),
    RESOLVING("Finding the latest release"), DOWNLOADING("Downloading"), VERIFYING("Checking the file"),
    SCANNING("Checking with VirusTotal"), INSTALLING("Installing"), WAITING_USER("Waiting for you in Android's installer"),
    DONE("Installed"), FAILED("Failed"), BLOCKED("Blocked"), CANCELLED("Cancelled"), NEEDS_PERMISSION("Needs permission");

    val terminal: Boolean get() = this == DONE || this == FAILED || this == BLOCKED || this == CANCELLED || this == NEEDS_PERMISSION
}

data class ApkInfo(val pkg: String, val versionCode: Long, val versionName: String, val signers: Set<String>)
data class InstalledInfo(val versionCode: Long, val versionName: String, val signers: Set<String>)

sealed class VtVerdict {
    data class Clean(val harmless: Int, val undetected: Int) : VtVerdict()
    data class Suspicious(val suspicious: Int, val total: Int) : VtVerdict()
    data class Malicious(val malicious: Int, val total: Int) : VtVerdict()
    /** VirusTotal has never seen this file (404). */
    object Unknown : VtVerdict()
    object NoKey : VtVerdict()
    data class Error(val why: String) : VtVerdict()

    /** One line for the card / the agent. */
    fun line(): String = when (this) {
        is Clean -> "VirusTotal: no engine flagged it (${harmless + undetected} engines)."
        is Suspicious -> "VirusTotal: $suspicious of $total engines call it suspicious (none malicious)."
        is Malicious -> "VirusTotal: $malicious of $total engines flag it as malicious."
        Unknown -> "Not scanned: VirusTotal has never seen this exact file."
        NoKey -> "Not scanned: no VirusTotal key is set up on this phone."
        is Error -> "Not scanned: the VirusTotal lookup failed ($why)."
    }
}

/** Shown on every listing, card and details page (user policy, 2026-10-23). */
object StorePolicy {
    const val NOTICE = "Open-source, not made by Blazeinchue. Install at your own risk."
    const val FREE = "Free. Blaze charges nothing and earns nothing from these apps. Star the developers' repos."
    const val SOURCE_BUTTON = "Source repo"
    const val PERMISSION = "To install apps, allow Blaze to install unknown apps in Android's settings (once). You still confirm every install in Android's own dialog."
}

/** What Android's installer said about one session. */
sealed class InstallOutcome {
    /** STATUS_SUCCESS (still confirmed with the package manager after). */
    object Installed : InstallOutcome()
    /** STATUS_FAILURE_ABORTED: the user tapped Cancel in Android's dialog (or backed out of it). */
    object Cancelled : InstallOutcome()
    /** The dialog wasn't answered in time; the session was abandoned. */
    object TimedOut : InstallOutcome()
    /** STATUS_FAILURE_CONFLICT / INCOMPATIBLE: Android refused it next to what is installed (e.g. a different signer). */
    data class Conflict(val why: String) : InstallOutcome()
    data class Failed(val why: String) : InstallOutcome()
}

data class PipelineResult(val step: StoreStep, val message: String, val version: String = "", val verified: Boolean = false) {
    val ok: Boolean get() = step == StoreStep.DONE && verified
}

/** The pure signer rules (StoreInstallTest). Hex SHA-256 of signing certificates, lower case. */
object StoreSigners {
    /** Catalog pin: no pins = not checked here; else the APK must carry one of them. */
    fun matchesCatalog(pins: Set<String>, apk: Set<String>): Boolean = pins.isEmpty() || apk.any { it in pins }
    /** Update: the APK (current signer or its rotation history) must share a certificate with the installed app. */
    fun matchesInstalled(installed: Set<String>, apk: Set<String>): Boolean = installed.isNotEmpty() && apk.isNotEmpty() && installed.any { it in apk }
}

class InstallPipeline(private val env: Env) {
    interface Env {
        /** The ConfirmGate card (always = true): shown before any download. */
        suspend fun confirmInstall(title: String, detail: String): Boolean
        suspend fun confirmRisk(title: String, detail: String): Boolean
        /** PackageManager.canRequestPackageInstalls(). */
        fun canInstall(): Boolean
        /** Opens Settings › Install unknown apps for Blaze (ACTION_MANAGE_UNKNOWN_APP_SOURCES). */
        fun requestInstallPermission()
        /** True once [canInstall] turns true within [timeoutMs] (the user came back from Settings). */
        suspend fun awaitInstallPermission(timeoutMs: Long): Boolean
        fun resolve(app: StoreApp): ResolvedAsset
        fun download(app: StoreApp, asset: ResolvedAsset, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File
        fun sha256(file: File): String
        fun inspect(file: File): ApkInfo?
        fun installed(pkg: String): InstalledInfo?
        fun scan(sha256: String): VtVerdict
        /** Writes [apk] into a PackageInstaller session, commits it and waits for its final status. */
        suspend fun install(apk: File, info: ApkInfo, onUserAction: () -> Unit): InstallOutcome
        /** True once the package manager shows [pkg] at [versionCode] or newer (waits up to [timeoutMs]). */
        suspend fun awaitInstalled(pkg: String, versionCode: Long, timeoutMs: Long): Boolean
        fun delete(file: File)
        fun sdkInt(): Int = 34
    }

    /** What the job shows; called on every step change and download tick. */
    fun interface Report { fun on(step: StoreStep, message: String, done: Long, total: Long) }

    suspend fun run(app: StoreApp, update: Boolean, report: Report, cancelled: () -> Boolean = { false }): PipelineResult {
        fun stop(step: StoreStep, msg: String, v: String = ""): PipelineResult { report.on(step, msg, 0, 0); return PipelineResult(step, msg, v) }
        if (app.minSdk > 0 && env.sdkInt() < app.minSdk) return stop(StoreStep.BLOCKED, "${app.name} needs a newer Android (API ${app.minSdk}).")

        // 1. The disclaimer card, before anything is downloaded.
        report.on(StoreStep.CONFIRM, "Waiting for your OK", 0, 0)
        val verb = if (update) "Update" else "Install"
        val go = try {
            env.confirmInstall("$verb ${app.name}?", "${StorePolicy.NOTICE} License: ${app.license.ifBlank { "not stated" }}. " +
                "Blaze downloads the developer's official release from ${app.source.url}, checks its SHA-256 and signing key, " +
                "checks it with VirusTotal, then Android asks you to confirm.")
        } catch (e: Exception) { false }
        if (!go) return stop(StoreStep.CANCELLED, "Not installed: you said no.")

        // 2. Android's "install unknown apps" permission for Blaze (asked once; never worked around).
        if (!env.canInstall()) {
            report.on(StoreStep.PERMISSION, StorePolicy.PERMISSION, 0, 0)
            try { env.requestInstallPermission() } catch (_: Exception) { }
            if (!env.awaitInstallPermission(PERMISSION_WAIT_MS))
                return stop(StoreStep.NEEDS_PERMISSION, "Not installed: Blaze isn't allowed to install apps yet. ${StorePolicy.PERMISSION} Then tap Get again.")
        }
        if (cancelled()) return stop(StoreStep.CANCELLED, "Cancelled.")

        // 3. Resolve and download the official release file.
        report.on(StoreStep.RESOLVING, "Finding the latest ${app.name} release on ${app.source.url.removePrefix("https://")}", 0, 0)
        val asset = try { env.resolve(app) } catch (e: Exception) { return stop(StoreStep.FAILED, e.message ?: "Couldn't find a release for ${app.name}.") }
        val v = StoreVersions.display(asset.tag)
        if (cancelled()) return stop(StoreStep.CANCELLED, "Cancelled.", v)
        if (!StoreHosts.official(asset.url)) return stop(StoreStep.BLOCKED, "Blocked: that release file isn't on the developer's official GitHub, GitLab or F-Droid page. Nothing was downloaded.", v)

        report.on(StoreStep.DOWNLOADING, "Downloading ${app.name} $v", 0, asset.size)
        val dl = try { env.download(app, asset, { d, t -> report.on(StoreStep.DOWNLOADING, "Downloading ${app.name} $v", d, t) }, cancelled) }
        catch (e: Exception) {
            // Env.download deletes its own partial file.
            return if (cancelled()) stop(StoreStep.CANCELLED, "Cancelled.", v) else stop(StoreStep.FAILED, "The download didn't finish: ${e.message ?: "network error"}.", v)
        }

        // 4. Identity: hash, package, catalog signer, installed signer, version.
        report.on(StoreStep.VERIFYING, "Checking the file", 0, 0)
        val expected = app.knownSha(asset.tag, asset.name) ?: asset.hostSha256
        val actual = try { env.sha256(dl).lowercase() } catch (e: Exception) { env.delete(dl); return stop(StoreStep.FAILED, "Couldn't read the download.", v) }
        if (expected != null && actual != expected.lowercase()) {
            env.delete(dl)
            return stop(StoreStep.BLOCKED, "Blocked: the downloaded file's SHA-256 doesn't match the published checksum, so it may be damaged or tampered with. I deleted it and installed nothing.", v)
        }
        val info = try { env.inspect(dl) } catch (e: Exception) { null }
        if (info == null) { env.delete(dl); return stop(StoreStep.BLOCKED, "Blocked: that download isn't a valid Android app file. I deleted it.", v) }
        if (info.pkg != app.pkg) { env.delete(dl); return stop(StoreStep.BLOCKED, "Blocked: the file is a different app (${info.pkg}), not ${app.name} (${app.pkg}). I deleted it.", v) }
        if (!StoreSigners.matchesCatalog(app.signers, info.signers)) {
            env.delete(dl); return stop(StoreStep.BLOCKED, "Blocked: ${app.name} $v is not signed with the developer's known key. I deleted it and installed nothing.", v)
        }
        val have = try { env.installed(app.pkg) } catch (e: Exception) { null }
        if (have != null) {
            if (!StoreSigners.matchesInstalled(have.signers, info.signers)) {
                env.delete(dl)
                return stop(StoreStep.BLOCKED, "Blocked: the ${app.name} on this phone is signed with a different key than this release (a different build, e.g. from another store), so it can't be updated safely. I deleted the file. To switch, uninstall it in Android's settings first.", v)
            }
            if (info.versionCode <= have.versionCode) {
                env.delete(dl)
                return stop(StoreStep.FAILED, if (info.versionCode == have.versionCode) "${app.name} ${have.versionName} is already installed and up to date." else "The release (${info.versionName}) is older than the ${app.name} you have (${have.versionName}). Nothing changed.", v)
            }
        }
        val hashNote = when {
            expected == null -> "No published checksum for this release" + if (app.signers.isNotEmpty()) "; the developer's signing key matched." else "; no signing key is pinned in the catalog either."
            app.signers.isNotEmpty() -> "Checksum and developer key matched."
            else -> "Checksum matched (no signing key pinned in the catalog)."
        }
        val unverified = expected == null && app.signers.isEmpty() && have == null

        // 5. VirusTotal (hash only).
        if (cancelled()) { env.delete(dl); return stop(StoreStep.CANCELLED, "Cancelled.", v) }
        report.on(StoreStep.SCANNING, "Checking with VirusTotal", 0, 0)
        val vt = try { env.scan(actual) } catch (e: Exception) { VtVerdict.Error(e.message ?: "error") }
        if (vt is VtVerdict.Malicious) { env.delete(dl); return stop(StoreStep.BLOCKED, "Blocked: ${vt.line()} I deleted the file and installed nothing.", v) }
        if (vt !is VtVerdict.Clean || unverified) {
            report.on(StoreStep.CONFIRM, vt.line(), 0, 0)
            val title = when { vt is VtVerdict.Suspicious -> "Install ${app.name} anyway?"; vt !is VtVerdict.Clean -> "Install ${app.name} without a virus scan?"; else -> "Install ${app.name} without a checksum?" }
            val ok = try { env.confirmRisk(title, "${vt.line()} $hashNote ${StorePolicy.NOTICE} Source: ${app.source.url}") } catch (e: Exception) { false }
            if (!ok) { env.delete(dl); return stop(StoreStep.CANCELLED, "Not installed: you didn't OK it. ${vt.line()}", v) }
        }

        // 6. Android's installer. The user confirms in Android's own dialog.
        if (cancelled()) { env.delete(dl); return stop(StoreStep.CANCELLED, "Cancelled.", v) }
        report.on(StoreStep.INSTALLING, "Handing ${app.name} ${info.versionName} to Android's installer", 0, 0)
        val out = try {
            env.install(dl, info) { report.on(StoreStep.WAITING_USER, "Android is asking you to confirm: tap ${if (update) "Update" else "Install"} on its screen.", 0, 0) }
        } catch (e: Exception) { InstallOutcome.Failed(e.message ?: "error") }
        env.delete(dl)
        return when (out) {
            InstallOutcome.Installed ->
                if (env.awaitInstalled(app.pkg, info.versionCode, CONFIRM_MS)) done(report, app, info, vt, hashNote, update)
                else stop(StoreStep.FAILED, "Android's installer said it finished, but the package manager doesn't show ${app.name} ${info.versionName}. Not installed.", v)
            InstallOutcome.Cancelled -> stop(StoreStep.CANCELLED, "Not installed: you cancelled in Android's installer.", v)
            InstallOutcome.TimedOut -> stop(StoreStep.CANCELLED, "Not installed: Android's install screen wasn't answered, so I closed it. Tap Get to try again.", v)
            is InstallOutcome.Conflict -> stop(StoreStep.BLOCKED, "Blocked by Android: ${out.why}. Nothing changed.", v)
            is InstallOutcome.Failed -> stop(StoreStep.FAILED, "Android couldn't install ${app.name}: ${out.why}.", v)
        }
    }

    private fun done(report: Report, app: StoreApp, info: ApkInfo, vt: VtVerdict, hashNote: String, update: Boolean): PipelineResult {
        val msg = "${if (update) "Updated" else "Installed"} ${app.name} ${info.versionName} on this phone with Android's installer (checked with the package manager). $hashNote ${vt.line()} Uninstall it any time from Android's settings."
        report.on(StoreStep.DONE, msg, 0, 0)
        return PipelineResult(StoreStep.DONE, msg, info.versionName, verified = true)
    }

    companion object {
        const val CONFIRM_MS = 30_000L
        /** How long the job waits for the user to come back from the "install unknown apps" screen. */
        const val PERMISSION_WAIT_MS = 90_000L
        /** How long a session waits for the user's answer in Android's dialog before it is abandoned. */
        const val USER_WAIT_MS = 10 * 60_000L
    }
}
