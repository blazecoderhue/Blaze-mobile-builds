package com.blaze.assistant.appstore

import android.content.pm.PackageInstaller
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.45.19 App Store install pipeline (destination A: Android's PackageInstaller on the user's phone). The six strict
 * scenarios each run 5 times on a fresh fake (5/5): fresh install, hash-mismatch abort, signer-mismatch abort,
 * permission denied, user cancel in Android's dialog, update of an installed app. Plus the order of steps, the
 * installed-signer rule, the official-host rule and the PackageInstaller status mapping.
 */
class StoreInstallTest {
    private val goodSha = "a".repeat(64)
    private val badSha = "b".repeat(64)
    private val devKey = "1".repeat(64)
    private val otherKey = "2".repeat(64)

    private fun app(signers: Set<String> = setOf(devKey), releases: Map<String, KnownRelease> = mapOf("v1.2.0" to KnownRelease(sha256 = goodSha))) = StoreApp(
        id = "demo", name = "Demo", pkg = "org.example.demo", summary = "A demo app", description = "", icon = "", category = "tools",
        website = "", license = "GPL-3.0", tags = emptyList(), source = StoreSourceRef("github", "example/demo"),
        asset = AssetRule(Regex("^demo-[0-9.]+\\.apk$")), signers = signers, releases = releases, minSdk = 0)

    /** A scriptable Android: every outside effect is recorded. */
    private inner class Fake(
        var confirmAnswer: Boolean = true,
        var riskAnswer: Boolean = true,
        var permission: Boolean = true,
        var grantsWhenAsked: Boolean = false,
        var fileSha: String = goodSha,
        var apk: ApkInfo? = ApkInfo("org.example.demo", 120, "1.2.0", setOf(devKey)),
        var have: InstalledInfo? = null,
        var vt: VtVerdict = VtVerdict.Clean(60, 10),
        var outcome: InstallOutcome = InstallOutcome.Installed,
        var assetUrl: String = "https://github.com/example/demo/releases/download/v1.2.0/demo-1.2.0.apk",
    ) : InstallPipeline.Env {
        val calls = mutableListOf<String>()
        val deleted = mutableListOf<File>()
        var file: File? = null
        var installedNow: Long = -1
        var askedForPermission = false
        override suspend fun confirmInstall(title: String, detail: String): Boolean { calls += "confirm:$title|$detail"; return confirmAnswer }
        override suspend fun confirmRisk(title: String, detail: String): Boolean { calls += "risk:$title"; return riskAnswer }
        override fun canInstall(): Boolean { calls += "canInstall"; return permission }
        override fun requestInstallPermission() { calls += "askPermission"; askedForPermission = true; if (grantsWhenAsked) permission = true }
        override suspend fun awaitInstallPermission(timeoutMs: Long): Boolean = permission
        override fun resolve(app: StoreApp): ResolvedAsset { calls += "resolve"; return ResolvedAsset("v1.2.0", "demo-1.2.0.apk", assetUrl, 1000, null) }
        override fun download(app: StoreApp, asset: ResolvedAsset, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File {
            calls += "download"; progress(1000, 1000)
            return File.createTempFile("store-test", ".apk").apply { writeText("apk"); file = this }
        }
        override fun sha256(file: File): String { calls += "sha"; return fileSha }
        override fun inspect(file: File): ApkInfo? { calls += "inspect"; return apk }
        override fun installed(pkg: String): InstalledInfo? = have
        override fun scan(sha256: String): VtVerdict { calls += "vt"; return vt }
        override suspend fun install(apk: File, info: ApkInfo, onUserAction: () -> Unit): InstallOutcome {
            calls += "install:${info.pkg}"
            assertTrue("the file handed to Android still exists", apk.exists())
            onUserAction()
            if (outcome == InstallOutcome.Installed) installedNow = info.versionCode
            return outcome
        }
        override suspend fun awaitInstalled(pkg: String, versionCode: Long, timeoutMs: Long): Boolean = installedNow >= versionCode
        override fun delete(file: File) { deleted += file; file.delete() }
        fun installs() = calls.count { it.startsWith("install:") }
    }

    private fun run(f: Fake, a: StoreApp = app(), update: Boolean = false): Pair<PipelineResult, List<StoreStep>> = runBlocking {
        val steps = mutableListOf<StoreStep>()
        InstallPipeline(f).run(a, update, { s, _, _, _ -> steps += s }) to steps
    }

    private fun fiveTimes(block: () -> Unit) = repeat(5) { block() }

    // ---- the strict six, 5/5 each ----

    @Test fun freshInstall() = fiveTimes {
        val f = Fake()
        val (r, steps) = run(f)
        assertTrue(r.message, r.ok)
        assertEquals(StoreStep.DONE, r.step)
        assertTrue(r.message, r.message.startsWith("Installed Demo 1.2.0"))
        assertEquals(1, f.installs())
        // The disclaimer card comes before anything is downloaded, and carries the notice.
        val confirm = f.calls.indexOfFirst { it.startsWith("confirm:") }
        assertTrue(confirm in 0 until f.calls.indexOf("download"))
        assertTrue(f.calls[confirm].contains(StorePolicy.NOTICE))
        // Order: confirm → permission → resolve → download → sha → inspect → VirusTotal → Android's installer.
        val order = listOf("canInstall", "resolve", "download", "sha", "inspect", "vt", "install:org.example.demo").map { f.calls.indexOf(it) }
        assertEquals(order.sorted(), order)
        assertTrue(StoreStep.WAITING_USER in steps)   // the user confirmed in Android's dialog
        assertTrue("the download is deleted after install", f.file!!.let { !it.exists() && it in f.deleted })
    }

    @Test fun hashMismatchAborts() = fiveTimes {
        val f = Fake(fileSha = badSha)
        val (r, _) = run(f)
        assertEquals(StoreStep.BLOCKED, r.step)
        assertTrue(r.message, r.message.contains("SHA-256"))
        assertEquals(0, f.installs())
        assertFalse("never scanned or inspected after a bad hash", "vt" in f.calls || "inspect" in f.calls)
        assertTrue("file deleted", f.file!! in f.deleted && !f.file!!.exists())
    }

    @Test fun hostDigestMismatchAbortsToo() {
        val f = object : InstallPipeline.Env by Fake(fileSha = badSha) {
            override fun resolve(app: StoreApp) = ResolvedAsset("v9", "demo-9.apk", "https://github.com/example/demo/releases/download/v9/demo-9.apk", 1, goodSha)
        }
        val r = runBlocking { InstallPipeline(f).run(app(releases = emptyMap()), false, { _, _, _, _ -> }) }
        assertEquals(StoreStep.BLOCKED, r.step)
    }

    @Test fun signerMismatchAborts() = fiveTimes {
        val f = Fake(apk = ApkInfo("org.example.demo", 120, "1.2.0", setOf(otherKey)))
        val (r, _) = run(f)
        assertEquals(StoreStep.BLOCKED, r.step)
        assertTrue(r.message, r.message.contains("not signed with the developer's known key"))
        assertEquals(0, f.installs())
        assertFalse("vt" in f.calls)
        assertTrue(f.file!! in f.deleted && !f.file!!.exists())
    }

    @Test fun permissionDenied() = fiveTimes {
        val f = Fake(permission = false)
        val (r, steps) = run(f)
        assertEquals(StoreStep.NEEDS_PERMISSION, r.step)
        assertTrue("Android's 'install unknown apps' screen was opened", f.askedForPermission)
        assertTrue(StoreStep.PERMISSION in steps)
        assertFalse("nothing downloaded without the permission", "download" in f.calls)
        assertEquals(0, f.installs())
        assertTrue(r.message, r.message.contains("install unknown apps"))
    }

    @Test fun permissionGrantedWhileWaitingContinues() {
        val f = Fake(permission = false, grantsWhenAsked = true)
        val (r, _) = run(f)
        assertTrue(r.message, r.ok)
    }

    @Test fun userCancelInAndroidDialog() = fiveTimes {
        val f = Fake(outcome = InstallOutcome.Cancelled)
        val (r, steps) = run(f)
        assertEquals(StoreStep.CANCELLED, r.step)
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("cancelled in Android's installer"))
        assertTrue(StoreStep.WAITING_USER in steps)
        assertFalse("never reported installed", StoreStep.DONE in steps)
        assertTrue(f.file!! in f.deleted && !f.file!!.exists())
    }

    @Test fun updateOfInstalledApp() = fiveTimes {
        val f = Fake(have = InstalledInfo(100, "1.1.0", setOf(devKey)))
        val (r, _) = run(f, update = true)
        assertTrue(r.message, r.ok)
        assertTrue(r.message, r.message.startsWith("Updated Demo 1.2.0"))
        assertTrue(f.calls.first { it.startsWith("confirm:") }.startsWith("confirm:Update Demo?"))
        assertEquals(1, f.installs())
    }

    // ---- more edges ----

    @Test fun updateWithDifferentInstalledSignerAborts() {
        val f = Fake(have = InstalledInfo(100, "1.1.0", setOf(otherKey)))
        val (r, _) = run(f, update = true)
        assertEquals(StoreStep.BLOCKED, r.step)
        assertTrue(r.message, r.message.contains("different key"))
        assertEquals(0, f.installs())
        assertTrue(f.file!! in f.deleted)
    }

    @Test fun updateWithUnpinnedCatalogStillNeedsInstalledSigner() {
        val f = Fake(have = InstalledInfo(100, "1.1.0", setOf(otherKey)))
        val (r, _) = run(f, a = app(signers = emptySet()), update = true)
        assertEquals(StoreStep.BLOCKED, r.step)
        assertEquals(0, f.installs())
    }

    @Test fun rotatedKeyWithHistoryIsAnUpdate() {
        val f = Fake(have = InstalledInfo(100, "1.1.0", setOf(devKey)), apk = ApkInfo("org.example.demo", 120, "1.2.0", setOf(devKey, otherKey)))
        assertTrue(run(f, update = true).first.ok)
    }

    @Test fun sameVersionIsNotReinstalled() {
        val f = Fake(have = InstalledInfo(120, "1.2.0", setOf(devKey)))
        val (r, _) = run(f, update = true)
        assertEquals(StoreStep.FAILED, r.step)
        assertTrue(r.message.contains("up to date"))
        assertEquals(0, f.installs())
    }

    @Test fun saidNoOnTheCardDownloadsNothing() {
        val f = Fake(confirmAnswer = false)
        val (r, _) = run(f)
        assertEquals(StoreStep.CANCELLED, r.step)
        assertFalse("download" in f.calls || "canInstall" in f.calls)
    }

    @Test fun wrongPackageAborts() {
        val f = Fake(apk = ApkInfo("org.evil.app", 120, "1.2.0", setOf(devKey)))
        assertEquals(StoreStep.BLOCKED, run(f).first.step)
        assertEquals(0, f.installs())
    }

    @Test fun maliciousBlocksAndUnknownAsks() {
        val bad = Fake(vt = VtVerdict.Malicious(3, 70))
        assertEquals(StoreStep.BLOCKED, run(bad).first.step); assertEquals(0, bad.installs())
        val noKey = Fake(vt = VtVerdict.NoKey, riskAnswer = false)
        assertEquals(StoreStep.CANCELLED, run(noKey).first.step); assertTrue(noKey.calls.any { it.startsWith("risk:") }); assertEquals(0, noKey.installs())
        val ok = Fake(vt = VtVerdict.Unknown, riskAnswer = true)
        assertTrue(run(ok).first.ok)
    }

    @Test fun noChecksumNoPinAsksFirst() {
        val f = Fake(riskAnswer = false)
        val (r, _) = run(f, a = app(signers = emptySet(), releases = emptyMap()))
        assertEquals(StoreStep.CANCELLED, r.step)
        assertTrue(f.calls.any { it.startsWith("risk:") })
    }

    @Test fun androidConflictAndTimeoutAreNotInstalled() {
        val c = Fake(outcome = InstallOutcome.Conflict("x"))
        assertEquals(StoreStep.BLOCKED, run(c).first.step)
        val t = Fake(outcome = InstallOutcome.TimedOut)
        assertEquals(StoreStep.CANCELLED, run(t).first.step)
        val s = object : InstallPipeline.Env by Fake() { override suspend fun awaitInstalled(pkg: String, versionCode: Long, timeoutMs: Long) = false }
        val r = runBlocking { InstallPipeline(s).run(app(), false, { _, _, _, _ -> }) }
        assertEquals("success without the package manager showing it is not DONE", StoreStep.FAILED, r.step)
    }

    @Test fun unofficialHostIsBlockedBeforeDownload() {
        val f = Fake(assetUrl = "https://evil.example.com/demo.apk")
        assertEquals(StoreStep.BLOCKED, run(f).first.step)
        assertFalse("download" in f.calls)
    }

    @Test fun officialHosts() {
        assertTrue(StoreHosts.official("https://github.com/a/b/releases/download/v1/x.apk"))
        assertTrue(StoreHosts.official("https://gitlab.com/a/b/-/releases/v1/downloads/x.apk"))
        assertTrue(StoreHosts.official("https://f-droid.org/repo/x.apk"))
        assertFalse(StoreHosts.official("http://github.com/a/b.apk"))
        assertFalse(StoreHosts.official("https://github.com.evil.io/x.apk"))
        assertFalse(StoreHosts.official("https://evilgithub.com/x.apk"))
        assertFalse(StoreHosts.official("https://user@github.com/x.apk"))
        assertFalse(StoreHosts.official("not a url"))
    }

    @Test fun signerRules() {
        assertTrue(StoreSigners.matchesCatalog(emptySet(), setOf(devKey)))
        assertTrue(StoreSigners.matchesCatalog(setOf(devKey), setOf(devKey, otherKey)))
        assertFalse(StoreSigners.matchesCatalog(setOf(devKey), setOf(otherKey)))
        assertFalse(StoreSigners.matchesInstalled(setOf(devKey), setOf(otherKey)))
        assertFalse(StoreSigners.matchesInstalled(emptySet(), setOf(devKey)))
        assertTrue(StoreSigners.matchesInstalled(setOf(devKey), setOf(otherKey, devKey)))
    }

    // ---- PackageInstaller status receiver ----

    @Test fun statusMapping() {
        assertEquals(InstallOutcome.Installed, StoreSessions.outcome(PackageInstaller.STATUS_SUCCESS, null))
        assertEquals(InstallOutcome.Cancelled, StoreSessions.outcome(PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertTrue(StoreSessions.outcome(PackageInstaller.STATUS_FAILURE_CONFLICT, null) is InstallOutcome.Conflict)
        assertTrue(StoreSessions.outcome(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, null) is InstallOutcome.Conflict)
        assertTrue(StoreSessions.outcome(PackageInstaller.STATUS_FAILURE_STORAGE, null) is InstallOutcome.Failed)
        assertEquals(InstallOutcome.Failed("boom"), StoreSessions.outcome(PackageInstaller.STATUS_FAILURE, "boom"))
    }

    @Test fun sessionsCompleteTheWaitingJobOnceAndForget() = runBlocking {
        var asked = 0
        val w = StoreSessions.open(4242, "org.example.demo") { asked++ }
        val waiting = async { w.await() }
        assertTrue(StoreSessions.userAction(4242, "org.example.demo", null))
        assertEquals(1, asked)
        StoreSessions.finish(4242, StoreSessions.outcome(PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertEquals(InstallOutcome.Cancelled, waiting.await())
        assertFalse("closed after the result", StoreSessions.isOpen(4242))
        // A late or duplicate broadcast is ignored and starts no screen.
        assertFalse(StoreSessions.userAction(4242, "org.example.demo", null))
        StoreSessions.finish(4242, InstallOutcome.Installed)
        assertNull(StoreSessions.pendingConfirm["org.example.demo"])
    }

    @Test fun closeWithoutResultLeavesNothingBehind() {
        StoreSessions.open(7, "p.q") { }
        StoreSessions.close(7)
        assertFalse(StoreSessions.isOpen(7))
        assertNull(StoreSessions.pendingConfirm["p.q"])
    }

    @Test fun virusTotalParse() {
        assertEquals(VtVerdict.Unknown, VirusTotal.parse(404, ""))
        assertEquals(VtVerdict.NoKey, VirusTotal.lookup(goodSha, null))
        val body = """{"data":{"attributes":{"last_analysis_stats":{"malicious":0,"suspicious":0,"harmless":10,"undetected":50}}}}"""
        assertEquals(VtVerdict.Clean(10, 50), VirusTotal.parse(200, body))
    }

    @Test fun sourceHasNoSilentOrSideChannelInstall() {
        val dir = File("src/main/java/com/blaze/assistant/appstore")
        val all = dir.listFiles()!!.filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertTrue(all.contains("USER_ACTION_REQUIRED"))
        for (bad in listOf("rikka.shizuku", "Shizuku.", "pm install", "Runtime.getRuntime", "su -c", "INSTALL_REPLACE_EXISTING", "USER_ACTION_NOT_REQUIRED", "BLAZE_PHONE", "SOURCE_PAGE"))
            assertFalse("store code must not contain $bad", all.contains(bad))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(manifest.contains(".appstore.StoreInstallReceiver\" android:exported=\"false\""))
    }
}
