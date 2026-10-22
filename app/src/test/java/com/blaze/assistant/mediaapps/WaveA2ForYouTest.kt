package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.StoreItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave A items 27-28: For you mix, "All" chips selected by default, chips narrow and toggle back. */
class WaveA2ForYouTest {
    private val now = 1_760_000_000_000L
    private fun e(kind: String, title: String, author: String, w: Int = 1, daysAgo: Int = 1) = TasteEvent(kind, title, author, w, now - daysAgo * 86_400_000L)
    private fun item(src: String, id: String) = StoreItem(src, id, "t$id", "a")
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    @Test fun nothingSelectedMeansForYouMix() {
        assertTrue(ForYou.isMix("", ""))
        assertTrue(ForYou.isMix("  ", ""))
        assertFalse(ForYou.isMix("ptogrammers", ""))
        assertFalse(ForYou.isMix("", "Silicon Valley Girl"))
        assertEquals(listOf("A", "B", "C"), ForYou.queries("", "", listOf("A", "B", "C")))
    }

    @Test fun chipNarrowsTypedSearchAndTogglesBack() {
        assertEquals(listOf("programmers Silicon Valley Girl"), ForYou.queries("programmers", "Silicon Valley Girl", listOf("x")))
        assertEquals(listOf("Amapiano"), ForYou.queries("", "Amapiano", listOf("x")))
        assertEquals(listOf("lo-fi"), ForYou.queries(" lo-fi ", "", listOf("x")))
        assertEquals("Tobs Manhwa", ForYou.toggle(ForYou.ALL, "Tobs Manhwa"))
        assertEquals(ForYou.ALL, ForYou.toggle("Tobs Manhwa", "Tobs Manhwa"))
        assertEquals("His Story", ForYou.toggle("Tobs Manhwa", "His Story"))
    }

    @Test fun allSourcesByDefault() {
        val ready = listOf("youtube", "archive-films", "blender")
        assertEquals(ready, ForYou.sources(ForYou.ALL, ready))
        assertEquals(listOf("blender"), ForYou.sources("blender", ready))
    }

    @Test fun mixInterleavesAndDedupes() {
        val a = listOf(item("yt", "1"), item("yt", "2"), item("yt", "3"))
        val b = listOf(item("bl", "9"), item("yt", "1"))
        val m = ForYou.mix(listOf(a, b))
        assertEquals(listOf("yt:1", "bl:9", "yt:2", "yt:3"), m.map { it.source + ":" + it.id })
        assertEquals(2, ForYou.mix(listOf(a, b), max = 2).size)
        assertTrue(ForYou.mix(emptyList()).isEmpty())
    }

    @Test fun musicGenresComeFromWhatTheyPlay() {
        val ev = listOf(
            e("audio", "Mnike (Amapiano mix)", "Tyler ICU", 3), e("audio", "Amapiano Friday", "DJ Maphorisa", 1),
            e("audio", "Way Maker (Live worship)", "Sinach", 1, daysAgo = 40), e("video", "Rock documentary", "BBC", 5),
        )
        val g = ForYou.genres(ev, now)
        assertEquals("Amapiano", g.first())
        assertTrue("Gospel" in g)
        assertFalse("video plays don't make music genres", g.indexOf("Rock") in 0..1)
        assertEquals(ForYou.starterGenres.take(4), ForYou.genres(emptyList(), now).take(4))   // cold start
        assertFalse("'rap' must not match inside 'grape'", ForYou.genres(listOf(e("audio", "Grapevine", "x", 9)), now).first() == "Hip hop")
    }

    @Test fun videoSeedsAreTopChannels() {
        val ev = listOf(e("video", "Startup ep 1", "Silicon Valley Girl", 3), e("video", "Startup ep 2", "Silicon Valley Girl", 3), e("video", "Knight", "Tobs Manhwa"))
        val s = ForYou.seeds("video", ev, now)
        assertEquals("Silicon Valley Girl", s.first())
        assertTrue(s.size <= ForYou.SEEDS)
        assertEquals("Silicon Valley Girl", ForYou.chips("video", ev, now).first())
    }

    @Test fun storeViewWiring() {
        val s = src("mediaapps/MediaUi.kt")
        assertTrue("source defaults to All", "var source by mutableStateOf(ForYou.ALL)" in s)
        assertTrue("source row has All first", "listOf(ForYou.ALL to \"All\") + sources.map" in s)
        assertTrue("channel/genre row has All first", "listOf(ForYou.ALL to \"All\") + ideas.map" in s)
        assertFalse("no channel pre-picked on open", "search(idea = ideas.firstOrNull())" in s)
        assertTrue("results open from their own source under All", "state.previewSource = StoreSources.get(r.source)" in s)
    }
}
