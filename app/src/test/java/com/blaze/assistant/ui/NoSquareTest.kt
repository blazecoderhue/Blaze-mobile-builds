package com.blaze.assistant.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.24.8 "no square UI": fails the build when an app UI source uses a square shape. Comments are ignored; add a
 * file to [ALLOW] only when a square edge is truly needed (none are today).
 */
class NoSquareTest {
    private val square = listOf(
        Regex("""\bRectangleShape\b"""),
        Regex("""\bCutCornerShape\b"""),
        Regex("""RoundedCornerShape\(\s*(?:size\s*=\s*)?0+(?:\.0+)?\s*(?:\.dp|f|%)?\s*[,)]"""),
        Regex("""RoundedCornerShape\(\s*(?:percent\s*=\s*)?0\s*\)"""),
    )
    private val ALLOW = setOf<String>()

    private fun root(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    /** The code part of a line: drops // comments and lines inside block comments (KDoc rows start with *). */
    private fun code(line: String): String {
        val t = line.trim()
        if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) return ""
        val i = t.indexOf("//")
        return if (i >= 0 && !t.substring(0, i).contains("\"")) t.substring(0, i) else t
    }

    fun offenders(files: Sequence<File>): List<String> = files.filter { it.extension == "kt" && it.name !in ALLOW }.flatMap { f ->
        f.readLines().mapIndexedNotNull { n, l -> val c = code(l); if (square.any { it.containsMatchIn(c) }) "${f.name}:${n + 1}: ${l.trim()}" else null }
    }.toList()

    @Test fun noSquareShapesInAppUi() {
        val bad = offenders(root().walkTopDown())
        assertTrue("Square shapes found (round them with BlazeShapes):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun detectorCatchesSquares() {
        val tmp = File.createTempFile("square", ".kt").apply {
            writeText("val a = RectangleShape\nval b = RoundedCornerShape(0.dp)\nval c = RoundedCornerShape(0)\n// RectangleShape in a comment\nval ok = RoundedCornerShape(12.dp)\nval ok2 = RoundedCornerShape(10.dp, 0.dp, 4.dp, 4.dp)\n")
            deleteOnExit()
        }
        val bad = offenders(sequenceOf(tmp))
        assertTrue(bad.toString(), bad.size == 3)
    }
}
