package com.blaze.assistant.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.5: heads-ups for new things on Home. */
class HomeNewsTest {
    private val news = Widget("w1", WidgetTypes.NEWS, "Tech news", "News")
    private val sport = Widget("w2", WidgetTypes.SPORTS, "Arsenal", "Sports")
    private val btc = Widget("w3", WidgetTypes.PRICES, "Bitcoin", "Money")
    private val weather = Widget("w4", WidgetTypes.WEATHER, "Ndola", "Weather")
    private val widgets = listOf(news, sport, btc, weather)
    private fun d(vararg t: String, change: String = "") = WidgetData(change = change, lines = t.map { WidgetLine(it, "", "https://x/$it", "") }, fetched = 1)

    @Test fun onlyUnseenThingsAreNew() {
        val before = mapOf("w1" to d("A", "B"), "w2" to d("Arsenal 1-0 Spurs"), "w4" to d("Sunny"))
        val seen = HomeNews.keys(widgets, before)
        val after = mapOf("w1" to d("C", "A", "B"), "w2" to d("Arsenal 2-1 Chelsea", "Arsenal 1-0 Spurs"), "w4" to d("Rain"))
        val f = HomeNews.fresh(widgets, after, seen)
        assertEquals(listOf("C", "Arsenal 2-1 Chelsea"), f.map { it.text })   // weather never counts
        assertTrue(HomeNews.summary(f).startsWith("New on your Home: \u201cC\u201d (Tech news)"))
        assertTrue(HomeNews.summary(f).contains("1 new in Arsenal"))
        assertTrue(HomeNews.fresh(widgets, after, seen + f.map { it.key }).isEmpty())
    }

    @Test fun bigPriceMovesOnly() {
        assertEquals(-6.2, HomeNews.pct("−6.2%")!!, 0.001)
        assertEquals(3.0, HomeNews.pct("+3 %")!!, 0.001)
        assertTrue(HomeNews.fresh(widgets, mapOf("w3" to WidgetData(value = "$60k", change = "+3%")), emptySet()).isEmpty())
        val f = HomeNews.fresh(widgets, mapOf("w3" to WidgetData(value = "$60k", change = "+7.5%")), emptySet())
        assertEquals("Bitcoin is up +7.5%", f.single().text)
    }

    @Test fun throttled() {
        val h = 3600_000L
        assertTrue(HomeNews.mayTell(10 * h, 0, "d1", "d1", 0))
        assertFalse(HomeNews.mayTell(10 * h, 8 * h, "d1", "d1", 0))
        assertFalse(HomeNews.mayTell(20 * h, 10 * h, "d1", "d1", 3))
        assertTrue(HomeNews.mayTell(20 * h, 10 * h, "d2", "d1", 3))
    }
}
