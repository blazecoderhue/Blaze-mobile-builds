package com.blaze.assistant.media

import com.blaze.assistant.media.GalleryModel.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** v0.34.0 Gallery: duplicates, blur, On this day, Trash, backup plan, pinch columns, paging and the editor maths. */
class GalleryToolsTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun gradient(w: Int, h: Int, flip: Boolean = false) = IntArray(w * h) { i -> val x = i % w; val v = if (flip) 255 - x * 255 / w else x * 255 / w; v }
    private fun checker(w: Int, h: Int) = IntArray(w * h) { i -> if (((i % w) / 4 + (i / w) / 4) % 2 == 0) 0 else 255 }

    @Test fun greyFromArgb() {
        assertEquals(listOf(255, 0, 76), GalleryTools.grey(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt())).toList())
    }

    @Test fun dHashFindsNearDuplicatesNotOpposites() {
        val a = GalleryTools.dHash(gradient(64, 64), 64, 64)
        val b = GalleryTools.dHash(gradient(64, 64).map { (it + 3).coerceAtMost(255) }.toIntArray(), 64, 64)
        val c = GalleryTools.dHash(gradient(64, 64, flip = true), 64, 64)
        assertTrue(GalleryTools.hamming(a, b) <= 2)
        assertTrue(GalleryTools.hamming(a, c) >= 40)
        assertEquals(0L, GalleryTools.dHash(IntArray(4), 2, 2))
    }

    @Test fun sharpnessSeparatesBlur() {
        val sharp = GalleryTools.sharpness(checker(64, 64), 64, 64)
        val flat = GalleryTools.sharpness(IntArray(64 * 64) { 128 }, 64, 64)
        val soft = GalleryTools.sharpness(gradient(64, 64), 64, 64)
        assertTrue(sharp > 1000); assertEquals(0.0, flat, 1e-9); assertTrue(soft < GalleryTools.BLUR_LIMIT)
    }

    @Test fun duplicateGroupsKeepTheSharpest() {
        val s = listOf(GalleryTools.Scan("a", 0b1111, 100.0, 10, 1), GalleryTools.Scan("b", 0b1110, 300.0, 20, 2),
            GalleryTools.Scan("c", -1L, 50.0, 5, 3), GalleryTools.Scan("d", 0b0111, 10.0, 30, 4), GalleryTools.Scan("e", 0L, 0.0, 1, 5))
        val g = GalleryTools.duplicates(s, 2)
        assertEquals(1, g.size); assertEquals(listOf("b", "a", "d"), g[0].map { it.id })
        assertEquals(40L, GalleryTools.savings(g))
        assertEquals(listOf("e", "d"), GalleryTools.blurry(s).map { it.id })
    }

    private fun at(y: Int, m: Int, d: Int) = Calendar.getInstance(utc).apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis
    private fun e(id: String, t: Long, kind: String = "image", size: Long = 0) = Entry(id, "$id.jpg", id, kind, "pictures", t, size)

    @Test fun onThisDay() {
        val now = at(2026, 10, 18)
        val list = listOf(e("a", at(2024, 10, 18)), e("b", at(2025, 10, 18)), e("c", at(2025, 10, 17)), e("d", at(2026, 10, 18)), e("s", at(2023, 10, 18), "audio"))
        val r = GalleryTools.onThisDay(list, now, utc)
        assertEquals(listOf(2025, 2024), r.map { it.first }); assertEquals("b", r[0].second[0].id)
        assertEquals("2 years ago", GalleryTools.yearsAgo(2024, now, utc)); assertEquals("1 year ago", GalleryTools.yearsAgo(2025, now, utc))
    }

    @Test fun trashRules() {
        val day = 86_400_000L
        val t = GalleryTools.Trashed("f", "o", "t", 0)
        assertEquals(30, GalleryTools.daysLeft(t, 0)); assertEquals(1, GalleryTools.daysLeft(t, 29 * day)); assertEquals(0, GalleryTools.daysLeft(t, 40 * day))
        assertEquals(listOf(t), GalleryTools.expired(listOf(t, t.copy(at = 20 * day)), 30 * day))
        assertEquals(listOf(t), GalleryTools.trashParse(GalleryTools.trashJson(listOf(t))))
        assertTrue(GalleryTools.trashParse("x").isEmpty())
    }

    @Test fun backupPlan() {
        val now = at(2026, 10, 18)
        val list = listOf(e("p", now - 1000, size = 2_000_000_000), e("v", now - 1000, "video", 10_000_000_000), e("m", at(2020, 1, 1), "audio", 1_000_000_000))
        val p = GalleryTools.plan(list, now)
        assertEquals(13_000_000_000L, p.totalBytes); assertEquals(2_000_000_000L, p.perMonth)
        assertTrue(p.lines[0].startsWith("Free 15 GB")); assertTrue(p.lines[0].contains("fits")); assertTrue(p.lines[0].contains("1 month of"))
        assertTrue(p.lines.last().startsWith("Videos are most of it"))
        assertEquals("v", p.biggest.first().id)
    }

    @Test fun pinchAndPaging() {
        assertEquals(2, GalleryTools.columnsAfterPinch(3, 1.5f)); assertEquals(4, GalleryTools.columnsAfterPinch(3, 0.5f))
        assertEquals(2, GalleryTools.columnsAfterPinch(2, 2f)); assertEquals(6, GalleryTools.columnsAfterPinch(6, 0.3f)); assertEquals(3, GalleryTools.columnsAfterPinch(3, 1.1f))
        assertEquals(listOf(0 to 600, 600 to 2000, 2600 to 2000, 4600 to 400), GalleryTools.pages(5000))
        assertEquals(listOf(0 to 100), GalleryTools.pages(100)); assertTrue(GalleryTools.pages(0).isEmpty())
    }

    @Test fun editorMaths() {
        val id = GalleryTools.colorMatrix(0f, 1f, 1f)
        assertEquals(1f, id[0], 1e-5f); assertEquals(0f, id[1], 1e-5f); assertEquals(0f, id[4], 1e-5f); assertEquals(1f, id[18], 1e-5f)
        val grey = GalleryTools.colorMatrix(0f, 1f, 0f)
        assertEquals(0.213f, grey[0], 1e-5f); assertEquals(0.213f, grey[5], 1e-5f)
        assertEquals(255f * 0.5f, GalleryTools.colorMatrix(0.5f, 1f, 1f)[4], 1e-3f)
        assertEquals(-64f, GalleryTools.colorMatrix(0f, 1.5f, 1f)[4], 1e-3f)
        assertEquals(listOf(200, 0, 600, 600), GalleryTools.centerCrop(1000, 600, 1f).toList())
        assertEquals(listOf(0, 0, 1000, 600), GalleryTools.centerCrop(1000, 600, 0f).toList())
        assertEquals(listOf(0, 219, 1000, 562), GalleryTools.centerCrop(1000, 1000, 16f / 9f).toList())
    }
}
