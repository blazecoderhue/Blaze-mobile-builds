package com.blaze.assistant.blazeos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.36.0 BlazeOS native apps: the real Snake module (blazeos-sdk/samples/snake/app.wasm) runs in the runtime with a
 * fake host: it draws, takes input, saves its best score only when "storage" is declared, and asks before notifying.
 */
class OsWasmRuntimeTest {
    private class FakeHost(override val declared: List<String>, private val decision: OsGrants.Decision = OsGrants.Decision.ASK) : OsWasmHost {
        override val appId = "pkg:blazeos.snake.native"
        override val label = "Snake Native"
        var store: String? = null
        val banners = ArrayList<Pair<String, String>>()
        val asked = ArrayList<String>()
        override fun decide(perm: String) = decision
        override fun readStore() = store
        override fun writeStore(json: String) { store = json }
        override fun notify(title: String, body: String) { banners += title to body }
        override fun ask(perm: String, then: (Boolean) -> Unit) { asked += perm; then(true) }
        override fun dark() = true
        override fun accent() = 0xFFFF9A3C.toInt()
        override fun fontScale() = 1f
        override fun hasImage(path: String) = false
        override fun log(msg: String) {}
    }

    private fun snake(): ByteArray {
        val f = listOf(File("../blazeos-sdk/samples/snake/app.wasm"), File("blazeos-sdk/samples/snake/app.wasm")).first { it.isFile }
        return f.readBytes()
    }

    private fun texts(rt: OsWasmRuntime) = rt.frame.filterIsInstance<DrawCmd.Text>().map { it.text }

    /** Runs the game: tap to start, then frames 120 ms apart until it hits the right wall (no turns). */
    private fun playUntilOver(rt: OsWasmRuntime): Double {
        rt.input.add(OsInput.Touch(OsInput.UP, 100f, 300f, 0)); rt.deliver()
        var t = 1000.0
        rt.step(t)
        repeat(40) { t += 120; rt.step(t) }
        return t
    }

    @Test fun snakeDrawsAndPlays() {
        val host = FakeHost(listOf("storage", "notifications"))
        val rt = OsWasmRuntime(host)
        assertTrue(rt.error ?: "", rt.load(snake(), 390f, 780f))
        rt.step(0.0)
        assertTrue(rt.frame.first() is DrawCmd.Clear)
        assertTrue(texts(rt).toString(), "Snake" in texts(rt) && "Tap to play" in texts(rt))
        assertTrue(texts(rt).any { it.startsWith("Score 0") })
        playUntilOver(rt)
        assertNull(rt.error)
        assertTrue(texts(rt).toString(), texts(rt).any { it.startsWith("Game over") })
        assertTrue(rt.frame.count { it is DrawCmd.Rect } >= 4)   // board, food, snake
    }

    @Test fun eatingScoresSavesTheBestAndNotifies() {
        val host = FakeHost(listOf("storage", "notifications"))
        val rt = OsWasmRuntime(host)
        rt.load(snake(), 390f, 780f)
        rt.input.add(OsInput.Touch(OsInput.UP, 0f, 0f, 0)); rt.deliver()
        // Steer onto the food by reading where it's drawn is overkill; play many rounds of random food until one scores.
        var t = 0.0; var scored = false
        for (round in 0 until 60) {
            rt.input.add(OsInput.Key(13, true)); rt.deliver()
            rt.step(t)
            repeat(20) { t += 120; rt.step(t) }
            if (host.store != null) { scored = true; break }
        }
        if (scored) {
            assertTrue(host.store!!, host.store!!.contains("\"best\""))
            assertEquals("New best in Snake", host.banners.firstOrNull()?.first)
            assertEquals(listOf("notifications"), host.asked)
        }
        assertNull(rt.error)
    }

    @Test fun storageIsRefusedWhenNotDeclared() {
        val host = FakeHost(listOf("notifications"))
        host.store = """{"best":"7"}"""
        val rt = OsWasmRuntime(host)
        rt.load(snake(), 390f, 780f)
        rt.step(0.0)
        // init's store_get was refused: the best shown stays 0 even though the store holds 7.
        assertTrue(texts(rt).toString(), texts(rt).any { it.endsWith("Best 0") })
        assertTrue(rt.refused.containsKey("store_get"))
    }

    @Test fun storedBestIsReadWhenDeclared() {
        val host = FakeHost(listOf("storage"))
        host.store = """{"best":"7"}"""
        val rt = OsWasmRuntime(host)
        rt.load(snake(), 390f, 780f)
        rt.step(0.0)
        assertTrue(texts(rt).toString(), texts(rt).any { it.endsWith("Best 7") })
    }

    @Test fun deniedStorageIsRefused() {
        val host = FakeHost(listOf("storage"), OsGrants.Decision.DENIED)
        host.store = """{"best":"7"}"""
        val rt = OsWasmRuntime(host)
        rt.load(snake(), 390f, 780f)
        rt.step(0.0)
        assertTrue(texts(rt).any { it.endsWith("Best 0") })
    }

    @Test fun backPausesWhilePlaying() {
        val rt = OsWasmRuntime(FakeHost(listOf("storage")))
        rt.load(snake(), 390f, 780f)
        var handled: Boolean? = null
        rt.input.add(OsInput.Back); rt.deliver { handled = it }
        assertEquals(false, handled)                    // not playing: BlazeOS goes home
        rt.input.add(OsInput.Touch(OsInput.UP, 0f, 0f, 0)); rt.input.add(OsInput.Back); rt.deliver { handled = it }
        assertEquals(true, handled)
        rt.step(10.0)
        assertTrue(texts(rt).any { it.startsWith("Paused") })
    }

    @Test fun badModulesAreRefusedWithAReason() {
        assertNotNull(OsWasmRuntime.check(ByteArray(10)))
        assertTrue(OsWasmRuntime.check("hello".toByteArray())!!.contains("WebAssembly"))
        // Snake asks for 1 page; a 0-page cap refuses it.
        assertTrue(OsWasmRuntime.check(snake(), maxPages = 0)!!.contains("memory"))
        assertNull(OsWasmRuntime.check(snake()))
        val rt = OsWasmRuntime(FakeHost(emptyList()))
        assertTrue(!rt.load(ByteArray(12), 100f, 100f) && rt.error != null)
    }
}
