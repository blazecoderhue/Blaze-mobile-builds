package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.9 save_file: which code, which name and extension, and the "save this" cue (the Android write is on the phone list). */
class SaveFileTest {
    private val html = "Here you go:\n```html\n<!DOCTYPE html>\n<html><head><title>Solar System 3D</title></head><body><script src=\"https://cdn.jsdelivr.net/npm/three\"></script></body></html>\n```\nOpen it in a browser."

    @Test fun lastHtmlBlockGetsTitleName() {
        val p = SaveFile.plan(JSONObject(), listOf("hi", html))
        assertNotNull(p); p!!
        assertEquals("solar-system-3d.html", p.name)
        assertEquals("text/html", p.mime)
        assertTrue(p.content.startsWith("<!DOCTYPE html>"))
        assertFalse(p.content.contains("```"))
    }

    @Test fun extensionsByLanguage() {
        val cases = mapOf("python" to "py", "py" to "py", "javascript" to "js", "js" to "js", "css" to "css", "json" to "json", "svg" to "svg",
            "markdown" to "md", "md" to "md", "txt" to "txt", "html" to "html", "kotlin" to "kt", "bash" to "sh", "typescript" to "ts")
        cases.forEach { (lang, ext) -> assertEquals(lang, ext, SaveFile.extFor(lang, "x")) }
        assertEquals("blaze-python.py", SaveFile.nameFor("", "python", "print(1)\n"))
        assertEquals("blaze-js.js", SaveFile.nameFor("", "js", "let a = 1\n"))
    }

    @Test fun guessesWithoutALanguage() {
        assertEquals("html", SaveFile.extFor("", "<!doctype html><html></html>"))
        assertEquals("svg", SaveFile.extFor("", "<svg xmlns='http://www.w3.org/2000/svg'></svg>"))
        assertEquals("json", SaveFile.extFor("", "{\"a\": 1}"))
        assertEquals("py", SaveFile.extFor("", "import os\nprint(os.name)"))
        assertEquals("txt", SaveFile.extFor("", "just words"))
    }

    @Test fun lastOfSeveralBlocksAndBareHtml() {
        val two = "```python\nprint(1)\n```\nand\n```css\nbody { color: red }\n```"
        assertEquals("css", SaveFile.lastCode(listOf(two))!!.lang)
        val bare = "<!DOCTYPE html><html><head><title>Hi</title></head></html>"
        assertEquals("hi.html", SaveFile.plan(JSONObject(), listOf(bare))!!.name)
        assertNull(SaveFile.plan(JSONObject(), listOf("no code here")))
    }

    @Test fun givenContentAndFilename() {
        val p = SaveFile.plan(JSONObject().put("content", "print('x')").put("filename", "My Script.py"), emptyList())!!
        assertEquals("my-script.py", p.name)
        val q = SaveFile.plan(JSONObject().put("content", "a,b\n1,2").put("filename", "data"), emptyList())!!
        assertEquals("data.txt", q.name)
        val r = SaveFile.plan(JSONObject().put("content", "a,b\n1,2").put("language", "csv").put("filename", "data"), emptyList())!!
        assertEquals("data.csv", r.name)
    }

    @Test fun saveCue() {
        listOf("save this file", "save this", "save the file", "download it", "Save it", "save it as page.html", "save the code", "download this",
            "can you save that to my phone", "export the html").forEach { assertTrue(it, SaveFile.wantsSave(it)) }
        listOf("hi", "what is a file", "how do I save money", "save me a seat tomorrow").forEach { assertFalse(it, SaveFile.wantsSave(it)) }
    }

    @Test fun ruleSaysPhoneAndNoRefusal() {
        assertTrue(SaveFile.RULE.contains("PHONE"))
        assertTrue(SaveFile.RULE.contains("never say you can't save"))
        val def = SaveFile.tool().getJSONObject("function")
        assertEquals("save_file", def.getString("name"))
        assertEquals("object", def.getJSONObject("parameters").getString("type"))
    }

    @Test fun finalAnswerAlwaysNamesTheSavedPath() {
        val p = SaveFile.pathIn(SaveFile.savedLine("Downloads/Blaze/solar-system-3d.html"))
        org.junit.Assert.assertEquals("Downloads/Blaze/solar-system-3d.html", p)
        org.junit.Assert.assertNull(SaveFile.pathIn(SaveFile.NOTHING))
        org.junit.Assert.assertEquals("Saved to $p.\n<<follow-ups: open it | add planets>>", SaveFile.withPath("<<follow-ups: open it | add planets>>", p!!))
        org.junit.Assert.assertEquals("Saved to $p.", SaveFile.withPath("", p))
        org.junit.Assert.assertEquals("Saved to $p. Want me to open it?", SaveFile.withPath("Want me to open it?", p))
        val had = "Saved to $p. You can open it from Files."
        org.junit.Assert.assertEquals(had, SaveFile.withPath(had, p))
    }
}
