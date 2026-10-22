package com.blaze.assistant.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.28.2: chats, memory and account / gateway tokens stay out of Google's cloud backup. */
class BackupRulesTest {
    private val res = listOf(File("src/main/res/xml"), File("app/src/main/res/xml")).first { it.isDirectory }
    private fun section(xml: String, tag: String) = Regex("<$tag>([\\s\\S]*?)</$tag>").find(xml)!!.groupValues[1]

    @Test fun cloudBackupKeepsChatsMemoryAndTokensOut() {
        val data = File(res, "data_rules.xml").readText()
        val cloud = section(data, "cloud-backup")
        val full = File(res, "backup_rules.xml").readText()
        for (rules in listOf(cloud, section(full, "full-backup-content"))) {
            assertFalse(rules.contains("chats/"))
            assertFalse(Regex("""<include domain="file"""").containsMatchIn(rules))
            assertFalse(Regex("""<include domain="database"""").containsMatchIn(rules))
            assertTrue(rules.contains("""<exclude domain="sharedpref" path="blaze.xml" />"""))
        }
        // Device transfer unchanged: everything but the Keystore-sealed vault.
        val transfer = section(data, "device-transfer")
        assertTrue(transfer.contains("""<include domain="file" path="." />"""))
        assertTrue(transfer.contains("""<exclude domain="file" path="vault.json" />"""))
    }
}
