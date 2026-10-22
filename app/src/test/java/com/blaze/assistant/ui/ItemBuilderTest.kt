package com.blaze.assistant.ui

import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v0.45.0 P3 #10: incremental chat rows are always identical to a full rebuild, and streaming reuses the prefix. */
class ItemBuilderTest {
    private fun sig(items: List<Item>): List<String> = items.map { i ->
        when (i) {
            is Item.Msg -> "M ${i.key} ${i.pos} ${i.m.text}"
            is Item.Steps -> "S ${i.key} ${i.pos} live=${i.live} end=${i.endAt} start=${i.startAt} n=${i.steps.size} ${i.steps.joinToString("|") { it.text }}"
        }
    }

    @Test fun randomEditSequencesMatchAFullRebuild() {
        val rnd = Random(45)
        repeat(30) { round ->
            val b = ItemBuilder()
            var msgs = listOf<ChatMessage>()
            var id = 1L
            var at = 1_000_000L
            var working = false
            val hide = round % 3 == 0
            repeat(250) {
                at += rnd.nextLong(0, 900_000)
                when (rnd.nextInt(10)) {
                    0, 1 -> msgs = msgs + ChatMessage(id++, Role.USER, "u$id", at = at)
                    2, 3 -> msgs = msgs + ChatMessage(id++, Role.STEP, "s$id", at = at)
                    4 -> msgs = msgs + ChatMessage(id++, Role.BLAZE, "b$id", at = at)
                    5, 6 -> if (msgs.isNotEmpty()) msgs = msgs.dropLast(1) + msgs.last().copy(text = msgs.last().text + "+")   // a streamed frame
                    7 -> working = !working
                    8 -> if (msgs.size > 3) { val j = rnd.nextInt(msgs.size); msgs = msgs.mapIndexed { i, m -> if (i == j) m.copy(text = m.text + "*") else m } }
                    9 -> if (msgs.isNotEmpty() && rnd.nextBoolean()) msgs = msgs.dropLast(1) else msgs = msgs + ChatMessage(id++, Role.ERROR, "e$id", at = at)
                }
                assertEquals(sig(buildItems(msgs, working, hide)), sig(b.build(msgs, working, hide)))
            }
        }
    }

    @Test fun aStreamedFrameReusesEveryEarlierRow() {
        val b = ItemBuilder()
        var msgs = (1..400).map { i -> ChatMessage(i.toLong(), if (i % 2 == 0) Role.BLAZE else Role.USER, "m$i", at = i * 60_000_000L) }
        msgs = msgs + ChatMessage(1000, Role.STEP, "Looking", at = 9_000_000_000L) + ChatMessage(1001, Role.BLAZE, "He", at = 9_000_000_100L)
        val first = b.build(msgs, true, false)
        msgs = msgs.dropLast(1) + msgs.last().copy(text = "Hello")
        val second = b.build(msgs, true, false)
        assertTrue("rows before the streamed reply are the same instances", (0 until 399).all { first[it] === second[it] })
        assertTrue(b.reused >= 399)
        assertEquals(sig(buildItems(msgs, true, false)), sig(second))
    }
}
