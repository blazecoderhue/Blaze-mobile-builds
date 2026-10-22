package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.15: the user's real-device /phoneqa on 0.45.14 (Android 12, 21 Oct 22:40) scored 10/55. The
 * replies below are the device's own, word for word (request ids in the comments).
 */
class DevicePhoneQa0451Test {
    private fun fn(name: String, vararg required: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name)
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray(required.toList()))))
    private val all = JSONArray().put(fn("open_settings", "page")).put(fn("tap")).put(fn("run_plan", "steps")).put(fn("compose_sms", "number"))
        .put(fn("set_timer")).put(fn("read_screen")).put(fn("wait"))
    private fun calls(text: String) = TextCalls.blockCalls(text, all)
    private fun call(r: Pair<JSONArray, String>?, i: Int): Pair<String, JSONObject> {
        val f = r!!.first.getJSONObject(i).getJSONObject("function")
        return f.getString("name") to JSONObject(f.getString("arguments"))
    }

    @Test fun deviceToolBlocksBecomeRealCalls() {
        // a4e31c7a382c98b4 (overlay-taps #1)
        var r = calls("open_settings\npage: dnd\ntap\ntext: \"Do Not Disturb\"\nwant: \"on\"\n\nDo Not Disturb is now on.")
        assertEquals(2, r!!.first.length())
        assertEquals("open_settings", call(r, 0).first); assertEquals("dnd", call(r, 0).second.getString("page"))
        assertEquals("tap", call(r, 1).first); assertEquals("Do Not Disturb", call(r, 1).second.getString("text")); assertEquals("on", call(r, 1).second.getString("want"))
        assertEquals("Do Not Disturb is now on.", r!!.second)
        // a4e31a3539952b1c (settings-value #1): "app: null" is no argument
        r = calls("open_settings\npage: display\napp: null\n\ntap\ntext: Dark mode\nwant: on\n\nWant me to open the settings and turn dark mode on?")
        assertFalse(call(r, 0).second.has("app")); assertEquals("Dark mode", call(r, 1).second.getString("text"))
        // a4e32161dfbd2b1c (settings-value #2): pairs on the tap line; a bare open_settings (no page) can't run
        r = calls("open_settings\ntap text=\"Dark mode\" want=\"on\"")
        assertEquals(1, r!!.first.length()); assertEquals("tap", call(r, 0).first); assertEquals("on", call(r, 0).second.getString("want"))
        // a4e326418a652b1c (settings-toggles #3): "- key: value" lines; a repeated key keeps the first
        r = calls("open_settings\n- page: wifi\n- page: bluetooth\n- page: dnd\n\ntap\n- text: Wi-Fi\n- want: on\n\ntap\n- text: Bluetooth\n- want: on\n\ntap\n- text: Do Not Disturb\n- want: on\n\nWi-Fi, Bluetooth and Do Not Disturb are turned on.")
        assertEquals(4, r!!.first.length()); assertEquals("wifi", call(r, 0).second.getString("page")); assertEquals("Bluetooth", call(r, 2).second.getString("text"))
        // a4e334570a4d2b1c (settings-toggles #5): key=value lines, three tools in a row
        r = calls("open_settings\npage=wifi\ntap\ntext=Wi-Fi\nwant=on\nopen_settings\npage=bluetooth\ntap\ntext=Bluetooth\nwant=on\nopen_settings\npage=dnd\ntap\ntext=Do Not Disturb\nwant=on")
        assertEquals(6, r!!.first.length()); assertEquals("Do Not Disturb", call(r, 5).second.getString("text")); assertEquals("", r!!.second)
        // a4e322214f882b1c (maps #2): name, then the JSON on the next line
        r = calls("run_plan\n{\"steps\": [{\"do\": \"open_app\", \"app\": \"Google Maps\"}, {\"do\": \"tap\", \"target\": \"Search\"}]}")
        assertEquals("run_plan", call(r, 0).first); assertEquals(2, call(r, 0).second.getJSONArray("steps").length())
        // a4e327ccb9192b1c (maps #3) / a4e32a79abcf98b4 (youtube #4): a bare steps object in a json fence is run_plan
        r = calls("```json\n{\n  \"steps\": [\n    {\n      \"do\": \"open_app\",\n      \"app\": \"YouTube\"\n    }\n  ]\n}\n```")
        assertEquals("run_plan", call(r, 0).first); assertEquals("", r!!.second)
    }

    @Test fun syntaxThatCantRunIsStillCaught() {
        // a4e32b9dbe692b1c (settings-toggles #4): names only. Nothing runnable, but the loop must ask again, never show it.
        val t = "open_settings\ntap\nopen_settings\ntap\nopen_settings\ntap"
        assertNull(calls(t))
        assertTrue(TextCalls.blockHas(t, ToolCalls.names(all)))
        // Prose is left alone: a word that happens to be a tool, a sentence, "tap element 17 and tap …".
        assertFalse(TextCalls.blockHas("I can wait.\nTap the switch to turn it on.", ToolCalls.names(all)))
        assertNull(calls("tap element 17 and tap element 13 and tap element 17"))
        assertNull(calls("wait\nI'll check again in a minute."))
    }

    @Test fun appNotesAreNeverTheAsk() {
        val tools = JSONArray().put(fn("set_alarm")).put(fn("open_settings", "page")).put(fn("tap")).put(fn("device_setting")).put(fn("run_plan", "steps"))
        val note = TapLoop.hint(TapLoop.Recipe("k", JSONArray().put(JSONObject().put("do", "open_settings").put("page", "display")), 1, 0))
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", "s"))
            .put(JSONObject().put("role", "user").put("content", "open display settings and turn dark mode on"))
            .put(JSONObject().put("role", "user").put("content", note))
        assertTrue(ToolTruth.isAppNote(note))
        assertEquals("open_settings", ToolTruth.choiceName(msgs, ToolTruth.names(tools)))
        val orient = Orient.contextNote("Screen: Blaze (com.blaze.assistant) (14 elements)\n[12] field")
        val m2 = JSONArray().put(JSONObject().put("role", "user").put("content", "set an alarm for 6:40 am called QA wake")).put(JSONObject().put("role", "user").put("content", orient))
        assertEquals("set_alarm", ToolTruth.choiceName(m2, ToolTruth.names(tools)))
        // A direct phone ask never gets the saved tap plan.
        assertTrue(ToolTruth.directPhone("open display settings and turn dark mode on"))
        assertTrue(ToolTruth.directPhone("set a timer for 2 minutes"))
        assertFalse(ToolTruth.directPhone("open WhatsApp and scroll to the top of my chat with Mum"))
        // The run route carries the named choice (was "auto" + blaze_tools "auto" on every run).
        val body = CloudRuns.createBody("c1", msgs, tools, "agent")
        assertEquals("open_settings", body.getJSONObject("tool_choice").getJSONObject("function").getString("name"))
        assertFalse(body.has("blaze_tools"))
        val plain = CloudRuns.createBody("c2", JSONArray().put(JSONObject().put("role", "user").put("content", "hi there")), tools, "agent")
        assertEquals("auto", plain.get("tool_choice")); assertEquals("auto", plain.getString("blaze_tools"))
    }

    @Test fun actionsAreNotScheduleQuestions() {
        // a4e3... alarm-off 0/5: "turn off the QA wake alarm" got today's agenda instead.
        assertFalse(TurnContext.scheduleAsk("turn off the QA wake alarm"))
        assertFalse(TurnContext.scheduleAsk("set an alarm for 6:40 am called QA wake"))
        assertFalse(TurnContext.scheduleAsk("add a calendar event QA review tomorrow at 15:00 for 30 minutes"))
        assertTrue(TurnContext.scheduleAsk("any alarms tomorrow?"))
        assertTrue(TurnContext.scheduleAsk("what's on friday"))
    }

    @Test fun draftClaimNeedsTheTool() {
        // a4e31e32fbfa98b4 (draft-only #2): "Drafted the text to 0977000000." with no tool at all.
        val a = ActionProof.ACTIONS.first { it.id == "draft" }
        assertTrue(a.claim.containsMatchIn("Drafted the text to 0977000000. Want me to send it?"))
        assertTrue(a.asks.containsMatchIn("draft a text to 0977000000 saying QA draft test, don't send it"))
        assertTrue("compose_sms" in a.tools)
        assertFalse(a.claim.containsMatchIn("Want me to draft it for you?"))
    }
}
