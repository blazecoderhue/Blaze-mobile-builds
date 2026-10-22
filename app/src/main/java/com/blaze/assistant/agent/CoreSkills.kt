package com.blaze.assistant.agent

/**
 * Core skills (v0.24.3). Six bundled skills that shape every turn: how Blaze talks (blaze-voice), how it works
 * (blaze-task-workflow), how it codes (blaze-coding-workflow), where output goes (blaze-views), how it uses specialists (blaze-specialists) and how it keeps
 * reusable workflows (blaze-workflow-builder).
 *
 * They are always on: they can't be switched off or deleted in Skills (they're shown there, marked "core"), the
 * library re-seeds them when missing, and their "## Pinned" bullets go into the static part of the system prompt
 * every turn within [BUDGET_CHARS] (≈ 1,600 tokens). The full SKILL.md is still matched per turn and readable with
 * use_skill. The pinned text is read from the shipped assets, so a broken edit in ~/skills can't remove the rules.
 * [pinned] and [block] are pure (CoreSkillsTest).
 */
object CoreSkills {
    /** In prompt order. */
    val NAMES = listOf("blaze-voice", "blaze-task-workflow", "blaze-views", "blaze-specialists", "blaze-workflow-builder", "blaze-coding-workflow")

    /** ≈ 1,500 tokens for all pinned rules together (the six skills fit in about 5,700 chars). */
    const val BUDGET_CHARS = 6_400

    fun isCore(name: String) = name.trim().lowercase() in NAMES

    /** The bullets under "## Pinned" in a SKILL.md (front matter and other sections ignored). */
    fun pinned(md: String): List<String> {
        val body = SkillMatch.body(md)
        val lines = body.lines()
        val start = lines.indexOfFirst { it.trim().equals("## Pinned", true) }
        if (start < 0) return emptyList()
        val out = ArrayList<String>()
        for (l in lines.drop(start + 1)) {
            val t = l.trim()
            if (t.startsWith("#")) break
            if (t.startsWith("- ")) out += t.removePrefix("- ").trim()
            else if (t.isNotEmpty() && out.isNotEmpty()) out[out.size - 1] = out.last() + " " + t
        }
        return out
    }

    /**
     * The always-on block for the system prompt. [mds] = core skill name → SKILL.md text. Skills keep [NAMES]
     * order; when the budget runs out, the remaining bullets are dropped (each skill keeps at least its first bullet
     * when it fits) and a pointer to use_skill is added.
     */
    fun block(mds: Map<String, String>, budget: Int = BUDGET_CHARS, names: List<String> = NAMES): String {
        val per = names.mapNotNull { n -> mds[n]?.let { n to pinned(it) } }.filter { it.second.isNotEmpty() }
        if (per.isEmpty()) return ""
        val head = "\n\nCORE SKILLS (always on, every turn; follow them — full text with use_skill <name>):\n"
        val sb = StringBuilder(head)
        var left = budget - head.length
        var cut = false
        // First pass: each skill's heading + first bullet, so every core skill is represented.
        val firsts = per.map { (n, b) -> "[$n] " to b }
        val taken = IntArray(per.size)
        val parts = Array(per.size) { StringBuilder() }
        for ((i, p) in firsts.withIndex()) {
            val line = p.first + "\n- " + p.second[0] + "\n"
            if (line.length <= left) { parts[i].append(line); left -= line.length; taken[i] = 1 } else cut = true
        }
        // Second pass: the rest, skill by skill in order.
        for ((i, p) in firsts.withIndex()) {
            if (taken[i] == 0) continue
            for (j in 1 until p.second.size) {
                val line = "- " + p.second[j] + "\n"
                if (line.length <= left) { parts[i].append(line); left -= line.length } else { cut = true; break }
            }
        }
        parts.forEach { sb.append(it) }
        if (cut) sb.append("(Some rules trimmed for length; read the full skill with use_skill.)\n")
        return sb.toString().trimEnd('\n')
    }

    /** Loaded once from assets by the agent; tests set it directly. */
    @Volatile var loader: (() -> Map<String, String>)? = null
    @Volatile private var cached: String? = null

    /** The pinned block for this turn ("" if the assets can't be read). Static, so it stays in the cached prefix. */
    fun promptBlock(): String = cached ?: try {
        block(loader?.invoke().orEmpty()).also { if (it.isNotEmpty()) cached = it }
    } catch (_: Throwable) { "" }

    private val byNames = java.util.concurrent.ConcurrentHashMap<List<String>, String>()

    /** v0.45.0 (#13, #16): the pinned block for a subset of the core skills (PromptProfile), cached per subset. */
    fun promptBlock(names: List<String>): String = if (names == NAMES) promptBlock() else byNames[names] ?: try {
        block(loader?.invoke().orEmpty(), names = names).also { if (it.isNotEmpty()) byNames[names] = it }
    } catch (_: Throwable) { "" }

    fun reset() { cached = null; byNames.clear() }
}
