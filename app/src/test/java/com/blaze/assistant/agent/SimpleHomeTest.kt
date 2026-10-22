package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleHomeTest {
    @Test fun greetingByHour() {
        assertEquals("Good morning, Sam", SimpleHome.greeting(8, "Sam Lee"))
        assertEquals("Good afternoon", SimpleHome.greeting(14))
        assertEquals("Good evening, Maya", SimpleHome.greeting(19, "Maya"))
        assertEquals("Still up, Maya?", SimpleHome.greeting(23, "Maya"))
        assertEquals("Still up?", SimpleHome.greeting(2))
    }

    @Test fun twoStartersThatFitTheHour() {
        listOf(3, 8, 14, 19, 23).forEach { assertEquals(2, SimpleHome.starters(it).size) }
        assertEquals("Plan my day", SimpleHome.starters(9)[0].label)
        assertEquals("Plan tomorrow", SimpleHome.starters(19)[0].label)
        assertEquals("Wind down", SimpleHome.starters(23)[0].label)
    }

    @Test fun replyChipsOnYesNoQuestions() {
        val c = SimpleHome.replyChips("Sam paid you back \$40 for dinner. Want to say thanks?").map { it.label }
        assertEquals(listOf("Yes", "No", "Later", "Do it for me"), c)
        assertEquals(listOf("Yes", "No", "Later"), SimpleHome.replyChips("All set. Are you happy with that?").map { it.label })
    }

    @Test fun noChipsForOpenQuestionsOrStatements() {
        assertTrue(SimpleHome.replyChips("What time works for you?").isEmpty())
        assertTrue(SimpleHome.replyChips("Done! It's in your calendar.").isEmpty())
        assertTrue(SimpleHome.replyChips("Coffee or tea?").isEmpty())
    }

    @Test fun lastQuestion() {
        assertEquals("Say thank you on WhatsApp?", SimpleHome.lastQuestion("Mutale sent you money. Say thank you on WhatsApp?"))
        assertEquals("", SimpleHome.lastQuestion("No question here."))
    }

    @Test fun talkToTags() {
        assertEquals("@halo hi there", SimpleHome.tagged("hi there", "halo"))
        assertEquals("@all plan a trip", SimpleHome.tagged("plan a trip", "all"))
        assertEquals("hi", SimpleHome.tagged("hi", "blaze"))
        assertEquals("/music", SimpleHome.tagged("/music", "halo"))
        assertEquals("hey @blaze do it", SimpleHome.tagged("hey @blaze do it", "halo"))
    }

    @Test fun draftsAndSees() {
        assertTrue(SimpleHome.isDraft("Send a text to Mary?", "Allow"))
        assertTrue(SimpleHome.isDraft("Reply to Jack on WhatsApp?", "Allow"))
        assertTrue(!SimpleHome.isDraft("Delete 3 photos?", "Delete"))
        assertEquals("Sees: WhatsApp", SimpleHome.seesLine("WhatsApp"))
        assertEquals("Sees: Finance team on WhatsApp", SimpleHome.seesLine("WhatsApp", "Finance team"))
        assertEquals("", SimpleHome.seesLine(""))
    }
}
