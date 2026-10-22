package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.28.1: tool calls leaked as text (Luke's exact strings) are run as real calls, or stripped, never shown. */
class ToolLeakTest {
    private fun tool(n: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n)
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
    private val all = JSONArray().apply { listOf("find_tool", "browser_open", "memory_save", "remember", "web_search").forEach { put(tool(it)) } }

    private val findTool = "[/find_tool need=\"stock tracking\"]"
    private val github = "[github_open repo=\"LacksonKapapa/Blaze-Agent\"]"
    private val blazer = "[/blazer_save kind=features text=\"## App ideas\n1. **Offline mode**: works without data\n2. **Sync**: across devices"

    private fun only(r: Pair<JSONArray, String>?): Pair<String, JSONObject> {
        assertNotNull(r); assertEquals(1, r!!.first.length())
        val f = r.first.getJSONObject(0).getJSONObject("function")
        return f.getString("name") to JSONObject(f.getString("arguments"))
    }

    @Test fun findToolBecomesARealCall() {
        val leaks = ToolLeak.find(findTool)
        assertEquals(1, leaks.size); assertEquals("find_tool", leaks[0].name); assertEquals("stock tracking", leaks[0].args.getString("need"))
        val (n, a) = only(ToolLeak.toCalls(findTool, all))
        assertEquals("find_tool", n); assertEquals("stock tracking", a.getString("need"))
        assertEquals("", ToolLeak.toCalls(findTool, all)!!.second)
    }

    @Test fun githubOpenOpensTheRepoInBlazesBrowser() {
        val (n, a) = only(ToolLeak.toCalls(github, all))
        assertEquals("browser_open", n)
        assertEquals(ToolLeak.BLAZE_REPO_URL, a.getString("url"))
        assertEquals("https://gitlab.com/blazeinchue/Blaze-agent", a.getString("url"))
        assertEquals("https://github.com/octocat/hello", ToolLeak.githubUrl(JSONObject().put("repo", "octocat/hello")))
    }

    @Test fun blazerSaveMapsToMemorySaveEvenUnclosed() {
        val (n, a) = only(ToolLeak.toCalls(blazer, all))
        assertEquals("memory_save", n)
        assertTrue(a.getString("text").startsWith("## App ideas\n1. **Offline mode**"))
        assertEquals("project", a.getString("kind"))
        assertEquals("features", a.getString("subject"))
    }

    @Test fun neverShownToTheUser() {
        for (s in listOf(findTool, github, blazer)) {
            assertEquals("", ToolLeak.strip(s))
            assertEquals("", ReplyParts.split(s, final = true).text)
            assertTrue(ToolLeak.has(s))
        }
        assertEquals("Here you go.", ReplyParts.split("Here you go. $findTool", final = true).text)
        // keepCalls lets the gatekeeper still see them.
        assertEquals(findTool, ReplyParts.split(findTool, final = true, keepCalls = true).text)
    }

    @Test fun halfStreamedCallIsHidden() {
        assertEquals("Checking", ToolLeak.strip("Checking [/find_to", final = false))
        assertEquals("Checking", ToolLeak.strip("Checking [github_open repo=\"Lack", final = false))
        assertEquals("Ok", ToolLeak.strip("Ok <tool_call>{\"name\": \"web_", final = false))
        assertEquals("Ok", ReplyParts.split("Ok [/blazer_save kind=features text=\"## App", final = false).text)
    }

    @Test fun otherForms() {
        val (n1, a1) = only(ToolLeak.toCalls("<tool_call>find_tool need=\"weather\"</tool_call>", all))
        assertEquals("find_tool", n1); assertEquals("weather", a1.getString("need"))
        val (n2, a2) = only(ToolLeak.toCalls("<function=web_search>{\"query\": \"zambia news\"}</function>", all))
        assertEquals("web_search", n2); assertEquals("zambia news", a2.getString("query"))
        val (n3, _) = only(ToolLeak.toCalls("[TOOL_CALLS][{\"name\": \"web_search\", \"arguments\": {\"query\": \"x\"}}]", all))
        assertEquals("web_search", n3)
    }

    @Test fun unknownToolIsStrippedNotRun() {
        val s = "[/teleport_user city=\"Ndola\"]"
        assertNull(ToolLeak.toCalls(s, all))
        assertTrue(ToolLeak.has(s))
        assertEquals("", ToolLeak.strip(s))
        assertFalse(ToolLeak.HONEST.contains("["))
    }

    @Test fun normalTextIsUntouched() {
        val s = "Tasks:\n- [x] done\n- [ ] next\nSee [the docs](https://example.com) and note [1]. Price [K1,299]."
        assertTrue(ToolLeak.find(s).isEmpty())
        assertFalse(ToolLeak.has(s))
        assertEquals(s, ToolLeak.strip(s))
        assertEquals(s, ToolLeak.strip(s, final = false))
    }

    // v0.28.3: Luke's 15:55 reply was saved by the old build and still drawn raw after updating to v0.28.1.
    @Test fun oldSavedLeaksAreHiddenWhenDrawn() {
        assertEquals(ToolLeak.STORED_EMPTY, ToolLeak.bubbleText(github))
        assertFalse(ToolLeak.bubbleText(github).contains("["))
        assertEquals("", ToolLeak.forDisplay(github))
        assertEquals("Opening it now.", ToolLeak.bubbleText("Opening it now. $github"))
        // The chat-list preview under a project chat.
        assertEquals("", ToolLeak.forDisplay("[github_open repo=\"LacksonKapapa/Blaze-Agent\"]"))
        // Mixed-case owner / repo maps to opening the real repo.
        val (n, a) = only(ToolLeak.toCalls("[github_open repo=\"LacksonKapapa/Blaze-Agent\"]", all))
        assertEquals("browser_open", n); assertEquals("https://gitlab.com/blazeinchue/Blaze-agent", a.getString("url"))
        assertEquals(ToolLeak.BLAZE_REPO_URL, ToolLeak.githubUrl(JSONObject().put("repo", "lacksonkapapa/BLAZE-AGENT")))
    }

    @Test fun displayLeavesNormalRepliesByteForByte() {
        for (s in listOf("", "Plain answer.", "Tasks:\n- [x] done\n- [ ] next  \n\n\n\nSee [the docs](https://example.com).", "Use <b>bold</b>   \n here"))
            assertEquals(s, ToolLeak.forDisplay(s))
        assertEquals("", ToolLeak.bubbleText(""))
    }

    @Test fun promptRuleAsksForNativeCalls() {
        assertTrue(ToolLeak.PROMPT_RULE.contains("native tool-calling"))
        assertTrue(ToolLeak.NUDGE.contains("native tool-calling"))
    }
}
