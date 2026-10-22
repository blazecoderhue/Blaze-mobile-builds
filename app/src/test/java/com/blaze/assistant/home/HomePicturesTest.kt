package com.blaze.assistant.home

import com.blaze.assistant.ui.GlassTilt
import com.blaze.assistant.ui.HomeImages
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePicturesTest {
    @Test fun feedPictures() {
        assertEquals("https://img.example.com/a.jpg", Rss.image("""<title>x</title><media:thumbnail url="http://img.example.com/a.jpg" width="1"/>"""))
        assertEquals("https://cdn.x/b.png", Rss.image("""<media:content url="https://cdn.x/b.png" medium="image"/>"""))
        assertEquals("https://e.x/c.jpg", Rss.image("""<enclosure length="1" type="image/jpeg" url="https://e.x/c.jpg"/>"""))
        assertEquals("https://www.bing.com/th?id=ONUT.abc&pid=News&w=480&h=270&c=7", Rss.image("<News:Image>http://www.bing.com/th?id=ONUT.abc&amp;pid=News</News:Image>"))
        assertEquals("https://d.x/i.jpg", Rss.image("""<description><![CDATA[<p><img src="//d.x/i.jpg"></p>]]></description>"""))
        assertEquals("https://i.ytimg.com/vi/abc123/mqdefault.jpg", Rss.image("", "abc123"))
        assertEquals("", Rss.image("<title>no picture</title>"))
    }

    @Test fun bingItemsParse() {
        val xml = """<rss><channel><item><title>CoreWeave credit default swaps hit distress levels</title><link>http://b/x</link>
            <pubDate>Thu, 15 Oct 2026 09:52:16 GMT</pubDate><News:Source>CNBC</News:Source><News:Image>http://www.bing.com/th?id=1&amp;pid=News</News:Image></item></channel></rss>"""
        val e = Rss.parse(xml).single()
        assertEquals("CNBC", e.source); assertEquals(true, e.image.startsWith("https://www.bing.com/th?id=1"))
    }

    @Test fun borrowMatchesSameStory() {
        val g = listOf(Rss.Entry("CoreWeave credit default swaps hit distress levels as investors sour on AI debt", "g1", "CNBC", 0),
            Rss.Entry("Totally different story about football", "g2", "BBC", 0))
        val b = listOf(Rss.Entry("CoreWeave credit default swaps hit distress levels as investors sour", "b1", "", 0, image = "https://p/1.jpg"),
            Rss.Entry("Weather turns cold", "b2", "", 0, image = "https://p/2.jpg"))
        val out = Rss.borrowImages(g, b)
        assertEquals("https://p/1.jpg", out[0].image); assertEquals("", out[1].image)
        assertEquals("https://p/1.jpg", Rss.toData(out, "AI", 0L).lines[0].image)
        assertEquals("https://p/1.jpg", WidgetLine.from(Rss.toData(out, "AI", 0L).lines[0].toJson()).image)
    }

    @Test fun tiltIsFinite() {
        assertEquals(GlassTilt.MAX_Y, GlassTilt.tilt(10_000f, 1000f, GlassTilt.MAX_Y))
        assertEquals(-GlassTilt.MAX_X, GlassTilt.tilt(-10_000f, 1000f, GlassTilt.MAX_X))
        assertEquals(0f, GlassTilt.tilt(50f, 0f, GlassTilt.MAX_Y))
        assertEquals(1, HomeImages.sample(400)); assertEquals(4, HomeImages.sample(1600)); assertEquals(2, HomeImages.sample(720))
    }
}
