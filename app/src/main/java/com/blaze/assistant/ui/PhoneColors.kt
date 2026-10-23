package com.blaze.assistant.ui

/**
 * 0.45.19 Blaze Phone UI fix 3 (contrast). The tile's step chip was white text on the light "blue" (#E6E6EA) fill,
 * unreadable on every theme. Every colour the Blaze Phone tile and the Blaze Phone screen draw text or icons with now
 * comes from one [Palette], made from the theme's accent and light / dark, and every text / ground pair in it meets
 * WCAG AA for normal text (contrast >= 4.5:1). Colours are opaque ARGB longs (no alpha over a moving background), so
 * this file needs no Android or Compose and PhoneContrastTest computes the real ratio for each theme x mode.
 */
object PhoneColors {
    /** WCAG AA, normal-size text. */
    const val AA = 4.5
    /** [StatusPill]'s default pair (the desktop cards): the old light fill with dark ink instead of white. */
    const val STATUS_CHIP = 0xFFE6E6EAL
    const val STATUS_ON_CHIP = 0xFF0E0E10L

    data class Palette(
        val dark: Boolean,
        /** The tile's own opaque ground (it floats over petals, embers, rain…). */
        val card: Long, val cardBorder: Long,
        /** "Blaze Phone · host" and secondary lines on the card. */
        val title: Long, val sub: Long,
        /** The step chip ("Reading Blaze Phone's page") and its dot. */
        val chip: Long, val onChip: Long,
        /** Quiet button (Take control while Blaze works) and the close disc. */
        val button: Long, val onButton: Long,
        /** Strong button (Take control when Blaze needs you, Hand back). */
        val strong: Long, val onStrong: Long,
        /** The full screen: ground, title, host line, bottom bar, address field. */
        val screen: Long, val screenText: Long, val screenSub: Long,
        val bar: Long, val barText: Long,
        val field: Long, val fieldText: Long,
        /** The page frame (web pages are white) and the text drawn on it (placeholder, "Live view isn't available"). */
        val frame: Long, val frameText: Long,
    ) {
        /** Every text-on-ground pair this palette is drawn with (name to foreground / background). */
        fun pairs(): Map<String, Pair<Long, Long>> = linkedMapOf(
            "tile title" to (title to card), "tile sub" to (sub to card),
            "tile chip" to (onChip to chip),
            "tile button" to (onButton to button), "tile close" to (onButton to button),
            "tile strong button" to (onStrong to strong),
            "screen title" to (screenText to screen), "screen host" to (screenSub to screen),
            "screen bar text" to (barText to bar), "screen bar chip" to (onChip to chip),
            "screen bar button" to (onStrong to strong), "screen icon button" to (onButton to button),
            "address field" to (fieldText to field),
            "frame text" to (frameText to frame),
            "pip label" to (onChip to chip),
        )
    }

    /** The palette for [accent] (the app's accent for the picked theme; null = Classic with no accent) on [dark]. */
    fun of(accent: Long?, dark: Boolean): Palette {
        val a = opaque(accent ?: if (dark) 0xFFE6E6EAL else 0xFF2A2A2EL)
        return if (dark) {
            val card = 0xFF141416L
            val chip = chipFor(a, card, dark = true)
            Palette(true,
                card = card, cardBorder = 0xFF2E2E33L,
                title = 0xFFF2F2F3L, sub = ensure(0xFFA9A9AEL, card),
                chip = chip, onChip = bestText(chip),
                button = 0xFF2C2C31L, onButton = 0xFFF2F2F3L,
                strong = 0xFFF2F2F3L, onStrong = 0xFF0E0E10L,
                screen = 0xFF0A0A0CL, screenText = 0xFFF2F2F3L, screenSub = ensure(0xFFA9A9AEL, 0xFF0A0A0CL),
                bar = card, barText = 0xFFF2F2F3L,
                field = 0xFF222226L, fieldText = 0xFFF2F2F3L,
                frame = 0xFFFFFFFFL, frameText = 0xFF4A4A4FL)
        } else {
            val card = 0xFFFFFFFFL
            val chip = chipFor(a, card, dark = false)
            Palette(false,
                card = card, cardBorder = 0xFFDADADEL,
                title = 0xFF111112L, sub = ensure(0xFF5E5E63L, card),
                chip = chip, onChip = bestText(chip),
                button = 0xFFEDEDF0L, onButton = 0xFF111112L,
                strong = 0xFF111112L, onStrong = 0xFFFFFFFFL,
                screen = 0xFFF4F4F5L, screenText = 0xFF111112L, screenSub = ensure(0xFF5E5E63L, 0xFFF4F4F5L),
                bar = card, barText = 0xFF111112L,
                field = 0xFFE9E9ECL, fieldText = 0xFF111112L,
                frame = 0xFFFFFFFFL, frameText = 0xFF4A4A4FL)
        }
    }

    /**
     * The chip fill: the theme's accent, moved toward the card's ground until it stands apart from it, then moved
     * the other way (lighter on dark, deeper on light) until black or white text on it reads at [AA] or better.
     */
    fun chipFor(accent: Long, card: Long, dark: Boolean): Long {
        var c = opaque(accent)
        // On light cards a pale accent is deepened; on dark cards a deep accent is lifted, so the chip reads as a chip.
        val toward = if (dark) 0xFFFFFFFFL else 0xFF000000L
        var i = 0
        while (contrast(c, card) < 1.6 && i < 20) { c = mix(c, toward, 0.12f); i++ }
        i = 0
        while (contrast(bestText(c), c) < AA && i < 40) { c = mix(c, if (luminance(c) > 0.18) 0xFFFFFFFFL else 0xFF000000L, 0.08f); i++ }
        return c
    }

    /** Black-ish or white text, whichever reads better on [bg]. */
    fun bestText(bg: Long): Long = if (contrast(0xFF0E0E10L, bg) >= contrast(0xFFFFFFFFL, bg)) 0xFF0E0E10L else 0xFFFFFFFFL

    /** [fg] moved toward black or white (whichever is further from [bg]) until it reads at [AA] on [bg]. */
    fun ensure(fg: Long, bg: Long): Long {
        var c = opaque(fg)
        val toward = if (bestText(bg) == 0xFF0E0E10L) 0xFF000000L else 0xFFFFFFFFL
        var i = 0
        while (contrast(c, bg) < AA && i < 40) { c = mix(c, toward, 0.1f); i++ }
        return c
    }

    // ------------------------------------------------------------------ WCAG 2.x maths
    fun opaque(c: Long): Long = (c and 0xFFFFFFL) or 0xFF000000L

    private fun channel(v: Long): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    /** Relative luminance of an opaque colour (alpha ignored). */
    fun luminance(c: Long): Double =
        0.2126 * channel((c shr 16) and 0xFF) + 0.7152 * channel((c shr 8) and 0xFF) + 0.0722 * channel(c and 0xFF)

    /** WCAG contrast ratio, 1.0 to 21.0. */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [a] mixed toward [b] by [t] (0..1), opaque. */
    fun mix(a: Long, b: Long, t: Float): Long {
        fun ch(s: Int): Long {
            val x = (a shr s) and 0xFF; val y = (b shr s) and 0xFF
            return (x + (y - x) * t).toLong().coerceIn(0L, 255L)
        }
        return 0xFF000000L or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** [fg] with its alpha drawn over opaque [bg], as the eye sees it (for checking translucent colours). */
    fun over(fg: Long, bg: Long): Long = mix(bg, opaque(fg), ((fg ushr 24) and 0xFF) / 255f)
}
