package com.blaze.assistant.update

import com.blaze.assistant.agent.PrefCoerce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.45.8: the in-app update from 0.45.5 (versionCode 153) to 0.45.8 (156). The update code (Updater, VersionFeed,
 * ApkDownload) is unchanged since 0.45.5, so what 0.45.5 runs is what this checks: versions.json's android entry, the
 * download of android.url only, the SHA-256 check, the package / versionCode / signer check, PackageInstaller, and that
 * the stored data and the 0.45.5 PrefCoerce migration carry over.
 */
class UpdatePath0458Test {
    private val app = File(System.getProperty("user.dir")).let { if (File(it, "src/main").isDirectory) it else File(it, "app") }
    private fun src(p: String) = File(app, p).readText()

    private val feed = """{"schema":1,"android":{"version":"0.45.8","url":"https://blazeinchue.pages.dev/dl/android/v0.45.8",""" +
        """"sha256":"${"e".repeat(64)}","notes":"Blaze starts working the instant you tap Send"},"desktop":{"version":"0.19.10","platforms":{}},"cli":{"version":"v0.14.12"}}"""

    @Test fun feedFrom0455SeesTheUpdate() {
        val a = VersionFeed.parse(feed)
        assertNotNull(a)
        assertTrue(VersionFeed.newer(a!!.version, "0.45.5"))
        assertEquals("Blaze 0.45.8 is available", VersionFeed.banner(a, "0.45.5", ""))
        assertEquals(listOf("https://blazeinchue.pages.dev/dl/android/v0.45.8"), VersionFeed.sources(a))
        assertTrue(VersionFeed.hosts(a).none { "github" in it })
    }

    @Test fun versionCodeAndIdentity() {
        val g = src("build.gradle.kts")
        // v0.45.18 (163): the 0.45.5 → current update path still holds with a higher versionCode.
        val code = Regex("versionCode = (\\d+)").find(g)!!.groupValues[1].toInt()
        assertTrue(code >= 156)
        assertTrue(g.contains("versionCode = 164") && g.contains("versionName = \"0.45.19\""))
        assertTrue(g.contains("applicationId = \"com.blaze.assistant\""))
        assertTrue(156 > 153)
    }

    @Test fun installChecksAreThe0455Ones() {
        val u = src("src/main/java/com/blaze/assistant/update/Updater.kt")
        assertTrue(u.contains("VersionFeed.sources(entry)"))
        assertTrue(u.contains("entry.sha256"))
        assertTrue(u.contains("info.packageName != ctx.packageName"))
        assertTrue(u.contains("newCode <= oldCode"))
        assertTrue(u.contains("offered.containsAll(trusted)"))
        assertTrue(u.contains("PackageInstaller.SessionParams.MODE_FULL_INSTALL"))
        assertTrue(u.contains("ACTION_MANAGE_UNKNOWN_APP_SOURCES"))
        assertTrue(u.contains("STATUS_PENDING_USER_ACTION"))
        val m = src("src/main/AndroidManifest.xml")
        assertTrue(m.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(m.contains(".update.InstallReceiver"))
    }

    @Test fun prefsFrom0455Survive() {
        // A 0.45.5 phone already ran the migration: nothing to do, nothing removed.
        val done = mapOf(PrefCoerce.MIGRATED to true, "followups" to true, PrefCoerce.FOLLOWUP_ITEMS to "[]", "look_theme" to "desert", "reply_lang" to "fr")
        assertTrue(PrefCoerce.migrate(done).none { it is PrefCoerce.Op.Remove && it.key != PrefCoerce.OLD_SHARED })
        // A phone that never ran it (crashed before) still gets the fix on the first 0.45.8 start.
        val broken = mapOf("followups" to "[{\"id\":\"F1\"}]", "look_theme" to "desert", "haptics" to "true")
        val ops = PrefCoerce.migrate(broken)
        assertTrue(ops.contains(PrefCoerce.Op.PutStr(PrefCoerce.FOLLOWUP_ITEMS, "[{\"id\":\"F1\"}]")))
        assertTrue(ops.contains(PrefCoerce.Op.Remove(PrefCoerce.OLD_SHARED)))
        assertTrue(ops.contains(PrefCoerce.Op.PutBool("haptics", true)))
        assertTrue(ops.none { (it as? PrefCoerce.Op.Remove)?.key == "look_theme" })
        // The startup hook still runs it once.
        val s = src("src/main/java/com/blaze/assistant/agent/Settings.kt")
        assertTrue(s.contains("PrefCoerce.migrate(prefs.all)"))
        assertTrue(s.contains("PrefCoerce.MIGRATED"))
    }
}
