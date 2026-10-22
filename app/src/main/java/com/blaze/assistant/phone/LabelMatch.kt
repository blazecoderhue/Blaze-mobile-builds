package com.blaze.assistant.phone

/**
 * v0.30.4 fast actions: how a tap target the model names ("Apps", "Open by default") is matched against the labels
 * really on screen. Before this, a run_plan step only matched plain substrings of the visible words, so Samsung's
 * "Apps" vs Pixel's "Apps & notifications", or "Set as default" / "Opening links" vs "Open by default", failed the
 * whole macro at its first tap. Now: normalised words, OEM synonyms, a fuzzy fallback for small spelling
 * differences, and two safety rules: a negated label ("Don't allow") never matches a plain target ("Allow") and the
 * other way round, and a destructive label ("Uninstall", "Force stop", "Clear data") only matches when the target
 * itself says so. Pure (LabelMatchTest).
 */
object LabelMatch {

    /** Lowercase words only: quotes, "&", dashes and punctuation folded ("Apps & notifications" → "apps and notifications"). */
    fun norm(s: String): String = s.lowercase()
        .replace('\u2019', '\'').replace('\u2018', '\'').replace('\u2011', '-').replace('\u2010', '-')
        .replace("&", " and ").replace("n't", "nt").replace("'", "")
        .replace(nonWord, " ").trim().replace(spaces, " ")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")

    private fun words(s: String) = norm(s).split(' ').filter { it.isNotEmpty() }

    /**
     * The same item under its names on Samsung One UI, Pixel / AOSP, Xiaomi MIUI, Oppo / Realme ColorOS, Huawei, Tecno /
     * Infinix HiOS. Each set is one thing; the first entry is the AOSP name.
     */
    val groups: List<List<String>> = listOf(
        listOf("apps", "apps and notifications", "applications", "app management", "manage apps", "application manager", "installed apps", "apps and permissions", "app settings"),
        listOf("open by default", "set as default", "opening links", "open supported links", "supported links", "app links", "open links", "default links", "links", "open in app", "go to supported urls"),
        listOf("default apps", "choose default apps", "default applications", "default app selection"),
        listOf("notifications", "app notifications", "notification settings", "manage notifications", "notifications and status bar"),
        listOf("permissions", "app permissions", "permission manager", "permission"),
        listOf("battery", "battery usage", "battery and device care", "device care", "power", "battery and performance", "app battery usage", "battery optimisation", "battery optimization"),
        listOf("storage", "storage and cache", "storage usage"),
        listOf("mobile data", "mobile data and wi fi", "data usage", "mobile data usage", "network access", "data saver"),
        listOf("network and internet", "connections", "wi fi", "wifi", "wlan", "wireless and networks", "sim cards and mobile networks"),
        listOf("connected devices", "bluetooth", "connection and sharing", "device connectivity"),
        listOf("display", "display and brightness", "screen", "brightness", "display and text size"),
        listOf("sound", "sounds and vibration", "sound and vibration", "sounds"),
        listOf("location", "location services", "gps"),
        listOf("security", "security and privacy", "biometrics and security", "password and security", "privacy"),
        listOf("about phone", "about device", "my phone", "about"),
        listOf("system", "general management", "additional settings", "system settings"),
        listOf("search", "search settings", "search apps"),
        listOf("more options", "more", "options", "menu", "overflow"),
        listOf("dont allow", "do not allow", "deny", "never allow", "block"),
        listOf("allow", "always allow", "permit", "allow all the time"),
        listOf("ok", "okay", "got it", "done"),
        listOf("cancel", "not now", "no thanks", "later", "skip"),
    ).map { g -> g.map { norm(it) } }

    private val negation = Regex("\\b(dont|do not|never|deny|disallow|block|not)\\b")
    private val destructive = Regex("\\b(uninstall|delete|remove|erase|reset|wipe|clear data|clear storage|force stop|factory|pay|buy|purchase|send|disable app)\\b")

    /** The other names of [target] (normalised), without [target] itself. */
    fun synonyms(target: String): List<String> {
        val t = norm(target); if (t.isEmpty()) return emptyList()
        return groups.filter { t in it }.flatten().filter { it != t }.distinct()
    }

    /** Levenshtein distance, small strings only (labels). */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun stem(w: String) = if (w.length > 4 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w

    /** A target prepared once (normalised, its words, flags, synonyms) and matched against many labels. */
    class Target(raw: String, withSynonyms: Boolean = true) {
        val t = norm(raw)
        val tw = t.split(' ').filter { it.isNotEmpty() }
        val ts = tw.map { stem(it) }.toSet()
        val neg = negation.containsMatchIn(t)
        val bad = destructive.containsMatchIn(t)
        val flat = t.replace(" ", "")
        val syns: List<Target> = if (withSynonyms) synonyms(raw).map { Target(it, false) } else emptyList()
    }

    private fun direct(p: Target, l: String): Int {
        val t = p.t
        if (t.isEmpty() || l.isEmpty()) return 0
        if (p.neg != negation.containsMatchIn(l)) return 0
        if (!p.bad && destructive.containsMatchIn(l)) return 0
        if (l == t) return 100
        val lw = l.split(' ')
        // Label starts with / contains the target as whole words ("Open by default" in "Open by default, Allow app to open…").
        if (" $l ".contains(" $t ")) return (90 - minOf(25, (lw.size - p.tw.size) * 3)).coerceAtLeast(60)
        val ls = lw.map { stem(it) }.toSet()
        if (ls.containsAll(p.ts)) return (78 - minOf(20, (ls.size - p.ts.size) * 3)).coerceAtLeast(55)
        // The target says more than the label ("Tap Apps settings" → "Apps"), label at least 3 letters.
        if (l.length >= 3 && lw.size * 2 >= p.tw.size && " $t ".contains(" $l ")) return 58
        // Plain substring of a longer word ("Search" in "Searchbar"), 3+ letters.
        if (t.length >= 3 && l.contains(t)) return 52
        // Small spelling differences ("Wi-Fi" / "WiFi", "Notifcations"); only worth computing when lengths are close.
        val a = p.flat; val b = l.replace(" ", "")
        if (a.length >= 4 && b.length >= 4 && kotlin.math.abs(a.length - b.length) * 5 <= maxOf(a.length, b.length)) {
            val ratio = 1.0 - distance(a, b).toDouble() / maxOf(a.length, b.length)
            if (ratio >= 0.8) return (ratio * 70).toInt()
        }
        val j = (p.ts intersect ls).size.toDouble() / (p.ts union ls).size
        return if (j >= 0.5) (j * 60).toInt() else 0
    }

    /** 0..100: how well on-screen [label] matches the [target] the model asked for (target's own words, no synonyms). */
    fun direct(target: String, label: String): Int = direct(Target(target, false), norm(label))

    /** [direct] for the prepared target or any of its synonyms (a synonym hit counts a little less than the model's own words). */
    fun score(p: Target, label: String): Int {
        val l = norm(label)
        var best = direct(p, l)
        if (best >= 100) return best
        for (s in p.syns) {
            // A one-word synonym ("links", "power", "more") only counts on a short label that is or starts with it,
            // so it can't pull in any long row that happens to contain the word.
            val st = s.t
            val hit = if (' ' !in st) { if (l == st || (l.startsWith("$st ") && l.split(' ').size <= 4)) direct(s, l) else 0 } else direct(s, l)
            best = maxOf(best, hit * 9 / 10)
        }
        return best
    }

    fun score(target: String, label: String): Int = score(Target(target), label)

    const val MIN = 50

    /** Index of the label in [labels] that best matches [target] (score ≥ [min]); ties go to the shorter label. */
    fun best(target: String, labels: List<String>, min: Int = MIN): Int? {
        var bi = -1; var bs = 0; var bl = Int.MAX_VALUE
        val p = Target(target)
        labels.forEachIndexed { i, l ->
            val s = score(p, l)
            if (s > bs || (s == bs && s > 0 && l.length < bl)) { bi = i; bs = s; bl = l.length }
        }
        return bi.takeIf { it >= 0 && bs >= min }
    }
}
