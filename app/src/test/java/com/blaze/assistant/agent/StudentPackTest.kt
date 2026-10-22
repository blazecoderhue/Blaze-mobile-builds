package com.blaze.assistant.agent

import com.blaze.assistant.connect.Classroom
import com.blaze.assistant.routine.Routine
import com.blaze.assistant.routine.Routines
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class StudentPackTest {
    private val day = 86_400_000L
    private val now = 1_760_000_000_000L

    @Test fun spacedRepetitionGrowsAndResets() {
        var c = Card("c1", "CIT102", "range(3)?", "0, 1, 2")
        c = Flashcards.review(c, Flashcards.GOOD, now); assertEquals(1, c.interval); assertEquals(now + day, c.due)
        c = Flashcards.review(c, Flashcards.GOOD, now); assertEquals(6, c.interval)
        c = Flashcards.review(c, Flashcards.GOOD, now); assertEquals(15, c.interval)   // 6 × 2.5
        val easy = Flashcards.review(c, Flashcards.EASY, now)
        assertTrue(easy.interval > Flashcards.review(c, Flashcards.GOOD, now).interval)
        val again = Flashcards.review(c, Flashcards.AGAIN, now)
        assertEquals(0, again.reps); assertEquals(1, again.lapses); assertEquals(now + 600_000L, again.due)
        assertTrue(again.ease >= 1.3 && again.ease < c.ease)
        var hard = Card("c2", "d", "f", "b", ease = 1.3)
        repeat(5) { hard = Flashcards.review(hard, Flashcards.HARD, now) }
        assertEquals(1.3, hard.ease, 1e-9)
        assertEquals("10 min", Flashcards.label(c, Flashcards.AGAIN, now))
        assertEquals("1 mo", Flashcards.label(c, Flashcards.GOOD, now))
    }

    @Test fun decksQueueAndToolOnDisk() {
        Flashcards.fileOverride = File(Files.createTempDirectory("learn").toFile(), "cards.json")
        Flashcards.reset()
        val r = Flashcards.run(JSONObject().put("action", "add").put("deck", "CIT102 loops").put("cards", JSONArray()
            .put(JSONObject().put("front", "What does break do?").put("back", "Leaves the loop"))
            .put(JSONObject().put("front", "what does BREAK do?").put("back", "dup"))
            .put(JSONObject().put("front", "").put("back", "empty"))))
        assertTrue(r, r.startsWith("Added 1 card"))
        assertEquals(1, Flashcards.decks().single().fresh)
        val id = Flashcards.queue("CIT102 loops").single().id
        Flashcards.answer(id, Flashcards.GOOD)
        assertEquals(0, Flashcards.queue("CIT102 loops").size)
        Flashcards.reset()   // read back from disk
        assertEquals(1, Flashcards.load().single().reps)
        assertTrue(Flashcards.run(JSONObject().put("action", "list")).contains("CIT102 loops: 1 cards"))
        Flashcards.deleteDeck("CIT102 loops")
        assertTrue(Flashcards.decks().isEmpty())
    }

    @Test fun classroomDatesAreUtcAndRemindADayBefore() {
        val due = Classroom.dueMillis(JSONObject().put("year", 2026).put("month", 11).put("day", 16), JSONObject().put("hours", 7).put("minutes", 0))!!
        assertEquals(1_794_812_400_000L, due)   // 2026-11-16 07:00 UTC
        val noTime = Classroom.dueMillis(JSONObject().put("year", 2026).put("month", 11).put("day", 16), null)!!
        assertEquals(due + (16 * 60 + 59) * 60_000L, noTime)
        assertNull(Classroom.dueMillis(null, null))
        assertEquals(due - day, Classroom.reminderAt(due, due - 3 * day))
        assertEquals(due - 3_600_000L + 300_000L, Classroom.reminderAt(due, due - 3_600_000L))
        assertNull(Classroom.reminderAt(due, due + 1))
        val w = Classroom.parseWork(JSONObject().put("id", "w1").put("courseId", "k1").put("title", "Lab 3")
            .put("dueDate", JSONObject().put("year", 2026).put("month", 11).put("day", 16)).put("alternateLink", "https://classroom.google.com/x"), "CIT102")
        assertEquals("Due: Lab 3", Classroom.reminderName(w))
        assertTrue(Classroom.reminderPrompt(w).contains("CIT102"))
        assertTrue(Classroom.line(w.copy(state = "TURNED_IN"), now).contains("turned in"))
    }

    @Test fun classroomReadsCoursesWorkAndSubmissions() {
        val fake: (String) -> JSONObject = { u ->
            when {
                u.contains("/studentSubmissions") -> JSONObject().put("studentSubmissions", JSONArray().put(JSONObject().put("courseWorkId", "w2").put("state", "TURNED_IN")))
                u.contains("/courseWork") -> JSONObject().put("courseWork", JSONArray()
                    .put(JSONObject().put("id", "w1").put("title", "Soon").put("dueDate", JSONObject().put("year", 2025).put("month", 10).put("day", 10)))
                    .put(JSONObject().put("id", "w2").put("title", "Done one").put("dueDate", JSONObject().put("year", 2025).put("month", 10).put("day", 11)))
                    .put(JSONObject().put("id", "w3").put("title", "Far away").put("dueDate", JSONObject().put("year", 2026).put("month", 6).put("day", 1))))
                else -> JSONObject().put("courses", JSONArray().put(JSONObject().put("id", "k1").put("name", "Intro to Programming").put("section", "CIT102")))
            }
        }
        assertEquals("Intro to Programming (CIT102)", Classroom.courses(fake).single().second)
        val l = Classroom.work(fake, "", 21, false, now)   // now = 2025-10-09
        assertEquals(listOf("Soon", "Done one"), l.map { it.title })
        assertTrue(Classroom.done(l[1]))
    }

    @Test fun datedRoutinesFireOnTheirDate() {
        val r = Routine(1, "Due: Lab 3", "remind", 7, 0, 0, at = now + 3 * day)
        assertEquals(now + 3 * day, Routines.next(r, now))
        assertTrue(r.toJson().getLong("at") == now + 3 * day)
        val old = Routine(2, "Brief", "x", 7, 0, 127)
        assertEquals(0L, old.at)
    }

    @Test fun studentSkillsShipWithMatchingHashes() {
        val root = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }
        val idx = JSONObject(File(root, "index.json").readText()).getJSONArray("skills")
        val byName = (0 until idx.length()).map { idx.getJSONObject(it) }.associateBy { it.getString("name") }
        for (n in listOf("study-planner", "tutor", "lecture-notes", "essay-coach", "group-project", "classroom-sync")) {
            val md = File(root, "$n/SKILL.md").readText()
            assertTrue("$n front matter", md.startsWith("---\nname: $n\n"))
            val sha = MessageDigest.getInstance("SHA-256").digest(md.toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals("$n sha256", sha, byName.getValue(n).getString("sha256"))
        }
        assertTrue(File(root, "essay-coach/SKILL.md").readText().contains("does not write a full essay"))
    }

    @Test fun lowDataChip() {
        assertEquals("\u2193\u2191 0.42 MB", DataMeter.format(420_000))
        assertEquals("\u2193\u2191 12.3 MB", DataMeter.format(12_300_000))
    }

    @Test fun classroomToolsDeclared() {
        val names = Classroom.tools { n, d, p, r -> JSONObject().put("function", JSONObject().put("name", n).put("description", d)) }
            .map { it.getJSONObject("function").getString("name") }.toSet()
        assertEquals(Classroom.toolNames, names)
        val modes = File(listOf("src/main", "app/src/main").first { File(it).isDirectory }, "java/com/blaze/assistant/agent/Modes.kt").readText()
        listOf("classroom_courses", "classroom_coursework").forEach { assertTrue(it, modes.contains("\"$it\"")) }
    }
}
