package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.45.3 Halo stall: text must reach the screen as it arrives. (1) The SSE reader hands every chunk on at once (before,
 * a chunk within 16 ms of the previous one waited for the next chunk). (2) The streaming filters (tool-call text, [re:]
 * markers, follow-up tags, Autopilot markers) hold back at most a short ambiguous suffix, never whole paragraphs.
 */
class StreamPromptTest {
    private val halo = "Looking at your 79 memories, here's what's safe to clean up:\n\n**Duplicates & redundant entries:**\n" +
        "- **Task speed** — #2, #12, #26 all say the same thing\n- **Communication style** — #17, #24, #72, #73, #75, #77 (6 entries for one preference)\n" +
        "- **Default browser = Blaze** — #19, #20, #21, #27 (4 entries)\n- **Music/video taste** — #13, #22, #62 overlap\n\n" +
        "**Outdated / one-time facts:**\n- **Phone health** — #63 (score 92%, battery 43% from a past check)\n- **Unused apps list** — #64 (specific apps/sizes from a past scan)\n" +
        "- **Linux browser launch** — #67 (one-time action done)\n\nWant me to remove the duplicates and outdated ones? I'd keep the newest version of each."

    private fun sse(pieces: List<String>): String = pieces.joinToString("") { p ->
        "data: " + org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject().put("delta", org.json.JSONObject().put("content", p)))) + "\n\n"
    } + "data: [DONE]\n\n"

    @Test fun everyChunkIsHandedOnAtOnce() {
        val pieces = halo.chunked(7)
        val seen = ArrayList<String>()
        val msg = LlmClient.readSse(sse(pieces).byteInputStream(), { seen += it }, null)
        assertEquals(halo, msg.getString("content"))
        // One hand-off per chunk (they all arrive within microseconds here: the old 16 ms rule dropped most of them).
        assertEquals(pieces.size, seen.size)
        var acc = ""
        pieces.forEachIndexed { i, p -> acc += p; assertEquals(acc, seen[i]) }
    }

    /** What the main chat bubble shows for streamed text so far (the agent's StreamGate path). */
    private fun shown(partial: String) = RapidMessages.display(ReplyParts.split(partial).text, final = false)

    @Test fun filtersHoldBackOnlyAShortSuffix() {
        // Feed the reply chunk by chunk; after every chunk the bubble must show all but a short tail of what arrived.
        var maxHeld = 0
        for (end in 1..halo.length) {
            val partial = halo.substring(0, end)
            val s = shown(partial)
            val held = partial.trimEnd().length - s.length
            assertTrue("held back $held chars at $end: '${partial.takeLast(40)}'", held <= 24)
            maxHeld = maxOf(maxHeld, held)
            if (end > 60) assertTrue(s.isNotEmpty())
        }
        assertEquals(halo, ReplyParts.split(halo, final = true).text)
        assertTrue(maxHeld <= 24)
    }

    @Test fun duoFiltersHoldBackOnlyAShortSuffix() {
        for (end in 1..halo.length) {
            val partial = halo.substring(0, end)
            val s = Duo.clean(DuoText.strip(RapidMessages.display(ReplyParts.split(Duo.stripSignal(partial, false), final = false).text, final = false), false, Duo.ROOM_TOOLS))
            assertTrue("duo held back at $end", partial.trim().length - s.length <= 24)
        }
    }

    @Test fun aToolLikeLineIsHeldOnlyWhileItCouldBeACall() {
        val a = "Here's the plan.\n\nmemory_search(query=\"all\""
        assertEquals("Here's the plan.", shown(a))
        val b = "Here's the plan.\n\nmemory_search is the tool I'd use, and it found 79 lines."
        assertEquals(b, shown(b))
    }

    @Test fun loneListMarkerWaitsForItsWords() {
        assertEquals("- one\n- two", com.blaze.assistant.ui.Md.dropLoneMarker("- one\n- two\n- "))
        assertEquals("1. a", com.blaze.assistant.ui.Md.dropLoneMarker("1. a\n2."))
        assertEquals("- one\n- t", com.blaze.assistant.ui.Md.dropLoneMarker("- one\n- t"))
    }
}
