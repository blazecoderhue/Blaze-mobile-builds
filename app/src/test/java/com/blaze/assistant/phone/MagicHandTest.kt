package com.blaze.assistant.phone

import com.blaze.assistant.phone.MagicHandLogic.Action
import com.blaze.assistant.phone.MagicHandLogic.Gesture
import com.blaze.assistant.phone.MagicHandLogic.Intent
import com.blaze.assistant.phone.MagicHandLogic.Kind
import com.blaze.assistant.phone.MagicHandLogic.Mode
import com.blaze.assistant.phone.MagicHandLogic.P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Magic Hand (v0.24.7): gestures → intents → actions, loop bounds, teach steps → workflow, watch diffs. */
class MagicHandTest {
    private val d = 2f

    private fun circle(cx: Float, cy: Float, r: Float, turns: Float = 1f, n: Int = 40): List<P> =
        (0..n).map { i -> val a = 2 * Math.PI * turns * i / n; P((cx + r * Math.cos(a)).toFloat(), (cy + r * Math.sin(a)).toFloat(), i * 16L) }

    // ---------- gestures ----------
    @Test fun tapHoldDoubleTap() {
        assertEquals(Gesture.TAP, MagicHandLogic.classify(listOf(P(100f, 100f, 1000), P(102f, 101f, 1080)), d))
        assertEquals(Gesture.HOLD, MagicHandLogic.classify(listOf(P(100f, 100f, 1000), P(104f, 100f, 1700)), d))
        assertEquals(Gesture.DOUBLE_TAP, MagicHandLogic.classify(listOf(P(100f, 100f, 1200), P(100f, 100f, 1260)), d, prevTapUpAt = 1000))
        // Too slow for a double-tap: just a tap.
        assertEquals(Gesture.TAP, MagicHandLogic.classify(listOf(P(100f, 100f, 2000), P(100f, 100f, 2060)), d, prevTapUpAt = 1000))
        assertEquals(Gesture.NONE, MagicHandLogic.classify(emptyList(), d))
    }

    @Test fun dragVersusLoop() {
        val line = (0..30).map { P(100f + it * 10, 400f, it * 16L) }
        assertEquals(Gesture.DRAG, MagicHandLogic.classify(line, d))
        assertEquals(Gesture.LOOP, MagicHandLogic.classify(circle(300f, 600f, 150f), d))
        // A half circle isn't closed.
        assertEquals(Gesture.DRAG, MagicHandLogic.classify(circle(300f, 600f, 150f, turns = 0.5f), d))
        // A tiny wobble isn't a loop (under 48 dp).
        assertFalse(MagicHandLogic.isLoop(circle(300f, 600f, 30f), d))
        // An almost-closed loop still counts (≥ ~300°).
        assertTrue(MagicHandLogic.isLoop(circle(300f, 600f, 150f, turns = 0.9f), d))
    }

    @Test fun gestureToIntent() {
        assertEquals(Intent.HIGHLIGHT, MagicHandLogic.intentFor(Gesture.DRAG, Mode.POINT, false, Kind.BUTTON))
        assertEquals(Intent.MENU, MagicHandLogic.intentFor(Gesture.DOUBLE_TAP, Mode.POINT, false, Kind.TEXT))
        assertEquals(Intent.DROP, MagicHandLogic.intentFor(Gesture.DOUBLE_TAP, Mode.POINT, true, Kind.FIELD))
        assertEquals(Intent.MENU, MagicHandLogic.intentFor(Gesture.DOUBLE_TAP, Mode.POINT, true, Kind.TEXT))
        assertEquals(Intent.NONE, MagicHandLogic.intentFor(Gesture.DOUBLE_TAP, Mode.POINT, false, Kind.SECRET))
        assertEquals(Intent.WATCH, MagicHandLogic.intentFor(Gesture.HOLD, Mode.POINT, false, Kind.VALUE))
        assertEquals(Intent.MENU, MagicHandLogic.intentFor(Gesture.HOLD, Mode.POINT, false, Kind.IMAGE))
        assertEquals(Intent.NONE, MagicHandLogic.intentFor(Gesture.HOLD, Mode.POINT, false, Kind.SECRET))
        assertEquals(Intent.GRAB, MagicHandLogic.intentFor(Gesture.LOOP, Mode.POINT, false, Kind.EMPTY))
        assertEquals(Intent.NONE, MagicHandLogic.intentFor(Gesture.TAP, Mode.POINT, false, Kind.BUTTON))
        // Teach: a double-tap asks to tap for the user (confirmed first); loops and holds don't capture.
        assertEquals(Intent.TEACH_TAP, MagicHandLogic.intentFor(Gesture.DOUBLE_TAP, Mode.TEACH, false, Kind.BUTTON))
        assertEquals(Intent.NONE, MagicHandLogic.intentFor(Gesture.DOUBLE_TAP, Mode.TEACH, false, Kind.EMPTY))
        assertEquals(Intent.HIGHLIGHT, MagicHandLogic.intentFor(Gesture.LOOP, Mode.TEACH, false, Kind.EMPTY))
        assertEquals(Intent.NONE, MagicHandLogic.intentFor(Gesture.HOLD, Mode.TEACH, false, Kind.VALUE))
    }

    // ---------- element → actions ----------
    @Test fun kinds() {
        assertEquals(Kind.SECRET, MagicHandLogic.kindOf(MagicHandLogic.Element(text = "••••", editable = true, password = true)))
        assertEquals(Kind.FIELD, MagicHandLogic.kindOf(MagicHandLogic.Element(className = "android.widget.EditText", editable = true)))
        assertEquals(Kind.LINK, MagicHandLogic.kindOf(MagicHandLogic.Element(text = "https://blaze.ai/x")))
        assertEquals(Kind.IMAGE, MagicHandLogic.kindOf(MagicHandLogic.Element(className = "android.widget.ImageView", desc = "Product photo")))
        assertEquals(Kind.VALUE, MagicHandLogic.kindOf(MagicHandLogic.Element(text = "K1,299.00", className = "android.widget.TextView")))
        assertEquals(Kind.VALUE, MagicHandLogic.kindOf(MagicHandLogic.Element(text = "2 - 1")))
        assertEquals(Kind.VALUE, MagicHandLogic.kindOf(MagicHandLogic.Element(text = "Out of stock")))
        assertEquals(Kind.BUTTON, MagicHandLogic.kindOf(MagicHandLogic.Element(text = "Add to cart", className = "android.widget.Button", clickable = true)))
        assertEquals(Kind.TEXT, MagicHandLogic.kindOf(MagicHandLogic.Element(text = "The quick brown fox jumps over the lazy dog", className = "android.widget.TextView")))
        assertEquals(Kind.EMPTY, MagicHandLogic.kindOf(MagicHandLogic.Element()))
    }

    @Test fun actionsPerKind() {
        val text = MagicHandMenu.actions(Kind.TEXT, false)
        assertEquals(Action.ASK, text.first())
        assertTrue(text.containsAll(listOf(Action.COPY, Action.EXPLAIN, Action.TRANSLATE, Action.SHARE, Action.REMIND)))
        assertTrue(text.size <= MagicHandLogic.MAX_ACTIONS)
        assertTrue(MagicHandMenu.actions(Kind.LINK, false).containsAll(listOf(Action.OPEN_LINK, Action.COPY_LINK)))
        assertTrue(Action.SAVE_IMAGE in MagicHandMenu.actions(Kind.IMAGE, false))
        assertTrue(Action.WATCH in MagicHandMenu.actions(Kind.VALUE, false))
        assertEquals(Action.DROP, MagicHandMenu.actions(Kind.FIELD, true).first())
        assertTrue(Action.DROP_IN_CHAT in MagicHandMenu.actions(Kind.TEXT, true))
        assertTrue(MagicHandMenu.actions(Kind.SECRET, false).isEmpty())
        for (k in Kind.values()) for (c in listOf(true, false)) assertTrue(MagicHandMenu.actions(k, c).size <= MagicHandLogic.MAX_ACTIONS)
    }

    @Test fun adapterKeepsHandActions() {
        // A shared mapper (like the browser's) that only knows generic actions still gets Carry / Watch / Drop.
        val old = MagicHandMenu.mapper
        try {
            MagicHandMenu.mapper = object : ElementActionMapper {
                override fun actionsFor(kind: Kind, carrying: Boolean) = listOf(Action.COPY, Action.SHARE, Action.ASK, Action.EXPLAIN, Action.TRANSLATE, Action.REMIND, Action.SEARCH, Action.OPEN_LINK, Action.COPY_LINK)
            }
            val v = MagicHandMenu.actions(Kind.VALUE, false)
            assertEquals(Action.ASK, v.first()); assertTrue(Action.WATCH in v); assertTrue(Action.CARRY in v)
            assertTrue(v.size <= MagicHandLogic.MAX_ACTIONS)
            assertEquals(Action.DROP, MagicHandMenu.actions(Kind.FIELD, true).first())
        } finally { MagicHandMenu.mapper = old }
    }

    @Test fun ringPositions() {
        val r = MagicHandLogic.ring(4, 100f, 100f, 50f)
        assertEquals(100f, r[0].first, 0.01f); assertEquals(50f, r[0].second, 0.01f)   // top first
        assertEquals(150f, r[1].first, 0.01f); assertEquals(100f, r[1].second, 0.01f)  // then clockwise (right)
    }

    // ---------- loop bounds ----------
    @Test fun loopBoundsClampAndPad() {
        val b = MagicHandLogic.loopBounds(circle(300f, 600f, 150f), 720, 1560, padPx = 10)!!
        assertEquals(140, b.l); assertEquals(440, b.t); assertEquals(460, b.r); assertEquals(760, b.b)
        // Clamped to the screen at the edges.
        val e = MagicHandLogic.loopBounds(circle(20f, 20f, 100f), 720, 1560, padPx = 10)!!
        assertEquals(0, e.l); assertEquals(0, e.t); assertTrue(e.r <= 720)
        // Too small, empty or off-screen → null.
        assertNull(MagicHandLogic.loopBounds(listOf(P(5f, 5f), P(10f, 10f)), 720, 1560))
        assertNull(MagicHandLogic.loopBounds(emptyList(), 720, 1560))
        assertNull(MagicHandLogic.loopBounds(listOf(P(900f, 2000f), P(1000f, 2100f)), 720, 1560))
    }

    @Test fun cropAroundElement() {
        val c = MagicHandLogic.cropAround(MagicHandLogic.Box(100, 100, 200, 140), 720, 1560, padPx = 40, maxPx = 600)
        assertEquals(60, c.l); assertEquals(60, c.t); assertEquals(240, c.r); assertEquals(180, c.b)
        val big = MagicHandLogic.cropAround(MagicHandLogic.Box(0, 0, 720, 1560), 720, 1560, padPx = 40, maxPx = 600)
        assertEquals(600, big.w); assertEquals(600, big.h)
        val edge = MagicHandLogic.cropAround(MagicHandLogic.Box(700, 1540, 720, 1560), 720, 1560, padPx = 40, maxPx = 600)
        assertTrue(edge.r <= 720 && edge.b <= 1560 && edge.l >= 0 && edge.t >= 0)
    }

    // ---------- teach → workflow ----------
    @Test fun teachRecorderFoldsAndSkipsOwnApp() {
        val r = MagicHandTeach.Recorder("com.blaze.assistant")
        r.window("com.whatsapp", "WhatsApp", "Chats")
        r.window("com.blaze.assistant", "Blaze", "Magic Hand")   // ignored
        r.click("com.whatsapp", "WhatsApp", "New chat", "com.whatsapp:id/fab", "android.widget.ImageButton")
        r.window("com.whatsapp", "WhatsApp", "Select contact")
        r.window("com.whatsapp", "WhatsApp", "Contacts")         // replaces the previous screen step
        r.typed("com.whatsapp", "WhatsApp", "Search", "com.whatsapp:id/search", "Gl", false)
        r.typed("com.whatsapp", "WhatsApp", "Search", "com.whatsapp:id/search", "Gloria", false)  // same field: final text only
        r.typed("com.bank", "Bank", "PIN", "com.bank:id/pin", "1234", true)
        val s = r.steps
        assertEquals(listOf(MagicHandTeach.Kind.OPEN_APP, MagicHandTeach.Kind.TAP, MagicHandTeach.Kind.SCREEN, MagicHandTeach.Kind.TYPE,
            MagicHandTeach.Kind.OPEN_APP, MagicHandTeach.Kind.TYPE), s.map { it.kind })
        assertEquals("Contacts", s[2].screen)
        assertEquals("Gloria", s[3].text)
        assertTrue(s[5].secret); assertEquals("", s[5].text)   // never the password
        r.undo(); assertEquals(5, r.size)
    }

    @Test fun teachToWorkflow() {
        val r = MagicHandTeach.Recorder("com.blaze.assistant")
        r.window("com.android.settings", "Settings", "Settings")
        r.click("com.android.settings", "Settings", "Connections", "com.android.settings:id/title", "android.widget.TextView")
        r.click("com.android.settings", "Settings", "Wi-Fi", "", "android.widget.Switch")
        r.typed("com.android.settings", "Settings", "Password", "com.android.settings:id/password", "hunter2", true)
        val w = MagicHandTeach.toWorkflow(r.steps, "Turn on wifi", "turn on wi-fi")
        assertFalse(w.tested)
        assertEquals("Turn on wifi", w.name); assertEquals("turn on wi-fi", w.trigger)
        val lines = w.steps.lines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("Open Settings (package com.android.settings)"))
        assertTrue(lines[1], lines[1].contains("Tap \u201cConnections\u201d") && lines[1].contains("id title"))
        assertTrue(lines[3].contains("Ask the user for the password")); assertFalse(w.steps.contains("hunter2"))
        assertTrue(w.checks.contains("Passwords were not recorded"))
        assertTrue("taught" in w.tags && "settings" in w.tags)
        // Blank name / trigger get defaults.
        val d2 = MagicHandTeach.toWorkflow(r.steps, "", "")
        assertEquals("Taught: Settings", d2.name); assertTrue(d2.trigger.isNotBlank())
        // And the saved file says tested: false (v0.24.3 workflow format).
        val md = com.blaze.assistant.agent.SkillPacks.workflowMd(w.name, w.trigger, w.steps, w.checks, w.tags, w.tested)
        assertTrue(md.contains("tested: false")); assertTrue(md.contains("workflow: true")); assertTrue(md.contains("## Steps\n1. Open Settings"))
    }

    // ---------- watch diff ----------
    @Test fun watchNumbers() {
        assertEquals(1299.5, MagicHandWatch.number("K 1,299.50")!!, 0.001)
        assertEquals(1299.5, MagicHandWatch.number("1.299,50 €")!!, 0.001)
        assertEquals(12.0, MagicHandWatch.number("12 pts")!!, 0.001)
        assertEquals(1299.0, MagicHandWatch.number("R1 299")!!, 0.001)
        assertNull(MagicHandWatch.number("Sold out"))
    }

    @Test fun watchDiff() {
        val down = MagicHandWatch.diff("K1,299", "K1,099")
        assertTrue(down.changed); assertEquals(-1, down.direction)
        assertEquals("Price went down: K1,299 \u2192 K1,099", down.summary("Price"))
        val score = MagicHandWatch.diff("2 - 1", "2 - 3")
        assertTrue(score.changed); assertEquals(0, score.direction)   // scores have two numbers: no up / down
        assertEquals(1, MagicHandWatch.diff("12 pts", "15 pts").direction)
        // Formatting / whitespace / case noise isn't a change.
        assertFalse(MagicHandWatch.diff("K1,299", " K1,299 ").changed)
        assertFalse(MagicHandWatch.diff("In stock", "in  STOCK").changed)
        assertFalse(MagicHandWatch.diff("K1,299.00", "K1299.00").changed)
        // Status text changes.
        val st = MagicHandWatch.diff("In stock", "Out of stock")
        assertTrue(st.changed); assertEquals(0, st.direction)
        // Can't read it now: not a change.
        val u = MagicHandWatch.diff("K1,299", "")
        assertFalse(u.changed); assertTrue(u.unreadable)
        assertFalse(MagicHandWatch.diff("K1,299", null).changed)
    }

    @Test fun watchApplyDueAndJson() {
        val w = MagicHandWatch.Watch("w1", "com.shop", "Shop", "Price", listOf(0, 2, 1), "com.shop:id/price", "android.widget.TextView", "K100", created = 1L)
        assertTrue(MagicHandWatch.due(w, 2 * MagicHandWatch.MIN_GAP_MS))
        val (same, c0) = MagicHandWatch.apply(w, "K100", 5000L)
        assertFalse(c0.changed); assertEquals(5000L, same.lastChecked); assertEquals("K100", same.value)
        assertFalse(MagicHandWatch.due(same, 5000L + 1000))
        val (nw, c1) = MagicHandWatch.apply(same, "K80", 9000L)
        assertTrue(c1.changed); assertEquals("K80", nw.value); assertEquals("K100", nw.previous); assertEquals(9000L, nw.lastChanged)
        val back = MagicHandWatch.listFromJson(MagicHandWatch.listToJson(listOf(nw)))
        assertEquals(listOf(nw), back)
        assertTrue(MagicHandWatch.listFromJson("not json").isEmpty())
        assertNotNull(MagicHandWatch.fromJson(MagicHandWatch.toJson(w)))
    }

    @Test fun promptsAndPreview() {
        assertTrue(MagicHandLogic.prompt(Action.ASK, "Add to cart", "Takealot").contains("\u201cAdd to cart\u201d (from Takealot)"))
        assertTrue(MagicHandLogic.prompt(Action.TRANSLATE, "Bonjour", "").startsWith("Translate"))
        assertEquals("abc\u2026", MagicHandLogic.preview("abcdef  ghi", 4))
    }

    @Test fun drawingLineStartsAtTheHandsCentre() {
        val p = MagicHandLogic.trailPoint(100, 200, 128, 7L)
        assertEquals(164f, p.x, 0.001f)
        assertEquals(264f, p.y, 0.001f)
        assertEquals(7L, p.t)
        // Moving the window moves the line by the same amount, so loop detection is unchanged.
        val q = MagicHandLogic.trailPoint(150, 260, 128)
        assertEquals(50f, q.x - p.x, 0.001f); assertEquals(60f, q.y - p.y, 0.001f)
    }
}
