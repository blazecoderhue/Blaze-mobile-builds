package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.45.1 speed benchmark: how much of each model request a provider's prefix cache can reuse, before (v0.45.0: parts
 * in written order, live data rebuilt every step, tools in canonical order) and after (PromptParts tiers, the live tail
 * kept per turn, ToolPick.keepOrder).
 *
 * The system prompt is rebuilt here from the real parts: the shipped constants and core skills, and every literal
 * paragraph read out of Agent.kt's systemPrompt source (so the sizes follow the code). Only the live data (clock,
 * memory, phone line, awareness, skills lookup) and the history are synthetic, sized like a real phone task.
 * Results go to app/build/prompt-cache-bench.txt.
 */
class PromptCacheBenchTest {
    private val agentSrc = File("src/main/java/com/blaze/assistant/agent/Agent.kt").readText()
    private val sysSrc = agentSrc.substring(agentSrc.indexOf("private fun systemPrompt("), agentSrc.indexOf("private suspend fun tools(): JSONArray"))

    /** The string literals of the call `<fn>("<name>", …)` in systemPrompt, joined (templates left as written). */
    private fun lit(fn: String, name: String): String {
        val start = sysSrc.indexOf("$fn(\"$name\", ").also { require(it >= 0) { "$fn $name not in Agent.systemPrompt" } } + fn.length + name.length + 5
        val out = StringBuilder(); var i = start; var depth = 1
        while (i < sysSrc.length && depth > 0) {
            when {
                sysSrc.startsWith("\"\"\"", i) -> { val e = sysSrc.indexOf("\"\"\"", i + 3); out.append(sysSrc, i + 3, e); i = e + 3 }
                sysSrc[i] == '"' -> { var j = i + 1; while (sysSrc[j] != '"') { if (sysSrc[j] == '\\') j++; j++ }; out.append(sysSrc, i + 1, j); i = j + 1 }
                sysSrc[i] == '(' -> { depth++; i++ }
                sysSrc[i] == ')' -> { depth--; i++ }
                else -> i++
            }
        }
        return out.toString().replace("\\n", "\n").replace("\\\"", "\"")
            .replace("\${Orient.RULE}", Orient.RULE).replace("\${FastActions.RULE.trimStart('\\n')}", FastActions.RULE.trimStart('\n'))
            .replace("\${PromptProfile.filesLine(prof.simple)}", PromptProfile.filesLine(false))
    }

    private fun core(names: List<String>): String {
        CoreSkills.reset()
        CoreSkills.loader = { CoreSkills.NAMES.associateWith { File("src/main/assets/skills/$it/SKILL.md").readText() } }
        return CoreSkills.promptBlock(names).also { CoreSkills.reset(); CoreSkills.loader = null }
    }

    private fun filler(tag: String, n: Int) = buildString { var k = 0; while (length < n) append("$tag-${k++} fact about the user and their phone. ") }.take(n)

    /** Live data of one model call ([t] = minutes into the turn, [screen] = what the phone shows now). */
    private class Live(val t: Int, val screen: Int, val turn: Int)

    /** The parts in Agent.systemPrompt's written order and tiers (checked against the source below). */
    private fun parts(userText: String, groups: Set<String>, viewRouted: Boolean, live: Live): List<PromptParts.Part> {
        val prof = PromptProfile.plan(false, userText, groups, viewRouted = viewRouted)
        return PromptParts.build {
            s("persona", Personas.byId("blaze").prompt + " ")
            s("name", "The user's name is Lackson. ")
            s("names", GreetName.RULE)   // v0.45.9
            s("lang", ReplyLanguage.line("auto"))   // v0.45.7
            slow("connectors", "\nConnected accounts you can use with tools: Gmail (mail), Google Calendar (events), Spotify (music).\n")
            s("talk", lit("s", "talk")); s("style", AnswerStyle.RULE); s("format", AnswerStyle.FORMAT)
            s("self", lit("s", "self")); s("remember", lit("s", "remember")); s("again", lit("s", "again"))
            s("rapid", RapidMessages.PROMPT_RULE); s("ask", lit("s", "ask"))
            s("control", lit("s", "control").trimIndent())
            s("pim", lit("s", "pim")); s("followups", lit("s", "followups")); s("load_tools", lit("s", "load_tools")); s("weather", GatewayWeather.SYSTEM_LINE)
            s("leak", "\n" + ToolLeak.PROMPT_RULE); s("verify", lit("s", "verify")); s("docs", BlazeDocs.PROMPT_LINE); s("ids", lit("s", "ids"))
            turn("core_skills", core(prof.core))
            turn("views", if (prof.views) ViewRouter.PROMPT else PromptProfile.viewsShort(ViewRouter.specs.map { it.name }))
            s("tool_verify", ToolVerify.PROMPT_RULE)
            s("rich", com.blaze.assistant.ui.RichReplies.PROMPT)
            live("time", "\n\nCURRENT LOCAL TIME: Tuesday 20 October 2026, 08:${10 + live.t} (Africa/Lusaka). This is exact: answer any time, date or day question straight from it, never say you can't see the time. ")
            live("memory", "\nWhat you remember about the user (use quietly, don't recite):\n" + filler("m", 1400) + "\n")
            s("learn", Learn.rules(true))
            live("device", "\nPhone: an Android phone, Android 12, One UI 4.1.\nPhone now: battery ${81 - live.t}%, Wi-Fi, ringer normal.")
            live("awareness", "\nAWARENESS: front app ${if (live.screen % 2 == 0) "WhatsApp" else "Blaze"}; screen step ${live.screen}; " + filler("aw${live.screen}", 700))
            s("phone_state", PhoneState.RULE); s("know", lit("s", "know"))
            turn("make", if (prof.terminal) lit("turn", "terminal") + lit("turn", "make") else PromptProfile.MAKE_SHORT + PromptProfile.TERMINAL_SHORT)
            slow("about", "\nABOUT YOU: Blaze family. You are Blaze for Android v0.45.1 (build 149). Paired PC: none. Blaze account: signed in. " + filler("about", 300))
            s("calls", lit("s", "calls"))
            s("autonomy", Autonomy.promptBlock())
            turn("skills", "\nSKILLS that may fit this request: " + filler("skill${live.turn}", 600))
            s("assistant", Duo.ASSISTANT_RULES); s("context_rule", TurnContext.RULE); s("phone_save", SaveFile.RULE + ToolTruth.RULE)   // v0.45.9
        }
    }

    private class Turn(val name: String, val text: String, val groups: Set<String>, val views: Boolean)
    private val turns = listOf(
        Turn("phone task", "open whatsapp and text mum I'm running late", setOf("screen", "device", "comms"), false),
        Turn("coding", "write a python script that renames my photos by date", setOf("terminal"), false),
        Turn("view", "show me a chart of my spending this month", setOf("make"), true),
    )

    @Test fun everyPartInTheSourceIsBenchmarked() {
        val inSrc = Regex("""\b(s|slow|turn|tail)\("([a-z_]+)"""").findAll(sysSrc).map { it.groupValues[2] }.toSet()
        val here = parts(turns[0].text, turns[0].groups, false, Live(0, 0, 0)).map { it.name }.toSet() +
            setOf("simple", "terminal", "processes", "in_call", "goals", "setup", "frequent", "desktop", "project")   // empty / off in this phone's state
        assertEquals(emptySet<String>(), inSrc - here)
    }

    private fun req(tools: JSONArray, system: String, history: List<JSONObject>): String =
        tools.toString() + JSONArray().apply { put(JSONObject().put("role", "system").put("content", system)); history.forEach { put(it) } }.toString()

    @Test fun measure() {
        val sb = StringBuilder("Prompt cache benchmark (chars; tokens ≈ chars/4). Before = v0.45.0 order + live data every step; after = v0.45.1.\n\n")
        // 1. System prompt size by tier and biggest parts.
        val p0 = parts(turns[0].text, turns[0].groups, false, Live(0, 0, 0))
        sb.append("System prompt (phone task): ").append(PromptParts.line(p0)).append("\nBiggest parts: ").append(PromptParts.rank(p0, 8)).append("\n\n")

        // 2. Across turns of different kinds: the system prompt's common prefix (what a new turn can reuse).
        sb.append("Across turns (system prompt common prefix):\n")
        var b2 = 0; var a2 = 0
        for (i in turns.indices) for (j in turns.indices) if (i < j) {
            val x = parts(turns[i].text, turns[i].groups, turns[i].views, Live(0, 0, i)); val y = parts(turns[j].text, turns[j].groups, turns[j].views, Live(3, 1, j))
            val before = PromptParts.commonPrefix(PromptParts.legacy(x), PromptParts.legacy(y))
            val after = PromptParts.commonPrefix(PromptParts.assemble(x), PromptParts.assemble(y))
            b2 += before; a2 += after
            sb.append("  ${turns[i].name} → ${turns[j].name}: $before → $after chars (of ${PromptParts.assemble(y).length})\n")
            assertTrue(after >= before)
        }
        sb.append("  sum: $b2 → $a2 chars (≈ ${b2 / 4} → ${a2 / 4} tokens)\n\n")

        // 3. Inside one 5-step phone task: what each step can reuse from the step before (tools + system + history).
        val (allTools, _) = ToolPickSizeTest().all()
        val t = turns[0]
        val diet = ToolRouter.selectDefault(allTools, t.groups)
        val pick0 = ToolPick.pick(diet, t.text, emptySet(), t.groups)
        // From step 3 a tool the model used turns its cue on (ToolPick then sends more tools, in canonical order).
        val pick2 = ToolPick.pick(diet, t.text, setOf("type_text", "set_timer", "web_search"), t.groups + setOf("web"))
        val history = ArrayList<JSONObject>().apply { add(JSONObject().put("role", "user").put("content", "[m1] ${t.text}")) }
        var prevB = ""; var prevA = ""; var reuseB = 0L; var reuseA = 0L; var total = 0L
        var order = emptyList<String>(); val snap = PromptParts.LiveSnap<List<PromptParts.Part>>(); var liveBuilds = 0
        for (step in 0 until 5) {
            val live = Live(step, step, 0)
            val ps = parts(t.text, t.groups, false, live)
            val toolsNow = if (step < 2) pick0 else pick2
            val before = req(toolsNow, PromptParts.legacy(ps), history)
            // After: the live tail is kept for the turn (same key all turn here), the tools keep their order.
            val livePs = snap.get("chat", listOf(1L, false, t.text)) { liveBuilds++; ps.filter { it.tier == PromptParts.Tier.LIVE } }
            val kept = ToolPick.keepOrder(order, toolsNow); order = ToolPick.names(kept)
            val after = req(kept, PromptParts.assemble(ps.filter { it.tier != PromptParts.Tier.LIVE } + livePs), history)
            if (step > 0) {
                val rb = PromptParts.commonPrefix(prevB, before); val ra = PromptParts.commonPrefix(prevA, after)
                reuseB += rb; reuseA += ra; total += after.length
                sb.append("  step ${step + 1}: request ${after.length} chars, reusable $rb → $ra\n")
                assertTrue(ra >= rb)
            }
            prevB = before; prevA = after
            history.add(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject()
                .put("id", "c$step").put("type", "function").put("function", JSONObject().put("name", "run_plan").put("arguments", "{\"steps\":[\"tap Search\",\"type mum\"]}")))))
            history.add(JSONObject().put("role", "tool").put("tool_call_id", "c$step").put("content", "Screen now:\nWhatsApp (com.whatsapp). Elements, use the [number]:\n" + filler("el$step", 1400) + " [verified=true]"))
        }
        sb.insert(sb.indexOf("Across turns"), "")
        sb.append("Inside a 5-step phone task (steps 2-5 vs the step before):\n  reusable prefix $reuseB → $reuseA of $total chars " +
            "(${reuseB * 100 / total}% → ${reuseA * 100 / total}%; ≈ ${reuseB / 4} → ${reuseA / 4} tokens); live tail built $liveBuilds time(s) instead of 5\n")
        assertTrue(reuseA > reuseB)
        File("build").mkdirs()
        File("build/prompt-cache-bench.txt").writeText(sb.toString())
        println(sb)
    }
}
