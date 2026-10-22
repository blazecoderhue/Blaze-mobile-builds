package com.blaze.assistant.desktop

import com.blaze.assistant.desktop.DesktopAgentLogic.Act
import com.blaze.assistant.desktop.DesktopAgentLogic.Parsed
import com.blaze.assistant.desktop.DesktopAgentLogic.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAgentLogicTest {
    private fun ok(s: String) = (DesktopAgentLogic.parse(s) as Parsed.Ok).step

    @Test fun parsesAStepInsideFencesAndWords() {
        val st = ok("Sure.\n```json\n{\"say\":\"Filling in your surname\",\"action\":\"click\",\"x\":120,\"y\":340,\"total\":9}\n```")
        assertEquals(Act.CLICK, st.act); assertEquals(120, st.x); assertEquals(9, st.total); assertEquals("Filling in your surname", st.say)
        assertEquals(Act.TYPE, ok("{\"action\":\"type\",\"text\":\"Mwila {x}\"}").act)
        assertEquals(Act.DONE, ok("{\"action\":\"finish\",\"answer\":\"Form sent\"}").act)
        assertEquals(false, ok("{\"action\":\"scroll\",\"direction\":\"up\"}").down)
    }

    @Test fun rejectsBadSteps() {
        assertTrue(DesktopAgentLogic.parse("no json here") is Parsed.Bad)
        assertTrue(DesktopAgentLogic.parse("{\"action\":\"click\"}") is Parsed.Bad)
        assertTrue(DesktopAgentLogic.parse("{\"action\":\"fly\"}") is Parsed.Bad)
        assertTrue(DesktopAgentLogic.parse("{\"action\":\"type\"}") is Parsed.Bad)
    }

    @Test fun moneyMessagesAndDeletesAsk() {
        assertEquals(Risk.MONEY, DesktopAgentLogic.risk(ok("{\"action\":\"click\",\"x\":1,\"y\":1,\"say\":\"Clicking Pay now\"}")))
        assertEquals(Risk.MESSAGE, DesktopAgentLogic.risk(ok("{\"action\":\"click\",\"x\":1,\"y\":1,\"say\":\"Clicking Send\"}")))
        assertEquals(Risk.DELETE, DesktopAgentLogic.risk(ok("{\"action\":\"run\",\"app\":\"rm -rf ~/docs\"}")))
        assertEquals(Risk.MONEY, DesktopAgentLogic.risk(ok("{\"action\":\"click\",\"x\":1,\"y\":1,\"risk\":\"money\"}")))
        assertEquals(Risk.NONE, DesktopAgentLogic.risk(ok("{\"action\":\"type\",\"text\":\"send me the brochure\",\"say\":\"Typing your note\"}")))
        assertEquals(Risk.NONE, DesktopAgentLogic.risk(ok("{\"action\":\"click\",\"x\":1,\"y\":1,\"say\":\"Choosing your programme\"}")))
    }

    @Test fun commandsAndWords() {
        val st = ok("{\"action\":\"double_click\",\"x\":10,\"y\":20}")
        assertTrue(DesktopAgentLogic.command(st)!!.contains("--repeat 2"))
        assertEquals("DISPLAY=:1 xdotool mousemove 5 6 click --repeat 3 5", DesktopAgentLogic.scrollCmd(5, 6, true, 3))
        assertNull(DesktopAgentLogic.command(ok("{\"action\":\"wait\"}")))
        assertEquals(2048, DesktopAgentLogic.scale(1024, 2048, 1152)); assertEquals(500, DesktopAgentLogic.scale(500, 1024, 600))
        assertEquals("Step 4 of 9", DesktopAgentLogic.progress(4, 9)); assertEquals("Step 5 of 5", DesktopAgentLogic.progress(5, 3))
        assertEquals("Scrolling up", DesktopAgentLogic.line(ok("{\"action\":\"scroll\",\"direction\":\"up\"}")))
    }
}
