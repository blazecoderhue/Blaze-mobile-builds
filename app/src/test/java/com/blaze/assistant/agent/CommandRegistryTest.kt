package com.blaze.assistant.agent

import com.blaze.assistant.ui.Views
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRegistryTest {
    @Test fun everyCommandHasAKind() {
        Commands.all().forEach { assertNotEquals("/${it.name} has no kind", CommandRegistry.Kind.UNKNOWN, CommandRegistry.kind(it.name)) }
    }

    @Test fun kinds() {
        assertEquals(CommandRegistry.Kind.VIEW, CommandRegistry.kind("music"))
        assertEquals(CommandRegistry.Kind.VIEW, CommandRegistry.kind("/doing"))
        assertEquals(CommandRegistry.Kind.VIEW, CommandRegistry.kind("home"))
        assertEquals(CommandRegistry.Kind.PROMPT, CommandRegistry.kind("translate"))
        assertEquals(CommandRegistry.Kind.ACTION, CommandRegistry.kind("autopilot"))
        assertEquals(CommandRegistry.Kind.USER_ONLY, CommandRegistry.kind("stop"))
        assertEquals(CommandRegistry.Kind.UNKNOWN, CommandRegistry.kind("nope"))
    }

    @Test fun catalogListsEveryCommand() {
        val cat = CommandRegistry.catalog()
        Commands.all().forEach { assertTrue("/${it.name} missing", cat.contains("- /${it.name}")) }
    }

    @Test fun awarenessReportKnowsEveryViewAndCommand() {
        Awareness.reset()
        val r = Awareness.report(Views.all.map { Triple(it.name, it.id, it.desc) }, commands = CommandRegistry.catalog())
        Views.all.forEach { assertTrue("view ${it.id}", r.contains("(/${it.id})")) }
        assertTrue(r.contains("- /translate")); assertFalse(r.contains("desktop_task")); assertTrue(r.contains("open_view view=home"))
    }

    @Test fun openViewToolCoversEveryView() {
        val enum = Views.tool().getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").getJSONObject("view").getJSONArray("enum")
        val ids = (0 until enum.length()).map { enum.getString(it) }.toSet()
        Views.all.forEach { assertTrue(it.id, it.id in ids) }
        assertTrue("home" in ids && "doing" in ids && "look" in ids)
    }
}
