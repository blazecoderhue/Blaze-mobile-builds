package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalStatusTest {
    private val tools = listOf("run_shell", "web_search", "file_write", "read_screen")

    @Test fun plainDropsCommands() {
        assertEquals("", Friendly.plain("$ python app.py"))
        assertEquals("", Friendly.plain("```bash\npip install flask\n```"))
        assertEquals("Setting up the project", Friendly.plain("Setting up the project. Running `pip install flask` now."))
        assertEquals("Let me check the git history", Friendly.plain("Let me check the git history."))
        assertEquals("Now I'll save the notes", Friendly.plain("Now I'll save the notes with file_write(path=\"a.md\")."))
        assertFalse(Friendly.plain("I'll run python main.py --fast to test it.").contains("python"))
    }

    @Test fun betweenIsPlain() {
        assertEquals("Reading your message", Friendly.between(null))
        assertEquals("Going through the search results", Friendly.between("web_search"))
        assertEquals("Checking how that went", Friendly.between("run_shell"))
    }

    @Test fun unknownToolNeverShowsItsName() {
        assertFalse(Friendly.say("some_new_tool", org.json.JSONObject()).contains("_"))
        assertFalse(Friendly.say("some_new_tool", org.json.JSONObject()).lowercase().contains("some new tool"))
    }

    @Test fun fakeCallsCaught() {
        assertTrue(FakeCall.looksFake("run_shell(command=\"ls\")", tools, false, "list my files"))
        assertTrue(FakeCall.looksFake("{\"name\": \"web_search\", \"arguments\": {}}", tools, false, "news"))
        assertTrue(FakeCall.looksFake("```bash\npython app.py\n```", tools, true, "start the app"))
        assertTrue(FakeCall.looksFake("I ran the tests and they all pass.", tools, false, "run the tests"))
        assertTrue(FakeCall.looksFake("Action: web_search\nAction Input: weather", tools, false, "weather"))
    }

    @Test fun realAnswersPass() {
        assertFalse(FakeCall.looksFake("I ran the tests and they all pass.", tools, true, "run the tests"))
        assertFalse(FakeCall.looksFake("Paris is the capital of France.", tools, false, "capital of france"))
        assertFalse(FakeCall.looksFake("Install it with:\n```bash\npip install flask\n```\nThen import it in your app and you're set.", tools, false, "how do I install flask"))
    }

    @Test fun callishStreams() {
        assertTrue(FakeCall.callish("{\"name\":"))
        assertTrue(FakeCall.callish("run_shell(command"))
        assertTrue(FakeCall.callish("$ ls"))
        assertFalse(FakeCall.callish("Sure, here's the plan"))
    }
}
