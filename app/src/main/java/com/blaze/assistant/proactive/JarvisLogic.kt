package com.blaze.assistant.proactive

import org.json.JSONObject

/**
 * v0.38.0 Jarvis mode, the pure part (JarvisLogicTest). Blaze isn't a chat box that waits: it gets to know the user
 * (a real question now and then, saved to memory from their reply), glances at the screen to offer help in the
 * moment, can take a look through the front camera while Blaze is open, watches app usage, and sets up routines
 * for itself. How much of that happens is the [Level]; screen and camera glances are separate switches on top.
 */
object JarvisLogic {
    enum class Level(val id: String, val label: String) {
        OFF("off", "Off"), GENTLE("gentle", "Gentle"), EAGER("eager", "Eager"), JARVIS("jarvis", "Jarvis");
        companion object { fun of(id: String?): Level = values().firstOrNull { it.id == id } ?: EAGER }
    }

    /**
     * What a level allows. [questionsPerDay] getting-to-know-you questions, at least [questionGapMin] apart;
     * a screen glance every [glanceEveryMin] (when that switch is on); a camera look every [cameraEveryMin];
     * [selfRoutines]: Blaze creates its own routines (true) or only offers them (false); [routineEveryDays].
     */
    data class Limits(val questionsPerDay: Int, val questionGapMin: Int, val glanceEveryMin: Int, val cameraEveryMin: Int,
                      val selfRoutines: Boolean, val routineEveryDays: Int)

    fun limits(l: Level): Limits = when (l) {
        Level.OFF -> Limits(0, 0, 0, 0, false, 0)
        Level.GENTLE -> Limits(1, 240, 60, 0, false, 14)
        Level.EAGER -> Limits(2, 180, 25, 90, false, 7)
        Level.JARVIS -> Limits(4, 120, 12, 45, true, 3)
    }

    /** Questions go out in the day only (never before 9 or after 21, and never in quiet hours). */
    fun questionHour(hour: Int): Boolean = hour in 9..21

    fun questionDue(l: Level, askedToday: Int, lastAskedAt: Long, now: Long, hour: Int, quiet: Boolean): Boolean {
        val lim = limits(l)
        if (lim.questionsPerDay <= 0 || quiet || !questionHour(hour)) return false
        if (askedToday >= lim.questionsPerDay) return false
        return now - lastAskedAt >= lim.questionGapMin * 60_000L
    }

    fun everyDue(everyMin: Int, lastAt: Long, now: Long): Boolean = everyMin > 0 && now - lastAt >= everyMin * 60_000L

    // ---------------- offering a call ----------------

    /** Eager: every 3 days, Jarvis: every day, in the early evening only. */
    fun callDue(l: Level, lastAt: Long, now: Long, hour: Int): Boolean {
        val days = when (l) { Level.EAGER -> 3; Level.JARVIS -> 1; else -> 0 }
        return days > 0 && hour in 17..20 && now - lastAt >= days * 86_400_000L
    }

    fun callLine(openFollowUps: Int, hour: Int): String = when {
        openFollowUps > 0 -> "Got a minute? I can run through the $openFollowUps thing${if (openFollowUps == 1) "" else "s"} waiting on you, out loud."
        hour >= 19 -> "Want a quick call? I can catch you up on today and set up tomorrow."
        else -> "Got a minute for a quick call? Tell me what's on your plate and I'll sort it."
    }

    // ---------------- getting to know the user ----------------

    /** [covered]: memory words that mean Blaze already knows this ([topic] is skipped when any is in memory). */
    data class Question(val id: String, val topic: String, val text: String, val covered: List<String>)

    val bank: List<Question> = listOf(
        Question("fun", "fun", "Quick question: what do you do for fun?", listOf("hobby", "hobbies", "for fun", "likes to")),
        Question("weekend", "fun", "What does a perfect weekend look like for you?", listOf("weekend")),
        Question("music", "music", "Who have you had on repeat lately?", listOf("favourite artist", "favorite artist", "listens to", "music taste")),
        Question("film", "film", "What's a film or show you could watch again and again?", listOf("favourite film", "favorite film", "favourite movie", "favorite movie", "favourite show", "favorite show")),
        Question("food", "food", "What's your go-to meal when you can't decide?", listOf("favourite food", "favorite food", "go-to meal", "likes to eat")),
        Question("allergy", "food", "Anything you don't eat, or can't?", listOf("allergic", "allergy", "vegetarian", "vegan", "doesn't eat", "halal")),
        Question("work", "work", "What are you working on these days, work or study?", listOf("works at", "works as", "studies", "student", "job")),
        Question("goal", "goals", "What's one thing you'd love to get done this month?", listOf("goal", "wants to")),
        Question("morning", "routine", "Are you a morning person or a night owl?", listOf("morning person", "night owl", "wakes up", "sleeps at")),
        Question("sport", "sport", "Do you follow a team or play a sport?", listOf("supports", "team", "plays football", "plays ")),
        Question("people", "people", "Who are the people you talk to most? I can keep their birthdays for you.", listOf("sister", "brother", "mother", "father", "partner", "wife", "husband", "girlfriend", "boyfriend", "best friend")),
        Question("birthday", "people", "When's your birthday? I'd like to remember it.", listOf("birthday", "born on")),
        Question("learn", "learning", "Is there something you've been meaning to learn?", listOf("learning", "wants to learn")),
        Question("travel", "travel", "Where's somewhere you'd love to go next?", listOf("travel", "wants to visit", "dream trip")),
        Question("stress", "wellbeing", "What usually helps you switch off after a long day?", listOf("relax", "unwind", "switch off")),
        Question("help", "blaze", "What's one thing on your phone you wish just happened by itself?", listOf("automate", "wish blaze")),
        Question("read", "reading", "Reading anything good at the moment?", listOf("reading", "favourite book", "favorite book")),
        Question("game", "games", "Do you play any games on your phone or a console?", listOf("plays games", "gamer", "favourite game", "favorite game")),
    )

    /**
     * The next question: not asked before, its topic not already in memory, not the same topic as the last one.
     * [seed] rotates the choice (the day number), so it isn't always the bank's order. null = nothing left to ask.
     */
    fun pick(asked: Set<String>, memory: String, lastTopic: String, seed: Int): Question? {
        val mem = memory.lowercase()
        val open = bank.filter { q -> q.id !in asked && q.covered.none { mem.contains(it) } }
        if (open.isEmpty()) return null
        val fresh = open.filter { it.topic != lastTopic }.ifEmpty { open }
        return fresh[Math.floorMod(seed, fresh.size)]
    }

    // ---------------- screen glances ----------------

    /** Apps whose screens Blaze never reads on its own: banks, wallets, mobile money, passwords, authenticators. */
    private val sensitivePkg = Regex(
        "bank|wallet|pay\\b|\\.pay|paypal|venmo|cashapp|revolut|wise|momo|mpesa|m-pesa|airtelmoney|mtn|zamtel|zanaco|absa|stanbic|fnb|" +
            "password|1password|bitwarden|lastpass|keepass|dashlane|authenticator|authy|\\.otp|2fa|vault|keychain|" +
            "crypto|binance|coinbase|metamask|trustwallet|" +
            "com\\.android\\.settings|permissioncontroller|packageinstaller|systemui|incallui|dialer|" +
            "secure ?folder|knox", RegexOption.IGNORE_CASE)

    /** Words on screen that mean it's private right now (a sign-in, a code, a card), whatever the app. */
    private val sensitiveText = Regex(
        "\\b(password|passcode|pin code|enter (?:your )?pin|one[- ]time|otp|verification code|cvv|cvc|card number|security code|seed phrase|recovery phrase|private key)\\b",
        RegexOption.IGNORE_CASE)

    fun sensitive(pkg: String, screen: String): Boolean = sensitivePkg.containsMatchIn(pkg) || sensitiveText.containsMatchIn(screen)

    /** A glance is worth taking: a real app (not Blaze, not the launcher), the same one not glanced at for 20 minutes. */
    fun glanceable(pkg: String, ownPkg: String, launcher: Boolean, lastPkg: String, lastAt: Long, now: Long): Boolean {
        if (pkg.isBlank() || pkg == ownPkg || launcher) return false
        return !(pkg == lastPkg && now - lastAt < 20 * 60_000L)
    }

    // ---------------- usage ----------------

    /** "TikTok 2 h 10 min, WhatsApp 48 min" from (label, foreground ms), the top [n] over 5 minutes. */
    fun usageLine(apps: List<Pair<String, Long>>, n: Int = 5): String =
        apps.filter { it.second >= 5 * 60_000L }.sortedByDescending { it.second }.take(n).joinToString(", ") { (l, ms) -> "$l ${duration(ms)}" }

    fun duration(ms: Long): String {
        val m = ms / 60_000L
        return if (m >= 60) "${m / 60} h" + (if (m % 60 > 0) " ${m % 60} min" else "") else "$m min"
    }

    // ---------------- model replies ----------------

    /** What a glance decided: [say] (empty = stay quiet), a one-tap [suggest], and a [note] of what the user is doing. */
    data class Glance(val say: String, val suggest: String, val note: String)

    fun parseGlance(raw: String?): Glance {
        val o = obj(raw) ?: return Glance("", "", "")
        return Glance(o.optString("say").trim().take(240), o.optString("suggest").trim().take(60), o.optString("note").trim().take(140))
    }

    /** A routine Blaze wants for itself. Mon=1 … Sun=64 as in Routines; 127 = every day. */
    data class RoutineIdea(val name: String, val prompt: String, val hour: Int, val minute: Int, val days: Int, val why: String)

    fun parseRoutine(raw: String?): RoutineIdea? {
        val o = obj(raw) ?: return null
        val name = o.optString("name").trim(); val prompt = o.optString("prompt").trim()
        if (name.isBlank() || prompt.isBlank()) return null
        val h = o.optInt("hour", -1); val m = o.optInt("minute", 0); val d = o.optInt("days", 127)
        if (h !in 0..23 || m !in 0..59 || d !in 1..127) return null
        return RoutineIdea(name.take(40), prompt.take(400), h, m, d, o.optString("why").trim().take(160))
    }

    /** A question Blaze wrote itself (fits what's going on), or "" to use the bank's. */
    fun parseQuestion(raw: String?): String {
        val q = obj(raw)?.optString("question")?.trim().orEmpty()
        return if (q.length in 8..180 && q.endsWith("?")) q else ""
    }

    /** Routines Blaze made for itself carry this prefix, so the user can see (and remove) them. */
    const val SELF_PREFIX = "Blaze · "

    private fun obj(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(raw.substring(a, b + 1)) } catch (_: Exception) { null }
    }
}
