package com.blaze.assistant.agent

import androidx.compose.ui.graphics.Color
import com.blaze.assistant.ui.Md
import com.blaze.assistant.ui.ParseMemo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.45.7 local languages: a message in each language goes through the reply parsers, the streaming filters, the bubble
 * text, the chat file and the prompt pieces unchanged (byte for byte, no lost combining marks), and the cutting helpers
 * never split a letter.
 */
class LocalLanguagesTest {
    companion object {
        /** One everyday line per language. Yorùbá uses DECOMPOSED tone marks on purpose (e + U+0323 + U+0301 …). */
        val SAMPLES = linkedMapOf(
            "sw" to "Habari za asubuhi! Je, unaweza kunikumbusha kulipa kodi tarehe moja saa tatu?",
            "bem" to "Mwashibukeni mukwai! Bushe kuli shani lelo? Ndefwaya ukulanda na bamayo.",
            "ny" to "Muli bwanji? Ndikufuna kugula chimanga ku msika mawa m'mawa. Ŵana ŵathu ali bwino.",
            "ha" to "Ina kwana? Ɗan'uwana yana ƙauye, ya ce ɓarawo ya ɗauki ƴaƴan itace. Ƙasar Ɓadau.",
            "yo" to "E\u0323 ka\u0301a\u0300a\u0301ro\u0323\u0300! S\u0323e\u0301 da\u0301adaa ni? O\u0323ja\u0300 wa\u0301 ni ọ̀la. Ẹ ṣé o.",
            "am" to "ሰላም! እንደምን አደርክ? ነገ ጠዋት በሦስት ሰዓት አስታውሰኝ። አመሰግናለሁ።",
            "fr" to "Bonjour ! Peux-tu me rappeler de payer le loyer à 9 h ? Merci, ça va très bien.",
            "pt" to "Olá! Podes lembrar-me de pagar a renda às 9h? Obrigado, estou ótimo.",
            "ar" to "مرحبا! هل يمكنك تذكيري بدفع الإيجار غدا الساعة التاسعة؟ شكرا جزيلا.",
            "emoji" to "Sawa 👍🏾 tuonane kesho 👨\u200d👩\u200d👧 ❤️ 🇿🇲",
        )
        private fun marks(s: String) = s.count { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
    }

    @Test fun replyParsersKeepEveryLanguageByteForByte() {
        for ((lang, s) in SAMPLES) {
            assertEquals("$lang ReplyClean final", s, ReplyClean.clean(s, final = true).text)
            assertEquals("$lang ReplyClean streaming", s, ReplyClean.clean(s, final = false).text)
            assertEquals("$lang ToolLeak.forDisplay", s, ToolLeak.forDisplay(s))
            assertEquals("$lang ToolLeak.bubbleText", s, ToolLeak.bubbleText(s))
            assertEquals("$lang ToolLeak.strip", s, ToolLeak.strip(s, final = true))
            assertFalse("$lang no leak", ToolLeak.has(s))
            assertEquals("$lang Md.render", s, Md.render(s, Color.Blue, Color.Gray).text)
            assertEquals("$lang Md.plain", s, Md.plain(s))
            assertEquals("$lang marks kept", marks(s), marks(Md.render(s, Color.Blue, Color.Gray).text))
        }
    }

    @Test fun streamingPrefixesNeverLoseAMark() {
        // Every streamed prefix renders to exactly that prefix (live parse, the ParseMemo path and the final parse agree).
        for ((lang, s) in SAMPLES) {
            var i = 1
            while (i <= s.length) {
                val p = s.substring(0, i)
                val live = ParseMemo.memo(ParseMemo.md, "t|$lang|$p", true) { Md.render(p, Color.Blue, Color.Gray, live = true) }
                assertEquals("$lang live prefix $i", p, live.text)
                i += 3
            }
            val done = ParseMemo.memo(ParseMemo.md, "t|$lang|$s", false) { Md.render(s, Color.Blue, Color.Gray) }
            assertEquals(s, done.text)
        }
    }

    @Test fun markdownAroundLocalTextKeepsTheText() {
        val yo = SAMPLES["yo"]!!; val am = SAMPLES["am"]!!; val ha = SAMPLES["ha"]!!
        val r = Md.render("## $am\n- **$yo**\n- *$ha*", Color.Blue, Color.Gray).text
        assertTrue(r.contains(am)); assertTrue(r.contains(yo)); assertTrue(r.contains(ha))
        assertFalse(r.contains("**")); assertFalse(r.contains("##"))
    }

    @Test fun theChatFileRoundTripsEveryLanguage() {
        val msgs = SAMPLES.values.mapIndexed { i, s -> ChatMessage(i + 1L, if (i % 2 == 0) Role.USER else Role.BLAZE, s, at = 10L + i) }
        val head = JSONObject().put("id", "main").put("title", "Blaze").put("pinned", false).put("updated", 9L)
        val file = ChatJson.file(head, ChatJson.messages(msgs, emptyMap()), JSONArray())
        val back = ChatJson.parse(JSONObject(file)).msgs
        assertEquals(msgs.map { it.text }, back.map { it.text })
    }

    @Test fun promptLinesAreStableAndNamedInEnglish() {
        assertEquals("auto", ReplyLanguage.option(null).code)
        assertEquals("auto", ReplyLanguage.option("xx").code)
        val auto = ReplyLanguage.line("auto")
        assertTrue(auto.contains("language of the user's latest message"))
        assertTrue(auto.contains("Ge'ez") && auto.contains("ɓ ɗ ƙ ƴ") && auto.contains("ẹ ọ ṣ"))
        for (o in ReplyLanguage.OPTIONS.drop(1)) {
            val l = ReplyLanguage.line(o.code)
            assertTrue(o.code, l.contains("Always reply in ${o.english}"))
            assertEquals("stable for ${o.code}", l, ReplyLanguage.line(o.code))
        }
        assertEquals(11, ReplyLanguage.OPTIONS.size)
        assertEquals(listOf("Auto (match me)", "English", "Kiswahili", "ChiBemba", "ChiNyanja", "Hausa", "Yorùbá", "አማርኛ", "Français", "Português", "العربية"),
            ReplyLanguage.OPTIONS.map { it.label })
        // Scripts Inter can't draw switch the UI to the system font.
        for (c in listOf("ha", "yo", "am", "ar", "ny")) assertTrue(c, ReplyLanguage.systemFont(c))
        for (c in listOf("auto", "en", "sw", "bem", "fr", "pt")) assertFalse(c, ReplyLanguage.systemFont(c))
    }

    @Test fun promptBuildingKeepsTheUserTextAndPersonaFirst() {
        // Request shape (Agent.requestMessages): the shared persona first, byte-identical; the app context with the
        // language line second; the user's words last, unchanged on the wire.
        val asset = listOf(java.io.File("src/main/assets/persona-v1.json"), java.io.File("app/src/main/assets/persona-v1.json")).first { it.exists() }
        val persona = SharedPersona.parse(asset.readText())!!
        assertFalse(persona.system.contains("LANGUAGE:"))
        for ((lang, s) in SAMPLES) for (code in listOf("auto", lang.takeIf { ReplyLanguage.option(it).code == it } ?: "en")) {
            val req = JSONArray().put(JSONObject().put("role", "system").put("content", persona.system))
                .put(JSONObject().put("role", "system").put("content", SharedPersona.contextLine("Ada", "") + ReplyLanguage.line(code)))
                .put(JSONObject().put("role", "user").put("content", s))
            val wire = JSONArray(req.toString())
            assertEquals(persona.system, wire.getJSONObject(0).getString("content"))
            assertTrue(wire.getJSONObject(1).getString("content").contains("LANGUAGE:"))
            assertEquals("$lang/$code", s, wire.getJSONObject(2).getString("content"))
        }
    }

    @Test fun fontCoverageMatchesTheBundledInter() {
        assertFalse(Scripts.needsSystemFont("Hello, how are you? Café déjà vu – “quotes” … €5"))
        assertFalse(Scripts.needsSystemFont(SAMPLES["sw"]!!))
        assertFalse(Scripts.needsSystemFont(SAMPLES["bem"]!!))
        assertFalse(Scripts.needsSystemFont(SAMPLES["fr"]!!))
        assertFalse(Scripts.needsSystemFont(SAMPLES["pt"]!!))
        assertFalse("emoji are drawn by the emoji font", Scripts.needsSystemFont(SAMPLES["emoji"]!!))
        for (l in listOf("ha", "yo", "am", "ar", "ny")) assertTrue(l, Scripts.needsSystemFont(SAMPLES[l]!!))
        // Decomposed Yorùbá on plain letters is covered (Inter has U+0300, U+0301, U+0323), precomposed ẹ is not.
        assertFalse(Scripts.needsSystemFont("e\u0323\u0301"))
        assertTrue(Scripts.needsSystemFont("ẹ"))
    }

    @Test fun cuttingNeverSplitsALetterOrAnEmoji() {
        for ((lang, s) in SAMPLES) {
            val b = Scripts.boundaries(s).toSet()
            for (n in 1..s.length) {
                val cut = Scripts.safeCut(s, n)
                assertTrue("$lang cut $n -> $cut", cut in b && cut <= n)
                val c = Scripts.clip(s, n)
                assertTrue("$lang clip $n", c.length <= maxOf(n, 1))
                val body = c.removeSuffix("\u2026")
                assertTrue("$lang clip is a prefix", s.startsWith(body))
                assertTrue("$lang clip ends on a boundary", body.length in b || body.trimEnd().length in b)
            }
            assertEquals(s, Scripts.clip(s, s.length))
            // The streaming fade: spans cover the tail without overlap, each on boundaries.
            val spans = Scripts.fadeSpans(s, 4, 28)
            var end = s.length
            for ((a, z) in spans) { assertEquals(end, z); assertTrue(a in b && a < z); end = a }
        }
        // The Yorùbá ọ̀ (o + U+0323 + U+0300) stays whole.
        val yo = "o\u0323\u0300la"
        assertEquals(0, Scripts.safeCut(yo, 1)); assertEquals(0, Scripts.safeCut(yo, 2)); assertEquals(3, Scripts.safeCut(yo, 3))
        // A family emoji (ZWJ sequence) is one letter.
        val fam = "👨\u200d👩\u200d👧x"
        assertEquals(0, Scripts.safeCut(fam, 4)); assertEquals(fam.length - 1, Scripts.safeCut(fam, fam.length - 1))
        // ASCII stays exactly as before.
        assertEquals(listOf(10 to 14, 6 to 10, 2 to 6, 0 to 2), Scripts.fadeSpans("abcdefghijklmn", 4, 28))
    }

    @Test fun chipSnippetsAndThreadSummariesKeepWholeLetters() {
        val yo = SAMPLES["yo"]!!.repeat(3)
        val sn = RapidMessages.snippet(yo)
        val b = Scripts.boundaries(yo).toSet()
        assertTrue(sn.endsWith("\u2026"))
        assertTrue(sn.removeSuffix("\u2026").trimEnd().length in b || sn.removeSuffix("\u2026").length in b)
    }

    @Test fun whatHappenedKeepsTheLongestPause() {
        val t = TurnTrace.build(mapOf("x-request-id" to "abc"), "Blaze Cloud", "agent", listOf("abc"), 900, 400, 5000, 1, emptyList(), emptyList(), 2300)
        val back = TurnTrace.fromJson(TurnTrace.toJson(t))!!
        assertEquals(2300L, back.maxGapMs)
        assertEquals("2.30 s", TurnTrace.rows(back).first { it.first == "Longest pause" }.second)
        assertTrue(TurnTrace.rows(TurnTrace.build(emptyMap(), "", "", emptyList(), 1, 1, 1, 1, emptyList(), emptyList())).none { it.first == "Longest pause" })
        // An older saved trace (no "gap") still reads.
        assertEquals(0L, TurnTrace.fromJson("{\"model\":\"m\"}")!!.maxGapMs)
    }
}
