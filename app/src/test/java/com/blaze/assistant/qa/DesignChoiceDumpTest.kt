package com.blaze.assistant.qa

import com.blaze.assistant.agent.Flashcards
import com.blaze.assistant.agent.ToolRouter
import com.blaze.assistant.agent.ToolTruth
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * QA helper for the design workflow suite (tools/design_replay.py): with BLAZE_DESIGN_ASKS=<in.json> set, writes the
 * tool_choice and follow-up map the app itself would send for every ask (ToolTruth.choiceFor / follow) to <in>.out.json,
 * plus extra_tools (group tools the app adds for the ask, e.g. flashcards for study asks), so the replay sends what the real app sends. Skipped otherwise.
 * In: {"tools":[names], "asks":[{"key":..., "user":..., "said":[earlier assistant texts]}]}.
 */
class DesignChoiceDumpTest {
    @Test fun dump() {
        val path = System.getenv("BLAZE_DESIGN_ASKS").orEmpty()
        assumeTrue(path.isNotBlank() && File(path).exists())
        val src = JSONObject(File(path).readText())
        val names = src.getJSONArray("tools").let { a -> (0 until a.length()).map { a.getString(it) } }
        val out = JSONObject()
        val asks = src.getJSONArray("asks")
        for (i in 0 until asks.length()) {
            val a = asks.getJSONObject(i)
            val said = a.optJSONArray("said") ?: JSONArray()
            val choice = ToolTruth.choiceFor(a.getString("user"), (0 until said.length()).map { said.getString(it) }, names)
            // Tools the app adds for this ask by group (ToolRouter.guess) that the fixed captured set lacks.
            val extra = JSONArray()
            if ("study" in ToolRouter.guess(a.getString("user")) && "flashcards" !in names) extra.put(Flashcards.tool())
            out.put(a.getString("key"), JSONObject().put("choice", choice ?: JSONObject.NULL)
                .put("follow", JSONObject(ToolTruth.follow(a.getString("user")) as Map<*, *>)).put("extra_tools", extra))
        }
        File("$path.out.json").writeText(out.toString(1))
    }
}
