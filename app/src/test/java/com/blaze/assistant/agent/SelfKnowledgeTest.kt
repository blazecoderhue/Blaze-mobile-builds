package com.blaze.assistant.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class SelfKnowledgeTest {
    @Test fun familyNamesEveryAppAndHowTheyConnect() {
        val f = SelfKnowledge.FAMILY
        listOf("Blaze for Android", "Blaze Desktop", "Blaze CLI", "Blaze account", "Blaze Link", "QR").forEach { assertTrue(it, f.contains(it)) }
    }
    @Test fun toolIsDescribed() {
        val fn = SelfKnowledge.tool().getJSONObject("function")
        assertTrue(fn.getString("name") == "about_blaze")
        assertTrue(fn.getString("description").contains("Blaze"))
    }
}
