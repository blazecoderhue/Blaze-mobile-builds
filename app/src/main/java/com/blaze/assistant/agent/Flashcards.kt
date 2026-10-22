package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Student pack (v0.16.0): flashcards with spaced repetition, shown in the Learn view and filled by
 * the tutor skill through the `flashcards` tool. Stored in ~/learn/cards.json (Blaze's home, so the
 * terminal and skills can read it too, and updates never touch it).
 *
 * Scheduling is SM-2 (Anki-style): Again resets the card to 10 minutes, Hard / Good / Easy grow the
 * interval by the card's ease factor (min 1.3).
 */
data class Card(val id: String, val deck: String, val front: String, val back: String,
                val due: Long = 0L, val interval: Int = 0, val ease: Double = 2.5, val reps: Int = 0, val lapses: Int = 0) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("deck", deck).put("front", front).put("back", back)
        .put("due", due).put("interval", interval).put("ease", ease).put("reps", reps).put("lapses", lapses)
    companion object {
        fun from(o: JSONObject) = Card(o.optString("id"), o.optString("deck", "Cards"), o.optString("front"), o.optString("back"),
            o.optLong("due"), o.optInt("interval"), o.optDouble("ease", 2.5).let { if (it.isNaN()) 2.5 else it }, o.optInt("reps"), o.optInt("lapses"))
    }
}

object Flashcards {
    const val AGAIN = 0; const val HARD = 1; const val GOOD = 2; const val EASY = 3
    private const val DAY = 86_400_000L

    /** The next state of [c] after an answer [grade] at [now]. Pure (unit-tested). */
    fun review(c: Card, grade: Int, now: Long = System.currentTimeMillis()): Card {
        if (grade == AGAIN) return c.copy(due = now + 10 * 60_000L, interval = 0, ease = (c.ease - 0.2).coerceAtLeast(1.3), reps = 0, lapses = c.lapses + 1)
        val ease = (c.ease + when (grade) { HARD -> -0.15; EASY -> 0.15; else -> 0.0 }).coerceAtLeast(1.3)
        val interval = when {
            c.reps == 0 -> when (grade) { HARD -> 1; EASY -> 4; else -> 1 }
            c.reps == 1 -> when (grade) { HARD -> 3; EASY -> 8; else -> 6 }
            else -> (c.interval * when (grade) { HARD -> 1.2; EASY -> ease * 1.3; else -> ease }).toInt().coerceAtLeast(c.interval + 1)
        }.coerceAtMost(3650)
        return c.copy(due = now + interval * DAY, interval = interval, ease = ease, reps = c.reps + 1)
    }

    /** "10 min", "1 d", "6 d", "3 mo" for the answer buttons. */
    fun label(c: Card, grade: Int, now: Long = System.currentTimeMillis()): String {
        val n = review(c, grade, now)
        val ms = n.due - now
        return when {
            ms < 3_600_000L -> "${ms / 60_000L} min"
            n.interval < 31 -> "${n.interval} d"
            n.interval < 365 -> "${n.interval / 30} mo"
            else -> "%.1f y".format(java.util.Locale.US, n.interval / 365.0)
        }
    }

    // ---------------- store ----------------

    @Volatile var fileOverride: File? = null
    private fun file(): File = fileOverride ?: File(com.blaze.assistant.terminal.Terminal.home(), "learn/cards.json")

    private val _cards = MutableStateFlow<List<Card>>(emptyList())
    val cards: StateFlow<List<Card>> = _cards
    @Volatile private var loaded = false

    @Synchronized fun load(): List<Card> {
        if (!loaded) {
            _cards.value = try {
                val f = file(); if (!f.isFile) emptyList() else {
                    val a = JSONObject(f.readText()).optJSONArray("cards") ?: JSONArray()
                    (0 until a.length()).map { Card.from(a.getJSONObject(it)) }.filter { it.id.isNotBlank() }
                }
            } catch (_: Exception) { emptyList() }
            loaded = true
        }
        return _cards.value
    }

    @Synchronized private fun save(list: List<Card>) {
        _cards.value = list
        try {
            val f = file(); f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("format", 1).put("cards", JSONArray(list.map { it.toJson() })).toString())
            tmp.renameTo(f)
        } catch (_: Exception) { }
    }

    fun reset() { loaded = false; _cards.value = emptyList() }

    data class Deck(val name: String, val total: Int, val due: Int, val fresh: Int)

    fun decks(now: Long = System.currentTimeMillis()): List<Deck> = load().groupBy { it.deck }.map { (n, l) ->
        Deck(n, l.size, l.count { it.reps > 0 && it.due <= now }, l.count { it.reps == 0 })
    }.sortedWith(compareByDescending<Deck> { it.due + it.fresh }.thenBy { it.name })

    /** Cards to study now in [deck]: due reviews first, then up to [newLimit] new ones. */
    fun queue(deck: String, now: Long = System.currentTimeMillis(), newLimit: Int = 20): List<Card> {
        val l = load().filter { it.deck == deck }
        return l.filter { it.reps > 0 && it.due <= now }.sortedBy { it.due } + l.filter { it.reps == 0 }.take(newLimit)
    }

    fun add(deck: String, pairs: List<Pair<String, String>>): Int {
        val d = deck.trim().ifBlank { "Cards" }.take(60)
        val have = load().filter { it.deck == d }.map { it.front.trim().lowercase() }.toMutableSet()
        val new = pairs.filter { it.first.isNotBlank() && it.second.isNotBlank() && have.add(it.first.trim().lowercase()) }
            .map { (f, b) -> Card("c" + java.util.UUID.randomUUID().toString().replace("-", "").take(12), d, f.trim().take(600), b.trim().take(1500)) }
        if (new.isNotEmpty()) save(load() + new)
        return new.size
    }

    fun answer(id: String, grade: Int) { save(load().map { if (it.id == id) review(it, grade) else it }) }
    fun deleteDeck(deck: String) = save(load().filter { it.deck != deck })
    fun deleteCard(id: String) = save(load().filter { it.id != id })

    // ---------------- tool ----------------

    const val TOOL = "flashcards"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "The user's flashcards in the Learn view (spaced repetition). add: create a Learn deck or add cards to one (deck name + cards [{front, back}]) whenever the user wants flashcards, a quiz or something to study, then open_view view=learn; never write the cards in chat instead. " +
            "list: decks with due counts; due: cards due now in a deck (to quiz the user in chat); delete_deck.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("add", "list", "due", "delete_deck"))))
            .put("deck", JSONObject().put("type", "string").put("description", "Deck name, e.g. 'CIT102 loops'"))
            .put("cards", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("front", JSONObject().put("type", "string")).put("back", JSONObject().put("type", "string"))))))
            .put("required", JSONArray(listOf("action")))))

    fun run(a: JSONObject): String = when (a.optString("action")) {
        "add" -> {
            val arr = a.optJSONArray("cards") ?: JSONArray()
            val pairs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { it.optString("front") to it.optString("back") }
            val n = add(a.optString("deck"), pairs)
            "Added $n card${if (n == 1) "" else "s"} to \u201c${a.optString("deck").ifBlank { "Cards" }}\u201d" +
                (if (n < pairs.size) " (${pairs.size - n} skipped: empty or already there)" else "") + ". The user reviews them in the Learn view (/learn)."
        }
        "due" -> {
            val q = queue(a.optString("deck"))
            if (q.isEmpty()) "Nothing due in \u201c${a.optString("deck")}\u201d." else q.take(20).joinToString("\n") { "- ${it.front} → ${it.back}" }
        }
        "delete_deck" -> { deleteDeck(a.optString("deck")); "Deleted the deck \u201c${a.optString("deck")}\u201d." }
        else -> decks().let { d -> if (d.isEmpty()) "No flashcards yet." else d.joinToString("\n") { "${it.name}: ${it.total} cards, ${it.due} due, ${it.fresh} new" } }
    }
}
