package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.30.5 (Blazeinchue: "create higher measures stopping blaze from calling unrelated skills and also keep track of often
 * performed tasks so it can give priority to creating skills for them").
 *
 *  - [relevant] / [gate]: use_skill and run_skill only load a skill that shares real words with what the user is asking
 *    (the last few user messages, so "yes, do it" still counts the request before it). The user naming the skill always
 *    passes. A weak match is refused once with the reason; the model may call again with `reason` only when the skill
 *    still shares at least one word with the request. No overlap at all is always refused.
 *  - [strongHit]: the per-turn "best matches" list only shows skills matching ≥ 2 distinct request words, or one
 *    name word (a bare description word like "open" or "app" no longer drags a skill in).
 *  - [Freq]: every real task (≥ 2 work tool calls) is counted by its fingerprint (Learn.signature). A task done
 *    [Freq.PRIORITY] times with no skill covering it is flagged: the end-of-turn learning review runs for it with
 *    skill-writing first, and the prompt carries a one-line nudge listing such tasks.
 * Pure except [Freq]'s small store in Settings (FreqLogicTest / SkillGuardTest cover the logic).
 */
object SkillGuard {

    // ---------------- relevance ----------------

    /** Distinct request words the skill's name / keywords / description share, and whether a name word is among them. */
    data class Overlap(val words: Int, val nameWord: Boolean, val named: Boolean)

    private val weakName = setOf("phone", "task", "app", "skill", "flow", "speed", "general", "workflow", "blaze", "helper", "tool", "open")

    fun overlap(request: String, c: SkillMatch.Cand): Overlap {
        val q = SkillMatch.words(request)
        val nameW = SkillMatch.words(c.name.replace('-', ' ').replace('_', ' '))
        val mine = nameW + c.keywords.flatMap { SkillMatch.words(it) } + SkillMatch.words(c.description)
        val shared = q intersect mine
        val low = request.lowercase()
        val named = c.name.length >= 3 && (low.contains(c.name.lowercase()) || low.contains(c.name.lowercase().replace('-', ' ')))
        return Overlap(shared.size, (shared intersect nameW).any { it !in weakName }, named)
    }

    enum class Verdict { ALLOW, SOFT_REFUSE, REFUSE }

    /** Strong enough to load without question: named, a real name word, or ≥ 2 distinct shared words. */
    fun relevant(request: String, c: SkillMatch.Cand): Verdict {
        if (request.isBlank()) return Verdict.ALLOW   // nothing to judge against (routines, background agents)
        val o = overlap(request, c)
        return when {
            o.named || o.nameWord || o.words >= 2 -> Verdict.ALLOW
            o.words == 1 -> Verdict.SOFT_REFUSE
            else -> Verdict.REFUSE
        }
    }

    /**
     * The gate for use_skill / run_skill [name]: null to go ahead, or the refusal the model reads.
     * [request] is the user's recent words; [reason] the model's stated reason on a second try.
     */
    fun gate(name: String, request: String, reason: String, cands: List<SkillMatch.Cand>): String? {
        if (name.isBlank()) return null
        val c = cands.firstOrNull { it.name.equals(name, true) } ?: return null   // unknown name: the tool says so itself
        return when (relevant(request, c)) {
            Verdict.ALLOW -> null
            Verdict.SOFT_REFUSE -> if (reason.trim().length >= 12) null else
                "Not loaded: skill \u201c${c.name}\u201d (${c.description.take(120)}) barely matches what the user asked. " +
                    "Don't use skills that aren't about this request: do the task with your tools. Only if it truly covers this task, " +
                    "call again with reason=\"how it applies\"."
            Verdict.REFUSE ->
                "Not loaded: skill \u201c${c.name}\u201d (${c.description.take(120)}) is unrelated to what the user asked. " +
                    "Do the task with your tools, or find_skill with words from the request."
        }
    }

    /** The per-turn suggestions: a hit counts only when it matches ≥ 2 distinct request words or one real name word. */
    fun strongHit(request: String, c: SkillMatch.Cand): Boolean = relevant(request, c) == Verdict.ALLOW && request.isNotBlank()

    // ---------------- often-performed tasks ----------------

    object Freq {
        data class Task(val sig: List<String>, val count: Int, val last: Long, val example: String, val skilled: Boolean = false)

        /** A task this many times with no skill gets priority for one. */
        const val PRIORITY = 3
        const val SAME = 0.5
        const val KEEP = 60

        /** Adds [sig] (one real task) to [tasks]: merged into the most similar one, or a new row. Pure. */
        fun record(tasks: List<Task>, sig: List<String>, example: String, now: Long): Pair<List<Task>, Task> {
            val best = tasks.withIndex().maxByOrNull { Learn.similarity(sig, it.value.sig) }
            val out = tasks.toMutableList()
            val t = if (best != null && Learn.similarity(sig, best.value.sig) >= SAME) {
                val o = best.value
                val merged = o.copy(sig = (o.sig + sig).distinct().takeLast(16), count = o.count + 1, last = now, example = example.take(160))
                out[best.index] = merged; merged
            } else Task(sig, 1, now, example.take(160)).also { out += it }
            // Keep the most-done, then the most recent.
            val kept = out.sortedWith(compareByDescending<Task> { it.count }.thenByDescending { it.last }).take(KEEP)
            return kept to t
        }

        /** Done often, no skill yet. */
        fun needsSkill(t: Task) = t.count >= PRIORITY && !t.skilled

        fun markSkilled(tasks: List<Task>, sig: List<String>): List<Task> =
            tasks.map { if (Learn.similarity(sig, it.sig) >= SAME) it.copy(skilled = true) else it }

        fun toJson(tasks: List<Task>): String = JSONArray().apply {
            tasks.forEach { put(JSONObject().put("sig", JSONArray(it.sig)).put("n", it.count).put("last", it.last).put("ex", it.example).put("skilled", it.skilled)) }
        }.toString()

        fun fromJson(s: String): List<Task> = try {
            val a = JSONArray(s)
            (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                val sa = o.optJSONArray("sig") ?: JSONArray()
                Task((0 until sa.length()).map { sa.getString(it) }, o.optInt("n", 1), o.optLong("last"), o.optString("ex"), o.optBoolean("skilled"))
            } }
        } catch (_: Exception) { emptyList() }

        /** The prompt nudge for the top tasks still without a skill ("" when none). */
        fun promptLine(tasks: List<Task>): String {
            val top = tasks.filter { needsSkill(it) }.sortedByDescending { it.count }.take(3)
            if (top.isEmpty()) return ""
            return "\nFREQUENT TASKS WITHOUT A SKILL (save one with save_workflow when you next finish one of these; reuse it after): " +
                top.joinToString("; ") { "\u201c${it.example.take(70)}\u201d ×${it.count}" }
        }

        // store
        private const val KEY = "task_freq"
        fun load(): List<Task> = try { fromJson(Settings.str(KEY, "[]")) } catch (_: Throwable) { emptyList() }
        fun save(tasks: List<Task>) { try { Settings.setStr(KEY, toJson(tasks)) } catch (_: Throwable) { } }

        /** Records a finished task; true when it now deserves a skill and none covers it. */
        @Synchronized fun note(sig: List<String>, example: String, covered: Boolean): Boolean {
            if (sig.isEmpty()) return false
            val (all, t) = record(load(), sig, example, System.currentTimeMillis())
            val fixed = if (covered) markSkilled(all, t.sig) else all
            save(fixed)
            return !covered && needsSkill(t)
        }

        @Synchronized fun skillSaved(sig: List<String>) { if (sig.isNotEmpty()) save(markSkilled(load(), sig)) }
    }
}
